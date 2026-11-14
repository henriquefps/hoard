package server

import (
	"context"
	"embed"
	"encoding/base64"
	"errors"
	"io/fs"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"rsc.io/qr"

	"github.com/henriquefps/reveal/sync-server/internal/auth"
	"github.com/henriquefps/reveal/sync-server/internal/blobstore"
	"github.com/henriquefps/reveal/sync-server/internal/mounts"
	"github.com/henriquefps/reveal/sync-server/internal/store"
)

//go:embed web
var webFiles embed.FS

const adminCSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
	"font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

func (s *Server) routesAdmin(mux *http.ServeMux) {
	static, _ := fs.Sub(webFiles, "web")
	files := http.FileServer(http.FS(static))
	page := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy", adminCSP)
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Cache-Control", "no-cache")
		r2 := r.Clone(r.Context())
		r2.URL.Path = strings.TrimPrefix(r.URL.Path, "/admin")
		files.ServeHTTP(w, r2)
	}
	mux.Handle("GET /admin", s.adminGate(http.RedirectHandler("/admin/", http.StatusFound)))
	mux.Handle("GET /admin/{$}", s.adminGate(http.HandlerFunc(page)))
	mux.Handle("GET /admin/assets/", s.adminGate(http.HandlerFunc(page)))

	mux.Handle("GET /admin/api/state", s.adminGate(http.HandlerFunc(s.handleAdminState)))
	mux.Handle("POST /admin/api/setup", s.adminGate(s.csrf(s.handleSetup)))
	mux.Handle("POST /admin/api/login", s.adminGate(s.csrf(s.handleLogin)))
	mux.Handle("POST /admin/api/logout", s.adminGate(s.csrf(s.handleLogout)))

	authed := func(pattern string, h http.HandlerFunc) {
		if strings.HasPrefix(pattern, "GET ") {
			mux.Handle(pattern, s.adminGate(s.session(h)))
			return
		}
		mux.Handle(pattern, s.adminGate(s.csrf(s.session(h))))
	}
	authed("GET /admin/api/status", s.handleStatus)
	authed("PUT /admin/api/settings", s.handleSettings)
	authed("PUT /admin/api/password", s.handlePassword)
	authed("GET /admin/api/devices", s.handleDevices)
	authed("POST /admin/api/devices/{id}/revoke", s.handleRevoke)
	authed("POST /admin/api/pairings", s.handleCreatePairing)
	authed("GET /admin/api/pairings/{id}", s.handlePairingState)
	authed("GET /admin/api/storage", s.handleStorage)
	authed("PUT /admin/api/storage", s.handleSetStorage)
	authed("GET /admin/api/nas", s.handleNas)
	authed("POST /admin/api/nas", s.handleAddNas)
	authed("DELETE /admin/api/nas/{id}", s.handleRemoveNas)
	authed("POST /admin/api/nas/reindex", s.handleReindex)
}

// adminAllowed: the setup page answers direct LAN/Tailscale requests, or any request once the admin allowed proxies.
func (s *Server) adminAllowed(r *http.Request) bool {
	if directLocal(r) {
		return true
	}
	v, _, _ := s.store.Setting(r.Context(), settingAdminViaProxy)
	return v == "true"
}

func (s *Server) adminGate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.adminAllowed(r) {
			fail(w, r, http.StatusNotFound, "not_found", "Not found.", nil)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// csrf: state-changing admin calls carry X-Reveal-Admin: 1 (a cross-site form cannot) and a same-host Origin.
func (s *Server) csrf(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Reveal-Admin") != "1" {
			fail(w, r, http.StatusForbidden, "forbidden", "Missing X-Reveal-Admin header.", nil)
			return
		}
		if o := r.Header.Get("Origin"); o != "" {
			u, err := url.Parse(o)
			if err != nil || u.Host != r.Host {
				fail(w, r, http.StatusForbidden, "forbidden", "Cross-origin request refused.", nil)
				return
			}
		}
		next(w, r)
	}
}

func (s *Server) sessionValid(r *http.Request) bool {
	c, err := r.Cookie(sessionCookieName)
	if err != nil || c.Value == "" || len(c.Value) > 100 {
		return false
	}
	ok, err := s.store.TouchSession(r.Context(), auth.HashToken(c.Value), s.store.Now().Add(sessionTTL).UnixMilli())
	return err == nil && ok
}

func (s *Server) session(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.sessionValid(r) {
			fail(w, r, http.StatusUnauthorized, "unauthorized", "Sign in again.", nil)
			return
		}
		next(w, r)
	}
}

func secureRequest(r *http.Request) bool {
	return r.TLS != nil || (viaProxy(r) && strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https"))
}

func (s *Server) startSession(w http.ResponseWriter, r *http.Request) error {
	token, hash := auth.NewToken()
	if err := s.store.CreateSession(r.Context(), hash, s.store.Now().Add(sessionTTL).UnixMilli()); err != nil {
		return err
	}
	// Secure only over HTTPS: the setup page is plain HTTP on the LAN, where a Secure cookie would never come back.
	http.SetCookie(w, &http.Cookie{Name: sessionCookieName, Value: token, Path: "/admin", HttpOnly: true, //nolint:gosec // see above
		SameSite: http.SameSiteStrictMode, Secure: secureRequest(r)})
	return nil
}

func (s *Server) handleAdminState(w http.ResponseWriter, r *http.Request) {
	hash, err := s.store.AdminPasswordHash(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"setup": hash == "", "signedIn": hash != "" && s.sessionValid(r), "version": s.cfg.Version})
}

func validPassword(p string) bool {
	n := utf8.RuneCountInString(p)
	return utf8.ValidString(p) && n >= 12 && n <= 256
}

func (s *Server) handleSetup(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.loginLimit.Allow("setup:" + clientKey(r)); !ok {
		rateLimited(w, r, wait)
		return
	}
	var req struct {
		Code     string `json:"code"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	s.setupMu.Lock()
	defer s.setupMu.Unlock()
	if s.setupCode == "" {
		fail(w, r, http.StatusConflict, "conflict", "The admin password is already set.", nil)
		return
	}
	if !auth.SameCode(req.Code, s.setupCode) {
		fail(w, r, http.StatusUnauthorized, "unauthorized", "Wrong setup code. It is in the server's log.", nil)
		return
	}
	if !validPassword(req.Password) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The password needs 12 to 256 characters.", nil)
		return
	}
	if err := s.store.CreateAdmin(r.Context(), auth.HashPassword(req.Password)); err != nil {
		if errors.Is(err, store.ErrAdminExists) {
			fail(w, r, http.StatusConflict, "conflict", "The admin password is already set.", nil)
			return
		}
		s.internal(w, r, err)
		return
	}
	s.setupCode = ""
	s.log.Info("admin password set")
	if err := s.startSession(w, r); err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.loginLimit.Allow("login:" + clientKey(r)); !ok {
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
		fail(w, r, http.StatusConflict, "conflict", "Set the admin password first.", nil)
		return
	}
	if len(req.Password) > 1024 || !auth.CheckPassword(req.Password, hash) {
		s.log.Warn("admin sign-in failed")
		fail(w, r, http.StatusUnauthorized, "unauthorized", "Wrong password.", nil)
		return
	}
	if err := s.startSession(w, r); err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookieName); err == nil {
		_ = s.store.DeleteSession(r.Context(), auth.HashToken(c.Value))
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookieName, Value: "", Path: "/admin", MaxAge: -1, HttpOnly: true, //nolint:gosec // clears the cookie
		SameSite: http.SameSiteStrictMode, Secure: secureRequest(r)})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handlePassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Current  string `json:"current"`
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
	if len(req.Current) > 1024 || !auth.CheckPassword(req.Current, hash) {
		fail(w, r, http.StatusUnauthorized, "unauthorized", "Wrong current password.", nil)
		return
	}
	if !validPassword(req.Password) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The password needs 12 to 256 characters.", nil)
		return
	}
	if err := s.store.SetAdminPassword(r.Context(), auth.HashPassword(req.Password)); err != nil {
		s.internal(w, r, err)
		return
	}
	if err := s.startSession(w, r); err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// --- status and settings ------------------------------------------------------------------------------------------

func (s *Server) settings(ctx context.Context) map[string]any {
	name, _, _ := s.store.Setting(ctx, settingServerName)
	public, _, _ := s.store.Setting(ctx, settingPublicURL)
	viaProxy, _, _ := s.store.Setting(ctx, settingAdminViaProxy)
	return map[string]any{"serverName": name, "publicUrl": public, "adminViaProxy": viaProxy == "true"}
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	counts, err := s.store.Counts(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	blobs, err := s.store.BlobBytes(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	folders, err := s.store.NasFolders(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	var folderErrors []map[string]any
	for _, f := range folders {
		if f.Enabled && (f.State == "error" || f.State == "unavailable") {
			folderErrors = append(folderErrors, map[string]any{"folder": f.Name, "state": f.State, "error": f.Error})
		}
	}
	head, _ := s.store.HeadRev(ctx)
	writeJSON(w, http.StatusOK, map[string]any{
		"version": s.cfg.Version, "serverId": s.serverID, "counts": counts, "blobs": blobs, "revision": head,
		"storageConfigured": s.blobStore() != nil, "indexing": s.indexer.Running(), "folderErrors": folderErrors,
		"errors": s.recentErrors(), "settings": s.settings(ctx), "direct": directLocal(r),
	})
}

func (s *Server) handleSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ServerName    *string `json:"serverName"`
		PublicURL     *string `json:"publicUrl"`
		AdminViaProxy *bool   `json:"adminViaProxy"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	ctx := r.Context()
	if req.ServerName != nil {
		name, ok := cleanText(*req.ServerName, 60)
		if !ok || name == "" {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The server name needs 1 to 60 characters.", nil)
			return
		}
		if err := s.store.SetSetting(ctx, settingServerName, name); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if req.PublicURL != nil {
		u, ok := validPublicURL(*req.PublicURL)
		if !ok {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The server address must be an http(s) URL.", nil)
			return
		}
		if err := s.store.SetSetting(ctx, settingPublicURL, u); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if req.AdminViaProxy != nil {
		if !directLocal(r) {
			fail(w, r, http.StatusForbidden, "forbidden", "This setting can only be changed from your local network.", nil)
			return
		}
		v := "false"
		if *req.AdminViaProxy {
			v = "true"
		}
		if err := s.store.SetSetting(ctx, settingAdminViaProxy, v); err != nil {
			s.internal(w, r, err)
			return
		}
		s.log.Info("setting changed", "adminViaProxy", v)
	}
	writeJSON(w, http.StatusOK, s.settings(ctx))
}

func validPublicURL(raw string) (string, bool) {
	raw = strings.TrimRight(strings.TrimSpace(raw), "/")
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || len(raw) > 300 {
		return "", false
	}
	return raw, true
}

// --- devices and pairing ------------------------------------------------------------------------------------------

func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	list, err := s.store.Devices(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": list})
}

func (s *Server) handleRevoke(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such device.", nil)
		return
	}
	if err := s.store.RevokeDevice(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, r, http.StatusNotFound, "not_found", "No such device, or already revoked.", nil)
			return
		}
		s.internal(w, r, err)
		return
	}
	s.log.Info("device revoked", "device", id)
	go s.sweep(context.WithoutCancel(r.Context())) // drop its unfinished uploads
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleCreatePairing(w http.ResponseWriter, r *http.Request) {
	var req struct {
		URL string `json:"url"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	serverURL, ok := validPublicURL(req.URL)
	if !ok {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The server address must be an http(s) URL the phone can reach.", nil)
		return
	}
	ctx := r.Context()
	if err := s.store.SetSetting(ctx, settingPublicURL, serverURL); err != nil {
		s.internal(w, r, err)
		return
	}
	token, hash := auth.NewToken()
	expires := s.store.Now().Add(pairingTTL)
	id, err := s.store.CreatePairing(ctx, hash, expires.UnixMilli())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	q := url.Values{}
	q.Set("v", "1")
	q.Set("url", serverURL)
	q.Set("token", token)
	q.Set("server", s.serverID)
	uri := "revealsync://pair?" + q.Encode()
	code, err := qr.Encode(uri, qr.M)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	code.Scale = 6
	writeJSON(w, http.StatusCreated, map[string]any{
		"id": id, "uri": uri, "token": token, "expiresAt": expires.UnixMilli(),
		"qr": "data:image/png;base64," + base64.StdEncoding.EncodeToString(code.PNG()),
	})
}

func (s *Server) handlePairingState(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such pairing.", nil)
		return
	}
	p, err := s.store.PairingByID(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No such pairing.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	out := map[string]any{"id": p.ID, "expiresAt": p.ExpiresAt, "used": p.UsedAt != nil, "expired": p.UsedAt == nil && p.ExpiresAt <= time.Now().UnixMilli()}
	if p.DeviceID != nil {
		if d, err := s.store.DeviceByID(r.Context(), *p.DeviceID); err == nil {
			out["deviceName"] = d.Name
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// --- storage ---------------------------------------------------------------------------------------------------

func (s *Server) handleStorage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	root, _, err := s.store.Setting(ctx, settingBackupRoot)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	blobs, err := s.store.BlobBytes(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	n, err := s.store.BlobCount(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	out := map[string]any{"backupRoot": root, "available": s.blobStore() != nil, "locked": n > 0, "blobs": blobs,
		"candidates": s.roots.Candidates(), "mountRoots": s.roots.List()}
	if root != "" {
		total, free := mounts.Usage(root)
		out["totalBytes"], out["freeBytes"] = total, free
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleSetStorage(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path string `json:"path"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	ctx := r.Context()
	real, err := s.roots.Resolve(req.Path)
	if err != nil {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Choose one of the folders mounted into the server.", nil)
		return
	}
	current, _, _ := s.store.Setting(ctx, settingBackupRoot)
	n, err := s.store.BlobCount(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if n > 0 && current != "" && filepath.Clean(current) != real {
		fail(w, r, http.StatusConflict, "conflict", "The backup folder holds photos already; move its reveal-sync folder by hand to change it (see the README).", nil)
		return
	}
	if !mounts.Writable(real) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The server cannot write to this folder (read-only mount or permissions).", nil)
		return
	}
	if blobstore.HasAnyBlob(real) && n == 0 {
		fail(w, r, http.StatusConflict, "conflict", "This folder holds another server's reveal-sync data.", nil)
		return
	}
	if err := s.setBackupRoot(ctx, real); err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"backupRoot": real})
}

// --- NAS folders ------------------------------------------------------------------------------------------------

func (s *Server) handleNas(w http.ResponseWriter, r *http.Request) {
	folders, err := s.store.NasFolders(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	enabled := []store.NasFolder{}
	for _, f := range folders {
		if f.Enabled {
			enabled = append(enabled, f)
		}
	}
	// The backup folder is never offered as a NAS folder.
	backup, _, _ := s.store.Setting(r.Context(), settingBackupRoot)
	candidates := []mounts.Candidate{}
	for _, c := range s.roots.Candidates() {
		if c.Path != backup {
			candidates = append(candidates, c)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"folders": enabled, "candidates": candidates, "indexing": s.indexer.Running()})
}

func (s *Server) handleAddNas(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path string `json:"path"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	real, err := s.roots.Resolve(req.Path)
	if err != nil {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Choose one of the folders mounted into the server.", nil)
		return
	}
	if root, _, _ := s.store.Setting(r.Context(), settingBackupRoot); root != "" && real == root {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The backup folder cannot also be a NAS folder.", nil)
		return
	}
	f, err := s.store.AddNasFolder(r.Context(), real, filepath.Base(real))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.log.Info("nas folder added", "folder", f.ID, "path", real)
	s.indexer.Trigger()
	writeJSON(w, http.StatusCreated, f)
}

func (s *Server) handleRemoveNas(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such folder.", nil)
		return
	}
	if err := s.store.RemoveNasFolder(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, r, http.StatusNotFound, "not_found", "No such folder.", nil)
			return
		}
		s.internal(w, r, err)
		return
	}
	s.log.Info("nas folder removed", "folder", id)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleReindex(w http.ResponseWriter, r *http.Request) {
	s.indexer.Trigger()
	writeJSON(w, http.StatusAccepted, map[string]any{"ok": true})
}
