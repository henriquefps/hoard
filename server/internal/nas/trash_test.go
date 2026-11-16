package nas

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The trash (ADR 0023). Every file here is synthetic.

const day = ".hoard-trash/2026-11-15"

func (e *env) move(from, to string) {
	e.t.Helper()
	dst := filepath.Join(e.nas, to)
	if err := os.MkdirAll(filepath.Dir(dst), 0o700); err != nil {
		e.t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(e.nas, from), dst); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) remove(rel string) {
	e.t.Helper()
	if err := os.Remove(filepath.Join(e.nas, rel)); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) row(id string) store.ItemRow {
	e.t.Helper()
	r, err := e.st.Item(context.Background(), id)
	if err != nil {
		e.t.Fatal(err)
	}
	return r
}

func (e *env) states() map[string]store.NasFileState {
	e.t.Helper()
	st, err := e.st.NasFileStates(context.Background(), e.folder.ID)
	if err != nil {
		e.t.Fatal(err)
	}
	return st
}

// trashLocations returns, per file name, the trash path of each location of an item ("" for a live one).
func (e *env) trashLocations(id string) map[string][]string {
	e.t.Helper()
	out := map[string][]string{}
	for _, f := range e.item(id).Files {
		for _, l := range f.Locations {
			out[f.Name] = append(out[f.Name], l.Trash)
		}
	}
	return out
}

func (e *env) nasFeed() map[string]store.NasFile {
	e.t.Helper()
	files, _, err := e.st.NasChanges(context.Background(), 0, 10000)
	if err != nil {
		e.t.Fatal(err)
	}
	out := map[string]store.NasFile{}
	for _, f := range files {
		out[f.Path] = f
	}
	return out
}

func TestTrashWalk(t *testing.T) {
	e := newEnv(t)
	jpeg := func(u uint64) []byte { return mediatest.JPEG(shotAt(u, "2024:05:01 10:00:00"), "") }
	e.write("2024/A.ARW", mediatest.RAW(shotAt(1, "2024:05:01 10:00:00")))
	e.write("2024/A.JPG", jpeg(2))
	e.write("2024/B.JPG", jpeg(3))
	e.write("2024/C.JPG", jpeg(4))
	e.write("2024/D.JPG", jpeg(5))
	e.write("E.JPG", jpeg(6))
	e.walk()
	live := e.live()
	a, b, c, d := live["A.ARW"].ID, live["B.JPG"].ID, live["C.JPG"].ID, live["D.JPG"].ID
	if a == "" || b == "" || c == "" || d == "" || len(live) != 5 {
		t.Fatalf("%v", live)
	}

	// Trash: A (a pair) and C in the folder's trash, D in a trash inside its directory (a share rooted at 2024).
	e.move("2024/A.ARW", day+"/2024/A.ARW")
	e.move("2024/A.JPG", day+"/2024/A.JPG")
	e.move("2024/C.JPG", day+"/2024/C.JPG")
	e.move("2024/D.JPG", "2024/"+day+"/D.JPG")
	revA := e.row(a).Rev
	e.walk()
	for _, id := range []string{a, c, d} {
		r := e.row(id)
		if r.Deleted || !r.Trashed() || r.Rev <= 0 {
			t.Fatalf("%s not trashed: %+v", id, r)
		}
	}
	if e.row(a).Rev == revA {
		t.Fatal("trashed without a revision")
	}
	if locs := e.trashLocations(a); len(locs) != 2 || locs["A.ARW"][0] != day+"/2024/A.ARW" || locs["A.JPG"][0] != day+"/2024/A.JPG" {
		t.Fatalf("A locations %v", locs)
	}
	if p := e.item(a).Files[0].Locations[0].Path; p != "2024/A.ARW" {
		t.Fatalf("a trash location keeps the original path: %q", p)
	}
	if locs := e.trashLocations(d); locs["D.JPG"][0] != "2024/"+day+"/D.JPG" {
		t.Fatalf("D locations %v", locs)
	}
	st := e.states()
	if k := st["2024/A.JPG"]; !k.Removed || k.TrashPath != day+"/2024/A.JPG" {
		t.Fatalf("original row %+v", k)
	}
	if k := st[day+"/2024/A.JPG"]; k.Removed || k.Origin != "2024/A.JPG" || k.Fingerprint != st["2024/A.JPG"].Fingerprint {
		t.Fatalf("trash row %+v", k)
	}
	if n, _ := e.st.ItemCount(context.Background()); n != 2 {
		t.Fatalf("live items %d", n)
	}

	// Restore A, trash B into a "-2" day folder, purge C: all in one walk. Nested and malformed trash entries are
	// ignored.
	e.move(day+"/2024/A.ARW", "2024/A.ARW")
	e.move(day+"/2024/A.JPG", "2024/A.JPG")
	e.move("2024/B.JPG", ".hoard-trash/2026-11-15-2/2024/B.JPG")
	e.remove(day + "/2024/C.JPG")
	e.write(day+"/2024/.hoard-trash/2026-11-15/N.JPG", jpeg(10)) // a trash in a trash
	e.write(".hoard-trash/old/M.JPG", jpeg(11))                  // not a day folder
	e.write(".hoard-trash/2026-13-40/M.JPG", jpeg(12))           // not a date
	e.write(".hoard-trash/2026-11-15-0/M.JPG", jpeg(13))         // -0 is no suffix
	e.write(".hoard-trash/F.JPG", jpeg(14))                      // directly in the trash
	e.write(".other/G.JPG", jpeg(15))                            // other dot folders stay skipped
	e.walk()
	if r := e.row(a); r.Deleted || r.Trashed() {
		t.Fatalf("A not restored: %+v", r)
	}
	if locs := e.trashLocations(a); locs["A.ARW"][0] != "" || len(locs["A.ARW"]) != 1 {
		t.Fatalf("restored A %v", locs)
	}
	if r := e.row(b); !r.Trashed() {
		t.Fatalf("B not trashed: %+v", r)
	}
	if locs := e.trashLocations(b); locs["B.JPG"][0] != ".hoard-trash/2026-11-15-2/2024/B.JPG" {
		t.Fatalf("B %v", locs)
	}
	if r := e.row(c); !r.Deleted || r.Trashed() {
		t.Fatalf("C not purged: %+v", r)
	}
	if r := e.row(d); !r.Trashed() {
		t.Fatalf("D: %+v", r)
	}
	for rel := range e.states() {
		for _, bad := range []string{"N.JPG", "M.JPG", "F.JPG", "G.JPG"} {
			if strings.HasSuffix(rel, bad) {
				t.Fatalf("%s indexed", rel)
			}
		}
	}
	feed := e.nasFeed()
	if f := feed["2024/A.JPG"]; f.Removed || f.Trashed {
		t.Fatalf("restored in the feed %+v", f)
	}
	if f := feed["2024/B.JPG"]; !f.Removed || !f.Trashed {
		t.Fatalf("trashed in the feed %+v", f)
	}
	if f := feed["2024/C.JPG"]; !f.Removed || f.Trashed {
		t.Fatalf("purged in the feed %+v", f)
	}
	for p := range feed {
		if strings.Contains(p, ".hoard-trash") {
			t.Fatalf("trash row in the NAS feed: %s", p)
		}
	}

	// An unreadable trash keeps its rows: nothing purged from what was not read.
	if os.Getuid() == 0 {
		return
	}
	trash := filepath.Join(e.nas, ".hoard-trash")
	if err := os.Chmod(trash, 0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(trash, 0o755) })
	e.walk()
	if r := e.row(b); !r.Trashed() {
		t.Fatalf("B after an unreadable trash: %+v", r)
	}
	if k := e.states()[".hoard-trash/2026-11-15-2/2024/B.JPG"]; k.Removed {
		t.Fatal("trash row dropped")
	}
	if f := e.nasFeed()["2024/B.JPG"]; !f.Trashed {
		t.Fatalf("B lost trashed %+v", f)
	}
	os.Chmod(trash, 0o755)
	e.walk()
	if r := e.row(b); !r.Trashed() {
		t.Fatalf("B: %+v", r)
	}
}

// A rename while the walk reads the folder is always seen at one end: trashing a file after the trash was read, or
// restoring one after its original folder was read, keeps the item (ADR 0023, "second look").
func TestTrashSecondLook(t *testing.T) {
	e := newEnv(t)
	e.write("2024/A.JPG", mediatest.JPEG(shotAt(1, "2024:05:01 10:00:00"), ""))
	e.write("!old/B.JPG", mediatest.JPEG(shotAt(2, "2024:05:01 10:00:01"), "")) // '!' sorts before ".hoard-trash"
	e.walk()
	live := e.live()
	a, b := live["A.JPG"].ID, live["B.JPG"].ID

	// Trash A while the walk is between ".hoard-trash" (read first) and "2024".
	e.ix.beforeDir = func(rel string) {
		if rel == "2024" {
			e.move("2024/A.JPG", day+"/2024/A.JPG")
		}
	}
	e.walk()
	if r := e.row(a); r.Deleted || !r.Trashed() {
		t.Fatalf("A tombstoned by a trash during the walk: %+v", r)
	}

	// Trash B, then restore it while the walk is between "!old" (read first) and ".hoard-trash".
	e.ix.beforeDir = nil
	e.move("!old/B.JPG", day+"/!old/B.JPG")
	e.walk()
	if !e.row(b).Trashed() {
		t.Fatal("B not trashed")
	}
	e.ix.beforeDir = func(rel string) {
		if rel == ".hoard-trash" {
			e.move(day+"/!old/B.JPG", "!old/B.JPG")
		}
	}
	e.walk()
	if r := e.row(b); r.Deleted || r.Trashed() {
		t.Fatalf("B lost by a restore during the walk: %+v", r)
	}
	e.ix.beforeDir = nil
	e.walk()
	if r := e.row(b); r.Deleted || r.Trashed() {
		t.Fatalf("B: %+v", r)
	}
	if r := e.row(a); !r.Trashed() {
		t.Fatalf("A: %+v", r)
	}

	// A file deleted for good is found at neither end: removed.
	e.remove(day + "/2024/A.JPG")
	e.walk()
	if r := e.row(a); !r.Deleted {
		t.Fatalf("A not purged: %+v", r)
	}
}

func TestTrashNoRehash(t *testing.T) {
	e := newEnv(t)
	e.write("2024/A.ARW", mediatest.RAW(shotAt(1, "2024:05:01 10:00:00")))
	e.write("2024/A.JPG", mediatest.JPEG(shotAt(2, "2024:05:01 10:00:00"), ""))
	e.write("2024/B.JPG", mediatest.JPEG(shotAt(3, "2024:05:01 10:00:01"), ""))
	e.write("2024/B.xmp", sidecar(`xmp:Rating="2"`))
	if st := e.walk(); st.Hashed != 4 {
		t.Fatalf("first walk hashed %d", st.Hashed)
	}
	for _, n := range []string{"A.ARW", "A.JPG", "B.JPG", "B.xmp"} {
		e.move("2024/"+n, day+"/2024/"+n)
	}
	st := e.walk()
	if st.Hashed != 0 || st.Removed != 4 {
		t.Fatalf("trash walk %+v", st)
	}
	for _, it := range e.live() { // live() lists trashed items too (not deleted)
		if !e.row(it.ID).Trashed() {
			t.Fatalf("%s not trashed", it.Files[0].Name)
		}
	}
	for _, n := range []string{"A.ARW", "A.JPG", "B.JPG", "B.xmp"} {
		e.move(day+"/2024/"+n, "2024/"+n)
	}
	if st := e.walk(); st.Hashed != 0 {
		t.Fatalf("restore walk hashed %d", st.Hashed)
	}
	if n, _ := e.st.ItemCount(context.Background()); n != 2 {
		t.Fatalf("live items %d", n)
	}
	// A changed file is hashed as before.
	e.move("2024/B.JPG", day+"/2024/B.JPG")
	e.touch(day+"/2024/B.JPG", time.UnixMilli(1_700_000_000_000))
	if st := e.walk(); st.Hashed != 1 {
		t.Fatalf("changed mtime hashed %d", st.Hashed)
	}
}

// Every file of the folder trashed: the folder is not empty (trash rows count). Then the trash emptied too: the
// guard fires and the items stay trashed, not purged.
func TestTrashEmptyMount(t *testing.T) {
	e := newEnv(t)
	e.write("A.JPG", mediatest.JPEG(shotAt(1, "2024:05:01 10:00:00"), ""))
	e.write("sub/B.JPG", mediatest.JPEG(shotAt(2, "2024:05:01 10:00:01"), ""))
	e.walk()
	live := e.live()
	a, b := live["A.JPG"].ID, live["B.JPG"].ID
	e.move("A.JPG", day+"/A.JPG")
	e.move("sub/B.JPG", day+"/sub/B.JPG")
	e.walk() // no error: the trash shows files
	if !e.row(a).Trashed() || !e.row(b).Trashed() {
		t.Fatal("not trashed")
	}
	if err := os.RemoveAll(filepath.Join(e.nas, ".hoard-trash")); err != nil {
		t.Fatal(err)
	}
	if err := e.ix.Folder(context.Background(), e.folder); err == nil {
		t.Fatal("an emptied folder walked fine")
	}
	if !e.row(a).Trashed() || !e.row(b).Trashed() {
		t.Fatal("items purged by an empty folder")
	}
	folders, _ := e.st.NasFolders(context.Background())
	if folders[0].State != "unavailable" {
		t.Fatalf("%+v", folders[0])
	}
}

// Sidecars in a trash are never imported, and their first-read state carries over with the original path: a trash
// then restore is not a first read (ADR 0023 point 8).
func TestTrashSidecars(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	t0 := time.UnixMilli(1_700_000_000_000)
	e.write("2024/A.JPG", mediatest.JPEG(shotAt(1, "2024:05:01 10:00:00"), ""))
	e.write("2024/A.xmp", sidecar(`xmp:Rating="3"`))
	e.touch("2024/A.xmp", t0)
	e.walk()
	a := e.live()["A.JPG"].ID
	if f := e.culling(a); string(f[store.CullRating].Value) != "3" {
		t.Fatalf("%+v", f)
	}
	// A device rates it 5 afterwards.
	if _, err := e.st.PushCulling(ctx, "device-1", []store.CullChange{{ItemID: a, Field: store.CullRating, Value: []byte("5"),
		ModifiedAt: t0.UnixMilli() + 1000}}); err != nil {
		t.Fatal(err)
	}
	rec, _ := e.st.Culling(ctx, a)

	// Trashed: the sidecar row stays under its original path; nothing imported.
	e.move("2024/A.JPG", day+"/2024/A.JPG")
	e.move("2024/A.xmp", day+"/2024/A.xmp")
	if st := e.walk(); st.Culling != 0 {
		t.Fatalf("trash walk imported %d", st.Culling)
	}
	states, _ := e.st.SidecarStates(ctx, e.folder.ID)
	if _, ok := states["2024/A.xmp"]; !ok || len(states) != 1 {
		t.Fatalf("sidecar rows %v", states)
	}
	// A sidecar that is only in a trash is never read, even changed.
	e.write(day+"/2024/Z.JPG", mediatest.JPEG(shotAt(2, "2024:05:01 10:00:02"), ""))
	e.write(day+"/2024/Z.xmp", sidecar(`xmp:Rating="4"`))
	if st := e.walk(); st.Culling != 0 {
		t.Fatalf("a trash sidecar imported %d", st.Culling)
	}
	if states, _ := e.st.SidecarStates(ctx, e.folder.ID); len(states) != 1 {
		t.Fatalf("sidecar rows %v", states)
	}

	// Restored: the same file at its path, not a first read, nothing read again.
	e.move(day+"/2024/A.JPG", "2024/A.JPG")
	e.move(day+"/2024/A.xmp", "2024/A.xmp")
	if st := e.walk(); st.Culling != 0 {
		t.Fatalf("restore walk imported %d", st.Culling)
	}
	if now, _ := e.st.Culling(ctx, a); now.Rev != rec.Rev || string(now.Fields[store.CullRating].Value) != "5" {
		t.Fatalf("culling changed: %+v → %+v", rec, now)
	}
	// Purged: the sidecar row goes with the trash row.
	e.move("2024/A.JPG", day+"/2024/A.JPG")
	e.move("2024/A.xmp", day+"/2024/A.xmp")
	e.walk()
	e.remove(day + "/2024/A.JPG")
	e.remove(day + "/2024/A.xmp")
	e.walk()
	if states, _ := e.st.SidecarStates(ctx, e.folder.ID); len(states) != 0 {
		t.Fatalf("sidecar rows after purge %v", states)
	}
	if !e.row(a).Deleted {
		t.Fatal("A not purged")
	}
	if _, err := e.st.Culling(ctx, a); err != nil {
		t.Fatalf("culling record dropped at purge: %v", err)
	}
}
