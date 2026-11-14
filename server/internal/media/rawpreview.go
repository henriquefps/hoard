package media

import (
	"bytes"
	"encoding/binary"
	"io"
	"sort"
)

// Embedded is a JPEG stored inside a RAW: its preview, or the camera's full-size rendering (ADR 0015 "Sources").
type Embedded struct {
	Offset, Length int64
	Width, Height  int
}

// LongEdge is the larger of the image's sides.
func (e Embedded) LongEdge() int { return max(e.Width, e.Height) }

// TIFF tags of embedded images.
const (
	tagCompression     = 0x0103
	tagStripOffsets    = 0x0111
	tagStripByteCounts = 0x0117
	tagJPEGOffset      = 0x0201
	tagJPEGLength      = 0x0202
	tagRW2JPEG         = 0x002E // Panasonic JpgFromRaw (UNDEFINED, the JPEG itself)
	maxEmbeddedIFDs    = 6
	minEmbeddedBytes   = 1 << 10
)

// RawPreviews lists the viewable JPEGs inside a RAW, largest first: TIFF-based RAWs (IFD chain and SubIFDs, the JPEG
// interchange tags or single-strip JPEG images, Panasonic's JpgFromRaw), Canon CR3 (the first track's full-size
// JPEG and the PRVW preview) and Fujifilm RAF (the header's JPEG). Only baseline or progressive JPEGs count: a DNG's
// lossless-JPEG raw data is not a preview. Reads are bounded like the metadata readers'.
func RawPreviews(r io.ReaderAt, size int64) []Embedded {
	var head [16]byte
	n, _ := r.ReadAt(head[:], 0)
	h := head[:n]
	var cands [][2]int64
	switch {
	case n >= 16 && string(h[:16]) == "FUJIFILMCCD-RAW ":
		var b [8]byte
		if _, err := r.ReadAt(b[:], 84); err == nil {
			cands = append(cands, [2]int64{int64(binary.BigEndian.Uint32(b[:4])), int64(binary.BigEndian.Uint32(b[4:8]))})
		}
	case n >= 12 && string(h[4:8]) == "ftyp" && string(h[8:12]) == "crx ":
		cands = cr3Previews(r, size)
	default:
		cands = tiffPreviews(r)
	}
	var out []Embedded
	seen := map[int64]bool{}
	for _, c := range cands {
		off, l := c[0], c[1]
		if off <= 0 || l < minEmbeddedBytes || off+l > size || seen[off] {
			continue
		}
		seen[off] = true
		if w, hgt, ok := jpegFrame(r, off, l); ok {
			out = append(out, Embedded{Offset: off, Length: l, Width: w, Height: hgt})
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		return out[i].Width*out[i].Height > out[j].Width*out[j].Height
	})
	return out
}

func tiffPreviews(r io.ReaderAt) [][2]int64 {
	t, off, err := newTIFF(r, 0)
	if err != nil {
		return nil
	}
	var out [][2]int64
	visit := func(d ifd) {
		if o, ok := t.uint(d, tagJPEGOffset); ok {
			if l, ok := t.uint(d, tagJPEGLength); ok {
				out = append(out, [2]int64{int64(o), int64(l)})
			}
		}
		if c, ok := t.uint(d, tagCompression); ok && (c == 6 || c == 7) {
			so, sl := t.uints(d, tagStripOffsets), t.uints(d, tagStripByteCounts)
			if len(so) == 1 && len(sl) == 1 {
				out = append(out, [2]int64{int64(so[0]), int64(sl[0])})
			}
		}
		if e, ok := d[tagRW2JPEG]; ok && t.magic == 0x55 && (e.typ == 7 || e.typ == 1) && e.count > 4 {
			out = append(out, [2]int64{int64(t.bo.Uint32(e.raw[:])), int64(e.count)})
		}
	}
	seen := map[uint32]bool{}
	for i := 0; i < maxEmbeddedIFDs && off != 0 && !seen[off]; i++ {
		seen[off] = true
		d, nextOff, err := t.readIFD(off)
		if err != nil {
			break
		}
		visit(d)
		for j, p := range t.uints(d, tagSubIFDs) {
			if j >= maxSubIFDs {
				break
			}
			if sd, _, err := t.readIFD(p); err == nil {
				visit(sd)
			}
		}
		if i == 0 && (t.magic == 0x4F52 || t.magic == 0x5352) {
			if p, ok := t.uint(d, tagExifIFD); ok {
				if ed, _, err := t.readIFD(p); err == nil {
					out = append(out, olympusPreview(t, ed)...)
				}
			}
		}
		off = nextOff
	}
	return out
}

// olympusPreview reads an ORF's preview from the Olympus MakerNote ("OLYMPUS\0" + byte order, offsets from the
// MakerNote's start): CameraSettings (0x2020) → PreviewImageStart (0x0101) and PreviewImageLength (0x0102).
func olympusPreview(t *tiffReader, exif ifd) [][2]int64 {
	e, ok := exif[0x927C]
	if !ok || e.count < 16 {
		return nil
	}
	base := t.base + int64(t.bo.Uint32(e.raw[:]))
	h, err := readAt(t.r, base, 12)
	if err != nil || string(h[:8]) != "OLYMPUS\x00" {
		return nil
	}
	mn := &tiffReader{r: t.r, base: base, bo: binary.LittleEndian}
	if string(h[8:10]) == "MM" {
		mn.bo = binary.BigEndian
	}
	d, _, err := mn.readIFD(12)
	if err != nil {
		return nil
	}
	cs, ok := mn.uint(d, 0x2020)
	if !ok {
		return nil
	}
	sd, _, err := mn.readIFD(cs)
	if err != nil {
		return nil
	}
	start, ok1 := mn.uint(sd, 0x0101)
	length, ok2 := mn.uint(sd, 0x0102)
	if !ok1 || !ok2 {
		return nil
	}
	return [][2]int64{{base + int64(start), int64(length)}}
}

// cr3PRVW is the Canon CR3 preview box (uuid in the file's top level holding PRVW: a 1620×1080 JPEG).
var cr3PRVW = []byte{0xea, 0xf4, 0x2b, 0x5e, 0x1c, 0x98, 0x4b, 0x88, 0xb9, 0xfb, 0xb7, 0xdc, 0x40, 0x6e, 0x4d, 0x16}

// cr3Previews finds the first track's first sample (the full-size JPEG of a CR3) and the PRVW preview.
func cr3Previews(r io.ReaderAt, size int64) [][2]int64 {
	var out [][2]int64
	_ = eachBox(r, 0, size, func(typ string, p, e int64) bool {
		switch typ {
		case "moov":
			_ = eachBox(r, p, e, func(t string, p2, e2 int64) bool {
				if t != "trak" {
					return true
				}
				if off, l, ok := firstSample(r, p2, e2); ok {
					out = append(out, [2]int64{off, l})
				}
				return false // the first track only
			})
		case "uuid":
			u, err := readAt(r, p, 16)
			if err != nil || !bytes.Equal(u, cr3PRVW) {
				return true
			}
			// uuid payload: 8 bytes, then a PRVW box: u32 size, "PRVW", 4 bytes, u16 1, u16 width, u16 height,
			// u16 1, u32 JPEG length, the JPEG.
			_ = eachBox(r, p+16+8, e, func(t string, p3, _ int64) bool {
				if t == "PRVW" {
					if b, err := readAt(r, p3, 16); err == nil {
						out = append(out, [2]int64{p3 + 16, int64(binary.BigEndian.Uint32(b[12:16]))})
					}
				}
				return false
			})
		}
		return true
	})
	return out
}

// firstSample returns the offset and size of a track's first sample (stco/co64 and stsz of mdia/minf/stbl).
func firstSample(r io.ReaderAt, start, end int64) (int64, int64, bool) {
	var off, n int64
	var walk func(s, e int64, depth int)
	walk = func(s, e int64, depth int) {
		_ = eachBox(r, s, e, func(t string, p, be int64) bool {
			switch t {
			case "mdia", "minf", "stbl":
				if depth < 4 {
					walk(p, be, depth+1)
				}
			case "stsz":
				if b, err := readAt(r, p, 16); err == nil {
					n = int64(binary.BigEndian.Uint32(b[4:8]))
					if n == 0 && binary.BigEndian.Uint32(b[8:12]) > 0 {
						n = int64(binary.BigEndian.Uint32(b[12:16]))
					}
				}
			case "co64":
				if b, err := readAt(r, p, 16); err == nil && binary.BigEndian.Uint32(b[4:8]) > 0 {
					off = int64(binary.BigEndian.Uint64(b[8:16])) //nolint:gosec // checked against the file size by the caller
				}
			case "stco":
				if b, err := readAt(r, p, 12); err == nil && binary.BigEndian.Uint32(b[4:8]) > 0 {
					off = int64(binary.BigEndian.Uint32(b[8:12]))
				}
			}
			return true
		})
	}
	walk(start, end, 0)
	return off, n, off > 0 && n > 0
}

// jpegFrame reads the frame header of the JPEG at [off, off+n): its size, if it is a baseline, extended or
// progressive Huffman JPEG (SOF0–SOF2) that a viewer can decode.
func jpegFrame(r io.ReaderAt, off, n int64) (int, int, bool) {
	var hdr [4]byte
	if _, err := r.ReadAt(hdr[:2], off); err != nil || hdr[0] != 0xFF || hdr[1] != 0xD8 {
		return 0, 0, false
	}
	p := off + 2
	end := off + n
	for range maxSegments {
		if p+4 > end {
			return 0, 0, false
		}
		if _, err := r.ReadAt(hdr[:], p); err != nil || hdr[0] != 0xFF {
			return 0, 0, false
		}
		m := hdr[1]
		switch {
		case m == 0xFF:
			p++
			continue
		case m == 0x01 || (m >= 0xD0 && m <= 0xD7):
			p += 2
			continue
		case m == 0xDA || m == 0xD9:
			return 0, 0, false
		case m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC:
			if m > 0xC2 {
				return 0, 0, false // lossless, hierarchical or arithmetic: raw data, not a preview
			}
			var sof [5]byte
			if _, err := r.ReadAt(sof[:], p+4); err != nil {
				return 0, 0, false
			}
			w, h := int(binary.BigEndian.Uint16(sof[3:5])), int(binary.BigEndian.Uint16(sof[1:3]))
			return w, h, w > 0 && h > 0
		}
		l := int64(binary.BigEndian.Uint16(hdr[2:4]))
		if l < 2 {
			return 0, 0, false
		}
		p += 2 + l
	}
	return 0, 0, false
}

// JPEGSize returns the size of a baseline or progressive JPEG.
func JPEGSize(r io.ReaderAt, size int64) (int, int, bool) { return jpegFrame(r, 0, size) }

// WithOrientation returns the JPEG with its EXIF replaced by a minimal one holding only the given orientation (1–8),
// so that a decoder that applies EXIF orientation shows it upright. An embedded RAW preview carries no orientation of
// its own: the RAW's IFD0 has it.
func WithOrientation(jpeg []byte, orientation int) []byte {
	if len(jpeg) < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8 {
		return jpeg
	}
	if orientation < 1 || orientation > 8 {
		orientation = 1
	}
	tiff := []byte{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, byte(orientation), 0, 0, 0, 0, 0, 0, 0}
	payload := append([]byte("Exif\x00\x00"), tiff...)
	seg := []byte{0xFF, 0xE1, 0, 0}
	binary.BigEndian.PutUint16(seg[2:], uint16(len(payload)+2)) //nolint:gosec // a few dozen bytes
	out := make([]byte, 0, len(jpeg)+len(seg)+len(payload))
	out = append(out, 0xFF, 0xD8)
	out = append(out, seg...)
	out = append(out, payload...)
	// Copy the rest, dropping existing EXIF APP1 segments before the first scan.
	p := 2
	for p+4 <= len(jpeg) && jpeg[p] == 0xFF {
		m := jpeg[p+1]
		if m == 0xDA || m == 0xD9 || m == 0xFF || m == 0x01 || (m >= 0xD0 && m <= 0xD7) {
			break
		}
		l := int(binary.BigEndian.Uint16(jpeg[p+2:]))
		if l < 2 || p+2+l > len(jpeg) {
			break
		}
		segEnd := p + 2 + l
		if isExif := m == 0xE1 && l >= 8 && bytes.Equal(jpeg[p+4:p+10], exifHeader); !isExif {
			out = append(out, jpeg[p:segEnd]...)
		}
		p = segEnd
	}
	return append(out, jpeg[p:]...)
}
