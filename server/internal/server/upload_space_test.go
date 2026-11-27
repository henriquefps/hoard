package server

import (
	"testing"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
)

// hoard-server 0.14.0: a nearly full backup volume refuses an upload cleanly (507 storage_full with free and needed)
// before anything is written: at the create, at a resume and before a chunk; the upload goes on once there is room.
func TestUploadStorageFull(t *testing.T) {
	hs := newHarness(t, true)
	key, devID := hs.pair("Phone")
	data := shot(5)
	free := int64(10 << 20)
	hs.srv.freeBytesFn = func(string) (int64, error) { return free, nil }
	create := func() map[string]any {
		w := hs.do("POST", "/v1/uploads", map[string]any{"sha256": sum(data), "size": len(data), "kind": "original", "fingerprint": fingerprint.Bytes(data)}, bearer(key))
		if w.Code == 507 {
			return decode(t, w)
		}
		expect(t, w, 201, "")
		return decode(t, w)
	}
	m := create()
	e, _ := m["error"].(map[string]any)
	if e == nil || e["code"] != "storage_full" {
		t.Fatalf("%v", m)
	}
	d := e["details"].(map[string]any)
	if d["free"] != float64(free) || d["needed"].(float64) < float64(len(data)+storageReserve) {
		t.Fatalf("%v", d)
	}
	if n, _ := hs.st.UploadCount(t.Context(), devID); n != 0 {
		t.Fatal("an upload row was made")
	}
	// Room again: created; then full before the chunk: nothing written, the offset stays 0.
	free = 1 << 40
	id := create()["uploadId"].(string)
	free = 1 << 20
	w := hs.do("PATCH", "/v1/uploads/"+id, data, bearer(key), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream"))
	expect(t, w, 507, "storage_full")
	if w.Header().Get("Upload-Offset") != "0" || hs.srv.blobStore().PartSize(id) != 0 {
		t.Fatalf("written while full: offset %s, part %d", w.Header().Get("Upload-Offset"), hs.srv.blobStore().PartSize(id))
	}
	free = 1 << 40
	expect(t, hs.do("PATCH", "/v1/uploads/"+id, data, bearer(key), header("Upload-Offset", "0"), header("Content-Type", "application/offset+octet-stream")), 204, "")
}
