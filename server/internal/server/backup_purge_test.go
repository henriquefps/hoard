package server

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/store"
)

// hoard-server 0.14.0: the admin's purge of a phone's backup settles that phone's compressions in the same transaction
// (finished ones purged, unfinished ones cancelled, none left naming a deleted entry or holding a blob), releases what
// only that backup kept, and leaves Reveal's uploads, NAS files and NAS compressions alone.
func TestBackupPurgeSettlesCompressions(t *testing.T) {
	p := newPhoneH(t)
	ctx := t.Context()
	// A NAS compression elsewhere, replaced.
	p.writeVideo("N.MP4", 99)
	p.walkAll()
	p.storeProbes()
	nasOrig := p.fp("N.MP4")
	p.job(map[string]any{"itemIds": []string{store.ItemID(nasOrig)}}, nil)
	p.runAll()
	// A freed photo, a freed video compressed on the server (replaced: its original kept, pinned), and a freed video
	// whose compression is still queued.
	photo := shot(77)
	uploadFor(t, p.harness, p.ph.key, photo, false)
	p.report(p.ph, p.root, []map[string]any{fileEntry("DCIM/IMG_1.jpg", photo, true)}, nil)
	vid, queued := phoneVideo(41), phoneVideo(42)
	p.backUp("DCIM/VID_F.mp4", vid)
	p.report(p.ph, p.root, nil, []map[string]any{{"path": "DCIM/VID_F.mp4", "reason": "freed"}, {"path": "DCIM/IMG_1.jpg", "reason": "freed"}})
	p.index()
	p.jobAs(p.key, "backup", map[string]any{"itemIds": []string{store.ItemID(fingerprint.Bytes(vid))}}, nil)
	p.runAll()
	var replaced store.CompressItem
	for _, it := range p.items() {
		if it.Source == "blob" {
			replaced = it
		}
	}
	if replaced.State != store.CompressReplaced {
		t.Fatalf("%+v", replaced)
	}
	p.backUp("DCIM/VID_Q.mp4", queued)
	p.report(p.ph, p.root, nil, []map[string]any{{"path": "DCIM/VID_Q.mp4", "reason": "freed"}})
	p.index()
	// (the queue runs only through runAll in tests: this one stays queued)
	p.jobAs(p.key, "backup", map[string]any{"itemIds": []string{store.ItemID(fingerprint.Bytes(queued))}}, nil)
	// Reveal's own upload, claimed: it stays whatever the purge does.
	reveal := p.phone("Pixel 8", "reveal")
	revealPhoto := shot(78)
	uploadFor(t, p.harness, reveal.key, revealPhoto, true)
	p.index()

	// The page shows the compressed originals; the purge needs both counts.
	w := p.do("GET", "/admin/api/backup", nil, adminHeaders())
	expect(t, w, 200, "")
	var phone map[string]any
	for _, x := range decode(t, w)["phones"].([]any) {
		if x.(map[string]any)["physicalDeviceId"] == p.ph.physical {
			phone = x.(map[string]any)
		}
	}
	if phone["compressedKept"] != float64(1) || phone["compressUnfinished"] != float64(1) {
		t.Fatalf("%v", phone)
	}
	_, pinned, _ := p.st.MirrorPurgeInfo(ctx, p.ph.physical)
	w = p.do("POST", "/admin/api/backup/"+p.ph.physical+"/purge", map[string]any{"confirmPinned": pinned}, adminHeaders())
	expect(t, w, 409, "conflict")
	if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["compressedKept"] != float64(1) {
		t.Fatalf("%v", d)
	}
	w = p.do("POST", "/admin/api/backup/"+p.ph.physical+"/purge", map[string]any{"confirmPinned": pinned, "confirmCompressed": 1}, adminHeaders())
	expect(t, w, 200, "")
	if m := decode(t, w); m["compressCancelled"] != float64(1) || m["compressPurged"] != float64(1) {
		t.Fatalf("%v", m)
	}
	p.index()

	// No orphans: every compression of the phone ended, none names an entry; the NAS one is untouched.
	var orphans int
	for _, it := range p.items() {
		switch {
		case it.Source == "nas":
			if it.State != store.CompressReplaced || it.Fingerprint != nasOrig {
				t.Fatalf("NAS compression changed: %+v", it)
			}
		case it.ID == replaced.ID:
			if it.State != store.CompressPurged || it.Reason != store.ReasonBackupPurged {
				t.Fatalf("%+v", it)
			}
		default:
			if it.State != store.CompressCancelled {
				t.Fatalf("%+v", it)
			}
		}
		if it.MirrorEntryID != nil {
			orphans++
		}
	}
	if orphans != 0 {
		t.Fatalf("%d compression rows still name a mirror entry", orphans)
	}
	// Blobs: the kept original, the output, the freed photo and the queued video are gone; Reveal's upload stays.
	for name, sha := range map[string]string{"original": replaced.SHA256, "output": replaced.OutSHA256, "photo": sum(photo), "queued": sum(queued)} {
		if _, err := os.Stat(p.blobFile(sha)); err == nil {
			t.Fatalf("the %s's blob stays", name)
		}
	}
	if _, err := os.Stat(p.blobFile(sum(revealPhoto))); err != nil {
		t.Fatal("Reveal's upload was removed")
	}
	if _, err := os.Stat(filepath.Join(p.nas, "N.MP4")); err != nil {
		t.Fatal("the NAS video went")
	}
	// The feed: the backup's items are tombstones; Reveal's photo and the NAS output live.
	for name, id := range map[string]string{"original": store.ItemID(replaced.Fingerprint), "output": store.ItemID(replaced.OutFingerprint),
		"photo": store.ItemID(fingerprint.Bytes(photo)), "queued": store.ItemID(fingerprint.Bytes(queued))} {
		if it := getItem(t, p.harness, p.key, id, "?include=trash"); it["deleted"] != true {
			t.Fatalf("%s: %v", name, it)
		}
	}
	if it := getItem(t, p.harness, p.key, store.ItemID(fingerprint.Bytes(revealPhoto)), ""); it["deleted"] != false {
		t.Fatalf("%v", it)
	}
	// The next report of the phone starts over: its root is gone (404), a new one can be made.
	w = p.do("POST", "/v1/backup/report", map[string]any{"rootId": p.root, "entries": []any{}, "removed": []any{}}, bearer(p.ph.key))
	expect(t, w, 404, "")
	p.addRoot(p.ph, "primary", "DCIM", 201)
}
