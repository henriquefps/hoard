package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/henriquefps/reveal/sync-server/internal/auth"
	"github.com/henriquefps/reveal/sync-server/internal/blobstore"
	"github.com/henriquefps/reveal/sync-server/internal/fingerprint"
	"github.com/henriquefps/reveal/sync-server/internal/store"
)

func (s *Server) routesAPI(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/health", s.handleHealth)
	mux.HandleFunc("POST /v1/pair", s.handlePair)
	mux.HandleFunc("GET /v1/server", s.device(s.handleServerInfo))
	mux.HandleFunc("GET /v1/devices", s.device(s.handleDeviceList))
	mux.HandleFunc("GET /v1/changes", s.device(s.handlePull))
	mux.HandleFunc("POST /v1/changes", s.device(s.handlePush))
	mux.HandleFunc("POST /v1/fingerprints/lookup", s.device(s.handleLookup))
	mux.HandleFunc("GET /v1/originals/{fingerprint}", s.device(s.handleOriginal))
	mux.HandleFunc("GET /v1/blobs/{sha256}", s.device(s.handleBlob))
	mux.HandleFunc("DELETE /v1/blobs/{sha256}", s.device(s.handleBlobDelete))
	mux.HandleFunc("POST /v1/uploads", s.device(s.handleUploadCreate))
	mux.HandleFunc("HEAD /v1/uploads/{id}", s.device(s.handleUploadHead))
	mux.HandleFunc("PATCH /v1/uploads/{id}", s.device(s.handleUploadPatch))
	mux.HandleFunc("DELETE /v1/uploads/{id}", s.device(s.handleUploadDelete))
	mux.HandleFunc("GET /v1/nas/files", s.device(s.handleNasFiles))
}

// device authenticates a device key and applies the per-device rate limit.
func (s *Server) device(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		bearer, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		keyID, hash, err := auth.ParseKey(strings.TrimSpace(bearer))
		if !ok || err != nil {
			s.unauthorized(w, r)
			return
		}
		d, err := s.store.DeviceByKeyID(r.Context(), keyID)
		if errors.Is(err, store.ErrNotFound) {
			s.unauthorized(w, r)
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		if !auth.Equal(d.KeyHash, hash) || d.RevokedAt != nil {
			s.unauthorized(w, r)
			return
		}
		info(r).device = d
		if ok, wait := s.deviceLimit.Allow(d.ID); !ok {
			rateLimited(w, r, wait)
			return
		}
		s.touch(r.Context(), d.ID, false)
		next(w, r)
	}
}

func (s *Server) unauthorized(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.anonLimit.Allow("auth:" + clientKey(r)); !ok {
		rateLimited(w, r, wait)
		return
	}
	w.Header().Set("WWW-Authenticate", `Bearer realm="reveal-sync"`)
	fail(w, r, http.StatusUnauthorized, "unauthorized", "Missing, invalid or revoked device key.", nil)
}

// touch updates last-seen at most once a minute per device (sync always).
func (s *Server) touch(ctx context.Context, id string, sync bool) {
	s.touchMu.Lock()
	last := s.touched[id]
	now := time.Now()
	due := sync || now.Sub(last) > time.Minute
	if due {
		s.touched[id] = now
	}
	s.touchMu.Unlock()
	if due {
		if err := s.store.TouchDevice(ctx, id, sync); err != nil {
			s.log.Warn("touch device", "err", err)
		}
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	c, err := s.store.Counts(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "version": s.cfg.Version, "api": APIVersion, "counts": c})
}

func limits() map[string]any {
	return map[string]any{
		"maxJsonBody": maxJSONBody, "maxRecordData": maxRecordData, "maxPushChanges": maxPushChanges,
		"maxPullLimit": maxPullLimit, "maxLookup": maxLookup, "chunkSize": chunkSize, "maxBlobSize": int64(maxBlobSize),
		"maxUploadsPerDevice": maxUploadsPerDev, "requestsPerSecond": 30, "requestBurst": 120,
	}
}

func (s *Server) handleServerInfo(w http.ResponseWriter, r *http.Request) {
	name, _, _ := s.store.Setting(r.Context(), settingServerName)
	writeJSON(w, http.StatusOK, map[string]any{
		"serverId": s.serverID, "serverName": name, "version": s.cfg.Version, "api": APIVersion,
		"deviceId": info(r).device.ID, "limits": limits(), "storageConfigured": s.blobStore() != nil,
	})
}

// handleDeviceList lists the paired devices that are not revoked: id, name and platform only (the app names the device
// of a conflict copy with it; ADR 0017 "Other endpoints").
func (s *Server) handleDeviceList(w http.ResponseWriter, r *http.Request) {
	devices, err := s.store.Devices(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	out := make([]map[string]any, 0, len(devices))
	for _, d := range devices {
		if d.RevokedAt != nil {
			continue
		}
		out = append(out, map[string]any{"id": d.ID, "name": d.Name, "platform": d.Platform})
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": out})
}

// --- pairing ----------------------------------------------------------------------------------------------------

func cleanText(v string, max int) (string, bool) {
	v = strings.TrimSpace(v)
	if !utf8.ValidString(v) || utf8.RuneCountInString(v) > max {
		return "", false
	}
	for _, r := range v {
		if r < 0x20 || r == 0x7f {
			return "", false
		}
	}
	return v, true
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.anonLimit.Allow("pair:" + clientKey(r)); !ok {
		rateLimited(w, r, wait)
		return
	}
	var req struct {
		Token      string `json:"token"`
		DeviceName string `json:"deviceName"`
		Platform   string `json:"platform"`
		AppVersion string `json:"appVersion"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	name, ok1 := cleanText(req.DeviceName, 100)
	platform, ok2 := cleanText(req.Platform, 40)
	appVersion, ok3 := cleanText(req.AppVersion, 40)
	if !ok1 || !ok2 || !ok3 || name == "" || req.Token == "" || len(req.Token) > 100 {
		fail(w, r, http.StatusBadRequest, "invalid_request", "token and deviceName are required; names are at most 100 characters.", nil)
		return
	}
	key, keyID, keyHash := auth.NewKey()
	d, err := s.store.RedeemPairing(r.Context(), auth.HashToken(req.Token), store.Device{
		Name: name, Platform: platform, AppVersion: appVersion, KeyID: keyID, KeyHash: keyHash,
	})
	if errors.Is(err, store.ErrInvalidToken) {
		fail(w, r, http.StatusUnauthorized, "invalid_pairing_token", "The pairing code is unknown, used or expired. Show a new QR code.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	info(r).device = d
	s.log.Info("device paired", "device", d.ID, "platform", platform)
	serverName, _, _ := s.store.Setting(r.Context(), settingServerName)
	writeJSON(w, http.StatusCreated, map[string]any{"deviceId": d.ID, "apiKey": key, "serverId": s.serverID, "serverName": serverName})
}

// --- change feed --------------------------------------------------------------------------------------------------

// Cursors are "<first 8 characters of the server id>:<revision>" (records) or ":n<revision>" (NAS files); clients
// treat them as opaque strings.
func (s *Server) cursor(prefix string, rev int64) string {
	return s.serverID[:8] + ":" + prefix + strconv.FormatInt(rev, 10)
}

func (s *Server) parseCursor(c, prefix string, head int64) (int64, bool) {
	if c == "" {
		return 0, true
	}
	sid, rest, ok := strings.Cut(c, ":")
	if !ok || sid != s.serverID[:8] || !strings.HasPrefix(rest, prefix) {
		return 0, false
	}
	v, err := strconv.ParseInt(rest[len(prefix):], 10, 64)
	if err != nil || v < 0 || v > head || strconv.FormatInt(v, 10) != rest[len(prefix):] {
		return 0, false
	}
	return v, true
}

func pageLimit(r *http.Request) (int, bool) {
	l := r.URL.Query().Get("limit")
	if l == "" {
		return defaultPullLimit, true
	}
	n, err := strconv.Atoi(l)
	if err != nil || n < 1 {
		return 0, false
	}
	return min(n, maxPullLimit), true
}

func (s *Server) handlePull(w http.ResponseWriter, r *http.Request) {
	head, err := s.store.HeadRev(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	since, ok := s.parseCursor(r.URL.Query().Get("cursor"), "", head)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued. Pull from the start (empty cursor).", nil)
		return
	}
	limit, ok := pageLimit(r)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "limit must be a positive integer.", nil)
		return
	}
	recs, more, err := s.store.Changes(r.Context(), since, limit)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	last := since
	if len(recs) > 0 {
		last = recs[len(recs)-1].Rev
	}
	s.touch(r.Context(), info(r).device.ID, true)
	writeJSON(w, http.StatusOK, map[string]any{"changes": recs, "cursor": s.cursor("", last), "hasMore": more})
}

var (
	uuidPattern    = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	settingPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)
)

type pushChange struct {
	Kind       string          `json:"kind"`
	ID         string          `json:"id"`
	BaseRev    *int64          `json:"baseRev"`
	ModifiedAt *int64          `json:"modifiedAt"`
	Deleted    bool            `json:"deleted"`
	Data       json.RawMessage `json:"data"`
}

// validateChange checks one pushed change (ADR 0017 "Records") and returns it ready for the store.
func validateChange(c pushChange) (store.Change, string) {
	out := store.Change{Kind: c.Kind, ID: c.ID, Deleted: c.Deleted}
	switch c.Kind {
	case store.KindPhoto, store.KindVersion, store.KindPreset:
		if !uuidPattern.MatchString(c.ID) {
			return out, "id must be a lower-case UUID"
		}
	case store.KindSetting:
		if !settingPattern.MatchString(c.ID) {
			return out, "a setting id is [a-z0-9][a-z0-9._-]{0,63}"
		}
	default:
		return out, "kind must be photo, version, preset or setting"
	}
	if c.BaseRev == nil || *c.BaseRev < 0 {
		return out, "baseRev is required (0 for a new record)"
	}
	if c.ModifiedAt == nil || *c.ModifiedAt <= 0 || *c.ModifiedAt > 1<<53 {
		return out, "modifiedAt (Unix milliseconds) is required"
	}
	out.BaseRev, out.ModifiedAt = *c.BaseRev, *c.ModifiedAt
	if c.Deleted {
		return out, ""
	}
	if len(c.Data) > maxRecordData {
		return out, fmt.Sprintf("data is limited to %d bytes", maxRecordData)
	}
	var obj map[string]json.RawMessage
	if len(c.Data) == 0 || json.Unmarshal(c.Data, &obj) != nil || obj == nil {
		return out, "data must be a JSON object (or deleted: true)"
	}
	str := func(field string) (string, bool) {
		var v string
		raw, ok := obj[field]
		return v, ok && json.Unmarshal(raw, &v) == nil
	}
	switch c.Kind {
	case store.KindPhoto:
		if fp, ok := str("fingerprint"); !ok || !fingerprint.Valid(fp) {
			return out, "a photo needs data.fingerprint (v1: + 64 lower-case hex)"
		}
	case store.KindVersion:
		if id, ok := str("photoId"); !ok || !uuidPattern.MatchString(id) {
			return out, "a version needs data.photoId (a lower-case UUID)"
		}
	}
	var buf bytes.Buffer
	if err := json.Compact(&buf, c.Data); err != nil {
		return out, "data is not valid JSON"
	}
	out.Data = buf.Bytes()
	return out, ""
}

func (s *Server) handlePush(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Changes []pushChange `json:"changes"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Changes) == 0 || len(req.Changes) > maxPushChanges {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_change", fmt.Sprintf("Send 1 to %d changes.", maxPushChanges), nil)
		return
	}
	changes := make([]store.Change, len(req.Changes))
	for i, c := range req.Changes {
		v, problem := validateChange(c)
		if problem != "" {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_change", problem, map[string]any{"index": i})
			return
		}
		changes[i] = v
	}
	results, err := s.store.Push(r.Context(), info(r).device.ID, changes)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.touch(r.Context(), info(r).device.ID, true)
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

// --- originals and blobs -------------------------------------------------------------------------------------------

func (s *Server) handleLookup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Fingerprints []string `json:"fingerprints"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Fingerprints) > maxLookup {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("At most %d fingerprints per lookup.", maxLookup), nil)
		return
	}
	type found struct {
		Fingerprint string `json:"fingerprint"`
		Size        int64  `json:"size"`
		Source      string `json:"source"`
		SHA256      string `json:"sha256,omitempty"`
	}
	out := struct {
		Found   []found  `json:"found"`
		Missing []string `json:"missing"`
	}{Found: []found{}, Missing: []string{}}
	for i, fp := range req.Fingerprints {
		if !fingerprint.Valid(fp) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Fingerprints are v1: + 64 lower-case hex.", map[string]any{"index": i})
			return
		}
	}
	for _, fp := range req.Fingerprints {
		o, err := s.store.LookupOriginal(r.Context(), fp)
		if errors.Is(err, store.ErrNotFound) {
			out.Missing = append(out.Missing, fp)
			continue
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		out.Found = append(out.Found, found{fp, o.Size, o.Source, o.SHA256})
	}
	writeJSON(w, http.StatusOK, out)
}

func serveFile(w http.ResponseWriter, r *http.Request, f *os.File, etag string) {
	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("ETag", `"`+etag+`"`)
	http.ServeContent(w, r, "", time.Time{}, f)
}

func (s *Server) handleBlob(w http.ResponseWriter, r *http.Request) {
	sha := r.PathValue("sha256")
	if !blobstore.ValidSHA256(sha) {
		fail(w, r, http.StatusNotFound, "not_found", "No such blob.", nil)
		return
	}
	bs := s.blobStore()
	if bs == nil {
		fail(w, r, http.StatusNotFound, "not_found", "No such blob.", nil)
		return
	}
	if _, err := s.store.BlobBySHA(r.Context(), sha); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, r, http.StatusNotFound, "not_found", "No such blob.", nil)
			return
		}
		s.internal(w, r, err)
		return
	}
	f, err := bs.Open(sha)
	if err != nil {
		s.internal(w, r, fmt.Errorf("blob on record but not readable: %w", err))
		return
	}
	defer f.Close()
	serveFile(w, r, f, sha)
}

// handleBlobDelete drops a smart preview this device uploaded once no live photo record names it (ADR 0017 "Dropping
// a smart preview"): its row first (one transaction with the reference check), then its file. Originals are never
// dropped by a device.
func (s *Server) handleBlobDelete(w http.ResponseWriter, r *http.Request) {
	sha := r.PathValue("sha256")
	if !blobstore.ValidSHA256(sha) {
		fail(w, r, http.StatusNotFound, "not_found", "No such blob.", nil)
		return
	}
	bs := s.blobStore()
	if bs == nil {
		fail(w, r, http.StatusNotFound, "not_found", "No such blob.", nil)
		return
	}
	// Serialised with preview upload completions: a preview completed again right now must not lose its file.
	s.completeMu.Lock()
	defer s.completeMu.Unlock()
	err := s.store.DropPreview(r.Context(), sha, info(r).device.ID)
	switch {
	case errors.Is(err, store.ErrNotFound):
		fail(w, r, http.StatusNotFound, "not_found", "No such blob.", nil)
		return
	case errors.Is(err, store.ErrNotPreview):
		fail(w, r, http.StatusForbidden, "forbidden", "Only smart previews can be dropped.", map[string]any{"reason": "original"})
		return
	case errors.Is(err, store.ErrNotUploader):
		fail(w, r, http.StatusForbidden, "forbidden", "Another device uploaded this preview.", map[string]any{"reason": "other_device"})
		return
	case errors.Is(err, store.ErrReferenced):
		fail(w, r, http.StatusConflict, "conflict", "A photo record still names this preview; push it first.", map[string]any{"reason": "referenced"})
		return
	case err != nil:
		s.internal(w, r, err)
		return
	}
	if err := bs.Remove(sha); err != nil {
		s.internal(w, r, fmt.Errorf("blob dropped but its file stays: %w", err))
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleOriginal(w http.ResponseWriter, r *http.Request) {
	fp := r.PathValue("fingerprint")
	if !fingerprint.Valid(fp) {
		fail(w, r, http.StatusNotFound, "not_found", "No file with this fingerprint.", nil)
		return
	}
	o, err := s.store.LookupOriginal(r.Context(), fp)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No file with this fingerprint.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if o.Source == "backup" {
		bs := s.blobStore()
		if bs == nil {
			fail(w, r, http.StatusServiceUnavailable, "unavailable", "The backup folder is not available.", nil)
			return
		}
		f, err := bs.Open(o.SHA256)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		defer f.Close()
		serveFile(w, r, f, fp)
		return
	}
	f, err := s.openNasFile(o)
	if err != nil {
		s.indexer.Trigger()
		w.Header().Set("Retry-After", "60")
		fail(w, r, http.StatusServiceUnavailable, "unavailable", "The NAS file is not available right now (moved, changed or the share is offline).", nil)
		return
	}
	defer f.Close()
	serveFile(w, r, f, fp)
}

// openNasFile opens an indexed NAS file through its folder's os.Root, and only if it is still what was indexed.
func (s *Server) openNasFile(o store.Original) (*os.File, error) {
	dir, err := s.roots.Resolve(o.FolderPath)
	if err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	f, err := root.Open(o.RelPath)
	if err != nil {
		return nil, err
	}
	fi, err := f.Stat()
	if err != nil || !fi.Mode().IsRegular() || fi.Size() != o.Size || fi.ModTime().UnixNano() != o.ModTime {
		f.Close()
		return nil, fmt.Errorf("changed since indexed")
	}
	return f, nil
}

// --- uploads -----------------------------------------------------------------------------------------------------

func (s *Server) uploadResponse(u store.Upload, status string) map[string]any {
	return map[string]any{
		"status": status, "uploadId": u.ID, "offset": u.Offset, "chunkSize": chunkSize,
		"expiresAt": time.UnixMilli(u.UpdatedAt).Add(uploadExpiry).UnixMilli(),
	}
}

func (s *Server) handleUploadCreate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		SHA256      string `json:"sha256"`
		Size        int64  `json:"size"`
		Kind        string `json:"kind"`
		Fingerprint string `json:"fingerprint"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	switch {
	case !blobstore.ValidSHA256(req.SHA256):
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "sha256 must be 64 lower-case hex characters.", nil)
		return
	case req.Size <= 0 || req.Size > maxBlobSize:
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("size must be 1 to %d bytes.", int64(maxBlobSize)), nil)
		return
	case req.Kind != store.BlobOriginal && req.Kind != store.BlobPreview:
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "kind must be original or preview.", nil)
		return
	case req.Kind == store.BlobOriginal && !fingerprint.Valid(req.Fingerprint):
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "An original needs its v1 fingerprint.", nil)
		return
	case req.Kind == store.BlobPreview && req.Fingerprint != "":
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "A preview has no fingerprint.", nil)
		return
	}
	bs := s.blobStore()
	if bs == nil {
		fail(w, r, http.StatusServiceUnavailable, "storage_not_configured", "Choose a backup folder on the server's setup page first.", nil)
		return
	}
	ctx := r.Context()
	dev := info(r).device
	if b, err := s.store.BlobBySHA(ctx, req.SHA256); err == nil && bs.Has(req.SHA256) {
		if b.Size != req.Size {
			fail(w, r, http.StatusUnprocessableEntity, "checksum_mismatch", "A blob with this sha256 has another size.", nil)
			return
		}
		if req.Fingerprint != "" {
			if err := s.registerFingerprint(ctx, bs, b, req.Fingerprint, dev.ID); err != nil {
				if errors.Is(err, blobstore.ErrFingerprint) {
					fail(w, r, http.StatusUnprocessableEntity, "checksum_mismatch", "The stored blob does not have this fingerprint.", nil)
					return
				}
				s.internal(w, r, err)
				return
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "exists"})
		return
	} else if err != nil && !errors.Is(err, store.ErrNotFound) {
		s.internal(w, r, err)
		return
	}
	if u, err := s.store.UploadByBlob(ctx, dev.ID, req.SHA256); err == nil {
		if u.Size != req.Size || u.Kind != req.Kind || u.Fingerprint != req.Fingerprint {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "An unfinished upload of this blob has other details; DELETE it first.", map[string]any{"uploadId": u.ID})
			return
		}
		writeJSON(w, http.StatusOK, s.uploadResponse(u, "resume"))
		return
	} else if !errors.Is(err, store.ErrNotFound) {
		s.internal(w, r, err)
		return
	}
	n, err := s.store.UploadCount(ctx, dev.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if n >= maxUploadsPerDev {
		rateLimited(w, r, time.Minute)
		return
	}
	u, err := s.store.CreateUpload(ctx, store.Upload{DeviceID: dev.ID, SHA256: req.SHA256, Size: req.Size, Kind: req.Kind, Fingerprint: req.Fingerprint})
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, s.uploadResponse(u, "created"))
}

// registerFingerprint links an existing original blob to a fingerprint after checking it (2 MiB read).
func (s *Server) registerFingerprint(ctx context.Context, bs *blobstore.Store, b store.Blob, fp, deviceID string) error {
	f, err := bs.Open(b.SHA256)
	if err != nil {
		return err
	}
	defer f.Close()
	got, err := fingerprint.Of(f, b.Size)
	if err != nil {
		return err
	}
	if got != fp {
		return blobstore.ErrFingerprint
	}
	return s.store.AddBlob(ctx, b, fp, deviceID)
}

func (s *Server) upload(w http.ResponseWriter, r *http.Request) (store.Upload, bool) {
	id := r.PathValue("id")
	if !blobstore.ValidUploadID(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such upload.", nil)
		return store.Upload{}, false
	}
	u, err := s.store.UploadByID(r.Context(), info(r).device.ID, id)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No such upload.", nil)
		return u, false
	}
	if err != nil {
		s.internal(w, r, err)
		return u, false
	}
	return u, true
}

func uploadHeaders(w http.ResponseWriter, u store.Upload, offset int64) {
	w.Header().Set("Upload-Offset", strconv.FormatInt(offset, 10))
	w.Header().Set("Upload-Length", strconv.FormatInt(u.Size, 10))
	w.Header().Set("Cache-Control", "no-store")
}

func (s *Server) handleUploadHead(w http.ResponseWriter, r *http.Request) {
	u, ok := s.upload(w, r)
	if !ok {
		return
	}
	uploadHeaders(w, u, u.Offset)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) lockUpload(id string) (*sync.Mutex, bool) {
	v, _ := s.uploadLocks.LoadOrStore(id, &sync.Mutex{})
	m := v.(*sync.Mutex)
	return m, m.TryLock()
}

func (s *Server) handleUploadPatch(w http.ResponseWriter, r *http.Request) {
	u, ok := s.upload(w, r)
	if !ok {
		return
	}
	if ct := r.Header.Get("Content-Type"); !strings.EqualFold(strings.TrimSpace(strings.Split(ct, ";")[0]), "application/offset+octet-stream") {
		fail(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Send Content-Type: application/offset+octet-stream.", nil)
		return
	}
	mu, locked := s.lockUpload(u.ID)
	if !locked {
		fail(w, r, http.StatusConflict, "upload_busy", "Another request is writing this upload.", nil)
		return
	}
	defer mu.Unlock()
	// Read the offset again under the lock: a request that just finished may have moved it.
	u, ok = s.upload(w, r)
	if !ok {
		return
	}
	offset, err := strconv.ParseInt(r.Header.Get("Upload-Offset"), 10, 64)
	if err != nil || offset < 0 {
		fail(w, r, http.StatusBadRequest, "invalid_request", "Upload-Offset header is required.", nil)
		return
	}
	if offset != u.Offset {
		uploadHeaders(w, u, u.Offset)
		fail(w, r, http.StatusConflict, "offset_mismatch", fmt.Sprintf("Upload-Offset is %d, the server has %d.", offset, u.Offset), map[string]any{"offset": u.Offset})
		return
	}
	limit := min(int64(chunkSize), u.Size-u.Offset)
	if r.ContentLength > limit {
		uploadHeaders(w, u, u.Offset)
		fail(w, r, http.StatusRequestEntityTooLarge, "too_large", fmt.Sprintf("A chunk here is at most %d bytes.", limit), map[string]any{"offset": u.Offset})
		return
	}
	bs := s.blobStore()
	if bs == nil {
		fail(w, r, http.StatusServiceUnavailable, "storage_not_configured", "Choose a backup folder on the server's setup page first.", nil)
		return
	}
	ctx := context.WithoutCancel(r.Context()) // record what arrived even when the client is gone
	newOffset, readErr := bs.WriteChunk(u.ID, u.Offset, r.Body, limit)
	if err := s.store.SetUploadOffset(ctx, u.ID, newOffset); err != nil {
		s.internal(w, r, err)
		return
	}
	u.Offset = newOffset
	if readErr != nil {
		uploadHeaders(w, u, newOffset)
		fail(w, r, http.StatusBadRequest, "invalid_request", "The chunk was interrupted; HEAD the upload and continue from Upload-Offset.", map[string]any{"offset": newOffset})
		return
	}
	if extra, _ := r.Body.Read(make([]byte, 1)); extra > 0 {
		uploadHeaders(w, u, newOffset)
		fail(w, r, http.StatusRequestEntityTooLarge, "too_large", fmt.Sprintf("A chunk here is at most %d bytes; the first %d were kept.", limit, limit), map[string]any{"offset": newOffset})
		return
	}
	if newOffset < u.Size {
		uploadHeaders(w, u, newOffset)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if u.Kind == store.BlobPreview {
		// Only previews can be dropped (handleBlobDelete): originals complete without waiting for each other.
		s.completeMu.Lock()
		defer s.completeMu.Unlock()
	}
	err = bs.Complete(u.ID, u.SHA256, u.Size, u.Fingerprint)
	if errors.Is(err, blobstore.ErrChecksum) || errors.Is(err, blobstore.ErrFingerprint) {
		_ = s.store.SetUploadOffset(ctx, u.ID, 0)
		uploadHeaders(w, u, 0)
		what := "sha256"
		if errors.Is(err, blobstore.ErrFingerprint) {
			what = "fingerprint"
		}
		fail(w, r, http.StatusUnprocessableEntity, "checksum_mismatch", "The uploaded bytes do not match the declared "+what+"; the upload starts again at 0.", map[string]any{"offset": 0})
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if err := s.store.AddBlob(ctx, store.Blob{SHA256: u.SHA256, Size: u.Size, Kind: u.Kind}, u.Fingerprint, u.DeviceID); err != nil {
		s.internal(w, r, err)
		return
	}
	if err := s.store.DeleteUpload(ctx, u.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	s.uploadLocks.Delete(u.ID)
	uploadHeaders(w, u, u.Size)
	w.Header().Set("Upload-Complete", "1")
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleUploadDelete(w http.ResponseWriter, r *http.Request) {
	u, ok := s.upload(w, r)
	if !ok {
		return
	}
	mu, locked := s.lockUpload(u.ID)
	if !locked {
		fail(w, r, http.StatusConflict, "upload_busy", "Another request is writing this upload.", nil)
		return
	}
	defer mu.Unlock()
	if bs := s.blobStore(); bs != nil {
		if err := bs.Abort(u.ID); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if err := s.store.DeleteUpload(r.Context(), u.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	s.uploadLocks.Delete(u.ID)
	w.WriteHeader(http.StatusNoContent)
}

// --- NAS feed ----------------------------------------------------------------------------------------------------

func (s *Server) handleNasFiles(w http.ResponseWriter, r *http.Request) {
	head, err := s.store.Counter(r.Context(), "nas")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	since, ok := s.parseCursor(r.URL.Query().Get("cursor"), "n", head)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued. List from the start (empty cursor).", nil)
		return
	}
	limit, ok := pageLimit(r)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "limit must be a positive integer.", nil)
		return
	}
	page, more, err := s.store.NasChanges(r.Context(), since, limit)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	last := since
	if len(page) > 0 {
		last = page[len(page)-1].Rev
	}
	writeJSON(w, http.StatusOK, map[string]any{"files": page, "cursor": s.cursor("n", last), "hasMore": more})
}
