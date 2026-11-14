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

	trigger chan struct{}
	backup  chan struct{}
	once    sync.Once
	mu      sync.Mutex
	running bool
	last    Stats
	warned  bool
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
	})
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
	case errors.Is(err, context.Canceled):
		return "cancelled"
	default:
		return "io error"
	}
}

type seen struct {
	rel   string
	size  int64
	mtime int64
	media string
	hash  bool   // compute the fingerprint
	fp    string // known fingerprint (meta only)
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
	stats := Stats{}

	var files []seen
	walkErr := fs.WalkDir(root.FS(), ".", func(p string, d fs.DirEntry, err error) error {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		if err != nil {
			if p == "." {
				return err
			}
			return fs.SkipDir // an unreadable sub-folder is skipped, never the reason to drop files
		}
		if p != "." && strings.HasPrefix(d.Name(), ".") {
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
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
			return nil
		}
		files = append(files, seen{rel: p, size: info.Size(), mtime: info.ModTime().UnixNano(), media: mediaClass})
		return nil
	})
	if walkErr != nil {
		return fail("error", walkErr) // nothing is removed when the walk itself fails
	}
	stats.Files = len(files)

	known, err := ix.Store.NasFileStates(ctx, f.ID)
	if err != nil {
		return fail("error", err)
	}
	haveMeta, err := ix.Store.MetaFingerprints(ctx, media.MetaVersion)
	if err != nil {
		return fail("error", err)
	}
	var todo []seen
	present := make(map[string]bool, len(files))
	for _, s := range files {
		present[s.rel] = true
		k, ok := known[s.rel]
		if ok && !k.Removed && k.Size == s.size && k.MTime == s.mtime && k.Fingerprint != "" && k.Media == s.media {
			if !haveMeta[k.Fingerprint] { // unchanged file without metadata (a data directory from before items, new rules)
				s.fp = k.Fingerprint
				todo = append(todo, s)
			}
			continue
		}
		s.hash = true
		todo = append(todo, s)
	}
	var updates []store.NasFileUpdate
	for rel, k := range known {
		if !k.Removed && !present[rel] {
			updates = append(updates, store.NasFileUpdate{RelPath: rel, Removed: true, Media: k.Media})
		}
	}
	stats.Removed = len(updates)
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
	current := make([]items.Seen, 0, len(states))
	for rel, k := range states {
		if !k.Removed {
			current = append(current, items.Seen{Path: rel, Size: k.Size, MTime: k.MTime, Fingerprint: k.Fingerprint})
		}
	}
	groups := items.GroupFolder(current)
	ist, err := ix.syncer(ctx).SyncFolder(ctx, f.ID, groups)
	if err != nil {
		return fail("error", err)
	}
	stats.Sources, stats.Items = ist.Sources, ist.Items
	// Culling from the folder's sidecars (ADR 0016): read only, and only the sidecars that changed.
	if stats.Culling, err = ix.importSidecars(ctx, root, f.ID, groups); err != nil {
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
			r.update = &store.NasFileUpdate{RelPath: s.rel, Size: s.size, MTime: s.mtime, Media: s.media}
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
		r.update = &store.NasFileUpdate{RelPath: s.rel, Size: s.size, MTime: s.mtime, Fingerprint: fp, Media: s.media}
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
	st, err := ix.syncer(ctx).SyncBackup(ctx, originals, roles)
	if err != nil {
		ix.Log.Error("items: uploaded originals", "err", err)
		return
	}
	if st.Sources > 0 {
		ix.Log.Info("items: uploaded originals synced", "sources", st.Sources, "items", st.Items)
	}
}
