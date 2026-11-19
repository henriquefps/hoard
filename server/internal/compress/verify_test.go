package compress

import (
	"bytes"
	"context"
	"errors"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// The Verifier with real ffmpeg on synthetic lavfi clips (CPU encoders, seconds). Skipped without ffmpeg/ffprobe;
// the VMAF parts need ffmpeg built with libvmaf.

type tools struct{ ffmpeg, ffprobe string }

func needTools(t *testing.T) tools {
	t.Helper()
	ff, err1 := exec.LookPath("ffmpeg")
	fp, err2 := exec.LookPath("ffprobe")
	if err1 != nil || err2 != nil {
		t.Skip("ffmpeg/ffprobe not installed")
	}
	enc, _ := exec.Command(ff, "-hide_banner", "-encoders").Output()
	if !bytes.Contains(enc, []byte("libx264")) || !bytes.Contains(enc, []byte("libx265")) {
		t.Skip("ffmpeg without libx264/libx265")
	}
	return tools{ff, fp}
}

func hasVMAF(ff string) bool {
	out, _ := exec.Command(ff, "-hide_banner", "-filters").Output()
	return bytes.Contains(out, []byte("libvmaf"))
}

func (tl tools) make(t *testing.T, out string, args ...string) string {
	t.Helper()
	full := append([]string{"-hide_banner", "-loglevel", "error", "-y"}, args...)
	full = append(full, out)
	if b, err := exec.Command(tl.ffmpeg, full...).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg %v: %v\n%s", args, err, b)
	}
	return out
}

func open(t *testing.T, p string) *os.File {
	t.Helper()
	f, err := os.Open(p)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { f.Close() })
	return f
}

func checkReason(err error) string {
	var ve *VerifyError
	if errors.As(err, &ve) {
		return ve.Reason()
	}
	if err != nil {
		return "error: " + err.Error()
	}
	return ""
}

func TestVerifierChecks(t *testing.T) {
	tl := needTools(t)
	dir := t.TempDir()
	v := &FFVerifier{FFmpeg: tl.ffmpeg, FFprobe: tl.ffprobe, Threads: 2}
	ctx := context.Background()
	src := tl.make(t, filepath.Join(dir, "src.mp4"), "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=10", "-f", "lavfi", "-i", "sine=f=440:d=10",
		"-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-c:a", "aac", "-shortest")
	good := tl.make(t, filepath.Join(dir, "good.mp4"), "-i", src, "-map", "0", "-c:a", "copy", "-c:v", "libx265", "-preset", "ultrafast", "-crf", "28",
		"-tag:v", "hvc1", "-x265-params", "log-level=error", "-fps_mode", "passthrough", "-enc_time_base:v", "demux")
	var log bytes.Buffer
	if err := v.Check(ctx, open(t, src), good, &log); err != nil {
		t.Fatalf("good: %v\n%s", err, log.String())
	}
	if !strings.Contains(log.String(), "metadata diff (ffprobe):") || !strings.Contains(log.String(), "codec_name") {
		t.Fatalf("no metadata diff in the log:\n%s", log.String())
	}
	cases := []struct {
		name, want string
		args       []string
	}{
		{"short", "verify_duration", []string{"-i", src, "-t", "9.5", "-c", "copy"}},
		{"no audio", "verify_streams", []string{"-i", src, "-an", "-c", "copy"}},
		{"resized", "verify_picture", []string{"-i", src, "-c:a", "copy", "-vf", "scale=320:180", "-c:v", "libx264", "-preset", "ultrafast"}},
		{"rotated", "verify_picture", []string{"-display_rotation:v:0", "90", "-i", src, "-c", "copy"}},
		{"ten bit", "verify_picture", []string{"-i", src, "-c:a", "copy", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
			"-x265-params", "log-level=error"}},
		{"a frame dropped", "verify_frames", []string{"-i", src, "-c:a", "copy", "-vf", `select=not(eq(n\,150))`, "-fps_mode", "passthrough",
			"-c:v", "libx264", "-preset", "ultrafast"}},
	}
	for _, c := range cases {
		out := tl.make(t, filepath.Join(dir, strings.ReplaceAll(c.name, " ", "_")+".mp4"), c.args...)
		log.Reset()
		if got := checkReason(v.Check(ctx, open(t, src), out, &log)); got != c.want {
			t.Errorf("%s: %q, want %s\n%s", c.name, got, c.want, log.String())
		}
	}
}

// HDR10 static metadata (mastering display, content light level) must be equal on both sides.
func TestVerifierHDRSideData(t *testing.T) {
	tl := needTools(t)
	dir := t.TempDir()
	v := &FFVerifier{FFmpeg: tl.ffmpeg, FFprobe: tl.ffprobe, Threads: 2}
	hdr := "log-level=error:hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:" +
		"master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400"
	src := tl.make(t, filepath.Join(dir, "pq.mp4"), "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=3", "-c:v", "libx265", "-preset", "ultrafast",
		"-pix_fmt", "yuv420p10le", "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-x265-params", hdr, "-tag:v", "hvc1")
	info, err := v.probe(context.Background(), nil, src)
	if err != nil {
		t.Fatal(err)
	}
	if s, _ := mainVideo(info.Streams); s.Mastering == "" || s.CLL == "" {
		t.Skip("this ffmpeg does not export HDR10 side data as stream side data")
	}
	// Same metadata: ok. Other mastering values: picture.
	same := tl.make(t, filepath.Join(dir, "same.mp4"), "-i", src, "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
		"-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-x265-params", hdr, "-tag:v", "hvc1")
	if err := v.Check(context.Background(), open(t, src), same, &bytes.Buffer{}); err != nil {
		t.Fatalf("same HDR metadata: %v", err)
	}
	other := tl.make(t, filepath.Join(dir, "other.mp4"), "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=3", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
		"-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-tag:v", "hvc1",
		"-x265-params", strings.Replace(hdr, "max-cll=1000,400", "max-cll=600,200", 1))
	if got := checkReason(v.Check(context.Background(), open(t, src), other, &bytes.Buffer{})); got != "verify_picture" {
		t.Fatalf("other CLL: %q", got)
	}
}

// VMAF: full up to 30 s; above, three 2 s spots, close to the full score.
func TestVerifierVMAF(t *testing.T) {
	tl := needTools(t)
	if !hasVMAF(tl.ffmpeg) {
		t.Skip("ffmpeg without libvmaf")
	}
	dir := t.TempDir()
	v := &FFVerifier{FFmpeg: tl.ffmpeg, FFprobe: tl.ffprobe, Threads: 4}
	ctx := context.Background()
	src := tl.make(t, filepath.Join(dir, "src.mp4"), "-f", "lavfi", "-i", "testsrc2=s=320x180:r=25:d=40", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "12")
	out := tl.make(t, filepath.Join(dir, "out.mp4"), "-i", src, "-c:v", "libx265", "-preset", "ultrafast", "-crf", "30", "-x265-params", "log-level=error",
		"-fps_mode", "passthrough", "-enc_time_base:v", "demux")
	f, err := os.Open(src)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	pr := &Prober{FFprobe: tl.ffprobe}
	p, err := pr.Probe(ctx, f)
	if err != nil || p.Unreadable || p.DurationMs < 39000 {
		t.Fatalf("probe %+v %v", p, err)
	}
	var log bytes.Buffer
	spot, err := v.VMAF(ctx, f, out, p, &log)
	if err != nil {
		t.Fatalf("%v\n%s", err, log.String())
	}
	full, err := v.vmafRange(ctx, f, out, p, -1, 0, &log)
	if err != nil {
		t.Fatal(err)
	}
	fs := summarize(full)
	if spot.Mode != "spot" || math.Abs(spot.Mean-fs.Mean) > 3 || fs.Mean < 50 || spot.Low > spot.Mean {
		t.Fatalf("spot %+v vs full %+v", spot, fs)
	}
	if strings.Count(log.String(), "vmaf spot at") != 3 {
		t.Fatalf("spots in the log:\n%s", log.String())
	}
	// A short clip is scored whole; the same file scores ~100.
	short := tl.make(t, filepath.Join(dir, "short.mp4"), "-i", src, "-t", "4", "-c", "copy")
	sf := open(t, short)
	sp, _ := pr.Probe(ctx, sf)
	r, err := v.VMAF(ctx, sf, short, sp, &log)
	if err != nil || r.Mode != "full" || r.Mean < 97 {
		t.Fatalf("identical %+v %v", r, err)
	}
	t.Logf("spot %.2f, full %.2f, identical %.2f", spot.Mean, fs.Mean, r.Mean)
}
