package compress

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"math"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// ProbeVersion versions the rows of video_probe: raise it when Probe gains a field the rules read.
const ProbeVersion = 1

// Probe is what compression needs to know of a video beyond file_meta (ADR 0025 "Probe").
type Probe struct {
	Format       string   `json:"format"`
	DurationMs   int64    `json:"durationMs"`
	Codec        string   `json:"codec"` // the first video stream's (not a cover)
	Profile      string   `json:"profile,omitempty"`
	Width        int      `json:"width"`
	Height       int      `json:"height"`
	PixFmt       string   `json:"pixFmt"`
	BitDepth     int      `json:"bitDepth"`
	BitRate      int64    `json:"bitRate"` // the video stream's (b/s), else the file's less its audio
	RFrameRate   float64  `json:"rFrameRate"`
	AvgFrameRate float64  `json:"avgFrameRate"`
	NbFrames     int64    `json:"nbFrames"`
	Encoder      string   `json:"encoder,omitempty"` // the video stream's encoder tag
	Marker       string   `json:"marker,omitempty"`  // dev.hfps.hoard.compressed
	Audio        []string `json:"audio,omitempty"`   // codec of every audio stream
	AudioBitRate int64    `json:"audioBitRate"`      // sum, b/s
	Data         []string `json:"data,omitempty"`    // data streams' codec tags (djmd, rtmd, tmcd, …)
	Covers       int      `json:"covers"`            // attached pictures
	Transfer     string   `json:"transfer,omitempty"`
	Primaries    string   `json:"primaries,omitempty"`
	Rotation     int      `json:"rotation"`
	Mastering    bool     `json:"mastering"` // HDR10 mastering display
	CLL          bool     `json:"cll"`       // content light level
	HDR10Plus    bool     `json:"hdr10plus"`
	DolbyVision  bool     `json:"dolbyVision"`
	Unreadable   bool     `json:"unreadable,omitempty"` // ffprobe could not read it (no moov) or no video stream
}

// TenBit reports a source above 8 bits.
func (p Probe) TenBit() bool { return p.BitDepth > 8 }

// PCM reports PCM audio (re-encoded to AAC).
func (p Probe) PCM() bool {
	for _, a := range p.Audio {
		if strings.HasPrefix(a, "pcm_") {
			return true
		}
	}
	return false
}

// PerFrameData reports timed data the recipe drops (anything but a timecode track, which is regenerated).
func (p Probe) PerFrameData() bool {
	for _, d := range p.Data {
		if d != "tmcd" {
			return true
		}
	}
	return false
}

// MaxRate is the encode's -maxrate (the cap fix): the source's video bitrate × max(1, r_frame_rate ÷ avg_frame_rate).
func (p Probe) MaxRate() int64 {
	f := 1.0
	if p.AvgFrameRate > 0 && p.RFrameRate > p.AvgFrameRate {
		f = p.RFrameRate / p.AvgFrameRate
	}
	return int64(math.Round(float64(p.BitRate) * f))
}

// BPP is bits per pixel: video bitrate ÷ (width × height × avg fps).
func (p Probe) BPP() float64 {
	fps := p.AvgFrameRate
	if fps <= 0 {
		fps = p.RFrameRate
	}
	if p.Width == 0 || p.Height == 0 || fps <= 0 {
		return 0
	}
	return float64(p.BitRate) / (float64(p.Width) * float64(p.Height) * fps)
}

// Prober runs ffprobe for compression (two runs: streams and format, then the first video frame's side data).
type Prober struct {
	FFprobe string
	Timeout time.Duration
}

const probeFormats = "mov,mp4,m4a,3gp,3g2,mj2,mpegts,matroska,webm,avi"

// ffprobe runs ffprobe on f (descriptor 3) with args before the input.
func (p *Prober) ffprobe(ctx context.Context, f *os.File, args ...string) ([]byte, error) {
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	full := append([]string{"-v", "error", "-protocol_whitelist", "file", "-format_whitelist", probeFormats, "-print_format", "json"}, args...)
	full = append(full, fdInput)
	//nolint:gosec // the configured ffprobe; fixed arguments; the file is a descriptor
	cmd := exec.CommandContext(ctx, p.FFprobe, full...)
	cmd.ExtraFiles = []*os.File{f}
	cmd.Env = toolEnv()
	var out bytes.Buffer
	cmd.Stdout = &limited{w: &out, n: 4 << 20}
	err := cmd.Run()
	if ctx.Err() == context.DeadlineExceeded {
		return nil, errors.New("ffprobe timed out")
	}
	return out.Bytes(), err
}

// Probe reads a file. A file ffprobe cannot read, or without a video stream, is a Probe with Unreadable set (not an
// error: it is recorded so it is not read again).
func (p *Prober) Probe(ctx context.Context, f *os.File) (Probe, error) {
	out, err := p.ffprobe(ctx, f, "-show_format", "-show_streams")
	if ctx.Err() != nil {
		return Probe{}, ctx.Err()
	}
	if err != nil {
		var ee *exec.ExitError
		if errors.As(err, &ee) {
			return Probe{Unreadable: true}, nil
		}
		return Probe{}, err
	}
	pr, err := ParseProbe(out)
	if err != nil || pr.Unreadable {
		return Probe{Unreadable: true}, nil
	}
	frames, err := p.ffprobe(ctx, f, "-select_streams", "V:0", "-read_intervals", "%+#1", "-show_frames", "-show_entries", "frame=side_data_list")
	if ctx.Err() != nil {
		return Probe{}, ctx.Err()
	}
	if err == nil {
		ApplyFrameSideData(&pr, frames)
	}
	return pr, nil
}

type probeJSON struct {
	Streams []struct {
		Index         int               `json:"index"`
		CodecType     string            `json:"codec_type"`
		CodecName     string            `json:"codec_name"`
		CodecTag      string            `json:"codec_tag_string"`
		Profile       string            `json:"profile"`
		Width         int               `json:"width"`
		Height        int               `json:"height"`
		PixFmt        string            `json:"pix_fmt"`
		BitsPerRaw    string            `json:"bits_per_raw_sample"`
		AvgFrameRate  string            `json:"avg_frame_rate"`
		RFrameRate    string            `json:"r_frame_rate"`
		BitRate       string            `json:"bit_rate"`
		NbFrames      string            `json:"nb_frames"`
		ColorTransfer string            `json:"color_transfer"`
		ColorPrim     string            `json:"color_primaries"`
		Tags          map[string]string `json:"tags"`
		Disposition   map[string]int    `json:"disposition"`
		SideDataList  []sideData        `json:"side_data_list"`
	} `json:"streams"`
	Format struct {
		FormatName string            `json:"format_name"`
		Duration   string            `json:"duration"`
		BitRate    string            `json:"bit_rate"`
		Tags       map[string]string `json:"tags"`
	} `json:"format"`
}

type sideData struct {
	Type     string  `json:"side_data_type"`
	Rotation float64 `json:"rotation"`
}

var depthRe = regexp.MustCompile(`p(9|10|12|14|16)(le|be)?$`)

func rate(s string) float64 {
	a, b, ok := strings.Cut(s, "/")
	n, err := strconv.ParseFloat(a, 64)
	if err != nil {
		return 0
	}
	if !ok {
		return n
	}
	d, err := strconv.ParseFloat(b, 64)
	if err != nil || d == 0 {
		return 0
	}
	return n / d
}

// ParseProbe reads ffprobe's -show_format -show_streams JSON.
func ParseProbe(b []byte) (Probe, error) {
	var o probeJSON
	if err := json.Unmarshal(b, &o); err != nil {
		return Probe{}, err
	}
	p := Probe{Format: o.Format.FormatName}
	if d, err := strconv.ParseFloat(o.Format.Duration, 64); err == nil && d > 0 {
		p.DurationMs = int64(math.Round(d * 1000))
	}
	for k, v := range o.Format.Tags {
		if strings.EqualFold(k, Marker) || strings.EqualFold(k, MarkerKey) {
			p.Marker = v
		}
	}
	video := false
	for _, s := range o.Streams {
		br, _ := strconv.ParseInt(s.BitRate, 10, 64)
		switch s.CodecType {
		case "audio":
			p.Audio = append(p.Audio, s.CodecName)
			p.AudioBitRate += br
		case "data":
			tag := s.CodecTag
			if tag == "" || tag == "[0][0][0][0]" {
				tag = s.CodecName
			}
			p.Data = append(p.Data, tag)
		case "video":
			if s.Disposition["attached_pic"] == 1 {
				p.Covers++
				continue
			}
			if video {
				continue
			}
			video = true
			p.Codec, p.Profile, p.Width, p.Height, p.PixFmt = s.CodecName, s.Profile, s.Width, s.Height, s.PixFmt
			p.RFrameRate, p.AvgFrameRate = rate(s.RFrameRate), rate(s.AvgFrameRate)
			p.BitRate = br
			p.NbFrames, _ = strconv.ParseInt(s.NbFrames, 10, 64)
			p.Transfer, p.Primaries = s.ColorTransfer, s.ColorPrim
			for k, v := range s.Tags {
				if strings.EqualFold(k, "encoder") {
					p.Encoder = v
				}
			}
			if n, err := strconv.Atoi(s.BitsPerRaw); err == nil && n > 0 && n <= 16 {
				p.BitDepth = n
			} else if m := depthRe.FindStringSubmatch(s.PixFmt); m != nil {
				p.BitDepth, _ = strconv.Atoi(m[1])
			} else {
				p.BitDepth = 8
			}
			for _, sd := range s.SideDataList {
				switch {
				case sd.Type == "Display Matrix":
					p.Rotation = int(math.Round(sd.Rotation))
				case strings.HasPrefix(sd.Type, "DOVI configuration"):
					p.DolbyVision = true
				case sd.Type == "Mastering display metadata":
					p.Mastering = true
				case sd.Type == "Content light level metadata":
					p.CLL = true
				}
			}
		}
	}
	if !video {
		p.Unreadable = true
		return p, nil
	}
	if p.BitRate == 0 { // MKV and some MOV files only give the file's rate
		if fbr, err := strconv.ParseInt(o.Format.BitRate, 10, 64); err == nil {
			p.BitRate = max(fbr-p.AudioBitRate, 0)
		}
	}
	return p, nil
}

// ApplyFrameSideData reads the first video frame's side data (HDR10+ dynamic metadata, a Dolby Vision RPU, HDR10
// static metadata carried in the stream).
func ApplyFrameSideData(p *Probe, b []byte) {
	var o struct {
		Frames []struct {
			SideData []sideData `json:"side_data_list"`
		} `json:"frames"`
	}
	if json.Unmarshal(b, &o) != nil {
		return
	}
	for _, f := range o.Frames {
		for _, sd := range f.SideData {
			t := strings.ToLower(sd.Type)
			switch {
			case strings.Contains(t, "hdr dynamic metadata") || strings.Contains(t, "2094-40") || strings.Contains(t, "hdr10+"):
				p.HDR10Plus = true
			case strings.Contains(t, "dolby vision"):
				p.DolbyVision = true
			case t == "mastering display metadata":
				p.Mastering = true
			case t == "content light level metadata":
				p.CLL = true
			}
		}
	}
}
