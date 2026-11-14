package media

import (
	"encoding/binary"
	"errors"
	"io"
)

const (
	maxSegments = 1000
	maxXMPBytes = 1 << 20
)

var (
	exifHeader = []byte("Exif\x00\x00")
	xmpHeader  = []byte("http://ns.adobe.com/xap/1.0/\x00")
)

// readJPEG walks the markers of the JPEG at base up to the first scan: EXIF (APP1), XMP (APP1, returned) and the frame
// size (SOFn).
func readJPEG(r io.ReaderAt, base, size int64, f *exifFields) ([][]byte, error) {
	var xmp [][]byte
	off := base + 2
	var hdr [4]byte
	for range maxSegments {
		if off+4 > size {
			return xmp, nil
		}
		if _, err := r.ReadAt(hdr[:], off); err != nil {
			return xmp, err
		}
		if hdr[0] != 0xFF {
			return xmp, errors.New("jpeg: marker expected")
		}
		marker := hdr[1]
		switch {
		case marker == 0xFF: // fill byte
			off++
			continue
		case marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7):
			off += 2
			continue
		case marker == 0xDA || marker == 0xD9: // start of scan, end of image
			return xmp, nil
		}
		segLen := int64(binary.BigEndian.Uint16(hdr[2:4]))
		if segLen < 2 {
			return xmp, errors.New("jpeg: bad segment length")
		}
		payload := off + 4
		n := segLen - 2
		switch {
		case marker == 0xE1:
			head := make([]byte, min(n, int64(len(xmpHeader))))
			if _, err := r.ReadAt(head, payload); err == nil {
				switch {
				case len(head) >= len(exifHeader) && string(head[:len(exifHeader)]) == string(exifHeader):
					_ = readTIFFStream(io.NewSectionReader(r, payload+6, n-6), 0, f)
				case len(head) == len(xmpHeader) && string(head) == string(xmpHeader) && n-int64(len(xmpHeader)) <= maxXMPBytes:
					b := make([]byte, n-int64(len(xmpHeader)))
					if _, err := r.ReadAt(b, payload+int64(len(xmpHeader))); err == nil {
						xmp = append(xmp, b)
					}
				}
			}
		case marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC:
			var sof [5]byte
			if _, err := r.ReadAt(sof[:], payload); err == nil {
				f.size(uint32(binary.BigEndian.Uint16(sof[3:5])), uint32(binary.BigEndian.Uint16(sof[1:3])))
			}
		}
		off = payload + n
	}
	return xmp, nil
}

// readRAF reads a Fujifilm RAF: its EXIF lives in the embedded JPEG (offset and length at bytes 84 and 88).
func readRAF(r io.ReaderAt, size int64, f *exifFields) error {
	var b [8]byte
	if _, err := r.ReadAt(b[:], 84); err != nil {
		return err
	}
	off, n := int64(binary.BigEndian.Uint32(b[:4])), int64(binary.BigEndian.Uint32(b[4:]))
	if off <= 0 || n <= 0 || off+n > size {
		return errors.New("raf: bad JPEG offset")
	}
	_, err := readJPEG(io.NewSectionReader(r, off, n), 0, n, f)
	return err
}
