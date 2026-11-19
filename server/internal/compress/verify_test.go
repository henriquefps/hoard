package compress

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
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

func reasonOf(_ CheckResult, err error) string { return checkReason(err) }

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
	if _, err := v.Check(ctx, open(t, src), good, "", &log); err != nil {
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
		if got := reasonOf(v.Check(ctx, open(t, src), out, "", &log)); got != c.want {
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
	if _, err := v.Check(context.Background(), open(t, src), same, "", &bytes.Buffer{}); err != nil {
		t.Fatalf("same HDR metadata: %v", err)
	}
	other := tl.make(t, filepath.Join(dir, "other.mp4"), "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=3", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
		"-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-tag:v", "hvc1",
		"-x265-params", strings.Replace(hdr, "max-cll=1000,400", "max-cll=600,200", 1))
	if got := reasonOf(v.Check(context.Background(), open(t, src), other, "", &bytes.Buffer{})); got != "verify_picture" {
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

// Review of 0.13.0: an output that does not decode cleanly mid-clip, an audio track cut short (the file's duration
// unchanged), lost capture time and place, and unknown durations all fail.
func TestVerifierGates(t *testing.T) {
	tl := needTools(t)
	dir := t.TempDir()
	v := &FFVerifier{FFmpeg: tl.ffmpeg, FFprobe: tl.ffprobe, Threads: 2}
	ctx := context.Background()
	src := tl.make(t, filepath.Join(dir, "src.mp4"), "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=10", "-f", "lavfi", "-i", "sine=f=440:d=10",
		"-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-c:a", "aac", "-shortest",
		"-metadata", "creation_time=2024-05-01T10:00:00.000000Z", "-metadata", "location=+48.8584+002.2945/")
	reenc := []string{"-c:v", "libx265", "-preset", "ultrafast", "-crf", "28", "-tag:v", "hvc1", "-x265-params", "log-level=error",
		"-fps_mode", "passthrough", "-enc_time_base:v", "demux"}
	good := tl.make(t, filepath.Join(dir, "good.mp4"), append([]string{"-i", src, "-map", "0", "-map_metadata", "0", "-c:a", "copy"}, reenc...)...)
	var log bytes.Buffer
	if _, err := v.Check(ctx, open(t, src), good, "", &log); err != nil {
		t.Fatalf("good: %v\n%s", err, log.String())
	}
	if !strings.Contains(log.String(), "full decode: ok") {
		t.Fatal("no full decode in the log")
	}
	// Corrupted in the middle of the media data: the header, durations and frame counts are unchanged.
	b, err := os.ReadFile(good)
	if err != nil {
		t.Fatal(err)
	}
	i := bytes.Index(b, []byte("mdat"))
	if i < 0 {
		t.Fatal("no mdat")
	}
	mid := i + (len(b)-i)/2
	for j := mid; j < mid+64<<10 && j < len(b); j++ {
		b[j] = byte(j * 7)
	}
	bad := filepath.Join(dir, "corrupt.mp4")
	if err := os.WriteFile(bad, b, 0o600); err != nil {
		t.Fatal(err)
	}
	if got := reasonOf(v.Check(ctx, open(t, src), bad, "", &bytes.Buffer{})); got != "verify_decode" {
		t.Errorf("corrupt: %q", got)
	}
	// The audio stream 1 s short; the file is still 10 s long.
	short := tl.make(t, filepath.Join(dir, "shortaudio.mp4"), append([]string{"-i", src, "-map", "0", "-map_metadata", "0", "-af", "atrim=0:8.8",
		"-c:a", "aac"}, reenc...)...)
	if got := reasonOf(v.Check(ctx, open(t, src), short, "", &bytes.Buffer{})); got != "verify_duration" {
		t.Errorf("audio short: %q", got)
	}
	// Capture time and place gone.
	bare := tl.make(t, filepath.Join(dir, "bare.mp4"), append([]string{"-i", src, "-map", "0", "-map_metadata", "-1", "-c:a", "copy"}, reenc...)...)
	log.Reset()
	if got := reasonOf(v.Check(ctx, open(t, src), bare, "", &log)); got != "verify_metadata" || !strings.Contains(log.String(), "creation_time") {
		t.Errorf("metadata: %q\n%s", got, log.String())
	}
}

func TestCompareInfoUnknownDuration(t *testing.T) {
	v := verifyStream{Kind: "video", Width: 1, Height: 1, FrameRate: 30, BitDepth: 8}
	if got := checkReason(compareInfo(verifyInfo{Streams: []verifyStream{v}}, verifyInfo{Streams: []verifyStream{v}})); got != "verify_duration" {
		t.Fatalf("0 = 0 passed: %q", got)
	}
	if got := checkReason(compareInfo(verifyInfo{Duration: 10, Streams: []verifyStream{v}}, verifyInfo{Duration: 10, Streams: []verifyStream{v}})); got != "" {
		t.Fatalf("equal: %q", got)
	}
}

// damageLastAudioFrame overwrites the payload of a file's last AAC packet.
func damageLastAudioFrame(t *testing.T, tl tools, p string) {
	t.Helper()
	out, err := exec.Command(tl.ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pos,size", "-of", "csv=p=0", p).Output()
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Fields(string(out))
	var pos, size int64
	// ffprobe prints the fields in its own order: size, then pos.
	if _, err := fmt.Sscanf(strings.TrimSuffix(strings.ReplaceAll(lines[len(lines)-1], ",", " "), " "), "%d %d", &size, &pos); err != nil {
		t.Fatalf("%q: %v", lines[len(lines)-1], err)
	}
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	for i := pos + 2; i < pos+size; i++ { // the payload after the first bytes
		b[i] = 0xff
	}
	if err := os.WriteFile(p, b, 0o600); err != nil {
		t.Fatal(err)
	}
}

// Re-review N3: a damaged last AAC frame the source already has (a copied track keeps it) does not fail the item; the
// same damage only in the output does.
func TestVerifierDamagedLastAudioFrame(t *testing.T) {
	tl := needTools(t)
	dir := t.TempDir()
	v := &FFVerifier{FFmpeg: tl.ffmpeg, FFprobe: tl.ffprobe, Threads: 2}
	ctx := context.Background()
	clean := tl.make(t, filepath.Join(dir, "clean.mp4"), "-f", "lavfi", "-i", "testsrc2=s=320x180:r=30:d=6", "-f", "lavfi", "-i", "sine=f=440:d=6",
		"-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest")
	damaged := filepath.Join(dir, "damaged.mp4")
	b, _ := os.ReadFile(clean)
	if err := os.WriteFile(damaged, b, 0o600); err != nil {
		t.Fatal(err)
	}
	damageLastAudioFrame(t, tl, damaged)
	if err := v.decode(ctx, time.Minute, nil, damaged, "0:a"); err == nil {
		t.Skip("this ffmpeg decodes the damaged frame without an error")
	}
	reenc := []string{"-map", "0", "-c:a", "copy", "-c:v", "libx265", "-preset", "ultrafast", "-crf", "28", "-tag:v", "hvc1", "-x265-params", "log-level=error",
		"-fps_mode", "passthrough", "-enc_time_base:v", "demux"}
	// The source has the damage; the output copies it.
	out := tl.make(t, filepath.Join(dir, "out.mp4"), append([]string{"-i", damaged}, reenc...)...)
	var log bytes.Buffer
	if _, err := v.Check(ctx, open(t, damaged), out, "", &log); err != nil {
		t.Fatalf("inherited damage failed the check: %v\n%s", err, log.String())
	}
	if !strings.Contains(log.String(), "the source's audio has the same damage") {
		t.Fatalf("not logged:\n%s", log.String())
	}
	// A clean source; only the output is damaged.
	out2 := tl.make(t, filepath.Join(dir, "out2.mp4"), append([]string{"-i", clean}, reenc...)...)
	damageLastAudioFrame(t, tl, out2)
	if got := reasonOf(v.Check(ctx, open(t, clean), out2, "", &bytes.Buffer{})); got != "verify_decode" {
		t.Fatalf("damage only in the output: %q", got)
	}
}
