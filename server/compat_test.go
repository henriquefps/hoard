package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/server"
	"github.com/henriquefps/hoard/server/internal/store"
)

// fixtureDir is a data directory and backup folder made by the original reveal-sync (Reveal 94fbfe7, sync-server/ at
// 0.5.3) with testdata/compat/reveal-sync-0.5.3/mkfixture: an admin password, two paired devices, photo and version
// records (one edited by the second device) and an uploaded original and smart preview. manifest.json holds what
// that server answered.
const fixtureDir = "testdata/compat/reveal-sync-0.5.3"

type compatManifest struct {
	AdminPassword  string         `json:"adminPassword"`
	PhoneKey       string         `json:"phoneKey"`
	TabletKey      string         `json:"tabletKey"`
	Fingerprint    string         `json:"fingerprint"`
	OriginalSHA256 string         `json:"originalSha256"`
	PreviewSHA256  string         `json:"previewSha256"`
	Changes        map[string]any `json:"changes"`
	Server         map[string]any `json:"server"`
}

func copyTree(t *testing.T, src, dst string) {
	t.Helper()
	err := filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		target := filepath.Join(dst, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0o700)
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		return os.WriteFile(target, b, 0o600)
	})
	if err != nil {
		t.Fatal(err)
	}
}

// TestOpensRevealSyncDataDir: hoard-server opens a reveal-sync data directory as is (ADR 0012): same database file,
// same blob folder, same admin password, same device keys, same change feed, byte-identical blobs.
func TestOpensRevealSyncDataDir(t *testing.T) {
	var m compatManifest
	raw, err := os.ReadFile(filepath.Join(fixtureDir, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	base := t.TempDir()
	dataDir, mnt := filepath.Join(base, "data"), filepath.Join(base, "mnt")
	backup := filepath.Join(mnt, "backup")
	copyTree(t, filepath.Join(fixtureDir, "data-dir"), dataDir)
	copyTree(t, filepath.Join(fixtureDir, "backup"), backup)

	dbPath, legacy := databasePath(dataDir)
	if !legacy || filepath.Base(dbPath) != "reveal-sync.db" {
		t.Fatalf("databasePath = %s, %v; want the reveal-sync file", dbPath, legacy)
	}
	st, err := store.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// The fixture was made in another folder: point the stored backup folder at the copy, as a move would (README).
	if err := st.SetSetting(ctx, "backupRoot", backup); err != nil {
		t.Fatal(err)
	}
	logs := &bytes.Buffer{}
	srv, err := server.New(ctx, server.Config{Version: "compat", Mounts: []string{mnt}, NasInterval: time.Hour,
		Logger: slog.New(slog.NewJSONHandler(logs, nil))}, st)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(logs.String(), "setupCode") {
		t.Fatal("the admin password was lost: the server asks for a setup code again")
	}
	hs := httptest.NewServer(srv.Handler())
	defer hs.Close()

	call := func(key, method, path string, body string) (*http.Response, []byte) {
		t.Helper()
		req, _ := http.NewRequest(method, hs.URL+path, strings.NewReader(body))
		if key != "" {
			req.Header.Set("Authorization", "Bearer "+key)
		}
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res, b
	}

	// Admin login with the reveal-sync password hash.
	jar, _ := cookiejar.New(nil)
	admin := &http.Client{Jar: jar}
	login := func(password string) int {
		js, _ := json.Marshal(map[string]string{"password": password})
		req, _ := http.NewRequest("POST", hs.URL+"/admin/api/login", bytes.NewReader(js))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Hoard-Admin", "1")
		res, err := admin.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if code := login("not the password"); code != 401 {
		t.Fatalf("wrong password: %d", code)
	}
	if code := login(m.AdminPassword); code != 200 {
		t.Fatalf("admin login: %d", code)
	}
	res, err := admin.Get(hs.URL + "/admin/api/devices")
	if err != nil || res.StatusCode != 200 {
		t.Fatalf("devices with the session: %v %v", res, err)
	}
	res.Body.Close()

	// Device keys (rvk1_) still authenticate; the server keeps its id and name.
	if res, _ := call("", "GET", "/v1/server", ""); res.StatusCode != 401 {
		t.Fatalf("no key: %d", res.StatusCode)
	}
	for _, key := range []string{m.PhoneKey, m.TabletKey} {
		if !strings.HasPrefix(key, "rvk1_") {
			t.Fatalf("fixture key %q", key)
		}
		res, b := call(key, "GET", "/v1/server", "")
		if res.StatusCode != 200 {
			t.Fatalf("device key: %d %s", res.StatusCode, b)
		}
		var info map[string]any
		_ = json.Unmarshal(b, &info)
		for _, k := range []string{"api", "serverId", "serverName", "limits", "storageConfigured"} {
			if key == m.PhoneKey && !reflect.DeepEqual(info[k], m.Server[k]) {
				t.Errorf("/v1/server %s = %v, reveal-sync said %v", k, info[k], m.Server[k])
			}
		}
	}

	// ADR 0017: every reveal-sync device is a physical device of its own, with its own id; no app reported yet.
	res, b := call(m.PhoneKey, "GET", "/v1/device", "")
	var dev map[string]any
	if res.StatusCode != 200 || json.Unmarshal(b, &dev) != nil || dev["physicalDevice"].(map[string]any)["id"] != dev["deviceId"] || dev["app"] != nil {
		t.Fatalf("device view after the upgrade: %d %s", res.StatusCode, b)
	}

	// The change feed is the one reveal-sync served.
	res, b = call(m.PhoneKey, "GET", "/v1/changes?limit=500", "")
	if res.StatusCode != 200 {
		t.Fatalf("changes: %d %s", res.StatusCode, b)
	}
	var changes map[string]any
	_ = json.Unmarshal(b, &changes)
	if !reflect.DeepEqual(changes, m.Changes) {
		t.Fatalf("changes differ:\n got %s\nwant %v", b, m.Changes)
	}

	// Blobs download byte-identical from the reveal-sync folder of the backup root.
	blobs := map[string]string{"/v1/originals/" + m.Fingerprint: m.OriginalSHA256, "/v1/blobs/" + m.PreviewSHA256: m.PreviewSHA256}
	for path, sha := range blobs {
		res, b := call(m.TabletKey, "GET", path, "")
		sum := sha256.Sum256(b)
		if res.StatusCode != 200 || hex.EncodeToString(sum[:]) != sha {
			t.Fatalf("%s: %d, sha256 %x, want %s", path, res.StatusCode, sum, sha)
		}
		onDisk, err := os.ReadFile(filepath.Join(backup, "reveal-sync", "blobs", sha[:2], sha[2:4], sha))
		if err != nil || !bytes.Equal(onDisk, b) {
			t.Fatalf("%s is not the file on disk (%v)", path, err)
		}
	}
	res, b = call(m.PhoneKey, "POST", "/v1/fingerprints/lookup", `{"fingerprints":["`+m.Fingerprint+`"]}`)
	if res.StatusCode != 200 || !strings.Contains(string(b), m.Fingerprint) || strings.Contains(string(b), `"missing":["`) {
		t.Fatalf("lookup: %d %s", res.StatusCode, b)
	}

	// Nothing was renamed: no new database file, no new blob folder.
	if _, err := os.Stat(filepath.Join(dataDir, "hoard-server.db")); !os.IsNotExist(err) {
		t.Fatal("a hoard-server.db appeared next to reveal-sync.db")
	}
	entries, _ := os.ReadDir(backup)
	if len(entries) != 1 || entries[0].Name() != "reveal-sync" {
		t.Fatalf("backup folder holds %v", entries)
	}
}

func TestDatabasePath(t *testing.T) {
	dir := t.TempDir()
	if p, legacy := databasePath(dir); legacy || filepath.Base(p) != "hoard-server.db" {
		t.Fatalf("empty dir: %s %v", p, legacy)
	}
	if err := os.WriteFile(filepath.Join(dir, "reveal-sync.db"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if p, legacy := databasePath(dir); !legacy || filepath.Base(p) != "reveal-sync.db" {
		t.Fatalf("reveal-sync dir: %s %v", p, legacy)
	}
	if err := os.WriteFile(filepath.Join(dir, "hoard-server.db"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if p, legacy := databasePath(dir); legacy || filepath.Base(p) != "hoard-server.db" {
		t.Fatalf("both: %s %v", p, legacy)
	}
}
