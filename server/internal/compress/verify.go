package compress

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// VerifyError is a failed check: the item fails as verify_<Check>.
type VerifyError struct {
	Check  string // duration | streams | picture | frames | quality
	Detail string
}

func (e *VerifyError) Error() string { return "verify_" + e.Check + ": " + e.Detail }

// Reason is the item's failure reason.
func (e *VerifyError) Reason() string { return "verify_" + e.Check }

// CheckResult is what a passed Check learned of the source's metadata: the metadata sidecar keeps it (ADR 0025
// Amendment (N4)).
type CheckResult struct {
	Exiftool    json.RawMessage // the source's `exiftool -a -G1 -j` object (nil without exiftool)
	FFprobe     json.RawMessage // the source's ffprobe -show_format -show_streams
	NotRestored []string        // Group:Tag names of the source missing from the output
	Expected    []string        // the part of NotRestored in a known unwritable family (camera identity)
	Make, Model string          // the camera, from any group (an unwritable one too)
}

// VMAFResult is a quality score.
type VMAFResult struct {
	Mean float64
	Low  float64 // 1 % low (the mean of the worst 1 % of frames)
	Mode string  // full | spot
}

// Verifier checks an output against its source before anything is replaced (ADR 0025 "Checks").
type Verifier interface {
	// Check compares the two files' streams, picture and duration (and frames when the headers differ), writes the
	// metadata diff to log and answers what the metadata sidecar keeps. marker, when not "", is the value the output's
	// marker must have (every job passes it).
	Check(ctx context.Context, src *os.File, out, marker string, log io.Writer) (CheckResult, error)
	// VMAF scores the output against the source.
	VMAF(ctx context.Context, src *os.File, out string, p Probe, log io.Writer) (VMAFResult, error)
}

// FFVerifier is the Verifier on ffprobe, ffmpeg's libvmaf (on the CPU) and exiftool.
type FFVerifier struct {
	FFmpeg, FFprobe string
	Exiftool        string // "" = the diff has no exiftool half
	Threads         int    // VMAF_THREADS
	Nice            func(pid int)
}

// run runs a tool with src as descriptor 3 (nil: none); stdout is returned (at most 32 MiB).
func (v *FFVerifier) run(ctx context.Context, timeout time.Duration, src *os.File, stderr io.Writer, name string, args ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if src != nil {
		if _, err := src.Seek(0, io.SeekStart); err != nil {
			return nil, err
		}
	}
	//nolint:gosec // configured tools; arguments built here; inputs are a descriptor and a work file
	cmd := exec.CommandContext(ctx, name, args...)
	if src != nil {
		cmd.ExtraFiles = []*os.File{src}
	}
	cmd.Env = toolEnv()
	var out bytes.Buffer
	cmd.Stdout = &limited{w: &out, n: 32 << 20}
	errTail := &tail{n: 16 << 10}
	cmd.Stderr = errTail
	if stderr != nil {
		cmd.Stderr = io.MultiWriter(errTail, stderr)
	}
	err := cmd.Start()
	if err == nil {
		if v.Nice != nil {
			v.Nice(cmd.Process.Pid)
		}
		err = cmd.Wait()
	}
	if ctx.Err() == context.DeadlineExceeded {
		return nil, fmt.Errorf("%s timed out", name)
	}
	if err != nil {
		return out.Bytes(), fmt.Errorf("%w: %s", err, lastLine(errTail.String()))
	}
	return out.Bytes(), nil
}

// verifyStream is what the checks compare of a stream.
type verifyStream struct {
	Kind              string
	Codec             string
	Width, Height     int
	Rotation          int
	FrameRate         float64
	BitDepth          int
	Transfer, Prim    string
	Mastering, CLL    string // side data, as JSON (compared bit for bit)
	NbFrames          int64
	AttachedPic       bool
	DurationTimestamp string
	Duration          float64 // the stream's own duration (0: unknown)
}

type verifyInfo struct {
	Duration float64
	Streams  []verifyStream
	raw      map[string]any
}

func (v *FFVerifier) probe(ctx context.Context, src *os.File, path string) (verifyInfo, error) {
	in := path
	if src != nil {
		in = fdInput
	}
	out, err := v.run(ctx, time.Minute, src, nil, v.FFprobe, "-v", "error", "-protocol_whitelist", "file", "-format_whitelist", probeFormats,
		"-print_format", "json", "-show_format", "-show_streams", in)
	if err != nil {
		return verifyInfo{}, err
	}
	info, err := parseVerifyInfo(out)
	if err != nil {
		return info, err
	}
	// HDR10 static metadata in the stream's SEI (not in a container box): the first frame's side data.
	frames, err := v.run(ctx, time.Minute, src, nil, v.FFprobe, "-v", "error", "-protocol_whitelist", "file", "-format_whitelist", probeFormats,
		"-select_streams", "V:0", "-read_intervals", "%+#1", "-show_frames", "-show_entries", "frame=side_data_list", "-print_format", "json", in)
	if err == nil {
		applyFrameHDR(&info, frames)
	}
	return info, nil
}

// applyFrameHDR fills the main video stream's mastering display and content light level from its first frame when the
// container did not carry them.
func applyFrameHDR(info *verifyInfo, b []byte) {
	var o struct {
		Frames []struct {
			SideData []json.RawMessage `json:"side_data_list"`
		} `json:"frames"`
	}
	if json.Unmarshal(b, &o) != nil || len(o.Frames) == 0 {
		return
	}
	for i := range info.Streams {
		s := &info.Streams[i]
		if s.Kind != "video" || s.AttachedPic {
			continue
		}
		for _, raw := range o.Frames[0].SideData {
			var sd struct {
				Type string `json:"side_data_type"`
			}
			_ = json.Unmarshal(raw, &sd)
			switch {
			case sd.Type == "Mastering display metadata" && s.Mastering == "":
				s.Mastering = canonical(raw)
			case sd.Type == "Content light level metadata" && s.CLL == "":
				s.CLL = canonical(raw)
			}
		}
		return
	}
}

func parseVerifyInfo(out []byte) (verifyInfo, error) {
	var o struct {
		Streams []struct {
			CodecType    string            `json:"codec_type"`
			CodecName    string            `json:"codec_name"`
			Width        int               `json:"width"`
			Height       int               `json:"height"`
			PixFmt       string            `json:"pix_fmt"`
			BitsPerRaw   string            `json:"bits_per_raw_sample"`
			RFrameRate   string            `json:"r_frame_rate"`
			AvgFrameRate string            `json:"avg_frame_rate"`
			NbFrames     string            `json:"nb_frames"`
			Duration     string            `json:"duration"`
			Transfer     string            `json:"color_transfer"`
			Prim         string            `json:"color_primaries"`
			Disposition  map[string]int    `json:"disposition"`
			SideData     []json.RawMessage `json:"side_data_list"`
		} `json:"streams"`
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out, &o); err != nil {
		return verifyInfo{}, err
	}
	var info verifyInfo
	info.Duration, _ = strconv.ParseFloat(o.Format.Duration, 64)
	_ = json.Unmarshal(out, &info.raw)
	for _, s := range o.Streams {
		vs := verifyStream{Kind: s.CodecType, Codec: s.CodecName, Width: s.Width, Height: s.Height, Transfer: s.Transfer, Prim: s.Prim,
			AttachedPic: s.Disposition["attached_pic"] == 1}
		vs.NbFrames, _ = strconv.ParseInt(s.NbFrames, 10, 64)
		vs.Duration, _ = strconv.ParseFloat(s.Duration, 64)
		vs.FrameRate = rate(s.AvgFrameRate)
		if vs.FrameRate == 0 {
			vs.FrameRate = rate(s.RFrameRate)
		}
		if n, err := strconv.Atoi(s.BitsPerRaw); err == nil && n > 0 {
			vs.BitDepth = n
		} else if m := depthRe.FindStringSubmatch(s.PixFmt); m != nil {
			vs.BitDepth, _ = strconv.Atoi(m[1])
		} else if s.PixFmt != "" {
			vs.BitDepth = 8
		}
		for _, raw := range s.SideData {
			var sd struct {
				Type     string  `json:"side_data_type"`
				Rotation float64 `json:"rotation"`
			}
			_ = json.Unmarshal(raw, &sd)
			switch sd.Type {
			case "Display Matrix":
				vs.Rotation = int(math.Round(sd.Rotation))
			case "Mastering display metadata":
				vs.Mastering = canonical(raw)
			case "Content light level metadata":
				vs.CLL = canonical(raw)
			}
		}
		info.Streams = append(info.Streams, vs)
	}
	return info, nil
}

func canonical(raw json.RawMessage) string {
	var m map[string]any
	if json.Unmarshal(raw, &m) != nil {
		return string(raw)
	}
	b, _ := json.Marshal(m)
	return string(b)
}

// kept is the list of streams that must survive: data streams are dropped by the recipe.
func kept(ss []verifyStream) []verifyStream {
	var out []verifyStream
	for _, s := range ss {
		if s.Kind != "data" {
			out = append(out, s)
		}
	}
	return out
}

func mainVideo(ss []verifyStream) (verifyStream, bool) {
	for _, s := range ss {
		if s.Kind == "video" && !s.AttachedPic {
			return s, true
		}
	}
	return verifyStream{}, false
}

// videoDurationDeferred is compareInfo's answer when everything matched but a video stream's header duration: an MP4
// edit list can hide leading frames that the header still counts (a Samsung VFR clip: 607 frames / 20.216 s in the
// header, 603 decoded / 20.083 s, both CPU and CUDA). Check then compares the decoded video instead (ADR 0025
// Amendment (N4)).
type videoDurationDeferred struct{ detail string }

func (e *videoDurationDeferred) Error() string {
	return "video stream header duration differs: " + e.detail
}

// compareInfo is the probe half of the checks. When the only difference is a video stream's header duration, it
// answers *videoDurationDeferred, after every other check passed.
func compareInfo(a, b verifyInfo) error {
	if a.Duration <= 0 || b.Duration <= 0 {
		return &VerifyError{"duration", fmt.Sprintf("unknown duration (%.3f s vs %.3f s)", a.Duration, b.Duration)}
	}
	if math.Abs(a.Duration-b.Duration) > 0.1 {
		return &VerifyError{"duration", fmt.Sprintf("%.3f s vs %.3f s", a.Duration, b.Duration)}
	}
	// Every audio and video stream keeps its own duration (an audio track cut short does not change the file's). The
	// main video stream's header duration is only deferred: its decoded video decides.
	deferred := ""
	for _, kind := range []string{"video", "audio"} {
		var da, db []float64
		for _, s := range a.Streams {
			if s.Kind == kind && !s.AttachedPic {
				da = append(da, s.Duration)
			}
		}
		for _, s := range b.Streams {
			if s.Kind == kind && !s.AttachedPic {
				db = append(db, s.Duration)
			}
		}
		for i := range min(len(da), len(db)) {
			tol := 0.1
			if kind == "audio" {
				tol = 0.15 // an AAC re-encode's priming and padding
			}
			if da[i] > 0 && db[i] > 0 && math.Abs(da[i]-db[i]) > tol {
				detail := fmt.Sprintf("%s stream %d: %.3f s vs %.3f s", kind, i, da[i], db[i])
				// Only the main video (the first) is decoded to settle it: any other video stream must agree as is.
				if kind == "audio" || i > 0 {
					return &VerifyError{"duration", detail}
				}
				if deferred == "" {
					deferred = detail
				}
			}
		}
	}
	ka, kb := kept(a.Streams), kept(b.Streams)
	count := func(ss []verifyStream) map[string]int {
		m := map[string]int{}
		for _, s := range ss {
			k := s.Kind
			if s.AttachedPic {
				k = "cover"
			}
			m[k]++
		}
		return m
	}
	ca, cb := count(ka), count(kb)
	for k, n := range ca {
		if cb[k] != n {
			return &VerifyError{"streams", fmt.Sprintf("%d %s stream(s) in the source, %d in the output", n, k, cb[k])}
		}
	}
	for k, n := range cb {
		if ca[k] != n {
			return &VerifyError{"streams", fmt.Sprintf("%d %s stream(s) in the output, %d in the source", n, k, ca[k])}
		}
	}
	va, ok1 := mainVideo(ka)
	vb, ok2 := mainVideo(kb)
	if !ok1 || !ok2 {
		return &VerifyError{"streams", "no video stream"}
	}
	switch {
	case va.Width != vb.Width || va.Height != vb.Height:
		return &VerifyError{"picture", fmt.Sprintf("size %dx%d vs %dx%d", va.Width, va.Height, vb.Width, vb.Height)}
	case va.Rotation != vb.Rotation:
		return &VerifyError{"picture", fmt.Sprintf("rotation %d vs %d", va.Rotation, vb.Rotation)}
	case math.Abs(va.FrameRate-vb.FrameRate) > 0.01*math.Max(va.FrameRate, 1):
		return &VerifyError{"picture", fmt.Sprintf("frame rate %.3f vs %.3f", va.FrameRate, vb.FrameRate)}
	case va.BitDepth != vb.BitDepth:
		return &VerifyError{"picture", fmt.Sprintf("bit depth %d vs %d", va.BitDepth, vb.BitDepth)}
	case va.Transfer != vb.Transfer || va.Prim != vb.Prim:
		return &VerifyError{"picture", fmt.Sprintf("colour %s/%s vs %s/%s", va.Transfer, va.Prim, vb.Transfer, vb.Prim)}
	case va.Mastering != vb.Mastering:
		return &VerifyError{"picture", "HDR10 mastering display differs"}
	case va.CLL != vb.CLL:
		return &VerifyError{"picture", "HDR10 content light level differs"}
	}
	if deferred != "" {
		return &videoDurationDeferred{deferred}
	}
	return nil
}

// Check implements Verifier.
func (v *FFVerifier) Check(ctx context.Context, src *os.File, out, marker string, log io.Writer) (CheckResult, error) {
	a, err := v.probe(ctx, src, "")
	if err != nil {
		return CheckResult{}, fmt.Errorf("probe source: %w", err)
	}
	b, err := v.probe(ctx, nil, out)
	if err != nil {
		return CheckResult{}, &VerifyError{"streams", "the output cannot be read: " + err.Error()}
	}
	var deferred *videoDurationDeferred
	if err := compareInfo(a, b); errors.As(err, &deferred) {
		fmt.Fprintf(log, "check: %v: comparing the decoded video\n", err)
	} else if err != nil {
		fmt.Fprintf(log, "check: %v\n", err)
		return CheckResult{}, err
	} else {
		fmt.Fprintln(log, "check: duration, streams, picture: ok")
	}
	va, _ := mainVideo(a.Streams)
	vb, _ := mainVideo(b.Streams)
	if deferred != nil {
		// The decoded video of both: the same frame count and the same span (first frame's start to the last one's end).
		sa, err := v.decodedVideo(ctx, src, "", a.Duration)
		if err != nil {
			return CheckResult{}, fmt.Errorf("decode source frames: %w", err)
		}
		sb, err := v.decodedVideo(ctx, nil, out, b.Duration)
		if err != nil {
			return CheckResult{}, &VerifyError{"frames", "the output cannot be decoded: " + err.Error()}
		}
		fmt.Fprintf(log, "check: header frames %d vs %d, decoded %d vs %d, decoded span %.3f s vs %.3f s\n", va.NbFrames, vb.NbFrames,
			sa.Frames, sb.Frames, sa.Span, sb.Span)
		if sa.Frames != sb.Frames || math.Abs(sa.Span-sb.Span) > 0.1 {
			err := &VerifyError{"duration", fmt.Sprintf("%s; decoded %d frames / %.3f s vs %d / %.3f s", deferred.detail, sa.Frames, sa.Span,
				sb.Frames, sb.Span)}
			fmt.Fprintf(log, "check: %v\n", err)
			return CheckResult{}, err
		}
		fmt.Fprintln(log, "check: duration (decoded), streams, picture: ok")
	} else if va.NbFrames != vb.NbFrames {
		fa, err := v.countFrames(ctx, src, "")
		if err != nil {
			return CheckResult{}, fmt.Errorf("count source frames: %w", err)
		}
		fb, err := v.countFrames(ctx, nil, out)
		if err != nil {
			return CheckResult{}, &VerifyError{"frames", "the output cannot be decoded: " + err.Error()}
		}
		fmt.Fprintf(log, "check: header frames %d vs %d, decoded %d vs %d\n", va.NbFrames, vb.NbFrames, fa, fb)
		if fa != fb {
			return CheckResult{}, &VerifyError{"frames", fmt.Sprintf("%d decoded frames vs %d", fa, fb)}
		}
	} else {
		fmt.Fprintf(log, "check: frames %d = %d\n", va.NbFrames, vb.NbFrames)
	}
	// The whole output decodes without an error (spots of VMAF and equal frame counts do not prove that): the video with
	// -xerror; the audio too, but a decode error there fails only when the source's audio decodes cleanly (camera files
	// often end with a damaged AAC frame, which a copied track keeps).
	timeout := 10*time.Minute + time.Duration(b.Duration*10*float64(time.Second))
	if err := v.decode(ctx, timeout, nil, out, "0:v:0"); err != nil {
		fmt.Fprintf(log, "check: decode: %v\n", err)
		return CheckResult{}, &VerifyError{"decode", "the output's video does not decode cleanly: " + err.Error()}
	}
	hasAudio := false
	for _, st := range b.Streams {
		hasAudio = hasAudio || st.Kind == "audio"
	}
	if hasAudio {
		if err := v.decode(ctx, timeout, nil, out, "0:a"); err != nil {
			if serr := v.decode(ctx, timeout, src, "", "0:a"); serr == nil {
				fmt.Fprintf(log, "check: decode audio: %v\n", err)
				return CheckResult{}, &VerifyError{"decode", "the output's audio does not decode cleanly: " + err.Error()}
			}
			fmt.Fprintf(log, "check: decode audio: %v (the source's audio has the same damage: kept)\n", err)
		}
	}
	fmt.Fprintln(log, "check: full decode: ok")
	res, err := v.metaDiff(ctx, a, b, src, out, marker, log)
	if err != nil {
		fmt.Fprintf(log, "check: %v\n", err)
		return CheckResult{}, err
	}
	return res, nil
}

// decodedSpan is the decoded main video of a file: its frame count and the time from the first frame's start to the
// last frame's end.
type decodedSpan struct {
	Frames int64
	Span   float64
}

// decodedVideo decodes the main video stream (src as descriptor 3, else path) and measures it.
func (v *FFVerifier) decodedVideo(ctx context.Context, src *os.File, path string, duration float64) (decodedSpan, error) {
	in := path
	if src != nil {
		in = fdInput
	}
	timeout := 10*time.Minute + time.Duration(duration*10*float64(time.Second))
	out, err := v.run(ctx, timeout, src, nil, v.FFprobe, "-v", "error", "-protocol_whitelist", "file", "-format_whitelist", probeFormats,
		"-select_streams", "V:0", "-show_entries", "frame=best_effort_timestamp_time,duration_time", "-print_format", "csv=p=0", in)
	if err != nil {
		return decodedSpan{}, err
	}
	return parseDecodedSpan(out)
}

// parseDecodedSpan reads ffprobe's "best_effort_timestamp_time,duration_time" lines.
func parseDecodedSpan(b []byte) (decodedSpan, error) {
	var d decodedSpan
	first, end := math.Inf(1), math.Inf(-1)
	for line := range strings.Lines(string(b)) {
		fields := strings.Split(strings.TrimSpace(line), ",")
		if len(fields) == 0 || fields[0] == "" {
			continue
		}
		d.Frames++
		t, err := strconv.ParseFloat(fields[0], 64)
		if err != nil { // N/A: counted, no time
			continue
		}
		dur := 0.0
		if len(fields) > 1 {
			dur, _ = strconv.ParseFloat(fields[1], 64)
		}
		first, end = math.Min(first, t), math.Max(end, t+dur)
	}
	if d.Frames == 0 || math.IsInf(first, 0) {
		return d, errors.New("no decoded frame")
	}
	d.Span = end - first
	return d, nil
}

// decode decodes the mapped streams of a file (src as descriptor 3, else path) to nothing, stopping at the first error.
func (v *FFVerifier) decode(ctx context.Context, timeout time.Duration, src *os.File, path, mapping string) error {
	in := path
	if src != nil {
		in = fdInput
	}
	_, err := v.run(ctx, timeout, src, nil, v.FFmpeg, "-hide_banner", "-nostdin", "-xerror", "-v", "error",
		"-protocol_whitelist", "file", "-format_whitelist", probeFormats, "-threads", strconv.Itoa(max(v.Threads, 1)), "-i", in,
		"-map", mapping, "-f", "null", "-")
	return err
}

// keyFFTags are container tags whose loss fails the check (capture time, place, camera).
var keyFFTags = []string{"creation_time", "location", "location-eng", "com.apple.quicktime.location.iso6709", "com.apple.quicktime.make",
	"com.apple.quicktime.model", "com.apple.quicktime.creationdate", "make", "model"}

// keyExifTags are exiftool tag names (any group) whose loss fails the check; Rotation must also keep its value. The
// camera is gated by presence (cameraOf, any family) in exifGate.
var keyExifTags = []string{"CreateDate", "MediaCreateDate", "TrackCreateDate", "CreationDate", "DateTimeOriginal", "GPSCoordinates",
	"GPSLatitude", "GPSLongitude", "GPSPosition", "Rotation"}

func emptyValue(v string) bool {
	v = strings.Trim(v, `"`)
	return v == "" || v == "null" || strings.HasPrefix(v, "0000:00:00") || v == "1970-01-01T00:00:00.000000Z"
}

// lostTags lists the key tags the source has (non-empty) and the output lacks; byName keys are tag names, values the
// values of every group.
func lostTags(src, out map[string][]string, names []string, sameValue map[string]bool) []string {
	var lost []string
	for _, n := range names {
		have := false
		for _, v := range src[n] {
			have = have || !emptyValue(v)
		}
		if !have {
			continue
		}
		ok := false
		for _, v := range out[n] {
			if emptyValue(v) {
				continue
			}
			if !sameValue[n] {
				ok = true
				break
			}
			for _, sv := range src[n] {
				ok = ok || sv == v
			}
		}
		if !ok {
			lost = append(lost, n)
		}
	}
	return lost
}

// byTagName groups flattened values by their last path segment without the group ("/QuickTime:CreateDate" →
// "CreateDate", "/format/tags/creation_time" → "creation_time"), lower-cased when lower.
func byTagName(flat map[string]string, prefix string, lower bool) map[string][]string {
	out := map[string][]string{}
	for k, v := range flat {
		if !strings.HasPrefix(k, prefix) {
			continue
		}
		name := k[strings.LastIndex(k, "/")+1:]
		if i := strings.LastIndex(name, ":"); i >= 0 {
			name = name[i+1:]
		}
		if lower {
			name = strings.ToLower(name)
		}
		out[name] = append(out[name], v)
	}
	return out
}

var frameCountRe = regexp.MustCompile(`"nb_read_frames":\s*"(\d+)"`)

func (v *FFVerifier) countFrames(ctx context.Context, src *os.File, path string) (int64, error) {
	in := path
	if src != nil {
		in = fdInput
	}
	out, err := v.run(ctx, 10*time.Minute, src, nil, v.FFprobe, "-v", "error", "-protocol_whitelist", "file", "-format_whitelist", probeFormats,
		"-select_streams", "V:0", "-count_frames", "-show_entries", "stream=nb_read_frames", "-print_format", "json", in)
	if err != nil {
		return 0, err
	}
	m := frameCountRe.FindSubmatch(out)
	if m == nil {
		return 0, errors.New("no frame count")
	}
	return strconv.ParseInt(string(m[1]), 10, 64)
}

// metaDiff writes the metadata diff to the item's log and fails when the output lost the capture time, the place, the
// camera's make or model (the source's from any family, the same value in any group of the output), the rotation the
// source has, or, when marker is not "", the marker (ADR 0025 "Marker").
// Tags exiftool cannot write back into a re-encoded MP4 are listed as not restored (the metadata sidecar keeps them);
// the camera of the known unwritable families is carried in UserData by the encoder's restore.
func (v *FFVerifier) metaDiff(ctx context.Context, a, b verifyInfo, src *os.File, out, marker string, log io.Writer) (CheckResult, error) {
	var res CheckResult
	res.FFprobe, _ = json.Marshal(a.raw)
	fmt.Fprintln(log, "metadata diff (ffprobe):")
	ffa, ffb := flatten("", a.raw), flatten("", b.raw)
	writeDiff(log, ffa, ffb)
	lost := lostTags(byTagName(ffa, "/format/tags/", true), byTagName(ffb, "/format/tags/", true), keyFFTags, nil)
	if marker != "" {
		if got := markerOf(b.raw); got != marker {
			fmt.Fprintf(log, "marker: %q, want %q\n", got, marker)
			return res, &VerifyError{"metadata", fmt.Sprintf("the output's marker is %q, want %q", got, marker)}
		}
		fmt.Fprintf(log, "marker: %s\n", marker)
	}
	if v.Exiftool == "" {
		fmt.Fprintln(log, "metadata diff (exiftool): not installed")
		if len(lost) > 0 {
			return res, &VerifyError{"metadata", "lost: " + strings.Join(lost, ", ")}
		}
		return res, nil
	}
	ea, err1 := v.run(ctx, 2*time.Minute, src, nil, v.Exiftool, "-j", "-G1", "-a", "-m", "--", fdInput)
	eb, err2 := v.run(ctx, 2*time.Minute, nil, nil, v.Exiftool, "-j", "-G1", "-a", "-m", "--", out)
	if err1 != nil || err2 != nil {
		fmt.Fprintf(log, "metadata diff (exiftool): %v %v\n", err1, err2)
		return res, &VerifyError{"metadata", "exiftool could not read the files"}
	}
	var ja, jb []map[string]any
	err1, err2 = json.Unmarshal(ea, &ja), json.Unmarshal(eb, &jb)
	if err1 != nil || err2 != nil { // a read cut at the 32 MiB cap, or not exiftool's JSON: nothing to compare
		fmt.Fprintf(log, "metadata diff (exiftool): unreadable output: %v %v\n", err1, err2)
		return res, &VerifyError{"metadata", "exiftool's output could not be read"}
	}
	ta, tb := map[string]any{}, map[string]any{}
	if len(ja) > 0 {
		ta = ja[0]
	}
	if len(jb) > 0 {
		tb = jb[0]
	}
	res.Exiftool, _ = json.Marshal(ta)
	fmt.Fprintln(log, "metadata diff (exiftool):")
	writeDiff(log, flatten("", ta), flatten("", tb))
	g := exifGate(ta, tb)
	res.NotRestored, res.Expected, res.Make, res.Model = g.NotRestored, g.Expected, g.Make, g.Model
	if len(g.NotRestored) > 0 {
		fmt.Fprintf(log, "metadata not restored (%d, kept in the metadata sidecar): %s\n", len(g.NotRestored), strings.Join(g.NotRestored, ", "))
	}
	if len(g.Expected) > 0 {
		fmt.Fprintf(log, "camera identity not restored as such (exiftool cannot write it back; carried in UserData Make and Model): %s\n",
			strings.Join(g.Expected, ", "))
	}
	lost = append(lost, g.Lost...)
	if len(lost) > 0 {
		return res, &VerifyError{"metadata", "lost: " + strings.Join(lost, ", ")}
	}
	return res, nil
}

// markerOf reads the marker from ffprobe's format tags (either name, as ParseProbe).
func markerOf(raw map[string]any) string {
	format, _ := raw["format"].(map[string]any)
	tags, _ := format["tags"].(map[string]any)
	for k, v := range tags {
		if s, ok := v.(string); ok && (strings.EqualFold(k, Marker) || strings.EqualFold(k, MarkerKey)) {
			return s
		}
	}
	return ""
}

// unwritableCamera is the camera identity of the families exiftool reads but cannot write back into a re-encoded MP4
// (ADR 0025 Amendment (N4)): Samsung's `smta` box (group Samsung) and Sony's NRTM XML (group XML). Their loss as such
// is expected; their values go to UserData Make/Model (NvencEncoder.restoreCamera).
var unwritableCamera = map[string][]string{
	"Samsung": {"SamsungModel", "Make", "Model"},
	"XML":     {"DeviceManufacturer", "DeviceModelName", "Make", "Model"},
}

// notTags are exiftool groups that describe the file or the tool, not the video's metadata.
var notTags = map[string]bool{"ExifTool": true, "File": true, "System": true, "Composite": true, "SourceFile": true}

type gateResult struct {
	Lost        []string // key tags (by name) or the camera's make/model gone: the gate fails
	NotRestored []string // every Group:Tag of the source missing from the output
	Expected    []string // NotRestored entries in unwritableCamera
	Make, Model string
}

func splitTag(k string) (group, name string) {
	if i := strings.IndexByte(k, ':'); i >= 0 {
		return k[:i], k[i+1:]
	}
	return "", k
}

func unwritable(k string) bool {
	group, name := splitTag(k)
	for _, n := range unwritableCamera[group] {
		if n == name {
			return true
		}
	}
	return false
}

// exifGate classifies the source's exiftool tags (`-a -G1 -j` objects) against the output's.
func exifGate(src, out map[string]any) gateResult {
	var g gateResult
	for k := range src {
		group, _ := splitTag(k)
		if notTags[group] {
			continue
		}
		if _, ok := out[k]; !ok {
			g.NotRestored = append(g.NotRestored, k)
			if unwritable(k) {
				g.Expected = append(g.Expected, k)
			}
		}
	}
	sort.Strings(g.NotRestored)
	sort.Strings(g.Expected)
	// The gate: key tags by name in any group; the camera by value (trimmed, any case), the source's from any family.
	g.Lost = lostTags(byTagName(flatten("", src), "/", false), byTagName(flatten("", out), "/", false), keyExifTags,
		map[string]bool{"Rotation": true})
	g.Make, g.Model = cameraOf(src)
	omk, omodel := cameraOf(out)
	if g.Make != "" && !sameCamera(g.Make, omk, out, "Make") {
		g.Lost = append(g.Lost, "Make")
	}
	if g.Model != "" && !sameCamera(g.Model, omodel, out, "Model") {
		g.Lost = append(g.Lost, "Model")
	}
	return g
}

// sameCamera reports whether the output keeps the source's camera make or model value (trimmed, any case): as the
// output's camera, or as a tag of that name in any of its groups.
func sameCamera(want, got string, out map[string]any, name string) bool {
	want = strings.TrimSpace(want)
	if strings.EqualFold(want, strings.TrimSpace(got)) {
		return true
	}
	for k, v := range out {
		if _, n := splitTag(k); n == name && v != nil && strings.EqualFold(want, strings.TrimSpace(fmt.Sprint(v))) {
			return true
		}
	}
	return false
}

// cameraOf reads the camera's make and model from exiftool tags: Make/Model in any group (writable groups first), else
// Sony's XML device, else Samsung's smta model (make "Samsung").
func cameraOf(tags map[string]any) (mk, model string) {
	str := func(k string) string {
		v, ok := tags[k]
		if !ok || v == nil {
			return ""
		}
		var s string
		if t, ok := v.(string); ok {
			s = t
		} else {
			s = fmt.Sprint(v)
		}
		s = strings.TrimSpace(s)
		if emptyValue(s) {
			return ""
		}
		return s
	}
	keys := make([]string, 0, len(tags))
	for k := range tags {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { // writable groups first, then by name
		ui, uj := unwritable(keys[i]), unwritable(keys[j])
		if ui != uj {
			return !ui
		}
		return keys[i] < keys[j]
	})
	for _, k := range keys {
		_, name := splitTag(k)
		if name == "Make" && mk == "" {
			mk = str(k)
		}
		if name == "Model" && model == "" {
			model = str(k)
		}
	}
	if mk == "" {
		mk = str("XML:DeviceManufacturer")
	}
	if model == "" {
		model = str("XML:DeviceModelName")
	}
	if model == "" {
		if model = str("Samsung:SamsungModel"); model != "" && mk == "" {
			mk = "Samsung"
		}
	}
	return mk, model
}

func flatten(prefix string, v any) map[string]string {
	out := map[string]string{}
	switch t := v.(type) {
	case map[string]any:
		for k, x := range t {
			for k2, v2 := range flatten(prefix+"/"+k, x) {
				out[k2] = v2
			}
		}
	case []any:
		for i, x := range t {
			for k2, v2 := range flatten(prefix+"["+strconv.Itoa(i)+"]", x) {
				out[k2] = v2
			}
		}
	default:
		b, _ := json.Marshal(t)
		out[prefix] = string(b)
	}
	return out
}

func writeDiff(w io.Writer, a, b map[string]string) {
	keys := map[string]bool{}
	for k := range a {
		keys[k] = true
	}
	for k := range b {
		keys[k] = true
	}
	list := make([]string, 0, len(keys))
	for k := range keys {
		list = append(list, k)
	}
	sort.Strings(list)
	for _, k := range list {
		va, oka := a[k]
		vb, okb := b[k]
		switch {
		case !okb:
			fmt.Fprintf(w, "  - %s (lost) %s\n", k, va)
		case !oka:
			fmt.Fprintf(w, "  + %s (new) %s\n", k, vb)
		case va != vb:
			fmt.Fprintf(w, "  ~ %s %s -> %s\n", k, va, vb)
		}
	}
}

// vmafModel picks libvmaf's model: the 4K model above 2.7K.
func vmafModel(p Probe) string {
	if p.Width*p.Height > 2720*1530 {
		return "vmaf_4k_v0.6.1"
	}
	return "vmaf_v0.6.1"
}

// VMAF implements Verifier: the full clip up to 30 s, else three 2 s spots at 10, 50 and 85 % (ADR 0025).
func (v *FFVerifier) VMAF(ctx context.Context, src *os.File, out string, p Probe, log io.Writer) (VMAFResult, error) {
	dur := float64(p.DurationMs) / 1000
	if dur <= 30 {
		scores, err := v.vmafRange(ctx, src, out, p, -1, 0, log)
		if err != nil {
			return VMAFResult{}, err
		}
		r := summarize(scores)
		r.Mode = "full"
		fmt.Fprintf(log, "vmaf full: mean %.2f, 1%% low %.2f (%d frames)\n", r.Mean, r.Low, len(scores))
		return r, nil
	}
	var all []float64
	for _, at := range []float64{0.10, 0.50, 0.85} {
		start := math.Max(0, math.Min(dur*at, dur-2))
		scores, err := v.vmafRange(ctx, src, out, p, start, 2, log)
		if err != nil {
			return VMAFResult{}, err
		}
		s := summarize(scores)
		fmt.Fprintf(log, "vmaf spot at %.1f s: mean %.2f, 1%% low %.2f\n", start, s.Mean, s.Low)
		all = append(all, scores...)
	}
	r := summarize(all)
	r.Mode = "spot"
	fmt.Fprintf(log, "vmaf spots: mean %.2f, 1%% low %.2f\n", r.Mean, r.Low)
	return r, nil
}

func summarize(scores []float64) VMAFResult {
	if len(scores) == 0 {
		return VMAFResult{}
	}
	var sum float64
	for _, s := range scores {
		sum += s
	}
	sorted := append([]float64(nil), scores...)
	sort.Float64s(sorted)
	n := max(1, len(sorted)/100)
	var low float64
	for _, s := range sorted[:n] {
		low += s
	}
	return VMAFResult{Mean: sum / float64(len(scores)), Low: low / float64(n)}
}

// vmafRange scores [start, start+length) (start < 0: the whole clip) at the source's resolution and bit depth,
// unrotated, the distorted stream shifted +5 ms so frame timestamps pair up.
func (v *FFVerifier) vmafRange(ctx context.Context, src *os.File, out string, p Probe, start, length float64, log io.Writer) ([]float64, error) {
	threads := max(v.Threads, 1)
	pix := "yuv420p"
	if p.TenBit() {
		pix = "yuv420p10le"
	}
	var seek []string
	if start >= 0 {
		seek = []string{"-ss", fmt.Sprintf("%.3f", start), "-t", fmt.Sprintf("%.3f", length)}
	}
	prep := fmt.Sprintf("scale=%d:%d:flags=bicubic,format=%s,setpts=PTS-STARTPTS", p.Width, p.Height, pix)
	graph := fmt.Sprintf("[0:v:0]%s+0.005/TB[dist];[1:v:0]%s[ref];[dist][ref]libvmaf=model=version=%s:n_threads=%d:log_fmt=json:log_path=/dev/stdout",
		prep, prep, vmafModel(p), threads)
	args := []string{"-hide_banner", "-nostdin", "-loglevel", "error", "-protocol_whitelist", "file", "-format_whitelist", probeFormats}
	// Decoder threads capped at VMAF_THREADS on both inputs: frame-threaded decoding otherwise sizes to the host's cores
	// (a 4K 10-bit HEVC pair took 0.9 GiB more on 16 cores, at the same speed; ADR 0025 Amendment (N4)).
	dt := []string{"-threads", strconv.Itoa(threads), "-noautorotate"}
	args = append(append(append(args, dt...), seek...), "-i", out)
	args = append(append(append(args, dt...), seek...), "-i", fdInput)
	args = append(args, "-filter_complex", graph, "-an", "-sn", "-dn", "-f", "null", "-")
	timeout := 10*time.Minute + time.Duration(p.DurationMs)*time.Millisecond*10
	fmt.Fprintf(log, "$ ffmpeg %s\n", strings.ReplaceAll(strings.ReplaceAll(strings.Join(args, " "), fdInput, "SRC"), out, "OUT"))
	o, err := v.run(ctx, timeout, src, nil, v.FFmpeg, args...)
	if err != nil {
		return nil, err
	}
	return parseVMAFLog(o)
}

// parseVMAFLog reads libvmaf's JSON log (per-frame "vmaf" scores) from ffmpeg's stdout.
func parseVMAFLog(b []byte) ([]float64, error) {
	i := bytes.IndexByte(b, '{')
	if i < 0 {
		return nil, errors.New("no VMAF log")
	}
	var o struct {
		Frames []struct {
			Metrics map[string]float64 `json:"metrics"`
		} `json:"frames"`
	}
	if err := json.NewDecoder(bytes.NewReader(b[i:])).Decode(&o); err != nil {
		return nil, fmt.Errorf("VMAF log: %w", err)
	}
	out := make([]float64, 0, len(o.Frames))
	for _, f := range o.Frames {
		out = append(out, f.Metrics["vmaf"])
	}
	if len(out) == 0 {
		return nil, errors.New("VMAF scored no frame")
	}
	return out, nil
}
