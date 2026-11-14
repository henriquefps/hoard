package previews

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Config is what the server resolves for the previews.
type Config struct {
	Dir          string // where the files live (default <data dir>/previews)
	ImageWorkers int    // image jobs at a time
	ProxyWorkers int    // proxy jobs at a time
	FullWorkers  int    // full-resolution jobs at a time (default 1)
	// FullCacheBytes bounds the full-resolution stills kept on disk (default 4 GiB): past it, the ones served least
	// recently go, and are made again when asked.
	FullCacheBytes int64
	Backlog        map[string]bool // jobs made for every item ahead of requests (the others only when asked)
	Generator      Generator
	Store          *store.Store
	// Open opens a source file by fingerprint (a NAS copy still as indexed, or an uploaded blob). An error means the
	// file is not reachable now: the job waits and is retried.
	Open func(ctx context.Context, fingerprint string) (*os.File, error)
	Log  *slog.Logger
	// RecentWindow: items created this recently are generated before the backlog (default 7 days).
	RecentWindow time.Duration
}

// Manager runs the preview queue (ADR 0015 "Queue").
type Manager struct {
	cfg     Config
	tmp     string
	wake    map[string]chan struct{}
	running map[string]*atomic.Int32
	noTools sync.Once
}

// maxAttempts: a job that fails this many times (not counting an unreachable source) is marked failed.
const maxAttempts = 3

// New prepares the previews directory (and empties its temporary folder: files of runs a stop interrupted).
func New(cfg Config) (*Manager, error) {
	if cfg.RecentWindow == 0 {
		cfg.RecentWindow = 7 * 24 * time.Hour
	}
	cfg.ImageWorkers = max(cfg.ImageWorkers, 1)
	cfg.ProxyWorkers = max(cfg.ProxyWorkers, 1)
	cfg.FullWorkers = max(cfg.FullWorkers, 1)
	if cfg.FullCacheBytes <= 0 {
		cfg.FullCacheBytes = DefaultFullCacheBytes
	}
	if err := os.MkdirAll(cfg.Dir, 0o700); err != nil {
		return nil, err
	}
	tmp := filepath.Join(cfg.Dir, "tmp")
	if err := os.RemoveAll(tmp); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(tmp, 0o700); err != nil {
		return nil, err
	}
	m := &Manager{cfg: cfg, tmp: tmp,
		wake:    map[string]chan struct{}{JobImage: make(chan struct{}, 1), JobProxy: make(chan struct{}, 1), JobFull: make(chan struct{}, 1)},
		running: map[string]*atomic.Int32{JobImage: {}, JobProxy: {}, JobFull: {}}}
	return m, nil
}

// DefaultFullCacheBytes: the full-resolution cache's default bound (about 200 stills of 24-60 MP).
const DefaultFullCacheBytes = 4 << 30

// Dir is the previews directory.
func (m *Manager) Dir() string { return m.cfg.Dir }

// Generator is the generator in use.
func (m *Manager) Generator() Generator { return m.cfg.Generator }

// Running reports how many jobs of a kind run now.
func (m *Manager) Running(job string) int { return int(m.running[job].Load()) }

// Path is where a key's file lives: <dir>/<key[0:2]>/<key>.<file>.
func (m *Manager) Path(key, file string) string {
	return filepath.Join(m.cfg.Dir, key[:2], key+"."+file)
}

func (m *Manager) signal(job string) {
	select {
	case m.wake[job] <- struct{}{}:
	default:
	}
}

// Run starts the workers and the backlog scanner until ctx ends. Jobs a previous run left running are queued again.
func (m *Manager) Run(ctx context.Context) {
	if n, err := m.cfg.Store.ResetRunningPreviews(ctx); err != nil {
		m.cfg.Log.Error("previews: resume", "err", err)
	} else if n > 0 {
		m.cfg.Log.Info("previews: resumed interrupted jobs", "count", n)
	}
	for range m.cfg.ImageWorkers {
		go m.worker(ctx, JobImage)
	}
	for range m.cfg.ProxyWorkers {
		go m.worker(ctx, JobProxy)
	}
	for range m.cfg.FullWorkers {
		go m.worker(ctx, JobFull)
	}
	go m.scanner(ctx)
}

// --- requests from the API ------------------------------------------------------------------------------------------

// State is what a client can have of an item's job now.
type State struct {
	Status  string            // ready | pending | unavailable
	Key     string            // the files' key when ready (stale or not)
	Stale   bool              // ready, but made from an older recipe or source; a new one is on its way
	Outputs map[string]Output // the files when ready
	Reason  string            // unavailable: not_applicable | unsupported | failed | no_tools
}

// Status values.
const (
	StatusReady       = "ready"
	StatusPending     = "pending"
	StatusUnavailable = "unavailable"
)

// Request returns the states of an item's jobs and queues the ones not ready (or stale) at the given priority; within
// a priority, a larger orderKey runs first.
func (m *Manager) Request(ctx context.Context, row store.ItemRow, jobs []string, priority int, orderKey int64) (map[string]State, error) {
	out := map[string]State{}
	if row.Deleted {
		for _, j := range jobs {
			out[j] = State{Status: StatusUnavailable, Reason: "not_applicable"}
		}
		return out, nil
	}
	plan, err := m.plan(ctx, row)
	if err != nil {
		return nil, err
	}
	rows, err := m.cfg.Store.Previews(ctx, row.ID)
	if err != nil {
		return nil, err
	}
	var wants []store.PreviewWant
	for _, job := range jobs {
		j, ok := plan[job]
		if !ok {
			out[job] = State{Status: StatusUnavailable, Reason: "not_applicable"}
			continue
		}
		p, have := rows[job]
		st := State{Status: StatusPending}
		if have && p.Key != "" {
			st = State{Status: StatusReady, Key: p.Key, Stale: p.Key != j.Key}
			_ = json.Unmarshal(p.Outputs, &st.Outputs)
		}
		if have && p.State == store.PreviewFailed && p.WantKey == j.Key {
			if st.Status != StatusReady {
				st = State{Status: StatusUnavailable, Reason: failReason(p.Error)}
			}
			st.Stale = false
			out[job] = st
			continue
		}
		if !have || p.Key != j.Key {
			wants = append(wants, store.PreviewWant{ItemID: row.ID, Job: job, WantKey: j.Key, Priority: priority, OrderKey: orderKey})
		}
		out[job] = st
	}
	if len(wants) > 0 {
		n, err := m.cfg.Store.WantPreviews(ctx, wants)
		if err != nil {
			return nil, err
		}
		if n > 0 {
			for _, w := range wants {
				m.signal(Lane(w.Job))
			}
		}
	}
	return out, nil
}

func failReason(e string) string {
	switch {
	case strings.HasPrefix(e, "unsupported"):
		return "unsupported"
	case strings.HasPrefix(e, "no_tools"):
		return "no_tools"
	}
	return "failed"
}

// plan is an item's jobs: Plan's, and an image job per edit (ADR 0020).
func (m *Manager) plan(ctx context.Context, row store.ItemRow) (map[string]Job, error) {
	var d items.Data
	if err := json.Unmarshal(row.Data, &d); err != nil {
		return nil, err
	}
	plan := Plan(row.ID, d)
	if len(row.Edits) > 0 {
		edits, err := m.cfg.Store.ItemEdits(ctx, row.ID) // with the fields the served JSON leaves out (the file)
		if err != nil {
			return nil, err
		}
		for job, j := range PlanEdits(row.ID, edits) {
			plan[job] = j
		}
	}
	return plan, nil
}

// FilesMissing is called when a ready row's file cannot be opened (removed by hand): the row is queued again.
func (m *Manager) FilesMissing(ctx context.Context, itemID, job string) {
	if _, err := m.cfg.Store.DeletePreviews(ctx, itemID, job); err != nil {
		m.cfg.Log.Error("previews: forget missing files", "err", err)
	}
}

// --- workers --------------------------------------------------------------------------------------------------------

func (m *Manager) worker(ctx context.Context, job string) {
	for ctx.Err() == nil {
		p, ok, err := m.cfg.Store.LeasePreview(ctx, job)
		if err != nil {
			if ctx.Err() == nil {
				m.cfg.Log.Error("previews: lease", "err", err)
			}
			m.sleep(ctx, job, 30*time.Second)
			continue
		}
		if !ok {
			wait := 5 * time.Minute
			if at, err := m.cfg.Store.NextPreviewAt(ctx, job); err == nil && at > 0 {
				wait = min(wait, max(time.Until(time.UnixMilli(at)), time.Second))
			}
			m.sleep(ctx, job, wait)
			continue
		}
		m.running[job].Add(1)
		m.runJob(ctx, p)
		m.running[job].Add(-1)
	}
}

// Work runs one queued job of a kind now, if one may run (tests and tools); it reports whether it ran one.
func (m *Manager) Work(ctx context.Context, job string) (bool, error) {
	p, ok, err := m.cfg.Store.LeasePreview(ctx, job)
	if err != nil || !ok {
		return false, err
	}
	m.runJob(ctx, p)
	return true, nil
}

func (m *Manager) sleep(ctx context.Context, job string, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-m.wake[job]:
	case <-t.C:
	}
}

// backoff is the wait before retrying a job after its n-th attempt: 1 min, 5 min, 30 min, then 6 h.
func backoff(n int) time.Duration {
	switch {
	case n <= 1:
		return time.Minute
	case n == 2:
		return 5 * time.Minute
	case n == 3:
		return 30 * time.Minute
	}
	return 6 * time.Hour
}

func (m *Manager) runJob(ctx context.Context, p store.Preview) {
	st := m.cfg.Store
	log := m.cfg.Log.With("item", p.ItemID, "job", p.Job)
	row, err := st.Item(ctx, p.ItemID)
	if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
		m.drop(ctx, p.ItemID, p.Job)
		return
	}
	if err != nil {
		m.retry(ctx, p, "store: "+err.Error(), time.Minute)
		return
	}
	plan, err := m.plan(ctx, row)
	if err != nil {
		m.fail(ctx, p, "failed: item data")
		return
	}
	j, ok := plan[p.Job]
	if !ok {
		m.drop(ctx, p.ItemID, p.Job)
		return
	}
	if j.Key != p.WantKey {
		if err := st.SetPreviewWant(ctx, p.ItemID, p.Job, j.Key); err != nil {
			m.retry(ctx, p, "store: "+err.Error(), time.Minute)
			return
		}
	}
	gen := m.cfg.Generator
	if !gen.Accepts(j) {
		m.retry(ctx, p, "no_tools: no generator takes this job", time.Hour)
		return
	}
	src, err := m.cfg.Open(ctx, j.Source.Fingerprint)
	if err != nil {
		m.retry(ctx, p, "source_unavailable", backoff(p.Attempts))
		return
	}
	defer src.Close()
	dir, err := os.MkdirTemp(m.tmp, "job-")
	if err != nil {
		m.retry(ctx, p, "tmp: "+err.Error(), time.Minute)
		return
	}
	defer func() { _ = os.RemoveAll(dir) }()
	start := time.Now()
	res, err := gen.Generate(ctx, j, src, dir)
	ms := time.Since(start).Milliseconds()
	switch {
	case ctx.Err() != nil:
		return // queued again on the next start
	case errors.Is(err, ErrNoTools):
		m.noTools.Do(func() { log.Warn("previews: tools missing (vips, ffmpeg): jobs wait until they are installed") })
		m.retry(ctx, p, "no_tools", time.Hour)
		return
	case errors.Is(err, ErrUnsupported):
		log.Info("preview unsupported", "kind", j.Kind, "role", j.Source.Role, "format", j.Source.Format, "err", err.Error())
		m.fail(ctx, p, "unsupported: "+err.Error())
		return
	case err != nil:
		if p.Attempts >= maxAttempts {
			log.Warn("preview failed", "kind", j.Kind, "role", j.Source.Role, "format", j.Source.Format, "err", err.Error())
			m.fail(ctx, p, "failed: "+err.Error())
		} else {
			m.retry(ctx, p, "failed: "+err.Error(), backoff(p.Attempts))
		}
		return
	}
	var total int64
	if err := os.MkdirAll(filepath.Join(m.cfg.Dir, j.Key[:2]), 0o700); err != nil {
		m.retry(ctx, p, "store: "+err.Error(), time.Minute)
		return
	}
	for name, o := range res {
		if err := os.Rename(filepath.Join(dir, name), m.Path(j.Key, name)); err != nil {
			m.retry(ctx, p, "store: "+err.Error(), time.Minute)
			return
		}
		total += o.Bytes
	}
	outputs, _ := json.Marshal(res)
	old, err := st.FinishPreview(ctx, p.ItemID, p.Job, j.Key, outputs, total, gen.Name(), ms)
	if err != nil {
		log.Error("previews: finish", "err", err)
		return
	}
	if old != "" {
		m.removeKey(old)
	}
	log.Debug("preview made", "kind", j.Kind, "ms", ms, "bytes", total)
	if p.Job == JobFull {
		m.TrimFull(ctx)
	}
}

// Served marks a full-resolution still as just used (the cache keeps the most recently served ones).
func (m *Manager) Served(ctx context.Context, itemID, job string) {
	if job != JobFull {
		return
	}
	if err := m.cfg.Store.TouchPreview(ctx, itemID, job); err != nil && ctx.Err() == nil {
		m.cfg.Log.Warn("previews: touch", "err", err.Error())
	}
}

// SetFullCacheBytes changes the full-resolution cache's bound (tests and tools); TrimFull applies it.
func (m *Manager) SetFullCacheBytes(n int64) { m.cfg.FullCacheBytes = n }

// TrimFull drops the least recently served full-resolution stills past FullCacheBytes (after every full job).
func (m *Manager) TrimFull(ctx context.Context) {
	rows, err := m.cfg.Store.PreviewsByAge(ctx, JobFull)
	if err != nil {
		m.cfg.Log.Error("previews: full cache", "err", err)
		return
	}
	var total int64
	for _, p := range rows {
		total += p.Bytes
	}
	for _, p := range rows[:max(len(rows)-1, 0)] { // never the newest: the one just made or served
		if total <= m.cfg.FullCacheBytes {
			return
		}
		m.drop(ctx, p.ItemID, JobFull)
		total -= p.Bytes
	}
}

func (m *Manager) retry(ctx context.Context, p store.Preview, msg string, after time.Duration) {
	if err := m.cfg.Store.RetryPreview(ctx, p.ItemID, p.Job, msg, time.Now().Add(after).UnixMilli()); err != nil && ctx.Err() == nil {
		m.cfg.Log.Error("previews: requeue", "err", err)
	}
}

func (m *Manager) fail(ctx context.Context, p store.Preview, msg string) {
	if len(msg) > 400 {
		msg = msg[:400]
	}
	if err := m.cfg.Store.FailPreview(ctx, p.ItemID, p.Job, msg); err != nil && ctx.Err() == nil {
		m.cfg.Log.Error("previews: fail", "err", err)
	}
}

func (m *Manager) drop(ctx context.Context, itemID, job string) {
	keys, err := m.cfg.Store.DeletePreviews(ctx, itemID, job)
	if err != nil {
		m.cfg.Log.Error("previews: drop", "err", err)
		return
	}
	for _, k := range keys {
		m.removeKey(k)
	}
}

// files every key may have.
var allFiles = []string{FileThumb, FileScreen, FileThumbWebP, FileScreenWebP, FileProxy, FileFull}

func (m *Manager) removeKey(key string) {
	if len(key) < 2 {
		return
	}
	for _, f := range allFiles {
		if err := os.Remove(m.Path(key, f)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			m.cfg.Log.Warn("previews: a file stays", "err", err.Error())
		}
	}
}

// --- backlog --------------------------------------------------------------------------------------------------------

// scanner follows the items feed: every changed item gets its backlog jobs queued (recent items first, newest shot
// first), deleted items lose their rows and files. It polls the items counter, so it needs no hook in the indexer.
// Files no row names are swept every 6 hours.
func (m *Manager) scanner(ctx context.Context) {
	var since int64
	var lastSweep time.Time
	for ctx.Err() == nil {
		head, err := m.cfg.Store.Counter(ctx, "items")
		if err == nil && head > since {
			since, err = m.scan(ctx, since)
		}
		if err != nil && ctx.Err() == nil {
			m.cfg.Log.Error("previews: scan", "err", err)
		}
		if time.Since(lastSweep) > 6*time.Hour {
			lastSweep = time.Now()
			m.sweep(ctx)
		}
		t := time.NewTimer(15 * time.Second)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-t.C:
		}
	}
}

// ScanNow runs one scan from the start of the items feed (tests and tools).
func (m *Manager) ScanNow(ctx context.Context) error {
	_, err := m.scan(ctx, 0)
	return err
}

func (m *Manager) scan(ctx context.Context, since int64) (int64, error) {
	recent := time.Now().Add(-m.cfg.RecentWindow).UnixMilli()
	queued := map[string]bool{}
	for {
		rows, more, err := m.cfg.Store.ItemChanges(ctx, since, 500)
		if err != nil {
			return since, err
		}
		var wants []store.PreviewWant
		var withEdits []string
		for _, r := range rows {
			if !r.Deleted && len(r.Edits) > 0 {
				withEdits = append(withEdits, r.ID)
			}
		}
		edits, err := m.cfg.Store.EditsByItem(ctx, withEdits)
		if err != nil {
			return since, err
		}
		for _, r := range rows {
			since = r.Rev
			if r.Deleted {
				m.drop(ctx, r.ID, "")
				continue
			}
			var d items.Data
			if err := json.Unmarshal(r.Data, &d); err != nil {
				continue
			}
			plan := Plan(r.ID, d)
			for job, j := range PlanEdits(r.ID, edits[r.ID]) {
				plan[job] = j
			}
			existing, err := m.cfg.Store.Previews(ctx, r.ID)
			if err != nil {
				return since, err
			}
			for job := range existing {
				if _, ok := plan[job]; !ok {
					m.drop(ctx, r.ID, job)
				}
			}
			prio := store.PriorityBacklog
			if r.CreatedAt >= recent {
				prio = store.PriorityRecent
			}
			for job, j := range plan {
				if p, ok := existing[job]; (ok && p.WantKey == j.Key) || !m.cfg.Backlog[Lane(job)] && !ok {
					continue // up to date, or made on request only
				}
				wants = append(wants, store.PreviewWant{ItemID: r.ID, Job: job, WantKey: j.Key, Priority: prio, OrderKey: r.SortTime})
				queued[Lane(job)] = true
			}
		}
		if _, err := m.cfg.Store.WantPreviews(ctx, wants); err != nil {
			return since, err
		}
		for job := range queued {
			m.signal(job)
		}
		if !more {
			return since, nil
		}
	}
}

// sweep removes files no row names (left by a crash between writing files and recording them), older than an hour.
func (m *Manager) sweep(ctx context.Context) {
	keys, err := m.cfg.Store.PreviewKeys(ctx)
	if err != nil {
		m.cfg.Log.Error("previews: sweep", "err", err)
		return
	}
	removed := 0
	cutoff := time.Now().Add(-time.Hour)
	entries, err := os.ReadDir(m.cfg.Dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		if !e.IsDir() || len(e.Name()) != 2 {
			continue
		}
		files, err := os.ReadDir(filepath.Join(m.cfg.Dir, e.Name()))
		if err != nil {
			continue
		}
		for _, f := range files {
			key, _, _ := strings.Cut(f.Name(), ".")
			if keys[key] {
				continue
			}
			if fi, err := f.Info(); err == nil && fi.ModTime().Before(cutoff) {
				if os.Remove(filepath.Join(m.cfg.Dir, e.Name(), f.Name())) == nil {
					removed++
				}
			}
		}
	}
	if removed > 0 {
		m.cfg.Log.Info("previews: removed unused files", "count", removed)
	}
}

// DiskUsage walks the previews directory (the status page's size report).
func (m *Manager) DiskUsage() (files int, bytes int64) {
	_ = filepath.WalkDir(m.cfg.Dir, func(_ string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if fi, err := d.Info(); err == nil {
			files++
			bytes += fi.Size()
		}
		return nil
	})
	return
}

// String describes the configuration for the log.
func (m *Manager) String() string {
	var b []string
	for j, on := range m.cfg.Backlog {
		if on {
			b = append(b, j)
		}
	}
	return fmt.Sprintf("generator=%s image=%d proxy=%d full=%d fullCache=%dMiB backlog=%v", m.cfg.Generator.Name(), m.cfg.ImageWorkers,
		m.cfg.ProxyWorkers, m.cfg.FullWorkers, m.cfg.FullCacheBytes>>20, b)
}
