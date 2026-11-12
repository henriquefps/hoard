package store

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

// Record kinds (ADR 0017 "Records").
const (
	KindPhoto   = "photo"
	KindVersion = "version"
	KindPreset  = "preset"
	KindSetting = "setting"
)

// Record is one synced row at its latest state.
type Record struct {
	Kind       string          `json:"kind"`
	ID         string          `json:"id"`
	Rev        int64           `json:"rev"`
	Deleted    bool            `json:"deleted"`
	ModifiedAt int64           `json:"modifiedAt"`
	DeviceID   string          `json:"deviceId"`
	CopyOf     *string         `json:"copyOf,omitempty"`
	Data       json.RawMessage `json:"data"`
}

// Change is one write pushed by a device (already validated; Data compacted, nil for a delete).
type Change struct {
	Kind       string
	ID         string
	BaseRev    int64
	ModifiedAt int64
	Deleted    bool
	Data       json.RawMessage
}

// Result is the outcome of one change.
type Result struct {
	Kind   string `json:"kind"`
	ID     string `json:"id"`
	Status string `json:"status"` // applied | conflict
	Rev    int64  `json:"rev"`
	Winner string `json:"winner,omitempty"` // incoming | server
	CopyID string `json:"copyId,omitempty"`
}

type current struct {
	rev        int64
	deleted    bool
	modifiedAt int64
	deviceID   string
	copyOf     *string
	data       []byte
}

// Push applies changes from deviceID in order, in one transaction, with the conflict rule of ADR 0017.
func (s *Store) Push(ctx context.Context, deviceID string, changes []Change) ([]Result, error) {
	results := make([]Result, 0, len(changes))
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		results = results[:0]
		for _, c := range changes {
			r, err := s.apply(ctx, tx, deviceID, c, now)
			if err != nil {
				return err
			}
			results = append(results, r)
		}
		return nil
	})
	return results, err
}

func dataOrNil(c Change) any {
	if c.Deleted {
		return nil
	}
	return string(c.Data)
}

func (s *Store) apply(ctx context.Context, tx *sql.Tx, deviceID string, c Change, now int64) (Result, error) {
	res := Result{Kind: c.Kind, ID: c.ID}
	var cur current
	var data sql.NullString
	err := tx.QueryRowContext(ctx, "SELECT rev, deleted, modified_at, device_id, copy_of, data FROM records WHERE kind = ? AND id = ?", c.Kind, c.ID).
		Scan(&cur.rev, &cur.deleted, &cur.modifiedAt, &cur.deviceID, &cur.copyOf, &data)
	exists := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return res, err
	}
	if data.Valid {
		cur.data = []byte(data.String)
	}

	write := func() (int64, error) {
		rev, err := next(ctx, tx, "records")
		if err != nil {
			return 0, err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO records (kind, id, rev, deleted, modified_at, device_id, data, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (kind, id) DO UPDATE SET rev = excluded.rev, deleted = excluded.deleted, modified_at = excluded.modified_at,
				device_id = excluded.device_id, data = excluded.data, updated_at = excluded.updated_at`,
			c.Kind, c.ID, rev, c.Deleted, c.ModifiedAt, deviceID, dataOrNil(c), now)
		return rev, err
	}

	if !exists {
		rev, err := write()
		res.Status, res.Rev = "applied", rev
		return res, err
	}
	if cur.deleted == c.Deleted && cur.modifiedAt == c.ModifiedAt && cur.deviceID == deviceID && bytes.Equal(cur.data, c.Data) {
		res.Status, res.Rev = "applied", cur.rev // a retried push: nothing new
		return res, nil
	}
	if c.BaseRev == cur.rev {
		rev, err := write()
		res.Status, res.Rev = "applied", rev
		return res, err
	}

	// Conflict: last write wins on modifiedAt, then device id.
	incomingWins := c.ModifiedAt > cur.modifiedAt || (c.ModifiedAt == cur.modifiedAt && deviceID > cur.deviceID)
	res.Status = "conflict"
	if incomingWins {
		res.Winner = "incoming"
		if c.Kind == KindVersion && !cur.deleted {
			id, err := s.copy(ctx, tx, c.ID, cur.deviceID, cur.modifiedAt, cur.data, now)
			if err != nil {
				return res, err
			}
			res.CopyID = id
		}
		rev, err := write()
		res.Rev = rev
		return res, err
	}
	res.Winner = "server"
	res.Rev = cur.rev
	if c.Kind == KindVersion && !c.Deleted {
		id, err := s.copy(ctx, tx, c.ID, deviceID, c.ModifiedAt, c.Data, now)
		if err != nil {
			return res, err
		}
		res.CopyID = id
	}
	return res, nil
}

// copy keeps a losing version state as a new version record; a retried push reuses the copy it made before.
func (s *Store) copy(ctx context.Context, tx *sql.Tx, of, deviceID string, modifiedAt int64, data []byte, now int64) (string, error) {
	var existing string
	err := tx.QueryRowContext(ctx, `SELECT id FROM records WHERE kind = 'version' AND copy_of = ? AND device_id = ? AND modified_at = ? AND data = ?`,
		of, deviceID, modifiedAt, string(data)).Scan(&existing)
	if err == nil {
		return existing, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	rev, err := next(ctx, tx, "records")
	if err != nil {
		return "", err
	}
	id := NewUUID()
	_, err = tx.ExecContext(ctx, `INSERT INTO records (kind, id, rev, deleted, modified_at, device_id, copy_of, data, updated_at)
		VALUES ('version', ?, ?, 0, ?, ?, ?, ?, ?)`, id, rev, modifiedAt, deviceID, of, string(data), now)
	return id, err
}

// Changes returns up to limit records with rev > since, oldest first, and whether more follow.
func (s *Store) Changes(ctx context.Context, since int64, limit int) ([]Record, bool, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT kind, id, rev, deleted, modified_at, device_id, copy_of, data
		FROM records WHERE rev > ? ORDER BY rev LIMIT ?`, since, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	out := []Record{}
	for rows.Next() {
		var r Record
		var data sql.NullString
		if err := rows.Scan(&r.Kind, &r.ID, &r.Rev, &r.Deleted, &r.ModifiedAt, &r.DeviceID, &r.CopyOf, &data); err != nil {
			return nil, false, err
		}
		if data.Valid {
			r.Data = json.RawMessage(data.String)
		} else {
			r.Data = json.RawMessage("null")
		}
		out = append(out, r)
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

// HeadRev is the newest record revision (0 when empty).
func (s *Store) HeadRev(ctx context.Context) (int64, error) {
	var v int64
	err := s.db.QueryRowContext(ctx, "SELECT value FROM counters WHERE name = 'records'").Scan(&v)
	return v, err
}
