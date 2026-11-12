package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/reveal/sync-server/internal/auth"
	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
)

const (
	photoA   = "11111111-1111-4111-8111-111111111111"
	photoB   = "33333333-3333-4333-8333-333333333333"
	versionA = "22222222-2222-4222-8222-222222222222"
)

func TestDeviceAuth(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Phone")
	expect(t, hs.do("GET", "/v1/server", nil), 401, "unauthorized")
	expect(t, hs.do("GET", "/v1/server", nil, header("Authorization", "Basic "+key)), 401, "unauthorized")
	expect(t, hs.do("GET", "/v1/server", nil, bearer("nonsense")), 401, "unauthorized")
	other, _, _ := auth.NewKey()
	expect(t, hs.do("GET", "/v1/server", nil, bearer(other)), 401, "unauthorized")
	// Right key id, wrong secret.
	forged := key[:len("rvk1_")+16+1] + strings.Repeat("A", 43)
	expect(t, hs.do("GET", "/v1/server", nil, bearer(forged)), 401, "unauthorized")
	w := hs.do("GET", "/v1/server", nil, bearer(key))
	expect(t, w, 200, "")
	if m := decode(t, w); m["storageConfigured"] != true || m["api"].(float64) != 1 {
		t.Fatalf("server info %v", m)
	}
}

func TestRevokedKeyIsRejected(t *testing.T) {
	hs := newHarness(t, true)
	key, id := hs.pair("Tablet")
	expect(t, hs.do("GET", "/v1/changes", nil, bearer(key)), 200, "")
	expect(t, hs.do("POST", "/admin/api/devices/"+id+"/revoke", nil, adminHeaders()), 200, "")
	expect(t, hs.do("GET", "/v1/changes", nil, bearer(key)), 401, "unauthorized")
	expect(t, hs.do("POST", "/admin/api/devices/"+id+"/revoke", nil, adminHeaders()), 404, "not_found")
}

func TestPairingTokenIsOneTimeAndExpires(t *testing.T) {
	hs := newHarness(t, true)
	hs.signIn()
	w := hs.do("POST", "/admin/api/pairings", map[string]any{"url": "http://192.168.1.10:3071"}, adminHeaders())
	expect(t, w, 201, "")
	m := decode(t, w)
	uri, _ := url.Parse(m["uri"].(string))
	if uri.Scheme != "revealsync" || uri.Query().Get("url") != "http://192.168.1.10:3071" || uri.Query().Get("token") != m["token"] ||
		!strings.HasPrefix(m["qr"].(string), "data:image/png;base64,") {
		t.Fatalf("pairing %v", m)
	}
	token := m["token"].(string)
	body := map[string]any{"token": token, "deviceName": "Phone", "platform": "android"}
	cookie := hs.cookie
	hs.cookie = nil
	expect(t, hs.do("POST", "/v1/pair", body), 201, "")
	expect(t, hs.do("POST", "/v1/pair", body), 401, "invalid_pairing_token")
	expect(t, hs.do("POST", "/v1/pair", map[string]any{"token": "x", "deviceName": "P"}), 401, "invalid_pairing_token")
	expect(t, hs.do("POST", "/v1/pair", map[string]any{"token": token, "deviceName": ""}), 400, "invalid_request")
	expect(t, hs.do("POST", "/v1/pair", map[string]any{"token": token, "deviceName": "bad\x00name"}), 400, "invalid_request")
	hs.cookie = cookie

	w = hs.do("POST", "/admin/api/pairings", map[string]any{"url": "http://192.168.1.10:3071"}, adminHeaders())
	token = decode(t, w)["token"].(string)
	hs.st.Now = func() time.Time { return time.Now().Add(11 * time.Minute) }
	hs.cookie = nil
	expect(t, hs.do("POST", "/v1/pair", map[string]any{"token": token, "deviceName": "Late", "platform": "android"}, remote("203.0.113.10:1")), 401, "invalid_pairing_token")
}

func TestPairIsRateLimited(t *testing.T) {
	hs := newHarness(t, true)
	var last int
	for range 12 {
		last = hs.do("POST", "/v1/pair", map[string]any{"token": "guess", "deviceName": "x"}).Code
	}
	if last != http.StatusTooManyRequests {
		t.Fatalf("want 429 after 10 attempts, got %d", last)
	}
	w := hs.do("POST", "/v1/pair", map[string]any{"token": "guess", "deviceName": "x"})
	if w.Header().Get("Retry-After") == "" || errCode(t, w) != "rate_limited" {
		t.Fatal("429 needs Retry-After and rate_limited")
	}
}

func change(kind, id string, base, modified int64, data any) map[string]any {
	c := map[string]any{"kind": kind, "id": id, "baseRev": base, "modifiedAt": modified}
	if data == nil {
		c["deleted"] = true
	} else {
		c["data"] = data
	}
	return c
}

func push(t *testing.T, hs *harness, key string, changes ...map[string]any) []map[string]any {
	t.Helper()
	w := hs.do("POST", "/v1/changes", map[string]any{"changes": changes}, bearer(key))
	expect(t, w, 200, "")
	var out struct {
		Results []map[string]any `json:"results"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return out.Results
}

type pulled struct {
	Changes []struct {
		Kind       string          `json:"kind"`
		ID         string          `json:"id"`
		Rev        int64           `json:"rev"`
		Deleted    bool            `json:"deleted"`
		DeviceID   string          `json:"deviceId"`
		CopyOf     *string         `json:"copyOf"`
		ModifiedAt int64           `json:"modifiedAt"`
		Data       json.RawMessage `json:"data"`
	} `json:"changes"`
	Cursor  string `json:"cursor"`
	HasMore bool   `json:"hasMore"`
}

func pull(t *testing.T, hs *harness, key, cursor string, limit int) pulled {
	t.Helper()
	q := "/v1/changes?cursor=" + url.QueryEscape(cursor)
	if limit > 0 {
		q += fmt.Sprintf("&limit=%d", limit)
	}
	w := hs.do("GET", q, nil, bearer(key))
	expect(t, w, 200, "")
	var p pulled
	if err := json.Unmarshal(w.Body.Bytes(), &p); err != nil {
		t.Fatal(err)
	}
	return p
}

func version(recipe float64) map[string]any {
	return map[string]any{"photoId": photoA, "position": 0, "recipe": map[string]any{"v": 1, "exposure": recipe}, "history": []any{}, "cursor": -1}
}

func photo() map[string]any {
	return map[string]any{"fingerprint": fingerprint.Bytes([]byte("raw")), "rating": 3}
}

func TestFeedCursorsAndTombstones(t *testing.T) {
	hs := newHarness(t, true)
	phone, _ := hs.pair("Phone")
	tablet, _ := hs.pair("Tablet")
	r := push(t, hs, phone, change("photo", photoA, 0, 1000, photo()), change("version", versionA, 0, 1000, version(0.5)),
		change("setting", "export.author", 0, 1000, map[string]any{"value": "Me"}))
	if r[0]["status"] != "applied" || r[1]["rev"].(float64) != 2 {
		t.Fatalf("push %v", r)
	}
	// Paging: 2 then 1.
	p1 := pull(t, hs, tablet, "", 2)
	if len(p1.Changes) != 2 || !p1.HasMore {
		t.Fatalf("page 1 %+v", p1)
	}
	p2 := pull(t, hs, tablet, p1.Cursor, 2)
	if len(p2.Changes) != 1 || p2.HasMore || p2.Changes[0].Kind != "setting" {
		t.Fatalf("page 2 %+v", p2)
	}
	if again := pull(t, hs, tablet, p2.Cursor, 0); len(again.Changes) != 0 || again.Cursor != p2.Cursor {
		t.Fatalf("nothing new %+v", again)
	}
	// A record changed twice since the cursor appears once, at its latest state; a delete is a tombstone.
	push(t, hs, phone, change("version", versionA, 2, 2000, version(0.7)))
	push(t, hs, phone, change("version", versionA, 4, 3000, nil))
	p3 := pull(t, hs, tablet, p2.Cursor, 0)
	if len(p3.Changes) != 1 || !p3.Changes[0].Deleted || string(p3.Changes[0].Data) != "null" || p3.Changes[0].Rev != 5 {
		t.Fatalf("tombstone %+v", p3)
	}
	// Tombstones stay for a device that starts from scratch.
	all := pull(t, hs, tablet, "", 0)
	found := false
	for _, c := range all.Changes {
		if c.ID == versionA && c.Deleted {
			found = true
		}
	}
	if !found {
		t.Fatal("tombstone missing from a full pull")
	}
	// Revive with the tombstone's rev.
	if r := push(t, hs, tablet, change("version", versionA, 5, 4000, version(1))); r[0]["status"] != "applied" {
		t.Fatalf("revive %v", r)
	}
	// Bad cursors.
	for _, c := range []string{"garbage", "00000000:1", strings.Split(p3.Cursor, ":")[0] + ":999", strings.Split(p3.Cursor, ":")[0] + ":-1", strings.Split(p3.Cursor, ":")[0] + ":01"} {
		expect(t, hs.do("GET", "/v1/changes?cursor="+url.QueryEscape(c), nil, bearer(tablet)), 400, "invalid_cursor")
	}
	expect(t, hs.do("GET", "/v1/changes?limit=0", nil, bearer(tablet)), 400, "invalid_request")
	// Health shows counts only.
	w := hs.do("GET", "/v1/health", nil)
	expect(t, w, 200, "")
	if strings.Contains(w.Body.String(), "Phone") || strings.Contains(w.Body.String(), "fingerprint") {
		t.Fatal("health must show counts only")
	}
	if c := decode(t, w)["counts"].(map[string]any); c["photos"].(float64) != 1 || c["versions"].(float64) != 1 || c["devices"].(float64) != 2 {
		t.Fatalf("counts %v", c)
	}
}

func TestConflicts(t *testing.T) {
	hs := newHarness(t, true)
	phone, phoneID := hs.pair("Phone")
	tablet, tabletID := hs.pair("Tablet")
	push(t, hs, phone, change("version", versionA, 0, 1000, version(0.1))) // rev 1

	// Both edit rev 1. The phone wins (applied normally), the tablet's later write conflicts and wins: the phone's
	// state becomes a copy.
	push(t, hs, phone, change("version", versionA, 1, 2000, version(0.2))) // rev 2
	r := push(t, hs, tablet, change("version", versionA, 1, 3000, version(0.3)))[0]
	if r["status"] != "conflict" || r["winner"] != "incoming" || r["copyId"] == nil {
		t.Fatalf("incoming wins %v", r)
	}
	copy1 := r["copyId"].(string)
	all := pull(t, hs, phone, "", 0)
	byID := map[string]int{}
	for i, c := range all.Changes {
		byID[c.ID] = i
	}
	main := all.Changes[byID[versionA]]
	cp := all.Changes[byID[copy1]]
	if !bytes.Contains(main.Data, []byte(`"exposure":0.3`)) || main.DeviceID != tabletID {
		t.Fatalf("winner %+v", main)
	}
	if cp.CopyOf == nil || *cp.CopyOf != versionA || !bytes.Contains(cp.Data, []byte(`"exposure":0.2`)) || cp.DeviceID != phoneID || cp.ModifiedAt != 2000 {
		t.Fatalf("copy of the loser %+v", cp)
	}
	head := main.Rev

	// An older write that conflicts loses: kept as a copy, the main version unchanged.
	r = push(t, hs, phone, change("version", versionA, 2, 2500, version(0.25)))[0]
	if r["status"] != "conflict" || r["winner"] != "server" || r["copyId"] == nil || int64(r["rev"].(float64)) != head {
		t.Fatalf("incoming loses %v", r)
	}
	// Retrying the same losing push reuses its copy.
	r2 := push(t, hs, phone, change("version", versionA, 2, 2500, version(0.25)))[0]
	if r2["copyId"] != r["copyId"] {
		t.Fatalf("retry made another copy: %v vs %v", r2, r)
	}
	// Equal times: the greater device id wins, the same on every server.
	r = push(t, hs, phone, change("version", versionA, 1, 3000, version(0.31)))[0]
	if (phoneID > tabletID) != (r["winner"] == "incoming") {
		t.Fatalf("tie break %v (phone %s tablet %s)", r, phoneID, tabletID)
	}
	headNow := pull(t, hs, phone, "", 0)
	var cur int64
	for _, c := range headNow.Changes {
		if c.ID == versionA {
			cur = c.Rev
		}
	}
	// A losing delete is dropped (nothing to keep); a winning delete keeps the edit as a copy.
	r = push(t, hs, tablet, change("version", versionA, 1, 100, nil))[0]
	if r["winner"] != "server" || r["copyId"] != nil {
		t.Fatalf("losing delete %v", r)
	}
	r = push(t, hs, tablet, change("version", versionA, 1, 9000, nil))[0]
	if r["winner"] != "incoming" || r["copyId"] == nil || int64(r["rev"].(float64)) <= cur {
		t.Fatalf("winning delete %v", r)
	}
	// An identical retry of an applied write is applied without a new revision.
	r = push(t, hs, tablet, change("version", versionA, 1, 9000, nil))[0]
	r3 := push(t, hs, tablet, change("version", versionA, 1, 9000, nil))[0]
	if r["status"] != "applied" || r3["rev"] != r["rev"] {
		t.Fatalf("idempotent retry %v %v", r, r3)
	}
	// Photos: last write wins, no copy.
	push(t, hs, phone, change("photo", photoA, 0, 1000, photo()))
	push(t, hs, phone, change("photo", photoA, 1, 1000, photo()))
	r = push(t, hs, tablet, change("photo", photoA, 0, 500, map[string]any{"fingerprint": fingerprint.Bytes([]byte("raw")), "rating": 1}))[0]
	if r["status"] != "conflict" || r["winner"] != "server" || r["copyId"] != nil {
		t.Fatalf("photo LWW %v", r)
	}
}

func TestPushValidation(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Phone")
	bad := []map[string]any{
		change("album", photoA, 0, 1, map[string]any{}),
		change("photo", "not-a-uuid", 0, 1, photo()),
		change("photo", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", 0, 1, photo()),
		change("photo", "../../etc", 0, 1, photo()),
		change("photo", photoA, 0, 1, map[string]any{"fingerprint": "v1:abc"}),
		change("version", versionA, 0, 1, map[string]any{"photoId": "x"}),
		change("setting", "../x", 0, 1, map[string]any{}),
		change("setting", "Upper", 0, 1, map[string]any{}),
		change("photo", photoA, -1, 1, photo()),
		change("photo", photoA, 0, 0, photo()),
		{"kind": "photo", "id": photoA, "modifiedAt": 1, "data": photo()},
		{"kind": "photo", "id": photoA, "baseRev": 0, "modifiedAt": 1, "data": []int{1}},
		{"kind": "photo", "id": photoA, "baseRev": 0, "modifiedAt": 1},
		change("version", versionA, 0, 1, map[string]any{"photoId": photoA, "blob": strings.Repeat("x", maxRecordData)}),
	}
	for i, c := range bad {
		w := hs.do("POST", "/v1/changes", map[string]any{"changes": []any{change("setting", "ok", 0, 1, map[string]any{}), c}}, bearer(key))
		if w.Code != 422 {
			t.Fatalf("case %d: %d %s", i, w.Code, w.Body.String())
		}
		expect(t, w, 422, "invalid_change")
		if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["index"].(float64) != 1 {
			t.Fatalf("case %d: index", i)
		}
	}
	// Nothing was applied: the batch is all or nothing.
	if p := pull(t, hs, key, "", 0); len(p.Changes) != 0 {
		t.Fatalf("a rejected batch applied %d changes", len(p.Changes))
	}
	many := make([]any, maxPushChanges+1)
	for i := range many {
		many[i] = change("setting", fmt.Sprintf("k%d", i), 0, 1, map[string]any{})
	}
	expect(t, hs.do("POST", "/v1/changes", map[string]any{"changes": many}, bearer(key)), 422, "invalid_change")
	expect(t, hs.do("POST", "/v1/changes", "{", bearer(key), header("Content-Type", "application/json")), 400, "invalid_json")
	expect(t, hs.do("POST", "/v1/changes", "{}", bearer(key), header("Content-Type", "text/plain")), 415, "unsupported_media_type")
	big := bytes.Repeat([]byte(" "), maxJSONBody+1)
	expect(t, hs.do("POST", "/v1/changes", big, bearer(key)), 413, "too_large")
	expect(t, hs.do("PUT", "/v1/changes", nil, bearer(key)), 405, "method_not_allowed")
	expect(t, hs.do("GET", "/v1/nothing", nil, bearer(key)), 404, "not_found")
}

func TestChunkedUploadResumeAndDownload(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Phone")
	other, _ := hs.pair("Tablet")
	data := fingerprint.VectorBytes(chunkSize+3*fingerprint.Edge+17, 4)
	sha, fp := sum(data), fingerprint.Bytes(data)

	expect(t, hs.do("POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(data), "kind": "original"}, bearer(key)), 422, "invalid_request")
	w := hs.do("POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(data), "kind": "original", "fingerprint": fp}, bearer(key))
	expect(t, w, 201, "")
	up := decode(t, w)
	id := up["uploadId"].(string)
	if up["chunkSize"].(float64) != chunkSize {
		t.Fatal(up)
	}
	patch := func(offset int, body io.Reader, k string) *httptest.ResponseRecorder {
		return hs.do("PATCH", "/v1/uploads/"+id, body, bearer(k),
			header("Upload-Offset", fmt.Sprint(offset)), header("Content-Type", "application/offset+octet-stream"))
	}
	// Another device cannot see or write this upload.
	expect(t, patch(0, bytes.NewReader(data[:10]), other), 404, "not_found")
	expect(t, hs.do("PATCH", "/v1/uploads/"+id, data[:10], bearer(key), header("Upload-Offset", "0")), 415, "unsupported_media_type")
	// First chunk is interrupted after 1 MB: the server keeps those bytes.
	c := patch(0, io.MultiReader(bytes.NewReader(data[:1<<20]), errReader{}), key)
	expect(t, c, 400, "invalid_request")
	h := hs.do("HEAD", "/v1/uploads/"+id, nil, bearer(key))
	if h.Code != 204 || h.Header().Get("Upload-Offset") != fmt.Sprint(1<<20) || h.Header().Get("Upload-Length") != fmt.Sprint(len(data)) {
		t.Fatalf("HEAD %d %v", h.Code, h.Header())
	}
	// A wrong offset is refused with the server's offset.
	w = patch(0, bytes.NewReader(data[:10]), key)
	expect(t, w, 409, "offset_mismatch")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["offset"].(float64) != 1<<20 {
		t.Fatal("offset_mismatch details")
	}
	// A client that lost its upload id finds it again.
	w = hs.do("POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(data), "kind": "original", "fingerprint": fp}, bearer(key))
	expect(t, w, 200, "")
	if m := decode(t, w); m["status"] != "resume" || m["uploadId"] != id || m["offset"].(float64) != 1<<20 {
		t.Fatalf("resume %v", m)
	}
	// Too big a chunk.
	expect(t, hs.do("PATCH", "/v1/uploads/"+id, data[1<<20:1<<20+chunkSize+1], bearer(key), header("Upload-Offset", fmt.Sprint(1<<20)),
		header("Content-Type", "application/offset+octet-stream")), 413, "too_large")
	// Resume in chunks.
	off := 1 << 20
	for off < len(data) {
		end := min(off+chunkSize, len(data))
		w = patch(off, bytes.NewReader(data[off:end]), key)
		if w.Code != 204 {
			t.Fatalf("chunk at %d: %d %s", off, w.Code, w.Body.String())
		}
		off = end
	}
	if w.Header().Get("Upload-Complete") != "1" {
		t.Fatal("last chunk must complete the upload")
	}
	expect(t, hs.do("HEAD", "/v1/uploads/"+id, nil, bearer(key)), 404, "")
	// The blob, by hash and by fingerprint, with ranges.
	w = hs.do("GET", "/v1/blobs/"+sha, nil, bearer(other), header("Range", "bytes=100-199"))
	if w.Code != 206 || !bytes.Equal(w.Body.Bytes(), data[100:200]) || w.Header().Get("ETag") != `"`+sha+`"` {
		t.Fatalf("range %d", w.Code)
	}
	w = hs.do("GET", "/v1/originals/"+fp, nil, bearer(other))
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), data) {
		t.Fatalf("original %d", w.Code)
	}
	w = hs.do("POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{fp, fingerprint.Bytes([]byte("nope"))}}, bearer(other))
	expect(t, w, 200, "")
	lk := decode(t, w)
	if len(lk["found"].([]any)) != 1 || lk["found"].([]any)[0].(map[string]any)["source"] != "backup" || len(lk["missing"].([]any)) != 1 {
		t.Fatalf("lookup %v", lk)
	}
	// Uploading it again: exists.
	w = hs.do("POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(data), "kind": "original", "fingerprint": fp}, bearer(other))
	expect(t, w, 200, "")
	if decode(t, w)["status"] != "exists" {
		t.Fatal("exists")
	}
	// The same bytes declared with another fingerprint are refused.
	w = hs.do("POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(data), "kind": "original", "fingerprint": fingerprint.Bytes([]byte("x"))}, bearer(other))
	expect(t, w, 422, "checksum_mismatch")
	blob := filepath.Join(hs.backup, "reveal-sync", "blobs", sha[:2], sha[2:4], sha)
	if fi, err := os.Stat(blob); err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("blob file %v %v", fi, err)
	}
}

type errReader struct{}

func (errReader) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

func TestUploadChecksumMismatchRestarts(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Phone")
	data := []byte("preview bytes")
	w := hs.do("POST", "/v1/uploads", map[string]any{"sha256": sum([]byte("something else")), "size": len(data), "kind": "preview"}, bearer(key))
	expect(t, w, 201, "")
	id := decode(t, w)["uploadId"].(string)
	w = hs.do("PATCH", "/v1/uploads/"+id, data, bearer(key), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream"))
	expect(t, w, 422, "checksum_mismatch")
	if h := hs.do("HEAD", "/v1/uploads/"+id, nil, bearer(key)); h.Header().Get("Upload-Offset") != "0" {
		t.Fatal("restart at 0")
	}
	expect(t, hs.do("DELETE", "/v1/uploads/"+id, nil, bearer(key)), 204, "")
	expect(t, hs.do("HEAD", "/v1/uploads/"+id, nil, bearer(key)), 404, "")
}

func TestUploadsNeedStorage(t *testing.T) {
	hs := newHarness(t, false)
	key, _ := hs.pair("Phone")
	expect(t, hs.do("POST", "/v1/uploads", map[string]any{"sha256": sum([]byte("x")), "size": 1, "kind": "preview"}, bearer(key)), 503, "storage_not_configured")
}

func TestPathTraversalOnEveryPathInput(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Phone")
	secret := filepath.Join(filepath.Dir(hs.mnt), "secret")
	if err := os.MkdirAll(secret, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(secret, "passwd"), []byte("root"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(secret, filepath.Join(hs.mnt, "escape")); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{
		"/v1/blobs/..%2F..%2F..%2Fsecret%2Fpasswd", "/v1/blobs/" + strings.Repeat("a", 63) + "%2F", "/v1/originals/v1:..%2F..%2Fpasswd",
		"/v1/originals/..%2Fsecret", "/v1/blobs/%2e%2e", "/v1/uploads/..%2F..%2Fsecret",
	} {
		w := hs.do("GET", p, nil, bearer(key))
		if w.Code == 200 || strings.Contains(w.Body.String(), "root") {
			t.Errorf("%s: %d %s", p, w.Code, w.Body.String())
		}
	}
	for _, m := range []string{"HEAD", "PATCH", "DELETE"} {
		w := hs.do(m, "/v1/uploads/..%2F..%2F..%2Fsecret%2Fpasswd", []byte("x"), bearer(key), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream"))
		if w.Code != 404 {
			t.Errorf("%s upload traversal: %d", m, w.Code)
		}
	}
	if b, _ := os.ReadFile(filepath.Join(secret, "passwd")); string(b) != "root" {
		t.Fatal("a file outside was touched")
	}
	for _, p := range []string{secret, filepath.Join(hs.mnt, ".."), filepath.Join(hs.mnt, "..", "secret"), filepath.Join(hs.mnt, "escape"),
		"backup", "../secret", "/", "/etc", filepath.Join(hs.mnt, "nas") + "/../../secret", filepath.Join(hs.mnt, "nas\x00")} {
		expect(t, hs.do("POST", "/admin/api/nas", map[string]any{"path": p}, adminHeaders()), 422, "invalid_request")
		expect(t, hs.do("PUT", "/admin/api/storage", map[string]any{"path": p}, adminHeaders()), 422, "invalid_request")
	}
	expect(t, hs.do("DELETE", "/admin/api/nas/..%2F..", nil, adminHeaders()), 404, "")
	expect(t, hs.do("POST", "/admin/api/devices/..%2F..%2Fx/revoke", nil, adminHeaders()), 404, "")
	expect(t, hs.do("GET", "/admin/api/pairings/..%2Fx", nil, adminHeaders()), 404, "")
	expect(t, hs.do("GET", "/admin/assets/..%2F..%2Fadmin.go", nil, adminHeaders()), 404, "")
}

func TestNasFilesAreFoundAndServed(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Tablet")
	raw := fingerprint.VectorBytes(3*fingerprint.Edge+5, 11)
	dir := filepath.Join(hs.nas, "2026-11 shoot")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "DSC09999.ARW"), raw, 0o600); err != nil {
		t.Fatal(err)
	}
	w := hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders())
	expect(t, w, 201, "")
	folderID := decode(t, w)["id"].(string)
	f, _ := hs.st.NasFolder(t.Context(), folderID)
	if err := hs.srv.indexer.Folder(t.Context(), f); err != nil {
		t.Fatal(err)
	}
	fp := fingerprint.Bytes(raw)
	w = hs.do("POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{fp}}, bearer(key))
	if found := decode(t, w)["found"].([]any); len(found) != 1 || found[0].(map[string]any)["source"] != "nas" {
		t.Fatalf("lookup %s", w.Body.String())
	}
	w = hs.do("GET", "/v1/originals/"+fp, nil, bearer(key), header("Range", "bytes=-10"))
	if w.Code != 206 || !bytes.Equal(w.Body.Bytes(), raw[len(raw)-10:]) {
		t.Fatalf("NAS range %d", w.Code)
	}
	w = hs.do("GET", "/v1/nas/files", nil, bearer(key))
	expect(t, w, 200, "")
	var feed struct {
		Files  []map[string]any `json:"files"`
		Cursor string           `json:"cursor"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &feed)
	if len(feed.Files) != 1 || feed.Files[0]["path"] != "2026-11 shoot/DSC09999.ARW" || feed.Files[0]["fingerprint"] != fp {
		t.Fatalf("nas feed %v", feed)
	}
	expect(t, hs.do("GET", "/v1/changes?cursor="+url.QueryEscape(feed.Cursor), nil, bearer(key)), 400, "invalid_cursor")
	// A file changed after indexing is not served stale.
	if err := os.WriteFile(filepath.Join(dir, "DSC09999.ARW"), raw[:100], 0o600); err != nil {
		t.Fatal(err)
	}
	expect(t, hs.do("GET", "/v1/originals/"+fp, nil, bearer(key)), 503, "unavailable")
	// Removing the folder: removed entries in the feed, no longer found.
	expect(t, hs.do("DELETE", "/admin/api/nas/"+folderID, nil, adminHeaders()), 200, "")
	w = hs.do("GET", "/v1/nas/files?cursor="+url.QueryEscape(feed.Cursor), nil, bearer(key))
	if !strings.Contains(w.Body.String(), `"removed":true`) {
		t.Fatalf("removal %s", w.Body.String())
	}
	w = hs.do("POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{fp}}, bearer(key))
	if len(decode(t, w)["missing"].([]any)) != 1 {
		t.Fatal("removed folder must not be found")
	}
}

func TestLogsHoldNoSecretsOrNames(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Secret Phone Name")
	raw := []byte("tiny jpeg")
	if err := os.WriteFile(filepath.Join(hs.nas, "Family Holiday 001.jpg"), raw, 0o600); err != nil {
		t.Fatal(err)
	}
	w := hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders())
	f, _ := hs.st.NasFolder(t.Context(), decode(t, w)["id"].(string))
	_ = hs.srv.indexer.Folder(t.Context(), f)
	hs.do("GET", "/v1/originals/"+fingerprint.Bytes(raw), nil, bearer(key))
	hs.do("GET", "/v1/server", nil, bearer(key+"x"))
	hs.do("POST", "/admin/api/login", map[string]any{"password": "wrong password typed"}, adminHeaders())
	logs := hs.logs.String()
	_, secretPart, _ := strings.Cut(key[5:], "_")
	for _, s := range []string{key, secretPart, "Family Holiday", "Secret Phone Name", "a long admin password", "wrong password typed", hs.cookie.Value} {
		if strings.Contains(logs, s) {
			t.Errorf("logs contain %q", s)
		}
	}
	if !strings.Contains(logs, `"route":"GET /v1/originals/{fingerprint}"`) {
		t.Errorf("logs should name the route pattern: %s", logs)
	}
}

func TestDevicesListsNamesButNotRevokedOnesOrKeys(t *testing.T) {
	hs := newHarness(t, true)
	phone, phoneID := hs.pair("Phone")
	_, tabletID := hs.pair("Tablet")
	expect(t, hs.do("GET", "/v1/devices", nil), 401, "unauthorized")
	w := hs.do("GET", "/v1/devices", nil, bearer(phone))
	expect(t, w, 200, "")
	if strings.Contains(w.Body.String(), "rvk1_") || strings.Contains(w.Body.String(), "keyId") {
		t.Fatalf("devices leak key material: %s", w.Body.String())
	}
	names := map[string]string{}
	for _, d := range decode(t, w)["devices"].([]any) {
		m := d.(map[string]any)
		names[m["id"].(string)] = m["name"].(string)
	}
	if names[phoneID] != "Phone" || names[tabletID] != "Tablet" {
		t.Fatalf("devices %v", names)
	}
	expect(t, hs.do("POST", "/admin/api/devices/"+tabletID+"/revoke", nil, adminHeaders()), 200, "")
	w = hs.do("GET", "/v1/devices", nil, bearer(phone))
	if strings.Contains(w.Body.String(), tabletID) {
		t.Fatalf("a revoked device is listed: %s", w.Body.String())
	}
}

// uploadPreview sends a small preview in one chunk and returns its SHA-256.
func uploadPreview(t *testing.T, hs *harness, key string, data []byte) string {
	t.Helper()
	sha := sum(data)
	w := hs.do("POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(data), "kind": "preview"}, bearer(key))
	if w.Code == 200 && decode(t, w)["status"] == "exists" {
		return sha
	}
	expect(t, w, 201, "")
	id := decode(t, w)["uploadId"].(string)
	w = hs.do("PATCH", "/v1/uploads/"+id, data, bearer(key), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream"))
	expect(t, w, 204, "")
	return sha
}

func photoWithPreview(sha string) map[string]any {
	p := photo()
	if sha != "" {
		p["preview"] = map[string]any{"sha256": sha, "width": 2560, "height": 1707}
	}
	return p
}

func TestDroppingAPreview(t *testing.T) {
	hs := newHarness(t, true)
	phone, _ := hs.pair("Phone")
	tablet, _ := hs.pair("Tablet")
	sha := uploadPreview(t, hs, phone, []byte("a smart preview"))
	file := filepath.Join(hs.backup, "reveal-sync", "blobs", sha[:2], sha[2:4], sha)
	r := push(t, hs, phone, change("photo", photoA, 0, 1000, photoWithPreview(sha)))
	rev := int64(r[0]["rev"].(float64))

	// A live photo record names it: kept, for its uploader too.
	w := hs.do("DELETE", "/v1/blobs/"+sha, nil, bearer(phone))
	expect(t, w, 409, "conflict")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"] != "referenced" {
		t.Fatal("reason")
	}
	// Another device may never drop it.
	w = hs.do("DELETE", "/v1/blobs/"+sha, nil, bearer(tablet))
	expect(t, w, 403, "forbidden")

	// Once the record no longer names it, the uploader drops it: the row, the file, and every later read.
	push(t, hs, phone, change("photo", photoA, rev, 2000, photoWithPreview("")))
	expect(t, hs.do("DELETE", "/v1/blobs/"+sha, nil, bearer(phone)), 204, "")
	if _, err := os.Stat(file); !os.IsNotExist(err) {
		t.Fatalf("file still there: %v", err)
	}
	expect(t, hs.do("GET", "/v1/blobs/"+sha, nil, bearer(tablet)), 404, "not_found")
	expect(t, hs.do("DELETE", "/v1/blobs/"+sha, nil, bearer(phone)), 404, "not_found")
	expect(t, hs.do("DELETE", "/v1/blobs/not-a-sha", nil, bearer(phone)), 404, "not_found")
	expect(t, hs.do("DELETE", "/v1/blobs/"+sha, nil), 401, "unauthorized")

	// Uploaded again after a drop: a normal new blob.
	if again := uploadPreview(t, hs, phone, []byte("a smart preview")); again != sha {
		t.Fatal("same bytes, same address")
	}
	if _, err := os.Stat(file); err != nil {
		t.Fatalf("re-uploaded file: %v", err)
	}
}

func TestDroppingAPreviewOtherRecordsStillName(t *testing.T) {
	hs := newHarness(t, true)
	phone, _ := hs.pair("Phone")
	sha := uploadPreview(t, hs, phone, []byte("shared preview bytes"))
	// Two photos name the same bytes (e.g. a duplicate file): the preview stays while either does.
	push(t, hs, phone, change("photo", photoA, 0, 1000, photoWithPreview(sha)), change("photo", photoB, 0, 1000, photoWithPreview(sha)))
	pa := pull(t, hs, phone, "", 0)
	revA := int64(0)
	for _, c := range pa.Changes {
		if c.ID == photoA {
			revA = c.Rev
		}
	}
	push(t, hs, phone, change("photo", photoA, revA, 2000, photoWithPreview("")))
	expect(t, hs.do("DELETE", "/v1/blobs/"+sha, nil, bearer(phone)), 409, "conflict")
	// A deleted photo (tombstone) no longer holds it.
	revB := int64(0)
	for _, c := range pull(t, hs, phone, "", 0).Changes {
		if c.ID == photoB {
			revB = c.Rev
		}
	}
	push(t, hs, phone, change("photo", photoB, revB, 3000, nil))
	expect(t, hs.do("DELETE", "/v1/blobs/"+sha, nil, bearer(phone)), 204, "")
}

func TestOriginalsAreNeverDroppedByADevice(t *testing.T) {
	hs := newHarness(t, true)
	phone, _ := hs.pair("Phone")
	data := []byte("an original file")
	sha, fp := sum(data), fingerprint.Bytes(data)
	w := hs.do("POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(data), "kind": "original", "fingerprint": fp}, bearer(phone))
	expect(t, w, 201, "")
	id := decode(t, w)["uploadId"].(string)
	expect(t, hs.do("PATCH", "/v1/uploads/"+id, data, bearer(phone), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream")), 204, "")
	w = hs.do("DELETE", "/v1/blobs/"+sha, nil, bearer(phone))
	expect(t, w, 403, "forbidden")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"] != "original" {
		t.Fatal("reason")
	}
	expect(t, hs.do("GET", "/v1/originals/"+fp, nil, bearer(phone)), 200, "")
}
