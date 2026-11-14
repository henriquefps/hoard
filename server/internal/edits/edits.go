// Package edits checks and cleans the edited previews Reveal uploads (ADR 0020): one sRGB JPEG or WebP of an edited
// photo, long edge at most 2560 px. An upload is decoded whole (a truncated or corrupt file is refused), its size and
// colour space are checked, and its metadata (EXIF, XMP, ICC, comments) is removed without re-encoding the pixels.
package edits

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"image/jpeg"
	"math"
	"strings"

	"golang.org/x/image/webp"
)

// Limits of an upload (ADR 0020 "Upload"); GET /v1/server publishes them in "edits".
const (
	MaxBytes = 8 << 20 // request body
	MaxEdge  = 2560    // long edge, px (the screen preview's, ADR 0015)
	MinEdge  = 16      // long edge, px
)

// Formats.
const (
	FormatJPEG = "jpeg"
	FormatWebP = "webp"
)

// Image is an accepted upload, cleaned: what the server stores and derives its previews from.
type Image struct {
	Format string // jpeg | webp
	Width  int
	Height int
	Data   []byte // the file without metadata
	SHA256 string // of Data, lower-case hex
}

// ContentType is the MIME type of a format.
func ContentType(format string) string {
	if format == FormatWebP {
		return "image/webp"
	}
	return "image/jpeg"
}

// Ext is the file extension of a format (without the dot).
func Ext(format string) string {
	if format == FormatWebP {
		return "webp"
	}
	return "jpg"
}

// Invalid is why an upload was refused: Reason is stable (the API's details.reason), Message is for developers.
type Invalid struct {
	Reason  string // format | decode | dimensions | colour_space | animated
	Message string
}

func (e *Invalid) Error() string { return e.Reason + ": " + e.Message }

func invalid(reason, format string, a ...any) error {
	return &Invalid{Reason: reason, Message: fmt.Sprintf(format, a...)}
}

// Check validates an upload declared as contentType (image/jpeg or image/webp) and returns it cleaned.
func Check(body []byte, contentType string) (Image, error) {
	ct := strings.ToLower(strings.TrimSpace(strings.SplitN(contentType, ";", 2)[0]))
	var format string
	switch {
	case ct == "image/jpeg" && len(body) > 3 && body[0] == 0xFF && body[1] == 0xD8 && body[2] == 0xFF:
		format = FormatJPEG
	case ct == "image/webp" && len(body) > 12 && string(body[0:4]) == "RIFF" && string(body[8:12]) == "WEBP":
		format = FormatWebP
	case ct != "image/jpeg" && ct != "image/webp":
		return Image{}, invalid("format", "send image/jpeg or image/webp")
	default:
		return Image{}, invalid("format", "the body is not a %s file", ct)
	}
	var (
		clean []byte
		icc   []byte
		err   error
	)
	if format == FormatJPEG {
		clean, icc, err = stripJPEG(body)
	} else {
		clean, icc, err = stripWebP(body)
	}
	if err != nil {
		return Image{}, err
	}
	// Size before decoding: a decode is bounded by MaxEdge².
	var w, h int
	if format == FormatJPEG {
		cfg, err := jpeg.DecodeConfig(bytes.NewReader(clean))
		if err != nil {
			return Image{}, invalid("decode", "not a decodable JPEG: %v", err)
		}
		w, h = cfg.Width, cfg.Height
	} else {
		cfg, err := webp.DecodeConfig(bytes.NewReader(clean))
		if err != nil {
			return Image{}, invalid("decode", "not a decodable WebP: %v", err)
		}
		w, h = cfg.Width, cfg.Height
	}
	if long := max(w, h); long > MaxEdge || long < MinEdge || min(w, h) < 1 {
		return Image{}, invalid("dimensions", "%d×%d: the long edge must be %d to %d px", w, h, MinEdge, MaxEdge)
	}
	if format == FormatJPEG {
		if _, err := jpeg.Decode(bytes.NewReader(clean)); err != nil {
			return Image{}, invalid("decode", "the JPEG does not decode: %v", err)
		}
	} else if _, err := webp.Decode(bytes.NewReader(clean)); err != nil {
		return Image{}, invalid("decode", "the WebP does not decode: %v", err)
	}
	if icc != nil {
		if err := checkICC(icc); err != nil {
			return Image{}, err
		}
	}
	sum := sha256.Sum256(clean)
	return Image{Format: format, Width: w, Height: h, Data: clean, SHA256: hex.EncodeToString(sum[:])}, nil
}

// stripJPEG keeps the segments a decoder needs (quantisation and Huffman tables, frame and scan headers, restart
// interval, JFIF APP0 and Adobe APP14, which tells YCbCr from RGB) and the entropy-coded data, and drops every other
// application segment (EXIF, XMP, ICC, maker data) and comments. It returns the ICC profile it found (reassembled).
func stripJPEG(b []byte) ([]byte, []byte, error) {
	out := make([]byte, 0, len(b))
	out = append(out, 0xFF, 0xD8)
	var iccChunks [][]byte
	i := 2
	for {
		// Markers may be preceded by fill bytes (0xFF).
		for i < len(b) && b[i] == 0xFF && i+1 < len(b) && b[i+1] == 0xFF {
			i++
		}
		if i+4 > len(b) || b[i] != 0xFF {
			return nil, nil, invalid("decode", "truncated or malformed JPEG header")
		}
		m := b[i+1]
		n := int(binary.BigEndian.Uint16(b[i+2:]))
		if n < 2 || i+2+n > len(b) {
			return nil, nil, invalid("decode", "truncated JPEG segment")
		}
		seg := b[i : i+2+n]
		payload := b[i+4 : i+2+n]
		switch {
		case m == 0xDA: // start of scan: the rest is image data
			out = append(out, b[i:]...)
			return out, joinICC(iccChunks), nil
		case m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC: // start of frame
			if len(payload) >= 6 {
				if c := payload[5]; c != 1 && c != 3 {
					return nil, nil, invalid("colour_space", "a JPEG with %d components (CMYK?): send sRGB", c)
				}
			}
			out = append(out, seg...)
		case m == 0xE2 && len(payload) > 14 && string(payload[:12]) == "ICC_PROFILE\x00":
			iccChunks = append(iccChunks, payload[12:])
		case m == 0xE0 || m == 0xEE: // JFIF, Adobe
			out = append(out, seg...)
		case m >= 0xE1 && m <= 0xEF, m == 0xFE: // other APPn, COM
		default:
			out = append(out, seg...)
		}
		i += 2 + n
	}
}

// joinICC reassembles an ICC profile split over APP2 segments (each: sequence number, count, data).
func joinICC(chunks [][]byte) []byte {
	if len(chunks) == 0 {
		return nil
	}
	parts := make([][]byte, 256)
	for _, c := range chunks {
		parts[c[0]] = c[2:]
	}
	var out []byte
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

// stripWebP rewrites the RIFF container with the image chunks only (VP8, VP8L, and ALPH with its VP8X header);
// ICCP, EXIF, XMP and unknown chunks go. Animated WebPs are refused.
func stripWebP(b []byte) ([]byte, []byte, error) {
	size := int(binary.LittleEndian.Uint32(b[4:8]))
	if size+8 > len(b) || size < 4 {
		return nil, nil, invalid("decode", "truncated WebP")
	}
	b = b[:size+8]
	var vp8x, alph, img, icc []byte
	for i := 12; i < len(b); {
		if i+8 > len(b) {
			return nil, nil, invalid("decode", "truncated WebP chunk")
		}
		id := string(b[i : i+4])
		n := int(binary.LittleEndian.Uint32(b[i+4 : i+8]))
		end := i + 8 + n
		if n < 0 || end > len(b) {
			return nil, nil, invalid("decode", "truncated WebP chunk")
		}
		chunk := b[i:end]
		switch id {
		case "VP8X":
			if n < 10 {
				return nil, nil, invalid("decode", "malformed VP8X")
			}
			if b[i+8]&0x02 != 0 {
				return nil, nil, invalid("animated", "animated WebPs are not edits")
			}
			vp8x = chunk
		case "ANIM", "ANMF":
			return nil, nil, invalid("animated", "animated WebPs are not edits")
		case "ALPH":
			alph = chunk
		case "VP8 ", "VP8L":
			if img != nil {
				return nil, nil, invalid("decode", "two image chunks")
			}
			img = chunk
		case "ICCP":
			icc = b[i+8 : end]
		}
		i = end + n%2 // chunks are padded to an even size
	}
	if img == nil {
		return nil, nil, invalid("decode", "no image data in the WebP")
	}
	body := []byte("WEBP")
	pad := func(c []byte) []byte {
		body = append(body, c...)
		if len(c)%2 == 1 {
			body = append(body, 0)
		}
		return body
	}
	if alph != nil && vp8x != nil && string(img[:4]) == "VP8 " {
		x := append([]byte(nil), vp8x...)
		x[8] = 0x10 // alpha only: no ICC, EXIF, XMP, animation
		pad(x)
		pad(alph)
	}
	pad(img)
	out := make([]byte, 0, len(body)+8)
	out = append(out, "RIFF"...)
	out = binary.LittleEndian.AppendUint32(out, uint32(len(body))) //nolint:gosec // ≤ MaxBytes
	out = append(out, body...)
	return out, icc, nil
}

// sRGB's primaries as an ICC profile stores them (rXYZ, gXYZ, bXYZ, adapted to D50).
var srgbD50 = [3][3]float64{{0.4361, 0.2225, 0.0139}, {0.3851, 0.7169, 0.0971}, {0.1431, 0.0606, 0.7141}}

// checkICC accepts an sRGB or a grey profile. A profile is sRGB when its primaries are sRGB's (within 0.005), or,
// without matrix primaries, when its description names sRGB. Anything else (Display P3, Adobe RGB, CMYK) is refused:
// the server would show it with the wrong colours.
func checkICC(p []byte) error {
	if len(p) < 132 {
		return invalid("colour_space", "malformed ICC profile")
	}
	space := string(p[16:20])
	if space == "GRAY" {
		return nil
	}
	if space != "RGB " {
		return invalid("colour_space", "an ICC profile for %q: send sRGB", strings.TrimSpace(space))
	}
	count := int(binary.BigEndian.Uint32(p[128:132]))
	tags := map[string][]byte{}
	for k := 0; k < count && k < 100 && 132+12*k+12 <= len(p); k++ {
		e := p[132+12*k:]
		off, n := int(binary.BigEndian.Uint32(e[4:8])), int(binary.BigEndian.Uint32(e[8:12]))
		if off >= 0 && n >= 0 && off+n <= len(p) {
			tags[string(e[:4])] = p[off : off+n]
		}
	}
	xyz := func(t []byte) ([3]float64, bool) {
		if len(t) < 20 || string(t[:4]) != "XYZ " {
			return [3]float64{}, false
		}
		var v [3]float64
		for c := range 3 {
			v[c] = float64(int32(binary.BigEndian.Uint32(t[8+4*c:]))) / 65536 //nolint:gosec // s15Fixed16
		}
		return v, true
	}
	var prim [3][3]float64
	matrix := true
	for c, sig := range []string{"rXYZ", "gXYZ", "bXYZ"} {
		v, ok := xyz(tags[sig])
		if !ok {
			matrix = false
			break
		}
		prim[c] = v
	}
	if matrix {
		for c := range 3 {
			for k := range 3 {
				if math.Abs(prim[c][k]-srgbD50[c][k]) > 0.005 {
					return invalid("colour_space", "the ICC profile is not sRGB (Display P3, Adobe RGB?): send sRGB")
				}
			}
		}
		return nil
	}
	if strings.Contains(strings.ToLower(string(tags["desc"])), "srgb") || strings.Contains(strings.ToLower(utf16ish(tags["desc"])), "srgb") {
		return nil
	}
	return invalid("colour_space", "the ICC profile is not sRGB: send sRGB")
}

// utf16ish drops the zero bytes of a big-endian UTF-16 description (an 'mluc' tag) so an ASCII name can be found.
func utf16ish(b []byte) string {
	return strings.ReplaceAll(string(b), "\x00", "")
}
