package server

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/store"
)

// hoard-server 0.13.2 (ADR 0025 Amendment (0.13.2)).

// A statfs error fails closed: the NAS replace goes back to ready with no_space and the original stays; the work
// directory's check puts the item back in the queue; both run once the free space can be read again.
func TestCompressFreeSpaceUnknownFailsClosed(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4", "B.MP4")
	ctx := t.Context()
	origA := ch.fp("A.MP4")
	fail := map[string]bool{"nas": true}
	ch.srv.compress.SetFreeHook(func(where string) error {
		if fail[where] {
			return errors.New("statfs: input/output error")
		}
		return nil
	})
	ch.job(map[string]any{"itemIds": []string{store.ItemID(origA)}}, nil)
	ch.runAll()
	it := ch.one()
	if it.State != store.CompressReady || it.Reason != "no_space" || it.NotBefore <= time.Now().UnixMilli() {
		t.Fatalf("nas statfs error: %+v", it)
	}
	if ch.fp("A.MP4") != origA || len(trashDirs(t, ch.nas)) != 0 {
		t.Fatal("replaced without knowing the free space")
	}

	// The work directory's check.
	fail = map[string]bool{"work": true}
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("B.MP4")}}, nil)
	ch.runAll()
	var b store.CompressItem
	for _, x := range ch.items() {
		if x.ID != it.ID {
			b = x
		}
	}
	if b.State != store.CompressQueued || b.Reason != "no_space" || b.Attempts != 0 || exists(filepath.Join(ch.backup, ".hoard-compress/work", fmt.Sprint(b.ID))) {
		t.Fatalf("work statfs error: %+v", b)
	}

	// Readable again: both go on later.
	fail = map[string]bool{}
	ch.srv.compress.SetFreeHook(nil)
	ch.st.SetNow(func() time.Time { return time.Now().Add(15 * time.Minute) })
	defer ch.st.SetNow(time.Now)
	ch.srv.compress.Housekeeping(ctx)
	ch.runAll()
	for _, x := range ch.items() {
		if x.State != store.CompressReplaced {
			t.Fatalf("after: %+v", x)
		}
	}
	if _, err := os.Stat(filepath.Join(ch.nas, "A.MP4")); err != nil {
		t.Fatal(err)
	}
}

// #73 review: after a rename on the phone (a move that gave the file a new mtime), /restored and /replaced check the
// mtime against the live entry, not the item's first one; a wrong mtime is still refused.
func TestCompressPhoneReportMTimeOfTheLiveEntry(t *testing.T) {
	p := newPhoneH(t)
	orig := phoneVideo(131)
	p.backUp("DCIM/M.mp4", orig)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	it := p.one()
	id := fmt.Sprint(it.ID)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	const moved = "DCIM/Trip/M.mp4"
	later := int64(phoneMTime + 3_600_000)
	e := p.outEntry(moved, it)
	e["modifiedAt"] = later
	p.report(p.ph, p.root, []map[string]any{e}, []map[string]any{{"path": "DCIM/M.mp4", "reason": "moved"}})
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")

	body := p.origBody(it)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/restored", body, bearer(p.ph.key)), 409, "conflict") // the first entry's mtime
	body["modifiedAt"] = later
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/restored", body, bearer(p.ph.key)), 200, "")
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/restored", body, bearer(p.ph.key)), 200, "") // the same report again
	if got := p.entryAt(p.ph, moved); got.Fingerprint != it.Fingerprint || got.ModifiedAt != later {
		t.Fatalf("restored entry %+v", got)
	}
	// Restore again: /replaced at the moved path with its mtime.
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")
	out := p.outBody(it)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", out, bearer(p.ph.key)), 409, "conflict")
	out["modifiedAt"] = later
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", out, bearer(p.ph.key)), 200, "")
}

// compressItemsJSON: the live owners of many phone items in one read transaction give what one lookup each gave.
func TestCompressLiveOwnersBatch(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	for i, name := range []string{"DCIM/1.mp4", "DCIM/2.mp4", "DCIM/3.mp4"} {
		p.backUp(name, phoneVideo(uint64(141+i)))
	}
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	items := p.items()
	if len(items) != 3 {
		t.Fatalf("%d items", len(items))
	}
	for i, x := range items { // the one at DCIM/1.mp4 first
		if x.RelPath == "DCIM/1.mp4" {
			items[0], items[i] = items[i], items[0]
		}
	}
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/replaced", items[0].ID), p.outBody(items[0]), bearer(p.ph.key)), 200, "")
	p.report(p.ph, p.root, []map[string]any{p.outEntry("DCIM/Moved/1.mp4", p.item(items[0].ID))},
		[]map[string]any{{"path": "DCIM/1.mp4", "reason": "moved"}})
	ids := []int64{items[0].ID, items[1].ID, items[2].ID, 999999}
	batch, err := p.st.CompressLiveOwners(ctx, ids)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range ids {
		one, err := p.st.CompressLiveOwner(ctx, id)
		got, ok := batch[id]
		if (err == nil) != ok || (ok && got.ID != one.ID) {
			t.Fatalf("item %d: batch %+v %v, one %+v %v", id, got, ok, one, err)
		}
	}
	if batch[items[0].ID].Path != "DCIM/Moved/1.mp4" {
		t.Fatalf("%+v", batch[items[0].ID])
	}
	w := p.do("GET", "/v1/compress/items?mine=true", nil, bearer(p.ph.key))
	expect(t, w, 200, "")
	for _, x := range decode(t, w)["items"].([]any) {
		m := x.(map[string]any)
		if m["id"] == fmt.Sprint(items[0].ID) && m["path"] != "DCIM/Moved/1.mp4" {
			t.Fatalf("served %v", m)
		}
	}
}

// The estimate says how many sub-folders of a selected folder hold its videos.
func TestCompressEstimateSubfolders(t *testing.T) {
	ch := compressHarness(t, true, "top.MP4", "a/1.MP4", "a/x/2.MP4", "b/3.MP4")
	est := func(sel map[string]any) float64 {
		w := ch.do("POST", "/v1/compress/estimate", map[string]any{"target": "nas", "select": sel}, bearer(ch.key))
		expect(t, w, 200, "")
		return decode(t, w)["subfolders"].(float64)
	}
	if n := est(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder}}}); n != 3 {
		t.Fatalf("whole folder: %v", n)
	}
	if n := est(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder, "path": "a"}}}); n != 1 {
		t.Fatalf("a: %v", n)
	}
	if n := est(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder, "recursive": false}}}); n != 0 {
		t.Fatalf("not recursive: %v", n)
	}
	if n := est(map[string]any{"itemIds": []string{ch.itemOf("a/x/2.MP4")}}); n != 0 {
		t.Fatalf("items: %v", n)
	}
}
