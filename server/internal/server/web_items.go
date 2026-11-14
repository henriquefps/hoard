package server

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"path"
	"sort"
	"strings"
	"time"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Items in the web gallery (ADR 0018): one item with its culling, its exposure, its files and zip downloads.

const (
	maxDownloadItems = 5000
	downloadTTL      = 10 * time.Minute
)

// liveItem loads a live item and its data; on failure it answers and returns false.
func (s *Server) liveItem(w http.ResponseWriter, r *http.Request) (store.ItemRow, items.Data, bool) {
	id := r.PathValue("itemId")
	if !itemIDPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return store.ItemRow{}, items.Data{}, false
	}
	row, err := s.store.Item(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return store.ItemRow{}, items.Data{}, false
	}
	if err != nil {
		s.internal(w, r, err)
		return store.ItemRow{}, items.Data{}, false
	}
	var d items.Data
	if err := json.Unmarshal(row.Data, &d); err != nil {
		s.internal(w, r, err)
		return store.ItemRow{}, items.Data{}, false
	}
	return row, d, true
}

// handleWebItem answers {"item": <as /v1/items/{id}>, "culling": <record> | null, "edited": bool}.
func (s *Server) handleWebItem(w http.ResponseWriter, r *http.Request) {
	row, d, ok := s.liveItem(w, r)
	if !ok {
		return
	}
	var buf bytes.Buffer
	buf.WriteString(`{"item":`)
	itemJSON(&buf, row)
	buf.WriteString(`,"culling":`)
	rec, err := s.store.Culling(r.Context(), row.ID)
	switch {
	case errors.Is(err, store.ErrNotFound):
		buf.WriteString("null")
	case err != nil:
		s.internal(w, r, err)
		return
	default:
		b, err := json.Marshal(rec)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		buf.Write(b)
	}
	edited, err := s.store.EditedFingerprints(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	fmt.Fprintf(&buf, `,"edited":%t}`+"\n", edited[d.MainFingerprint])
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(buf.Bytes())
}

// handleWebExif reads the exposure of an item's still on demand (the rendered JPEG of a pair, else the main file):
// {"exposure": {...}}; videos and unreadable files answer an empty object.
func (s *Server) handleWebExif(w http.ResponseWriter, r *http.Request) {
	_, d, ok := s.liveItem(w, r)
	if !ok {
		return
	}
	fp := ""
	for _, f := range d.Files {
		if f.Role == media.RoleRendered && fp == "" {
			fp = f.Fingerprint
		}
	}
	if fp == "" && d.Kind != items.KindVideo {
		fp = d.MainFingerprint
	}
	exp := media.Exposure{}
	if fp != "" {
		if f, err := s.openSource(r.Context(), fp); err == nil {
			if fi, err := f.Stat(); err == nil {
				exp, _ = media.ReadExposure(f, fi.Size())
			}
			f.Close()
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"exposure": exp})
}

// contentType of a member file by its name (the server serves originals as octet-stream on /v1).
func contentType(name string) string {
	ext := strings.ToLower(path.Ext(name))
	switch ext {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".heic", ".heif":
		return "image/heic"
	case ".avif":
		return "image/avif"
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	case ".mp4":
		return "video/mp4"
	case ".mov":
		return "video/quicktime"
	case ".xmp":
		return "application/rdf+xml"
	}
	return "application/octet-stream"
}

func disposition(kind, name string) string {
	if name == "" {
		return kind
	}
	if v := mime.FormatMediaType(kind, map[string]string{"filename": name}); v != "" {
		return v
	}
	return kind
}

// handleWebFile serves one member file of an item (inline, or as an attachment with ?download=1), with ranges.
func (s *Server) handleWebFile(w http.ResponseWriter, r *http.Request) {
	_, d, ok := s.liveItem(w, r)
	if !ok {
		return
	}
	fp := r.PathValue("fingerprint")
	var file *items.File
	for i := range d.Files {
		if d.Files[i].Fingerprint == fp {
			file = &d.Files[i]
		}
	}
	if file == nil {
		fail(w, r, http.StatusNotFound, "not_found", "The item has no such file.", nil)
		return
	}
	f, err := s.openSource(r.Context(), fp)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No file with this fingerprint.", nil)
		return
	}
	if err != nil {
		w.Header().Set("Retry-After", "60")
		fail(w, r, http.StatusServiceUnavailable, "unavailable", "The file is not available right now (moved, changed or the share is offline).", nil)
		return
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Type", contentType(file.Name))
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("ETag", `"`+fp+`"`)
	kind := "inline"
	if r.URL.Query().Get("download") == "1" {
		kind = "attachment"
	}
	h.Set("Content-Disposition", disposition(kind, file.Name))
	http.ServeContent(w, r, "", time.Time{}, f)
}

type webDownload struct {
	writer  string
	items   []string
	name    string
	expires time.Time
}

// handleWebDownloadCreate checks the items and answers a link to their zip: {"url", "name", "items", "files", "bytes"}.
func (s *Server) handleWebDownloadCreate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Items []string `json:"items"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Items) == 0 || len(req.Items) > maxDownloadItems {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("Send 1 to %d items.", maxDownloadItems), nil)
		return
	}
	seen := map[string]bool{}
	var ids []string
	files, size := 0, int64(0)
	for i, id := range req.Items {
		if !itemIDPattern.MatchString(id) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "items are item ids.", map[string]any{"index": i})
			return
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		row, err := s.store.Item(r.Context(), id)
		if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "No such item.", map[string]any{"index": i})
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		var d items.Data
		if err := json.Unmarshal(row.Data, &d); err != nil {
			s.internal(w, r, err)
			return
		}
		for _, f := range d.Files {
			files++
			size += f.Size
		}
		ids = append(ids, id)
	}
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		s.internal(w, r, err)
		return
	}
	token := hex.EncodeToString(b[:])
	name := fmt.Sprintf("hoard-%s-%d.zip", time.Now().Format("2006-01-02"), len(ids))
	s.web.dlMu.Lock()
	s.web.downloads[token] = webDownload{writer: info(r).device.ID, items: ids, name: name, expires: time.Now().Add(downloadTTL)}
	s.web.dlMu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"url": "/web/downloads/" + token, "name": name, "items": len(ids), "files": files, "bytes": size})
}

// zipName makes names unique inside one archive: "DSC0001.JPG", then "DSC0001 (2).JPG".
type zipNames map[string]bool

func (z zipNames) unique(name string) string {
	name = strings.TrimLeft(strings.ReplaceAll(name, "\\", "_"), "./")
	if name == "" {
		name = "file"
	}
	ext := path.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	out := name
	for i := 2; z[strings.ToLower(out)]; i++ {
		out = fmt.Sprintf("%s (%d)%s", stem, i, ext)
	}
	z[strings.ToLower(out)] = true
	return out
}

// handleWebDownload streams the zip of a download link (stored, not compressed: photos and videos are compressed
// already). A file the server cannot open is listed in "missing.txt" at the end instead of failing the whole archive.
func (s *Server) handleWebDownload(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	s.web.dlMu.Lock()
	dl, ok := s.web.downloads[token]
	s.web.dlMu.Unlock()
	if !ok || time.Now().After(dl.expires) || dl.writer != info(r).device.ID {
		fail(w, r, http.StatusNotFound, "not_found", "This download link expired. Start the download again.", nil)
		return
	}
	h := w.Header()
	h.Set("Content-Type", "application/zip")
	h.Set("Cache-Control", "no-store")
	h.Set("Content-Disposition", disposition("attachment", dl.name))
	zw := zip.NewWriter(w)
	names := zipNames{}
	var missing []string
	ctx := r.Context()
	for _, id := range dl.items {
		row, err := s.store.Item(ctx, id)
		if err != nil || row.Deleted {
			missing = append(missing, id)
			continue
		}
		var d items.Data
		if err := json.Unmarshal(row.Data, &d); err != nil {
			missing = append(missing, id)
			continue
		}
		files := append([]items.File(nil), d.Files...)
		sort.SliceStable(files, func(i, j int) bool { return files[i].Name < files[j].Name })
		for _, f := range files {
			if ctx.Err() != nil {
				return
			}
			if err := s.zipFile(ctx, zw, names, f); err != nil {
				if ctx.Err() != nil {
					return
				}
				missing = append(missing, f.Name)
			}
		}
	}
	if len(missing) > 0 {
		if fw, err := zw.Create(names.unique("missing.txt")); err == nil {
			_, _ = io.WriteString(fw, "These files could not be read when the archive was made (moved, changed or offline):\n"+strings.Join(missing, "\n")+"\n")
		}
	}
	_ = zw.Close()
}

// zipFile adds one file; an error before its entry is created leaves the archive valid (the file is listed as
// missing), an error while copying ends the response (the browser reports a failed download).
func (s *Server) zipFile(ctx context.Context, zw *zip.Writer, names zipNames, f items.File) error {
	src, err := s.openSource(ctx, f.Fingerprint)
	if err != nil {
		return err
	}
	defer src.Close()
	fi, err := src.Stat()
	if err != nil {
		return err
	}
	hdr := &zip.FileHeader{Name: names.unique(f.Name), Method: zip.Store, Modified: fi.ModTime()}
	hdr.SetMode(0o644)
	dst, err := zw.CreateHeader(hdr)
	if err != nil {
		return err
	}
	_, err = io.Copy(dst, src)
	return err
}
