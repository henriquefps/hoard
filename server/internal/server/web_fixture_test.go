package server

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/henriquefps/hoard/server/internal/media/mediatest"
)

// Synthetic libraries for the web gallery's end-to-end tests and measurements (ADR 0018 "Tests"). Opt-in:
//
//	HOARD_WEB_FIXTURE=/abs/dir go test -run TestWriteWebFixture ./internal/server/     # ~40 items: photos, pairs,
//	    videos (ffmpeg testsrc2), a motion photo, an XMP sidecar, in nested folders (server/web/e2e uses it)
//	HOARD_WEB_BENCH=/abs/dir HOARD_WEB_BENCH_N=20000 go test -run TestWriteWebBench ./internal/server/
//	    # N small photos over six years, for first paint and scroll measurements
//
// Every photo is a real, decodable JPEG (drawn here) carrying synthetic EXIF from mediatest. Nothing is private.

// spliceEXIF puts the EXIF APP1 segment of a mediatest JPEG for shot right after the SOI of a real JPEG.
func spliceEXIF(real []byte, shot mediatest.Shot, xmp string) []byte {
	fake := mediatest.JPEG(shot, xmp)
	// fake = SOI, APP1 EXIF, [APP1 XMP], SOF, SOS, ...: copy every APP1 segment.
	var app []byte
	for i := 2; i+4 <= len(fake) && fake[i] == 0xFF && fake[i+1] == 0xE1; {
		n := int(fake[i+2])<<8 | int(fake[i+3])
		app = append(app, fake[i:i+2+n]...)
		i += 2 + n
	}
	out := append([]byte{0xFF, 0xD8}, app...)
	return append(out, real[2:]...)
}

// drawPhoto draws a w×h picture: a diagonal gradient in a hue, a sun and horizon bands, so previews and zooms show
// structure (fine stripes at the centre for checking 100 %).
func drawPhoto(w, h int, hue float64, seed int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	hsv := func(h, s, v float64) color.RGBA {
		h = math.Mod(h, 360) / 60
		c := v * s
		x := c * (1 - math.Abs(math.Mod(h, 2)-1))
		var r, g, b float64
		switch int(h) {
		case 0:
			r, g = c, x
		case 1:
			r, g = x, c
		case 2:
			g, b = c, x
		case 3:
			g, b = x, c
		case 4:
			r, b = x, c
		default:
			r, b = c, x
		}
		m := v - c
		return color.RGBA{uint8((r + m) * 255), uint8((g + m) * 255), uint8((b + m) * 255), 255}
	}
	sunX, sunY, sunR := float64(w)*(0.2+0.6*float64(seed%7)/7), float64(h)*0.35, float64(h)*0.12
	horizon := float64(h) * (0.55 + 0.1*float64(seed%3)/3)
	for y := range h {
		for x := range w {
			fx, fy := float64(x)/float64(w), float64(y)/float64(h)
			var c color.RGBA
			switch {
			case math.Hypot(float64(x)-sunX, float64(y)-sunY) < sunR:
				c = hsv(hue+40, 0.35, 1)
			case float64(y) > horizon:
				c = hsv(hue+180, 0.5, 0.25+0.35*(1-fy)+0.05*math.Sin(fx*40))
			default:
				c = hsv(hue+fx*30, 0.55, 0.55+0.4*fy)
			}
			if abs(x-w/2) < w/20 && abs(y-h/2) < h/20 && (x/2+y/2)%2 == 0 { // fine detail for 100 %
				c = color.RGBA{20, 20, 20, 255}
			}
			img.SetRGBA(x, y, c)
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 82}); err != nil {
		panic(err)
	}
	return buf.Bytes()
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

type fixtureWriter struct {
	t   *testing.T
	nas string
}

func (f fixtureWriter) write(rel string, b []byte) {
	f.t.Helper()
	p := filepath.Join(f.nas, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		f.t.Fatal(err)
	}
	if err := os.WriteFile(p, b, 0o644); err != nil {
		f.t.Fatal(err)
	}
}

// ffmpegClip makes an H.264 test pattern clip (testsrc2) with a creation time and optional rotation.
func ffmpegClip(t *testing.T, out string, seconds int, size, created string, rotate int) {
	t.Helper()
	args := []string{"-hide_banner", "-loglevel", "error", "-y"}
	if rotate != 0 {
		args = append(args, "-display_rotation", strconv.Itoa(rotate))
	}
	args = append(args, "-f", "lavfi", "-i", fmt.Sprintf("testsrc2=size=%s:rate=30:duration=%d", size, seconds),
		"-f", "lavfi", "-i", fmt.Sprintf("sine=frequency=440:duration=%d", seconds), "-shortest",
		"-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "64k",
		"-metadata", "creation_time="+created)
	args = append(args, out)
	if b, err := exec.Command("ffmpeg", args...).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v %s", err, b)
	}
}

// rawWithPreview is a synthetic RAW (mediatest.RAW's tags) with a JPEG embedded through JPEGInterchangeFormat in
// IFD0. LibRaw cannot decode it; the server's RAW readers find the embedded JPEG by content (ADR 0015 "Sources").
func rawWithPreview(s mediatest.Shot, jpg []byte) []byte {
	build := func(off uint32) []byte {
		d := s.IFD0()
		d.Tags = append(d.Tags, mediatest.Tag{ID: 0x0100, Val: []uint32{160}}, mediatest.Tag{ID: 0x0101, Val: []uint32{120}},
			mediatest.Tag{ID: 0x0201, Val: []uint32{off}}, mediatest.Tag{ID: 0x0202, Val: []uint32{uint32(len(jpg))}})
		d.Subs = []*mediatest.IFD{{Tags: []mediatest.Tag{{ID: 0x0100, Val: []uint32{s.Width}}, {ID: 0x0101, Val: []uint32{s.Height}}}}}
		ex := *d.Exif
		ex.Tags = nil
		for _, t := range d.Exif.Tags {
			if t.ID != 0xA002 && t.ID != 0xA003 {
				ex.Tags = append(ex.Tags, t)
			}
		}
		d.Exif = &ex
		return mediatest.TIFF(d, binary.LittleEndian, 42)
	}
	head := build(0)
	head = build(uint32(len(head)))
	return append(head, jpg...)
}

func TestWriteWebFixture(t *testing.T) {
	dir := os.Getenv("HOARD_WEB_FIXTURE")
	if dir == "" {
		t.Skip("HOARD_WEB_FIXTURE not set")
	}
	f := fixtureWriter{t, filepath.Join(dir, "mnt", "nas")}
	cams := []struct{ make, model, lens string }{
		{"SONY", "ILCE-7M4", "FE 35mm F1.8"}, {"FUJIFILM", "X-T5", "XF23mmF1.4 R LM WR"}, {"Google", "Pixel 8", ""}, {"Canon", "Canon EOS R6", "RF24-105mm F4 L IS USM"},
	}
	n := 0
	shot := func(day string, hour, minute, cam int, w, h uint32) mediatest.Shot {
		n++
		c := cams[cam]
		return mediatest.Shot{Make: c.make, Model: c.model, Lens: c.lens, DateTime: fmt.Sprintf("%s %02d:%02d:%02d", day, hour, minute, n%60),
			SubSec: "250", Offset: "-03:00", Width: w, Height: h, Unique: uint64(n), Lat: -23.55 + float64(n)/1000, Lon: -46.63,
			ExposureTime: [2]uint32{1, uint32(60 * (1 + n%8))}, FNumber: [2]uint32{uint32(18 + n%40), 10}, ISO: uint16(100 * (1 + n%16)),
			FocalLength: [2]uint32{uint32(24 + n%60), 1}, Focal35: uint16(24 + n%60)}
	}
	// A trip in May 2024: pairs and JPEGs from two cameras; one 6000×4000 photo for 100 % zoom.
	for i := range 12 {
		s := shot("2024:05:03", 9+i/3, i*4, i%2, 2400, 1600)
		name := fmt.Sprintf("2024/2024-05-03 Trip/DSC%05d", 1000+i)
		if i == 0 {
			s.Width, s.Height = 6000, 4000
			f.write(name+".JPG", spliceEXIF(drawPhoto(6000, 4000, 20, i), s, ""))
		} else {
			f.write(name+".JPG", spliceEXIF(drawPhoto(2400, 1600, float64(i*25), i), s, ""))
		}
		if i%3 == 0 { // a RAW next to it: a RAW+JPG pair
			r := s
			r.Unique += 5000
			f.write(name+".ARW", mediatest.RAW(r))
		}
	}
	// A lone RAW whose camera JPEG is embedded at full size (as Canon and Nikon store theirs): the viewer's 100 % zoom
	// shows it through /web/items/{id}/full (ADR 0018 "Full resolution").
	rs := shot("2023:11:20", 7, 30, 0, 6000, 4000)
	f.write("2023/2023-11-20 Raw/DSC03000.ARW", rawWithPreview(rs, drawPhoto(6000, 4000, 140, 7)))
	// A sidecar written by another tool: 5 stars, picked (imported by the server, ADR 0016).
	f.write("2024/2024-05-03 Trip/DSC01001.xmp", []byte(`<?xpacket begin=""?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">`+
		`<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:digiKam="http://www.digikam.org/ns/1.0/" xmp:Rating="5" digiKam:PickLabel="3"/>`+
		`</rdf:RDF></x:xmpmeta><?xpacket end="w"?>`))
	// Portrait shots on another day, a phone in December 2023, a Canon in 2025.
	for i := range 6 {
		s := shot("2024:05:04", 17, i*7, 0, 1600, 2400)
		f.write(fmt.Sprintf("2024/2024-05-04/DSC%05d.JPG", 2000+i), spliceEXIF(drawPhoto(1600, 2400, 200+float64(i*12), i), s, ""))
	}
	for i := range 8 {
		s := shot("2023:12:24", 19+i/4, i*6, 2, 2000, 1500)
		f.write(fmt.Sprintf("Phone/2023-12/PXL_20231224_%04d.jpg", i), spliceEXIF(drawPhoto(2000, 1500, 320+float64(i*9), i), s, ""))
	}
	for i := range 6 {
		s := shot("2025:01:15", 8, i*3, 3, 2400, 1600)
		f.write(fmt.Sprintf("2025/Snow/IMG_%04d.JPG", 300+i), spliceEXIF(drawPhoto(2400, 1600, 190+float64(i*5), i), s, ""))
	}
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Log("ffmpeg not found: no videos or motion photo in the fixture")
		return
	}
	tmp := t.TempDir()
	// Videos: landscape and a rotated phone clip.
	for i, v := range []struct {
		name, size, created string
		rotate              int
	}{{"Phone/2023-12/PXL_20231224_VID1.mp4", "1280x720", "2023-12-24T22:10:00Z", 0},
		{"2024/2024-05-03 Trip/C0001.MP4", "1920x1080", "2024-05-03T15:30:00Z", 0},
		{"2025/Snow/MVI_0310.MP4", "1280x720", "2025-01-15T11:20:00Z", -90}} {
		out := filepath.Join(tmp, fmt.Sprintf("v%d.mp4", i))
		ffmpegClip(t, out, 4+i, v.size, v.created, v.rotate)
		b, err := os.ReadFile(out)
		if err != nil {
			t.Fatal(err)
		}
		f.write(v.name, b)
	}
	// A motion photo: a JPEG with Google's XMP and the MP4 appended.
	mp4 := filepath.Join(tmp, "motion.mp4")
	ffmpegClip(t, mp4, 2, "960x720", "2023-12-24T21:00:00Z", 0)
	clip, err := os.ReadFile(mp4)
	if err != nil {
		t.Fatal(err)
	}
	s := shot("2023:12:24", 18, 0, 2, 1600, 1200)
	still := spliceEXIF(drawPhoto(1600, 1200, 30, 3), s, mediatest.MotionXMP(len(clip)))
	f.write("Phone/2023-12/PXL_20231224_MOTION.MP.jpg", append(still, clip...))
}

func TestWriteWebBench(t *testing.T) {
	dir := os.Getenv("HOARD_WEB_BENCH")
	if dir == "" {
		t.Skip("HOARD_WEB_BENCH not set")
	}
	n, _ := strconv.Atoi(os.Getenv("HOARD_WEB_BENCH_N"))
	if n <= 0 {
		n = 20000
	}
	f := fixtureWriter{t, filepath.Join(dir, "mnt", "nas")}
	tiles := make([][]byte, 24)
	for i := range tiles {
		tiles[i] = drawPhoto(240, 160, float64(i*15), i)
	}
	cams := [][2]string{{"SONY", "ILCE-7M4"}, {"FUJIFILM", "X-T5"}, {"Google", "Pixel 8"}, {"Canon", "Canon EOS R6"}, {"Apple", "iPhone 15 Pro"}}
	// Six years, a few shooting days a week, bursts of shots on each day.
	for i := range n {
		day := i / 12
		y, m, d := 2020+day/300, 1+(day/25)%12, 1+day%25
		c := cams[(day/3)%len(cams)]
		s := mediatest.Shot{Make: c[0], Model: c[1], DateTime: fmt.Sprintf("%04d:%02d:%02d %02d:%02d:%02d", y, m, d, 9+(i%12), (i*7)%60, i%60),
			Offset: "+00:00", Width: 240, Height: 160, Unique: uint64(i + 1)}
		f.write(fmt.Sprintf("%04d/%02d/IMG_%06d.jpg", y, m, i), spliceEXIF(tiles[i%len(tiles)], s, ""))
	}
}
