package server

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The trash from the web gallery (ADR 0023 Amendment (0.14.0)). Synthetic files only; the write mount is the test's
// NAS directory itself (the same directory, as the deployment's two mounts of one host directory).

type trashHarness struct {
	*harness
	key, folder string
	cookie      *http.Cookie
	// linker, when set, replaces the write mount's hard link (tests make a move fail).
	linker func(r *os.Root, o, n string) error
}

func newTrashHarness(t *testing.T, writeMount bool) *trashHarness {
	t.Helper()
	th := &trashHarness{}
	hs := newHarnessWith(t, true, func(c *Config) {
		nas := filepath.Join(c.Mounts[0], "nas")
		c.NasReadRoot, c.NasWriteRoot = nas, nas
		if !writeMount {
			c.NasWriteRoot = filepath.Join(c.Mounts[0], "no-write-mount")
		}
		c.CompressLinker = func(r *os.Root, o, n string) error {
			if th.linker != nil {
				return th.linker(r, o, n)
			}
			return r.Link(o, n)
		}
	})
	key, _ := hs.pair("Tablet")
	th.harness, th.key = hs, key
	shotAt := func(day, i int) mediatest.Shot {
		return mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", DateTime: fmt.Sprintf("2024:05:%02d 10:%02d:00", day, i), Offset: "+00:00",
			Width: 600, Height: 400, Unique: uint64(day*100 + i)}
	}
	// A pair with every sidecar convention, a single JPEG, and photos in a sub-folder.
	th.write("2024-05-01/DSC00100.JPG", mediatest.JPEG(shotAt(1, 0), ""))
	raw := shotAt(1, 0)
	raw.Unique += 50
	th.write("2024-05-01/DSC00100.ARW", mediatest.RAW(raw))
	th.write("2024-05-01/DSC00100.xmp", []byte("<x:xmpmeta/>"))
	th.write("2024-05-01/DSC00100.ARW.xmp", []byte("<x:xmpmeta darktable/>"))
	th.write("2024-05-01/.DSC00100.xmp", []byte("<x:xmpmeta hidden/>"))
	th.write("2024-05-01/DSC00101.JPG", mediatest.JPEG(shotAt(1, 1), ""))
	th.write("2024-05-02/DSC00200.JPG", mediatest.JPEG(shotAt(2, 0), ""))
	th.write("2024-05-02/DSC00201.JPG", mediatest.JPEG(shotAt(2, 1), ""))
	w := hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders())
	expect(t, w, 201, "")
	th.folder = decode(t, w)["id"].(string)
	hs.walkAll()
	th.cookie = webSignIn(t, hs)
	return th
}

func (th *trashHarness) write(rel string, b []byte) {
	th.t.Helper()
	p := filepath.Join(th.nas, rel)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		th.t.Fatal(err)
	}
	if err := os.WriteFile(p, b, 0o600); err != nil {
		th.t.Fatal(err)
	}
}

func (th *trashHarness) exists(rel string) bool {
	_, err := os.Lstat(filepath.Join(th.nas, rel))
	return err == nil
}

func (th *trashHarness) itemOfFile(rel string) string {
	th.t.Helper()
	b, err := os.ReadFile(filepath.Join(th.nas, rel))
	if err != nil {
		th.t.Fatal(err)
	}
	return store.ItemID(fingerprint.Bytes(b))
}

func (th *trashHarness) web(method, path string, body any) map[string]any {
	th.t.Helper()
	w := th.do(method, path, body, webWrite(), withCookie(th.cookie))
	if w.Code != 200 {
		th.t.Fatalf("%s %s: %d %s", method, path, w.Code, w.Body.String())
	}
	return decode(th.t, w)
}

func (th *trashHarness) move(ids []string, dry bool) map[string]any {
	th.t.Helper()
	return th.web("POST", "/web/trash/move", map[string]any{"itemIds": ids, "dryRun": dry})
}

// trash lists GET /web/trash's entries by item id.
func (th *trashHarness) trash() map[string]map[string]any {
	th.t.Helper()
	w := th.do("GET", "/web/trash", nil, webLocal(), withCookie(th.cookie))
	expect(th.t, w, 200, "")
	out := map[string]map[string]any{}
	for _, e := range decode(th.t, w)["items"].([]any) {
		m := e.(map[string]any)
		out[m["id"].(string)] = m
	}
	return out
}

func unitsOf(e map[string]any) []map[string]any {
	var out []map[string]any
	for _, u := range e["units"].([]any) {
		out = append(out, u.(map[string]any))
	}
	return out
}

func strs(v any) []string {
	var out []string
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	return out
}

// The shot rule (ADR 0023 Amendment (A2) item 8): a shot's media files, then its sidecars only when no other media file
// of that stem stays in the directory.
func TestWebTrashShotRule(t *testing.T) {
	u := nasUnit{dir: "d", stem: "dsc1", files: []nasFileRef{{rel: "d/DSC1.ARW"}, {rel: "d/DSC1.JPG"}, {rel: "d/DSC1.xmp", sidecar: true}}}
	listing := []string{"DSC1.ARW", "DSC1.JPG", "DSC1.xmp", "DSC1.ARW.xmp", ".DSC1.xmp", "._DSC1.xmp", "DSC2.JPG", "DSC2.xmp", "DSC10.xmp"}
	m, s := unitFiles(u, listing)
	if !slices.Equal(m, []string{"d/DSC1.ARW", "d/DSC1.JPG"}) || !slices.Equal(s, []string{"d/.DSC1.xmp", "d/DSC1.ARW.xmp", "d/DSC1.xmp"}) {
		t.Fatalf("whole shot: %v %v", m, s)
	}
	// The JPEG alone while the RAW stays: every sidecar stays with the RAW.
	u.files = []nasFileRef{{rel: "d/DSC1.JPG"}}
	if m, s := unitFiles(u, listing); !slices.Equal(m, []string{"d/DSC1.JPG"}) || len(s) != 0 {
		t.Fatalf("not the last member: %v %v", m, s)
	}
	// The last member takes X.xmp and X.JPG.xmp (case-insensitive), never another stem's.
	if m, s := unitFiles(u, []string{"dsc1.jpg", "dsc1.JPG.XMP", "Dsc1.xmp", "DSC1.mov.xmp"}); len(m) != 1 || !slices.Equal(s, []string{"d/Dsc1.xmp", "d/dsc1.JPG.XMP"}) {
		t.Fatalf("last member: %v %v", m, s)
	}
}

func TestWebTrashMoveRestoreAndDelete(t *testing.T) {
	th := newTrashHarness(t, true)
	pair, single, other := th.itemOfFile("2024-05-01/DSC00100.ARW"), th.itemOfFile("2024-05-01/DSC00101.JPG"), th.itemOfFile("2024-05-02/DSC00200.JPG")
	today := time.Now().Format("2006-01-02")
	// The same path already in today's trash: the shot goes to today's -2 folder, never over it.
	th.write(".hoard-trash/"+today+"/2024-05-01/DSC00101.JPG", []byte("someone else's"))

	// Dry run: counts, nothing moved.
	dry := th.move([]string{pair, single, "00000000-0000-8000-8000-000000000000"}, true)
	if dry["nasShots"] != float64(2) || dry["nasFiles"] != float64(5) || dry["skipped"].(map[string]any)["00000000-0000-8000-8000-000000000000"] != "not_found" ||
		!th.exists("2024-05-01/DSC00100.ARW") {
		t.Fatalf("dry run %v", dry)
	}
	ans := th.move([]string{pair, single}, false)
	if len(strs(ans["moved"])) != 2 || len(ans["failed"].(map[string]any)) != 0 {
		t.Fatalf("move %v", ans)
	}
	for _, rel := range []string{"DSC00100.ARW", "DSC00100.JPG", "DSC00100.xmp", "DSC00100.ARW.xmp", ".DSC00100.xmp"} {
		if th.exists("2024-05-01/"+rel) || !th.exists(".hoard-trash/"+today+"/2024-05-01/"+rel) {
			t.Fatalf("%s not in today's trash", rel)
		}
	}
	if th.exists("2024-05-01/DSC00101.JPG") || !th.exists(".hoard-trash/"+today+"-2/2024-05-01/DSC00101.JPG") {
		t.Fatal("the single JPEG is not in today's -2 folder")
	}
	if b, _ := os.ReadFile(filepath.Join(th.nas, ".hoard-trash", today, "2024-05-01/DSC00101.JPG")); string(b) != "someone else's" {
		t.Fatal("a trashed file was overwritten")
	}
	// The walk is queued at once, outside the request budget.
	if !slices.Contains(th.srv.indexer.Queued(), th.folder) {
		t.Fatalf("no walk queued: %v", th.srv.indexer.Queued())
	}
	th.walkAll()
	it := getItem(t, th.harness, th.key, pair, "")
	if it["deleted"] != true {
		t.Fatalf("old clients see a tombstone: %v", it)
	}
	it = getItem(t, th.harness, th.key, pair, "?include=trash")
	if it["trashed"] == nil {
		t.Fatalf("include=trash: %v", it)
	}
	tr := th.trash()
	us := unitsOf(tr[pair])
	if len(us) != 1 || us[0]["key"] != "nas:"+th.folder+":.hoard-trash/"+today || us[0]["byServer"] != true || us[0]["day"] != today ||
		us[0]["dir"] != "2024-05-01" || us[0]["purgeAfter"] == nil {
		t.Fatalf("units %v", us)
	}

	// Restore refuses a taken path, with its name; then restores every file (hidden sidecar too) with the same id.
	th.write("2024-05-01/DSC00100.JPG", []byte("a newer file"))
	res := th.web("POST", "/web/trash/restore", map[string]any{"units": []map[string]any{{"itemId": pair, "key": us[0]["key"]}}})
	k := pair + "|" + us[0]["key"].(string)
	if res["refused"].(map[string]any)[k] != "path_taken" || res["names"].(map[string]any)[k] != "2024-05-01/DSC00100.JPG" || th.exists("2024-05-01/DSC00100.ARW") {
		t.Fatalf("restore over a file %v", res)
	}
	_ = os.Remove(filepath.Join(th.nas, "2024-05-01/DSC00100.JPG"))
	res = th.web("POST", "/web/trash/restore", map[string]any{"units": []map[string]any{{"itemId": pair, "key": us[0]["key"]}}})
	if len(res["done"].([]any)) != 1 {
		t.Fatalf("restore %v", res)
	}
	for _, rel := range []string{"DSC00100.ARW", "DSC00100.JPG", "DSC00100.xmp", "DSC00100.ARW.xmp", ".DSC00100.xmp"} {
		if !th.exists("2024-05-01/" + rel) {
			t.Fatalf("%s not restored", rel)
		}
	}
	if !th.exists(".hoard-trash") || th.exists(".hoard-trash/"+today+"/2024-05-01/DSC00100.ARW") {
		t.Fatal("the trash should stay, the restored files leave it")
	}
	th.walkAll()
	if it := getItem(t, th.harness, th.key, pair, ""); it["deleted"] != false {
		t.Fatalf("restored item %v", it)
	}

	// Delete permanently: the single JPEG goes for good; the other day's file someone else put there stays.
	tr = th.trash()
	su := unitsOf(tr[single])
	res = th.web("POST", "/web/trash/delete", map[string]any{"units": []map[string]any{{"itemId": single, "key": su[0]["key"]}}})
	if len(res["done"].([]any)) != 1 || th.exists(".hoard-trash/"+today+"-2/2024-05-01/DSC00101.JPG") ||
		!th.exists(".hoard-trash/"+today+"/2024-05-01/DSC00101.JPG") {
		t.Fatalf("delete %v", res)
	}
	th.walkAll()
	if it := getItem(t, th.harness, th.key, single, "?include=trash"); it["deleted"] != true {
		t.Fatalf("purged item %v", it)
	}
	// Untouched: another day's photo.
	if it := getItem(t, th.harness, th.key, other, ""); it["deleted"] != false {
		t.Fatalf("%v", it)
	}
}

// Without a usable write mount, nothing moves and the answer names the reason; the dry run says it before.
func TestWebTrashRefusedWithoutWriteMount(t *testing.T) {
	th := newTrashHarness(t, false)
	pair := th.itemOfFile("2024-05-01/DSC00100.ARW")
	if dry := th.move([]string{pair}, true); dry["nasBlocked"] != "no_write_mount" {
		t.Fatalf("%v", dry)
	}
	w := th.do("POST", "/web/trash/move", map[string]any{"itemIds": []string{pair}}, webWrite(), withCookie(th.cookie))
	expect(t, w, 503, "unavailable")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"] != "no_write_mount" || !th.exists("2024-05-01/DSC00100.ARW") ||
		th.exists(".hoard-trash") {
		t.Fatalf("%s", w.Body.String())
	}
	// More than a batch is refused (the gallery sends Select all in batches of 500).
	ids := make([]string, 501)
	for i := range ids {
		ids[i] = pair
	}
	expect(t, th.do("POST", "/web/trash/move", map[string]any{"itemIds": ids}, webWrite(), withCookie(th.cookie)), 422, "invalid_request")
	// A device key cannot use the gallery's routes.
	if w := th.do("POST", "/web/trash/move", map[string]any{"itemIds": []string{pair}}, bearer(th.key)); w.Code == 200 {
		t.Fatal("a device key used the gallery's trash")
	}
}

// A file someone changed since the walk is not moved (the shot is left as it was).
func TestWebTrashChangedFileNotMoved(t *testing.T) {
	th := newTrashHarness(t, true)
	single := th.itemOfFile("2024-05-01/DSC00101.JPG")
	th.write("2024-05-01/DSC00101.JPG", []byte("rewritten since the walk"))
	ans := th.move([]string{single}, false)
	if ans["failed"].(map[string]any)[single] != "changed" || !th.exists("2024-05-01/DSC00101.JPG") {
		t.Fatalf("%v", ans)
	}
}

// The journal settles a move a kill left between its link and its unlink, and a row whose file never moved.
func TestWebTrashJournalRecovery(t *testing.T) {
	th := newTrashHarness(t, true)
	ctx := t.Context()
	today := time.Now().Format("2006-01-02")
	src, dst := "2024-05-02/DSC00200.JPG", ".hoard-trash/"+today+"/2024-05-02/DSC00200.JPG"
	if err := os.MkdirAll(filepath.Dir(filepath.Join(th.nas, dst)), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Link(filepath.Join(th.nas, src), filepath.Join(th.nas, dst)); err != nil {
		t.Fatal(err)
	}
	if _, err := th.st.PutTrashMove(ctx, store.TrashMove{FolderID: th.folder, RelPath: src, TrashPath: dst, State: store.MoveMoving, MovedAt: 1, PurgeAfter: 1}); err != nil {
		t.Fatal(err)
	}
	if _, err := th.st.PutTrashMove(ctx, store.TrashMove{FolderID: th.folder, RelPath: "2024-05-02/DSC00201.JPG", TrashPath: ".hoard-trash/" + today + "/2024-05-02/DSC00201.JPG",
		State: store.MoveMoving, MovedAt: 1, PurgeAfter: 1 << 60}); err != nil {
		t.Fatal(err)
	}
	th.srv.settleTrashMoves(ctx)
	if th.exists(src) || !th.exists(dst) || !th.exists("2024-05-02/DSC00201.JPG") {
		t.Fatal("not settled")
	}
	rows, _ := th.st.TrashMoves(ctx)
	if len(rows) != 1 || rows[0].State != store.MoveTrashed || rows[0].TrashPath != dst {
		t.Fatalf("%+v", rows)
	}
}

// Items that are not on the NAS go to the server trash: warnings for the only copy and Reveal's edits, blobs pinned
// while trashed, restore, the server's auto-empty after trashDays, and the purge's tombstone.
func TestWebTrashServerTrash(t *testing.T) {
	th := newTrashHarness(t, true)
	ctx := t.Context()
	pixel := th.phone("Pixel 8", "hoard")
	root := th.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	freed, onPhone := shot(11), shot(12)
	for _, d := range [][]byte{freed, onPhone} {
		uploadFor(t, th.harness, pixel.key, d, false)
	}
	th.report(pixel, root, []map[string]any{fileEntry("DCIM/Camera/freed.jpg", freed, true), fileEntry("DCIM/Camera/here.jpg", onPhone, true)}, nil)
	th.report(pixel, root, nil, []map[string]any{{"path": "DCIM/Camera/freed.jpg", "reason": "freed"}})
	th.srv.IndexNow(ctx)
	freedID, hereID := store.ItemID(fingerprint.Bytes(freed)), store.ItemID(fingerprint.Bytes(onPhone))
	// Reveal edited the freed photo.
	if _, _, _, _, err := th.st.PutEdit(ctx, store.Edit{VersionID: verMain, ItemID: freedID, DeviceID: "reveal", RecipeDigest: "d1", EditedAt: 1,
		Primary: true, SHA256: strings.Repeat("ab", 32), Format: "jpeg", Width: 64, Height: 48, Bytes: 100}, 10); err != nil {
		t.Fatal(err)
	}

	dry := th.move([]string{freedID, hereID}, true)
	warn := dry["warnings"].(map[string]any)
	if !slices.Equal(strs(warn["onlyCopy"]), []string{freedID}) || !slices.Equal(strs(warn["edits"]), []string{freedID}) ||
		dry["skipped"].(map[string]any)[hereID] != "on_phone" || dry["serverItems"] != float64(1) {
		t.Fatalf("dry run %v", dry)
	}
	ans := th.move([]string{freedID, hereID}, false)
	if !slices.Equal(strs(ans["moved"]), []string{freedID}) {
		t.Fatalf("%v", ans)
	}
	it := getItem(t, th.harness, th.key, freedID, "?include=trash")
	st, _ := it["serverTrash"].(map[string]any)
	if it["trashed"] == nil || st == nil || st["purgeAfter"].(float64) <= st["since"].(float64) {
		t.Fatalf("server trash %v", it)
	}
	if it := getItem(t, th.harness, th.key, freedID, ""); it["deleted"] != true {
		t.Fatalf("old clients %v", it)
	}
	// Pinned: the blob stays, Reveal's delete is refused, the backup stays.
	if _, err := os.Stat(th.blobFile(sum(freed))); err != nil {
		t.Fatal("blob gone while trashed")
	}
	w := th.do("DELETE", "/v1/originals/"+fingerprint.Bytes(freed), nil, bearer(pixel.key))
	expect(t, w, 409, "conflict")
	tr := th.trash()
	us := unitsOf(tr[freedID])
	if len(us) != 1 || us[0]["key"] != "server" || us[0]["device"] != "Pixel 8" || tr[freedID]["edits"] != true || tr[freedID]["size"].(float64) <= 0 {
		t.Fatalf("trash entry %v", tr[freedID])
	}
	// Restore: live again, with its edit.
	res := th.web("POST", "/web/trash/restore", map[string]any{"units": []map[string]any{{"itemId": freedID, "key": "server"}}})
	if len(res["done"].([]any)) != 1 {
		t.Fatalf("%v", res)
	}
	if it := getItem(t, th.harness, th.key, freedID, ""); it["deleted"] != false || it["edits"] == nil {
		t.Fatalf("restored %v", it)
	}
	// Trash again; 31 days later the auto-empty purges it: blob and backup gone, a tombstone, its edits dropped.
	th.move([]string{freedID}, false)
	th.st.SetNow(func() time.Time { return time.Now().Add(31 * 24 * time.Hour) })
	th.srv.trashHousekeeping(ctx)
	th.srv.IndexNow(ctx)
	if _, err := os.Stat(th.blobFile(sum(freed))); err == nil {
		t.Fatal("blob kept after the purge")
	}
	if it := getItem(t, th.harness, th.key, freedID, "?include=trash"); it["deleted"] != true {
		t.Fatalf("purged %v", it)
	}
	if _, err := th.st.MirrorEntryAt(ctx, pixel.physical, "primary", "DCIM/Camera/freed.jpg"); err == nil {
		t.Fatal("backup entry kept")
	}
	// The photo still on the phone is untouched.
	if it := getItem(t, th.harness, th.key, hereID, ""); it["deleted"] != false {
		t.Fatalf("%v", it)
	}
	th.srv.trashHousekeeping(ctx)
	if rows, _ := th.st.ServerTrashRows(ctx, true); len(rows) != 0 {
		t.Fatalf("server trash rows left %+v", rows)
	}
}

// Empty trash takes everything not held after the page's confirmation (count and size); the server's auto-empty
// takes what the server moved into a NAS trash once trashDays passed, never a file someone replaced there.
func TestWebTrashEmptyAndAutoEmpty(t *testing.T) {
	th := newTrashHarness(t, true)
	ctx := t.Context()
	a, b := th.itemOfFile("2024-05-02/DSC00200.JPG"), th.itemOfFile("2024-05-02/DSC00201.JPG")
	th.move([]string{a, b}, false)
	th.walkAll()
	w := th.do("POST", "/web/trash/empty", map[string]any{"confirm": map[string]any{"count": 1, "size": 1}}, webWrite(), withCookie(th.cookie))
	expect(t, w, 409, "conflict")
	d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any)
	if d["count"] != float64(2) || d["size"].(float64) <= 0 {
		t.Fatalf("%v", d)
	}
	// Auto-empty first: b's trashed file was replaced by someone (another size): it stays; a's goes after 31 days.
	today := time.Now().Format("2006-01-02")
	th.write(".hoard-trash/"+today+"/2024-05-02/DSC00201.JPG", []byte("replaced in the trash"))
	th.srv.trashHousekeeping(ctx)
	if !th.exists(".hoard-trash/" + today + "/2024-05-02/DSC00200.JPG") {
		t.Fatal("auto-empty before trashDays")
	}
	th.st.SetNow(func() time.Time { return time.Now().Add(31 * 24 * time.Hour) })
	th.srv.trashHousekeeping(ctx)
	if th.exists(".hoard-trash/"+today+"/2024-05-02/DSC00200.JPG") || !th.exists(".hoard-trash/"+today+"/2024-05-02/DSC00201.JPG") {
		t.Fatal("auto-empty")
	}
	th.st.SetNow(time.Now)
	th.walkAll()
	tr := th.trash()
	if len(tr) != 1 {
		t.Fatalf("%v", tr)
	}
	var e map[string]any
	for _, v := range tr {
		e = v
	}
	w = th.do("POST", "/web/trash/empty", map[string]any{"confirm": map[string]any{"count": 1, "size": e["size"]}}, webWrite(), withCookie(th.cookie))
	expect(t, w, 200, "")
	if th.exists(".hoard-trash/" + today + "/2024-05-02/DSC00201.JPG") {
		t.Fatal("not emptied")
	}
	th.walkAll()
	if len(th.trash()) != 0 {
		t.Fatal("trash not empty")
	}
}

// Held entries and items whose compression is in flight are never moved; a mixed selection says what happened to each.
func TestWebTrashHeldBusyAndMixed(t *testing.T) {
	ch := compressHarness(t, true, "V/A.MP4", "V/B.MP4")
	c := webSignIn(t, ch.harness)
	a, b := ch.itemOf("V/A.MP4"), ch.itemOf("V/B.MP4")
	ch.job(map[string]any{"itemIds": []string{a}}, nil) // queued: busy
	w := ch.do("POST", "/web/trash/move", map[string]any{"itemIds": []string{a, b, "not-an-id"}}, webWrite(), withCookie(c))
	expect(t, w, 200, "")
	m := decode(t, w)
	if m["skipped"].(map[string]any)[a] != "busy" || !slices.Equal(strs(m["moved"]), []string{b}) || m["counts"].(map[string]any)["skipped.busy"] != float64(1) {
		t.Fatalf("%v", m)
	}
	if _, err := os.Stat(filepath.Join(ch.nas, "V/A.MP4")); err != nil {
		t.Fatal("a busy video moved")
	}
}

// A journal stopped after its trash link (a crash) holds the entry: the item is skipped "held", its unit is listed held
// and every action and the auto-empty leave it.
func TestWebTrashHeldEntry(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	c := webSignIn(t, ch.harness)
	id := ch.itemOf("A.MP4")
	ch.srv.compress.SetJournalHook(func(s string) error {
		if s == "replace:swap" {
			return fmt.Errorf("killed")
		}
		return nil
	})
	ch.job(map[string]any{"itemIds": []string{id}}, nil)
	ch.runAll()
	ch.walkAll()
	ch.srv.refreshItems(t.Context(), []string{ch.fp("A.MP4")})
	w := ch.do("POST", "/web/trash/move", map[string]any{"itemIds": []string{id}}, webWrite(), withCookie(c))
	expect(t, w, 200, "")
	if m := decode(t, w); m["skipped"].(map[string]any)[id] != "held" && m["skipped"].(map[string]any)[id] != "busy" {
		t.Fatalf("%v", m)
	}
	w = ch.do("GET", "/web/trash", nil, webLocal(), withCookie(c))
	var held map[string]any
	for _, e := range decode(t, w)["items"].([]any) {
		if e.(map[string]any)["held"] == true {
			held = e.(map[string]any)
		}
	}
	if held == nil {
		t.Fatalf("no held entry: %s", w.Body.String())
	}
	us := unitsOf(held)
	if len(us) != 1 || us[0]["held"] != true {
		t.Fatalf("%v", us)
	}
	for _, route := range []string{"/web/trash/restore", "/web/trash/delete"} {
		w = ch.do("POST", route, map[string]any{"units": []map[string]any{{"itemId": held["id"], "key": us[0]["key"]}}}, webWrite(), withCookie(c))
		expect(t, w, 200, "")
		if m := decode(t, w); len(m["done"].([]any)) != 0 {
			t.Fatalf("%s acted on a held entry: %v", route, m)
		}
	}
	trashed := trashDirs(t, ch.nas)
	ch.st.SetNow(func() time.Time { return time.Now().Add(400 * 24 * time.Hour) })
	ch.srv.trashHousekeeping(t.Context())
	if after := trashDirs(t, ch.nas); len(after) != len(trashed) || len(after) == 0 {
		t.Fatalf("auto-empty touched a held entry: %v -> %v", trashed, after)
	}
}
