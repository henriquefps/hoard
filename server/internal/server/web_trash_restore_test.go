package server

import (
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/store"
)

// Review of #84 (data loss): a restore or a delete of a shot Hoard trashed (no journal row) that fails or is
// interrupted must never leave a row the server's auto-empty takes; a server move's row goes back exactly as it was.

const hoardDay = ".hoard-trash/2026-11-20"

// hoardTrashed moves the pair DSC00100 (with its sidecars) into a trash as Hoard does over SMB, and walks.
func hoardTrashed(th *trashHarness) (pair string, files []string) {
	th.t.Helper()
	pair = th.itemOfFile("2024-05-01/DSC00100.ARW")
	files = []string{"DSC00100.ARW", "DSC00100.JPG", "DSC00100.xmp", "DSC00100.ARW.xmp", ".DSC00100.xmp"}
	for _, f := range files {
		th.harness.move("2024-05-01/"+f, hoardDay+"/2024-05-01/"+f)
	}
	th.walkAll()
	return pair, files
}

func (th *trashHarness) unitKey(id string) string {
	th.t.Helper()
	us := unitsOf(th.trash()[id])
	if len(us) != 1 {
		th.t.Fatalf("units %v", us)
	}
	return us[0]["key"].(string)
}

// allMoves lists every journal row.
func (th *trashHarness) allMoves() []store.TrashMove {
	th.t.Helper()
	rows, err := th.st.TrashMoves(th.t.Context())
	if err != nil {
		th.t.Fatal(err)
	}
	return rows
}

// housekeepingLater runs the server's housekeeping (settle and auto-empty) as if a year had passed.
func (th *trashHarness) housekeepingLater() {
	th.t.Helper()
	th.st.SetNow(func() time.Time { return time.Now().Add(365 * 24 * time.Hour) })
	defer th.st.SetNow(time.Now)
	th.srv.trashHousekeeping(th.t.Context())
}

func (th *trashHarness) inTrash(files []string) []string {
	var out []string
	for _, f := range files {
		if th.exists(hoardDay + "/2024-05-01/" + f) {
			out = append(out, f)
		}
	}
	return out
}

func TestWebTrashFailedRestoreOfHoardsShotPurgesNothing(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		for _, route := range []string{"restore", "delete"} {
			t.Run(route, func(t *testing.T) {
				th := newTrashHarnessAPI(t, true, api)
				pair, files := hoardTrashed(th)
				key := th.unitKey(pair)
				// Refused: restore into a read-only original folder; delete from a read-only day folder.
				ro := filepath.Join(th.nas, "2024-05-01")
				if route == "delete" {
					ro = filepath.Join(th.nas, hoardDay, "2024-05-01")
				}
				if err := os.Chmod(ro, 0o500); err != nil {
					t.Fatal(err)
				}
				res := th.web("POST", "/web/trash/"+route, map[string]any{"units": []map[string]any{{"itemId": pair, "key": key}}})
				_ = os.Chmod(ro, 0o700)
				if res["refused"].(map[string]any)[pair+"|"+key] != "not_writable" {
					t.Fatalf("%v", res)
				}
				if rows := th.allMoves(); len(rows) != 0 {
					t.Fatalf("rows left after a refused %s: %+v", route, rows)
				}
				th.housekeepingLater()
				if got := th.inTrash(files); len(got) != len(files) {
					t.Fatalf("the auto-empty took Hoard's files: left %v", got)
				}
			})
		}
	})
}

func TestWebTrashRestoreFailingHalfwayPurgesNothing(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		th := newTrashHarnessAPI(t, true, api)
		pair, files := hoardTrashed(th)
		key := th.unitKey(pair)
		n := 0
		th.linker = func(r *os.Root, o, nn string) error {
			if strings.Contains(o, ".hoard-trash") { // a restore's link
				n++
				if n == 2 {
					return syscall.EIO
				}
			}
			return r.Link(o, nn)
		}
		res := th.web("POST", "/web/trash/restore", map[string]any{"units": []map[string]any{{"itemId": pair, "key": key}}})
		if res["refused"].(map[string]any)[pair+"|"+key] != "not_writable" {
			t.Fatalf("%v", res)
		}
		// 0.15.0: a shot that fails half-way is put back into the trash whole; nothing is left at the original paths.
		left := th.inTrash(files)
		if len(left) != len(files) {
			t.Fatalf("not rolled back: %v in the trash", left)
		}
		for _, f := range files {
			if th.exists("2024-05-01/" + f) {
				t.Fatalf("%s left at its path", f)
			}
		}
		if rows := th.allMoves(); len(rows) != 0 {
			t.Fatalf("rows left: %+v", rows)
		}
		th.linker = nil
		th.housekeepingLater()
		if got := th.inTrash(files); len(got) != len(left) {
			t.Fatalf("the auto-empty took the unrestored half: %v -> %v", left, got)
		}
	})
}

// A server killed in the middle of a restore or a delete: the restart's settle forgets an operation's row (Hoard's file
// stays, never due) and puts a server move's row back to trashed with its own purge_after.
func TestWebTrashKilledMidRestoreSettles(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		th := newTrashHarnessAPI(t, true, api)
		ctx := t.Context()
		_, files := hoardTrashed(th)
		for i, state := range []string{store.MoveRestoring, store.MovePurging} {
			if _, err := th.st.PutTrashMove(ctx, store.TrashMove{FolderID: th.folder, RelPath: "2024-05-01/" + files[i], TrashPath: hoardDay + "/2024-05-01/" + files[i],
				State: state, MovedAt: 1, PurgeAfter: 1, Kind: store.MoveKindOp}); err != nil {
				t.Fatal(err)
			}
		}
		// A server move of DSC00101, then killed while restoring it.
		single := th.itemOfFile("2024-05-01/DSC00101.JPG")
		th.move([]string{single}, false)
		rows := th.allMoves()
		var own store.TrashMove
		for _, r := range rows {
			if r.Kind == store.MoveKindMove {
				own = r
			}
		}
		if own.ID == 0 {
			t.Fatalf("%+v", rows)
		}
		if _, err := th.st.PutTrashMove(ctx, store.TrashMove{FolderID: th.folder, RelPath: own.RelPath, TrashPath: own.TrashPath, State: store.MoveRestoring,
			MovedAt: 2, PurgeAfter: 2, Kind: store.MoveKindOp}); err != nil {
			t.Fatal(err)
		}
		th.srv.settleTrashMoves(ctx)
		rows = th.allMoves()
		if len(rows) != 1 || rows[0].ID != own.ID || rows[0].State != store.MoveTrashed || rows[0].PurgeAfter != own.PurgeAfter || rows[0].Kind != store.MoveKindMove {
			t.Fatalf("after the settle: %+v (want %+v)", rows, own)
		}
		// A year later the auto-empty takes the server's own move only.
		th.housekeepingLater()
		if got := th.inTrash(files); len(got) != len(files) {
			t.Fatalf("Hoard's files purged: %v", got)
		}
		if th.exists(own.TrashPath) {
			t.Fatal("the server's own move was not emptied")
		}
	})
}

// A refused delete of a file the server moved keeps its row exactly (state trashed, the same purge_after).
func TestWebTrashRefusedDeleteKeepsServerRow(t *testing.T) {
	forTrashAPIs(t, func(t *testing.T, api string) {
		th := newTrashHarnessAPI(t, true, api)
		single := th.itemOfFile("2024-05-01/DSC00101.JPG")
		th.move([]string{single}, false)
		th.walkAll()
		before := th.allMoves()
		if len(before) != 1 {
			t.Fatalf("%+v", before)
		}
		key := th.unitKey(single)
		day := filepath.Join(th.nas, filepath.Dir(before[0].TrashPath))
		if err := os.Chmod(day, 0o500); err != nil {
			t.Fatal(err)
		}
		res := th.web("POST", "/web/trash/delete", map[string]any{"units": []map[string]any{{"itemId": single, "key": key}}})
		_ = os.Chmod(day, 0o700)
		if res["refused"].(map[string]any)[single+"|"+key] != "not_writable" {
			t.Fatalf("%v", res)
		}
		after := th.allMoves()
		if len(after) != 1 || after[0] != before[0] {
			t.Fatalf("row changed: %+v -> %+v", before, after)
		}
	})
}

// Empty confirms each group apart (review of #84): what the web gallery moved, what Hoard trashed on a device, the
// originals of compressed videos; a group left out stays.
func TestWebTrashEmptyByGroup(t *testing.T) {
	th := newTrashHarness(t, true)
	pair, files := hoardTrashed(th)
	single := th.itemOfFile("2024-05-02/DSC00200.JPG")
	th.move([]string{single}, false)
	th.walkAll()
	tr := th.trash()
	if g := unitsOf(tr[pair])[0]["group"]; g != "hoard" {
		t.Fatalf("Hoard's shot in group %v", g)
	}
	if g := unitsOf(tr[single])[0]["group"]; g != "web" {
		t.Fatalf("the web's move in group %v", g)
	}
	w := th.call("POST", "/web/trash/empty", map[string]any{"groups": []string{"web"}, "confirm": map[string]any{"count": 0, "size": 0}})
	expect(t, w, 409, "conflict")
	d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any)
	groups := d["groups"].(map[string]any)
	web, hoard := groups["web"].(map[string]any), groups["hoard"].(map[string]any)
	if web["count"] != float64(1) || hoard["count"] != float64(1) || groups["compressed"].(map[string]any)["count"] != float64(0) ||
		d["count"] != float64(1) || d["size"] != web["size"] {
		t.Fatalf("%v", d)
	}
	w = th.call("POST", "/web/trash/empty", map[string]any{"groups": []string{"web"}, "confirm": map[string]any{"count": 1, "size": web["size"]}})
	expect(t, w, 200, "")
	if got := th.inTrash(files); len(got) != len(files) {
		t.Fatalf("Hoard's shot was emptied with the web's group: %v", got)
	}
	th.walkAll()
	if _, ok := th.trash()[single]; ok {
		t.Fatal("the web's move stays")
	}
	expect(t, th.call("POST", "/web/trash/empty", map[string]any{"groups": []string{"bogus"}}), 422, "invalid_request")
}
