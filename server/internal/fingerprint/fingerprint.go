// Package fingerprint computes Reveal's content fingerprint of a file (ADR 0006 "Identity").
//
// v1: "v1:" + lower-case hex SHA-256 over the size as 8 bytes (big-endian), then the first Edge bytes, then the last
// Edge bytes; a file of at most 2*Edge bytes is hashed whole after its size instead (no byte twice). It must agree
// byte for byte with FileFingerprint in the app's :core:image module; both check testdata/fingerprint-vectors.txt.
package fingerprint

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"regexp"
)

// Version is the prefix of every v1 fingerprint.
const Version = "v1:"

// Edge is the number of bytes hashed from each end of a large file.
const Edge = 1 << 20

var pattern = regexp.MustCompile(`^v1:[0-9a-f]{64}$`)

// Valid reports whether s is a well-formed v1 fingerprint.
func Valid(s string) bool { return pattern.MatchString(s) }

// Of computes the fingerprint of the size bytes readable from r (it reads at most 2*Edge bytes). A source shorter
// than size is an error, never a fingerprint of fewer bytes.
func Of(r io.ReaderAt, size int64) (string, error) {
	if size < 0 {
		return "", fmt.Errorf("negative size")
	}
	r = sizedReaderAt{r, size}
	h := sha256.New()
	var sz [8]byte
	binary.BigEndian.PutUint64(sz[:], uint64(size))
	h.Write(sz[:])
	if size <= 2*Edge {
		if _, err := io.Copy(h, io.NewSectionReader(r, 0, size)); err != nil {
			return "", err
		}
	} else {
		if _, err := io.Copy(h, io.NewSectionReader(r, 0, Edge)); err != nil {
			return "", err
		}
		if _, err := io.Copy(h, io.NewSectionReader(r, size-Edge, Edge)); err != nil {
			return "", err
		}
	}
	return Version + hex.EncodeToString(h.Sum(nil)), nil
}

// sizedReaderAt turns an early EOF into an error: io.Copy stops quietly at EOF, but a file that shrank must not hash.
type sizedReaderAt struct {
	r    io.ReaderAt
	size int64
}

func (s sizedReaderAt) ReadAt(p []byte, off int64) (int, error) {
	n, err := s.r.ReadAt(p, off)
	if err == io.EOF && off+int64(n) < s.size {
		return n, io.ErrUnexpectedEOF
	}
	return n, err
}

// Bytes is the fingerprint of an in-memory file.
func Bytes(b []byte) string {
	fp, _ := Of(bytesReaderAt(b), int64(len(b)))
	return fp
}

type bytesReaderAt []byte

func (b bytesReaderAt) ReadAt(p []byte, off int64) (int, error) {
	if off >= int64(len(b)) {
		return 0, io.EOF
	}
	n := copy(p, b[off:])
	if n < len(p) {
		return n, io.EOF
	}
	return n, nil
}

// VectorBytes is the deterministic content of a test vector, shared with the Kotlin test:
// byte i = ((i * 2654435761 + seed * 97) >> 13) & 0xff, in 64-bit arithmetic.
func VectorBytes(size int, seed int64) []byte {
	b := make([]byte, size)
	for i := range b {
		b[i] = byte((uint64(i)*2654435761 + uint64(seed)*97) >> 13) //nolint:gosec // test-vector generator, seeds are small and non-negative
	}
	return b
}
