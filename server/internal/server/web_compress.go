package server

import (
	"encoding/json"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Video compression in the web gallery (ADR 0025 phase S3). The gallery's twins of /v1/compress/* call the same
// handlers under the gallery's session (no device key; a job it starts is created by "admin", the ADR's
// compress_jobs.created_by for a web gallery session). Phone jobs (target phone) need the caller's phone: the gallery
// never starts one and lists them read-only.
//
//	GET    /web/compress                       {compress: GET /v1/server's object | null, roots: phone backups}
//	POST   /web/compress/estimate              as /v1
//	POST   /web/compress/jobs                  as /v1 (created_by "admin")
//	GET    /web/compress/jobs[/{jobId}]        as /v1
//	PATCH  /web/compress/jobs/{jobId}          as /v1
//	GET    /web/compress/items                 as /v1
//	POST   /web/compress/cancel|restore|purge|lookup   as /v1
//	GET    /web/compress/items/{id}/log        the item's log (text; as the setup page's)
//	GET    /web/compress/items/{id}/meta       the output's metadata sidecar (gzip JSON) as a download
//	GET    /web/trash?cursor=                  the trashed items, with their compression (compressed originals)

func (s *Server) routesWebCompress(authed func(string, http.HandlerFunc)) {
	authed("GET /web/compress", s.handleWebCompress)
	authed("POST /web/compress/estimate", s.handleCompressEstimate)
	authed("POST /web/compress/jobs", s.webAsAdmin(s.handleCompressJobCreate))
	authed("GET /web/compress/jobs", s.handleCompressJobs)
	authed("GET /web/compress/jobs/{jobId}", s.handleCompressJob)
	authed("PATCH /web/compress/jobs/{jobId}", s.handleCompressJobUpdate)
	authed("GET /web/compress/items", s.handleCompressItems)
	authed("POST /web/compress/cancel", s.handleCompressCancel)
	authed("POST /web/compress/restore", s.handleCompressRestore)
	authed("POST /web/compress/purge", s.handleCompressPurge)
	authed("POST /web/compress/lookup", s.handleCompressLookup)
	authed("GET /web/compress/items/{id}/log", s.handleAdminCompressLog)
	authed("GET /web/compress/items/{id}/meta", s.handleWebCompressMeta)
	authed("GET /web/trash", s.handleWebTrash)
}

// webAsAdmin runs h as the web gallery's admin (compress_jobs.created_by = "admin", ADR 0025 "Tables").
func (s *Server) webAsAdmin(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		d := info(r).device
		d.ID = "admin"
		info(r).device = d
		h(w, r)
	}
}

type webRoot struct {
	ID       string `json:"id"`
	Device   string `json:"device"`
	Physical string `json:"physicalId"`
	Volume   string `json:"volume"`
	Path     string `json:"path"`
	Stopped  bool   `json:"stopped,omitempty"`
}

func (s *Server) handleWebCompress(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{"compress": nil}
	if feature, _ := s.compressFeature(); s.compress != nil && (feature || s.compress.Available() || s.compressHasJobs(r.Context())) {
		out["compress"] = s.compressInfo(r.Context())
	}
	roots, err := s.store.MirrorRoots(r.Context(), "")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, err := s.store.PhysicalNames(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	list := make([]webRoot, 0, len(roots))
	for _, rt := range roots {
		list = append(list, webRoot{ID: rt.ID, Device: names[rt.PhysicalID], Physical: rt.PhysicalID, Volume: rt.Volume, Path: rt.Path,
			Stopped: rt.StoppedAt != nil})
	}
	out["roots"] = list
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, out)
}

// handleWebCompressMeta serves an output's metadata sidecar (ADR 0025 "Metadata", Amendment (N4)): the gzip JSON as
// stored, as an attachment named after the video.
func (s *Server) handleWebCompressMeta(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	}
	it, err := s.store.CompressItem(r.Context(), id)
	if err != nil || it.OutFingerprint == "" {
		fail(w, r, http.StatusNotFound, "not_found", "No metadata saved for this item.", nil)
		return
	}
	f, err := os.Open(s.compress.MetaPath(it.OutFingerprint))
	if err != nil {
		fail(w, r, http.StatusNotFound, "not_found", "No metadata saved for this item.", nil)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		s.internal(w, r, err)
		return
	}
	stem := strings.TrimSuffix(path.Base(it.RelPath), path.Ext(it.RelPath))
	if stem == "" || stem == "." || stem == "/" {
		stem = "video-" + strconv.FormatInt(it.ID, 10)
	}
	h := w.Header()
	h.Set("Content-Type", "application/gzip")
	h.Set("Content-Disposition", contentDisposition("attachment", stem+".metadata.json.gz"))
	h.Set("Cache-Control", "no-store")
	http.ServeContent(w, r, "", fi.ModTime(), f)
}

// webTrashEntry is one trashed item of GET /web/trash.
type webTrashEntry struct {
	ID        string          `json:"id"`
	Kind      string          `json:"kind"`
	Name      string          `json:"name"`
	Size      int64           `json:"size"`
	TrashedAt *int64          `json:"trashedAt,omitempty"`
	Places    []webTrashPlace `json:"places"`
	Held      bool            `json:"held,omitempty"`
	To        string          `json:"compressedTo,omitempty"`
	Compress  map[string]any  `json:"compression,omitempty"`
	Duration  int64           `json:"durationMs,omitempty"`
	Live      bool            `json:"live,omitempty"` // a live item listed for its held trash entry
}

type webTrashPlace struct {
	Source string `json:"source"`
	Folder string `json:"folder,omitempty"`
	Path   string `json:"path,omitempty"`
	Trash  string `json:"trash,omitempty"`
	Device string `json:"device,omitempty"`
	Held   bool   `json:"held,omitempty"`
}

// handleWebTrash lists the trashed items (ADR 0023), newest first, with each compressed original's compression (ADR
// 0025 "Originals: the trash"): what restoring and emptying it through /web/compress/restore and …/purge does. An entry
// a compression journal holds is marked held: nothing empties it. The first page also lists the live items whose file
// has a held trash entry (a replace that waits after its hard link keeps the original at its path and in the trash).
func (s *Server) handleWebTrash(w http.ResponseWriter, r *http.Request) {
	iq := store.ItemQuery{Trashed: true, Desc: true, Limit: 200}
	cursor := r.URL.Query().Get("cursor")
	if cursor != "" {
		key, desc, ok := s.parseItemCursor(cursor)
		if !ok || !desc {
			fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued.", nil)
			return
		}
		iq.After = &key
	}
	rows, more, err := s.store.QueryItems(r.Context(), iq)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	all := rows
	if cursor == "" {
		waiting, err := s.store.CompressItemsIn(r.Context(), store.CompressReplacing)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		seen := map[string]bool{}
		for _, it := range waiting {
			for _, id := range []string{it.ItemID, items.ID(it.OutFingerprint)} {
				if id == "" || seen[id] {
					continue
				}
				seen[id] = true
				if row, err := s.store.Item(r.Context(), id); err == nil && !row.Deleted && !row.Trashed() {
					all = append([]store.ItemRow{row}, all...)
				}
			}
		}
	}
	list := make([]webTrashEntry, 0, len(all))
	var fps []string
	mainFP := map[string]string{}
	for _, row := range all {
		var d items.Data
		if json.Unmarshal(row.Data, &d) != nil {
			continue
		}
		e := webTrashEntry{ID: row.ID, Kind: row.Kind, TrashedAt: row.TrashedAt, To: d.CompressedTo, Places: []webTrashPlace{}, Live: !row.Trashed()}
		if d.Video != nil {
			e.Duration = d.Video.DurationMs
		}
		for _, f := range d.Files {
			if f.Fingerprint == d.MainFingerprint {
				e.Name = f.Name
			}
			trashed := false
			for _, l := range f.Locations {
				if l.Trash == "" && l.Gone != store.RemovedCompressed {
					continue
				}
				trashed = true
				e.Held = e.Held || l.Held
				if f.Fingerprint == d.MainFingerprint {
					e.Places = append(e.Places, webTrashPlace{Source: l.Source, Folder: l.FolderName, Path: l.Path, Trash: l.Trash, Device: l.DeviceName, Held: l.Held})
				}
			}
			if trashed {
				e.Size += f.Size
			}
		}
		if e.Live && !e.Held { // a live item is listed only for its held trash entry
			continue
		}
		if e.To != "" || e.Held {
			fps = append(fps, d.MainFingerprint)
		}
		mainFP[row.ID] = d.MainFingerprint
		list = append(list, e)
	}
	if len(fps) > 0 {
		recs, err := s.store.CompressItemsByFingerprint(r.Context(), fps)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		kept := list[:0]
		for _, e := range list {
			if e.To != "" || e.Held {
				var stale bool
				e.Compress, stale = webTrashCompression(recs, mainFP[e.ID], e.Live)
				if stale { // emptied or swapped back already: gone from the trash at the next walk
					continue
				}
			}
			kept = append(kept, e)
		}
		list = kept
	}
	var next any
	if more && len(rows) > 0 {
		last := rows[len(rows)-1]
		next = s.itemCursor(store.ItemKey{SortTime: last.SortTime, ID: last.ID}, true)
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"items": list, "cursor": next, "hasMore": more})
}

// webTrashCompression is the newest compression that names fp (newest first in recs), as the trash row shows it: the
// trashed side (role original, or output after a restore), its sizes, state and when auto-empty takes it. stale is true
// when the record says this side is not in the trash any more (purged, or live again after a restore) while the item
// still says so until the next walk; a live item (held) is never stale.
func webTrashCompression(recs []store.CompressItem, fp string, live bool) (map[string]any, bool) {
	for _, it := range recs {
		if it.State == store.CompressSkipped || it.State == store.CompressFailed || it.State == store.CompressCancelled {
			continue
		}
		role := ""
		switch {
		case it.Fingerprint == fp:
			role = "original"
		case it.OutFingerprint == fp:
			role = "output"
		default:
			continue
		}
		if !live && (it.State == store.CompressPurged || (role == "original") == (it.State == store.CompressRestored) &&
			(it.State == store.CompressReplaced || it.State == store.CompressRestored)) {
			return nil, true
		}
		m := map[string]any{"id": strconv.FormatInt(it.ID, 10), "jobId": it.JobID, "state": it.State, "role": role, "size": it.Size, "source": it.Source}
		if it.OutSize != nil {
			m["outSize"] = *it.OutSize
		}
		if it.PurgeAfter != nil && (it.State == store.CompressReplaced || it.State == store.CompressRestored) {
			m["purgeAfter"] = *it.PurgeAfter
		}
		if it.Reason != "" {
			m["reason"] = it.Reason
		}
		return m, false
	}
	return nil, false
}
