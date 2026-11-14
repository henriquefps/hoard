package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/henriquefps/hoard/server/internal/edits"
	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/previews"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Reveal's edited previews (ADR 0020, hoard-server 0.9.0): additions under /v1 (routed in routesAPI).
//
//	PUT    /v1/items/{itemId}/edits/{versionId}   an sRGB JPEG or WebP of the edited version, metadata in X-Hoard-* headers
//	GET    /v1/items/{itemId}/edits/{versionId}   the stored (cleaned) upload
//	DELETE /v1/items/{itemId}/edits/{versionId}
//
// and ?edit=<versionId>|primary on GET /v1/items/{itemId}/preview, "edit" in POST /v1/items/previews.

// Limits of edits (ADR 0020 "Limits"); GET /v1/server publishes them.
const (
	maxEditsPerItem = 32
	maxEditLabel    = 64  // characters
	maxEditDigest   = 128 // characters
	editClockSlack  = 10 * time.Minute
)

// Request headers of a PUT.
const (
	hdrRecipeDigest = "X-Hoard-Recipe-Digest"
	hdrEditedAt     = "X-Hoard-Edited-At"
	hdrLabel        = "X-Hoard-Label"
	hdrVirtualCopy  = "X-Hoard-Virtual-Copy"
	hdrPrimary      = "X-Hoard-Primary"
)

var digestPattern = regexp.MustCompile(`^[A-Za-z0-9._:+/=-]{1,128}$`)

func editsInfo() map[string]any {
	return map[string]any{"maxBytes": edits.MaxBytes, "maxEdge": edits.MaxEdge, "minEdge": edits.MinEdge,
		"formats": []string{edits.FormatJPEG, edits.FormatWebP}, "maxPerItem": maxEditsPerItem, "maxLabel": maxEditLabel,
		"maxDigest": maxEditDigest}
}

// --- files ------------------------------------------------------------------------------------------------------------

var shaPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// editPath is where an uploaded image lives: <edits dir>/<sha[0:2]>/<sha> (content-addressed, no extension).
func (s *Server) editPath(sha string) string {
	return filepath.Join(s.cfg.EditsDir, sha[:2], sha)
}

// openEdit opens an uploaded image by its SHA-256 (the previews' source "edit:<sha>").
func (s *Server) openEdit(sha string) (*os.File, error) {
	if !shaPattern.MatchString(sha) {
		return nil, fs.ErrNotExist
	}
	return os.Open(s.editPath(sha))
}

// writeEdit stores an image atomically (temporary file, fsync, rename, fsync of the directory); an image already
// stored is kept.
func (s *Server) writeEdit(img edits.Image) error {
	p := s.editPath(img.SHA256)
	if _, err := os.Stat(p); err == nil {
		return nil
	}
	dir := filepath.Dir(p)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".up-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if _, err := f.Write(img.Data); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, p); err != nil {
		return err
	}
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

func (s *Server) removeEdit(sha string) {
	if sha == "" || !shaPattern.MatchString(sha) {
		return
	}
	if err := os.Remove(s.editPath(sha)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		s.log.Warn("edits: a file stays", "err", err.Error())
	}
}

// sweepEdits removes uploaded images no edit names (an item deleted, a crash between writing and recording), older
// than an hour.
func (s *Server) sweepEdits(ctx context.Context) {
	if s.cfg.EditsDir == "" {
		return
	}
	used, err := s.store.EditImages(ctx)
	if err != nil {
		s.log.Error("edits: sweep", "err", err)
		return
	}
	cutoff := time.Now().Add(-time.Hour)
	removed := 0
	dirs, err := os.ReadDir(s.cfg.EditsDir)
	if err != nil {
		return
	}
	for _, d := range dirs {
		if !d.IsDir() || len(d.Name()) != 2 {
			continue
		}
		files, err := os.ReadDir(filepath.Join(s.cfg.EditsDir, d.Name()))
		if err != nil {
			continue
		}
		for _, f := range files {
			if used[f.Name()] {
				continue
			}
			if fi, err := f.Info(); err == nil && fi.ModTime().Before(cutoff) {
				if os.Remove(filepath.Join(s.cfg.EditsDir, d.Name(), f.Name())) == nil {
					removed++
				}
			}
		}
	}
	if removed > 0 {
		s.log.Info("edits: removed unused files", "count", removed)
	}
}

// editsDiskUsage walks the edits directory (the status page).
func (s *Server) editsDiskUsage() (files int, bytes int64) {
	if s.cfg.EditsDir == "" {
		return 0, 0
	}
	_ = filepath.WalkDir(s.cfg.EditsDir, func(_ string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if fi, err := d.Info(); err == nil {
			files++
			bytes += fi.Size()
		}
		return nil
	})
	return
}

// --- handlers -----------------------------------------------------------------------------------------------------------

// editTarget validates the path of an edit route; on failure it answers and returns false.
func (s *Server) editTarget(w http.ResponseWriter, r *http.Request) (string, string, bool) {
	if s.prev == nil || s.cfg.EditsDir == "" {
		fail(w, r, http.StatusServiceUnavailable, "unavailable", "Previews (and so edited previews) are not enabled on this server.", nil)
		return "", "", false
	}
	itemID, versionID := r.PathValue("itemId"), r.PathValue("versionId")
	if !itemIDPattern.MatchString(itemID) {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return "", "", false
	}
	if !uuidPattern.MatchString(versionID) {
		fail(w, r, http.StatusBadRequest, "invalid_request", "versionId is a lower-case UUID (Reveal's version id).", map[string]any{"field": "versionId"})
		return "", "", false
	}
	return itemID, versionID, true
}

func parseBool(v string) (bool, bool) {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "", "false", "0":
		return false, true
	case "true", "1":
		return true, true
	}
	return false, false
}

// handleEditPut stores Reveal's edited preview of one version of an item (ADR 0020 "Upload").
func (s *Server) handleEditPut(w http.ResponseWriter, r *http.Request) {
	itemID, versionID, ok := s.editTarget(w, r)
	if !ok {
		return
	}
	bad := func(field, msg string) {
		fail(w, r, http.StatusBadRequest, "invalid_request", msg, map[string]any{"field": field})
	}
	digest := strings.TrimSpace(r.Header.Get(hdrRecipeDigest))
	if !digestPattern.MatchString(digest) {
		bad("recipeDigest", hdrRecipeDigest+" is required: 1 to 128 characters of A-Z a-z 0-9 . _ : + / = -.")
		return
	}
	editedAt, err := strconv.ParseInt(strings.TrimSpace(r.Header.Get(hdrEditedAt)), 10, 64)
	if err != nil || editedAt <= 0 {
		bad("editedAt", hdrEditedAt+" is required: Unix milliseconds.")
		return
	}
	if limit := time.Now().Add(editClockSlack).UnixMilli(); editedAt > limit {
		editedAt = limit // a clock far ahead must not win every later edit (as culling, ADR 0016)
	}
	label, err := url.PathUnescape(r.Header.Get(hdrLabel))
	if err != nil || !utf8.ValidString(label) || utf8.RuneCountInString(label) > maxEditLabel || strings.ContainsFunc(label, func(c rune) bool { return c < 0x20 || c == 0x7f }) {
		bad("label", fmt.Sprintf("%s is percent-encoded UTF-8, at most %d characters, no control characters.", hdrLabel, maxEditLabel))
		return
	}
	label = strings.TrimSpace(label)
	vc, ok1 := parseBool(r.Header.Get(hdrVirtualCopy))
	primary, ok2 := parseBool(r.Header.Get(hdrPrimary))
	if !ok1 || !ok2 {
		bad("flags", hdrVirtualCopy+" and "+hdrPrimary+" are true or false.")
		return
	}
	ctx := r.Context()
	row, err := s.store.Item(ctx, itemID)
	if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if row.Kind == items.KindVideo {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Videos have no edited previews.", map[string]any{"reason": "not_applicable"})
		return
	}
	e := store.Edit{VersionID: versionID, ItemID: itemID, DeviceID: info(r).device.ID, RecipeDigest: digest, EditedAt: editedAt,
		Label: label, IsVirtualCopy: vc, Primary: primary}
	// The same render as stored: the body is not read (a client sending Expect: 100-continue does not even send it).
	old, err := s.store.Edit(ctx, versionID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		s.internal(w, r, err)
		return
	}
	same := err == nil && old.RecipeDigest == digest
	var img edits.Image
	if !same && r.ContentLength != 0 {
		ct := strings.ToLower(strings.TrimSpace(strings.SplitN(r.Header.Get("Content-Type"), ";", 2)[0]))
		if ct != "image/jpeg" && ct != "image/webp" {
			fail(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Send image/jpeg or image/webp.", nil)
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, edits.MaxBytes))
		if err != nil {
			var tooBig *http.MaxBytesError
			if errors.As(err, &tooBig) {
				fail(w, r, http.StatusRequestEntityTooLarge, "too_large", fmt.Sprintf("An edited preview is at most %d bytes.", edits.MaxBytes), nil)
				return
			}
			fail(w, r, http.StatusBadRequest, "invalid_request", "The body could not be read.", nil)
			return
		}
		img, err = edits.Check(body, ct)
		var inv *edits.Invalid
		if errors.As(err, &inv) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_image", inv.Message, map[string]any{"reason": inv.Reason})
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		if err := s.writeEdit(img); err != nil {
			s.internal(w, r, err)
			return
		}
		e.SHA256, e.Format, e.Width, e.Height, e.Bytes = img.SHA256, img.Format, img.Width, img.Height, int64(len(img.Data))
	}
	status, stored, unused, rev, err := s.store.PutEdit(ctx, e, maxEditsPerItem)
	if err != nil && img.SHA256 != "" {
		if used, uerr := s.store.EditImageUsed(ctx, img.SHA256); uerr == nil && !used {
			s.removeEdit(img.SHA256)
		}
	}
	switch {
	case errors.Is(err, store.ErrItemGone):
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	case errors.Is(err, store.ErrTooManyEdits):
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("An item has at most %d edits.", maxEditsPerItem), map[string]any{"reason": "too_many_edits"})
		return
	case errors.Is(err, store.ErrImageRequired):
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The server has no edit of this version with this recipe digest: send the image.", map[string]any{"reason": "image_required"})
		return
	case errors.Is(err, store.ErrEditOlder):
		fail(w, r, http.StatusConflict, "conflict", "The server has a later edit of this version.", map[string]any{"reason": "older",
			"editedAt": stored.EditedAt, "recipeDigest": stored.RecipeDigest})
		return
	case err != nil:
		s.internal(w, r, err)
		return
	}
	s.removeEdit(unused)
	if status != store.EditUnchanged {
		// Made ahead of the backlog: a client that uploaded an edit is likely to look at it.
		if row, err := s.store.Item(ctx, itemID); err == nil {
			if _, err := s.prev.Request(ctx, row, []string{previews.EditJob(versionID)}, store.PriorityWarm, time.Now().UnixMilli()); err != nil {
				s.log.Warn("edits: queue preview", "err", err.Error())
			}
		}
	}
	code := http.StatusOK
	if status == store.EditCreated {
		code = http.StatusCreated
	}
	writeJSON(w, code, map[string]any{"status": status, "itemId": itemID, "rev": rev, "edit": stored})
}

// handleEditDelete removes a version's edit from an item: {"status": "deleted" | "absent", "itemId", "rev"}.
func (s *Server) handleEditDelete(w http.ResponseWriter, r *http.Request) {
	itemID, versionID, ok := s.editTarget(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	if _, err := s.store.Item(ctx, itemID); errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	found, unused, rev, err := s.store.DeleteEdit(ctx, itemID, versionID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	status := "absent"
	if found {
		status = "deleted"
		s.removeEdit(unused)
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": status, "itemId": itemID, "rev": rev})
}

// handleEditGet serves the stored upload of a version's edit (metadata removed), with ranges. On /web/ it is the
// gallery's "Download edited JPEG" (?download=1: as an attachment named after the photo and the edit).
func (s *Server) handleEditGet(w http.ResponseWriter, r *http.Request) {
	itemID, versionID, ok := s.editTarget(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	row, err := s.store.Item(ctx, itemID)
	if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	e, err := s.store.Edit(ctx, versionID)
	if errors.Is(err, store.ErrNotFound) || err == nil && e.ItemID != itemID {
		fail(w, r, http.StatusNotFound, "not_found", "The item has no edit of this version.", map[string]any{"reason": "no_edit"})
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	f, err := s.openEdit(e.SHA256)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	defer f.Close()
	kind := "inline"
	if r.URL.Query().Get("download") == "1" {
		kind = "attachment"
	}
	var d items.Data
	_ = json.Unmarshal(row.Data, &d)
	h := w.Header()
	h.Set("Content-Type", edits.ContentType(e.Format))
	h.Set("ETag", `"`+e.SHA256+`"`)
	h.Set(hdrRecipeDigest, e.RecipeDigest)
	if r.URL.Query().Get("v") == e.SHA256 {
		h.Set("Cache-Control", "private, max-age=31536000, immutable")
	} else {
		h.Set("Cache-Control", "private, no-cache")
	}
	h.Set("Content-Disposition", contentDisposition(kind, editFileName(d, e)))
	http.ServeContent(w, r, "", time.Time{}, f)
}

// editFileName names a downloaded edit after the photo and the edit: "DSC01000 (Edited).jpg", "DSC01000 (Copy 1).jpg".
func editFileName(d items.Data, e store.Edit) string {
	stem := "edit"
	for _, f := range d.Files {
		if f.Fingerprint == d.MainFingerprint {
			stem = media.Stem(f.Name)
		}
	}
	label := e.Label
	if label == "" {
		label = "Edited"
	}
	label = strings.Map(func(r rune) rune {
		if strings.ContainsRune(`/\:*?"<>|`, r) {
			return '_'
		}
		return r
	}, label)
	return fmt.Sprintf("%s (%s).%s", stem, label, edits.Ext(e.Format))
}

// itemEdit finds an item's edit by version id, or its primary ("primary"); ok is false when it has none.
func itemEdit(row store.ItemRow, which string) (store.Edit, bool) {
	if len(row.Edits) == 0 {
		return store.Edit{}, false
	}
	var list []store.Edit
	if json.Unmarshal(row.Edits, &list) != nil {
		return store.Edit{}, false
	}
	for _, e := range list {
		if which == "primary" && e.Primary || e.VersionID == which {
			return e, true
		}
	}
	return store.Edit{}, false
}

// validEditParam reports a valid ?edit= value: "primary" or a version id.
func validEditParam(v string) bool { return v == "primary" || uuidPattern.MatchString(v) }
