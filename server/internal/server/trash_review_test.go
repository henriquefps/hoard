package server

import (
	"strings"
	"testing"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Review of #86 (0.15.0): only Hoard's devices (ADR 0017: hoard, hoard-debug) use /v1/trash; a Reveal key, or a device
// that never said its app, gets 403 not_hoard on every route.
func TestTrashV1OnlyHoardDevices(t *testing.T) {
	hs := newHarness(t, true)
	hoard, _, _ := hs.pairApp("Hoard", "hoard", "A")
	debug, _, _ := hs.pairApp("Hoard debug", "hoard-debug", "B")
	routes := []string{"GET /v1/trash", "POST /v1/trash/move", "POST /v1/trash/restore", "POST /v1/trash/delete", "POST /v1/trash/empty"}
	for _, app := range []string{"reveal", "reveal-debug", "reveal-bench", ""} {
		var key string
		if app == "" {
			key, _ = hs.pair("No app")
		} else {
			key, _, _ = hs.pairApp("Dev "+app, app, "M-"+app)
		}
		for _, route := range routes {
			m, p, _ := strings.Cut(route, " ")
			w := hs.do(m, p, map[string]any{}, bearer(key))
			expect(t, w, 403, "forbidden")
			if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["reason"] != "not_hoard" {
				t.Fatalf("%s %q: %v", route, app, d)
			}
		}
	}
	// Hoard's two builds pass the check (an empty body is then the route's own 4xx/2xx, never 403).
	for _, key := range []string{hoard, debug} {
		for _, route := range routes {
			m, p, _ := strings.Cut(route, " ")
			if w := hs.do(m, p, map[string]any{}, bearer(key)); w.Code == 403 || w.Code == 401 {
				t.Fatalf("%s: %d %s", route, w.Code, w.Body.String())
			}
		}
	}
}

// Review of #86: a backup-only unit (the server trash) is moved and restored by a device but never deleted for good or
// emptied by one; only the web gallery's admin does. A NAS unit stays deletable by a device.
func TestTrashBackupOnlyIsWebOnlyForDevices(t *testing.T) {
	th := newTrashHarnessAPI(t, true, "v1")
	pixel := th.phone("Pixel 8", "hoard")
	root := th.addRoot(pixel, "primary", "DCIM", 201)["id"].(string)
	freed := shot(21)
	uploadFor(t, th.harness, pixel.key, freed, false)
	th.report(pixel, root, []map[string]any{fileEntry("DCIM/Camera/freed.jpg", freed, true)}, nil)
	th.report(pixel, root, nil, []map[string]any{{"path": "DCIM/Camera/freed.jpg", "reason": "freed"}})
	th.srv.IndexNow(t.Context())
	freedID := store.ItemID(fingerprint.Bytes(freed))
	nasID := th.itemOfFile("2024-05-02/DSC00200.JPG")
	if ans := th.move([]string{freedID, nasID}, false); len(strs(ans["moved"])) != 2 {
		t.Fatalf("%v", ans)
	}
	th.walkAll()
	srvUnit := []map[string]any{{"itemId": freedID, "key": "server"}}

	page := decode(t, th.call("GET", "/web/trash", nil))
	wo := page["webOnly"].(map[string]any)
	if wo["count"] != float64(1) || wo["size"].(float64) <= 0 {
		t.Fatalf("webOnly figure %v", wo)
	}
	if g := page["groups"].(map[string]any)["hoard"].(map[string]any); g["count"] != float64(1) {
		t.Fatalf("groups %v", page["groups"])
	}
	if us := unitsOf(th.trash()[freedID]); len(us) != 1 || us[0]["webOnly"] != true {
		t.Fatalf("unit %v", us)
	}

	// Delete: refused web_only, nothing happens.
	res := th.web("POST", "/web/trash/delete", map[string]any{"units": srvUnit})
	if len(res["done"].([]any)) != 0 || res["refused"].(map[string]any)[freedID+"|server"] != "web_only" {
		t.Fatalf("delete %v", res)
	}
	if _, ok := th.trash()[freedID]; !ok {
		t.Fatal("a device deleted a backup-only unit")
	}
	// Empty: the confirmation sees the NAS unit only; the 409 carries the web-only figure.
	w := th.call("POST", "/web/trash/empty", map[string]any{"confirm": map[string]any{"count": 2, "size": 1}})
	expect(t, w, 409, "conflict")
	d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any)
	if d["count"] != float64(1) || d["webOnly"].(map[string]any)["count"] != float64(1) {
		t.Fatalf("409 %v", d)
	}
	w = th.call("POST", "/web/trash/empty", map[string]any{"confirm": map[string]any{"count": 1, "size": d["size"]}})
	expect(t, w, 200, "")
	if ans := decode(t, w); len(ans["refused"].(map[string]any)) != 0 || len(ans["done"].([]any)) != 1 {
		t.Fatalf("empty %v", ans)
	}
	tr := th.trash()
	if _, ok := tr[nasID]; ok {
		t.Fatalf("the NAS unit was not emptied by the device: %v", tr[nasID])
	}
	if _, ok := tr[freedID]; !ok {
		t.Fatal("empty by a device took the backup-only unit")
	}
	// Restore is allowed.
	if res := th.web("POST", "/web/trash/restore", map[string]any{"units": srvUnit}); len(res["done"].([]any)) != 1 {
		t.Fatalf("restore %v", res)
	}
	// The web gallery (admin session) deletes it for good, and its Empty counts it.
	th.move([]string{freedID}, false)
	by := func(path string, body any) map[string]any {
		w := th.do("POST", path, body, webWrite(), withCookie(th.cookie))
		expect(t, w, 200, "")
		return decode(t, w)
	}
	w = th.do("GET", "/web/trash", nil, webLocal(), withCookie(th.cookie))
	expect(t, w, 200, "")
	if _, ok := decode(t, w)["webOnly"]; ok {
		t.Fatal("the web gallery's trash has no webOnly figure")
	}
	if res := by("/web/trash/delete", map[string]any{"units": srvUnit}); len(res["done"].([]any)) != 1 || len(mapKeys(res["refused"])) != 0 {
		t.Fatalf("admin delete %v", res)
	}
}

func mapKeys(v any) []string {
	var out []string
	for k := range v.(map[string]any) {
		out = append(out, k)
	}
	return out
}
