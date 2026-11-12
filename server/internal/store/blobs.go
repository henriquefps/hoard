package store

import (
	"context"
	"database/sql"
	"errors"
)

// Blob kinds.
const (
	BlobOriginal = "original"
	BlobPreview  = "preview"
)

// Blob is a stored blob's metadata.
type Blob struct {
	SHA256 string
	Size   int64
	Kind   string
}

// BlobBySHA returns a blob's metadata.
func (s *Store) BlobBySHA(ctx context.Context, sha string) (Blob, error) {
	var b Blob
	err := s.db.QueryRowContext(ctx, "SELECT sha256, size, kind FROM blobs WHERE sha256 = ?", sha).Scan(&b.SHA256, &b.Size, &b.Kind)
	if errors.Is(err, sql.ErrNoRows) {
		return b, ErrNotFound
	}
	return b, err
}

// AddBlob records a stored blob (idempotent) and, for an original, its fingerprint.
func (s *Store) AddBlob(ctx context.Context, b Blob, fingerprint, deviceID string) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "INSERT INTO blobs (sha256, size, kind, created_at, device_id) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
			b.SHA256, b.Size, b.Kind, s.nowMs(), deviceID); err != nil {
			return err
		}
		if fingerprint == "" {
			return nil
		}
		_, err := tx.ExecContext(ctx, "INSERT INTO originals (fingerprint, sha256) VALUES (?, ?) ON CONFLICT DO NOTHING", fingerprint, b.SHA256)
		return err
	})
}

// BlobCount is the number of stored blobs.
func (s *Store) BlobCount(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM blobs").Scan(&n)
	return n, err
}

// BlobBytes is the total size of stored blobs per kind.
func (s *Store) BlobBytes(ctx context.Context) (map[string]int64, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT kind, count(*), coalesce(sum(size), 0) FROM blobs GROUP BY kind")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int64{}
	for rows.Next() {
		var k string
		var n, b int64
		if err := rows.Scan(&k, &n, &b); err != nil {
			return nil, err
		}
		out[k+"Count"] = n
		out[k+"Bytes"] = b
	}
	return out, rows.Err()
}

// Upload is an unfinished chunked upload.
type Upload struct {
	ID          string
	DeviceID    string
	SHA256      string
	Size        int64
	Kind        string
	Fingerprint string
	Offset      int64
	UpdatedAt   int64
}

const uploadCols = `id, device_id, sha256, size, kind, coalesce(fingerprint, ''), "offset", updated_at`

func scanUpload(row interface{ Scan(...any) error }) (Upload, error) {
	var u Upload
	err := row.Scan(&u.ID, &u.DeviceID, &u.SHA256, &u.Size, &u.Kind, &u.Fingerprint, &u.Offset, &u.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return u, ErrNotFound
	}
	return u, err
}

// UploadByBlob finds a device's unfinished upload of a blob.
func (s *Store) UploadByBlob(ctx context.Context, deviceID, sha string) (Upload, error) {
	return scanUpload(s.db.QueryRowContext(ctx, "SELECT "+uploadCols+" FROM uploads WHERE device_id = ? AND sha256 = ?", deviceID, sha))
}

// UploadByID finds an upload of a device.
func (s *Store) UploadByID(ctx context.Context, deviceID, id string) (Upload, error) {
	return scanUpload(s.db.QueryRowContext(ctx, "SELECT "+uploadCols+" FROM uploads WHERE device_id = ? AND id = ?", deviceID, id))
}

// UploadCount is a device's number of unfinished uploads.
func (s *Store) UploadCount(ctx context.Context, deviceID string) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM uploads WHERE device_id = ?", deviceID).Scan(&n)
	return n, err
}

// CreateUpload stores a new upload.
func (s *Store) CreateUpload(ctx context.Context, u Upload) (Upload, error) {
	u.ID = NewUUID()
	now := s.nowMs()
	u.UpdatedAt = now
	var fp any
	if u.Fingerprint != "" {
		fp = u.Fingerprint
	}
	_, err := s.db.ExecContext(ctx, `INSERT INTO uploads (id, device_id, sha256, size, kind, fingerprint, "offset", created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)`, u.ID, u.DeviceID, u.SHA256, u.Size, u.Kind, fp, now, now)
	return u, err
}

// SetUploadOffset stores how many bytes of an upload are on disk.
func (s *Store) SetUploadOffset(ctx context.Context, id string, offset int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE uploads SET "offset" = ?, updated_at = ? WHERE id = ?`, offset, s.nowMs(), id)
	return err
}

// DeleteUpload removes an upload's row.
func (s *Store) DeleteUpload(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM uploads WHERE id = ?", id)
	return err
}

// StaleUploads lists uploads not touched since before (ms), and uploads of revoked devices.
func (s *Store) StaleUploads(ctx context.Context, before int64) ([]Upload, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+uploadCols+` FROM uploads WHERE updated_at < ?
		OR device_id IN (SELECT id FROM devices WHERE revoked_at IS NOT NULL)`, before)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Upload
	for rows.Next() {
		u, err := scanUpload(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// Original is where the server can read a file with a fingerprint.
type Original struct {
	Fingerprint string
	Size        int64
	Source      string // "nas" | "backup"
	SHA256      string // backup only
	FolderID    string // nas only
	FolderPath  string // nas only
	RelPath     string // nas only
	ModTime     int64  // nas only (ns)
}

// LookupOriginal finds a file by fingerprint: an indexed NAS file first (no copy needed), then an uploaded original.
func (s *Store) LookupOriginal(ctx context.Context, fp string) (Original, error) {
	o := Original{Fingerprint: fp, Source: "nas"}
	err := s.db.QueryRowContext(ctx, `SELECT f.size, f.folder_id, d.path, f.rel_path, f.mtime FROM nas_files f
		JOIN nas_folders d ON d.id = f.folder_id
		WHERE f.fingerprint = ? AND f.removed = 0 AND d.enabled = 1 ORDER BY f.id LIMIT 1`, fp).
		Scan(&o.Size, &o.FolderID, &o.FolderPath, &o.RelPath, &o.ModTime)
	if err == nil {
		return o, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return o, err
	}
	o = Original{Fingerprint: fp, Source: "backup"}
	err = s.db.QueryRowContext(ctx, `SELECT b.sha256, b.size FROM originals o JOIN blobs b ON b.sha256 = o.sha256 WHERE o.fingerprint = ?`, fp).
		Scan(&o.SHA256, &o.Size)
	if errors.Is(err, sql.ErrNoRows) {
		return o, ErrNotFound
	}
	return o, err
}
