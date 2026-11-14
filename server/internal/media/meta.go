// Package media reads what the server needs to know about a photo or video file without decoding it (ADR 0014
// "Metadata"): capture time, camera, lens, GPS, dimensions and orientation from EXIF (JPEG, HEIF, TIFF-based RAWs,
// CR3, RAF), motion photos (an MP4 inside a JPEG/HEIF: Google/Samsung XMP, Google MicroVideo, Samsung's SEF trailer),
// and video properties through ffprobe. It reads headers only (bounded reads) and never writes.
package media

import (
	"errors"
	"fmt"
	"io"
	"math"
	"strconv"
	"strings"
	"time"
)

// MetaVersion is the version of the extraction rules. Raising it makes the indexer read every file's metadata again
// (never its fingerprint).
const MetaVersion = 1

// Media classes of a file.
const (
	MediaImage   = "image"
	MediaVideo   = "video"
	MediaSidecar = "sidecar"
)

// Capture is when a shot was taken (ADR 0014 "Capture time").
type Capture struct {
	// Local is the camera's wall-clock time, "2006-01-02T15:04:05" plus ".000" when sub-seconds are known; empty
	// when the file only has an absolute time (most videos).
	Local string `json:"local,omitempty"`
	// Offset is the UTC offset of Local, "+09:00", when known.
	Offset string `json:"offset,omitempty"`
	// UTC is the instant (Unix milliseconds) when known: an offset was recorded (or derived from GPS time), or the
	// source is absolute (QuickTime creation time, the file's modification time).
	UTC *int64 `json:"utc,omitempty"`
	// Source: exif, exif-gps (offset derived from GPS time), quicktime, mtime.
	Source string `json:"source"`
}

// SortTime is the time used to order and filter items: the instant when known, else the wall clock read as if it
// were UTC (a "floating" time).
func (c *Capture) SortTime() int64 {
	if c == nil {
		return 0
	}
	if c.UTC != nil {
		return *c.UTC
	}
	if t, err := time.Parse("2006-01-02T15:04:05.000", padMillis(c.Local)); err == nil {
		return t.UnixMilli()
	}
	return 0
}

func padMillis(local string) string {
	if strings.Contains(local, ".") {
		return local
	}
	return local + ".000"
}

// GPS is a position in decimal degrees (WGS 84) and metres above sea level.
type GPS struct {
	Lat float64  `json:"lat"`
	Lon float64  `json:"lon"`
	Alt *float64 `json:"alt,omitempty"`
}

// Video describes a video stream (ffprobe).
type Video struct {
	DurationMs int64   `json:"durationMs,omitempty"`
	Container  string  `json:"container,omitempty"` // ffprobe format_name, e.g. "mov,mp4,m4a,3gp,3g2,mj2"
	Codec      string  `json:"codec,omitempty"`     // hevc, h264, prores, av1, …
	Profile    string  `json:"profile,omitempty"`
	Width      int     `json:"width,omitempty"`  // coded width (before Rotation)
	Height     int     `json:"height,omitempty"` // coded height
	Rotation   int     `json:"rotation,omitempty"`
	FPS        float64 `json:"fps,omitempty"`
	BitDepth   int     `json:"bitDepth,omitempty"`
	HDR        string  `json:"hdr,omitempty"` // pq, hlg, dolby-vision
	ColorSpace string  `json:"colorSpace,omitempty"`
	Audio      string  `json:"audio,omitempty"` // codec of the first audio stream
}

// Motion is a video embedded in a still (a motion photo).
type Motion struct {
	// Format: google (Container XMP, MotionPhoto 1), microvideo (GCamera MicroVideo), samsung (SEF trailer).
	Format         string `json:"format"`
	Offset         int64  `json:"offset"` // where the MP4 starts in the file
	Length         int64  `json:"length"`
	PresentationUs *int64 `json:"presentationUs,omitempty"` // the still's position in the video, when given
	Video          *Video `json:"video,omitempty"`
}

// Meta is what the server knows about one file's content. It is stored per fingerprint.
type Meta struct {
	Media       string   `json:"media"`            // image | video | sidecar
	Format      string   `json:"format,omitempty"` // content format: jpeg, heif, tiff (TIFF-based RAW), cr3, raf, mp4, mov, …
	Capture     *Capture `json:"capture,omitempty"`
	Make        string   `json:"make,omitempty"`
	Model       string   `json:"model,omitempty"`
	LensMake    string   `json:"lensMake,omitempty"`
	Lens        string   `json:"lens,omitempty"`
	GPS         *GPS     `json:"gps,omitempty"`
	Width       int      `json:"width,omitempty"`
	Height      int      `json:"height,omitempty"`
	Orientation int      `json:"orientation,omitempty"` // EXIF orientation 1-8
	Video       *Video   `json:"video,omitempty"`
	Motion      *Motion  `json:"motion,omitempty"`
	// Error says why nothing (or not everything) could be read: unsupported, unreadable, probe_failed,
	// probe_timeout.
	Error string `json:"error,omitempty"`
}

// ErrUnsupported is returned for content the readers do not know.
var ErrUnsupported = errors.New("unsupported content")

// ReadImage reads the metadata of a still (JPEG, HEIF, TIFF-based RAW, CR3, RAF) from its content (the extension is
// not trusted). Motion photos get Motion without Motion.Video (see Prober).
func ReadImage(r io.ReaderAt, size int64) (Meta, error) {
	m, _, err := readImage(r, size)
	return m, err
}

// Exposure is how a still was shot, read from its EXIF on demand (the web gallery's info panel, ADR 0018). Zero values
// are "not recorded". It is not part of Meta: the stored metadata (and MetaVersion) do not change.
type Exposure struct {
	ExposureTime  float64  `json:"exposureTime,omitempty"` // seconds
	FNumber       float64  `json:"fNumber,omitempty"`
	ISO           int      `json:"iso,omitempty"`
	Bias          *float64 `json:"bias,omitempty"` // EV
	FocalLength   float64  `json:"focalLength,omitempty"`
	FocalLength35 int      `json:"focalLength35,omitempty"`
}

// ReadExposure reads the exposure of a still (the formats of ReadImage).
func ReadExposure(r io.ReaderAt, size int64) (Exposure, error) {
	_, f, err := readImage(r, size)
	return f.exposure, err
}

func readImage(r io.ReaderAt, size int64) (Meta, exifFields, error) {
	m := Meta{Media: MediaImage}
	var head [16]byte
	n, _ := r.ReadAt(head[:], 0)
	h := head[:n]
	var f exifFields
	var xmp [][]byte
	var err error
	switch {
	case len(h) >= 3 && h[0] == 0xFF && h[1] == 0xD8 && h[2] == 0xFF:
		m.Format = "jpeg"
		xmp, err = readJPEG(r, 0, size, &f)
	case len(h) >= 4 && (string(h[:4]) == "II*\x00" || string(h[:4]) == "MM\x00*" || string(h[:4]) == "IIRO" ||
		string(h[:4]) == "MMOR" || string(h[:4]) == "IIU\x00" || string(h[:4]) == "IIRS"):
		m.Format = "tiff"
		err = readTIFFStream(r, 0, &f)
	case len(h) >= 15 && string(h[:15]) == "FUJIFILMCCD-RAW":
		m.Format = "raf"
		err = readRAF(r, size, &f)
	case len(h) >= 12 && string(h[4:8]) == "ftyp":
		brand := string(h[8:12])
		switch {
		case brand == "crx ":
			m.Format = "cr3"
			err = readCR3(r, size, &f)
		case heifBrands[brand]:
			m.Format = "heif"
			xmp, err = readHEIF(r, size, &f)
		default:
			return m, f, ErrUnsupported
		}
	default:
		return m, f, ErrUnsupported
	}
	if err != nil && f.isEmpty() {
		return m, f, err
	}
	f.apply(&m)
	if m.Format == "jpeg" || m.Format == "heif" {
		m.Motion = findMotion(r, size, xmp)
	}
	return m, f, nil
}

var heifBrands = map[string]bool{"heic": true, "heix": true, "heim": true, "heis": true, "mif1": true, "msf1": true,
	"hevc": true, "hevx": true, "avif": true, "mif2": true}

func (f *exifFields) isEmpty() bool {
	return f.make == "" && f.model == "" && f.dateTimeOrig == "" && f.dateTime == "" && f.width == 0
}

func (f *exifFields) apply(m *Meta) {
	m.Make, m.Model, m.LensMake, m.Lens = f.make, f.model, f.lensMake, f.lens
	m.Orientation = f.orientation
	m.Width, m.Height = f.width, f.height
	m.GPS = f.gps
	m.Capture = f.capture()
}

// capture applies ADR 0014 "Capture time" to EXIF: DateTimeOriginal + SubSecTimeOriginal + OffsetTimeOriginal,
// falling back to the digitized time, then to DateTime (IFD0), each with its own sub-seconds and offset (and the
// general OffsetTime when the specific one is missing). Without an offset, GPS date and time (UTC) give one when they
// differ from the wall clock by a whole number of quarter hours within ±2 minutes (fix delay), at most 14 hours.
func (f *exifFields) capture() *Capture {
	type cand struct{ dt, sub, off string }
	for _, c := range []cand{{f.dateTimeOrig, f.subSecOrig, f.offsetOrig}, {f.dateTimeDig, f.subSecDig, f.offsetDig}, {f.dateTime, f.subSec, f.offset}} {
		local, ok := parseExifTime(c.dt, c.sub)
		if !ok {
			continue
		}
		off := c.off
		if off == "" {
			off = f.offset
		}
		cp := &Capture{Local: formatLocal(local, c.sub != ""), Source: "exif"}
		if mins, ok := parseOffset(off); ok {
			cp.Offset = formatOffset(mins)
			u := local.Add(-time.Duration(mins) * time.Minute).UnixMilli()
			cp.UTC = &u
		} else if mins, ok := f.gpsOffset(local); ok {
			cp.Offset = formatOffset(mins)
			u := local.Add(-time.Duration(mins) * time.Minute).UnixMilli()
			cp.UTC = &u
			cp.Source = "exif-gps"
		}
		return cp
	}
	return nil
}

func (f *exifFields) gpsOffset(local time.Time) (int, bool) {
	if f.gpsDate == "" || len(f.gpsTime) != 3 {
		return 0, false
	}
	d, err := time.Parse("2006:01:02", strings.ReplaceAll(f.gpsDate, "-", ":"))
	if err != nil {
		return 0, false
	}
	for i, limit := range []float64{24, 60, 61} {
		if v := f.gpsTime[i]; math.IsNaN(v) || v < 0 || v >= limit {
			return 0, false
		}
	}
	secs := f.gpsTime[0]*3600 + f.gpsTime[1]*60 + f.gpsTime[2]
	utc := d.Add(time.Duration(secs * float64(time.Second)))
	diff := local.Sub(utc).Minutes()
	q := math.Round(diff/15) * 15
	if math.Abs(diff-q) > 2 || math.Abs(q) > 14*60 {
		return 0, false
	}
	return int(q), true
}

// parseExifTime reads "YYYY:MM:DD HH:MM:SS" (also with '-' in the date) plus sub-second digits, as a wall clock
// (returned in UTC, meaning "no zone").
func parseExifTime(dt, sub string) (time.Time, bool) {
	dt = strings.TrimSpace(dt)
	if len(dt) < 19 {
		return time.Time{}, false
	}
	dt = dt[:19]
	b := []byte(dt)
	if b[4] == '-' && b[7] == '-' {
		b[4], b[7] = ':', ':'
	}
	if b[10] == 'T' {
		b[10] = ' '
	}
	t, err := time.Parse("2006:01:02 15:04:05", string(b))
	if err != nil || t.Year() < 1900 {
		return time.Time{}, false
	}
	if ms, ok := subSecMillis(sub); ok {
		t = t.Add(time.Duration(ms) * time.Millisecond)
	}
	return t, true
}

func subSecMillis(sub string) (int, bool) {
	sub = strings.TrimSpace(sub)
	if sub == "" {
		return 0, false
	}
	for _, r := range sub {
		if r < '0' || r > '9' {
			return 0, false
		}
	}
	for len(sub) < 3 {
		sub += "0"
	}
	ms, err := strconv.Atoi(sub[:3])
	return ms, err == nil
}

func formatLocal(t time.Time, millis bool) string {
	if millis {
		return t.Format("2006-01-02T15:04:05.000")
	}
	return t.Format("2006-01-02T15:04:05")
}

// parseOffset reads "+09:00", "-0330", "+09" or "Z" into minutes east of UTC.
func parseOffset(s string) (int, bool) {
	s = strings.TrimSpace(s)
	if s == "Z" {
		return 0, true
	}
	if len(s) < 3 || (s[0] != '+' && s[0] != '-') {
		return 0, false
	}
	digits := strings.ReplaceAll(s[1:], ":", "")
	if len(digits) != 2 && len(digits) != 4 {
		return 0, false
	}
	h, err := strconv.Atoi(digits[:2])
	if err != nil {
		return 0, false
	}
	m := 0
	if len(digits) == 4 {
		if m, err = strconv.Atoi(digits[2:]); err != nil || m >= 60 {
			return 0, false
		}
	}
	if h > 14 {
		return 0, false
	}
	v := h*60 + m
	if s[0] == '-' {
		v = -v
	}
	return v, true
}

func formatOffset(mins int) string {
	sign := '+'
	if mins < 0 {
		sign, mins = '-', -mins
	}
	return fmt.Sprintf("%c%02d:%02d", sign, mins/60, mins%60)
}

// MTimeCapture is the last resort: the file's modification time (an instant).
func MTimeCapture(mtimeNs int64) *Capture {
	u := mtimeNs / 1e6
	return &Capture{UTC: &u, Source: "mtime"}
}
