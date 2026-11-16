package server

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Phone backup on /v1 (ADR 0024). Synthetic files only.

func shot(n uint64) []byte {
	return mediatest.JPEG(mediatest.Shot{Make: "Google", Model: "Pixel 8", DateTime: "2026:11:01 10:15:00", Width: 400, Height: 300, Unique: n}, "")
}

type phoneDev struct {
	key, id, physical string
}

func (hs *harness) phone(name, app string) phoneDev {
	hs.t.Helper()
	key, id, m := hs.pairApp(name, app, "Pixel 8")
	return phoneDev{key: key, id: id, physical: m["physicalDeviceId"].(string)}
}

func (hs *harness) addRoot(p phoneDev, volume, path string, status int) map[string]any {
	hs.t.Helper()
	w := hs.do("POST", "/v1/backup/roots", map[string]any{"volume": volume, "path": path}, bearer(p.key))
	expect(hs.t, w, status, "")
	return decode(hs.t, w)
}

func fileEntry(path string, data []byte, withSHA bool) map[string]any {
	e := map[string]any{"path": path, "fingerprint": fingerprint.Bytes(data), "size": len(data), "modifiedAt": 1730455000000}
	if withSHA {
		e["sha256"] = sum(data)
	}
	return e
}

func (hs *harness) report(p phoneDev, rootID string, entries, removed []map[string]any) map[string]any {
	hs.t.Helper()
	if entries == nil {
		entries = []map[string]any{}
	}
	if removed == nil {
		removed = []map[string]any{}
	}
	w := hs.do("POST", "/v1/backup/report", map[string]any{"rootId": rootID, "entries": entries, "removed": removed}, bearer(p.key))
	expect(hs.t, w, 200, "")
	return decode(hs.t, w)
}

func states(m map[string]any) []string {
	var out []string
	for _, r := range m["results"].([]any) {
		out = append(out, r.(map[string]any)["state"].(string))
	}
	return out
}

func (hs *harness) entryAt(p phoneDev, path string) store.MirrorEntry {
	hs.t.Helper()
	e, err := hs.st.MirrorEntryAt(hs.t.Context(), p.physical, "primary", path)
	if err != nil {
		hs.t.Fatalf("entry %s: %v", path, err)
	}
	return e
}

func (hs *harness) deviceDir(physical string) string {
	hs.t.Helper()
	d, err := hs.st.MirrorDeviceByID(hs.t.Context(), physical)
	if err != nil {
		hs.t.Fatal(err)
	}
	return filepath.Join(hs.backup, d.DirName)
}

func (hs *harness) blobFile(sha string) string {
	return filepath.Join(hs.backup, "reveal-sync", "blobs", sha[:2], sha[2:4], sha)
}

// linkedTo reports whether the tree path is a hard link of the blob.
func (hs *harness) linkedTo(physical, rel, sha string) bool {
	hs.t.Helper()
	a, err := os.Stat(filepath.Join(hs.deviceDir(physical), rel))
	if err != nil {
		return false
	}
	b, err := os.Stat(hs.blobFile(sha))
	return err == nil && os.SameFile(a, b)
}

// uploadFor uploads data as an original; claim false is the mirror's own upload.
func uploadFor(t *testing.T, hs *harness, key string, data []byte, claim bool) {
	t.Helper()
	w := hs.do("POST", "/v1/uploads", map[string]any{"sha256": sum(data), "size": len(data), "kind": "original",
		"fingerprint": fingerprint.Bytes(data), "claim": claim}, bearer(key))
	if w.Code == 200 && decode(t, w)["status"] == "exists" {
		return
	}
	expect(t, w, 201, "")
	id := decode(t, w)["uploadId"].(string)
	expect(t, hs.do("PATCH", "/v1/uploads/"+id, data, bearer(key), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream")), 204, "")
}

func (hs *harness) drain() {
	hs.t.Helper()
	hs.srv.runBackupJobs(hs.t.Context())
}

func TestBackupRootsValidationAndOwnership(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	other := hs.phone("Tablet", "hoard")

	// The server announces the feature and its limits.
	info := decode(t, hs.do("GET", "/v1/server", nil, bearer(pixel.key)))
	b := info["backup"].(map[string]any)
	if b["maxRoots"] != float64(64) || b["maxReportEntries"] != float64(1000) || b["maxVerify"] != float64(500) || b["maxPath"] != float64(1024) {
		t.Fatalf("limits %v", b)
	}

	// Validation of roots.
	for _, body := range []map[string]any{
		{"volume": "Primary", "path": "DCIM"}, {"volume": "../x", "path": "DCIM"}, {"volume": "primary"},
		{"volume": "primary", "path": "/DCIM"}, {"volume": "primary", "path": "DCIM/../.."}, {"volume": "primary", "path": ".thumbnails"},
		{"volume": "primary", "path": `DCIM\Camera`}, {"volume": "primary", "path": "DCIM\x00"}, {"volume": "primary", "path": "DCIM/"},
		{"volume": "primary", "path": "DCIM", "types": []string{}}, {"volume": "primary", "path": "DCIM", "types": []string{"image", "exe"}},
		{"volume": "primary", "path": "DCIM", "types": []string{"image", "image"}},
	} {
		expect(t, hs.do("POST", "/v1/backup/roots", body, bearer(pixel.key)), 422, "invalid_request")
	}
	cam := hs.addRoot(pixel, "primary", "DCIM/Camera", 201)
	camID := cam["id"].(string)
	if cam["mine"] != true || cam["active"] != true || !strings.HasPrefix(cam["folder"].(string), "Pixel 8 (") || cam["app"] != "hoard" ||
		len(cam["types"].([]any)) != 2 {
		t.Fatalf("root %v", cam)
	}
	if fi, err := os.Stat(hs.deviceDir(pixel.physical)); err != nil || !fi.IsDir() {
		t.Fatal("no device folder")
	}
	// Again: the same root (200); a path inside it: 409 covered; types change.
	if again := hs.addRoot(pixel, "primary", "DCIM/Camera", 200); again["id"] != camID {
		t.Fatalf("%v", again)
	}
	w := hs.do("POST", "/v1/backup/roots", map[string]any{"volume": "primary", "path": "DCIM/Camera/Sub"}, bearer(pixel.key))
	expect(t, w, 409, "conflict")
	if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["reason"] != "covered" || d["rootId"] != camID {
		t.Fatalf("%v", d)
	}
	w = hs.do("PATCH", "/v1/backup/roots/"+camID, map[string]any{"types": []string{"sidecar", "image", "video", "raw"}}, bearer(pixel.key))
	expect(t, w, 200, "")
	if ty := decode(t, w)["types"].([]any); len(ty) != 4 || ty[0] != "image" || ty[3] != "sidecar" {
		t.Fatalf("types %v", ty)
	}
	// An SD card's root.
	sd := hs.addRoot(pixel, "1234-abcd", "DCIM", 201)
	if sd["volume"] != "1234-ABCD" {
		t.Fatalf("%v", sd)
	}

	// Another phone's root is 404 on every route that names it (no 403: nothing is learnt through writes).
	expect(t, hs.do("PATCH", "/v1/backup/roots/"+camID, map[string]any{"paused": true}, bearer(other.key)), 404, "not_found")
	expect(t, hs.do("DELETE", "/v1/backup/roots/"+camID, nil, bearer(other.key)), 404, "not_found")
	data := shot(1)
	expect(t, hs.do("POST", "/v1/backup/report", map[string]any{"rootId": camID, "entries": []any{fileEntry("DCIM/Camera/a.jpg", data, true)}},
		bearer(other.key)), 404, "not_found")
	expect(t, hs.do("POST", "/v1/backup/verify", map[string]any{"entries": []any{map[string]any{"rootId": camID, "path": "DCIM/Camera/a.jpg",
		"sha256": sum(data), "size": len(data)}}}, bearer(other.key)), 404, "not_found")
	expect(t, hs.do("PATCH", "/v1/backup/roots/0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f", map[string]any{"paused": true}, bearer(pixel.key)), 404, "not_found")
	// Reads of other phones' roots go through the list only.
	var mine, theirs int
	for _, r := range decode(t, hs.do("GET", "/v1/backup/roots", nil, bearer(other.key)))["roots"].([]any) {
		if r.(map[string]any)["mine"] == true {
			mine++
		} else {
			theirs++
		}
	}
	if mine != 0 || theirs != 2 {
		t.Fatalf("mine %d theirs %d", mine, theirs)
	}

	// Paths of reports and removals: validated, and inside the root (relative to the volume).
	for _, p := range []string{"DCIM/Camera/../../etc/passwd", "/DCIM/Camera/a.jpg", "DCIM/Camera/.hidden.jpg", "DCIM/Other/a.jpg",
		"DCIM/Camera", "DCIM/Camera/a\x00.jpg", `DCIM/Camera\a.jpg`, "DCIM/Camera/" + strings.Repeat("x", 300) + ".jpg", "DCIM/Camera/a.txt",
		"DCIM/Camera/Café.jpg"} {
		w := hs.do("POST", "/v1/backup/report", map[string]any{"rootId": camID, "entries": []any{fileEntry("DCIM/Camera/ok.jpg", data, false),
			fileEntry(p, data, false)}}, bearer(pixel.key))
		expect(t, w, 422, "invalid_request")
		if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["index"] != float64(1) || d["field"] != "entries" {
			t.Fatalf("%q: %v", p, d)
		}
		w = hs.do("POST", "/v1/backup/report", map[string]any{"rootId": camID, "removed": []any{map[string]any{"path": p, "reason": "deleted"}}}, bearer(pixel.key))
		if p != "DCIM/Camera/a.txt" { // a removal may name any file
			expect(t, w, 422, "invalid_request")
		}
	}
	for _, bad := range []map[string]any{
		{"path": "DCIM/Camera/a.jpg", "fingerprint": "v1:x", "size": 1, "modifiedAt": 1},
		{"path": "DCIM/Camera/a.jpg", "fingerprint": fingerprint.Bytes(data), "size": 0, "modifiedAt": 1},
		{"path": "DCIM/Camera/a.jpg", "fingerprint": fingerprint.Bytes(data), "size": 1},
		{"path": "DCIM/Camera/a.jpg", "fingerprint": fingerprint.Bytes(data), "size": 1, "modifiedAt": 1, "sha256": "XY"},
	} {
		expect(t, hs.do("POST", "/v1/backup/report", map[string]any{"rootId": camID, "entries": []any{bad}}, bearer(pixel.key)), 422, "invalid_request")
	}
	expect(t, hs.do("POST", "/v1/backup/report", map[string]any{"rootId": camID, "removed": []any{map[string]any{"path": "DCIM/Camera/a.jpg", "reason": "gone"}}},
		bearer(pixel.key)), 422, "invalid_request")
	if n, _ := hs.st.MirrorEntriesOfRoot(t.Context(), camID); len(n) != 0 {
		t.Fatal("a refused report applied something")
	}
	// Verify paths too.
	expect(t, hs.do("POST", "/v1/backup/verify", map[string]any{"entries": []any{map[string]any{"rootId": camID, "path": "DCIM/Camera/../x.jpg",
		"sha256": sum(data), "size": 1}}}, bearer(pixel.key)), 422, "invalid_request")

	// GET /v1/device: the caller's phone's roots, with types.
	dv := decode(t, hs.do("GET", "/v1/device", nil, bearer(pixel.key)))
	roots := dv["physicalDevice"].(map[string]any)["backup"].(map[string]any)["roots"].([]any)
	if len(roots) != 2 {
		t.Fatalf("%v", roots)
	}
	r0 := roots[0].(map[string]any)
	if r0["id"] != camID {
		r0 = roots[1].(map[string]any)
	}
	if r0["id"] != camID || r0["active"] != true || r0["since"] == nil || len(r0["types"].([]any)) != 4 || r0["app"] != "hoard" {
		t.Fatalf("%v", r0)
	}
	if ov := decode(t, hs.do("GET", "/v1/device", nil, bearer(other.key)))["physicalDevice"].(map[string]any)["backup"].(map[string]any)["roots"].([]any); len(ov) != 0 {
		t.Fatalf("%v", ov)
	}

	// Pause, stop (no device deletes a backup), resume by adding it again.
	expect(t, hs.do("PATCH", "/v1/backup/roots/"+camID, map[string]any{"paused": true}, bearer(pixel.key)), 200, "")
	expect(t, hs.do("DELETE", "/v1/backup/roots/"+camID, nil, bearer(pixel.key)), 200, "")
	w = hs.do("POST", "/v1/backup/report", map[string]any{"rootId": camID, "entries": []any{fileEntry("DCIM/Camera/a.jpg", data, false)}}, bearer(pixel.key))
	expect(t, w, 409, "conflict")
	if reason(t, w) != "stopped" {
		t.Fatal(w.Body.String())
	}
	if r := hs.addRoot(pixel, "primary", "DCIM/Camera", 200); r["stopped"] != false || r["paused"] != false {
		t.Fatalf("%v", r)
	}

	// A root containing others absorbs them.
	sub := hs.addRoot(pixel, "1234-ABCD", "DCIM/Sub", 409) // covered by the SD card's DCIM
	_ = sub
	whole := hs.addRoot(pixel, "1234-ABCD", "", 201)
	if abs := whole["details"].(map[string]any)["absorbed"].([]any); len(abs) != 1 || abs[0] != sd["id"] {
		t.Fatalf("%v", whole)
	}

	// Without a backup root: 503.
	hs2 := newHarness(t, false)
	p2 := hs2.phone("Pixel", "hoard")
	expect(t, hs2.do("POST", "/v1/backup/roots", map[string]any{"volume": "primary", "path": "DCIM"}, bearer(p2.key)), 503, "storage_not_configured")
}

func TestBackupReportStoresLinksAndWaits(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	reveal := hs.phone("Pixel 8", "reveal")
	tablet := hs.phone("Tablet", "hoard")
	root := hs.addRoot(pixel, "primary", "DCIM/Camera", 201)["id"].(string)

	byReveal, onNas, fresh, byTablet := shot(1), shot(2), shot(3), shot(4)
	uploadFor(t, hs, reveal.key, byReveal, true) // Reveal backed it up already
	if err := os.WriteFile(filepath.Join(hs.nas, "nas.jpg"), onNas, 0o600); err != nil {
		t.Fatal(err)
	}
	expect(t, hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders()), 201, "")
	hs.walkAll()

	out := hs.report(pixel, root, []map[string]any{
		fileEntry("DCIM/Camera/reveal.jpg", byReveal, false), fileEntry("DCIM/Camera/nas.jpg", onNas, true),
		fileEntry("DCIM/Camera/fresh.jpg", fresh, true), fileEntry("DCIM/Camera/tablet.jpg", byTablet, true),
	}, nil)
	if got := strings.Join(states(out), ","); got != "stored,nas,missing,missing" {
		t.Fatalf("states %s", got)
	}
	// Reveal's blob is linked into the tree, read-only; a NAS file has no file in the tree.
	e := hs.entryAt(pixel, "DCIM/Camera/reveal.jpg")
	if e.Store != store.StoreBlob || !e.Linked || !hs.linkedTo(pixel.physical, "DCIM/Camera/reveal.jpg", sum(byReveal)) {
		t.Fatalf("%+v", e)
	}
	if fi, _ := os.Stat(hs.blobFile(sum(byReveal))); fi.Mode().Perm() != 0o400 {
		t.Fatalf("blob mode %v", fi.Mode().Perm())
	}
	if _, err := os.Stat(filepath.Join(hs.deviceDir(pixel.physical), "DCIM/Camera/nas.jpg")); !os.IsNotExist(err) {
		t.Fatal("a NAS reference has a file in the tree")
	}
	// The mirror's upload adds no claim; Reveal's keeps claiming.
	uploadFor(t, hs, pixel.key, fresh, false)
	if c, _ := hs.st.Claims(t.Context(), fingerprint.Bytes(fresh)); len(c) != 0 {
		t.Fatalf("claims %v", c)
	}
	if c, _ := hs.st.Claims(t.Context(), fingerprint.Bytes(byReveal)); len(c) != 1 || c[0] != reveal.id {
		t.Fatalf("claims %v", c)
	}
	if e := hs.entryAt(pixel, "DCIM/Camera/fresh.jpg"); e.Store != store.StoreBlob || !hs.linkedTo(pixel.physical, "DCIM/Camera/fresh.jpg", sum(fresh)) {
		t.Fatalf("waiting entry not linked on completion: %+v", e)
	}
	// Another device's upload of the same content links the waiting entry too.
	uploadFor(t, hs, tablet.key, byTablet, true)
	if !hs.linkedTo(pixel.physical, "DCIM/Camera/tablet.jpg", sum(byTablet)) {
		t.Fatal("not linked after another device's upload")
	}
	again := hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Camera/fresh.jpg", fresh, true), fileEntry("DCIM/Camera/tablet.jpg", byTablet, true)}, nil)
	if got := strings.Join(states(again), ","); got != "stored,stored" {
		t.Fatalf("states %s", got)
	}

	// in_progress: another device's upload of this content had a chunk recently.
	busy := shot(5)
	w := hs.do("POST", "/v1/uploads", map[string]any{"sha256": sum(busy), "size": len(busy), "kind": "original", "fingerprint": fingerprint.Bytes(busy)}, bearer(tablet.key))
	expect(t, w, 201, "")
	r := hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Camera/busy.jpg", busy, true)}, nil)
	res := r["results"].([]any)[0].(map[string]any)
	if res["state"] != "in_progress" || res["retryAfter"] != float64(300) {
		t.Fatalf("%v", res)
	}
	// differs: the server has this fingerprint with other bytes.
	r = hs.report(pixel, root, []map[string]any{{"path": "DCIM/Camera/collide.jpg", "fingerprint": fingerprint.Bytes(byReveal), "size": len(byReveal),
		"modifiedAt": 1, "sha256": sum([]byte("other bytes"))}}, nil)
	if states(r)[0] != "differs" {
		t.Fatalf("%v", r)
	}

	// The manifest lists every entry, NAS references included.
	hs.drain()
	m, err := os.ReadFile(filepath.Join(hs.deviceDir(pixel.physical), ".hoard-mirror", "manifest-"+root+".tsv"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(m), "DCIM/Camera/nas.jpg\t") || !strings.Contains(string(m), "\tnas:nas/nas.jpg\t") ||
		!strings.Contains(string(m), "DCIM/Camera/reveal.jpg\t") {
		t.Fatalf("%s", m)
	}
}

func TestBackupLinkFallbackNeverCopies(t *testing.T) {
	hs := newHarnessWith(t, true, func(c *Config) {
		c.MirrorLinker = func(_ *os.Root, oldname, newname string) error {
			return &os.LinkError{Op: "link", Old: oldname, New: newname, Err: syscall.EXDEV}
		}
	})
	pixel := hs.phone("Pixel 8", "hoard")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	data := shot(1)
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/a.jpg", data, true)}, nil)
	uploadFor(t, hs, pixel.key, data, false)
	e := hs.entryAt(pixel, "DCIM/a.jpg")
	if e.Store != store.StoreBlob || e.Linked || e.TreePath != "" {
		t.Fatalf("%+v", e)
	}
	if _, err := os.Stat(filepath.Join(hs.deviceDir(pixel.physical), "DCIM/a.jpg")); !os.IsNotExist(err) {
		t.Fatal("the server copied the file")
	}
	if states(hs.report(pixel, root, []map[string]any{fileEntry("DCIM/a.jpg", data, true)}, nil))[0] != "stored" {
		t.Fatal("not stored")
	}
	hs.drain()
	m, _ := os.ReadFile(filepath.Join(hs.deviceDir(pixel.physical), ".hoard-mirror", "manifest-"+root+".tsv"))
	if !strings.Contains(string(m), "DCIM/a.jpg\t") || !strings.Contains(string(m), "\tblob-unlinked\t") {
		t.Fatalf("%s", m)
	}
}

func TestBackupMoveRenameRewriteDeleteRestore(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	a, b, a2 := shot(1), shot(2), shot(3)
	for _, d := range [][]byte{a, b, a2} {
		uploadFor(t, hs, pixel.key, d, false)
	}
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Camera/a.jpg", a, true), fileEntry("DCIM/Camera/b.jpg", b, true)}, nil)
	ph := pixel.physical

	// A rename reported as removal + new path (in any order): the entry moves, nothing is uploaded.
	r := hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Renamed/a.jpg", a, true)}, []map[string]any{{"path": "DCIM/Camera/a.jpg", "reason": "moved"}})
	if states(r)[0] != "stored" || r["removed"].([]any)[0].(map[string]any)["state"] != "moved" {
		t.Fatalf("%v", r)
	}
	if hs.linkedTo(ph, "DCIM/Camera/a.jpg", sum(a)) || !hs.linkedTo(ph, "DCIM/Renamed/a.jpg", sum(a)) {
		t.Fatal("the link did not move")
	}
	// A move inferred: a deletion of content present at another current path.
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Moved/b.jpg", b, true)}, nil)
	r = hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/Camera/b.jpg", "reason": "deleted"}})
	if r["removed"].([]any)[0].(map[string]any)["state"] != "moved" || hs.linkedTo(ph, "DCIM/Camera/b.jpg", sum(b)) {
		t.Fatalf("%v", r)
	}
	// The other order: a moved removal first; it settles as "deleted" (kept) after 24 h without its new path.
	c := shot(9)
	uploadFor(t, hs, pixel.key, c, false)
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/c.jpg", c, true)}, nil)
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/c.jpg", "reason": "moved"}})
	now := time.Now()
	hs.st.Now = func() time.Time { return now.Add(25 * time.Hour) }
	hs.srv.settleMoves(t.Context())
	hs.st.Now = time.Now
	if e := hs.entryAt(pixel, "DCIM/c.jpg"); e.RemovedReason != store.RemovedDeleted || e.OnDevice || !hs.linkedTo(ph, "DCIM/c.jpg", sum(c)) {
		t.Fatalf("%+v", e)
	}

	// Rewritten in place: the old content becomes a previous version, linked under .hoard-mirror/previous.
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Renamed/a.jpg", a2, true)}, nil)
	if !hs.linkedTo(ph, "DCIM/Renamed/a.jpg", sum(a2)) || !hs.linkedTo(ph, ".hoard-mirror/previous/DCIM/Renamed/a.jpg~"+sum(a)[:8], sum(a)) {
		t.Fatal("rewrite")
	}
	es, _ := hs.st.MirrorEntriesOfRoot(t.Context(), root)
	replaced := 0
	for _, e := range es {
		if e.RemovedReason == store.RemovedReplaced && e.Fingerprint == fingerprint.Bytes(a) {
			replaced++
		}
	}
	if replaced != 1 {
		t.Fatalf("%+v", es)
	}

	// Deleted on the phone: kept, in the tree and the index (owner's decision 1: forever).
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/Renamed/a.jpg", "reason": "deleted"}})
	e := hs.entryAt(pixel, "DCIM/Renamed/a.jpg")
	if e.OnDevice || e.RemovedReason != store.RemovedDeleted || !hs.linkedTo(ph, "DCIM/Renamed/a.jpg", sum(a2)) {
		t.Fatalf("%+v", e)
	}
	// Restored: the same content at the same path is current again, no upload.
	r = hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Renamed/a.jpg", a2, true)}, nil)
	if e := hs.entryAt(pixel, "DCIM/Renamed/a.jpg"); !e.OnDevice || e.RemovedReason != "" || states(r)[0] != "stored" {
		t.Fatalf("%+v", e)
	}
	// Freed: pinned; a later "deleted" keeps it freed.
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/Moved/b.jpg", "reason": "freed"}})
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/Moved/b.jpg", "reason": "deleted"}})
	if e := hs.entryAt(pixel, "DCIM/Moved/b.jpg"); e.RemovedReason != store.RemovedFreed || !hs.linkedTo(ph, "DCIM/Moved/b.jpg", sum(b)) {
		t.Fatalf("%+v", e)
	}
	// An unknown path is ignored.
	if st := hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/none.jpg", "reason": "deleted"}})["removed"].([]any)[0].(map[string]any)["state"]; st != "unknown" {
		t.Fatal(st)
	}
	// Restored at another path: a current entry again (no upload), the freed entry stays pinned.
	if states(hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Restored/b.jpg", b, true)}, nil))[0] != "stored" {
		t.Fatal("restore")
	}
}

func TestUploadWaitLiveAndStalled(t *testing.T) {
	hs := newHarness(t, true)
	reveal := hs.phone("Pixel 8", "reveal")
	hoard := hs.phone("Pixel 8", "hoard")
	data := shot(1)
	req := map[string]any{"sha256": sum(data), "size": len(data), "kind": "original", "fingerprint": fingerprint.Bytes(data)}
	w := hs.do("POST", "/v1/uploads", req, bearer(reveal.key))
	expect(t, w, 201, "")
	revealUpload := decode(t, w)["uploadId"].(string)
	expect(t, hs.do("PATCH", "/v1/uploads/"+revealUpload, data[:10], bearer(reveal.key), header("Upload-Offset", "0"),
		header("Content-Type", "application/offset+octet-stream")), 204, "")

	// Without wait nothing changes: a second, parallel upload.
	w = hs.do("POST", "/v1/uploads", req, bearer(hoard.key))
	expect(t, w, 201, "")
	expect(t, hs.do("DELETE", "/v1/uploads/"+decode(t, w)["uploadId"].(string), nil, bearer(hoard.key)), 204, "")

	// With wait: in_progress while the other upload is live.
	wait := map[string]any{"sha256": sum(data), "size": len(data), "kind": "original", "fingerprint": fingerprint.Bytes(data), "wait": true, "claim": false}
	w = hs.do("POST", "/v1/uploads", wait, bearer(hoard.key))
	expect(t, w, 200, "")
	if m := decode(t, w); m["status"] != "in_progress" || m["retryAfter"] != float64(300) || w.Header().Get("Retry-After") != "300" {
		t.Fatalf("%v %v", m, w.Header())
	}
	// The uploader itself resumes, wait or not.
	w = hs.do("POST", "/v1/uploads", map[string]any{"sha256": sum(data), "size": len(data), "kind": "original", "fingerprint": fingerprint.Bytes(data), "wait": true}, bearer(reveal.key))
	if expect(t, w, 200, ""); decode(t, w)["status"] != "resume" {
		t.Fatal(w.Body.String())
	}
	// Stalled (no chunk for 10 minutes): nobody waits.
	now := time.Now()
	hs.st.Now = func() time.Time { return now.Add(11 * time.Minute) }
	w = hs.do("POST", "/v1/uploads", wait, bearer(hoard.key))
	expect(t, w, 201, "")
	hs.st.Now = time.Now
	if u, _ := hs.st.UploadUnclaimed(t.Context(), decode(t, w)["uploadId"].(string)); !u {
		t.Fatal("claim false not recorded")
	}
}

func TestBackupVerify(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	reveal := hs.phone("Pixel 8", "reveal")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	good, corrupt, onNas, waiting := shot(1), shot(2), shot(3), shot(4)
	uploadFor(t, hs, pixel.key, good, false)
	uploadFor(t, hs, pixel.key, corrupt, false)
	if err := os.WriteFile(filepath.Join(hs.nas, "n.jpg"), onNas, 0o600); err != nil {
		t.Fatal(err)
	}
	expect(t, hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders()), 201, "")
	hs.walkAll()
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/good.jpg", good, true), fileEntry("DCIM/corrupt.jpg", corrupt, true),
		fileEntry("DCIM/nas.jpg", onNas, true), fileEntry("DCIM/waiting.jpg", waiting, true)}, nil)
	// The stored copy of one file is damaged on disk.
	bp := hs.blobFile(sum(corrupt))
	_ = os.Chmod(bp, 0o600)
	if err := os.WriteFile(bp, append([]byte("x"), corrupt[1:]...), 0o600); err != nil {
		t.Fatal(err)
	}
	// A Reveal photo record names one file, a version record another (namedByRecord).
	expect(t, hs.do("POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "photo", "id": "11111111-1111-4111-8111-111111111111", "baseRev": 0, "modifiedAt": 1,
			"data": map[string]any{"fingerprint": fingerprint.Bytes(good)}},
		map[string]any{"kind": "version", "id": "22222222-2222-4222-8222-222222222222", "baseRev": 0, "modifiedAt": 1,
			"data": map[string]any{"photoId": "11111111-1111-4111-8111-111111111111", "photoFingerprint": fingerprint.Bytes(onNas)}},
	}}, bearer(reveal.key)), 200, "")

	ask := func(entries ...map[string]any) []map[string]any {
		t.Helper()
		w := hs.do("POST", "/v1/backup/verify", map[string]any{"entries": entries}, bearer(pixel.key))
		expect(t, w, 200, "")
		var out []map[string]any
		for _, r := range decode(t, w)["results"].([]any) {
			out = append(out, r.(map[string]any))
		}
		return out
	}
	v := func(p string, data []byte) map[string]any {
		return map[string]any{"rootId": root, "path": p, "sha256": sum(data), "size": len(data)}
	}
	all := []map[string]any{v("DCIM/good.jpg", good), v("DCIM/corrupt.jpg", corrupt), v("DCIM/nas.jpg", onNas), v("DCIM/waiting.jpg", waiting),
		v("DCIM/none.jpg", good), v("DCIM/good.jpg", corrupt)}
	first := ask(all...)
	for i, want := range []string{"pending", "pending", "pending", "no", "no", "no"} {
		if first[i]["verified"] != want {
			t.Fatalf("%d: %v", i, first[i])
		}
	}
	if first[3]["reason"] != "missing" || first[4]["reason"] != "not_found" || first[5]["reason"] != "differs" {
		t.Fatalf("%v", first)
	}
	if first[0]["namedByRecord"] != true || first[2]["namedByRecord"] != true || first[1]["namedByRecord"] != false {
		t.Fatalf("namedByRecord %v", first)
	}
	hs.drain()
	second := ask(all[:3]...)
	if second[0]["verified"] != "yes" || second[0]["verifiedAt"] == nil || second[1]["verified"] != "no" || second[1]["reason"] != "differs" {
		t.Fatalf("%v", second)
	}
	// The NAS-only file: the server made its own copy (setting on by default), checked, linked.
	if second[2]["verified"] != "yes" {
		t.Fatalf("%v", second[2])
	}
	if e := hs.entryAt(pixel, "DCIM/nas.jpg"); e.Store != store.StoreBlob || !hs.linkedTo(pixel.physical, "DCIM/nas.jpg", sum(onNas)) {
		t.Fatalf("%+v", e)
	}

	// With the setting off, a NAS file is hashed where it is; a NAS file that changed is not verified.
	expect(t, hs.do("PUT", "/admin/api/backup/settings", map[string]any{"keepNasCopies": false}, adminHeaders()), 200, "")
	other := shot(7)
	if err := os.WriteFile(filepath.Join(hs.nas, "o.jpg"), other, 0o600); err != nil {
		t.Fatal(err)
	}
	hs.walkAll()
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/o.jpg", other, true)}, nil)
	if r := ask(v("DCIM/o.jpg", other)); r[0]["verified"] != "pending" {
		t.Fatalf("%v", r)
	}
	hs.drain()
	if r := ask(v("DCIM/o.jpg", other)); r[0]["verified"] != "yes" {
		t.Fatalf("%v", r)
	}
	if e := hs.entryAt(pixel, "DCIM/o.jpg"); e.Store != store.StoreNAS {
		t.Fatalf("copied with the setting off: %+v", e)
	}
	later := time.Now().Add(time.Hour)
	if err := os.Chtimes(filepath.Join(hs.nas, "o.jpg"), later, later); err != nil {
		t.Fatal(err)
	}
	hs.st.Now = func() time.Time { return time.Now().Add(31 * 24 * time.Hour) } // the last hash is too old
	ask(v("DCIM/o.jpg", other))
	hs.drain()
	hs.st.Now = time.Now
	r := ask(v("DCIM/o.jpg", other))
	if r[0]["verified"] != "no" || r[0]["reason"] != "changed_on_nas" {
		t.Fatalf("%v", r)
	}
}

func TestBackupOwnership(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	reveal := hs.phone("Pixel 8", "reveal")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	claimed, recorded, mirrored := shot(1), shot(2), shot(3)
	uploadFor(t, hs, reveal.key, claimed, true)
	uploadFor(t, hs, pixel.key, recorded, false)
	uploadFor(t, hs, pixel.key, mirrored, false)
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/c.jpg", claimed, true), fileEntry("DCIM/r.jpg", recorded, true),
		fileEntry("DCIM/m.jpg", mirrored, true)}, []map[string]any{})
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/m.jpg", "reason": "freed"}})

	// A Reveal delete never removes the phone's backup: 409 reason mirror, its claim is released.
	w := hs.do("DELETE", "/v1/originals/"+fingerprint.Bytes(claimed), nil, bearer(reveal.key))
	expect(t, w, 409, "conflict")
	if reason(t, w) != "mirror" {
		t.Fatal(w.Body.String())
	}
	if c, _ := hs.st.Claims(t.Context(), fingerprint.Bytes(claimed)); len(c) != 0 {
		t.Fatalf("claim kept %v", c)
	}
	expect(t, hs.do("GET", "/v1/originals/"+fingerprint.Bytes(claimed), nil, bearer(reveal.key)), 200, "")
	// Reveal claims it again (an upload of the same bytes registers it).
	uploadFor(t, hs, reveal.key, claimed, true)
	// A live photo record names another.
	expect(t, hs.do("POST", "/v1/changes", map[string]any{"changes": []any{map[string]any{"kind": "photo", "id": "11111111-1111-4111-8111-111111111111",
		"baseRev": 0, "modifiedAt": 1, "data": map[string]any{"fingerprint": fingerprint.Bytes(recorded)}}}}, bearer(reveal.key)), 200, "")

	// The admin's purge: pinned entries must be confirmed; claimed and recorded contents stay, the rest goes.
	w = hs.do("GET", "/admin/api/backup", nil, adminHeaders())
	expect(t, w, 200, "")
	phones := decode(t, w)["phones"].([]any)
	if len(phones) != 1 || phones[0].(map[string]any)["usage"].(map[string]any)["pinned"] != float64(1) {
		t.Fatalf("%v", phones)
	}
	purge := "/admin/api/backup/" + pixel.physical + "/purge"
	expect(t, hs.do("POST", purge, map[string]any{"confirmPinned": 0}, adminHeaders()), 409, "conflict")
	w = hs.do("POST", purge, map[string]any{"confirmPinned": 1}, adminHeaders())
	expect(t, w, 200, "")
	for _, c := range []struct {
		data []byte
		kept bool
	}{{claimed, true}, {recorded, true}, {mirrored, false}} {
		_, err := os.Stat(hs.blobFile(sum(c.data)))
		if c.kept != (err == nil) {
			t.Fatalf("kept %v: %v", c.kept, err)
		}
	}
	if _, err := os.Stat(filepath.Join(hs.backup)); err != nil {
		t.Fatal(err)
	}
	if ds, _ := hs.st.MirrorDevices(t.Context()); len(ds) != 0 {
		t.Fatal("device row left")
	}
	// No device key can delete a backup: nothing under /v1 removes entries (only removal reports, which keep them).
	expect(t, hs.do("DELETE", "/v1/backup/roots/"+root, nil, bearer(pixel.key)), 404, "not_found")
}

func TestBackupRevokeAndClaimMerge(t *testing.T) {
	hs := newHarness(t, true)
	old := hs.phone("Pixel 8", "hoard")
	root := hs.addRoot(old, "primary", "DCIM", 201)["id"].(string)
	a, b, bNew := shot(1), shot(2), shot(3)
	for _, d := range [][]byte{a, b, bNew} {
		uploadFor(t, hs, old.key, d, false)
	}
	hs.report(old, root, []map[string]any{fileEntry("DCIM/a.jpg", a, true), fileEntry("DCIM/b.jpg", b, true)}, nil)
	oldDir := hs.deviceDir(old.physical)

	// Revoked: its next request is 401; its roots stay, readable; active turns false after 7 days without a report.
	expect(t, hs.do("POST", "/admin/api/devices/"+old.id+"/revoke", nil, adminHeaders()), 200, "")
	expect(t, hs.do("GET", "/v1/backup/roots", nil, bearer(old.key)), 401, "unauthorized")
	tablet := hs.phone("Tablet", "hoard")
	roots := decode(t, hs.do("GET", "/v1/backup/roots", nil, bearer(tablet.key)))["roots"].([]any)
	if len(roots) != 1 || roots[0].(map[string]any)["active"] != false { // no live device left on that phone
		t.Fatalf("%v", roots)
	}

	// A new Hoard on another physical device backs up the same folder, then claims the old phone: its backup moves
	// there and merges (the newer file wins a path).
	fresh := hs.phone("Pixel 8", "hoard")
	root2 := hs.addRoot(fresh, "primary", "DCIM", 201)["id"].(string)
	newer := fileEntry("DCIM/b.jpg", bNew, true)
	newer["modifiedAt"] = 1830455000000
	hs.report(fresh, root2, []map[string]any{newer}, nil)
	freshDir := hs.deviceDir(fresh.physical)
	w := hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": old.physical}, bearer(fresh.key))
	expect(t, w, 200, "")
	fresh.physical = old.physical
	if r := decode(t, hs.do("GET", "/v1/backup/roots", nil, bearer(fresh.key)))["roots"].([]any); len(r) != 1 || r[0].(map[string]any)["id"] != root ||
		r[0].(map[string]any)["mine"] != true {
		t.Fatalf("%v", r)
	}
	if !hs.linkedTo(old.physical, "DCIM/b.jpg", sum(bNew)) || !hs.linkedTo(old.physical, ".hoard-mirror/previous/DCIM/b.jpg~"+sum(b)[:8], sum(b)) ||
		!hs.linkedTo(old.physical, "DCIM/a.jpg", sum(a)) {
		t.Fatal("merge on disk")
	}
	if hs.deviceDir(old.physical) != oldDir {
		t.Fatal("the folder was renamed")
	}
	if _, err := os.Stat(freshDir); !os.IsNotExist(err) {
		t.Fatalf("the moved phone's folder is left: %v", err)
	}
	// The re-paired Hoard continues the same root: reporting what is there uploads nothing.
	out := hs.report(fresh, root, []map[string]any{fileEntry("DCIM/a.jpg", a, true), newer}, nil)
	if strings.Join(states(out), ",") != "stored,stored" {
		t.Fatalf("%v", out)
	}
	if r := decode(t, hs.do("GET", "/v1/backup/roots", nil, bearer(tablet.key)))["roots"].([]any); r[0].(map[string]any)["active"] != true {
		t.Fatalf("%v", r)
	}
	// 8 days later without a report: inactive (Reveal takes the folder back).
	now := time.Now()
	hs.st.Now = func() time.Time { return now.Add(8 * 24 * time.Hour) }
	if r := decode(t, hs.do("GET", "/v1/device", nil, bearer(fresh.key)))["physicalDevice"].(map[string]any)["backup"].(map[string]any)["roots"].([]any); r[0].(map[string]any)["active"] != false {
		t.Fatalf("%v", r)
	}
	hs.st.Now = time.Now
	// A physical device whose last device moved away keeps its row while it has a backup; here it moved, so it goes.
	if ok, _ := hs.st.PhysicalExists(context.Background(), old.physical); !ok {
		t.Fatal("physical device gone")
	}
}

func TestBackupNasCopyWhenThePhoneFileGoes(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	a, b, c := shot(1), shot(2), shot(3)
	for n, d := range map[string][]byte{"a.jpg": a, "b.jpg": b, "c.jpg": c} {
		if err := os.WriteFile(filepath.Join(hs.nas, n), d, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	expect(t, hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders()), 201, "")
	hs.walkAll()
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/a.jpg", a, true), fileEntry("DCIM/b.jpg", b, true), fileEntry("DCIM/c.jpg", c, true)}, nil)

	// A deletion on the phone makes the NAS file the only copy: the server copies it into the store.
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/a.jpg", "reason": "deleted"}})
	hs.drain()
	if e := hs.entryAt(pixel, "DCIM/a.jpg"); e.Store != store.StoreBlob || !hs.linkedTo(pixel.physical, "DCIM/a.jpg", sum(a)) {
		t.Fatalf("%+v", e)
	}
	if c, _ := hs.st.Claims(t.Context(), fingerprint.Bytes(a)); len(c) != 0 {
		t.Fatal("the server's copy is nobody's claim")
	}

	// The walk is the backstop: with the setting off at the time, a file moved to the NAS trash is copied from there
	// once the setting is on.
	expect(t, hs.do("PUT", "/admin/api/backup/settings", map[string]any{"keepNasCopies": false}, adminHeaders()), 200, "")
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/b.jpg", "reason": "deleted"}})
	hs.drain()
	if e := hs.entryAt(pixel, "DCIM/b.jpg"); e.Store != store.StoreNAS {
		t.Fatalf("%+v", e)
	}
	hs.move("b.jpg", trashDay+"/b.jpg")
	expect(t, hs.do("PUT", "/admin/api/backup/settings", map[string]any{"keepNasCopies": true}, adminHeaders()), 200, "")
	hs.srv.IndexNow(t.Context())
	hs.drain()
	if e := hs.entryAt(pixel, "DCIM/b.jpg"); e.Store != store.StoreBlob {
		t.Fatalf("not copied out of the trash: %+v", e)
	}
	// A NAS file deleted outright before any copy: the entry has no copy left, and the admin page says so.
	expect(t, hs.do("PUT", "/admin/api/backup/settings", map[string]any{"keepNasCopies": false}, adminHeaders()), 200, "")
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/c.jpg", "reason": "deleted"}})
	if err := os.Remove(filepath.Join(hs.nas, "c.jpg")); err != nil {
		t.Fatal(err)
	}
	hs.srv.IndexNow(t.Context())
	if e := hs.entryAt(pixel, "DCIM/c.jpg"); e.Store != store.StoreNone || e.LostAt == nil {
		t.Fatalf("%+v", e)
	}
	st := decode(t, hs.do("GET", "/admin/api/backup", nil, adminHeaders()))
	if st["lost"] != float64(1) || st["keepNasCopies"] != false {
		t.Fatalf("%v", st)
	}
	// While the phone still has the file, a NAS file that goes makes the entry "none": the next report asks for it.
	d := shot(4)
	if err := os.WriteFile(filepath.Join(hs.nas, "d.jpg"), d, 0o600); err != nil {
		t.Fatal(err)
	}
	hs.walkAll()
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/d.jpg", d, true)}, nil)
	_ = os.Remove(filepath.Join(hs.nas, "d.jpg"))
	hs.srv.IndexNow(t.Context())
	if states(hs.report(pixel, root, []map[string]any{fileEntry("DCIM/d.jpg", d, true)}, nil))[0] != "missing" {
		t.Fatal("not missing")
	}
}

func TestBackupMirrorItems(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	reveal := hs.phone("Pixel 8", "reveal")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	jpg, dng, freed, gone := shot(1), mediatest.RAW(mediatest.Shot{Make: "Google", Model: "Pixel 8", DateTime: "2026:11:01 10:15:00", Width: 40, Height: 30, Unique: 2}),
		shot(3), shot(4)
	for _, d := range [][]byte{jpg, dng, freed, gone} {
		uploadFor(t, hs, pixel.key, d, false)
	}
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Camera/PXL_1.jpg", jpg, true), fileEntry("DCIM/Camera/PXL_1.dng", dng, true),
		fileEntry("DCIM/Camera/freed.jpg", freed, true), fileEntry("DCIM/Other/gone.jpg", gone, true)}, nil)
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/Camera/freed.jpg", "reason": "freed"}, {"path": "DCIM/Other/gone.jpg", "reason": "deleted"}})
	hs.srv.IndexNow(t.Context())

	list := func(q string) []map[string]any {
		t.Helper()
		var out []map[string]any
		for _, it := range page(t, hs, reveal.key, "/v1/items?limit=50&folder="+root+q).Items {
			out = append(out, it)
		}
		return out
	}
	all := list("")
	if len(all) != 3 {
		t.Fatalf("%d items: %v", len(all), all)
	}
	camera := list("&path=Camera")
	if len(camera) != 2 {
		t.Fatalf("%v", camera)
	}
	byName := map[string]map[string]any{}
	for _, it := range all {
		f := it["files"].([]any)[0].(map[string]any)
		byName[f["name"].(string)] = it
	}
	pair := byName["PXL_1.dng"]
	if pair == nil || pair["kind"] != "pair" || len(pair["files"].([]any)) != 2 {
		t.Fatalf("pair %v", byName)
	}
	locs := func(it map[string]any) []map[string]any {
		var out []map[string]any
		for _, l := range it["files"].([]any)[0].(map[string]any)["locations"].([]any) {
			out = append(out, l.(map[string]any))
		}
		return out
	}
	mirrorLoc := func(it map[string]any) map[string]any {
		for _, l := range locs(it) {
			if l["source"] == "mirror" {
				return l
			}
		}
		t.Fatalf("no mirror location: %v", it)
		return nil
	}
	m := mirrorLoc(pair)
	if m["physicalDeviceId"] != pixel.physical || m["deviceName"] != "Pixel 8" || m["rootId"] != root || m["volume"] != "primary" ||
		m["path"] != "DCIM/Camera/PXL_1.dng" || m["onDevice"] != true || m["gone"] != nil || m["modifiedAt"] != float64(1730455000000) {
		t.Fatalf("%v", m)
	}
	// Next to the backup location that holds the bytes (an upload joins the mirror item, not an item of its own).
	hasBackup := false
	for _, l := range locs(pair) {
		hasBackup = hasBackup || l["source"] == "backup" && l["sha256"] == sum(dng)
	}
	if !hasBackup {
		t.Fatalf("%v", locs(pair))
	}
	if g := mirrorLoc(byName["freed.jpg"]); g["onDevice"] != false || g["gone"] != "freed" {
		t.Fatalf("%v", g)
	}
	if g := mirrorLoc(byName["gone.jpg"]); g["onDevice"] != false || g["gone"] != "deleted" {
		t.Fatalf("%v", g)
	}
	// One item per shot across the library: the uploads did not make items of their own.
	total := page(t, hs, reveal.key, "/v1/items?limit=50").Items
	if len(total) != 3 {
		t.Fatalf("%d items in the library", len(total))
	}
	// A NAS folder id still works the old way; an unknown id lists nothing.
	if n := len(page(t, hs, reveal.key, "/v1/items?folder=0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f").Items); n != 0 {
		t.Fatal(n)
	}
}
