package store

import (
	"context"
	"database/sql"
	"errors"
)

// AdminPasswordHash returns the stored argon2id hash, or "" before the first run's setup.
func (s *Store) AdminPasswordHash(ctx context.Context) (string, error) {
	var h string
	err := s.db.QueryRowContext(ctx, "SELECT password_hash FROM admin WHERE id = 1").Scan(&h)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return h, err
}

// ErrAdminExists is returned when the first-run setup already happened.
var ErrAdminExists = errors.New("admin password already set")

// CreateAdmin sets the first password; it fails once one exists.
func (s *Store) CreateAdmin(ctx context.Context, hash string) error {
	res, err := s.db.ExecContext(ctx, "INSERT INTO admin (id, password_hash, created_at) VALUES (1, ?, ?) ON CONFLICT (id) DO NOTHING", hash, s.nowMs())
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrAdminExists
	}
	return nil
}

// SetAdminPassword replaces the password and ends every session.
func (s *Store) SetAdminPassword(ctx context.Context, hash string) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "UPDATE admin SET password_hash = ? WHERE id = 1", hash); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, "DELETE FROM sessions")
		return err
	})
}

// CreateSession stores a session id's hash.
func (s *Store) CreateSession(ctx context.Context, idHash []byte, expiresAt int64) error {
	_, err := s.db.ExecContext(ctx, "INSERT INTO sessions (id_hash, created_at, expires_at) VALUES (?, ?, ?)", idHash, s.nowMs(), expiresAt)
	return err
}

// TouchSession checks a session and slides its expiry; false when unknown or expired.
func (s *Store) TouchSession(ctx context.Context, idHash []byte, expiresAt int64) (bool, error) {
	res, err := s.db.ExecContext(ctx, "UPDATE sessions SET expires_at = ? WHERE id_hash = ? AND expires_at > ?", expiresAt, idHash, s.nowMs())
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

// DeleteSession ends a session.
func (s *Store) DeleteSession(ctx context.Context, idHash []byte) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM sessions WHERE id_hash = ?", idHash)
	return err
}

// Sweep removes expired sessions and pairing tokens.
func (s *Store) Sweep(ctx context.Context) error {
	now := s.nowMs()
	if _, err := s.db.ExecContext(ctx, "DELETE FROM sessions WHERE expires_at <= ?", now); err != nil {
		return err
	}
	_, err := s.db.ExecContext(ctx, "DELETE FROM pairing_tokens WHERE used_at IS NULL AND expires_at <= ?", now-24*3600*1000)
	return err
}
