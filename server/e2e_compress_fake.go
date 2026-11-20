//go:build hoarde2e

package main

// A CPU stand-in for NVENC for the web gallery's end-to-end tests (ADR 0025 phase S3: "the fake encoder, a server flag
// only tests can set"). It is compiled only with -tags hoarde2e (server/web/e2e builds its binary so; no release,
// image or workflow does) and switched on by HOARD_E2E_FAKE_COMPRESS=1. Its encoder is libx264 at 1 Mb/s, its
// verifier passes with a fixed VMAF, the GPU is "detected" and never busy. A source whose name contains HOLD gets its
// trash link and then EIO: its replace journal waits with the original in the trash (a held entry, Amendment (S1)).
// Taken from docs/testing/compression/a1fake/fake.go.txt (Hoard 2.7.0's emulator test).

import (
	"compress/gzip"
	"context"
	"errors"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/server"
)

func e2eCompress(cfg *server.Config, logger *slog.Logger) {
	if os.Getenv("HOARD_E2E_FAKE_COMPRESS") != "1" {
		return
	}
	logger.Warn("e2e build: video compression uses a fake encoder (libx264), never for real use")
	cfg.CompressEncoder = fakeEncoder{ffmpeg: cfg.FFmpeg}
	cfg.CompressVerifier = fakeVerifier{}
	cfg.CompressDetector = fakeDetector{}
	cfg.CompressNVML = fakeNVML{}
	cfg.CompressLinker = holdLinker
}

type fakeEncoder struct{ ffmpeg string }

func (f fakeEncoder) Encode(ctx context.Context, r compress.EncodeRequest) (compress.EncodeResult, error) {
	bin := f.ffmpeg
	if bin == "" {
		bin = "ffmpeg"
	}
	cmd := exec.CommandContext(ctx, bin, //nolint:gosec // the configured ffmpeg; arguments are constants and work-directory paths
		"-hide_banner", "-nostdin", "-y", "-i", "/dev/fd/3", "-map", "0", "-c", "copy",
		"-c:v:0", "libx264", "-preset", "ultrafast", "-b:v", "1M", "-metadata", "dev.hfps.hoard.compressed=v1, fake-cq28, "+r.Date,
		"-movflags", "+use_metadata_tags+faststart", "-f", "mp4", r.Out)
	if _, err := r.Src.Seek(0, io.SeekStart); err != nil {
		return compress.EncodeResult{}, err
	}
	cmd.ExtraFiles = []*os.File{r.Src}
	cmd.Stderr = r.Log
	if r.Progress != nil {
		r.Progress(0.5)
	}
	if err := cmd.Run(); err != nil {
		return compress.EncodeResult{}, errors.Join(compress.ErrEncoder, err)
	}
	return compress.EncodeResult{Decoder: "cpu"}, os.Chtimes(r.Out, r.SrcMTime, r.SrcMTime)
}

// Sidecar writes a small per-frame stand-in so the gallery's metadata download has something to serve.
func (fakeEncoder) Sidecar(_ context.Context, _ *os.File, out io.Writer, _ io.Writer) error {
	zw := gzip.NewWriter(out)
	if _, err := io.WriteString(zw, `[{"SourceFile":"SRC","Doc1:SampleTime":0,"Doc1:ISO":100}]`); err != nil {
		return err
	}
	return zw.Close()
}

type fakeVerifier struct{}

func (fakeVerifier) Check(context.Context, *os.File, string, string, io.Writer) (compress.CheckResult, error) {
	return compress.CheckResult{}, nil
}

func (fakeVerifier) VMAF(context.Context, *os.File, string, compress.Probe, io.Writer) (compress.VMAFResult, error) {
	return compress.VMAFResult{Mean: 95.4, Low: 92.1, Mode: "full"}, nil
}

type fakeDetector struct{}

func (fakeDetector) Detect(context.Context) compress.GPUStatus {
	return compress.GPUStatus{Available: true, GPU: "Fake GPU (web e2e)", Codecs: []string{"hevc", "av1"}, VMAF: true, CUDADecode: true, Exiftool: true}
}

type fakeNVML struct{}

func (fakeNVML) Load(context.Context) (compress.GPULoad, error) { return compress.GPULoad{}, nil }

// holdLinker links like the real one; for HOLD sources it then reports EIO, every time it is tried again too, so the
// journal keeps waiting (held) for as long as the test runs.
func holdLinker(root *os.Root, oldname, newname string) error {
	hold := strings.Contains(filepath.Base(oldname), "HOLD") && strings.Contains(newname, ".hoard-trash")
	if err := root.Link(oldname, newname); err != nil && (!hold || !errors.Is(err, fs.ErrExist)) {
		return err
	}
	if hold {
		return &os.LinkError{Op: "link", Old: oldname, New: newname, Err: syscall.EIO}
	}
	return nil
}
