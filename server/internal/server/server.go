// Package server is hoard-server's HTTP layer: the device API (/v1, ADR 0013) and the setup page (/admin).
package server

import (
	"context"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/henriquefps/hoard/server/internal/auth"
	"github.com/henriquefps/hoard/server/internal/blobstore"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/mounts"
	"github.com/henriquefps/hoard/server/internal/nas"
	"github.com/henriquefps/hoard/server/internal/previews"
	"github.com/henriquefps/hoard/server/internal/ratelimit"
	"github.com/henriquefps/hoard/server/internal/store"
)

// API version and limits (ADR 0013 "Limits").
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
	sessionCookieName = "hoard_server_admin"

	// Accepted from setup pages and browsers of the reveal-sync days (ADR 0012); never sent.
	legacySessionCookieName = "reveal_sync_admin"
	legacyAdminHeader       = "X-Reveal-Admin"
	adminHeader             = "X-Hoard-Admin"
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
	// Video metadata (ADR 0014): the ffprobe binary, how many run at once and how long one may take.
	FFprobe      string
	ProbeWorkers int
	ProbeTimeout time.Duration
	// Gallery previews (ADR 0015); disabled when PreviewsDir is "".
	PreviewsDir string
	// EditsDir keeps Reveal's edited previews (ADR 0020; main sets <data dir>/edits; tests default to "edits" next to
	// PreviewsDir). Data, not a cache: only Reveal can make them again.
	EditsDir       string
	Vips, FFmpeg   string // programs (paths or names in PATH)
	PreviewWorkers int    // image jobs at a time
	ProxyWorkers   int    // video proxy jobs at a time
	PreviewThreads int    // threads per vips/ffmpeg run
	FullCacheBytes int64  // bound of the full-resolution stills kept (ADR 0018; 0 = the default)
	PreviewBacklog []string
	// PreviewGenerator replaces the CPU generator (tests; a GPU worker later).
	PreviewGenerator previews.Generator
	// TrustTailscaleServe (TRUST_TAILSCALE_SERVE=1) lets the LAN/Tailscale-only pages (the gallery, the setup page)
	// answer requests `tailscale serve` proxies: from a TailscaleServePeers address (loopback always), with one
	// tailnet address in X-Forwarded-For and no other proxy's header (ADR 0018 "HTTPS with tailscale serve").
	TrustTailscaleServe bool
	TailscaleServePeers []netip.Addr
}

// Server holds the state behind the handlers.
type Server struct {
	cfg     Config
	store   *store.Store
	roots   mounts.Roots
	log     *slog.Logger
	indexer *nas.Indexer
	prev    *previews.Manager // nil when previews are disabled

	serverID string

	blobMu sync.RWMutex
	blobs  *blobstore.Store // nil until a backup root is chosen

	deviceLimit  *ratelimit.Limiter
	previewLimit *ratelimit.Limiter
	anonLimit    *ratelimit.Limiter
	loginLimit   *ratelimit.Limiter

	setupMu   sync.Mutex
	setupCode string

	uploadLocks sync.Map   // upload id -> *sync.Mutex
	completeMu  sync.Mutex // a preview upload completing vs a preview being dropped
	touchMu     sync.Mutex
	touched     map[string]time.Time

	errMu  sync.Mutex
	errors []ErrorEvent

	web webState // the web gallery (ADR 0018)
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
		deviceLimit: ratelimit.New(30, 120), previewLimit: ratelimit.New(previewRate, previewBurst),
		anonLimit:  ratelimit.New(10.0/60, 10),
		loginLimit: ratelimit.New(5.0/60, 5),
		touched:    map[string]time.Time{},
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
	if _, err := st.EnsureSetting(ctx, settingServerName, "Hoard server"); err != nil {
		return nil, err
	}
	// Once: the culling Reveal synced in its photo records before 0.7.0 becomes culling records (ADR 0016).
	if done, err := st.BridgeExistingRecords(ctx); err != nil {
		return nil, err
	} else if done {
		s.log.Info("culling: photo records bridged")
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
	s.indexer = &nas.Indexer{Store: st, Roots: s.roots, Log: s.log, Interval: cfg.NasInterval,
		Prober: media.NewProber(cfg.FFprobe, cfg.ProbeWorkers, cfg.ProbeTimeout), OpenBlob: s.openBlob}
	if !s.indexer.Prober.Available() {
		s.log.Warn("ffprobe not found: videos are indexed, their metadata is read once it is installed")
	}
	if cfg.PreviewsDir != "" {
		gen := cfg.PreviewGenerator
		if gen == nil {
			cpu := previews.NewCPU(cfg.Vips, cfg.FFmpeg, cfg.PreviewThreads)
			if !cpu.HasVips() || !cpu.HasFFmpeg() {
				s.log.Warn("previews: vips or ffmpeg not found: previews wait until both are installed", "vips", cpu.HasVips(), "ffmpeg", cpu.HasFFmpeg())
			}
			gen = cpu
		}
		backlog := map[string]bool{}
		for _, j := range cfg.PreviewBacklog {
			backlog[j] = true
		}
		if cfg.EditsDir == "" {
			s.cfg.EditsDir = filepath.Join(filepath.Dir(cfg.PreviewsDir), "edits")
		}
		if err := os.MkdirAll(s.cfg.EditsDir, 0o700); err != nil {
			return nil, fmt.Errorf("edits directory: %w", err)
		}
		s.prev, err = previews.New(previews.Config{Dir: cfg.PreviewsDir, ImageWorkers: cfg.PreviewWorkers, ProxyWorkers: cfg.ProxyWorkers,
			FullCacheBytes: cfg.FullCacheBytes,
			Backlog:        backlog, Generator: gen, Store: st, Open: s.openSource, Log: s.log})
		if err != nil {
			return nil, fmt.Errorf("previews directory: %w", err)
		}
	}
	return s, nil
}

// openSource opens a file by fingerprint for the previews: an indexed NAS copy still as indexed, else an uploaded
// original (never written to).
func (s *Server) openSource(ctx context.Context, fp string) (*os.File, error) {
	if sha, ok := strings.CutPrefix(fp, previews.EditSourcePrefix); ok { // an edited preview (ADR 0020)
		return s.openEdit(sha)
	}
	o, err := s.store.LookupOriginal(ctx, fp)
	if err != nil {
		return nil, err
	}
	if o.Source == "backup" {
		return s.openBlob(o.SHA256)
	}
	f, err := s.openNasFile(o)
	if err != nil {
		s.indexer.Trigger()
	}
	return f, err
}

// Run starts the background work (NAS indexing, sweeping) until ctx ends.
func (s *Server) Run(ctx context.Context) {
	go s.indexer.Run(ctx)
	if s.prev != nil {
		s.log.Info("previews", "config", s.prev.String(), "dir", s.prev.Dir())
		s.prev.Run(ctx)
	}
	go func() {
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			s.sweep(ctx)
			s.sweepWeb(ctx)
			s.sweepEdits(ctx)
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

// IndexNow runs one full indexing pass (every NAS folder, then uploaded originals) and returns the statistics of the
// last folder walked. For tests and tools; the server indexes on its own in Run.
func (s *Server) IndexNow(ctx context.Context) nas.Stats {
	s.indexer.All(ctx)
	return s.indexer.LastStats()
}

// openBlob opens a stored blob for the items indexer (uploaded originals).
func (s *Server) openBlob(sha string) (*os.File, error) {
	bs := s.blobStore()
	if bs == nil {
		return nil, fmt.Errorf("no backup root")
	}
	return bs.Open(sha)
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
	s.routesWeb(mux)
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		if s.localRequest(r) { // the web gallery (ADR 0018)
			s.serveWebApp(w, r)
			return
		}
		// Through a proxy the gallery never answers; the setup page does when the admin allowed it (ADR 0013).
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
	device  store.Device
	code    string
	tailnet string // the tailnet address of a request `tailscale serve` proxied (TRUST_TAILSCALE_SERVE)
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

// tailnetV6 is Tailscale's IPv6 range (its IPv4 range is cgnat).
var tailnetV6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")

// localRequest reports a request the LAN/Tailscale-only pages answer: direct from the LAN or Tailscale, or proxied by
// `tailscale serve` for a tailnet address when TRUST_TAILSCALE_SERVE is on.
func (s *Server) localRequest(r *http.Request) bool {
	return directLocal(r) || s.viaTailscaleServe(r)
}

// viaTailscaleServe reports a request `tailscale serve` proxied, and records the tailnet address it was made from.
// tailscale serve connects from this host (loopback; in a container, the bridge's gateway), sets X-Forwarded-For to
// the one tailnet address the request came from (it replaces any value the client sent), X-Forwarded-Proto and
// X-Forwarded-Host, and marks Funnel (public) requests with Tailscale-Funnel-Request: those are refused, as is anything
// carrying another proxy's header (Cloudflare's tunnel sets Cf-Connecting-Ip).
func (s *Server) viaTailscaleServe(r *http.Request) bool {
	if !s.cfg.TrustTailscaleServe {
		return false
	}
	peer, ok := remoteIP(r)
	if !ok || !peer.IsLoopback() && !slices.Contains(s.cfg.TailscaleServePeers, peer) {
		return false
	}
	for _, h := range []string{"Cf-Connecting-Ip", "X-Real-Ip", "Forwarded", "Tailscale-Funnel-Request"} {
		if len(r.Header.Values(h)) > 0 {
			return false
		}
	}
	xff := r.Header.Values("X-Forwarded-For")
	if len(xff) != 1 || strings.Contains(xff[0], ",") {
		return false
	}
	a, err := netip.ParseAddr(strings.TrimSpace(xff[0]))
	if err != nil {
		return false
	}
	if a = a.Unmap(); !cgnat.Contains(a) && !tailnetV6.Contains(a) {
		return false
	}
	info(r).tailnet = a.String()
	return true
}

// publicHost is the host the browser addressed: X-Forwarded-Host behind tailscale serve (it connects to
// 127.0.0.1:3071), else the request's Host. Origin checks compare with it.
func publicHost(r *http.Request) string {
	if info(r).tailnet != "" {
		if h := r.Header.Get("X-Forwarded-Host"); h != "" {
			return h
		}
	}
	return r.Host
}

// ContainerGateway returns the default IPv4 gateway when the server runs in a container (Docker, Podman): there,
// `tailscale serve` on the host reaches a published port from the bridge's gateway address, not from loopback.
func ContainerGateway() (netip.Addr, bool) {
	inContainer := false
	for _, f := range []string{"/.dockerenv", "/run/.containerenv"} {
		if _, err := os.Stat(f); err == nil {
			inContainer = true
		}
	}
	if !inContainer {
		return netip.Addr{}, false
	}
	b, err := os.ReadFile("/proc/net/route")
	if err != nil {
		return netip.Addr{}, false
	}
	return defaultGateway(string(b))
}

// defaultGateway reads the default route's gateway from /proc/net/route (little-endian hex).
func defaultGateway(table string) (netip.Addr, bool) {
	for _, line := range strings.Split(table, "\n")[1:] {
		f := strings.Fields(line)
		if len(f) < 3 || f[1] != "00000000" {
			continue
		}
		b, err := hex.DecodeString(f[2]) // the address as a little-endian number: its bytes reversed
		if err != nil || len(b) != 4 || binary.LittleEndian.Uint32(b) == 0 {
			continue
		}
		return netip.AddrFrom4([4]byte{b[3], b[2], b[1], b[0]}), true
	}
	return netip.Addr{}, false
}

// directLocal reports a request from the LAN/Tailscale that did not pass through a proxy.
func directLocal(r *http.Request) bool {
	ip, ok := remoteIP(r)
	return ok && isLocal(ip) && !viaProxy(r)
}

// clientKey is the rate-limit key of an unauthenticated request: the address the tunnel reports when the TCP peer is
// a local connector, else the TCP peer.
func clientKey(r *http.Request) string {
	if a := info(r).tailnet; a != "" {
		return a
	}
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
