package server

import (
	"fmt"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/store"
)

// ADR 0025 Amendment (S2) "Follow-ups": a served output whose phone never reports (stalled, cancel once the backup no
// longer has the original, the admin's force-cancel), the path served after a rename on the phone, and the kept entry
// linked to its item exactly.

// servedItem backs up a video, compresses it for the phone and serves its output (the phone downloaded it).
func (p *phoneH) servedItem(path string, seed uint64) store.CompressItem {
	p.t.Helper()
	v := phoneVideo(seed)
	p.backUp(path, v)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"itemIds": []string{store.ItemID(fingerprint.Bytes(v))}}, nil)
	p.runAll()
	var it store.CompressItem
	for _, x := range p.items() {
		if x.Fingerprint == fingerprint.Bytes(v) {
			it = x
		}
	}
	expect(p.t, p.do("GET", fmt.Sprintf("/v1/compress/items/%d/output", it.ID), nil, bearer(p.ph.key)), 200, "")
	if got := p.item(it.ID); got.State != store.CompressReady || got.ReplaceStep != store.StepServed {
		p.t.Fatalf("not served: %+v", got)
	}
	return p.item(it.ID)
}

func (p *phoneH) attention() map[string]any {
	p.t.Helper()
	return decode(p.t, p.do("GET", "/admin/api/compress", nil, adminHeaders()))["attention"].(map[string]any)
}

func (p *phoneH) cancelItem(id int64) map[string]any {
	p.t.Helper()
	sid := fmt.Sprint(id)
	w := p.do("POST", "/v1/compress/cancel", map[string]any{"itemIds": []string{sid}}, bearer(p.ph.key))
	expect(p.t, w, 200, "")
	return decode(p.t, w)["items"].(map[string]any)[sid].(map[string]any)
}

// A served output no phone reported for a day counts as stalled (attention, the admin page's waiting list); a cancel
// still leaves it ready while the backup has the original; the admin's force-cancel drops the output, keeps the
// original (the backup's), and a late report is refused.
func TestCompressServedOutputStalledAndForced(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	it := p.servedItem("DCIM/S.mp4", 131)

	p.srv.compress.Housekeeping(ctx)
	if a := p.attention(); a["stalled"].(float64) != 0 {
		t.Fatalf("stalled at once: %v", a)
	}
	p.st.SetNow(func() time.Time { return time.Now().Add(25 * time.Hour) })
	p.srv.compress.Housekeeping(ctx)
	p.st.SetNow(time.Now)
	if a := p.attention(); a["stalled"].(float64) != 1 || a["stalledSince"] == nil {
		t.Fatalf("not stalled after a day: %v", a)
	}
	page := decode(t, p.do("GET", "/admin/api/compress", nil, adminHeaders()))
	waiting := page["waiting"].([]any)
	if len(waiting) != 1 || waiting[0].(map[string]any)["id"] != fmt.Sprint(it.ID) || waiting[0].(map[string]any)["stalledAt"] == nil {
		t.Fatalf("waiting %v", waiting)
	}
	// The phone's backup still has the original: a cancel leaves it to the phone.
	if a := p.cancelItem(it.ID); a["state"] != "ready" {
		t.Fatalf("cancel %v", a)
	}
	// The force-cancel needs the admin.
	path := fmt.Sprintf("/admin/api/compress/items/%d/force-cancel", it.ID)
	if w := p.do("POST", path, nil, bearer(p.ph.key)); w.Code == 200 {
		t.Fatal("a device forced it")
	}
	w := p.do("POST", path, nil, adminHeaders())
	expect(t, w, 200, "")
	if got := decode(t, w); got["state"] != "cancelled" || got["reason"] != store.ReasonForced {
		t.Fatalf("force %v", got)
	}
	if exists(p.blobFile(it.OutSHA256)) || !exists(p.blobFile(it.SHA256)) {
		t.Fatal("the force-cancel did not drop the output or lost the original")
	}
	if a := p.attention(); a["stalled"].(float64) != 0 {
		t.Fatalf("still stalled: %v", a)
	}
	if e := p.entryAt(p.ph, "DCIM/S.mp4"); e.Fingerprint != it.Fingerprint || !e.OnDevice {
		t.Fatalf("the backup's entry changed: %+v", e)
	}
	// The original is the backup's now, not held by a compression.
	if w := p.do("DELETE", "/v1/originals/"+it.Fingerprint, nil, bearer(p.ph.key)); w.Code == 409 && decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"] == "compressed" {
		t.Fatalf("still held by the compression: %s", w.Body.String())
	}
	// The phone's late report finds nothing to complete; again is a conflict too.
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/replaced", it.ID), p.outBody(it), bearer(p.ph.key)), 409, "conflict")
	expect(t, p.do("POST", path, nil, adminHeaders()), 409, "conflict")
}

// The admin purges the phone's backup while a served output waits (0.14.0, ADR 0024 Amendment (0.14.0)): the phone may
// have written the output, so the original the server holds is confirmed with the compressed originals; the purge ends
// the item (cancelled, backup_purged) and releases both blobs, which nothing else names.
func TestCompressServedOutputCancelledOnceTheBackupIsGone(t *testing.T) {
	p := newPhoneH(t)
	it := p.servedItem("DCIM/P.mp4", 141)
	w := p.do("POST", "/admin/api/backup/"+p.ph.physical+"/purge", map[string]any{"confirmPinned": 0}, adminHeaders())
	expect(t, w, 409, "conflict")
	if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["compressedKept"] != float64(1) {
		t.Fatalf("%v", d)
	}
	expect(t, p.do("POST", "/admin/api/backup/"+p.ph.physical+"/purge", map[string]any{"confirmPinned": 0, "confirmCompressed": 1}, adminHeaders()), 200, "")
	got := p.item(it.ID)
	if got.State != store.CompressCancelled || got.Reason != store.ReasonBackupPurged || exists(p.blobFile(it.OutSHA256)) || exists(p.blobFile(it.SHA256)) {
		t.Fatalf("settle: %+v", got)
	}
	w = p.do("DELETE", "/v1/originals/"+it.Fingerprint, nil, bearer(p.ph.key))
	if w.Code == 409 {
		t.Fatalf("still held: %s", w.Body.String())
	}
}

// A cancel also settles a served output whose original is nowhere on the phone's backup any more (deleted from the
// phone and its entry history gone is the purge above; here the phone moved it out of every root: removed "moved").
// The output the phone's backup already holds stays.
func TestCompressServedOutputCancelKeepsWhatTheMirrorNames(t *testing.T) {
	p := newPhoneH(t)
	it := p.servedItem("DCIM/M.mp4", 151)
	// The phone wrote the output, then the file moved out of the backup's roots before either report arrived: the
	// backup has the original nowhere, and the output under another root's path is an ordinary file of the backup.
	other := p.addRoot(p.ph, "primary", "Movies", 201)["id"].(string)
	p.report(p.ph, p.root, nil, []map[string]any{{"path": "DCIM/M.mp4", "reason": "moved"}})
	if st := states(p.report(p.ph, other, []map[string]any{p.outEntry("Movies/M.mp4", it)}, nil)); st[0] != "stored" {
		t.Fatalf("output elsewhere %v", st)
	}
	if a := p.cancelItem(it.ID); a["state"] != "cancelled" {
		t.Fatalf("cancel %v (%+v)", a, p.item(it.ID))
	}
	if p.item(it.ID).Reason != store.ReasonPhoneGone || !exists(p.blobFile(it.SHA256)) {
		t.Fatalf("%+v", p.item(it.ID))
	}
	if e := p.entryAt(p.ph, "Movies/M.mp4"); e.Fingerprint != it.OutFingerprint || e.Store != store.StoreBlob {
		t.Fatalf("the output's entry %+v", e)
	}
	if !exists(p.blobFile(it.OutSHA256)) {
		t.Fatal("the output the backup holds was dropped")
	}
}

// The admin gives up a phone restore that never reports: back to replaced, the retention restarted, nothing deleted.
func TestCompressForceCancelPhoneRestore(t *testing.T) {
	p := newPhoneH(t)
	v := phoneVideo(161)
	p.backUp("DCIM/R.mp4", v)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	it := p.one()
	id := fmt.Sprint(it.ID)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	before := *p.item(it.ID).PurgeAfter
	later := time.Now().Add(2 * time.Hour)
	p.st.SetNow(func() time.Time { return later })
	defer p.st.SetNow(time.Now)
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")
	w := p.do("POST", "/admin/api/compress/items/"+id+"/force-cancel", nil, adminHeaders())
	expect(t, w, 200, "")
	got := p.item(it.ID)
	if got.State != store.CompressReplaced || got.Reason != store.ReasonForced || got.ReplaceStep != "" || *got.PurgeAfter <= before {
		t.Fatalf("%+v", got)
	}
	p.srv.compress.Housekeeping(t.Context())
	if !exists(p.blobFile(it.SHA256)) || !exists(p.blobFile(it.OutSHA256)) || p.item(it.ID).State != store.CompressReplaced {
		t.Fatal("a forced restore lost a side")
	}
	// A ready item never served can be forced too; a purged one cannot.
	expect(t, p.do("POST", "/admin/api/compress/items/999999/force-cancel", nil, adminHeaders()), 404, "not_found")
}

// The path served after a rename on the phone: replaced, replacing (a restore) and restored items name the file
// where the phone keeps it now, not the kept side's old path, and the phone's restore there completes.
func TestCompressServedPathFollowsARename(t *testing.T) {
	p := newPhoneH(t)
	orig := phoneVideo(171)
	p.backUp("DCIM/N.mp4", orig)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	it := p.one()
	id := fmt.Sprint(it.ID)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	const moved = "DCIM/Trip/N2.mp4"
	p.report(p.ph, p.root, []map[string]any{p.outEntry(moved, it)}, []map[string]any{{"path": "DCIM/N.mp4", "reason": "moved"}})
	served := func(state string) map[string]any {
		t.Helper()
		w := p.do("GET", "/v1/compress/items?mine=true&state="+state, nil, bearer(p.ph.key))
		expect(t, w, 200, "")
		for _, x := range decode(t, w)["items"].([]any) {
			if m := x.(map[string]any); m["id"] == id {
				return m
			}
		}
		t.Fatalf("item not listed in %s", state)
		return nil
	}
	if m := served("replaced"); m["path"] != moved || m["name"] != "N2.mp4" {
		t.Fatalf("replaced served %v", m)
	}
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")
	m := served("replacing")
	if m["path"] != moved || m["direction"] != "restore" || m["modifiedAt"].(float64) != phoneMTime {
		t.Fatalf("replacing served %v", m)
	}
	// The phone writes the original at the served path and reports it: the restore completes there.
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/restored", p.origBody(it), bearer(p.ph.key)), 200, "")
	if e := p.entryAt(p.ph, moved); e.Fingerprint != it.Fingerprint || !e.OnDevice {
		t.Fatalf("restored entry %+v", e)
	}
	if m := served("restored"); m["path"] != moved {
		t.Fatalf("restored served %v", m)
	}
	if got := p.item(it.ID); got.State != store.CompressRestored {
		t.Fatalf("%+v", got)
	}
}

// The same video at two paths, compressed one after the other: each item brings back and purges exactly its own kept
// entry. The first one was renamed on the phone before its replace (its kept entry at the new path), so a lookup by
// content and path would leave it behind at the purge and hand it to the second item's restore.
func TestCompressKeptEntryIsTheItemsOwn(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	v := phoneVideo(181)
	fp := fingerprint.Bytes(v)
	p.backUp("DCIM/A.mp4", v)
	if st := states(p.report(p.ph, p.root, []map[string]any{fileEntry("DCIM/B.mp4", v, true)}, nil)); st[0] != "stored" {
		t.Fatalf("B %v", st)
	}
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"itemIds": []string{store.ItemID(fp)}}, nil)
	p.runAll()
	first := p.one()
	if first.RelPath != "DCIM/A.mp4" {
		t.Fatalf("first %+v", first)
	}
	// A renamed to C on the phone, then replaced there.
	p.report(p.ph, p.root, []map[string]any{fileEntry("DCIM/C.mp4", v, true)}, []map[string]any{{"path": "DCIM/A.mp4", "reason": "moved"}})
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/replaced", first.ID), p.outBody(first), bearer(p.ph.key)), 200, "")
	firstKept := *p.item(first.ID).KeptEntryID
	if e := p.entry(firstKept); e.Path != "DCIM/C.mp4" || e.RemovedReason != store.RemovedCompressed {
		t.Fatalf("first kept %+v", e)
	}
	compressedOf := func(fp string) []store.MirrorEntry {
		t.Helper()
		es, err := p.st.MirrorEntriesOfRoot(ctx, p.root)
		if err != nil {
			t.Fatal(err)
		}
		var out []store.MirrorEntry
		for _, e := range es {
			if e.Fingerprint == fp && e.RemovedReason == store.RemovedCompressed {
				out = append(out, e)
			}
		}
		return out
	}
	// The purge removes exactly that entry (at C, not the item's own path A).
	w := p.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{fmt.Sprint(first.ID)}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	if left := compressedOf(fp); len(left) != 0 {
		t.Fatalf("the purge left kept entries: %+v", left)
	}

	// B is compressed now.
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"itemIds": []string{store.ItemID(fp)}}, nil)
	p.runAll()
	var second store.CompressItem
	for _, x := range p.items() {
		if x.ID != first.ID {
			second = x
		}
	}
	if second.RelPath != "DCIM/B.mp4" || second.State != store.CompressReady {
		t.Fatalf("second %+v", second)
	}
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/replaced", second.ID), p.outBody(second), bearer(p.ph.key)), 200, "")
	secondKept := *p.item(second.ID).KeptEntryID
	if secondKept != *second.MirrorEntryID {
		t.Fatalf("second kept %d, entry %d", secondKept, *second.MirrorEntryID)
	}
	// B renamed to D on the phone, then restored by the phone there: B's own kept entry comes back at D.
	p.report(p.ph, p.root, []map[string]any{p.outEntry("DCIM/D.mp4", second)}, []map[string]any{{"path": "DCIM/B.mp4", "reason": "moved"}})
	sid := fmt.Sprint(second.ID)
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{sid}}, bearer(p.ph.key)), 200, "")
	expect(t, p.do("POST", "/v1/compress/items/"+sid+"/restored", p.origBody(second), bearer(p.ph.key)), 200, "")
	if e := p.entryAt(p.ph, "DCIM/D.mp4"); e.ID != secondKept || e.Fingerprint != fp || !e.OnDevice {
		t.Fatalf("D %+v, want entry %d", e, secondKept)
	}
	// Restore again: the output's kept entry (the one the restore made compressed) comes back, nothing new.
	outKept := *p.item(second.ID).KeptEntryID
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{sid}}, bearer(p.ph.key)), 200, "")
	expect(t, p.do("POST", "/v1/compress/items/"+sid+"/replaced", p.outBody(second), bearer(p.ph.key)), 200, "")
	if e := p.entryAt(p.ph, "DCIM/D.mp4"); e.ID != outKept || e.Fingerprint != second.OutFingerprint {
		t.Fatalf("D after restore again %+v, want entry %d", e, outKept)
	}
	if got := p.item(second.ID); *got.KeptEntryID != secondKept {
		t.Fatalf("kept %d, want %d", *got.KeptEntryID, secondKept)
	}
	p.noPreviousVersions()
}
