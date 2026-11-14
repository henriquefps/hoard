package previews

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/henriquefps/hoard/server/internal/store"
	"image/jpeg"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/media"
)

// The CPU generator's tests run the real programs: vips and ffmpeg from PATH (or VIPS / FFMPEG). Without them they
// skip; CI installs both (.github/workflows/server.yml).

func testCPU(t *testing.T, needVideo bool) *CPU {
	t.Helper()
	c := NewCPU(envOr("VIPS", "vips"), envOr("FFMPEG", "ffmpeg"), 2)
	if !c.HasVips() || needVideo && !c.HasFFmpeg() {
		t.Skip("vips/ffmpeg not installed")
	}
	return c
}

func envOr(k, v string) string {
	if s := os.Getenv(k); s != "" {
		return s
	}
	return v
}

func hasLoader(t *testing.T, c *CPU, name string) bool {
	out, err := exec.Command(c.vips, "-l", "foreign").Output()
	return err == nil && bytes.Contains(out, []byte("("+name+")"))
}

// ffmpegRun runs an ffmpeg to make fixtures (lavfi sources only: nothing from outside the test). FIXTURE_FFMPEG names
// a full build when the one under test is the image's minimal build (no lavfi, no test sources).
func ffmpegRun(t *testing.T, c *CPU, args ...string) {
	t.Helper()
	bin := envOr("FIXTURE_FFMPEG", c.ffmpeg)
	if bin == "" {
		t.Skip("no ffmpeg for fixtures")
	}
	cmd := exec.Command(bin, append([]string{"-nostdin", "-hide_banner", "-loglevel", "error", "-y"}, args...)...)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("fixture: ffmpeg %v: %v: %s", args, err, out)
	}
}

// testJPEG writes a w×h test-pattern JPEG (ffmpeg → PPM → vips).
func testJPEG(t *testing.T, c *CPU, dir string, w, h int) string {
	t.Helper()
	ppm := filepath.Join(dir, "src.ppm")
	ffmpegRun(t, c, "-f", "lavfi", "-i", fmt.Sprintf("testsrc2=s=%dx%d", w, h), "-frames:v", "1", "-c:v", "ppm", "-f", "image2", ppm)
	out := filepath.Join(dir, "src.jpg")
	if b, err := exec.Command(c.vips, "copy", ppm, out+"[Q=90]").CombinedOutput(); err != nil {
		t.Fatalf("vips copy: %v %s", err, b)
	}
	return out
}

func generate(t *testing.T, c *CPU, j Job, path string) (Result, string) {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	dir := t.TempDir()
	res, err := c.Generate(context.Background(), j, f, dir)
	if err != nil {
		t.Fatalf("generate %s: %v", j.Job, err)
	}
	return res, dir
}

func checkImages(t *testing.T, res Result, dir string, wantW, wantH int) {
	t.Helper()
	s, th := res[FileScreen], res[FileThumb]
	if s.Width != wantW || s.Height != wantH {
		t.Fatalf("screen = %dx%d, want %dx%d", s.Width, s.Height, wantW, wantH)
	}
	if max(th.Width, th.Height) != min(ThumbEdge, max(wantW, wantH)) || (th.Width > th.Height) != (wantW > wantH) {
		t.Fatalf("thumb = %dx%d for screen %dx%d", th.Width, th.Height, wantW, wantH)
	}
	for _, name := range []string{FileThumbWebP, FileScreenWebP} {
		o, ok := res[name]
		b, err := os.ReadFile(filepath.Join(dir, name))
		if !ok || err != nil || len(b) < 12 || string(b[:4]) != "RIFF" || string(b[8:12]) != "WEBP" {
			t.Fatalf("%s: %v %v", name, ok, err)
		}
		if o.Width != res[strings.Replace(name, ".webp", ".jpg", 1)].Width {
			t.Fatalf("%s: size %+v", name, o)
		}
	}
	for name, o := range res {
		fi, err := os.Stat(filepath.Join(dir, name))
		if err != nil || fi.Size() != o.Bytes || o.Bytes == 0 {
			t.Fatalf("%s: %v size %d vs %d", name, err, fi.Size(), o.Bytes)
		}
	}
	if entries, _ := os.ReadDir(dir); len(entries) != len(res) {
		t.Fatalf("left behind in the job directory: %d files for %d outputs", len(entries), len(res))
	}
}

func TestCPUStillJPEGOrientation(t *testing.T) {
	c := testCPU(t, false)
	dir := t.TempDir()
	src := testJPEG(t, c, dir, 4000, 3000)
	b, _ := os.ReadFile(src)
	rotated := filepath.Join(dir, "rotated.jpg")
	if err := os.WriteFile(rotated, media.WithOrientation(b, 6), 0o600); err != nil {
		t.Fatal(err)
	}
	res, out := generate(t, c, Job{Job: JobImage, Source: Source{Role: media.RoleRendered, Format: "jpeg"}}, src)
	checkImages(t, res, out, 2560, 1920)
	res, out = generate(t, c, Job{Job: JobImage, Source: Source{Role: media.RoleRendered, Format: "jpeg"}}, rotated)
	checkImages(t, res, out, 1920, 2560) // EXIF orientation 6: upright is portrait
	// Never upscaled.
	small := testJPEG(t, c, t.TempDir(), 800, 600)
	res, out = generate(t, c, Job{Job: JobImage, Source: Source{Role: media.RoleRendered}}, small)
	checkImages(t, res, out, 800, 600)
}

// meanRGB decodes a JPEG and averages its centre.
func meanRGB(t *testing.T, path string) [3]float64 {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	img, err := jpeg.Decode(f)
	if err != nil {
		t.Fatal(err)
	}
	b := img.Bounds()
	var sum [3]float64
	n := 0.0
	for y := b.Min.Y + b.Dy()/4; y < b.Max.Y-b.Dy()/4; y++ {
		for x := b.Min.X + b.Dx()/4; x < b.Max.X-b.Dx()/4; x++ {
			r, g, bl, _ := img.At(x, y).RGBA()
			sum[0] += float64(r >> 8)
			sum[1] += float64(g >> 8)
			sum[2] += float64(bl >> 8)
			n++
		}
	}
	return [3]float64{sum[0] / n, sum[1] / n, sum[2] / n}
}

// TestCPUStillICC: a Display P3 JPEG comes out as sRGB with the same look (the profile applied, not ignored).
func TestCPUStillICC(t *testing.T) {
	c := testCPU(t, false)
	dir := t.TempDir()
	// A flat sRGB colour (rgb 200,100,50), then the same colour stored as Display P3 with its profile.
	srgb := filepath.Join(dir, "flat.jpg")
	p3 := filepath.Join(dir, "p3.jpg")
	if b, err := exec.Command(c.vips, "black", filepath.Join(dir, "black.v"), "640", "480", "--bands", "3").CombinedOutput(); err != nil {
		t.Fatalf("%v %s", err, b)
	}
	if b, err := exec.Command(c.vips, "linear", filepath.Join(dir, "black.v"), filepath.Join(dir, "flat.v"), "1 1 1", "200 100 50", "--uchar").CombinedOutput(); err != nil {
		t.Fatalf("%v %s", err, b)
	}
	if b, err := exec.Command(c.vips, "copy", filepath.Join(dir, "flat.v"), srgb+"[Q=95]").CombinedOutput(); err != nil {
		t.Fatalf("%v %s", err, b)
	}
	if b, err := exec.Command(c.vips, "icc_transform", filepath.Join(dir, "flat.v"), p3+"[Q=95]", "p3", "--input-profile", "srgb", "--embedded").CombinedOutput(); err != nil {
		t.Skipf("no Display P3 profile in this libvips: %v %s", err, b)
	}
	stored := meanRGB(t, p3)
	if stored[0] > 195 { // P3 numbers for this colour are visibly different (about 189, 104, 60)
		t.Skipf("the P3 file is not different from sRGB: %v", stored)
	}
	_, out := generate(t, c, Job{Job: JobImage, Source: Source{Role: media.RoleRendered}}, p3)
	got := meanRGB(t, filepath.Join(out, FileScreen))
	for i, want := range []float64{200, 100, 50} {
		if d := got[i] - want; d > 4 || d < -4 {
			t.Fatalf("screen preview of a P3 photo = %v, want about 200,100,50 (stored P3 values %v)", got, stored)
		}
	}
}

func TestCPUStillHEIFAndAVIF(t *testing.T) {
	c := testCPU(t, false)
	if !hasLoader(t, c, "heifload") {
		t.Skip("libvips without libheif")
	}
	for _, name := range []string{"synthetic.heic", "synthetic.avif"} {
		f, err := os.Open(filepath.Join("testdata", name))
		if err != nil {
			t.Fatal(err)
		}
		dir := t.TempDir()
		res, err := c.Generate(context.Background(), Job{Job: JobImage, Source: Source{Role: media.RoleRendered, Format: "heif"}}, f, dir)
		f.Close()
		if err != nil {
			if strings.Contains(err.Error(), "decoder") || strings.Contains(err.Error(), "No decoding plugin") || strings.Contains(err.Error(), "Unsupported") {
				t.Logf("%s: no decoder plugin in this libheif: %v", name, err)
				continue
			}
			t.Fatalf("%s: %v", name, err)
		}
		checkImages(t, res, dir, 320, 240)
	}
}

// TestCPURawEmbeddedPreview: a RAW with a big enough embedded JPEG uses it, upright by the RAW's orientation.
func TestCPURawEmbeddedPreview(t *testing.T) {
	c := testCPU(t, false)
	dir := t.TempDir()
	preview, err := os.ReadFile(testJPEG(t, c, dir, 3000, 2000))
	if err != nil {
		t.Fatal(err)
	}
	raw := filepath.Join(dir, "shot.arw")
	if err := os.WriteFile(raw, syntheticRAW(preview), 0o600); err != nil {
		t.Fatal(err)
	}
	res, out := generate(t, c, Job{Job: JobImage, Source: Source{Role: media.RoleRaw, Format: "tiff", Orientation: 8, LongEdge: 6000}}, raw)
	checkImages(t, res, out, 1707, 2560)
	// Not a RAW and no preview: unsupported (or a LibRaw failure), never a crash.
	junk := filepath.Join(dir, "junk.arw")
	_ = os.WriteFile(junk, bytes.Repeat([]byte{1}, 4096), 0o600)
	f, _ := os.Open(junk)
	defer f.Close()
	if _, err := c.Generate(context.Background(), Job{Job: JobImage, Source: Source{Role: media.RoleRaw}}, f, t.TempDir()); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("junk RAW: %v", err)
	}
}

// syntheticRAW is a little-endian TIFF whose IFD0 points at the JPEG (JPEGInterchangeFormat), appended after it.
func syntheticRAW(j []byte) []byte {
	le := func(v uint32, n int) []byte {
		b := make([]byte, n)
		for i := range n {
			b[i] = byte(v >> (8 * i))
		}
		return b
	}
	head := []byte{'I', 'I', 42, 0, 8, 0, 0, 0}
	ifd := le(2, 2)
	off := uint32(8 + 2 + 2*12 + 4)
	ifd = append(ifd, append(append(le(0x0201, 2), le(4, 2)...), append(le(1, 4), le(off, 4)...)...)...)
	ifd = append(ifd, append(append(le(0x0202, 2), le(4, 2)...), append(le(1, 4), le(uint32(len(j)), 4)...)...)...)
	ifd = append(ifd, le(0, 4)...)
	return append(append(head, ifd...), j...)
}

// TestCPURealSamples makes the previews of real camera files and tries LibRaw on each RAW (opt-in: HOARD_SAMPLES_DIR;
// HOARD_PREVIEW_OUT keeps the files to look at).
func TestCPURealSamples(t *testing.T) {
	dir := os.Getenv("HOARD_SAMPLES_DIR")
	if dir == "" {
		t.Skip("HOARD_SAMPLES_DIR not set")
	}
	c := testCPU(t, false)
	entries, _ := os.ReadDir(dir)
	keep := os.Getenv("HOARD_PREVIEW_OUT")
	for _, e := range entries {
		_, role := media.Classify(e.Name())
		if role != media.RoleRaw && role != media.RoleRendered {
			continue
		}
		p := filepath.Join(dir, e.Name())
		f, _ := os.Open(p)
		fi, _ := f.Stat()
		m, _ := media.ReadImage(f, fi.Size())
		f.Close()
		j := Job{Job: JobImage, Source: Source{Role: role, Format: m.Format, Orientation: m.Orientation, LongEdge: max(m.Width, m.Height)}}
		res, out := generate(t, c, j, p)
		t.Logf("%s: screen %dx%d %d B, thumb %dx%d %d B", e.Name(), res[FileScreen].Width, res[FileScreen].Height,
			res[FileScreen].Bytes, res[FileThumb].Width, res[FileThumb].Height, res[FileThumb].Bytes)
		if keep != "" {
			for name := range res {
				b, _ := os.ReadFile(filepath.Join(out, name))
				_ = os.WriteFile(filepath.Join(keep, e.Name()+"."+name), b, 0o600)
			}
		}
	}
	if hasLoader(t, c, "dcrawload") {
		for _, e := range entries {
			if _, role := media.Classify(e.Name()); role != media.RoleRaw {
				continue
			}
			f, _ := os.Open(filepath.Join(dir, e.Name()))
			out := t.TempDir()
			start := time.Now()
			res, err := c.decodeRaw(context.Background(), f, out)
			f.Close()
			t.Logf("%s: LibRaw decode: %v %v (%v)", e.Name(), res[FileScreen], err, time.Since(start).Round(time.Millisecond))
			if keep != "" && err == nil {
				b, _ := os.ReadFile(filepath.Join(out, FileScreen))
				_ = os.WriteFile(filepath.Join(keep, e.Name()+".libraw."+FileScreen), b, 0o600)
			}
		}
	}
}

// --- video ---------------------------------------------------------------------------------------------------------

type probed struct {
	Streams []struct {
		CodecType string `json:"codec_type"`
		CodecName string `json:"codec_name"`
		Width     int    `json:"width"`
		Height    int    `json:"height"`
		PixFmt    string `json:"pix_fmt"`
		Rate      string `json:"avg_frame_rate"`
		Transfer  string `json:"color_transfer"`
	} `json:"streams"`
	Format struct {
		BitRate string `json:"bit_rate"`
	} `json:"format"`
}

func ffprobe(t *testing.T, path string) probed {
	t.Helper()
	bin := envOr("FFPROBE", "ffprobe")
	out, err := exec.Command(bin, "-v", "error", "-print_format", "json", "-show_streams", "-show_format", path).Output()
	if err != nil {
		t.Skipf("ffprobe: %v", err)
	}
	var p probed
	if err := json.Unmarshal(out, &p); err != nil {
		t.Fatal(err)
	}
	return p
}

// testVideo writes an H.264 clip: 1 s of black then a test pattern (a poster must skip the black), with audio.
func testVideo(t *testing.T, c *CPU, dir, name string, w, h int, fps int, extra ...string) string {
	t.Helper()
	out := filepath.Join(dir, name)
	args := []string{"-f", "lavfi", "-i", fmt.Sprintf("color=black:s=%dx%d:r=%d:d=1", w, h, fps),
		"-f", "lavfi", "-i", fmt.Sprintf("testsrc2=s=%dx%d:r=%d:d=4", w, h, fps),
		"-f", "lavfi", "-i", "sine=f=440:d=5",
		"-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]", "-map", "[v]", "-map", "2:a"}
	args = append(args, extra...)
	if len(extra) == 0 {
		args = append(args, "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p")
	}
	args = append(args, "-c:a", "aac", "-shortest", out)
	ffmpegRun(t, c, args...)
	return out
}

func videoSource(t *testing.T, path string) Source {
	t.Helper()
	p := ffprobe(t, path)
	v := &media.Video{DurationMs: 5000}
	for _, s := range p.Streams {
		if s.CodecType == "video" {
			v.Width, v.Height = s.Width, s.Height
			if s.Transfer == "smpte2084" {
				v.HDR = "pq"
			}
			var n, d float64
			if _, err := fmt.Sscanf(s.Rate, "%g/%g", &n, &d); err == nil && d > 0 {
				v.FPS = n / d
			}
		}
	}
	return Source{Role: media.RoleVideo, Format: "mp4", Video: v}
}

func TestCPUPosterSkipsBlack(t *testing.T) {
	c := testCPU(t, true)
	dir := t.TempDir()
	src := testVideo(t, c, dir, "clip.mp4", 1280, 720, 30)
	s := videoSource(t, src)
	// 5 s: the first candidate (10 %, 0.5 s) is in the black second, the next (25 %) is not.
	res, out := generate(t, c, Job{Job: JobImage, Source: s}, src)
	checkImages(t, res, out, 1280, 720)
	if l := meanRGB(t, filepath.Join(out, FileScreen)); l[0]+l[1]+l[2] < 3*darkLuma {
		t.Fatalf("poster is dark: %v", l)
	}
}

func TestCPUProxy(t *testing.T) {
	c := testCPU(t, true)
	dir := t.TempDir()
	src := testVideo(t, c, dir, "clip60.mp4", 1920, 1080, 60)
	s := videoSource(t, src)
	res, out := generate(t, c, Job{Job: JobProxy, Source: s}, src)
	o := res[FileProxy]
	if o.Width != 1280 || o.Height != 720 || o.Bytes == 0 {
		t.Fatalf("proxy = %+v", o)
	}
	p := ffprobe(t, filepath.Join(out, FileProxy))
	var gotV, gotA bool
	for _, st := range p.Streams {
		switch st.CodecType {
		case "video":
			gotV = st.CodecName == "h264" && st.Width == 1280 && st.Height == 720 && st.PixFmt == "yuv420p" && st.Rate == "30/1"
		case "audio":
			gotA = st.CodecName == "aac"
		}
	}
	if !gotV || !gotA {
		t.Fatalf("proxy streams: %+v", p.Streams)
	}
	// faststart: the index (moov) comes before the media (mdat).
	b, _ := os.ReadFile(filepath.Join(out, FileProxy))
	if m, d := bytes.Index(b, []byte("moov")), bytes.Index(b, []byte("mdat")); m < 0 || d < 0 || m > d {
		t.Fatalf("moov at %d, mdat at %d: not faststart", m, d)
	}
}

// TestCPUProxyRotatedHDR: a portrait (display-rotated) 10-bit PQ clip gives an upright 8-bit BT.709 proxy and poster.
func TestCPUProxyRotatedHDR(t *testing.T) {
	c := testCPU(t, true)
	dir := t.TempDir()
	plain := testVideo(t, c, dir, "hdr.mp4", 1280, 720, 30, "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
		"-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc")
	rotated := filepath.Join(dir, "hdr-rotated.mp4")
	ffmpegRun(t, c, "-display_rotation", "90", "-i", plain, "-c", "copy", rotated)
	s := videoSource(t, rotated)
	if s.Video.HDR != "pq" {
		t.Skip("this ffmpeg did not tag the clip as PQ")
	}
	s.Video.Rotation = 270
	res, out := generate(t, c, Job{Job: JobProxy, Source: s}, rotated)
	p := ffprobe(t, filepath.Join(out, FileProxy))
	for _, st := range p.Streams {
		if st.CodecType == "video" && (st.Width != 720 || st.Height != 1280 || st.PixFmt != "yuv420p" || st.Transfer == "smpte2084") {
			t.Fatalf("proxy video: %+v (want upright 720x1280 8-bit SDR)", st)
		}
	}
	if res[FileProxy].Width != 720 || res[FileProxy].Height != 1280 {
		t.Fatalf("reported %+v", res[FileProxy])
	}
	res, out = generate(t, c, Job{Job: JobImage, Source: s}, rotated)
	checkImages(t, res, out, 720, 1280)
}

func TestCPUMotionEmbeddedProxy(t *testing.T) {
	c := testCPU(t, true)
	dir := t.TempDir()
	clip, _ := os.ReadFile(testVideo(t, c, dir, "m.mp4", 640, 480, 30))
	still, _ := os.ReadFile(testJPEG(t, c, dir, 1600, 1200))
	motion := filepath.Join(dir, "MVIMG.jpg")
	_ = os.WriteFile(motion, append(append([]byte{}, still...), clip...), 0o600)
	s := videoSource(t, filepath.Join(dir, "m.mp4"))
	s.Offset, s.Length = int64(len(still)), int64(len(clip))
	res, out := generate(t, c, Job{Job: JobProxy, Source: s}, motion)
	if res[FileProxy].Height != 480 {
		t.Fatalf("proxy = %+v", res[FileProxy])
	}
	if p := ffprobe(t, filepath.Join(out, FileProxy)); len(p.Streams) == 0 {
		t.Fatal("no streams")
	}
}

func TestMeanLumaAndProxySize(t *testing.T) {
	ppm := append([]byte("P6\n2 1\n255\n"), 255, 255, 255, 0, 0, 0)
	if l := meanLuma(ppm); l != 255 { // every 7th pixel: only the first
		t.Fatalf("luma %v", l)
	}
	if meanLuma([]byte("P5\n1 1\n255\n\x00")) != -1 || meanLuma(nil) != -1 {
		t.Fatal("bad PPMs")
	}
	for _, c := range []struct{ w, h, ew, eh int }{
		{3840, 2160, 1280, 720}, {2160, 3840, 720, 1280}, {640, 480, 640, 480}, {1440, 1080, 960, 720}, {1081, 721, 1080, 720}, {0, 1, 0, 0},
	} {
		if w, h := ProxySize(c.w, c.h); w != c.ew || h != c.eh {
			t.Errorf("ProxySize(%d,%d) = %d,%d want %d,%d", c.w, c.h, w, h, c.ew, c.eh)
		}
	}
}

// TestCPUEditedPreview: Reveal's uploaded edit (ADR 0020), a JPEG or a WebP, makes the same four files as an item's
// image job, never upscaled.
func TestCPUEditedPreview(t *testing.T) {
	c := testCPU(t, false)
	dir := t.TempDir()
	src := testJPEG(t, c, dir, 2560, 1440)
	webp := filepath.Join(dir, "edit.webp")
	if b, err := exec.Command(c.vips, "copy", src, webp+"[Q=85]").CombinedOutput(); err != nil {
		t.Fatalf("vips copy: %v %s", err, b)
	}
	for _, p := range []string{src, webp} {
		jobs := PlanEdits("item", []store.Edit{{VersionID: "v1", RecipeDigest: "r1", SHA256: strings.Repeat("a", 64), Format: "jpeg", Width: 2560, Height: 1440}})
		j := jobs[EditJob("v1")]
		if Lane(j.Job) != JobImage || j.Source.Role != RoleEdit {
			t.Fatalf("%+v", j)
		}
		res, out := generate(t, c, j, p)
		checkImages(t, res, out, 2560, 1440)
	}
	small := testJPEG(t, c, t.TempDir(), 1200, 900)
	res, out := generate(t, c, PlanEdits("item", []store.Edit{{VersionID: "v2", SHA256: strings.Repeat("b", 64)}})[EditJob("v2")], small)
	checkImages(t, res, out, 1200, 900)
}

func TestEditKeys(t *testing.T) {
	e := store.Edit{VersionID: "v1", RecipeDigest: "r1", SHA256: strings.Repeat("a", 64)}
	k := PlanEdits("item", []store.Edit{e})[EditJob("v1")].Key
	for _, mod := range []func(*store.Edit){
		func(e *store.Edit) { e.RecipeDigest = "r2" },
		func(e *store.Edit) { e.SHA256 = strings.Repeat("c", 64) },
		func(e *store.Edit) { e.VersionID = "v2" },
	} {
		e2 := e
		mod(&e2)
		if PlanEdits("item", []store.Edit{e2})[EditJob(e2.VersionID)].Key == k {
			t.Fatalf("same key for %+v", e2)
		}
	}
	if PlanEdits("other", []store.Edit{e})[EditJob("v1")].Key == k {
		t.Fatal("same key on another item")
	}
	if PlanEdits("item", []store.Edit{{VersionID: "v1", RecipeDigest: "r1", SHA256: strings.Repeat("a", 64), Label: "renamed", Primary: true}})[EditJob("v1")].Key != k {
		t.Fatal("metadata changed the key")
	}
}
