// mkfixture drives the original reveal-sync to make this compatibility fixture. Kept as a record of how it was made:
// it builds only inside sync-server/ of github.com/henriquefps/reveal at 94fbfe7 (copy it to cmd/mkfixture there).
//
//	go build -o reveal-sync . && go run ./cmd/mkfixture ./reveal-sync <empty dir>   # this fixture
//	go run ./cmd/mkfixture http://127.0.0.1:<port> <dir> <setup code>               # against a running image
//
// testdata is ignored by go build and go vet.
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
	"strings"
	"syscall"
	"time"

	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bin, base := os.Args[1], os.Args[2]
	var addr, code string
	var cmd *exec.Cmd
	if strings.HasPrefix(bin, "http://") { // a running server (e.g. the image): its address and setup code
		addr, code = strings.TrimPrefix(bin, "http://"), os.Args[3]
	} else {
		mnt := filepath.Join(base, "mnt")
		must(os.MkdirAll(filepath.Join(mnt, "backup"), 0o700))
		cmd = exec.Command(bin, "serve", "--port", "0", "--listen", "127.0.0.1", "--data-dir", filepath.Join(base, "data"),
			"--mounts", mnt, "--backup-root", filepath.Join(mnt, "backup"))
		stderr, _ := cmd.StderrPipe()
		must(cmd.Start())
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
	}
	url := "http://" + addr
	jar, _ := cookiejar.New(nil)
	admin := &http.Client{Jar: jar}
	adminCall := func(path string, body any) map[string]any {
		js, _ := json.Marshal(body)
		req, _ := http.NewRequest("POST", url+"/admin/api/"+path, bytes.NewReader(js))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Reveal-Admin", "1")
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
	adminCall("setup", map[string]any{"code": code, "password": password})
	pair := func(name string) (string, string) {
		p := adminCall("pairings", map[string]any{"url": url})
		m := call("", "POST", "/v1/pair", map[string]any{"token": p["token"], "deviceName": name, "platform": "android", "appVersion": "0.5.3"}, "", 201)
		return m["apiKey"].(string), m["deviceId"].(string)
	}
	phoneKey, phoneID := pair("Fixture phone")
	tabletKey, _ := pair("Fixture tablet")

	raw := fingerprint.VectorBytes(65_537, 7)
	fp := fingerprint.Bytes(raw)
	preview := fingerprint.VectorBytes(40_000, 11)
	sha := func(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
	rawSha, previewSha := sha(raw), sha(preview)
	upload := func(b []byte, kind, fpv string) {
		up := call(phoneKey, "POST", "/v1/uploads", map[string]any{"sha256": sha(b), "size": len(b), "kind": kind, "fingerprint": fpv}, "", 201)
		call(phoneKey, "PATCH", "/v1/uploads/"+up["uploadId"].(string), b, "application/offset+octet-stream", 204)
	}
	upload(raw, "original", fp)
	upload(preview, "preview", "")

	photoID, versionID := "11111111-0000-4000-8000-000000000001", "11111111-0000-4000-8000-000000000002"
	photo2 := "11111111-0000-4000-8000-000000000003"
	call(phoneKey, "POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "photo", "id": photoID, "baseRev": 0, "modifiedAt": 1000, "data": map[string]any{"fingerprint": fp, "rating": 4,
			"preview": map[string]any{"sha256": previewSha, "width": 2048, "height": 1365}}},
		map[string]any{"kind": "version", "id": versionID, "baseRev": 0, "modifiedAt": 1000, "data": map[string]any{"photoId": photoID, "position": 0,
			"recipe": map[string]any{"v": 1, "light": map[string]any{"exposure": 0.5}}, "history": []any{}, "cursor": 0}},
		map[string]any{"kind": "photo", "id": photo2, "baseRev": 0, "modifiedAt": 1500, "data": map[string]any{"fingerprint": "v1:" + rawSha, "rating": 2}},
	}}, "", 200)
	res := call(tabletKey, "POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "photo", "id": photo2, "baseRev": 3, "modifiedAt": 2000, "data": map[string]any{"fingerprint": "v1:" + rawSha, "rating": 5, "flag": "pick"}},
	}}, "", 200)
	fmt.Fprintln(os.Stderr, "tablet push:", res)
	pulled := call(phoneKey, "GET", "/v1/changes?limit=500", nil, "", 200)
	serverInfo := call(phoneKey, "GET", "/v1/server", nil, "", 200)

	if cmd != nil {
		must(cmd.Process.Signal(syscall.SIGTERM))
		_ = cmd.Wait()
	}
	time.Sleep(100 * time.Millisecond)
	manifest := map[string]any{
		"createdBy":     "reveal-sync built from github.com/henriquefps/reveal 94fbfe7 (sync-server/ at ef0b8db, 0.5.3)",
		"adminPassword": password, "phoneKey": phoneKey, "phoneId": phoneID, "tabletKey": tabletKey,
		"fingerprint": fp, "originalSha256": rawSha, "previewSha256": previewSha,
		"changes": pulled, "server": serverInfo,
	}
	js, _ := json.MarshalIndent(manifest, "", "  ")
	must(os.WriteFile(filepath.Join(base, "manifest.json"), append(js, '\n'), 0o600))
}
