package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/previews"
	"github.com/henriquefps/hoard/server/internal/store"
)

// fakeGen stands in for the CPU generator: it writes small files whose content names the job and the source, and can
// be told to fail for a fingerprint.
type fakeGen struct {
	mu    sync.Mutex
	calls []previews.Job
	fail  map[string]error // by source fingerprint
	seen  map[string]int64 // source fingerprint -> size of the file it was handed
}

func (g *fakeGen) Name() string              { return "fake" }
func (g *fakeGen) Accepts(previews.Job) bool { return true }
func (g *fakeGen) Available() bool           { return true }

func (g *fakeGen) Generate(_ context.Context, j previews.Job, src *os.File, dir string) (previews.Result, error) {
	g.mu.Lock()
	g.calls = append(g.calls, j)
	err := g.fail[j.Source.Fingerprint]
	if fi, e := src.Stat(); e == nil {
		g.seen[j.Source.Fingerprint] = fi.Size()
	}
	g.mu.Unlock()
	if err != nil {
		return nil, err
	}
	res := previews.Result{}
	write := func(name string, w, h int) error {
		body := []byte(fmt.Sprintf("%s %s %s", j.Job, name, j.Source.Fingerprint))
		if name == previews.FileProxy {
			body = append(body, make([]byte, 4096)...) // something to range over
		}
		res[name] = previews.Output{Width: w, Height: h, Bytes: int64(len(body))}
		return os.WriteFile(filepath.Join(dir, name), body, 0o600)
	}
	if j.Job == previews.JobProxy {
		return res, write(previews.FileProxy, 1280, 720)
	}
	if j.Job == previews.JobFull {
		return res, write(previews.FileFull, 6000, 4000)
	}
	for _, f := range []struct {
		name string
		w, h int
	}{{previews.FileScreen, 2560, 1707}, {previews.FileScreenWebP, 2560, 1707}, {previews.FileThumb, 512, 341}, {previews.FileThumbWebP, 512, 341}} {
		if err := write(f.name, f.w, f.h); err != nil {
			return nil, err
		}
	}
	return res, nil
}

func (g *fakeGen) jobs() []previews.Job {
	g.mu.Lock()
	defer g.mu.Unlock()
	return append([]previews.Job{}, g.calls...)
}

// previewsHarness indexes a NAS folder with a photo, a RAW+JPEG pair, a RAW alone, a video and a Live Photo, with
// previews on (fake generator, nothing in the backlog unless asked).
func previewsHarness(t *testing.T, backlog ...string) (*harness, *fakeGen, string, map[string]string) {
	t.Helper()
	gen := &fakeGen{fail: map[string]error{}, seen: map[string]int64{}}
	hs := newHarnessWith(t, true, func(c *Config) {
		c.PreviewsDir = filepath.Join(t.TempDir(), "previews")
		c.PreviewGenerator = gen
		c.PreviewBacklog = backlog
	})
	key, _ := hs.pair("Gallery")
	write := func(rel string, b []byte) {
		if err := os.WriteFile(filepath.Join(hs.nas, rel), b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	shot := func(day, u int) mediatest.Shot {
		return mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", DateTime: fmt.Sprintf("2024:05:%02d 10:00:00", day), Offset: "+00:00",
			Width: 6000, Height: 4000, Orientation: 6, Unique: uint64(u)}
	}
	write("PHOTO.JPG", mediatest.JPEG(shot(1, 1), ""))
	write("PAIR.ARW", mediatest.RAW(shot(2, 2)))
	write("PAIR.JPG", mediatest.JPEG(shot(2, 3), ""))
	write("RAWONLY.ARW", mediatest.RAW(shot(3, 4)))
	write("CLIP.MP4", append(mediatest.MiniMP4(5), make([]byte, 1000)...))
	write("LIVE.HEIC", mediatest.HEIF(shot(4, 6), ""))
	write("LIVE.MOV", append(mediatest.MiniMP4(7), make([]byte, 1000)...))
	w := hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders())
	expect(t, w, 201, "")
	f, _ := hs.st.NasFolder(t.Context(), decode(t, w)["id"].(string))
	if err := hs.srv.indexer.Folder(t.Context(), f); err != nil {
		t.Fatal(err)
	}
	ids := map[string]string{}
	p := page(t, hs, key, "/v1/items?limit=50")
	for _, it := range p.Items {
		files := it["files"].([]any)
		ids[files[0].(map[string]any)["name"].(string)] = it["id"].(string)
	}
	for _, n := range []string{"PHOTO.JPG", "PAIR.ARW", "RAWONLY.ARW", "CLIP.MP4", "LIVE.HEIC"} {
		if ids[n] == "" {
			t.Fatalf("no item for %s: %v", n, ids)
		}
	}
	return hs, gen, key, ids
}

func work(t *testing.T, hs *harness, job string) int {
	t.Helper()
	n := 0
	for {
		ran, err := hs.srv.prev.Work(t.Context(), job)
		if err != nil {
			t.Fatal(err)
		}
		if !ran {
			return n
		}
		n++
	}
}

func TestPreviewLifecycle(t *testing.T) {
	hs, gen, key, ids := previewsHarness(t)
	photo := ids["PHOTO.JPG"]

	// Not made yet: 202, queued ahead of everything.
	w := hs.do("GET", "/v1/items/"+photo+"/preview?size=screen", nil, bearer(key))
	expect(t, w, http.StatusAccepted, "")
	if w.Header().Get("Retry-After") == "" || w.Header().Get("X-Hoard-Preview") != "pending" || decode(t, w)["status"] != "pending" {
		t.Fatalf("pending headers %v %s", w.Header(), w.Body)
	}
	if n := work(t, hs, previews.JobImage); n != 1 {
		t.Fatalf("ran %d image jobs, want 1 (only what was asked)", n)
	}

	// Ready: the file, its key, revalidation, and an immutable answer for ?v=<key>.
	w = hs.do("GET", "/v1/items/"+photo+"/preview?size=screen", nil, bearer(key))
	expect(t, w, 200, "")
	v := w.Header().Get("X-Hoard-Preview-Version")
	if w.Header().Get("Content-Type") != "image/jpeg" || !strings.HasPrefix(w.Body.String(), "image screen.jpg ") || len(v) != 32 ||
		w.Header().Get("Cache-Control") != "private, no-cache" || w.Header().Get("ETag") != `"`+v+`-screen.jpg"` {
		t.Fatalf("ready: %v %q", w.Header(), w.Body)
	}
	w = hs.do("GET", "/v1/items/"+photo+"/preview?size=thumb&v="+v, nil, bearer(key))
	expect(t, w, 200, "")
	if w.Header().Get("Cache-Control") != "private, max-age=31536000, immutable" || !strings.HasPrefix(w.Body.String(), "image thumb.jpg") {
		t.Fatalf("immutable: %v %q", w.Header(), w.Body)
	}
	w = hs.do("GET", "/v1/items/"+photo+"/preview", nil, bearer(key), header("If-None-Match", `"`+v+`-thumb.jpg"`))
	expect(t, w, http.StatusNotModified, "")
	w = hs.do("GET", "/v1/items/"+photo+"/preview?size=thumb&format=webp", nil, bearer(key))
	expect(t, w, 200, "")
	if w.Header().Get("Content-Type") != "image/webp" || !strings.HasPrefix(w.Body.String(), "image thumb.webp") ||
		w.Header().Get("ETag") != `"`+v+`-thumb.webp"` {
		t.Fatalf("webp: %v %q", w.Header(), w.Body)
	}
	expect(t, hs.do("GET", "/v1/items/"+photo+"/preview?format=png", nil, bearer(key)), 400, "invalid_request")

	// The pair uses its camera JPEG, the lone RAW itself (with the RAW's orientation); Live Photo: its HEIC.
	for _, n := range []string{"PAIR.ARW", "RAWONLY.ARW", "LIVE.HEIC", "CLIP.MP4"} {
		expect(t, hs.do("GET", "/v1/items/"+ids[n]+"/preview", nil, bearer(key)), http.StatusAccepted, "")
	}
	work(t, hs, previews.JobImage)
	roles := map[string]previews.Source{}
	for _, j := range gen.jobs() {
		roles[j.ItemID] = j.Source
	}
	if s := roles[ids["PAIR.ARW"]]; s.Role != media.RoleRendered || s.Format != "jpeg" {
		t.Fatalf("pair source %+v", s)
	}
	if s := roles[ids["RAWONLY.ARW"]]; s.Role != media.RoleRaw || s.Orientation != 6 || s.LongEdge != 6000 {
		t.Fatalf("raw source %+v", s)
	}
	if s := roles[ids["LIVE.HEIC"]]; s.Role != media.RoleRendered || s.Format != "heif" {
		t.Fatalf("live photo source %+v", s)
	}
	if s := roles[ids["CLIP.MP4"]]; s.Role != media.RoleVideo {
		t.Fatalf("video poster source %+v", s)
	}

	// Posters are for videos; proxies for videos and motion photos.
	expect(t, hs.do("GET", "/v1/items/"+photo+"/poster", nil, bearer(key)), 404, "preview_unavailable")
	expect(t, hs.do("GET", "/v1/items/"+photo+"/proxy", nil, bearer(key)), 404, "preview_unavailable")
	expect(t, hs.do("GET", "/v1/items/"+ids["CLIP.MP4"]+"/poster?size=thumb", nil, bearer(key)), 200, "")
	expect(t, hs.do("GET", "/v1/items/"+ids["CLIP.MP4"]+"/proxy", nil, bearer(key)), http.StatusAccepted, "")
	expect(t, hs.do("GET", "/v1/items/"+ids["LIVE.HEIC"]+"/proxy", nil, bearer(key)), http.StatusAccepted, "")
	if n := work(t, hs, previews.JobProxy); n != 2 {
		t.Fatalf("proxies made: %d", n)
	}
	w = hs.do("GET", "/v1/items/"+ids["CLIP.MP4"]+"/proxy", nil, bearer(key), header("Range", "bytes=10-19"))
	expect(t, w, http.StatusPartialContent, "")
	if w.Header().Get("Content-Type") != "video/mp4" || w.Body.Len() != 10 || w.Header().Get("Content-Range") == "" {
		t.Fatalf("range: %v %d", w.Header(), w.Body.Len())
	}

	// Bad requests.
	expect(t, hs.do("GET", "/v1/items/"+photo+"/preview?size=huge", nil, bearer(key)), 400, "invalid_request")
	expect(t, hs.do("GET", "/v1/items/00000000-0000-8000-8000-000000000000/preview", nil, bearer(key)), 404, "not_found")
	expect(t, hs.do("GET", "/v1/items/nope/preview", nil, bearer(key)), 404, "not_found")
	expect(t, hs.do("GET", "/v1/items/"+photo+"/preview", nil), 401, "unauthorized")

	// The server announces the feature.
	f := decode(t, hs.do("GET", "/v1/server", nil, bearer(key)))["features"].([]any)
	if len(f) != 4 || f[0] != "items" || f[1] != "previews" || f[2] != "culling" || f[3] != "device-identity" {
		t.Fatalf("features %v", f)
	}
}

func TestPreviewWarm(t *testing.T) {
	hs, _, key, ids := previewsHarness(t)
	missing := "00000000-0000-8000-8000-000000000001"
	body := map[string]any{"items": []string{ids["PHOTO.JPG"], ids["CLIP.MP4"], missing, ids["PHOTO.JPG"]}, "previews": []string{"thumb", "screen", "proxy"}}
	w := hs.do("POST", "/v1/items/previews", body, bearer(key))
	expect(t, w, 200, "")
	var res struct {
		Raw     []map[string]any `json:"items"`
		Missing []string         `json:"missing"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Raw) != 2 || len(res.Missing) != 1 || res.Missing[0] != missing {
		t.Fatalf("warm: %s", w.Body)
	}
	if res.Raw[0]["thumb"].(map[string]any)["status"] != "pending" ||
		res.Raw[0]["proxy"].(map[string]any)["reason"] != "not_applicable" || res.Raw[1]["proxy"].(map[string]any)["status"] != "pending" {
		t.Fatalf("states: %s", w.Body)
	}
	// Queued at the warm priority, the first item of the list first.
	p, ok, err := hs.st.LeasePreview(t.Context(), previews.JobImage)
	if err != nil || !ok || p.ItemID != ids["PHOTO.JPG"] || p.Priority != store.PriorityWarm {
		t.Fatalf("lease %+v %v %v", p, ok, err)
	}
	if err := hs.st.RetryPreview(t.Context(), p.ItemID, p.Job, "", 0); err != nil {
		t.Fatal(err)
	}
	work(t, hs, previews.JobImage)
	work(t, hs, previews.JobProxy)
	w = hs.do("POST", "/v1/items/previews", map[string]any{"items": []string{ids["PHOTO.JPG"]}}, bearer(key))
	expect(t, w, 200, "")
	thumb := decode(t, w)["items"].([]any)[0].(map[string]any)["thumb"].(map[string]any)
	if thumb["status"] != "ready" || len(thumb["v"].(string)) != 32 || thumb["width"].(float64) != 512 || thumb["bytes"].(float64) == 0 ||
		thumb["webpBytes"].(float64) == 0 {
		t.Fatalf("ready thumb %v", thumb)
	}
	// Limits.
	many := make([]string, maxPreviewWarm+1)
	for i := range many {
		many[i] = missing
	}
	expect(t, hs.do("POST", "/v1/items/previews", map[string]any{"items": many}, bearer(key)), 422, "invalid_request")
	expect(t, hs.do("POST", "/v1/items/previews", map[string]any{"items": []string{missing}, "previews": []string{"huge"}}, bearer(key)), 422, "invalid_request")
	expect(t, hs.do("POST", "/v1/items/previews", map[string]any{"items": []string{"x"}}, bearer(key)), 422, "invalid_request")
}

// TestPreviewBacklogAndPriority: the scan queues every item (recent first, newest shot first); a client's request
// jumps the queue.
func TestPreviewBacklogAndPriority(t *testing.T) {
	hs, gen, key, ids := previewsHarness(t, "image", "proxy")
	if err := hs.srv.prev.ScanNow(t.Context()); err != nil {
		t.Fatal(err)
	}
	stats, _ := hs.st.PreviewStats(t.Context())
	pending := map[string]int{}
	for _, s := range stats {
		pending[s.Job] += s.Count
	}
	if pending[previews.JobImage] != 5 || pending[previews.JobProxy] != 2 {
		t.Fatalf("backlog %+v", stats)
	}
	expect(t, hs.do("GET", "/v1/items/"+ids["PHOTO.JPG"]+"/preview", nil, bearer(key)), http.StatusAccepted, "")
	if _, err := hs.srv.prev.Work(t.Context(), previews.JobImage); err != nil {
		t.Fatal(err)
	}
	if j := gen.jobs(); len(j) != 1 || j[0].ItemID != ids["PHOTO.JPG"] {
		t.Fatalf("first job %+v", j)
	}
	work(t, hs, previews.JobImage)
	// Newest shot first among the backlog: the video (no capture time: its file's mtime, today), then LIVE (day 4),
	// RAWONLY (3), PAIR (2).
	order := []string{}
	for _, j := range gen.jobs()[1:] {
		order = append(order, j.ItemID)
	}
	want := []string{ids["CLIP.MP4"], ids["LIVE.HEIC"], ids["RAWONLY.ARW"], ids["PAIR.ARW"]}
	if fmt.Sprint(order) != fmt.Sprint(want) {
		t.Fatalf("order %v want %v", order, want)
	}
	// A second scan with nothing changed queues nothing.
	if err := hs.srv.prev.ScanNow(t.Context()); err != nil {
		t.Fatal(err)
	}
	if n := work(t, hs, previews.JobImage); n != 0 {
		t.Fatalf("rescan made %d jobs", n)
	}
}

// TestPreviewStaleFailedAndDeleted: a recipe/source change serves the old files until the new ones exist and then
// removes them; an unsupported source is reported; a deleted item loses its previews.
func TestPreviewStaleFailedAndDeleted(t *testing.T) {
	hs, gen, key, ids := previewsHarness(t)
	photo := ids["PHOTO.JPG"]
	expect(t, hs.do("GET", "/v1/items/"+photo+"/preview", nil, bearer(key)), http.StatusAccepted, "")
	work(t, hs, previews.JobImage)
	w := hs.do("GET", "/v1/items/"+photo+"/preview", nil, bearer(key))
	cur := w.Header().Get("X-Hoard-Preview-Version")

	// Pretend the files on disk were made by an older recipe.
	old := strings.Repeat("ab", 16)
	m := hs.srv.prev
	for _, f := range []string{previews.FileThumb, previews.FileScreen} {
		_ = os.MkdirAll(filepath.Dir(m.Path(old, f)), 0o700)
		if err := os.Rename(m.Path(cur, f), m.Path(old, f)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := hs.st.FinishPreview(t.Context(), photo, previews.JobImage, old, []byte(`{"thumb.jpg":{"bytes":1}}`), 1, "fake", 1); err != nil {
		t.Fatal(err)
	}
	w = hs.do("GET", "/v1/items/"+photo+"/preview", nil, bearer(key))
	expect(t, w, 200, "")
	if w.Header().Get("X-Hoard-Preview") != "stale" || w.Header().Get("X-Hoard-Preview-Version") != old {
		t.Fatalf("stale: %v", w.Header())
	}
	if n := work(t, hs, previews.JobImage); n != 1 {
		t.Fatalf("regenerated %d", n)
	}
	w = hs.do("GET", "/v1/items/"+photo+"/preview", nil, bearer(key))
	if w.Header().Get("X-Hoard-Preview") != "ready" || w.Header().Get("X-Hoard-Preview-Version") != cur {
		t.Fatalf("fresh: %v", w.Header())
	}
	if _, err := os.Stat(m.Path(old, previews.FileThumb)); !os.IsNotExist(err) {
		t.Fatal("the old files stay")
	}

	// Unsupported: reported, not retried.
	var rawFP string
	for _, j := range previews.Plan(ids["RAWONLY.ARW"], itemData(t, hs, ids["RAWONLY.ARW"])) {
		rawFP = j.Source.Fingerprint
	}
	gen.fail[rawFP] = fmt.Errorf("%w: no decoder", previews.ErrUnsupported)
	expect(t, hs.do("GET", "/v1/items/"+ids["RAWONLY.ARW"]+"/preview", nil, bearer(key)), http.StatusAccepted, "")
	work(t, hs, previews.JobImage)
	w = hs.do("GET", "/v1/items/"+ids["RAWONLY.ARW"]+"/preview", nil, bearer(key))
	expect(t, w, 404, "preview_unavailable")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"] != "unsupported" {
		t.Fatalf("reason: %s", w.Body)
	}

	// A file the generator fails on is retried later, then marked failed.
	var pairFP string
	for _, j := range previews.Plan(ids["PAIR.ARW"], itemData(t, hs, ids["PAIR.ARW"])) {
		pairFP = j.Source.Fingerprint
	}
	gen.fail[pairFP] = fmt.Errorf("vips: crashed")
	expect(t, hs.do("GET", "/v1/items/"+ids["PAIR.ARW"]+"/preview", nil, bearer(key)), http.StatusAccepted, "")
	hs.st.Now = func() time.Time { return time.Now().Add(7 * time.Hour) } // past every retry delay
	if n := work(t, hs, previews.JobImage); n != 3 {
		t.Fatalf("attempts %d, want 3", n)
	}
	hs.st.Now = time.Now
	expect(t, hs.do("GET", "/v1/items/"+ids["PAIR.ARW"]+"/preview", nil, bearer(key)), 404, "preview_unavailable")

	// Deleted: the item becomes a tombstone, the scan removes its row and files.
	if err := os.Remove(filepath.Join(hs.nas, "PHOTO.JPG")); err != nil {
		t.Fatal(err)
	}
	f, _ := hs.st.NasFolders(t.Context())
	if err := hs.srv.indexer.Folder(t.Context(), f[0]); err != nil {
		t.Fatal(err)
	}
	if err := hs.srv.prev.ScanNow(t.Context()); err != nil {
		t.Fatal(err)
	}
	if rows, _ := hs.st.Previews(t.Context(), photo); len(rows) != 0 {
		t.Fatalf("rows of a deleted item: %+v", rows)
	}
	if _, err := os.Stat(m.Path(cur, previews.FileThumb)); !os.IsNotExist(err) {
		t.Fatal("files of a deleted item stay")
	}
	expect(t, hs.do("GET", "/v1/items/"+photo+"/preview", nil, bearer(key)), 404, "not_found")
}

// TestPreviewSourceOffline: a NAS file that cannot be opened keeps the job queued (no failure), with a delay.
func TestPreviewSourceOffline(t *testing.T) {
	hs, gen, key, ids := previewsHarness(t)
	if err := os.WriteFile(filepath.Join(hs.nas, "PHOTO.JPG"), []byte("changed since indexed"), 0o600); err != nil {
		t.Fatal(err)
	}
	expect(t, hs.do("GET", "/v1/items/"+ids["PHOTO.JPG"]+"/preview", nil, bearer(key)), http.StatusAccepted, "")
	work(t, hs, previews.JobImage)
	if len(gen.jobs()) != 0 {
		t.Fatal("generated from a changed file")
	}
	rows, _ := hs.st.Previews(t.Context(), ids["PHOTO.JPG"])
	p := rows[previews.JobImage]
	if p.State != store.PreviewPending || p.Error != "source_unavailable" || p.NotBefore == 0 {
		t.Fatalf("row %+v", p)
	}
	expect(t, hs.do("GET", "/v1/items/"+ids["PHOTO.JPG"]+"/preview", nil, bearer(key)), http.StatusAccepted, "")
}

// TestPreviewsResumeAndStatusPage: a job left running by a stop is queued again; the Storage page reports sizes.
func TestPreviewsResumeAndStatusPage(t *testing.T) {
	hs, _, key, ids := previewsHarness(t)
	expect(t, hs.do("GET", "/v1/items/"+ids["PHOTO.JPG"]+"/preview", nil, bearer(key)), http.StatusAccepted, "")
	if _, ok, err := hs.st.LeasePreview(t.Context(), previews.JobImage); !ok || err != nil {
		t.Fatal("lease")
	}
	if n, err := hs.st.ResetRunningPreviews(t.Context()); n != 1 || err != nil {
		t.Fatalf("reset %d %v", n, err)
	}
	if n := work(t, hs, previews.JobImage); n != 1 {
		t.Fatalf("resumed %d", n)
	}
	w := hs.do("GET", "/admin/api/storage", nil, adminHeaders())
	expect(t, w, 200, "")
	p := decode(t, w)["previews"].(map[string]any)
	img := p["jobs"].(map[string]any)["image"].(map[string]any)
	if img["ready"].(float64) != 1 || img["bytes"].(float64) == 0 || p["generator"] != "fake" {
		t.Fatalf("report %v", p)
	}
}

func itemData(t *testing.T, hs *harness, id string) items.Data {
	t.Helper()
	row, err := hs.st.Item(t.Context(), id)
	if err != nil {
		t.Fatal(err)
	}
	var d items.Data
	if err := json.Unmarshal(row.Data, &d); err != nil {
		t.Fatal(err)
	}
	return d
}
