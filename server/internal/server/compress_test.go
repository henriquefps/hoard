package server

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Video compression (ADR 0025, phase S1) end to end with a fake encoder and verifier: synthetic files on a temporary
// NAS folder mounted "read-only" and "read-write" (the same directory), the walk, the heir rule, restore and purge.

// fakeEncoder writes an "output": ratio of the source's size, its own bytes (a different fingerprint per q), with the
// source's mtime. slow, when set, blocks each encode until released or cancelled.
type fakeEncoder struct {
	ratio   float64
	fail    atomic.Int32 // encodes left to fail
	slow    chan struct{}
	running atomic.Int32
	maxRun  atomic.Int32
	encodes atomic.Int32
	started chan struct{}
}

func (f *fakeEncoder) Encode(ctx context.Context, r compress.EncodeRequest) (compress.EncodeResult, error) {
	n := f.running.Add(1)
	defer f.running.Add(-1)
	for {
		m := f.maxRun.Load()
		if n <= m || f.maxRun.CompareAndSwap(m, n) {
			break
		}
	}
	f.encodes.Add(1)
	if f.started != nil {
		select {
		case f.started <- struct{}{}:
		default:
		}
	}
	if f.slow != nil {
		select {
		case <-f.slow:
		case <-ctx.Done():
			return compress.EncodeResult{}, ctx.Err()
		}
	}
	if f.fail.Load() > 0 {
		f.fail.Add(-1)
		return compress.EncodeResult{}, fmt.Errorf("%w: fake", compress.ErrEncoder)
	}
	fi, err := r.Src.Stat()
	if err != nil {
		return compress.EncodeResult{}, err
	}
	ratio := f.ratio
	if ratio == 0 {
		ratio = 0.3
	}
	size := int64(float64(fi.Size()) * ratio)
	if _, err := r.Src.Seek(0, io.SeekStart); err != nil {
		return compress.EncodeResult{}, err
	}
	buf := make([]byte, size)
	if _, err := io.ReadFull(r.Src, buf); err != nil {
		return compress.EncodeResult{}, err
	}
	for i := range buf { // rewrite the bytes: another fingerprint
		buf[i] ^= byte(0x5a + r.Q)
	}
	if err := os.WriteFile(r.Out, buf, 0o600); err != nil {
		return compress.EncodeResult{}, err
	}
	return compress.EncodeResult{Decoder: "cuda"}, os.Chtimes(r.Out, r.SrcMTime, r.SrcMTime)
}

func (f *fakeEncoder) Sidecar(_ context.Context, _ *os.File, out io.Writer, _ io.Writer) error {
	zw := gzip.NewWriter(out)
	_, _ = zw.Write([]byte(`[{"Track1:SampleTime":"0 s"}]`))
	return zw.Close()
}

type fakeVerifier struct {
	mu    sync.Mutex
	vmaf  map[int]float64 // by q; default 95
	check error
}

func (v *fakeVerifier) Check(context.Context, *os.File, string, io.Writer) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.check
}

func (v *fakeVerifier) VMAF(_ context.Context, _ *os.File, out string, _ compress.Probe, _ io.Writer) (compress.VMAFResult, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	b, err := os.ReadFile(out)
	if err != nil || len(b) == 0 {
		return compress.VMAFResult{}, errors.New("no output")
	}
	q := int(b[0]) - 0x5a // the first source byte is 0 in our files, so the output's first byte is 0x5a+q
	s, ok := v.vmaf[q]
	if !ok {
		s = 95
	}
	return compress.VMAFResult{Mean: s, Low: s - 3, Mode: "full"}, nil
}

type fakeDetector struct{ ok bool }

func (d fakeDetector) Detect(context.Context) compress.GPUStatus {
	if !d.ok {
		return compress.GPUStatus{Codecs: []string{}, Reason: "no_gpu"}
	}
	return compress.GPUStatus{Available: true, GPU: "Fake GPU", Codecs: []string{"hevc", "av1"}, VMAF: true, CUDADecode: true, Exiftool: true}
}

type fakeNVML struct {
	mu   sync.Mutex
	load compress.GPULoad
}

func (n *fakeNVML) Load(context.Context) (compress.GPULoad, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.load, nil
}

type cHarness struct {
	*harness
	key, folder string
	enc         *fakeEncoder
	ver         *fakeVerifier
	nvml        *fakeNVML
	linker      func(root *os.Root, o, n string) error
}

// compressHarness: a NAS folder with videos at the given paths (8 MB of synthetic bytes each), walked, with probes
// stored as a 1080p30 H.264 camera clip (10 s, 50 Mb/s).
func compressHarness(t *testing.T, gpu bool, paths ...string) *cHarness {
	t.Helper()
	ch := &cHarness{enc: &fakeEncoder{}, ver: &fakeVerifier{vmaf: map[int]float64{}}, nvml: &fakeNVML{}}
	hs := newHarnessWith(t, true, func(c *Config) {
		nas := filepath.Join(c.Mounts[0], "nas")
		c.NasReadRoot, c.NasWriteRoot = nas, nas
		c.CompressEncoder, c.CompressVerifier, c.CompressDetector, c.CompressNVML = ch.enc, ch.ver, fakeDetector{ok: gpu}, ch.nvml
		c.CompressPoll = 10 * time.Millisecond
		c.CompressLinker = func(r *os.Root, o, n string) error {
			if ch.linker != nil {
				return ch.linker(r, o, n)
			}
			return r.Link(o, n)
		}
	})
	ch.harness = hs
	hs.srv.compress.Detect(t.Context())
	ch.key, _ = hs.pair("Phone")
	for i, p := range paths {
		ch.writeVideo(p, uint64(i+1))
	}
	w := hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders())
	expect(t, w, 201, "")
	ch.folder = decode(t, w)["id"].(string)
	hs.walkAll()
	ch.storeProbes()
	return ch
}

func (ch *cHarness) writeVideo(rel string, seed uint64) {
	ch.t.Helper()
	b := make([]byte, 8<<20)
	r := rand.New(rand.NewPCG(seed, 7))
	for i := range b {
		b[i] = byte(r.Uint32())
	}
	b[0] = 0
	p := filepath.Join(ch.nas, rel)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		ch.t.Fatal(err)
	}
	if err := os.WriteFile(p, b, 0o640); err != nil {
		ch.t.Fatal(err)
	}
	at := time.Date(2024, 5, 1, 10, 0, int(seed), 0, time.UTC)
	if err := os.Chtimes(p, at, at); err != nil {
		ch.t.Fatal(err)
	}
}

var testProbe = compress.Probe{Format: "mov,mp4", DurationMs: 10000, Codec: "h264", Width: 1920, Height: 1080, PixFmt: "yuv420p", BitDepth: 8,
	BitRate: 50_000_000, RFrameRate: 30, AvgFrameRate: 30, NbFrames: 300, Audio: []string{"aac"}, AudioBitRate: 128000}

// storeProbes stores testProbe for every video file without one.
func (ch *cHarness) storeProbes() {
	ch.t.Helper()
	states, err := ch.st.NasFileStates(ch.t.Context(), ch.folder)
	if err != nil {
		ch.t.Fatal(err)
	}
	b, _ := json.Marshal(testProbe)
	for _, s := range states {
		if s.Fingerprint != "" && s.Media == "video" {
			if have, _ := ch.st.VideoProbes(ch.t.Context(), compress.ProbeVersion, []string{s.Fingerprint}); len(have) == 0 {
				if err := ch.st.PutVideoProbe(ch.t.Context(), s.Fingerprint, compress.ProbeVersion, b); err != nil {
					ch.t.Fatal(err)
				}
			}
		}
	}
}

func (ch *cHarness) fp(rel string) string {
	ch.t.Helper()
	f, err := os.Open(filepath.Join(ch.nas, rel))
	if err != nil {
		ch.t.Fatal(err)
	}
	defer f.Close()
	fi, _ := f.Stat()
	fp, err := fingerprint.Of(f, fi.Size())
	if err != nil {
		ch.t.Fatal(err)
	}
	return fp
}

func (ch *cHarness) itemOf(rel string) string { return store.ItemID(ch.fp(rel)) }

func (ch *cHarness) job(sel map[string]any, extra map[string]any) map[string]any {
	ch.t.Helper()
	body := map[string]any{"target": "nas", "select": sel, "requestId": fmt.Sprint(rand.Uint64())}
	for k, v := range extra {
		body[k] = v
	}
	w := ch.do("POST", "/v1/compress/jobs", body, bearer(ch.key))
	if w.Code != 201 && w.Code != 200 {
		ch.t.Fatalf("job: %d %s", w.Code, w.Body.String())
	}
	return decode(ch.t, w)
}

func (ch *cHarness) runAll() {
	ch.t.Helper()
	for ch.srv.compress.RunOnce(ch.t.Context()) {
	}
}

func (ch *cHarness) items() []store.CompressItem {
	ch.t.Helper()
	list, _, err := ch.st.CompressItems(ch.t.Context(), store.CompressItemQuery{Limit: 1000})
	if err != nil {
		ch.t.Fatal(err)
	}
	return list
}

func (ch *cHarness) one() store.CompressItem {
	ch.t.Helper()
	l := ch.items()
	if len(l) != 1 {
		ch.t.Fatalf("%d items", len(l))
	}
	return l[0]
}

func exists(p string) bool { _, err := os.Lstat(p); return err == nil }

func trashDirs(t *testing.T, dir string) []string {
	t.Helper()
	var out []string
	_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && strings.Contains(p, ".hoard-trash") {
			rel, _ := filepath.Rel(dir, p)
			out = append(out, rel)
		}
		return nil
	})
	return out
}

// The whole NAS flow: estimate, job, replaced (the original in the trash by a hard link, the path never missing), the
// walk (original trashed with replacedBy and compressedTo, the output with compressed, culling moved), restore,
// restore again, purge by route; old clients see the tombstone shape with replacedBy.
func TestCompressNASFlow(t *testing.T) {
	ch := compressHarness(t, true, "2024/Trip/A.MP4", "2024/Trip/B.MOV")
	ctx := t.Context()
	origFP := ch.fp("2024/Trip/A.MP4")
	orig := store.ItemID(origFP)
	// A rating on the original's item, to follow replacedBy.
	if _, err := ch.st.PushCulling(ctx, mustDevice(t, ch), []store.CullChange{{ItemID: orig, Field: store.CullRating,
		Value: json.RawMessage(`4`), ModifiedAt: 1}}); err != nil {
		t.Fatal(err)
	}

	// The estimate.
	w := ch.do("POST", "/v1/compress/estimate", map[string]any{"target": "nas", "select": map[string]any{"folders": []any{map[string]any{"folderId": ch.folder,
		"path": "2024"}}}}, bearer(ch.key))
	expect(t, w, 200, "")
	est := decode(t, w)
	totals := est["totals"].(map[string]any)
	if totals["items"].(float64) != 2 || totals["encode"].(float64) != 2 || est["pending"].(float64) != 0 {
		t.Fatalf("estimate %v", est)
	}
	first := est["items"].([]any)[0].(map[string]any)
	if first["decision"] != "encode" || first["estSaving"].(float64) < 0.2 || first["warnings"] == nil {
		t.Fatalf("estimate item %v", first)
	}

	// The job: selection by item.
	job := ch.job(map[string]any{"itemIds": []string{orig}}, nil)
	if job["queued"].(float64) != 1 || job["state"] != "active" {
		t.Fatalf("job %v", job)
	}
	ch.runAll()
	it := ch.one()
	if it.State != store.CompressReplaced || it.TrashPath == "" || *it.Q != 28 || it.Decoder != "cuda" || it.OutFingerprint == "" {
		t.Fatalf("item %+v", it)
	}
	rel := "2024/Trip/A.MP4"
	if ch.fp(rel) != it.OutFingerprint {
		t.Fatal("the path does not hold the output")
	}
	if !strings.HasPrefix(it.TrashPath, ".hoard-trash/") || !strings.HasSuffix(it.TrashPath, "/2024/Trip/A.MP4") || ch.fp(it.TrashPath) != origFP {
		t.Fatalf("trash path %q", it.TrashPath)
	}
	var st syscall.Stat_t
	_ = syscall.Stat(filepath.Join(ch.nas, it.TrashPath), &st)
	if st.Nlink != 1 {
		t.Fatalf("the original has %d names", st.Nlink)
	}
	fi, _ := os.Stat(filepath.Join(ch.nas, rel))
	if fi.Mode().Perm() != 0o640 || fi.ModTime().Unix() != time.Date(2024, 5, 1, 10, 0, 1, 0, time.UTC).Unix() {
		t.Fatalf("mode %v mtime %v", fi.Mode(), fi.ModTime())
	}
	if leftovers, _ := filepath.Glob(filepath.Join(ch.nas, "2024/Trip/.*")); len(leftovers) != 0 {
		t.Fatalf("temporary files left: %v", leftovers)
	}

	// The walk: the output a new item with "compressed", the original trashed with replacedBy.
	ch.walkAll()
	out := store.ItemID(it.OutFingerprint)
	oi := getItem(t, ch.harness, ch.key, out, "")
	c, _ := oi["compressed"].(map[string]any)
	if c == nil || c["from"] != origFP || c["fromItem"] != orig || c["original"] != "trashed" || c["profile"] != "hevc-cq28" || c["q"].(float64) != 28 {
		t.Fatalf("output item %v", oi)
	}
	tomb := getItem(t, ch.harness, ch.key, orig, "")
	if tomb["deleted"] != true || tomb["replacedBy"] != out || len(tomb) != 6 {
		t.Fatalf("old-client shape %v", tomb)
	}
	whole := getItem(t, ch.harness, ch.key, orig, "?include=trash")
	if whole["trashed"] == nil || whole["compressedTo"] != out || whole["replacedBy"] != out {
		t.Fatalf("trashed original %v", whole)
	}
	if rec, err := ch.st.Culling(ctx, out); err != nil || string(rec.Fields[store.CullRating].Value) != "4" {
		t.Fatalf("culling did not follow: %+v %v", rec, err)
	}
	// GET /v1/originals of the trashed original still answers (ADR 0023).
	expect(t, ch.do("GET", "/v1/originals/"+origFP, nil, bearer(ch.key)), 200, "")
	// Lookup: both sides.
	w = ch.do("POST", "/v1/compress/lookup", map[string]any{"fingerprints": []string{origFP, it.OutFingerprint}}, bearer(ch.key))
	expect(t, w, 200, "")
	lk := decode(t, w)["fingerprints"].(map[string]any)
	if lk[origFP].(map[string]any)["role"] != "original" || lk[it.OutFingerprint].(map[string]any)["role"] != "output" {
		t.Fatalf("lookup %v", lk)
	}

	// Restore by the gallery item id of the trashed original.
	w = ch.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{orig}}, bearer(ch.key))
	expect(t, w, 200, "")
	if s := decode(t, w)["items"].(map[string]any)[orig].(map[string]any)["state"]; s != "replacing" {
		t.Fatalf("restore answer %v", s)
	}
	ch.srv.compress.RunOps(ctx)
	it = ch.one()
	if it.State != store.CompressRestored || ch.fp(rel) != origFP || ch.fp(it.TrashPath) != it.OutFingerprint {
		t.Fatalf("restored %+v", it)
	}
	ch.walkAll()
	if live := getItem(t, ch.harness, ch.key, orig, ""); live["deleted"] != false || live["replacedBy"] != nil {
		t.Fatalf("the original is not live again: %v", live)
	}
	if o := getItem(t, ch.harness, ch.key, out, "?include=trash"); o["trashed"] == nil || o["replacedBy"] != orig {
		t.Fatalf("the output is not trashed with replacedBy: %v", o)
	}
	if rec, err := ch.st.Culling(ctx, orig); err != nil || string(rec.Fields[store.CullRating].Value) != "4" {
		t.Fatalf("culling did not come back: %+v %v", rec, err)
	}

	// Restore again: the output back, the original into the trash.
	w = ch.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{fmt.Sprint(it.ID)}}, bearer(ch.key))
	expect(t, w, 200, "")
	ch.srv.compress.RunOps(ctx)
	it = ch.one()
	if it.State != store.CompressReplaced || ch.fp(rel) != it.OutFingerprint || ch.fp(it.TrashPath) != origFP {
		t.Fatalf("replaced again %+v", it)
	}
	ch.walkAll()
	if tomb := getItem(t, ch.harness, ch.key, orig, ""); tomb["replacedBy"] != out {
		t.Fatalf("after restore again %v", tomb)
	}

	// Purge by route: the trashed original deleted; after the walk a tombstone with replacedBy.
	w = ch.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{fmt.Sprint(it.ID)}}, bearer(ch.key))
	expect(t, w, 200, "")
	it = ch.one()
	if it.State != store.CompressPurged || exists(filepath.Join(ch.nas, it.TrashPath)) {
		t.Fatalf("purge %+v", it)
	}
	ch.walkAll()
	row, _ := ch.st.Item(ctx, orig)
	if !row.Deleted || !strings.Contains(string(row.Data), out) {
		t.Fatalf("purged original %+v %s", row, row.Data)
	}
	if oi := getItem(t, ch.harness, ch.key, out, ""); oi["compressed"].(map[string]any)["original"] != "purged" {
		t.Fatalf("output after purge %v", oi)
	}
	// Nothing left to restore.
	w = ch.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{fmt.Sprint(it.ID)}}, bearer(ch.key))
	if e := decode(t, w)["items"].(map[string]any)[fmt.Sprint(it.ID)].(map[string]any)["error"]; e != "state" {
		t.Fatalf("restore of a purged item: %v", e)
	}
	// The job is done; GET /v1/server has the feature and the object.
	_ = ch.st.FinishCompressJobs(ctx)
	j := decode(t, ch.do("GET", "/v1/compress/jobs/"+job["id"].(string), nil, bearer(ch.key)))
	if j["state"] != "done" || j["bytes"].(map[string]any)["saved"].(float64) <= 0 {
		t.Fatalf("job %v", j)
	}
	srv := decode(t, ch.do("GET", "/v1/server", nil, bearer(ch.key)))
	if !strings.Contains(fmt.Sprint(srv["features"]), "compress") || srv["compress"].(map[string]any)["nasWrite"] != true {
		t.Fatalf("server %v", srv)
	}
}

func mustDevice(t *testing.T, ch *cHarness) string {
	t.Helper()
	ds, err := ch.st.Devices(t.Context())
	if err != nil || len(ds) == 0 {
		t.Fatal(err)
	}
	return ds[0].ID
}

// Purge by trashDays (the server's own retention) and by Hoard emptying the trash (the next walk).
func TestCompressPurgeByRetentionAndByTrash(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4", "B.MP4")
	ctx := t.Context()
	ch.job(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder}}}, nil)
	ch.runAll()
	items := ch.items()
	if len(items) != 2 || items[0].State != store.CompressReplaced || items[1].State != store.CompressReplaced {
		t.Fatalf("%+v", items)
	}
	// Hoard empties B's original over SMB: the walk makes the row purged.
	if err := os.Remove(filepath.Join(ch.nas, items[1].TrashPath)); err != nil {
		t.Fatal(err)
	}
	ch.srv.IndexNow(ctx)
	if it, _ := ch.st.CompressItem(ctx, items[1].ID); it.State != store.CompressPurged {
		t.Fatalf("emptied by Hoard: %s", it.State)
	}
	// A's original: due after trashDays.
	ch.st.Now = func() time.Time { return time.Now().Add(31 * 24 * time.Hour) }
	defer func() { ch.st.Now = time.Now }()
	ch.srv.compress.Housekeeping(ctx)
	it, _ := ch.st.CompressItem(ctx, items[0].ID)
	if it.State != store.CompressPurged || exists(filepath.Join(ch.nas, items[0].TrashPath)) {
		t.Fatalf("retention: %+v", it)
	}
}

// The replace journal killed after each step and settled at the next start.
func TestCompressReplaceJournalCrash(t *testing.T) {
	for _, step := range []string{"replace:check", "replace:copy", "replace:trash", "replace:swap"} {
		t.Run(step, func(t *testing.T) {
			ch := compressHarness(t, true, "A.MP4")
			origFP := ch.fp("A.MP4")
			ch.srv.compress.SetJournalHook(func(s string) error {
				if s == step {
					return errors.New("crash")
				}
				return nil
			})
			ch.job(map[string]any{"itemIds": []string{store.ItemID(origFP)}}, nil)
			ch.runAll()
			if it := ch.one(); it.State != store.CompressReplacing || it.ReplaceStep != step {
				t.Fatalf("crashed at %+v", it)
			}
			if !exists(filepath.Join(ch.nas, "A.MP4")) {
				t.Fatal("the path went missing")
			}
			ch.srv.compress.SetJournalHook(nil)
			if err := ch.srv.compress.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			it := ch.one()
			if it.State != store.CompressReplaced || ch.fp("A.MP4") != it.OutFingerprint || ch.fp(it.TrashPath) != origFP {
				t.Fatalf("settled %+v", it)
			}
			if tr := trashDirs(t, ch.nas); len(tr) != 1 {
				t.Fatalf("trash %v", tr)
			}
			if left, _ := filepath.Glob(filepath.Join(ch.nas, ".A.MP4*")); len(left) != 0 {
				t.Fatalf("left %v", left)
			}
		})
	}
}

// The restore journal killed after each step and settled.
func TestCompressRestoreJournalCrash(t *testing.T) {
	for _, step := range []string{"restore:check", "restore:trash", "restore:swap"} {
		t.Run(step, func(t *testing.T) {
			ch := compressHarness(t, true, "A.MP4")
			origFP := ch.fp("A.MP4")
			ch.job(map[string]any{"itemIds": []string{store.ItemID(origFP)}}, nil)
			ch.runAll()
			ch.srv.compress.SetJournalHook(func(s string) error {
				if strings.HasPrefix(s, step) {
					return errors.New("crash")
				}
				return nil
			})
			id := ch.one().ID
			ch.srv.compress.Restore(t.Context(), []int64{id})
			ch.srv.compress.RunOps(t.Context())
			if it := ch.one(); it.State != store.CompressReplacing || !strings.HasPrefix(it.ReplaceStep, step) {
				t.Fatalf("crashed at %+v", it)
			}
			ch.srv.compress.SetJournalHook(nil)
			if err := ch.srv.compress.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			it := ch.one()
			if it.State != store.CompressRestored || ch.fp("A.MP4") != origFP || ch.fp(it.TrashPath) != it.OutFingerprint {
				t.Fatalf("settled %+v", it)
			}
		})
	}
}

// Without hard links (EPERM) the original is renamed into the trash; across filesystems (EXDEV) the item fails cleanly.
func TestCompressLinkerFallbacks(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4", "B.MP4")
	ch.linker = func(*os.Root, string, string) error { return &os.LinkError{Op: "link", Err: syscall.EPERM} }
	a := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(a)}}, nil)
	ch.runAll()
	it := ch.one()
	if it.State != store.CompressReplaced || ch.fp(it.TrashPath) != a || ch.fp("A.MP4") != it.OutFingerprint {
		t.Fatalf("EPERM: %+v", it)
	}
	ch.linker = func(*os.Root, string, string) error { return &os.LinkError{Op: "link", Err: syscall.EXDEV} }
	b := ch.fp("B.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(b)}}, nil)
	ch.runAll()
	var bi store.CompressItem
	for _, x := range ch.items() {
		if x.Fingerprint == b {
			bi = x
		}
	}
	if bi.State != store.CompressFailed || bi.Reason != compress.ReasonTrashFailed || ch.fp("B.MP4") != b {
		t.Fatalf("EXDEV: %+v", bi)
	}
	if left, _ := filepath.Glob(filepath.Join(ch.nas, ".B.MP4*")); len(left) != 0 {
		t.Fatalf("left %v", left)
	}
}

// A source changed between the encode and the replace is refused; mode C re-encodes lower under 93 and fails under 92;
// rule 5 discards a small real saving; a transient encoder failure is retried.
func TestCompressQualityAndGuards(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4", "B.MP4", "C.MP4", "D.MP4")
	ctx := t.Context()
	id := func(rel string) string { return ch.itemOf(rel) }
	fpOf := map[string]string{}
	for _, r := range []string{"A.MP4", "B.MP4", "C.MP4", "D.MP4"} {
		fpOf[r] = ch.fp(r)
	}
	byFP := func(fp string) store.CompressItem {
		for _, x := range ch.items() {
			if x.Fingerprint == fp {
				return x
			}
		}
		t.Fatalf("no item for %s", fp)
		return store.CompressItem{}
	}
	// Mode C: 90 at 28, 92.5 at 26 → 94 at 24 kept.
	ch.ver.vmaf = map[int]float64{28: 90, 26: 92.5, 24: 94}
	ch.job(map[string]any{"itemIds": []string{id("A.MP4")}}, nil)
	ch.runAll()
	if a := byFP(fpOf["A.MP4"]); a.State != store.CompressReplaced || *a.Q != 24 || *a.VmafMean != 94 {
		t.Fatalf("mode C %+v", a)
	}
	// Under 92 at the last step: verify_quality.
	ch.ver.vmaf = map[int]float64{28: 88, 26: 89, 24: 91}
	ch.job(map[string]any{"itemIds": []string{id("B.MP4")}}, nil)
	ch.runAll()
	if b := byFP(fpOf["B.MP4"]); b.State != store.CompressFailed || b.Reason != "verify_quality" || ch.fp("B.MP4") != fpOf["B.MP4"] {
		t.Fatalf("quality %+v", b)
	}
	// Rule 5: a real saving under 20 %.
	ch.ver.vmaf = map[int]float64{}
	ch.enc.ratio = 0.9
	ch.job(map[string]any{"itemIds": []string{id("C.MP4")}}, nil)
	ch.runAll()
	if c := byFP(fpOf["C.MP4"]); c.State != store.CompressSkipped || c.Reason != "small_saving" {
		t.Fatalf("rule 5 %+v", c)
	}
	// A transient encoder failure: queued again with a back-off, then done.
	ch.enc.ratio = 0.3
	ch.enc.fail.Store(1)
	ch.job(map[string]any{"itemIds": []string{id("D.MP4")}}, nil)
	ch.runAll()
	d := byFP(fpOf["D.MP4"])
	if d.State != store.CompressQueued || d.Reason != "encoder" || d.Attempts != 1 || d.NotBefore <= time.Now().UnixMilli() {
		t.Fatalf("retry %+v", d)
	}
	ch.st.Now = func() time.Time { return time.Now().Add(time.Hour) }
	ch.runAll()
	ch.st.Now = time.Now
	if d = byFP(fpOf["D.MP4"]); d.State != store.CompressReplaced {
		t.Fatalf("after retry %+v", d)
	}
	// A source changed after the output was made: ready → failed source_changed, the file untouched.
	ch.writeVideo("E.MP4", 50)
	ch.walkAll()
	ch.storeProbes()
	e := ch.fp("E.MP4")
	ch.srv.compress.SetJournalHook(func(s string) error {
		if s == "replace:check" {
			ch.writeVideo("E.MP4", 51) // edited meanwhile
		}
		return nil
	})
	ch.job(map[string]any{"itemIds": []string{store.ItemID(e)}}, nil)
	ch.runAll()
	ch.srv.compress.SetJournalHook(nil)
	if x := byFP(e); x.State != store.CompressFailed || x.Reason != "source_changed" || len(trashDirs(t, ch.nas)) != 2 {
		t.Fatalf("changed %+v %v", x, trashDirs(t, ch.nas))
	}
	_ = ctx
}

// Skip rules seen through the job: a marker, an older compression, a Reveal record, a sync conflict copy, an output.
func TestCompressSkipsThroughJob(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4", "B.MP4", "C.MP4", "x/.stversions/D.MP4", "E.sync-conflict-20240101-1.MP4")
	ctx := t.Context()
	set := func(rel string, p compress.Probe) {
		b, _ := json.Marshal(p)
		if err := ch.st.PutVideoProbe(ctx, ch.fp(rel), compress.ProbeVersion, b); err != nil {
			t.Fatal(err)
		}
	}
	marked := testProbe
	marked.Marker = "v1, hevc-cq28, 2026-01-01"
	set("A.MP4", marked)
	old := testProbe
	old.Codec, old.Encoder, old.BitRate = "hevc", "Lavc61.3.100 hevc_nvenc", 20_000_000
	set("B.MP4", old)
	w := ch.do("POST", "/v1/compress/estimate", map[string]any{"target": "nas", "select": map[string]any{"folders": []any{map[string]any{"folderId": ch.folder}}}},
		bearer(ch.key))
	expect(t, w, 200, "")
	skip := decode(t, w)["totals"].(map[string]any)["skip"].(map[string]any)
	if skip["already_compressed"] != 1.0 || skip["older_compression"] != 1.0 || skip["sync_artifact"] != 1.0 {
		t.Fatalf("skips %v", skip)
	}
	// .stversions is a dot folder: the walk never indexed it.
	job := ch.job(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder}}}, nil)
	if job["queued"].(float64) != 1 {
		t.Fatalf("job %v", job)
	}
	// The same selection again: the queued one is busy.
	w = ch.do("POST", "/v1/compress/jobs", map[string]any{"target": "nas", "requestId": "again", "select": map[string]any{"itemIds": []string{ch.itemOf("C.MP4")}}},
		bearer(ch.key))
	expect(t, w, 409, "conflict")
	// Idempotency: the same requestId answers the same job.
	w = ch.do("POST", "/v1/compress/jobs", map[string]any{"target": "nas", "requestId": "again", "select": map[string]any{"itemIds": []string{ch.itemOf("C.MP4")}}},
		bearer(ch.key))
	expect(t, w, 409, "conflict")
	// Targets of phase S2, AV1 not offered, bad selections.
	expect(t, ch.do("POST", "/v1/compress/estimate", map[string]any{"target": "phone", "select": map[string]any{"itemIds": []string{ch.itemOf("C.MP4")}}}, bearer(ch.key)),
		422, "invalid_request")
	expect(t, ch.do("POST", "/v1/compress/jobs", map[string]any{"target": "nas", "requestId": "av1", "profile": "av1-cq34",
		"select": map[string]any{"itemIds": []string{ch.itemOf("C.MP4")}}}, bearer(ch.key)), 422, "invalid_request")
	expect(t, ch.do("POST", "/v1/compress/estimate", map[string]any{"target": "nas", "select": map[string]any{"folders": []any{map[string]any{"folderId": ch.folder,
		"path": "../x"}}}}, bearer(ch.key)), 422, "invalid_request")
}

// Without a GPU: no feature, starting work is 503 no_gpu, the queue does not lease; restore and purge still work.
func TestCompressWithoutGPU(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("A.MP4")}}, nil)
	ch.runAll()
	id := ch.one().ID
	// The GPU goes away.
	ch.srv.cfg.CompressDetector = fakeDetector{ok: false}
	ch.srv.compress = ch.srv.newCompress()
	ch.srv.compress.Detect(t.Context())
	srv := decode(t, ch.do("GET", "/v1/server", nil, bearer(ch.key)))
	if strings.Contains(fmt.Sprint(srv["features"]), "compress") || srv["compress"].(map[string]any)["available"] != false ||
		srv["compress"].(map[string]any)["reason"] != "no_gpu" {
		t.Fatalf("server %v", srv)
	}
	w := ch.do("POST", "/v1/compress/jobs", map[string]any{"target": "nas", "requestId": "x", "select": map[string]any{"itemIds": []string{ch.itemOf("A.MP4")}}},
		bearer(ch.key))
	expect(t, w, 503, "unavailable")
	if decode(t, w)["error"].(map[string]any)["details"].(map[string]any)["reason"] != "no_gpu" {
		t.Fatal(w.Body.String())
	}
	if ch.srv.compress.RunOnce(t.Context()) {
		t.Fatal("leased without a GPU")
	}
	expect(t, ch.do("POST", "/v1/compress/restore", map[string]any{"itemIds": []string{fmt.Sprint(id)}}, bearer(ch.key)), 200, "")
	ch.srv.compress.RunOps(t.Context())
	if ch.one().State != store.CompressRestored {
		t.Fatal("restore without a GPU")
	}
	expect(t, ch.do("POST", "/v1/compress/purge", map[string]any{"itemIds": []string{fmt.Sprint(id)}}, bearer(ch.key)), 200, "")
	if ch.one().State != store.CompressPurged {
		t.Fatal("purge without a GPU")
	}
}

// Priorities, one session, pause while the GPU is busy, the night window holding automatic items, cancel of a running
// encode, and the restart of running items.
func TestCompressQueue(t *testing.T) {
	ch := compressHarness(t, true, "a/1.MP4", "a/2.MP4", "b/3.MP4", "b/4.MP4")
	ctx := t.Context()
	// Priorities: the job with priority 1 first, newest shot first within it.
	ch.job(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder, "path": "a"}}}, nil)
	jb := ch.job(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder, "path": "b"}}}, nil)
	expect(t, ch.do("PATCH", "/v1/compress/jobs/"+jb["id"].(string), map[string]any{"priority": 1}, bearer(ch.key)), 200, "")
	it, ok, err := ch.st.LeaseCompressItem(ctx, 3)
	if err != nil || !ok || !strings.HasPrefix(it.RelPath, "b/4") {
		t.Fatalf("first lease %+v", it)
	}
	if err := ch.srv.compress.Recover(ctx); err != nil {
		t.Fatal(err)
	}
	if back, _ := ch.st.CompressItem(ctx, it.ID); back.State != store.CompressQueued || back.Attempts != 0 {
		t.Fatalf("restart: %+v", back)
	}

	// Pause while busy: another process's NVENC session.
	ch.nvml.mu.Lock()
	ch.nvml.load = compress.GPULoad{Sessions: 1, Utilization: 20}
	ch.nvml.mu.Unlock()
	ch.srv.compress.SampleGPU(ctx)
	if ch.srv.compress.RunOnce(ctx) {
		t.Fatal("leased while another process encodes")
	}
	_ = ch.st.SetSetting(ctx, compress.SettingPauseWhileBusy, "0")
	if !ch.srv.compress.RunOnce(ctx) {
		t.Fatal("pause-while-busy off: nothing leased")
	}
	_ = ch.st.SetSetting(ctx, compress.SettingPauseWhileBusy, "1")
	// Utilization over 50 % in the last minute holds the queue too, after the other session ended.
	ch.nvml.mu.Lock()
	ch.nvml.load = compress.GPULoad{Utilization: 80}
	ch.nvml.mu.Unlock()
	ch.srv.compress.SampleGPU(ctx)
	if ch.srv.compress.RunOnce(ctx) {
		t.Fatal("leased while the GPU is busy")
	}
	ch.nvml.mu.Lock()
	ch.nvml.load = compress.GPULoad{}
	ch.nvml.mu.Unlock()
	ch.st.Now = func() time.Time { return time.Now().Add(2 * time.Minute) } // the busy minute has passed
	ch.srv.compress.SampleGPU(ctx)

	// Paused job: its items wait.
	expect(t, ch.do("PATCH", "/v1/compress/jobs/"+jb["id"].(string), map[string]any{"paused": true}, bearer(ch.key)), 200, "")

	// One session with workers running: a slow encoder, then cancel.
	ch.enc.slow = make(chan struct{})
	ch.enc.started = make(chan struct{}, 8)
	runCtx, stop := context.WithCancel(ctx)
	defer stop()
	ch.srv.compress.Start(runCtx)
	<-ch.enc.started
	time.Sleep(100 * time.Millisecond)
	if ch.enc.maxRun.Load() != 1 {
		t.Fatalf("%d encodes at once", ch.enc.maxRun.Load())
	}
	var running store.CompressItem
	for _, x := range ch.items() {
		if x.State == store.CompressEncoding {
			running = x
		}
	}
	if running.ID == 0 {
		t.Fatal("nothing encoding")
	}
	w := ch.do("POST", "/v1/compress/cancel", map[string]any{"itemIds": []string{fmt.Sprint(running.ID)}}, bearer(ch.key))
	expect(t, w, 200, "")
	deadline := time.Now().Add(5 * time.Second)
	for ch.enc.running.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if c, _ := ch.st.CompressItem(ctx, running.ID); c.State != store.CompressCancelled || exists(filepath.Join(ch.backup, ".hoard-compress/work", fmt.Sprint(running.ID))) {
		t.Fatalf("cancel %+v", c)
	}
	close(ch.enc.slow)
	stop()
	ch.srv.compress.Wait()
	ch.st.Now = time.Now
}

// The night window holds automatic items only; a folder compressed automatically gets only the videos that appear after
// the switch; turning it off cancels its queued items.
func TestCompressAutomaticFolder(t *testing.T) {
	ch := compressHarness(t, true, "Drone/old.MP4")
	ctx := t.Context()
	auto := ch.job(map[string]any{"folders": []any{map[string]any{"folderId": ch.folder, "path": "Drone"}}}, map[string]any{"auto": true})
	if auto["auto"] != true || auto["queued"].(float64) != 0 {
		t.Fatalf("auto %v", auto)
	}
	w := ch.do("POST", "/v1/compress/jobs", map[string]any{"target": "nas", "requestId": "again", "auto": true,
		"select": map[string]any{"folders": []any{map[string]any{"folderId": ch.folder, "path": "Drone"}}}}, bearer(ch.key))
	expect(t, w, 409, "conflict")
	time.Sleep(5 * time.Millisecond)
	ch.writeVideo("Drone/new.MP4", 9)
	ch.writeVideo("Other/new.MP4", 10)
	ch.walkAll()
	ch.storeProbes()
	ch.srv.compress.AfterWalk(ctx)
	list := ch.items()
	if len(list) != 1 || list[0].RelPath != "Drone/new.MP4" || list[0].Priority != 3 {
		t.Fatalf("appended %+v", list)
	}
	// The window (e.g. 01:00–01:01, not now) holds it.
	now := time.Now()
	win := fmt.Sprintf("%02d:%02d-%02d:%02d", (now.Hour()+2)%24, 0, (now.Hour()+3)%24, 0)
	_ = ch.st.SetSetting(ctx, compress.SettingWindow, win)
	if ch.srv.compress.RunOnce(ctx) {
		t.Fatal("an automatic item ran outside the window")
	}
	// An on-demand job runs anyway.
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("Drone/old.MP4")}}, nil)
	if !ch.srv.compress.RunOnce(ctx) {
		t.Fatal("on-demand item held by the window")
	}
	// Switch off: the queued automatic item is cancelled.
	expect(t, ch.do("PATCH", "/v1/compress/jobs/"+auto["id"].(string), map[string]any{"auto": false}, bearer(ch.key)), 200, "")
	for _, x := range ch.items() {
		if x.RelPath == "Drone/new.MP4" && x.State != store.CompressCancelled {
			t.Fatalf("after switch off %+v", x)
		}
	}
	jobs := decode(t, ch.do("GET", "/v1/compress/jobs?auto=true&state=active", nil, bearer(ch.key)))["jobs"].([]any)
	if len(jobs) != 0 {
		t.Fatalf("auto jobs %v", jobs)
	}
}

// An output copied into another NAS folder over its original there follows the heir rule: the original's item (whose
// only location vanished) becomes a tombstone with replacedBy, not a plain one.
func TestCompressOutputCopiedOverOriginal(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ctx := t.Context()
	origFP := ch.fp("A.MP4")
	ch.job(map[string]any{"itemIds": []string{store.ItemID(origFP)}}, nil)
	ch.runAll()
	it := ch.one()
	ch.walkAll()
	// Another folder, outside the write mount, with a copy of the original… then the output synced over it.
	other := filepath.Join(ch.mnt, "copies")
	if err := os.MkdirAll(other, 0o700); err != nil {
		t.Fatal(err)
	}
	orig, _ := os.ReadFile(filepath.Join(ch.nas, it.TrashPath))
	if err := os.WriteFile(filepath.Join(other, "A.MP4"), orig, 0o600); err != nil {
		t.Fatal(err)
	}
	expect(t, ch.do("POST", "/admin/api/nas", map[string]any{"path": other}, adminHeaders()), 201, "")
	// Purge the original from the trash first: the copy is then its only location.
	ch.srv.compress.Purge(ctx, []int64{it.ID})
	ch.walkAll()
	if r, _ := ch.st.Item(ctx, store.ItemID(origFP)); r.Deleted || r.Trashed() {
		t.Fatalf("the copy keeps the original live: %+v", r)
	}
	out, _ := os.ReadFile(filepath.Join(ch.nas, "A.MP4"))
	if err := os.WriteFile(filepath.Join(other, "A.MP4"), out, 0o600); err != nil {
		t.Fatal(err)
	}
	ch.walkAll()
	r, _ := ch.st.Item(ctx, store.ItemID(origFP))
	if !r.Deleted || !strings.Contains(string(r.Data), store.ItemID(it.OutFingerprint)) {
		t.Fatalf("tombstone %+v %s", r, r.Data)
	}
}

// Every write to the NAS goes through the Replacer: no other file of the server names the write mount.
func TestOnlyReplacerWrites(t *testing.T) {
	var offenders []string
	_ = filepath.WalkDir("../..", func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && (d.Name() == "web" || d.Name() == "node_modules" || d.Name() == "testdata") {
			return filepath.SkipDir
		}
		if d.IsDir() || !strings.HasSuffix(p, ".go") || strings.HasSuffix(p, "_test.go") {
			return nil
		}
		b, _ := os.ReadFile(p)
		src := string(b)
		if strings.Contains(src, "WriteRoot") || strings.Contains(src, "nas-write") || strings.Contains(src, "NAS_WRITE_ROOT") {
			switch filepath.Base(p) {
			case "replace.go", "select.go", "compress.go", "server.go", "main.go", "compress_api.go":
				// replace.go opens it; the others pass the configured path along or name it in messages.
			default:
				offenders = append(offenders, p)
			}
		}
		if strings.Contains(src, ".Mount.Folder(") && filepath.Base(p) != "nas.go" {
			offenders = append(offenders, p)
		}
		return nil
	})
	if len(offenders) > 0 {
		t.Fatalf("files naming the write mount: %v", offenders)
	}
}

// The setup page's Compression section: status, settings (validated), an item's log.
func TestCompressAdmin(t *testing.T) {
	ch := compressHarness(t, true, "A.MP4")
	ch.job(map[string]any{"itemIds": []string{ch.itemOf("A.MP4")}}, nil)
	ch.runAll()
	w := ch.do("GET", "/admin/api/compress", nil, adminHeaders())
	expect(t, w, 200, "")
	m := decode(t, w)
	if m["gpu"].(map[string]any)["available"] != true || m["nasWrite"] != true || m["pauseWhileBusy"] != true || m["trashDays"].(float64) != 30 ||
		m["accounting"].(map[string]any)["inTrash"].(float64) != 1 || len(m["items"].([]any)) != 1 {
		t.Fatalf("%v", m)
	}
	expect(t, ch.do("PUT", "/admin/api/compress/settings", map[string]any{"window": "25:00-01:00"}, adminHeaders()), 422, "invalid_request")
	expect(t, ch.do("PUT", "/admin/api/compress/settings", map[string]any{"trashDays": 0}, adminHeaders()), 422, "invalid_request")
	w = ch.do("PUT", "/admin/api/compress/settings", map[string]any{"window": "01:00-08:00", "offerAv1": true, "trashDays": 14, "pauseWhileBusy": false},
		adminHeaders())
	expect(t, w, 200, "")
	if m := decode(t, w); m["window"] != "01:00-08:00" || m["offerAv1"] != true || m["trashDays"].(float64) != 14 || m["pauseWhileBusy"] != false {
		t.Fatalf("%v", m)
	}
	if srv := decode(t, ch.do("GET", "/v1/server", nil, bearer(ch.key))); srv["compress"].(map[string]any)["offerAv1"] != true {
		t.Fatal("offerAv1")
	}
	w = ch.do("GET", fmt.Sprintf("/admin/api/compress/items/%d/log", ch.one().ID), nil, adminHeaders())
	expect(t, w, 200, "")
	if body := w.Body.String(); !strings.Contains(body, "replaced") || strings.Contains(body, ch.nas) {
		t.Fatalf("log %q", body)
	}
	// Device keys never reach the setup page (from outside the network it does not answer at all).
	expect(t, ch.do("GET", "/admin/api/compress", nil, bearer(ch.key)), 404, "")
}
