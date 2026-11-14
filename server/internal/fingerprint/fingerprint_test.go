package fingerprint

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"strconv"
	"strings"
	"testing"
)

func reference(parts ...[]byte) string {
	h := sha256.New()
	for _, p := range parts {
		h.Write(p)
	}
	return Version + hex.EncodeToString(h.Sum(nil))
}

func sizeBytes(n int) []byte {
	b := make([]byte, 8)
	binary.BigEndian.PutUint64(b, uint64(n))
	return b
}

func TestSmallFileIsHashedWhole(t *testing.T) {
	for _, n := range []int{0, 1, 1000, 2 * Edge} {
		data := VectorBytes(n, 3)
		if got, want := Bytes(data), reference(sizeBytes(n), data); got != want {
			t.Fatalf("size %d: got %s want %s", n, got, want)
		}
	}
}

func TestLargeFileHashesHeadAndTail(t *testing.T) {
	n := 5*Edge + 7
	data := VectorBytes(n, 1)
	want := reference(sizeBytes(n), data[:Edge], data[n-Edge:])
	if got := Bytes(data); got != want {
		t.Fatalf("got %s want %s", got, want)
	}
	middle := bytes.Clone(data)
	middle[n/2]++
	if Bytes(middle) != want {
		t.Fatal("a change only in the middle must keep the fingerprint (documented limit)")
	}
	head := bytes.Clone(data)
	head[3]++
	if Bytes(head) == want {
		t.Fatal("a change in the head must change the fingerprint")
	}
}

func TestShortSourceIsAnError(t *testing.T) {
	data := VectorBytes(100, 1)
	if _, err := Of(bytes.NewReader(data), 200); !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("want ErrUnexpectedEOF, got %v", err)
	}
	if _, err := Of(bytes.NewReader(VectorBytes(3*Edge, 1)), 4*Edge); err == nil {
		t.Fatal("a large file that shrank must fail")
	}
}

func TestValid(t *testing.T) {
	ok := Bytes(nil)
	if !Valid(ok) {
		t.Fatal(ok)
	}
	for _, bad := range []string{"", "v1:", "v2:" + ok[3:], strings.ToUpper(ok), ok + "0", "v1:../../etc/passwd"} {
		if Valid(bad) {
			t.Fatalf("%q must be invalid", bad)
		}
	}
}

// TestSharedVectors checks the vectors the Kotlin FileFingerprintVectorsTest reads too. REVEAL_WRITE_VECTORS=1
// rewrites the file (only when the generator or the sizes change, never the rule).
func TestSharedVectors(t *testing.T) {
	path := "../../testdata/fingerprint-vectors.txt"
	if os.Getenv("REVEAL_WRITE_VECTORS") == "1" {
		writeVectors(t, path)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	count := 0
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) != 3 {
			t.Fatalf("bad line %q", line)
		}
		size, _ := strconv.Atoi(fields[0])
		seed, _ := strconv.ParseInt(fields[1], 10, 64)
		if got := Bytes(VectorBytes(size, seed)); got != fields[2] {
			t.Errorf("size %d seed %d: got %s want %s", size, seed, got, fields[2])
		}
		count++
	}
	if count < 8 {
		t.Fatalf("only %d vectors", count)
	}
}

var vectorCases = []struct {
	size int
	seed int64
}{
	{0, 0}, {1, 0}, {5, 7}, {1000, 1}, {65537, 2}, {Edge, 3}, {2*Edge - 1, 4}, {2 * Edge, 5}, {2*Edge + 1, 6},
	{3*Edge + 5, 7}, {25*Edge + 123, 8},
}

func writeVectors(t *testing.T, path string) {
	var b strings.Builder
	b.WriteString("# Reveal v1 content fingerprint vectors (ADR 0006 \"Identity\", ADR 0017 \"Tests\").\n")
	b.WriteString("# size seed fingerprint; content byte i = ((i * 2654435761 + seed * 97) >> 13) & 0xff (64-bit).\n")
	b.WriteString("# Read by sync-server/internal/fingerprint and core/image FileFingerprintVectorsTest.\n")
	for _, c := range vectorCases {
		b.WriteString(strconv.Itoa(c.size) + " " + strconv.FormatInt(c.seed, 10) + " " + Bytes(VectorBytes(c.size, c.seed)) + "\n")
	}
	if err := os.WriteFile(path, []byte(b.String()), 0o644); err != nil {
		t.Fatal(err)
	}
}
