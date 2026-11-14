// Package server is reveal-sync's HTTP layer: the device API (/v1, ADR 0017) and the setup page (/admin).
package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/henriquefps/reveal/sync-server/internal/auth"
	"github.com/henriquefps/reveal/sync-server/internal/blobstore"
	"github.com/henriquefps/reveal/sync-server/internal/mounts"
	"github.com/henriquefps/reveal/sync-server/internal/nas"
	"github.com/henriquefps/reveal/sync-server/internal/ratelimit"
	"github.com/henriquefps/reveal/sync-server/internal/store"
)

// API version and limits (ADR 0017 "Limits").
const (
	APIVersion        = 1
	maxJSONBody       = 8 << 20
	maxRecordData     = 2 << 20
	maxPushChanges    = 500
	maxPullLimit      = 500
	defaultPullLimit  = 200
	maxLookup         = 1000
	chunkSize         = 8 << 20
	maxBlobSize       = 4 << 30
	maxUploadsPerDev  = 32
	pairingTTL        = 10 * time.Minute
	sessionTTL        = 12 * time.Hour
	uploadExpiry      = 7 * 24 * time.Hour
	sessionCookieName = "reveal_sync_admin"
)

// Settings keys.
const (
	settingServerID      = "serverId"
	settingServerName    = "serverName"
	settingBackupRoot    = "backupRoot"
	settingPublicURL     = "publicUrl"
	settingAdminViaProxy = "adminViaProxy"
)

// Config is what main resolves from flags and environment.
type Config struct {
	Version     string
	Mounts      []string
	BackupRoot  string // initial backup root when none is stored yet (optional)
	NasInterval time.Duration
	Logger      *slog.Logger
}

// Server holds the state behind the handlers.
type Server struct {
	cfg     Config
	store   *store.Store
	roots   mounts.Roots
	log     *slog.Logger
	indexer *nas.Indexer

	serverID string

	blobMu sync.RWMutex
	blobs  *blobstore.Store // nil until a backup root is chosen

	deviceLimit *ratelimit.Limiter
	anonLimit   *ratelimit.Limiter
	loginLimit  *ratelimit.Limiter

	setupMu   sync.Mutex
	setupCode string

	uploadLocks sync.Map   // upload id -> *sync.Mutex
	completeMu  sync.Mutex // a preview upload completing vs a preview being dropped
	touchMu     sync.Mutex
	touched     map[string]time.Time

	errMu  sync.Mutex
	errors []ErrorEvent
}

// ErrorEvent is one recent server-side failure, for the status page (no paths, names or keys).
type ErrorEvent struct {
	At    int64  `json:"at"`
	Route string `json:"route"`
	Code  string `json:"code"`
}

// New prepares the server: ids, mount roots, backup root and the first-run setup code.
func New(ctx context.Context, cfg Config, st *store.Store) (*Server, error) {
	s := &Server{
		cfg: cfg, store: st, log: cfg.Logger,
		deviceLimit: ratelimit.New(30, 120),
		anonLimit:   ratelimit.New(10.0/60, 10),
		loginLimit:  ratelimit.New(5.0/60, 5),
		touched:     map[string]time.Time{},
	}
	var missing []string
	s.roots, missing = mounts.New(cfg.Mounts)
	for _, m := range missing {
		s.log.Warn("mount root not found, skipped", "root", m)
	}
	id, err := st.EnsureSetting(ctx, settingServerID, store.NewUUID())
	if err != nil {
		return nil, err
	}
	s.serverID = id
	if _, err := st.EnsureSetting(ctx, settingServerName, "Reveal sync"); err != nil {
		return nil, err
	}
	root, ok, err := st.Setting(ctx, settingBackupRoot)
	if err != nil {
		return nil, err
	}
	if !ok && cfg.BackupRoot != "" {
		root = cfg.BackupRoot
	}
	if root != "" {
		if err := s.setBackupRoot(ctx, root); err != nil {
			s.log.Error("backup root unavailable: uploads are refused until it is back or another is chosen", "root", root, "err", err)
		}
	}
	hash, err := st.AdminPasswordHash(ctx)
	if err != nil {
		return nil, err
	}
	if hash == "" {
		s.setupCode = auth.SetupCode()
		s.log.Warn("first run: open the setup page from your network and enter this one-time setup code", "setupCode", s.setupCode)
	}
	s.indexer = &nas.Indexer{Store: st, Roots: s.roots, Log: s.log, Interval: cfg.NasInterval}
	return s, nil
}

// Run starts the background work (NAS indexing, sweeping) until ctx ends.
func (s *Server) Run(ctx context.Context) {
	go s.indexer.Run(ctx)
	go func() {
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			s.sweep(ctx)
			select {
			case <-ctx.Done():
				return
			case <-t.C:
			}
		}
	}()
}

func (s *Server) sweep(ctx context.Context) {
	if err := s.store.Sweep(ctx); err != nil {
		s.log.Error("sweep", "err", err)
	}
	stale, err := s.store.StaleUploads(ctx, s.store.Now().Add(-uploadExpiry).UnixMilli())
	if err != nil {
		s.log.Error("sweep uploads", "err", err)
		return
	}
	bs := s.blobStore()
	for _, u := range stale {
		if bs != nil {
			_ = bs.Abort(u.ID)
		}
		_ = s.store.DeleteUpload(ctx, u.ID)
	}
	if len(stale) > 0 {
		s.log.Info("expired uploads removed", "count", len(stale))
	}
}

func (s *Server) blobStore() *blobstore.Store {
	s.blobMu.RLock()
	defer s.blobMu.RUnlock()
	return s.blobs
}

// setBackupRoot validates and opens a backup root inside the mount roots and stores it.
func (s *Server) setBackupRoot(ctx context.Context, p string) error {
	real, err := s.roots.Resolve(p)
	if err != nil {
		return err
	}
	if !mounts.Writable(real) {
		return fmt.Errorf("not writable")
	}
	bs, err := blobstore.Open(real)
	if err != nil {
		return err
	}
	s.blobMu.Lock()
	old := s.blobs
	s.blobs = bs
	s.blobMu.Unlock()
	if old != nil {
		old.Close()
	}
	if err := s.store.SetSetting(ctx, settingBackupRoot, real); err != nil {
		return err
	}
	s.log.Info("backup root", "path", real)
	return nil
}

// Handler is the full HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	s.routesAPI(mux)
	s.routesAdmin(mux)
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		if s.adminAllowed(r) {
			http.Redirect(w, r, "/admin/", http.StatusFound)
			return
		}
		writeError(w, http.StatusNotFound, "not_found", "Not found.", nil)
	})
	return s.logRequests(s.recoverPanics(jsonMux(mux)))
}

// jsonMux turns the mux's own plain-text 404 and 405 into the JSON error format.
func jsonMux(mux *http.ServeMux) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h, pattern := mux.Handler(r)
		if pattern != "" {
			mux.ServeHTTP(w, r)
			return
		}
		c := &capture{header: http.Header{}}
		h.ServeHTTP(c, r)
		if c.status == http.StatusMethodNotAllowed {
			if allow := c.header.Get("Allow"); allow != "" {
				w.Header().Set("Allow", allow)
			}
			writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "Method not allowed.", nil)
			return
		}
		writeError(w, http.StatusNotFound, "not_found", "Not found.", nil)
	})
}

type capture struct {
	header http.Header
	status int
}

func (c *capture) Header() http.Header         { return c.header }
func (c *capture) Write(b []byte) (int, error) { return len(b), nil }
func (c *capture) WriteHeader(code int)        { c.status = code }

// --- request context and logging ------------------------------------------------------------------------------

type ctxKey int

const keyInfo ctxKey = 0

// reqInfo is filled by handlers for the access log (the device id, never its key).
type reqInfo struct {
	device store.Device
	code   string
}

func info(r *http.Request) *reqInfo {
	if v, ok := r.Context().Value(keyInfo).(*reqInfo); ok {
		return v
	}
	return &reqInfo{}
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += int64(n)
	return n, err
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// logRequests logs one line per request: the route pattern (never the raw path), status, size, time and device id.
func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ri := &reqInfo{}
		r = r.WithContext(context.WithValue(r.Context(), keyInfo, ri))
		sw := &statusWriter{ResponseWriter: w}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(sw, r)
		route := r.Pattern
		if route == "" {
			route = "unmatched"
		}
		attrs := []any{"method", r.Method, "route", route, "status", sw.status, "bytes", sw.bytes, "ms", time.Since(start).Milliseconds()}
		if ri.device.ID != "" {
			attrs = append(attrs, "device", ri.device.ID)
		}
		if ri.code != "" {
			attrs = append(attrs, "code", ri.code)
		}
		level := slog.LevelInfo
		if sw.status >= 500 {
			level = slog.LevelError
			s.recordError(route, ri.code)
		}
		s.log.Log(r.Context(), level, "request", attrs...)
	})
}

func (s *Server) recordError(route, code string) {
	s.errMu.Lock()
	defer s.errMu.Unlock()
	s.errors = append(s.errors, ErrorEvent{At: time.Now().UnixMilli(), Route: route, Code: code})
	if len(s.errors) > 50 {
		s.errors = s.errors[len(s.errors)-50:]
	}
}

func (s *Server) recentErrors() []ErrorEvent {
	s.errMu.Lock()
	defer s.errMu.Unlock()
	return append([]ErrorEvent{}, s.errors...)
}

func (s *Server) recoverPanics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				if err, ok := v.(error); ok && errors.Is(err, http.ErrAbortHandler) {
					panic(v)
				}
				s.log.Error("panic", "route", r.Pattern, "panic", fmt.Sprint(v))
				info(r).code = "internal"
				writeError(w, http.StatusInternalServerError, "internal", "Internal error.", nil)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// --- JSON helpers ---------------------------------------------------------------------------------------------

type apiError struct {
	Code    string         `json:"code"`
	Message string         `json:"message"`
	Details map[string]any `json:"details,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, message string, details map[string]any) {
	writeJSON(w, status, map[string]apiError{"error": {Code: code, Message: message, Details: details}})
}

// fail writes an error and records its code for the access log.
func fail(w http.ResponseWriter, r *http.Request, status int, code, message string, details map[string]any) {
	info(r).code = code
	writeError(w, status, code, message, details)
}

func (s *Server) internal(w http.ResponseWriter, r *http.Request, err error) {
	s.log.Error("internal error", "route", r.Pattern, "err", err.Error())
	fail(w, r, http.StatusInternalServerError, "internal", "Internal error.", nil)
}

func rateLimited(w http.ResponseWriter, r *http.Request, wait time.Duration) {
	secs := int(wait.Seconds()) + 1
	w.Header().Set("Retry-After", fmt.Sprint(secs))
	fail(w, r, http.StatusTooManyRequests, "rate_limited", "Too many requests.", map[string]any{"retryAfter": secs})
}

// readJSON decodes a JSON body of at most maxJSONBody bytes; on failure it writes the error and returns false.
func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	ct := r.Header.Get("Content-Type")
	if ct != "" && !strings.HasPrefix(strings.ToLower(ct), "application/json") {
		fail(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Send application/json.", nil)
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxJSONBody)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			fail(w, r, http.StatusRequestEntityTooLarge, "too_large", fmt.Sprintf("JSON bodies are limited to %d bytes.", maxJSONBody), nil)
			return false
		}
		fail(w, r, http.StatusBadRequest, "invalid_json", "The body is not valid JSON for this request.", nil)
		return false
	}
	if dec.More() {
		fail(w, r, http.StatusBadRequest, "invalid_json", "Trailing data after the JSON body.", nil)
		return false
	}
	return true
}

// --- network classification -----------------------------------------------------------------------------------

// proxyHeaders are set by Cloudflare Tunnel and reverse proxies: a request carrying one did not come straight from
// the local network, even when the TCP peer (the tunnel connector) is on it.
var proxyHeaders = []string{"Cf-Connecting-Ip", "X-Forwarded-For", "X-Real-Ip", "Forwarded"}

var cgnat = netip.MustParsePrefix("100.64.0.0/10") // Tailscale

func remoteIP(r *http.Request) (netip.Addr, bool) {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if i := strings.IndexByte(host, '%'); i >= 0 {
		host = host[:i]
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return netip.Addr{}, false
	}
	return ip.Unmap(), true
}

func isLocal(ip netip.Addr) bool {
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || cgnat.Contains(ip)
}

func viaProxy(r *http.Request) bool {
	for _, h := range proxyHeaders {
		if r.Header.Get(h) != "" {
			return true
		}
	}
	return false
}

// directLocal reports a request from the LAN/Tailscale that did not pass through a proxy.
func directLocal(r *http.Request) bool {
	ip, ok := remoteIP(r)
	return ok && isLocal(ip) && !viaProxy(r)
}

// clientKey is the rate-limit key of an unauthenticated request: the address the tunnel reports when the TCP peer is
// a local connector, else the TCP peer.
func clientKey(r *http.Request) string {
	ip, ok := remoteIP(r)
	if ok && isLocal(ip) {
		if cf := r.Header.Get("Cf-Connecting-Ip"); cf != "" {
			if a, err := netip.ParseAddr(strings.TrimSpace(cf)); err == nil {
				return a.String()
			}
		}
	}
	if !ok {
		return r.RemoteAddr
	}
	return ip.String()
}
