// Package blobstore keeps content-addressed blobs and unfinished uploads under the backup root:
// <root>/reveal-sync/blobs/<sha[0:2]>/<sha[2:4]>/<sha> and <root>/reveal-sync/uploads/<upload id>.part.
// Files are mode 0600, directories 0700; every access goes through os.Root, so no name can leave the folder.
package blobstore

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"

	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
)

// Dir is the folder reveal-sync owns inside the backup root.
const Dir = "reveal-sync"

var (
	shaPattern  = regexp.MustCompile(`^[0-9a-f]{64}$`)
	uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

	// ErrInvalidName is returned for a hash or upload id that is not well-formed.
	ErrInvalidName = errors.New("invalid blob or upload name")
	// ErrChecksum is returned when a completed upload's SHA-256 is not the declared one.
	ErrChecksum = errors.New("sha256 mismatch")
	// ErrFingerprint is returned when a completed original's fingerprint is not the declared one.
	ErrFingerprint = errors.New("fingerprint mismatch")
)

// ValidSHA256 reports whether s is a lower-case hex SHA-256.
func ValidSHA256(s string) bool { return shaPattern.MatchString(s) }

// ValidUploadID reports whether s is a well-formed upload id.
func ValidUploadID(s string) bool { return uuidPattern.MatchString(s) }

// Store is the blob folder of one backup root.
type Store struct {
	root *os.Root
	dir  string
}

// Open creates (if needed) and opens the reveal-sync folder of backupRoot.
func Open(backupRoot string) (*Store, error) {
	dir := filepath.Join(backupRoot, Dir)
	for _, d := range []string{dir, filepath.Join(dir, "blobs"), filepath.Join(dir, "uploads")} {
		if err := os.Mkdir(d, 0o700); err != nil && !errors.Is(err, fs.ErrExist) {
			return nil, err
		}
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return nil, err
	}
	return &Store{root: root, dir: dir}, nil
}

// Close releases the folder.
func (s *Store) Close() error { return s.root.Close() }

// Path is the folder (for the setup page and logs: it is inside a configured root).
func (s *Store) Path() string { return s.dir }

func blobPath(sha string) string { return path.Join("blobs", sha[0:2], sha[2:4], sha) }

func partPath(id string) string { return path.Join("uploads", id+".part") }

// Open opens a blob for reading.
func (s *Store) Open(sha string) (*os.File, error) {
	if !ValidSHA256(sha) {
		return nil, ErrInvalidName
	}
	return s.root.Open(blobPath(sha))
}

// Has reports whether a blob is on disk.
func (s *Store) Has(sha string) bool {
	if !ValidSHA256(sha) {
		return false
	}
	fi, err := s.root.Stat(blobPath(sha))
	return err == nil && fi.Mode().IsRegular()
}

// WriteChunk appends at most limit bytes from r to an upload's part file at offset (the database's offset is the
// truth: a longer file from an interrupted write is cut back first). The bytes that arrived are synced to disk even
// when r fails, so the returned offset is always what is safely stored; err is the read error, if any.
func (s *Store) WriteChunk(id string, offset int64, r io.Reader, limit int64) (int64, error) {
	if !ValidUploadID(id) {
		return offset, ErrInvalidName
	}
	f, err := s.root.OpenFile(partPath(id), os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return offset, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return offset, err
	}
	if fi.Size() < offset {
		return offset, fmt.Errorf("part file is shorter (%d) than its recorded offset (%d)", fi.Size(), offset)
	}
	if fi.Size() > offset {
		if err := f.Truncate(offset); err != nil {
			return offset, err
		}
	}
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return offset, err
	}
	n, copyErr := io.Copy(f, io.LimitReader(r, limit))
	if err := f.Sync(); err != nil {
		return offset, err
	}
	return offset + n, copyErr
}

// PartSize is the size of an upload's part file (0 when missing).
func (s *Store) PartSize(id string) int64 {
	if !ValidUploadID(id) {
		return 0
	}
	fi, err := s.root.Stat(partPath(id))
	if err != nil {
		return 0
	}
	return fi.Size()
}

// Complete checks a finished upload (SHA-256, and the v1 fingerprint when fp is not empty) and moves it into place
// atomically. On a mismatch the part file is removed and ErrChecksum or ErrFingerprint is returned.
func (s *Store) Complete(id, sha string, size int64, fp string) error {
	if !ValidUploadID(id) || !ValidSHA256(sha) {
		return ErrInvalidName
	}
	f, err := s.root.Open(partPath(id))
	if err != nil {
		return err
	}
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err == nil && n != size {
		err = fmt.Errorf("part file has %d bytes, want %d", n, size)
	}
	var gotFP string
	if err == nil && fp != "" {
		gotFP, err = fingerprint.Of(f, size)
	}
	f.Close()
	if err != nil {
		return err
	}
	if hex.EncodeToString(h.Sum(nil)) != sha {
		_ = s.Abort(id)
		return ErrChecksum
	}
	if fp != "" && gotFP != fp {
		_ = s.Abort(id)
		return ErrFingerprint
	}
	dst := blobPath(sha)
	if err := s.root.MkdirAll(path.Dir(dst), 0o700); err != nil {
		return err
	}
	if err := s.root.Rename(partPath(id), dst); err != nil {
		return err
	}
	return s.syncDir(path.Dir(dst))
}

func (s *Store) syncDir(dir string) error {
	d, err := s.root.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// Abort removes an upload's part file.
func (s *Store) Abort(id string) error {
	if !ValidUploadID(id) {
		return ErrInvalidName
	}
	err := s.root.Remove(partPath(id))
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return err
}

// HasAnyBlob reports whether the folder holds blobs (the backup root is then fixed).
func HasAnyBlob(backupRoot string) bool {
	entries, err := os.ReadDir(filepath.Join(backupRoot, Dir, "blobs"))
	return err == nil && len(entries) > 0
}
