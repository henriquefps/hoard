// mkfixture drives hoard-server 0.4.0 (the commit before items, 0c401bf) to make this upgrade fixture: a data
// directory with an indexed NAS folder (photos, a pair, a sidecar, hidden files and a video 0.4.0 ignores), two paired
// devices, records, an uploaded original and a smart preview. Kept as a record of how it was made:
//
//	git worktree add /tmp/hs040 0c401bf && (cd /tmp/hs040/server && go build -o /tmp/hoard-server-0.4.0 .)
//	go run ./testdata/compat/hoard-server-0.4.0/mkfixture /tmp/hoard-server-0.4.0 <empty dir>
//
// It needs ffmpeg for the video. The NAS files are synthetic (internal/media/mediatest); their mtimes are fixed
// (manifest.json "mtimes") so a copy can restore them.
package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bin, base := os.Args[1], os.Args[2]
	mnt := filepath.Join(base, "mnt")
	nas := filepath.Join(mnt, "nas")
	must(os.MkdirAll(filepath.Join(mnt, "backup"), 0o700))

	// NAS files.
	shot := func(u uint64, dt string) mediatest.Shot {
		return mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", Lens: "FE 35mm F1.8", DateTime: dt, SubSec: "25", Offset: "+02:00",
			Width: 7008, Height: 4672, Lat: 46.5197, Lon: 6.6323, Unique: u}
	}
	clip := filepath.Join(base, "clip.mp4")
	must(exec.Command("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=25:duration=1", "-pix_fmt", "yuv420p",
		"-c:v", "libx264", "-metadata", "creation_time=2024-05-02T08:00:00Z", clip).Run())
	video, err := os.ReadFile(clip)
	must(err)
	must(os.Remove(clip))
	files := map[string][]byte{
		"2024/DSC00001.ARW":     mediatest.RAW(shot(1, "2024:05:01 10:00:00")),
		"2024/DSC00001.JPG":     mediatest.JPEG(shot(2, "2024:05:01 10:00:00"), ""),
		"2024/DSC00001.ARW.xmp": []byte(`<x:xmpmeta xmlns:x="adobe:ns:meta/"/>`),
		"2024/DSC00002.JPG":     mediatest.JPEG(shot(3, "2024:05:01 10:05:00"), ""),
		"2024/IMG_0003.HEIC":    mediatest.HEIF(shot(4, "2024:05:01 10:10:00"), ""),
		"2024/._DSC00001.ARW":   []byte("AppleDouble"),
		"2024/.DS_Store":        []byte("Bud1"),
		"clips/VID_0004.mp4":    video,
	}
	mtime := time.Date(2024, 5, 3, 12, 0, 0, 0, time.UTC)
	mtimes := map[string]int64{}
	for rel, b := range files {
		p := filepath.Join(nas, rel)
		must(os.MkdirAll(filepath.Dir(p), 0o700))
		must(os.WriteFile(p, b, 0o600))
		must(os.Chtimes(p, mtime, mtime))
		mtimes[rel] = mtime.UnixNano()
	}

	cmd := exec.Command(bin, "serve", "--port", "0", "--listen", "127.0.0.1", "--data-dir", filepath.Join(base, "data-dir"),
		"--mounts", mnt, "--backup-root", filepath.Join(mnt, "backup"))
	stderr, _ := cmd.StderrPipe()
	must(cmd.Start())
	var addr, code string
	sc := bufio.NewScanner(stderr)
	for sc.Scan() {
		var m map[string]any
		if json.Unmarshal(sc.Bytes(), &m) == nil {
			if v, ok := m["setupCode"].(string); ok {
				code = v
			}
			if v, ok := m["addr"].(string); ok {
				addr = v
				break
			}
		}
	}
	go func() { _, _ = io.Copy(io.Discard, stderr) }()
	url := "http://" + addr
	jar, _ := cookiejar.New(nil)
	admin := &http.Client{Jar: jar}
	adminCall := func(method, path string, body any) map[string]any {
		var rd io.Reader
		if body != nil {
			js, _ := json.Marshal(body)
			rd = bytes.NewReader(js)
		}
		req, _ := http.NewRequest(method, url+"/admin/api/"+path, rd)
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Hoard-Admin", "1")
		res, err := admin.Do(req)
		must(err)
		defer res.Body.Close()
		var m map[string]any
		_ = json.NewDecoder(res.Body).Decode(&m)
		if res.StatusCode >= 300 {
			panic(fmt.Sprint(path, res.StatusCode, m))
		}
		return m
	}
	call := func(key, method, path string, body any, ct string, want int) map[string]any {
		var rd io.Reader
		switch b := body.(type) {
		case nil:
		case []byte:
			rd = bytes.NewReader(b)
		default:
			js, _ := json.Marshal(b)
			rd = bytes.NewReader(js)
			ct = "application/json"
		}
		req, _ := http.NewRequest(method, url+path, rd)
		if ct != "" {
			req.Header.Set("Content-Type", ct)
		}
		if key != "" {
			req.Header.Set("Authorization", "Bearer "+key)
		}
		if method == "PATCH" {
			req.Header.Set("Upload-Offset", "0")
		}
		res, err := http.DefaultClient.Do(req)
		must(err)
		defer res.Body.Close()
		var m map[string]any
		_ = json.NewDecoder(res.Body).Decode(&m)
		if res.StatusCode != want {
			panic(fmt.Sprint(method, path, res.StatusCode, m))
		}
		return m
	}
	const password = "fixture admin password"
	adminCall("POST", "setup", map[string]any{"code": code, "password": password})
	adminCall("POST", "nas", map[string]any{"path": nas})
	for range 100 { // wait for the first walk
		time.Sleep(100 * time.Millisecond)
		m := adminCall("GET", "nas", nil)
		folders := m["folders"].([]any)
		if len(folders) == 1 && folders[0].(map[string]any)["state"] == "ready" && m["indexing"] == false {
			break
		}
	}
	pair := func(name string) (string, string) {
		p := adminCall("POST", "pairings", map[string]any{"url": url})
		m := call("", "POST", "/v1/pair", map[string]any{"token": p["token"], "deviceName": name, "platform": "android", "appVersion": "0.6.0"}, "", 201)
		return m["apiKey"].(string), m["deviceId"].(string)
	}
	phoneKey, phoneID := pair("Fixture phone")
	tabletKey, _ := pair("Fixture tablet")

	sha := func(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
	upload := func(b []byte, kind, fpv string) {
		up := call(phoneKey, "POST", "/v1/uploads", map[string]any{"sha256": sha(b), "size": len(b), "kind": kind, "fingerprint": fpv}, "", 201)
		call(phoneKey, "PATCH", "/v1/uploads/"+up["uploadId"].(string), b, "application/offset+octet-stream", 204)
	}
	phonePhoto := mediatest.JPEG(mediatest.Shot{Make: "Google", Model: "Pixel 9", DateTime: "2024:05:04 18:30:00", Offset: "+02:00",
		Width: 4080, Height: 3072, Unique: 99}, "")
	phoneFp := fingerprint.Bytes(phonePhoto)
	preview := fingerprint.VectorBytes(40_000, 11)
	upload(phonePhoto, "original", phoneFp)
	upload(preview, "preview", "")

	nasRaw := fingerprint.Bytes(files["2024/DSC00001.ARW"])
	photoID, versionID, photo2 := "22222222-0000-4000-8000-000000000001", "22222222-0000-4000-8000-000000000002", "22222222-0000-4000-8000-000000000003"
	call(phoneKey, "POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "photo", "id": photoID, "baseRev": 0, "modifiedAt": 1000, "data": map[string]any{"fingerprint": nasRaw, "rating": 4,
			"files":   []any{map[string]any{"fingerprint": fingerprint.Bytes(files["2024/DSC00001.JPG"]), "role": "rendered", "name": "DSC00001.JPG"}},
			"preview": map[string]any{"sha256": sha(preview), "width": 2048, "height": 1365}}},
		map[string]any{"kind": "version", "id": versionID, "baseRev": 0, "modifiedAt": 1000, "data": map[string]any{"photoId": photoID, "position": 0,
			"recipe": map[string]any{"v": 1}, "history": []any{}, "cursor": 0}},
		map[string]any{"kind": "photo", "id": photo2, "baseRev": 0, "modifiedAt": 1500, "data": map[string]any{"fingerprint": phoneFp, "rating": 2}},
	}}, "", 200)
	call(tabletKey, "POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "photo", "id": photo2, "baseRev": 3, "modifiedAt": 2000, "data": map[string]any{"fingerprint": phoneFp, "rating": 5}},
	}}, "", 200)
	pulled := call(phoneKey, "GET", "/v1/changes?limit=500", nil, "", 200)
	nasFeed := call(phoneKey, "GET", "/v1/nas/files?limit=500", nil, "", 200)
	lookup := call(phoneKey, "POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{nasRaw, phoneFp,
		fingerprint.Bytes(files["clips/VID_0004.mp4"])}}, "", 200)
	serverInfo := call(phoneKey, "GET", "/v1/server", nil, "", 200)
	health := call("", "GET", "/v1/health", nil, "", 200)

	must(cmd.Process.Signal(syscall.SIGTERM))
	_ = cmd.Wait()
	time.Sleep(100 * time.Millisecond)
	manifest := map[string]any{
		"createdBy":     "hoard-server 0.4.0 built from github.com/henriquefps/hoard 0c401bf (server/)",
		"adminPassword": password, "phoneKey": phoneKey, "phoneId": phoneID, "tabletKey": tabletKey,
		"nasPath": nas, "mtimes": mtimes, "phoneFingerprint": phoneFp, "phoneSha256": sha(phonePhoto), "previewSha256": sha(preview),
		"changes": pulled, "nasFiles": nasFeed, "lookup": lookup, "server": serverInfo, "health": health,
	}
	js, _ := json.MarshalIndent(manifest, "", "  ")
	must(os.WriteFile(filepath.Join(base, "manifest.json"), append(js, '\n'), 0o600))
}
