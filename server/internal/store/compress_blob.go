package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Video compression of phone backups (ADR 0025 "Phone videos: the server side", "Server-only phone videos"): the
// original and the output are blobs, and the swap of the two at a phone path is one transaction on mirror_entries.

// RemovedCompressed is the removal reason of an entry a compression keeps: the original after a replace (or the output
// after a restore). It owns no path (the partial unique index mirror_entries_path does not list it), it is pinned, and
// it is the "kept" item source (a trashed server item) until the purge.
const RemovedCompressed = "compressed"

// Blob compression errors.
var (
	// ErrCompressMismatch: what the phone reported is not the file the server expects (fingerprint, SHA-256, size, mtime).
	ErrCompressMismatch = errors.New("mismatch")
	// ErrCompressPath: the phone path holds another file than the one the swap expects.
	ErrCompressPath = errors.New("path changed")
	// ErrCompressed: a compression keeps the original (ownership rule 5); DELETE /v1/originals answers 409.
	ErrCompressed = errors.New("kept by a compression")
)

// MirrorVideo is a phone backup's file whose live item is a video, a candidate of a phone or backup compression.
type MirrorVideo struct {
	Entry    MirrorEntry
	RootPath string
	ItemID   string
	Kind     string
	SortTime int64
}

// MirrorVideoQuery selects mirror entries for a compression.
type MirrorVideoQuery struct {
	PhysicalID string // "" = every phone
	// OnDevice true: current files the phone still has; false: files no longer on the phone (freed or deleted).
	OnDevice bool
	RootID   string
	Path     string // a path on the volume inside the root ("" = the whole root)
	ItemIDs  []string
	// CreatedSince (ms, 0 = any): entries whose row and item were both first seen at or after it.
	CreatedSince int64
}

// MirrorVideos lists the entries of a query whose bytes the server has (stored, or a NAS reference) and whose live item
// is a video, newest shot first; one row per entry.
func (s *Store) MirrorVideos(ctx context.Context, q MirrorVideoQuery) ([]MirrorVideo, error) {
	where := []string{"e.store IN ('blob', 'nas')", "i.deleted = 0", "i.trashed_at IS NULL"}
	var args []any
	if q.OnDevice {
		where = append(where, "e.on_device = 1", "e.removed_reason IS NULL")
	} else {
		where = append(where, "e.on_device = 0", "e.removed_reason IN ('freed', 'deleted')")
	}
	if q.PhysicalID != "" {
		where = append(where, "e.physical_id = ?")
		args = append(args, q.PhysicalID)
	}
	if q.RootID != "" {
		where = append(where, "e.root_id = ?")
		args = append(args, q.RootID)
	}
	if q.Path != "" {
		where = append(where, `(e.path = ? OR e.path LIKE ? ESCAPE '\')`)
		args = append(args, q.Path, likeEscape(q.Path)+"/%")
	}
	if q.CreatedSince > 0 {
		// New in the backup: the entry and the shot's item both appeared since. A row the backup made again for an old
		// file (a moved or re-added path, a merge) keeps its item's first-seen time, which items never move forward.
		where = append(where, "e.created_at >= ?", "i.created_at >= ?")
		args = append(args, q.CreatedSince, q.CreatedSince)
	}
	if len(q.ItemIDs) > 0 {
		where = append(where, "f.item_id IN ("+placeholders(len(q.ItemIDs))+")")
		for _, id := range q.ItemIDs {
			args = append(args, id)
		}
	}
	//nolint:gosec // fixed clauses and placeholders only
	rows, err := s.db.QueryContext(ctx, `SELECT `+mirrorEntryCols+`, r.path, f.item_id, i.kind, i.sort_time
		FROM mirror_entries e JOIN mirror_roots r ON r.id = e.root_id JOIN item_files f ON f.fingerprint = e.fingerprint
		JOIN items i ON i.id = f.item_id WHERE `+strings.Join(where, " AND ")+`
		ORDER BY i.sort_time DESC, e.id, (i.main_fp = e.fingerprint) DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MirrorVideo
	seen := map[int64]bool{}
	for rows.Next() {
		var v MirrorVideo
		e := &v.Entry
		if err := rows.Scan(&e.ID, &e.PhysicalID, &e.Volume, &e.Path, &e.RootID, &e.Fingerprint, &e.SHA256, &e.Size, &e.ModifiedAt, &e.Store,
			&e.Linked, &e.OnDevice, &e.RemovedAt, &e.RemovedReason, &e.VerifiedSHA, &e.VerifiedAt, &e.ReportedBy, &e.TreePath, &e.LostAt, &e.DirName,
			&v.RootPath, &v.ItemID, &v.Kind, &v.SortTime); err != nil {
			return nil, err
		}
		if seen[e.ID] {
			continue
		}
		seen[e.ID] = true
		out = append(out, v)
	}
	return out, rows.Err()
}

// ReadyCompressBlob records a verified output that is now a blob (its blobs and originals rows, no device claim: the
// compression keeps it by ownership rule 5) and makes the item ready, in one transaction. ok is false when the item
// left verifying meanwhile (a cancel): nothing is written.
func (s *Store) ReadyCompressBlob(ctx context.Context, it CompressItem) (bool, error) {
	ok := false
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		it.State, it.UpdatedAt = CompressReady, now
		var err error
		if ok, err = saveCompressItem(ctx, tx, it, CompressVerifying); err != nil || !ok {
			return err
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO blobs (sha256, size, kind, created_at, device_id) VALUES (?, ?, 'original', ?, NULL) ON CONFLICT DO NOTHING",
			it.OutSHA256, it.OutSize, now); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "INSERT INTO originals (fingerprint, sha256) VALUES (?, ?) ON CONFLICT DO NOTHING", it.OutFingerprint, it.OutSHA256)
		return err
	})
	return ok, err
}

// PhoneFile is a file a phone reports at its path after writing it (POST …/replaced, …/restored, or a backup report).
type PhoneFile struct {
	Fingerprint string
	SHA256      string // "" = not sent (a backup report may omit it)
	Size        int64
	ModifiedAt  int64 // ms
	// CheckMTime: the mtime must be the original's (within 2 s: FAT keeps even seconds).
	CheckMTime bool
}

// CompressSwapIn asks for the swap of a blob compression at its phone path.
type CompressSwapIn struct {
	ID int64
	// ToOutput: the output takes the path (a replace, or a restore again); false: the original comes back (a restore).
	ToOutput bool
	// From lists the states the item may be in.
	From []string
	// Phone is the file the phone reported at the path (on_device then 1); nil: the server alone (the entry keeps
	// whether it is on the phone and why not).
	Phone    *PhoneFile
	DeviceID string
	// TrashMs is how long the trashed side is kept before the server purges it.
	TrashMs int64
	// OwnerID: the entry that holds the live side (a backup report's path); 0 = found by the item (its path, else the
	// phone's entry with that content, wherever the phone moved it).
	OwnerID int64
	// Gone: the phone said the file is no longer there (abandon "gone"): the new owner is off the phone (deleted)
	// unless the entry already says why it left.
	Gone bool
}

// liveOwnerTx is the entry holding a blob compression's live side (liveFP): the item's own path first, else the
// phone's entry with that content that holds a path (the phone renamed or moved the file), on the phone first.
func liveOwnerTx(ctx context.Context, tx *sql.Tx, orig MirrorEntry, liveFP string, hint int64) (MirrorEntry, error) {
	if hint != 0 {
		if e, err := entryTx(ctx, tx, hint); err == nil && e.OwnsPath() && e.Fingerprint == liveFP && e.PhysicalID == orig.PhysicalID {
			return e, nil
		}
	}
	if e, err := ownerAt(ctx, tx, orig.PhysicalID, orig.Volume, orig.Path); err == nil && e.Fingerprint == liveFP {
		return e, nil
	} else if err != nil && !errors.Is(err, ErrNotFound) {
		return e, err
	}
	return scanMirrorEntry(tx.QueryRowContext(ctx, "SELECT "+mirrorEntryCols+" FROM mirror_entries e WHERE e.physical_id = ? AND e.fingerprint = ? AND "+
		ownsPathSQL+" ORDER BY e.on_device DESC, e.id DESC LIMIT 1", orig.PhysicalID, liveFP))
}

// liveFingerprint is the side of a blob compression the phone's path holds now: the output once replaced (and while
// a phone restores it), else the original.
func liveFingerprint(it CompressItem) string {
	if it.State == CompressReplaced || (it.State == CompressReplacing && it.ReplaceStep == StepRestorePhone) {
		return it.OutFingerprint
	}
	return it.Fingerprint
}

// CompressLiveOwner is the entry holding a blob compression's live side now (the original while ready, the output once
// replaced, the original once restored; while a phone swaps them, the side it is replacing), wherever the phone moved
// it. ErrNotFound: the phone's backup has it nowhere (the entry gone, the backup purged).
func (s *Store) CompressLiveOwner(ctx context.Context, id int64) (MirrorEntry, error) {
	var out MirrorEntry
	err := s.tx(ctx, func(tx *sql.Tx) error {
		it, err := compressItemTx(ctx, tx, id)
		if err != nil {
			return err
		}
		if it.MirrorEntryID == nil {
			return ErrNotFound
		}
		orig, err := entryTx(ctx, tx, *it.MirrorEntryID)
		if err != nil {
			return err
		}
		out, err = liveOwnerTx(ctx, tx, orig, liveFingerprint(it), 0)
		return err
	})
	return out, err
}

// CompressLiveOwners is CompressLiveOwner for several items in one read transaction (a list of items served at once:
// one snapshot, one transaction). Items without a live owner (ErrNotFound) are left out.
func (s *Store) CompressLiveOwners(ctx context.Context, ids []int64) (map[int64]MirrorEntry, error) {
	out := map[int64]MirrorEntry{}
	if len(ids) == 0 {
		return out, nil
	}
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback() }()
	for _, id := range ids {
		it, err := compressItemTx(ctx, tx, id)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if it.MirrorEntryID == nil {
			continue
		}
		orig, err := entryTx(ctx, tx, *it.MirrorEntryID)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		e, err := liveOwnerTx(ctx, tx, orig, liveFingerprint(it), 0)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		out[id] = e
	}
	return out, nil
}

// Reasons of a cancel that settles a served phone item (S2 follow-ups): the phone's backup no longer has the original
// (phone_gone), or the admin forced it (forced; a phone restore given up on by the admin keeps this reason too).
const (
	ReasonPhoneGone = "phone_gone"
	ReasonForced    = "forced"
)

// servedPhoneGoneTx reports whether a ready phone item's original is nowhere in its phone's backup any more: its entry
// was removed (the admin purged the backup) or no entry of that phone holds a path with the original's content. Then no
// phone report can come for it, and a cancel may settle it.
func servedPhoneGoneTx(ctx context.Context, tx *sql.Tx, it CompressItem) (bool, error) {
	if it.MirrorEntryID == nil {
		return true, nil
	}
	orig, err := entryTx(ctx, tx, *it.MirrorEntryID)
	if errors.Is(err, ErrNotFound) {
		return true, nil
	}
	if err != nil {
		return false, err
	}
	_, err = liveOwnerTx(ctx, tx, orig, it.Fingerprint, 0)
	if errors.Is(err, ErrNotFound) {
		return true, nil
	}
	return false, err
}

// ForceCancelCompressItem is the admin's way out of a phone item that waits for a phone (S2 follow-ups): a ready item
// (its output served or not) is cancelled (reason forced; the caller drops the output unless something keeps it, and
// the original stays: the backup's, or a server item when the backup no longer has it); a phone restore (or restore
// again) goes back to replaced (restored) with its retention restarted, as an abandon would. Nothing else is touched.
// prev is the state before.
func (s *Store) ForceCancelCompressItem(ctx context.Context, id, trashMs int64) (it CompressItem, prev string, err error) {
	now := s.nowMs()
	err = s.tx(ctx, func(tx *sql.Tx) error {
		if it, err = compressItemTx(ctx, tx, id); err != nil {
			return err
		}
		prev = it.State
		if it.Source != "blob" {
			return ErrConflict
		}
		switch {
		case it.State == CompressReady:
			it.State = CompressCancelled
		case it.State == CompressReplacing && it.ReplaceStep == StepRestorePhone:
			it.State, it.PurgeAfter = CompressReplaced, ptr(now+trashMs)
		case it.State == CompressReplacing && it.ReplaceStep == StepReswapPhone:
			it.State, it.PurgeAfter = CompressRestored, ptr(now+trashMs)
		default:
			return ErrConflict
		}
		it.Reason, it.ReplaceStep, it.StalledAt, it.UpdatedAt = ReasonForced, "", nil, now
		ok, err := saveCompressItem(ctx, tx, it, prev)
		if err == nil && !ok {
			err = ErrConflict
		}
		return err
	})
	return it, prev, err
}

// CompressSwapOut is what a swap changed.
type CompressSwapOut struct {
	Item    CompressItem
	Touched []int64 // entries whose links follow
	Changed bool    // false: it was done already (the same report again)
}

func compressItemTx(ctx context.Context, tx *sql.Tx, id int64) (CompressItem, error) {
	return scanCompressItem(tx.QueryRowContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE id = ?", id))
}

func entryTx(ctx context.Context, tx *sql.Tx, id int64) (MirrorEntry, error) {
	return scanMirrorEntry(tx.QueryRowContext(ctx, "SELECT "+mirrorEntryCols+" FROM mirror_entries e WHERE e.id = ?", id))
}

// CompressSwap runs the swap of a blob compression (ADR 0025 "The phone reports the replace", "Server-only phone
// videos", "Restore") in one transaction.
func (s *Store) CompressSwap(ctx context.Context, in CompressSwapIn) (CompressSwapOut, error) {
	var out CompressSwapOut
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		it, err := compressItemTx(ctx, tx, in.ID)
		if err != nil {
			return err
		}
		out, err = compressSwapTx(ctx, tx, it, in, now)
		return err
	})
	return out, err
}

// compressSwapTx: the entry that holds the path (the side being replaced) becomes "compressed" (pinned, on_device 0,
// its link moves to .hoard-mirror/compressed/), and the other side takes the path: a new entry with the output's
// content the first time, else the kept entry again. With a phone report the new owner is on the phone; without one
// it keeps the old owner's on_device, removed_reason and pinned (a freed video stays freed and pinned).
func compressSwapTx(ctx context.Context, tx *sql.Tx, it CompressItem, in CompressSwapIn, now int64) (CompressSwapOut, error) {
	out := CompressSwapOut{Item: it}
	if it.Source != "blob" || it.MirrorEntryID == nil || it.OutFingerprint == "" || it.OutSize == nil {
		return out, ErrConflict
	}
	liveFP, newFP, newSHA, newSize, final := it.Fingerprint, it.OutFingerprint, it.OutSHA256, *it.OutSize, CompressReplaced
	if !in.ToOutput {
		liveFP, newFP, newSHA, newSize, final = it.OutFingerprint, it.Fingerprint, it.SHA256, it.Size, CompressRestored
	}
	orig, err := entryTx(ctx, tx, *it.MirrorEntryID)
	if err != nil {
		return out, err
	}
	if p := in.Phone; p != nil {
		if p.Fingerprint != newFP || p.Size != newSize || (p.SHA256 != "" && newSHA != "" && p.SHA256 != newSHA) {
			return out, ErrCompressMismatch
		}
		if p.CheckMTime {
			// The mtime the phone keeps is the live entry's (#73 review): after a rename on the phone that is not the
			// item's own entry. A report again is checked against the side it already put there.
			ref, fp := orig, liveFP
			if it.State == final {
				fp = newFP
			}
			if e, err := liveOwnerTx(ctx, tx, orig, fp, in.OwnerID); err == nil {
				ref = e
			} else if !errors.Is(err, ErrNotFound) {
				return out, err
			}
			if p.ModifiedAt < ref.ModifiedAt-2000 || p.ModifiedAt > ref.ModifiedAt+2000 {
				return out, ErrCompressMismatch
			}
		}
	}
	if it.State == final && !contains(in.From, final) { // the same report again
		return out, nil
	}
	if !contains(in.From, it.State) {
		return out, ErrConflict
	}
	owner, err := liveOwnerTx(ctx, tx, orig, liveFP, in.OwnerID)
	if errors.Is(err, ErrNotFound) {
		return out, ErrCompressPath
	}
	if err != nil {
		return out, err
	}
	onDevice, reason, pinned, removedAt, modified, by := owner.OnDevice, nullString(owner.RemovedReason), 0, owner.RemovedAt, owner.ModifiedAt, owner.ReportedBy
	if err := tx.QueryRowContext(ctx, "SELECT pinned FROM mirror_entries WHERE id = ?", owner.ID).Scan(&pinned); err != nil {
		return out, err
	}
	if owner.RemovedReason == RemovedFreed { // a freed video stays freed and pinned (the only copies)
		pinned = 1
	}
	if _, err := tx.ExecContext(ctx, `UPDATE mirror_entries SET removed_reason = 'compressed', removed_at = ?, on_device = 0, pinned = 1, updated_at = ?
		WHERE id = ?`, now, now, owner.ID); err != nil {
		return out, err
	}
	if p := in.Phone; p != nil {
		onDevice, reason, pinned, removedAt, modified = true, nil, 0, nil, p.ModifiedAt
	} else if in.Gone && owner.OnDevice { // the path is empty on the phone now: the new owner is not there either
		onDevice, reason, pinned, removedAt = false, RemovedDeleted, 0, &now
	}
	if in.DeviceID != "" {
		by = in.DeviceID
	}
	// The kept side is exactly the entry this item's last swap made "compressed" (the same video at another path, or
	// another item's kept entry of the same content, is never taken). The first swap has none: a new entry.
	var kept int64
	err = sql.ErrNoRows
	if it.KeptEntryID != nil {
		err = tx.QueryRowContext(ctx, `SELECT id FROM mirror_entries WHERE id = ? AND physical_id = ? AND fingerprint = ? AND removed_reason = 'compressed'`,
			*it.KeptEntryID, orig.PhysicalID, newFP).Scan(&kept)
	}
	switch {
	case err == nil: // the kept side comes back, at the path the live side holds now (the phone may have moved it)
		if _, err := tx.ExecContext(ctx, `UPDATE mirror_entries SET volume = ?, path = ?, root_id = ?, on_device = ?, removed_reason = ?, removed_at = ?,
			pinned = ?, modified_at = ?, reported_by = ?, updated_at = ? WHERE id = ?`, owner.Volume, owner.Path, owner.RootID, onDevice, reason, removedAt,
			pinned, modified, by, now, kept); err != nil {
			return out, err
		}
	case errors.Is(err, sql.ErrNoRows):
		if err := tx.QueryRowContext(ctx, `INSERT INTO mirror_entries (physical_id, volume, path, root_id, fingerprint, sha256, size, modified_at, store,
			on_device, removed_at, removed_reason, pinned, verified_sha256, verified_at, reported_by, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'blob', ?, ?, ?, ?, (SELECT nullif(result, '') FROM blob_verifications WHERE sha256 = ?),
			(SELECT verified_at FROM blob_verifications WHERE sha256 = ? AND result != ''), ?, ?, ?) RETURNING id`,
			orig.PhysicalID, owner.Volume, owner.Path, owner.RootID, newFP, newSHA, newSize, modified, onDevice, removedAt, reason, pinned,
			newSHA, newSHA, by, now, now).Scan(&kept); err != nil {
			return out, err
		}
	default:
		return out, err
	}
	it.State, it.Reason, it.ReplaceStep, it.TrashPath, it.StalledAt = final, "", "", "", nil
	it.TrashedAt, it.PurgeAfter, it.KeptEntryID = &now, ptr(now+in.TrashMs), ptr(owner.ID)
	if final == CompressReplaced {
		it.ReplacedAt = &now
	} else {
		it.RestoredAt = &now
	}
	it.UpdatedAt = now
	if _, err := saveCompressItem(ctx, tx, it); err != nil {
		return out, err
	}
	out.Item, out.Touched, out.Changed = it, []int64{owner.ID, kept}, true
	return out, nil
}

func ptr(v int64) *int64 { return &v }

// compressRewriteTx finds the blob compression a phone's report of a rewritten path completes (ADR 0025 "Order does
// not matter"): the path's owner is the side being replaced and the reported file the other side. ok is false when
// the rewrite is an ordinary one.
func compressRewriteTx(ctx context.Context, tx *sql.Tx, owner MirrorEntry, e ReportEntry) (CompressItem, bool, bool, error) {
	// Any path of the phone: it may have moved the file since the item was queued.
	rows, err := tx.QueryContext(ctx, `SELECT `+compressItemCols+` FROM compress_items WHERE source = 'blob' AND state IN ('ready', 'replacing')
		AND (fingerprint = ?1 OR out_fingerprint = ?1) AND mirror_entry_id IN (SELECT id FROM mirror_entries WHERE physical_id = ?2) ORDER BY id DESC`,
		owner.Fingerprint, owner.PhysicalID)
	if err != nil {
		return CompressItem{}, false, false, err
	}
	list, err := scanCompressItems(rows)
	if err != nil {
		return CompressItem{}, false, false, err
	}
	for _, it := range list {
		if it.OutSize == nil {
			continue
		}
		switch {
		case (it.State == CompressReady || it.ReplaceStep == StepReswapPhone) && owner.Fingerprint == it.Fingerprint &&
			e.Fingerprint == it.OutFingerprint && e.Size == *it.OutSize && (e.SHA256 == "" || e.SHA256 == it.OutSHA256):
			return it, true, true, nil
		case it.ReplaceStep == StepRestorePhone && owner.Fingerprint == it.OutFingerprint && e.Fingerprint == it.Fingerprint &&
			e.Size == it.Size && (e.SHA256 == "" || it.SHA256 == "" || e.SHA256 == it.SHA256):
			return it, false, true, nil
		}
	}
	return CompressItem{}, false, false, nil
}

// The journal steps of a phone restore and a phone restore again (the phone writes the file; ADR 0025 "Restore
// original"), and the mark of a ready phone item whose output the phone downloaded (StepServed: it may have replaced
// its file already, so the item is no longer cancelled; Amendment (S2), "A served output").
const (
	StepServed       = "phone:served"
	StepRestorePhone = "restore:phone"
	StepReswapPhone  = "reswap:phone"
)

// --- ownership rule 5, the purge and dropped outputs ----------------------------------------------------------------

// compressHolds reports whether a blob compression in ready, replacing, replaced or restored names the content as its
// original or its output (ADR 0024 amendment, ownership rule 5).
func compressHolds(ctx context.Context, q queryer, fp, sha string) (bool, error) {
	var one int
	err := q.QueryRowContext(ctx, `SELECT 1 FROM compress_items WHERE source = 'blob' AND state IN ('ready', 'replacing', 'replaced', 'restored')
		AND (fingerprint = ?1 OR out_fingerprint = ?1 OR (?2 != '' AND (sha256 = ?2 OR out_sha256 = ?2))) LIMIT 1`, fp, sha).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// keptBy names what keeps a content: "claim" (a device asked for it), "mirror" (a phone backup entry), "record" (a live
// Reveal photo record), "compress" (another compression); none when nothing does.
func keptBy(ctx context.Context, tx *sql.Tx, fps []string, sha string) ([]string, error) {
	var out []string
	add := func(r string) {
		if !contains(out, r) {
			out = append(out, r)
		}
	}
	for _, fp := range fps {
		var one int
		err := tx.QueryRowContext(ctx, "SELECT 1 FROM original_claims WHERE fingerprint = ? LIMIT 1", fp).Scan(&one)
		switch {
		case err == nil:
			add("claim")
		case !errors.Is(err, sql.ErrNoRows):
			return nil, err
		}
		if m, err := mirrorNames(ctx, tx, fp, sha); err != nil {
			return nil, err
		} else if m {
			add("mirror")
		}
		if r, err := originalNamed(ctx, tx, fp); err != nil {
			return nil, err
		} else if r {
			add("record")
		}
		if c, err := compressHolds(ctx, tx, fp, sha); err != nil {
			return nil, err
		} else if c {
			add("compress")
		}
	}
	return out, nil
}

// releaseTx drops the rows of a stored content (every fingerprint of its SHA-256) when nothing keeps it; removed is
// true when the caller must remove the blob file.
func releaseTx(ctx context.Context, tx *sql.Tx, fp, sha string) (removed bool, kept []string, err error) {
	if sha == "" {
		return false, nil, nil
	}
	fps := []string{fp}
	rows, err := tx.QueryContext(ctx, "SELECT fingerprint FROM originals WHERE sha256 = ?", sha)
	if err != nil {
		return false, nil, err
	}
	for rows.Next() {
		var f string
		if err := rows.Scan(&f); err != nil {
			rows.Close()
			return false, nil, err
		}
		if !contains(fps, f) {
			fps = append(fps, f)
		}
	}
	rows.Close()
	if kept, err = keptBy(ctx, tx, fps, sha); err != nil || len(kept) > 0 {
		return false, kept, err
	}
	var kind string
	err = tx.QueryRowContext(ctx, "SELECT kind FROM blobs WHERE sha256 = ?", sha).Scan(&kind)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && kind != BlobOriginal) {
		return false, nil, nil
	}
	if err != nil {
		return false, nil, err
	}
	for _, q := range []string{"DELETE FROM originals WHERE sha256 = ?", "DELETE FROM blobs WHERE sha256 = ?", "DELETE FROM blob_verifications WHERE sha256 = ?"} {
		if _, err := tx.ExecContext(ctx, q, sha); err != nil {
			return false, nil, err
		}
	}
	return true, nil, nil
}

// CompressBlobPurge is what the purge of a blob compression's trashed side did.
type CompressBlobPurge struct {
	Item    CompressItem
	Links   []MirrorEntry // the kept entries removed: the caller removes their links
	Removed string        // the blob whose rows went (the caller removes its file), "" when it stays
	KeptBy  []string      // why the bytes stay: claim, mirror, record, compress
}

// PurgeCompressBlob empties the trashed side of a replaced (the original) or restored (the output) blob compression:
// its kept entries go, the item is purged, and the blob goes when nothing else keeps it (ADR 0025 "Purge").
func (s *Store) PurgeCompressBlob(ctx context.Context, id int64) (CompressBlobPurge, error) {
	var out CompressBlobPurge
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		out = CompressBlobPurge{}
		it, err := compressItemTx(ctx, tx, id)
		if err != nil {
			return err
		}
		if it.Source != "blob" || (it.State != CompressReplaced && it.State != CompressRestored) || it.MirrorEntryID == nil {
			out.Item = it
			return ErrConflict
		}
		fp, sha := it.Fingerprint, it.SHA256
		if it.State == CompressRestored {
			fp, sha = it.OutFingerprint, it.OutSHA256
		}
		if it.KeptEntryID != nil {
			// Exactly the kept entry of this item (wherever the phone had moved the file when it was swapped); gone with
			// a backup purge already.
			if out.Links, err = queryMirrorEntries(ctx, tx, `WHERE e.id = ? AND e.fingerprint = ? AND e.removed_reason = 'compressed'`,
				*it.KeptEntryID, fp); err != nil {
				return err
			}
			for _, e := range out.Links {
				if _, err := tx.ExecContext(ctx, "DELETE FROM mirror_entries WHERE id = ?", e.ID); err != nil {
					return err
				}
			}
		}
		it.State, it.PurgedAt, it.UpdatedAt = CompressPurged, &now, now
		if _, err := saveCompressItem(ctx, tx, it); err != nil {
			return err
		}
		out.Item = it
		removed, kept, err := releaseTx(ctx, tx, fp, sha)
		if err != nil {
			return err
		}
		if removed {
			out.Removed = sha
		}
		out.KeptBy = kept
		return nil
	})
	return out, err
}

// DropCompressOutput forgets the output blob of a compression that will not be used (cancelled, abandoned, failed)
// when nothing keeps it. It returns the SHA-256 whose file the caller removes ("" none).
func (s *Store) DropCompressOutput(ctx context.Context, id int64) (string, error) {
	removed := ""
	err := s.tx(ctx, func(tx *sql.Tx) error {
		removed = ""
		it, err := compressItemTx(ctx, tx, id)
		if err != nil {
			return err
		}
		if it.Source != "blob" || it.OutSHA256 == "" {
			return nil
		}
		ok, _, err := releaseTx(ctx, tx, it.OutFingerprint, it.OutSHA256)
		if ok {
			removed = it.OutSHA256
		}
		return err
	})
	return removed, err
}

// CompressBlobHidden lists the fingerprints of blobs a compression holds out of the gallery's backup items: an output
// not on the phone yet (ready), both sides while a phone swaps them (replacing), and the trashed side (the original
// once replaced, the output once restored), which is the "kept" source's.
func (s *Store) CompressBlobHidden(ctx context.Context) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT state, fingerprint, coalesce(out_fingerprint, '') FROM compress_items
		WHERE source = 'blob' AND state IN ('ready', 'replacing', 'replaced', 'restored')`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var st, fp, ofp string
		if err := rows.Scan(&st, &fp, &ofp); err != nil {
			return nil, err
		}
		switch st {
		case CompressReady, CompressRestored:
			out[ofp] = true
		case CompressReplacing:
			out[fp], out[ofp] = true, true
		case CompressReplaced:
			out[fp] = true
		}
	}
	delete(out, "")
	return out, rows.Err()
}

// CompressedMembers lists the entries compressions keep (removed_reason compressed), by root: the "kept" item sources.
func (s *Store) CompressedMembers(ctx context.Context) (map[string][]MirrorMember, error) {
	return mirrorMembers(ctx, s.db, "e.store = 'blob' AND e.removed_reason = 'compressed'")
}

// defaultTrashMs is the retention when none is given (compress.trashDays, 30 days).
const defaultTrashMs = 30 * 24 * 3600 * 1000

// compressRewrite completes the swap of a blob compression when a backup report shows the other side at its path
// before the phone's own report (ADR 0025 "Order does not matter"): nothing is uploaded and nothing is a previous
// version. It returns the entry now at the path, or nil for an ordinary rewrite.
func compressRewrite(ctx context.Context, tx *sql.Tx, had bool, owner MirrorEntry, e ReportEntry, opt ReportOptions, now int64, out *ReportOutcome,
	touch func(int64)) (*MirrorEntry, error) {
	if !had {
		return nil, nil
	}
	it, toOut, ok, err := compressRewriteTx(ctx, tx, owner, e)
	if err != nil || !ok {
		return nil, err
	}
	trash := opt.CompressTrashMs
	if trash <= 0 {
		trash = defaultTrashMs
	}
	res, err := compressSwapTx(ctx, tx, it, CompressSwapIn{ID: it.ID, ToOutput: toOut, From: []string{CompressReady, CompressReplacing},
		Phone: &PhoneFile{Fingerprint: e.Fingerprint, SHA256: e.SHA256, Size: e.Size, ModifiedAt: e.ModifiedAt}, DeviceID: opt.DeviceID,
		TrashMs: trash, OwnerID: owner.ID}, now)
	switch {
	case errors.Is(err, ErrCompressMismatch), errors.Is(err, ErrCompressPath), errors.Is(err, ErrConflict):
		return nil, nil
	case err != nil:
		return nil, err
	case !res.Changed:
		return nil, nil
	}
	for _, id := range res.Touched {
		touch(id)
	}
	out.Compressed = append(out.Compressed, res.Item)
	cur, err := entryTx(ctx, tx, res.Touched[1])
	return &cur, err
}
