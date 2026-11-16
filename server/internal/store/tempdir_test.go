package store

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// TestMigrationsNeedNoTempDir reproduces hoard-server 0.11.0's crash loop: migration 0009 rebuilds the items indexes,
// SQLite sorts the rows with a temp file, and in a read-only container there is no temp directory
// ("disk I/O error (6410)", SQLITE_IOERR_GETTEMPPATH). The server keeps temp tables and sorts in memory.
//
// A test cannot take /tmp away (SQLite falls back from TMPDIR to /var/tmp, /tmp and the working directory), so this
// pins the setting that removes the need and runs the migration with a broken TMPDIR; the real thing, a read-only
// container, is checked with the image: HOARD_SCHEMA8_OUT=/path/db.sqlite go test ./internal/store -run NeedNoTempDir
// writes the schema 8 database, and `docker run --read-only -v <dir>:/data <image>` must start on it.
func TestMigrationsNeedNoTempDir(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "db.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	// A few thousand rows keep the test fast under -race; the rebuild runs the same statements as on a big library.
	if _, err := s.db.ExecContext(ctx, `
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 30000)
INSERT INTO items (id, main_fp, rev, deleted, kind, sort_time, data, created_at, updated_at)
SELECT 'id-' || i, 'fp-' || i || '-' || hex(randomblob(8)), i, 0, 'photo', abs(random()) % 1000000000, hex(randomblob(48)), 0, 0 FROM n`); err != nil {
		t.Fatal(err)
	}
	// Put the database back to schema 8 (the indexes of 0003, none of 0009's columns).
	for _, q := range []string{
		"DROP INDEX nas_files_origin",
		"ALTER TABLE nas_files DROP COLUMN origin_path",
		"ALTER TABLE nas_files DROP COLUMN trash_path",
		"DROP INDEX items_trashed_time",
		"DROP INDEX items_time",
		"DROP INDEX items_kind_time",
		"ALTER TABLE items DROP COLUMN trashed_at",
		"CREATE INDEX items_time ON items (sort_time, id) WHERE deleted = 0",
		"CREATE INDEX items_kind_time ON items (kind, sort_time, id) WHERE deleted = 0",
		"PRAGMA user_version = 8",
	} {
		if _, err := s.db.ExecContext(ctx, q); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	s.Close()
	if out := os.Getenv("HOARD_SCHEMA8_OUT"); out != "" { // for the read-only container check (README, upgrade notes)
		if err := exec.Command("cp", path, out).Run(); err != nil {
			t.Fatal(err)
		}
		t.Skip("schema 8 database written to " + out)
	}

	// No usable temp directory, as in a container with read_only: true and no tmpfs.
	missing := filepath.Join(t.TempDir(), "does", "not", "exist")
	t.Setenv("TMPDIR", missing)
	t.Setenv("SQLITE_TMPDIR", missing)
	s, err = Open(path)
	if err != nil {
		t.Fatalf("migrating with no temp directory: %v", err)
	}
	defer s.Close()
	if v, err := s.SchemaVersion(ctx); err != nil || v != 9 {
		t.Fatalf("schema %d %v", v, err)
	}
	var mode int
	if err := s.db.QueryRowContext(ctx, "PRAGMA temp_store").Scan(&mode); err != nil || mode != 2 {
		t.Fatalf("temp_store %d %v, want 2 (MEMORY)", mode, err)
	}
}
