package compress

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// The metadata restore is a gate (review of 0.13.0): without exiftool, or when it fails, the encode fails as
// verify_metadata and leaves no output.
func TestEncoderMetadataGate(t *testing.T) {
	ok, err := exec.LookPath("true")
	bad, err2 := exec.LookPath("false")
	if err != nil || err2 != nil {
		t.Skip("no true/false")
	}
	dir := t.TempDir()
	src, _ := os.Create(filepath.Join(dir, "src.mp4"))
	defer src.Close()
	prof, _ := ProfileByID(DefaultProfile)
	for _, exif := range []string{"", bad} {
		out := filepath.Join(dir, "out.mp4")
		if err := os.WriteFile(out, []byte("x"), 0o600); err != nil { // the "encode" made an output
			t.Fatal(err)
		}
		e := &NvencEncoder{FFmpeg: ok, Exiftool: exif}
		_, err := e.Encode(context.Background(), EncodeRequest{Src: src, Profile: prof, Q: 28, Out: out, Log: &bytes.Buffer{}})
		var ve *VerifyError
		if !errors.As(err, &ve) || ve.Reason() != ReasonVerifyMeta {
			t.Fatalf("exiftool %q: %v", exif, err)
		}
		if _, err := os.Stat(out); !os.IsNotExist(err) {
			t.Fatalf("exiftool %q: the output stayed", exif)
		}
	}
}

// A trash path is clean, relative and inside a day folder (review nit).
func TestIsTrashPath(t *testing.T) {
	for p, want := range map[string]bool{".hoard-trash/2026-11-19/a.mp4": true, "x/.hoard-trash/2026-11-19/a.mp4": true,
		"x/.hoard-trash/../y.mp4": false, ".hoard-trash/../../y": false, "/.hoard-trash/d/a": false, ".hoard-trash/a": false,
		"a/b.mp4": false, "x/.hoard-trash/./d/a": false, "": false} {
		if IsTrashPath(p) != want {
			t.Errorf("%q: %v", p, !want)
		}
	}
}
