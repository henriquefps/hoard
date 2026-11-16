package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/henriquefps/hoard/server/internal/blobstore"
	"github.com/henriquefps/hoard/server/internal/mirror"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Phone backup (ADR 0024, hoard-server 0.12.0): the mirror tree follows the database. Reports, completed uploads,
// NAS copies and moves change entries in the database first; then reconcile puts each entry's hard link where it
// belongs (or nowhere), and the roots' manifests are written again in the background. A bounded queue (one job at a
// time) re-hashes stored copies for free up and copies NAS files into the store.

const (
	settingKeepNasCopies = "backupKeepNasCopies" // "Keep a copy of NAS files a phone no longer has" (on unless "false")

	backupMaxRoots      = 64
	backupMaxReport     = 1000
	backupMaxVerify     = 500
	uploadLiveWindow    = 10 * time.Minute    // an upload with a chunk this recent is "in progress"
	uploadRetryAfter    = 300                 // seconds
	verifyRetryAfter    = 30                  // seconds
	verifyValidFor      = 30 * 24 * time.Hour // a re-hash is good for this long
	rootActiveWindow    = 7 * 24 * time.Hour  // a root is active while a live device reported within it
	moveWindow          = 24 * time.Hour      // a reported move waits this long for its new path
	backupFailureMemory = 10 * time.Minute    // a failed job's reason is answered for this long
)

var backupTypes = map[string]bool{"image": true, "video": true, "raw": true, "sidecar": true}

// backupState is the phone backup's runtime: the tree (with the blob store's backup root), the job queue and the
// manifests to write.
type backupState struct {
	fsMu sync.Mutex // tree writes

	// Linker replaces the hard-link call (tests inject EXDEV); nil = mirror.DefaultLinker.
	linker mirror.Linker

	mu       sync.Mutex
	queue    []backupJob
	queued   map[string]bool
	failures map[string]backupFailure
	dirty    map[string]bool // root ids whose manifest is to be written
	gone     map[string]string
	wake     chan struct{}
	done     int
}

type backupFailure struct {
	reason string
	at     time.Time
}

// backupJob is one unit of background work: "hash:<sha256>" (re-hash a blob), "nas:<fingerprint>" (hash a NAS file),
// "copy:<entry id>" (copy a NAS file into the store).
type backupJob struct {
	key string
}

func (b *backupState) init() {
	if b.queued == nil {
		b.queued = map[string]bool{}
		b.failures = map[string]backupFailure{}
		b.dirty = map[string]bool{}
		b.gone = map[string]string{}
		b.wake = make(chan struct{}, 1)
	}
}

func (s *Server) backupInit() {
	s.backup.mu.Lock()
	s.backup.init()
	s.backup.mu.Unlock()
}

// enqueue adds a job unless it is queued already; it reports whether it is queued now.
func (s *Server) enqueue(key string) {
	b := &s.backup
	b.mu.Lock()
	b.init()
	if !b.queued[key] {
		b.queued[key] = true
		b.queue = append(b.queue, backupJob{key: key})
	}
	b.mu.Unlock()
	s.wakeBackup()
}

func (s *Server) wakeBackup() {
	select {
	case s.backup.wake <- struct{}{}:
	default:
	}
}

// pendingJob reports whether a job is queued or running.
func (s *Server) pendingJob(key string) bool {
	b := &s.backup
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.queued[key]
}

// takeFailure returns (and forgets) the reason a job failed recently.
func (s *Server) takeFailure(key string) (string, bool) {
	b := &s.backup
	b.mu.Lock()
	defer b.mu.Unlock()
	f, ok := b.failures[key]
	if !ok {
		return "", false
	}
	delete(b.failures, key)
	if time.Since(f.at) > backupFailureMemory {
		return "", false
	}
	return f.reason, true
}

func (s *Server) markDirty(rootIDs ...string) {
	b := &s.backup
	b.mu.Lock()
	b.init()
	for _, id := range rootIDs {
		b.dirty[id] = true
	}
	b.mu.Unlock()
	s.wakeBackup()
}

// markGone schedules a manifest's removal (a root absorbed or merged), in the folder it was in.
func (s *Server) markGone(dir string, rootIDs ...string) {
	b := &s.backup
	b.mu.Lock()
	b.init()
	for _, id := range rootIDs {
		b.gone[id] = dir
	}
	b.mu.Unlock()
	s.wakeBackup()
}

// runBackup works the queue until ctx ends (the server's Run).
func (s *Server) runBackup(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		s.runBackupJobs(ctx)
		select {
		case <-ctx.Done():
			return
		case <-s.backup.wake:
		case <-t.C:
		}
	}
}

// runBackupJobs runs every queued job, one at a time, then writes the manifests that changed. Tests call it to drain
// the queue.
func (s *Server) runBackupJobs(ctx context.Context) {
	for ctx.Err() == nil {
		b := &s.backup
		b.mu.Lock()
		if len(b.queue) == 0 {
			b.mu.Unlock()
			break
		}
		job := b.queue[0]
		b.queue = b.queue[1:]
		b.mu.Unlock()
		reason := s.runJob(ctx, job.key)
		b.mu.Lock()
		delete(b.queued, job.key)
		if reason != "" {
			b.failures[job.key] = backupFailure{reason: reason, at: time.Now()}
		}
		b.done++
		b.mu.Unlock()
	}
	s.writeManifests(ctx)
}

func (s *Server) runJob(ctx context.Context, key string) string {
	switch {
	case len(key) > 5 && key[:5] == "hash:":
		return s.rehashBlob(ctx, key[5:])
	case len(key) > 4 && key[:4] == "nas:":
		return s.hashNasFile(ctx, key[4:])
	case len(key) > 5 && key[:5] == "copy:":
		id, err := strconv.ParseInt(key[5:], 10, 64)
		if err != nil {
			return ""
		}
		return s.copyNasEntry(ctx, id)
	}
	return ""
}

// rehashBlob reads a stored blob and records what its bytes hash to ("" when it cannot be read).
func (s *Server) rehashBlob(ctx context.Context, sha string) string {
	bs := s.blobStore()
	if bs == nil {
		return "unavailable"
	}
	got := ""
	if f, err := bs.Open(sha); err == nil {
		h := sha256.New()
		if _, err := io.Copy(h, f); err == nil {
			got = hex.EncodeToString(h.Sum(nil))
		}
		f.Close()
	}
	if err := s.store.SetBlobVerification(ctx, sha, got); err != nil {
		s.log.Error("backup: verification", "err", err)
		return "unavailable"
	}
	return ""
}

// hashNasFile hashes the NAS file a reference reads, only while it is still what the index says.
func (s *Server) hashNasFile(ctx context.Context, fp string) string {
	nf, err := s.store.NasFileForVerify(ctx, fp)
	if errors.Is(err, store.ErrNotFound) {
		return "missing"
	}
	if err != nil {
		return "unavailable"
	}
	f, err := s.openNasFile(nf.Original)
	if err != nil {
		s.indexer.Trigger()
		if _, statErr := s.roots.Resolve(nf.FolderPath); statErr != nil {
			return "unavailable"
		}
		return "changed_on_nas"
	}
	h := sha256.New()
	_, err = io.Copy(h, f)
	fi, statErr := f.Stat()
	f.Close()
	if err != nil {
		return "unavailable"
	}
	if statErr != nil || fi.Size() != nf.Size || fi.ModTime().UnixNano() != nf.ModTime {
		s.indexer.Trigger()
		return "changed_on_nas"
	}
	if err := s.store.SetNasSHA(ctx, nf.FileID, nf.ModTime, fp, hex.EncodeToString(h.Sum(nil))); err != nil {
		return "unavailable"
	}
	return ""
}

// errNotPhoneBytes: the NAS file's bytes are not the phone's (another SHA-256 than the one the phone reported).
var errNotPhoneBytes = errors.New("differs")

// copyNasEntry copies the NAS file a reference reads into the store (server-side, disk to disk), checks it, and links
// every entry of that content: "Keep a copy of NAS files a phone no longer has" (ADR 0024).
func (s *Server) copyNasEntry(ctx context.Context, id int64) string {
	es, err := s.store.MirrorEntries(ctx, []int64{id})
	if err != nil || len(es) == 0 {
		return ""
	}
	e := es[0]
	if e.Store != store.StoreNAS {
		return ""
	}
	bs := s.blobStore()
	if bs == nil {
		return "unavailable"
	}
	nf, err := s.store.NasFileForVerify(ctx, e.Fingerprint)
	if errors.Is(err, store.ErrNotFound) {
		return "missing"
	}
	if err != nil {
		return "unavailable"
	}
	f, err := s.openNasFile(nf.Original)
	if err != nil {
		s.indexer.Trigger()
		return "changed_on_nas"
	}
	sha, err := bs.Ingest(f, nf.Size, e.Fingerprint, func(sha string) error {
		if e.SHA256 != "" && e.SHA256 != sha {
			return errNotPhoneBytes
		}
		return nil
	})
	f.Close()
	switch {
	case errors.Is(err, errNotPhoneBytes):
		_ = s.store.SetEntryVerified(ctx, e.ID, "nas-differs")
		return "differs"
	case errors.Is(err, blobstore.ErrFingerprint), errors.Is(err, blobstore.ErrChecksum):
		s.indexer.Trigger()
		return "changed_on_nas"
	case err != nil:
		s.log.Warn("backup: NAS copy failed", "err", errorClassOf(err))
		return "unavailable"
	}
	if err := s.store.AddBlobClaim(ctx, store.Blob{SHA256: sha, Size: nf.Size, Kind: store.BlobOriginal}, e.Fingerprint, "", false); err != nil {
		s.log.Error("backup: NAS copy", "err", err)
		return "unavailable"
	}
	// Complete read the stored bytes again: that is the server's own re-hash of its copy.
	if err := s.store.SetBlobVerification(ctx, sha, sha); err != nil {
		return "unavailable"
	}
	s.linkContent(ctx, e.Fingerprint, sha)
	return ""
}

func errorClassOf(err error) string {
	switch {
	case errors.Is(err, os.ErrPermission):
		return "permission denied"
	case errors.Is(err, os.ErrNotExist):
		return "not found"
	default:
		return "io error"
	}
}

// linkContent links the entries waiting for (or referencing on the NAS) content now in the store.
func (s *Server) linkContent(ctx context.Context, fp, sha string) {
	ids, err := s.store.LinkWaiting(ctx, fp, sha)
	if err != nil {
		s.log.Error("backup: link waiting entries", "err", err)
		return
	}
	if len(ids) == 0 {
		return
	}
	s.reconcile(ctx, ids)
	s.indexer.TriggerBackup()
}

// keepNasCopies reads the server setting "Keep a copy of NAS files a phone no longer has" (on by default).
func (s *Server) keepNasCopies(ctx context.Context) bool {
	v, ok, err := s.store.Setting(ctx, settingKeepNasCopies)
	return err != nil || !ok || v != "false"
}

// desiredLink is where an entry's hard link belongs in its device folder ("" = none): its path while it holds it
// (current, deleted, freed), .hoard-mirror/previous/… for a rewritten file's earlier content, nowhere after a move or
// when its bytes are not in the store.
func desiredLink(e store.MirrorEntry) string {
	if e.Store != store.StoreBlob || !blobstore.ValidSHA256(e.SHA256) {
		return ""
	}
	switch e.RemovedReason {
	case "", store.RemovedDeleted, store.RemovedFreed:
		return mirror.TreePath(e.Volume, e.Path)
	case store.RemovedReplaced:
		return mirror.PreviousPath(e.Volume, e.Path, e.SHA256)
	}
	return ""
}

// reconcile puts the links of the given entries where they belong: first every link that must leave its place, then
// every link that is missing, so a rewritten file's previous content moves out before the new content takes its path.
// A link that cannot be made (EXDEV, EPERM, a file in the way) leaves the entry index-only; the server never copies.
func (s *Server) reconcile(ctx context.Context, ids []int64) {
	t := s.mirrorTree()
	if t == nil || len(ids) == 0 {
		return
	}
	es, err := s.store.MirrorEntries(ctx, ids)
	if err != nil {
		s.log.Error("backup: reconcile", "err", err)
		return
	}
	s.backup.fsMu.Lock()
	defer s.backup.fsMu.Unlock()
	roots := map[string]bool{}
	for i := range es {
		e := &es[i]
		roots[e.RootID] = true
		want := desiredLink(*e)
		if e.TreePath != "" && (e.TreePath != want || e.DirName == "") {
			if err := t.Remove(e.DirName, e.TreePath, e.SHA256); err != nil {
				s.log.Warn("backup: a link stays", "err", errorClassOf(err))
			}
			if err := s.store.SetMirrorLink(ctx, e.ID, "", false); err != nil {
				s.log.Error("backup: reconcile", "err", err)
				return
			}
			e.TreePath, e.Linked = "", false
		}
	}
	failed := 0
	for i := range es {
		e := &es[i]
		want := desiredLink(*e)
		if want == "" || e.DirName == "" || (e.TreePath == want && e.Linked) {
			continue
		}
		if err := t.Place(e.DirName, want, e.SHA256); err != nil {
			failed++
			if e.Linked || e.TreePath != "" {
				_ = s.store.SetMirrorLink(ctx, e.ID, "", false)
			}
			continue
		}
		if err := s.store.SetMirrorLink(ctx, e.ID, want, true); err != nil {
			s.log.Error("backup: reconcile", "err", err)
			return
		}
	}
	if failed > 0 {
		s.log.Warn("backup: files kept index-only (no hard link possible)", "count", failed)
	}
	ids2 := make([]string, 0, len(roots))
	for id := range roots {
		ids2 = append(ids2, id)
	}
	s.markDirty(ids2...)
}

// writeManifests writes the manifests of the roots that changed (and removes those of roots that are gone).
func (s *Server) writeManifests(ctx context.Context) {
	b := &s.backup
	b.mu.Lock()
	dirty := make([]string, 0, len(b.dirty))
	for id := range b.dirty {
		dirty = append(dirty, id)
	}
	gone := b.gone
	b.dirty = map[string]bool{}
	b.gone = map[string]string{}
	b.mu.Unlock()
	t := s.mirrorTree()
	if t == nil {
		return
	}
	sort.Strings(dirty)
	s.backup.fsMu.Lock()
	defer s.backup.fsMu.Unlock()
	for id, dir := range gone {
		_ = t.RemoveFile(dir, mirror.ManifestPath(id))
	}
	for _, id := range dirty {
		root, err := s.store.MirrorRoot(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			continue
		}
		if err != nil {
			s.log.Error("backup: manifest", "err", err)
			continue
		}
		rows, err := s.store.MirrorManifest(ctx, id)
		if err != nil {
			s.log.Error("backup: manifest", "err", err)
			continue
		}
		out := make([]mirror.ManifestRow, 0, len(rows))
		for _, r := range rows {
			where := r.Store
			switch {
			case r.Store == store.StoreBlob && r.RemovedReason == store.RemovedReplaced && r.Linked:
				where = "previous:" + r.TreePath
			case r.Store == store.StoreBlob && !r.Linked:
				where = "blob-unlinked"
			case r.Store == store.StoreNAS && r.NasPath != "":
				where = "nas:" + r.NasFolder + "/" + r.NasPath
			}
			out = append(out, mirror.ManifestRow{Path: r.Path, Size: r.Size, SHA256: r.SHA256, Fingerprint: r.Fingerprint, Where: where,
				OnDevice: r.OnDevice, Removed: r.RemovedReason})
		}
		if err := t.WriteManifest(root.DirName, mirror.ManifestPath(id), mirror.Manifest(root.Volume, root.Path, out)); err != nil {
			s.log.Warn("backup: manifest not written", "err", errorClassOf(err))
		}
	}
}

// checkNasReferences runs after each NAS walk (ADR 0024 "With ADR 0023's trash"): NAS references whose file went turn
// "none" (the admin page warns when that was the only copy), and NAS references whose phone file is gone are copied
// into the store (from a trash too) while the setting is on.
func (s *Server) checkNasReferences(ctx context.Context) {
	res, err := s.store.CheckNasReferences(ctx)
	if err != nil {
		s.log.Error("backup: NAS references", "err", err)
		return
	}
	if res.Lost > 0 {
		s.log.Warn("backup: phone backups lost their only copy on the NAS", "count", res.Lost)
	}
	if len(res.Touched) > 0 {
		s.reconcile(ctx, res.Touched)
	}
	if s.keepNasCopies(ctx) {
		for _, id := range res.CopyOut {
			s.enqueue("copy:" + strconv.FormatInt(id, 10))
		}
	}
}

// settleMoves turns reported moves whose new path never came into deletions (kept), after moveWindow.
func (s *Server) settleMoves(ctx context.Context) {
	ids, err := s.store.SettleMoves(ctx, s.store.Now().Add(-moveWindow).UnixMilli())
	if err != nil {
		s.log.Error("backup: settle moves", "err", err)
		return
	}
	if len(ids) > 0 {
		s.reconcile(ctx, ids)
		s.indexer.TriggerBackup()
	}
}

// applyMirrorMove moves a phone backup's links after a claim or an admin move moved it to another physical device.
func (s *Server) applyMirrorMove(ctx context.Context, mv *store.MirrorMove) {
	if mv == nil {
		return
	}
	t := s.mirrorTree()
	if t != nil && mv.FromDir != mv.ToDir {
		s.backup.fsMu.Lock()
		for _, e := range mv.Relinked {
			if err := t.Remove(mv.FromDir, e.TreePath, e.SHA256); err != nil {
				s.log.Warn("backup: a link stays", "err", errorClassOf(err))
			}
		}
		s.backup.fsMu.Unlock()
		s.markGone(mv.FromDir, mv.Dropped...)
	}
	ids, err := s.store.MirrorEntryIDsOfPhysical(ctx, mv.ToID)
	if err != nil {
		s.log.Error("backup: move", "err", err)
		return
	}
	s.reconcile(ctx, ids)
	if t != nil && mv.FromDir != mv.ToDir {
		s.runBackupJobs(ctx) // the manifests first, then what is left of the old folder goes
		s.backup.fsMu.Lock()
		t.PruneDevice(mv.FromDir)
		s.backup.fsMu.Unlock()
	}
	roots, err := s.store.MirrorRoots(ctx, mv.ToID)
	if err == nil {
		for _, r := range roots {
			s.markDirty(r.ID)
		}
	}
	s.indexer.TriggerBackup()
}

// rootActive: not paused, not stopped, and a live device of its phone reported (or made it) within rootActiveWindow.
func rootActive(r store.MirrorRoot, live map[string]bool, now time.Time) bool {
	if r.Paused() || r.Stopped() || !live[r.PhysicalID] {
		return false
	}
	last := r.CreatedAt
	if r.LastReportAt != nil && *r.LastReportAt > last {
		last = *r.LastReportAt
	}
	return now.Sub(time.UnixMilli(last)) < rootActiveWindow
}

func (s *Server) backupStatusLine() string {
	b := &s.backup
	b.mu.Lock()
	defer b.mu.Unlock()
	return fmt.Sprintf("%d queued, %d done", len(b.queue), b.done)
}
