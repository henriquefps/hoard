package compress

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/henriquefps/hoard/server/internal/store"
)

// Settings keys (rows of settings; the admin page writes them).
const (
	SettingTrashDays      = "compress.trashDays"
	SettingPauseWhileBusy = "compress.pauseWhileBusy"
	SettingWindow         = "compress.window" // "HH:MM-HH:MM" or ""
	SettingOfferAV1       = "compress.offerAv1"
)

// Defaults.
const (
	DefaultTrashDays = 30
	busyUtilization  = 50 // percent: the GPU counts as busy above it
	maxAttempts      = 3
	noSpaceWait      = 10 * time.Minute
)

// Config is what the server gives the manager.
type Config struct {
	Store *store.Store
	Log   *slog.Logger
	// DataDir holds compress/logs and compress/meta (the metadata sidecars, one per replaced video).
	DataDir string
	// WorkRoot returns <backup root>/.hoard-compress/work ("" without a backup root: nothing runs).
	WorkRoot func() string
	Mount    *WriteMount
	// FolderDir resolves a NAS folder's read path.
	FolderDir func(ctx context.Context, folderID string) (string, error)
	Prober    *Prober
	Encoder   Encoder
	Verifier  Verifier
	Detector  Detector
	NVML      NVML // nil: pause-while-busy has nothing to read
	// Sessions is the number of encodes at a time (COMPRESS_SESSIONS, 1–3); CPUWorkers the CPU pool (estimate,
	// sidecar, checks, VMAF).
	Sessions, CPUWorkers int
	// Refresh rebuilds the items holding these fingerprints (the heir rule and "compressed" follow the records).
	Refresh func(ctx context.Context, fps []string)
	// RequestWalk asks for a walk of the folders (the replace's last step).
	RequestWalk func(folderIDs []string)
	// Blobs serves the phone and backup targets (phase S2); nil: those items cannot run.
	Blobs BlobHost
	// Poll is how often idle workers look for work (default 5 s); BusyEvery how often the GPU is read (30 s).
	Poll, BusyEvery time.Duration
	// Now (tests).
	Now func() time.Time
}

// Manager is the GPU queue and everything around it.
type Manager struct {
	cfg Config
	log *slog.Logger

	mu       sync.Mutex
	status   GPUStatus
	running  map[int64]context.CancelFunc
	progress map[int64]float64
	encoding int // our encodes now
	samples  []busySample
	probing  map[string]bool
	journal  map[int64]bool // items whose journal runs now (Settle)
	// mountProblem: a folder's probe found the write mount unusable (unstable_inode); cleared by Detect.
	mountProblem string

	gpu chan struct{}
	cpu chan struct{}
	// cpuWide serializes taking several CPU slots at once (a verification above 2.7K), so two of them never deadlock
	// holding part of the pool each.
	cpuWide sync.Mutex
	wake    chan struct{}
	ops     chan op // restore and purge, one at a time
	wg      sync.WaitGroup

	// afterStep, in tests, runs after each journal step is written; an error stops the journal there (a crash).
	afterStep func(step string) error
}

// wideFrame is the frame size above which a verification takes every CPU slot (more than 2.7K, about 4.2 MP): a 4K
// 10-bit VMAF takes about 2.9 GiB and a 4K encode 2.1 GiB, so two 4K verifications next to an encode would need about
// 8 GiB (ADR 0025 Amendment (N4)).
const wideFrame = 2720 * 1530

// cpuSlots is how many CPU slots verifying a source takes: all of them above wideFrame, else one.
func (m *Manager) cpuSlots(p Probe) int {
	if p.Width*p.Height > wideFrame {
		return cap(m.cpu)
	}
	return 1
}

// acquireCPU takes n CPU slots (false: cancelled, none held).
func (m *Manager) acquireCPU(ctx context.Context, n int) bool {
	if n > 1 {
		m.cpuWide.Lock()
		defer m.cpuWide.Unlock()
	}
	for i := range n {
		select {
		case m.cpu <- struct{}{}:
		case <-ctx.Done():
			m.releaseCPU(i)
			return false
		}
	}
	return true
}

func (m *Manager) releaseCPU(n int) {
	for range n {
		<-m.cpu
	}
}

type busySample struct {
	at       time.Time
	sessions bool // another process held an NVENC session
	busy     bool // the GPU was over 50 % busy while none of our encodes ran
}

type op struct {
	kind string // restore | purge
	ids  []int64
}

// New makes a manager; Start runs it.
func New(cfg Config) *Manager {
	if cfg.Sessions < 1 {
		cfg.Sessions = 1
	}
	if cfg.Sessions > 3 {
		cfg.Sessions = 3
	}
	if cfg.CPUWorkers < 1 {
		cfg.CPUWorkers = 2
	}
	if cfg.Poll <= 0 {
		cfg.Poll = 5 * time.Second
	}
	if cfg.BusyEvery <= 0 {
		cfg.BusyEvery = 30 * time.Second
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
		if st := cfg.Store; st != nil {
			cfg.Now = func() time.Time { return st.Now() }
		}
	}
	if cfg.Log == nil {
		cfg.Log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Manager{cfg: cfg, log: cfg.Log, running: map[int64]context.CancelFunc{}, progress: map[int64]float64{},
		probing: map[string]bool{}, gpu: make(chan struct{}, cfg.Sessions), cpu: make(chan struct{}, cfg.CPUWorkers),
		wake: make(chan struct{}, cfg.Sessions+cfg.CPUWorkers), ops: make(chan op, 64), status: GPUStatus{Codecs: []string{}, Reason: "no_gpu"}}
}

// Sessions is the configured number of encodes at a time.
func (m *Manager) Sessions() int { return m.cfg.Sessions }

// Status returns the last detection.
func (m *Manager) Status() GPUStatus {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.status
}

// Available reports whether new work may start (the feature "compress").
func (m *Manager) Available() bool { return m.Status().Available }

// NasWrite reports whether the write mount exists.
func (m *Manager) NasWrite() bool { return m.cfg.Mount.Available() }

// WriteProblem says why NAS videos cannot be replaced ("" when they can): the write mount's own state, or an unstable
// inode seen by a folder's probe (until the next "Check again").
func (m *Manager) WriteProblem() string {
	if m.cfg.Mount == nil {
		return "no_write_mount"
	}
	if p := m.cfg.Mount.Usable(); p != "" {
		return p
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.mountProblem
}

// FeatureAvailable is the feature "compress": a working GPU and a usable write mount (ADR 0025 Amendment (S1)).
func (m *Manager) FeatureAvailable() bool { return m.Available() && m.WriteProblem() == "" }

func (m *Manager) setMountProblem(p string) {
	m.mu.Lock()
	m.mountProblem = p
	m.mu.Unlock()
}

// Detect runs the GPU check now.
func (m *Manager) Detect(ctx context.Context) GPUStatus {
	if m.cfg.Detector == nil {
		return m.Status()
	}
	st := m.cfg.Detector.Detect(ctx)
	m.mu.Lock()
	was := m.status.Available
	m.status = st
	m.mountProblem = ""
	m.mu.Unlock()
	if st.Available != was || !st.Available {
		m.log.Info("compress: gpu check", "available", st.Available, "reason", st.Reason, "gpu", st.GPU, "driver", st.Driver,
			"codecs", strings.Join(st.Codecs, ","), "vmaf", st.VMAF, "cudaDecode", st.CUDADecode)
	}
	if st.Available {
		m.Wake()
	}
	return st
}

// Wake makes idle workers look for work now (one signal per worker).
func (m *Manager) Wake() {
	for range cap(m.wake) {
		select {
		case m.wake <- struct{}{}:
		default:
			return
		}
	}
}

func (m *Manager) now() time.Time { return m.cfg.Now() }

// --- settings --------------------------------------------------------------------------------------------------

func (m *Manager) setting(ctx context.Context, key string) string {
	v, _, _ := m.cfg.Store.Setting(ctx, key)
	return v
}

// TrashDays is how long what compression trashed stays before the server purges it.
func (m *Manager) TrashDays(ctx context.Context) int {
	if n, err := strconv.Atoi(m.setting(ctx, SettingTrashDays)); err == nil && n >= 1 {
		return n
	}
	return DefaultTrashDays
}

// PauseWhileBusy is on unless the admin turned it off.
func (m *Manager) PauseWhileBusy(ctx context.Context) bool {
	return m.setting(ctx, SettingPauseWhileBusy) != "0"
}

// OfferAV1 is off until the owner's playback check.
func (m *Manager) OfferAV1(ctx context.Context) bool { return m.setting(ctx, SettingOfferAV1) == "1" }

// Window returns the night window ("" = off).
func (m *Manager) Window(ctx context.Context) string { return m.setting(ctx, SettingWindow) }

// ParseWindow reads "HH:MM-HH:MM".
func ParseWindow(w string) (start, end int, ok bool) {
	a, b, found := strings.Cut(w, "-")
	if !found {
		return 0, 0, false
	}
	pm := func(s string) (int, bool) {
		h, mm, ok := strings.Cut(strings.TrimSpace(s), ":")
		hi, err1 := strconv.Atoi(h)
		mi, err2 := strconv.Atoi(mm)
		if !ok || err1 != nil || err2 != nil || hi < 0 || hi > 23 || mi < 0 || mi > 59 {
			return 0, false
		}
		return hi*60 + mi, true
	}
	s, ok1 := pm(a)
	e, ok2 := pm(b)
	return s, e, ok1 && ok2 && s != e
}

// InWindow reports whether t is inside the window (which may cross midnight).
func InWindow(w string, t time.Time) bool {
	s, e, ok := ParseWindow(w)
	if !ok {
		return true
	}
	m := t.Hour()*60 + t.Minute()
	if s < e {
		return m >= s && m < e
	}
	return m >= s || m < e
}

// --- the gate --------------------------------------------------------------------------------------------------

// sampleBusy reads the GPU. Another process's NVENC session, or utilization above 50 % while none of our encodes ran,
// makes the sample busy.
func (m *Manager) sampleBusy(ctx context.Context) {
	if m.cfg.NVML == nil {
		return
	}
	l, err := m.cfg.NVML.Load(ctx)
	if err != nil {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	m.samples = append(m.samples, busySample{at: now, sessions: l.Sessions > m.encoding, busy: m.encoding == 0 && l.Utilization > busyUtilization})
	cut := 0
	for cut < len(m.samples) && now.Sub(m.samples[cut].at) > time.Minute {
		cut++
	}
	m.samples = m.samples[cut:]
}

// gpuBusy reports another process's NVENC session now (the last sample), or the GPU over 50 % busy in the last minute.
func (m *Manager) gpuBusy() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	if n := len(m.samples); n > 0 && m.samples[n-1].sessions {
		return true
	}
	for _, s := range m.samples {
		if s.busy && now.Sub(s.at) <= time.Minute {
			return true
		}
	}
	return false
}

// leaseLimit is the highest priority a worker may lease now (-1: none): nothing without the GPU, a work directory or
// while the GPU is busy; automatic items (priority 3) only inside the night window.
func (m *Manager) leaseLimit(ctx context.Context) int {
	if !m.Available() || m.cfg.WorkRoot == nil || m.cfg.WorkRoot() == "" {
		return -1
	}
	if m.PauseWhileBusy(ctx) && m.gpuBusy() {
		return -1
	}
	if w := m.Window(ctx); w != "" && !InWindow(w, m.now()) {
		return 2
	}
	return 3
}

// Start recovers what a restart interrupted and runs the workers, the GPU sampler, the re-detection and the purge
// until ctx ends.
func (m *Manager) Start(ctx context.Context) {
	m.Detect(ctx)
	if err := m.Recover(ctx); err != nil {
		m.log.Error("compress: recover", "err", err)
	}
	m.recoverBlobs(ctx)
	for range m.cfg.Sessions + m.cfg.CPUWorkers {
		m.wg.Add(1)
		go func() { defer m.wg.Done(); m.worker(ctx) }()
	}
	m.wg.Add(1)
	go func() { defer m.wg.Done(); m.opsLoop(ctx) }()
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		busy := time.NewTicker(m.cfg.BusyEvery)
		detect := time.NewTicker(10 * time.Minute)
		housekeeping := time.NewTicker(time.Minute)
		defer busy.Stop()
		defer detect.Stop()
		defer housekeeping.Stop()
		m.sampleBusy(ctx)
		for {
			select {
			case <-ctx.Done():
				return
			case <-busy.C:
				m.sampleBusy(ctx)
			case <-detect.C:
				if !m.Available() {
					m.Detect(ctx)
				}
			case <-housekeeping.C:
				m.Housekeeping(ctx)
			}
		}
	}()
}

// Wait waits for the goroutines Start began (after ctx ended).
func (m *Manager) Wait() { m.wg.Wait() }

func (m *Manager) worker(ctx context.Context) {
	for ctx.Err() == nil {
		limit := m.leaseLimit(ctx)
		if limit >= 0 {
			it, ok, err := m.cfg.Store.LeaseCompressItem(ctx, limit)
			if err != nil && ctx.Err() == nil {
				m.log.Error("compress: lease", "err", err)
			}
			if ok {
				m.process(ctx, it)
				continue
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-m.wake:
		case <-time.After(m.cfg.Poll):
		}
	}
}

// Housekeeping settles ready items that wait (space), purges what is due and finishes jobs.
func (m *Manager) Housekeeping(ctx context.Context) {
	// Journals that wait (the write mount was unavailable, an I/O error): tried again.
	if waiting, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReplacing); err == nil {
		for _, it := range waiting {
			if it.NotBefore <= m.now().UnixMilli() && ctx.Err() == nil {
				m.Settle(ctx, it)
			}
		}
	}
	ready, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReady)
	if err == nil {
		for _, it := range ready {
			if it.NotBefore <= m.now().UnixMilli() && it.Source == "nas" && ctx.Err() == nil {
				m.replaceReady(ctx, it)
			}
		}
	}
	due, err := m.cfg.Store.CompressPurgeDue(ctx, m.now().UnixMilli())
	if err == nil && len(due) > 0 {
		ids := make([]int64, len(due))
		for i, it := range due {
			ids[i] = it.ID
		}
		m.purgeNow(ctx, ids, "auto")
	}
	m.housekeepBlobs(ctx)
	_ = m.cfg.Store.FinishCompressJobs(ctx)
	m.sweepLogs()
}

// --- running items: cancel and progress --------------------------------------------------------------------------

func (m *Manager) register(id int64, cancel context.CancelFunc) {
	m.mu.Lock()
	m.running[id] = cancel
	m.mu.Unlock()
}

func (m *Manager) unregister(id int64) {
	m.mu.Lock()
	delete(m.running, id)
	delete(m.progress, id)
	m.mu.Unlock()
}

// Progress returns the fraction of a running encode (ok false when none runs).
func (m *Manager) Progress(id int64) (float64, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	p, ok := m.progress[id]
	return p, ok
}

func (m *Manager) setProgress(id int64, p float64) {
	m.mu.Lock()
	m.progress[id] = p
	m.mu.Unlock()
}

// Cancel cancels a job's items or the given items that have not reached replacing; running processes are killed and
// their work files removed. It returns the cancelled items and, for the others, their state.
func (m *Manager) Cancel(ctx context.Context, jobID string, ids []int64) ([]store.CompressItem, []store.CompressItem, error) {
	cancelled, others, err := m.cfg.Store.CancelCompressItems(ctx, jobID, ids)
	if err != nil {
		return nil, nil, err
	}
	m.mu.Lock()
	for _, it := range cancelled {
		if c := m.running[it.ID]; c != nil {
			c()
		}
	}
	m.mu.Unlock()
	for _, it := range cancelled {
		if it.State == store.CompressCancelled {
			m.removeWork(it.ID)
			m.dropOutput(ctx, it) // a phone output nobody will fetch (ADR 0025)
		}
	}
	if jobID != "" {
		if j, err := m.cfg.Store.CompressJob(ctx, jobID); err == nil && !j.Auto {
			_ = m.cfg.Store.SetCompressJob(ctx, jobID, store.JobCancelled, 0, false)
		}
	}
	return cancelled, others, nil
}

// --- files -------------------------------------------------------------------------------------------------------

func (m *Manager) workDir(id int64) string {
	root := ""
	if m.cfg.WorkRoot != nil {
		root = m.cfg.WorkRoot()
	}
	if root == "" {
		return ""
	}
	return filepath.Join(root, strconv.FormatInt(id, 10))
}

func (m *Manager) removeWork(id int64) {
	if d := m.workDir(id); d != "" {
		_ = os.RemoveAll(d)
	}
}

// LogPath is an item's log file.
func (m *Manager) LogPath(jobID string, id int64) string {
	return filepath.Join(m.cfg.DataDir, "compress", "logs", jobID, strconv.FormatInt(id, 10)+".log")
}

func (m *Manager) openLog(it store.CompressItem) io.WriteCloser {
	p := m.LogPath(it.JobID, it.ID)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return nopCloser{io.Discard}
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return nopCloser{io.Discard}
	}
	fmt.Fprintf(f, "--- %s item %d, state %s, attempt %d\n", m.now().UTC().Format(time.RFC3339), it.ID, it.State, it.Attempts+1)
	return f
}

type nopCloser struct{ io.Writer }

func (nopCloser) Close() error { return nil }

// MetaPath is where the metadata sidecar of an output is kept: <data dir>/compress/meta/<hex[0:2]>/<hex>.json.gz.
func (m *Manager) MetaPath(outFingerprint string) string {
	h := strings.TrimPrefix(outFingerprint, "v1:")
	if len(h) < 2 {
		h = "xx" + h
	}
	return filepath.Join(m.cfg.DataDir, "compress", "meta", h[:2], h+".json.gz")
}

// sweepLogs removes the logs of jobs settled more than 90 days ago (by the directory's mtime).
func (m *Manager) sweepLogs() {
	dir := filepath.Join(m.cfg.DataDir, "compress", "logs")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	cut := m.now().Add(-90 * 24 * time.Hour)
	for _, e := range entries {
		info, err := e.Info()
		if err == nil && e.IsDir() && info.ModTime().Before(cut) {
			_ = os.RemoveAll(filepath.Join(dir, e.Name()))
		}
	}
}

var errStopped = errors.New("stopped")

// RunOnce leases and processes one item (tests and tools; the server's workers do this on their own). It reports
// whether an item was leased.
func (m *Manager) RunOnce(ctx context.Context) bool {
	limit := m.leaseLimit(ctx)
	if limit < 0 {
		return false
	}
	it, ok, err := m.cfg.Store.LeaseCompressItem(ctx, limit)
	if err != nil || !ok {
		return false
	}
	m.process(ctx, it)
	return true
}

// SetJournalHook sets a function run after each journal step is written; an error stops the journal there, as a crash
// would (tests only).
func (m *Manager) SetJournalHook(fn func(step string) error) { m.afterStep = fn }

// SampleGPU reads the GPU's load now (tests; the server samples every 30 s).
func (m *Manager) SampleGPU(ctx context.Context) { m.sampleBusy(ctx) }
