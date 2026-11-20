package server

import (
	"compress/gzip"
	"io"
	"os"
	"strings"
	"syscall"
	"testing"

	"github.com/henriquefps/hoard/server/internal/store"
)

// The web gallery's compression routes (ADR 0025 phase S3): the /v1 handlers under the gallery's session, created_by
// "admin", the item log and the metadata download, the trash list with compressed originals and held entries, and
// the library index's "cz".
func TestWebCompress(t *testing.T) {
	ch := compressHarness(t, true, "V/A.MP4", "V/B.MP4", "V/HOLD.MP4")
	c := webSignIn(t, ch.harness)
	web := func(method, path string, body any) map[string]any {
		t.Helper()
		opt := webLocal()
		if method != "GET" {
			opt = webWrite()
		}
		w := ch.do(method, path, body, opt, withCookie(c))
		if w.Code != 200 && w.Code != 201 {
			t.Fatalf("%s %s: %d %s", method, path, w.Code, w.Body.String())
		}
		return decode(t, w)
	}

	// Signed out: every route is the gallery's (401), and a write without the CSRF header is refused.
	expect(t, ch.do("GET", "/web/compress", nil, webLocal()), 401, "unauthorized")
	expect(t, ch.do("POST", "/web/compress/jobs", map[string]any{}, webLocal(), withCookie(c)), 403, "forbidden")
	// From outside the LAN the gallery does not exist.
	expect(t, ch.do("GET", "/web/compress", nil, remote("203.0.113.9:4000"), withCookie(c)), 404, "not_found")

	info := web("GET", "/web/compress", nil)
	cz := info["compress"].(map[string]any)
	if cz["available"] != true || cz["targets"].(map[string]any)["nas"].(map[string]any)["ok"] != true {
		t.Fatalf("compress %v", cz)
	}
	if roots, _ := info["roots"].([]any); roots == nil || len(roots) != 0 {
		t.Fatalf("roots %v", info["roots"])
	}

	sel := map[string]any{"folders": []any{map[string]any{"folderId": ch.folder, "path": "V", "recursive": true}}}
	est := web("POST", "/web/compress/estimate", map[string]any{"target": "nas", "select": sel})
	if est["totals"].(map[string]any)["encode"].(float64) != 3 {
		t.Fatalf("estimate %v", est)
	}
	// HOLD's trash link is made, then reported as an I/O error: its journal waits (held).
	ch.linker = func(r *os.Root, o, n string) error {
		if err := r.Link(o, n); err != nil {
			return err
		}
		if strings.Contains(o, "HOLD") && strings.Contains(n, ".hoard-trash") {
			return &os.LinkError{Op: "link", Old: o, New: n, Err: syscall.EIO}
		}
		return nil
	}
	job := web("POST", "/web/compress/jobs", map[string]any{"target": "nas", "select": sel, "requestId": "web-1"})
	if job["createdBy"] != "admin" || job["queued"].(float64) != 3 {
		t.Fatalf("job %v", job)
	}
	ch.runAll()
	ch.srv.compress.RunOps(t.Context())
	ch.walkAll()

	items := web("GET", "/web/compress/items?jobId="+job["id"].(string), nil)["items"].([]any)
	byName := map[string]map[string]any{}
	for _, it := range items {
		m := it.(map[string]any)
		byName[m["name"].(string)] = m
	}
	a := byName["A.MP4"]
	if a["state"] != store.CompressReplaced || byName["HOLD.MP4"]["state"] != store.CompressReplacing || a["logAvailable"] != true {
		t.Fatalf("items %v", byName)
	}
	id := a["id"].(string)

	// The log and the metadata sidecar (gzip JSON, named after the video).
	w := ch.do("GET", "/web/compress/items/"+id+"/log", nil, webLocal(), withCookie(c))
	expect(t, w, 200, "")
	if !strings.Contains(w.Body.String(), "estimate:") {
		t.Fatalf("log %q", w.Body.String())
	}
	w = ch.do("GET", "/web/compress/items/"+id+"/meta", nil, webLocal(), withCookie(c))
	expect(t, w, 200, "")
	if cd := w.Header().Get("Content-Disposition"); !strings.Contains(cd, `filename="A.metadata.json.gz"`) || w.Header().Get("Content-Type") != "application/gzip" {
		t.Fatalf("meta headers %v", w.Header())
	}
	zr, err := gzip.NewReader(w.Body)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := io.ReadAll(zr); !strings.Contains(string(b), `"version":2`) {
		t.Fatalf("sidecar %s", b)
	}
	expect(t, ch.do("GET", "/web/compress/items/999/meta", nil, webLocal(), withCookie(c)), 404, "not_found")
	expect(t, ch.do("GET", "/web/compress/items/"+id+"/meta", nil, webLocal()), 401, "unauthorized")

	// The trash: A and B as compressed originals, HOLD (live, its trash link held) as held.
	trash := func() map[string]map[string]any {
		out := map[string]map[string]any{}
		for _, e := range web("GET", "/web/trash", nil)["items"].([]any) {
			m := e.(map[string]any)
			out[m["name"].(string)] = m
		}
		return out
	}
	tr := trash()
	if len(tr) != 3 || tr["A.MP4"]["compressedTo"] == nil || tr["A.MP4"]["compression"].(map[string]any)["role"] != "original" ||
		tr["A.MP4"]["compression"].(map[string]any)["state"] != store.CompressReplaced {
		t.Fatalf("trash %v", tr)
	}
	if h := tr["HOLD.MP4"]; h["held"] != true || h["live"] != true {
		t.Fatalf("held entry %v", h)
	}

	// The library index: "cz" on the outputs.
	n := 0
	for _, r := range library(t, ch.harness, c, "").Rows {
		if r.Cz == "trashed" {
			n++
		}
	}
	if n != 2 {
		t.Fatalf("%d rows with cz", n)
	}

	// Restore A, purge B (the gallery's twins of /v1): A's original is back, B's gone; neither listed any more.
	res := web("POST", "/web/compress/restore", map[string]any{"itemIds": []string{id}})["items"].(map[string]any)[id].(map[string]any)
	if res["error"] != nil {
		t.Fatalf("restore %v", res)
	}
	bid := byName["B.MP4"]["id"].(string)
	if p := web("POST", "/web/compress/purge", map[string]any{"itemIds": []string{bid}})["items"].(map[string]any)[bid].(map[string]any); p["state"] != store.CompressPurged {
		t.Fatalf("purge %v", p)
	}
	ch.srv.compress.RunOps(t.Context())
	tr = trash() // before the walk: the stale rows are left out already
	if _, ok := tr["B.MP4"]; ok {
		t.Fatalf("purged original still listed: %v", tr)
	}
	ch.walkAll()
	tr = trash()
	if e := tr["A.MP4"]; e == nil || e["compression"].(map[string]any)["role"] != "output" || e["compression"].(map[string]any)["state"] != store.CompressRestored {
		t.Fatalf("restored: the output is the trashed side %v", tr)
	}

	// Compress automatically for the folder, then off (PATCH with auto false).
	auto := web("POST", "/web/compress/jobs", map[string]any{"target": "nas", "select": sel, "auto": true, "requestId": "web-2"})
	if auto["auto"] != true {
		t.Fatalf("auto %v", auto)
	}
	if list := web("GET", "/web/compress/jobs?auto=true", nil)["jobs"].([]any); len(list) != 1 {
		t.Fatalf("auto jobs %v", list)
	}
	off := web("PATCH", "/web/compress/jobs/"+auto["id"].(string), map[string]any{"auto": false})
	if off["state"] != store.JobCancelled {
		t.Fatalf("auto off %v", off)
	}
}
