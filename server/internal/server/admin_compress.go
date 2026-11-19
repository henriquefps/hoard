package server

import (
	"net/http"
	"os"
	"strconv"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The setup page's Compression section (ADR 0025): GPU status, the write mount, sessions, "Pause while the GPU is busy",
// the night window, "Offer AV1", the retention of what compression trashed, the accounting, recent jobs and item logs.

func (s *Server) routesAdminCompress(authed func(string, http.HandlerFunc)) {
	authed("GET /admin/api/compress", s.handleAdminCompress)
	authed("PUT /admin/api/compress/settings", s.handleAdminCompressSettings)
	authed("POST /admin/api/compress/detect", s.handleAdminCompressDetect)
	authed("GET /admin/api/compress/items/{id}/log", s.handleAdminCompressLog)
}

func (s *Server) handleAdminCompress(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	stats, err := s.store.CompressStats(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	acc := map[string]any{}
	var saved, inTrashBytes, encodeMs int64
	inTrash := 0
	var oldest *int64
	skipped, failed := map[string]int{}, map[string]int{}
	for _, st := range stats {
		encodeMs += st.EncMs
		switch st.State {
		case store.CompressPurged:
			saved += st.Size - st.OutSize
		case store.CompressReplaced, store.CompressRestored:
			inTrash += st.Items
			if st.State == store.CompressReplaced {
				inTrashBytes += st.Size
			} else {
				inTrashBytes += st.OutSize
			}
			if st.Oldest != nil && (oldest == nil || *st.Oldest < *oldest) {
				oldest = st.Oldest
			}
		case store.CompressSkipped:
			skipped[st.Reason] += st.Items
		case store.CompressFailed:
			failed[st.Reason] += st.Items
		}
	}
	acc["saved"], acc["inTrash"], acc["inTrashBytes"], acc["oldestInTrash"], acc["skipped"], acc["failed"], acc["gpuMs"] =
		saved, inTrash, inTrashBytes, oldest, skipped, failed, encodeMs
	jobs, _, err := s.store.CompressJobs(ctx, store.CompressJobQuery{Limit: 20})
	if err != nil {
		s.internal(w, r, err)
		return
	}
	list := make([]map[string]any, 0, len(jobs))
	for _, j := range jobs {
		list = append(list, s.jobJSON(ctx, j))
	}
	recent, _, _ := s.store.CompressItems(ctx, store.CompressItemQuery{After: 0, Limit: 1000})
	var last []store.CompressItem
	for i := len(recent) - 1; i >= 0 && len(last) < 50; i-- {
		last = append(last, recent[i])
	}
	items := s.compressItemsJSON(ctx, last)
	att, err := s.store.CompressAttentionNow(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	feature, featureWhy := s.compressFeature()
	writeJSON(w, http.StatusOK, map[string]any{"gpu": s.compress.Status(), "nasWrite": s.compress.NasWrite(), "writeProblem": s.compress.WriteProblem(),
		"feature": feature, "featureReason": featureWhy, "targets": s.compressTargets(), "attention": att, "sessions": s.compress.Sessions(),
		"pauseWhileBusy": s.compress.PauseWhileBusy(ctx), "window": s.compress.Window(ctx), "offerAv1": s.compress.OfferAV1(ctx),
		"trashDays": s.compress.TrashDays(ctx), "accounting": acc, "jobs": list, "items": items})
}

func (s *Server) handleAdminCompressSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		PauseWhileBusy *bool   `json:"pauseWhileBusy"`
		Window         *string `json:"window"`
		OfferAV1       *bool   `json:"offerAv1"`
		TrashDays      *int    `json:"trashDays"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	ctx := r.Context()
	if req.Window != nil && *req.Window != "" {
		if _, _, ok := compress.ParseWindow(*req.Window); !ok {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The window is HH:MM-HH:MM.", nil)
			return
		}
	}
	if req.TrashDays != nil && (*req.TrashDays < 1 || *req.TrashDays > 3650) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "The retention is 1 to 3650 days.", nil)
		return
	}
	set := func(k, v string) bool {
		if err := s.store.SetSetting(ctx, k, v); err != nil {
			s.internal(w, r, err)
			return false
		}
		s.log.Info("setting changed", k, v)
		return true
	}
	b := func(v bool) string {
		if v {
			return "1"
		}
		return "0"
	}
	if req.PauseWhileBusy != nil && !set(compress.SettingPauseWhileBusy, b(*req.PauseWhileBusy)) {
		return
	}
	if req.Window != nil && !set(compress.SettingWindow, *req.Window) {
		return
	}
	if req.OfferAV1 != nil && !set(compress.SettingOfferAV1, b(*req.OfferAV1)) {
		return
	}
	if req.TrashDays != nil && !set(compress.SettingTrashDays, strconv.Itoa(*req.TrashDays)) {
		return
	}
	s.compress.Wake()
	writeJSON(w, http.StatusOK, map[string]any{"pauseWhileBusy": s.compress.PauseWhileBusy(ctx), "window": s.compress.Window(ctx),
		"offerAv1": s.compress.OfferAV1(ctx), "trashDays": s.compress.TrashDays(ctx)})
}

func (s *Server) handleAdminCompressDetect(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.compress.Detect(r.Context()))
}

// handleAdminCompressLog serves an item's log (paths in it are SRC/OUT; metadata values stay in this file only).
func (s *Server) handleAdminCompressLog(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	}
	it, err := s.store.CompressItem(r.Context(), id)
	if err != nil {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	}
	b, err := os.ReadFile(s.compress.LogPath(it.JobID, it.ID))
	if err != nil {
		fail(w, r, http.StatusNotFound, "not_found", "No log for this item.", nil)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(b)
}
