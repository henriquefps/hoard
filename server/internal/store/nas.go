package store

import (
	"context"
	"database/sql"
	"errors"
)

// NasFolder is a NAS folder the server indexes.
type NasFolder struct {
	ID        string  `json:"id"`
	Path      string  `json:"path"`
	Name      string  `json:"name"`
	Enabled   bool    `json:"enabled"`
	State     string  `json:"state"` // pending | indexing | ready | error | unavailable
	Error     *string `json:"error"`
	FileCount int     `json:"fileCount"`
	IndexedAt *int64  `json:"indexedAt"`
}

const folderCols = "id, path, name, enabled, state, error, file_count, indexed_at"

func scanFolder(row interface{ Scan(...any) error }) (NasFolder, error) {
	var f NasFolder
	err := row.Scan(&f.ID, &f.Path, &f.Name, &f.Enabled, &f.State, &f.Error, &f.FileCount, &f.IndexedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return f, ErrNotFound
	}
	return f, err
}

// NasFolders lists the folders.
func (s *Store) NasFolders(ctx context.Context) ([]NasFolder, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+folderCols+" FROM nas_folders ORDER BY name")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []NasFolder{}
	for rows.Next() {
		f, err := scanFolder(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// NasFolder returns one folder.
func (s *Store) NasFolder(ctx context.Context, id string) (NasFolder, error) {
	return scanFolder(s.db.QueryRowContext(ctx, "SELECT "+folderCols+" FROM nas_folders WHERE id = ?", id))
}

// AddNasFolder adds (or re-enables) a folder by its resolved path.
func (s *Store) AddNasFolder(ctx context.Context, path, name string) (NasFolder, error) {
	id := NewUUID()
	_, err := s.db.ExecContext(ctx, `INSERT INTO nas_folders (id, path, name, enabled, state, created_at) VALUES (?, ?, ?, 1, 'pending', ?)
		ON CONFLICT (path) DO UPDATE SET enabled = 1, state = 'pending'`, id, path, name, s.nowMs())
	if err != nil {
		return NasFolder{}, err
	}
	return scanFolder(s.db.QueryRowContext(ctx, "SELECT "+folderCols+" FROM nas_folders WHERE path = ?", path))
}

// RemoveNasFolder stops indexing a folder: its files become removed entries of the NAS feed, then the folder is
// disabled (kept, so the feed's tombstones still name it).
func (s *Store) RemoveNasFolder(ctx context.Context, id string) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, "UPDATE nas_folders SET enabled = 0, state = 'pending', file_count = 0 WHERE id = ?", id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		// Trashed originals are sent again without "trashed" (ADR 0023): the folder is gone, so is its trash.
		rows, err := tx.QueryContext(ctx, "SELECT id FROM nas_files WHERE folder_id = ? AND (removed = 0 OR trash_path IS NOT NULL)", id)
		if err != nil {
			return err
		}
		var ids []int64
		for rows.Next() {
			var fid int64
			if err := rows.Scan(&fid); err != nil {
				rows.Close()
				return err
			}
			ids = append(ids, fid)
		}
		rows.Close()
		for _, fid := range ids {
			rev, err := next(ctx, tx, "nas")
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "UPDATE nas_files SET removed = 1, trash_path = NULL, rev = ? WHERE id = ?", rev, fid); err != nil {
				return err
			}
		}
		return nil
	})
}

// SetNasFolderState records indexing progress.
func (s *Store) SetNasFolderState(ctx context.Context, id, state string, errMsg *string, indexed bool) error {
	// file_count counts photos and videos (sidecars are not shots of their own), not what is in a trash (ADR 0023).
	q := "UPDATE nas_folders SET state = ?, error = ?, file_count = (SELECT count(*) FROM nas_files WHERE folder_id = ? AND removed = 0 AND media != 'sidecar' AND origin_path IS NULL) WHERE id = ?"
	args := []any{state, errMsg, id, id}
	if indexed {
		q = "UPDATE nas_folders SET state = ?, error = ?, indexed_at = ?, file_count = (SELECT count(*) FROM nas_files WHERE folder_id = ? AND removed = 0 AND media != 'sidecar' AND origin_path IS NULL) WHERE id = ?"
		args = []any{state, errMsg, s.nowMs(), id, id}
	}
	_, err := s.db.ExecContext(ctx, q, args...)
	return err
}

// NasFileState is what the walk compares against.
type NasFileState struct {
	ID          int64
	Size        int64
	MTime       int64
	Fingerprint string
	Removed     bool
	Media       string // image | video | sidecar
	Origin      string // a trash row (ADR 0023): the folder-relative path the file came from; "" otherwise
	TrashPath   string // a removed original in a trash: its trash row's path
}

// NasFileStates loads every known file of a folder, keyed by relative path.
func (s *Store) NasFileStates(ctx context.Context, folderID string) (map[string]NasFileState, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, rel_path, size, mtime, coalesce(fingerprint, ''), removed, media, coalesce(origin_path, ''),
		coalesce(trash_path, '') FROM nas_files WHERE folder_id = ?`, folderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]NasFileState{}
	for rows.Next() {
		var st NasFileState
		var p string
		if err := rows.Scan(&st.ID, &p, &st.Size, &st.MTime, &st.Fingerprint, &st.Removed, &st.Media, &st.Origin, &st.TrashPath); err != nil {
			return nil, err
		}
		out[p] = st
	}
	return out, rows.Err()
}

// NasFileUpdate is one result of a walk.
type NasFileUpdate struct {
	RelPath     string
	Size        int64
	MTime       int64
	Fingerprint string // "" = could not be read
	Removed     bool
	Media       string // image (default) | video | sidecar
	Origin      string // a trash row: the path it came from (ADR 0023)
	TrashPath   string // Removed: the trash row the file moved to, when it is in a trash ("trashed" in the NAS feed)
}

// ApplyNasUpdates writes a batch of walk results, each with a new NAS feed revision.
func (s *Store) ApplyNasUpdates(ctx context.Context, folderID string, updates []NasFileUpdate) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		for _, u := range updates {
			rev, err := next(ctx, tx, "nas")
			if err != nil {
				return err
			}
			var fp any
			if u.Fingerprint != "" {
				fp = u.Fingerprint
			}
			mediaClass := u.Media
			if mediaClass == "" {
				mediaClass = "image"
			}
			if u.Removed {
				_, err = tx.ExecContext(ctx, "UPDATE nas_files SET removed = 1, rev = ?, trash_path = ? WHERE folder_id = ? AND rel_path = ?",
					rev, nullString(u.TrashPath), folderID, u.RelPath)
			} else {
				_, err = tx.ExecContext(ctx, `INSERT INTO nas_files (folder_id, rel_path, size, mtime, fingerprint, removed, rev, media, origin_path)
					VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
					ON CONFLICT (folder_id, rel_path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime,
						fingerprint = excluded.fingerprint, removed = 0, rev = excluded.rev, media = excluded.media,
						origin_path = excluded.origin_path, trash_path = NULL`, folderID, u.RelPath, u.Size, u.MTime, fp, rev, mediaClass, nullString(u.Origin))
			}
			if err != nil {
				return err
			}
		}
		return nil
	})
}

func nullString(v string) any {
	if v == "" {
		return nil
	}
	return v
}

// TrashMark sets the trash path of a removed original (ADR 0023): TrashPath "" = no longer in a trash (purged).
type TrashMark struct {
	RelPath   string
	TrashPath string
}

// MarkTrashed writes trash paths of removed originals. A change between "in a trash" and "not in a trash" takes a new
// NAS feed revision (the row is sent again, with or without "trashed"); another trash path of a file still in a trash
// is written without one.
func (s *Store) MarkTrashed(ctx context.Context, folderID string, marks []TrashMark) error {
	if len(marks) == 0 {
		return nil
	}
	return s.tx(ctx, func(tx *sql.Tx) error {
		for _, m := range marks {
			var cur sql.NullString
			err := tx.QueryRowContext(ctx, "SELECT trash_path FROM nas_files WHERE folder_id = ? AND rel_path = ? AND removed = 1",
				folderID, m.RelPath).Scan(&cur)
			if errors.Is(err, sql.ErrNoRows) {
				continue
			}
			if err != nil {
				return err
			}
			if cur.Valid == (m.TrashPath != "") {
				if cur.String != m.TrashPath {
					if _, err := tx.ExecContext(ctx, "UPDATE nas_files SET trash_path = ? WHERE folder_id = ? AND rel_path = ?",
						m.TrashPath, folderID, m.RelPath); err != nil {
						return err
					}
				}
				continue
			}
			rev, err := next(ctx, tx, "nas")
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "UPDATE nas_files SET trash_path = ?, rev = ? WHERE folder_id = ? AND rel_path = ?",
				nullString(m.TrashPath), rev, folderID, m.RelPath); err != nil {
				return err
			}
		}
		return nil
	})
}

// NasFile is one entry of the NAS feed.
type NasFile struct {
	Fingerprint *string `json:"fingerprint"`
	FolderID    string  `json:"folderId"`
	FolderName  string  `json:"folderName"`
	Path        string  `json:"path"`
	Size        int64   `json:"size"`
	ModifiedAt  int64   `json:"modifiedAt"`
	Removed     bool    `json:"removed"`
	Trashed     bool    `json:"trashed,omitempty"` // removed because it moved to a trash (ADR 0023, hoard-server 0.11.0)
	Rev         int64   `json:"-"`
}

// NasChanges returns NAS feed entries with rev > since. The feed lists photos only, as it always did (ADR 0013):
// videos and sidecars are indexed for items (ADR 0014) and skipped here, and so are the files in a trash (ADR 0023):
// a trashed file is its original row, removed and "trashed".
func (s *Store) NasChanges(ctx context.Context, since int64, limit int) ([]NasFile, bool, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT f.fingerprint, f.folder_id, d.name, f.rel_path, f.size, f.mtime, f.removed,
		f.removed = 1 AND f.trash_path IS NOT NULL, f.rev
		FROM nas_files f JOIN nas_folders d ON d.id = f.folder_id WHERE f.rev > ? AND f.media = 'image' AND f.origin_path IS NULL
		ORDER BY f.rev LIMIT ?`, since, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	out := []NasFile{}
	for rows.Next() {
		var f NasFile
		var mtime int64
		if err := rows.Scan(&f.Fingerprint, &f.FolderID, &f.FolderName, &f.Path, &f.Size, &mtime, &f.Removed, &f.Trashed, &f.Rev); err != nil {
			return nil, false, err
		}
		f.ModifiedAt = mtime / 1e6
		out = append(out, f)
	}
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	return out, more, nil
}
