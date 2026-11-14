package store

import (
	"context"
	"database/sql"
	"errors"
)

// Web gallery sessions (ADR 0018): a browser signed in with the admin password. Separate from the setup page's
// sessions, ended together with them when the admin password changes.

// CreateWebSession stores a gallery session's id hash and its culling writer id.
func (s *Store) CreateWebSession(ctx context.Context, idHash []byte, deviceID string, expiresAt int64) error {
	now := s.nowMs()
	_, err := s.db.ExecContext(ctx, "INSERT INTO web_sessions (id_hash, device_id, created_at, expires_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
		idHash, deviceID, now, expiresAt, now)
	return err
}

// TouchWebSession checks a gallery session, slides its expiry and returns its writer id; ErrNotFound when unknown or
// expired.
func (s *Store) TouchWebSession(ctx context.Context, idHash []byte, expiresAt int64) (string, error) {
	var id string
	now := s.nowMs()
	err := s.db.QueryRowContext(ctx, "UPDATE web_sessions SET expires_at = ?, last_seen_at = ? WHERE id_hash = ? AND expires_at > ? RETURNING device_id",
		expiresAt, now, idHash, now).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return id, err
}

// DeleteWebSession ends a gallery session.
func (s *Store) DeleteWebSession(ctx context.Context, idHash []byte) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM web_sessions WHERE id_hash = ?", idHash)
	return err
}

// WebSessionCount counts the live gallery sessions (status page).
func (s *Store) WebSessionCount(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM web_sessions WHERE expires_at > ?", s.nowMs()).Scan(&n)
	return n, err
}

// EditedFingerprints returns the main fingerprints of the photos Reveal edited: a live photo record with a live version
// whose history has at least one step (Reveal ADR 0006). The web gallery's "edited" badge.
func (s *Store) EditedFingerprints(ctx context.Context) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT DISTINCT json_extract(p.data, '$.fingerprint') FROM records p
		JOIN records v ON v.kind = 'version' AND v.deleted = 0 AND json_extract(v.data, '$.photoId') = p.id
		WHERE p.kind = 'photo' AND p.deleted = 0 AND json_type(p.data, '$.fingerprint') = 'text'
		AND json_type(v.data, '$.history') = 'array' AND json_array_length(v.data, '$.history') > 0`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var fp string
		if err := rows.Scan(&fp); err != nil {
			return nil, err
		}
		out[fp] = true
	}
	return out, rows.Err()
}

// PreviewKey is the on-disk content key of an item's preview job.
type PreviewKey struct {
	ItemID    string
	Job       string
	Key       string // "" when none was made yet
	UpdatedAt int64
}

// PreviewKeysSince lists the preview rows of a job ("edit:" for every edited preview) changed at or after since (ms),
// oldest first: the web gallery's index keeps each item's image key so the grid builds immutable thumbnail URLs (ADR
// 0015 "?v=").
func (s *Store) PreviewKeysSince(ctx context.Context, job string, since int64) ([]PreviewKey, error) {
	q := "SELECT item_id, job, key, updated_at FROM previews WHERE job = ? AND updated_at >= ? ORDER BY updated_at"
	args := []any{job, since}
	if job == "edit:" { // every edited preview (ADR 0020)
		q = "SELECT item_id, job, key, updated_at FROM previews WHERE job >= 'edit:' AND job < 'edit;' AND updated_at >= ? ORDER BY updated_at"
		args = args[1:]
	}
	rows, err := s.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PreviewKey
	for rows.Next() {
		var p PreviewKey
		if err := rows.Scan(&p.ItemID, &p.Job, &p.Key, &p.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
