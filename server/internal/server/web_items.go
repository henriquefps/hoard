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
	"net/http"
	"net/url"
	"path"
	"sort"
	"strings"
	"time"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/previews"
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

// contentDisposition names a response's file for the browser (RFC 6266): an ASCII fallback in filename (non-ASCII and
// quoting characters as "_") and the exact name, UTF-8 and percent-encoded (RFC 8187), in filename*. Browsers use
// filename* and keep the original name and extension.
func contentDisposition(kind, name string) string {
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || r == '/' || r == '\\' {
			return '_'
		}
		return r
	}, name)
	if name == "" {
		return kind
	}
	var ascii, enc strings.Builder
	for _, r := range name {
		if r > 0x7e || r == '"' || r == '%' {
			ascii.WriteByte('_')
		} else {
			ascii.WriteRune(r)
		}
	}
	for _, b := range []byte(name) {
		if b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || strings.IndexByte("!#$&+-.^_`|~", b) >= 0 {
			enc.WriteByte(b)
		} else {
			fmt.Fprintf(&enc, "%%%02X", b)
		}
	}
	return kind + `; filename="` + ascii.String() + `"; filename*=UTF-8''` + enc.String()
}

// memberFile finds a member file of an item by fingerprint.
func memberFile(d items.Data, fp string) *items.File {
	for i := range d.Files {
		if d.Files[i].Fingerprint == fp {
			return &d.Files[i]
		}
	}
	return nil
}

// handleWebFile serves one member file of an item (inline, or as an attachment with ?download=1), with ranges. The
// route has a variant ending in the file's name (/files/{fingerprint}/{name}) so that a browser that names a download
// by its URL (a blocked download, "Save link as") still gets the name and extension; the name in the path is ignored.
func (s *Server) handleWebFile(w http.ResponseWriter, r *http.Request) {
	_, d, ok := s.liveItem(w, r)
	if !ok {
		return
	}
	file := memberFile(d, r.PathValue("fingerprint"))
	if file == nil {
		fail(w, r, http.StatusNotFound, "not_found", "The item has no such file.", nil)
		return
	}
	kind := "inline"
	if r.URL.Query().Get("download") == "1" {
		kind = "attachment"
	}
	s.serveMember(w, r, *file, kind)
}

// serveMember answers a member file with ranges, its type by extension and its name.
func (s *Server) serveMember(w http.ResponseWriter, r *http.Request, file items.File, kind string) {
	f, err := s.openSource(r.Context(), file.Fingerprint)
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
	h.Set("ETag", `"`+file.Fingerprint+`"`)
	h.Set("Content-Disposition", contentDisposition(kind, file.Name))
	http.ServeContent(w, r, "", time.Time{}, f)
}

// handleWebFull answers the item's still at full resolution for the viewer's 100 % zoom (ADR 0018 "Full resolution"):
// the file itself when a browser shows it (X-Hoard-Full: original — a JPEG, a pair's camera JPEG), else a JPEG the
// server makes on demand and caches (X-Hoard-Full: converted for a HEIF, raw for a lone RAW: its full-size embedded
// rendering or a LibRaw decode), 202 with Retry-After while it is being made.
func (s *Server) handleWebFull(w http.ResponseWriter, r *http.Request) {
	row, d, ok := s.liveItem(w, r)
	if !ok {
		return
	}
	if d.Kind == items.KindVideo {
		fail(w, r, http.StatusNotFound, "preview_unavailable", "Videos have no full-resolution still.", map[string]any{"reason": "not_applicable"})
		return
	}
	if f := previews.FullOriginal(d); f != nil {
		w.Header().Set("X-Hoard-Full", "original")
		s.serveMember(w, r, *f, "inline")
		return
	}
	if s.prev == nil {
		fail(w, r, http.StatusServiceUnavailable, "unavailable", "Previews are not enabled on this server.", nil)
		return
	}
	source := "converted"
	if j, ok := previews.Plan(row.ID, d)[previews.JobFull]; ok && j.Source.Role == media.RoleRaw {
		source = "raw"
	}
	w.Header().Set("X-Hoard-Full", source)
	s.serveJob(w, r, row, previews.JobFull, previews.FileFull, "image/jpeg", 2)
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
	var namer zipNamer
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
		namer.add(d)
		ids = append(ids, id)
	}
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		s.internal(w, r, err)
		return
	}
	token := hex.EncodeToString(b[:])
	name := namer.name(time.Now())
	s.web.dlMu.Lock()
	s.web.downloads[token] = webDownload{writer: info(r).device.ID, items: ids, name: name, expires: time.Now().Add(downloadTTL)}
	s.web.dlMu.Unlock()
	// The name ends the URL too (ignored by the route), for browsers that name a download by its URL.
	writeJSON(w, http.StatusOK, map[string]any{"url": "/web/downloads/" + token + "/" + url.PathEscape(name), "name": name, "items": len(ids), "files": files, "bytes": size})
}

// zipNamer names a zip after what it holds (ADR 0018 "Downloads"): one item → its main file's stem ("DSC01000.zip");
// several from one directory → "<directory>-<n> photos.zip"; else the capture day, or the first and last days
// ("2024-05-03–2024-05-10-<n> photos.zip").
type zipNamer struct {
	n          int
	stem       string
	dir        string
	mixedDirs  bool
	first, end string
}

func (z *zipNamer) add(d items.Data) {
	z.n++
	dir := ""
	for _, f := range d.Files {
		if f.Fingerprint != d.MainFingerprint {
			continue
		}
		z.stem = media.Stem(f.Name)
		for _, l := range f.Locations {
			if l.Source == "nas" {
				dir = path.Base(path.Dir(l.Path))
				if dir == "." || dir == "/" {
					dir = l.FolderName
				}
				break
			}
		}
	}
	switch {
	case z.n == 1:
		z.dir = dir
	case dir != z.dir:
		z.mixedDirs = true
	}
	day := ""
	if d.Capture != nil && len(d.Capture.Local) >= 10 {
		day = d.Capture.Local[:10]
	} else if d.SortTime > 0 {
		day = time.UnixMilli(d.SortTime).UTC().Format("2006-01-02")
	}
	if day != "" {
		if z.first == "" || day < z.first {
			z.first = day
		}
		if day > z.end {
			z.end = day
		}
	}
}

func (z *zipNamer) name(now time.Time) string {
	clean := func(s string) string {
		return strings.TrimSpace(strings.Map(func(r rune) rune {
			if r < 0x20 || strings.ContainsRune(`/\:*?"<>|`, r) {
				return '_'
			}
			return r
		}, s))
	}
	if z.n == 1 && clean(z.stem) != "" {
		return clean(z.stem) + ".zip"
	}
	label := ""
	switch {
	case !z.mixedDirs && clean(z.dir) != "":
		label = clean(z.dir)
	case z.first != "" && z.first == z.end:
		label = z.first
	case z.first != "":
		label = z.first + "–" + z.end
	default:
		label = now.Format("2006-01-02")
	}
	unit := "photos"
	if z.n == 1 {
		unit = "photo"
	}
	return fmt.Sprintf("%s-%d %s.zip", label, z.n, unit)
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
	h.Set("Content-Disposition", contentDisposition("attachment", dl.name))
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
