package store

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMigrationsAreIdempotentAndRefuseNewerSchemas(t *testing.T) {
	path := filepath.Join(t.TempDir(), "db.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	v, err := s.SchemaVersion(ctx)
	if err != nil || v != 2 {
		t.Fatalf("schema %d %v", v, err)
	}
	var mode string
	if err := s.db.QueryRowContext(ctx, "PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("journal mode %q %v", mode, err)
	}
	if err := s.SetSetting(ctx, "k", "v"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.ExecContext(ctx, "PRAGMA user_version = 99"); err != nil {
		t.Fatal(err)
	}
	s.Close()
	if fi, _ := os.Stat(path); fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode %v", fi.Mode().Perm())
	}
	if _, err := Open(path); err == nil || !strings.Contains(err.Error(), "newer") {
		t.Fatalf("a newer schema must be refused: %v", err)
	}
}

func TestReopenKeepsData(t *testing.T) {
	path := filepath.Join(t.TempDir(), "db.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if _, _, err := s.Push(ctx, "dev", []Change{{Kind: KindSetting, ID: "a", ModifiedAt: 1, Data: []byte(`{"x":1}`)}}); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	recs, _, err := s.Changes(ctx, 0, 10)
	if err != nil || len(recs) != 1 || string(recs[0].Data) != `{"x":1}` {
		t.Fatalf("%v %v", recs, err)
	}
}

func TestPreviewReferenceCheckUsesItsIndex(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ctx := context.Background()
	rows, err := s.db.QueryContext(ctx, `EXPLAIN QUERY PLAN SELECT 1 FROM records WHERE kind = 'photo' AND deleted = 0
		AND data IS NOT NULL AND json_extract(data, '$.preview.sha256') = ? LIMIT 1`, "x")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var plan strings.Builder
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if err := rows.Scan(&id, &parent, &unused, &detail); err != nil {
			t.Fatal(err)
		}
		plan.WriteString(detail + "\n")
	}
	if !strings.Contains(plan.String(), "records_preview") {
		t.Fatalf("the reference check scans the records:\n%s", plan.String())
	}
	if ref, err := s.PreviewReferenced(ctx, "x"); err != nil || ref {
		t.Fatalf("nothing names x: %v %v", ref, err)
	}
}
