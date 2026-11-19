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

// replaceReady runs the replace of a ready NAS item (its verified output checked again first).
func (m *Manager) replaceReady(ctx context.Context, it store.CompressItem) {
	log := m.itemLog(it)
	defer func() { _ = log.Close() }()
	work, ok := m.workOutput(it)
	if !ok {
		fmt.Fprintln(log, "the work file is missing or changed: queued again")
		it.State, it.Reason = store.CompressQueued, ""
		m.save(ctx, &it, store.CompressReady)
		m.removeWork(it.ID)
		return
	}
	it.State = store.CompressReplacing
	it.ReplaceStep = "replace:check"
	if !m.save(ctx, &it, store.CompressReady) {
		return
	}
	m.runReplace(ctx, &it, work, log)
}

// runReplace runs (or resumes, after a restart) the replace journal from it.ReplaceStep.
func (m *Manager) runReplace(ctx context.Context, it *store.CompressItem, work string, log io.Writer) {
	failTo := func(state, reason string, err error) {
		fmt.Fprintf(log, "replace: %s (%s): %v\n", state, reason, err)
		it.State, it.Reason, it.ReplaceStep = state, reason, ""
		if state == store.CompressFailed {
			it.TrashPath = ""
		}
		m.save(ctx, it, store.CompressReplacing)
		if state == store.CompressFailed {
			m.removeWork(it.ID)
		}
		m.log.Info("compress: replace", "item", it.ID, "state", state, "reason", reason)
	}
	f, err := m.folder(ctx, it.FolderID)
	if err != nil {
		failTo(store.CompressFailed, ReasonNotWritable, err)
		return
	}
	defer func() { _ = f.Close() }()
	tmp := TempName(it.RelPath, it.ID)
	step := it.ReplaceStep
	if step == "replace:check" || step == "replace:copy" || step == "" {
		_ = f.Remove(tmp)
		if err := m.step(ctx, it, "replace:check"); err != nil {
			return
		}
		cur, err := f.Stat(it.RelPath, true)
		ofi, oerr := os.Stat(work)
		if err != nil || oerr != nil {
			failTo(store.CompressFailed, ReasonNotWritable, errors.Join(err, oerr))
			return
		}
		// The original: the queued fingerprint and size, and the mtime the output carries (the original's).
		if !cur.Exists || cur.Size != it.Size || cur.Fingerprint != it.Fingerprint || !cur.MTime.Equal(ofi.ModTime()) {
			failTo(store.CompressFailed, ReasonSourceChanged, errors.New("the file at the path is not the original any more"))
			return
		}
		if err := f.ProbeTrash(it.RelPath); err != nil {
			failTo(store.CompressFailed, ReasonNotWritable, err)
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
			failTo(store.CompressFailed, ReasonNotWritable, err)
			return
		}
		if err := f.CopyIn(work, tmp, it.OutSHA256, lfi.Mode(), cur.MTime); err != nil {
			failTo(store.CompressFailed, ReasonNotWritable, err)
			return
		}
		step = "replace:trash"
	}
	if step == "replace:trash" {
		if it.TrashPath == "" {
			p, err := f.TrashPath(it.RelPath, m.now())
			if err != nil {
				_ = f.Remove(tmp)
				failTo(store.CompressFailed, ReasonTrashFailed, err)
				return
			}
			it.TrashPath = p
		}
		if err := m.step(ctx, it, "replace:trash"); err != nil {
			return
		}
		tst, _ := f.Stat(it.TrashPath, false)
		if !tst.Exists { // not linked (or renamed) yet
			if _, err := f.ToTrash(it.RelPath, it.TrashPath); err != nil {
				_ = f.Remove(tmp)
				reason := ReasonTrashFailed
				failTo(store.CompressFailed, reason, err)
				return
			}
		}
		step = "replace:swap"
	}
	if step == "replace:swap" {
		if err := m.step(ctx, it, "replace:swap"); err != nil {
			return
		}
		if ts, _ := f.Stat(tmp, false); ts.Exists {
			if err := f.Swap(tmp, it.RelPath); err != nil {
				failTo(store.CompressReplacing, ReasonNotWritable, err) // kept replacing: settled at the next start
				return
			}
		} else if cur, _ := f.Stat(it.RelPath, true); cur.Fingerprint != it.OutFingerprint {
			failTo(store.CompressReplacing, ReasonNotWritable, errors.New("neither the temporary file nor the output at the path"))
			return
		}
	}
	now := m.now()
	it.State, it.Reason, it.ReplaceStep = store.CompressReplaced, "", ""
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
		it.State, it.ReplaceStep = store.CompressReplacing, dir+":check"
		if !m.save(ctx, &it, prev) {
			out = append(out, RestoreAnswer{ID: id, State: it.State, Error: "state"})
			continue
		}
		out = append(out, RestoreAnswer{ID: id, State: store.CompressReplacing})
		run = append(run, id)
	}
	if len(run) > 0 {
		m.ops <- op{kind: "restore", ids: run}
	}
	return out
}

func (m *Manager) opsLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case o := <-m.ops:
			for _, id := range o.ids {
				it, err := m.cfg.Store.CompressItem(ctx, id)
				if err != nil || it.State != store.CompressReplacing {
					continue
				}
				m.runSwapBack(ctx, &it)
			}
		}
	}
}

// RunOps runs the queued restores now (tests, without Start).
func (m *Manager) RunOps(ctx context.Context) {
	for {
		select {
		case o := <-m.ops:
			for _, id := range o.ids {
				if it, err := m.cfg.Store.CompressItem(ctx, id); err == nil && it.State == store.CompressReplacing {
					m.runSwapBack(ctx, &it)
				}
			}
		default:
			return
		}
	}
}

// runSwapBack runs a restore ("restore": the output to the trash, the original back) or a restore again ("reswap":
// the original to the trash, the output back) from its journal step.
func (m *Manager) runSwapBack(ctx context.Context, it *store.CompressItem) {
	log := m.itemLog(*it)
	defer func() { _ = log.Close() }()
	dir, rest, _ := strings.Cut(it.ReplaceStep, ":")
	stepName, newTrash, _ := strings.Cut(rest, ":")
	back := store.CompressReplaced // where a failure leaves it
	final := store.CompressRestored
	liveFP, trashedFP := it.OutFingerprint, it.Fingerprint
	if dir == "reswap" {
		back, final = store.CompressRestored, store.CompressReplaced
		liveFP, trashedFP = it.Fingerprint, it.OutFingerprint
	}
	abort := func(reason string, err error) {
		fmt.Fprintf(log, "%s: %s: %v\n", dir, reason, err)
		it.State, it.Reason, it.ReplaceStep = back, reason, ""
		m.save(ctx, it, store.CompressReplacing)
	}
	f, err := m.folder(ctx, it.FolderID)
	if err != nil {
		abort(ReasonNotWritable, err)
		return
	}
	defer func() { _ = f.Close() }()
	if stepName == "check" {
		if err := m.step(ctx, it, dir+":check"); err != nil {
			return
		}
		tst, err := f.Stat(it.TrashPath, true)
		if err != nil || !tst.Exists || tst.Fingerprint != trashedFP {
			abort("state", errors.New("the trashed file is not in the trash any more"))
			return
		}
		live, err := f.Stat(it.RelPath, true)
		if err != nil {
			abort(ReasonNotWritable, err)
			return
		}
		if live.Exists && live.Fingerprint != liveFP {
			abort("path_taken", errors.New("another file is at the path"))
			return
		}
		newTrash = ""
		if live.Exists {
			if newTrash, err = f.TrashPath(it.RelPath, m.now()); err != nil {
				abort(ReasonTrashFailed, err)
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
			if st, _ := f.Stat(newTrash, false); !st.Exists {
				if _, err := f.ToTrash(it.RelPath, newTrash); err != nil {
					abort(ReasonTrashFailed, err)
					return
				}
			}
		}
		stepName = "swap"
	}
	if stepName == "swap" {
		if err := m.step(ctx, it, dir+":swap:"+newTrash); err != nil {
			return
		}
		if st, _ := f.Stat(it.TrashPath, false); st.Exists {
			if err := f.Swap(it.TrashPath, it.RelPath); err != nil {
				fmt.Fprintf(log, "%s: swap: %v (settled at the next start)\n", dir, err)
				return
			}
		} else if cur, _ := f.Stat(it.RelPath, true); cur.Fingerprint != trashedFP {
			fmt.Fprintf(log, "%s: swap: the trashed file is neither in the trash nor at the path\n", dir)
			return
		}
	}
	now := m.now()
	it.State, it.Reason, it.ReplaceStep, it.TrashPath = final, "", "", newTrash
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

func (m *Manager) purgeNow(ctx context.Context, ids []int64, why string) []RestoreAnswer {
	var out []RestoreAnswer
	folders := map[string]*Folder{}
	defer func() {
		for _, f := range folders {
			_ = f.Close()
		}
	}()
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
		if it.TrashPath != "" {
			f := folders[it.FolderID]
			if f == nil {
				if f, err = m.folder(ctx, it.FolderID); err != nil {
					out = append(out, RestoreAnswer{ID: id, State: it.State, Error: ReasonNotWritable})
					continue
				}
				folders[it.FolderID] = f
			}
			want := it.Fingerprint
			if it.State == store.CompressRestored {
				want = it.OutFingerprint
			}
			if st, err := f.Stat(it.TrashPath, true); err == nil && st.Exists {
				if st.Fingerprint != want {
					out = append(out, RestoreAnswer{ID: id, State: it.State, Error: "state"})
					continue
				}
				if err := f.Purge(it.TrashPath); err != nil {
					out = append(out, RestoreAnswer{ID: id, State: it.State, Error: ReasonNotWritable})
					continue
				}
			}
		}
		prev := it.State
		it.State, it.PurgedAt = store.CompressPurged, i64(m.now().UnixMilli())
		if m.save(ctx, &it, prev) {
			m.log.Info("compress: purged", "job", it.JobID, "item", it.ID, "by", why)
			m.after(ctx, it)
		}
		out = append(out, RestoreAnswer{ID: id, State: it.State})
	}
	return out
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
		if it.Source != "nas" {
			continue
		}
		it := it
		if strings.HasPrefix(it.ReplaceStep, "replace:") || it.ReplaceStep == "" {
			work, ok := m.workOutput(it)
			if !ok && (it.ReplaceStep == "replace:check" || it.ReplaceStep == "replace:copy" || it.ReplaceStep == "") {
				if f, err := m.folder(ctx, it.FolderID); err == nil {
					_ = f.Remove(TempName(it.RelPath, it.ID))
					_ = f.Close()
				}
				it.State, it.ReplaceStep, it.TrashPath = store.CompressQueued, "", ""
				m.save(ctx, &it, store.CompressReplacing)
				continue
			}
			log := m.itemLog(it)
			m.runReplace(ctx, &it, work, log)
			_ = log.Close()
			continue
		}
		m.runSwapBack(ctx, &it)
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
				r.Close()
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
