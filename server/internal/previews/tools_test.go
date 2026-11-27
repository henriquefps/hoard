package previews

import (
	"context"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/henriquefps/hoard/server/internal/store"
)

// hoard-server 0.14.0: AVI and other camera files the -nvidia image of 0.13.x could not decode (pcm_mulaw audio: "no
// decoder found"). The proxy path on such a file, and the image's ffmpeg decoding each kind (opt-in, HOARD_IMAGE).

// cameraClips writes synthetic clips of old cameras' kinds with a full ffmpeg (lavfi sources only).
func cameraClips(t *testing.T, c *CPU, dir string) map[string]string {
	t.Helper()
	clips := map[string][]string{
		"mjpeg-mulaw.avi":   {"-c:v", "mjpeg", "-q:v", "5", "-c:a", "pcm_mulaw", "-ar", "8000", "-ac", "1"},
		"mjpeg-u8.avi":      {"-c:v", "mjpeg", "-q:v", "5", "-c:a", "pcm_u8", "-ar", "11025", "-ac", "1"},
		"mpeg4-adpcm.avi":   {"-c:v", "mpeg4", "-c:a", "adpcm_ima_wav", "-ar", "22050"},
		"msmpeg4-alaw.avi":  {"-c:v", "msmpeg4", "-c:a", "pcm_alaw", "-ar", "8000", "-ac", "1"},
		"mjpeg-alaw.mov":    {"-c:v", "mjpeg", "-q:v", "5", "-c:a", "pcm_alaw", "-ar", "8000", "-ac", "1"},
		"h263-aac.3gp":      {"-c:v", "h263", "-s", "352x288", "-c:a", "aac", "-ar", "16000", "-ac", "1"},
		"mpeg4-adpcmqt.mov": {"-c:v", "mpeg4", "-c:a", "adpcm_ima_qt", "-ar", "22050", "-ac", "1"},
	}
	out := map[string]string{}
	for name, codec := range clips {
		p := filepath.Join(dir, name)
		args := append([]string{"-f", "lavfi", "-i", "testsrc2=s=352x288:r=15:d=2", "-f", "lavfi", "-i", "sine=f=440:d=2"}, codec...)
		ffmpegRun(t, c, append(args, "-shortest", p)...)
		out[name] = p
	}
	return out
}

// A camera AVI (MJPEG + pcm_mulaw) gives an H.264 + AAC proxy.
func TestCPUProxyCameraAVI(t *testing.T) {
	c := testCPU(t, true)
	dir := t.TempDir()
	src := filepath.Join(dir, "MVI_0001.AVI")
	ffmpegRun(t, c, "-f", "lavfi", "-i", "testsrc2=s=640x480:r=30:d=3", "-f", "lavfi", "-i", "sine=f=440:d=3",
		"-c:v", "mjpeg", "-q:v", "5", "-c:a", "pcm_mulaw", "-ar", "8000", "-ac", "1", "-shortest", src)
	res, out := generate(t, c, Job{Job: JobProxy, Source: videoSource(t, src)}, src)
	if res[FileProxy].Bytes == 0 {
		t.Fatalf("%+v", res)
	}
	var gotA bool
	for _, st := range ffprobe(t, filepath.Join(out, FileProxy)).Streams {
		gotA = gotA || st.CodecType == "audio" && st.CodecName == "aac"
	}
	if !gotA {
		t.Fatal("no AAC audio in the proxy")
	}
}

// HOARD_IMAGE=hoard-server:test-nvidia go test ./internal/previews -run TestImageDecodesCameraClips: the image's ffmpeg
// decodes each clip in full (-xerror) and makes a proxy of it.
func TestImageDecodesCameraClips(t *testing.T) {
	image := os.Getenv("HOARD_IMAGE")
	if image == "" {
		t.Skip("HOARD_IMAGE names the image to check")
	}
	c := NewCPU("", envOr("FIXTURE_FFMPEG", "ffmpeg"), 1)
	if !c.HasFFmpeg() {
		t.Skip("no ffmpeg for fixtures")
	}
	dir := t.TempDir()
	if err := os.Chmod(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for name := range cameraClips(t, c, dir) {
		_ = os.Chmod(filepath.Join(dir, name), 0o644)
		for _, args := range [][]string{
			{"-v", "error", "-xerror", "-i", "/in/" + name, "-f", "null", "-"},
			{"-v", "error", "-i", "/in/" + name, "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-movflags", "frag_keyframe+empty_moov", "-f", "mp4", "-"},
		} {
			cmd := exec.Command("docker", append([]string{"run", "--rm", "--network", "none", "-v", dir + ":/in:ro", "--entrypoint", "/opt/hoard/bin/ffmpeg",
				image, "-hide_banner", "-nostdin"}, args...)...)
			cmd.Stdout = io.Discard
			if out, err := cmd.CombinedOutput(); err != nil {
				t.Errorf("%s: %v: %s", name, err, out)
			}
		}
	}
}

// Failed previews are tried again once after the tools changed (a decoder added), not at every start.
func TestRetryFailedAfterToolsChange(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(filepath.Join(t.TempDir(), "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	g := &toolsGen{id: "a"}
	m := &Manager{cfg: Config{Store: st, Generator: g, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}}
	fail := func() {
		if _, err := st.WantPreviews(ctx, []store.PreviewWant{{ItemID: "i1", Job: JobProxy, WantKey: "k1", Priority: 1}}); err != nil {
			t.Fatal(err)
		}
		if _, ok, err := st.LeasePreview(ctx, JobProxy); err != nil || !ok {
			t.Fatal("no lease", err)
		}
		if err := st.FailPreview(ctx, "i1", JobProxy, "failed: no decoder found for: pcm_mulaw"); err != nil {
			t.Fatal(err)
		}
	}
	state := func() string {
		p, err := st.Previews(ctx, "i1")
		if err != nil {
			t.Fatal(err)
		}
		return p[JobProxy].State
	}
	fail()
	if state() != store.PreviewFailed {
		t.Fatal(state())
	}
	m.retryAfterToolsChange(ctx) // first start with a tools id: retried
	if state() != store.PreviewPending {
		t.Fatalf("not retried: %s", state())
	}
	if _, ok, err := st.LeasePreview(ctx, JobProxy); err != nil || !ok {
		t.Fatal("no lease", err)
	}
	_ = st.FailPreview(ctx, "i1", JobProxy, "failed again")
	m.retryAfterToolsChange(ctx) // same tools: stays failed
	if state() != store.PreviewFailed {
		t.Fatalf("retried with the same tools: %s", state())
	}
	g.id = "b"
	m.retryAfterToolsChange(ctx)
	if state() != store.PreviewPending {
		t.Fatalf("not retried after a change: %s", state())
	}
}

type toolsGen struct{ id string }

func (g *toolsGen) Name() string                                                    { return "fake" }
func (g *toolsGen) Accepts(Job) bool                                                { return true }
func (g *toolsGen) Available() bool                                                 { return true }
func (g *toolsGen) ToolsID(context.Context) string                                  { return g.id }
func (g *toolsGen) Generate(context.Context, Job, *os.File, string) (Result, error) { return nil, nil }
