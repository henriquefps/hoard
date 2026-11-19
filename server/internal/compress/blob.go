package compress

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Phone and backup targets (ADR 0025 phase S2): the original is a phone backup's blob (ADR 0024), the output becomes a
// blob, and the swap of the two at the phone path is a transaction on the mirror (store.CompressSwap): reported by the
// phone (POST …/replaced, …/restored, or its next backup report) for a phone job, or done by the server alone for a
// video no longer on the phone (target "backup", or a phone video freed or deleted meanwhile).

// Targets.
const (
	TargetNAS    = "nas"
	TargetPhone  = "phone"
	TargetBackup = "backup"
)

// Phone abandon reasons (POST /v1/compress/items/{id}/abandon).
const (
	AbandonVerify  = "verify"
	AbandonChanged = "changed"
	AbandonGone    = "gone"
	AbandonRefused = "refused"
)

// ReasonVerifyPhone: the phone could not verify the downloaded output twice. ReasonLiveMissing: a restore found the
// live side nowhere in the phone's backup.
const (
	ReasonVerifyPhone = "verify_phone"
	ReasonLiveMissing = "live_missing"
)

// BlobHost is what the server gives the manager for phone and backup targets: the blob store, the NAS copies of ADR
// 0024 and the mirror tree.
type BlobHost interface {
	// Open opens a mirror entry's content: its blob, or the NAS file a reference reads.
	Open(ctx context.Context, e store.MirrorEntry) (*os.File, error)
	// Ingest copies the NAS file a reference reads into the blob store (ADR 0024's checked copy): the entry is then
	// "blob".
	Ingest(ctx context.Context, entryID int64) error
	// Adopt moves a verified work file into the blob store under its SHA-256 (a link or a rename on the backup root's
	// filesystem), under the content's lock: the caller writes its rows, then unlocks.
	Adopt(ctx context.Context, path, sha, fp string, size int64) (unlock func(), err error)
	// Changed puts the links of the entries a swap changed where they belong; the manifests and the items follow.
	Changed(ctx context.Context, entryIDs []int64)
	// Release removes the links of entries a purge removed and the files of blobs whose rows went (a file whose row
	// came back meanwhile stays); the manifests and the items follow.
	Release(ctx context.Context, links []store.MirrorEntry, shas []string)
}

// ErrNoBlobs: the server has no blob store (no backup root).
var ErrNoBlobs = errors.New("no blob store")

type mtimeInfo struct {
	os.FileInfo
	mtime time.Time
}

func (m mtimeInfo) ModTime() time.Time { return m.mtime }

// openSource opens an item's original, checked by size and fingerprint: a NAS file, or a mirror entry's content (whose
// mtime is the phone's).
func (m *Manager) openSource(ctx context.Context, it store.CompressItem) (*os.File, os.FileInfo, error) {
	if it.MirrorEntryID == nil {
		return m.openNas(ctx, it)
	}
	if m.cfg.Blobs == nil {
		return nil, nil, ErrNoBlobs
	}
	es, err := m.cfg.Store.MirrorEntries(ctx, []int64{*it.MirrorEntryID})
	if err != nil {
		return nil, nil, err
	}
	if len(es) == 0 {
		return nil, nil, ErrChanged
	}
	e := es[0]
	if e.Fingerprint != it.Fingerprint || e.Size != it.Size || !e.OwnsPath() {
		return nil, nil, ErrChanged
	}
	f, err := m.cfg.Blobs.Open(ctx, e)
	if err != nil {
		return nil, nil, err
	}
	fi, err := f.Stat()
	if err != nil || !fi.Mode().IsRegular() || fi.Size() != it.Size {
		f.Close()
		return nil, nil, ErrChanged
	}
	if fp, err := fingerprint.Of(f, fi.Size()); err != nil || fp != it.Fingerprint {
		f.Close()
		return nil, nil, ErrChanged
	}
	return f, mtimeInfo{fi, time.UnixMilli(e.ModifiedAt)}, nil
}

// ensureBlob ingests the original of a blob item into the store when its entry is a NAS reference (ADR 0025: the
// original never depends on a NAS file a library job, a copy or a sync may replace), and opens it from there.
func (m *Manager) ensureBlob(ctx context.Context, it store.CompressItem, src *os.File, fi os.FileInfo, log io.Writer) (*os.File, os.FileInfo, error) {
	es, err := m.cfg.Store.MirrorEntries(ctx, []int64{*it.MirrorEntryID})
	if err != nil || len(es) == 0 {
		return nil, nil, errors.Join(ErrChanged, err)
	}
	if es[0].Store == store.StoreBlob {
		return src, fi, nil
	}
	fmt.Fprintln(log, "the original is a NAS file: copied into the store first")
	if err := m.cfg.Blobs.Ingest(ctx, es[0].ID); err != nil {
		return nil, nil, err
	}
	es, err = m.cfg.Store.MirrorEntries(ctx, []int64{*it.MirrorEntryID})
	if err != nil || len(es) == 0 || es[0].Store != store.StoreBlob {
		return nil, nil, errors.Join(errors.New("the NAS copy is not in the store"), err)
	}
	return m.openSource(ctx, it)
}

// readyBlob moves a verified output into the blob store and makes the item ready (its rows in one transaction with
// the state). A cancel meanwhile drops it.
func (m *Manager) readyBlob(ctx context.Context, it *store.CompressItem, out string, log io.Writer) bool {
	unlock, err := m.cfg.Blobs.Adopt(ctx, out, it.OutSHA256, it.OutFingerprint, *it.OutSize)
	if err != nil {
		m.fail(ctx, it, ReasonNoSpace, log, err)
		return false
	}
	ok, err := m.cfg.Store.ReadyCompressBlob(context.WithoutCancel(ctx), *it)
	unlock()
	if err != nil || !ok {
		if err != nil {
			m.log.Error("compress: ready", "item", it.ID, "err", err)
		}
		m.dropOutput(context.WithoutCancel(ctx), *it)
		m.removeWork(it.ID)
		return false
	}
	it.State, it.Reason = store.CompressReady, ""
	fmt.Fprintln(log, "ready: the output is in the store")
	m.removeWork(it.ID)
	return true
}

// dropOutput forgets an output blob nothing keeps any more (a cancelled, abandoned or failed item).
func (m *Manager) dropOutput(ctx context.Context, it store.CompressItem) {
	if it.Source != "blob" || it.OutSHA256 == "" || m.cfg.Blobs == nil {
		return
	}
	sha, err := m.cfg.Store.DropCompressOutput(ctx, it.ID)
	if err != nil {
		m.log.Error("compress: drop output", "item", it.ID, "err", err)
		return
	}
	if sha == "" {
		if _, err := m.cfg.Store.BlobBySHA(ctx, it.OutSHA256); errors.Is(err, store.ErrNotFound) {
			sha = it.OutSHA256 // adopted, never recorded
		}
	}
	if sha != "" {
		m.cfg.Blobs.Release(ctx, nil, []string{sha})
	}
}

// refreshBlob rebuilds both sides' items after a state change (the heir rule and "compressed" follow the record); the
// items pass the swap triggered moves the sources.
func (m *Manager) refreshBlob(ctx context.Context, it store.CompressItem) {
	if m.cfg.Refresh != nil {
		m.cfg.Refresh(context.WithoutCancel(ctx), []string{it.Fingerprint, it.OutFingerprint})
	}
}

func (m *Manager) trashMs(ctx context.Context) int64 {
	return int64(m.TrashDays(ctx)) * 24 * int64(time.Hour/time.Millisecond)
}

func (m *Manager) entryOf(ctx context.Context, it store.CompressItem) (store.MirrorEntry, error) {
	if it.MirrorEntryID == nil {
		return store.MirrorEntry{}, store.ErrNotFound
	}
	es, err := m.cfg.Store.MirrorEntries(ctx, []int64{*it.MirrorEntryID})
	if err != nil {
		return store.MirrorEntry{}, err
	}
	if len(es) == 0 {
		return store.MirrorEntry{}, store.ErrNotFound
	}
	return es[0], nil
}

// blobReady settles a ready blob item: a backup item (or a phone item whose file is no longer on the phone) is
// swapped by the server now; a phone item waits for its phone.
func (m *Manager) blobReady(ctx context.Context, it store.CompressItem) {
	j, err := m.cfg.Store.CompressJob(ctx, it.JobID)
	if err != nil {
		return
	}
	if j.Target == TargetPhone {
		// The original wherever the phone keeps it now: on the phone, the phone replaces it (CompressWorker); not found
		// (the phone moved or replaced it and has not reported yet), the item waits for the phone's report or abandon.
		if e, err := m.cfg.Store.CompressLiveOwner(ctx, it.ID); err != nil || e.OnDevice {
			return
		}
	}
	_, _ = m.serverSwap(ctx, it, true, []string{store.CompressReady}) // a failure is in the item and its log
}

// MarkServed records that the phone downloaded a ready item's output: from then on the phone may have written it, so
// only the phone's report or abandon settles the item (a cancel answers its state; Amendment (S2), "A served output").
func (m *Manager) MarkServed(ctx context.Context, it store.CompressItem) {
	if it.State != store.CompressReady || it.ReplaceStep == store.StepServed {
		return
	}
	it.ReplaceStep = store.StepServed
	m.save(ctx, &it, store.CompressReady)
}

// serverSwap runs the swap with no phone step. A path that holds another file fails the item (source_changed) when it
// was ready, and leaves a restore where it was.
func (m *Manager) serverSwap(ctx context.Context, it store.CompressItem, toOutput bool, from []string, gone ...bool) (store.CompressItem, error) {
	log := m.openLog(it)
	defer func() { _ = log.Close() }()
	res, err := m.cfg.Store.CompressSwap(context.WithoutCancel(ctx), store.CompressSwapIn{ID: it.ID, ToOutput: toOutput, From: from, TrashMs: m.trashMs(ctx),
		Gone: len(gone) > 0 && gone[0]})
	switch {
	case errors.Is(err, store.ErrCompressPath) && it.State == store.CompressReady:
		fmt.Fprintln(log, "replace: the backup's path holds another file now")
		it.State, it.Reason = store.CompressFailed, ReasonSourceChanged
		m.save(ctx, &it, store.CompressReady)
		m.dropOutput(context.WithoutCancel(ctx), it)
		return it, err
	case err != nil:
		fmt.Fprintf(log, "swap: %v\n", err)
		return it, err
	}
	if res.Changed {
		fmt.Fprintf(log, "%s on the server: the %s is in the trash\n", res.Item.State, map[bool]string{true: "original", false: "output"}[toOutput])
		m.log.Info("compress: "+res.Item.State, "job", it.JobID, "item", it.ID, "by", "server")
		m.cfg.Blobs.Changed(context.WithoutCancel(ctx), res.Touched)
		m.refreshBlob(ctx, res.Item)
	}
	return res.Item, nil
}

// --- the phone's routes ----------------------------------------------------------------------------------------

// ErrNotPhoneItem: not a phone item of this phone (the routes answer 404).
var ErrNotPhoneItem = errors.New("not a phone item of this phone")

// PhoneItem returns a phone job's item of the given physical device.
func (m *Manager) PhoneItem(ctx context.Context, id int64, physicalID string) (store.CompressItem, error) {
	it, err := m.cfg.Store.CompressItem(ctx, id)
	if err != nil {
		return it, err
	}
	j, err := m.cfg.Store.CompressJob(ctx, it.JobID)
	if err != nil {
		return it, err
	}
	if it.Source != "blob" || j.Target != TargetPhone || physicalID == "" {
		return it, ErrNotPhoneItem
	}
	if j.PhysicalID == physicalID {
		return it, nil
	}
	// A phone backup moved to another physical device (ADR 0017 claim) takes its entries along: the phone is the
	// entry's.
	if e, err := m.entryOf(ctx, it); err == nil && e.PhysicalID == physicalID {
		return it, nil
	}
	return it, ErrNotPhoneItem
}

// OpenBlob opens a stored blob (the phone's download of an output).
func (m *Manager) OpenBlob(ctx context.Context, sha string) (*os.File, error) {
	if m.cfg.Blobs == nil {
		return nil, ErrNoBlobs
	}
	return m.cfg.Blobs.Open(ctx, store.MirrorEntry{Store: store.StoreBlob, SHA256: sha})
}

// PhoneReported applies what a phone reports after writing a file at its path: the output (POST …/replaced: a
// replace, or a restore again) or the original (…/restored). It answers store.ErrConflict when the item is not waiting
// for that, store.ErrCompressMismatch when the file is not the expected one, store.ErrCompressPath when the backup's
// path holds another file.
func (m *Manager) PhoneReported(ctx context.Context, it store.CompressItem, toOutput bool, f store.PhoneFile, deviceID string) (store.CompressItem, error) {
	var from []string
	switch {
	case toOutput && it.State == store.CompressReady, toOutput && it.State == store.CompressReplacing && it.ReplaceStep == store.StepReswapPhone,
		!toOutput && it.State == store.CompressReplacing && it.ReplaceStep == store.StepRestorePhone:
		from = []string{it.State}
	case toOutput && it.State == store.CompressReplaced, !toOutput && it.State == store.CompressRestored:
		from = []string{} // the same report again: answered from the stored state
	default:
		return it, store.ErrConflict
	}
	f.CheckMTime = true
	res, err := m.cfg.Store.CompressSwap(ctx, store.CompressSwapIn{ID: it.ID, ToOutput: toOutput, From: from, Phone: &f, DeviceID: deviceID,
		TrashMs: m.trashMs(ctx)})
	if err != nil {
		return it, err
	}
	if res.Changed {
		log := m.openLog(it)
		fmt.Fprintf(log, "%s: the phone wrote the %s at its path\n", res.Item.State, map[bool]string{true: "output", false: "original"}[toOutput])
		_ = log.Close()
		m.log.Info("compress: "+res.Item.State, "job", it.JobID, "item", it.ID, "by", "phone")
		m.cfg.Blobs.Changed(context.WithoutCancel(ctx), res.Touched)
		m.refreshBlob(ctx, res.Item)
	}
	return res.Item, nil
}

// Abandon settles a phone item the phone gave up on (ADR 0025 "Steps"): "gone" (the file is no longer on the phone)
// finishes it on the server as a backup item; "verify" and "changed" fail it, "refused" cancels it; the output blob is
// dropped when nothing else keeps it. A phone restore (or restore again) given up on goes back to where it was.
func (m *Manager) Abandon(ctx context.Context, it store.CompressItem, reason string) (store.CompressItem, error) {
	log := m.openLog(it)
	defer func() { _ = log.Close() }()
	fmt.Fprintf(log, "the phone abandoned it: %s\n", reason)
	switch {
	case it.State == store.CompressReady:
		if reason == AbandonGone {
			return m.serverSwap(ctx, it, true, []string{store.CompressReady}, true)
		}
		state, r := store.CompressFailed, ReasonVerifyPhone
		switch reason {
		case AbandonChanged:
			r = ReasonSourceChanged
		case AbandonRefused:
			state, r = store.CompressCancelled, AbandonRefused
		}
		it.State, it.Reason = state, r
		if !m.save(ctx, &it, store.CompressReady) {
			return it, store.ErrConflict
		}
		m.dropOutput(context.WithoutCancel(ctx), it)
		m.log.Info("compress: abandoned", "job", it.JobID, "item", it.ID, "reason", reason)
		return it, nil
	case it.State == store.CompressReplacing && (it.ReplaceStep == store.StepRestorePhone || it.ReplaceStep == store.StepReswapPhone):
		toOutput := it.ReplaceStep == store.StepReswapPhone
		if reason == AbandonGone {
			return m.serverSwap(ctx, it, toOutput, []string{store.CompressReplacing}, true)
		}
		back := store.CompressReplaced
		if toOutput {
			back = store.CompressRestored
		}
		it.State, it.Reason, it.ReplaceStep, it.StalledAt = back, reason, "", nil
		// The retention starts again: an abandoned restore never makes an already due trashed side go at once.
		it.PurgeAfter = i64(m.now().UnixMilli() + m.trashMs(ctx))
		if !m.save(ctx, &it, store.CompressReplacing) {
			return it, store.ErrConflict
		}
		m.refreshBlob(ctx, it) // its kept side is no longer held
		return it, nil
	}
	return it, store.ErrConflict
}

// --- restore and purge of blob items ---------------------------------------------------------------------------------

// RestoreItems starts the restore (or restore again) of replaced and restored items: NAS items through their journal
// (Restore), blob items by the server at once, or by the phone when its file is on the phone (the item is replacing
// until it reports).
func (m *Manager) RestoreItems(ctx context.Context, ids []int64) []RestoreAnswer {
	var nas []int64
	var out []RestoreAnswer
	for _, id := range ids {
		it, err := m.cfg.Store.CompressItem(ctx, id)
		if err == nil && it.Source == "blob" {
			out = append(out, m.restoreBlob(ctx, it))
			continue
		}
		nas = append(nas, id)
	}
	if len(nas) > 0 {
		if !m.NasWrite() {
			for _, id := range nas {
				out = append(out, RestoreAnswer{ID: id, Error: ReasonNotWritable})
			}
		} else {
			out = append(out, m.Restore(ctx, nas)...)
		}
	}
	return out
}

func (m *Manager) restoreBlob(ctx context.Context, it store.CompressItem) RestoreAnswer {
	toOutput := false
	switch it.State {
	case store.CompressReplaced:
	case store.CompressRestored:
		toOutput = true
	default:
		return RestoreAnswer{ID: it.ID, State: it.State, Error: "state"}
	}
	j, err := m.cfg.Store.CompressJob(ctx, it.JobID)
	if err != nil {
		return RestoreAnswer{ID: it.ID, State: it.State, Error: "state"}
	}
	owner, err := m.cfg.Store.CompressLiveOwner(ctx, it.ID)
	if err == nil && owner.OnDevice && j.Target == TargetPhone {
		prev := it.State
		it.State, it.Reason, it.ReplaceStep, it.StalledAt = store.CompressReplacing, "", store.StepRestorePhone, nil
		if toOutput {
			it.ReplaceStep = store.StepReswapPhone
		}
		if !m.save(ctx, &it, prev) {
			return RestoreAnswer{ID: it.ID, State: it.State, Error: "state"}
		}
		m.refreshBlob(ctx, it) // its kept side is held while the phone writes
		m.log.Info("compress: restore by the phone", "job", it.JobID, "item", it.ID)
		return RestoreAnswer{ID: it.ID, State: store.CompressReplacing}
	}
	res, err := m.serverSwap(ctx, it, toOutput, []string{it.State})
	if errors.Is(err, store.ErrCompressPath) {
		// The live side is nowhere in the backup now (deleted from it): the restore cannot swap, and the trashed side
		// is kept for another retention rather than purged by an old date.
		prev := it.State
		it.Reason, it.PurgeAfter = ReasonLiveMissing, i64(m.now().UnixMilli()+m.trashMs(ctx))
		m.save(ctx, &it, prev)
		return RestoreAnswer{ID: it.ID, State: it.State, Error: ReasonLiveMissing}
	}
	if err != nil {
		return RestoreAnswer{ID: it.ID, State: it.State, Error: "state"}
	}
	return RestoreAnswer{ID: it.ID, State: res.State}
}

// PurgeItems empties the trashed side of replaced and restored items now: NAS items through the write mount (Purge),
// blob items in the store. keptBy names, per blob item, why its bytes stay (claim, mirror, record, compress).
func (m *Manager) PurgeItems(ctx context.Context, ids []int64) ([]RestoreAnswer, map[int64][]string) {
	var nas []int64
	var out []RestoreAnswer
	kept := map[int64][]string{}
	for _, id := range ids {
		it, err := m.cfg.Store.CompressItem(ctx, id)
		if err == nil && it.Source == "blob" {
			a, k := m.purgeBlob(ctx, it, "route")
			out = append(out, a)
			if len(k) > 0 {
				kept[id] = k
			}
			continue
		}
		nas = append(nas, id)
	}
	if len(nas) > 0 {
		if !m.NasWrite() {
			for _, id := range nas {
				out = append(out, RestoreAnswer{ID: id, Error: ReasonNotWritable})
			}
		} else {
			out = append(out, m.Purge(ctx, nas)...)
		}
	}
	return out, kept
}

func (m *Manager) purgeBlob(ctx context.Context, it store.CompressItem, why string) (RestoreAnswer, []string) {
	res, err := m.cfg.Store.PurgeCompressBlob(ctx, it.ID)
	if err != nil {
		return RestoreAnswer{ID: it.ID, State: res.Item.State, Error: "state"}, nil
	}
	var shas []string
	if res.Removed != "" {
		shas = append(shas, res.Removed)
	}
	if m.cfg.Blobs != nil {
		m.cfg.Blobs.Release(context.WithoutCancel(ctx), res.Links, shas)
	}
	log := m.openLog(it)
	fmt.Fprintf(log, "purged (%s): the trashed side is emptied; kept by %v\n", why, res.KeptBy)
	_ = log.Close()
	m.log.Info("compress: purged", "job", it.JobID, "item", it.ID, "by", why, "blobRemoved", res.Removed != "")
	m.refreshBlob(ctx, res.Item)
	return RestoreAnswer{ID: it.ID, State: store.CompressPurged}, res.KeptBy
}

// housekeepBlobs settles ready blob items that need no phone and purges the due trashed sides of blob items.
func (m *Manager) housekeepBlobs(ctx context.Context) {
	if m.cfg.Blobs == nil {
		return
	}
	ready, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReady)
	if err == nil {
		for _, it := range ready {
			if it.Source == "blob" && ctx.Err() == nil {
				m.blobReady(ctx, it)
			}
		}
	}
	due, err := m.cfg.Store.CompressPurgeDue(ctx, m.now().UnixMilli())
	if err == nil {
		for _, it := range due {
			if it.Source == "blob" && ctx.Err() == nil {
				m.purgeBlob(ctx, it, "auto")
			}
		}
	}
	m.stallPhones(ctx)
}

// phoneStall is how long a phone may take to write a restore, or to report an output it downloaded, before the item
// counts as waiting (attention).
const phoneStall = 24 * time.Hour

// stallPhones marks the phone restores (and restores again) and the served outputs (S2 follow-ups) no phone reported
// for phoneStall as waiting: they are counted with the stalled journals (compress.attention) until the phone reports
// or gives up, a cancel settles them (the phone's backup no longer has the original), or the admin forces them.
func (m *Manager) stallPhones(ctx context.Context) {
	list, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReplacing, store.CompressReady)
	if err != nil {
		return
	}
	now := m.now()
	for _, it := range list {
		phone := it.ReplaceStep == store.StepRestorePhone || it.ReplaceStep == store.StepReswapPhone || it.ReplaceStep == store.StepServed
		if it.Source != "blob" || it.StalledAt != nil || !phone || now.Sub(time.UnixMilli(it.UpdatedAt)) < phoneStall {
			continue
		}
		it.StalledAt = i64(it.UpdatedAt)
		// Written as is (m.save clears stalled_at on any state but replacing).
		if ok, err := m.cfg.Store.SaveCompressItem(context.WithoutCancel(ctx), it, it.State); err == nil && ok {
			m.log.Warn("compress: a phone item waits", "job", it.JobID, "item", it.ID, "state", it.State, "step", it.ReplaceStep)
		}
	}
}

// ForceCancel is the admin's way out of a phone item waiting for a phone that may never report (lost, reset, Hoard
// uninstalled; S2 follow-ups): a ready item is cancelled and its output dropped unless something keeps it (the
// phone's backup already holding it, a claim, another compression); the original is never touched (the phone backup's,
// or a server item when the backup no longer has it). A phone restore (or restore again) goes back to the trash as it
// was, its retention restarted. store.ErrConflict: the item is in no such state.
func (m *Manager) ForceCancel(ctx context.Context, id int64) (store.CompressItem, error) {
	it, prev, err := m.cfg.Store.ForceCancelCompressItem(ctx, id, m.trashMs(ctx))
	if err != nil {
		return it, err
	}
	log := m.openLog(it)
	fmt.Fprintf(log, "forced by the admin: %s → %s\n", prev, it.State)
	_ = log.Close()
	m.log.Warn("compress: forced by the admin", "job", it.JobID, "item", it.ID, "from", prev, "to", it.State)
	if it.State == store.CompressCancelled {
		m.dropOutput(context.WithoutCancel(ctx), it)
	}
	m.refreshBlob(ctx, it)
	return it, nil
}

// recoverBlobs, after a restart: a ready output that is no longer in the store goes back to the queue. A phone's
// replacing item waits for its phone.
func (m *Manager) recoverBlobs(ctx context.Context) {
	if m.cfg.Blobs == nil {
		return
	}
	ready, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressReady)
	if err != nil {
		return
	}
	for _, it := range ready {
		if it.Source != "blob" || it.OutSize == nil {
			continue
		}
		f, err := m.OpenBlob(ctx, it.OutSHA256)
		ok := err == nil
		if ok {
			fi, err := f.Stat()
			ok = err == nil && fi.Size() == *it.OutSize
			f.Close()
		}
		if !ok {
			it.State = store.CompressQueued
			m.save(ctx, &it, store.CompressReady)
		}
	}
}

// --- selections of phone backups ---------------------------------------------------------------------------------

// ResolveTarget lists the videos of a selection of a target, newest first. Phone: current files of the caller's phone
// (physicalID); backup: files no longer on their phone, of any phone.
func (m *Manager) ResolveTarget(ctx context.Context, target, physicalID string, sel Selection) ([]Candidate, error) {
	if target == TargetNAS || target == "" {
		return m.Resolve(ctx, sel)
	}
	q := store.MirrorVideoQuery{OnDevice: target == TargetPhone}
	if target == TargetPhone {
		q.PhysicalID = physicalID
	}
	var list []store.MirrorVideo
	if len(sel.ItemIDs) > 0 {
		var ids []string
		seen := map[string]bool{}
		for _, id := range sel.ItemIDs {
			if !seen[id] {
				seen[id] = true
				ids = append(ids, id)
			}
		}
		for start := 0; start < len(ids); start += 500 {
			qq := q
			qq.ItemIDs = ids[start:min(start+500, len(ids))]
			got, err := m.cfg.Store.MirrorVideos(ctx, qq)
			if err != nil {
				return nil, err
			}
			list = append(list, got...)
		}
	} else {
		for _, r := range sel.Roots {
			qq := q
			qq.RootID, qq.Path = r.RootID, r.Path
			got, err := m.cfg.Store.MirrorVideos(ctx, qq)
			if err != nil {
				return nil, err
			}
			list = append(list, got...)
		}
	}
	return m.candidates(ctx, list, sel)
}

// candidates turns mirror videos into candidates: one per item (the first entry by path), filtered, newest first.
// Selected items that are not there are answered (not_in_target, trashed, not_video).
func (m *Manager) candidates(ctx context.Context, list []store.MirrorVideo, sel Selection) ([]Candidate, error) {
	byItem := map[string]store.MirrorVideo{}
	for _, v := range list {
		cur, ok := byItem[v.ItemID]
		if !ok || v.Entry.Volume < cur.Entry.Volume || v.Entry.Volume == cur.Entry.Volume && v.Entry.Path < cur.Entry.Path {
			byItem[v.ItemID] = v
		}
	}
	var out []Candidate
	for _, v := range byItem {
		if f := sel.Filter; f != nil {
			if (f.From != nil && v.SortTime < *f.From) || (f.To != nil && v.SortTime >= *f.To) || v.Entry.Size < f.MinBytes {
				continue
			}
		}
		id := v.Entry.ID
		c := Candidate{ItemID: v.ItemID, Kind: v.Kind, RelPath: v.Entry.Path, Fingerprint: v.Entry.Fingerprint, Size: v.Entry.Size, SortTime: v.SortTime,
			MirrorEntryID: &id, RootID: v.Entry.RootID, Volume: v.Entry.Volume}
		if c.Kind != "video" {
			c.Skip = ReasonNotVideo
		}
		out = append(out, c)
	}
	for _, id := range sel.ItemIDs {
		if _, ok := byItem[id]; ok {
			continue
		}
		c := Candidate{ItemID: id, Skip: ReasonNotInTarget}
		if row, err := m.cfg.Store.Item(ctx, id); err == nil && !row.Deleted {
			c.Kind = row.Kind
			switch {
			case row.Trashed():
				c.Skip = ReasonTrashed
			case row.Kind != "video":
				c.Skip = ReasonNotVideo
			}
		}
		out = append(out, c)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].SortTime != out[j].SortTime {
			return out[i].SortTime > out[j].SortTime
		}
		return out[i].ItemID < out[j].ItemID
	})
	return out, nil
}

// AfterBackup runs after the phone backups' items pass: folders compressed automatically get the videos new in their
// backup since the switch was turned on: a phone folder those still on the phone, a phone backup compressed on the
// server those no longer on it (freed or deleted).
func (m *Manager) AfterBackup(ctx context.Context) {
	if m.cfg.Blobs == nil {
		return
	}
	for _, target := range []string{TargetPhone, TargetBackup} {
		jobs, err := m.cfg.Store.AutoCompressJobs(ctx, target)
		if err != nil {
			m.log.Error("compress: automatic phone folders", "err", err)
			return
		}
		for _, j := range jobs {
			if err := m.autoAppendMirror(ctx, j); err != nil && ctx.Err() == nil {
				m.log.Error("compress: automatic phone folders", "err", err)
			}
		}
	}
}

func (m *Manager) autoAppendMirror(ctx context.Context, j store.CompressJob) error {
	var sel Selection
	if json.Unmarshal(j.Selection, &sel) != nil || len(sel.Roots) != 1 {
		return nil
	}
	// Only videos new in the folder's backup since the switch (the coordinator's rule, Amendment (S2)): a video backed up
	// before it is never taken automatically, even once it leaves the phone. The root identifies the phone (it moves
	// with a claimed backup, ADR 0017).
	q := store.MirrorVideoQuery{OnDevice: j.Target == TargetPhone, RootID: sel.Roots[0].RootID, Path: sel.Roots[0].Path, CreatedSince: j.CreatedAt}
	list, err := m.cfg.Store.MirrorVideos(ctx, q)
	if err != nil {
		return err
	}
	var fps []string
	var fresh []store.MirrorVideo
	seen := map[string]bool{}
	for _, v := range list {
		if v.Kind != "video" || seen[v.ItemID] || IsSyncArtifact(v.Entry.Path) {
			continue
		}
		seen[v.ItemID] = true
		fresh = append(fresh, v)
		fps = append(fps, v.Entry.Fingerprint)
	}
	if len(fresh) == 0 {
		return nil
	}
	known, err := m.cfg.Store.CompressItemsByFingerprint(ctx, fps)
	if err != nil {
		return err
	}
	had := map[string]bool{}
	for _, it := range known {
		had[it.Fingerprint], had[it.OutFingerprint] = true, true
	}
	var rows []store.CompressItem
	for _, v := range fresh {
		if had[v.Entry.Fingerprint] {
			continue
		}
		id := v.Entry.ID
		rows = append(rows, store.CompressItem{ItemID: v.ItemID, Fingerprint: v.Entry.Fingerprint, Size: v.Entry.Size, Source: "blob",
			RelPath: v.Entry.Path, MirrorEntryID: &id, Priority: 3, OrderKey: v.SortTime, State: store.CompressQueued})
	}
	if len(rows) == 0 {
		return nil
	}
	if _, err := m.cfg.Store.AppendCompressItems(ctx, j.ID, rows); err != nil {
		return err
	}
	m.log.Info("compress: automatic phone folder", "job", j.ID, "target", j.Target, "appended", len(rows))
	m.Wake()
	return nil
}
