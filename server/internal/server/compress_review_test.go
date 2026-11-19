package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Review of 0.13.0 (PR #71): crashes, restarts and races around the replace, restore and purge journals.

func crashAt(step string) func(string) error {
	return func(s string) error {
		if strings.HasPrefix(s, step) {
			return errors.New("crash")
		}
		return nil
	}
}

func eperm(*os.Root, string, string) error { return &os.LinkError{Op: "link", Err: syscall.EPERM} }

// unmount takes the NAS directory away (the write mount and the read mount with it) and returns a function that puts
// it back.
func (ch *cHarness) unmount(empty bool) func() {
	ch.t.Helper()
	away := ch.nas + ".away"
	if err := os.Rename(ch.nas, away); err != nil {
		ch.t.Fatal(err)
	}
	if empty { // an unmounted share leaves an empty, readable directory
		if err := os.Mkdir(ch.nas, 0o700); err != nil {
			ch.t.Fatal(err)
		}
	}
	return func() {
		_ = os.Remove(ch.nas)
		if err := os.Rename(away, ch.nas); err != nil {
			ch.t.Fatal(err)
		}
	}
}

// later moves the clock past a stalled journal's wait and runs the housekeeping.
func (ch *cHarness) later(d time.Duration) {
	ch.st.SetNow(func() time.Time { return time.Now().Add(d) })
	ch.srv.compress.Housekeeping(ch.t.Context())
	ch.st.SetNow(time.Now)
}

// Blockers 1 and 2: a replace interrupted after its rename-fallback trash step (the path empty), restarted while the
// NAS is not mounted, keeps its step and trash path; it finishes once the NAS is back, never failed nor re-queued.
func TestCompressReplaceWaitsForTheWriteMount(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ch.linker = eperm
	orig := ch.fp("A.MP4")
	ch.srv.compress.SetJournalHook(crashAt("replace:swap"))
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	it := ch.one()
	if it.State != store.CompressReplacing || it.ReplaceStep != "replace:swap" || it.TrashPath == "" || exists(filepath.Join(ch.nas, "A.MP4")) {
		t.Fatalf("crashed at %+v", it)
	}
	ch.srv.compress.SetJournalHook(nil)
	back := ch.unmount(false)
	if err := ch.srv.compress.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	ch.later(2 * time.Minute)
	w := ch.one()
	if w.State != store.CompressReplacing || w.ReplaceStep != "replace:swap" || w.TrashPath != it.TrashPath || w.Reason != compress.ReasonNotWritable {
		t.Fatalf("without the NAS: %+v", w)
	}
	back()
	ch.later(10 * time.Minute)
	it = ch.one()
	if it.State != store.CompressReplaced || ch.fp("A.MP4") != it.OutFingerprint || ch.fp(it.TrashPath) != orig {
		t.Fatalf("after the NAS came back: %+v", it)
	}
}

// Blocker 2: a restore interrupted after its trash step (rename fallback: the path empty) and restarted without the NAS
// keeps the output's trash path in its step; it finishes once the NAS is back.
func TestCompressRestoreWaitsForTheWriteMount(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	orig := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	id := ch.one().ID
	ch.linker = eperm
	ch.srv.compress.SetJournalHook(crashAt("restore:swap"))
	ch.srv.compress.Restore(t.Context(), []int64{id})
	ch.srv.compress.RunOps(t.Context())
	it := ch.one()
	step := it.ReplaceStep
	if it.State != store.CompressReplacing || !strings.HasPrefix(step, "restore:swap:.hoard-trash/") || exists(filepath.Join(ch.nas, "A.MP4")) {
		t.Fatalf("crashed at %+v", it)
	}
	ch.srv.compress.SetJournalHook(nil)
	back := ch.unmount(false)
	if err := ch.srv.compress.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	if w := ch.one(); w.State != store.CompressReplacing || w.ReplaceStep != step {
		t.Fatalf("without the NAS: %+v", w)
	}
	// Neither auto-purge nor a purge by route touches it meanwhile.
	ch.later(40 * 24 * time.Hour)
	if a := ch.srv.compress.Purge(t.Context(), []int64{id}); a[0].Error != "state" {
		t.Fatalf("purge during a restore: %+v", a)
	}
	back()
	ch.later(41 * 24 * time.Hour)
	it = ch.one()
	if it.State != store.CompressRestored || ch.fp("A.MP4") != orig || ch.fp(it.TrashPath) != it.OutFingerprint {
		t.Fatalf("after the NAS came back: %+v", it)
	}
}

// Blocker 1 (the "neither temporary file nor output" branch): the verified copy vanished after a rename-fallback trash
// step; the original is put back at its path, nothing is left in the trash.
func TestCompressReplaceLostCopyPutsTheOriginalBack(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ch.linker = eperm
	orig := ch.fp("A.MP4")
	ch.srv.compress.SetJournalHook(crashAt("replace:swap"))
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	it := ch.one()
	if err := os.Remove(filepath.Join(ch.nas, compress.TempName("A.MP4", it.ID))); err != nil {
		t.Fatal(err)
	}
	ch.srv.compress.SetJournalHook(nil)
	if err := ch.srv.compress.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	it = ch.one()
	if it.State != store.CompressFailed || ch.fp("A.MP4") != orig || len(trashDirs(t, ch.nas)) != 0 {
		t.Fatalf("%+v %v", it, trashDirs(t, ch.nas))
	}
}

func (ch *cHarness) replaceWith(rel, content string) {
	ch.t.Helper()
	tmp := filepath.Join(ch.nas, rel+".new")
	if err := os.WriteFile(tmp, []byte(content), 0o600); err != nil {
		ch.t.Fatal(err)
	}
	if err := os.Rename(tmp, filepath.Join(ch.nas, rel)); err != nil {
		ch.t.Fatal(err)
	}
}

func (ch *cHarness) read(rel string) string {
	ch.t.Helper()
	b, err := os.ReadFile(filepath.Join(ch.nas, rel))
	if err != nil {
		ch.t.Fatal(err)
	}
	return string(b)
}

// Blocker 3: the file at the path is checked again right before the trash link and right before the rename: a newer
// version written meanwhile is never trashed under the original's record, never overwritten.
func TestCompressReplaceRaces(t *testing.T) {
	cases := []struct {
		name, step string
		touch      func(ch *cHarness)
		trashed    int // files left in the trash
	}{
		{"replaced during the copy", "replace:trash", func(ch *cHarness) { ch.replaceWith("A.MP4", "newer version") }, 0},
		{"replaced after the link", "replace:swap", func(ch *cHarness) { ch.replaceWith("A.MP4", "newer version") }, 1},
		{"written in place after the link", "replace:swap", func(ch *cHarness) {
			f, err := os.OpenFile(filepath.Join(ch.nas, "A.MP4"), os.O_WRONLY|os.O_TRUNC, 0)
			if err != nil {
				ch.t.Fatal(err)
			}
			_, _ = f.WriteString("newer version")
			_ = f.Close()
		}, 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ch := compressHarness(t, true, "A.MP4")
			orig := ch.fp("A.MP4")
			ch.srv.compress.SetJournalHook(func(s string) error {
				if s == c.step {
					c.touch(ch)
				}
				return nil
			})
			ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
			ch.runAll()
			it := ch.one()
			if it.State != store.CompressFailed || it.Reason != compress.ReasonSourceChanged || ch.read("A.MP4") != "newer version" {
				t.Fatalf("%+v %q", it, ch.read("A.MP4"))
			}
			if tr := trashDirs(t, ch.nas); len(tr) != c.trashed {
				t.Fatalf("trash %v", tr)
			}
			if c.trashed == 1 && ch.fp(trashDirs(t, ch.nas)[0]) != orig {
				t.Fatal("the trash holds something else than the original")
			}
			if left, _ := filepath.Glob(filepath.Join(ch.nas, ".A.MP4*")); len(left) != 0 {
				t.Fatalf("left %v", left)
			}
		})
	}
}

// Blocker 3 for a restore: a file that takes the path during the restore is never overwritten.
func TestCompressRestoreRace(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	orig := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	id := ch.one().ID
	ch.srv.compress.SetJournalHook(func(s string) error {
		if strings.HasPrefix(s, "restore:swap") {
			ch.replaceWith("A.MP4", "newer version")
		}
		return nil
	})
	ch.srv.compress.Restore(t.Context(), []int64{id})
	ch.srv.compress.RunOps(t.Context())
	it := ch.one()
	if it.State != store.CompressReplaced || it.Reason != "path_taken" || ch.read("A.MP4") != "newer version" || ch.fp(it.TrashPath) != orig {
		t.Fatalf("%+v", it)
	}
}

// Should-fix 4: a purge and a restore of the same item exclude each other (a claimed journal), whichever comes first,
// also when they race; a purge interrupted mid-way is finished at the next start.
func TestCompressPurgeRestoreExclusion(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4", "B.MP4", "C.MP4", "D.MP4", "E.MP4", "F.MP4")
	ch.job(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder}}}, nil)
	ch.runAll()
	items := ch.items()
	// Restore claimed first: the purge is refused, the restore finishes.
	ch.srv.compress.Restore(t.Context(), []int64{items[0].ID})
	if a := ch.srv.compress.Purge(t.Context(), []int64{items[0].ID}); a[0].Error != "state" {
		t.Fatalf("purge during a restore: %+v", a)
	}
	ch.srv.compress.RunOps(t.Context())
	if it, _ := ch.st.CompressItem(t.Context(), items[0].ID); it.State != store.CompressRestored || !exists(filepath.Join(ch.nas, it.TrashPath)) {
		t.Fatalf("%+v", it)
	}
	// A purge interrupted after its claim: a restore is refused, the next start finishes the purge.
	ch.srv.compress.SetJournalHook(crashAt("purge:"))
	ch.srv.compress.Purge(t.Context(), []int64{items[1].ID})
	ch.srv.compress.SetJournalHook(nil)
	if a := ch.srv.compress.Restore(t.Context(), []int64{items[1].ID}); a[0].Error != "state" {
		t.Fatalf("restore during a purge: %+v", a)
	}
	if err := ch.srv.compress.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	if it, _ := ch.st.CompressItem(t.Context(), items[1].ID); it.State != store.CompressPurged || exists(filepath.Join(ch.nas, items[1].TrashPath)) {
		t.Fatalf("%+v", it)
	}
	// Racing: each item ends either restored (its original at the path) or purged (its output at the path), never
	// with the original deleted under a restore.
	var wg sync.WaitGroup
	for _, it := range items[2:] {
		wg.Add(2)
		go func() {
			defer wg.Done()
			ch.srv.compress.Restore(context.Background(), []int64{it.ID})
			ch.srv.compress.RunOps(context.Background())
		}()
		go func() { defer wg.Done(); ch.srv.compress.Purge(context.Background(), []int64{it.ID}) }()
	}
	wg.Wait()
	ch.srv.compress.RunOps(t.Context())
	for _, it := range items[2:] {
		got, _ := ch.st.CompressItem(t.Context(), it.ID)
		switch got.State {
		case store.CompressRestored:
			if ch.fp(it.RelPath) != it.Fingerprint || ch.fp(got.TrashPath) != it.OutFingerprint {
				t.Fatalf("restored %+v", got)
			}
		case store.CompressPurged:
			if ch.fp(it.RelPath) != it.OutFingerprint {
				t.Fatalf("purged %+v", got)
			}
		default:
			t.Fatalf("raced into %+v", got)
		}
	}
}

// Should-fix 5: the output gone from its path (deleted outside Hoard, no other live copy): neither a purge by route nor
// the retention deletes the trashed original.
func TestCompressPurgeKeepsTheOnlyCopy(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	orig := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	it := ch.one()
	ch.walkAll()
	if err := os.Remove(filepath.Join(ch.nas, "A.MP4")); err != nil {
		t.Fatal(err)
	}
	w := ch.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{fmt.Sprint(it.ID)}}, bearer(ch.key))
	expect(t, w, 200, "")
	if e := decode(t, w)["items"].(map[string]any)[fmt.Sprint(it.ID)].(map[string]any)["error"]; e != compress.ReasonOutputMissing {
		t.Fatalf("purge answered %v", e)
	}
	ch.later(40 * 24 * time.Hour)
	got := ch.one()
	if got.State != store.CompressReplaced || got.Reason != compress.ReasonOutputMissing || ch.fp(it.TrashPath) != orig {
		t.Fatalf("%+v", got)
	}
}

// Should-fix 6: a walk over an unmounted share (an empty, readable directory) marks nothing purged.
func TestCompressAfterWalkUnmounted(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("A.MP4")}}, nil)
	ch.runAll()
	back := ch.unmount(true)
	ch.srv.compress.AfterWalk(t.Context())
	back()
	if it := ch.one(); it.State != store.CompressReplaced {
		t.Fatalf("%+v", it)
	}
	// Restore still works.
	ch.srv.compress.Restore(t.Context(), []int64{ch.one().ID})
	ch.srv.compress.RunOps(t.Context())
	if it := ch.one(); it.State != store.CompressRestored {
		t.Fatalf("%+v", it)
	}
}

// Should-fix 7: per-frame data that cannot be saved fails the item before anything is replaced.
func TestCompressSidecarIsAGate(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	p := testProbe
	p.Data = []string{"djmd"}
	b, _ := json.Marshal(p)
	if err := ch.st.PutVideoProbe(t.Context(), ch.fp("A.MP4"), compress.ProbeVersion, b); err != nil {
		t.Fatal(err)
	}
	ch.enc.sideErr = errors.New("exiftool: cannot read the data track")
	orig := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	if it := ch.one(); it.State != store.CompressFailed || it.Reason != compress.ReasonVerifyMeta || ch.fp("A.MP4") != orig || ch.enc.encodes.Load() != 0 {
		t.Fatalf("%+v", it)
	}
}

// Nit 12: trash folders take the mode of the folder that holds them (the server's umask is private); the replaced
// file keeps its mode.
func TestCompressTrashFolderModes(t *testing.T) {
	ch := compressHarness(t, true, "Trip/A.MP4")
	if err := os.Chmod(ch.nas, 0o775); err != nil {
		t.Fatal(err)
	}
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("Trip/A.MP4")}}, nil)
	ch.runAll()
	it := ch.one()
	for d := filepath.Dir(filepath.Join(ch.nas, it.TrashPath)); d != ch.nas; d = filepath.Dir(d) {
		fi, err := os.Stat(d)
		if err != nil || fi.Mode().Perm() != 0o775 {
			t.Fatalf("%s: %v %v", d, fi.Mode(), err)
		}
	}
}

// Re-review N1: the output trashed by hand is not a live copy: the purge keeps the original.
func TestCompressPurgeOutputTrashedByHand(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	orig := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	it := ch.one()
	ch.move("A.MP4", ".hoard-trash/2026-11-20/A.MP4")
	ch.walkAll()
	if a := ch.srv.compress.Purge(t.Context(), []int64{it.ID}); a[0].Error != compress.ReasonOutputMissing {
		t.Fatalf("purge answered %+v", a)
	}
	if ch.fp(it.TrashPath) != orig {
		t.Fatal("the original was purged")
	}
}

func trashLocation(t *testing.T, item map[string]any) map[string]any {
	t.Helper()
	for _, f := range item["files"].([]any) {
		for _, l := range f.(map[string]any)["locations"].([]any) {
			if lm := l.(map[string]any); lm["trash"] != nil {
				return lm
			}
		}
	}
	return nil
}

// Re-review N2 (a, b): a waiting journal is surfaced (GET /v1/server, the admin page, the status errors) and the trash
// entry it holds is marked held on the item, for Hoard's Empty and auto-empty; the server's own retention skips it.
func TestCompressStalledSurfacedAndHeld(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	orig := ch.fp("A.MP4")
	ch.srv.compress.SetJournalHook(crashAt("replace:swap"))
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	ch.srv.compress.SetJournalHook(nil)
	ch.walkAll()
	id := store.ItemID(orig)
	l := trashLocation(t, getItem(t, ch.harness, ch.key, id, "?include=trash"))
	if l == nil || l["held"] != true {
		t.Fatalf("the trash entry of a running journal is not held: %v", l)
	}
	back := ch.unmount(false)
	if err := ch.srv.compress.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	att := decode(t, ch.do("GET", "/v1/server", nil, bearer(ch.key)))["compress"].(map[string]any)["attention"].(map[string]any)
	if att["stalled"].(float64) != 1 || att["stalledSince"] == nil {
		t.Fatalf("attention %v", att)
	}
	st := decode(t, ch.do("GET", "/admin/api/status", nil, adminHeaders()))
	if st["compressAttention"].(map[string]any)["stalled"].(float64) != 1 {
		t.Fatalf("status %v", st["compressAttention"])
	}
	since := att["stalledSince"].(float64)
	ch.later(40 * 24 * time.Hour) // retried, still waiting: the first time it waited is kept; never purged
	if it := ch.one(); it.State != store.CompressReplacing || it.StalledAt == nil || float64(*it.StalledAt) != since {
		t.Fatalf("%+v", it)
	}
	back()
	if !exists(filepath.Join(ch.nas, ch.one().TrashPath)) {
		t.Fatal("the held original was purged")
	}
	ch.later(41 * 24 * time.Hour)
	if it := ch.one(); it.State != store.CompressReplaced || it.StalledAt != nil {
		t.Fatalf("%+v", it)
	}
	ch.walkAll()
	if l := trashLocation(t, getItem(t, ch.harness, ch.key, id, "?include=trash")); l == nil || l["held"] != nil {
		t.Fatalf("still held after the journal ended: %v", l)
	}
	if att := decode(t, ch.do("GET", "/v1/server", nil, bearer(ch.key)))["compress"].(map[string]any)["attention"].(map[string]any); att["stalled"].(float64) != 0 {
		t.Fatalf("attention %v", att)
	}
}

// Re-review N2 (c): without the write mount, a purge, a restore and a replace that moved nothing yet go back to their
// previous state instead of waiting (and work again once it is back).
func TestCompressNothingMovedGoesBack(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4", "B.MP4")
	a := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(a)}}, nil)
	ch.runAll()
	id := ch.one().ID
	back := ch.unmount(false)
	if ans := ch.srv.compress.Purge(t.Context(), []int64{id}); ans[0].Error != compress.ReasonNotWritable || ans[0].State != store.CompressReplaced {
		t.Fatalf("purge %+v", ans)
	}
	ch.srv.compress.Restore(t.Context(), []int64{id})
	ch.srv.compress.RunOps(t.Context())
	if it := ch.one(); it.State != store.CompressReplaced || it.Reason != compress.ReasonNotWritable || it.StalledAt != nil {
		t.Fatalf("restore %+v", it)
	}
	back()
	// A replace interrupted at its check and resumed without the write mount goes back to ready.
	b := ch.fp("B.MP4")
	ch.srv.compress.SetJournalHook(crashAt("replace:check"))
	ch.job(map[string]any{"itemIds": []string{store.ItemID(b)}}, nil)
	ch.runAll()
	ch.srv.compress.SetJournalHook(nil)
	back = ch.unmount(false)
	if err := ch.srv.compress.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	var bi store.CompressItem
	for _, x := range ch.items() {
		if x.Fingerprint == b {
			bi = x
		}
	}
	if bi.State != store.CompressReady || bi.Reason != compress.ReasonNotWritable || bi.TrashPath != "" {
		t.Fatalf("replace %+v", bi)
	}
	back()
	ch.later(2 * time.Minute)
	for _, x := range ch.items() {
		if x.State != store.CompressReplaced {
			t.Fatalf("after the NAS came back %+v", x)
		}
	}
	ch.srv.compress.Restore(t.Context(), []int64{id})
	ch.srv.compress.RunOps(t.Context())
	if it, _ := ch.st.CompressItem(t.Context(), id); it.State != store.CompressRestored {
		t.Fatalf("%+v", it)
	}
}

// The features gate (coordinator's N2 addition): "compress" needs a GPU and a usable write mount; the object says why.
func TestCompressFeatureNeedsTheWriteMount(t *testing.T) {
	for _, c := range []struct {
		name, reason string
		write        func(nas string) string
	}{
		{"missing", "no_write_mount", func(nas string) string { return filepath.Join(filepath.Dir(nas), "nas-write") }},
		{"another directory", "write_mount_mismatch", func(nas string) string {
			d := filepath.Join(filepath.Dir(nas), "elsewhere")
			_ = os.MkdirAll(d, 0o700)
			return d
		}},
		{"usable", "", func(nas string) string { return nas }},
	} {
		t.Run(c.name, func(t *testing.T) {
			hs := newHarnessWith(t, true, func(cfg *Config) {
				nas := filepath.Join(cfg.Mounts[0], "nas")
				cfg.NasReadRoot, cfg.NasWriteRoot = nas, c.write(nas)
				cfg.CompressDetector, cfg.CompressEncoder, cfg.CompressVerifier = fakeDetector{ok: true}, &fakeEncoder{}, &fakeVerifier{}
			})
			hs.srv.compress.Detect(t.Context())
			key, _ := hs.pair("Phone")
			srv := decode(t, hs.do("GET", "/v1/server", nil, bearer(key)))
			has := strings.Contains(fmt.Sprint(srv["features"]), "compress")
			obj := srv["compress"].(map[string]any)
			if has != (c.reason == "") || obj["available"] != (c.reason == "") || (c.reason != "" && obj["reason"] != c.reason) {
				t.Fatalf("features %v, compress %v", srv["features"], obj)
			}
			adm := decode(t, hs.do("GET", "/admin/api/compress", nil, adminHeaders()))
			if adm["feature"] != (c.reason == "") || adm["writeProblem"] != c.reason {
				t.Fatalf("admin %v %v", adm["feature"], adm["writeProblem"])
			}
		})
	}
}

// Re-review N5: a write mount whose inode numbers are not stable (a link that comes out as another file) fails the
// item before anything moves and turns the feature off until the next check.
func TestCompressUnstableInodeTurnsTheFeatureOff(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ch.linker = func(r *os.Root, o, n string) error { // a "link" that is a copy: another inode
		b, err := r.ReadFile(o)
		if err != nil {
			return err
		}
		return r.WriteFile(n, b, 0o600)
	}
	orig := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	if it := ch.one(); it.State != store.CompressFailed || it.Reason != compress.ReasonNotWritable || ch.fp("A.MP4") != orig || len(trashDirs(t, ch.nas)) != 0 {
		t.Fatalf("%+v %v", it, trashDirs(t, ch.nas))
	}
	srv := decode(t, ch.do("GET", "/v1/server", nil, bearer(ch.key)))
	if strings.Contains(fmt.Sprint(srv["features"]), "compress") || srv["compress"].(map[string]any)["reason"] != "unstable_inode" {
		t.Fatalf("%v %v", srv["features"], srv["compress"])
	}
	ch.linker = nil
	ch.srv.compress.Detect(t.Context()) // "Check again"
	if srv := decode(t, ch.do("GET", "/v1/server", nil, bearer(ch.key))); !strings.Contains(fmt.Sprint(srv["features"]), "compress") {
		t.Fatal("not back after the check")
	}
}

// Re-review N6: an original left in the trash by a failed replace is counted (and kept in the failed row).
func TestCompressUntrackedTrashCounted(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ch.srv.compress.SetJournalHook(func(s string) error {
		if s == "replace:swap" {
			ch.replaceWith("A.MP4", "newer version")
		}
		return nil
	})
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("A.MP4")}}, nil)
	ch.runAll()
	it := ch.one()
	if it.State != store.CompressFailed || it.TrashPath == "" || !exists(filepath.Join(ch.nas, it.TrashPath)) {
		t.Fatalf("%+v", it)
	}
	att := decode(t, ch.do("GET", "/admin/api/compress", nil, adminHeaders()))["attention"].(map[string]any)
	if att["untrackedTrash"].(float64) != 1 {
		t.Fatalf("%v", att)
	}
	// A walk while it is still there keeps it counted; once Hoard emptied it, the next walk stops counting it.
	ch.walkAll()
	ch.srv.compress.AfterWalk(t.Context())
	if att := decode(t, ch.do("GET", "/admin/api/compress", nil, adminHeaders()))["attention"].(map[string]any); att["untrackedTrash"].(float64) != 1 {
		t.Fatalf("%v", att)
	}
	if err := os.Remove(filepath.Join(ch.nas, it.TrashPath)); err != nil {
		t.Fatal(err)
	}
	ch.walkAll()
	ch.srv.compress.AfterWalk(t.Context())
	if att := decode(t, ch.do("GET", "/admin/api/compress", nil, adminHeaders()))["attention"].(map[string]any); att["untrackedTrash"].(float64) != 0 {
		t.Fatalf("%v", att)
	}
}
