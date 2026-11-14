package media

import (
	"bytes"
	"encoding/binary"
	"encoding/xml"
	"io"
	"strconv"
	"strings"
)

const (
	nsGCamera   = "http://ns.google.com/photos/1.0/camera/"
	nsContainer = "http://ns.google.com/photos/1.0/container/"
	nsItem      = "http://ns.google.com/photos/1.0/container/item/"
)

type xmpMotion struct {
	motionPhoto      bool
	microVideo       bool
	microVideoOffset int64
	presentationUs   *int64
	items            []xmpItem
}

type xmpItem struct {
	semantic, mime  string
	length, padding int64
}

// parseXMPMotion reads the Google camera properties of an XMP packet: GCamera:MotionPhoto (with the
// Container:Directory items) and the older GCamera:MicroVideo, as attributes or elements.
func parseXMPMotion(packet []byte, m *xmpMotion) {
	// Tokens only, from a packet of at most 1 MiB; encoding/xml expands no external entities.
	d := xml.NewDecoder(bytes.NewReader(packet)) //nolint:gosec // see above
	d.Strict = false
	var stack []xml.Name
	for {
		tok, err := d.Token()
		if err != nil {
			return
		}
		switch t := tok.(type) {
		case xml.StartElement:
			stack = append(stack, t.Name)
			var it xmpItem
			isItem := false
			for _, a := range t.Attr {
				m.prop(a.Name, a.Value)
				if a.Name.Space == nsItem {
					isItem = true
					it.set(a.Name.Local, a.Value)
				}
			}
			if isItem {
				m.items = append(m.items, it)
			}
		case xml.EndElement:
			if len(stack) > 0 {
				stack = stack[:len(stack)-1]
			}
		case xml.CharData:
			if len(stack) > 0 {
				m.prop(stack[len(stack)-1], strings.TrimSpace(string(t)))
			}
		}
	}
}

func (m *xmpMotion) prop(n xml.Name, v string) {
	if n.Space != nsGCamera {
		return
	}
	switch n.Local {
	case "MotionPhoto":
		m.motionPhoto = m.motionPhoto || v == "1"
	case "MicroVideo":
		m.microVideo = m.microVideo || v == "1"
	case "MicroVideoOffset":
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			m.microVideoOffset = n
		}
	case "MotionPhotoPresentationTimestampUs", "MicroVideoPresentationTimestampUs":
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n >= 0 {
			m.presentationUs = &n
		}
	}
}

func (it *xmpItem) set(k, v string) {
	switch k {
	case "Semantic":
		it.semantic = v
	case "Mime":
		it.mime = v
	case "Length":
		it.length, _ = strconv.ParseInt(v, 10, 64)
	case "Padding":
		it.padding, _ = strconv.ParseInt(v, 10, 64)
	}
}

// isMP4At checks that an ISO-BMFF file (an "ftyp" box) starts at off.
func isMP4At(r io.ReaderAt, off, size int64) bool {
	if off <= 0 || off+16 > size {
		return false
	}
	var b [8]byte
	if _, err := r.ReadAt(b[:], off); err != nil {
		return false
	}
	return string(b[4:8]) == "ftyp"
}

// findMotion locates the video of a motion photo: the Container directory's MotionPhoto item (at the end of the
// file), GCamera:MicroVideoOffset (bytes from the end), then Samsung's SEF trailer ("MotionPhoto_Data"). Each
// candidate is accepted only when an MP4 "ftyp" box starts there.
func findMotion(r io.ReaderAt, size int64, packets [][]byte) *Motion {
	var x xmpMotion
	for _, p := range packets {
		parseXMPMotion(p, &x)
	}
	if x.motionPhoto {
		// Secondary items follow the primary in directory order and end at the end of the file.
		var tail int64
		for i := len(x.items) - 1; i >= 1; i-- {
			tail += x.items[i].length + x.items[i].padding
			if x.items[i].semantic == "MotionPhoto" && x.items[i].length > 0 {
				off := size - tail
				if isMP4At(r, off, size) {
					return &Motion{Format: "google", Offset: off, Length: x.items[i].length, PresentationUs: x.presentationUs}
				}
			}
		}
	}
	if x.microVideo && x.microVideoOffset > 0 {
		off := size - x.microVideoOffset
		if isMP4At(r, off, size) {
			return &Motion{Format: "microvideo", Offset: off, Length: x.microVideoOffset, PresentationUs: x.presentationUs}
		}
	}
	if off, n, ok := samsungMotion(r, size); ok {
		m := &Motion{Format: "samsung", Offset: off, Length: n}
		if x.motionPhoto {
			m.PresentationUs = x.presentationUs
		}
		return m
	}
	return nil
}

// samsungMotion reads Samsung's SEF trailer: "...[data blocks][SEFH directory][u32 directory length]SEFT". Each
// directory entry (12 bytes, little-endian: u16 -, u16 type, u32 offset back from SEFH, u32 length) points at a block
// "u16 -, u16 type, u32 name length, name, payload"; the MP4 is the payload of "MotionPhoto_Data".
func samsungMotion(r io.ReaderAt, size int64) (int64, int64, bool) {
	if size < 32 {
		return 0, 0, false
	}
	var tail [8]byte
	if _, err := r.ReadAt(tail[:], size-8); err != nil || string(tail[4:]) != "SEFT" {
		return 0, 0, false
	}
	dirLen := int64(binary.LittleEndian.Uint32(tail[:4]))
	dirPos := size - 8 - dirLen
	if dirLen < 12 || dirLen > 1<<16 || dirPos < 0 {
		return 0, 0, false
	}
	dir, err := readAt(r, dirPos, int(dirLen))
	if err != nil || string(dir[:4]) != "SEFH" {
		return 0, 0, false
	}
	count := int(binary.LittleEndian.Uint32(dir[8:12]))
	for i := range count {
		e := 12 + i*12
		if e+12 > len(dir) {
			break
		}
		noff := int64(binary.LittleEndian.Uint32(dir[e+4:]))
		n := int64(binary.LittleEndian.Uint32(dir[e+8:]))
		pos := dirPos - noff
		if pos < 0 || n < 8 || pos+n > dirPos {
			continue
		}
		h, err := readAt(r, pos, 8)
		if err != nil {
			continue
		}
		nameLen := int64(binary.LittleEndian.Uint32(h[4:8]))
		if nameLen <= 0 || nameLen > 256 || 8+nameLen > n {
			continue
		}
		name, err := readAt(r, pos+8, int(nameLen))
		if err != nil || string(name) != "MotionPhoto_Data" {
			continue
		}
		off := pos + 8 + nameLen
		if isMP4At(r, off, size) {
			return off, n - 8 - nameLen, true
		}
	}
	return 0, 0, false
}
