package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
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
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
)

// TestEndToEnd builds the real binary, runs it and drives a scripted client through
// pair -> push edits -> second device pulls -> conflict -> chunked upload, interrupted and resumed -> download.
func TestEndToEnd(t *testing.T) {
	if testing.Short() {
		t.Skip("builds and runs the binary")
	}
	base := t.TempDir()
	bin := filepath.Join(base, "reveal-sync")
	build := exec.Command("go", "build", "-o", bin, ".")
	build.Stderr = os.Stderr
	if err := build.Run(); err != nil {
		t.Fatal(err)
	}
	mnt := filepath.Join(base, "mnt")
	for _, d := range []string{filepath.Join(mnt, "backup"), filepath.Join(mnt, "nas")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	cmd := exec.Command(bin, "serve", "--port", "0", "--listen", "127.0.0.1", "--data-dir", filepath.Join(base, "data"),
		"--mounts", mnt, "--backup-root", filepath.Join(mnt, "backup"))
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })

	var addr, setupCode string
	logs := &bytes.Buffer{}
	lines := bufio.NewScanner(stderr)
	deadline := time.After(20 * time.Second)
	found := make(chan struct{})
	scanned := make(chan struct{})
	go func() {
		defer close(scanned)
		for lines.Scan() {
			line := lines.Text()
			logs.WriteString(line + "\n")
			var m map[string]any
			if json.Unmarshal([]byte(line), &m) == nil {
				if v, ok := m["setupCode"].(string); ok {
					setupCode = v
				}
				if v, ok := m["addr"].(string); ok && m["msg"] == "reveal-sync listening" {
					addr = v
					close(found)
				}
			}
		}
	}()
	select {
	case <-found:
	case <-deadline:
		t.Fatalf("server did not start:\n%s", logs.String())
	}
	baseURL := "http://" + addr
	if setupCode == "" {
		t.Fatal("no setup code in the log")
	}

	// Admin: set the password with the setup code, then make two pairing codes.
	jar, _ := cookiejar.New(nil)
	admin := &http.Client{Jar: jar}
	adminCall := func(method, path string, body any) map[string]any {
		t.Helper()
		js, _ := json.Marshal(body)
		req, _ := http.NewRequest(method, baseURL+"/admin/api/"+path, bytes.NewReader(js))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Reveal-Admin", "1")
		res, err := admin.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var m map[string]any
		_ = json.NewDecoder(res.Body).Decode(&m)
		if res.StatusCode >= 300 {
			t.Fatalf("%s %s: %d %v", method, path, res.StatusCode, m)
		}
		return m
	}
	adminCall("POST", "setup", map[string]any{"code": setupCode, "password": "end to end password"})

	type device struct {
		key, id string
		cursor  string
	}
	call := func(d *device, method, path string, body any, wantStatus int) map[string]any {
		t.Helper()
		var rd io.Reader
		if body != nil {
			js, _ := json.Marshal(body)
			rd = bytes.NewReader(js)
		}
		req, _ := http.NewRequest(method, baseURL+path, rd)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if d != nil {
			req.Header.Set("Authorization", "Bearer "+d.key)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var m map[string]any
		_ = json.NewDecoder(res.Body).Decode(&m)
		if res.StatusCode != wantStatus {
			t.Fatalf("%s %s: %d (want %d) %v", method, path, res.StatusCode, wantStatus, m)
		}
		return m
	}
	pairDevice := func(name string) *device {
		p := adminCall("POST", "pairings", map[string]any{"url": baseURL})
		m := call(nil, "POST", "/v1/pair", map[string]any{"token": p["token"], "deviceName": name, "platform": "android", "appVersion": "e2e"}, 201)
		return &device{key: m["apiKey"].(string), id: m["deviceId"].(string)}
	}
	phone, tablet := pairDevice("Phone"), pairDevice("Tablet")

	// Push edits from the phone; the tablet pulls them.
	photoID, versionID := "aaaaaaaa-0000-4000-8000-000000000001", "aaaaaaaa-0000-4000-8000-000000000002"
	raw := fingerprint.VectorBytes(20<<20+12345, 42) // a 20 MB "RAW"
	fp := fingerprint.Bytes(raw)
	recipe := func(ev float64) map[string]any {
		return map[string]any{"photoId": photoID, "position": 0, "recipe": map[string]any{"v": 1, "light": map[string]any{"exposure": ev}}, "history": []any{}, "cursor": 0}
	}
	res := call(phone, "POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "photo", "id": photoID, "baseRev": 0, "modifiedAt": 1000, "data": map[string]any{"fingerprint": fp, "rating": 4}},
		map[string]any{"kind": "version", "id": versionID, "baseRev": 0, "modifiedAt": 1000, "data": recipe(0.5)},
	}}, 200)
	baseRev := res["results"].([]any)[1].(map[string]any)["rev"].(float64)
	pullAll := func(d *device) []map[string]any {
		var out []map[string]any
		for {
			m := call(d, "GET", "/v1/changes?cursor="+d.cursor+"&limit=1", nil, 200)
			for _, c := range m["changes"].([]any) {
				out = append(out, c.(map[string]any))
			}
			d.cursor = m["cursor"].(string)
			if m["hasMore"] != true {
				return out
			}
		}
	}
	got := pullAll(tablet)
	if len(got) != 2 || got[0]["kind"] != "photo" || got[1]["id"] != versionID {
		t.Fatalf("tablet pulled %v", got)
	}

	// Conflict: both edit the same base; the tablet's later edit wins, the phone's is kept as a copy.
	call(phone, "POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "version", "id": versionID, "baseRev": baseRev, "modifiedAt": 2000, "data": recipe(1.0)}}}, 200)
	r := call(tablet, "POST", "/v1/changes", map[string]any{"changes": []any{
		map[string]any{"kind": "version", "id": versionID, "baseRev": baseRev, "modifiedAt": 3000, "data": recipe(-0.3)}}}, 200)
	result := r["results"].([]any)[0].(map[string]any)
	if result["status"] != "conflict" || result["winner"] != "incoming" {
		t.Fatalf("conflict %v", result)
	}
	var copyFound, winnerFound bool
	for _, c := range pullAll(phone) {
		data, _ := json.Marshal(c["data"])
		if c["id"] == versionID && strings.Contains(string(data), `"exposure":-0.3`) {
			winnerFound = true
		}
		if c["copyOf"] == versionID && strings.Contains(string(data), `"exposure":1`) && c["deviceId"] == phone.id {
			copyFound = true
		}
	}
	if !winnerFound || !copyFound {
		t.Fatal("the phone must see the winner and its own edit as a copy")
	}

	// The original is missing on the server: upload it in chunks, interrupt one, resume.
	lk := call(phone, "POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{fp}}, 200)
	if len(lk["missing"].([]any)) != 1 {
		t.Fatalf("lookup %v", lk)
	}
	h := sha256.Sum256(raw)
	sha := hex.EncodeToString(h[:])
	up := call(phone, "POST", "/v1/uploads", map[string]any{"sha256": sha, "size": len(raw), "kind": "original", "fingerprint": fp}, 201)
	uploadID := up["uploadId"].(string)
	chunk := int(up["chunkSize"].(float64))
	patch := func(offset int, body []byte) *http.Response {
		req, _ := http.NewRequest("PATCH", baseURL+"/v1/uploads/"+uploadID, bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+phone.key)
		req.Header.Set("Content-Type", "application/offset+octet-stream")
		req.Header.Set("Upload-Offset", strconv.Itoa(offset))
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res
	}
	if res := patch(0, raw[:chunk]); res.StatusCode != 204 {
		t.Fatalf("chunk 1: %d", res.StatusCode)
	}
	// Chunk 2 dies after 3 MB: raw TCP, announce a full chunk, send part of it, close.
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Fprintf(conn, "PATCH /v1/uploads/%s HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nContent-Type: application/offset+octet-stream\r\n"+
		"Upload-Offset: %d\r\nContent-Length: %d\r\n\r\n", uploadID, addr, phone.key, chunk, chunk)
	if _, err := conn.Write(raw[chunk : chunk+3<<20]); err != nil {
		t.Fatal(err)
	}
	conn.Close()
	head := func() int {
		req, _ := http.NewRequest("HEAD", baseURL+"/v1/uploads/"+uploadID, nil)
		req.Header.Set("Authorization", "Bearer "+phone.key)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		n, _ := strconv.Atoi(res.Header.Get("Upload-Offset"))
		return n
	}
	offset := 0
	for range 100 { // the server notices the broken connection asynchronously
		if offset = head(); offset > chunk {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if offset != chunk+3<<20 {
		t.Fatalf("after the interrupted chunk the server holds %d bytes, want %d", offset, chunk+3<<20)
	}
	var last *http.Response
	for offset < len(raw) {
		end := min(offset+chunk, len(raw))
		if last = patch(offset, raw[offset:end]); last.StatusCode != 204 {
			t.Fatalf("resume at %d: %d", offset, last.StatusCode)
		}
		offset = end
	}
	if last.Header.Get("Upload-Complete") != "1" {
		t.Fatal("upload not complete")
	}

	// The tablet finds it and downloads it, whole and by range.
	lk = call(tablet, "POST", "/v1/fingerprints/lookup", map[string]any{"fingerprints": []string{fp}}, 200)
	if len(lk["found"].([]any)) != 1 {
		t.Fatalf("lookup after upload %v", lk)
	}
	get := func(rangeHeader string) ([]byte, int) {
		req, _ := http.NewRequest("GET", baseURL+"/v1/originals/"+fp, nil)
		req.Header.Set("Authorization", "Bearer "+tablet.key)
		if rangeHeader != "" {
			req.Header.Set("Range", rangeHeader)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return b, res.StatusCode
	}
	whole, status := get("")
	if status != 200 || !bytes.Equal(whole, raw) {
		t.Fatalf("download %d, %d bytes", status, len(whole))
	}
	part, status := get("bytes=1048576-1048675")
	if status != 206 || !bytes.Equal(part, raw[1<<20:1<<20+100]) {
		t.Fatalf("range %d", status)
	}

	// Revoking the phone locks it out; the tablet keeps working.
	adminCall("POST", "devices/"+phone.id+"/revoke", map[string]any{})
	call(phone, "GET", "/v1/changes", nil, 401)
	call(tablet, "GET", "/v1/server", nil, 200)
	health := call(nil, "GET", "/v1/health", nil, 200)
	if c := health["counts"].(map[string]any); c["blobs"].(float64) != 1 || c["devices"].(float64) != 1 {
		t.Fatalf("health %v", health)
	}

	// Graceful stop.
	if err := cmd.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("exit: %v", err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("no clean shutdown")
	}
	<-scanned
	for _, secret := range []string{phone.key, tablet.key, "end to end password"} {
		if strings.Contains(logs.String(), secret) {
			t.Fatal("a secret reached the logs")
		}
	}
	if fi, err := os.Stat(filepath.Join(base, "data", "reveal-sync.db")); err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("database mode %v %v", fi, err)
	}
}
