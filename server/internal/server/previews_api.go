package server

import (
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/previews"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Gallery previews (ADR 0015): additions under /v1 that Reveal never calls (routed in routesAPI).
//
//	GET  /v1/items/{itemId}/preview?size=thumb|screen&format=jpeg|webp[&v=<key>]   a still's (or a video's poster)
//	GET  /v1/items/{itemId}/poster?size=thumb|screen&format=jpeg|webp[&v=<key>]    a video's poster frame
//	GET  /v1/items/{itemId}/proxy[?v=<key>]                       the H.264 proxy of a video or motion photo (ranges)
//	POST /v1/items/previews                                       states of many items; queues what is missing
//
// A ready file is served with ETag "<key>-<file>" and X-Hoard-Preview-Version: <key>. With ?v= equal to that key the
// response is immutable for a year; without it, clients revalidate (no-cache + ETag → 304). Not ready yet: 202 with
// Retry-After, after queueing the job ahead of the backlog.

// Limits: items per warm request; the preview GETs' own per-device rate.
const (
	maxPreviewWarm = 500
	previewRate    = 200
	previewBurst   = 600
)

// previewFiles: size → format → file.
var previewFiles = map[string]map[string]string{
	"thumb":  {"jpeg": previews.FileThumb, "webp": previews.FileThumbWebP},
	"screen": {"jpeg": previews.FileScreen, "webp": previews.FileScreenWebP},
}

func (s *Server) previewItem(w http.ResponseWriter, r *http.Request) (store.ItemRow, bool) {
	if s.prev == nil {
		fail(w, r, http.StatusServiceUnavailable, "unavailable", "Previews are not enabled on this server.", nil)
		return store.ItemRow{}, false
	}
	id := r.PathValue("itemId")
	if !itemIDPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return store.ItemRow{}, false
	}
	row, err := s.store.Item(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return store.ItemRow{}, false
	}
	if err != nil {
		s.internal(w, r, err)
		return store.ItemRow{}, false
	}
	return row, true
}

func (s *Server) handlePreview(w http.ResponseWriter, r *http.Request) {
	s.servePreview(w, r, false)
}

func (s *Server) handlePoster(w http.ResponseWriter, r *http.Request) {
	s.servePreview(w, r, true)
}

func (s *Server) servePreview(w http.ResponseWriter, r *http.Request, poster bool) {
	q := r.URL.Query()
	size, format := q.Get("size"), q.Get("format")
	if size == "" {
		size = "thumb"
	}
	if format == "" {
		format = "jpeg"
	}
	file, ok := previewFiles[size][format]
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "size is thumb or screen; format is jpeg or webp.", nil)
		return
	}
	row, ok := s.previewItem(w, r)
	if !ok {
		return
	}
	if poster && row.Kind != items.KindVideo {
		fail(w, r, http.StatusNotFound, "preview_unavailable", "Only videos have a poster frame; use /preview.", map[string]any{"reason": "not_applicable"})
		return
	}
	s.serveJob(w, r, row, previews.JobImage, file, "image/"+format, 2)
}

func (s *Server) handleProxy(w http.ResponseWriter, r *http.Request) {
	row, ok := s.previewItem(w, r)
	if !ok {
		return
	}
	s.serveJob(w, r, row, previews.JobProxy, previews.FileProxy, "video/mp4", 15)
}

func (s *Server) serveJob(w http.ResponseWriter, r *http.Request, row store.ItemRow, job, file, contentType string, retry int) {
	ctx := r.Context()
	states, err := s.prev.Request(ctx, row, []string{job}, store.PriorityWaiting, time.Now().UnixMilli())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	st := states[job]
	switch st.Status {
	case previews.StatusUnavailable:
		msg := map[string]string{
			"not_applicable": "This item has no such preview (proxies are for videos and motion photos).",
			"unsupported":    "The server cannot make a preview of this file.",
			"no_tools":       "The server's preview tools are not installed.",
		}[st.Reason]
		if msg == "" {
			msg = "Making this preview failed."
		}
		fail(w, r, http.StatusNotFound, "preview_unavailable", msg, map[string]any{"reason": st.Reason})
		return
	case previews.StatusPending:
		pending(w, r, retry)
		return
	}
	f, err := os.Open(s.prev.Path(st.Key, file))
	if errors.Is(err, fs.ErrNotExist) {
		s.prev.FilesMissing(ctx, row.ID, job)
		if _, err := s.prev.Request(ctx, row, []string{job}, store.PriorityWaiting, time.Now().UnixMilli()); err != nil {
			s.internal(w, r, err)
			return
		}
		pending(w, r, retry)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Type", contentType)
	h.Set("ETag", `"`+st.Key+"-"+file+`"`)
	h.Set("X-Hoard-Preview-Version", st.Key)
	if st.Stale {
		h.Set("X-Hoard-Preview", "stale")
	} else {
		h.Set("X-Hoard-Preview", "ready")
	}
	if v := r.URL.Query().Get("v"); v == st.Key {
		h.Set("Cache-Control", "private, max-age=31536000, immutable")
	} else {
		h.Set("Cache-Control", "private, no-cache")
	}
	http.ServeContent(w, r, "", time.Time{}, f)
}

func pending(w http.ResponseWriter, r *http.Request, retry int) {
	w.Header().Set("Retry-After", strconv.Itoa(retry))
	w.Header().Set("X-Hoard-Preview", "pending")
	info(r).code = "pending"
	writeJSON(w, http.StatusAccepted, map[string]any{"status": "pending", "retryAfter": retry})
}

// previewState is one preview in the warm answer.
type previewState struct {
	Status string `json:"status"`              // ready | pending | unavailable
	V      string `json:"v,omitempty"`         // ready: the key to put in ?v= (immutable URLs)
	Stale  bool   `json:"stale,omitempty"`     // ready, an older one; a new one is queued
	Width  int    `json:"width,omitempty"`     // ready
	Height int    `json:"height,omitempty"`    // ready
	Bytes  int64  `json:"bytes,omitempty"`     // ready (JPEG; MP4 for a proxy)
	WebP   int64  `json:"webpBytes,omitempty"` // ready thumb/screen: the WebP's size (format=webp)
	Reason string `json:"reason,omitempty"`    // unavailable: not_applicable | unsupported | failed | no_tools
}

func toPreviewState(st previews.State, file, webp string) previewState {
	out := previewState{Status: st.Status, Reason: st.Reason}
	if st.Status == previews.StatusReady {
		o := st.Outputs[file]
		out.V, out.Stale, out.Width, out.Height, out.Bytes = st.Key, st.Stale, o.Width, o.Height, o.Bytes
		if webp != "" {
			out.WebP = st.Outputs[webp].Bytes
		}
	}
	return out
}

// handleWarmPreviews answers the state of up to 500 items' previews and queues the missing ones (a gallery screen
// asking for what it is about to show).
func (s *Server) handleWarmPreviews(w http.ResponseWriter, r *http.Request) {
	if s.prev == nil {
		fail(w, r, http.StatusServiceUnavailable, "unavailable", "Previews are not enabled on this server.", nil)
		return
	}
	var req struct {
		Items    []string `json:"items"`
		Previews []string `json:"previews"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Items) > maxPreviewWarm {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("At most %d items per request.", maxPreviewWarm), nil)
		return
	}
	if len(req.Previews) == 0 {
		req.Previews = []string{"thumb"}
	}
	jobSet := map[string]bool{}
	for _, p := range req.Previews {
		switch p {
		case "thumb", "screen":
			jobSet[previews.JobImage] = true
		case "proxy":
			jobSet[previews.JobProxy] = true
		default:
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "previews is a list of thumb, screen, proxy.", nil)
			return
		}
	}
	var jobs []string
	for _, j := range []string{previews.JobImage, previews.JobProxy} {
		if jobSet[j] {
			jobs = append(jobs, j)
		}
	}
	for i, id := range req.Items {
		if !itemIDPattern.MatchString(id) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Item ids are lower-case UUIDs.", map[string]any{"index": i})
			return
		}
	}
	ctx := r.Context()
	type entry map[string]any
	out := []entry{}
	missing := []string{}
	base := time.Now().UnixMilli() * 1000 // the first item of the list runs first
	seen := map[string]bool{}
	for i, id := range req.Items {
		if seen[id] {
			continue
		}
		seen[id] = true
		row, err := s.store.Item(ctx, id)
		if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
			missing = append(missing, id)
			continue
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		states, err := s.prev.Request(ctx, row, jobs, store.PriorityWarm, base+int64(len(req.Items)-i))
		if err != nil {
			s.internal(w, r, err)
			return
		}
		e := entry{"id": id}
		for _, p := range req.Previews {
			switch p {
			case "thumb":
				e[p] = toPreviewState(states[previews.JobImage], previews.FileThumb, previews.FileThumbWebP)
			case "screen":
				e[p] = toPreviewState(states[previews.JobImage], previews.FileScreen, previews.FileScreenWebP)
			case "proxy":
				e[p] = toPreviewState(states[previews.JobProxy], previews.FileProxy, "")
			}
		}
		out = append(out, e)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out, "missing": missing})
}
