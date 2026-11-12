// Package store is reveal-sync's metadata database: SQLite (pure Go, WAL) with numbered, embedded migrations.
package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

//go:embed migrations/*.sql
var migrations embed.FS

// ErrNotFound is returned when a row does not exist.
var ErrNotFound = errors.New("not found")

// Store wraps the database.
type Store struct {
	db  *sql.DB
	Now func() time.Time
}

// Open opens (creating it with mode 0600 when missing) and migrates the database at path.
func Open(path string) (*Store, error) {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, err
	}
	f.Close()
	q := url.Values{}
	for _, p := range []string{"busy_timeout(10000)", "journal_mode(WAL)", "foreign_keys(1)", "synchronous(FULL)"} {
		q.Add("_pragma", p)
	}
	q.Set("_txlock", "immediate")
	db, err := sql.Open("sqlite", "file:"+path+"?"+q.Encode())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(8)
	s := &Store{db: db, Now: time.Now}
	if err := s.migrate(context.Background()); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return s, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.db.Close() }

func (s *Store) nowMs() int64 { return s.Now().UnixMilli() }

func (s *Store) migrate(ctx context.Context) error {
	entries, err := fs.ReadDir(migrations, "migrations")
	if err != nil {
		return err
	}
	type mig struct {
		n    int
		name string
	}
	var list []mig
	for _, e := range entries {
		n, err := strconv.Atoi(strings.SplitN(e.Name(), "_", 2)[0])
		if err != nil {
			return fmt.Errorf("bad migration name %s", e.Name())
		}
		list = append(list, mig{n, e.Name()})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].n < list[j].n })
	var current int
	if err := s.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&current); err != nil {
		return err
	}
	if len(list) > 0 && current > list[len(list)-1].n {
		return fmt.Errorf("database schema %d is newer than this server (%d): use a newer reveal-sync", current, list[len(list)-1].n)
	}
	for _, m := range list {
		if m.n <= current {
			continue
		}
		body, err := migrations.ReadFile("migrations/" + m.name)
		if err != nil {
			return err
		}
		err = s.tx(ctx, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, string(body)); err != nil {
				return fmt.Errorf("%s: %w", m.name, err)
			}
			_, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", m.n))
			return err
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// SchemaVersion returns PRAGMA user_version.
func (s *Store) SchemaVersion(ctx context.Context) (int, error) {
	var v int
	err := s.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&v)
	return v, err
}

func (s *Store) tx(ctx context.Context, fn func(*sql.Tx) error) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		_ = tx.Rollback()
		return err
	}
	return tx.Commit()
}

func next(ctx context.Context, tx *sql.Tx, counter string) (int64, error) {
	var v int64
	err := tx.QueryRowContext(ctx, "UPDATE counters SET value = value + 1 WHERE name = ? RETURNING value", counter).Scan(&v)
	return v, err
}

// NewUUID returns a random version 4 UUID, lower-case with dashes.
func NewUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// Setting reads a setting; ok is false when it is not set.
func (s *Store) Setting(ctx context.Context, key string) (value string, ok bool, err error) {
	err = s.db.QueryRowContext(ctx, "SELECT value FROM settings WHERE key = ?", key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	return value, err == nil, err
}

// SetSetting writes a setting.
func (s *Store) SetSetting(ctx context.Context, key, value string) error {
	_, err := s.db.ExecContext(ctx,
		"INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value)
	return err
}

// EnsureSetting stores value under key unless the key exists, and returns the stored value.
func (s *Store) EnsureSetting(ctx context.Context, key, value string) (string, error) {
	if _, err := s.db.ExecContext(ctx, "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING", key, value); err != nil {
		return "", err
	}
	v, _, err := s.Setting(ctx, key)
	return v, err
}

// Counts are the health endpoint's numbers.
type Counts struct {
	Devices        int `json:"devices"`
	Photos         int `json:"photos"`
	Versions       int `json:"versions"`
	Blobs          int `json:"blobs"`
	NasFiles       int `json:"nasFiles"`
	PendingUploads int `json:"pendingUploads"`
}

// Counts returns row counts (live records only).
func (s *Store) Counts(ctx context.Context) (Counts, error) {
	var c Counts
	err := s.db.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM devices WHERE revoked_at IS NULL),
		(SELECT count(*) FROM records WHERE kind = 'photo' AND deleted = 0),
		(SELECT count(*) FROM records WHERE kind = 'version' AND deleted = 0),
		(SELECT count(*) FROM blobs),
		(SELECT count(*) FROM nas_files WHERE removed = 0 AND fingerprint IS NOT NULL),
		(SELECT count(*) FROM uploads)`).Scan(&c.Devices, &c.Photos, &c.Versions, &c.Blobs, &c.NasFiles, &c.PendingUploads)
	return c, err
}

// Counter reads a revision counter ("records" or "nas").
func (s *Store) Counter(ctx context.Context, name string) (int64, error) {
	var v int64
	err := s.db.QueryRowContext(ctx, "SELECT value FROM counters WHERE name = ?", name).Scan(&v)
	return v, err
}
