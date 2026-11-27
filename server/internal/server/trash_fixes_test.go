package server

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/store"
)

// hoard-server 0.15.0: the trash for paired devices (ADR 0023 Amendment (0.15.0)) and the 0.14.x fixes. Synthetic
// files only.

// manyShots writes n single JPEGs under dir and walks; it returns their item ids.
func (th *trashHarness) manyShots(dir string, n int) []string {
	th.t.Helper()
	var ids []string
	for i := range n {
		rel := fmt.Sprintf("%s/IMG_%04d.JPG", dir, i)
		th.write(rel, mediatest.JPEG(mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", DateTime: fmt.Sprintf("2024:06:01 10:%02d:%02d", i/60, i%60),
			Offset: "+00:00", Width: 64, Height: 48, Unique: uint64(9000 + i)}, ""))
	}
	th.walkAll()
	for i := range n {
		ids = append(ids, th.itemOfFile(fmt.Sprintf("%s/IMG_%04d.JPG", dir, i)))
	}
	return ids
}

// A NAS walk holding the write lock longer than a statement waits (the owner's first run: each batch queued a walk of
// a large folder, which wrote while the next batch wrote its journal) failed PutTrashMove (answered not_writable) and
// left rows in moving when SetTrashMoveState failed. Reproduced with a short busy timeout, a writer that holds the lock
// longer, and walks running between the batches: every shot moves, no row is left unsettled.
func TestTrashBatchesUnderConcurrentWalk(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		harnessBusyTimeout = 100 * time.Millisecond
		t.Cleanup(func() { harnessBusyTimeout = 0 })
		th := newTrashHarnessAPI(t, true, api)
		ids := th.manyShots("2024-06-01", 30)
		ctx := t.Context()
		folder, err := th.st.NasFolder(ctx, th.folder)
		if err != nil {
			t.Fatal(err)
		}
		stop := make(chan struct{})
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { // a long writer, as a walk's index transaction on a large folder
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				_ = th.st.ItemsTx(ctx, func(*store.ItemTx) error { time.Sleep(250 * time.Millisecond); return nil })
				time.Sleep(40 * time.Millisecond)
			}
		}()
		go func() { // the walk the previous batch queued
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				_ = th.srv.indexer.Folder(ctx, folder)
			}
		}()
		moved := 0
		failed := map[string]string{}
		for start := 0; start < len(ids); start += 10 {
			w := th.call("POST", "/web/trash/move", map[string]any{"itemIds": ids[start : start+10]})
			if w.Code != 200 {
				close(stop)
				wg.Wait()
				t.Fatalf("batch at %d: %d %s\n%s", start, w.Code, w.Body.String(), th.logs.String())
			}
			m := decode(t, w)
			moved += len(m["moved"].([]any))
			for k, v := range m["failed"].(map[string]any) {
				failed[k] = v.(string)
			}
		}
		close(stop)
		wg.Wait()
		if moved != len(ids) || len(failed) != 0 {
			t.Fatalf("moved %d of %d, failed %v", moved, len(ids), failed)
		}
		if rows, _ := th.st.TrashMoves(ctx, store.MoveMoving, store.MoveRestoring, store.MovePurging); len(rows) != 0 {
			t.Fatalf("rows left unsettled: %+v", rows)
		}
		if rows, _ := th.st.TrashMoves(ctx, store.MoveTrashed); len(rows) != len(ids) {
			t.Fatalf("%d trashed rows for %d shots", len(rows), len(ids))
		}
		for i := range ids {
			if th.exists(fmt.Sprintf("2024-06-01/IMG_%04d.JPG", i)) {
				t.Fatalf("IMG_%04d.JPG still at its path", i)
			}
		}
	})
}

// A row a kill (or a write the index kept busy) left in moving is settled right after the next batch, not at the
// 10-minute housekeeping.
func TestTrashSettledAfterBatch(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		th := newTrashHarnessAPI(t, true, api)
		ctx := t.Context()
		today := time.Now().Format("2006-01-02")
		src, dst := "2024-05-02/DSC00200.JPG", ".hoard-trash/"+today+"/2024-05-02/DSC00200.JPG"
		if err := os.MkdirAll(filepath.Dir(filepath.Join(th.nas, dst)), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.Rename(filepath.Join(th.nas, src), filepath.Join(th.nas, dst)); err != nil {
			t.Fatal(err)
		}
		if _, err := th.st.PutTrashMove(ctx, store.TrashMove{FolderID: th.folder, RelPath: src, TrashPath: dst, State: store.MoveMoving, MovedAt: 1, PurgeAfter: 1 << 60}); err != nil {
			t.Fatal(err)
		}
		th.move([]string{th.itemOfFile("2024-05-02/DSC00201.JPG")}, false)
		rows, _ := th.st.TrashMoves(ctx, store.MoveMoving)
		if len(rows) != 0 {
			t.Fatalf("left in moving after the batch: %+v", rows)
		}
	})
}

// A shot whose move fails half-way is moved back whole: nothing stays in the trash, no journal row, and the answer
// names the reason (not partial).
func TestTrashMoveRollsBackHalfwayShot(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		th := newTrashHarnessAPI(t, true, api)
		pair := th.itemOfFile("2024-05-01/DSC00100.ARW")
		n := 0
		th.linker = func(r *os.Root, o, nn string) error {
			if !strings.Contains(o, ".hoard-trash") { // a move's link
				n++
				if n == 3 {
					return syscall.EIO
				}
			}
			return r.Link(o, nn)
		}
		ans := th.move([]string{pair}, false)
		th.linker = nil
		if ans["failed"].(map[string]any)[pair] != "not_writable" || ans["counts"].(map[string]any)["failed.not_writable"] != float64(1) {
			t.Fatalf("%v", ans)
		}
		for _, rel := range []string{"DSC00100.ARW", "DSC00100.JPG", "DSC00100.xmp", "DSC00100.ARW.xmp", ".DSC00100.xmp"} {
			if !th.exists("2024-05-01/" + rel) {
				t.Fatalf("%s not put back", rel)
			}
		}
		if dirs := trashDirs(t, th.nas); len(dirs) != 0 {
			t.Fatalf("left in the trash: %v", dirs)
		}
		if rows := th.allMoves(); len(rows) != 0 {
			t.Fatalf("rows left: %+v", rows)
		}
		// The failure is in the batch's log line, with its reason.
		if !strings.Contains(th.logs.String(), "failed.not_writable=1") {
			t.Fatalf("no reason counts in the log: %s", th.logs.String())
		}
	})
}

// A stale journal row at the trash path a new move takes (its file left the trash without the server: Hoard emptied
// it) is replaced whole: the new file's size, mtime, item, mover and purge date, never the old row's.
func TestTrashStaleRowReplaced(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		th := newTrashHarnessAPI(t, true, api)
		ctx := t.Context()
		today := time.Now().Format("2006-01-02")
		single := th.itemOfFile("2024-05-01/DSC00101.JPG")
		stale := store.TrashMove{FolderID: th.folder, RelPath: "2024-05-01/DSC00101.JPG", TrashPath: ".hoard-trash/" + today + "/2024-05-01/DSC00101.JPG",
			State: store.MoveTrashed, Size: 1, MTime: 2, ItemID: "old-item", MovedAt: 3, PurgeAfter: 4, MovedBy: "someone"}
		if _, err := th.st.PutTrashMove(ctx, stale); err != nil {
			t.Fatal(err)
		}
		fi, err := os.Stat(filepath.Join(th.nas, "2024-05-01/DSC00101.JPG"))
		if err != nil {
			t.Fatal(err)
		}
		th.move([]string{single}, false)
		rows := th.allMoves()
		if len(rows) != 1 {
			t.Fatalf("%+v", rows)
		}
		r := rows[0]
		want := "admin"
		if api == "v1" {
			want = deviceOf(t, th)
		}
		if r.TrashPath != stale.TrashPath || r.State != store.MoveTrashed || r.Size != fi.Size() || r.MTime != fi.ModTime().UnixNano() || r.ItemID != single ||
			r.PurgeAfter <= time.Now().UnixMilli() || r.MovedAt < time.Now().Add(-time.Minute).UnixMilli() || r.MovedBy != want || r.Kind != store.MoveKindMove {
			t.Fatalf("stale row kept: %+v", r)
		}
		// A restore over the server's row keeps its purge date (only its state changes while it runs).
		if _, err := th.st.PutTrashMove(ctx, store.TrashMove{FolderID: th.folder, RelPath: r.RelPath, TrashPath: r.TrashPath, State: store.MoveRestoring,
			Size: 9, MTime: 9, MovedAt: 9, PurgeAfter: 9, Kind: store.MoveKindOp}); err != nil {
			t.Fatal(err)
		}
		if got := th.allMoves()[0]; got.PurgeAfter != r.PurgeAfter || got.Kind != store.MoveKindMove || got.Size != r.Size || got.State != store.MoveRestoring {
			t.Fatalf("a restore changed the server's row: %+v -> %+v", r, got)
		}
	})
}

// deviceOf is the id of the harness's paired device.
func deviceOf(t *testing.T, th *trashHarness) string {
	t.Helper()
	return decode(t, th.do("GET", "/v1/server", nil, bearer(th.key)))["deviceId"].(string)
}

// The item may lag behind the index while a walk rebuilds it: a file whose index row (same content, its fingerprint)
// has the size and mtime on disk still moves; a file whose content changed does not.
func TestTrashChangedCheckFollowsTheIndex(t *testing.T) {
	th := newTrashHarness(t, true)
	ctx := t.Context()
	single, other := th.itemOfFile("2024-05-01/DSC00101.JPG"), th.itemOfFile("2024-05-02/DSC00200.JPG")
	later := time.Now().Add(time.Hour)
	for _, rel := range []string{"2024-05-01/DSC00101.JPG", "2024-05-02/DSC00200.JPG"} {
		if err := os.Chtimes(filepath.Join(th.nas, rel), later, later); err != nil {
			t.Fatal(err)
		}
	}
	// The walk re-indexed DSC00101 (same content, new mtime) but has not rebuilt its item yet; DSC00200 is not re-indexed.
	b, _ := os.ReadFile(filepath.Join(th.nas, "2024-05-01/DSC00101.JPG"))
	fi, _ := os.Stat(filepath.Join(th.nas, "2024-05-01/DSC00101.JPG"))
	if err := th.st.ApplyNasUpdates(ctx, th.folder, []store.NasFileUpdate{{RelPath: "2024-05-01/DSC00101.JPG", Size: fi.Size(), MTime: fi.ModTime().UnixNano(),
		Fingerprint: fingerprint.Bytes(b), Media: "image"}}); err != nil {
		t.Fatal(err)
	}
	ans := th.move([]string{single, other}, false)
	if !slices.Equal(strs(ans["moved"]), []string{single}) || ans["failed"].(map[string]any)[other] != "changed" {
		t.Fatalf("%v", ans)
	}
}

// The batches of one selection share one walk: the folders wait for a quiet moment after the last batch.
func TestTrashWalkDebounced(t *testing.T) {
	th := newTrashHarness(t, true)
	th.srv.cfg.TrashWalkQuiet = 300 * time.Millisecond
	a, b := th.itemOfFile("2024-05-02/DSC00200.JPG"), th.itemOfFile("2024-05-02/DSC00201.JPG")
	th.move([]string{a}, false)
	if !slices.Equal(th.srv.pendingTrashWalks(), []string{th.folder}) || slices.Contains(th.srv.indexer.Queued(), th.folder) {
		t.Fatalf("pending %v, queued %v", th.srv.pendingTrashWalks(), th.srv.indexer.Queued())
	}
	time.Sleep(150 * time.Millisecond)
	th.move([]string{b}, false) // pushes the walk back
	time.Sleep(200 * time.Millisecond)
	if slices.Contains(th.srv.indexer.Queued(), th.folder) {
		t.Fatal("walked before the selection was quiet")
	}
	deadline := time.Now().Add(5 * time.Second)
	for !slices.Contains(th.srv.indexer.Queued(), th.folder) && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !slices.Contains(th.srv.indexer.Queued(), th.folder) || len(th.srv.pendingTrashWalks()) != 0 {
		t.Fatalf("no walk queued after the quiet period: %v", th.srv.indexer.Queued())
	}
}

// /v1 for paired devices: the feature, the device key (no session, no anonymous call), who moved a unit and its group,
// the groups' figures on the first page, Empty by group through /v1, and the device id in the log.
func TestTrashV1Devices(t *testing.T) {
	th := newTrashHarnessAPI(t, true, "v1")
	dev := deviceOf(t, th)
	info := decode(t, th.do("GET", "/v1/server", nil, bearer(th.key)))
	if !slices.Contains(strs(info["features"]), "trash-actions") {
		t.Fatalf("features %v", info["features"])
	}
	for _, route := range []string{"GET /v1/trash", "POST /v1/trash/move", "POST /v1/trash/restore", "POST /v1/trash/delete", "POST /v1/trash/empty"} {
		m, p, _ := strings.Cut(route, " ")
		expect(t, th.do(m, p, map[string]any{}), 401, "unauthorized")
		expect(t, th.do(m, p, map[string]any{}, webWrite(), withCookie(th.cookie)), 401, "unauthorized")
	}
	single, web := th.itemOfFile("2024-05-01/DSC00101.JPG"), th.itemOfFile("2024-05-02/DSC00200.JPG")
	th.move([]string{single}, false)
	// The web gallery moves another: group web, by admin.
	w := th.do("POST", "/web/trash/move", map[string]any{"itemIds": []string{web}}, webWrite(), withCookie(th.cookie))
	expect(t, w, 200, "")
	th.walkAll()
	page := decode(t, th.call("GET", "/web/trash", nil))
	entries := map[string]map[string]any{}
	for _, e := range page["items"].([]any) {
		entries[e.(map[string]any)["id"].(string)] = e.(map[string]any)
	}
	us, uw := unitsOf(entries[single]), unitsOf(entries[web])
	if len(us) != 1 || us[0]["by"] != dev || us[0]["group"] != "hoard" || us[0]["byServer"] != true || us[0]["purgeAfter"] == nil {
		t.Fatalf("device's unit %v", us)
	}
	if len(uw) != 1 || uw[0]["by"] != "admin" || uw[0]["group"] != "web" {
		t.Fatalf("web's unit %v", uw)
	}
	groups := page["groups"].(map[string]any)
	hoard := groups["hoard"].(map[string]any)
	if hoard["count"] != float64(1) || hoard["size"] != us[0]["size"] || groups["web"].(map[string]any)["count"] != float64(1) {
		t.Fatalf("groups %v", groups)
	}
	if !strings.Contains(th.logs.String(), dev) {
		t.Fatal("the device id is not in the log")
	}
	// Empty the hoard group through /v1: the web's move stays.
	w = th.call("POST", "/web/trash/empty", map[string]any{"groups": []string{"hoard"}, "confirm": map[string]any{"count": 1, "size": hoard["size"]}})
	expect(t, w, 200, "")
	th.walkAll()
	tr := th.trash()
	if _, ok := tr[single]; ok {
		t.Fatal("the device's move stays after Empty")
	}
	if _, ok := tr[web]; !ok {
		t.Fatal("Empty of the hoard group took the web's move")
	}
}
