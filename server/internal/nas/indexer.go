// Package nas indexes the read-only NAS folders chosen on the setup page: it walks them, keeps photos (RAW, JPEG,
// HEIF), videos and XMP sidecars, computes each new or changed file's v1 fingerprint and reads its metadata, and
// groups the files into items (ADR 0014). It also turns uploaded originals into items. It never writes to a folder.
package nas

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/mounts"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Supported reports whether a file name is a photo the server indexes for Reveal (the NAS feed): RAW, JPEG, HEIF
// (the list of ImageFormats in Reveal's :core:image).
func Supported(name string) bool {
	m, _ := media.Classify(name)
	return m == media.MediaImage
}

// Indexer walks the enabled folders on demand and periodically.
type Indexer struct {
	Store    *store.Store
	Roots    mounts.Roots
	Log      *slog.Logger
	Interval time.Duration
	Workers  int
	// Prober reads video metadata (nil or without ffprobe: videos are indexed and their metadata read later).
	Prober *media.Prober
	// OpenBlob opens an uploaded blob by SHA-256 (nil: uploaded originals are not items yet).
	OpenBlob func(sha256 string) (*os.File, error)
	// AfterWalk runs after the folders are walked, before the uploaded originals and phone backups are synced (ADR
	// 0024: the phone backup's NAS references are checked against what the walk found).
	AfterWalk func(context.Context)
	// AfterBackup runs after the uploaded originals and phone backups are synced (ADR 0025: phone folders compressed
	// automatically).
	AfterBackup func(context.Context)

	trigger chan struct{}
	backup  chan struct{}
	folders chan struct{}
	once    sync.Once
	mu      sync.Mutex
	running bool
	last    Stats
	warned  bool
	// WalkGap is the shortest time between the start of a folder's last walk and a walk a device asked for
	// (POST /v1/nas/walk, ADR 0023); 0 = FolderWalkGap.
	WalkGap time.Duration
	// RequestGap is the server-wide budget of requested walks: one per folder per RequestGap, whoever asks;
	// 0 = FolderRequestGap. Requests beyond it are coalesced into the next walk the budget allows.
	RequestGap time.Duration
	lastAsked  map[string]time.Time // when a requested walk of each folder last started

	queued    map[string]bool
	lastStart map[string]time.Time
	// beforeDir, when set (tests), runs as the walk enters each directory, before reading it.
	beforeDir func(rel string)
}

// FolderWalkGap is the default WalkGap; FolderRequestGap the default RequestGap.
const (
	FolderWalkGap    = 10 * time.Second
	FolderRequestGap = time.Minute
)

// dueAt is when a requested walk of a folder may start (the zero time: now). Called with ix.mu held.
func (ix *Indexer) dueAt(id string) time.Time {
	gap, budget := ix.WalkGap, ix.RequestGap
	if gap <= 0 {
		gap = FolderWalkGap
	}
	if budget <= 0 {
		budget = FolderRequestGap
	}
	var at time.Time
	if last, ok := ix.lastStart[id]; ok {
		at = last.Add(gap)
	}
	if last, ok := ix.lastAsked[id]; ok && last.Add(budget).After(at) {
		at = last.Add(budget)
	}
	return at
}

// Stats is what the last walk of a folder did (for logs, tests and the benchmark).
type Stats struct {
	Files      int // supported files seen
	Hashed     int // fingerprints computed
	MetaRead   int // files whose metadata was read
	Probed     int // ffprobe runs
	Removed    int
	Sources    int // item sources written or removed
	Items      int // item revisions written
	Culling    int // culling fields written from sidecars
	DurationMs int64
}

func (ix *Indexer) init() {
	ix.once.Do(func() {
		ix.trigger = make(chan struct{}, 1)
		ix.backup = make(chan struct{}, 1)
		ix.folders = make(chan struct{}, 1)
	})
}

// RequestWalk queues a walk of each folder (ADR 0023, POST /v1/nas/walk), coalesced: a folder already queued is not
// queued again (one running is queued once more, so the walk sees what changed after it started). A requested walk
// starts at least WalkGap after the folder's last walk started and RequestGap after its last requested walk started
// (the server-wide budget). It returns, per folder whose walk waits for that, when it may start.
func (ix *Indexer) RequestWalk(ids []string) map[string]time.Time {
	ix.init()
	ix.mu.Lock()
	if ix.queued == nil {
		ix.queued = map[string]bool{}
	}
	now := time.Now()
	deferred := map[string]time.Time{}
	for _, id := range ids {
		ix.queued[id] = true
		if at := ix.dueAt(id); at.After(now) {
			deferred[id] = at
		}
	}
	ix.mu.Unlock()
	ix.signalFolders()
	return deferred
}

func (ix *Indexer) signalFolders() {
	select {
	case ix.folders <- struct{}{}:
	default:
	}
}

// LastRequested is when the folder's last requested walk started (zero: none since the start).
func (ix *Indexer) LastRequested(id string) time.Time {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	return ix.lastAsked[id]
}

// Queued lists the folders waiting for a requested walk.
func (ix *Indexer) Queued() []string {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	out := make([]string, 0, len(ix.queued))
	for id := range ix.queued {
		out = append(out, id)
	}
	return out
}

// walkQueued walks the requested folders that are due; the others are walked when their gap has passed.
func (ix *Indexer) walkQueued(ctx context.Context) {
	ix.mu.Lock()
	now := time.Now()
	var due []string
	var wait time.Duration
	if ix.lastAsked == nil {
		ix.lastAsked = map[string]time.Time{}
	}
	for id := range ix.queued {
		if at := ix.dueAt(id); at.After(now) {
			if w := at.Sub(now); wait == 0 || w < wait {
				wait = w
			}
			continue
		}
		due = append(due, id)
		delete(ix.queued, id)
		ix.lastAsked[id] = now
	}
	ix.mu.Unlock()
	if wait > 0 {
		time.AfterFunc(wait, ix.signalFolders)
	}
	if len(due) == 0 {
		return
	}
	folders, err := ix.Store.NasFolders(ctx)
	if err != nil {
		ix.Log.Error("nas: list folders", "err", err)
		return
	}
	want := map[string]bool{}
	for _, id := range due {
		want[id] = true
	}
	ix.setRunning(true)
	defer ix.setRunning(false)
	for _, f := range folders {
		if !want[f.ID] || !f.Enabled || ctx.Err() != nil {
			continue
		}
		if err := ix.Folder(ctx, f); err != nil && ctx.Err() == nil {
			ix.Log.Warn("nas: folder not indexed", "folder", f.ID, "err", errorClass(err))
		}
	}
	if ix.AfterWalk != nil && ctx.Err() == nil {
		ix.AfterWalk(ctx)
		ix.Backup(ctx)
	}
}

// Trigger asks for a walk soon (coalesced).
func (ix *Indexer) Trigger() {
	ix.init()
	select {
	case ix.trigger <- struct{}{}:
	default:
	}
}

// TriggerBackup asks for the uploaded originals to be turned into items soon (coalesced), without walking folders.
func (ix *Indexer) TriggerBackup() {
	ix.init()
	select {
	case ix.backup <- struct{}{}:
	default:
	}
}

// Running reports whether a walk is in progress.
func (ix *Indexer) Running() bool {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	return ix.running
}

// LastStats returns the statistics of the last folder walk.
func (ix *Indexer) LastStats() Stats {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	return ix.last
}

// Run walks every enabled folder at start, on Trigger and every Interval, until ctx ends.
func (ix *Indexer) Run(ctx context.Context) {
	ix.init()
	interval := ix.Interval
	if interval <= 0 {
		interval = 30 * time.Minute
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	ix.All(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		case <-ix.trigger:
		case <-ix.backup:
			ix.Backup(ctx)
			continue
		case <-ix.folders:
			ix.walkQueued(ctx)
			continue
		}
		ix.All(ctx)
	}
}

func (ix *Indexer) setRunning(v bool) {
	ix.mu.Lock()
	ix.running = v
	ix.mu.Unlock()
}

// All indexes every enabled folder once, drops the items of folders no longer indexed, then syncs uploaded
// originals.
func (ix *Indexer) All(ctx context.Context) {
	ix.setRunning(true)
	defer ix.setRunning(false)
	folders, err := ix.Store.NasFolders(ctx)
	if err != nil {
		ix.Log.Error("nas: list folders", "err", err)
		return
	}
	enabled := map[string]bool{}
	for _, f := range folders {
		if !f.Enabled || ctx.Err() != nil {
			continue
		}
		enabled[f.ID] = true
		if err := ix.Folder(ctx, f); err != nil && ctx.Err() == nil {
			ix.Log.Warn("nas: folder not indexed", "folder", f.ID, "err", errorClass(err))
		}
	}
	if ctx.Err() != nil {
		return
	}
	if ix.AfterWalk != nil {
		ix.AfterWalk(ctx)
	}
	withSources, err := ix.Store.SourceFolders(ctx)
	if err != nil {
		ix.Log.Error("nas: item sources", "err", err)
		return
	}
	for _, id := range withSources {
		if !enabled[id] {
			st, err := ix.syncer(ctx).SyncFolder(ctx, id, nil)
			if err != nil {
				ix.Log.Error("nas: drop items of a removed folder", "folder", id, "err", err)
			} else {
				ix.Log.Info("nas: items of a removed folder dropped", "folder", id, "items", st.Items)
			}
		}
	}
	ix.Backup(ctx)
}

// Rebuild recomputes the given items now (ADR 0025: a compression's state change moves its heir rule).
func (ix *Indexer) Rebuild(ctx context.Context, ids []string) (int, error) {
	return ix.syncer(ctx).Rebuild(ctx, ids)
}

func (ix *Indexer) syncer(ctx context.Context) *items.Syncer {
	names := map[string]string{}
	if folders, err := ix.Store.NasFolders(ctx); err == nil {
		for _, f := range folders {
			names[f.ID] = f.Name
		}
	}
	return &items.Syncer{Store: ix.Store, FolderNames: names}
}

// errorClass keeps file names out of the logs: only the kind of failure.
func errorClass(err error) string {
	switch {
	case errors.Is(err, mounts.ErrOutside):
		return "outside mount roots or missing"
	case errors.Is(err, fs.ErrPermission):
		return "permission denied"
	case errors.Is(err, fs.ErrNotExist):
		return "not found"
	case errors.Is(err, errEmptyMount):
		return "folder is empty but the index holds files (mount missing?)"
	case errors.Is(err, context.Canceled):
		return "cancelled"
	default:
		return "io error"
	}
}

// NasState is a known file with its path.
type NasState struct {
	Path  string
	State store.NasFileState
}

// under reports whether rel lies in one of dirs.
func under(rel string, dirs []string) bool {
	for _, d := range dirs {
		if strings.HasPrefix(rel, d+"/") {
			return true
		}
	}
	return false
}

// errEmptyMount: the walk saw no media file while the index holds live files of the folder. A mount that is gone leaves
// an empty, readable directory, so this is treated as unavailable: nothing is removed and no sidecar record is dropped.
var errEmptyMount = errors.New("folder shows no files but the index holds some")

type seen struct {
	rel    string
	size   int64
	mtime  int64
	media  string
	hash   bool   // compute the fingerprint
	fp     string // known fingerprint (meta only)
	origin string // in a trash (ADR 0023): where the file came from
}

type result struct {
	update *store.NasFileUpdate
	fp     string
	meta   []byte
	probed int
}

// Folder walks one folder, stores the differences and syncs the folder's items.
func (ix *Indexer) Folder(ctx context.Context, f store.NasFolder) error {
	fail := func(state string, err error) error {
		msg := errorClass(err)
		_ = ix.Store.SetNasFolderState(ctx, f.ID, state, &msg, false)
		return err
	}
	dir, err := ix.Roots.Resolve(f.Path)
	if err != nil {
		return fail("unavailable", err)
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return fail("unavailable", err)
	}
	defer root.Close()
	_ = ix.Store.SetNasFolderState(ctx, f.ID, "indexing", nil, false)
	start := time.Now()
	ix.mu.Lock()
	if ix.lastStart == nil {
		ix.lastStart = map[string]time.Time{}
	}
	ix.lastStart[f.ID] = start
	ix.mu.Unlock()
	stats := Stats{}

	var files []seen
	complete := true        // false when a sub-folder could not be read: nothing is concluded from what is missing
	var unreadable []string // the folders that could not be read
	walkErr := fs.WalkDir(root.FS(), ".", func(p string, d fs.DirEntry, err error) error {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		if err != nil {
			if p == "." {
				return err
			}
			complete = false
			unreadable = append(unreadable, p)
			return fs.SkipDir // an unreadable sub-folder is skipped, never the reason to drop files
		}
		if d.IsDir() && ix.beforeDir != nil {
			ix.beforeDir(p)
		}
		if p != "." && (strings.HasPrefix(d.Name(), ".") || inTrash(path.Dir(p)) || d.IsDir()) {
			enter, keep := walkDecision(p, d)
			if d.IsDir() {
				if enter {
					return nil
				}
				return fs.SkipDir
			}
			if !keep {
				return nil
			}
		}
		if !d.Type().IsRegular() { // symbolic links and devices are never followed
			return nil
		}
		mediaClass, _ := media.Classify(d.Name())
		if mediaClass == "" {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			complete = false
			return nil
		}
		s := seen{rel: p, size: info.Size(), mtime: info.ModTime().UnixNano(), media: mediaClass}
		if inTrash(p) {
			s.origin, _ = trashOrigin(p)
		}
		files = append(files, s)
		return nil
	})
	if walkErr != nil {
		return fail("error", walkErr) // nothing is removed when the walk itself fails
	}

	known, err := ix.Store.NasFileStates(ctx, f.ID)
	if err != nil {
		return fail("error", err)
	}
	if len(files) == 0 { // files in a trash count (ADR 0023): a folder whose every shot is trashed is not empty
		for _, k := range known {
			if !k.Removed {
				ix.Log.Warn("nas: folder shows no files but the index holds some, treated as unavailable (unmounted?); nothing removed", "folder", f.ID)
				return fail("unavailable", errEmptyMount)
			}
		}
	}
	present := make(map[string]bool, len(files))
	trashOf := map[string]string{} // original path -> a trash row of it seen now
	for _, s := range files {
		present[s.rel] = true
		if s.origin != "" && trashOf[s.origin] < s.rel {
			trashOf[s.origin] = s.rel
		}
	}
	// The second look (ADR 0023): a file missing from the listing whose counterpart (its trash copy, or a trash row's
	// original) is missing too may have moved while the walk read the other end. It is looked at again, then its
	// counterpart, then itself; only a file found at neither is removed. A file under a folder that could not be read,
	// or whose counterpart cannot be read, is kept as it is.
	look := newSecondLook(root)
	keep := map[string]bool{}
	add := func(s seen) {
		if present[s.rel] {
			return
		}
		present[s.rel] = true
		files = append(files, s)
		if s.origin != "" && trashOf[s.origin] < s.rel {
			trashOf[s.origin] = s.rel
		}
	}
	for rel, k := range known {
		if k.Removed || present[rel] {
			continue
		}
		if under(rel, unreadable) {
			keep[rel] = true
			continue
		}
		if k.Origin != "" { // a trash row: restored, purged or moving
			if present[k.Origin] {
				continue
			}
			if s, ok, kn := lookAgain(root, rel); ok || !kn {
				if ok {
					add(s)
				} else {
					keep[rel] = true
				}
				continue
			}
			if s, ok, kn := lookAgain(root, k.Origin); ok || !kn {
				if ok {
					add(s)
				} else {
					keep[rel] = true
				}
				continue
			}
			if s, ok, kn := lookAgain(root, rel); ok || !kn {
				if ok {
					add(s)
				} else {
					keep[rel] = true
				}
			}
			continue
		}
		if trashOf[rel] != "" { // moved to a trash
			continue
		}
		if s, ok, kn := lookAgain(root, rel); ok || !kn {
			if ok {
				add(s)
			} else {
				keep[rel] = true
			}
			continue
		}
		found, kn := look.inTrashNow(rel)
		for _, s := range found {
			add(s)
		}
		if len(found) > 0 {
			continue
		}
		if !kn {
			keep[rel] = true
			continue
		}
		if s, ok, kn := lookAgain(root, rel); ok || !kn {
			if ok {
				add(s)
			} else {
				keep[rel] = true
			}
		}
	}
	stats.Files = len(files)

	haveMeta, err := ix.Store.MetaFingerprints(ctx, media.MetaVersion)
	if err != nil {
		return fail("error", err)
	}
	// A file that moved into or out of a trash keeps its fingerprint when its size and mtime are its counterpart's (a
	// rename keeps both, ADR 0023): no re-hash for trash moves and restores.
	trashRows := map[string][]NasState{}
	for rel, k := range known {
		if k.Origin != "" {
			trashRows[k.Origin] = append(trashRows[k.Origin], NasState{rel, k})
		}
	}
	same := func(s seen, k store.NasFileState) bool {
		return k.Fingerprint != "" && k.Size == s.size && k.MTime == s.mtime && k.Media == s.media
	}
	carried := func(s seen) string {
		if s.origin != "" {
			if k, ok := known[s.origin]; ok && same(s, k) {
				return k.Fingerprint
			}
			return ""
		}
		if k, ok := known[s.rel]; ok && k.Removed && k.TrashPath != "" && same(s, k) {
			return k.Fingerprint
		}
		for _, t := range trashRows[s.rel] {
			if same(s, t.State) {
				return t.State.Fingerprint
			}
		}
		return ""
	}
	var todo []seen
	var updates []store.NasFileUpdate
	for rel, k := range known {
		if !k.Removed && !present[rel] && !keep[rel] {
			updates = append(updates, store.NasFileUpdate{RelPath: rel, Removed: true, Media: k.Media, TrashPath: trashOf[rel]})
		}
	}
	stats.Removed = len(updates)
	for _, s := range files {
		k, ok := known[s.rel]
		if ok && !k.Removed && k.Size == s.size && k.MTime == s.mtime && k.Fingerprint != "" && k.Media == s.media {
			if !haveMeta[k.Fingerprint] { // unchanged file without metadata (a data directory from before items, new rules)
				s.fp = k.Fingerprint
				todo = append(todo, s)
			}
			continue
		}
		if fp := carried(s); fp != "" {
			updates = append(updates, store.NasFileUpdate{RelPath: s.rel, Size: s.size, MTime: s.mtime, Fingerprint: fp, Media: s.media, Origin: s.origin})
			if !haveMeta[fp] {
				s.fp = fp
				todo = append(todo, s)
			}
			continue
		}
		s.hash = true
		todo = append(todo, s)
	}
	if err := ix.Store.ApplyNasUpdates(ctx, f.ID, updates); err != nil {
		return fail("error", err)
	}

	workers := ix.Workers
	if workers <= 0 {
		workers = 2
	}
	jobs := make(chan seen)
	results := make(chan result)
	var wg sync.WaitGroup
	var metaMu sync.Mutex
	metaDone := map[string]bool{} // fingerprints read in this walk (copies of one file are read once)
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for s := range jobs {
				results <- ix.process(ctx, root, s, func(fp string) bool {
					metaMu.Lock()
					defer metaMu.Unlock()
					if haveMeta[fp] || metaDone[fp] {
						return false
					}
					metaDone[fp] = true
					return true
				})
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, s := range todo {
			select {
			case jobs <- s:
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() { wg.Wait(); close(results) }()
	batch := make([]store.NasFileUpdate, 0, 200)
	metas := map[string][]byte{}
	var storeErr error
	flush := func() {
		if err := ix.Store.ApplyNasUpdates(ctx, f.ID, batch); err != nil && storeErr == nil {
			storeErr = err
		}
		if err := ix.Store.PutFileMetas(ctx, media.MetaVersion, metas); err != nil && storeErr == nil {
			storeErr = err
		}
		batch = batch[:0]
		metas = map[string][]byte{}
	}
	for r := range results {
		if r.update != nil {
			batch = append(batch, *r.update)
			stats.Hashed++
		}
		if r.meta != nil {
			metas[r.fp] = r.meta
			stats.MetaRead++
		}
		stats.Probed += r.probed
		if len(batch) == cap(batch) || len(metas) >= 200 {
			flush()
		}
	}
	flush()
	if storeErr != nil {
		return fail("error", storeErr)
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	// Items: group what the folder holds now and sync the groups that changed.
	states, err := ix.Store.NasFileStates(ctx, f.ID)
	if err != nil {
		return fail("error", err)
	}
	// A removed original is "trashed" in the NAS feed while a trash row of it is there (ADR 0023): set when it moved to
	// a trash, cleared when that trash row is purged.
	inTrashNow := map[string]string{}
	for rel, k := range states {
		if k.Origin != "" && !k.Removed && inTrashNow[k.Origin] < rel {
			inTrashNow[k.Origin] = rel
		}
	}
	var marks []store.TrashMark
	for rel, k := range states {
		if k.Removed && k.Origin == "" && k.TrashPath != inTrashNow[rel] {
			marks = append(marks, store.TrashMark{RelPath: rel, TrashPath: inTrashNow[rel]})
		}
	}
	if err := ix.Store.MarkTrashed(ctx, f.ID, marks); err != nil {
		return fail("error", err)
	}
	current := make([]items.Seen, 0, len(states))
	for rel, k := range states {
		if !k.Removed {
			is := items.Seen{Path: rel, Size: k.Size, MTime: k.MTime, Fingerprint: k.Fingerprint}
			if k.Origin != "" {
				is.Origin = k.Origin
				is.TrashDir = trashDayDir(rel)
			}
			current = append(current, is)
		}
	}
	groups := items.GroupFolder(current)
	ist, err := ix.syncer(ctx).SyncFolder(ctx, f.ID, groups)
	if err != nil {
		return fail("error", err)
	}
	stats.Sources, stats.Items = ist.Sources, ist.Items
	// Culling from the folder's sidecars (ADR 0016): read only, and only the sidecars that changed. Sidecars in a
	// trash are never read; one keeps its record under its original path while it is there (ADR 0023).
	live := make([]items.Group, 0, len(groups))
	for _, g := range groups {
		if !g.Trash() {
			live = append(live, g)
		}
	}
	kept := make(map[string]bool, len(present)+len(inTrashNow))
	for p := range present {
		kept[p] = true
	}
	for p := range keep {
		kept[p] = true
	}
	for origin := range inTrashNow {
		kept[origin] = true
	}
	if stats.Culling, err = ix.importSidecars(ctx, root, f.ID, live, kept, complete); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return fail("error", err)
	}
	stats.DurationMs = time.Since(start).Milliseconds()
	ix.mu.Lock()
	ix.last = stats
	ix.mu.Unlock()
	ix.Log.Info("nas: folder indexed", "folder", f.ID, "files", stats.Files, "hashed", stats.Hashed, "metadata", stats.MetaRead,
		"probed", stats.Probed, "removed", stats.Removed, "itemSources", stats.Sources, "items", stats.Items, "culling", stats.Culling, "ms", stats.DurationMs)
	return ix.Store.SetNasFolderState(ctx, f.ID, "ready", nil, true)
}

// process fingerprints a new or changed file (at most 2 MiB read) and reads its metadata when needMeta says no other
// copy did. A file that changed while it was read (size or mtime) gets no fingerprint now and is read again on the
// next walk.
func (ix *Indexer) process(ctx context.Context, root *os.Root, s seen, needMeta func(string) bool) result {
	var r result
	file, err := root.Open(s.rel)
	if err != nil {
		if s.hash {
			r.update = &store.NasFileUpdate{RelPath: s.rel, Size: s.size, MTime: s.mtime, Media: s.media, Origin: s.origin}
		}
		return r
	}
	defer file.Close()
	fp := s.fp
	if s.hash {
		fp, err = fingerprint.Of(file, s.size)
		if err != nil {
			fp = ""
		}
	}
	if fp != "" && needMeta(fp) {
		r.meta, r.probed = ix.readMeta(ctx, file, s.size, s.media, path.Base(s.rel))
	}
	if fi, err := file.Stat(); err != nil || fi.Size() != s.size || fi.ModTime().UnixNano() != s.mtime {
		fp, r.meta = "", nil
	}
	r.fp = fp
	if s.hash {
		r.update = &store.NasFileUpdate{RelPath: s.rel, Size: s.size, MTime: s.mtime, Fingerprint: fp, Media: s.media, Origin: s.origin}
	}
	return r
}

// readMeta reads a file's metadata (ADR 0014 "Metadata"). It returns nil when the metadata must be read again later
// (ffprobe missing or the walk cancelled), never for a file that is merely unreadable or unknown (that is recorded,
// so it is not retried every walk).
func (ix *Indexer) readMeta(ctx context.Context, f *os.File, size int64, mediaClass, name string) ([]byte, int) {
	var m media.Meta
	probed := 0
	switch mediaClass {
	case media.MediaSidecar:
		m = media.Meta{Media: media.MediaSidecar, Format: "xmp"}
	case media.MediaImage:
		var err error
		m, err = media.ReadImage(f, size)
		if err != nil {
			m = media.Meta{Media: media.MediaImage, Error: "unreadable"}
			if errors.Is(err, media.ErrUnsupported) {
				m.Error = "unsupported"
			}
		}
		if m.Motion != nil {
			probed++
			v, _, err := ix.probe(ctx, f, m.Motion.Offset, m.Motion.Length)
			if errors.Is(err, media.ErrNoProber) || ctx.Err() != nil {
				return nil, probed
			}
			m.Motion.Video = v
		}
	case media.MediaVideo:
		probed++
		v, tags, err := ix.probe(ctx, f, 0, 0)
		switch {
		case errors.Is(err, media.ErrNoProber) || ctx.Err() != nil:
			return nil, probed
		case errors.Is(err, media.ErrProbeTimeout):
			m = media.Meta{Media: media.MediaVideo, Error: "probe_timeout"}
		case err != nil:
			m = media.Meta{Media: media.MediaVideo, Error: "probe_failed"}
		default:
			m = media.VideoMeta(v, tags, media.VideoFormat(v.Container, media.Ext(name)))
		}
	}
	b, err := json.Marshal(m)
	if err != nil {
		return nil, probed
	}
	return b, probed
}

func (ix *Indexer) probe(ctx context.Context, f *os.File, off, n int64) (*media.Video, map[string]string, error) {
	v, tags, err := ix.Prober.Probe(ctx, f, off, n)
	if errors.Is(err, media.ErrNoProber) {
		ix.mu.Lock()
		if !ix.warned {
			ix.warned = true
			ix.Log.Warn("nas: ffprobe not found: videos are indexed without their metadata until it is installed")
		}
		ix.mu.Unlock()
	}
	return v, tags, err
}

// Backup turns uploaded originals into items (ADR 0014 "Uploads"): it reads the metadata of originals that have none
// (from the blob) and syncs the backup sources.
func (ix *Indexer) Backup(ctx context.Context) {
	if ix.OpenBlob == nil {
		return
	}
	originals, err := ix.Store.UploadedOriginals(ctx)
	if err != nil {
		ix.Log.Error("items: uploaded originals", "err", err)
		return
	}
	have, err := ix.Store.MetaFingerprints(ctx, media.MetaVersion)
	if err != nil {
		ix.Log.Error("items: metadata", "err", err)
		return
	}
	metas := map[string][]byte{}
	for _, o := range originals {
		if have[o.Fingerprint] || ctx.Err() != nil {
			continue
		}
		f, err := ix.OpenBlob(o.SHA256)
		if err != nil {
			continue
		}
		mediaClass, _, format := media.Sniff(f)
		if mediaClass != "" {
			if b, _ := ix.readMeta(ctx, f, o.Size, mediaClass, "x."+format); b != nil {
				metas[o.Fingerprint] = b
			}
		} else {
			metas[o.Fingerprint], _ = json.Marshal(media.Meta{Error: "unsupported"})
		}
		f.Close()
	}
	if err := ix.Store.PutFileMetas(ctx, media.MetaVersion, metas); err != nil {
		ix.Log.Error("items: store metadata", "err", err)
		return
	}
	roles := map[string]string{}
	fps := make([]string, 0, len(originals))
	for _, o := range originals {
		fps = append(fps, o.Fingerprint)
	}
	stored, err := ix.Store.FileMetas(ctx, fps)
	if err != nil {
		ix.Log.Error("items: metadata", "err", err)
		return
	}
	for fp, b := range stored {
		var m media.Meta
		if json.Unmarshal(b, &m) != nil {
			continue
		}
		switch {
		case m.Media == media.MediaVideo:
			roles[fp] = media.RoleVideo
		case m.Media == media.MediaImage && (m.Format == "tiff" || m.Format == "cr3" || m.Format == "raf"):
			roles[fp] = media.RoleRaw
		case m.Media == media.MediaImage:
			roles[fp] = media.RoleRendered
		}
	}
	// Phone backups (ADR 0024) first: an upload then joins the mirror item that holds its file.
	if mst, err := ix.syncer(ctx).SyncMirrors(ctx); err != nil {
		ix.Log.Error("items: phone backups", "err", err)
	} else if mst.Sources > 0 {
		ix.Log.Info("items: phone backups synced", "sources", mst.Sources, "items", mst.Items)
	}
	st, err := ix.syncer(ctx).SyncBackup(ctx, originals, roles)
	if err != nil {
		ix.Log.Error("items: uploaded originals", "err", err)
		return
	}
	if st.Sources > 0 {
		ix.Log.Info("items: uploaded originals synced", "sources", st.Sources, "items", st.Items)
	}
	if ix.AfterBackup != nil && ctx.Err() == nil {
		ix.AfterBackup(ctx)
	}
}
