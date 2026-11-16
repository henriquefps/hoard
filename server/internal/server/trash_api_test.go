package server

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/ratelimit"
)

// The trash on /v1 (ADR 0023). Synthetic files only.

const trashDay = ".hoard-trash/2026-11-15"

func (hs *harness) move(from, to string) {
	hs.t.Helper()
	dst := filepath.Join(hs.nas, to)
	if err := os.MkdirAll(filepath.Dir(dst), 0o700); err != nil {
		hs.t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(hs.nas, from), dst); err != nil {
		hs.t.Fatal(err)
	}
}

func (hs *harness) walkAll() {
	hs.t.Helper()
	folders, err := hs.st.NasFolders(hs.t.Context())
	if err != nil {
		hs.t.Fatal(err)
	}
	for _, f := range folders {
		if err := hs.srv.indexer.Folder(hs.t.Context(), f); err != nil {
			hs.t.Fatal(err)
		}
	}
}

func getItem(t *testing.T, hs *harness, key, id, query string) map[string]any {
	t.Helper()
	w := hs.do("GET", "/v1/items/"+id+query, nil, bearer(key))
	expect(t, w, 200, "")
	return decode(t, w)
}

func nasFeed(t *testing.T, hs *harness, key string) map[string]map[string]any {
	t.Helper()
	out := map[string]map[string]any{}
	cursor := ""
	for {
		w := hs.do("GET", "/v1/nas/files?limit=500&cursor="+cursor, nil, bearer(key))
		expect(t, w, 200, "")
		var p struct {
			Files   []map[string]any `json:"files"`
			Cursor  string           `json:"cursor"`
			HasMore bool             `json:"hasMore"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &p); err != nil {
			t.Fatal(err)
		}
		for _, f := range p.Files {
			out[f["path"].(string)] = f
		}
		cursor = p.Cursor
		if !p.HasMore {
			return out
		}
	}
}

// Clients that do not ask for the trash see what older servers answered: a trashed item is a tombstone, a restored one
// comes back live with the same id; the NAS feed sends the original row removed (and "trashed").
func TestTrashOldClients(t *testing.T) {
	hs, key, folderID := itemsHarness(t)
	ids := map[string]string{}
	for _, it := range page(t, hs, key, "/v1/items?limit=50").Items {
		ids[it["files"].([]any)[0].(map[string]any)["name"].(string)] = it["id"].(string)
	}
	pair, single := ids["DSC00100.ARW"], ids["DSC00101.JPG"]
	if pair == "" || single == "" {
		t.Fatalf("%v", ids)
	}
	cursor := *page(t, hs, key, "/v1/items/changes?limit=500").Cursor
	nasBefore := nasFeed(t, hs, key)

	// Trash the pair and one JPEG, plus a pair whose RAW has a live copy elsewhere (that item stays live, its JPEG
	// only in the trash).
	raw, err := os.ReadFile(filepath.Join(hs.nas, "2024-05-02/DSC00200.ARW"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(hs.nas, "copies"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(hs.nas, "copies/DSC00200.ARW"), raw, 0o600); err != nil {
		t.Fatal(err)
	}
	hs.move("2024-05-01/DSC00100.ARW", trashDay+"/2024-05-01/DSC00100.ARW")
	hs.move("2024-05-01/DSC00100.JPG", trashDay+"/2024-05-01/DSC00100.JPG")
	hs.move("2024-05-01/DSC00101.JPG", trashDay+"/2024-05-01/DSC00101.JPG")
	hs.move("2024-05-02/DSC00200.ARW", trashDay+"/2024-05-02/DSC00200.ARW")
	hs.move("2024-05-02/DSC00200.JPG", trashDay+"/2024-05-02/DSC00200.JPG")
	hs.walkAll()
	half := ids["DSC00200.ARW"]

	// Without include=trash: tombstones, exactly.
	it := getItem(t, hs, key, pair, "")
	if it["deleted"] != true || len(it) != 5 || it["trashed"] != nil || it["files"] != nil {
		t.Fatalf("tombstone shape %v", it)
	}
	feed := page(t, hs, key, "/v1/items/changes?limit=500&cursor="+cursor)
	byID := map[string]map[string]any{}
	for _, it := range feed.Items {
		byID[it["id"].(string)] = it
	}
	if byID[pair]["deleted"] != true || byID[single]["deleted"] != true || len(byID[pair]) != 5 {
		t.Fatalf("feed %v", feed.Items)
	}
	// A live item with a member only in the trash lists only its live files and locations.
	h := getItem(t, hs, key, half, "")
	if files := h["files"].([]any); h["deleted"] != false || len(files) != 1 || strings.Contains(fmt.Sprint(h), "hoard-trash") ||
		len(files[0].(map[string]any)["locations"].([]any)) != 1 {
		t.Fatalf("partly trashed item %v", h)
	}
	// GET /v1/items: live only; ?trashed=only: the trashed ones whole.
	for _, it := range page(t, hs, key, "/v1/items?limit=50").Items {
		if it["id"] == pair || it["id"] == single {
			t.Fatal("a trashed item is listed as live")
		}
	}
	tr := page(t, hs, key, "/v1/items?trashed=only&limit=50")
	if len(tr.Items) != 2 {
		t.Fatalf("trashed=only %v", tr.Items)
	}
	if n := len(page(t, hs, key, "/v1/items?trashed=only&folder="+folderID+"&path=2024-05-01").Items); n != 2 {
		t.Fatalf("trashed=only in the original folder: %d", n)
	}
	expect(t, hs.do("GET", "/v1/items?trashed=yes", nil, bearer(key)), 400, "invalid_request")
	// With include=trash: whole, deleted false, trashed.since, trash paths.
	it = getItem(t, hs, key, pair, "?include=trash")
	since, _ := it["trashed"].(map[string]any)["since"].(float64)
	if it["deleted"] != false || since <= 0 {
		t.Fatalf("include=trash %v", it)
	}
	loc := it["files"].([]any)[0].(map[string]any)["locations"].([]any)[0].(map[string]any)
	if loc["path"] != "2024-05-01/DSC00100.ARW" || loc["trash"] != trashDay+"/2024-05-01/DSC00100.ARW" || loc["source"] != "nas" {
		t.Fatalf("trash location %v", loc)
	}
	if h := getItem(t, hs, key, half, "?include=trash"); len(h["files"].([]any)) != 2 ||
		len(h["files"].([]any)[0].(map[string]any)["locations"].([]any)) != 2 {
		t.Fatalf("partly trashed with include %v", h)
	}
	withTrash := page(t, hs, key, "/v1/items/changes?include=items,culling,trash&limit=500&cursor="+cursor)
	found := false
	for _, it := range withTrash.Items {
		if it["id"] == pair {
			found = it["deleted"] == false && it["trashed"] != nil
		}
	}
	if !found {
		t.Fatalf("feed with trash %v", withTrash.Items)
	}
	if n := len(page(t, hs, key, "/v1/items/changes?include=trash&limit=500&cursor="+cursor).Items); n != len(withTrash.Items) {
		t.Fatal("include=trash alone is not the items with the trash")
	}
	expect(t, hs.do("GET", "/v1/items/"+pair+"?include=culling", nil, bearer(key)), 400, "invalid_request")
	// Lookup: trashed items and files only in a trash are missing, unless include trash.
	fpRaw := it["files"].([]any)[0].(map[string]any)["fingerprint"].(string)
	var halfJPEG string
	for _, f := range getItem(t, hs, key, half, "?include=trash")["files"].([]any) {
		if f.(map[string]any)["role"] == "rendered" {
			halfJPEG = f.(map[string]any)["fingerprint"].(string)
		}
	}
	w := hs.do("POST", "/v1/items/lookup", map[string]any{"fingerprints": []string{fpRaw, halfJPEG}}, bearer(key))
	expect(t, w, 200, "")
	var lk itemsPage
	_ = json.Unmarshal(w.Body.Bytes(), &lk)
	if len(lk.Matches) != 0 || len(lk.Missing) != 2 || len(lk.Items) != 0 {
		t.Fatalf("lookup without trash %+v", lk)
	}
	w = hs.do("POST", "/v1/items/lookup", map[string]any{"fingerprints": []string{fpRaw, halfJPEG}, "include": []string{"trash"}}, bearer(key))
	expect(t, w, 200, "")
	lk = itemsPage{}
	_ = json.Unmarshal(w.Body.Bytes(), &lk)
	if len(lk.Matches) != 2 || len(lk.Missing) != 0 || len(lk.Items) != 2 {
		t.Fatalf("lookup with trash %+v", lk)
	}
	expect(t, hs.do("POST", "/v1/items/lookup", map[string]any{"fingerprints": []string{fpRaw}, "include": []string{"x"}}, bearer(key)), 422, "invalid_request")
	// by-path stays live only (neither the original path nor the trash path).
	w = hs.do("POST", "/v1/items/by-path", map[string]any{"folder": folderID, "paths": []string{"2024-05-01/DSC00100.ARW",
		trashDay + "/2024-05-01/DSC00100.ARW"}}, bearer(key))
	expect(t, w, 200, "")
	if bp := decode(t, w); len(bp["found"].([]any)) != 0 || len(bp["missing"].([]any)) != 2 {
		t.Fatalf("by-path %v", bp)
	}
	// Originals and the fingerprint lookup still find a trashed file.
	expect(t, hs.do("GET", "/v1/originals/"+fpRaw, nil, bearer(key)), 200, "")
	w = hs.do("POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{fpRaw}}, bearer(key))
	expect(t, w, 200, "")
	if !strings.Contains(w.Body.String(), fpRaw) || strings.Contains(w.Body.String(), `"missing":["`+fpRaw) {
		t.Fatalf("fingerprint lookup %s", w.Body.String())
	}
	// Health counts live items and live NAS photos only.
	c := decode(t, hs.do("GET", "/v1/health", nil))["counts"].(map[string]any)
	if c["items"] != float64(11) || c["nasFiles"] != float64(16-4) {
		t.Fatalf("counts %v", c)
	}

	// NAS feed: the original rows removed and trashed; no trash row.
	nf := nasFeed(t, hs, key)
	for p := range nf {
		if strings.Contains(p, ".hoard-trash") {
			t.Fatalf("trash row in the NAS feed: %s", p)
		}
	}
	for _, p := range []string{"2024-05-01/DSC00100.ARW", "2024-05-01/DSC00101.JPG", "2024-05-02/DSC00200.JPG"} {
		if f := nf[p]; f["removed"] != true || f["trashed"] != true || f["fingerprint"] != nasBefore[p]["fingerprint"] {
			t.Fatalf("%s %v", p, f)
		}
	}
	if f := nf["2024-05-03/DSC00300.JPG"]; f["removed"] != false || f["trashed"] != nil {
		t.Fatalf("a live row %v", f)
	}

	// Restore: live again with the same id, a new revision; the NAS feed row is back at the same path and fingerprint.
	revTrashed := getItem(t, hs, key, pair, "?include=trash")["rev"].(float64)
	hs.move(trashDay+"/2024-05-01/DSC00100.ARW", "2024-05-01/DSC00100.ARW")
	hs.move(trashDay+"/2024-05-01/DSC00100.JPG", "2024-05-01/DSC00100.JPG")
	hs.walkAll()
	it = getItem(t, hs, key, pair, "")
	if it["deleted"] != false || it["trashed"] != nil || it["rev"].(float64) <= revTrashed || len(it["files"].([]any)) != 2 {
		t.Fatalf("restored %v", it)
	}
	nf = nasFeed(t, hs, key)
	if f := nf["2024-05-01/DSC00100.ARW"]; f["removed"] != false || f["trashed"] != nil || f["fingerprint"] != nasBefore["2024-05-01/DSC00100.ARW"]["fingerprint"] {
		t.Fatalf("restored row %v", f)
	}

	// Purge: the row again, removed without "trashed"; a tombstone for every client.
	if err := os.Remove(filepath.Join(hs.nas, trashDay, "2024-05-01/DSC00101.JPG")); err != nil {
		t.Fatal(err)
	}
	hs.walkAll()
	if f := nasFeed(t, hs, key)["2024-05-01/DSC00101.JPG"]; f["removed"] != true || f["trashed"] != nil {
		t.Fatalf("purged row %v", f)
	}
	if it := getItem(t, hs, key, single, "?include=trash"); it["deleted"] != true || it["trashed"] != nil {
		t.Fatalf("purged %v", it)
	}
	expect(t, hs.do("GET", "/v1/originals/"+nasBefore["2024-05-01/DSC00101.JPG"]["fingerprint"].(string), nil, bearer(key)), 404, "")

	// The web gallery's library leaves trashed items out.
	cookie := webSignIn(t, hs)
	inLib := func() map[string]bool {
		out := map[string]bool{}
		for _, r := range library(t, hs, cookie, "").Rows {
			out[r.ID] = true
		}
		return out
	}
	if lib := inLib(); !lib[half] || !lib[pair] || lib[ids["DSC00102.JPG"]] == false {
		t.Fatalf("library misses live items %v", lib)
	}
	hs.move("2024-05-01/DSC00100.ARW", trashDay+"/2024-05-01/DSC00100.ARW")
	hs.move("2024-05-01/DSC00100.JPG", trashDay+"/2024-05-01/DSC00100.JPG")
	hs.walkAll()
	if lib := inLib(); lib[pair] || !lib[half] {
		t.Fatal("the library lists a trashed item")
	}
	expect(t, hs.do("GET", "/web/items/"+pair, nil, webLocal(), withCookie(cookie)), 404, "not_found")
	// Nothing in a trash reaches the web: not a trashed item's files or zip, nor a live item's member that is only in a
	// trash; the library's sizes, folders and counts come from live files.
	for _, r := range library(t, hs, cookie, "").Rows {
		if r.ID == half && (r.D != "copies" || r.Size != int64(len(raw))) {
			t.Fatalf("library row of a partly trashed item %+v", r)
		}
	}
	w = hs.do("GET", "/web/folders", nil, webLocal(), withCookie(cookie))
	expect(t, w, 200, "")
	if strings.Contains(w.Body.String(), "hoard-trash") {
		t.Fatalf("folders %s", w.Body.String())
	}
	w = hs.do("GET", "/web/items/"+half, nil, webLocal(), withCookie(cookie))
	expect(t, w, 200, "")
	if strings.Contains(w.Body.String(), "hoard-trash") || strings.Contains(w.Body.String(), halfJPEG) {
		t.Fatalf("web item %s", w.Body.String())
	}
	expect(t, hs.do("GET", "/web/items/"+half+"/files/"+halfJPEG, nil, webLocal(), withCookie(cookie)), 404, "not_found")
	expect(t, hs.do("GET", "/web/items/"+pair+"/files/"+fpRaw, nil, webLocal(), withCookie(cookie)), 404, "not_found")
	expect(t, hs.do("POST", "/web/downloads", map[string]any{"items": []string{half, pair}}, webWrite(), withCookie(cookie)), 422, "invalid_request")
	w = hs.do("POST", "/web/downloads", map[string]any{"items": []string{half}}, webWrite(), withCookie(cookie))
	expect(t, w, 200, "")
	dl := decode(t, w)
	if dl["files"].(float64) != 1 || dl["bytes"].(float64) != float64(len(raw)) {
		t.Fatalf("download %v", dl)
	}
	w = hs.do("GET", dl["url"].(string), nil, webLocal(), withCookie(cookie))
	expect(t, w, 200, "")
	zr, err := zip.NewReader(bytes.NewReader(w.Body.Bytes()), int64(w.Body.Len()))
	if err != nil {
		t.Fatal(err)
	}
	if len(zr.File) != 1 || zr.File[0].Name != "DSC00200.ARW" {
		t.Fatalf("zip %v", zr.File)
	}
	// A download made before its item went to the trash leaves it out.
	w = hs.do("POST", "/web/downloads", map[string]any{"items": []string{ids["DSC00102.JPG"]}}, webWrite(), withCookie(cookie))
	expect(t, w, 200, "")
	later := decode(t, w)["url"].(string)
	hs.move("2024-05-01/DSC00102.JPG", trashDay+"/2024-05-01/DSC00102.JPG")
	hs.walkAll()
	w = hs.do("GET", later, nil, webLocal(), withCookie(cookie))
	expect(t, w, 200, "")
	if zr, err := zip.NewReader(bytes.NewReader(w.Body.Bytes()), int64(w.Body.Len())); err != nil || len(zr.File) > 1 ||
		len(zr.File) == 1 && strings.HasSuffix(zr.File[0].Name, ".JPG") {
		t.Fatalf("zip of an item trashed since: %v %v", err, zr)
	}
}

// Edits and culling survive the trash and a restore, and the edits go at the purge (ADR 0020 "Lifecycle"); the culling
// record stays.
func TestTrashEditsAndCulling(t *testing.T) {
	hs, _, key, ids := previewsHarness(t)
	photo := ids["PAIR.ARW"]
	t0 := time.Now().Add(-time.Hour).UnixMilli()
	expect(t, putEdit(hs, key, photo, verMain, editReq{digest: "r1:aaaa", editedAt: t0, primary: true, body: editJPEG(t, 800, 533, 40)}), 201, "")
	expect(t, putEdit(hs, key, photo, verCopy, editReq{digest: "r1:bbbb", editedAt: t0, copy: true, body: editJPEG(t, 800, 533, 90)}), 201, "")
	w := hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
		map[string]any{"itemId": photo, "field": "flag", "value": "pick", "baseRev": 0, "modifiedAt": t0}}}, bearer(key))
	expect(t, w, 200, "")
	cull := func() map[string]any {
		t.Helper()
		w := hs.do("GET", "/v1/culling/"+photo, nil, bearer(key))
		expect(t, w, 200, "")
		return decode(t, w)
	}
	before := cull()
	hs.do("GET", "/v1/items/"+photo+"/preview?size=thumb", nil, bearer(key)) // queues it
	work(t, hs, "image")
	expect(t, hs.do("GET", "/v1/items/"+photo+"/preview?size=thumb", nil, bearer(key)), 200, "")

	hs.move("PAIR.ARW", trashDay+"/PAIR.ARW")
	hs.move("PAIR.JPG", trashDay+"/PAIR.JPG")
	hs.walkAll()
	it := getItem(t, hs, key, photo, "?include=trash")
	if it["trashed"] == nil || len(it["edits"].([]any)) != 2 {
		t.Fatalf("trashed item %v", it)
	}
	// PUT: 404 as a tombstone; GET and DELETE work; previews are kept and served.
	expect(t, putEdit(hs, key, photo, verLate, editReq{digest: "r1:cccc", editedAt: t0, body: editJPEG(t, 800, 533, 120)}), 404, "not_found")
	expect(t, hs.do("GET", "/v1/items/"+photo+"/edits/"+verMain, nil, bearer(key)), 200, "")
	expect(t, hs.do("DELETE", "/v1/items/"+photo+"/edits/"+verCopy, nil, bearer(key)), 200, "")
	expect(t, hs.do("GET", "/v1/items/"+photo+"/preview?size=thumb", nil, bearer(key)), 200, "")
	// The web gallery shows no trash: its previews and edits of a trashed item are not found.
	cookie := webSignIn(t, hs)
	expect(t, hs.do("GET", "/web/items/"+photo+"/preview?size=thumb", nil, webLocal(), withCookie(cookie)), 404, "not_found")
	expect(t, hs.do("GET", "/web/items/"+photo+"/edits/"+verMain, nil, webLocal(), withCookie(cookie)), 404, "not_found")
	expect(t, hs.do("GET", "/web/items/"+ids["PHOTO.JPG"]+"/preview?size=thumb", nil, webLocal(), withCookie(cookie)), 202, "")
	// Culling: untouched (no new revision), and a push still applies.
	if after := cull(); after["rev"] != before["rev"] || fmt.Sprint(after["fingerprints"]) != fmt.Sprint(before["fingerprints"]) {
		t.Fatalf("culling changed by the trash: %v → %v", before, after)
	}
	w = hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
		map[string]any{"itemId": photo, "field": "rating", "value": 4, "baseRev": before["rev"], "modifiedAt": t0 + 1}}}, bearer(key))
	expect(t, w, 200, "")
	if r := decode(t, w)["results"].([]any)[0].(map[string]any); r["status"] != "applied" {
		t.Fatalf("push on a trashed item %v", r)
	}

	// Restore: live, same id, with the edit left and the culling.
	hs.move(trashDay+"/PAIR.ARW", "PAIR.ARW")
	hs.move(trashDay+"/PAIR.JPG", "PAIR.JPG")
	hs.walkAll()
	_, edits := itemEdits(t, hs, key, photo)
	if len(edits) != 1 || edits[0].VersionID != verMain {
		t.Fatalf("edits after restore %v", edits)
	}
	expect(t, putEdit(hs, key, photo, verLate, editReq{digest: "r1:cccc", editedAt: t0, body: editJPEG(t, 800, 533, 120)}), 201, "")

	// Purge: a tombstone; its edits go, the culling record stays.
	hs.move("PAIR.ARW", trashDay+"/PAIR.ARW")
	hs.move("PAIR.JPG", trashDay+"/PAIR.JPG")
	hs.walkAll()
	if err := os.RemoveAll(filepath.Join(hs.nas, ".hoard-trash")); err != nil {
		t.Fatal(err)
	}
	hs.walkAll()
	if it := getItem(t, hs, key, photo, "?include=trash"); it["deleted"] != true {
		t.Fatalf("not purged %v", it)
	}
	if left, _ := hs.st.ItemEdits(t.Context(), photo); len(left) != 0 {
		t.Fatalf("edits kept after the purge: %v", left)
	}
	if c := cull(); c["flag"] == nil || c["rating"] == nil {
		t.Fatalf("culling record after the purge %v", c)
	}
}

func TestNasWalkRoute(t *testing.T) {
	hs := newHarness(t, false)
	key, _ := hs.pair("Hoard")
	w := hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders())
	expect(t, w, 201, "")
	folderID := decode(t, w)["id"].(string)
	if err := os.WriteFile(filepath.Join(hs.nas, "A.JPG"), mediatest.JPEG(mediatest.Shot{DateTime: "2024:05:01 10:00:00", Unique: 1}, ""), 0o600); err != nil {
		t.Fatal(err)
	}

	// The per-device rate is checked on its own below; the budget per folder with short gaps.
	hs.srv.walkLimit = ratelimit.New(1000, 1000)
	hs.srv.indexer.WalkGap = 500 * time.Millisecond
	hs.srv.indexer.RequestGap = 2 * time.Second
	w = hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID, folderID}}, bearer(key))
	expect(t, w, 202, "")
	if q := decode(t, w)["queued"].([]any); len(q) != 1 || q[0] != folderID {
		t.Fatalf("queued %v", q)
	}
	if d := decode(t, w)["deferred"].(map[string]any); len(d) != 0 {
		t.Fatalf("deferred before any walk %v", d)
	}
	// Coalesced: queued once, however often it is asked.
	expect(t, hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}, bearer(key)), 202, "")
	if q := hs.srv.indexer.Queued(); len(q) != 1 {
		t.Fatalf("queue %v", q)
	}
	unknown := "0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f"
	w = hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID, unknown}}, bearer(key))
	expect(t, w, 404, "not_found")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["folder"] != unknown {
		t.Fatalf("404 %s", w.Body.String())
	}
	many := make([]string, 101)
	for i := range many {
		many[i] = folderID
	}
	for _, body := range []any{map[string]any{"folders": many}, map[string]any{"folders": []string{}}, map[string]any{"folders": []string{"x"}},
		map[string]any{}} {
		expect(t, hs.do("POST", "/v1/nas/walk", body, bearer(key)), 422, "invalid_request")
	}
	expect(t, hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}), 401, "unauthorized")

	// The queued walk runs in the background loop (after its first full pass, once the gap since that walk passed).
	ctx := t.Context()
	go hs.srv.indexer.Run(ctx)
	wait := func(files int) {
		t.Helper()
		deadline := time.Now().Add(30 * time.Second)
		for {
			f, _ := hs.st.NasFolder(ctx, folderID)
			if f.State == "ready" && f.FileCount == files && len(hs.srv.indexer.Queued()) == 0 && !hs.srv.indexer.Running() {
				return
			}
			if time.Now().After(deadline) {
				t.Fatalf("folder not walked: %+v, queued %v", f, hs.srv.indexer.Queued())
			}
			time.Sleep(20 * time.Millisecond)
		}
	}
	wait(1)
	// Another request right after a requested walk is deferred by the server-wide budget (one requested walk per
	// folder per RequestGap), answered with when it may start, then runs.
	if err := os.WriteFile(filepath.Join(hs.nas, "B.JPG"), mediatest.JPEG(mediatest.Shot{DateTime: "2024:05:01 10:00:01", Unique: 2}, ""), 0o600); err != nil {
		t.Fatal(err)
	}
	w = hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}, bearer(key))
	expect(t, w, 202, "")
	at, _ := decode(t, w)["deferred"].(map[string]any)[folderID].(float64)
	if at <= float64(time.Now().UnixMilli()) {
		t.Fatalf("not deferred %s", w.Body.String())
	}
	for range 5 { // more requests while it waits change nothing (one walk)
		expect(t, hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}, bearer(key)), 202, "")
	}
	time.Sleep(300 * time.Millisecond)
	if f, _ := hs.st.NasFolder(ctx, folderID); f.FileCount != 1 {
		t.Fatalf("walked again within the budget: %+v", f)
	}
	wait(2)
	if time.Now().UnixMilli() < int64(at) {
		t.Fatal("walked before the time it announced")
	}

	// Per device: a burst, then 429 with Retry-After.
	hs.srv.walkLimit = ratelimit.New(walkRate, walkBurst)
	for range walkBurst {
		expect(t, hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}, bearer(key)), 202, "")
	}
	w = hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}, bearer(key))
	expect(t, w, 429, "rate_limited")
	if w.Header().Get("Retry-After") == "" {
		t.Fatal("no Retry-After")
	}
	other, _ := hs.pair("Other")
	expect(t, hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}, bearer(other)), 202, "")
	hs.srv.walkLimit = ratelimit.New(1000, 1000)
	// A removed folder is unknown to the route.
	expect(t, hs.do("DELETE", "/admin/api/nas/"+folderID, nil, adminHeaders()), 200, "")
	expect(t, hs.do("POST", "/v1/nas/walk", map[string]any{"folders": []string{folderID}}, bearer(key)), 404, "not_found")
}
