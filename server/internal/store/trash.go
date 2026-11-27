package store

import (
	"context"
	"database/sql"
	"errors"
	"slices"
)

// The trash from the web gallery (ADR 0023 Amendment (0.14.0)): the server trash of items that are not on the NAS
// (server_trash), and the journal of the server's own moves in NAS folders (trash_moves).

// ErrTrashed: the content belongs to an item in the server trash (pinned until the purge); DELETE /v1/originals answers
// 409 with reason "trashed".
var ErrTrashed = errors.New("in the server trash")

// ServerTrash is one item in the server trash.
type ServerTrash struct {
	ItemID       string
	MainFP       string
	Fingerprints []string
	TrashedAt    int64
	PurgeAfter   int64
	TrashedBy    string
	Size         int64
	PurgedAt     *int64
}

func scanServerTrash(rows *sql.Rows) ([]ServerTrash, error) {
	defer rows.Close()
	var out []ServerTrash
	for rows.Next() {
		var r ServerTrash
		if err := rows.Scan(&r.ItemID, &r.MainFP, &r.TrashedAt, &r.PurgeAfter, &r.TrashedBy, &r.Size, &r.PurgedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

const serverTrashCols = "item_id, main_fp, trashed_at, purge_after, trashed_by, size, purged_at"

// AddServerTrash puts items into the server trash (an item already there keeps its dates).
func (s *Store) AddServerTrash(ctx context.Context, rows []ServerTrash) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		for _, r := range rows {
			if _, err := tx.ExecContext(ctx, `INSERT INTO server_trash (item_id, main_fp, trashed_at, purge_after, trashed_by, size)
				VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (item_id) DO UPDATE SET purged_at = NULL`,
				r.ItemID, r.MainFP, r.TrashedAt, r.PurgeAfter, r.TrashedBy, r.Size); err != nil {
				return err
			}
			for _, fp := range r.Fingerprints {
				if _, err := tx.ExecContext(ctx, "INSERT INTO server_trash_files (fingerprint, item_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
					fp, r.ItemID); err != nil {
					return err
				}
			}
		}
		return nil
	})
}

// RemoveServerTrash takes items out of the server trash (a restore); it reports the ids that were there.
func (s *Store) RemoveServerTrash(ctx context.Context, ids []string) ([]string, error) {
	var out []string
	err := s.tx(ctx, func(tx *sql.Tx) error {
		out = nil
		for _, id := range ids {
			res, err := tx.ExecContext(ctx, "DELETE FROM server_trash WHERE item_id = ? AND purged_at IS NULL", id)
			if err != nil {
				return err
			}
			if n, _ := res.RowsAffected(); n > 0 {
				out = append(out, id)
			}
		}
		return nil
	})
	return out, err
}

// ServerTrashRows lists the server trash (purged rows too when withPurged), oldest first.
func (s *Store) ServerTrashRows(ctx context.Context, withPurged bool) ([]ServerTrash, error) {
	q := "SELECT " + serverTrashCols + " FROM server_trash"
	if !withPurged {
		q += " WHERE purged_at IS NULL"
	}
	rows, err := s.db.QueryContext(ctx, q+" ORDER BY trashed_at, item_id")
	if err != nil {
		return nil, err
	}
	out, err := scanServerTrash(rows)
	if err != nil {
		return nil, err
	}
	for i := range out {
		if out[i].Fingerprints, err = s.serverTrashFiles(ctx, s.db, out[i].ItemID); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// ServerTrashDue lists the rows auto-empty takes now (purge_after passed, not purged yet).
func (s *Store) ServerTrashDue(ctx context.Context, now int64) ([]ServerTrash, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+serverTrashCols+" FROM server_trash WHERE purged_at IS NULL AND purge_after <= ? ORDER BY purge_after", now)
	if err != nil {
		return nil, err
	}
	return scanServerTrash(rows)
}

func (s *Store) serverTrashFiles(ctx context.Context, q rowsQueryer, id string) ([]string, error) {
	rows, err := q.QueryContext(ctx, "SELECT fingerprint FROM server_trash_files WHERE item_id = ? ORDER BY fingerprint", id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var fp string
		if err := rows.Scan(&fp); err != nil {
			return nil, err
		}
		out = append(out, fp)
	}
	return out, rows.Err()
}

// ServerTrashOf reads an item's server trash row (ErrNotFound when it is not there).
func (s *Store) ServerTrashOf(ctx context.Context, id string) (ServerTrash, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+serverTrashCols+" FROM server_trash WHERE item_id = ?", id)
	if err != nil {
		return ServerTrash{}, err
	}
	list, err := scanServerTrash(rows)
	if err != nil {
		return ServerTrash{}, err
	}
	if len(list) == 0 {
		return ServerTrash{}, ErrNotFound
	}
	list[0].Fingerprints, err = s.serverTrashFiles(ctx, s.db, id)
	return list[0], err
}

// ServerTrashed reads an item's server trash state for its rebuild: ok is false when it is not in the server trash.
func (t *ItemTx) ServerTrashed(id string) (since, purgeAfter int64, ok bool, err error) {
	err = t.tx.QueryRowContext(t.ctx, "SELECT trashed_at, purge_after FROM server_trash WHERE item_id = ?", id).Scan(&since, &purgeAfter)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, 0, false, nil
	}
	return since, purgeAfter, err == nil, err
}

// serverTrashHolds reports whether an item in the server trash names a content (ownership: pinned until the purge).
func serverTrashHolds(ctx context.Context, q queryer, fp string) (bool, error) {
	var one int
	err := q.QueryRowContext(ctx, `SELECT 1 FROM server_trash_files f JOIN server_trash t ON t.item_id = f.item_id
		WHERE f.fingerprint = ? AND t.purged_at IS NULL LIMIT 1`, fp).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// ServerTrashPurge is what emptying an item from the server trash removed.
type ServerTrashPurge struct {
	ItemID  string
	Entries int           // mirror entries removed (off every phone)
	Links   []MirrorEntry // their hard links in the backup tree (the caller removes exactly these)
	Removed []string      // blobs nothing names any more (the caller removes their files)
	KeptBy  []string      // what keeps some of the bytes: "mirror" (still on a phone), "record" (Reveal), "compress"
	Live    bool          // the item was live again (a NAS copy came back): only the row went
}

// PurgeServerTrash empties items from the server trash (the admin's Delete permanently, Empty trash and auto-empty):
// for each member file, the backup's entries no phone has any more and every device claim go, then each content that
// nothing else names (a phone that still has the file, a live Reveal photo record, a compression). The row stays,
// marked purged, so the item stays trashed until the backup pass drops its sources (it then becomes a tombstone and
// its edits go, ADR 0020); DropPurgedServerTrash removes it then. An item no longer trashed (live again) only loses
// its row.
func (s *Store) PurgeServerTrash(ctx context.Context, ids []string) ([]ServerTrashPurge, error) {
	var out []ServerTrashPurge
	err := s.tx(ctx, func(tx *sql.Tx) error {
		out = nil
		now := s.nowMs()
		for _, id := range ids {
			var purged sql.NullInt64
			err := tx.QueryRowContext(ctx, "SELECT purged_at FROM server_trash WHERE item_id = ?", id).Scan(&purged)
			if errors.Is(err, sql.ErrNoRows) || err == nil && purged.Valid {
				continue
			}
			if err != nil {
				return err
			}
			p := ServerTrashPurge{ItemID: id}
			var trashed sql.NullInt64
			var deleted bool
			err = tx.QueryRowContext(ctx, "SELECT deleted, trashed_at FROM items WHERE id = ?", id).Scan(&deleted, &trashed)
			if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			if err == nil && !deleted && !trashed.Valid {
				p.Live = true
				if _, err := tx.ExecContext(ctx, "DELETE FROM server_trash WHERE item_id = ?", id); err != nil {
					return err
				}
				out = append(out, p)
				continue
			}
			fps, err := s.serverTrashFiles(ctx, tx, id)
			if err != nil {
				return err
			}
			// Marked first: the checks below must not count this item's own pin.
			if _, err := tx.ExecContext(ctx, "UPDATE server_trash SET purged_at = ? WHERE item_id = ?", now, id); err != nil {
				return err
			}
			for _, fp := range fps {
				gone, err := queryMirrorEntries(ctx, tx, "WHERE e.fingerprint = ? AND e.on_device = 0 ORDER BY e.id", fp)
				if err != nil {
					return err
				}
				for _, e := range gone {
					if e.TreePath != "" {
						p.Links = append(p.Links, e)
					}
				}
				res, err := tx.ExecContext(ctx, "DELETE FROM mirror_entries WHERE fingerprint = ? AND on_device = 0", fp)
				if err != nil {
					return err
				}
				n, _ := res.RowsAffected()
				p.Entries += int(n)
				if _, err := tx.ExecContext(ctx, "DELETE FROM original_claims WHERE fingerprint = ?", fp); err != nil {
					return err
				}
				var sha string
				err = tx.QueryRowContext(ctx, "SELECT sha256 FROM originals WHERE fingerprint = ?", fp).Scan(&sha)
				if errors.Is(err, sql.ErrNoRows) {
					continue
				}
				if err != nil {
					return err
				}
				kept, err := keptBy(ctx, tx, []string{fp}, sha)
				if err != nil {
					return err
				}
				if held, err := serverTrashHolds(ctx, tx, fp); err != nil {
					return err
				} else if held {
					kept = append(kept, "trash")
				}
				if len(kept) > 0 {
					for _, k := range kept {
						if !slices.Contains(p.KeptBy, k) {
							p.KeptBy = append(p.KeptBy, k)
						}
					}
					continue
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM originals WHERE fingerprint = ?", fp); err != nil {
					return err
				}
				var others int
				if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM originals WHERE sha256 = ?", sha).Scan(&others); err != nil {
					return err
				}
				var kind string
				err = tx.QueryRowContext(ctx, "SELECT kind FROM blobs WHERE sha256 = ?", sha).Scan(&kind)
				if errors.Is(err, sql.ErrNoRows) || others > 0 || kind != BlobOriginal {
					continue
				}
				if err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM blobs WHERE sha256 = ?", sha); err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM blob_verifications WHERE sha256 = ?", sha); err != nil {
					return err
				}
				p.Removed = append(p.Removed, sha)
			}
			out = append(out, p)
		}
		return nil
	})
	return out, err
}

// DropPurgedServerTrash removes the purged rows whose item is gone (a tombstone) or that were purged before `before`
// (whatever stays of the item, a file back on a phone, is live again).
func (s *Store) DropPurgedServerTrash(ctx context.Context, before int64) (int, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM server_trash WHERE purged_at IS NOT NULL AND (purged_at < ?
		OR NOT EXISTS (SELECT 1 FROM items i WHERE i.id = server_trash.item_id AND i.deleted = 0))`, before)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// TrashMove is one row of the journal of the server's moves in NAS folders.
type TrashMove struct {
	ID         int64
	FolderID   string
	RelPath    string
	TrashPath  string
	State      string // moving | trashed | restoring | purging
	Size       int64
	MTime      int64 // ns
	ItemID     string
	MovedAt    int64
	PurgeAfter int64
	// Kind is MoveKindMove (the server moved the file into the trash) or MoveKindOp (a row written only while a restore
	// or a delete of a file the server did not move runs: never due for the auto-empty, forgotten when it ends).
	Kind string
}

// Kinds of a trash move row.
const (
	MoveKindMove = "move"
	MoveKindOp   = "op"
)

// States of a trash move.
const (
	MoveMoving    = "moving"
	MoveTrashed   = "trashed"
	MoveRestoring = "restoring"
	MovePurging   = "purging"
)

const trashMoveCols = "id, folder_id, rel_path, trash_path, state, size, mtime, item_id, moved_at, purge_after, kind"

func scanTrashMoves(rows *sql.Rows) ([]TrashMove, error) {
	defer rows.Close()
	var out []TrashMove
	for rows.Next() {
		var m TrashMove
		if err := rows.Scan(&m.ID, &m.FolderID, &m.RelPath, &m.TrashPath, &m.State, &m.Size, &m.MTime, &m.ItemID, &m.MovedAt, &m.PurgeAfter, &m.Kind); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// PutTrashMove writes a journal row (before its operation runs): a new one, or an existing row (same folder and trash
// path) in its new state; an existing row keeps its kind, times and purge_after (a failed restore or delete puts it
// back exactly as it was by setting its state again).
func (s *Store) PutTrashMove(ctx context.Context, m TrashMove) (int64, error) {
	if m.Kind == "" {
		m.Kind = MoveKindMove
	}
	var id int64
	err := s.db.QueryRowContext(ctx, `INSERT INTO trash_moves (folder_id, rel_path, trash_path, state, size, mtime, item_id, moved_at, purge_after, kind)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (folder_id, trash_path) DO UPDATE SET state = excluded.state, rel_path = excluded.rel_path
		RETURNING id`, m.FolderID, m.RelPath, m.TrashPath, m.State, m.Size, m.MTime, m.ItemID, m.MovedAt, m.PurgeAfter, m.Kind).Scan(&id)
	return id, err
}

// SetTrashMoveState settles a journal row.
func (s *Store) SetTrashMoveState(ctx context.Context, id int64, state string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE trash_moves SET state = ? WHERE id = ?", state, id)
	return err
}

// DeleteTrashMove forgets a journal row (the file left the trash: restored or deleted).
func (s *Store) DeleteTrashMove(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM trash_moves WHERE id = ?", id)
	return err
}

// TrashMoves lists the journal rows in the given states ("" = all).
func (s *Store) TrashMoves(ctx context.Context, states ...string) ([]TrashMove, error) {
	q := "SELECT " + trashMoveCols + " FROM trash_moves"
	var args []any
	if len(states) > 0 {
		q += " WHERE state IN (" + placeholders(len(states)) + ")"
		for _, st := range states {
			args = append(args, st)
		}
	}
	rows, err := s.db.QueryContext(ctx, q+" ORDER BY id", args...)
	if err != nil {
		return nil, err
	}
	return scanTrashMoves(rows)
}

// TrashMoveAt reads the journal row of a trashed path (ErrNotFound when the server did not move it there).
func (s *Store) TrashMoveAt(ctx context.Context, folderID, trashPath string) (TrashMove, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+trashMoveCols+" FROM trash_moves WHERE folder_id = ? AND trash_path = ?", folderID, trashPath)
	if err != nil {
		return TrashMove{}, err
	}
	list, err := scanTrashMoves(rows)
	if err != nil {
		return TrashMove{}, err
	}
	if len(list) == 0 {
		return TrashMove{}, ErrNotFound
	}
	return list[0], nil
}

// TrashMovesDue lists the trashed rows whose purge_after passed (the server's auto-empty).
func (s *Store) TrashMovesDue(ctx context.Context, now int64) ([]TrashMove, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+trashMoveCols+" FROM trash_moves WHERE state = ? AND kind = ? AND purge_after <= ? ORDER BY id", MoveTrashed, MoveKindMove, now)
	if err != nil {
		return nil, err
	}
	return scanTrashMoves(rows)
}

// NasTrashBytes sums the size of the files in a NAS folder's trashes (indexed trash rows): what emptying the trash would
// free on that folder's filesystem.
func (s *Store) NasTrashBytes(ctx context.Context, folderID string) (int64, error) {
	var n int64
	err := s.db.QueryRowContext(ctx, "SELECT coalesce(sum(size), 0) FROM nas_files WHERE folder_id = ? AND removed = 0 AND origin_path IS NOT NULL",
		folderID).Scan(&n)
	return n, err
}
