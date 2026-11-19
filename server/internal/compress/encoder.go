package compress

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// EncodeRequest is one encode of one source at one q.
type EncodeRequest struct {
	Src      *os.File // the original, read through its descriptor
	SrcMTime time.Time
	Probe    Probe
	Profile  Profile
	Q        int
	Set      string // lean | quality
	Out      string // the output path in the work directory (the source's container by extension)
	Date     string // yyyy-MM-dd for the marker
	Log      io.Writer
	Progress func(float64) // 0–1, optional
}

// EncodeResult says how an encode went.
type EncodeResult struct {
	Decoder string // cuda | cpu
}

// Encoder makes the output of one source (ADR 0025 "The recipe"): the encode, the metadata restore and the mtime. The
// per-frame part of the metadata sidecar is a separate call, made once before the first encode.
type Encoder interface {
	Encode(ctx context.Context, r EncodeRequest) (EncodeResult, error)
	// Sidecar writes the per-frame metadata of src as gzip JSON (exiftool -ee3 -j -G3 -a).
	Sidecar(ctx context.Context, src *os.File, out io.Writer, log io.Writer) error
}

// ErrEncoder is a failed encode (transient: retried with back-off).
var ErrEncoder = errors.New("encoder failed")

// NvencEncoder runs ffmpeg with hevc_nvenc/av1_nvenc, then exiftool and the mtime.
type NvencEncoder struct {
	FFmpeg   string
	Exiftool string // "" = no metadata restore (logged)
	Timeout  func(durationMs int64) time.Duration
	// Nice lowers the priority of a started process (nil: none).
	Nice func(pid int)
}

// encodeArgs builds the ffmpeg arguments (the ADR's recipe, as an argument vector). in is the input's name.
func encodeArgs(r EncodeRequest, in string, cuda bool) []string {
	a := []string{"-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-stats_period", "2", "-progress", "pipe:1",
		"-protocol_whitelist", "file,pipe", "-format_whitelist", probeFormats}
	if cuda {
		a = append(a, "-hwaccel", "cuda")
	}
	a = append(a, "-noautorotate", "-i", in,
		"-map", "0", "-map", "-0:d", "-map_metadata", "0", "-map_chapters", "0", "-c", "copy")
	maxr := r.Probe.MaxRate()
	codec := "hevc_nvenc"
	if r.Profile.Codec == "av1" {
		codec = "av1_nvenc"
	}
	a = append(a, "-c:v:0", codec, "-rc", "vbr", "-cq", strconv.Itoa(r.Q), "-b:v:0", "0")
	if maxr > 0 {
		a = append(a, "-maxrate:v:0", strconv.FormatInt(maxr, 10), "-bufsize:v:0", strconv.FormatInt(2*maxr, 10))
	}
	a = append(a, "-preset", "p7", "-tune", "hq")
	if r.Set == SetQuality {
		a = append(a, "-multipass", "fullres", "-rc-lookahead", "32", "-bf", "4", "-b_ref_mode", "middle")
	}
	if codec == "hevc_nvenc" {
		a = append(a, "-tag:v:0", "hvc1")
	}
	if r.Probe.TenBit() {
		a = append(a, "-pix_fmt", "p010le")
		if codec == "hevc_nvenc" {
			a = append(a, "-profile:v:0", "main10")
		}
	} else {
		a = append(a, "-pix_fmt", "yuv420p")
		if codec == "hevc_nvenc" {
			a = append(a, "-profile:v:0", "main")
		}
	}
	if r.Probe.PCM() {
		a = append(a, "-c:a", "aac", "-b:a", "256k")
	}
	a = append(a, "-fps_mode", "passthrough", "-enc_time_base:v", "demux",
		"-metadata", fmt.Sprintf("%s=v1, %s-cq%d, %s", Marker, r.Profile.Codec, r.Q, r.Date),
		"-movflags", "+use_metadata_tags+faststart", r.Out)
	return a
}

// Encode implements Encoder: CUDA decode first, once more with CPU decode when that fails (logged).
func (e *NvencEncoder) Encode(ctx context.Context, r EncodeRequest) (EncodeResult, error) {
	res := EncodeResult{Decoder: "cuda"}
	fellBack, err := e.ffmpeg(ctx, r, true)
	if err != nil && ctx.Err() == nil {
		fmt.Fprintf(r.Log, "encode with CUDA decode failed (%v): retrying with CPU decode\n", err)
		_ = os.Remove(r.Out)
		res.Decoder = "cpu"
		_, err = e.ffmpeg(ctx, r, false)
	} else if fellBack {
		// ffmpeg could not set CUDA decode up for this source (an unsupported profile or chroma format) and decoded
		// on the CPU itself: the output is the CPU-decode one.
		fmt.Fprintln(r.Log, "CUDA decode unavailable for this source: ffmpeg decoded on the CPU")
		res.Decoder = "cpu"
	}
	if err != nil {
		_ = os.Remove(r.Out)
		if ctx.Err() != nil {
			return res, ctx.Err()
		}
		return res, fmt.Errorf("%w: %w", ErrEncoder, err)
	}
	// The metadata restore is a gate: an output whose metadata exiftool could not restore never replaces anything.
	if e.Exiftool == "" {
		_ = os.Remove(r.Out)
		return res, &VerifyError{Check: "metadata", Detail: "exiftool is not installed: the metadata cannot be restored"}
	}
	err = e.exiftool(ctx, r)
	// exiftool's temporary file sits next to its target, in the work directory; it may leave one on failure.
	_ = os.Remove(r.Out + "_exiftool_tmp")
	if err != nil {
		_ = os.Remove(r.Out)
		if ctx.Err() != nil {
			return res, ctx.Err()
		}
		fmt.Fprintf(r.Log, "exiftool: %v (metadata not restored)\n", err)
		return res, &VerifyError{Check: "metadata", Detail: "exiftool could not restore the metadata: " + err.Error()}
	}
	err = e.restoreCamera(ctx, r)
	_ = os.Remove(r.Out + "_exiftool_tmp")
	if err != nil {
		_ = os.Remove(r.Out)
		if ctx.Err() != nil {
			return res, ctx.Err()
		}
		fmt.Fprintf(r.Log, "exiftool: %v (camera not written)\n", err)
		return res, &VerifyError{Check: "metadata", Detail: "exiftool could not write the camera: " + err.Error()}
	}
	if !r.SrcMTime.IsZero() {
		if err := os.Chtimes(r.Out, r.SrcMTime, r.SrcMTime); err != nil {
			return res, err
		}
	}
	return res, nil
}

func (e *NvencEncoder) timeout(ms int64) time.Duration {
	if e.Timeout != nil {
		return e.Timeout(ms)
	}
	return 10*time.Minute + time.Duration(ms)*time.Millisecond*4
}

// cudaFallback is what ffmpeg logs when it cannot use CUDA decode for a stream and decodes on the CPU instead.
const cudaFallback = "Failed setup for format cuda"

func (e *NvencEncoder) ffmpeg(ctx context.Context, r EncodeRequest, cuda bool) (fellBack bool, err error) {
	ctx, cancel := context.WithTimeout(ctx, e.timeout(r.Probe.DurationMs))
	defer cancel()
	args := encodeArgs(r, fdInput, cuda)
	fmt.Fprintf(r.Log, "$ ffmpeg %s\n", strings.ReplaceAll(strings.ReplaceAll(strings.Join(args, " "), fdInput, "SRC"), r.Out, "OUT"))
	if _, err := r.Src.Seek(0, io.SeekStart); err != nil {
		return false, err
	}
	//nolint:gosec // the configured ffmpeg; arguments built here; the source is a descriptor, the output a work file
	cmd := exec.CommandContext(ctx, e.FFmpeg, args...)
	cmd.ExtraFiles = []*os.File{r.Src}
	cmd.Env = toolEnv()
	errTail := &tail{n: 64 << 10}
	cmd.Stderr = errTail
	pr, pw := io.Pipe()
	cmd.Stdout = pw
	go readProgress(pr, r.Probe.DurationMs, r.Progress)
	start := time.Now()
	err = cmd.Start()
	if err == nil {
		if e.Nice != nil {
			e.Nice(cmd.Process.Pid)
		}
		err = cmd.Wait()
	}
	_ = pw.Close()
	fmt.Fprintf(r.Log, "ffmpeg (%s decode, q %d) took %d ms\n%s", map[bool]string{true: "cuda", false: "cpu"}[cuda], r.Q,
		time.Since(start).Milliseconds(), errTail.String())
	fellBack = cuda && strings.Contains(errTail.String(), cudaFallback)
	if err != nil {
		if msg := lastLine(errTail.String()); msg != "" {
			return fellBack, fmt.Errorf("%w: %s", err, msg)
		}
	}
	return fellBack, err
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}

// readProgress reads ffmpeg's -progress output (out_time_us=…) into a fraction of the duration.
func readProgress(r io.Reader, durationMs int64, report func(float64)) {
	buf := make([]byte, 4096)
	var acc []byte
	for {
		n, err := r.Read(buf)
		acc = append(acc, buf[:n]...)
		for {
			i := bytes.IndexByte(acc, '\n')
			if i < 0 {
				break
			}
			line := string(acc[:i])
			acc = acc[i+1:]
			if v, ok := strings.CutPrefix(line, "out_time_us="); ok && report != nil && durationMs > 0 {
				if us, err := strconv.ParseInt(v, 10, 64); err == nil && us > 0 {
					report(min(1, float64(us)/1000/float64(durationMs)))
				}
			}
		}
		if err != nil {
			return
		}
	}
}

// restoreCamera writes the source's camera into the output's QuickTime UserData (©mak/©mod: ffprobe's make/model,
// exiftool's UserData:Make/Model) when the restore left the output without one: the families exiftool reads but cannot
// write back (Samsung's smta model, Sony's NRTM XML device) would otherwise lose it, and Hoard's, Reveal's and the web
// gallery's camera filters read it from the file (ADR 0025 Amendment (N4)). Not Keys: exiftool would add a second
// Keys table (moov/meta) next to ffmpeg's (moov/udta/meta), and ffmpeg's reader maps the second one's items through
// the first table.
func (e *NvencEncoder) restoreCamera(ctx context.Context, r EncodeRequest) error {
	st, err := readExif(ctx, e.Exiftool, r.Src, "")
	if err != nil {
		return err
	}
	mk, model := cameraOf(st)
	if mk == "" && model == "" {
		return nil
	}
	ot, err := readExif(ctx, e.Exiftool, nil, r.Out)
	if err != nil {
		return err
	}
	omk, omodel := cameraOf(ot)
	var args, wrote []string
	if mk != "" && omk == "" {
		args = append(args, "-UserData:Make="+mk)
		wrote = append(wrote, "make")
	}
	if model != "" && omodel == "" {
		args = append(args, "-UserData:Model="+model)
		wrote = append(wrote, "model")
	}
	if len(args) == 0 {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	args = append(append([]string{"-q", "-q", "-m", "-overwrite_original"}, args...), "--", r.Out)
	fmt.Fprintf(r.Log, "$ exiftool -UserData:… -- OUT (the camera's %s, from tags exiftool cannot write back)\n", strings.Join(wrote, " and "))
	//nolint:gosec // the configured exiftool; tag assignments built here, values as single arguments; the output a work file
	cmd := exec.CommandContext(ctx, e.Exiftool, args...)
	cmd.Env = toolEnv()
	errTail := &tail{n: 8 << 10}
	cmd.Stderr, cmd.Stdout = errTail, errTail
	err = cmd.Run()
	if s := errTail.String(); s != "" {
		fmt.Fprint(r.Log, s)
	}
	return err
}

// readExif reads `exiftool -j -G1 -a -m` of a file (src as descriptor 3, else path) as its tag object.
func readExif(ctx context.Context, exiftool string, src *os.File, path string) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	in := path
	if src != nil {
		in = fdInput
		if _, err := src.Seek(0, io.SeekStart); err != nil {
			return nil, err
		}
	}
	//nolint:gosec // the configured exiftool; fixed arguments; a descriptor or a work file
	cmd := exec.CommandContext(ctx, exiftool, "-j", "-G1", "-a", "-m", "--", in)
	if src != nil {
		cmd.ExtraFiles = []*os.File{src}
	}
	cmd.Env = toolEnv()
	var out bytes.Buffer
	cmd.Stdout = &limited{w: &out, n: 32 << 20}
	errTail := &tail{n: 8 << 10}
	cmd.Stderr = errTail
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("%w: %s", err, lastLine(errTail.String()))
	}
	var objs []map[string]any
	if err := json.Unmarshal(out.Bytes(), &objs); err != nil {
		return nil, err
	}
	if len(objs) == 0 {
		return map[string]any{}, nil
	}
	return objs[0], nil
}

// exiftool copies every tag of the source onto the output (ADR 0025: "-all:all -unsafe").
func (e *NvencEncoder) exiftool(ctx context.Context, r EncodeRequest) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	if _, err := r.Src.Seek(0, io.SeekStart); err != nil {
		return err
	}
	args := []string{"-q", "-q", "-m", "-overwrite_original", "-tagsFromFile", fdInput, "-all:all", "-unsafe", "--", r.Out}
	fmt.Fprintf(r.Log, "$ exiftool %s\n", strings.ReplaceAll(strings.ReplaceAll(strings.Join(args, " "), fdInput, "SRC"), r.Out, "OUT"))
	//nolint:gosec // the configured exiftool; fixed arguments; the source is a descriptor, the output a work file
	cmd := exec.CommandContext(ctx, e.Exiftool, args...)
	cmd.ExtraFiles = []*os.File{r.Src}
	cmd.Env = toolEnv()
	errTail := &tail{n: 8 << 10}
	cmd.Stderr, cmd.Stdout = errTail, errTail
	err := cmd.Run()
	if s := errTail.String(); s != "" {
		fmt.Fprint(r.Log, s)
	}
	return err
}

// Sidecar implements Encoder: exiftool -ee3 -j -G3 -a of the source, binary payloads left out, gzip-compressed.
func (e *NvencEncoder) Sidecar(ctx context.Context, src *os.File, out io.Writer, log io.Writer) error {
	if e.Exiftool == "" {
		return errors.New("exiftool not installed")
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Minute)
	defer cancel()
	if _, err := src.Seek(0, io.SeekStart); err != nil {
		return err
	}
	//nolint:gosec // the configured exiftool; fixed arguments; the source is a descriptor
	cmd := exec.CommandContext(ctx, e.Exiftool, "-ee3", "-j", "-G3", "-a", "-m", "--", fdInput)
	cmd.ExtraFiles = []*os.File{src}
	cmd.Env = toolEnv()
	zw := gzip.NewWriter(out)
	cmd.Stdout = &limited{w: zw, n: 512 << 20}
	errTail := &tail{n: 8 << 10}
	cmd.Stderr = errTail
	fmt.Fprintln(log, "$ exiftool -ee3 -j -G3 -a -m -- SRC")
	err := cmd.Run()
	if s := errTail.String(); s != "" {
		fmt.Fprint(log, s)
	}
	if cerr := zw.Close(); err == nil {
		err = cerr
	}
	return err
}
