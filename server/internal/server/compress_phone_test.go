package server

import (
	"encoding/json"
	"fmt"
	"math/rand/v2"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/mirror"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Video compression of phone backups (ADR 0025 phase S2) end to end with the fake encoder and verifier: synthetic
// videos backed up from a phone (ADR 0024), compressed into blobs, swapped at the phone path by the phone's report (in
// either order) or by the server alone, restored, purged; ownership rule 5.

const phoneMTime = 1730455000000 // fileEntry's modifiedAt

type phoneH struct {
	*cHarness
	ph   phoneDev
	root string
}

func newPhoneH(t *testing.T) *phoneH {
	t.Helper()
	ch := compressHarness(t, true)
	ph := ch.phone("Pixel 8", "hoard")
	root := ch.addRoot(ph, "primary", "DCIM", 201)["id"].(string)
	return &phoneH{cHarness: ch, ph: ph, root: root}
}

// phoneVideo is 8 MB of synthetic bytes (one upload chunk), first byte 0 (the fake verifier reads the q from it).
func phoneVideo(seed uint64) []byte {
	b := make([]byte, 8<<20)
	r := rand.New(rand.NewPCG(seed, 11))
	for i := range b {
		b[i] = byte(r.Uint32())
	}
	b[0] = 0
	return b
}

// backUp uploads a phone video (the mirror's own upload) and reports it; its probe is stored.
func (p *phoneH) backUp(path string, data []byte) {
	p.t.Helper()
	uploadFor(p.t, p.harness, p.ph.key, data, false)
	if st := states(p.report(p.ph, p.root, []map[string]any{fileEntry(path, data, true)}, nil)); len(st) != 1 || st[0] != "stored" {
		p.t.Fatalf("backup %v", st)
	}
	p.probe(fingerprint.Bytes(data))
}

func (p *phoneH) probe(fp string) {
	p.t.Helper()
	b, _ := json.Marshal(testProbe)
	if err := p.st.PutVideoProbe(p.t.Context(), fp, compress.ProbeVersion, b); err != nil {
		p.t.Fatal(err)
	}
}

func (p *phoneH) index() { p.t.Helper(); p.srv.IndexNow(p.t.Context()) }

func (p *phoneH) jobAs(key, target string, sel map[string]any, extra map[string]any) map[string]any {
	p.t.Helper()
	body := map[string]any{"target": target, "select": sel, "requestId": fmt.Sprint(rand.Uint64())}
	for k, v := range extra {
		body[k] = v
	}
	w := p.do("POST", "/v1/compress/jobs", body, bearer(key))
	if w.Code != 201 && w.Code != 200 {
		p.t.Fatalf("job: %d %s", w.Code, w.Body.String())
	}
	return decode(p.t, w)
}

func (p *phoneH) item(id int64) store.CompressItem {
	p.t.Helper()
	it, err := p.st.CompressItem(p.t.Context(), id)
	if err != nil {
		p.t.Fatal(err)
	}
	return it
}

func (p *phoneH) entry(id int64) store.MirrorEntry {
	p.t.Helper()
	es, err := p.st.MirrorEntries(p.t.Context(), []int64{id})
	if err != nil || len(es) != 1 {
		p.t.Fatalf("entry %d: %v", id, err)
	}
	return es[0]
}

// phoneFile is the body of …/replaced and …/restored.
func phoneFile(fp, sha string, size int64) map[string]any {
	return map[string]any{"fingerprint": fp, "sha256": sha, "size": size, "modifiedAt": phoneMTime}
}

func (p *phoneH) outBody(it store.CompressItem) map[string]any {
	return phoneFile(it.OutFingerprint, it.OutSHA256, *it.OutSize)
}

func (p *phoneH) origBody(it store.CompressItem) map[string]any {
	return phoneFile(it.Fingerprint, it.SHA256, it.Size)
}

// outEntry is the report entry of the output at a phone path (what the phone's next backup pass sends).
func (p *phoneH) outEntry(path string, it store.CompressItem) map[string]any {
	return map[string]any{"path": path, "fingerprint": it.OutFingerprint, "sha256": it.OutSHA256, "size": *it.OutSize, "modifiedAt": phoneMTime}
}

func (p *phoneH) noPreviousVersions() {
	p.t.Helper()
	es, err := p.st.MirrorEntriesOfRoot(p.t.Context(), p.root)
	if err != nil {
		p.t.Fatal(err)
	}
	for _, e := range es {
		if e.RemovedReason == store.RemovedReplaced || e.RemovedReason == store.RemovedDeleted {
			p.t.Fatalf("a replace made a %s entry: %+v", e.RemovedReason, e)
		}
	}
}

func itemLocations(it map[string]any) []map[string]any {
	var out []map[string]any
	for _, f := range it["files"].([]any) {
		for _, l := range f.(map[string]any)["locations"].([]any) {
			out = append(out, l.(map[string]any))
		}
	}
	return out
}

// The phone flow with the phone's report first: estimate and job of the caller's phone, the output a blob not linked,
// served by range; …/replaced swaps the entries (the original compressed, pinned, linked under .hoard-mirror/compressed/);
// the next backup report uploads nothing and removes nothing; free-up verify of the output; the original a trashed
// server item with compressedTo (include=trash, trashed=only), culling moved; restore by the phone, restore again,
// purge by route.
func TestCompressPhoneFlow(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	orig := phoneVideo(11)
	const path = "DCIM/Camera/VID_1.mp4"
	p.backUp(path, orig)
	p.index()
	origFP, origSHA := fingerprint.Bytes(orig), sum(orig)
	origItem := store.ItemID(origFP)
	origEntry := p.entryAt(p.ph, path)
	if _, err := p.st.PushCulling(ctx, p.ph.id, []store.CullChange{{ItemID: origItem, Field: store.CullRating, Value: json.RawMessage(`4`),
		ModifiedAt: 1}}); err != nil {
		t.Fatal(err)
	}

	// The estimate by root and path; another phone's estimate does not take it.
	w := p.do("POST", "/v1/compress/estimate", map[string]any{"target": "phone", "select": map[string]any{"roots": []any{map[string]any{"rootId": p.root,
		"path": "DCIM/Camera"}}}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	est := decode(t, w)["items"].([]any)
	if len(est) != 1 || est[0].(map[string]any)["decision"] != "encode" || est[0].(map[string]any)["rootId"] != p.root ||
		est[0].(map[string]any)["path"] != path {
		t.Fatalf("estimate %v", est)
	}
	w = p.do("POST", "/v1/compress/estimate", map[string]any{"target": "phone", "select": map[string]any{"itemIds": []string{origItem}}}, bearer(p.key))
	expect(t, w, 200, "")
	if it := decode(t, w)["items"].([]any)[0].(map[string]any); it["reason"] != "not_in_target" {
		t.Fatalf("another phone: %v", it)
	}
	expect(t, p.do("POST", "/v1/compress/estimate", map[string]any{"target": "phone", "select": map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}},
		bearer(p.key)), 404, "not_found")
	expect(t, p.do("POST", "/v1/compress/estimate", map[string]any{"target": "phone", "select": map[string]any{"roots": []any{map[string]any{"rootId": p.root,
		"path": "Pictures"}}}}, bearer(p.ph.key)), 422, "invalid_request")

	job := p.jobAs(p.ph.key, "phone", map[string]any{"itemIds": []string{origItem}}, nil)
	if job["queued"].(float64) != 1 || job["priority"].(float64) != 1 || job["target"] != "phone" {
		t.Fatalf("job %v", job)
	}
	p.runAll()
	it := p.one()
	if it.State != store.CompressReady || it.Source != "blob" || it.OutSHA256 == "" || it.SHA256 != origSHA || *it.MirrorEntryID != origEntry.ID {
		t.Fatalf("ready %+v", it)
	}
	if !exists(p.blobFile(it.OutSHA256)) || p.linkedTo(p.ph.physical, path, it.OutSHA256) {
		t.Fatal("the output is not a blob, or linked before the phone has it")
	}
	if left, _ := filepath.Glob(filepath.Join(p.backup, ".hoard-compress", "work", "*", "*")); len(left) != 0 {
		t.Fatalf("work files left: %v", left)
	}
	// Not a gallery item before the phone has it.
	p.index()
	if w := p.do("GET", "/v1/items/"+store.ItemID(it.OutFingerprint), nil, bearer(p.ph.key)); w.Code != 404 {
		t.Fatalf("the output is an item already: %d", w.Code)
	}

	// mine=true: the phone's items, with what it checks.
	w = p.do("GET", "/v1/compress/items?mine=true&state=ready", nil, bearer(p.ph.key))
	expect(t, w, 200, "")
	mine := decode(t, w)["items"].([]any)
	if len(mine) != 1 {
		t.Fatalf("mine %v", mine)
	}
	m := mine[0].(map[string]any)
	if m["target"] != "phone" || m["path"] != path || m["rootId"] != p.root || m["outSha256"] != it.OutSHA256 || m["sha256"] != origSHA ||
		m["modifiedAt"].(float64) != phoneMTime {
		t.Fatalf("mine item %v", m)
	}
	w = p.do("GET", "/v1/compress/items?mine=true", nil, bearer(p.key))
	expect(t, w, 200, "")
	if n := len(decode(t, w)["items"].([]any)); n != 0 {
		t.Fatalf("another phone's mine: %d", n)
	}

	// The output by range, to this phone only.
	id := fmt.Sprint(it.ID)
	expect(t, p.do("GET", "/v1/compress/items/"+id+"/output", nil, bearer(p.key)), 404, "not_found")
	w = p.do("GET", "/v1/compress/items/"+id+"/output", nil, bearer(p.ph.key))
	expect(t, w, 200, "")
	blob, _ := os.ReadFile(p.blobFile(it.OutSHA256))
	if w.Body.Len() != len(blob) || sum(w.Body.Bytes()) != it.OutSHA256 || w.Header().Get("X-Hoard-Sha256") != it.OutSHA256 ||
		w.Header().Get("X-Hoard-Fingerprint") != it.OutFingerprint || w.Header().Get("ETag") != `"`+it.OutSHA256+`"` {
		t.Fatalf("output %d %v", w.Body.Len(), w.Header())
	}
	w = p.do("GET", "/v1/compress/items/"+id+"/output", nil, bearer(p.ph.key), header("Range", "bytes=100-199"))
	if w.Code != http.StatusPartialContent || w.Body.Len() != 100 || w.Body.String() != string(blob[100:200]) {
		t.Fatalf("range %d %d", w.Code, w.Body.Len())
	}

	// A file that is not the output; a restore report while not restoring.
	bad := p.outBody(it)
	bad["sha256"] = origSHA
	w = p.do("POST", "/v1/compress/items/"+id+"/replaced", bad, bearer(p.ph.key))
	expect(t, w, 409, "conflict")
	if reason(t, w) != "mismatch" {
		t.Fatal(w.Body.String())
	}
	late := p.outBody(it)
	late["modifiedAt"] = phoneMTime + 60_000
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", late, bearer(p.ph.key)), 409, "conflict")
	w = p.do("POST", "/v1/compress/items/"+id+"/restored", p.origBody(it), bearer(p.ph.key))
	expect(t, w, 409, "conflict")
	if reason(t, w) != "state" {
		t.Fatal(w.Body.String())
	}

	// The phone wrote the output in place: …/replaced.
	w = p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key))
	expect(t, w, 200, "")
	if decode(t, w)["state"] != "replaced" {
		t.Fatal(w.Body.String())
	}
	it = p.item(it.ID)
	if it.PurgeAfter == nil || it.TrashedAt == nil || it.ReplacedAt == nil {
		t.Fatalf("replaced %+v", it)
	}
	oe := p.entry(origEntry.ID)
	if oe.RemovedReason != store.RemovedCompressed || oe.OnDevice || !p.linkedTo(p.ph.physical, mirror.CompressedPath("primary", path, origSHA), origSHA) {
		t.Fatalf("original entry %+v", oe)
	}
	cur := p.entryAt(p.ph, path)
	if cur.Fingerprint != it.OutFingerprint || !cur.OnDevice || cur.RemovedReason != "" || !p.linkedTo(p.ph.physical, path, it.OutSHA256) {
		t.Fatalf("current entry %+v", cur)
	}
	if _, pinned, _ := p.st.MirrorPurgeInfo(ctx, p.ph.physical); pinned != 1 {
		t.Fatalf("pinned %d", pinned)
	}
	// The same report again answers the item.
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")

	// The next backup pass: stored, nothing uploaded, no removal, no previous version.
	res := p.report(p.ph, p.root, []map[string]any{p.outEntry(path, it)}, nil)
	if st := states(res); len(st) != 1 || st[0] != "stored" || len(res["removed"].([]any)) != 0 {
		t.Fatalf("report %v", res)
	}
	p.noPreviousVersions()
	if n, _ := p.st.UploadCount(ctx, p.ph.id); n != 0 {
		t.Fatalf("%d uploads", n)
	}

	// Free up: the output verifies like any stored file.
	ask := func() string {
		t.Helper()
		w := p.do("POST", "/v1/backup/verify", map[string]any{"entries": []any{map[string]any{"rootId": p.root, "path": path, "sha256": it.OutSHA256,
			"size": *it.OutSize}}}, bearer(p.ph.key))
		expect(t, w, 200, "")
		return decode(t, w)["results"].([]any)[0].(map[string]any)["verified"].(string)
	}
	if v := ask(); v != "pending" {
		t.Fatalf("verify %s", v)
	}
	p.drain()
	if v := ask(); v != "yes" {
		t.Fatalf("verify %s", v)
	}

	// The items: the output with "compressed", the original a trashed server item.
	p.index()
	outItem := store.ItemID(it.OutFingerprint)
	oi := getItem(t, p.harness, p.ph.key, outItem, "")
	if c, _ := oi["compressed"].(map[string]any); c == nil || c["from"] != origFP || c["fromItem"] != origItem || c["original"] != "trashed" {
		t.Fatalf("output item %v", oi)
	}
	if tomb := getItem(t, p.harness, p.ph.key, origItem, ""); tomb["deleted"] != true || tomb["replacedBy"] != outItem {
		t.Fatalf("old-client shape %v", tomb)
	}
	whole := getItem(t, p.harness, p.ph.key, origItem, "?include=trash")
	if whole["trashed"] == nil || whole["compressedTo"] != outItem || whole["replacedBy"] != outItem {
		t.Fatalf("trashed original %v", whole)
	}
	var gone, backup bool
	for _, l := range itemLocations(whole) {
		gone = gone || l["source"] == "mirror" && l["gone"] == "compressed" && l["onDevice"] == false && l["path"] == path
		backup = backup || l["source"] == "backup" && l["sha256"] == origSHA
	}
	if !gone || !backup {
		t.Fatalf("locations %v", itemLocations(whole))
	}
	found := false
	for _, x := range page(t, p.harness, p.ph.key, "/v1/items?trashed=only&limit=50").Items {
		found = found || x["id"] == origItem
	}
	if !found {
		t.Fatal("the original is not listed with trashed=only")
	}
	if rec, err := p.st.Culling(ctx, outItem); err != nil || string(rec.Fields[store.CullRating].Value) != "4" {
		t.Fatalf("culling did not follow: %+v %v", rec, err)
	}
	expect(t, p.do("GET", "/v1/originals/"+origFP, nil, bearer(p.ph.key)), 200, "")

	// Restore: the file is on the phone, so the phone writes the original back.
	w = p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{origItem}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	if s := decode(t, w)["items"].(map[string]any)[origItem].(map[string]any)["state"]; s != "replacing" {
		t.Fatalf("restore %v", s)
	}
	w = p.do("GET", "/v1/compress/items?mine=true&state=replacing", nil, bearer(p.ph.key))
	if m := decode(t, w)["items"].([]any)[0].(map[string]any); m["direction"] != "restore" {
		t.Fatalf("restore item %v", m)
	}
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 409, "conflict")
	w = p.do("POST", "/v1/compress/items/"+id+"/restored", p.origBody(it), bearer(p.ph.key))
	expect(t, w, 200, "")
	if it = p.item(it.ID); it.State != store.CompressRestored || it.RestoredAt == nil {
		t.Fatalf("restored %+v", it)
	}
	if cur := p.entryAt(p.ph, path); cur.ID != origEntry.ID || !cur.OnDevice || !p.linkedTo(p.ph.physical, path, origSHA) {
		t.Fatalf("the original is not back at its path: %+v", cur)
	}
	p.index()
	if live := getItem(t, p.harness, p.ph.key, origItem, ""); live["deleted"] != false || live["replacedBy"] != nil {
		t.Fatalf("the original is not live: %v", live)
	}
	if o := getItem(t, p.harness, p.ph.key, outItem, "?include=trash"); o["trashed"] == nil || o["replacedBy"] != origItem {
		t.Fatalf("the output is not trashed: %v", o)
	}
	if rec, err := p.st.Culling(ctx, origItem); err != nil || string(rec.Fields[store.CullRating].Value) != "4" {
		t.Fatalf("culling did not come back: %+v %v", rec, err)
	}

	// Restore again: the phone writes the output again.
	w = p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	expect(t, p.do("GET", "/v1/compress/items/"+id+"/output", nil, bearer(p.ph.key)), 200, "")
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	if it = p.item(it.ID); it.State != store.CompressReplaced {
		t.Fatalf("replaced again %+v", it)
	}
	p.index()
	if tomb := getItem(t, p.harness, p.ph.key, origItem, ""); tomb["replacedBy"] != outItem {
		t.Fatalf("after restore again %v", tomb)
	}

	// Purge by route: the kept entry, its link and the original's blob go; a tombstone with replacedBy.
	w = p.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	if a := decode(t, w)["items"].(map[string]any)[id].(map[string]any); a["state"] != "purged" || a["alsoKeptBy"] != nil {
		t.Fatalf("purge %v", a)
	}
	if exists(p.blobFile(origSHA)) || exists(filepath.Join(p.deviceDir(p.ph.physical), mirror.CompressedPath("primary", path, origSHA))) {
		t.Fatal("the original's blob or link stays")
	}
	if _, pinned, _ := p.st.MirrorPurgeInfo(ctx, p.ph.physical); pinned != 0 {
		t.Fatalf("pinned %d", pinned)
	}
	p.index()
	tomb := getItem(t, p.harness, p.ph.key, origItem, "?include=trash")
	if tomb["deleted"] != true || tomb["replacedBy"] != outItem {
		t.Fatalf("after the purge %v", tomb)
	}
	if c := getItem(t, p.harness, p.ph.key, outItem, "")["compressed"].(map[string]any); c["original"] != "purged" {
		t.Fatalf("compressed %v", c)
	}
	expect(t, p.do("GET", "/v1/originals/"+origFP, nil, bearer(p.ph.key)), 404, "not_found")
}

// The backup's report first (ADR 0025 "Order does not matter"): the report completes the swap (stored, nothing
// uploaded, no previous version), then …/replaced answers the item as it is; the same for a phone restore.
func TestCompressPhoneReportFirst(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	orig := phoneVideo(21)
	const path = "DCIM/VID_2.mp4"
	p.backUp(path, orig)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root, "path": ""}}}, nil)
	p.runAll()
	it := p.one()
	if it.State != store.CompressReady {
		t.Fatalf("%+v", it)
	}
	id := fmt.Sprint(it.ID)
	res := p.report(p.ph, p.root, []map[string]any{p.outEntry(path, it)}, nil)
	if st := states(res); st[0] != "stored" || len(res["removed"].([]any)) != 0 {
		t.Fatalf("report %v", res)
	}
	if it = p.item(it.ID); it.State != store.CompressReplaced {
		t.Fatalf("not replaced by the report: %+v", it)
	}
	p.noPreviousVersions()
	if n, _ := p.st.UploadCount(ctx, p.ph.id); n != 0 {
		t.Fatalf("%d uploads", n)
	}
	w := p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key))
	expect(t, w, 200, "")
	if decode(t, w)["state"] != "replaced" {
		t.Fatal(w.Body.String())
	}
	if e := p.entryAt(p.ph, path); e.Fingerprint != it.OutFingerprint || !e.OnDevice || !p.linkedTo(p.ph.physical, path, it.OutSHA256) {
		t.Fatalf("%+v", e)
	}
	// A phone restore whose report comes first.
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")
	res = p.report(p.ph, p.root, []map[string]any{fileEntry(path, orig, true)}, nil)
	if st := states(res); st[0] != "stored" {
		t.Fatalf("report %v", res)
	}
	if it = p.item(it.ID); it.State != store.CompressRestored {
		t.Fatalf("not restored by the report: %+v", it)
	}
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/restored", p.origBody(it), bearer(p.ph.key)), 200, "")
	p.noPreviousVersions()
}

// Ownership rule 5: a Reveal delete is 409 compressed (its claim released) and the bytes stay until the purge; a purge
// with another claim keeps the bytes (alsoKeptBy claim); a backup purge keeps both sides until the compression's own
// purge by trashDays.
func TestCompressPhoneOwnership(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	reveal := p.phone("Pixel 8", "reveal")
	a, b, c := phoneVideo(31), phoneVideo(32), phoneVideo(33)
	p.backUp("DCIM/A.mp4", a)
	p.backUp("DCIM/B.mp4", b)
	p.backUp("DCIM/C.mp4", c)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	byFP := map[string]store.CompressItem{}
	for _, it := range p.items() {
		if it.State != store.CompressReady {
			t.Fatalf("%+v", it)
		}
		expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/replaced", it.ID), p.outBody(it), bearer(p.ph.key)), 200, "")
		byFP[it.Fingerprint] = p.item(it.ID)
	}
	ia, ib, ic := byFP[fingerprint.Bytes(a)], byFP[fingerprint.Bytes(b)], byFP[fingerprint.Bytes(c)]

	// A Reveal delete: 409 compressed, the claim released, the bytes stay; the purge then removes them.
	uploadFor(t, p.harness, reveal.key, a, true)
	w := p.do("DELETE", "/v1/originals/"+ia.Fingerprint, nil, bearer(reveal.key))
	expect(t, w, 409, "conflict")
	if reason(t, w) != "compressed" {
		t.Fatal(w.Body.String())
	}
	if cl, _ := p.st.Claims(ctx, ia.Fingerprint); len(cl) != 0 {
		t.Fatalf("claim kept %v", cl)
	}
	if !exists(p.blobFile(ia.SHA256)) {
		t.Fatal("the original went")
	}
	w = p.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{fmt.Sprint(ia.ID)}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	if exists(p.blobFile(ia.SHA256)) {
		t.Fatal("the purged original stays")
	}

	// A purge with another claim: the bytes stay, and the answer says why.
	uploadFor(t, p.harness, reveal.key, c, true)
	w = p.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{fmt.Sprint(ic.ID)}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	ans := decode(t, w)["items"].(map[string]any)[fmt.Sprint(ic.ID)].(map[string]any)
	if ans["state"] != "purged" || fmt.Sprint(ans["alsoKeptBy"]) != "[claim]" || !exists(p.blobFile(ic.SHA256)) {
		t.Fatalf("purge with a claim %v", ans)
	}
	if p.item(ic.ID).State != store.CompressPurged {
		t.Fatal("not purged")
	}

	// The admin's backup purge: both sides of B stay (rule 5) until the compression's purge by trashDays.
	_, pinned, err := p.st.MirrorPurgeInfo(ctx, p.ph.physical)
	if err != nil {
		t.Fatal(err)
	}
	expect(t, p.do("POST", "/admin/api/backup/"+p.ph.physical+"/purge", map[string]any{"confirmPinned": pinned}, adminHeaders()), 200, "")
	if !exists(p.blobFile(ib.SHA256)) || !exists(p.blobFile(ib.OutSHA256)) {
		t.Fatal("the backup purge removed a compression's blob")
	}
	if p.item(ib.ID).State != store.CompressReplaced {
		t.Fatal("the backup purge changed the compression")
	}
	p.st.SetNow(func() time.Time { return time.Now().Add(31 * 24 * time.Hour) })
	p.srv.compress.Housekeeping(ctx)
	p.st.SetNow(time.Now)
	if it := p.item(ib.ID); it.State != store.CompressPurged || exists(p.blobFile(ib.SHA256)) {
		t.Fatalf("purge by trashDays: %+v", it)
	}
}

// A server-only (freed) phone video (target backup): compressed by the server alone; the entry at the path stays freed
// and pinned with the output's content; the original a trashed server item; restore and restore again on the server;
// purge by trashDays. A phone job does not take a freed video.
func TestCompressBackupFreed(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	orig := phoneVideo(41)
	const path = "DCIM/VID_F.mp4"
	p.backUp(path, orig)
	p.report(p.ph, p.root, nil, []map[string]any{{"path": path, "reason": "freed"}})
	p.index()
	origFP, origSHA := fingerprint.Bytes(orig), sum(orig)
	origItem := store.ItemID(origFP)

	w := p.do("POST", "/v1/compress/estimate", map[string]any{"target": "phone", "select": map[string]any{"itemIds": []string{origItem}}}, bearer(p.ph.key))
	if it := decode(t, w)["items"].([]any)[0].(map[string]any); it["reason"] != "not_in_target" {
		t.Fatalf("phone target of a freed video: %v", it)
	}
	job := p.jobAs(p.key, "backup", map[string]any{"roots": []any{map[string]any{"rootId": p.root, "path": "DCIM"}}}, nil)
	if job["priority"].(float64) != 2 {
		t.Fatalf("job %v", job)
	}
	p.runAll()
	it := p.one()
	if it.State != store.CompressReplaced {
		t.Fatalf("%+v", it)
	}
	cur := p.entryAt(p.ph, path)
	if cur.Fingerprint != it.OutFingerprint || cur.OnDevice || cur.RemovedReason != store.RemovedFreed || !p.linkedTo(p.ph.physical, path, it.OutSHA256) {
		t.Fatalf("the path's entry %+v", cur)
	}
	if _, pinned, _ := p.st.MirrorPurgeInfo(ctx, p.ph.physical); pinned != 2 {
		t.Fatalf("pinned %d (the output freed, the original compressed)", pinned)
	}
	p.index()
	outItem := store.ItemID(it.OutFingerprint)
	var freed bool
	for _, l := range itemLocations(getItem(t, p.harness, p.ph.key, outItem, "")) {
		freed = freed || l["source"] == "mirror" && l["gone"] == "freed"
	}
	if !freed {
		t.Fatal("the output is not the freed file")
	}
	if whole := getItem(t, p.harness, p.ph.key, origItem, "?include=trash"); whole["trashed"] == nil || whole["compressedTo"] != outItem {
		t.Fatalf("trashed original %v", whole)
	}

	// Restore and restore again: on the server, at once.
	id := fmt.Sprint(it.ID)
	w = p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{origItem}}, bearer(p.key))
	expect(t, w, 200, "")
	if s := decode(t, w)["items"].(map[string]any)[origItem].(map[string]any)["state"]; s != "restored" {
		t.Fatalf("restore %v", s)
	}
	if cur := p.entryAt(p.ph, path); cur.Fingerprint != origFP || cur.RemovedReason != store.RemovedFreed || !p.linkedTo(p.ph.physical, path, origSHA) {
		t.Fatalf("restored entry %+v", cur)
	}
	p.index()
	if live := getItem(t, p.harness, p.ph.key, origItem, ""); live["deleted"] != false {
		t.Fatalf("%v", live)
	}
	w = p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.key))
	if s := decode(t, w)["items"].(map[string]any)[id].(map[string]any)["state"]; s != "replaced" {
		t.Fatalf("restore again %v", s)
	}
	if _, pinned, _ := p.st.MirrorPurgeInfo(ctx, p.ph.physical); pinned != 2 {
		t.Fatalf("pinned %d", pinned)
	}

	// Purge by trashDays.
	p.st.SetNow(func() time.Time { return time.Now().Add(31 * 24 * time.Hour) })
	p.srv.compress.Housekeeping(ctx)
	p.st.SetNow(time.Now)
	if it := p.item(it.ID); it.State != store.CompressPurged || exists(p.blobFile(origSHA)) {
		t.Fatalf("%+v", it)
	}
	p.index()
	if tomb := getItem(t, p.harness, p.ph.key, origItem, "?include=trash"); tomb["deleted"] != true || tomb["replacedBy"] != outItem {
		t.Fatalf("%v", tomb)
	}
	if cur := p.entryAt(p.ph, path); cur.Fingerprint != it.OutFingerprint || cur.RemovedReason != store.RemovedFreed {
		t.Fatalf("%+v", cur)
	}
}

// "Compress automatically" on a phone folder: only videos new in the folder's backup since the switch (not the output
// of a replace); on a phone backup (target backup): only videos new since the switch that are no longer on the phone. A
// video backed up before the switch is never taken automatically, even once freed (the owner's rule, review B1).
func TestCompressAutomaticPhoneFolder(t *testing.T) {
	p := newPhoneH(t)
	old, fresh := phoneVideo(51), phoneVideo(52)
	p.backUp("DCIM/old.mp4", old)
	p.index()
	sel := map[string]any{"roots": []any{map[string]any{"rootId": p.root, "path": "DCIM"}}}
	job := p.jobAs(p.ph.key, "phone", sel, map[string]any{"auto": true})
	if job["auto"] != true || job["queued"].(float64) != 0 || job["priority"].(float64) != 3 {
		t.Fatalf("job %v", job)
	}
	w := p.do("POST", "/v1/compress/jobs", map[string]any{"target": "phone", "select": sel, "auto": true, "requestId": "again"}, bearer(p.ph.key))
	expect(t, w, 409, "conflict")
	if reason(t, w) != "exists" {
		t.Fatal(w.Body.String())
	}
	time.Sleep(5 * time.Millisecond)
	p.backUp("DCIM/new.mp4", fresh)
	p.index()
	list := p.items()
	if len(list) != 1 || list[0].Fingerprint != fingerprint.Bytes(fresh) || list[0].Priority != 3 || list[0].Source != "blob" {
		t.Fatalf("appended %+v", list)
	}
	p.runAll()
	it := p.one()
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/replaced", it.ID), p.outBody(p.item(it.ID)), bearer(p.ph.key)), 200, "")
	p.report(p.ph, p.root, []map[string]any{p.outEntry("DCIM/new.mp4", p.item(it.ID))}, nil)
	p.index()
	if n := len(p.items()); n != 1 {
		t.Fatalf("the output was appended: %d items", n)
	}

	// The server-side switch on the phone's backup: the old video freed after it is not new; a video recorded after it,
	// then freed, is.
	p.jobAs(p.key, "backup", sel, map[string]any{"auto": true})
	time.Sleep(5 * time.Millisecond)
	later := phoneVideo(53)
	p.backUp("DCIM/later.mp4", later)
	p.report(p.ph, p.root, nil, []map[string]any{{"path": "DCIM/old.mp4", "reason": "freed"}, {"path": "DCIM/later.mp4", "reason": "freed"}})
	p.index()
	var got *store.CompressItem
	for _, x := range p.items() {
		switch x.Fingerprint {
		case fingerprint.Bytes(old):
			t.Fatal("a video backed up before the switch was appended once freed")
		case fingerprint.Bytes(later):
			got = &x
		}
	}
	if got == nil {
		t.Fatal("the new freed video was not appended")
	}
	p.runAll()
	if s := p.item(got.ID).State; s != store.CompressReplaced {
		t.Fatalf("freed video %s", s)
	}
	// Turning the switch off cancels nothing replaced.
	expect(t, p.do("PATCH", "/v1/compress/jobs/"+job["id"].(string), map[string]any{"auto": false}, bearer(p.ph.key)), 200, "")
	if s := p.item(it.ID).State; s != store.CompressReplaced {
		t.Fatalf("%s", s)
	}
}

// Review S3: rows the backup makes again for old videos are not new to an automatic folder: a file moved on the phone
// (a new entry for an old shot), a root stopped and added again (its files reported again), and a claim that merges
// another phone's older backup into the folder.
func TestCompressAutomaticIgnoresRecreatedRows(t *testing.T) {
	p := newPhoneH(t)
	a, b, c := phoneVideo(81), phoneVideo(82), phoneVideo(83)
	p.backUp("DCIM/a.mp4", a)
	p.backUp("DCIM/b.mp4", b)
	// Another phone (new hardware) backed up c before the switch.
	tablet := p.phone("Pixel 9", "hoard")
	root2 := p.addRoot(tablet, "primary", "DCIM", 201)["id"].(string)
	uploadFor(t, p.harness, tablet.key, c, false)
	p.report(tablet, root2, []map[string]any{fileEntry("DCIM/c.mp4", c, true)}, nil)
	p.probe(fingerprint.Bytes(c))
	p.index()
	sel := map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}
	p.jobAs(p.ph.key, "phone", sel, map[string]any{"auto": true})
	time.Sleep(5 * time.Millisecond)

	// a moves on the phone: a new row for an old shot.
	p.report(p.ph, p.root, []map[string]any{fileEntry("DCIM/Moved/a.mp4", a, true)}, []map[string]any{{"path": "DCIM/a.mp4", "reason": "moved"}})
	// The root stopped and added again; the phone reports its files again.
	w := p.do("DELETE", "/v1/backup/roots/"+p.root, nil, bearer(p.ph.key))
	if w.Code != 200 && w.Code != 204 {
		t.Fatalf("stop: %d %s", w.Code, w.Body.String())
	}
	if again := p.addRoot(p.ph, "primary", "DCIM", 200)["id"]; again != p.root {
		t.Fatalf("re-added root %v", again)
	}
	p.report(p.ph, p.root, []map[string]any{fileEntry("DCIM/b.mp4", b, true), fileEntry("DCIM/Moved/a.mp4", a, true)}, nil)
	// The phone is replaced by new hardware that claims it: c's backup merges into the folder.
	expect(t, p.do("POST", "/admin/api/devices/"+p.ph.id+"/revoke", nil, adminHeaders()), 200, "")
	expect(t, p.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": p.ph.physical}, bearer(tablet.key)), 200, "")
	p.index()
	if es, _ := p.st.MirrorEntriesOfRoot(t.Context(), p.root); func() bool {
		for _, e := range es {
			if e.Fingerprint == fingerprint.Bytes(c) && e.RemovedReason == "" {
				return false
			}
		}
		return true
	}() {
		t.Fatal("the claim did not merge the tablet's file into the folder")
	}
	if list := p.items(); len(list) != 0 {
		t.Fatalf("old videos appended: %+v", list)
	}
}

// A phone file that is a NAS reference: the NAS copy is ingested into the store before the item is ready. The phone
// gives items up: verify fails it (the output blob dropped), gone finishes it on the server, refused cancels it; a
// cancel drops a ready output.
func TestCompressPhoneNasReferenceAndAbandon(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	p.writeVideo("lib/VID_N.mp4", 61)
	p.walkAll()
	nasBytes, err := os.ReadFile(filepath.Join(p.nas, "lib/VID_N.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	if st := states(p.report(p.ph, p.root, []map[string]any{fileEntry("DCIM/VID_N.mp4", nasBytes, true)}, nil)); st[0] != "nas" {
		t.Fatalf("%v", st)
	}
	p.probe(fingerprint.Bytes(nasBytes))
	g, r, x := phoneVideo(62), phoneVideo(63), phoneVideo(64)
	p.backUp("DCIM/G.mp4", g)
	p.backUp("DCIM/R.mp4", r)
	p.backUp("DCIM/X.mp4", x)
	p.index()
	if e := p.entryAt(p.ph, "DCIM/VID_N.mp4"); e.Store != store.StoreNAS {
		t.Fatalf("%+v", e)
	}
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	byFP := map[string]store.CompressItem{}
	for _, it := range p.items() {
		if it.State != store.CompressReady {
			t.Fatalf("%+v", it)
		}
		byFP[it.Fingerprint] = it
	}
	if e := p.entryAt(p.ph, "DCIM/VID_N.mp4"); e.Store != store.StoreBlob || !exists(p.blobFile(sum(nasBytes))) {
		t.Fatalf("the NAS copy was not ingested: %+v", e)
	}
	abandon := func(it store.CompressItem, reason string) map[string]any {
		t.Helper()
		w := p.do("POST", fmt.Sprintf("/v1/compress/items/%d/abandon", it.ID), map[string]any{"reason": reason}, bearer(p.ph.key))
		expect(t, w, 200, "")
		return decode(t, w)
	}
	n := byFP[fingerprint.Bytes(nasBytes)]
	if a := abandon(n, "verify"); a["state"] != "failed" || a["reason"] != compress.ReasonVerifyPhone || exists(p.blobFile(n.OutSHA256)) {
		t.Fatalf("verify %v", a)
	}
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/abandon", n.ID), map[string]any{"reason": "verify"}, bearer(p.ph.key)), 409, "conflict")
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/abandon", n.ID), map[string]any{"reason": "bored"}, bearer(p.ph.key)), 422, "invalid_request")
	gi := byFP[fingerprint.Bytes(g)]
	if a := abandon(gi, "gone"); a["state"] != "replaced" {
		t.Fatalf("gone %v", a)
	}
	if e := p.entryAt(p.ph, "DCIM/G.mp4"); e.Fingerprint != gi.OutFingerprint {
		t.Fatalf("%+v", e)
	}
	ri := byFP[fingerprint.Bytes(r)]
	if a := abandon(ri, "refused"); a["state"] != "cancelled" || a["reason"] != "refused" || exists(p.blobFile(ri.OutSHA256)) {
		t.Fatalf("refused %v", a)
	}
	xi := byFP[fingerprint.Bytes(x)]
	expect(t, p.do("POST", "/v1/compress/cancel", map[string]any{"itemIds": []string{fmt.Sprint(xi.ID)}}, bearer(p.ph.key)), 200, "")
	if exists(p.blobFile(xi.OutSHA256)) {
		t.Fatal("a cancelled output stays")
	}
	if p.item(xi.ID).State != store.CompressCancelled {
		t.Fatal("not cancelled")
	}
	// The phone's file never changed meanwhile: its entries are current.
	for _, path := range []string{"DCIM/VID_N.mp4", "DCIM/R.mp4", "DCIM/X.mp4"} {
		if e := p.entryAt(p.ph, path); !e.OnDevice || e.RemovedReason != "" {
			t.Fatalf("%s: %+v", path, e)
		}
	}
	_ = ctx
}

// A phone restore holds the kept side (held: true on its trash location) until the phone reports: a purge refuses it,
// and a restore no phone reported for a day counts among the stalled journals (compress.attention).
func TestCompressPhoneRestoreHeldAndStalled(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	orig := phoneVideo(71)
	const path = "DCIM/VID_H.mp4"
	p.backUp(path, orig)
	p.index()
	origItem := store.ItemID(fingerprint.Bytes(orig))
	p.jobAs(p.ph.key, "phone", map[string]any{"itemIds": []string{origItem}}, nil)
	p.runAll()
	it := p.one()
	id := fmt.Sprint(it.ID)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	heldOf := func() bool {
		t.Helper()
		for _, l := range itemLocations(getItem(t, p.harness, p.ph.key, origItem, "?include=trash")) {
			if l["source"] == "mirror" && l["gone"] == "compressed" {
				return l["held"] == true
			}
		}
		t.Fatal("no kept location")
		return false
	}
	attention := func() map[string]any {
		t.Helper()
		return decode(t, p.do("GET", "/admin/api/compress", nil, adminHeaders()))["attention"].(map[string]any)
	}
	p.index()
	if heldOf() {
		t.Fatal("held before any restore")
	}
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")
	if !heldOf() {
		t.Fatal("the kept side is not held while the phone restores it")
	}
	w := p.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	if a := decode(t, w)["items"].(map[string]any)[id].(map[string]any); a["error"] != "state" || !exists(p.blobFile(it.SHA256)) {
		t.Fatalf("a held entry was purged: %v", a)
	}
	p.srv.compress.Housekeeping(ctx)
	if att := attention(); att["stalled"].(float64) != 0 {
		t.Fatalf("stalled at once: %v", att)
	}
	p.st.SetNow(func() time.Time { return time.Now().Add(25 * time.Hour) })
	p.srv.compress.Housekeeping(ctx)
	p.st.SetNow(time.Now)
	if att := attention(); att["stalled"].(float64) != 1 || att["stalledSince"] == nil {
		t.Fatalf("not stalled after a day: %v", att)
	}
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/restored", p.origBody(it), bearer(p.ph.key)), 200, "")
	if att := attention(); att["stalled"].(float64) != 0 {
		t.Fatalf("still stalled: %v", att)
	}
	p.index()
	for _, l := range itemLocations(getItem(t, p.harness, p.ph.key, store.ItemID(it.OutFingerprint), "?include=trash")) {
		if l["held"] == true {
			t.Fatalf("held after the restore: %v", l)
		}
	}
}

// Review S1: once the phone downloaded an output it may have written it, so a cancel leaves the item ready (answered
// with its state) and the output stays; the late …/replaced, or the backup report first, completes the swap.
func TestCompressServedOutputIsNotCancelled(t *testing.T) {
	p := newPhoneH(t)
	a, b := phoneVideo(91), phoneVideo(92)
	p.backUp("DCIM/A.mp4", a)
	p.backUp("DCIM/B.mp4", b)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	byFP := map[string]store.CompressItem{}
	for _, it := range p.items() {
		byFP[it.Fingerprint] = it
		expect(t, p.do("GET", fmt.Sprintf("/v1/compress/items/%d/output", it.ID), nil, bearer(p.ph.key)), 200, "")
	}
	ia, ib := byFP[fingerprint.Bytes(a)], byFP[fingerprint.Bytes(b)]
	// The user cancels the job while the phone's reports are lost.
	w := p.do("POST", "/v1/compress/cancel", map[string]any{"jobId": ia.JobID}, bearer(p.ph.key))
	expect(t, w, 200, "")
	for _, it := range []store.CompressItem{ia, ib} {
		if got := p.item(it.ID); got.State != store.CompressReady || !exists(p.blobFile(it.OutSHA256)) {
			t.Fatalf("a served output was cancelled: %+v", got)
		}
	}
	// The late route.
	expect(t, p.do("POST", fmt.Sprintf("/v1/compress/items/%d/replaced", ia.ID), p.outBody(ia), bearer(p.ph.key)), 200, "")
	if p.item(ia.ID).State != store.CompressReplaced {
		t.Fatal("the late report did not complete the swap")
	}
	// The backup report first.
	res := p.report(p.ph, p.root, []map[string]any{p.outEntry("DCIM/B.mp4", ib)}, nil)
	if st := states(res); st[0] != "stored" || p.item(ib.ID).State != store.CompressReplaced {
		t.Fatalf("report %v, item %+v", res, p.item(ib.ID))
	}
	p.noPreviousVersions()
	// An output never served is still cancelled (and dropped).
	c := phoneVideo(93)
	p.backUp("DCIM/C.mp4", c)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root, "path": "DCIM/C.mp4"}}}, nil)
	p.runAll()
	var ic store.CompressItem
	for _, it := range p.items() {
		if it.Fingerprint == fingerprint.Bytes(c) {
			ic = it
		}
	}
	expect(t, p.do("POST", "/v1/compress/cancel", map[string]any{"itemIds": []string{fmt.Sprint(ic.ID)}}, bearer(p.ph.key)), 200, "")
	if p.item(ic.ID).State != store.CompressCancelled || exists(p.blobFile(ic.OutSHA256)) {
		t.Fatalf("%+v", p.item(ic.ID))
	}
}

// Review S2: the phone renames a compressed video; restore follows it to its new path (by the phone, then, once freed,
// on the server), and restore again too.
func TestCompressRestoreFollowsARename(t *testing.T) {
	p := newPhoneH(t)
	orig := phoneVideo(101)
	p.backUp("DCIM/V.mp4", orig)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	it := p.one()
	id := fmt.Sprint(it.ID)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	const moved = "DCIM/Trip/V.mp4"
	p.report(p.ph, p.root, []map[string]any{p.outEntry(moved, it)}, []map[string]any{{"path": "DCIM/V.mp4", "reason": "moved"}})

	// The phone restores it at its new path.
	w := p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key))
	expect(t, w, 200, "")
	if a := decode(t, w)["items"].(map[string]any)[id].(map[string]any); a["state"] != "replacing" {
		t.Fatalf("restore %v", a)
	}
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/restored", p.origBody(it), bearer(p.ph.key)), 200, "")
	if e := p.entryAt(p.ph, moved); e.Fingerprint != it.Fingerprint || !e.OnDevice || !p.linkedTo(p.ph.physical, moved, it.SHA256) {
		t.Fatalf("restored entry %+v", e)
	}
	// Restore again by the phone, at the same new path.
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	// Freed at its new path: the restore is the server's.
	p.report(p.ph, p.root, nil, []map[string]any{{"path": moved, "reason": "freed"}})
	w = p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key))
	if a := decode(t, w)["items"].(map[string]any)[id].(map[string]any); a["state"] != "restored" {
		t.Fatalf("server restore %v", a)
	}
	if e := p.entryAt(p.ph, moved); e.Fingerprint != it.Fingerprint || e.RemovedReason != store.RemovedFreed {
		t.Fatalf("%+v", e)
	}
	p.noPreviousVersions()
}

// Review S4: abandon "gone" finishes the item on the server, and the path's new owner is off the phone (deleted), not
// a file the phone would have.
func TestCompressAbandonGoneIsOffThePhone(t *testing.T) {
	p := newPhoneH(t)
	v := phoneVideo(111)
	p.backUp("DCIM/G.mp4", v)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	it := p.one()
	w := p.do("POST", fmt.Sprintf("/v1/compress/items/%d/abandon", it.ID), map[string]any{"reason": "gone"}, bearer(p.ph.key))
	expect(t, w, 200, "")
	if decode(t, w)["state"] != "replaced" {
		t.Fatal(w.Body.String())
	}
	e := p.entryAt(p.ph, "DCIM/G.mp4")
	if e.Fingerprint != it.OutFingerprint || e.OnDevice || e.RemovedReason != store.RemovedDeleted {
		t.Fatalf("%+v", e)
	}
}

// Review nit: a phone restore given up on restarts the retention, so an already due trashed side is not purged at once.
func TestCompressAbandonedRestoreKeepsTheTrash(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	v := phoneVideo(121)
	p.backUp("DCIM/K.mp4", v)
	p.index()
	p.jobAs(p.ph.key, "phone", map[string]any{"roots": []any{map[string]any{"rootId": p.root}}}, nil)
	p.runAll()
	it := p.one()
	id := fmt.Sprint(it.ID)
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/replaced", p.outBody(it), bearer(p.ph.key)), 200, "")
	later := time.Now().Add(31 * 24 * time.Hour)
	p.st.SetNow(func() time.Time { return later })
	defer p.st.SetNow(time.Now)
	expect(t, p.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{id}}, bearer(p.ph.key)), 200, "")
	expect(t, p.do("POST", "/v1/compress/items/"+id+"/abandon", map[string]any{"reason": "verify"}, bearer(p.ph.key)), 200, "")
	p.srv.compress.Housekeeping(ctx)
	if got := p.item(it.ID); got.State != store.CompressReplaced || *got.PurgeAfter <= later.UnixMilli() || !exists(p.blobFile(it.SHA256)) {
		t.Fatalf("%+v", got)
	}
}
