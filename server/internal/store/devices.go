package store

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"
	"sort"
	"strings"
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
	// ADR 0017: the physical device it runs on, and what it reported about itself (nil: not reported).
	PhysicalID   string  `json:"physicalDeviceId"`
	App          *string `json:"app"`
	Manufacturer *string `json:"manufacturer"`
	Model        *string `json:"model"`
	// CullingSince is when the device declared the "culling" capability or first pushed culling (ADR 0016; nil: never).
	CullingSince *int64 `json:"-"`
	// Features are the other capabilities the device declared (sorted; "culling" is CullingSince's).
	Features []string `json:"-"`
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
		// A new pairing starts a physical device of its own, with the device's id (ADR 0017).
		d.PhysicalID = d.ID
		if _, err := tx.ExecContext(ctx, "INSERT INTO physical_devices (id, created_at) VALUES (?, ?)", d.ID, now); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO devices (id, name, platform, app_version, key_id, key_hash, created_at, physical_id,
			app, manufacturer, model) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, d.ID, d.Name, d.Platform, d.AppVersion, d.KeyID, d.KeyHash, now,
			d.PhysicalID, d.App, d.Manufacturer, d.Model); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE pairing_tokens SET used_at = ?, device_id = ? WHERE id = ?", now, d.ID, id)
		return err
	})
	return d, err
}

const deviceCols = "id, name, platform, app_version, key_id, key_hash, created_at, last_seen_at, last_sync_at, revoked_at, physical_id, app, manufacturer, model, culling_since, features"

func scanDevice(row interface{ Scan(...any) error }) (Device, error) {
	var d Device
	var physical sql.NullString
	var features string
	err := row.Scan(&d.ID, &d.Name, &d.Platform, &d.AppVersion, &d.KeyID, &d.KeyHash, &d.CreatedAt, &d.LastSeenAt, &d.LastSyncAt, &d.RevokedAt,
		&physical, &d.App, &d.Manufacturer, &d.Model, &d.CullingSince, &features)
	if features != "" {
		d.Features = strings.Split(features, ",")
	}
	d.PhysicalID = physical.String
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

// --- physical devices (ADR 0017) -------------------------------------------------------------------------------------

// ErrSameApp is returned when a physical device already has a live device of the same app.
var ErrSameApp = errors.New("the physical device already has this app")

// DeviceInfo is what a device reports about itself; nil fields are left as they are.
type DeviceInfo struct {
	App          *string
	Manufacturer *string
	Model        *string
}

// UpdateDeviceInfo stores what a device reported.
func (s *Store) UpdateDeviceInfo(ctx context.Context, id string, info DeviceInfo) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET app = coalesce(?, app), manufacturer = coalesce(?, manufacturer),
		model = coalesce(?, model) WHERE id = ?`, info.App, info.Manufacturer, info.Model, id)
	return err
}

// AddDeviceFeatures records capabilities a device declared (names [a-z0-9-], never undone, so a repeat is harmless).
func (s *Store) AddDeviceFeatures(ctx context.Context, id string, features []string) error {
	if len(features) == 0 {
		return nil
	}
	return s.tx(ctx, func(tx *sql.Tx) error {
		var cur string
		if err := tx.QueryRowContext(ctx, "SELECT features FROM devices WHERE id = ?", id).Scan(&cur); err != nil {
			return err
		}
		set := map[string]bool{}
		for _, f := range append(strings.Split(cur, ","), features...) {
			if f != "" {
				set[f] = true
			}
		}
		all := make([]string, 0, len(set))
		for f := range set {
			all = append(all, f)
		}
		sort.Strings(all)
		_, err := tx.ExecContext(ctx, "UPDATE devices SET features = ? WHERE id = ?", strings.Join(all, ","), id)
		return err
	})
}

// MarkCullingAware records that a device syncs culling per field (ADR 0016 "Reveal's records"): from now on the
// bridge ignores the culling fields of its photo records. The first time is kept; it is never undone.
func (s *Store) MarkCullingAware(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE devices SET culling_since = coalesce(culling_since, ?) WHERE id = ?", s.nowMs(), id)
	return err
}

// cullingAware reports whether a device declared the culling capability (an unknown device is not).
func cullingAware(ctx context.Context, tx *sql.Tx, deviceID string) (bool, error) {
	var since sql.NullInt64
	err := tx.QueryRowContext(ctx, "SELECT culling_since FROM devices WHERE id = ?", deviceID).Scan(&since)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return since.Valid, err
}

// PhysicalExists reports whether a physical device exists.
func (s *Store) PhysicalExists(ctx context.Context, id string) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM physical_devices WHERE id = ?", id).Scan(&n)
	return n > 0, err
}

// MoveDevice moves a device into a physical device (to == "": one of its own, with the device's id when free). The
// physical device it leaves is deleted when no device is left in it.
func (s *Store) MoveDevice(ctx context.Context, deviceID, to string) (string, error) {
	id, _, err := s.MoveDeviceMirror(ctx, deviceID, to)
	return id, err
}

// MoveDeviceMirror is MoveDevice that also reports what happened to a phone backup (ADR 0024 "Claims and moves"):
// when the device was the last live device of the physical device it leaves, that physical device's backup moves with
// it (nil when nothing moved); the caller then moves the links on disk.
func (s *Store) MoveDeviceMirror(ctx context.Context, deviceID, to string) (string, *MirrorMove, error) {
	now := s.nowMs()
	var mv *MirrorMove
	err := s.tx(ctx, func(tx *sql.Tx) error {
		mv = nil
		var from string
		var app sql.NullString
		err := tx.QueryRowContext(ctx, "SELECT physical_id, app FROM devices WHERE id = ?", deviceID).Scan(&from, &app)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		if to == from {
			return nil
		}
		if to == "" {
			var n int
			if from == deviceID {
				if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM devices WHERE physical_id = ? AND id != ?", from, deviceID).Scan(&n); err != nil {
					return err
				}
				if n == 0 {
					return nil // already alone
				}
				to = NewUUID()
			} else {
				if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM physical_devices WHERE id = ?", deviceID).Scan(&n); err != nil {
					return err
				}
				to = deviceID
				if n > 0 {
					to = NewUUID()
				}
			}
			if _, err := tx.ExecContext(ctx, "INSERT INTO physical_devices (id, created_at) VALUES (?, ?)", to, now); err != nil {
				return err
			}
		} else {
			var n int
			if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM physical_devices WHERE id = ?", to).Scan(&n); err != nil {
				return err
			}
			if n == 0 {
				return ErrNotFound
			}
			if app.Valid {
				if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM devices WHERE physical_id = ? AND app = ? AND revoked_at IS NULL AND id != ?`,
					to, app.String, deviceID).Scan(&n); err != nil {
					return err
				}
				if n > 0 {
					return ErrSameApp
				}
			}
		}
		if _, err := tx.ExecContext(ctx, "UPDATE devices SET physical_id = ? WHERE id = ?", to, deviceID); err != nil {
			return err
		}
		var live int
		if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM devices WHERE physical_id = ? AND revoked_at IS NULL", from).Scan(&live); err != nil {
			return err
		}
		if live == 0 { // its last live device left: the backup goes with it (ADR 0024)
			if mv, err = moveMirror(ctx, tx, from, to, now); err != nil {
				return err
			}
		}
		// A physical device with no device left is deleted, unless it still has a backup (ADR 0024).
		_, err = tx.ExecContext(ctx, `DELETE FROM physical_devices WHERE id = ? AND NOT EXISTS (SELECT 1 FROM devices WHERE physical_id = ?)
			AND NOT EXISTS (SELECT 1 FROM mirror_devices WHERE physical_id = ?)`, from, from, from)
		return err
	})
	if err != nil {
		return "", nil, err
	}
	d, err := s.DeviceByID(ctx, deviceID)
	return d.PhysicalID, mv, err
}
