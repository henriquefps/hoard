package server

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/url"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/henriquefps/hoard/server/internal/auth"
	"github.com/henriquefps/hoard/server/internal/ratelimit"
	"github.com/henriquefps/hoard/server/internal/store"
	"github.com/henriquefps/hoard/server/internal/webui"
)

// The web gallery (ADR 0018): a browser app served at / and its API under /web/, for computers on the LAN or
// Tailscale only. Reveal's /v1 and the setup page's /admin are unchanged.
//
//	GET    /web/session                       {signedIn, setup, version}
//	POST   /web/session      {password}       sign in with the admin password (argon2id) → session cookie
//	DELETE /web/session                       sign out
//	GET    /web/library?cursor=               the compact index of every live item, or the changes since cursor
//	GET    /web/folders                       NAS folders and their directories with item counts
//	GET    /web/items/{itemId}                one item (as /v1) plus its culling record, edited flag and folder
//	GET    /web/items/{itemId}/exif           exposure read from the item's still
//	GET    /web/items/{itemId}/preview|poster|proxy    as /v1 (ADR 0015)
//	GET    /web/items/{itemId}/full               the still at full resolution: the file, or a JPEG made on demand (202)
//	GET    /web/items/{itemId}/files/{fingerprint}[/{name}]  one member file (?download=1: as an attachment)
//	GET    /web/items/{itemId}/edits/{versionId}[/{name}]    Reveal's uploaded edit of a version (ADR 0020)
//	POST   /web/culling      as POST /v1/culling, written by the session's writer id "web:<16 hex>"
//	POST   /web/downloads    {items: [ids]}  → {url}: a zip of the items' files, valid 10 minutes
//	GET    /web/downloads/{token}[/{name}]    the zip, streamed

const (
	webCookieName = "hoard_web"
	webHeader     = "X-Hoard-Web"
	webSessionTTL = 7 * 24 * time.Hour
	webCSP        = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; " +
		"media-src 'self' blob:; connect-src 'self'; font-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; " +
		"frame-ancestors 'none'"
)

type webState struct {
	once  sync.Once
	index *libraryIndex

	dlMu      sync.Mutex
	downloads map[string]webDownload

	limit *ratelimit.Limiter
}

func (s *Server) webInit() *webState {
	s.web.once.Do(func() {
		s.web.index = newLibraryIndex(s.store)
		s.web.downloads = map[string]webDownload{}
		s.web.limit = ratelimit.New(60, 240)
	})
	return &s.web
}

func (s *Server) routesWeb(mux *http.ServeMux) {
	s.webInit()
	gated := s.webGate(http.HandlerFunc(s.serveWebApp))
	// The app's own routes (client-side) and its files.
	for _, p := range []string{"GET /folders", "GET /folders/{rest...}", "GET /items/{rest...}", "GET /assets/{file...}",
		"GET /favicon.svg", "GET /manifest.webmanifest"} {
		mux.Handle(p, gated)
	}

	mux.Handle("GET /web/session", s.webGate(http.HandlerFunc(s.handleWebSession)))
	mux.Handle("POST /web/session", s.webGate(s.webCSRF(s.handleWebLogin)))
	mux.Handle("DELETE /web/session", s.webGate(s.webCSRF(s.handleWebLogout)))

	authed := func(pattern string, h http.HandlerFunc) {
		if strings.HasPrefix(pattern, "GET ") {
			mux.Handle(pattern, s.webGate(s.webSessionAuth(h)))
			return
		}
		mux.Handle(pattern, s.webGate(s.webCSRF(s.webSessionAuth(h))))
	}
	authed("GET /web/library", s.handleWebLibrary)
	authed("GET /web/folders", s.handleWebFolders)
	authed("GET /web/items/{itemId}", s.handleWebItem)
	authed("GET /web/items/{itemId}/exif", s.handleWebExif)
	authed("GET /web/items/{itemId}/preview", s.handlePreview)
	authed("GET /web/items/{itemId}/poster", s.handlePoster)
	authed("GET /web/items/{itemId}/proxy", s.handleProxy)
	authed("GET /web/items/{itemId}/full", s.handleWebFull)
	authed("GET /web/items/{itemId}/files/{fingerprint}", s.handleWebFile)
	authed("GET /web/items/{itemId}/files/{fingerprint}/{name}", s.handleWebFile)
	authed("GET /web/items/{itemId}/edits/{versionId}", s.handleEditGet)
	authed("GET /web/items/{itemId}/edits/{versionId}/{name}", s.handleEditGet)
	authed("POST /web/culling", s.handleCullingPush)
	authed("POST /web/downloads", s.handleWebDownloadCreate)
	authed("GET /web/downloads/{token}", s.handleWebDownload)
	authed("GET /web/downloads/{token}/{name}", s.handleWebDownload)
}

// serveWebApp serves the gallery's built files; paths without an extension (the app's own routes) get index.html.
func (s *Server) serveWebApp(w http.ResponseWriter, r *http.Request) {
	files, built := webui.Files()
	static := http.FileServer(http.FS(files))
	h := w.Header()
	h.Set("Content-Security-Policy", webCSP)
	h.Set("X-Frame-Options", "DENY")
	if !built {
		h.Set("Content-Type", "text/html; charset=utf-8")
		h.Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "<!doctype html><meta charset=utf-8><title>Hoard</title><p>This hoard-server was built without "+
			"its web gallery (run <code>bun run build</code> in <code>server/web</code>). The setup page is at <a href=/admin/>/admin/</a>.</p>")
		return
	}
	r2 := r.Clone(r.Context())
	if strings.HasPrefix(r.URL.Path, "/assets/") {
		// Vite names assets by content hash: cache them for good.
		h.Set("Cache-Control", "public, max-age=31536000, immutable")
	} else {
		h.Set("Cache-Control", "no-cache")
		if path.Ext(r.URL.Path) == "" {
			r2.URL.Path = "/" // client-side routes get the app
		}
	}
	static.ServeHTTP(w, r2)
}

// webGate: the gallery answers direct LAN/Tailscale requests only (the setup page's address check, ADR 0013 "Admin"),
// and, with TRUST_TAILSCALE_SERVE=1, requests `tailscale serve` proxied for a tailnet address — never a request that
// came through a tunnel or another proxy, whatever the setup page's own proxy setting says (ADR 0018).
func (s *Server) webGate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.localRequest(r) {
			fail(w, r, http.StatusNotFound, "not_found", "Not found.", nil)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// webCSRF: a state-changing gallery call carries X-Hoard-Web: 1 (a cross-site form cannot set it) and, when present, a
// same-host Origin; the cookie is SameSite=Strict as well.
func (s *Server) webCSRF(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get(webHeader) != "1" {
			fail(w, r, http.StatusForbidden, "forbidden", "Missing "+webHeader+" header.", nil)
			return
		}
		if o := r.Header.Get("Origin"); o != "" {
			u, err := url.Parse(o)
			if err != nil || u.Host != publicHost(r) {
				fail(w, r, http.StatusForbidden, "forbidden", "Cross-origin request refused.", nil)
				return
			}
		}
		next(w, r)
	}
}

func webToken(r *http.Request) string {
	if c, err := r.Cookie(webCookieName); err == nil && c.Value != "" && len(c.Value) <= 100 {
		return c.Value
	}
	return ""
}

// webWriter returns the culling writer id of the request's gallery session ("" when not signed in).
func (s *Server) webWriter(r *http.Request) (string, error) {
	token := webToken(r)
	if token == "" {
		return "", nil
	}
	id, err := s.store.TouchWebSession(r.Context(), auth.HashToken(token), s.store.Now().Add(webSessionTTL).UnixMilli())
	if errors.Is(err, store.ErrNotFound) {
		return "", nil
	}
	return id, err
}

// webSessionAuth: a signed-in gallery session, as a device named by its writer id (the culling handlers and the access
// log read info(r).device), with its own rate limit (previews use the preview bucket's size).
func (s *Server) webSessionAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := s.webWriter(r)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		if id == "" {
			fail(w, r, http.StatusUnauthorized, "unauthorized", "Sign in again.", nil)
			return
		}
		info(r).device = store.Device{ID: id, Name: "Web gallery", Platform: "web"}
		limit := s.web.limit
		if strings.HasSuffix(r.Pattern, "/preview") || strings.HasSuffix(r.Pattern, "/poster") || strings.HasSuffix(r.Pattern, "/proxy") ||
			strings.HasSuffix(r.Pattern, "/full") {
			limit = s.previewLimit
		}
		if ok, wait := limit.Allow(id); !ok {
			rateLimited(w, r, wait)
			return
		}
		next(w, r)
	}
}

func (s *Server) handleWebSession(w http.ResponseWriter, r *http.Request) {
	hash, err := s.store.AdminPasswordHash(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	id, err := s.webWriter(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"signedIn": id != "", "setup": hash == "", "version": s.cfg.Version,
		"writer": id, "previews": s.prev != nil})
}

func (s *Server) handleWebLogin(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.loginLimit.Allow("web:" + clientKey(r)); !ok {
		rateLimited(w, r, wait)
		return
	}
	var req struct {
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	hash, err := s.store.AdminPasswordHash(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if hash == "" {
		fail(w, r, http.StatusConflict, "conflict", "Set the admin password on the setup page first.", nil)
		return
	}
	if len(req.Password) > 1024 || !auth.CheckPassword(req.Password, hash) {
		s.log.Warn("web gallery sign-in failed")
		fail(w, r, http.StatusUnauthorized, "unauthorized", "Wrong password.", nil)
		return
	}
	token, idHash := auth.NewToken()
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		s.internal(w, r, err)
		return
	}
	writer := "web:" + hex.EncodeToString(b[:])
	if err := s.store.CreateWebSession(r.Context(), idHash, writer, s.store.Now().Add(webSessionTTL).UnixMilli()); err != nil {
		s.internal(w, r, err)
		return
	}
	// Secure over HTTPS (tailscale serve): on plain HTTP on the LAN a Secure cookie would never come back.
	http.SetCookie(w, &http.Cookie{Name: webCookieName, Value: token, Path: "/web/", HttpOnly: true, //nolint:gosec // see above
		SameSite: http.SameSiteStrictMode, Secure: secureRequest(r), MaxAge: int(webSessionTTL.Seconds())})
	s.log.Info("web gallery signed in", "writer", writer)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "writer": writer})
}

func (s *Server) handleWebLogout(w http.ResponseWriter, r *http.Request) {
	if token := webToken(r); token != "" {
		_ = s.store.DeleteWebSession(r.Context(), auth.HashToken(token))
	}
	http.SetCookie(w, &http.Cookie{Name: webCookieName, Value: "", Path: "/web/", MaxAge: -1, HttpOnly: true, //nolint:gosec // clears the cookie
		SameSite: http.SameSiteStrictMode, Secure: secureRequest(r)})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// writeCompressed answers JSON, gzipped when the client accepts it (the library index of a large library is
// megabytes of repetitive JSON; gzip makes it about a tenth).
func writeCompressed(w http.ResponseWriter, r *http.Request, body []byte) {
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	h.Add("Vary", "Accept-Encoding")
	if len(body) < 1024 || !strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(body)
		return
	}
	var buf bytes.Buffer
	zw, _ := gzip.NewWriterLevel(&buf, gzip.BestSpeed)
	_, _ = zw.Write(body)
	_ = zw.Close()
	h.Set("Content-Encoding", "gzip")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(buf.Bytes())
}

// sweepWeb drops expired download links.
func (s *Server) sweepWeb(_ context.Context) {
	now := time.Now()
	s.web.dlMu.Lock()
	defer s.web.dlMu.Unlock()
	for k, d := range s.web.downloads {
		if now.After(d.expires) {
			delete(s.web.downloads, k)
		}
	}
}
