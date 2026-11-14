package edits

import (
	"bytes"
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"strings"
	"testing"
)

// Synthetic images only: drawn here, or the two tiny WebPs in testdata (libvips' gaussnoise, encoded lossy and
// lossless with metadata off).

func drawJPEG(t *testing.T, w, h int, gray bool) []byte {
	t.Helper()
	var img image.Image
	if gray {
		g := image.NewGray(image.Rect(0, 0, w, h))
		for i := range g.Pix {
			g.Pix[i] = uint8(i)
		}
		img = g
	} else {
		c := image.NewRGBA(image.Rect(0, 0, w, h))
		for y := range h {
			for x := range w {
				c.Set(x, y, color.RGBA{uint8(x), uint8(y), 128, 255})
			}
		}
		img = c
	}
	var b bytes.Buffer
	if err := jpeg.Encode(&b, img, &jpeg.Options{Quality: 80}); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// segment makes a JPEG marker segment.
func segment(marker byte, payload []byte) []byte {
	out := []byte{0xFF, marker, 0, 0}
	binary.BigEndian.PutUint16(out[2:], uint16(len(payload)+2))
	return append(out, payload...)
}

// insertAfterSOI puts segments right after the SOI.
func insertAfterSOI(j []byte, segs ...[]byte) []byte {
	out := append([]byte{}, j[:2]...)
	for _, s := range segs {
		out = append(out, s...)
	}
	return append(out, j[2:]...)
}

// iccProfile builds a minimal matrix profile with the given primaries (rXYZ, gXYZ, bXYZ) and description.
func iccProfile(space string, prim [3][3]float64, desc string) []byte {
	type tag struct {
		sig  string
		data []byte
	}
	xyz := func(v [3]float64) []byte {
		b := []byte("XYZ \x00\x00\x00\x00")
		for _, c := range v {
			b = binary.BigEndian.AppendUint32(b, uint32(int32(c*65536)))
		}
		return b
	}
	var tags []tag
	if space == "RGB " && prim != [3][3]float64{} {
		tags = append(tags, tag{"rXYZ", xyz(prim[0])}, tag{"gXYZ", xyz(prim[1])}, tag{"bXYZ", xyz(prim[2])})
	}
	tags = append(tags, tag{"desc", append([]byte("desc\x00\x00\x00\x00"), []byte(desc)...)})
	head := make([]byte, 128)
	copy(head[12:], "mntr")
	copy(head[16:], space)
	copy(head[20:], "XYZ ")
	copy(head[36:], "acsp")
	off := 128 + 4 + 12*len(tags)
	table := binary.BigEndian.AppendUint32(nil, uint32(len(tags)))
	var data []byte
	for _, tg := range tags {
		table = append(table, tg.sig...)
		table = binary.BigEndian.AppendUint32(table, uint32(off+len(data)))
		table = binary.BigEndian.AppendUint32(table, uint32(len(tg.data)))
		data = append(data, tg.data...)
		for len(data)%4 != 0 {
			data = append(data, 0)
		}
	}
	p := append(append(head, table...), data...)
	binary.BigEndian.PutUint32(p[0:], uint32(len(p)))
	return p
}

func iccSegment(p []byte) []byte {
	return segment(0xE2, append([]byte("ICC_PROFILE\x00\x01\x01"), p...))
}

var displayP3 = [3][3]float64{{0.5151, 0.2412, -0.0011}, {0.2920, 0.6922, 0.0419}, {0.1571, 0.0666, 0.7841}}

func reason(err error) string {
	var inv *Invalid
	if errors.As(err, &inv) {
		return inv.Reason
	}
	if err != nil {
		return "other: " + err.Error()
	}
	return ""
}

func TestJPEGIsCleanedWithoutReencoding(t *testing.T) {
	plain := drawJPEG(t, 300, 200, false)
	exif := segment(0xE1, append([]byte("Exif\x00\x00"), bytes.Repeat([]byte{1}, 40)...))
	xmp := segment(0xE1, []byte("http://ns.adobe.com/xap/1.0/\x00<x:xmpmeta GPS secret/>"))
	com := segment(0xFE, []byte("a comment"))
	srgb := iccSegment(iccProfile("RGB ", srgbD50, "sRGB IEC61966-2.1"))
	in := insertAfterSOI(plain, exif, xmp, srgb, com)
	img, err := Check(in, "image/jpeg")
	if err != nil {
		t.Fatal(err)
	}
	if img.Format != FormatJPEG || img.Width != 300 || img.Height != 200 || len(img.SHA256) != 64 {
		t.Fatalf("%+v", img)
	}
	// Exactly the encoder's own file: metadata gone, pixels untouched (no re-encode).
	if !bytes.Equal(img.Data, plain) {
		t.Fatalf("cleaned %d bytes, plain %d", len(img.Data), len(plain))
	}
	for _, s := range []string{"Exif", "xmpmeta", "ICC_PROFILE", "a comment"} {
		if bytes.Contains(img.Data, []byte(s)) {
			t.Fatalf("%s kept", s)
		}
	}
	// The content type may carry parameters.
	if _, err := Check(in, "image/JPEG; charset=binary"); err != nil {
		t.Fatal(err)
	}
	// Grey is a colour space too (a black-and-white edit).
	if _, err := Check(drawJPEG(t, 64, 64, true), "image/jpeg"); err != nil {
		t.Fatal(err)
	}
	// An sRGB profile without matrix tags is recognised by its name; a grey profile is fine.
	if _, err := Check(insertAfterSOI(plain, iccSegment(iccProfile("RGB ", [3][3]float64{}, "sRGB built-in"))), "image/jpeg"); err != nil {
		t.Fatal(err)
	}
	if _, err := Check(insertAfterSOI(plain, iccSegment(iccProfile("GRAY", [3][3]float64{}, "Gray Gamma 2.2"))), "image/jpeg"); err != nil {
		t.Fatal(err)
	}
}

func TestRefusals(t *testing.T) {
	plain := drawJPEG(t, 300, 200, false)
	cmyk := append([]byte{}, plain...)
	// The frame header's component count (SOF0: FF C0, length, precision, height, width, components).
	i := bytes.Index(cmyk, []byte{0xFF, 0xC0})
	cmyk[i+9] = 4
	webp, err := os.ReadFile("testdata/edit.webp")
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name, ct string
		body     []byte
		reason   string
	}{
		{"png type", "image/png", plain, "format"},
		{"jpeg body sent as webp", "image/webp", plain, "format"},
		{"webp body sent as jpeg", "image/jpeg", webp, "format"},
		{"not an image", "image/jpeg", []byte("hello"), "format"},
		{"truncated", "image/jpeg", plain[:len(plain)/2], "decode"},
		{"header only", "image/jpeg", plain[:40], "decode"},
		{"too large", "image/jpeg", drawJPEG(t, 2561, 100, false), "dimensions"},
		{"too small", "image/jpeg", drawJPEG(t, 15, 15, false), "dimensions"},
		{"display p3", "image/jpeg", insertAfterSOI(plain, iccSegment(iccProfile("RGB ", displayP3, "Display P3"))), "colour_space"},
		{"adobe rgb by name", "image/jpeg", insertAfterSOI(plain, iccSegment(iccProfile("RGB ", [3][3]float64{}, "Adobe RGB (1998)"))), "colour_space"},
		{"cmyk profile", "image/jpeg", insertAfterSOI(plain, iccSegment(iccProfile("CMYK", [3][3]float64{}, "U.S. Web Coated"))), "colour_space"},
		{"cmyk frame", "image/jpeg", cmyk, "colour_space"},
	}
	for _, c := range cases {
		if _, err := Check(c.body, c.ct); reason(err) != c.reason {
			t.Errorf("%s: %v, want %s", c.name, err, c.reason)
		}
	}
	// The long edge may be exactly 2560.
	if img, err := Check(drawJPEG(t, 2560, 1440, false), "image/jpeg"); err != nil || img.Width != 2560 {
		t.Fatal(img.Width, err)
	}
}

// riffChunk makes a WebP chunk (padded to an even size).
func riffChunk(id string, data []byte) []byte {
	out := append([]byte(id), binary.LittleEndian.AppendUint32(nil, uint32(len(data)))...)
	out = append(out, data...)
	if len(data)%2 == 1 {
		out = append(out, 0)
	}
	return out
}

func riff(chunks ...[]byte) []byte {
	body := []byte("WEBP")
	for _, c := range chunks {
		body = append(body, c...)
	}
	return append(append([]byte("RIFF"), binary.LittleEndian.AppendUint32(nil, uint32(len(body)))...), body...)
}

func vp8x(flags byte, w, h int) []byte {
	d := make([]byte, 10)
	d[0] = flags
	put24 := func(b []byte, v int) { b[0], b[1], b[2] = byte(v), byte(v>>8), byte(v>>16) }
	put24(d[4:], w-1)
	put24(d[7:], h-1)
	return riffChunk("VP8X", d)
}

func TestWebP(t *testing.T) {
	for _, name := range []string{"edit.webp", "lossless.webp"} {
		simple, err := os.ReadFile("testdata/" + name)
		if err != nil {
			t.Fatal(err)
		}
		img, err := Check(simple, "image/webp")
		if err != nil {
			t.Fatal(name, err)
		}
		if img.Format != FormatWebP || img.Width != 96 || img.Height != 64 || !bytes.Equal(img.Data, simple) {
			t.Fatalf("%s: %+v", name, img)
		}
		// The extended format with ICC, EXIF and XMP: the image chunk is kept, the metadata goes.
		imageChunk := simple[12:]
		ext := riff(vp8x(0x20|0x08|0x04, 96, 64), riffChunk("ICCP", iccProfile("RGB ", srgbD50, "sRGB")), imageChunk,
			riffChunk("EXIF", []byte("II*\x00secret")), riffChunk("XMP ", []byte("<x:xmpmeta/>")))
		img, err = Check(ext, "image/webp")
		if err != nil {
			t.Fatal(name, err)
		}
		if !bytes.Equal(img.Data, simple) {
			t.Fatalf("%s: extended not reduced to the simple file (%d vs %d bytes)", name, len(img.Data), len(simple))
		}
		// A Display P3 profile is refused; an animation is refused.
		p3 := riff(vp8x(0x20, 96, 64), riffChunk("ICCP", iccProfile("RGB ", displayP3, "Display P3")), imageChunk)
		if _, err := Check(p3, "image/webp"); reason(err) != "colour_space" {
			t.Fatalf("%s p3: %v", name, err)
		}
		anim := riff(vp8x(0x02, 96, 64), riffChunk("ANIM", make([]byte, 6)), riffChunk("ANMF", make([]byte, 16)))
		if _, err := Check(anim, "image/webp"); reason(err) != "animated" {
			t.Fatalf("%s anim: %v", name, err)
		}
		if _, err := Check(simple[:len(simple)-30], "image/webp"); reason(err) != "decode" {
			t.Fatalf("%s truncated: %v", name, err)
		}
		if _, err := Check(riff(riffChunk("EXIF", []byte("x"))), "image/webp"); reason(err) != "decode" {
			t.Fatalf("%s no image: %v", name, err)
		}
	}
}

func TestEveryTruncationFailsCleanly(t *testing.T) {
	plain := insertAfterSOI(drawJPEG(t, 40, 30, false), iccSegment(iccProfile("RGB ", srgbD50, "sRGB")))
	webp, _ := os.ReadFile("testdata/edit.webp")
	for _, c := range []struct {
		ct   string
		body []byte
	}{{"image/jpeg", plain}, {"image/webp", webp}} {
		for n := range len(c.body) - 1 {
			if _, err := Check(c.body[:n], c.ct); err == nil {
				t.Fatalf("%s truncated at %d accepted", c.ct, n)
			} else if r := reason(err); strings.HasPrefix(r, "other") {
				t.Fatalf("%s at %d: %v", c.ct, n, err)
			}
		}
	}
}
