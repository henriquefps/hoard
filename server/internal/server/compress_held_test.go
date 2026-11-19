package server

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/store"
)

// 0.13.1 (ADR 0025 Amendment (S1), "The trash link is held"): between the replace's trash link and its swap the trash
// entry is served held, and a swap whose trash link was deleted in that window (an Empty) aborts with the original at
// its path.

// The swap hook pauses the journal between the link and the rename.
func TestCompressTrashLinkHeldUntilTheSwap(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	orig := ch.fp("A.MP4")
	id := store.ItemID(orig)
	var during any
	ch.srv.compress.SetSwapHook(func(string) {
		ch.walkAll() // a walk (or Hoard's sync) in the window lists the new trash entry
		if l := trashLocation(t, getItem(t, ch.harness, ch.key, id, "?include=trash")); l != nil {
			during = l["held"]
		}
	})
	ch.job(map[string]any{"itemIds": []string{id}}, nil)
	ch.runAll()
	ch.srv.compress.SetSwapHook(nil)
	if during != true {
		t.Fatalf("the trash link was not held between link and swap: %v", during)
	}
	if it := ch.one(); it.State != store.CompressReplaced {
		t.Fatalf("%+v", it)
	}
	ch.walkAll()
	if l := trashLocation(t, getItem(t, ch.harness, ch.key, id, "?include=trash")); l == nil || l["held"] != nil {
		t.Fatalf("still held after the replace: %v", l)
	}
}

// The trash link deleted in the window (right before the rename, or before the swap step looks): the swap aborts, the
// original stays at its path, the output is not swapped in and its temporary copy is removed.
func TestCompressTrashLinkDeletedBeforeTheSwap(t *testing.T) {
	for _, at := range []string{"before the rename", "at the swap step"} {
		t.Run(at, func(t *testing.T) {
			ch := compressHarness(t, true, "A.MP4")
			orig := ch.fp("A.MP4")
			empty := func() {
				for _, p := range trashDirs(t, ch.nas) {
					if err := os.Remove(filepath.Join(ch.nas, p)); err != nil {
						t.Fatal(err)
					}
				}
			}
			if at == "before the rename" {
				ch.srv.compress.SetSwapHook(func(string) { empty() })
			} else {
				ch.srv.compress.SetJournalHook(func(s string) error {
					if s == "replace:swap" {
						empty()
					}
					return nil
				})
			}
			ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
			ch.runAll()
			it := ch.one()
			if it.State != store.CompressFailed || it.Reason != compress.ReasonTrashFailed || it.TrashPath != "" {
				t.Fatalf("%+v", it)
			}
			if ch.fp("A.MP4") != orig {
				t.Fatal("the original is not at its path")
			}
			if left, _ := filepath.Glob(filepath.Join(ch.nas, ".A.MP4*")); len(left) != 0 {
				t.Fatalf("left %v", left)
			}
		})
	}
}

// The same for a restore: its trash step's link of the output deleted right before the rename aborts the restore, the
// output stays at its path and the original in the trash; in the window the new entry is held.
func TestCompressRestoreTrashLinkHeldAndDeleted(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	orig := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(orig)}}, nil)
	ch.runAll()
	it := ch.one()
	out := ch.fp("A.MP4")
	var during any
	ch.srv.compress.SetSwapHook(func(trash string) {
		if trash == it.TrashPath {
			t.Errorf("the swap rechecked the original's entry, not the new one: %s", trash)
		}
		ch.walkAll()
		for _, f := range getItem(t, ch.harness, ch.key, store.ItemID(out), "?include=trash")["files"].([]any) {
			for _, l := range f.(map[string]any)["locations"].([]any) {
				if lm := l.(map[string]any); lm["trash"] == trash {
					during = lm["held"]
				}
			}
		}
		if err := os.Remove(filepath.Join(ch.nas, trash)); err != nil {
			t.Fatal(err)
		}
	})
	ch.srv.compress.Restore(t.Context(), []int64{it.ID})
	ch.srv.compress.RunOps(t.Context())
	ch.srv.compress.SetSwapHook(nil)
	if during != true {
		t.Fatalf("the restore's trash link was not held before the swap: %v", during)
	}
	after := ch.one()
	if after.State != store.CompressReplaced || after.Reason != compress.ReasonTrashFailed || after.TrashPath != it.TrashPath {
		t.Fatalf("%+v", after)
	}
	if ch.fp("A.MP4") != out || ch.fp(it.TrashPath) != orig {
		t.Fatal("the restore moved something")
	}
}
