package compress

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
)

// hideLeadingFrames moves the first edit list entry of an MP4's first track (the video) n frames later: the header
// keeps its frame count and duration, a decode skips n frames, as in a Samsung VFR clip (607 frames / 20.216 s in the
// header, 603 decoded).
func hideLeadingFrames(t *testing.T, tl tools, p string, n int) {
	t.Helper()
	entry := func(name string) string {
		out, err := exec.Command(tl.ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream="+name, "-of", "csv=p=0", p).Output()
		if err != nil {
			t.Fatal(err)
		}
		return strings.TrimSpace(string(out))
	}
	tb := entry("time_base")
	_, den, _ := strings.Cut(tb, "/")
	scale, _ := strconv.ParseInt(den, 10, 64)
	fr := rate(entry("r_frame_rate"))
	if scale == 0 || fr == 0 {
		t.Fatalf("time base %q", tb)
	}
	shift := int64(float64(scale)/fr+0.5) * int64(n)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	i := bytes.Index(b, []byte("elst"))
	if i < 0 {
		t.Fatal("no edit list")
	}
	if b[i+4] == 1 {
		o := i + 12 + 8
		binary.BigEndian.PutUint64(b[o:], uint64(int64(binary.BigEndian.Uint64(b[o:]))+shift))
	} else {
		o := i + 12 + 4
		binary.BigEndian.PutUint32(b[o:], uint32(int32(binary.BigEndian.Uint32(b[o:]))+int32(shift)))
	}
	if err := os.WriteFile(p, b, 0o600); err != nil {
		t.Fatal(err)
	}
}

// TestVerifierEditListHidesLeadingFrames: the source's video header counts frames its edit list hides; the output,
// encoded from the decoded frames, is shorter by them. Only the video stream's header duration differs (the file's and
// the audio's agree), so the decoded video decides: it passes, and a truly truncated output still fails.
func TestVerifierEditListHidesLeadingFrames(t *testing.T) {
	tl := needTools(t)
	dir := t.TempDir()
	v := &FFVerifier{FFmpeg: tl.ffmpeg, FFprobe: tl.ffprobe, Threads: 2}
	ctx := context.Background()
	src := tl.make(t, filepath.Join(dir, "src.mp4"), "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=8", "-f", "lavfi", "-i", "sine=f=440:d=8.3",
		"-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-bf", "2", "-c:a", "aac")
	hideLeadingFrames(t, tl, src, 4)
	sa, err := v.probe(ctx, nil, src)
	if err != nil {
		t.Fatal(err)
	}
	vs, _ := mainVideo(sa.Streams)
	decoded, err := v.decodedVideo(ctx, nil, src, sa.Duration)
	if err != nil {
		t.Fatal(err)
	}
	if vs.NbFrames != 240 || decoded.Frames != 236 || vs.Duration-decoded.Span < 0.12 {
		t.Fatalf("the source does not hide frames: header %d / %.3f s, decoded %d / %.3f s", vs.NbFrames, vs.Duration, decoded.Frames, decoded.Span)
	}
	reenc := []string{"-c:v", "libx265", "-preset", "ultrafast", "-crf", "28", "-tag:v", "hvc1", "-x265-params", "log-level=error",
		"-fps_mode", "passthrough", "-enc_time_base:v", "demux"}
	good := tl.make(t, filepath.Join(dir, "good.mp4"), append([]string{"-i", src, "-map", "0", "-c:a", "copy"}, reenc...)...)
	var log bytes.Buffer
	if _, err := v.Check(ctx, open(t, src), good, "", &log); err != nil {
		t.Fatalf("good: %v\n%s", err, log.String())
	}
	if !strings.Contains(log.String(), "duration (decoded), streams, picture: ok") {
		t.Fatalf("the decoded comparison did not run:\n%s", log.String())
	}
	// The video cut 0.5 s short, the audio whole: the file's duration is unchanged.
	short := tl.make(t, filepath.Join(dir, "short.mp4"), append([]string{"-i", src, "-map", "0", "-c:a", "copy", "-frames:v", "221"}, reenc...)...)
	log.Reset()
	if got := reasonOf(v.Check(ctx, open(t, src), short, "", &log)); got != "verify_duration" {
		t.Fatalf("truncated: %q\n%s", got, log.String())
	}
}

func TestCompareInfoDefersVideoHeaderDuration(t *testing.T) {
	video := verifyStream{Kind: "video", Width: 1, Height: 1, FrameRate: 30, BitDepth: 8, Duration: 20.216}
	audio := verifyStream{Kind: "audio", Duration: 20.352}
	a := verifyInfo{Duration: 20.352, Streams: []verifyStream{video, audio}}
	video.Duration = 20.083
	b := verifyInfo{Duration: 20.352, Streams: []verifyStream{video, audio}}
	var d *videoDurationDeferred
	if err := compareInfo(a, b); !errors.As(err, &d) {
		t.Fatalf("video header only: %v", err)
	}
	// The picture is still checked first.
	b.Streams[0].Width = 2
	if got := checkReason(compareInfo(a, b)); got != "verify_picture" {
		t.Fatalf("picture: %q", got)
	}
	b.Streams[0].Width = 1
	// An audio stream's duration is never deferred.
	b.Streams[1].Duration = 19.9
	if got := checkReason(compareInfo(a, b)); got != "verify_duration" {
		t.Fatalf("audio: %q", got)
	}
}

func TestParseDecodedSpan(t *testing.T) {
	d, err := parseDecodedSpan([]byte("0.066667,0.033333,\n0.100000,0.033333\nN/A,N/A\n7.900000,0.033333\n"))
	if err != nil || d.Frames != 4 || d.Span < 7.866 || d.Span > 7.867 {
		t.Fatalf("%+v %v", d, err)
	}
	if _, err := parseDecodedSpan(nil); err == nil {
		t.Fatal("no frames passed")
	}
}

func exif(t *testing.T, s string) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// The gate on canned `exiftool -a -G1 -j` objects (shapes from exiftool 13.64 on synthetic files carrying Samsung's
// smta and Sony's NRTM XML, which -tagsFromFile does not write back).
func TestExifGate(t *testing.T) {
	kept := `"QuickTime:CreateDate":"2026:05:01 09:00:00","Track1:MediaCreateDate":"2026:05:01 09:00:00",
		"Keys:GPSCoordinates":"48 deg 51' 30.24\" N, 2 deg 17' 40.20\" E","Composite:Rotation":90`
	samsung := exif(t, `{"SourceFile":"x","ExifTool:ExifToolVersion":13.64,"File:FileSize":"1 MB",`+kept+`,
		"Samsung:SamsungModel":"SM-S918B","QuickTime:HandlerVendorID":"Apple"}`)
	sony := exif(t, `{`+kept+`,"XML:DeviceManufacturer":"Sony","XML:DeviceModelName":"ZV-E10","XML:DeviceSerialNo":1234567,
		"XML:CreationDateValue":"2026:05:01 10:00:00+01:00"}`)
	out := exif(t, `{"SourceFile":"y","File:FileSize":"0.4 MB",`+kept+`}`)

	// Without the camera written back, the camera of an unwritable family is lost: the gate fails.
	g := exifGate(samsung, out)
	if !reflect.DeepEqual(g.Lost, []string{"Make", "Model"}) || !reflect.DeepEqual(g.Expected, []string{"Samsung:SamsungModel"}) ||
		!reflect.DeepEqual(g.NotRestored, []string{"QuickTime:HandlerVendorID", "Samsung:SamsungModel"}) || g.Make != "Samsung" || g.Model != "SM-S918B" {
		t.Fatalf("samsung %+v", g)
	}
	// With it in UserData (the encoder's restoreCamera): passes, the family's own tag still listed.
	withCam := exif(t, `{"SourceFile":"y",`+kept+`,"UserData:Make":"Samsung","UserData:Model":"SM-S918B"}`)
	if g = exifGate(samsung, withCam); len(g.Lost) != 0 || !reflect.DeepEqual(g.Expected, []string{"Samsung:SamsungModel"}) {
		t.Fatalf("samsung written back %+v", g)
	}
	if mk, model := cameraOf(withCam); mk != "Samsung" || model != "SM-S918B" {
		t.Fatalf("output camera %q %q", mk, model)
	}
	g = exifGate(sony, out)
	if !reflect.DeepEqual(g.Lost, []string{"Make", "Model"}) || !reflect.DeepEqual(g.Expected, []string{"XML:DeviceManufacturer", "XML:DeviceModelName"}) ||
		len(g.NotRestored) != 4 || g.Make != "Sony" || g.Model != "ZV-E10" {
		t.Fatalf("sony %+v", g)
	}
	if g = exifGate(sony, exif(t, `{`+kept+`,"UserData:Make":"Sony","UserData:Model":"ZV-E10"}`)); len(g.Lost) != 0 {
		t.Fatalf("sony written back %+v", g)
	}
	// Make and Model in a writable group: kept in another group passes, gone fails.
	withMake := exif(t, `{`+kept+`,"UserData:Make":"DJI","UserData:Model":"Osmo Nano"}`)
	if g = exifGate(withMake, out); !reflect.DeepEqual(g.Lost, []string{"Make", "Model"}) || g.Make != "DJI" {
		t.Fatalf("writable make %+v", g)
	}
	if g = exifGate(withMake, exif(t, `{`+kept+`,"Keys:Make":"DJI","Keys:Model":"Osmo Nano"}`)); len(g.Lost) != 0 {
		t.Fatalf("make kept in another group %+v", g)
	}
	// No camera in the source: nothing to keep.
	if g = exifGate(exif(t, `{`+kept+`}`), out); len(g.Lost) != 0 || g.Make != "" {
		t.Fatalf("no camera %+v", g)
	}
	// A writable Make wins over the unwritable family's.
	both := exif(t, `{`+kept+`,"XML:DeviceManufacturer":"Sony","XML:Make":"Other","ItemList:Make":"Sony Corp","Samsung:SamsungModel":"X"}`)
	if mk, model := cameraOf(both); mk != "Sony Corp" || model != "X" {
		t.Fatalf("camera %q %q", mk, model)
	}
	// Date, place and rotation keep failing, whatever else is expected.
	for _, drop := range []string{"QuickTime:CreateDate", "Keys:GPSCoordinates"} {
		o := exif(t, `{`+kept+`}`)
		delete(o, drop)
		if drop == "QuickTime:CreateDate" {
			delete(o, "Track1:MediaCreateDate")
		}
		o["Keys:Make"], o["Keys:Model"] = "Samsung", "SM-S918B"
		if g = exifGate(samsung, o); len(g.Lost) == 0 || g.Lost[0] == "Make" {
			t.Fatalf("%s lost passed", drop)
		}
	}
	o := exif(t, `{`+kept+`,"Keys:Make":"Sony","Keys:Model":"ZV-E10"}`)
	o["Composite:Rotation"] = 0.0
	if g = exifGate(sony, o); !reflect.DeepEqual(g.Lost, []string{"Rotation"}) {
		t.Fatalf("rotation %+v", g)
	}
}

func TestWriteSidecar(t *testing.T) {
	dir := t.TempDir()
	pf := filepath.Join(dir, "perframe.json.gz")
	f, _ := os.Create(pf)
	zw := gzip.NewWriter(f)
	_, _ = zw.Write([]byte(`[{"Doc1:SampleTime":"0 s"}]` + "\n"))
	_ = zw.Close()
	_ = f.Close()
	var h sidecarHead
	h.Original.Fingerprint, h.Output.Fingerprint = "v1:aa", "v1:bb"
	h.Camera.Make, h.Camera.Model = "Sony", "ZV-E10"
	h.Exiftool = json.RawMessage(`{"XML:DeviceModelName":"ZV-E10"}`)
	h.NotRestored, h.ExpectedLost = []string{"XML:DeviceModelName"}, []string{"XML:DeviceModelName"}
	for _, per := range []string{"", pf} {
		var buf bytes.Buffer
		if err := writeSidecar(&buf, h, per); err != nil {
			t.Fatal(err)
		}
		zr, err := gzip.NewReader(&buf)
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		if err := json.NewDecoder(zr).Decode(&m); err != nil {
			t.Fatal(err)
		}
		if m["version"] != 2.0 || m["camera"].(map[string]any)["model"] != "ZV-E10" || m["ffprobe"] != nil ||
			m["exiftool"].(map[string]any)["XML:DeviceModelName"] != "ZV-E10" {
			t.Fatalf("%v", m)
		}
		if (per != "") != (m["perFrame"] != nil) {
			t.Fatalf("perFrame %v", m["perFrame"])
		}
	}
}
