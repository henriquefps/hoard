package media

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"math"
	"os"
	"os/exec"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// Prober runs ffprobe on videos in a bounded pool, each run with a timeout (ADR 0014 "Video metadata").
type Prober struct {
	path    string
	timeout time.Duration
	sem     chan struct{}
}

// ErrNoProber means ffprobe is not installed: video metadata is read again once it is.
var ErrNoProber = errors.New("ffprobe not available")

// ErrProbeTimeout means ffprobe did not answer in time.
var ErrProbeTimeout = errors.New("ffprobe timed out")

// NewProber finds ffprobe (a path or a name looked up in PATH). A missing binary gives a Prober whose Probe returns
// ErrNoProber.
func NewProber(bin string, workers int, timeout time.Duration) *Prober {
	if bin == "" {
		bin = "ffprobe"
	}
	if workers <= 0 {
		workers = 2
	}
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	p, err := exec.LookPath(bin)
	if err != nil {
		p = ""
	}
	return &Prober{path: p, timeout: timeout, sem: make(chan struct{}, workers)}
}

// Available reports whether ffprobe was found.
func (p *Prober) Available() bool { return p != nil && p.path != "" }

// formats ffprobe may use: the containers phones and cameras write. Anything else (playlists, images, network
// formats) is refused, and only the file and subfile protocols are allowed, so a hostile file cannot make ffprobe
// open other files or the network.
const formatWhitelist = "mov,mp4,m4a,3gp,3g2,mj2,mpegts,matroska,webm,avi"

// Probe reads the video at [offset, offset+length) of f (length 0 = to the end). The file is handed to ffprobe as an
// open descriptor, never by name.
func (p *Prober) Probe(ctx context.Context, f *os.File, offset, length int64) (*Video, map[string]string, error) {
	if !p.Available() {
		return nil, nil, ErrNoProber
	}
	select {
	case p.sem <- struct{}{}:
	case <-ctx.Done():
		return nil, nil, ctx.Err()
	}
	defer func() { <-p.sem }()
	ctx, cancel := context.WithTimeout(ctx, p.timeout)
	defer cancel()
	input := "/proc/self/fd/3"
	if runtime.GOOS != "linux" {
		input = "/dev/fd/3"
	}
	if offset > 0 || length > 0 {
		end := int64(0)
		if length > 0 {
			end = offset + length
		}
		input = "subfile,,start," + strconv.FormatInt(offset, 10) + ",end," + strconv.FormatInt(end, 10) + ",,:" + input
	}
	//nolint:gosec // the binary is the configured ffprobe; every argument is fixed and the file is an fd, never a name
	cmd := exec.CommandContext(ctx, p.path, "-v", "error", "-protocol_whitelist", "file,subfile",
		"-format_whitelist", formatWhitelist, "-print_format", "json", "-show_format", "-show_streams", input)
	cmd.ExtraFiles = []*os.File{f}
	var out limitedBuffer
	out.limit = 1 << 20
	cmd.Stdout = &out
	cmd.Stderr = nil
	err := cmd.Run()
	if ctx.Err() == context.DeadlineExceeded {
		return nil, nil, ErrProbeTimeout
	}
	if err != nil {
		return nil, nil, err
	}
	return parseProbe(out.Bytes())
}

type limitedBuffer struct {
	bytes.Buffer
	limit int
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	if room := b.limit - b.Len(); room < len(p) {
		if room > 0 {
			b.Buffer.Write(p[:room])
		}
		return len(p), nil
	}
	return b.Buffer.Write(p)
}

type probeOutput struct {
	Streams []struct {
		CodecType        string            `json:"codec_type"`
		CodecName        string            `json:"codec_name"`
		Profile          string            `json:"profile"`
		Width            int               `json:"width"`
		Height           int               `json:"height"`
		PixFmt           string            `json:"pix_fmt"`
		BitsPerRawSample string            `json:"bits_per_raw_sample"`
		AvgFrameRate     string            `json:"avg_frame_rate"`
		RFrameRate       string            `json:"r_frame_rate"`
		ColorTransfer    string            `json:"color_transfer"`
		ColorPrimaries   string            `json:"color_primaries"`
		Duration         string            `json:"duration"`
		Tags             map[string]string `json:"tags"`
		Disposition      map[string]int    `json:"disposition"`
		SideDataList     []struct {
			SideDataType string  `json:"side_data_type"`
			Rotation     float64 `json:"rotation"`
		} `json:"side_data_list"`
	} `json:"streams"`
	Format struct {
		FormatName string            `json:"format_name"`
		Duration   string            `json:"duration"`
		Tags       map[string]string `json:"tags"`
	} `json:"format"`
}

var bitDepthRe = regexp.MustCompile(`p(9|10|12|14|16)(le|be)?$`)

// parseProbe turns ffprobe's JSON into a Video and the container's tags (lower-cased keys).
func parseProbe(b []byte) (*Video, map[string]string, error) {
	var o probeOutput
	if err := json.Unmarshal(b, &o); err != nil {
		return nil, nil, err
	}
	v := &Video{Container: o.Format.FormatName}
	tags := map[string]string{}
	for k, val := range o.Format.Tags {
		tags[strings.ToLower(k)] = val
	}
	if d, err := strconv.ParseFloat(o.Format.Duration, 64); err == nil && d > 0 && d < 1e7 {
		v.DurationMs = int64(math.Round(d * 1000))
	}
	video := false
	for _, s := range o.Streams {
		switch s.CodecType {
		case "audio":
			if v.Audio == "" {
				v.Audio = s.CodecName
			}
		case "video":
			if video || s.Disposition["attached_pic"] == 1 {
				continue
			}
			video = true
			v.Codec, v.Profile, v.Width, v.Height = s.CodecName, s.Profile, s.Width, s.Height
			v.FPS = frameRate(s.AvgFrameRate)
			if v.FPS == 0 {
				v.FPS = frameRate(s.RFrameRate)
			}
			if n, err := strconv.Atoi(s.BitsPerRawSample); err == nil && n > 0 && n <= 16 {
				v.BitDepth = n
			} else if m := bitDepthRe.FindStringSubmatch(s.PixFmt); m != nil {
				v.BitDepth, _ = strconv.Atoi(m[1])
			} else if s.PixFmt != "" {
				v.BitDepth = 8
			}
			switch s.ColorTransfer {
			case "smpte2084":
				v.HDR = "pq"
			case "arib-std-b67":
				v.HDR = "hlg"
			}
			v.ColorSpace = s.ColorPrimaries
			rot := 0.0
			for _, sd := range s.SideDataList {
				if sd.SideDataType == "Display Matrix" {
					rot = sd.Rotation
				}
				if strings.HasPrefix(sd.SideDataType, "DOVI configuration") {
					v.HDR = "dolby-vision"
				}
			}
			if r, err := strconv.ParseFloat(s.Tags["rotate"], 64); err == nil && rot == 0 {
				rot = -r // the old tag is clockwise, the display matrix counter-clockwise
			}
			v.Rotation = normalizeRotation(rot)
			if v.DurationMs == 0 {
				if d, err := strconv.ParseFloat(s.Duration, 64); err == nil && d > 0 && d < 1e7 {
					v.DurationMs = int64(math.Round(d * 1000))
				}
			}
			for k, val := range s.Tags {
				if _, ok := tags[strings.ToLower(k)]; !ok {
					tags[strings.ToLower(k)] = val
				}
			}
		}
	}
	if !video {
		return nil, tags, errors.New("no video stream")
	}
	return v, tags, nil
}

// normalizeRotation turns the display matrix angle (counter-clockwise degrees) into the clockwise rotation to apply
// for display: 0, 90, 180 or 270.
func normalizeRotation(ccw float64) int {
	r := int(math.Round(-ccw/90)) * 90 % 360
	if r < 0 {
		r += 360
	}
	return r
}

func frameRate(s string) float64 {
	num, den, ok := strings.Cut(s, "/")
	n, err1 := strconv.ParseFloat(num, 64)
	d := 1.0
	var err2 error
	if ok {
		d, err2 = strconv.ParseFloat(den, 64)
	}
	if err1 != nil || err2 != nil || d == 0 || n <= 0 {
		return 0
	}
	return round(n/d, 3)
}

// VideoMeta builds a video file's Meta from ffprobe's answer: QuickTime/MP4 tags for the capture time, camera and
// position (ADR 0014 "Capture time": com.apple.quicktime.creationdate carries the local time and its offset;
// creation_time is UTC; Samsung's com.samsung.android.utc_offset gives the offset of creation_time).
func VideoMeta(v *Video, tags map[string]string, format string) Meta {
	m := Meta{Media: MediaVideo, Format: format, Video: v, Width: v.Width, Height: v.Height}
	m.Make = cleanString(firstTag(tags, "com.apple.quicktime.make", "make", "com.android.manufacturer"))
	m.Model = cleanString(firstTag(tags, "com.apple.quicktime.model", "model", "com.android.model"))
	m.Capture = videoCapture(tags)
	if g := parseISO6709(firstTag(tags, "com.apple.quicktime.location.iso6709", "location")); g != nil {
		m.GPS = g
	}
	return m
}

// firstTag returns the first non-empty tag of keys. ffprobe joins a key written twice (the movie header and a
// metadata atom) with ";": the first value is used.
func firstTag(tags map[string]string, keys ...string) string {
	for _, k := range keys {
		v, _, _ := strings.Cut(tags[k], ";")
		if v = strings.TrimSpace(v); v != "" {
			return v
		}
	}
	return ""
}

func videoCapture(tags map[string]string) *Capture {
	if s := firstTag(tags, "com.apple.quicktime.creationdate"); s != "" {
		for _, layout := range []string{"2006-01-02T15:04:05Z0700", "2006-01-02T15:04:05Z07:00", "2006-01-02T15:04:05.000Z0700",
			"2006-01-02T15:04:05.000Z07:00"} {
			if t, err := time.Parse(layout, s); err == nil {
				_, off := t.Zone()
				u := t.UnixMilli()
				return &Capture{Local: formatLocal(t, t.Nanosecond() != 0), Offset: formatOffset(off / 60), UTC: &u, Source: "quicktime"}
			}
		}
	}
	if s := firstTag(tags, "creation_time", "date"); s != "" {
		t, err := time.Parse(time.RFC3339Nano, s)
		if err != nil {
			t, err = time.Parse("2006-01-02 15:04:05", s)
		}
		if err == nil && t.Year() > 1970 {
			u := t.UnixMilli()
			c := &Capture{UTC: &u, Source: "quicktime"}
			if mins, ok := parseOffset(firstTag(tags, "com.samsung.android.utc_offset")); ok {
				c.Offset = formatOffset(mins)
				c.Local = formatLocal(t.UTC().Add(time.Duration(mins)*time.Minute), t.Nanosecond() != 0)
			}
			return c
		}
	}
	return nil
}

var iso6709 = regexp.MustCompile(`^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?`)

// parseISO6709 reads "+37.5090+127.0620/" or "+37.5090+127.0620+045.000/" (decimal degrees; the forms with
// minutes are not written by phones).
func parseISO6709(s string) *GPS {
	m := iso6709.FindStringSubmatch(strings.TrimSpace(s))
	if m == nil {
		return nil
	}
	lat, err1 := strconv.ParseFloat(m[1], 64)
	lon, err2 := strconv.ParseFloat(m[2], 64)
	if err1 != nil || err2 != nil || math.Abs(lat) > 90 || math.Abs(lon) > 180 || (lat == 0 && lon == 0) {
		return nil
	}
	g := &GPS{Lat: round(lat, 7), Lon: round(lon, 7)}
	if m[3] != "" {
		if a, err := strconv.ParseFloat(m[3], 64); err == nil {
			a = round(a, 2)
			g.Alt = &a
		}
	}
	return g
}

// VideoFormat names a video container from ffprobe's format_name and the file's extension.
func VideoFormat(container, ext string) string {
	switch {
	case strings.Contains(container, "mov"):
		if ext == "mov" || ext == "mp4" || ext == "3gp" {
			return ext
		}
		return "mp4"
	case container == "mpegts":
		return "m2ts"
	case strings.Contains(container, "matroska"):
		if ext == "webm" {
			return "webm"
		}
		return "mkv"
	}
	return container
}
