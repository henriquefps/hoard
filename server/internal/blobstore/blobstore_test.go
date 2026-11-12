package blobstore

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"

	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
)

const upload = "0f8fad5b-d9cb-469f-a165-70867728950e"

func sum(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

// failingReader returns n bytes, then an error: a connection that breaks in the middle of a chunk.
type failingReader struct {
	r io.Reader
	n int
}

func (f *failingReader) Read(p []byte) (int, error) {
	if f.n <= 0 {
		return 0, io.ErrUnexpectedEOF
	}
	if len(p) > f.n {
		p = p[:f.n]
	}
	n, err := f.r.Read(p)
	f.n -= n
	return n, err
}

func TestResumableUploadWithInterruptedChunk(t *testing.T) {
	root := t.TempDir()
	s, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	data := fingerprint.VectorBytes(3*fingerprint.Edge+11, 9)
	sha := sum(data)

	off, err := s.WriteChunk(upload, 0, bytes.NewReader(data[:1<<20]), 1<<20)
	if err != nil || off != 1<<20 {
		t.Fatalf("first chunk: %d %v", off, err)
	}
	// The second chunk breaks after 300 KB: those bytes are kept.
	off, err = s.WriteChunk(upload, off, &failingReader{r: bytes.NewReader(data[off:]), n: 300_000}, 1<<20)
	if !errors.Is(err, io.ErrUnexpectedEOF) || off != 1<<20+300_000 {
		t.Fatalf("interrupted chunk: %d %v", off, err)
	}
	if s.PartSize(upload) != off {
		t.Fatal("part size must equal the stored offset")
	}
	// A longer part file than the recorded offset (a crash between write and database update) is cut back.
	recorded := int64(1 << 20)
	off, err = s.WriteChunk(upload, recorded, bytes.NewReader(data[recorded:]), int64(len(data)))
	if err != nil || off != int64(len(data)) {
		t.Fatalf("resume: %d %v", off, err)
	}
	if err := s.Complete(upload, sha, int64(len(data)), fingerprint.Bytes(data)); err != nil {
		t.Fatal(err)
	}
	if !s.Has(sha) {
		t.Fatal("blob missing")
	}
	f, err := s.Open(sha)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(f)
	f.Close()
	if !bytes.Equal(got, data) {
		t.Fatal("content differs")
	}
	fi, _ := os.Stat(filepath.Join(root, Dir, "blobs", sha[:2], sha[2:4], sha))
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode %v", fi.Mode().Perm())
	}
	if !HasAnyBlob(root) {
		t.Fatal("HasAnyBlob")
	}
}

func TestCompleteRejectsMismatches(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	data := []byte("not what was declared")
	if _, err := s.WriteChunk(upload, 0, bytes.NewReader(data), 100); err != nil {
		t.Fatal(err)
	}
	if err := s.Complete(upload, sum([]byte("other")), int64(len(data)), ""); !errors.Is(err, ErrChecksum) {
		t.Fatalf("want ErrChecksum, got %v", err)
	}
	if s.PartSize(upload) != 0 {
		t.Fatal("a bad upload must restart from 0")
	}
	if _, err := s.WriteChunk(upload, 0, bytes.NewReader(data), 100); err != nil {
		t.Fatal(err)
	}
	if err := s.Complete(upload, sum(data), int64(len(data)), fingerprint.Bytes([]byte("x"))); !errors.Is(err, ErrFingerprint) {
		t.Fatalf("want ErrFingerprint, got %v", err)
	}
}

func TestNamesCannotTraverse(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, bad := range []string{"../../etc/passwd", "../" + upload, upload + "/..", "", "0F8FAD5B-D9CB-469F-A165-70867728950E"} {
		if _, err := s.WriteChunk(bad, 0, bytes.NewReader(nil), 1); !errors.Is(err, ErrInvalidName) {
			t.Errorf("WriteChunk(%q): %v", bad, err)
		}
		if err := s.Abort(bad); !errors.Is(err, ErrInvalidName) {
			t.Errorf("Abort(%q): %v", bad, err)
		}
	}
	for _, bad := range []string{"../../../../etc/passwd", "aa/../../x", sum(nil)[:63] + "G", sum(nil) + "/"} {
		if _, err := s.Open(bad); !errors.Is(err, ErrInvalidName) {
			t.Errorf("Open(%q): %v", bad, err)
		}
		if s.Has(bad) {
			t.Errorf("Has(%q)", bad)
		}
	}
}
