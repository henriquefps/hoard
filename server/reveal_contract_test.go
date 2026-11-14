package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/server"
	"github.com/henriquefps/hoard/server/internal/store"
)

// revealClient sends exactly what Reveal's SyncApi sends (github.com/henriquefps/reveal bcfb71b,
// core/sync/src/main/kotlin/dev/hfps/reveal/sync/api/SyncApi.kt): JSON bodies as "application/json; charset=utf-8",
// "Accept: application/json", the cursor query parameter even when empty, null fields left out. Every answer is
// checked against the fields Reveal's data classes require (kotlinx.serialization fails on a missing field without a
// default or on a wrong JSON type; unknown fields are ignored).
type revealClient struct {
	t    *testing.T
	base string
	key  string
}

// shape lists the fields Reveal requires in an object: name -> JSON type ("string", "number", "bool", "array",
// "object", "string?" = string or absent).
type shape map[string]string

func (c *revealClient) check(where string, v any, s shape) {
	c.t.Helper()
	obj, ok := v.(map[string]any)
	if !ok {
		c.t.Fatalf("%s: not an object: %v", where, v)
	}
	for k, want := range s {
		got, present := obj[k]
		optional := strings.HasSuffix(want, "?")
		want = strings.TrimSuffix(want, "?")
		if !present || got == nil {
			if !optional {
				c.t.Fatalf("%s: Reveal requires %q: %v", where, k, obj)
			}
			continue
		}
		ok := false
		switch want {
		case "string":
			_, ok = got.(string)
		case "number":
			_, ok = got.(float64)
		case "bool":
			_, ok = got.(bool)
		case "array":
			_, ok = got.([]any)
		case "object":
			_, ok = got.(map[string]any)
		}
		if !ok {
			c.t.Fatalf("%s: %q is %T, Reveal reads a %s", where, k, got, want)
		}
	}
}

func (c *revealClient) call(method, path string, body any, auth bool) (int, http.Header, map[string]any) {
	c.t.Helper()
	var rd io.Reader
	if body != nil {
		js, _ := json.Marshal(body)
		rd = bytes.NewReader(js)
	}
	req, _ := http.NewRequest(method, c.base+path, rd)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	if auth {
		req.Header.Set("Authorization", "Bearer "+c.key)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return res.StatusCode, res.Header, m
}

func (c *revealClient) ok(method, path string, body any, want int) map[string]any {
	c.t.Helper()
	code, _, m := c.call(method, path, body, c.key != "")
	if code != want {
		c.t.Fatalf("%s %s: %d %v, want %d", method, path, code, m, want)
	}
	return m
}

func (c *revealClient) patch(id string, offset int64, chunk []byte) (int, http.Header) {
	c.t.Helper()
	req, _ := http.NewRequest("PATCH", c.base+"/v1/uploads/"+id, bytes.NewReader(chunk))
	req.Header.Set("Content-Type", "application/offset+octet-stream")
	req.Header.Set("Upload-Offset", strconv.FormatInt(offset, 10))
	req.Header.Set("Authorization", "Bearer "+c.key)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode, res.Header
}

func (c *revealClient) raw(method, path string, hdr map[string]string) (int, http.Header, []byte) {
	c.t.Helper()
	req, _ := http.NewRequest(method, c.base+path, nil)
	req.Header.Set("Authorization", "Bearer "+c.key)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res.StatusCode, res.Header, b
}

// TestRevealProtocolContract runs Reveal's sync flows against a server that also indexes items: pairing, server
// info, devices, push and pull with a conflict, a chunked original upload resumed with HEAD, lookups, ranged reads,
// a smart preview dropped, an original deleted, and the NAS feed with videos and sidecars in the folder.
func TestRevealProtocolContract(t *testing.T) {
	base := t.TempDir()
	mnt := filepath.Join(base, "mnt")
	nasDir, backup := filepath.Join(mnt, "nas"), filepath.Join(mnt, "backup")
	for _, d := range []string{nasDir, backup} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	nasRaw := mediatest.RAW(mediatest.Shot{Make: "SONY", DateTime: "2024:05:01 10:00:00", Width: 600, Height: 400, Unique: 1})
	nasFiles := map[string][]byte{
		"shoot/DSC00001.ARW": nasRaw, "shoot/DSC00001.JPG": mediatest.JPEG(mediatest.Shot{DateTime: "2024:05:01 10:00:00", Unique: 2}, ""),
		"shoot/DSC00001.xmp": []byte("<x/>"), "shoot/C0001.MP4": mediatest.MiniMP4(3), "shoot/._DSC00001.ARW": []byte("x"),
	}
	for rel, b := range nasFiles {
		write := filepath.Join(nasDir, rel)
		_ = os.MkdirAll(filepath.Dir(write), 0o700)
		if err := os.WriteFile(write, b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	st, err := store.Open(filepath.Join(base, "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	logs := &bytes.Buffer{}
	srv, err := server.New(ctx, server.Config{Version: "contract", Mounts: []string{mnt}, BackupRoot: backup, NasInterval: time.Hour,
		Logger: slog.New(slog.NewJSONHandler(logs, nil))}, st)
	if err != nil {
		t.Fatal(err)
	}
	hs := httptest.NewServer(srv.Handler())
	defer hs.Close()

	// The admin side (the setup page): password, NAS folder, pairing tokens.
	code := ""
	for _, line := range strings.Split(logs.String(), "\n") {
		var m map[string]any
		if json.Unmarshal([]byte(line), &m) == nil && m["setupCode"] != nil {
			code = m["setupCode"].(string)
		}
	}
	jar, _ := cookiejar.New(nil)
	admin := &http.Client{Jar: jar}
	adminCall := func(path string, body any) map[string]any {
		js, _ := json.Marshal(body)
		req, _ := http.NewRequest("POST", hs.URL+"/admin/api/"+path, bytes.NewReader(js))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Reveal-Admin", "1") // the reveal-sync header name still works
		res, err := admin.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var m map[string]any
		_ = json.NewDecoder(res.Body).Decode(&m)
		if res.StatusCode >= 300 {
			t.Fatalf("admin %s: %d %v", path, res.StatusCode, m)
		}
		return m
	}
	adminCall("setup", map[string]any{"code": code, "password": "contract admin password"})
	realNas, _ := filepath.EvalSymlinks(nasDir)
	adminCall("nas", map[string]any{"path": realNas})
	srv.IndexNow(ctx)

	anon := &revealClient{t: t, base: hs.URL}
	anon.check("health", anon.ok("GET", "/v1/health", nil, 200), shape{"status": "string", "version": "string?", "api": "number"})
	pair := func(name string) *revealClient {
		tok := adminCall("pairings", map[string]any{"url": hs.URL})["token"].(string)
		p := anon.ok("POST", "/v1/pair", map[string]any{"token": tok, "deviceName": name, "platform": "android", "appVersion": "0.9.0"}, 201)
		anon.check("pair", p, shape{"deviceId": "string", "apiKey": "string", "serverId": "string", "serverName": "string?"})
		return &revealClient{t: t, base: hs.URL, key: p["apiKey"].(string)}
	}
	phone, tablet := pair("Phone"), pair("Tablet")
	info := phone.ok("GET", "/v1/server", nil, 200)
	phone.check("server", info, shape{"serverId": "string", "serverName": "string?", "version": "string?", "api": "number", "deviceId": "string",
		"limits": "object", "storageConfigured": "bool"})
	if info["api"] != float64(1) {
		t.Fatalf("api %v", info["api"])
	}
	devs := phone.ok("GET", "/v1/devices", nil, 200)
	for _, d := range devs["devices"].([]any) {
		phone.check("device", d, shape{"id": "string", "name": "string", "platform": "string?"})
	}

	// Push a photo (naming the NAS RAW and its JPEG) and a version; the tablet pulls from the empty cursor.
	rawFp := fingerprint.Bytes(nasRaw)
	photoID, versionID := "33333333-0000-4000-8000-000000000001", "33333333-0000-4000-8000-000000000002"
	res := phone.ok("POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "photo", "id": photoID, "baseRev": 0, "modifiedAt": 1000, "deleted": false,
			"data": map[string]any{"fingerprint": rawFp, "rating": 3, "files": []any{map[string]any{"fingerprint": fingerprint.Bytes(nasFiles["shoot/DSC00001.JPG"]), "role": "rendered"}}}},
		map[string]any{"kind": "version", "id": versionID, "baseRev": 0, "modifiedAt": 1000, "deleted": false,
			"data": map[string]any{"photoId": photoID, "position": 0, "recipe": map[string]any{"v": 1}, "history": []any{}, "cursor": -1}},
	}}, 200)
	for _, r := range res["results"].([]any) {
		phone.check("push result", r, shape{"kind": "string", "id": "string", "status": "string", "rev": "number", "winner": "string?", "copyId": "string?"})
	}
	pulled := tablet.ok("GET", "/v1/changes?cursor=&limit=200", nil, 200)
	tablet.check("pull", pulled, shape{"changes": "array", "cursor": "string", "hasMore": "bool"})
	if len(pulled["changes"].([]any)) != 2 {
		t.Fatalf("pull %v", pulled)
	}
	for _, r := range pulled["changes"].([]any) {
		tablet.check("record", r, shape{"kind": "string", "id": "string", "rev": "number", "deleted": "bool", "modifiedAt": "number",
			"deviceId": "string", "copyOf": "string?", "data": "object"})
	}
	// A conflict: both edit the version from rev 2; the later one wins and the other becomes a copy.
	tablet.ok("POST", "/v1/changes", map[string]any{"changes": []any{map[string]any{"kind": "version", "id": versionID, "baseRev": 2, "modifiedAt": 3000,
		"data": map[string]any{"photoId": photoID, "recipe": map[string]any{"v": 1, "x": 1}}}}}, 200)
	conflict := phone.ok("POST", "/v1/changes", map[string]any{"changes": []any{map[string]any{"kind": "version", "id": versionID, "baseRev": 2, "modifiedAt": 2000,
		"data": map[string]any{"photoId": photoID, "recipe": map[string]any{"v": 1, "x": 2}}}}}, 200)
	if r := conflict["results"].([]any)[0].(map[string]any); r["status"] != "conflict" || r["winner"] != "server" || r["copyId"] == nil {
		t.Fatalf("conflict %v", r)
	}
	after := phone.ok("GET", "/v1/changes?cursor="+pulled["cursor"].(string)+"&limit=200", nil, 200)
	if len(after["changes"].([]any)) != 2 {
		t.Fatalf("after the conflict %v", after)
	}

	// Lookup: the NAS RAW is found; a phone photo is missing, so it is uploaded in two chunks, resumed with HEAD.
	phonePhoto := append(mediatest.JPEG(mediatest.Shot{Make: "Google", DateTime: "2024:06:01 12:00:00", Unique: 9}, ""), fingerprint.VectorBytes(3<<20, 5)...)
	phoneFp, phoneSha := fingerprint.Bytes(phonePhoto), sum(phonePhoto)
	lk := phone.ok("POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{rawFp, phoneFp}}, 200)
	phone.check("lookup", lk, shape{"found": "array", "missing": "array"})
	if len(lk["found"].([]any)) != 1 || lk["missing"].([]any)[0] != phoneFp {
		t.Fatalf("lookup %v", lk)
	}
	phone.check("found", lk["found"].([]any)[0], shape{"fingerprint": "string", "size": "number", "source": "string", "sha256": "string?"})
	up := phone.ok("POST", "/v1/uploads", map[string]any{"sha256": phoneSha, "size": len(phonePhoto), "kind": "original", "fingerprint": phoneFp}, 201)
	phone.check("upload", up, shape{"status": "string", "uploadId": "string?", "offset": "number", "chunkSize": "number", "expiresAt": "number"})
	id := up["uploadId"].(string)
	half := int64(len(phonePhoto) / 2)
	if code, h := phone.patch(id, 0, phonePhoto[:half]); code != 204 || h.Get("Upload-Offset") != strconv.FormatInt(half, 10) {
		t.Fatalf("first chunk %d %v", code, h)
	}
	if code, h, _ := phone.raw("HEAD", "/v1/uploads/"+id, nil); code != 204 || h.Get("Upload-Offset") != strconv.FormatInt(half, 10) {
		t.Fatalf("HEAD %d %v", code, h)
	}
	if code, h := phone.patch(id, half, phonePhoto[half:]); code != 204 || h.Get("Upload-Complete") != "1" {
		t.Fatalf("last chunk %d %v", code, h)
	}
	lk = tablet.ok("POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{phoneFp}}, 200)
	if f := lk["found"].([]any); len(f) != 1 || f[0].(map[string]any)["source"] != "backup" || f[0].(map[string]any)["sha256"] != phoneSha {
		t.Fatalf("lookup after upload %v", lk)
	}
	if code, h, _ := tablet.raw("HEAD", "/v1/originals/"+phoneFp, nil); code != 200 || h.Get("Content-Length") != strconv.Itoa(len(phonePhoto)) {
		t.Fatalf("original size %d %v", code, h)
	}
	if code, _, b := tablet.raw("GET", "/v1/originals/"+rawFp, map[string]string{"Range": "bytes=0-9"}); code != 206 || !bytes.Equal(b, nasRaw[:10]) {
		t.Fatalf("NAS range %d", code)
	}
	// The upload also became an item; Reveal does not see it anywhere it reads.
	srv.IndexNow(ctx)

	// A smart preview: referenced → 409; dropped once the record no longer names it → 204.
	preview := fingerprint.VectorBytes(30_000, 3)
	pu := phone.ok("POST", "/v1/uploads", map[string]any{"sha256": sum(preview), "size": len(preview), "kind": "preview"}, 201)
	if code, _ := phone.patch(pu["uploadId"].(string), 0, preview); code != 204 {
		t.Fatalf("preview upload %d", code)
	}
	phone.ok("POST", "/v1/changes", map[string]any{"changes": []any{map[string]any{"kind": "photo", "id": photoID, "baseRev": 1, "modifiedAt": 4000,
		"data": map[string]any{"fingerprint": rawFp, "preview": map[string]any{"sha256": sum(preview), "width": 2048, "height": 1365}}}}}, 200)
	if code, _, _ := phone.raw("DELETE", "/v1/blobs/"+sum(preview), nil); code != 409 {
		t.Fatalf("drop referenced preview %d", code)
	}
	cur := phone.ok("GET", "/v1/changes?cursor=&limit=500", nil, 200)
	var photoRev float64
	for _, r := range cur["changes"].([]any) {
		if r.(map[string]any)["id"] == photoID {
			photoRev = r.(map[string]any)["rev"].(float64)
		}
	}
	phone.ok("POST", "/v1/changes", map[string]any{"changes": []any{map[string]any{"kind": "photo", "id": photoID, "baseRev": photoRev, "modifiedAt": 5000,
		"data": map[string]any{"fingerprint": rawFp}}}}, 200)
	if code, _, _ := phone.raw("DELETE", "/v1/blobs/"+sum(preview), nil); code != 204 {
		t.Fatalf("drop preview %d", code)
	}
	// Originals: a NAS file is refused (403); the uploaded one is deleted (204).
	if code, _, _ := phone.raw("DELETE", "/v1/originals/"+rawFp, nil); code != 403 {
		t.Fatalf("delete NAS original %d", code)
	}
	if code, _, _ := phone.raw("DELETE", "/v1/originals/"+phoneFp, nil); code != 204 {
		t.Fatalf("delete original %d", code)
	}

	// The NAS feed: the two photos only (no video, no sidecar, no hidden file), in Reveal's shape.
	nf := tablet.ok("GET", "/v1/nas/files?cursor=&limit=500", nil, 200)
	tablet.check("nas files", nf, shape{"files": "array", "cursor": "string", "hasMore": "bool"})
	var paths []string
	for _, f := range nf["files"].([]any) {
		tablet.check("nas file", f, shape{"fingerprint": "string?", "folderId": "string", "folderName": "string?", "path": "string",
			"size": "number", "modifiedAt": "number", "removed": "bool"})
		paths = append(paths, f.(map[string]any)["path"].(string))
	}
	if fmt.Sprint(paths) != "[shoot/DSC00001.ARW shoot/DSC00001.JPG]" {
		t.Fatalf("NAS feed %v", paths)
	}
	// Errors keep their shape: {"error": {"code", "message"}}.
	status, _, e := phone.call("GET", "/v1/changes?cursor=nope&limit=10", nil, true)
	if status != 400 || e["error"].(map[string]any)["code"] != "invalid_cursor" {
		t.Fatalf("error shape %d %v", status, e)
	}
	if code, h, _ := anon.raw("GET", "/v1/server", nil); code != 401 || !strings.Contains(h.Get("WWW-Authenticate"), `realm="reveal-sync"`) {
		t.Fatalf("unauthorized %d %v", code, h)
	}
}

func sum(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
