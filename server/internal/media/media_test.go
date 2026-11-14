package media

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/media/mediatest"
)

var shot = mediatest.Shot{
	Make: "SONY", Model: "ILCE-7M4", Lens: "FE 35mm F1.8", DateTime: "2024:05:01 10:00:00", SubSec: "123",
	Offset: "+09:00", Orientation: 6, Width: 7008, Height: 4672, Lat: 35.6586, Lon: 139.7454, Unique: 1,
}

func read(t *testing.T, b []byte) Meta {
	t.Helper()
	m, err := ReadImage(bytes.NewReader(b), int64(len(b)))
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func checkShot(t *testing.T, m Meta, format string, wantSize bool) {
	t.Helper()
	if m.Format != format || m.Make != "SONY" || m.Model != "ILCE-7M4" {
		t.Fatalf("%s: %+v", format, m)
	}
	if m.Capture == nil || m.Capture.Local != "2024-05-01T10:00:00.123" || m.Capture.Offset != "+09:00" || m.Capture.Source != "exif" ||
		m.Capture.UTC == nil || *m.Capture.UTC != time.Date(2024, 5, 1, 1, 0, 0, 123e6, time.UTC).UnixMilli() {
		t.Fatalf("%s capture: %+v", format, m.Capture)
	}
	if m.GPS == nil || m.GPS.Lat < 35.658 || m.GPS.Lat > 35.659 || m.GPS.Lon < 139.745 || m.GPS.Lon > 139.746 || m.GPS.Alt == nil || *m.GPS.Alt != 123.45 {
		t.Fatalf("%s gps: %+v", format, m.GPS)
	}
	if m.Orientation != 6 {
		t.Fatalf("%s orientation %d", format, m.Orientation)
	}
	if wantSize && (m.Width != 7008 || m.Height != 4672) {
		t.Fatalf("%s size %dx%d", format, m.Width, m.Height)
	}
}

func TestReadStills(t *testing.T) {
	checkShot(t, read(t, mediatest.JPEG(shot, "")), "jpeg", true)
	checkShot(t, read(t, mediatest.RAW(shot)), "tiff", true)
	checkShot(t, read(t, mediatest.HEIF(shot, "")), "heif", true)
	checkShot(t, read(t, mediatest.CR3(shot)), "cr3", true)
	checkShot(t, read(t, mediatest.RAF(shot)), "raf", true)
	if m := read(t, mediatest.RAW(shot)); m.Lens != "FE 35mm F1.8" || m.Motion != nil {
		t.Fatalf("lens %+v", m)
	}
}

func TestCaptureRules(t *testing.T) {
	s := shot
	s.Offset = ""
	s.Lat, s.Lon = 0, 0
	m := read(t, mediatest.JPEG(s, ""))
	if m.Capture.UTC != nil || m.Capture.Offset != "" || m.Capture.Local != "2024-05-01T10:00:00.123" {
		t.Fatalf("no offset: %+v", m.Capture)
	}
	if got, want := m.Capture.SortTime(), time.Date(2024, 5, 1, 10, 0, 0, 123e6, time.UTC).UnixMilli(); got != want {
		t.Fatalf("floating sort time %d, want %d", got, want)
	}
	// GPS time 00:58:50 UTC, wall clock 10:00 → +09:00 (the fix was a minute off).
	s.Lat, s.Lon, s.GPSDate, s.GPSTime = 35.6, 139.7, "2024:05:01", [3]uint32{0, 58, 50}
	m = read(t, mediatest.JPEG(s, ""))
	if m.Capture.Source != "exif-gps" || m.Capture.Offset != "+09:00" || m.Capture.UTC == nil {
		t.Fatalf("gps offset: %+v", m.Capture)
	}
	// Seven minutes off a quarter hour: no offset.
	s.GPSTime = [3]uint32{1, 7, 30}
	if m = read(t, mediatest.JPEG(s, "")); m.Capture.UTC != nil {
		t.Fatalf("ambiguous gps time gave %+v", m.Capture)
	}
	s.DateTime = "0000:00:00 00:00:00"
	if m = read(t, mediatest.JPEG(s, "")); m.Capture != nil {
		t.Fatalf("zero date: %+v", m.Capture)
	}
	for in, want := range map[string]int{"+09:00": 540, "-0330": -210, "+05": 300, "Z": 0} {
		if got, ok := parseOffset(in); !ok || got != want {
			t.Errorf("parseOffset(%q) = %d %v", in, got, ok)
		}
	}
	for _, bad := range []string{"", "09:00", "+25:00", "+9:0"} {
		if _, ok := parseOffset(bad); ok {
			t.Errorf("parseOffset(%q) accepted", bad)
		}
	}
}

func TestMotionPhotos(t *testing.T) {
	mp4 := mediatest.MiniMP4(7)
	g := mediatest.MotionJPEG(shot, mp4)
	m := read(t, g)
	if m.Motion == nil || m.Motion.Format != "google" || m.Motion.Offset != int64(len(g)-len(mp4)) || m.Motion.Length != int64(len(mp4)) ||
		m.Motion.PresentationUs == nil || *m.Motion.PresentationUs != 500000 {
		t.Fatalf("google: %+v", m.Motion)
	}
	sm := mediatest.SamsungMotionJPEG(shot, mp4)
	m = read(t, sm)
	if m.Motion == nil || m.Motion.Format != "samsung" || !bytes.Equal(sm[m.Motion.Offset:m.Motion.Offset+m.Motion.Length], mp4) {
		t.Fatalf("samsung: %+v", m.Motion)
	}
	h := append(mediatest.HEIF(shot, mediatest.MotionXMP(len(mp4))), mp4...)
	if m = read(t, h); m.Motion == nil || m.Motion.Offset != int64(len(h)-len(mp4)) {
		t.Fatalf("heif: %+v", m.Motion)
	}
	micro := `<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description ` +
		`xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"><GCamera:MicroVideo>1</GCamera:MicroVideo>` +
		`<GCamera:MicroVideoOffset>` + itoa(len(mp4)) + `</GCamera:MicroVideoOffset></rdf:Description></rdf:RDF></x:xmpmeta>`
	mv := append(mediatest.JPEG(shot, micro), mp4...)
	if m = read(t, mv); m.Motion == nil || m.Motion.Format != "microvideo" {
		t.Fatalf("microvideo: %+v", m.Motion)
	}
	// A directory pointing at something that is not an MP4 is not a motion photo.
	bad := append(mediatest.JPEG(shot, mediatest.MotionXMP(len(mp4))), bytes.Repeat([]byte{1}, len(mp4))...)
	if m = read(t, bad); m.Motion != nil {
		t.Fatalf("bogus motion %+v", m.Motion)
	}
}

func itoa(n int) string { return string(rune('0'+n/10%10)) + string(rune('0'+n%10)) }

func TestGarbage(t *testing.T) {
	for _, b := range [][]byte{nil, []byte("hello"), {0xFF, 0xD8, 0xFF, 0xE1, 0xFF, 0xFF}, []byte("II*\x00\xff\xff\xff\xff"),
		append([]byte{0, 0, 0, 24}, []byte("ftypheic\x00\x00\x00\x00mif1heic\x00\x00\x00\x01metaxxxx")...)} {
		_, _ = ReadImage(bytes.NewReader(b), int64(len(b))) // must not panic
	}
	// Truncated files at every length.
	full := mediatest.HEIF(shot, mediatest.MotionXMP(10))
	for i := range full {
		_, _ = ReadImage(bytes.NewReader(full[:i]), int64(i))
	}
	j := mediatest.SamsungMotionJPEG(shot, mediatest.MiniMP4(1))
	for i := range j {
		_, _ = ReadImage(bytes.NewReader(j[:i]), int64(i))
	}
}

func TestClassify(t *testing.T) {
	for name, want := range map[string][2]string{
		"a.ARW": {MediaImage, RoleRaw}, "b.jpeg": {MediaImage, RoleRendered}, "c.HIF": {MediaImage, RoleRendered},
		"d.MOV": {MediaVideo, RoleVideo}, "e.mp4": {MediaVideo, RoleVideo}, "f.xmp": {MediaSidecar, RoleSidecar},
		"._a.ARW": {"", ""}, ".DS_Store": {"", ""}, ".e.jpg": {"", ""}, "notes.txt": {"", ""}, "jpg": {"", ""},
	} {
		if m, r := Classify(name); m != want[0] || r != want[1] {
			t.Errorf("%s: %s %s", name, m, r)
		}
	}
	if Stem("dir/IMG_0001.HEIC") != "IMG_0001" || Stem("DSC.ARW.xmp") != "DSC.ARW" {
		t.Fatal("stem")
	}
}

func TestParseProbe(t *testing.T) {
	js := `{"streams":[{"codec_type":"video","codec_name":"hevc","profile":"Main 10","width":3840,"height":2160,
	"pix_fmt":"yuv420p10le","avg_frame_rate":"30000/1001","color_transfer":"arib-std-b67","color_primaries":"bt2020",
	"side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]},{"codec_type":"audio","codec_name":"aac"}],
	"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"3.003000","tags":{"creation_time":"2024-05-01T01:00:00.000000Z",
	"com.apple.quicktime.creationdate":"2024-05-01T10:00:00+0900","com.apple.quicktime.make":"Apple","com.apple.quicktime.model":"iPhone 15",
	"com.apple.quicktime.location.ISO6709":"+35.6586+139.7454+040.000/"}}}`
	v, tags, err := parseProbe([]byte(js))
	if err != nil {
		t.Fatal(err)
	}
	if v.Codec != "hevc" || v.BitDepth != 10 || v.HDR != "hlg" || v.FPS != 29.97 || v.Rotation != 90 || v.DurationMs != 3003 || v.Audio != "aac" {
		t.Fatalf("%+v", v)
	}
	m := VideoMeta(v, tags, "mov")
	if m.Make != "Apple" || m.Model != "iPhone 15" || m.GPS == nil || m.GPS.Lat != 35.6586 || *m.GPS.Alt != 40 {
		t.Fatalf("%+v", m)
	}
	if c := m.Capture; c.Source != "quicktime" || c.Local != "2024-05-01T10:00:00" || c.Offset != "+09:00" ||
		*c.UTC != time.Date(2024, 5, 1, 1, 0, 0, 0, time.UTC).UnixMilli() {
		t.Fatalf("%+v", c)
	}
	// creation_time only (UTC), with Samsung's offset tag.
	c := videoCapture(map[string]string{"creation_time": "2024-05-01T01:00:00.000000Z", "com.samsung.android.utc_offset": "+0900"})
	if c.Local != "2024-05-01T10:00:00" || c.Offset != "+09:00" || *c.UTC != time.Date(2024, 5, 1, 1, 0, 0, 0, time.UTC).UnixMilli() {
		t.Fatalf("samsung %+v", c)
	}
	if c := videoCapture(map[string]string{"creation_time": "2024-05-01T01:00:00.000000Z"}); c.Local != "" || c.Offset != "" || c.UTC == nil {
		t.Fatalf("utc only %+v", c)
	}
	for in, want := range map[float64]int{0: 0, -90: 90, 90: 270, 180: 180, -180: 180, 270: 90} {
		if got := normalizeRotation(in); got != want {
			t.Errorf("rotation %v = %d, want %d", in, got, want)
		}
	}
}

// ffmpegClip makes a 1-second test clip with ffmpeg, or skips when ffmpeg is not installed.
func ffmpegClip(t *testing.T, dir, name string, args ...string) string {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	p := filepath.Join(dir, name)
	cmd := exec.Command("ffmpeg", append(append([]string{"-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=25:duration=1",
		"-pix_fmt", "yuv420p"}, args...), p)...)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v %s", err, out)
	}
	return p
}

func TestProbeRealClips(t *testing.T) {
	p := NewProber("", 2, 20*time.Second)
	if !p.Available() {
		t.Skip("ffprobe not installed")
	}
	dir := t.TempDir()
	mov := ffmpegClip(t, dir, "IMG_0001.MOV", "-c:v", "libx264", "-movflags", "use_metadata_tags",
		"-metadata", "com.apple.quicktime.creationdate=2024-05-01T10:00:00+0900", "-metadata", "creation_time=2024-05-01T01:00:00Z",
		"-metadata", "com.apple.quicktime.make=Apple", "-metadata", "com.apple.quicktime.location.ISO6709=+35.6586+139.7454/")
	f, err := os.Open(mov)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	v, tags, err := p.Probe(context.Background(), f, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	m := VideoMeta(v, tags, "mov")
	if v.Codec != "h264" || v.Width != 64 || v.FPS != 25 || v.DurationMs < 900 || v.BitDepth != 8 || m.Make != "Apple" ||
		m.Capture == nil || m.Capture.Offset != "+09:00" || m.GPS == nil {
		t.Fatalf("%+v %+v %v", v, m, tags)
	}

	// The same clip embedded in a motion photo is probed through its byte range.
	clip, _ := os.ReadFile(mov)
	motion := mediatest.MotionJPEG(shot, clip)
	mp := filepath.Join(dir, "PXL_0001.MP.jpg")
	if err := os.WriteFile(mp, motion, 0o600); err != nil {
		t.Fatal(err)
	}
	mm := read(t, motion)
	if mm.Motion == nil {
		t.Fatal("not a motion photo")
	}
	g, _ := os.Open(mp)
	defer g.Close()
	ev, _, err := p.Probe(context.Background(), g, mm.Motion.Offset, mm.Motion.Length)
	if err != nil || ev.Codec != "h264" || ev.DurationMs < 900 {
		t.Fatalf("embedded: %+v %v", ev, err)
	}

	// Not a video: an error, never a hang.
	txt := filepath.Join(dir, "x.mp4")
	_ = os.WriteFile(txt, []byte("#EXTM3U\n#EXTINF:1,\nhttp://127.0.0.1:9/x.ts\n"), 0o600)
	h, _ := os.Open(txt)
	defer h.Close()
	if _, _, err := p.Probe(context.Background(), h, 0, 0); err == nil {
		t.Fatal("a playlist must not be probed")
	}
}
