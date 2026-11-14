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
		rows, err := tx.QueryContext(ctx, "SELECT id FROM nas_files WHERE folder_id = ? AND removed = 0", id)
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
			if _, err := tx.ExecContext(ctx, "UPDATE nas_files SET removed = 1, rev = ? WHERE id = ?", rev, fid); err != nil {
				return err
			}
		}
		return nil
	})
}

// SetNasFolderState records indexing progress.
func (s *Store) SetNasFolderState(ctx context.Context, id, state string, errMsg *string, indexed bool) error {
	q := "UPDATE nas_folders SET state = ?, error = ?, file_count = (SELECT count(*) FROM nas_files WHERE folder_id = ? AND removed = 0) WHERE id = ?"
	args := []any{state, errMsg, id, id}
	if indexed {
		q = "UPDATE nas_folders SET state = ?, error = ?, indexed_at = ?, file_count = (SELECT count(*) FROM nas_files WHERE folder_id = ? AND removed = 0) WHERE id = ?"
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
}

// NasFileStates loads every known file of a folder, keyed by relative path.
func (s *Store) NasFileStates(ctx context.Context, folderID string) (map[string]NasFileState, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT id, rel_path, size, mtime, coalesce(fingerprint, ''), removed FROM nas_files WHERE folder_id = ?", folderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]NasFileState{}
	for rows.Next() {
		var st NasFileState
		var p string
		if err := rows.Scan(&st.ID, &p, &st.Size, &st.MTime, &st.Fingerprint, &st.Removed); err != nil {
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
			if u.Removed {
				_, err = tx.ExecContext(ctx, "UPDATE nas_files SET removed = 1, rev = ? WHERE folder_id = ? AND rel_path = ?", rev, folderID, u.RelPath)
			} else {
				_, err = tx.ExecContext(ctx, `INSERT INTO nas_files (folder_id, rel_path, size, mtime, fingerprint, removed, rev) VALUES (?, ?, ?, ?, ?, 0, ?)
					ON CONFLICT (folder_id, rel_path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime,
						fingerprint = excluded.fingerprint, removed = 0, rev = excluded.rev`, folderID, u.RelPath, u.Size, u.MTime, fp, rev)
			}
			if err != nil {
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
	Rev         int64   `json:"-"`
}

// NasChanges returns NAS feed entries with rev > since.
func (s *Store) NasChanges(ctx context.Context, since int64, limit int) ([]NasFile, bool, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT f.fingerprint, f.folder_id, d.name, f.rel_path, f.size, f.mtime, f.removed, f.rev
		FROM nas_files f JOIN nas_folders d ON d.id = f.folder_id WHERE f.rev > ? ORDER BY f.rev LIMIT ?`, since, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	out := []NasFile{}
	for rows.Next() {
		var f NasFile
		var mtime int64
		if err := rows.Scan(&f.Fingerprint, &f.FolderID, &f.FolderName, &f.Path, &f.Size, &mtime, &f.Removed, &f.Rev); err != nil {
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
