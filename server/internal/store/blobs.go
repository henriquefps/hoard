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

// AddBlob records a stored blob (idempotent) and, for an original, its fingerprint and deviceID's claim on it (ADR
// 0024 "Ownership").
func (s *Store) AddBlob(ctx context.Context, b Blob, fingerprint, deviceID string) error {
	return s.AddBlobClaim(ctx, b, fingerprint, deviceID, true)
}

// AddBlobClaim is AddBlob with the claim optional: an upload made with "claim": false (the mirror's) adds none.
func (s *Store) AddBlobClaim(ctx context.Context, b Blob, fingerprint, deviceID string, claim bool) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "INSERT INTO blobs (sha256, size, kind, created_at, device_id) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
			b.SHA256, b.Size, b.Kind, s.nowMs(), deviceID); err != nil {
			return err
		}
		if fingerprint == "" {
			return nil
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO originals (fingerprint, sha256) VALUES (?, ?) ON CONFLICT DO NOTHING", fingerprint, b.SHA256); err != nil {
			return err
		}
		if !claim || deviceID == "" {
			return nil
		}
		_, err := tx.ExecContext(ctx, "INSERT INTO original_claims (fingerprint, device_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
			fingerprint, deviceID, s.nowMs())
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

// DeleteUpload removes an upload's row (and its "claim": false mark, ADR 0024).
func (s *Store) DeleteUpload(ctx context.Context, id string) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "DELETE FROM uploads WHERE id = ?", id); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, "DELETE FROM upload_unclaimed WHERE upload_id = ?", id)
		return err
	})
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

// LookupOriginal finds a file by fingerprint: an indexed NAS file first (no copy needed; one in a trash too, live
// copies first, ADR 0023), then an uploaded original.
func (s *Store) LookupOriginal(ctx context.Context, fp string) (Original, error) {
	o := Original{Fingerprint: fp, Source: "nas"}
	err := s.db.QueryRowContext(ctx, `SELECT f.size, f.folder_id, d.path, f.rel_path, f.mtime FROM nas_files f
		JOIN nas_folders d ON d.id = f.folder_id
		WHERE f.fingerprint = ? AND f.removed = 0 AND d.enabled = 1 ORDER BY f.origin_path IS NOT NULL, f.id LIMIT 1`, fp).
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

// Reasons a device may not drop a blob.
var (
	// ErrNotPreview: the blob is an original (a backup is never dropped by a device).
	ErrNotPreview = errors.New("not a preview")
	// ErrNotUploader: another device uploaded the preview.
	ErrNotUploader = errors.New("uploaded by another device")
	// ErrReferenced: a live photo record still names the preview.
	ErrReferenced = errors.New("still referenced")
)

// PreviewReferenced reports whether a live (not deleted) photo record names the blob as its smart preview.
func (s *Store) PreviewReferenced(ctx context.Context, sha string) (bool, error) {
	return previewReferenced(ctx, s.db, sha)
}

type queryer interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func previewReferenced(ctx context.Context, q queryer, sha string) (bool, error) {
	var one int
	err := q.QueryRowContext(ctx, `SELECT 1 FROM records WHERE kind = 'photo' AND deleted = 0 AND data IS NOT NULL
		AND json_extract(data, '$.preview.sha256') = ? LIMIT 1`, sha).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// DropPreview forgets a smart preview blob that deviceID uploaded and no live photo record names (ADR 0013 "Dropping
// a smart preview"). The check and the delete are one transaction, so a record written meanwhile is seen. The caller
// removes the file afterwards.
func (s *Store) DropPreview(ctx context.Context, sha, deviceID string) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		var kind string
		var owner sql.NullString
		err := tx.QueryRowContext(ctx, "SELECT kind, device_id FROM blobs WHERE sha256 = ?", sha).Scan(&kind, &owner)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		if kind != BlobPreview {
			return ErrNotPreview
		}
		if !owner.Valid || owner.String != deviceID {
			return ErrNotUploader
		}
		referenced, err := previewReferenced(ctx, tx, sha)
		if err != nil {
			return err
		}
		if referenced {
			return ErrReferenced
		}
		_, err = tx.ExecContext(ctx, "DELETE FROM blobs WHERE sha256 = ?", sha)
		return err
	})
}

// ErrNasOnly: the fingerprint is an indexed NAS file, not an uploaded original (the server never deletes NAS files).
var ErrNasOnly = errors.New("only a NAS file")

// originalNamed reports whether a live photo record names fp (its main file or any file of the shot). A fingerprint
// is `v1:` + 64 hex, so finding its text in the record's JSON is exact.
func originalNamed(ctx context.Context, q queryer, fp string) (bool, error) {
	var one int
	err := q.QueryRowContext(ctx, `SELECT 1 FROM records WHERE kind = 'photo' AND deleted = 0 AND data IS NOT NULL
		AND instr(data, ?) > 0 LIMIT 1`, `"`+fp+`"`).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// DeleteOriginal forgets the uploaded original of fp once nothing else needs it (ADR 0013 "Deleting an original",
// ADR 0024 "Ownership"): the user deleted the photo from the server. A live photo record that names it keeps it
// (ErrReferenced); a phone backup entry that names it keeps it too (ErrMirror), and then only deviceID's claim on it is
// released. Otherwise every claim and the originals row go. The checks and the delete are one transaction. It returns
// the blob's sha256 when its row went too (no other fingerprint or mirror entry uses the bytes): the caller removes the
// file.
func (s *Store) DeleteOriginal(ctx context.Context, fp, deviceID string) (string, error) {
	var removed string
	err := s.tx(ctx, func(tx *sql.Tx) error {
		removed = ""
		var sha string
		err := tx.QueryRowContext(ctx, "SELECT sha256 FROM originals WHERE fingerprint = ?", fp).Scan(&sha)
		if errors.Is(err, sql.ErrNoRows) {
			var one int
			nasErr := tx.QueryRowContext(ctx, `SELECT 1 FROM nas_files f JOIN nas_folders d ON d.id = f.folder_id
				WHERE f.fingerprint = ? AND f.removed = 0 AND d.enabled = 1 LIMIT 1`, fp).Scan(&one)
			if nasErr == nil {
				return ErrNasOnly
			}
			if !errors.Is(nasErr, sql.ErrNoRows) {
				return nasErr
			}
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		named, err := originalNamed(ctx, tx, fp)
		if err != nil {
			return err
		}
		if named {
			return ErrReferenced
		}
		if held, err := compressHolds(ctx, tx, fp, sha); err != nil {
			return err
		} else if held {
			return ErrCompressed // ADR 0025 rule 5: until the purge; the caller's claim is released below
		}
		if trashed, err := serverTrashHolds(ctx, tx, fp); err != nil {
			return err
		} else if trashed {
			return ErrTrashed // pinned in the server trash until its purge; the caller's claim is released below
		}
		mirrored, err := mirrorNames(ctx, tx, fp, sha)
		if err != nil {
			return err
		}
		if mirrored {
			return ErrMirror // nothing is deleted; the caller's claim is released below
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM original_claims WHERE fingerprint = ?", fp); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM originals WHERE fingerprint = ?", fp); err != nil {
			return err
		}
		var others int
		if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM originals WHERE sha256 = ?", sha).Scan(&others); err != nil {
			return err
		}
		if others > 0 {
			return nil
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM blobs WHERE sha256 = ?", sha); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM blob_verifications WHERE sha256 = ?", sha); err != nil {
			return err
		}
		removed = sha
		return nil
	})
	if errors.Is(err, ErrMirror) || errors.Is(err, ErrCompressed) || errors.Is(err, ErrTrashed) {
		// The device's claim goes (so the bytes go when the mirror no longer needs them).
		if _, cerr := s.db.ExecContext(ctx, "DELETE FROM original_claims WHERE fingerprint = ? AND device_id = ?", fp, deviceID); cerr != nil {
			return "", cerr
		}
	}
	return removed, err
}

// RecordNames reports whether a live Reveal photo record names a fingerprint (Reveal's library has the photo).
func (s *Store) RecordNames(ctx context.Context, fp string) (bool, error) {
	return originalNamed(ctx, s.db, fp)
}

// UploadsRemaining sums what the unfinished uploads updated since `since` (ms) still have to send, except one: the
// space they will take on the backup volume (hoard-server 0.14.0, the 507 storage_full check).
func (s *Store) UploadsRemaining(ctx context.Context, since int64, except string) (int64, error) {
	var n int64
	err := s.db.QueryRowContext(ctx, `SELECT coalesce(sum(size - "offset"), 0) FROM uploads WHERE updated_at >= ? AND id != ?`, since, except).Scan(&n)
	return n, err
}
