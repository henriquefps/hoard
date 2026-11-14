// Package mediatest builds small synthetic photo files (TIFF/EXIF, JPEG, HEIF, CR3, RAF, motion photos) for tests,
// the indexing benchmark and the compatibility fixture. Nothing here comes from a real camera or a real person.
package mediatest

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"sort"
)

// TIFF types.
const (
	Byte     = 1
	ASCII    = 2
	Short    = 3
	Long     = 4
	Rational = 5
	Undef    = 7
)

// Tag is one IFD entry. Val is a string (ASCII), []uint16 (SHORT), []uint32 (LONG), [][2]uint32 (RATIONAL) or []byte
// (BYTE/UNDEFINED).
type Tag struct {
	ID  uint16
	Val any
}

// IFD is a directory with optional child directories behind pointer tags (0x8769 EXIF, 0x8825 GPS, 0x014A SubIFDs).
type IFD struct {
	Tags []Tag
	Exif *IFD
	GPS  *IFD
	Subs []*IFD
}

type tiffWriter struct {
	bo  binary.ByteOrder
	buf []byte
}

func (w *tiffWriter) u16(v uint16) []byte { b := make([]byte, 2); w.bo.PutUint16(b, v); return b }
func (w *tiffWriter) u32(v uint32) []byte { b := make([]byte, 4); w.bo.PutUint32(b, v); return b }

func (w *tiffWriter) encode(t Tag) (typ uint16, count uint32, data []byte) {
	switch v := t.Val.(type) {
	case string:
		b := append([]byte(v), 0)
		return ASCII, uint32(len(b)), b
	case []uint16:
		var b []byte
		for _, x := range v {
			b = append(b, w.u16(x)...)
		}
		return Short, uint32(len(v)), b
	case []uint32:
		var b []byte
		for _, x := range v {
			b = append(b, w.u32(x)...)
		}
		return Long, uint32(len(v)), b
	case [][2]uint32:
		var b []byte
		for _, x := range v {
			b = append(b, w.u32(x[0])...)
			b = append(b, w.u32(x[1])...)
		}
		return Rational, uint32(len(v)), b
	case []byte:
		return Undef, uint32(len(v)), v
	}
	panic(fmt.Sprintf("mediatest: tag %#x has an unsupported value %T", t.ID, t.Val))
}

// write appends an IFD (and its children) and returns its offset.
func (w *tiffWriter) write(d *IFD) uint32 {
	tags := append([]Tag(nil), d.Tags...)
	type ptr struct {
		id   uint16
		dirs []*IFD
	}
	var ptrs []ptr
	if d.Exif != nil {
		ptrs = append(ptrs, ptr{0x8769, []*IFD{d.Exif}})
	}
	if d.GPS != nil {
		ptrs = append(ptrs, ptr{0x8825, []*IFD{d.GPS}})
	}
	if len(d.Subs) > 0 {
		ptrs = append(ptrs, ptr{0x014A, d.Subs})
	}
	for _, p := range ptrs {
		tags = append(tags, Tag{p.id, make([]uint32, len(p.dirs))})
	}
	sort.Slice(tags, func(i, j int) bool { return tags[i].ID < tags[j].ID })
	start := uint32(len(w.buf))
	n := len(tags)
	w.buf = append(w.buf, make([]byte, 2+12*n+4)...)
	copy(w.buf[start:], w.u16(uint16(n)))
	entryAt := map[uint16]int{}
	for i, t := range tags {
		typ, count, data := w.encode(t)
		e := int(start) + 2 + 12*i
		copy(w.buf[e:], w.u16(t.ID))
		copy(w.buf[e+2:], w.u16(typ))
		copy(w.buf[e+4:], w.u32(count))
		if len(data) <= 4 {
			copy(w.buf[e+8:], data)
		} else {
			if len(w.buf)%2 == 1 {
				w.buf = append(w.buf, 0)
			}
			copy(w.buf[e+8:], w.u32(uint32(len(w.buf))))
			w.buf = append(w.buf, data...)
		}
		entryAt[t.ID] = e
	}
	for _, p := range ptrs {
		offs := make([]uint32, len(p.dirs))
		for i, c := range p.dirs {
			if len(w.buf)%2 == 1 {
				w.buf = append(w.buf, 0)
			}
			offs[i] = w.write(c)
		}
		e := entryAt[p.id]
		if len(offs) == 1 {
			copy(w.buf[e+8:], w.u32(offs[0]))
		} else {
			valOff := w.bo.Uint32(w.buf[e+8:])
			for i, o := range offs {
				copy(w.buf[int(valOff)+4*i:], w.u32(o))
			}
		}
	}
	return start
}

// TIFF serialises a TIFF stream with the given magic (42 for TIFF and most RAWs).
func TIFF(d *IFD, bo binary.ByteOrder, magic uint16) []byte {
	w := &tiffWriter{bo: bo}
	if bo == binary.LittleEndian {
		w.buf = append(w.buf, 'I', 'I')
	} else {
		w.buf = append(w.buf, 'M', 'M')
	}
	w.buf = append(w.buf, w.u16(magic)...)
	w.buf = append(w.buf, 0, 0, 0, 0)
	off := w.write(d)
	copy(w.buf[4:], w.u32(off))
	return w.buf
}

// Shot is the metadata a synthetic file carries.
type Shot struct {
	Make, Model, Lens string
	DateTime          string // "2024:05:01 10:00:00"
	SubSec            string
	Offset            string // "+09:00"
	Orientation       uint16
	Width, Height     uint32
	Lat, Lon          float64 // 0, 0 = none
	GPSDate           string  // "2024:05:01"
	GPSTime           [3]uint32
	Unique            uint64 // written into ImageUniqueID so every file differs
	// Exposure (optional): ExposureTime and FNumber and FocalLength as rationals, ISO and the 35 mm focal length.
	ExposureTime, FNumber, FocalLength [2]uint32
	ISO, Focal35                       uint16
}

func dms(v float64) [][2]uint32 {
	if v < 0 {
		v = -v
	}
	d := uint32(v)
	m := uint32((v - float64(d)) * 60)
	s := (v - float64(d) - float64(m)/60) * 3600
	return [][2]uint32{{d, 1}, {m, 1}, {uint32(s * 1000), 1000}}
}

// IFD0 builds IFD0 with EXIF and GPS directories for s.
func (s Shot) IFD0() *IFD {
	d := &IFD{}
	add := func(dst *IFD, id uint16, v any) { dst.Tags = append(dst.Tags, Tag{id, v}) }
	if s.Make != "" {
		add(d, 0x010F, s.Make)
	}
	if s.Model != "" {
		add(d, 0x0110, s.Model)
	}
	if s.Orientation != 0 {
		add(d, 0x0112, []uint16{s.Orientation})
	}
	if s.DateTime != "" {
		add(d, 0x0132, s.DateTime)
	}
	ex := &IFD{}
	if s.DateTime != "" {
		add(ex, 0x9003, s.DateTime)
		add(ex, 0x9004, s.DateTime)
	}
	if s.SubSec != "" {
		add(ex, 0x9291, s.SubSec)
	}
	if s.Offset != "" {
		add(ex, 0x9011, s.Offset)
		add(ex, 0x9010, s.Offset)
	}
	if s.Lens != "" {
		add(ex, 0xA434, s.Lens)
	}
	if s.Width != 0 {
		add(ex, 0xA002, []uint32{s.Width})
		add(ex, 0xA003, []uint32{s.Height})
	}
	if s.ExposureTime[1] != 0 {
		add(ex, 0x829A, [][2]uint32{s.ExposureTime})
	}
	if s.FNumber[1] != 0 {
		add(ex, 0x829D, [][2]uint32{s.FNumber})
	}
	if s.ISO != 0 {
		add(ex, 0x8827, []uint16{s.ISO})
	}
	if s.FocalLength[1] != 0 {
		add(ex, 0x920A, [][2]uint32{s.FocalLength})
	}
	if s.Focal35 != 0 {
		add(ex, 0xA405, []uint16{s.Focal35})
	}
	add(ex, 0xA420, fmt.Sprintf("%032x", s.Unique))
	d.Exif = ex
	if s.Lat != 0 || s.Lon != 0 {
		g := &IFD{}
		latRef, lonRef := "N", "E"
		if s.Lat < 0 {
			latRef = "S"
		}
		if s.Lon < 0 {
			lonRef = "W"
		}
		add(g, 0x0001, latRef)
		add(g, 0x0002, dms(s.Lat))
		add(g, 0x0003, lonRef)
		add(g, 0x0004, dms(s.Lon))
		add(g, 0x0005, []byte{0})
		add(g, 0x0006, [][2]uint32{{12345, 100}})
		if s.GPSDate != "" {
			add(g, 0x0007, [][2]uint32{{s.GPSTime[0], 1}, {s.GPSTime[1], 1}, {s.GPSTime[2], 1}})
			add(g, 0x001D, s.GPSDate)
		}
		d.GPS = g
	}
	return d
}

// RAW is a TIFF-based RAW (ARW/NEF/DNG-like): IFD0 with a small preview size and a SubIFD with the full size.
func RAW(s Shot) []byte {
	d := s.IFD0()
	d.Tags = append(d.Tags, Tag{0x0100, []uint32{160}}, Tag{0x0101, []uint32{120}})
	full := &IFD{Tags: []Tag{{0x0100, []uint32{s.Width}}, {0x0101, []uint32{s.Height}}}}
	d.Subs = []*IFD{full}
	ex := *d.Exif
	ex.Tags = nil
	for _, t := range d.Exif.Tags {
		if t.ID != 0xA002 && t.ID != 0xA003 {
			ex.Tags = append(ex.Tags, t)
		}
	}
	d.Exif = &ex
	return TIFF(d, binary.LittleEndian, 42)
}

func segment(marker byte, payload []byte) []byte {
	b := []byte{0xFF, marker, 0, 0}
	binary.BigEndian.PutUint16(b[2:], uint16(len(payload)+2))
	return append(b, payload...)
}

// JPEG builds a JPEG with an EXIF APP1, optional XMP APP1, a frame header of the shot's size and a tiny scan.
func JPEG(s Shot, xmp string) []byte {
	var b bytes.Buffer
	b.Write([]byte{0xFF, 0xD8})
	b.Write(segment(0xE1, append([]byte("Exif\x00\x00"), TIFF(s.IFD0(), binary.BigEndian, 42)...)))
	if xmp != "" {
		b.Write(segment(0xE1, append([]byte("http://ns.adobe.com/xap/1.0/\x00"), xmp...)))
	}
	sof := []byte{8, 0, 0, 0, 0, 3, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0}
	binary.BigEndian.PutUint16(sof[1:], uint16(s.Height))
	binary.BigEndian.PutUint16(sof[3:], uint16(s.Width))
	b.Write(segment(0xC0, sof))
	b.Write(segment(0xDA, []byte{3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0}))
	b.Write([]byte{0x12, 0x34, 0x56, 0xFF, 0xD9})
	return b.Bytes()
}

func box(typ string, payload ...[]byte) []byte {
	n := 8
	for _, p := range payload {
		n += len(p)
	}
	b := make([]byte, 8, n)
	binary.BigEndian.PutUint32(b, uint32(n))
	copy(b[4:], typ)
	for _, p := range payload {
		b = append(b, p...)
	}
	return b
}

func full(version byte, flags uint32) []byte {
	return []byte{version, byte(flags >> 16), byte(flags >> 8), byte(flags)}
}

func u16(v uint16) []byte { b := make([]byte, 2); binary.BigEndian.PutUint16(b, v); return b }
func u32(v uint32) []byte { b := make([]byte, 4); binary.BigEndian.PutUint32(b, v); return b }

// HEIF builds a HEIC still: ftyp, a meta box with an EXIF item (and an XMP item when xmp is given), ispe properties
// for a tile and the full size, and the item data in an mdat box.
func HEIF(s Shot, xmp string) []byte {
	exif := append(u32(6), append([]byte("Exif\x00\x00"), TIFF(s.IFD0(), binary.BigEndian, 42)...)...)
	ftyp := box("ftyp", []byte("heic"), u32(0), []byte("mif1heic"))
	infe := func(id uint16, typ, contentType string) []byte {
		p := append(full(2, 0), u16(id)...)
		p = append(p, u16(0)...)
		p = append(p, typ...)
		p = append(p, 0)
		if typ == "mime" {
			p = append(p, contentType...)
			p = append(p, 0)
		}
		return box("infe", p)
	}
	items := [][]byte{infe(1, "hvc1", ""), infe(2, "Exif", "")}
	count := uint16(2)
	if xmp != "" {
		items = append(items, infe(3, "mime", "application/rdf+xml"))
		count = 3
	}
	iinf := box("iinf", append(append(full(0, 0), u16(count)...), bytes.Join(items, nil)...))
	ispe := func(w, h uint32) []byte { return box("ispe", full(0, 0), u32(w), u32(h)) }
	iprp := box("iprp", box("ipco", ispe(512, 512), ispe(s.Width, s.Height)))
	// iloc v0: offset 4, length 4, base 0; offsets patched once the meta box size is known.
	ilocFor := func(mdatStart uint32) []byte {
		p := append(full(0, 0), 0x44, 0x00)
		p = append(p, u16(count)...)
		off := mdatStart
		add := func(id uint16, n int) {
			p = append(p, u16(id)...)
			p = append(p, u16(0)...)
			p = append(p, u16(1)...)
			p = append(p, u32(off)...)
			p = append(p, u32(uint32(n))...)
			off += uint32(n)
		}
		add(1, 4)
		add(2, len(exif))
		if xmp != "" {
			add(3, len(xmp))
		}
		return box("iloc", p)
	}
	hdlr := box("hdlr", full(0, 0), u32(0), []byte("pict"), make([]byte, 12), []byte{0})
	pitm := box("pitm", full(0, 0), u16(1))
	meta := box("meta", full(0, 0), hdlr, pitm, iinf, ilocFor(0), iprp)
	start := uint32(len(ftyp) + len(meta) + 8)
	meta = box("meta", full(0, 0), hdlr, pitm, iinf, ilocFor(start), iprp)
	data := append([]byte{0, 0, 0, 0}, exif...)
	data = append(data, xmp...)
	return bytes.Join([][]byte{ftyp, meta, box("mdat", data)}, nil)
}

// CR3 builds a Canon CR3-like file: ftyp "crx " and moov/uuid with CMT1 (IFD0), CMT2 (EXIF) and CMT4 (GPS).
func CR3(s Shot) []byte {
	d := s.IFD0()
	cmt1 := TIFF(&IFD{Tags: d.Tags}, binary.LittleEndian, 42)
	cmt2 := TIFF(d.Exif, binary.LittleEndian, 42)
	parts := [][]byte{box("CMT1", cmt1), box("CMT2", cmt2)}
	if d.GPS != nil {
		parts = append(parts, box("CMT4", TIFF(d.GPS, binary.LittleEndian, 42)))
	}
	uuid := []byte{0x85, 0xc0, 0xb6, 0x87, 0x82, 0x0f, 0x11, 0xe0, 0x81, 0x11, 0xf4, 0xce, 0x46, 0x2b, 0x6a, 0x48}
	moov := box("moov", box("uuid", append(uuid, bytes.Join(parts, nil)...)))
	return append(box("ftyp", []byte("crx "), u32(1), []byte("crx isom")), moov...)
}

// RAF builds a Fujifilm RAF-like file: the header with the embedded JPEG's offset and length at bytes 84 and 88.
func RAF(s Shot) []byte {
	jpg := JPEG(s, "")
	h := make([]byte, 100)
	copy(h, "FUJIFILMCCD-RAW 0201FF383501")
	binary.BigEndian.PutUint32(h[84:], 100)
	binary.BigEndian.PutUint32(h[88:], uint32(len(jpg)))
	return append(h, jpg...)
}

// MotionXMP is the XMP of a Google/Samsung motion photo whose MP4 (videoLen bytes) ends the file.
func MotionXMP(videoLen int) string {
	return fmt.Sprintf(`<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">`+
		`<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" `+
		`xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" `+
		`GCamera:MotionPhotoPresentationTimestampUs="500000"><Container:Directory><rdf:Seq>`+
		`<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>`+
		`<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="%d" Item:Padding="0"/></rdf:li>`+
		`</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>`, videoLen)
}

// MotionJPEG is a Google-style motion photo: a JPEG with MotionXMP followed by the MP4.
func MotionJPEG(s Shot, mp4 []byte) []byte {
	return append(JPEG(s, MotionXMP(len(mp4))), mp4...)
}

// SamsungTrailer wraps an MP4 in Samsung's SEF trailer (a "MotionPhoto_Data" block, the SEFH directory, its length and
// "SEFT"), to append after a JPEG.
func SamsungTrailer(mp4 []byte) []byte {
	name := []byte("MotionPhoto_Data")
	block := make([]byte, 8)
	binary.LittleEndian.PutUint16(block[2:], 0x0a30)
	binary.LittleEndian.PutUint32(block[4:], uint32(len(name)))
	block = append(append(block, name...), mp4...)
	dir := []byte("SEFH")
	dir = binary.LittleEndian.AppendUint32(dir, 106)
	dir = binary.LittleEndian.AppendUint32(dir, 1)
	dir = binary.LittleEndian.AppendUint16(dir, 0)
	dir = binary.LittleEndian.AppendUint16(dir, 0x0a30)
	dir = binary.LittleEndian.AppendUint32(dir, uint32(len(block))) // offset back from SEFH
	dir = binary.LittleEndian.AppendUint32(dir, uint32(len(block)))
	out := append(block, dir...)
	out = binary.LittleEndian.AppendUint32(out, uint32(len(dir)))
	return append(out, "SEFT"...)
}

// SamsungMotionJPEG is a Samsung-style motion photo without the Container directory: JPEG + SEF trailer.
func SamsungMotionJPEG(s Shot, mp4 []byte) []byte {
	return append(JPEG(s, ""), SamsungTrailer(mp4)...)
}

// MiniMP4 is the smallest ISO-BMFF stream the motion readers accept (ftyp + an empty free box); not playable.
func MiniMP4(unique uint64) []byte {
	u := make([]byte, 8)
	binary.BigEndian.PutUint64(u, unique)
	return append(box("ftyp", []byte("mp42"), u32(0), []byte("isommp42")), box("free", u)...)
}
