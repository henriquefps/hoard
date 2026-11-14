// Package previews makes the gallery's previews of items (ADR 0015): for every item a thumbnail (long edge 512 px)
// and a screen preview (long edge 2560 px), upright sRGB JPEGs made from the item's best source, a video's poster
// frame at both sizes, and a low-bitrate H.264 proxy of every video (and of a motion photo's video). Generation runs
// in a bounded, resumable background queue behind the Generator interface, so another worker (a GPU one) can take
// jobs later; files live under the previews directory, never next to the originals.
package previews

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
)

// Version is PREVIEW_VERSION: raising it (or changing a recipe below) gives every preview a new key, so each one is
// made again — lazily: the old file is served until the new one is ready.
const Version = 1

// Jobs: one job makes the files of one kind of preview of an item.
const (
	JobImage = "image" // thumb and screen, JPEG and WebP (a still's, or a video's poster frame)
	JobProxy = "proxy" // proxy.mp4 (a video, or the video of a motion photo)
	// JobFull is the full-resolution still the web gallery shows at 100 % (ADR 0018 "Full resolution") for items whose
	// own file a browser cannot show: a HEIF rendering converted, a lone RAW rendered. Made only when asked (never in
	// the backlog), kept in a bounded cache.
	JobFull = "full"
)

// Output files.
const (
	FileThumb      = "thumb.jpg"
	FileScreen     = "screen.jpg"
	FileThumbWebP  = "thumb.webp"
	FileScreenWebP = "screen.webp"
	FileProxy      = "proxy.mp4"
	FileFull       = "full.jpg"
)

// Sizes (long edge, pixels) and encoder settings. Part of the key: changing one regenerates.
const (
	ThumbEdge     = 512
	ScreenEdge    = 2560
	ThumbQuality  = 80
	ScreenQuality = 82
	// WebP qualities with the same SSIM as the JPEGs on the bench set (ADR 0015 "Formats").
	ThumbWebPQuality  = 70
	ScreenWebPQuality = 85
	ProxyEdge         = 720 // short edge
	ProxyMaxFPS       = 30
	ProxyCRF          = 23
	ProxyMaxRate      = 2500 // kb/s
	ProxyAudio        = 128  // kb/s AAC
	// FullQuality: the full-resolution JPEG (baseline: it decodes faster than a progressive one, and the gallery
	// downloads it whole before showing it).
	FullQuality = 90
	// FullEmbeddedShare: a RAW's embedded JPEG is used as its full-resolution still when its long edge is at least this
	// share of the RAW's (Canon, Nikon and Apple embed a full-size rendering); else LibRaw decodes the RAW.
	FullEmbeddedShare = 0.9
)

// recipes name what a job makes; they are hashed into every key.
var recipes = map[string]string{
	JobImage: fmt.Sprintf("jpeg+webp srgb thumb=%d/q%d/w%d screen=%d/q%d/w%d poster=10%%,non-dark tonemap=hable", ThumbEdge, ThumbQuality,
		ThumbWebPQuality, ScreenEdge, ScreenQuality, ScreenWebPQuality),
	JobProxy: fmt.Sprintf("h264 high crf=%d maxrate=%dk short=%d fps<=%d gop=2s aac=%dk faststart tonemap=hable", ProxyCRF, ProxyMaxRate, ProxyEdge, ProxyMaxFPS, ProxyAudio),
	JobFull:  fmt.Sprintf("jpeg srgb full q%d baseline embedded-as-is>=%.2f else libraw", FullQuality, FullEmbeddedShare),
}

// Source is the file a job reads.
type Source struct {
	Fingerprint string
	Role        string // raw | rendered | video (a motion photo's embedded video: the still, with Offset/Length)
	Format      string // content format of the file (jpeg, heif, tiff, cr3, raf, mp4, mov, …)
	Offset      int64  // embedded video: where it starts in the file
	Length      int64
	Orientation int          // a RAW's EXIF orientation (its embedded preview carries none)
	LongEdge    int          // the item's long edge in pixels (0 = unknown); not part of the key
	Video       *media.Video // a video's stream (duration, HDR, rotation, frame rate)
}

// Job is one job of one item, as planned from the item's current state.
type Job struct {
	ItemID string
	Kind   string // the item's kind
	Job    string // JobImage | JobProxy
	Source Source
	Key    string // content key: the source, the recipe and Version
}

// Plan lists the jobs an item has (ADR 0015 "Sources"): every live item an image job — the camera rendering of a
// pair, else the still (a RAW's embedded preview, else the RAW decoded), a HEIF/AVIF, or a video's poster frame —
// and videos and motion photos a proxy job.
func Plan(id string, d items.Data) map[string]Job {
	var raw, rendered, video *items.File
	for i := range d.Files {
		f := &d.Files[i]
		switch {
		case f.Role == media.RoleRaw && raw == nil:
			raw = f
		case f.Role == media.RoleRendered && rendered == nil:
			rendered = f
		case f.Role == media.RoleVideo && video == nil:
			video = f
		}
	}
	out := map[string]Job{}
	add := func(job string, s Source) {
		out[job] = Job{ItemID: id, Kind: d.Kind, Job: job, Source: s, Key: Key(job, s)}
	}
	switch d.Kind {
	case items.KindVideo:
		if video != nil {
			s := Source{Fingerprint: video.Fingerprint, Role: media.RoleVideo, Format: video.Format, Video: d.Video}
			add(JobImage, s)
			add(JobProxy, s)
		}
		return out
	case items.KindPhoto, items.KindPair, items.KindMotion:
		switch {
		case rendered != nil:
			add(JobImage, Source{Fingerprint: rendered.Fingerprint, Role: media.RoleRendered, Format: rendered.Format})
		case raw != nil:
			add(JobImage, Source{Fingerprint: raw.Fingerprint, Role: media.RoleRaw, Format: raw.Format, Orientation: d.Orientation,
				LongEdge: max(d.Width, d.Height)})
		}
		if img, ok := out[JobImage]; ok && FullOriginal(d) == nil {
			add(JobFull, img.Source)
		}
	}
	if d.Kind == items.KindMotion && d.Motion != nil {
		switch d.Motion.Source {
		case "file":
			add(JobProxy, Source{Fingerprint: d.Motion.Fingerprint, Role: media.RoleVideo, Video: d.Video})
		case "embedded":
			if d.Motion.Length > 0 {
				add(JobProxy, Source{Fingerprint: d.Motion.Fingerprint, Role: media.RoleVideo, Format: "mp4",
					Offset: d.Motion.Offset, Length: d.Motion.Length, Video: d.Video})
			}
		}
	}
	return out
}

// BrowserShowable reports a still a browser shows as it is (JPEG, PNG, WebP, AVIF, GIF): the web gallery's
// full-resolution layer is then the file itself, and no JobFull is planned.
func BrowserShowable(f items.File) bool {
	switch f.Format {
	case "jpeg", "png", "webp", "avif", "gif":
		return true
	case "":
		switch media.Ext(f.Name) {
		case "jpg", "jpeg", "jpe", "jfif", "png", "webp", "avif", "gif":
			return true
		}
	}
	return false
}

// FullOriginal is the file the web gallery shows at full resolution as it is (a pair's camera JPEG, a JPEG photo),
// or nil when the item needs a JobFull (a HEIF, a lone RAW) or has no still (a video).
func FullOriginal(d items.Data) *items.File {
	if d.Kind == items.KindVideo {
		return nil
	}
	for i := range d.Files {
		if f := &d.Files[i]; f.Role == media.RoleRendered && BrowserShowable(*f) {
			return f
		}
	}
	return nil
}

// Key is a job's content key: SHA-256 (first 16 bytes, hex) of Version, the job's recipe and what it reads. Two
// items reading the same bytes the same way share their files.
func Key(job string, s Source) string {
	h := sha256.New()
	fmt.Fprintf(h, "hoard-preview\x00%d\x00%s\x00%s\x00%s\x00%s\x00%d\x00%d", Version, job, recipes[job], s.Fingerprint, s.Role, s.Offset, s.Length)
	if s.Role == media.RoleRaw {
		fmt.Fprintf(h, "\x00o%d", s.Orientation)
	}
	return hex.EncodeToString(h.Sum(nil)[:16])
}

// Errors a Generator returns.
var (
	// ErrUnsupported: this source cannot be previewed by this generator (no decoder); not retried until the recipe
	// or the source changes.
	ErrUnsupported = errors.New("unsupported source")
	// ErrNoTools: the generator's programs are missing; the job waits until they are installed.
	ErrNoTools = errors.New("preview tools not installed")
)

// Output is one file a job made.
type Output struct {
	Width  int   `json:"w,omitempty"`
	Height int   `json:"h,omitempty"`
	Bytes  int64 `json:"bytes"`
}

// Result maps the files a job made (FileThumb, …) to what they are.
type Result map[string]Output

// Generator makes a job's files. The CPU generator (libvips + ffmpeg) is the only one today; a GPU worker (ADR 0015
// "GPU worker") will implement the same contract: read the source, write the job's files into dir, report them.
type Generator interface {
	// Name identifies the generator in logs and the status page.
	Name() string
	// Accepts reports whether the generator takes this job (a GPU worker may take only videos).
	Accepts(j Job) bool
	// Available reports whether its tools are present.
	Available() bool
	// Generate reads src (the whole source file; an embedded video is Source.Offset/Length inside it) and writes
	// the job's files into dir (an empty, private directory). It never writes anywhere else.
	Generate(ctx context.Context, j Job, src *os.File, dir string) (Result, error)
}
