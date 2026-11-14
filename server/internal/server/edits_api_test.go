package server

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/previews"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Reveal's edited previews (ADR 0020). Images are drawn here; nothing private.

const (
	verMain = "0b6c41de-8b4a-4d55-9f0e-6f0c2d1a0001"
	verCopy = "0b6c41de-8b4a-4d55-9f0e-6f0c2d1a0002"
	verLate = "0b6c41de-8b4a-4d55-9f0e-6f0c2d1a0003"
)

// editJPEG draws an upright w×h JPEG in a hue, with an EXIF segment the server must remove.
func editJPEG(t *testing.T, w, h int, hue uint8) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			img.Set(x, y, color.RGBA{hue, uint8(x * 255 / w), uint8(y * 255 / h), 255})
		}
	}
	var b bytes.Buffer
	if err := jpeg.Encode(&b, img, &jpeg.Options{Quality: 85}); err != nil {
		t.Fatal(err)
	}
	exif := []byte{0xFF, 0xE1, 0, 20}
	exif = append(exif, []byte("Exif\x00\x00GPS-secret12")...)
	return append(append([]byte{0xFF, 0xD8}, exif...), b.Bytes()[2:]...)
}

type editReq struct {
	digest   string
	editedAt int64
	label    string
	copy     bool
	primary  bool
	body     []byte
	ct       string
}

func putEdit(hs *harness, key, item, version string, e editReq) *httptest.ResponseRecorder {
	hs.t.Helper()
	opts := []reqOpt{bearer(key), header(hdrRecipeDigest, e.digest), header(hdrEditedAt, strconv.FormatInt(e.editedAt, 10)),
		header(hdrVirtualCopy, strconv.FormatBool(e.copy)), header(hdrPrimary, strconv.FormatBool(e.primary))}
	if e.label != "" {
		opts = append(opts, header(hdrLabel, e.label))
	}
	var body any
	if e.body != nil {
		body = e.body
		ct := e.ct
		if ct == "" {
			ct = "image/jpeg"
		}
		opts = append(opts, header("Content-Type", ct))
	}
	return hs.do("PUT", "/v1/items/"+item+"/edits/"+version, body, opts...)
}

func itemEdits(t *testing.T, hs *harness, key, id string) (int64, []store.Edit) {
	t.Helper()
	w := hs.do("GET", "/v1/items/"+id, nil, bearer(key))
	expect(t, w, 200, "")
	var it struct {
		Rev   int64        `json:"rev"`
		Edits []store.Edit `json:"edits"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &it); err != nil {
		t.Fatal(err)
	}
	return it.Rev, it.Edits
}

func TestEditUploadFeedAndPreviews(t *testing.T) {
	hs, gen, key, ids := previewsHarness(t)
	photo := ids["PAIR.ARW"]
	t0 := time.Now().Add(-time.Hour).UnixMilli()
	rev0, none := itemEdits(t, hs, key, photo)
	if none != nil {
		t.Fatalf("edits before any upload: %v", none)
	}
	head := page(t, hs, key, "/v1/items/changes?limit=500").Cursor

	// The main version, primary.
	img := editJPEG(t, 2560, 1707, 40)
	w := putEdit(hs, key, photo, verMain, editReq{digest: "r1:aaaa", editedAt: t0, primary: true, body: img})
	expect(t, w, 201, "")
	res := decode(t, w)
	if res["status"] != "created" || res["itemId"] != photo {
		t.Fatalf("%v", res)
	}
	rev1, list := itemEdits(t, hs, key, photo)
	if rev1 <= rev0 || int64(res["rev"].(float64)) != rev1 || len(list) != 1 {
		t.Fatalf("rev %d → %d, %v", rev0, rev1, list)
	}
	e := list[0]
	if e.VersionID != verMain || !e.Primary || e.IsVirtualCopy || e.RecipeDigest != "r1:aaaa" || e.EditedAt != t0 || e.Format != "jpeg" ||
		e.Width != 2560 || e.Height != 1707 || e.DeviceID == "" || e.Bytes >= int64(len(img)) {
		t.Fatalf("%+v", e)
	}
	// The items feed sends the item again, with its edits.
	p := page(t, hs, key, "/v1/items/changes?cursor="+*head)
	if len(p.Items) != 1 || p.Items[0]["id"] != photo || len(p.Items[0]["edits"].([]any)) != 1 {
		t.Fatalf("feed after the upload: %v", p.Items)
	}

	// A virtual copy (label percent-encoded), and a second primary taking over.
	w = putEdit(hs, key, photo, verCopy, editReq{digest: "r1:bbbb", editedAt: t0 + 1, label: "C%C3%B3pia%201", copy: true, body: editJPEG(t, 1200, 800, 200)})
	expect(t, w, 201, "")
	_, list = itemEdits(t, hs, key, photo)
	if len(list) != 2 || list[0].VersionID != verMain || list[1].Label != "Cópia 1" || !list[1].IsVirtualCopy || list[1].Primary {
		t.Fatalf("%+v", list)
	}
	w = putEdit(hs, key, photo, verCopy, editReq{digest: "r1:bbbb", editedAt: t0 + 2, label: "C%C3%B3pia%201", copy: true, primary: true})
	expect(t, w, 200, "")
	if decode(t, w)["status"] != "updated" {
		t.Fatal(w.Body.String())
	}
	_, list = itemEdits(t, hs, key, photo)
	if list[0].VersionID != verCopy || !list[0].Primary || list[1].Primary {
		t.Fatalf("the last primary set wins: %+v", list)
	}
	// The same request again: unchanged, no new revision; the body is not read (sent or not).
	revBefore, _ := itemEdits(t, hs, key, photo)
	w = putEdit(hs, key, photo, verCopy, editReq{digest: "r1:bbbb", editedAt: t0 + 2, label: "C%C3%B3pia%201", copy: true, primary: true, body: []byte("not even an image")})
	expect(t, w, 200, "")
	if decode(t, w)["status"] != "unchanged" {
		t.Fatal(w.Body.String())
	}
	if r, _ := itemEdits(t, hs, key, photo); r != revBefore {
		t.Fatalf("unchanged made a revision: %d → %d", revBefore, r)
	}

	// Previews: queued at upload; ?edit=primary serves the copy's, ?edit=<id> the main's; the original's stays.
	if n := work(t, hs, previews.JobImage); n < 2 {
		t.Fatalf("%d image jobs ran", n)
	}
	var editJobs []previews.Job
	for _, j := range gen.jobs() {
		if previews.IsEditJob(j.Job) {
			editJobs = append(editJobs, j)
			if j.Source.Role != previews.RoleEdit || !strings.HasPrefix(j.Source.Fingerprint, previews.EditSourcePrefix) || gen.seen[j.Source.Fingerprint] == 0 {
				t.Fatalf("edit job %+v", j)
			}
		}
	}
	if len(editJobs) != 2 {
		t.Fatalf("edit jobs %v", editJobs)
	}
	get := func(q string) *httptest.ResponseRecorder {
		return hs.do("GET", "/v1/items/"+photo+"/preview"+q, nil, bearer(key))
	}
	w = get("?size=screen&edit=primary")
	expect(t, w, 200, "")
	if w.Header().Get("X-Hoard-Edit") != verCopy || !strings.HasPrefix(w.Body.String(), "edit:"+verCopy+" screen.jpg edit:") {
		t.Fatalf("primary: %v %q", w.Header(), w.Body.String())
	}
	keyCopy := w.Header().Get("X-Hoard-Preview-Version")
	w = get("?size=thumb&format=webp&edit=" + verMain + "&v=" + keyCopy)
	expect(t, w, 200, "")
	if w.Header().Get("X-Hoard-Edit") != verMain || w.Header().Get("X-Hoard-Preview-Version") == keyCopy || w.Header().Get("Cache-Control") != "private, no-cache" {
		t.Fatalf("main: %v", w.Header())
	}
	w = get("?size=thumb&edit=" + verLate)
	expect(t, w, 404, "preview_unavailable")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"] != "no_edit" {
		t.Fatal(w.Body.String())
	}
	expect(t, get("?edit=latest"), 400, "invalid_request")
	expect(t, hs.do("GET", "/v1/items/"+photo+"/poster?edit=primary", nil, bearer(key)), 400, "invalid_request")
	w = get("?size=thumb")
	if w.Code == 200 && strings.HasPrefix(w.Body.String(), "edit:") {
		t.Fatal("the original's preview is the edit's")
	}

	// Warm with an edit: the items without one say so.
	w = hs.do("POST", "/v1/items/previews", map[string]any{"items": []string{photo, ids["PHOTO.JPG"]}, "previews": []string{"thumb", "screen"}, "edit": "primary"}, bearer(key))
	expect(t, w, 200, "")
	warm := decode(t, w)["items"].([]any)
	first, second := warm[0].(map[string]any), warm[1].(map[string]any)
	if first["edit"] != verCopy || first["thumb"].(map[string]any)["status"] != "ready" || first["thumb"].(map[string]any)["v"] != keyCopy {
		t.Fatalf("warm with edit: %v", first)
	}
	if second["thumb"].(map[string]any)["status"] != "unavailable" || second["thumb"].(map[string]any)["reason"] != "no_edit" {
		t.Fatalf("warm without edit: %v", second)
	}

	// The stored upload: cleaned (no EXIF), named, ranges.
	w = hs.do("GET", "/v1/items/"+photo+"/edits/"+verMain, nil, bearer(key))
	expect(t, w, 200, "")
	if w.Header().Get("Content-Type") != "image/jpeg" || bytes.Contains(w.Body.Bytes(), []byte("GPS-secret")) || w.Header().Get(hdrRecipeDigest) != "r1:aaaa" ||
		!strings.Contains(w.Header().Get("Content-Disposition"), "PAIR (Edited).jpg") {
		t.Fatalf("%v %d", w.Header(), w.Body.Len())
	}
	if _, err := jpeg.Decode(bytes.NewReader(w.Body.Bytes())); err != nil {
		t.Fatal(err)
	}
	expect(t, hs.do("GET", "/v1/items/"+photo+"/edits/"+verLate, nil, bearer(key)), 404, "not_found")

	// A new render replaces the image and the preview; the old file goes.
	oldSHA := mustEdit(t, hs, verMain).SHA256
	w = putEdit(hs, key, photo, verMain, editReq{digest: "r1:cccc", editedAt: t0 + 10, body: editJPEG(t, 2000, 1333, 90)})
	expect(t, w, 200, "")
	if decode(t, w)["status"] != "replaced" {
		t.Fatal(w.Body.String())
	}
	if _, err := os.Stat(hs.srv.editPath(oldSHA)); !os.IsNotExist(err) {
		t.Fatalf("the replaced file stays: %v", err)
	}
	// An older render of the same version loses (another device's late upload).
	w = putEdit(hs, key, photo, verMain, editReq{digest: "r1:dddd", editedAt: t0 + 5, body: editJPEG(t, 100, 100, 10)})
	expect(t, w, 409, "conflict")
	if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["reason"] != "older" || d["recipeDigest"] != "r1:cccc" {
		t.Fatalf("%v", d)
	}
	// A new digest without an image.
	w = putEdit(hs, key, photo, verMain, editReq{digest: "r1:eeee", editedAt: t0 + 20})
	expect(t, w, 422, "invalid_request")

	// Delete: the item is sent again without it; deleting again is "absent".
	sha := mustEdit(t, hs, verMain).SHA256
	w = hs.do("DELETE", "/v1/items/"+photo+"/edits/"+verMain, nil, bearer(key))
	expect(t, w, 200, "")
	if decode(t, w)["status"] != "deleted" {
		t.Fatal(w.Body.String())
	}
	if _, list = itemEdits(t, hs, key, photo); len(list) != 1 || list[0].VersionID != verCopy {
		t.Fatalf("%+v", list)
	}
	if _, err := os.Stat(hs.srv.editPath(sha)); !os.IsNotExist(err) {
		t.Fatal("the deleted edit's file stays")
	}
	w = hs.do("DELETE", "/v1/items/"+photo+"/edits/"+verMain, nil, bearer(key))
	expect(t, w, 200, "")
	if decode(t, w)["status"] != "absent" {
		t.Fatal(w.Body.String())
	}
	// The backlog scan drops the deleted edit's preview row.
	if err := hs.srv.prev.ScanNow(t.Context()); err != nil {
		t.Fatal(err)
	}
	rows, _ := hs.st.Previews(t.Context(), photo)
	if _, ok := rows[previews.EditJob(verMain)]; ok {
		t.Fatal("the deleted edit's previews stay")
	}
	if _, ok := rows[previews.EditJob(verCopy)]; !ok {
		t.Fatal("the copy's previews went")
	}

	// The setup page counts them.
	w = hs.do("GET", "/admin/api/storage", nil, adminHeaders())
	expect(t, w, 200, "")
	st := decode(t, w)["edits"].(map[string]any)["stats"].(map[string]any)
	if st["edits"].(float64) != 1 || st["virtualCopies"].(float64) != 1 || st["bytes"].(float64) <= 0 || st["previewBytes"].(float64) <= 0 {
		t.Fatalf("storage %v", st)
	}
	// /v1/server publishes the limits.
	info := decode(t, hs.do("GET", "/v1/server", nil, bearer(key)))
	if lim := info["edits"].(map[string]any); lim["maxEdge"].(float64) != 2560 || lim["maxBytes"].(float64) != 8<<20 {
		t.Fatalf("%v", lim)
	}
}

func mustEdit(t *testing.T, hs *harness, version string) store.Edit {
	t.Helper()
	e, err := hs.st.Edit(t.Context(), version)
	if err != nil {
		t.Fatal(err)
	}
	return e
}

func TestEditValidation(t *testing.T) {
	hs, _, key, ids := previewsHarness(t)
	photo := ids["PHOTO.JPG"]
	now := time.Now().UnixMilli()
	ok := editReq{digest: "r1:x", editedAt: now, body: editJPEG(t, 400, 300, 1)}
	cases := []struct {
		name    string
		item    string
		version string
		mod     func(*editReq)
		status  int
		code    string
		reason  string
	}{
		{"no digest", photo, verMain, func(e *editReq) { e.digest = "" }, 400, "invalid_request", ""},
		{"digest with spaces", photo, verMain, func(e *editReq) { e.digest = "a b" }, 400, "invalid_request", ""},
		{"long digest", photo, verMain, func(e *editReq) { e.digest = strings.Repeat("a", 129) }, 400, "invalid_request", ""},
		{"no editedAt", photo, verMain, func(e *editReq) { e.editedAt = 0 }, 400, "invalid_request", ""},
		{"label too long", photo, verMain, func(e *editReq) { e.label = strings.Repeat("x", 65) }, 400, "invalid_request", ""},
		{"label control char", photo, verMain, func(e *editReq) { e.label = "a%0Ab" }, 400, "invalid_request", ""},
		{"bad version", photo, "not-a-uuid", nil, 400, "invalid_request", ""},
		{"unknown item", "00000000-0000-8000-8000-000000000000", verMain, nil, 404, "not_found", ""},
		{"a video", ids["CLIP.MP4"], verMain, nil, 422, "invalid_request", "not_applicable"},
		{"png", photo, verMain, func(e *editReq) { e.ct = "image/png" }, 415, "unsupported_media_type", ""},
		{"not a jpeg", photo, verMain, func(e *editReq) { e.body = []byte("GIF89a....") }, 422, "invalid_image", "format"},
		{"too big", photo, verMain, func(e *editReq) { e.body = editJPEG(t, 3000, 2000, 1) }, 422, "invalid_image", "dimensions"},
		{"no image for a new version", photo, verMain, func(e *editReq) { e.body = nil }, 422, "invalid_request", "image_required"},
		{"body over 8 MiB", photo, verMain, func(e *editReq) { e.body = append(editJPEG(t, 64, 64, 1), make([]byte, 8<<20)...) }, 413, "too_large", ""},
	}
	for _, c := range cases {
		e := ok
		if c.mod != nil {
			c.mod(&e)
		}
		w := putEdit(hs, key, c.item, c.version, e)
		if w.Code != c.status || errCode(t, w) != c.code {
			t.Errorf("%s: %d %s", c.name, w.Code, w.Body.String())
			continue
		}
		if c.reason != "" {
			if r := decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"]; r != c.reason {
				t.Errorf("%s: reason %v", c.name, r)
			}
		}
	}
	// Nothing was stored, no file left behind.
	if n, _ := os.ReadDir(hs.srv.cfg.EditsDir); len(n) != 0 {
		t.Fatalf("files left: %v", n)
	}
	// A device key is needed.
	expect(t, hs.do("PUT", "/v1/items/"+photo+"/edits/"+verMain, ok.body, header("Content-Type", "image/jpeg")), 401, "unauthorized")
	// A clock far ahead is clipped (it must not win every later upload).
	w := putEdit(hs, key, photo, verMain, editReq{digest: "r1:x", editedAt: now + 365*24*3600*1000, body: ok.body})
	expect(t, w, 201, "")
	if at := mustEdit(t, hs, verMain).EditedAt; at > time.Now().Add(11*time.Minute).UnixMilli() {
		t.Fatalf("editedAt not clipped: %d", at)
	}
	// 32 edits per item at most.
	for i := 1; i < maxEditsPerItem; i++ {
		v := fmt.Sprintf("0b6c41de-8b4a-4d55-9f0e-%012d", i)
		expect(t, putEdit(hs, key, photo, v, editReq{digest: "r1:x", editedAt: now, body: ok.body}), 201, "")
	}
	w = putEdit(hs, key, photo, verLate, editReq{digest: "r1:x", editedAt: now, body: ok.body})
	expect(t, w, 422, "invalid_request")
	// The same image for every version: one file.
	if n, _ := os.ReadDir(hs.srv.cfg.EditsDir); len(n) != 1 {
		t.Fatalf("%d directories for one image", len(n))
	}
}

// TestEditLifecycle: a version moves with a PUT on another item; an item replaced by another (its RAW appeared) hands
// its edits over; a deleted item takes its edits with it; files no edit names are swept.
func TestEditLifecycle(t *testing.T) {
	hs, _, key, ids := previewsHarness(t)
	now := time.Now().UnixMilli()
	img := editJPEG(t, 300, 200, 3)
	// Moving a version between items.
	expect(t, putEdit(hs, key, ids["PHOTO.JPG"], verMain, editReq{digest: "r1:a", editedAt: now, primary: true, body: img}), 201, "")
	revA, _ := itemEdits(t, hs, key, ids["PHOTO.JPG"])
	expect(t, putEdit(hs, key, ids["RAWONLY.ARW"], verMain, editReq{digest: "r1:a", editedAt: now, primary: true}), 200, "")
	revA2, onA := itemEdits(t, hs, key, ids["PHOTO.JPG"])
	if _, onB := itemEdits(t, hs, key, ids["RAWONLY.ARW"]); len(onA) != 0 || len(onB) != 1 || revA2 <= revA {
		t.Fatalf("move: %v %v %d→%d", onA, onB, revA, revA2)
	}

	// A JPEG-only shot gets an edit, then its RAW appears: the new pair item has the edit.
	write := func(rel string, b []byte) {
		if err := os.WriteFile(filepath.Join(hs.nas, rel), b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	shot := mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", DateTime: "2024:06:01 10:00:00", Offset: "+00:00", Width: 6000, Height: 4000, Unique: 77}
	write("LATE.JPG", mediatest.JPEG(shot, ""))
	reindex(t, hs)
	jpgOnly := findItem(t, hs, key, "LATE.JPG")
	expect(t, putEdit(hs, key, jpgOnly, verLate, editReq{digest: "r1:l", editedAt: now, primary: true, body: editJPEG(t, 300, 200, 4)}), 201, "")
	shot.Unique = 78
	write("LATE.ARW", mediatest.RAW(shot))
	reindex(t, hs)
	pair := findItem(t, hs, key, "LATE.ARW")
	if pair == jpgOnly {
		t.Fatal("no new item")
	}
	if _, list := itemEdits(t, hs, key, pair); len(list) != 1 || list[0].VersionID != verLate || !list[0].Primary {
		t.Fatalf("the edit did not follow the replacement: %+v", list)
	}

	// The shot is deleted: its edits go, and the sweep removes the file once no edit names it.
	sha := mustEdit(t, hs, verLate).SHA256
	for _, n := range []string{"LATE.JPG", "LATE.ARW"} {
		if err := os.Remove(filepath.Join(hs.nas, n)); err != nil {
			t.Fatal(err)
		}
	}
	reindex(t, hs)
	if _, err := hs.st.Edit(t.Context(), verLate); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("the deleted item's edit stays: %v", err)
	}
	w := hs.do("GET", "/v1/items/"+pair, nil, bearer(key))
	if strings.Contains(w.Body.String(), `"edits"`) {
		t.Fatal("a tombstone with edits")
	}
	if sha == mustEdit(t, hs, verMain).SHA256 {
		t.Fatal("the test needs two images")
	}
	old := time.Now().Add(-2 * time.Hour)
	_ = os.Chtimes(hs.srv.editPath(sha), old, old)
	_ = os.Chtimes(hs.srv.editPath(mustEdit(t, hs, verMain).SHA256), old, old)
	hs.srv.sweepEdits(t.Context())
	if _, err := os.Stat(hs.srv.editPath(sha)); !os.IsNotExist(err) {
		t.Fatal("not swept")
	}
	if _, err := os.Stat(hs.srv.editPath(mustEdit(t, hs, verMain).SHA256)); err != nil {
		t.Fatal("a used file was swept")
	}
}

func reindex(t *testing.T, hs *harness) {
	t.Helper()
	folders, err := hs.st.NasFolders(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range folders {
		if err := hs.srv.indexer.Folder(t.Context(), f); err != nil {
			t.Fatal(err)
		}
	}
}

func findItem(t *testing.T, hs *harness, key, name string) string {
	t.Helper()
	for _, it := range page(t, hs, key, "/v1/items?limit=500").Items {
		for _, f := range it["files"].([]any) {
			if f.(map[string]any)["name"] == name {
				return it["id"].(string)
			}
		}
	}
	t.Fatalf("no item has %s", name)
	return ""
}

func TestWebEdits(t *testing.T) {
	hs, _, key, ids := previewsHarness(t)
	c := webSignIn(t, hs)
	photo := ids["PHOTO.JPG"]
	now := time.Now().UnixMilli()
	a := library(t, hs, c, "")
	expect(t, putEdit(hs, key, photo, verMain, editReq{digest: "r1:a", editedAt: now, primary: true, body: editJPEG(t, 600, 400, 5)}), 201, "")
	expect(t, putEdit(hs, key, photo, verCopy, editReq{digest: "r1:b", editedAt: now, label: "Copy%201", copy: true, body: editJPEG(t, 400, 600, 9)}), 201, "")
	work(t, hs, previews.JobImage)
	time.Sleep(2100 * time.Millisecond) // the index looks at preview keys at most every 2 s
	b := library(t, hs, c, a.Cursor)
	var row *libRow
	for i := range b.Rows {
		if b.Rows[i].ID == photo {
			row = &b.Rows[i]
		}
	}
	if row == nil || len(row.Ed) != 2 {
		t.Fatalf("library rows %+v", b.Rows)
	}
	if row.Ed[0].ID != verMain || !row.Ed[0].P || row.Ed[0].V == "" || row.Ed[0].W != 600 || row.Ed[1].ID != verCopy || !row.Ed[1].C || row.Ed[1].L != "Copy 1" || row.Ed[1].V == "" {
		t.Fatalf("ed %+v", row.Ed)
	}
	// The thumbnail URL the grid builds is immutable.
	w := hs.do("GET", "/web/items/"+photo+"/preview?size=thumb&format=webp&edit="+verMain+"&v="+row.Ed[0].V, nil, webLocal(), withCookie(c))
	expect(t, w, 200, "")
	if !strings.Contains(w.Header().Get("Cache-Control"), "immutable") {
		t.Fatal(w.Header())
	}
	// The item answer carries the edits; "Download edited JPEG" is named.
	w = hs.do("GET", "/web/items/"+photo, nil, webLocal(), withCookie(c))
	expect(t, w, 200, "")
	if !strings.Contains(w.Body.String(), `"edits":[{"versionId":"`+verMain) {
		t.Fatal(w.Body.String())
	}
	w = hs.do("GET", "/web/items/"+photo+"/edits/"+verCopy+"/PHOTO%20(Copy%201).jpg?download=1", nil, webLocal(), withCookie(c))
	expect(t, w, 200, "")
	if cd := w.Header().Get("Content-Disposition"); !strings.HasPrefix(cd, "attachment;") || !strings.Contains(cd, "PHOTO (Copy 1).jpg") {
		t.Fatal(cd)
	}
	// Not from outside the LAN.
	expect(t, hs.do("GET", "/web/items/"+photo+"/edits/"+verCopy, nil, withCookie(c)), 404, "not_found")
	// Deleted: the row loses it.
	expect(t, hs.do("DELETE", "/v1/items/"+photo+"/edits/"+verCopy, nil, bearer(key)), 200, "")
	d := library(t, hs, c, b.Cursor)
	for _, r := range d.Rows {
		if r.ID == photo && len(r.Ed) != 1 {
			t.Fatalf("%+v", r.Ed)
		}
	}
}
