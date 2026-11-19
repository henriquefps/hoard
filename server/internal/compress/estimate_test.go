package compress

import (
	"encoding/json"
	"math"
	"slices"
	"strings"
	"testing"
	"time"
)

// A 2.7K30 HEVC 10-bit drone clip of 30 s at 0.41 bpp (the camera originals), as ffprobe answers it.
const droneProbe = `{"streams":[
 {"index":0,"codec_type":"video","codec_name":"hevc","profile":"Main 10","width":2720,"height":1530,"pix_fmt":"yuv420p10le",
  "r_frame_rate":"30000/1001","avg_frame_rate":"30000/1001","bit_rate":"51000000","nb_frames":"899","color_transfer":"arib-std-b67",
  "color_primaries":"bt2020","tags":{"encoder":"HEVC Coding"},"side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]},
 {"index":1,"codec_type":"audio","codec_name":"aac","bit_rate":"128000"},
 {"index":2,"codec_type":"data","codec_name":"none","codec_tag_string":"djmd"},
 {"index":3,"codec_type":"data","codec_name":"none","codec_tag_string":"dbgi"},
 {"index":4,"codec_type":"video","codec_name":"mjpeg","width":960,"height":540,"disposition":{"attached_pic":1}}],
 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"30.030","bit_rate":"53000000","tags":{"major_brand":"isom"}}}`

func mustProbe(t *testing.T, js string) Probe {
	t.Helper()
	p, err := ParseProbe([]byte(js))
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestParseProbe(t *testing.T) {
	p := mustProbe(t, droneProbe)
	if p.Codec != "hevc" || p.Width != 2720 || p.BitDepth != 10 || p.Rotation != -90 || p.Covers != 1 || p.Transfer != "arib-std-b67" ||
		p.DurationMs != 30030 || p.NbFrames != 899 || !slices.Equal(p.Data, []string{"djmd", "dbgi"}) || !p.PerFrameData() || p.PCM() {
		t.Fatalf("%+v", p)
	}
	if math.Abs(p.BPP()-0.4089) > 0.001 {
		t.Fatalf("bpp %.4f", p.BPP())
	}
	// Frame side data: HDR10+ and a Dolby Vision RPU.
	ApplyFrameSideData(&p, []byte(`{"frames":[{"side_data_list":[{"side_data_type":"HDR Dynamic Metadata SMPTE2094-40 (HDR10+)"},
		{"side_data_type":"Mastering display metadata"},{"side_data_type":"Content light level metadata"}]}]}`))
	if !p.HDR10Plus || !p.Mastering || !p.CLL || p.DolbyVision {
		t.Fatalf("%+v", p)
	}
	ApplyFrameSideData(&p, []byte(`{"frames":[{"side_data_list":[{"side_data_type":"Dolby Vision RPU Data"}]}]}`))
	if !p.DolbyVision {
		t.Fatal("dovi")
	}
	// No video stream: unreadable. A file whose video stream has no bit_rate takes the format's less the audio.
	if q := mustProbe(t, `{"streams":[{"codec_type":"audio","codec_name":"aac"}],"format":{}}`); !q.Unreadable {
		t.Fatal("audio only")
	}
	q := mustProbe(t, `{"streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"pix_fmt":"yuv420p","avg_frame_rate":"30/1",
		"r_frame_rate":"30/1"},{"codec_type":"audio","codec_name":"pcm_s16be","bit_rate":"1536000"}],
		"format":{"duration":"10","bit_rate":"51536000","tags":{"dev.hfps.hoard.compressed":"v1, hevc-cq28, 2026-11-19"}}}`)
	if q.BitRate != 50_000_000 || !q.PCM() || q.Marker == "" || q.BitDepth != 8 {
		t.Fatalf("%+v", q)
	}
}

func TestMaxRateCap(t *testing.T) {
	// Samsung VFR: r_frame_rate 120, real ~30 fps.
	p := Probe{BitRate: 20_000_000, RFrameRate: 120, AvgFrameRate: 29.97}
	if got := p.MaxRate(); got < 80_000_000 || got > 80_200_000 {
		t.Fatalf("maxrate %d", got)
	}
	p.RFrameRate, p.AvgFrameRate = 30, 30
	if p.MaxRate() != 20_000_000 {
		t.Fatal("constant frame rate: the source's bitrate")
	}
}

func TestSkipRules(t *testing.T) {
	prof, _ := ProfileByID(DefaultProfile)
	base := mustProbe(t, droneProbe)
	in := func(mut func(*Input, *Probe)) Estimate {
		p := base
		i := Input{Kind: "video", Paths: []string{"DJI/DJI_0001.MP4"}, Probe: &p, Size: 191_000_000}
		if mut != nil {
			mut(&i, &p)
		}
		return Decide(i, prof)
	}
	cases := []struct {
		name string
		mut  func(*Input, *Probe)
		want string
	}{
		{"photo", func(i *Input, _ *Probe) { i.Kind = "photo" }, ReasonNotVideo},
		{"motion", func(i *Input, _ *Probe) { i.Kind = "motion" }, ReasonNotVideo},
		{"unreadable", func(_ *Input, p *Probe) { p.Unreadable = true }, ReasonUnreadable},
		{"marker", func(_ *Input, p *Probe) { p.Marker = "v1, hevc-cq28, 2026-11-19" }, ReasonAlreadyCompressed},
		{"output", func(i *Input, _ *Probe) { i.IsOutput = true }, ReasonAlreadyCompressed},
		{"old script", func(_ *Input, p *Probe) { p.Encoder = "Lavc61.19.100 hevc_nvenc" }, ReasonOlderCompression},
		{"lavc h264 is not rule 2", func(_ *Input, p *Probe) { p.Codec, p.Encoder = "h264", "Lavc61.19.100 libx264" }, ""},
		{"efficient", func(_ *Input, p *Probe) { p.BitRate = 7_000_000 }, ReasonEfficient},
		{"efficient h264 is not", func(_ *Input, p *Probe) { p.Codec, p.BitRate = "h264", 7_000_000 }, ""},
		{"dolby vision (meta)", func(i *Input, _ *Probe) { i.HDR = "dolby-vision" }, ReasonDolbyVision},
		{"dolby vision (rpu)", func(_ *Input, p *Probe) { p.DolbyVision = true }, ReasonDolbyVision},
		{"reveal", func(i *Input, _ *Probe) { i.RevealNamed = true }, ReasonRevealRecord},
		{"conflict copy", func(i *Input, _ *Probe) { i.Paths = []string{"Camera/VID_1.sync-conflict-20240101-120000-ABCDEFG.mp4"} }, ReasonSyncArtifact},
		{"stversions", func(i *Input, _ *Probe) { i.Paths = []string{"Camera/.stversions/VID_1~20240101.mp4"} }, ReasonSyncArtifact},
		{"trashed", func(i *Input, _ *Probe) { i.Trashed = true }, ReasonTrashed},
		{"hoard folder", func(i *Input, _ *Probe) { i.Paths = []string{".hoard-trash/2026-11-19/x.mp4"} }, ReasonTrashed},
		{"busy", func(i *Input, _ *Probe) { i.Busy = true }, ReasonBusy},
		{"small saving", func(i *Input, _ *Probe) { i.Size = 30_000_000 }, ReasonSmallSaving},
		{"encode", nil, ""},
	}
	for _, c := range cases {
		e := in(c.mut)
		if c.want == "" {
			if e.Decision != DecisionEncode {
				t.Errorf("%s: %+v", c.name, e)
			}
			continue
		}
		if e.Decision != DecisionSkip || e.Reason != c.want {
			t.Errorf("%s: %+v, want %s", c.name, e, c.want)
		}
	}
	// Not probed yet: pending.
	if e := Decide(Input{Kind: "video", Size: 1}, prof); e.Decision != DecisionPending {
		t.Fatalf("%+v", e)
	}
}

// The estimate: bpp_ref(28) = 0.067, bpp_ref(30) = 0.049 × pixels × fps × duration ÷ 8, AV1 0.82×, audio as it will be,
// 0.3 % container; time from the class's speed plus verification; the warnings.
func TestEstimateMaths(t *testing.T) {
	p := mustProbe(t, droneProbe)
	q28, _ := ProfileByID("hevc-cq28")
	q30, _ := ProfileByID("hevc-cq30")
	av1, _ := ProfileByID("av1-cq34")
	fps := 30000.0 / 1001
	video28 := 0.067 * 2720 * 1530 * fps * 30.03 / 8
	audio := 128000 * 30.03 / 8
	e := Decide(Input{Kind: "video", Probe: &p, Size: 191_000_000}, q28)
	if want := int64((video28 + audio) * 1.003); math.Abs(float64(e.EstSize-want)) > 2 {
		t.Fatalf("est %d want %d", e.EstSize, want)
	}
	if math.Abs(e.EstSaving-(1-float64(e.EstSize)/191e6)) > 1e-9 {
		t.Fatal("saving")
	}
	if want := durMs(30030, 2.5) + verifyMs(30030); e.EstMs != want {
		t.Fatalf("ms %d want %d", e.EstMs, want)
	}
	if !slices.Equal(e.Warnings, []string{WarnPerFrame}) {
		t.Fatalf("warnings %v", e.Warnings)
	}
	e30 := Decide(Input{Kind: "video", Probe: &p, Size: 191_000_000}, q30)
	if want := int64((0.049*2720*1530*fps*30.03/8 + audio) * 1.003); math.Abs(float64(e30.EstSize-want)) > 2 {
		t.Fatalf("cq30 %d want %d", e30.EstSize, want)
	}
	ea := Decide(Input{Kind: "video", Probe: &p, Size: 191_000_000}, av1)
	if want := int64((video28*0.82 + audio) * 1.003); math.Abs(float64(ea.EstSize-want)) > 2 || !slices.Contains(ea.Warnings, WarnAV1Playback) {
		t.Fatalf("av1 %d want %d %v", ea.EstSize, want, ea.Warnings)
	}
	// PCM: 256 kb/s per audio stream; 4K HEVC at 1.1×.
	pcm := p
	pcm.Audio, pcm.AudioBitRate, pcm.Codec, pcm.BitDepth, pcm.Width, pcm.Height = []string{"pcm_s16be"}, 1536000, "hevc", 8, 3840, 2160
	ep := Decide(Input{Kind: "video", Probe: &pcm, Size: 600_000_000}, q28)
	if !slices.Contains(ep.Warnings, WarnPCMAudio) || ep.EstMs != durMs(30030, 1.1)+verifyMs(30030) {
		t.Fatalf("%+v", ep)
	}
}

// The recipe as an argument vector: the cap, the VFR fix, lean options, Main 10 for 10-bit, PCM to AAC 256k, data
// tracks dropped, the marker; the quality set adds the draft's options without AQ.
func TestEncodeArgs(t *testing.T) {
	p := mustProbe(t, droneProbe)
	prof, _ := ProfileByID("hevc-cq28")
	r := EncodeRequest{Probe: p, Profile: prof, Q: 26, Set: SetLean, Out: "/w/out.mp4", Date: "2026-11-19"}
	a := strings.Join(encodeArgs(r, "SRC", true), " ")
	for _, want := range []string{"-hwaccel cuda -noautorotate -i SRC", "-map 0 -map -0:d -map_metadata 0 -map_chapters 0 -c copy",
		"-c:v:0 hevc_nvenc -rc vbr -cq 26 -b:v:0 0 -maxrate:v:0 51000000 -bufsize:v:0 102000000", "-preset p7 -tune hq -tag:v:0 hvc1",
		"-pix_fmt p010le -profile:v:0 main10", "-fps_mode passthrough -enc_time_base:v demux",
		"-fps_mode passthrough -enc_time_base:v demux -movflags +faststart /w/out.mp4"} {
		if !strings.Contains(a, want) {
			t.Errorf("missing %q in %s", want, a)
		}
	}
	if markerValue(r) != "v1, hevc-cq26, 2026-11-19" {
		t.Errorf("marker %q", markerValue(r))
	}
	for _, not := range []string{"aq", "multipass", "-c:a aac", "lookahead", "use_metadata_tags", "-metadata"} {
		if strings.Contains(a, not) {
			t.Errorf("unexpected %q", not)
		}
	}
	p.Audio, p.BitDepth = []string{"pcm_s16be"}, 8
	r.Probe, r.Set = p, SetQuality
	a = strings.Join(encodeArgs(r, "SRC", false), " ")
	for _, want := range []string{"-c:a aac -b:a 256k", "-pix_fmt yuv420p -profile:v:0 main", "-multipass fullres -rc-lookahead 32 -bf 4 -b_ref_mode middle"} {
		if !strings.Contains(a, want) {
			t.Errorf("missing %q in %s", want, a)
		}
	}
	if strings.Contains(a, "hwaccel") || strings.Contains(a, "spatial-aq") {
		t.Errorf("cpu decode / aq: %s", a)
	}
	av1, _ := ProfileByID("av1-cq34")
	r.Profile, r.Q = av1, 34
	a = strings.Join(encodeArgs(r, "SRC", true), " ")
	if !strings.Contains(a, "-c:v:0 av1_nvenc") || strings.Contains(a, "hvc1") || markerValue(r) != "v1, av1-cq34, 2026-11-19" {
		t.Errorf("av1: %s", a)
	}
}

func TestOptionsAndWindow(t *testing.T) {
	o, err := ParseOptions(nil)
	if err != nil || o.Quality.Mode != ModeFloor || o.Quality.VMAF != 93 || o.Set != SetLean {
		t.Fatalf("%+v %v", o, err)
	}
	o, _ = ParseOptions(json.RawMessage(`{"quality":{"mode":"target"},"set":"quality"}`))
	if o.Quality.VMAF != 95 || o.Set != SetQuality {
		t.Fatalf("%+v", o)
	}
	for _, bad := range []string{`{"quality":{"mode":"x"}}`, `{"set":"aq"}`, `{"quality":{"vmaf":120}}`, `[1]`} {
		if _, err := ParseOptions(json.RawMessage(bad)); err == nil {
			t.Errorf("accepted %s", bad)
		}
	}
	at := func(h, m int) time.Time { return time.Date(2026, 11, 19, h, m, 0, 0, time.Local) }
	if !InWindow("01:00-08:00", at(3, 0)) || InWindow("01:00-08:00", at(9, 0)) || !InWindow("22:00-06:00", at(23, 30)) ||
		!InWindow("22:00-06:00", at(5, 59)) || InWindow("22:00-06:00", at(12, 0)) || !InWindow("", at(12, 0)) {
		t.Fatal("window")
	}
	if _, _, ok := ParseWindow("25:00-01:00"); ok {
		t.Fatal("bad window accepted")
	}
}

func durMs(ms int64, speed float64) int64 { return int64(float64(ms) / speed) }
