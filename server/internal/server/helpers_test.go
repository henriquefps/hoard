package server

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/henriquefps/reveal/sync-server/internal/store"
)

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

type harness struct {
	t      *testing.T
	srv    *Server
	h      http.Handler
	st     *store.Store
	mnt    string
	backup string
	nas    string
	logs   *syncBuffer
	cookie *http.Cookie
}

func newHarness(t *testing.T, withBackup bool) *harness {
	t.Helper()
	base := t.TempDir()
	mnt := filepath.Join(base, "mnt")
	backup := filepath.Join(mnt, "backup")
	nasDir := filepath.Join(mnt, "nas")
	for _, d := range []string{backup, nasDir} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	st, err := store.Open(filepath.Join(base, "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	logs := &syncBuffer{}
	cfg := Config{Version: "test", Mounts: []string{mnt}, NasInterval: time.Hour, Logger: slog.New(slog.NewJSONHandler(logs, nil))}
	if withBackup {
		cfg.BackupRoot = backup
	}
	srv, err := New(context.Background(), cfg, st)
	if err != nil {
		t.Fatal(err)
	}
	realMnt, _ := filepath.EvalSymlinks(mnt)
	hs := &harness{t: t, srv: srv, h: srv.Handler(), st: st, mnt: realMnt, backup: filepath.Join(realMnt, "backup"), nas: filepath.Join(realMnt, "nas"), logs: logs}
	t.Cleanup(func() {
		if bs := srv.blobStore(); bs != nil {
			bs.Close()
		}
		st.Close()
	})
	return hs
}

type reqOpt func(*http.Request)

func remote(addr string) reqOpt { return func(r *http.Request) { r.RemoteAddr = addr } }
func header(k, v string) reqOpt { return func(r *http.Request) { r.Header.Set(k, v) } }
func bearer(key string) reqOpt  { return header("Authorization", "Bearer "+key) }
func adminHeaders() reqOpt {
	return func(r *http.Request) { r.Header.Set("X-Reveal-Admin", "1"); r.RemoteAddr = "127.0.0.1:5000" }
}

func (hs *harness) do(method, path string, body any, opts ...reqOpt) *httptest.ResponseRecorder {
	hs.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(b)
	case io.Reader:
		rd = b
	case string:
		rd = strings.NewReader(b)
	default:
		js, _ := json.Marshal(b)
		rd = bytes.NewReader(js)
	}
	r := httptest.NewRequest(method, path, rd)
	r.RemoteAddr = "203.0.113.9:4000" // a public address unless a test says otherwise
	if _, isJSON := body.(map[string]any); isJSON {
		r.Header.Set("Content-Type", "application/json")
	}
	if hs.cookie != nil {
		r.AddCookie(hs.cookie)
	}
	for _, o := range opts {
		o(r)
	}
	w := httptest.NewRecorder()
	hs.h.ServeHTTP(w, r)
	return w
}

func decode(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &m); err != nil {
		t.Fatalf("not JSON (%d): %q", w.Code, w.Body.String())
	}
	return m
}

func errCode(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	m := decode(t, w)
	e, _ := m["error"].(map[string]any)
	c, _ := e["code"].(string)
	return c
}

func expect(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	if w.Code != status {
		t.Fatalf("status %d, want %d: %s", w.Code, status, w.Body.String())
	}
	if code != "" {
		if got := errCode(t, w); got != code {
			t.Fatalf("code %q, want %q", got, code)
		}
	}
}

// signIn sets the admin password with the setup code and keeps the session cookie.
func (hs *harness) signIn() {
	hs.t.Helper()
	w := hs.do("POST", "/admin/api/setup", map[string]any{"code": hs.srv.setupCode, "password": "a long admin password"}, adminHeaders())
	expect(hs.t, w, 200, "")
	for _, c := range w.Result().Cookies() {
		if c.Name == sessionCookieName {
			hs.cookie = c
		}
	}
	if hs.cookie == nil {
		hs.t.Fatal("no session cookie")
	}
}

// pair creates a pairing token as the admin and redeems it as a device; it returns the device key and id.
func (hs *harness) pair(name string) (key, id string) {
	hs.t.Helper()
	if hs.cookie == nil {
		hs.signIn()
	}
	w := hs.do("POST", "/admin/api/pairings", map[string]any{"url": "https://sync.example.test"}, adminHeaders())
	expect(hs.t, w, 201, "")
	token := decode(hs.t, w)["token"].(string)
	saved := hs.cookie
	hs.cookie = nil
	w = hs.do("POST", "/v1/pair", map[string]any{"token": token, "deviceName": name, "platform": "android", "appVersion": "0.4.0"})
	hs.cookie = saved
	expect(hs.t, w, 201, "")
	m := decode(hs.t, w)
	return m["apiKey"].(string), m["deviceId"].(string)
}

func sum(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
