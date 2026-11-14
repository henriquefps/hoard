package previews

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/henriquefps/hoard/server/internal/media"
)

// CPU makes previews with libvips (stills: shrink-on-load, ICC to sRGB, EXIF orientation) and ffmpeg (poster frames
// and proxies: x264, zscale + tonemap for HDR) as separate processes, each reading the source as an inherited file
// descriptor (never by name) and writing only into the job's directory (ADR 0015 "CPU generator").
type CPU struct {
	vips, ffmpeg string
	threads      int
	timeout      time.Duration // an image job; a proxy gets more, by duration
}

// NewCPU finds the programs (paths or names in PATH; a missing one disables its jobs) and sets the threads each run
// may use.
func NewCPU(vips, ffmpeg string, threads int) *CPU {
	c := &CPU{threads: max(threads, 1), timeout: 2 * time.Minute}
	if p, err := exec.LookPath(vips); err == nil && vips != "" {
		c.vips = p
	}
	if p, err := exec.LookPath(ffmpeg); err == nil && ffmpeg != "" {
		c.ffmpeg = p
	}
	return c
}

// Name implements Generator.
func (c *CPU) Name() string { return "cpu" }

// Accepts implements Generator: every job.
func (c *CPU) Accepts(Job) bool { return true }

// Available implements Generator.
func (c *CPU) Available() bool { return c.vips != "" && c.ffmpeg != "" }

// HasVips and HasFFmpeg report each program.
func (c *CPU) HasVips() bool   { return c.vips != "" }
func (c *CPU) HasFFmpeg() bool { return c.ffmpeg != "" }

// fdInput is how a child process names the inherited source (ExtraFiles[0] is descriptor 3).
func fdInput() string {
	if runtime.GOOS == "linux" {
		return "/proc/self/fd/3"
	}
	return "/dev/fd/3"
}

// videoFormats is the ffmpeg -format_whitelist (the containers phones and cameras write, as for ffprobe).
const videoFormats = "mov,mp4,m4a,3gp,3g2,mj2,mpegts,matroska,webm,avi"

// Generate implements Generator.
func (c *CPU) Generate(ctx context.Context, j Job, src *os.File, dir string) (Result, error) {
	switch {
	case j.Job == JobFull:
		if c.vips == "" {
			return nil, ErrNoTools
		}
		return c.full(ctx, j, src, dir)
	case j.Job == JobProxy:
		if c.ffmpeg == "" {
			return nil, ErrNoTools
		}
		return c.proxy(ctx, j, src, dir)
	case j.Source.Role == media.RoleVideo:
		if c.ffmpeg == "" || c.vips == "" {
			return nil, ErrNoTools
		}
		return c.poster(ctx, j, src, dir)
	case j.Source.Role == media.RoleRaw:
		if c.vips == "" {
			return nil, ErrNoTools
		}
		return c.raw(ctx, j, src, dir)
	default:
		if c.vips == "" {
			return nil, ErrNoTools
		}
		return c.still(ctx, fdInput(), src, dir, true)
	}
}

// run runs a program with src as descriptor 3, the given extra environment and a time limit; stdout goes to out
// (when not nil, at most limit bytes), and the first line of stderr makes the error.
func (c *CPU) run(ctx context.Context, timeout time.Duration, src *os.File, env []string, out io.Writer, name string, args ...string) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	//nolint:gosec // the binaries are the configured vips/ffmpeg; arguments are built here and inputs are descriptors
	cmd := exec.CommandContext(ctx, name, args...)
	if src != nil {
		cmd.ExtraFiles = []*os.File{src}
	}
	cmd.Env = append([]string{"PATH=/usr/bin:/bin", "HOME=/nonexistent", "LD_LIBRARY_PATH=" + os.Getenv("LD_LIBRARY_PATH"),
		"VIPS_CONCURRENCY=" + strconv.Itoa(c.threads), "VIPS_WARNING=0", "G_MESSAGES_DEBUG="}, env...)
	cmd.Stdout = out
	var stderr bytes.Buffer
	cmd.Stderr = &limitWriter{w: &stderr, n: 4096}
	if err := cmd.Start(); err != nil {
		return err
	}
	lowerPriority(cmd.Process.Pid)
	err := cmd.Wait()
	if ctx.Err() == context.DeadlineExceeded {
		return fmt.Errorf("%s: timed out after %s", filepath.Base(name), timeout)
	}
	if err != nil {
		msg := strings.TrimSpace(stderr.String())
		if i := strings.IndexByte(msg, '\n'); i >= 0 {
			msg = msg[:i]
		}
		if len(msg) > 300 {
			msg = msg[:300]
		}
		return fmt.Errorf("%s: %w: %s", filepath.Base(name), err, msg)
	}
	return nil
}

type limitWriter struct {
	w io.Writer
	n int
}

func (l *limitWriter) Write(p []byte) (int, error) {
	if l.n > 0 {
		k := min(len(p), l.n)
		_, _ = l.w.Write(p[:k])
		l.n -= k
	}
	return len(p), nil
}

// still makes the thumb and screen previews, JPEG and WebP, from an image libvips reads: the screen size from the
// source (shrink-on-load, EXIF orientation applied, the embedded ICC profile converted to sRGB) into an uncompressed
// intermediate, both screen files from it, and the thumbnails from it (one decode of the source, no generation loss).
// input is fdInput() (with src) or a file in dir. blockUntrusted limits libvips to its fuzzed loaders (JPEG, PNG,
// WebP, HEIF/AVIF) for files from outside; LibRaw and the PPM frames ffmpeg writes here need it off.
func (c *CPU) still(ctx context.Context, input string, src *os.File, dir string, blockUntrusted bool) (Result, error) {
	var env []string
	if blockUntrusted {
		env = append(env, "VIPS_BLOCK_UNTRUSTED=1")
	}
	inter := filepath.Join(dir, "screen.v")
	defer os.Remove(inter)
	if err := c.run(ctx, c.timeout, src, env, nil, c.vips, "thumbnail", input, inter, strconv.Itoa(ScreenEdge),
		"--size", "down", "--export-profile", "srgb", "--intent", "perceptual"); err != nil {
		return nil, err
	}
	steps := [][]string{
		{"copy", inter, fmt.Sprintf("%s[Q=%d,optimize_coding,interlace,keep=none]", filepath.Join(dir, FileScreen), ScreenQuality)},
		{"copy", inter, fmt.Sprintf("%s[Q=%d,effort=4,keep=none]", filepath.Join(dir, FileScreenWebP), ScreenWebPQuality)},
		{"thumbnail", inter, fmt.Sprintf("%s[Q=%d,optimize_coding,keep=none]", filepath.Join(dir, FileThumb), ThumbQuality),
			strconv.Itoa(ThumbEdge), "--size", "down"},
		{"thumbnail", inter, fmt.Sprintf("%s[Q=%d,effort=4,keep=none]", filepath.Join(dir, FileThumbWebP), ThumbWebPQuality),
			strconv.Itoa(ThumbEdge), "--size", "down"},
	}
	for _, args := range steps {
		if err := c.run(ctx, c.timeout, nil, nil, nil, c.vips, args...); err != nil {
			return nil, err
		}
	}
	res, err := jpegOutputs(dir, FileScreen, FileThumb)
	if err != nil {
		return nil, err
	}
	for webp, jpeg := range map[string]string{FileScreenWebP: FileScreen, FileThumbWebP: FileThumb} {
		fi, err := os.Stat(filepath.Join(dir, webp))
		if err != nil {
			return nil, err
		}
		res[webp] = Output{Width: res[jpeg].Width, Height: res[jpeg].Height, Bytes: fi.Size()}
	}
	return res, nil
}

func jpegOutputs(dir string, names ...string) (Result, error) {
	res := Result{}
	for _, n := range names {
		f, err := os.Open(filepath.Join(dir, n))
		if err != nil {
			return nil, err
		}
		fi, err := f.Stat()
		if err != nil {
			f.Close()
			return nil, err
		}
		w, h, ok := media.JPEGSize(f, fi.Size())
		f.Close()
		if !ok {
			return nil, fmt.Errorf("%s: not a JPEG", n)
		}
		res[n] = Output{Width: w, Height: h, Bytes: fi.Size()}
	}
	return res, nil
}

// maxEmbedded bounds the embedded preview read into memory.
const maxEmbedded = 64 << 20

// minEmbeddedEdge: an embedded JPEG at least this long (or as long as the RAW, if that is smaller) is used rather
// than decoding the RAW. The camera's own rendering (its tone curve, picture style, lens corrections) looks like the
// shot; LibRaw's neutral decode does not, and some RAWs (Apple ProRAW DNGs) it decodes wrongly. Sony's previews are
// 1616 px: their screen preview is that size.
const minEmbeddedEdge = 1536

// raw makes a RAW's previews: from its largest embedded JPEG when that is big enough (minEmbeddedEdge), else by
// decoding the RAW (LibRaw, through libvips), else from the largest embedded JPEG anyway.
func (c *CPU) raw(ctx context.Context, j Job, src *os.File, dir string) (Result, error) {
	fi, err := src.Stat()
	if err != nil {
		return nil, err
	}
	previews := media.RawPreviews(src, fi.Size())
	need := minEmbeddedEdge
	if j.Source.LongEdge > 0 {
		need = min(need, j.Source.LongEdge)
	}
	useEmbedded := func(e media.Embedded) (Result, error) {
		if e.Length > maxEmbedded {
			return nil, ErrUnsupported
		}
		b := make([]byte, e.Length)
		if _, err := src.ReadAt(b, e.Offset); err != nil {
			return nil, err
		}
		p := filepath.Join(dir, "embedded.jpg")
		//nolint:gosec // p is the job's own temporary directory plus a fixed name
		if err := os.WriteFile(p, media.WithOrientation(b, j.Source.Orientation), 0o600); err != nil {
			return nil, err
		}
		defer os.Remove(p)
		return c.still(ctx, p, nil, dir, true)
	}
	if len(previews) > 0 && previews[0].LongEdge() >= need {
		return useEmbedded(previews[0])
	}
	res, err := c.decodeRaw(ctx, src, dir)
	if err == nil {
		return res, nil
	}
	if ctx.Err() != nil {
		return nil, err
	}
	if len(previews) > 0 {
		return useEmbedded(previews[0])
	}
	return nil, fmt.Errorf("%w: %w", ErrUnsupported, err)
}

// decodeRaw decodes a RAW with LibRaw (libvips' dcrawload, named explicitly: it finds RAWs by extension, and the
// source is a descriptor) into a temporary image, then makes the previews from it. LibRaw applies the orientation.
func (c *CPU) decodeRaw(ctx context.Context, src *os.File, dir string) (Result, error) {
	decoded := filepath.Join(dir, "decoded.v")
	defer os.Remove(decoded)
	if err := c.run(ctx, c.timeout, src, nil, nil, c.vips, "dcrawload", fdInput(), decoded); err != nil {
		return nil, err
	}
	return c.still(ctx, decoded, nil, dir, false) // LibRaw is not among libvips' fuzzed loaders; its output is ours
}

// --- full resolution -------------------------------------------------------------------------------------------------

// fullEdge is larger than any photo: vips thumbnail with --size down then keeps the size and only applies the
// orientation and the sRGB conversion.
const fullEdge = 100000

// full makes the full-resolution JPEG of a still a browser cannot show itself (ADR 0018 "Full resolution"): a HEIF
// rendering converted, or a lone RAW rendered — its embedded JPEG as it is when that is (nearly) full size, else
// decoded by LibRaw, else (LibRaw failed) the largest embedded JPEG when it is larger than the screen preview.
func (c *CPU) full(ctx context.Context, j Job, src *os.File, dir string) (Result, error) {
	timeout := 2 * c.timeout // a 60 MP LibRaw decode with two threads takes seconds; leave room on a busy machine
	encode := func(input string, src *os.File, blockUntrusted bool) (Result, error) {
		var env []string
		if blockUntrusted {
			env = append(env, "VIPS_BLOCK_UNTRUSTED=1")
		}
		out := fmt.Sprintf("%s[Q=%d,optimize_coding,keep=none]", filepath.Join(dir, FileFull), FullQuality)
		if err := c.run(ctx, timeout, src, env, nil, c.vips, "thumbnail", input, out, strconv.Itoa(fullEdge),
			"--size", "down", "--export-profile", "srgb", "--intent", "perceptual"); err != nil {
			return nil, err
		}
		return jpegOutputs(dir, FileFull)
	}
	if j.Source.Role != media.RoleRaw {
		return encode(fdInput(), src, true)
	}
	fi, err := src.Stat()
	if err != nil {
		return nil, err
	}
	previews := media.RawPreviews(src, fi.Size())
	// The camera's own JPEG is kept as it is (no second encode): the orientation of the RAW goes into a minimal EXIF
	// in front of it, and the browser turns it upright.
	embedded := func(e media.Embedded) (Result, error) {
		if e.Length > maxEmbedded {
			return nil, ErrUnsupported
		}
		b := make([]byte, e.Length)
		if _, err := src.ReadAt(b, e.Offset); err != nil {
			return nil, err
		}
		b = media.WithOrientation(b, j.Source.Orientation)
		//nolint:gosec // the job's own temporary directory plus a fixed name
		if err := os.WriteFile(filepath.Join(dir, FileFull), b, 0o600); err != nil {
			return nil, err
		}
		w, h := e.Width, e.Height
		if j.Source.Orientation >= 5 && j.Source.Orientation <= 8 {
			w, h = h, w
		}
		return Result{FileFull: {Width: w, Height: h, Bytes: int64(len(b))}}, nil
	}
	if len(previews) > 0 && j.Source.LongEdge > 0 && float64(previews[0].LongEdge()) >= FullEmbeddedShare*float64(j.Source.LongEdge) {
		return embedded(previews[0])
	}
	decoded := filepath.Join(dir, "decoded.v")
	defer os.Remove(decoded)
	err = c.run(ctx, timeout, src, nil, nil, c.vips, "dcrawload", fdInput(), decoded)
	if err == nil {
		return encode(decoded, nil, false) // LibRaw's output, written here
	}
	if ctx.Err() != nil {
		return nil, err
	}
	if len(previews) > 0 && previews[0].LongEdge() > ScreenEdge {
		return embedded(previews[0])
	}
	return nil, fmt.Errorf("%w: %w", ErrUnsupported, err)
}

// --- video ---------------------------------------------------------------------------------------------------------

// videoInput is ffmpeg's name of the source: the inherited descriptor, through the subfile protocol for a motion
// photo's embedded video.
func videoInput(s Source) string {
	in := fdInput()
	if s.Offset > 0 || s.Length > 0 {
		end := int64(0)
		if s.Length > 0 {
			end = s.Offset + s.Length
		}
		in = "subfile,,start," + strconv.FormatInt(s.Offset, 10) + ",end," + strconv.FormatInt(end, 10) + ",,:" + in
	}
	return in
}

func (c *CPU) ffmpegArgs() []string {
	return []string{"-nostdin", "-hide_banner", "-loglevel", "error", "-threads", strconv.Itoa(c.threads),
		"-protocol_whitelist", "file,subfile", "-format_whitelist", videoFormats}
}

// hdr reports a source to tone-map.
func hdr(s Source) bool { return s.Video != nil && s.Video.HDR != "" }

// tonemap converts HDR (PQ, HLG, Dolby Vision's base layer) to SDR BT.709 (Hable, zimg), ending in yuv420p.
const tonemap = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p"

// poster finds a frame that is not dark — at 10 %, 25 % and 50 % of the duration, then the first frame — and makes
// thumb.jpg and screen.jpg from it (the brightest candidate when all are dark).
func (c *CPU) poster(ctx context.Context, j Job, src *os.File, dir string) (Result, error) {
	var dur float64
	if j.Source.Video != nil {
		dur = float64(j.Source.Video.DurationMs) / 1000
	}
	times := []float64{0}
	if dur > 0 {
		times = []float64{dur * 0.10, dur * 0.25, dur * 0.50, 0}
	}
	scale := fmt.Sprintf("scale=w='if(gte(iw,ih),min(%d,iw),-2)':h='if(gte(iw,ih),-2,min(%d,ih))'", ScreenEdge, ScreenEdge)
	chains := []string{scale + ",format=rgb24"}
	if hdr(j.Source) {
		chains = []string{tonemap + "," + scale + ",format=rgb24", chains[0]} // without the tone map if zimg cannot read the tags
	}
	var best []byte
	bestLuma := -1.0
	var lastErr error
	for _, t := range times {
		var frame []byte
		for _, chain := range chains {
			var out bytes.Buffer
			args := append(c.ffmpegArgs(), "-ss", strconv.FormatFloat(t, 'f', 3, 64), "-i", videoInput(j.Source),
				"-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn", "-vf", chain, "-f", "image2pipe", "-c:v", "ppm", "pipe:1")
			err := c.run(ctx, c.timeout, src, nil, &limitWriter{w: &out, n: 64 << 20}, c.ffmpeg, args...)
			if err == nil && out.Len() > 0 {
				frame = out.Bytes()
				break
			}
			if err == nil {
				err = errors.New("ffmpeg: no frame")
			}
			lastErr = err
			if ctx.Err() != nil {
				return nil, err
			}
		}
		if frame == nil {
			continue
		}
		l := meanLuma(frame)
		if l > bestLuma {
			best, bestLuma = frame, l
		}
		if l >= darkLuma {
			break
		}
	}
	if best == nil {
		return nil, fmt.Errorf("%w: %w", ErrUnsupported, lastErr)
	}
	p := filepath.Join(dir, "frame.ppm")
	if err := os.WriteFile(p, best, 0o600); err != nil {
		return nil, err
	}
	defer os.Remove(p)
	return c.still(ctx, p, nil, dir, false) // a frame ffmpeg decoded, not the file
}

// darkLuma: a frame whose mean luma (0–255) is below this is "black" (a fade-in, a lens cap).
const darkLuma = 24

// meanLuma reads a binary PPM (P6, maxval 255) and returns its mean BT.601 luma, sampling every 7th pixel (-1 when
// it cannot be read).
func meanLuma(ppm []byte) float64 {
	r := bufio.NewReader(bytes.NewReader(ppm))
	var magic string
	var w, h, maxv int
	if _, err := fmt.Fscan(r, &magic, &w, &h, &maxv); err != nil || magic != "P6" || maxv != 255 || w <= 0 || h <= 0 {
		return -1
	}
	if _, err := r.ReadByte(); err != nil { // the single whitespace after maxval
		return -1
	}
	px, err := io.ReadAll(r)
	if err != nil || len(px) < w*h*3 {
		return -1
	}
	var sum float64
	n := 0
	for i := 0; i+2 < w*h*3; i += 3 * 7 {
		sum += 0.299*float64(px[i]) + 0.587*float64(px[i+1]) + 0.114*float64(px[i+2])
		n++
	}
	if n == 0 {
		return -1
	}
	return sum / float64(n)
}

// ProxySize is the proxy's frame size for a video of display size w×h: the short edge at most ProxyEdge, both
// even, the aspect kept.
func ProxySize(w, h int) (int, int) {
	if w <= 0 || h <= 0 {
		return 0, 0
	}
	even := func(v float64) int { return max(2, int(math.Round(v/2))*2) }
	if w >= h {
		nh := min(ProxyEdge, h) / 2 * 2
		return even(float64(w) * float64(nh) / float64(h)), max(nh, 2)
	}
	nw := min(ProxyEdge, w) / 2 * 2
	return max(nw, 2), even(float64(h) * float64(nw) / float64(w))
}

// proxy encodes the low-bitrate H.264 proxy: short edge ≤ 720, ≤ 30 fps, a keyframe every 2 s, capped CRF
// (≤ 2.5 Mb/s), AAC stereo, faststart (the index first, for streaming and range requests).
func (c *CPU) proxy(ctx context.Context, j Job, src *os.File, dir string) (Result, error) {
	v := j.Source.Video
	fps := 30.0
	timeout := 10 * time.Minute
	var filters []string
	if v != nil {
		if v.FPS > 0 {
			fps = v.FPS
		}
		if v.DurationMs > 0 {
			timeout = 5*time.Minute + 4*time.Duration(v.DurationMs)*time.Millisecond
		}
	}
	if fps > ProxyMaxFPS+0.5 {
		filters = append(filters, fmt.Sprintf("fps=%d", ProxyMaxFPS))
		fps = ProxyMaxFPS
	}
	scale := fmt.Sprintf("scale=w='if(gte(iw,ih),-2,trunc(min(%d,iw)/2)*2)':h='if(gte(iw,ih),trunc(min(%d,ih)/2)*2,-2)'", ProxyEdge, ProxyEdge)
	base := append([]string{}, filters...)
	chains := [][]string{append(append(base[:len(base):len(base)], scale), "format=yuv420p")}
	if hdr(j.Source) {
		chains = append([][]string{append(append(base[:len(base):len(base)], tonemap), scale, "format=yuv420p")}, chains...)
	}
	gop := strconv.Itoa(max(1, int(math.Round(2*fps))))
	out := filepath.Join(dir, FileProxy)
	var err error
	for _, chain := range chains {
		args := append(c.ffmpegArgs(), "-i", videoInput(j.Source),
			"-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
			"-vf", strings.Join(chain, ","),
			"-c:v", "libx264", "-preset", "faster", "-profile:v", "high", "-crf", strconv.Itoa(ProxyCRF),
			"-maxrate", strconv.Itoa(ProxyMaxRate)+"k", "-bufsize", strconv.Itoa(2*ProxyMaxRate)+"k", "-g", gop,
			"-threads", strconv.Itoa(c.threads),
			"-c:a", "aac", "-b:a", strconv.Itoa(ProxyAudio)+"k", "-ac", "2",
			"-movflags", "+faststart", "-f", "mp4", "-y", out)
		err = c.run(ctx, timeout, src, nil, nil, c.ffmpeg, args...)
		if err == nil || ctx.Err() != nil {
			break
		}
	}
	if err != nil {
		return nil, err
	}
	fi, err := os.Stat(out)
	if err != nil {
		return nil, err
	}
	o := Output{Bytes: fi.Size()}
	if v != nil {
		w, h := v.Width, v.Height
		if v.Rotation == 90 || v.Rotation == 270 {
			w, h = h, w
		}
		o.Width, o.Height = ProxySize(w, h)
	}
	return Result{FileProxy: o}, nil
}
