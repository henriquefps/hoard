package main

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestGPUCompression is the GPU integration test of video compression (ADR 0025, phase S1). Opt-in: HOARD_GPU_TEST=1,
// on a host with an NVIDIA GPU, Docker and the NVIDIA container toolkit (CDI). It builds the -nvidia image under
// HOARD_GPU_IMAGE (default hoard-server:s1-test-nvidia) unless it exists, makes synthetic clips with the host's ffmpeg
// (libx264, libx265), and runs the image as the caller's uid with the GPU against temporary data, backup and NAS
// directories: the recipe (marker, the cap, 10-bit, HLG and PQ with HDR10 metadata bit-exact, PCM to AAC 256k, VFR
// timestamps), the metadata sidecar of every output (per-frame data of a data track; Samsung smta and Sony NRTM camera
// identity expected lost and kept; an edit list hiding leading frames), the CUDA-decode fallback, and a NAS job end to end through the
// routes (estimate, job, replaced with the original in .hoard-trash, restore, purge, the walk afterwards). It reports
// aggregates and timings only. The container and the temporary directories are removed at the end; the image stays.
func TestGPUCompression(t *testing.T) {
	e, clips := startGPUEnv(t, "2g", makeClips)
	c, dirs, folder, image := e.c, e.dirs, e.folder, e.image
	sel := map[string]any{"folders": []any{map[string]any{"folderId": folder}}}
	var est map[string]any
	for i := 0; i < 60; i++ {
		est = c.device("POST", "compress/estimate", map[string]any{"target": "nas", "select": sel}, 200)
		if est["pending"].(float64) == 0 {
			break
		}
		time.Sleep(time.Second)
	}
	totals := est["totals"].(map[string]any)
	t.Logf("estimate: %v videos, %v to encode, %v → about %v bytes, about %v ms, skipped %v", totals["items"], totals["encode"], totals["size"],
		totals["estSize"], totals["estMs"], totals["skip"])
	warn := map[string][]string{}
	for _, it := range est["items"].([]any) {
		m := it.(map[string]any)
		for _, w := range asStrings(m["warnings"]) {
			warn[w] = append(warn[w], fmt.Sprint(m["name"]))
		}
	}
	if len(warn["pcm_audio"]) != 1 || len(warn["per_frame_metadata"]) != 1 {
		t.Errorf("warnings %v", warn)
	}

	items, jobID, wall := e.runJob(sel)
	byName := map[string]map[string]any{}
	var encodeMs, verifyMs, durMs float64
	for _, m := range items {
		byName[m["name"].(string)] = m
		vm, _ := m["vmaf"].(map[string]any)
		t.Logf("%-14s %-8s %-14s q %v, %v → %v bytes, VMAF %v, decoder %v, encode %v ms, verify %v ms, sidecar %v", m["name"], m["state"], m["reason"],
			m["q"], m["size"], m["outSize"], vm, m["decoder"], m["encodeMs"], m["verifyMs"], m["metaSidecar"])
		encodeMs += num(m["encodeMs"])
		verifyMs += num(m["verifyMs"])
		durMs += num(m["durationMs"])
		if m["state"] != "replaced" {
			t.Errorf("%s: %s %s", m["name"], m["state"], m["reason"])
		}
	}
	t.Logf("job: %d videos, %.1f s of video, wall %.1f s, encode %.1f s, verify %.1f s (%.2f× realtime encode)", len(items), durMs/1000,
		wall.Seconds(), encodeMs/1000, verifyMs/1000, durMs/max(encodeMs, 1))

	// The recipe, checked on each output against its original (now in the trash).
	for name, cl := range clips {
		it := byName[name]
		if it == nil || it["state"] != "replaced" {
			continue
		}
		out := filepath.Join(dirs["nas"], "Trip", name)
		orig := filepath.Join(dirs["nas"], it["trashPath"].(string))
		if fileSHA(t, orig) != cl.sha {
			t.Errorf("%s: the trashed original is not the original's bytes", name)
		}
		po, pi := probeJSON(t, out), probeJSON(t, orig)
		v := videoStream(po)
		if !strings.HasPrefix(po.Format.Tags["dev.hfps.hoard.compressed"], "v1, hevc-cq") {
			t.Errorf("%s: marker %q", name, po.Format.Tags["dev.hfps.hoard.compressed"])
		}
		if v.CodecName != "hevc" || v.CodecTag != "hvc1" {
			t.Errorf("%s: %s/%s", name, v.CodecName, v.CodecTag)
		}
		if cl.tenBit != (v.PixFmt == "yuv420p10le") || cl.tenBit && v.Profile != "Main 10" {
			t.Errorf("%s: pix_fmt %s profile %s", name, v.PixFmt, v.Profile)
		}
		vi := videoStream(pi)
		if v.Transfer != vi.Transfer || v.Primaries != vi.Primaries {
			t.Errorf("%s: colour %s/%s vs %s/%s", name, v.Transfer, v.Primaries, vi.Transfer, vi.Primaries)
		}
		if cl.hdr10 {
			a, b := frameHDR(t, orig), frameHDR(t, out)
			if a == "" || a != b {
				t.Errorf("%s: HDR10 metadata not bit-exact:\n%s\n%s", name, a, b)
			}
		}
		for _, s := range po.Streams {
			if s.CodecType == "audio" && cl.pcm && (s.CodecName != "aac" || s.BitRate > 300000) {
				t.Errorf("%s: audio %s at %d b/s", name, s.CodecName, s.BitRate)
			}
			if s.CodecType == "data" && s.CodecTag != "tmcd" {
				t.Errorf("%s: a data stream survived (%s)", name, s.CodecTag)
			}
		}
		for _, k := range []string{"creation_time", "location"} {
			// The restore may write a second atom with the same value (ffprobe joins them with ";").
			if v := pi.Format.Tags[k]; v != "" && !strings.Contains(";"+po.Format.Tags[k]+";", ";"+v+";") {
				t.Errorf("%s: %s %q -> %q", name, k, v, po.Format.Tags[k])
			}
		}
		if cl.camera != nil { // written back into UserData (ffprobe: make, model), and nowhere else
			ft := po.Format.Tags
			if ft["make"] != cl.camera[0] || ft["model"] != cl.camera[1] {
				t.Errorf("%s: camera tags %q %q", name, ft["make"], ft["model"])
			}
			for k, v := range ft {
				if strings.Contains(v, cl.camera[1]) && k != "model" && k != "model-eng" {
					t.Errorf("%s: the model in tag %s = %q", name, k, v)
				}
			}
		}
		if cl.vfr {
			if a, b := ptsList(t, orig), ptsList(t, out); a != b {
				t.Errorf("%s: VFR timestamps differ", name)
			}
		}
		log := c.adminText("compress/items/" + it["id"].(string) + "/log")
		if cl.dataTrack && it["metaSidecar"] != true {
			t.Errorf("%s: no per-frame sidecar; log:\n%s", name, log)
		}
		if cl.cpuDecode && it["decoder"] != "cpu" {
			t.Errorf("%s: decoder %v, want the CPU fallback", name, it["decoder"])
		}
		maxr := regexp.MustCompile(`-maxrate:v:0 (\d+)`).FindStringSubmatch(log)
		if maxr == nil {
			t.Errorf("%s: no -maxrate in the log", name)
		} else if cl.vfr {
			mr, _ := strconv.ParseInt(maxr[1], 10, 64)
			if src := streamRate(pi); src > 0 && mr < src*3/2 {
				t.Errorf("%s: the cap %d does not follow r_frame_rate ÷ avg_frame_rate (source %d b/s)", name, mr, src)
			}
		}
		if strings.Contains(log, dirs["nas"]) || strings.Contains(log, "/mnt/nas") {
			t.Errorf("%s: a path in the log", name)
		}
	}
	// The metadata sidecar of every replaced video is in the data directory, never next to the video; the per-frame
	// data in the data track's only; the camera only an unwritable family carried is expected lost and kept.
	metas, _ := filepath.Glob(filepath.Join(dirs["data"], "compress", "meta", "*", "*.json.gz"))
	if len(metas) != len(clips) {
		t.Errorf("sidecars %v", metas)
	}
	for name, cl := range clips {
		it := byName[name]
		if it == nil || it["outFingerprint"] == nil {
			continue
		}
		hx := strings.TrimPrefix(it["outFingerprint"].(string), "v1:")
		sc := readGzipJSON(t, filepath.Join(dirs["data"], "compress", "meta", hx[:2], hx+".json.gz"))
		if sc["version"] != 2.0 || sc["exiftool"] == nil || (sc["perFrame"] != nil) != cl.dataTrack {
			t.Errorf("%s: sidecar version %v, exiftool %v, perFrame %v", name, sc["version"], sc["exiftool"] != nil, sc["perFrame"] != nil)
		}
		if fmt.Sprint(sc["expectedLost"]) != fmt.Sprint(cl.expected) {
			t.Errorf("%s: expectedLost %v, want %v", name, sc["expectedLost"], cl.expected)
		}
		log := c.adminText("compress/items/" + it["id"].(string) + "/log")
		if cl.editList && !strings.Contains(log, "duration (decoded), streams, picture: ok") {
			t.Errorf("%s: the decoded comparison did not run; log:\n%s", name, log)
		}
		if cl.camera != nil {
			cam, _ := sc["camera"].(map[string]any)
			if cam["make"] != cl.camera[0] || cam["model"] != cl.camera[1] || !strings.Contains(log, "camera identity not restored") {
				t.Errorf("%s: sidecar camera %v", name, cam)
			}
		}
		t.Logf("%-14s sidecar: %d tags not restored, expected lost %v", name, len(asStrings(sc["notRestored"])), sc["expectedLost"])
	}
	if extra, _ := filepath.Glob(filepath.Join(dirs["nas"], "Trip", "*.json*")); len(extra) != 0 {
		t.Errorf("files next to the videos: %v", extra)
	}

	// The walk afterwards: the original trashed with replacedBy, the output with "compressed".
	c.device("POST", "nas/walk", map[string]any{"folders": []string{folder}}, 202)
	first := items[0]
	origItem := first["itemId"].(string)
	waitFor(t, "the walk", func() bool {
		m := c.device("GET", "items/"+origItem+"?include=trash", nil, 200)
		return m["trashed"] != nil && m["compressedTo"] != nil
	})
	outItem := c.device("GET", "items/"+origItem+"?include=trash", nil, 200)["compressedTo"].(string)
	if o := c.device("GET", "items/"+outItem, nil, 200); o["compressed"] == nil {
		t.Errorf("output item %v", o)
	}
	// The camera the sidecar kept is the output item's make and model.
	for name, cl := range clips {
		it := byName[name]
		if cl.camera == nil || it == nil || it["state"] != "replaced" {
			continue
		}
		orig := it["itemId"].(string)
		waitFor(t, "the walk ("+name+")", func() bool {
			return c.device("GET", "items/"+orig+"?include=trash", nil, 200)["compressedTo"] != nil
		})
		o := c.device("GET", "items/"+c.device("GET", "items/"+orig+"?include=trash", nil, 200)["compressedTo"].(string), nil, 200)
		if o["make"] != cl.camera[0] || o["model"] != cl.camera[1] {
			t.Errorf("%s: output item make %v model %v", name, o["make"], o["model"])
		}
	}
	// Restore the first, purge the second.
	c.device("POST", "compress/restore", map[string]any{"itemIds": []string{first["id"].(string)}}, 200)
	waitFor(t, "the restore", func() bool {
		for _, it := range c.device("GET", "compress/items?jobId="+jobID+"&limit=100", nil, 200)["items"].([]any) {
			if m := it.(map[string]any); m["id"] == first["id"] {
				return m["state"] == "restored"
			}
		}
		return false
	})
	if fileSHA(t, filepath.Join(dirs["nas"], "Trip", first["name"].(string))) != clips[first["name"].(string)].sha {
		t.Error("the restored original is not the original")
	}
	second := items[1]
	pr := c.device("POST", "compress/purge", map[string]any{"itemIds": []string{second["id"].(string)}}, 200)
	if s := pr["items"].(map[string]any)[second["id"].(string)].(map[string]any)["state"]; s != "purged" {
		t.Errorf("purge %v", pr)
	}
	if _, err := os.Stat(filepath.Join(dirs["nas"], second["trashPath"].(string))); !os.IsNotExist(err) {
		t.Error("the purged original is still in the trash")
	}
	time.Sleep(11 * time.Second) // a requested walk starts at least 10 s after the last (ADR 0023)
	c.device("POST", "nas/walk", map[string]any{"folders": []string{folder}}, 202)
	waitFor(t, "the walk after restore", func() bool {
		m := c.device("GET", "items/"+origItem, nil, 200)
		return m["deleted"] == false
	})
	purgedItem := second["itemId"].(string)
	waitFor(t, "the walk after purge", func() bool {
		m := c.device("GET", "items/"+purgedItem, nil, 200)
		return m["deleted"] == true && m["replacedBy"] != nil
	})
	size, _ := exec.Command("docker", "image", "inspect", "-f", "{{.Size}}", image).Output()
	t.Logf("image %s: %s bytes", image, strings.TrimSpace(string(size)))
}

type clip struct {
	sha                                           string
	tenBit, hdr10, pcm, vfr, dataTrack, cpuDecode bool
	editList                                      bool     // the video's edit list hides leading frames its header counts
	camera                                        []string // make, model only an unwritable family carries
	expected                                      []string // the sidecar's expectedLost
}

// makeClips writes the synthetic clips (made in work, then copied into dst) and returns them by name.
func makeClips(t *testing.T, work, dst string) map[string]clip {
	t.Helper()
	if err := os.MkdirAll(dst, 0o755); err != nil {
		t.Fatal(err)
	}
	ff := func(out string, args ...string) {
		t.Helper()
		full := append([]string{"-hide_banner", "-loglevel", "error", "-y"}, args...)
		if b, err := exec.Command("ffmpeg", append(full, out)...).CombinedOutput(); err != nil {
			t.Fatalf("ffmpeg %s: %v\n%s", filepath.Base(out), err, b)
		}
	}
	src := "testsrc2=s=1920x1080:r=30:d=12"
	hdr := "log-level=error:repeat-headers=1:colorprim=bt2020:transfer=%s:colormatrix=bt2020nc"
	out := map[string]clip{}
	add := func(name string, c clip, args ...string) {
		p := filepath.Join(work, name)
		ff(p, args...)
		b, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dst, name), b, 0o644); err != nil {
			t.Fatal(err)
		}
		h := sha256.Sum256(b)
		c.sha = hex.EncodeToString(h[:])
		out[name] = c
	}
	// 8-bit H.264 camera clip with PCM audio (MOV), at a camera's bitrate.
	add("CAM_PCM.MOV", clip{pcm: true}, "-f", "lavfi", "-i", src, "-f", "lavfi", "-i", "sine=f=440:d=12:sample_rate=48000",
		"-c:v", "libx264", "-preset", "veryfast", "-b:v", "40M", "-c:a", "pcm_s16be", "-shortest",
		"-metadata", "creation_time=2024-05-01T10:00:00.000000Z", "-metadata", "location=+48.8584+002.2945/")
	// 10-bit HEVC HLG, BT.2020.
	add("DRONE_HLG.MP4", clip{tenBit: true}, "-f", "lavfi", "-i", src, "-c:v", "libx265", "-preset", "veryfast", "-b:v", "50M", "-pix_fmt", "yuv420p10le",
		"-metadata:s:v:0", "encoder=HEVC Coding", "-color_primaries", "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc", "-tag:v", "hvc1",
		"-x265-params", fmt.Sprintf(hdr, "arib-std-b67"))
	// 10-bit HEVC PQ with HDR10 mastering display and content light level.
	add("PHONE_PQ.MP4", clip{tenBit: true, hdr10: true}, "-f", "lavfi", "-i", src, "-c:v", "libx265", "-preset", "veryfast", "-b:v", "50M",
		"-metadata:s:v:0", "encoder=HEVC Coding", "-pix_fmt", "yuv420p10le", "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-tag:v", "hvc1",
		"-x265-params", fmt.Sprintf(hdr, "smpte2084")+":hdr10=1:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400")
	// VFR: r_frame_rate 60, about 30 fps on average (every other frame and a few more dropped).
	add("PHONE_VFR.MP4", clip{vfr: true}, "-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=60:d=12", "-vf", `select='not(mod(n\,2))+eq(n\,7)+eq(n\,301)'`,
		"-fps_mode", "passthrough", "-c:v", "libx264", "-preset", "veryfast", "-b:v", "30M", "-video_track_timescale", "90000",
		"-metadata", "creation_time=2024-05-02T08:30:00.000000Z", "-metadata", "location=-23.5505-046.6333/")
	// (A camera's own encoder tag: ffmpeg's "Lavc…" would make rule 2 skip the HEVC clips as an older compression.)
	// A timed data track (GoPro-style gpmd) the recipe drops and the sidecar keeps.
	base := filepath.Join(work, "base.mp4")
	ff(base, "-f", "lavfi", "-i", src, "-c:v", "libx264", "-preset", "veryfast", "-b:v", "40M")
	meta := filepath.Join(work, "meta.bin")
	payload := bytes.Repeat([]byte("DEVC\x00\x01\x00\x10telemetry-sample"), 2000)
	if err := os.WriteFile(meta, payload, 0o600); err != nil {
		t.Fatal(err)
	}
	add("ACTION_DATA.MOV", clip{dataTrack: true}, "-i", base, "-f", "data", "-i", meta, "-map", "0", "-map", "1", "-c", "copy", "-tag:d", "gpmd", "-f", "mov")
	// H.264 4:4:4: NVDEC cannot decode it, so the encode falls back to the CPU decoder.
	add("SCREEN_444.MP4", clip{cpuDecode: true}, "-f", "lavfi", "-i", src, "-c:v", "libx264", "-preset", "veryfast", "-b:v", "40M", "-pix_fmt", "yuv444p")
	// Samsung-like (ADR 0025 Amendment (N4)): the video's edit list hides 4 leading frames its header counts (the audio
	// longer, so the file's duration is the audio's), and the model only in an smta box.
	sam := filepath.Join(work, "samsung-base.mp4")
	ff(sam, "-f", "lavfi", "-i", src, "-f", "lavfi", "-i", "sine=f=440:d=12.3:sample_rate=48000", "-c:v", "libx264", "-preset", "veryfast",
		"-b:v", "40M", "-g", "60", "-bf", "2", "-c:a", "aac", "-metadata", "creation_time=2024-05-03T12:00:00.000000Z",
		"-metadata", "location=+37.5665+126.9780/")
	addBytes := func(name string, c clip, b []byte) {
		if err := os.WriteFile(filepath.Join(dst, name), b, 0o644); err != nil {
			t.Fatal(err)
		}
		h := sha256.Sum256(b)
		c.sha = hex.EncodeToString(h[:])
		out[name] = c
	}
	b := mp4HideLeadingFrames(t, readFile(t, sam), 4*512) // 30 fps in ffmpeg's 1/15360 track time base
	smta := mp4Box("smta", append([]byte{0, 0, 0, 0}, append(mp4Box("mdln", []byte("SM-S918B")), mp4Box("saut", make([]byte, 4))...)...))
	addBytes("SAMSUNG_EL.MP4", clip{editList: true, camera: []string{"Samsung", "SM-S918B"}, expected: []string{"Samsung:SamsungModel"}},
		mp4AppendToMoov(t, b, mp4Box("udta", smta)))
	// Sony-like: the device only in the NRTM XML of a moov/meta box.
	sony := filepath.Join(work, "sony-base.mp4")
	ff(sony, "-f", "lavfi", "-i", src, "-c:v", "libx264", "-preset", "veryfast", "-b:v", "40M", "-metadata", "creation_time=2024-05-04T09:00:00.000000Z")
	xml := `<?xml version="1.0" encoding="UTF-8"?>` + "\n" + `<NonRealTimeMeta xmlns="urn:schemas-professionalDisc:nonRealTimeMeta:ver.2.00">` +
		`<Device manufacturer="Sony" modelName="ZV-E10" serialNo="0000001"/><CreationDate value="2024-05-04T10:00:00+01:00"/></NonRealTimeMeta>`
	hdlr := mp4Box("hdlr", append(append(make([]byte, 8), []byte("nrtm")...), make([]byte, 13)...))
	nrtm := mp4Box("meta", append(hdlr, mp4Box("xml ", append([]byte{0, 0, 0, 0}, xml...))...))
	addBytes("SONY_NRTM.MP4", clip{camera: []string{"Sony", "ZV-E10"}, expected: []string{"XML:DeviceManufacturer", "XML:DeviceModelName"}},
		mp4AppendToMoov(t, readFile(t, sony), nrtm))
	return out
}

func readFile(t *testing.T, p string) []byte {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func mp4Box(typ string, payload []byte) []byte {
	b := make([]byte, 8, 8+len(payload))
	binary.BigEndian.PutUint32(b, uint32(8+len(payload)))
	copy(b[4:], typ)
	return append(b, payload...)
}

// mp4AppendToMoov appends boxes to a file's moov, which must be its last top-level box (ffmpeg's default layout:
// nothing else moves, so no chunk offset changes).
func mp4AppendToMoov(t *testing.T, b []byte, boxes ...[]byte) []byte {
	t.Helper()
	for i := 0; i+8 <= len(b); {
		size := int(binary.BigEndian.Uint32(b[i:]))
		if size < 8 {
			t.Fatal("unexpected box size")
		}
		if string(b[i+4:i+8]) == "moov" {
			if i+size != len(b) {
				t.Fatal("moov is not the last box")
			}
			out := append([]byte(nil), b...)
			for _, x := range boxes {
				size += len(x)
				out = append(out, x...)
			}
			binary.BigEndian.PutUint32(out[i:], uint32(size))
			return out
		}
		i += size
	}
	t.Fatal("no moov")
	return nil
}

// mp4HideLeadingFrames moves the first track's first edit list entry shift ticks later (the header keeps its frames).
func mp4HideLeadingFrames(t *testing.T, b []byte, shift int64) []byte {
	t.Helper()
	i := bytes.Index(b, []byte("elst"))
	if i < 0 {
		t.Fatal("no edit list")
	}
	out := append([]byte(nil), b...)
	if out[i+4] == 1 {
		o := i + 20
		binary.BigEndian.PutUint64(out[o:], uint64(int64(binary.BigEndian.Uint64(out[o:]))+shift))
	} else {
		o := i + 16
		binary.BigEndian.PutUint32(out[o:], uint32(int32(binary.BigEndian.Uint32(out[o:]))+int32(shift)))
	}
	return out
}

type gpuClient struct {
	t    *testing.T
	base string
	http *http.Client
	key  string
}

func newGPUClient(t *testing.T, port int) *gpuClient {
	jar, _ := cookiejar.New(nil)
	return &gpuClient{t: t, base: fmt.Sprintf("http://127.0.0.1:%d", port), http: &http.Client{Jar: jar, Timeout: 2 * time.Minute}}
}

func (c *gpuClient) waitSetupCode(container string) string {
	c.t.Helper()
	re := regexp.MustCompile(`setupCode=(\S+)`)
	for range 120 {
		out, _ := exec.Command("docker", "logs", container).CombinedOutput()
		if m := re.FindSubmatch(out); m != nil {
			return strings.Trim(string(m[1]), `"`)
		}
		time.Sleep(500 * time.Millisecond)
	}
	c.t.Fatal("no setup code in the log")
	return ""
}

func (c *gpuClient) do(method, url string, body any, header map[string]string) (int, []byte) {
	c.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.base+url, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range header {
		req.Header.Set(k, v)
	}
	res, err := c.http.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res.StatusCode, b
}

func (c *gpuClient) json(method, url string, body any, header map[string]string, want int) map[string]any {
	c.t.Helper()
	code, b := c.do(method, url, body, header)
	if code != want {
		c.t.Fatalf("%s %s: %d %s", method, url, code, b)
	}
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return m
}

func (c *gpuClient) admin(method, path string, body any, want int) map[string]any {
	return c.json(method, "/admin/api/"+path, body, map[string]string{"X-Hoard-Admin": "1"}, want)
}

func (c *gpuClient) adminText(path string) string {
	c.t.Helper()
	code, b := c.do("GET", "/admin/api/"+path, nil, map[string]string{"X-Hoard-Admin": "1"})
	if code != 200 {
		c.t.Fatalf("%s: %d", path, code)
	}
	return string(b)
}

func (c *gpuClient) device(method, path string, body any, want int) map[string]any {
	h := map[string]string{}
	if c.key != "" {
		h["Authorization"] = "Bearer " + c.key
	}
	return c.json(method, "/v1/"+path, body, h, want)
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Minute)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(2 * time.Second)
	}
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func fileSHA(t *testing.T, p string) string {
	t.Helper()
	f, err := os.Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	h := sha256.New()
	_, _ = io.Copy(h, f)
	return hex.EncodeToString(h.Sum(nil))
}

type ffStream struct {
	CodecType string         `json:"codec_type"`
	CodecName string         `json:"codec_name"`
	CodecTag  string         `json:"codec_tag_string"`
	Profile   string         `json:"profile"`
	PixFmt    string         `json:"pix_fmt"`
	Transfer  string         `json:"color_transfer"`
	Primaries string         `json:"color_primaries"`
	BitRateS  string         `json:"bit_rate"`
	BitRate   int64          `json:"-"`
	Attached  map[string]int `json:"disposition"`
}

type ffProbe struct {
	Streams []ffStream `json:"streams"`
	Format  struct {
		Tags map[string]string `json:"tags"`
	} `json:"format"`
}

func probeJSON(t *testing.T, p string) ffProbe {
	t.Helper()
	out, err := exec.Command("ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", p).Output()
	if err != nil {
		t.Fatal(err)
	}
	var o ffProbe
	if err := json.Unmarshal(out, &o); err != nil {
		t.Fatal(err)
	}
	for i := range o.Streams {
		o.Streams[i].BitRate, _ = strconv.ParseInt(o.Streams[i].BitRateS, 10, 64)
	}
	return o
}

func videoStream(p ffProbe) ffStream {
	for _, s := range p.Streams {
		if s.CodecType == "video" && s.Attached["attached_pic"] == 0 {
			return s
		}
	}
	return ffStream{}
}

func streamRate(p ffProbe) int64 { return videoStream(p).BitRate }

// frameHDR is the first frame's HDR10 side data, as ffprobe prints it.
func frameHDR(t *testing.T, p string) string {
	t.Helper()
	out, err := exec.Command("ffprobe", "-v", "error", "-select_streams", "V:0", "-read_intervals", "%+#1", "-show_frames",
		"-show_entries", "frame=side_data_list", "-print_format", "json", p).Output()
	if err != nil {
		t.Fatal(err)
	}
	var o struct {
		Frames []struct {
			SideData []map[string]any `json:"side_data_list"`
		} `json:"frames"`
	}
	_ = json.Unmarshal(out, &o)
	var keep []map[string]any
	for _, f := range o.Frames {
		for _, sd := range f.SideData {
			if typ := fmt.Sprint(sd["side_data_type"]); typ == "Mastering display metadata" || typ == "Content light level metadata" {
				keep = append(keep, sd)
			}
		}
	}
	if len(keep) == 0 {
		return ""
	}
	b, _ := json.Marshal(keep)
	return string(b)
}

// ptsList is the video stream's packet timestamps in order of presentation.
func ptsList(t *testing.T, p string) string {
	t.Helper()
	out, err := exec.Command("ffprobe", "-v", "error", "-select_streams", "V:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", p).Output()
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Fields(string(out))
	var vals []float64
	for _, l := range lines {
		v, _ := strconv.ParseFloat(strings.TrimSuffix(l, ","), 64)
		vals = append(vals, v)
	}
	sortFloats(vals)
	var b strings.Builder
	for _, v := range vals {
		fmt.Fprintf(&b, "%.6f\n", v)
	}
	return b.String()
}

func sortFloats(v []float64) {
	for i := 1; i < len(v); i++ {
		for j := i; j > 0 && v[j] < v[j-1]; j-- {
			v[j], v[j-1] = v[j-1], v[j]
		}
	}
}

func asStrings(v any) []string {
	var out []string
	if l, ok := v.([]any); ok {
		for _, x := range l {
			out = append(out, fmt.Sprint(x))
		}
	}
	return out
}

func num(v any) float64 {
	f, _ := v.(float64)
	return f
}

func readGzipJSON(t *testing.T, p string) map[string]any {
	t.Helper()
	f, err := os.Open(p)
	if err != nil {
		t.Errorf("sidecar: %v", err)
		return map[string]any{}
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		t.Errorf("sidecar: %v", err)
		return map[string]any{}
	}
	var m map[string]any
	if err := json.NewDecoder(zr).Decode(&m); err != nil {
		t.Errorf("sidecar: %v", err)
	}
	return m
}

// gpuEnv is a running -nvidia container on temporary directories, set up, paired, with the NAS folder indexed.
type gpuEnv struct {
	t                   *testing.T
	c                   *gpuClient
	name, image, folder string
	dirs                map[string]string
}

// startGPUEnv builds the image when missing, makes the clips and runs the container (the caller's uid, the GPU through
// CDI, read-only root, 4 CPUs and the given memory limit, the NAS read-only and read-write). It is removed at the end.
func startGPUEnv(t *testing.T, memory string, mk func(t *testing.T, work, dst string) map[string]clip) (*gpuEnv, map[string]clip) {
	if os.Getenv("HOARD_GPU_TEST") != "1" {
		t.Skip("set HOARD_GPU_TEST=1 on an NVIDIA host (builds and runs the -nvidia image)")
	}
	for _, tool := range []string{"docker", "ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Fatalf("%s not found", tool)
		}
	}
	e := &gpuEnv{t: t, image: os.Getenv("HOARD_GPU_IMAGE"), dirs: map[string]string{}}
	if e.image == "" {
		e.image = "hoard-server:s1-test-nvidia"
	}
	if exec.Command("docker", "image", "inspect", e.image).Run() != nil {
		t.Logf("building %s", e.image)
		cmd := exec.Command("docker", "build", "-f", "Dockerfile.nvidia", "--build-arg", "BUILDPLATFORM=linux/amd64", "--build-arg", "JOBS=4", "-t", e.image, ".")
		cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
		if err := cmd.Run(); err != nil {
			t.Fatal(err)
		}
	}
	base := t.TempDir()
	for _, d := range []string{"data", "backup", "nas", "clips"} {
		e.dirs[d] = filepath.Join(base, d)
		if err := os.MkdirAll(e.dirs[d], 0o755); err != nil {
			t.Fatal(err)
		}
	}
	clips := mk(t, e.dirs["clips"], filepath.Join(e.dirs["nas"], "Trip"))

	port := freePort(t)
	e.name = fmt.Sprintf("hoard-gpu-test-%d", time.Now().UnixNano()%1e9)
	args := []string{"run", "-d", "--name", e.name, "--user", fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid()),
		"--device", "nvidia.com/gpu=all", "-e", "NVIDIA_DRIVER_CAPABILITIES=compute,video,utility",
		"--read-only", "--tmpfs", "/tmp", "--security-opt", "no-new-privileges", "--cpus", "4", "--memory", memory,
		"-p", fmt.Sprintf("127.0.0.1:%d:3071", port), "-e", "BACKUP_ROOT=/mnt/backup", "-e", "LOG_FORMAT=text", "-e", "PREVIEW_BACKLOG=none",
		"-v", e.dirs["data"] + ":/data", "-v", e.dirs["backup"] + ":/mnt/backup", "-v", e.dirs["nas"] + ":/mnt/nas:ro", "-v", e.dirs["nas"] + ":/mnt/nas-write",
		e.image}
	if out, err := exec.Command("docker", args...).CombinedOutput(); err != nil {
		t.Fatalf("docker run: %v\n%s", err, out)
	}
	t.Cleanup(func() {
		if t.Failed() {
			out, _ := exec.Command("docker", "logs", "--tail", "200", e.name).CombinedOutput()
			t.Logf("container log:\n%s", out)
		}
		_ = exec.Command("docker", "rm", "-f", e.name).Run()
	})
	c := newGPUClient(t, port)
	e.c = c
	setup := c.waitSetupCode(e.name)
	c.admin("POST", "setup", map[string]any{"code": setup, "password": "a long admin password for the test"}, 200)
	c.admin("PUT", "compress/settings", map[string]any{"pauseWhileBusy": false}, 200) // the desktop shares the GPU
	var gpu map[string]any
	for range 60 { // the start-up check runs in the background
		if gpu = c.admin("GET", "compress", nil, 200)["gpu"].(map[string]any); gpu["checkedAt"].(float64) > 0 {
			break
		}
		time.Sleep(time.Second)
	}
	if gpu["available"] != true {
		t.Fatalf("GPU check failed: %v", gpu)
	}
	t.Logf("GPU: %v, driver %v, codecs %v, VMAF %v, CUDA decode %v", gpu["gpu"], gpu["driver"], gpu["codecs"], gpu["vmaf"], gpu["cudaDecode"])
	tok := c.admin("POST", "pairings", map[string]any{"url": "http://127.0.0.1"}, 201)["token"].(string)
	paired := c.device("POST", "pair", map[string]any{"token": tok, "deviceName": "GPU test", "platform": "android", "appVersion": "2.7.0"}, 201)
	c.key = paired["apiKey"].(string)
	e.folder = c.admin("POST", "nas", map[string]any{"path": "/mnt/nas"}, 201)["id"].(string)
	srv := c.device("GET", "server", nil, 200)
	if !strings.Contains(fmt.Sprint(srv["features"]), "compress") {
		t.Fatalf("features %v", srv["features"])
	}
	// Wait for the walk to index the clips.
	deadline := time.Now().Add(3 * time.Minute)
	for {
		items := c.device("GET", "items?kind=video&limit=100", nil, 200)["items"].([]any)
		if len(items) == len(clips) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("%d of %d videos indexed", len(items), len(clips))
		}
		time.Sleep(2 * time.Second)
	}
	return e, clips
}

// runJob makes a NAS job of the selection and waits until every item settled.
func (e *gpuEnv) runJob(sel map[string]any) ([]map[string]any, string, time.Duration) {
	t, c := e.t, e.c
	start := time.Now()
	job := c.device("POST", "compress/jobs", map[string]any{"target": "nas", "select": sel, "requestId": "gpu-test", "name": "GPU test"}, 201)
	jobID := job["id"].(string)
	var items []map[string]any
	deadline := time.Now().Add(30 * time.Minute)
	for {
		items = items[:0]
		done := true
		for _, it := range c.device("GET", "compress/items?jobId="+jobID+"&limit=100", nil, 200)["items"].([]any) {
			m := it.(map[string]any)
			items = append(items, m)
			switch m["state"] {
			case "replaced", "skipped", "failed", "cancelled":
			default:
				done = false
			}
		}
		if done && len(items) > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("the job did not settle: %v", items)
		}
		time.Sleep(3 * time.Second)
	}
	return items, jobID, time.Since(start)
}

// cgroupFile reads a file of the container's own cgroup (cgroup v2).
func (e *gpuEnv) cgroupFile(name string) string {
	out, _ := exec.Command("docker", "exec", e.name, "cat", "/sys/fs/cgroup/"+name).Output()
	return string(out)
}

// TestGPUMemory measures the memory of a 4K HEVC 10-bit job with CUDA decode, two clips so an encode overlaps a
// verification (ADR 0025 Amendment (N4): 2 GiB let the kernel kill ffmpeg). Opt-in: HOARD_GPU_TEST=1 and HOARD_GPU_MEM=1. The container runs with 8 GiB; its cgroup's
// anonymous memory is sampled every 250 ms (the page cache of the clip is left out: it is reclaimable) and its
// memory.peak read at the end.
func TestGPUMemory(t *testing.T) {
	if os.Getenv("HOARD_GPU_MEM") != "1" {
		t.Skip("set HOARD_GPU_TEST=1 HOARD_GPU_MEM=1 on an NVIDIA host")
	}
	e, _ := startGPUEnv(t, "8g", func(t *testing.T, work, dst string) map[string]clip {
		if err := os.MkdirAll(dst, 0o755); err != nil {
			t.Fatal(err)
		}
		// Two clips, so the second's encode overlaps the first's verification as in a real queue.
		out := map[string]clip{}
		for i, name := range []string{"UHD_10BIT_A.MP4", "UHD_10BIT_B.MP4"} {
			p := filepath.Join(dst, name)
			args := []string{"-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=3840x2160:r=30:d=12", "-f", "lavfi",
				"-i", "sine=f=440:d=12:sample_rate=48000", "-vf", fmt.Sprintf("hue=h=%d", 90*i), "-c:v", "libx265", "-preset", "ultrafast",
				"-b:v", "100M", "-pix_fmt", "yuv420p10le", "-metadata:s:v:0", "encoder=HEVC Coding", "-tag:v", "hvc1",
				"-x265-params", "log-level=error:pools=4", "-c:a", "aac", "-shortest", p}
			if b, err := exec.Command("nice", append([]string{"-n", "19", "ffmpeg"}, args...)...).CombinedOutput(); err != nil {
				t.Fatalf("ffmpeg: %v\n%s", err, b)
			}
			out[name] = clip{tenBit: true}
		}
		return out
	})
	var peakAnon int64
	peakRSS := map[string]int64{} // by process kind, kB
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			for line := range strings.Lines(e.cgroupFile("memory.stat")) {
				if v, ok := strings.CutPrefix(line, "anon "); ok {
					n, _ := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
					peakAnon = max(peakAnon, n)
				}
			}
			out, _ := exec.Command("docker", "exec", e.name, "sh", "-c",
				`for p in /proc/[0-9]*; do r=$(sed -n 's/^VmRSS:[[:space:]]*\([0-9]*\).*/\1/p' $p/status 2>/dev/null); [ -n "$r" ] && echo "$r $(tr '\0' ' ' < $p/cmdline)"; done`).Output()
			for line := range strings.Lines(string(out)) {
				kb, cmd, _ := strings.Cut(strings.TrimSpace(line), " ")
				n, _ := strconv.ParseInt(kb, 10, 64)
				kind := "other"
				switch {
				case strings.Contains(cmd, "libvmaf"):
					kind = "vmaf"
				case strings.Contains(cmd, "_nvenc"):
					kind = "encode"
				case strings.Contains(cmd, "-xerror"):
					kind = "decode check"
				case strings.Contains(cmd, "ffprobe"):
					kind = "ffprobe"
				case strings.Contains(cmd, "exiftool"):
					kind = "exiftool"
				case strings.Contains(cmd, "hoard-server"):
					kind = "server"
				}
				peakRSS[kind] = max(peakRSS[kind], n)
			}
			select {
			case <-stop:
				return
			case <-time.After(250 * time.Millisecond):
			}
		}
	}()
	items, _, wall := e.runJob(map[string]any{"folders": []any{map[string]any{"folderId": e.folder}}})
	close(stop)
	<-done
	peak, _ := strconv.ParseInt(strings.TrimSpace(e.cgroupFile("memory.peak")), 10, 64)
	events := strings.ReplaceAll(strings.TrimSpace(e.cgroupFile("memory.events")), "\n", ", ")
	for _, m := range items {
		t.Logf("%s: %v %v, decoder %v, encode %v ms, verify %v ms, wall %.1f s", m["name"], m["state"], m["reason"], m["decoder"], m["encodeMs"],
			m["verifyMs"], wall.Seconds())
		if m["state"] != "replaced" || m["decoder"] != "cuda" {
			t.Errorf("%s: %v %v decoder %v", m["name"], m["state"], m["reason"], m["decoder"])
		}
	}
	t.Logf("4K HEVC 10-bit job (2 clips), CUDA decode: peak anonymous memory %d MiB, cgroup memory.peak %d MiB (page cache included); events: %s",
		peakAnon>>20, peak>>20, events)
	for kind, kb := range peakRSS {
		t.Logf("  peak RSS %-12s %5d MiB", kind, kb>>10)
	}
}
