package compress

import (
	"math"
	"path"
	"strings"
)

// Decisions of the estimate.
const (
	DecisionEncode  = "encode"
	DecisionSkip    = "skip"
	DecisionPending = "pending" // the probe is not done yet
)

// Skip and failure reasons (ADR 0025 "Rules, in order", "States").
const (
	ReasonNotVideo          = "not_video"
	ReasonUnreadable        = "unreadable"
	ReasonAlreadyCompressed = "already_compressed"
	ReasonOlderCompression  = "older_compression"
	ReasonEfficient         = "efficient"
	ReasonSmallSaving       = "small_saving"
	ReasonDolbyVision       = "dolby_vision"
	ReasonRevealRecord      = "reveal_record"
	ReasonSyncArtifact      = "sync_artifact"
	ReasonTrashed           = "trashed"
	ReasonBusy              = "busy"
	ReasonNotInTarget       = "not_in_target"
	ReasonNotWritable       = "not_writable"

	ReasonEncoder       = "encoder"
	ReasonSourceChanged = "source_changed"
	ReasonNoSpace       = "no_space"
	ReasonTrashFailed   = "trash_failed"
	ReasonVerifyQuality = "verify_quality"
)

// Warnings of the estimate.
const (
	WarnHDR10Plus    = "hdr10plus"
	WarnPerFrame     = "per_frame_metadata"
	WarnPCMAudio     = "pcm_audio"
	WarnAV1Playback  = "av1_playback"
	minSaving        = 0.20
	efficientBPP     = 0.15
	containerOverhed = 0.003
)

// Input is what the rules read of one video.
type Input struct {
	Kind        string // the item's kind (video only is compressed)
	Paths       []string
	Probe       *Probe // nil: not probed yet
	Size        int64  // the original file's
	HDR         string // file_meta: "dolby-vision" skips
	Trashed     bool
	Busy        bool // in an unfinished job
	IsOutput    bool // the fingerprint is the output of a compress_items row
	RevealNamed bool // a live Reveal record names a member's fingerprint
}

// Estimate is one video's decision.
type Estimate struct {
	Decision  string
	Reason    string
	EstSize   int64
	EstSaving float64
	EstMs     int64
	Warnings  []string
}

// bppRef is the predicted output bits per pixel at an HEVC q (the camera originals' median output); AV1 at 0.82×.
func bppRef(p Profile) float64 {
	q := p.Q
	if p.Codec == "av1" {
		q -= 6 // av1-cq34 ≈ hevc cq 28
	}
	var b float64
	switch {
	case q <= 28:
		b = 0.067 * math.Pow(0.067/0.049, float64(28-q)/2)
	default:
		b = 0.049 * math.Pow(0.049/0.067, float64(q-30)/2)
	}
	if p.Codec == "av1" {
		b *= 0.82
	}
	return b
}

// speed is the class's realtime factor (ADR 0025 "Estimate"): 1.1× for 4K HEVC, 2.5× for HEVC 10-bit, 2.6× otherwise.
func speed(p *Probe) float64 {
	switch {
	case p.Codec == "hevc" && p.Width*p.Height >= 3840*2160*9/10:
		return 1.1
	case p.Codec == "hevc" && p.TenBit():
		return 2.5
	default:
		return 2.6
	}
}

// verifyMs is the verification's time: VMAF at about 1.7 s per second of video up to 30 s, else 3 spots (about 15 s),
// plus the probes and the metadata diff.
func verifyMs(durationMs int64) int64 {
	if durationMs <= 30000 {
		return durationMs*17/10 + 2000
	}
	return 17000
}

// IsSyncArtifact reports Syncthing's own files: a conflict copy or a kept version (rule sync_artifact).
func IsSyncArtifact(p string) bool {
	if strings.Contains(path.Base(p), ".sync-conflict-") {
		return true
	}
	for _, seg := range strings.Split(p, "/") {
		if seg == ".stversions" {
			return true
		}
	}
	return false
}

// inHoardDir reports a path in one of Hoard's own folders (.hoard-trash, .hoard-mirror, .hoard-compress).
func inHoardDir(p string) bool {
	for _, seg := range strings.Split(path.Dir(p), "/") {
		if strings.HasPrefix(seg, ".hoard-") {
			return true
		}
	}
	return false
}

// Decide applies the skip rules in their order and, for a video to encode, the estimate.
func Decide(in Input, prof Profile) Estimate {
	skip := func(r string) Estimate { return Estimate{Decision: DecisionSkip, Reason: r} }
	if in.Kind != "video" {
		return skip(ReasonNotVideo)
	}
	for _, p := range in.Paths {
		if IsSyncArtifact(p) {
			return skip(ReasonSyncArtifact)
		}
	}
	if in.Trashed {
		return skip(ReasonTrashed)
	}
	for _, p := range in.Paths {
		if inHoardDir(p) {
			return skip(ReasonTrashed)
		}
	}
	if in.Busy {
		return skip(ReasonBusy)
	}
	if in.IsOutput {
		return skip(ReasonAlreadyCompressed)
	}
	if in.RevealNamed {
		return skip(ReasonRevealRecord)
	}
	if in.HDR == "dolby-vision" {
		return skip(ReasonDolbyVision)
	}
	p := in.Probe
	if p == nil {
		return Estimate{Decision: DecisionPending}
	}
	if p.Unreadable || p.Width == 0 {
		return skip(ReasonUnreadable)
	}
	if p.Marker != "" {
		return skip(ReasonAlreadyCompressed)
	}
	efficientCodec := p.Codec == "hevc" || p.Codec == "av1"
	if efficientCodec && strings.HasPrefix(p.Encoder, "Lavc") {
		return skip(ReasonOlderCompression)
	}
	if p.DolbyVision {
		return skip(ReasonDolbyVision)
	}
	if efficientCodec && p.BitRate > 0 && p.BPP() < efficientBPP {
		return skip(ReasonEfficient)
	}
	fps := p.AvgFrameRate
	if fps <= 0 {
		fps = p.RFrameRate
	}
	dur := float64(p.DurationMs) / 1000
	video := bppRef(prof) * float64(p.Width) * float64(p.Height) * fps * dur / 8
	audioBytes := float64(p.AudioBitRate) * dur / 8
	if p.PCM() {
		audioBytes = 256000 * dur / 8 * float64(len(p.Audio))
	}
	est := int64((video + audioBytes) * (1 + containerOverhed))
	e := Estimate{Decision: DecisionEncode, EstSize: est}
	if in.Size > 0 {
		e.EstSaving = 1 - float64(est)/float64(in.Size)
	}
	if e.EstSaving < minSaving {
		return Estimate{Decision: DecisionSkip, Reason: ReasonSmallSaving, EstSize: est, EstSaving: e.EstSaving}
	}
	e.EstMs = int64(float64(p.DurationMs)/speed(p)) + verifyMs(p.DurationMs)
	if p.HDR10Plus {
		e.Warnings = append(e.Warnings, WarnHDR10Plus)
	}
	if p.PerFrameData() {
		e.Warnings = append(e.Warnings, WarnPerFrame)
	}
	if p.PCM() {
		e.Warnings = append(e.Warnings, WarnPCMAudio)
	}
	if prof.Codec == "av1" {
		e.Warnings = append(e.Warnings, WarnAV1Playback)
	}
	return e
}
