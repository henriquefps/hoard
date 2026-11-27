package server

import (
	"errors"
	"testing"

	"github.com/henriquefps/hoard/server/internal/store"
)

// hoard-server 0.14.0: the whole-library estimate in one answer (totalsOnly) with the space the outputs need while the
// originals stay in the trash, and a NAS replace waiting for room that says what it needs and what is free.
func TestCompressEstimateSpace(t *testing.T) {
	ch := compressHarness(t, true, "V/A.MP4", "V/B.MP4", "W/C.MP4")
	free := int64(1 << 40)
	ch.srv.freeBytesFn = func(string) (int64, error) { return free, nil }
	est := func() map[string]any {
		w := ch.do("POST", "/v1/compress/estimate", map[string]any{"target": "nas", "totalsOnly": true,
			"select": map[string]any{"folders": []map[string]any{{"folderId": ch.folder, "path": "", "recursive": true}}}}, bearer(ch.key))
		expect(t, w, 200, "")
		return decode(t, w)
	}
	m := est()
	if len(m["items"].([]any)) != 0 || m["next"] != nil || m["totals"].(map[string]any)["encode"] != float64(3) {
		t.Fatalf("totals only %v", m)
	}
	sp := m["space"].(map[string]any)
	estSize := m["totals"].(map[string]any)["estSize"].(float64)
	if sp["needed"].(float64) != estSize+replaceMargin || sp["free"].(float64) != float64(free) || sp["fits"] != true || sp["disks"] != float64(1) {
		t.Fatalf("space %v (estSize %v)", sp, estSize)
	}
	free = 1 << 20
	if sp := est()["space"].(map[string]any); sp["fits"] != false || sp["known"] != true {
		t.Fatalf("too little room: %v", sp)
	}
	ch.srv.freeBytesFn = func(string) (int64, error) { return 0, errors.New("statfs") }
	if sp := est()["space"].(map[string]any); sp["fits"] != false || sp["known"] != false {
		t.Fatalf("unknown free space: %v", sp)
	}

	// A replace without room waits (ready, no_space) and its item says what it needs and what is free.
	ch.srv.freeBytesFn = func(string) (int64, error) { return 1 << 20, nil }
	ch.srv.compress.SetFreeHook(func(where string) error {
		if where == "nas" {
			return errors.New("statfs")
		}
		return nil
	})
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("V/A.MP4")}}, nil)
	ch.runAll()
	w := ch.do("GET", "/v1/compress/items", nil, bearer(ch.key))
	expect(t, w, 200, "")
	var it map[string]any
	for _, x := range decode(t, w)["items"].([]any) {
		if x.(map[string]any)["reason"] == "no_space" {
			it = x.(map[string]any)
		}
	}
	if it == nil || it["state"] != store.CompressReady {
		t.Fatalf("no waiting item: %s", w.Body.String())
	}
	sp = it["space"].(map[string]any)
	if sp["where"] != "nas" || sp["free"] != float64(1<<20) || sp["needed"].(float64) <= replaceMargin {
		t.Fatalf("%v", sp)
	}
}
