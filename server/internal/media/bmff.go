package media

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
)

const maxBoxes = 4096

var errBox = errors.New("bmff: bad box")

// eachBox calls fn for every ISO-BMFF box between start and end (payload offset, end offset); fn returns false to
// stop.
func eachBox(r io.ReaderAt, start, end int64, fn func(typ string, payload, boxEnd int64) bool) error {
	off := start
	var h [16]byte
	for range maxBoxes {
		if off+8 > end {
			return nil
		}
		if _, err := r.ReadAt(h[:8], off); err != nil {
			return err
		}
		size := int64(binary.BigEndian.Uint32(h[:4]))
		typ := string(h[4:8])
		payload := off + 8
		switch size {
		case 0:
			size = end - off
		case 1:
			if _, err := r.ReadAt(h[8:16], off+8); err != nil {
				return err
			}
			size = int64(binary.BigEndian.Uint64(h[8:16])) //nolint:gosec // checked against end below
			payload = off + 16
		}
		if size < payload-off || off+size > end || size < 0 {
			return errBox
		}
		if !fn(typ, payload, off+size) {
			return nil
		}
		off += size
	}
	return nil
}

func readAt(r io.ReaderAt, off int64, n int) ([]byte, error) {
	if n < 0 || n > maxValueBytes*16 {
		return nil, errBox
	}
	b := make([]byte, n)
	_, err := r.ReadAt(b, off)
	return b, err
}

type heifItem struct {
	typ, contentType string
	offset, length   int64
}

// readHEIF reads a HEIF/HEIC/AVIF still: the meta box's items (EXIF, XMP) and the largest image size (ispe).
func readHEIF(r io.ReaderAt, size int64, f *exifFields) ([][]byte, error) {
	var metaStart, metaEnd int64
	if err := eachBox(r, 0, size, func(typ string, p, e int64) bool {
		if typ == "meta" {
			metaStart, metaEnd = p+4, e // FullBox
			return false
		}
		return true
	}); err != nil {
		return nil, err
	}
	if metaEnd == 0 {
		return nil, errors.New("heif: no meta box")
	}
	items := map[uint32]*heifItem{}
	item := func(id uint32) *heifItem {
		if items[id] == nil {
			items[id] = &heifItem{}
		}
		return items[id]
	}
	err := eachBox(r, metaStart, metaEnd, func(typ string, p, e int64) bool {
		switch typ {
		case "iinf":
			parseIinf(r, p, e, item)
		case "iloc":
			parseIloc(r, p, e, item)
		case "iprp":
			_ = eachBox(r, p, e, func(t string, p2, e2 int64) bool {
				if t == "ipco" {
					_ = eachBox(r, p2, e2, func(t3 string, p3, _ int64) bool {
						if t3 == "ispe" {
							if b, err := readAt(r, p3+4, 8); err == nil {
								f.size(binary.BigEndian.Uint32(b[:4]), binary.BigEndian.Uint32(b[4:]))
							}
						}
						return true
					})
				}
				return true
			})
		}
		return true
	})
	if err != nil {
		return nil, err
	}
	var xmp [][]byte
	for _, it := range items {
		if it.length <= 0 || it.offset < 0 || it.offset+it.length > size {
			continue
		}
		switch {
		case it.typ == "Exif" && it.length > 4:
			b, err := readAt(r, it.offset, 4)
			if err != nil {
				continue
			}
			skip := int64(binary.BigEndian.Uint32(b))
			start := it.offset + 4 + skip
			if skip >= it.length {
				continue
			}
			if err := readTIFFStream(io.NewSectionReader(r, start, it.offset+it.length-start), 0, f); err != nil {
				// Some writers store "Exif\0\0" without the offset: look for the TIFF header near the start.
				head, _ := readAt(r, it.offset, int(min(it.length, 16)))
				for _, mark := range [][]byte{[]byte("II*\x00"), []byte("MM\x00*")} {
					if i := bytes.Index(head, mark); i >= 0 {
						_ = readTIFFStream(io.NewSectionReader(r, it.offset+int64(i), it.length-int64(i)), 0, f)
						break
					}
				}
			}
		case it.typ == "mime" && it.contentType == "application/rdf+xml" && it.length <= maxXMPBytes:
			if b, err := readAt(r, it.offset, int(it.length)); err == nil {
				xmp = append(xmp, b)
			}
		}
	}
	return xmp, nil
}

func cstring(b []byte) (string, []byte) {
	i := bytes.IndexByte(b, 0)
	if i < 0 {
		return string(b), nil
	}
	return string(b[:i]), b[i+1:]
}

func parseIinf(r io.ReaderAt, p, e int64, item func(uint32) *heifItem) {
	v, err := readAt(r, p, 1)
	if err != nil {
		return
	}
	start := p + 4 + 2
	if v[0] != 0 {
		start = p + 4 + 4
	}
	_ = eachBox(r, start, e, func(typ string, p2, e2 int64) bool {
		if typ != "infe" || e2-p2 > 4096 {
			return true
		}
		b, err := readAt(r, p2, int(e2-p2))
		if err != nil || len(b) < 4 {
			return true
		}
		ver := b[0]
		b = b[4:]
		var id uint32
		switch {
		case ver == 2 && len(b) >= 8:
			id, b = uint32(binary.BigEndian.Uint16(b)), b[2:]
		case ver == 3 && len(b) >= 10:
			id, b = binary.BigEndian.Uint32(b), b[4:]
		default:
			return true
		}
		b = b[2:] // protection index
		it := item(id)
		it.typ = string(b[:4])
		_, rest := cstring(b[4:])
		if it.typ == "mime" {
			it.contentType, _ = cstring(rest)
		}
		return true
	})
}

func parseIloc(r io.ReaderAt, p, e int64, item func(uint32) *heifItem) {
	if e-p > 1<<20 {
		return
	}
	b, err := readAt(r, p, int(e-p))
	if err != nil || len(b) < 8 {
		return
	}
	ver := b[0]
	offSize, lenSize, baseSize := int(b[4]>>4), int(b[4]&0xf), int(b[5]>>4)
	idxSize := 0
	if ver == 1 || ver == 2 {
		idxSize = int(b[5] & 0xf)
	}
	pos := 6
	read := func(n int) (uint64, bool) {
		if pos+n > len(b) {
			return 0, false
		}
		var v uint64
		for _, c := range b[pos : pos+n] {
			v = v<<8 | uint64(c)
		}
		pos += n
		return v, true
	}
	idBytes := 2
	if ver == 2 {
		idBytes = 4
	}
	count, ok := read(idBytes)
	if !ok || count > 10000 {
		return
	}
	for range count {
		id, ok := read(idBytes)
		if !ok {
			return
		}
		method := uint64(0)
		if ver == 1 || ver == 2 {
			if method, ok = read(2); !ok {
				return
			}
			method &= 0xf
		}
		if _, ok = read(2); !ok { // data reference index
			return
		}
		base, ok := read(baseSize)
		if !ok {
			return
		}
		extents, ok := read(2)
		if !ok {
			return
		}
		for i := range extents {
			if _, ok = read(idxSize); !ok {
				return
			}
			o, ok1 := read(offSize)
			l, ok2 := read(lenSize)
			if !ok1 || !ok2 {
				return
			}
			if i == 0 && method == 0 && id <= 0xffffffff {
				it := item(uint32(id))
				it.offset, it.length = int64(base+o), int64(l) //nolint:gosec // checked against the file size by the caller
			}
		}
	}
}

// cr3UUID is the Canon CR3 metadata box (moov/uuid) holding CMT1 (IFD0), CMT2 (EXIF) and CMT4 (GPS) as TIFF streams.
var cr3UUID = []byte{0x85, 0xc0, 0xb6, 0x87, 0x82, 0x0f, 0x11, 0xe0, 0x81, 0x11, 0xf4, 0xce, 0x46, 0x2b, 0x6a, 0x48}

func readCR3(r io.ReaderAt, size int64, f *exifFields) error {
	found := false
	err := eachBox(r, 0, size, func(typ string, p, e int64) bool {
		if typ != "moov" {
			return true
		}
		_ = eachBox(r, p, e, func(t string, p2, e2 int64) bool {
			if t != "uuid" {
				return true
			}
			u, err := readAt(r, p2, 16)
			if err != nil || !bytes.Equal(u, cr3UUID) {
				return true
			}
			found = true
			_ = eachBox(r, p2+16, e2, func(t3 string, p3, e3 int64) bool {
				sec := io.NewSectionReader(r, p3, e3-p3)
				tr, off, err := newTIFF(sec, 0)
				if err != nil {
					return true
				}
				d, _, err := tr.readIFD(off)
				if err != nil {
					return true
				}
				switch t3 {
				case "CMT1":
					tr.ifd0(d, f)
				case "CMT2":
					tr.exif(d, f)
				case "CMT4":
					tr.gpsIFD(d, f)
				}
				return true
			})
			return false
		})
		return false
	})
	if err == nil && !found {
		err = errors.New("cr3: no metadata box")
	}
	return err
}
