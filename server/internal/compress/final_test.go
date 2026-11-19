package compress

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Last pre-tag review of 0.13.0 (#74): the per-frame cap, the deferral's scope, the marker, the camera's values and
// exiftool's unreadable output.

// fakeTool writes an executable shell script that prints out (the tools run with PATH=/usr/bin:/bin).
func fakeTool(t *testing.T, out string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "exiftool")
	script := "#!/bin/sh\ncat <<'EOF'\n" + out + "\nEOF\n"
	if err := os.WriteFile(p, []byte(script), 0o700); err != nil { //nolint:gosec // a test tool
		t.Fatal(err)
	}
	return p
}

func gzFile(t *testing.T, p, s string) string {
	t.Helper()
	f, err := os.Create(p)
	if err != nil {
		t.Fatal(err)
	}
	zw := gzip.NewWriter(f)
	_, _ = zw.Write([]byte(s))
	_ = zw.Close()
	_ = f.Close()
	return p
}

func withPerFrameCap(t *testing.T, n int64) {
	t.Helper()
	old := perFrameCap
	perFrameCap = n
	t.Cleanup(func() { perFrameCap = old })
}

func TestSidecarFailsAtThePerFrameCap(t *testing.T) {
	withPerFrameCap(t, 64)
	src := open(t, gzFile(t, filepath.Join(t.TempDir(), "src"), "x"))
	small := `[{"Doc1:SampleTime":"0 s"}]`
	big := `[` + strings.Repeat(`{"Doc1:SampleTime":"0 s"},`, 10) + `{}]`
	for _, c := range []struct {
		out  string
		fail bool
	}{{small, false}, {big, true}} {
		e := &NvencEncoder{Exiftool: fakeTool(t, c.out)}
		var buf, log bytes.Buffer
		err := e.Sidecar(context.Background(), src, &buf, &log)
		if c.fail != errors.Is(err, errCapped) || (!c.fail && err != nil) {
			t.Fatalf("%d bytes: %v", len(c.out), err)
		}
		if !c.fail {
			p := filepath.Join(t.TempDir(), "pf.gz")
			if err := os.WriteFile(p, buf.Bytes(), 0o600); err != nil {
				t.Fatal(err)
			}
			if err := checkGzipJSON(p); err != nil {
				t.Fatal(err)
			}
		}
	}
	// A per-frame file over the cap (written before it was lowered) is refused, not cut short.
	dir := t.TempDir()
	pf := gzFile(t, filepath.Join(dir, "pf.gz"), `[`+strings.Repeat(`{"a":1},`, 20)+`{}]`)
	var h sidecarHead
	if err := writeSidecar(&bytes.Buffer{}, h, pf); !errors.Is(err, errCapped) {
		t.Fatalf("over the cap: %v", err)
	}
}

func TestCheckJSON(t *testing.T) {
	for s, ok := range map[string]bool{
		`[{"a":1},{"b":[1,2,{"c":"d"}]}]` + "\n": true,
		` {"a":null} `:                           true,
		`"x"`:                                    true,
		`[{"a":1},{"b":`:                         false,
		`[{"a":1}]]`:                             false,
		`[{"a":1}] {}`:                           false,
		``:                                       false,
		`{"a":tru}`:                              false,
	} {
		if err := checkJSON(strings.NewReader(s)); (err == nil) != ok {
			t.Errorf("%q: %v", s, err)
		}
	}
}

// A sidecar whose per-frame part does not parse is not kept: nothing at MetaPath.
func TestKeepSidecarRefusesBrokenPerFrame(t *testing.T) {
	m, _ := testManager(t, t.TempDir())
	work := t.TempDir()
	var h sidecarHead
	h.Output.Fingerprint = "v1:out"
	pf := gzFile(t, filepath.Join(work, "perframe.json.gz"), `[{"Doc1:SampleTime":"0 s"},{"Doc2:Sam`)
	if err := m.keepSidecar(work, h, pf); err == nil {
		t.Fatal("a truncated per-frame part was kept")
	}
	if _, err := os.Stat(m.MetaPath("v1:out")); !os.IsNotExist(err) {
		t.Fatalf("sidecar on disk: %v", err)
	}
	gzFile(t, pf, `[{"Doc1:SampleTime":"0 s"}]`)
	if err := m.keepSidecar(work, h, pf); err != nil {
		t.Fatal(err)
	}
	if err := checkGzipJSON(m.MetaPath("v1:out")); err != nil {
		t.Fatal(err)
	}
}

// Only the main video stream's header duration is deferred to the decoded video.
func TestCompareInfoDefersOnlyTheMainVideo(t *testing.T) {
	video := verifyStream{Kind: "video", Width: 1, Height: 1, FrameRate: 30, BitDepth: 8, Duration: 20.216}
	second := verifyStream{Kind: "video", Width: 1, Height: 1, FrameRate: 30, BitDepth: 8, Duration: 20.216}
	a := verifyInfo{Duration: 20.352, Streams: []verifyStream{video, second}}
	b := verifyInfo{Duration: 20.352, Streams: []verifyStream{video, second}}
	b.Streams[1].Duration = 20.083
	if got := checkReason(compareInfo(a, b)); got != "verify_duration" {
		t.Fatalf("second video stream: %q", got)
	}
	b.Streams[0].Duration, b.Streams[1].Duration = 20.083, 20.216
	var d *videoDurationDeferred
	if err := compareInfo(a, b); !errors.As(err, &d) {
		t.Fatalf("main video stream: %v", err)
	}
}

// Every job's check confirms the output's marker (either name), with the encode's value.
func TestVerifierMarker(t *testing.T) {
	tl := needTools(t)
	dir := t.TempDir()
	v := &FFVerifier{FFmpeg: tl.ffmpeg, FFprobe: tl.ffprobe, Threads: 2}
	ctx := context.Background()
	src := tl.make(t, filepath.Join(dir, "src.mp4"), "-f", "lavfi", "-i", "testsrc2=s=320x180:r=30:d=3", "-c:v", "libx264", "-preset", "ultrafast")
	const want = "v1, hevc-cq28, 2026-11-19"
	marked := func(name, key, value string) string {
		return tl.make(t, filepath.Join(dir, name), "-i", src, "-c", "copy", "-movflags", "use_metadata_tags", "-metadata", key+"="+value)
	}
	for _, c := range []struct {
		name, out, want string
	}{
		{"apple key", marked("apple.mp4", MarkerKey, want), ""},
		{"bare key", marked("bare.mp4", Marker, want), ""},
		{"other value", marked("other.mp4", MarkerKey, "v1, hevc-cq26, 2026-11-19"), "verify_metadata"},
		{"none", tl.make(t, filepath.Join(dir, "none.mp4"), "-i", src, "-c", "copy"), "verify_metadata"},
	} {
		var log bytes.Buffer
		if got := reasonOf(v.Check(ctx, open(t, src), c.out, want, &log)); got != c.want {
			t.Errorf("%s: %q, want %q\n%s", c.name, got, c.want, log.String())
		}
	}
}

// The camera's make and model must keep their values (trimmed, any case), not only be present.
func TestExifGateCameraValues(t *testing.T) {
	kept := `"QuickTime:CreateDate":"2026:05:01 09:00:00","Keys:GPSCoordinates":"48 deg 51' 30.24\" N, 2 deg 17' 40.20\" E"`
	src := exif(t, `{`+kept+`,"UserData:Make":"DJI","UserData:Model":"Osmo Nano"}`)
	if g := exifGate(src, exif(t, `{`+kept+`,"Keys:Make":" dji ","Keys:Model":"OSMO NANO"}`)); len(g.Lost) != 0 {
		t.Fatalf("same camera %+v", g)
	}
	if g := exifGate(src, exif(t, `{`+kept+`,"UserData:Make":"DJI","UserData:Model":"Osmo Pocket 3"}`)); !reflect.DeepEqual(g.Lost, []string{"Model"}) {
		t.Fatalf("other model %+v", g)
	}
	if g := exifGate(src, exif(t, `{`+kept+`,"UserData:Make":"GoPro","UserData:Model":"Osmo Nano"}`)); !reflect.DeepEqual(g.Lost, []string{"Make"}) {
		t.Fatalf("other make %+v", g)
	}
	// Another group with the right value is enough.
	if g := exifGate(src, exif(t, `{`+kept+`,"UserData:Make":"DJI","UserData:Model":"x","ItemList:Model":"Osmo Nano"}`)); len(g.Lost) != 0 {
		t.Fatalf("model in another group %+v", g)
	}
	samsung := exif(t, `{`+kept+`,"Samsung:SamsungModel":"SM-S918B"}`)
	if g := exifGate(samsung, exif(t, `{`+kept+`,"UserData:Make":"Samsung","UserData:Model":"SM-S911B"}`)); !reflect.DeepEqual(g.Lost, []string{"Model"}) {
		t.Fatalf("samsung other model %+v", g)
	}
}

// exiftool's output that does not parse (a read cut at the 32 MiB cap) fails the metadata check.
func TestMetaDiffUnreadableExiftool(t *testing.T) {
	if _, err := exec.LookPath("sh"); err != nil {
		t.Skip("no sh")
	}
	dir := t.TempDir()
	src := open(t, gzFile(t, filepath.Join(dir, "src"), "x"))
	out := filepath.Join(dir, "out")
	if err := os.WriteFile(out, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	for tool, want := range map[string]string{
		`[{"QuickTime:CreateDate":"2026:05:01 09:00:00"}]`:      "",
		`[{"QuickTime:CreateDate":"2026:05:01 09:00:00","Doc1:`: "verify_metadata",
	} {
		v := &FFVerifier{Exiftool: fakeTool(t, tool)}
		var log bytes.Buffer
		if got := reasonOf(v.metaDiff(context.Background(), verifyInfo{}, verifyInfo{}, src, out, "", &log)); got != want {
			t.Errorf("%q: %q, want %q\n%s", tool, got, want, log.String())
		}
	}
}
