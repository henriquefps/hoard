package compress

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/henriquefps/hoard/server/internal/store"
)

// The replace and restore journals (ADR 0025 "The replace journal", "Restore"): compress_items.replace_step is written
// before each step runs, so a restart settles the step by looking at the files.
//
//	replace:check, replace:copy, replace:trash, replace:swap        → replaced
//	restore:check, restore:trash:<path>, restore:swap:<path>        → restored (the output into the trash)
//	reswap:check,  reswap:trash:<path>,  reswap:swap:<path>         → replaced again (the original into the trash)

func (m *Manager) step(ctx context.Context, it *store.CompressItem, step string) error {
	it.ReplaceStep = step
	if !m.save(ctx, it, store.CompressReplacing) {
		return errStopped
	}
	if m.afterStep != nil {
		return m.afterStep(step)
	}
	return nil
}

func (m *Manager) folder(ctx context.Context, folderID string) (*Folder, error) {
	dir, err := m.cfg.FolderDir(ctx, folderID)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrNotWritable, err)
	}
	return m.cfg.Mount.Folder(dir)
}

func (m *Manager) itemLog(it store.CompressItem) io.WriteCloser { return m.openLog(it) }

func (m *Manager) after(ctx context.Context, it store.CompressItem) {
	if m.cfg.RequestWalk != nil && it.FolderID != "" {
		m.cfg.RequestWalk([]string{it.FolderID})
	}
	if m.cfg.Refresh != nil {
		fps := []string{it.Fingerprint}
		if it.OutFingerprint != "" {
			fps = append(fps, it.OutFingerprint)
		}
		m.cfg.Refresh(context.WithoutCancel(ctx), fps)
	}
}

// Journal rules (review of 0.13.0):
//   - Before anything moved (replace:check, replace:copy; restore:check), a mismatch fails (or aborts) the item and an
//     I/O error that may pass (the write mount gone) leaves it in its step to be tried again.
//   - From the trash step on, an item never leaves replacing without the files being looked at: an error keeps it
//     replacing with its step and trash path (stall: tried again by Housekeeping and at the next start); a file someone
//     changed meanwhile is settled by undoing our own move when that is safe, never by overwriting or deleting theirs.
//   - The file at the path is checked again right before it is linked into the trash, and right before the rename
//     over it: the trash entry must be the path's own inode (a link) or the path must be empty (a rename).

// journalRetry is how long a stalled journal waits before it is tried again.
const journalRetry = time.Minute

// claim marks an item's journal as running in this process (false: another goroutine runs it).
func (m *Manager) claim(id int64) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.journal == nil {
		m.journal = map[int64]bool{}
	}
	if m.journal[id] {
		return false
	}
	m.journal[id] = true
	return true
}

func (m *Manager) release(id int64) {
	m.mu.Lock()
	delete(m.journal, id)
	m.mu.Unlock()
}

// stall keeps an item replacing in its step (and its trash path) after an error that may pass; it is tried again after
// journalRetry.
func (m *Manager) stall(ctx context.Context, it *store.CompressItem, log io.Writer, err error) {
	fmt.Fprintf(log, "%s: waiting (%v): tried again later\n", it.ReplaceStep, err)
	it.Reason = ReasonNotWritable
	it.NotBefore = m.now().Add(journalRetry).UnixMilli()
	m.save(ctx, it, store.CompressReplacing)
	m.log.Warn("compress: journal waits", "item", it.ID, "step", stepName(it.ReplaceStep), "err", errorClass(err))
}

// stepName is a journal step without its path (for the server log).
func stepName(s string) string {
	a, b, _ := strings.Cut(s, ":")
	b, _, _ = strings.Cut(b, ":")
	return a + ":" + b
}

// errorClass keeps paths out of the server log.
func errorClass(err error) string {
	switch {
	case err == nil:
		return ""
	case errors.Is(err, os.ErrNotExist):
		return "not found"
	case errors.Is(err, os.ErrPermission):
		return "permission denied"
	case errors.Is(err, ErrNotWritable), errors.Is(err, ErrNoWriteMount):
		return "write mount unavailable"
	default:
		return "io error"
	}
}

// Settle runs (or resumes) the journal of a replacing item: a replace, a restore, a restore again or a purge.
func (m *Manager) Settle(ctx context.Context, it store.CompressItem) {
	if it.State != store.CompressReplacing || it.Source != "nas" || !m.claim(it.ID) {
		return
	}
	defer m.release(it.ID)
	// The row may have moved on since it was read.
	cur, err := m.cfg.Store.CompressItem(ctx, it.ID)
	if err != nil || cur.State != store.CompressReplacing {
		return
	}
	it = cur
	log := m.itemLog(it)
	defer func() { _ = log.Close() }()
	switch {
	case strings.HasPrefix(it.ReplaceStep, "purge:"):
		m.runPurge(ctx, &it, log)
	case strings.HasPrefix(it.ReplaceStep, "restore:"), strings.HasPrefix(it.ReplaceStep, "reswap:"):
		m.runSwapBack(ctx, &it, log)
	default:
		m.runReplace(ctx, &it, log)
	}
}

// replaceReady starts the replace of a ready NAS item (its verified output checked again first).
func (m *Manager) replaceReady(ctx context.Context, it store.CompressItem) {
	if _, ok := m.workOutput(it); !ok {
		log := m.itemLog(it)
		fmt.Fprintln(log, "the work file is missing or changed: queued again")
		_ = log.Close()
		it.State, it.Reason = store.CompressQueued, ""
		m.save(ctx, &it, store.CompressReady)
		m.removeWork(it.ID)
		return
	}
	it.State, it.Reason, it.ReplaceStep, it.TrashPath = store.CompressReplacing, "", "replace:check", ""
	if !m.save(ctx, &it, store.CompressReady) {
		return
	}
	m.Settle(ctx, it)
}

// runReplace runs (or resumes) the replace journal from it.ReplaceStep.
func (m *Manager) runReplace(ctx context.Context, it *store.CompressItem, log io.Writer) {
	tmp := TempName(it.RelPath, it.ID)
	var f *Folder
	// fail settles an item before anything is in the trash (or after our own move was undone).
	fail := func(reason string, err error) {
		fmt.Fprintf(log, "replace: failed (%s): %v\n", reason, err)
		if f != nil {
			_ = f.Remove(tmp)
		}
		it.State, it.Reason, it.ReplaceStep, it.TrashPath = store.CompressFailed, reason, "", ""
		m.save(ctx, it, store.CompressReplacing)
		m.removeWork(it.ID)
		m.log.Info("compress: replace", "item", it.ID, "state", it.State, "reason", reason)
	}
	f, err := m.folder(ctx, it.FolderID)
	if err != nil {
		f = nil
		m.stall(ctx, it, log, err)
		return
	}
	defer func() { _ = f.Close() }()
	step := it.ReplaceStep
	if step == "" {
		step = "replace:check"
	}
	if step == "replace:check" || step == "replace:copy" {
		work, ok := m.workOutput(*it)
		if !ok { // nothing moved yet: the output is made again
			fmt.Fprintln(log, "replace: the work file is missing or changed: queued again")
			_ = f.Remove(tmp)
			it.State, it.Reason, it.ReplaceStep, it.TrashPath = store.CompressQueued, "", "", ""
			m.save(ctx, it, store.CompressReplacing)
			m.removeWork(it.ID)
			return
		}
		_ = f.Remove(tmp)
		if err := m.step(ctx, it, "replace:check"); err != nil {
			return
		}
		cur, err := f.Stat(it.RelPath, true)
		ofi, oerr := os.Stat(work)
		if err != nil || oerr != nil {
			m.stall(ctx, it, log, errors.Join(err, oerr))
			return
		}
		// The original: the queued fingerprint and size, and the mtime the output carries (the original's).
		if !m.isOriginal(cur, it, ofi.ModTime()) {
			fail(ReasonSourceChanged, errors.New("the file at the path is not the original any more"))
			return
		}
		if err := f.ProbeTrash(it.RelPath); err != nil {
			fail(ReasonNotWritable, err)
			return
		}
		if free, err := f.Free(); err == nil && it.OutSize != nil && free < *it.OutSize+64<<20 {
			fmt.Fprintf(log, "replace: waiting for space on the NAS (%d free)\n", free)
			it.State, it.Reason, it.ReplaceStep = store.CompressReady, ReasonNoSpace, ""
			it.NotBefore = m.now().Add(noSpaceWait).UnixMilli()
			m.save(ctx, it, store.CompressReplacing)
			return
		}
		if err := m.step(ctx, it, "replace:copy"); err != nil {
			return
		}
		lfi, err := f.root.Lstat(it.RelPath)
		if err != nil {
			m.stall(ctx, it, log, err)
			return
		}
		if err := f.CopyIn(work, tmp, it.OutSHA256, lfi, cur.MTime); err != nil {
			fail(ReasonNotWritable, err)
			return
		}
		it.TrashPath = ""
		step = "replace:trash"
	}
	if step == "replace:trash" {
		if it.TrashPath == "" {
			p, err := f.TrashPath(it.RelPath, m.now())
			if err != nil {
				m.stall(ctx, it, log, err)
				return
			}
			it.TrashPath = p
		}
		if err := m.step(ctx, it, "replace:trash"); err != nil {
			return
		}
		tst, err := f.Stat(it.TrashPath, true)
		if err != nil {
			m.stall(ctx, it, log, err)
			return
		}
		if !tst.Exists { // not linked (or renamed) yet: the path must still hold the original, now
			cur, err := f.Stat(it.RelPath, true)
			if err != nil {
				m.stall(ctx, it, log, err)
				return
			}
			if !m.isOriginal(cur, it, time.Time{}) {
				fail(ReasonSourceChanged, errors.New("the file at the path changed during the copy"))
				return
			}
			if _, err := f.ToTrash(it.RelPath, it.TrashPath); err != nil {
				if errors.Is(err, ErrCrossDevice) {
					fail(ReasonTrashFailed, err)
				} else {
					m.stall(ctx, it, log, err)
				}
				return
			}
			if tst, err = f.Stat(it.TrashPath, true); err != nil {
				m.stall(ctx, it, log, err)
				return
			}
		}
		if tst.Fingerprint != it.Fingerprint || tst.Size != it.Size {
			// What went into the trash is not the original (it changed between the check and the link): undo.
			if m.undoTrash(f, it.RelPath, it.TrashPath, log) {
				fail(ReasonSourceChanged, errors.New("the file at the path changed while it was moved to the trash"))
			} else {
				m.stall(ctx, it, log, errors.New("the trash entry is not the original and cannot be put back"))
			}
			return
		}
		step = "replace:swap"
	}
	if step != "replace:swap" {
		m.stall(ctx, it, log, fmt.Errorf("unknown step %q", step))
		return
	}
	if err := m.step(ctx, it, "replace:swap"); err != nil {
		return
	}
	ts, err1 := f.Stat(tmp, false)
	cur, err2 := f.Stat(it.RelPath, true)
	tst, err3 := f.Stat(it.TrashPath, true)
	if err := errors.Join(err1, err2, err3); err != nil {
		m.stall(ctx, it, log, err)
		return
	}
	switch {
	case !ts.Exists && cur.Exists && cur.Fingerprint == it.OutFingerprint:
		// Swapped before the restart.
	case !ts.Exists:
		// The verified copy is gone and the output is not at the path: put the original back if the path is free.
		if !cur.Exists && tst.Exists && tst.Fingerprint == it.Fingerprint {
			if err := f.Swap(it.TrashPath, it.RelPath); err != nil {
				m.stall(ctx, it, log, err)
				return
			}
			fail(ReasonNotWritable, errors.New("the temporary copy disappeared: the original is back at its path"))
			return
		}
		if cur.Exists && tst.Exists && cur.Ino == tst.Ino {
			_ = f.Purge(it.TrashPath) // our link only: the path keeps the file
			fail(ReasonNotWritable, errors.New("the temporary copy disappeared"))
			return
		}
		m.stall(ctx, it, log, errors.New("neither the temporary copy nor the output at the path"))
		return
	case cur.Exists && tst.Exists && cur.Ino == tst.Ino && cur.Fingerprint != it.Fingerprint:
		// Written in place after the link: our trash link holds the same, changed bytes.
		_ = f.Remove(tmp)
		_ = f.Purge(it.TrashPath)
		fail(ReasonSourceChanged, errors.New("the file at the path was written to during the replace"))
		return
	case !tst.Exists || tst.Fingerprint != it.Fingerprint:
		m.stall(ctx, it, log, errors.New("the original is not in the trash"))
		return
	case cur.Exists && (cur.Ino != tst.Ino || cur.Fingerprint != it.Fingerprint):
		// The path changed after the link: never overwrite what is there now.
		_ = f.Remove(tmp)
		if cur.Ino == tst.Ino { // written in place: our trash link holds the same, changed bytes
			_ = f.Purge(it.TrashPath)
			fail(ReasonSourceChanged, errors.New("the file at the path was written to during the replace"))
			return
		}
		fmt.Fprintln(log, "replace: another file took the path; the original stays in the trash as a trashed file")
		fail(ReasonSourceChanged, errors.New("another file took the path during the replace"))
		return
	default: // the path is the original (a link) or empty (a rename): swap
		if err := f.Swap(tmp, it.RelPath); err != nil {
			m.stall(ctx, it, log, err)
			return
		}
	}
	now := m.now()
	it.State, it.Reason, it.ReplaceStep = store.CompressReplaced, "", ""
	it.NotBefore = 0
	it.ReplacedAt, it.TrashedAt = i64(now.UnixMilli()), i64(now.UnixMilli())
	it.PurgeAfter = i64(now.Add(time.Duration(m.TrashDays(ctx)) * 24 * time.Hour).UnixMilli())
	if !m.save(ctx, it, store.CompressReplacing) {
		return
	}
	fmt.Fprintln(log, "replaced: the original is in the trash")
	m.log.Info("compress: replaced", "job", it.JobID, "item", it.ID)
	m.removeWork(it.ID)
	m.after(ctx, *it)
}

// isOriginal reports whether st is the queued original (and carries mtime when it is not zero).
func (m *Manager) isOriginal(st FileState, it *store.CompressItem, mtime time.Time) bool {
	return st.Exists && st.Size == it.Size && st.Fingerprint == it.Fingerprint && (mtime.IsZero() || st.MTime.Equal(mtime))
}

// undoTrash puts back what we moved into the trash at trash: a link of the file still at rel is removed (the path
// keeps it); a file renamed away from an empty path is renamed back. It reports whether the move was undone.
func (m *Manager) undoTrash(f *Folder, rel, trash string, log io.Writer) bool {
	cur, err1 := f.Stat(rel, false)
	tst, err2 := f.Stat(trash, false)
	if err1 != nil || err2 != nil || !tst.Exists {
		return err1 == nil && err2 == nil
	}
	switch {
	case cur.Exists && cur.Ino == tst.Ino:
		if err := f.Purge(trash); err != nil {
			return false
		}
		fmt.Fprintln(log, "undo: the trash link removed")
		return true
	case !cur.Exists:
		if err := f.Swap(trash, rel); err != nil {
			return false
		}
		fmt.Fprintln(log, "undo: the file renamed back to its path")
		return true
	}
	return false
}

// --- restore and purge (one at a time, off the request) ------------------------------------------------------------

// RestoreAnswer is one item's answer to a restore or a purge.
type RestoreAnswer struct {
	ID    int64  `json:"-"`
	State string `json:"state,omitempty"`
	Error string `json:"error,omitempty"`
}

// Restore starts the restore of replaced items (and the restore again of restored ones): they become replacing now,
// the journal runs in the background.
func (m *Manager) Restore(ctx context.Context, ids []int64) []RestoreAnswer {
	var out []RestoreAnswer
	var run []int64
	for _, id := range ids {
		it, err := m.cfg.Store.CompressItem(ctx, id)
		if err != nil {
			out = append(out, RestoreAnswer{ID: id, Error: "not_found"})
			continue
		}
		if (it.State != store.CompressReplaced && it.State != store.CompressRestored) || it.Source != "nas" {
			out = append(out, RestoreAnswer{ID: id, State: it.State, Error: "state"})
			continue
		}
		dir := "restore"
		if it.State == store.CompressRestored {
			dir = "reswap"
		}
		prev := it.State
		it.State, it.Reason, it.ReplaceStep, it.NotBefore = store.CompressReplacing, "", dir+":check", 0
		if !m.save(ctx, &it, prev) {
			out = append(out, RestoreAnswer{ID: id, State: it.State, Error: "state"})
			continue
		}
		out = append(out, RestoreAnswer{ID: id, State: store.CompressReplacing})
		run = append(run, id)
	}
	if len(run) > 0 {
		o := op{kind: "restore", ids: run}
		select {
		case m.ops <- o:
		default: // a full queue never blocks the request; Housekeeping would also find the rows
			go func() { m.ops <- o }()
		}
	}
	return out
}

func (m *Manager) runOp(ctx context.Context, o op) {
	for _, id := range o.ids {
		if it, err := m.cfg.Store.CompressItem(ctx, id); err == nil && it.State == store.CompressReplacing {
			m.Settle(ctx, it)
		}
	}
}

func (m *Manager) opsLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case o := <-m.ops:
			m.runOp(ctx, o)
		}
	}
}

// RunOps runs the queued restores now (tests, without Start).
func (m *Manager) RunOps(ctx context.Context) {
	for {
		select {
		case o := <-m.ops:
			m.runOp(ctx, o)
		default:
			return
		}
	}
}

// runSwapBack runs a restore ("restore": the output to the trash, the original back) or a restore again ("reswap":
// the original to the trash, the output back) from its journal step.
func (m *Manager) runSwapBack(ctx context.Context, it *store.CompressItem, log io.Writer) {
	dir, rest, _ := strings.Cut(it.ReplaceStep, ":")
	stepName, newTrash, _ := strings.Cut(rest, ":")
	back := store.CompressReplaced // where an abort leaves it
	final := store.CompressRestored
	liveFP, trashedFP := it.OutFingerprint, it.Fingerprint
	if dir == "reswap" {
		back, final = store.CompressRestored, store.CompressReplaced
		liveFP, trashedFP = it.Fingerprint, it.OutFingerprint
	}
	// abort leaves the item as it was before the restore (only once our own move, if any, is undone).
	abort := func(reason string, err error) {
		fmt.Fprintf(log, "%s: %s: %v\n", dir, reason, err)
		it.State, it.Reason, it.ReplaceStep, it.NotBefore = back, reason, "", 0
		m.save(ctx, it, store.CompressReplacing)
	}
	f, err := m.folder(ctx, it.FolderID)
	if err != nil {
		m.stall(ctx, it, log, err)
		return
	}
	defer func() { _ = f.Close() }()
	if stepName == "check" {
		if err := m.step(ctx, it, dir+":check"); err != nil {
			return
		}
		tst, err1 := f.Stat(it.TrashPath, true)
		live, err2 := f.Stat(it.RelPath, true)
		if err := errors.Join(err1, err2); err != nil {
			m.stall(ctx, it, log, err)
			return
		}
		if !tst.Exists || tst.Fingerprint != trashedFP {
			abort("state", errors.New("the trashed file is not in the trash any more"))
			return
		}
		if live.Exists && live.Fingerprint != liveFP {
			abort("path_taken", errors.New("another file is at the path"))
			return
		}
		newTrash = ""
		if live.Exists {
			if newTrash, err = f.TrashPath(it.RelPath, m.now()); err != nil {
				m.stall(ctx, it, log, err)
				return
			}
		}
		stepName = "trash"
	}
	if stepName == "trash" {
		if err := m.step(ctx, it, dir+":trash:"+newTrash); err != nil {
			return
		}
		if newTrash != "" {
			nt, err := f.Stat(newTrash, true)
			if err != nil {
				m.stall(ctx, it, log, err)
				return
			}
			if !nt.Exists {
				live, err := f.Stat(it.RelPath, true)
				if err != nil {
					m.stall(ctx, it, log, err)
					return
				}
				if !live.Exists || live.Fingerprint != liveFP {
					abort("path_taken", errors.New("the file at the path changed"))
					return
				}
				if _, err := f.ToTrash(it.RelPath, newTrash); err != nil {
					if errors.Is(err, ErrCrossDevice) {
						abort(ReasonTrashFailed, err)
					} else {
						m.stall(ctx, it, log, err)
					}
					return
				}
				if nt, err = f.Stat(newTrash, true); err != nil {
					m.stall(ctx, it, log, err)
					return
				}
			}
			if nt.Fingerprint != liveFP {
				if m.undoTrash(f, it.RelPath, newTrash, log) {
					abort("path_taken", errors.New("the file at the path changed while it was moved to the trash"))
				} else {
					m.stall(ctx, it, log, errors.New("the trash entry is not the file and cannot be put back"))
				}
				return
			}
		}
		stepName = "swap"
	}
	if stepName != "swap" {
		m.stall(ctx, it, log, fmt.Errorf("unknown step %q", it.ReplaceStep))
		return
	}
	if err := m.step(ctx, it, dir+":swap:"+newTrash); err != nil {
		return
	}
	tst, err1 := f.Stat(it.TrashPath, true)
	cur, err2 := f.Stat(it.RelPath, true)
	var nt FileState
	var err3 error
	if newTrash != "" {
		nt, err3 = f.Stat(newTrash, true)
	}
	if err := errors.Join(err1, err2, err3); err != nil {
		m.stall(ctx, it, log, err)
		return
	}
	switch {
	case !tst.Exists && cur.Exists && cur.Fingerprint == trashedFP:
		// Swapped before the restart.
	case !tst.Exists || tst.Fingerprint != trashedFP:
		// The trashed file went (emptied meanwhile): undo our move and leave the item as it was.
		if newTrash == "" || m.undoTrash(f, it.RelPath, newTrash, log) {
			abort("state", errors.New("the trashed file is not in the trash any more"))
		} else {
			m.stall(ctx, it, log, errors.New("the trashed file went and our move cannot be undone"))
		}
		return
	case cur.Exists && (newTrash == "" || cur.Ino != nt.Ino || cur.Fingerprint != liveFP):
		// The path changed after our link (or a file appeared on an empty path): never overwrite it.
		if newTrash != "" && cur.Ino == nt.Ino {
			_ = f.Purge(newTrash) // written in place: our link holds the same bytes
		}
		fmt.Fprintln(log, dir+": another file took the path; what was moved stays in the trash")
		abort("path_taken", errors.New("the file at the path changed during the restore"))
		return
	default:
		if err := f.Swap(it.TrashPath, it.RelPath); err != nil {
			m.stall(ctx, it, log, err)
			return
		}
	}
	now := m.now()
	it.State, it.Reason, it.ReplaceStep, it.TrashPath, it.NotBefore = final, "", "", newTrash, 0
	it.TrashedAt = i64(now.UnixMilli())
	it.PurgeAfter = i64(now.Add(time.Duration(m.TrashDays(ctx)) * 24 * time.Hour).UnixMilli())
	if final == store.CompressRestored {
		it.RestoredAt = i64(now.UnixMilli())
	} else {
		it.ReplacedAt = i64(now.UnixMilli())
	}
	if newTrash == "" { // nothing was at the path (the output was trashed by hand): nothing of ours is in the trash
		it.TrashedAt, it.PurgeAfter = nil, nil
	}
	if !m.save(ctx, it, store.CompressReplacing) {
		return
	}
	fmt.Fprintf(log, "%s: done (%s)\n", dir, final)
	m.log.Info("compress: "+dir, "job", it.JobID, "item", it.ID, "state", final)
	m.after(ctx, *it)
}

// Purge empties the trashed file of replaced and restored items now (POST /v1/compress/purge).
func (m *Manager) Purge(ctx context.Context, ids []int64) []RestoreAnswer {
	return m.purgeNow(ctx, ids, "route")
}

// purgeNow claims each item (replaced or restored → replacing, journal "purge:<state>", so a restore or another purge
// cannot run on it meanwhile) and runs the purge.
func (m *Manager) purgeNow(ctx context.Context, ids []int64, why string) []RestoreAnswer {
	var out []RestoreAnswer
	for _, id := range ids {
		it, err := m.cfg.Store.CompressItem(ctx, id)
		if err != nil {
			out = append(out, RestoreAnswer{ID: id, Error: "not_found"})
			continue
		}
		if (it.State != store.CompressReplaced && it.State != store.CompressRestored) || it.Source != "nas" {
			out = append(out, RestoreAnswer{ID: id, State: it.State, Error: "state"})
			continue
		}
		prev := it.State
		it.State, it.ReplaceStep, it.Reason, it.NotBefore = store.CompressReplacing, "purge:"+prev, "", 0
		if !m.save(ctx, &it, prev) {
			out = append(out, RestoreAnswer{ID: id, State: it.State, Error: "state"})
			continue
		}
		m.Settle(ctx, it)
		after, err := m.cfg.Store.CompressItem(ctx, id)
		a := RestoreAnswer{ID: id, State: after.State}
		switch {
		case err != nil:
			a.Error = "not_found"
		case after.State == store.CompressReplacing:
			a.Error = ReasonNotWritable
		case after.State != store.CompressPurged:
			a.Error = after.Reason
		}
		if a.Error == "" {
			m.log.Info("compress: purged", "job", it.JobID, "item", it.ID, "by", why)
		}
		out = append(out, a)
	}
	return out
}

// runPurge deletes the trashed file of an item claimed for a purge. The live side must still exist (at its path, or
// as another live NAS copy): the trashed file is never deleted when it may be the only copy left.
func (m *Manager) runPurge(ctx context.Context, it *store.CompressItem, log io.Writer) {
	prev := strings.TrimPrefix(it.ReplaceStep, "purge:")
	back := func(reason string, err error) {
		fmt.Fprintf(log, "purge: refused (%s): %v\n", reason, err)
		it.State, it.Reason, it.ReplaceStep, it.NotBefore = prev, reason, "", 0
		m.save(ctx, it, store.CompressReplacing)
		m.log.Warn("compress: purge refused", "item", it.ID, "reason", reason)
	}
	liveFP, trashedFP := it.OutFingerprint, it.Fingerprint
	if prev == store.CompressRestored {
		liveFP, trashedFP = it.Fingerprint, it.OutFingerprint
	}
	if err := m.step(ctx, it, it.ReplaceStep); err != nil {
		return
	}
	if it.TrashPath != "" {
		f, err := m.folder(ctx, it.FolderID)
		if err != nil {
			m.stall(ctx, it, log, err)
			return
		}
		defer func() { _ = f.Close() }()
		tst, err1 := f.Stat(it.TrashPath, true)
		cur, err2 := f.Stat(it.RelPath, true)
		if err := errors.Join(err1, err2); err != nil {
			m.stall(ctx, it, log, err)
			return
		}
		if tst.Exists {
			if tst.Fingerprint != trashedFP {
				back("state", errors.New("the trash entry is not the trashed file"))
				return
			}
			if !cur.Exists || cur.Fingerprint != liveFP {
				elsewhere, err := m.cfg.Store.LiveNasCopy(ctx, liveFP, it.FolderID, it.RelPath)
				if err != nil {
					m.stall(ctx, it, log, err)
					return
				}
				if !elsewhere {
					back(ReasonOutputMissing, errors.New("the file at the path is gone or changed: the trashed file may be the only copy"))
					return
				}
			}
			if err := f.Purge(it.TrashPath); err != nil {
				m.stall(ctx, it, log, err)
				return
			}
		}
	}
	it.State, it.Reason, it.ReplaceStep, it.NotBefore = store.CompressPurged, "", "", 0
	it.PurgedAt = i64(m.now().UnixMilli())
	if m.save(ctx, it, store.CompressReplacing) {
		fmt.Fprintln(log, "purged")
		m.after(ctx, *it)
	}
}

// --- after a restart and after a walk ----------------------------------------------------------------------------

// Recover settles what a restart interrupted (ADR 0025 "Restart"): running steps go back to the queue (their work
// directories removed), replacing rows are settled from their journal, ready rows keep a checked work file or go
// back to the queue.
func (m *Manager) Recover(ctx context.Context) error {
	reset, err := m.cfg.Store.ResetRunningCompress(ctx)
	if err != nil {
		return err
	}
	for _, it := range reset {
		m.removeWork(it.ID)
	}
	if len(reset) > 0 {
		m.log.Info("compress: interrupted items queued again", "items", len(reset))
	}
	replacing, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReplacing)
	if err != nil {
		return err
	}
	for _, it := range replacing {
		m.Settle(ctx, it)
	}
	ready, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReady)
	if err != nil {
		return err
	}
	for _, it := range ready {
		if _, ok := m.workOutput(it); !ok && it.Source == "nas" {
			it.State = store.CompressQueued
			m.save(ctx, &it, store.CompressReady)
			m.removeWork(it.ID)
		}
	}
	return nil
}

// AfterWalk runs after the NAS folders are walked: a trashed file of ours that Hoard emptied (or that is gone) makes
// its item purged, and folders compressed automatically get their new videos.
func (m *Manager) AfterWalk(ctx context.Context) {
	list, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReplaced, store.CompressRestored)
	if err == nil {
		roots := map[string]*os.Root{}
		defer func() {
			for _, r := range roots {
				_ = r.Close()
			}
		}()
		for _, it := range list {
			if it.Source != "nas" || it.TrashPath == "" {
				continue
			}
			r := roots[it.FolderID]
			if r == nil {
				dir, err := m.cfg.FolderDir(ctx, it.FolderID)
				if err != nil {
					continue
				}
				if r, err = os.OpenRoot(dir); err != nil {
					continue
				}
				roots[it.FolderID] = r
			}
			// The folder must be there (an unmounted share leaves an empty, readable directory): the live side of the
			// item at its path, else nothing is concluded.
			if _, err := r.Lstat(it.RelPath); err != nil {
				continue
			}
			if _, err := r.Lstat(it.TrashPath); errors.Is(err, os.ErrNotExist) {
				prev := it.State
				it.State, it.PurgedAt = store.CompressPurged, i64(m.now().UnixMilli())
				if m.save(ctx, &it, prev) {
					m.log.Info("compress: purged", "job", it.JobID, "item", it.ID, "by", "trash")
					if m.cfg.Refresh != nil {
						m.cfg.Refresh(ctx, []string{it.Fingerprint, it.OutFingerprint})
					}
				}
			}
		}
	}
	if err := m.autoAppend(ctx); err != nil && ctx.Err() == nil {
		m.log.Error("compress: automatic folders", "err", err)
	}
}
