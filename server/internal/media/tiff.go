package media

import (
	"encoding/binary"
	"errors"
	"io"
	"math"
	"strings"
)

// TIFF/EXIF tags read by the server (ADR 0014 "Metadata").
const (
	tagImageWidth      = 0x0100
	tagImageLength     = 0x0101
	tagMake            = 0x010F
	tagModel           = 0x0110
	tagOrientation     = 0x0112
	tagDateTime        = 0x0132
	tagSubIFDs         = 0x014A
	tagExifIFD         = 0x8769
	tagGPSIFD          = 0x8825
	tagOffsetTime      = 0x9010
	tagOffsetTimeOrig  = 0x9011
	tagOffsetTimeDig   = 0x9012
	tagDateTimeOrig    = 0x9003
	tagDateTimeDig     = 0x9004
	tagSubSecTime      = 0x9290
	tagSubSecTimeOrig  = 0x9291
	tagSubSecTimeDig   = 0x9292
	tagPixelXDimension = 0xA002
	tagPixelYDimension = 0xA003
	tagLensMake        = 0xA433
	tagLensModel       = 0xA434

	gpsLatRef    = 0x0001
	gpsLat       = 0x0002
	gpsLonRef    = 0x0003
	gpsLon       = 0x0004
	gpsAltRef    = 0x0005
	gpsAlt       = 0x0006
	gpsTimeStamp = 0x0007
	gpsDateStamp = 0x001D
)

const (
	maxIFDEntries = 512
	maxValueBytes = 64 << 10
	maxSubIFDs    = 8
)

var errTIFF = errors.New("not a TIFF stream")

// tiffReader reads one TIFF stream (a TIFF-based RAW, or the EXIF block of a JPEG/HEIF) at base inside r.
type tiffReader struct {
	r     io.ReaderAt
	base  int64
	bo    binary.ByteOrder
	magic uint16
}

type ifdEntry struct {
	typ   uint16
	count uint32
	raw   [4]byte
}

type ifd map[uint16]ifdEntry

// newTIFF checks the header (II/MM and the magic: 42, or the ORF and RW2 variants) and returns the reader and the
// offset of IFD0.
func newTIFF(r io.ReaderAt, base int64) (*tiffReader, uint32, error) {
	var h [8]byte
	if _, err := r.ReadAt(h[:], base); err != nil {
		return nil, 0, errTIFF
	}
	t := &tiffReader{r: r, base: base}
	switch string(h[:2]) {
	case "II":
		t.bo = binary.LittleEndian
	case "MM":
		t.bo = binary.BigEndian
	default:
		return nil, 0, errTIFF
	}
	t.magic = t.bo.Uint16(h[2:4])
	switch t.magic {
	case 42, 0x4F52, 0x5352, 0x55: // TIFF, ORF ("IIRO", "MMOR"), ORF variant, RW2 ("IIU")
	default:
		return nil, 0, errTIFF
	}
	return t, t.bo.Uint32(h[4:8]), nil
}

var typeSize = map[uint16]uint32{1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4}

// readIFD reads the directory at off (relative to the stream) and the offset of the next one.
func (t *tiffReader) readIFD(off uint32) (ifd, uint32, error) {
	if off < 8 {
		return nil, 0, errTIFF
	}
	var nb [2]byte
	if _, err := t.r.ReadAt(nb[:], t.base+int64(off)); err != nil {
		return nil, 0, err
	}
	n := int(t.bo.Uint16(nb[:]))
	if n == 0 || n > maxIFDEntries {
		return nil, 0, errTIFF
	}
	buf := make([]byte, n*12+4)
	if _, err := t.r.ReadAt(buf, t.base+int64(off)+2); err != nil && (!errors.Is(err, io.EOF) || len(buf) <= 4) {
		return nil, 0, err
	}
	out := make(ifd, n)
	for i := range n {
		e := buf[i*12 : i*12+12]
		var raw [4]byte
		copy(raw[:], e[8:12])
		out[t.bo.Uint16(e[0:2])] = ifdEntry{typ: t.bo.Uint16(e[2:4]), count: t.bo.Uint32(e[4:8]), raw: raw}
	}
	return out, t.bo.Uint32(buf[n*12:]), nil
}

// value returns the bytes of an entry (inline or at its offset).
func (t *tiffReader) value(e ifdEntry) ([]byte, bool) {
	sz, ok := typeSize[e.typ]
	if !ok || e.count == 0 || uint64(e.count)*uint64(sz) > maxValueBytes {
		return nil, false
	}
	n := e.count * sz
	if n <= 4 {
		return e.raw[:n], true
	}
	b := make([]byte, n)
	if _, err := t.r.ReadAt(b, t.base+int64(t.bo.Uint32(e.raw[:]))); err != nil {
		return nil, false
	}
	return b, true
}

func (t *tiffReader) str(d ifd, tag uint16) string {
	e, ok := d[tag]
	if !ok || (e.typ != 2 && e.typ != 7 && e.typ != 1) {
		return ""
	}
	b, ok := t.value(e)
	if !ok {
		return ""
	}
	if i := strings.IndexByte(string(b), 0); i >= 0 {
		b = b[:i]
	}
	return cleanString(string(b))
}

// uints returns SHORT/LONG/BYTE values.
func (t *tiffReader) uints(d ifd, tag uint16) []uint32 {
	e, ok := d[tag]
	if !ok {
		return nil
	}
	b, ok := t.value(e)
	if !ok {
		return nil
	}
	var out []uint32
	switch e.typ {
	case 1, 7:
		for _, v := range b {
			out = append(out, uint32(v))
		}
	case 3:
		for i := 0; i+2 <= len(b); i += 2 {
			out = append(out, uint32(t.bo.Uint16(b[i:])))
		}
	case 4, 13:
		for i := 0; i+4 <= len(b); i += 4 {
			out = append(out, t.bo.Uint32(b[i:]))
		}
	}
	return out
}

func (t *tiffReader) uint(d ifd, tag uint16) (uint32, bool) {
	v := t.uints(d, tag)
	if len(v) == 0 {
		return 0, false
	}
	return v[0], true
}

// rationals returns RATIONAL/SRATIONAL values as floats (a zero denominator is NaN).
func (t *tiffReader) rationals(d ifd, tag uint16) []float64 {
	e, ok := d[tag]
	if !ok || (e.typ != 5 && e.typ != 10) {
		return nil
	}
	b, ok := t.value(e)
	if !ok {
		return nil
	}
	var out []float64
	for i := 0; i+8 <= len(b); i += 8 {
		var num, den float64
		if e.typ == 5 {
			num, den = float64(t.bo.Uint32(b[i:])), float64(t.bo.Uint32(b[i+4:]))
		} else {
			num, den = float64(int32(t.bo.Uint32(b[i:]))), float64(int32(t.bo.Uint32(b[i+4:]))) //nolint:gosec // SRATIONAL is signed by definition
		}
		if den == 0 {
			out = append(out, math.NaN())
		} else {
			out = append(out, num/den)
		}
	}
	return out
}

// exifFields is what one TIFF stream contributes; zero values are "not present".
type exifFields struct {
	make, model, lensMake, lens string
	orientation                 int
	width, height               int // the largest image described (IFD0, SubIFDs, EXIF pixel dimensions)
	dateTimeOrig, dateTimeDig   string
	dateTime                    string
	subSecOrig, subSecDig       string
	subSec                      string
	offsetOrig, offsetDig       string
	offset                      string
	gps                         *GPS
	gpsDate                     string // "YYYY:MM:DD"
	gpsTime                     []float64
}

func (f *exifFields) size(w, h uint32) {
	if w == 0 || h == 0 || w > 1<<17 || h > 1<<17 {
		return
	}
	if int64(w)*int64(h) > int64(f.width)*int64(f.height) {
		f.width, f.height = int(w), int(h)
	}
}

// readTIFFStream reads IFD0 and the IFDs it points to (EXIF, GPS, SubIFDs) of a whole TIFF stream.
func readTIFFStream(r io.ReaderAt, base int64, f *exifFields) error {
	t, off, err := newTIFF(r, base)
	if err != nil {
		return err
	}
	d0, _, err := t.readIFD(off)
	if err != nil {
		return err
	}
	t.ifd0(d0, f)
	if p, ok := t.uint(d0, tagExifIFD); ok {
		if d, _, err := t.readIFD(p); err == nil {
			t.exif(d, f)
		}
	}
	if p, ok := t.uint(d0, tagGPSIFD); ok {
		if d, _, err := t.readIFD(p); err == nil {
			t.gpsIFD(d, f)
		}
	}
	subs := t.uints(d0, tagSubIFDs)
	for i, p := range subs {
		if i >= maxSubIFDs {
			break
		}
		if d, _, err := t.readIFD(p); err == nil {
			w, _ := t.uint(d, tagImageWidth)
			h, _ := t.uint(d, tagImageLength)
			f.size(w, h)
		}
	}
	return nil
}

func (t *tiffReader) ifd0(d ifd, f *exifFields) {
	setStr(&f.make, t.str(d, tagMake))
	setStr(&f.model, t.str(d, tagModel))
	setStr(&f.dateTime, t.str(d, tagDateTime))
	if o, ok := t.uint(d, tagOrientation); ok && o >= 1 && o <= 8 && f.orientation == 0 {
		f.orientation = int(o)
	}
	w, _ := t.uint(d, tagImageWidth)
	h, _ := t.uint(d, tagImageLength)
	f.size(w, h)
	if t.magic == 0x55 { // Panasonic RW2: the sensor borders give the image size
		top, ok1 := t.uint(d, 0x0004)
		left, ok2 := t.uint(d, 0x0005)
		bottom, ok3 := t.uint(d, 0x0006)
		right, ok4 := t.uint(d, 0x0007)
		if ok1 && ok2 && ok3 && ok4 && right > left && bottom > top {
			f.size(right-left, bottom-top)
		}
	}
}

func (t *tiffReader) exif(d ifd, f *exifFields) {
	setStr(&f.dateTimeOrig, t.str(d, tagDateTimeOrig))
	setStr(&f.dateTimeDig, t.str(d, tagDateTimeDig))
	setStr(&f.subSecOrig, t.str(d, tagSubSecTimeOrig))
	setStr(&f.subSecDig, t.str(d, tagSubSecTimeDig))
	setStr(&f.subSec, t.str(d, tagSubSecTime))
	setStr(&f.offsetOrig, t.str(d, tagOffsetTimeOrig))
	setStr(&f.offsetDig, t.str(d, tagOffsetTimeDig))
	setStr(&f.offset, t.str(d, tagOffsetTime))
	setStr(&f.lensMake, t.str(d, tagLensMake))
	setStr(&f.lens, t.str(d, tagLensModel))
	w, _ := t.uint(d, tagPixelXDimension)
	h, _ := t.uint(d, tagPixelYDimension)
	f.size(w, h)
}

func (t *tiffReader) gpsIFD(d ifd, f *exifFields) {
	lat, lon := t.rationals(d, gpsLat), t.rationals(d, gpsLon)
	if len(lat) == 3 && len(lon) == 3 {
		la := lat[0] + lat[1]/60 + lat[2]/3600
		lo := lon[0] + lon[1]/60 + lon[2]/3600
		if strings.EqualFold(t.str(d, gpsLatRef), "S") {
			la = -la
		}
		if strings.EqualFold(t.str(d, gpsLonRef), "W") {
			lo = -lo
		}
		if !math.IsNaN(la) && !math.IsNaN(lo) && math.Abs(la) <= 90 && math.Abs(lo) <= 180 && (la != 0 || lo != 0) {
			g := &GPS{Lat: round(la, 7), Lon: round(lo, 7)}
			if alt := t.rationals(d, gpsAlt); len(alt) == 1 && !math.IsNaN(alt[0]) {
				a := alt[0]
				if ref, ok := t.uint(d, gpsAltRef); ok && ref == 1 {
					a = -a
				}
				a = round(a, 2)
				g.Alt = &a
			}
			f.gps = g
		}
	}
	f.gpsDate = t.str(d, gpsDateStamp)
	if ts := t.rationals(d, gpsTimeStamp); len(ts) == 3 {
		f.gpsTime = ts
	}
}

func setStr(dst *string, v string) {
	if *dst == "" && v != "" {
		*dst = v
	}
}

// cleanString trims spaces and drops control characters and invalid UTF-8 (camera strings are padded ASCII).
func cleanString(s string) string {
	s = strings.ToValidUTF8(s, "")
	s = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, s)
	s = strings.TrimSpace(s)
	if len(s) > 200 {
		s = s[:200]
	}
	return s
}

func round(v float64, places int) float64 {
	p := math.Pow(10, float64(places))
	return math.Round(v*p) / p
}
