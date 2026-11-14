package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/server"
	"github.com/henriquefps/hoard/server/internal/store"
)

// upgradeDir is a data directory, backup folder and NAS folder made by hoard-server 0.4.0 (0c401bf, schema 2) with
// testdata/compat/hoard-server-0.4.0/mkfixture: an indexed NAS folder (a RAW+JPEG pair with a sidecar, a JPEG, a HEIC,
// hidden files and a video 0.4.0 did not index), two devices, records, an uploaded original and a smart preview.
const upgradeDir = "testdata/compat/hoard-server-0.4.0"

type upgradeManifest struct {
	AdminPassword    string           `json:"adminPassword"`
	PhoneKey         string           `json:"phoneKey"`
	TabletKey        string           `json:"tabletKey"`
	NasPath          string           `json:"nasPath"`
	MTimes           map[string]int64 `json:"mtimes"`
	PhoneFingerprint string           `json:"phoneFingerprint"`
	Changes          map[string]any   `json:"changes"`
	NasFiles         map[string]any   `json:"nasFiles"`
	Lookup           map[string]any   `json:"lookup"`
	Server           map[string]any   `json:"server"`
	Health           map[string]any   `json:"health"`
}

// TestUpgradesHoardServer040DataDir: the items migration upgrades a 0.4.0 data directory in place; everything Reveal
// sees is unchanged before and after the first walk; the walk reads metadata without hashing the photos again and
// makes the items.
func TestUpgradesHoardServer040DataDir(t *testing.T) {
	var m upgradeManifest
	raw, err := os.ReadFile(filepath.Join(upgradeDir, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	base := t.TempDir()
	dataDir, mnt := filepath.Join(base, "data"), filepath.Join(base, "mnt")
	backup, nasDir := filepath.Join(mnt, "backup"), filepath.Join(mnt, "nas")
	copyTree(t, filepath.Join(upgradeDir, "data-dir"), dataDir)
	copyTree(t, filepath.Join(upgradeDir, "backup"), backup)
	copyTree(t, filepath.Join(upgradeDir, "nas"), nasDir)
	for rel, ns := range m.MTimes {
		ts := time.Unix(0, ns)
		if err := os.Chtimes(filepath.Join(nasDir, rel), ts, ts); err != nil {
			t.Fatal(err)
		}
	}
	realNas, _ := filepath.EvalSymlinks(nasDir)
	realBackup, _ := filepath.EvalSymlinks(backup)

	dbPath, legacy := databasePath(dataDir)
	if legacy {
		t.Fatal("a 0.4.0 data directory has hoard-server.db")
	}
	// The fixture was made in another folder: point the stored folders at the copies, as a move would (README).
	db, err := sql.Open("sqlite", "file:"+dbPath)
	if err != nil {
		t.Fatal(err)
	}
	var version int
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 2 {
		t.Fatalf("fixture schema %d %v", version, err)
	}
	if _, err := db.Exec("UPDATE nas_folders SET path = ? WHERE path = ?", realNas, m.NasPath); err != nil {
		t.Fatal(err)
	}
	db.Close()

	st, err := store.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if v, _ := st.SchemaVersion(ctx); v != 7 {
		t.Fatalf("schema after upgrade %d", v)
	}
	if err := st.SetSetting(ctx, "backupRoot", realBackup); err != nil {
		t.Fatal(err)
	}
	srv, err := server.New(ctx, server.Config{Version: "upgrade", Mounts: []string{mnt}, NasInterval: time.Hour,
		Logger: slog.New(slog.NewJSONHandler(io.Discard, nil))}, st)
	if err != nil {
		t.Fatal(err)
	}
	hs := httptest.NewServer(srv.Handler())
	defer hs.Close()
	call := func(key, method, path, body string) map[string]any {
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
		var out map[string]any
		b, _ := io.ReadAll(res.Body)
		if err := json.Unmarshal(b, &out); err != nil || res.StatusCode >= 300 {
			t.Fatalf("%s %s: %d %s", method, path, res.StatusCode, b)
		}
		return out
	}
	lookupBody := func() string {
		var fps []string
		for _, f := range m.Lookup["found"].([]any) {
			fps = append(fps, f.(map[string]any)["fingerprint"].(string))
		}
		for _, f := range m.Lookup["missing"].([]any) {
			fps = append(fps, f.(string))
		}
		js, _ := json.Marshal(map[string]any{"fingerprints": fps})
		return string(js)
	}()
	indexed := false
	revealView := func(when string) {
		t.Helper()
		if got := call(m.PhoneKey, "GET", "/v1/changes?limit=500", ""); !reflect.DeepEqual(got, m.Changes) {
			t.Fatalf("%s: /v1/changes differs:\n got %v\nwant %v", when, got, m.Changes)
		}
		if got := call(m.TabletKey, "GET", "/v1/nas/files?limit=500", ""); !reflect.DeepEqual(got, m.NasFiles) {
			t.Fatalf("%s: /v1/nas/files differs:\n got %v\nwant %v", when, got, m.NasFiles)
		}
		got := call(m.PhoneKey, "POST", "/v1/fingerprints/lookup", lookupBody)
		found := got["found"].([]any)
		if indexed {
			// Addition (ADR 0014): the video 0.4.0 did not index is now found on the NAS (the photos are as before).
			last := found[len(found)-1].(map[string]any)
			if last["fingerprint"] != m.Lookup["missing"].([]any)[0] || last["source"] != "nas" || len(got["missing"].([]any)) != 0 {
				t.Fatalf("%s: lookup %v", when, got)
			}
			found = found[:len(found)-1]
		}
		if !reflect.DeepEqual(any(found), m.Lookup["found"]) {
			t.Fatalf("%s: lookup found %v, want %v", when, found, m.Lookup["found"])
		}
		info := call(m.PhoneKey, "GET", "/v1/server", "")
		for _, k := range []string{"api", "serverId", "serverName", "limits", "storageConfigured", "deviceId"} {
			if !reflect.DeepEqual(info[k], m.Server[k]) {
				t.Fatalf("%s: /v1/server %s = %v, 0.4.0 said %v", when, k, info[k], m.Server[k])
			}
		}
		counts := call("", "GET", "/v1/health", "")["counts"].(map[string]any)
		for k, v := range m.Health["counts"].(map[string]any) {
			if counts[k] != v {
				t.Fatalf("%s: health %s = %v, 0.4.0 said %v", when, k, counts[k], v)
			}
		}
	}
	revealView("before the first walk")

	stats := srv.IndexNow(ctx)
	indexed = true
	// The four photos 0.4.0 indexed are not hashed again; the video and the sidecar are new to the index. Metadata is
	// read for all six.
	wantMeta := 6
	if _, err := exec.LookPath("ffprobe"); err != nil {
		wantMeta = 5 // the video's metadata waits for ffprobe
	}
	if stats.Files != 6 || stats.Hashed != 2 || stats.MetaRead != wantMeta || stats.Removed != 0 {
		t.Fatalf("first walk after the upgrade: %+v", stats)
	}
	revealView("after the first walk")

	// The items: the pair (with its sidecar), the JPEG, the HEIC, the video and the phone's upload.
	feed := call(m.PhoneKey, "GET", "/v1/items/changes?limit=500", "")
	kinds := map[string]int{}
	for _, it := range feed["items"].([]any) {
		kinds[it.(map[string]any)["kind"].(string)]++
	}
	if !reflect.DeepEqual(kinds, map[string]int{"pair": 1, "photo": 3, "video": 1}) {
		t.Fatalf("items %v", kinds)
	}
	look := call(m.PhoneKey, "POST", "/v1/items/lookup", `{"fingerprints":["`+m.PhoneFingerprint+`"]}`)
	phone := look["items"].([]any)[0].(map[string]any)
	if phone["model"] != "Pixel 9" || phone["capture"].(map[string]any)["offset"] != "+02:00" {
		t.Fatalf("uploaded original's item %v", phone)
	}
	// A second walk does nothing.
	if s := srv.IndexNow(ctx); s.Hashed != 0 || s.MetaRead != 0 && wantMeta == 6 || s.Items != 0 {
		t.Fatalf("second walk %+v", s)
	}
	// Reveal keeps writing: a push on the upgraded server.
	res := call(m.PhoneKey, "POST", "/v1/changes", `{"changes":[{"kind":"photo","id":"22222222-0000-4000-8000-000000000001","baseRev":1,
		"modifiedAt":3000,"data":{"fingerprint":"`+m.Changes["changes"].([]any)[0].(map[string]any)["data"].(map[string]any)["fingerprint"].(string)+`","rating":1}}]}`)
	if r := res["results"].([]any)[0].(map[string]any); r["status"] != "applied" {
		t.Fatalf("push after upgrade %v", r)
	}
	if !bytes.Contains(raw, []byte("0c401bf")) {
		t.Fatal("manifest does not name the 0.4.0 commit")
	}
}
