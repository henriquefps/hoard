package nas

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"

	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
	"github.com/henriquefps/reveal/sync-server/internal/mounts"
	"github.com/henriquefps/reveal/sync-server/internal/store"
)

func write(t *testing.T, p string, b []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, b, 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestIndexFolder(t *testing.T) {
	base := t.TempDir()
	mnt := filepath.Join(base, "mnt")
	nasDir := filepath.Join(mnt, "nas")
	secret := filepath.Join(base, "secret")
	raw := fingerprint.VectorBytes(3*fingerprint.Edge, 1)
	jpg := fingerprint.VectorBytes(5000, 2)
	write(t, filepath.Join(nasDir, "2026", "DSC00001.ARW"), raw)
	write(t, filepath.Join(nasDir, "2026", "DSC00001.JPG"), jpg)
	write(t, filepath.Join(nasDir, "2026", "notes.txt"), []byte("x"))
	write(t, filepath.Join(nasDir, ".hidden", "DSC00002.ARW"), raw[:100])
	write(t, filepath.Join(secret, "outside.jpg"), []byte("private"))
	if err := os.Symlink(secret, filepath.Join(nasDir, "link")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(secret, "outside.jpg"), filepath.Join(nasDir, "linked.jpg")); err != nil {
		t.Fatal(err)
	}

	st, err := store.Open(filepath.Join(base, "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	roots, _ := mounts.New([]string{mnt})
	ctx := context.Background()
	real, err := roots.Resolve(nasDir)
	if err != nil {
		t.Fatal(err)
	}
	folder, err := st.AddNasFolder(ctx, real, "nas")
	if err != nil {
		t.Fatal(err)
	}
	ix := &Indexer{Store: st, Roots: roots, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := ix.Folder(ctx, folder); err != nil {
		t.Fatal(err)
	}
	files, _, err := st.NasChanges(ctx, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 2 {
		t.Fatalf("want the ARW and the JPG only, got %+v", files)
	}
	o, err := st.LookupOriginal(ctx, fingerprint.Bytes(raw))
	if err != nil || o.Source != "nas" || o.RelPath != "2026/DSC00001.ARW" || o.Size != int64(len(raw)) {
		t.Fatalf("lookup: %+v %v", o, err)
	}
	if _, err := st.LookupOriginal(ctx, fingerprint.Bytes([]byte("private"))); err == nil {
		t.Fatal("a file behind a symbolic link must not be indexed")
	}

	// Unchanged walk: no new revisions. Then a removal is a tombstone.
	if err := ix.Folder(ctx, folder); err != nil {
		t.Fatal(err)
	}
	if again, _, _ := st.NasChanges(ctx, files[len(files)-1].Rev, 100); len(again) != 0 {
		t.Fatalf("unchanged walk wrote %d entries", len(again))
	}
	if err := os.Remove(filepath.Join(nasDir, "2026", "DSC00001.JPG")); err != nil {
		t.Fatal(err)
	}
	if err := ix.Folder(ctx, folder); err != nil {
		t.Fatal(err)
	}
	gone, _, _ := st.NasChanges(ctx, files[len(files)-1].Rev, 100)
	if len(gone) != 1 || !gone[0].Removed || gone[0].Path != "2026/DSC00001.JPG" {
		t.Fatalf("removal: %+v", gone)
	}
	f, _ := st.NasFolder(ctx, folder.ID)
	if f.State != "ready" || f.FileCount != 1 {
		t.Fatalf("folder %+v", f)
	}

	// A folder outside the roots (a tampered database row) is never walked.
	bad, _ := st.AddNasFolder(ctx, secret, "secret")
	if err := ix.Folder(ctx, bad); err == nil {
		t.Fatal("a folder outside the roots must fail")
	}
}

func TestSupported(t *testing.T) {
	for name, want := range map[string]bool{"a.ARW": true, "b.jpeg": true, "c.HIF": true, "d.xmp": false, ".e.jpg": false, "jpg": false, "f.png": false} {
		if Supported(name) != want {
			t.Errorf("%s", name)
		}
	}
}
