package store

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"
)

// Device is a paired device.
type Device struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Platform   string `json:"platform"`
	AppVersion string `json:"appVersion"`
	KeyID      string `json:"keyId"`
	KeyHash    []byte `json:"-"`
	CreatedAt  int64  `json:"createdAt"`
	LastSeenAt *int64 `json:"lastSeenAt"`
	LastSyncAt *int64 `json:"lastSyncAt"`
	RevokedAt  *int64 `json:"revokedAt"`
}

// ErrInvalidToken is returned for an unknown, used or expired pairing token.
var ErrInvalidToken = errors.New("invalid pairing token")

// CreatePairing stores a pairing token's hash, valid until expiresAt (ms).
func (s *Store) CreatePairing(ctx context.Context, tokenHash []byte, expiresAt int64) (string, error) {
	id := NewUUID()
	_, err := s.db.ExecContext(ctx, "INSERT INTO pairing_tokens (id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
		id, tokenHash, s.nowMs(), expiresAt)
	return id, err
}

// Pairing is the admin's view of a pairing token.
type Pairing struct {
	ID        string  `json:"id"`
	CreatedAt int64   `json:"createdAt"`
	ExpiresAt int64   `json:"expiresAt"`
	UsedAt    *int64  `json:"usedAt"`
	DeviceID  *string `json:"deviceId"`
}

// PairingByID returns a pairing token's state.
func (s *Store) PairingByID(ctx context.Context, id string) (Pairing, error) {
	var p Pairing
	err := s.db.QueryRowContext(ctx, "SELECT id, created_at, expires_at, used_at, device_id FROM pairing_tokens WHERE id = ?", id).
		Scan(&p.ID, &p.CreatedAt, &p.ExpiresAt, &p.UsedAt, &p.DeviceID)
	if errors.Is(err, sql.ErrNoRows) {
		return p, ErrNotFound
	}
	return p, err
}

// RedeemPairing checks the token (by hash, constant-time), marks it used and creates the device, in one transaction.
func (s *Store) RedeemPairing(ctx context.Context, tokenHash []byte, d Device) (Device, error) {
	now := s.nowMs()
	d.ID = NewUUID()
	d.CreatedAt = now
	err := s.tx(ctx, func(tx *sql.Tx) error {
		var id string
		var stored []byte
		var expires int64
		var used *int64
		err := tx.QueryRowContext(ctx, "SELECT id, token_hash, expires_at, used_at FROM pairing_tokens WHERE token_hash = ?", tokenHash).
			Scan(&id, &stored, &expires, &used)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrInvalidToken
		}
		if err != nil {
			return err
		}
		if subtle.ConstantTimeCompare(stored, tokenHash) != 1 || used != nil || expires <= now {
			return ErrInvalidToken
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO devices (id, name, platform, app_version, key_id, key_hash, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)`, d.ID, d.Name, d.Platform, d.AppVersion, d.KeyID, d.KeyHash, now); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE pairing_tokens SET used_at = ?, device_id = ? WHERE id = ?", now, d.ID, id)
		return err
	})
	return d, err
}

const deviceCols = "id, name, platform, app_version, key_id, key_hash, created_at, last_seen_at, last_sync_at, revoked_at"

func scanDevice(row interface{ Scan(...any) error }) (Device, error) {
	var d Device
	err := row.Scan(&d.ID, &d.Name, &d.Platform, &d.AppVersion, &d.KeyID, &d.KeyHash, &d.CreatedAt, &d.LastSeenAt, &d.LastSyncAt, &d.RevokedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return d, ErrNotFound
	}
	return d, err
}

// DeviceByKeyID finds a device by the public part of its key (revoked ones too: the caller checks).
func (s *Store) DeviceByKeyID(ctx context.Context, keyID string) (Device, error) {
	return scanDevice(s.db.QueryRowContext(ctx, "SELECT "+deviceCols+" FROM devices WHERE key_id = ?", keyID))
}

// DeviceByID finds a device.
func (s *Store) DeviceByID(ctx context.Context, id string) (Device, error) {
	return scanDevice(s.db.QueryRowContext(ctx, "SELECT "+deviceCols+" FROM devices WHERE id = ?", id))
}

// Devices lists every device, newest first.
func (s *Store) Devices(ctx context.Context) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+deviceCols+" FROM devices ORDER BY created_at DESC")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		d, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// TouchDevice records activity: seen always, synced when sync is true.
func (s *Store) TouchDevice(ctx context.Context, id string, sync bool) error {
	now := s.nowMs()
	q := "UPDATE devices SET last_seen_at = ? WHERE id = ?"
	args := []any{now, id}
	if sync {
		q = "UPDATE devices SET last_seen_at = ?, last_sync_at = ? WHERE id = ?"
		args = []any{now, now, id}
	}
	_, err := s.db.ExecContext(ctx, q, args...)
	return err
}

// RevokeDevice revokes a device's key and drops its unfinished uploads' rows (the caller removes the files).
func (s *Store) RevokeDevice(ctx context.Context, id string) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, "UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", s.nowMs(), id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return nil
	})
}
