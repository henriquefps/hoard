package server

import (
	"os"
	"path/filepath"
	"testing"
)

// Fixes from the S1 review (ADR 0024 "Amendment (S1)").

// A freed file stays pinned (the only copy) when new content takes its path, so the purge still asks about it.
func TestBackupFreedStaysPinnedAfterARewrite(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	old, fresh := shot(1), shot(2)
	uploadFor(t, hs, pixel.key, old, false)
	uploadFor(t, hs, pixel.key, fresh, false)
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/a.jpg", old, true)}, nil)
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/a.jpg", "reason": "freed"}})
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/a.jpg", fresh, true)}, nil) // a new photo with the same name
	if _, pinned, _ := hs.st.MirrorPurgeInfo(t.Context(), pixel.physical); pinned != 1 {
		t.Fatalf("pinned %d", pinned)
	}
	purge := "/admin/api/backup/" + pixel.physical + "/purge"
	expect(t, hs.do("POST", purge, map[string]any{"confirmPinned": 0}, adminHeaders()), 409, "conflict")
	// Back on the phone (the same content at a path): no longer the only copy.
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/b.jpg", shot(3), true)}, nil)
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/b.jpg", "reason": "freed"}})
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/b.jpg", shot(3), true)}, nil)
	if _, pinned, _ := hs.st.MirrorPurgeInfo(t.Context(), pixel.physical); pinned != 1 {
		t.Fatalf("pinned after a restore %d", pinned)
	}
	expect(t, hs.do("POST", purge, map[string]any{"confirmPinned": 1}, adminHeaders()), 200, "")
}

// Main-storage paths never land in an SD card's folder of the tree.
func TestBackupSDCardPrefixReserved(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	expect(t, hs.do("POST", "/v1/backup/roots", map[string]any{"volume": "primary", "path": "SD card 1234-abcd/DCIM"}, bearer(pixel.key)), 422, "invalid_request")
	whole := hs.addRoot(pixel, "primary", "", 201)["id"].(string)
	w := hs.do("POST", "/v1/backup/report", map[string]any{"rootId": whole, "entries": []any{fileEntry("SD card 1234-ABCD/DCIM/a.jpg", shot(1), true)}}, bearer(pixel.key))
	expect(t, w, 200, "")
	if r := decode(t, w)["results"].([]any)[0].(map[string]any); r["state"] != "refused" || r["reason"] != "reserved_prefix" {
		t.Fatalf("%v", r)
	}
	sd := hs.addRoot(pixel, "1234-ABCD", "", 201)["id"].(string)
	hs.report(pixel, sd, []map[string]any{fileEntry("SD card 1234-ABCD/DCIM/a.jpg", shot(1), true)}, nil) // on the card itself: its own folder
}

// The purge removes what the server made in the phone's folder, never a file the owner put there.
func TestBackupPurgeKeepsForeignFiles(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	a, b := shot(1), shot(2)
	uploadFor(t, hs, pixel.key, a, false)
	uploadFor(t, hs, pixel.key, b, false)
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/Camera/a.jpg", a, true), fileEntry("DCIM/b.jpg", b, true)}, nil)
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/b.jpg", shot(3), false)}, nil) // b becomes a previous version
	hs.drain()
	dir := hs.deviceDir(pixel.physical)
	mine := filepath.Join(dir, "DCIM", "Camera", "notes.txt")
	if err := os.WriteFile(mine, []byte("the owner's"), 0o600); err != nil {
		t.Fatal(err)
	}
	w := hs.do("POST", "/admin/api/backup/"+pixel.physical+"/purge", map[string]any{"confirmPinned": 0}, adminHeaders())
	expect(t, w, 200, "")
	if decode(t, w)["keptFiles"] != float64(1) {
		t.Fatal(w.Body.String())
	}
	if got, err := os.ReadFile(mine); err != nil || string(got) != "the owner's" {
		t.Fatalf("the owner's file: %v", err)
	}
	var left []string
	_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			left = append(left, p)
		}
		return nil
	})
	if len(left) != 1 {
		t.Fatalf("left %v", left)
	}
	if _, err := os.Stat(filepath.Join(dir, ".hoard-mirror")); !os.IsNotExist(err) {
		t.Fatal("the server's folder stays")
	}
}

// A NAS copy that finds the blob already on disk does not trust it: the bytes are re-hashed before verify says yes.
func TestBackupNasCopyRehashesAnExistingBlob(t *testing.T) {
	hs := newHarness(t, true)
	pixel := hs.phone("Pixel 8", "hoard")
	root := hs.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	data := shot(1)
	if err := os.WriteFile(filepath.Join(hs.nas, "a.jpg"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	expect(t, hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders()), 201, "")
	hs.walkAll()
	hs.report(pixel, root, []map[string]any{fileEntry("DCIM/a.jpg", data, true)}, nil)
	// A damaged file already sits at the blob's name (no row): the copy keeps it as it is.
	bp := hs.blobFile(sum(data))
	if err := os.MkdirAll(filepath.Dir(bp), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(bp, append([]byte("x"), data[1:]...), 0o600); err != nil {
		t.Fatal(err)
	}
	hs.report(pixel, root, nil, []map[string]any{{"path": "DCIM/a.jpg", "reason": "deleted"}})
	hs.drain()
	w := hs.do("POST", "/v1/backup/verify", map[string]any{"entries": []any{map[string]any{"rootId": root, "path": "DCIM/a.jpg",
		"sha256": sum(data), "size": len(data)}}}, bearer(pixel.key))
	expect(t, w, 200, "")
	res := decode(t, w)["results"].([]any)[0].(map[string]any)
	if res["verified"] != "no" || res["reason"] != "differs" {
		t.Fatalf("%v", res)
	}
}
