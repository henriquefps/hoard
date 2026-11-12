// Package nas indexes the read-only NAS folders chosen on the setup page: it walks them, keeps RAW, JPEG and HEIF
// files, and computes each new or changed file's v1 fingerprint, so the server can answer "do you have X?" and serve
// the file by fingerprint. It never writes to a folder.
package nas

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
	"github.com/henriquefps/reveal/sync-server/internal/mounts"
	"github.com/henriquefps/reveal/sync-server/internal/store"
)

// extensions are the image files Reveal opens (ImageFormats in :core:image): RAW, JPEG, HEIF.
var extensions = map[string]bool{}

func init() {
	for _, e := range []string{"arw", "srf", "sr2", "cr2", "cr3", "nef", "nrw", "raf", "dng", "orf", "rw2", "pef", "srw",
		"jpg", "jpeg", "jpe", "jfif", "heic", "heif", "hif"} {
		extensions[e] = true
	}
}

// Supported reports whether a file name is an image the server indexes.
func Supported(name string) bool {
	base := path.Base(name)
	if strings.HasPrefix(base, ".") {
		return false
	}
	dot := strings.LastIndexByte(base, '.')
	return dot > 0 && extensions[strings.ToLower(base[dot+1:])]
}

// Indexer walks the enabled folders on demand and periodically.
type Indexer struct {
	Store    *store.Store
	Roots    mounts.Roots
	Log      *slog.Logger
	Interval time.Duration
	Workers  int

	trigger chan struct{}
	once    sync.Once
	mu      sync.Mutex
	running bool
}

func (ix *Indexer) init() {
	ix.once.Do(func() { ix.trigger = make(chan struct{}, 1) })
}

// Trigger asks for a walk soon (coalesced).
func (ix *Indexer) Trigger() {
	ix.init()
	select {
	case ix.trigger <- struct{}{}:
	default:
	}
}

// Running reports whether a walk is in progress.
func (ix *Indexer) Running() bool {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	return ix.running
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
		}
		ix.All(ctx)
	}
}

// All indexes every enabled folder once.
func (ix *Indexer) All(ctx context.Context) {
	ix.mu.Lock()
	ix.running = true
	ix.mu.Unlock()
	defer func() {
		ix.mu.Lock()
		ix.running = false
		ix.mu.Unlock()
	}()
	folders, err := ix.Store.NasFolders(ctx)
	if err != nil {
		ix.Log.Error("nas: list folders", "err", err)
		return
	}
	for _, f := range folders {
		if !f.Enabled || ctx.Err() != nil {
			continue
		}
		if err := ix.Folder(ctx, f); err != nil && ctx.Err() == nil {
			ix.Log.Warn("nas: folder not indexed", "folder", f.ID, "err", errorClass(err))
		}
	}
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
}

// Folder walks one folder and stores the differences.
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
		if !d.Type().IsRegular() || !Supported(d.Name()) { // symbolic links and devices are never followed
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		files = append(files, seen{p, info.Size(), info.ModTime().UnixNano()})
		return nil
	})
	if walkErr != nil {
		return fail("error", walkErr) // nothing is removed when the walk itself fails
	}

	known, err := ix.Store.NasFileStates(ctx, f.ID)
	if err != nil {
		return fail("error", err)
	}
	var todo []seen
	present := make(map[string]bool, len(files))
	for _, s := range files {
		present[s.rel] = true
		k, ok := known[s.rel]
		if ok && !k.Removed && k.Size == s.size && k.MTime == s.mtime && k.Fingerprint != "" {
			continue
		}
		todo = append(todo, s)
	}
	var updates []store.NasFileUpdate
	for rel, k := range known {
		if !k.Removed && !present[rel] {
			updates = append(updates, store.NasFileUpdate{RelPath: rel, Removed: true})
		}
	}
	if err := ix.Store.ApplyNasUpdates(ctx, f.ID, updates); err != nil {
		return fail("error", err)
	}

	workers := ix.Workers
	if workers <= 0 {
		workers = 2
	}
	jobs := make(chan seen)
	results := make(chan store.NasFileUpdate)
	var wg sync.WaitGroup
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for s := range jobs {
				results <- store.NasFileUpdate{RelPath: s.rel, Size: s.size, MTime: s.mtime, Fingerprint: fingerprintOf(root, s)}
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
	var storeErr error
	for u := range results {
		batch = append(batch, u)
		if len(batch) == cap(batch) {
			if err := ix.Store.ApplyNasUpdates(ctx, f.ID, batch); err != nil && storeErr == nil {
				storeErr = err
			}
			batch = batch[:0]
		}
	}
	if err := ix.Store.ApplyNasUpdates(ctx, f.ID, batch); err != nil && storeErr == nil {
		storeErr = err
	}
	if storeErr != nil {
		return fail("error", storeErr)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	ix.Log.Info("nas: folder indexed", "folder", f.ID, "files", len(files), "hashed", len(todo), "removed", len(updates),
		"ms", time.Since(start).Milliseconds())
	return ix.Store.SetNasFolderState(ctx, f.ID, "ready", nil, true)
}

// fingerprintOf reads at most 2 MiB; a file that changed while it was read (size or mtime) gets no fingerprint now
// and is read again on the next walk.
func fingerprintOf(root *os.Root, s seen) string {
	file, err := root.Open(s.rel)
	if err != nil {
		return ""
	}
	defer file.Close()
	fp, err := fingerprint.Of(file, s.size)
	if err != nil {
		return ""
	}
	if fi, err := file.Stat(); err != nil || fi.Size() != s.size || fi.ModTime().UnixNano() != s.mtime {
		return ""
	}
	return fp
}
