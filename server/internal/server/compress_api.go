package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"path"
	"strconv"
	"strings"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/mirror"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Video compression (ADR 0025, hoard-server 0.13.0): additions under /v1 that Reveal never calls. Targets "nas" (the
// library, phase S1), "phone" (the caller's phone files) and "backup" (phone backups no longer on their phone; phase
// S2).

const (
	compressMaxItemsPerJob = 5000
	compressMaxEstimate    = 500
	compressMaxBatch       = 1000
)

// compressInfo is GET /v1/server's "compress" object (present with the feature or with jobs).
func (s *Server) compressInfo(ctx context.Context) map[string]any {
	st := s.compress.Status()
	var reason any
	switch {
	case !st.Available:
		reason = st.Reason
	case s.compress.WriteProblem() != "":
		reason = s.compress.WriteProblem()
	}
	att, _ := s.store.CompressAttentionNow(ctx)
	profiles := append([]compress.Profile{}, compress.Profiles...)
	return map[string]any{"available": s.compress.FeatureAvailable(), "reason": reason, "gpu": st.GPU, "attention": att, "codecs": st.Codecs, "vmaf": st.VMAF,
		"cudaDecode": st.CUDADecode, "sessions": s.compress.Sessions(), "nasWrite": s.compress.NasWrite(), "offerAv1": s.compress.OfferAV1(ctx),
		"trashDays": s.compress.TrashDays(ctx), "profiles": profiles,
		"limits": map[string]any{"maxItemsPerJob": compressMaxItemsPerJob, "maxEstimate": compressMaxEstimate, "maxBatch": compressMaxBatch}}
}

// compressHasJobs reports whether any job exists (GET /v1/server keeps the compress object then).
func (s *Server) compressHasJobs(ctx context.Context) bool {
	jobs, _, err := s.store.CompressJobs(ctx, store.CompressJobQuery{Limit: 1})
	return err == nil && len(jobs) > 0
}

func unavailable(w http.ResponseWriter, r *http.Request, reason, msg string) {
	fail(w, r, http.StatusServiceUnavailable, "unavailable", msg, map[string]any{"reason": reason})
}

type compressRequest struct {
	Target    string          `json:"target"`
	Select    json.RawMessage `json:"select"`
	Profile   string          `json:"profile"`
	Options   json.RawMessage `json:"options"`
	Cursor    string          `json:"cursor"`
	Auto      bool            `json:"auto"`
	Name      string          `json:"name"`
	RequestID string          `json:"requestId"`
}

// parseCompressRequest validates target, selection, profile and options; it writes the error and returns false.
func (s *Server) parseCompressRequest(w http.ResponseWriter, r *http.Request, req compressRequest) (compress.Selection, compress.Profile, json.RawMessage, bool) {
	var sel compress.Selection
	switch req.Target {
	case compress.TargetNAS, compress.TargetPhone, compress.TargetBackup:
	default:
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "target is nas, phone or backup.", nil)
		return sel, compress.Profile{}, nil, false
	}
	if len(req.Select) == 0 || json.Unmarshal(req.Select, &sel) != nil {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "select is an object naming folders or itemIds.", nil)
		return sel, compress.Profile{}, nil, false
	}
	if err := sel.Validate(); err != nil {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", err.Error()+".", nil)
		return sel, compress.Profile{}, nil, false
	}
	if len(sel.Roots) > 0 && req.Target == compress.TargetNAS {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "roots are for phone and backup targets.", nil)
		return sel, compress.Profile{}, nil, false
	}
	if len(sel.Folders) > 0 && req.Target != compress.TargetNAS {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "folders are NAS folders (target nas); phone and backup targets name roots.", nil)
		return sel, compress.Profile{}, nil, false
	}
	if !s.validRoots(w, r, req.Target, sel.Roots) {
		return sel, compress.Profile{}, nil, false
	}
	for _, f := range sel.Folders {
		if !uuidPattern.MatchString(f.FolderID) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "folderId is a NAS folder id.", nil)
			return sel, compress.Profile{}, nil, false
		}
		if fo, err := s.store.NasFolder(r.Context(), f.FolderID); err != nil || !fo.Enabled {
			fail(w, r, http.StatusNotFound, "not_found", "No such NAS folder.", map[string]any{"folder": f.FolderID})
			return sel, compress.Profile{}, nil, false
		}
	}
	if len(sel.ItemIDs) > compressMaxItemsPerJob {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "At most 5000 items per job.", nil)
		return sel, compress.Profile{}, nil, false
	}
	for i, id := range sel.ItemIDs {
		if !itemIDPattern.MatchString(id) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "itemIds are item ids.", map[string]any{"index": i})
			return sel, compress.Profile{}, nil, false
		}
	}
	if req.Profile == "" {
		req.Profile = compress.DefaultProfile
	}
	prof, ok := compress.ProfileByID(req.Profile)
	if !ok {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "profile is hevc-cq28, hevc-cq30 or av1-cq34.", nil)
		return sel, prof, nil, false
	}
	if prof.OptIn && !s.compress.OfferAV1(r.Context()) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "AV1 is not offered on this server yet (admin page, Offer AV1).", nil)
		return sel, prof, nil, false
	}
	opts, err := compress.ParseOptions(req.Options)
	if err != nil {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", err.Error()+".", nil)
		return sel, prof, nil, false
	}
	raw, _ := json.Marshal(opts)
	return sel, prof, raw, true
}

func estimateItemJSON(e compress.EstimateItem) map[string]any {
	m := map[string]any{"itemId": e.ItemID, "decision": e.Decision}
	if e.Fingerprint != "" {
		m["fingerprint"], m["name"], m["size"], m["path"] = e.Fingerprint, path.Base(e.RelPath), e.Size, e.RelPath
		if e.MirrorEntryID != nil {
			m["rootId"], m["volume"] = e.RootID, e.Volume
		} else {
			m["folderId"] = e.FolderID
		}
	}
	if e.DurationMs > 0 {
		m["durationMs"] = e.DurationMs
	}
	if len(e.Others) > 0 {
		m["otherCopies"] = e.Others
	}
	switch e.Decision {
	case compress.DecisionSkip:
		m["reason"] = e.Reason
		if e.Reason == compress.ReasonSmallSaving {
			m["estSize"], m["estSaving"] = e.EstSize, round3(e.EstSaving)
		}
	case compress.DecisionEncode:
		m["estSize"], m["estSaving"], m["estMs"] = e.EstSize, round3(e.EstSaving), e.EstMs
		w := e.Warnings
		if w == nil {
			w = []string{}
		}
		m["warnings"] = w
	}
	return m
}

func round3(f float64) float64 { return float64(int64(f*1000+0.5)) / 1000 }

func (s *Server) handleCompressEstimate(w http.ResponseWriter, r *http.Request) {
	var req compressRequest
	if !readJSON(w, r, &req) {
		return
	}
	sel, prof, _, ok := s.parseCompressRequest(w, r, req)
	if !ok {
		return
	}
	if p := s.compress.WriteProblem(); p != "" && req.Target == compress.TargetNAS {
		unavailable(w, r, p, "NAS videos cannot be replaced on this server: "+p+" (NAS_WRITE_ROOT, README \"GPU compression\").")
		return
	}
	start := 0
	if req.Cursor != "" {
		n, err := strconv.Atoi(req.Cursor)
		if err != nil || n < 0 {
			fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued.", nil)
			return
		}
		start = n
	}
	cands, err := s.compress.ResolveTarget(r.Context(), req.Target, info(r).device.PhysicalID, sel)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	est, err := s.compress.EstimateAll(r.Context(), cands, prof)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	totals := map[string]any{}
	skip := map[string]int{}
	var size, estSize, estMs int64
	encode, pending := 0, 0
	for _, e := range est {
		switch e.Decision {
		case compress.DecisionEncode:
			encode++
			size += e.Size
			estSize += e.EstSize
			estMs += e.EstMs
		case compress.DecisionPending:
			pending++
		default:
			skip[e.Reason]++
		}
	}
	totals["items"], totals["encode"], totals["skip"], totals["size"], totals["estSize"], totals["estMs"] = len(est), encode, skip, size, estSize, estMs
	page := []map[string]any{}
	end := min(start+compressMaxEstimate, len(est))
	for i := start; i < end; i++ {
		page = append(page, estimateItemJSON(est[i]))
	}
	var next any
	if end < len(est) {
		next = strconv.Itoa(end)
	}
	if pending > 0 {
		w.Header().Set("Retry-After", "2")
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": page, "totals": totals, "pending": pending, "next": next})
}

func (s *Server) handleCompressJobCreate(w http.ResponseWriter, r *http.Request) {
	var req compressRequest
	if !readJSON(w, r, &req) {
		return
	}
	if req.RequestID == "" || len(req.RequestID) > 128 {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "requestId is the client's idempotency key (1 to 128 characters).", nil)
		return
	}
	if len(req.Name) > 200 {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "name is at most 200 characters.", nil)
		return
	}
	sel, prof, opts, ok := s.parseCompressRequest(w, r, req)
	if !ok {
		return
	}
	if req.Auto {
		if len(sel.Folders)+len(sel.Roots) != 1 || len(sel.ItemIDs) > 0 || sel.Filter != nil {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "auto takes a single folder.", nil)
			return
		}
		if prof.Codec == "av1" {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Folders compressed automatically use the default profile, never AV1.", nil)
			return
		}
	}
	if !s.compress.Available() {
		reason := s.compress.Status().Reason
		if reason == "" {
			reason = "no_gpu"
		}
		unavailable(w, r, reason, "This server cannot compress videos (no working NVIDIA encoder, or tools missing).")
		return
	}
	if s.blobStore() == nil {
		fail(w, r, http.StatusServiceUnavailable, "storage_not_configured", "Choose a backup folder on the setup page first.", nil)
		return
	}
	if p := s.compress.WriteProblem(); p != "" && req.Target == compress.TargetNAS {
		unavailable(w, r, p, "NAS videos cannot be replaced on this server: "+p+" (NAS_WRITE_ROOT, README \"GPU compression\").")
		return
	}
	// The selection as stored: an automatic folder is matched on it (one switch per folder).
	if req.Auto && len(sel.Folders) == 1 {
		rec := true
		f := sel.Folders[0]
		if f.Recursive != nil {
			rec = *f.Recursive
		}
		sel = compress.Selection{Folders: []compress.SelFolder{{FolderID: f.FolderID, Path: f.Path, Recursive: &rec}}}
	} else if req.Auto {
		sel = compress.Selection{Roots: []compress.SelFolder{{RootID: sel.Roots[0].RootID, Path: sel.Roots[0].Path}}}
	}
	physical := ""
	if req.Target == compress.TargetPhone {
		physical = info(r).device.PhysicalID
	}
	raw, _ := json.Marshal(sel)
	res, err := s.compress.CreateJob(r.Context(), compress.JobRequest{Target: req.Target, Selection: sel, RawSel: raw, Profile: prof,
		Options: opts, Auto: req.Auto, Name: req.Name, RequestID: req.RequestID, CreatedBy: info(r).device.ID, PhysicalID: physical})
	if errors.Is(err, compress.ErrExists) {
		jobs, _ := s.store.AutoCompressJobs(r.Context(), req.Target)
		details := map[string]any{"reason": "exists"}
		for _, j := range jobs {
			if string(j.Selection) == string(raw) {
				details["job"] = s.jobJSON(r.Context(), j)
			}
		}
		fail(w, r, http.StatusConflict, "conflict", "This folder is compressed automatically already.", details)
		return
	}
	if errors.Is(err, compress.ErrBusy) {
		fail(w, r, http.StatusConflict, "conflict", "Every video is in an unfinished job already.", map[string]any{"reason": "busy", "itemIds": res.Busy})
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	out := s.jobJSON(r.Context(), res.Job)
	if res.Existed {
		writeJSON(w, http.StatusOK, out)
		return
	}
	busy := res.Busy
	if busy == nil {
		busy = []string{}
	}
	out["queued"], out["skipped"], out["busy"] = res.Queued, res.Skipped, busy
	if !req.Auto && res.Queued == 0 && len(busy) > 0 {
		fail(w, r, http.StatusConflict, "conflict", "Every video is in an unfinished job already.", map[string]any{"reason": "busy", "itemIds": busy, "job": out})
		return
	}
	writeJSON(w, http.StatusCreated, out)
}

// jobJSON is a job as served: its counts per state, bytes, saving and progress.
func (s *Server) jobJSON(ctx context.Context, j store.CompressJob) map[string]any {
	counts, _ := s.store.CompressJobCounts(ctx, []string{j.ID})
	return s.jobWithCounts(ctx, j, counts[j.ID])
}

func (s *Server) jobWithCounts(ctx context.Context, j store.CompressJob, cs []store.CompressJobCount) map[string]any {
	states := map[string]int{}
	skipped := map[string]int{}
	failed := map[string]int{}
	var size, outSize, saved, trashBytes, estLeft int64
	done, todo := 0, 0
	for _, c := range cs {
		states[c.State] += c.Items
		switch c.State {
		case store.CompressSkipped:
			skipped[c.Reason] += c.Items
		case store.CompressFailed:
			failed[c.Reason] += c.Items
		case store.CompressReplaced:
			size += c.Size
			outSize += c.OutSize
			trashBytes += c.Size
		case store.CompressPurged:
			size += c.Size
			outSize += c.OutSize
			saved += c.Size - c.OutSize
		case store.CompressRestored:
			trashBytes += c.OutSize
		}
		switch c.State {
		case store.CompressQueued, store.CompressEstimating, store.CompressEncoding, store.CompressVerifying, store.CompressReady, store.CompressReplacing:
			todo += c.Items
			estLeft += c.EstMs
		default:
			done += c.Items
		}
	}
	var sel, opts any
	_ = json.Unmarshal(j.Selection, &sel)
	_ = json.Unmarshal(j.Options, &opts)
	out := map[string]any{"id": j.ID, "target": j.Target, "name": j.Name, "profile": j.Profile, "options": opts, "select": sel, "auto": j.Auto,
		"priority": j.Priority, "state": j.State, "paused": j.State == store.JobPaused, "createdAt": j.CreatedAt, "finishedAt": j.FinishedAt,
		"createdBy": j.CreatedBy, "counts": states, "skipped": skipped, "failed": failed,
		"bytes":    map[string]any{"original": size, "output": outSize, "saved": saved, "inTrash": trashBytes},
		"progress": map[string]any{"done": done, "toDo": todo, "etaMs": estLeft}}
	// The current item's percent.
	if running, _, err := s.store.CompressItems(ctx, store.CompressItemQuery{JobID: j.ID, States: []string{store.CompressEncoding, store.CompressVerifying}, Limit: 1}); err == nil && len(running) > 0 {
		it := running[0]
		cur := map[string]any{"id": strconv.FormatInt(it.ID, 10), "itemId": it.ItemID, "state": it.State}
		if p, ok := s.compress.Progress(it.ID); ok {
			cur["percent"] = int(p * 100)
		}
		out["progress"].(map[string]any)["current"] = cur
	}
	return out
}

func (s *Server) handleCompressJobs(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var jq store.CompressJobQuery
	switch v := q.Get("state"); v {
	case "", store.JobActive, store.JobPaused, store.JobCancelled, store.JobDone:
		jq.State = v
	default:
		fail(w, r, http.StatusBadRequest, "invalid_request", "state is active, paused, cancelled or done.", nil)
		return
	}
	switch q.Get("auto") {
	case "":
	case "true":
		t := true
		jq.Auto = &t
	case "false":
		f := false
		jq.Auto = &f
	default:
		fail(w, r, http.StatusBadRequest, "invalid_request", "auto is true or false.", nil)
		return
	}
	if c := q.Get("cursor"); c != "" {
		if !uuidPattern.MatchString(c) {
			fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued.", nil)
			return
		}
		jq.Before = c
	}
	limit, ok := pageLimit(r)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "limit must be a positive integer.", nil)
		return
	}
	jq.Limit = min(limit, 100)
	jobs, more, err := s.store.CompressJobs(r.Context(), jq)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	ids := make([]string, len(jobs))
	for i, j := range jobs {
		ids[i] = j.ID
	}
	counts, err := s.store.CompressJobCounts(r.Context(), ids)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	list := make([]map[string]any, 0, len(jobs))
	for _, j := range jobs {
		list = append(list, s.jobWithCounts(r.Context(), j, counts[j.ID]))
	}
	var next any
	if more && len(jobs) > 0 {
		next = jobs[len(jobs)-1].ID
	}
	writeJSON(w, http.StatusOK, map[string]any{"jobs": list, "cursor": next, "hasMore": more})
}

func (s *Server) compressJobFrom(w http.ResponseWriter, r *http.Request) (store.CompressJob, bool) {
	id := r.PathValue("jobId")
	if !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such job.", nil)
		return store.CompressJob{}, false
	}
	j, err := s.store.CompressJob(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No such job.", nil)
		return j, false
	}
	if err != nil {
		s.internal(w, r, err)
		return j, false
	}
	return j, true
}

func (s *Server) handleCompressJob(w http.ResponseWriter, r *http.Request) {
	if j, ok := s.compressJobFrom(w, r); ok {
		writeJSON(w, http.StatusOK, s.jobJSON(r.Context(), j))
	}
}

func (s *Server) handleCompressJobUpdate(w http.ResponseWriter, r *http.Request) {
	j, ok := s.compressJobFrom(w, r)
	if !ok {
		return
	}
	var req struct {
		Paused   *bool `json:"paused"`
		Priority *int  `json:"priority"`
		Auto     *bool `json:"auto"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Priority != nil && (*req.Priority < 0 || *req.Priority > 3) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "priority is 0 to 3.", nil)
		return
	}
	if req.Auto != nil && *req.Auto {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "auto can only be turned off (false).", nil)
		return
	}
	if j.State == store.JobCancelled || j.State == store.JobDone {
		if req.Paused != nil || req.Priority != nil {
			fail(w, r, http.StatusConflict, "conflict", "The job is finished.", map[string]any{"reason": "state"})
			return
		}
	}
	state := ""
	if req.Paused != nil && j.State != store.JobCancelled && j.State != store.JobDone {
		state = store.JobActive
		if *req.Paused {
			state = store.JobPaused
		}
	}
	prio := 0
	if req.Priority != nil {
		prio = *req.Priority
		if prio == 0 { // 0 is a valid priority: store it through the item rows only
			prio = -1
		}
	}
	autoOff := req.Auto != nil && !*req.Auto
	if err := s.store.SetCompressJob(r.Context(), j.ID, state, max(prio, 0), autoOff); err != nil {
		s.internal(w, r, err)
		return
	}
	if prio == -1 {
		if err := s.store.SetCompressItemsPriority(r.Context(), j.ID, 0); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if autoOff && j.Auto {
		if _, _, err := s.compress.Cancel(r.Context(), j.ID, nil); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	s.compress.Wake()
	j, _ = s.store.CompressJob(r.Context(), j.ID)
	writeJSON(w, http.StatusOK, s.jobJSON(r.Context(), j))
}

// compressItemJSON is a compression item as served.
func (s *Server) compressItemJSON(it store.CompressItem) map[string]any {
	m := map[string]any{"id": strconv.FormatInt(it.ID, 10), "jobId": it.JobID, "itemId": it.ItemID, "fingerprint": it.Fingerprint,
		"target": "nas", "source": it.Source, "state": it.State, "reason": it.Reason, "size": it.Size, "priority": it.Priority,
		"metaSidecar": it.MetaSidecar, "updatedAt": it.UpdatedAt, "attempts": it.Attempts}
	if it.Source != "nas" {
		m["target"] = "phone"
	}
	if it.FolderID != "" {
		m["folderId"], m["path"], m["name"] = it.FolderID, it.RelPath, path.Base(it.RelPath)
	}
	opt := func(k string, v any, ok bool) {
		if ok {
			m[k] = v
		}
	}
	opt("estSize", it.EstSize, it.EstSize != nil)
	opt("estMs", it.EstMs, it.EstMs != nil)
	opt("durationMs", it.DurationMs, it.DurationMs != nil)
	opt("q", it.Q, it.Q != nil)
	opt("outFingerprint", it.OutFingerprint, it.OutFingerprint != "")
	opt("outSize", it.OutSize, it.OutSize != nil)
	if it.VmafMean != nil {
		m["vmaf"] = map[string]any{"mean": round2(*it.VmafMean), "low": round2(deref(it.VmafLow)), "mode": it.VmafMode}
	}
	opt("encodeMs", it.EncodeMs, it.EncodeMs != nil)
	opt("verifyMs", it.VerifyMs, it.VerifyMs != nil)
	opt("decoder", it.Decoder, it.Decoder != "")
	opt("trashPath", it.TrashPath, it.TrashPath != "")
	opt("replacedAt", it.ReplacedAt, it.ReplacedAt != nil)
	opt("trashedAt", it.TrashedAt, it.TrashedAt != nil)
	opt("purgeAfter", it.PurgeAfter, it.PurgeAfter != nil && (it.State == store.CompressReplaced || it.State == store.CompressRestored))
	opt("purgedAt", it.PurgedAt, it.PurgedAt != nil)
	opt("restoredAt", it.RestoredAt, it.RestoredAt != nil)
	if p, ok := s.compress.Progress(it.ID); ok && it.State == store.CompressEncoding {
		m["progress"] = round3(p)
	}
	m["logAvailable"] = fileExists(s.compress.LogPath(it.JobID, it.ID))
	return m
}

func round2(f float64) float64 { return float64(int64(f*100+0.5)) / 100 }

func deref(p *float64) float64 {
	if p == nil {
		return 0
	}
	return *p
}

func (s *Server) handleCompressItems(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var iq store.CompressItemQuery
	if j := q.Get("jobId"); j != "" {
		if !uuidPattern.MatchString(j) {
			fail(w, r, http.StatusBadRequest, "invalid_request", "jobId is a job id.", nil)
			return
		}
		iq.JobID = j
	}
	if st := q.Get("state"); st != "" {
		iq.States = strings.Split(st, ",")
	}
	if q.Get("mine") == "true" { // phone items of the caller's phone
		iq.PhysicalID = info(r).device.PhysicalID
	}
	if c := q.Get("cursor"); c != "" {
		n, err := strconv.ParseInt(c, 10, 64)
		if err != nil || n < 0 {
			fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued.", nil)
			return
		}
		iq.After = n
	}
	limit, ok := pageLimit(r)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "limit must be a positive integer.", nil)
		return
	}
	iq.Limit = limit
	list, more, err := s.store.CompressItems(r.Context(), iq)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	out := s.compressItemsJSON(r.Context(), list)
	var next any
	if more && len(list) > 0 {
		next = strconv.FormatInt(list[len(list)-1].ID, 10)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out, "cursor": next, "hasMore": more})
}

// compressIDs reads {"itemIds": [...]}: compression item ids ("12") or gallery item ids (the newest compression of
// that item, as original or output). Unknown ones are answered not_found.
func (s *Server) compressIDs(ctx context.Context, ids []string) (map[string]int64, error) {
	out := map[string]int64{}
	for _, id := range ids {
		if n, err := strconv.ParseInt(id, 10, 64); err == nil && n > 0 {
			out[id] = n
			continue
		}
		if !itemIDPattern.MatchString(id) {
			continue
		}
		fp, err := s.store.ItemMainFingerprint(ctx, id)
		if err != nil || fp == "" {
			continue
		}
		list, err := s.store.CompressItemsByFingerprint(ctx, []string{fp})
		if err != nil {
			return nil, err
		}
		for _, it := range list { // newest first
			if it.State != store.CompressSkipped && it.State != store.CompressFailed && it.State != store.CompressCancelled {
				out[id] = it.ID
				break
			}
		}
	}
	return out, nil
}

type compressBatch struct {
	JobID   string   `json:"jobId"`
	ItemIDs []string `json:"itemIds"`
}

func (s *Server) readBatch(w http.ResponseWriter, r *http.Request, allowJob bool) (compressBatch, map[string]int64, bool) {
	var req compressBatch
	if !readJSON(w, r, &req) {
		return req, nil, false
	}
	if (req.JobID == "") == (len(req.ItemIDs) == 0) || (!allowJob && req.JobID != "") {
		msg := "Name either jobId or itemIds."
		if !allowJob {
			msg = "itemIds lists the items."
		}
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", msg, nil)
		return req, nil, false
	}
	if len(req.ItemIDs) > compressMaxBatch {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "At most 1000 items per request.", nil)
		return req, nil, false
	}
	if req.JobID != "" && !uuidPattern.MatchString(req.JobID) {
		fail(w, r, http.StatusNotFound, "not_found", "No such job.", nil)
		return req, nil, false
	}
	ids, err := s.compressIDs(r.Context(), req.ItemIDs)
	if err != nil {
		s.internal(w, r, err)
		return req, nil, false
	}
	return req, ids, true
}

func (s *Server) handleCompressCancel(w http.ResponseWriter, r *http.Request) {
	req, ids, ok := s.readBatch(w, r, true)
	if !ok {
		return
	}
	var list []int64
	for _, n := range ids {
		list = append(list, n)
	}
	if req.JobID != "" {
		if _, err := s.store.CompressJob(r.Context(), req.JobID); errors.Is(err, store.ErrNotFound) {
			fail(w, r, http.StatusNotFound, "not_found", "No such job.", nil)
			return
		}
	}
	cancelled, others, err := s.compress.Cancel(r.Context(), req.JobID, list)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	res := map[string]any{}
	for _, it := range cancelled {
		res[strconv.FormatInt(it.ID, 10)] = map[string]any{"state": it.State}
	}
	for _, it := range others {
		res[strconv.FormatInt(it.ID, 10)] = map[string]any{"state": it.State}
	}
	for _, id := range req.ItemIDs {
		if n, ok := ids[id]; ok {
			if v, ok := res[strconv.FormatInt(n, 10)]; ok {
				res[id] = v
				continue
			}
		}
		if _, ok := res[id]; !ok {
			res[id] = map[string]any{"error": "not_found"}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": res})
}

func (s *Server) answerBatch(w http.ResponseWriter, req compressBatch, ids map[string]int64, answers []compress.RestoreAnswer, kept map[int64][]string) {
	byID := map[int64]compress.RestoreAnswer{}
	for _, a := range answers {
		byID[a.ID] = a
	}
	res := map[string]any{}
	keys := req.ItemIDs
	if req.JobID != "" {
		keys = nil
		for _, a := range answers {
			keys = append(keys, strconv.FormatInt(a.ID, 10))
			ids[strconv.FormatInt(a.ID, 10)] = a.ID
		}
	}
	for _, k := range keys {
		n, ok := ids[k]
		if !ok {
			res[k] = map[string]any{"error": "not_found"}
			continue
		}
		a := byID[n]
		v := map[string]any{"id": strconv.FormatInt(n, 10)}
		if a.State != "" {
			v["state"] = a.State
		}
		if k := kept[n]; len(k) > 0 {
			v["alsoKeptBy"] = k
		}
		if a.Error != "" {
			v["error"] = a.Error
		}
		res[k] = v
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": res})
}

func (s *Server) handleCompressRestore(w http.ResponseWriter, r *http.Request) {
	req, ids, ok := s.readBatch(w, r, false)
	if !ok {
		return
	}
	var list []int64
	for _, n := range ids {
		list = append(list, n)
	}
	if !s.compress.NasWrite() && !s.anyBlobItem(r.Context(), list) {
		unavailable(w, r, "no_write_mount", "The server has no write mount for NAS folders (NAS_WRITE_ROOT).")
		return
	}
	s.answerBatch(w, req, ids, s.compress.RestoreItems(r.Context(), list), nil)
}

func (s *Server) handleCompressPurge(w http.ResponseWriter, r *http.Request) {
	req, ids, ok := s.readBatch(w, r, true)
	if !ok {
		return
	}
	var list []int64
	if req.JobID != "" {
		items, err := s.store.CompressItemsIn(r.Context(), store.CompressReplaced, store.CompressRestored)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		for _, it := range items {
			if it.JobID == req.JobID {
				list = append(list, it.ID)
			}
		}
	} else {
		for _, n := range ids {
			list = append(list, n)
		}
	}
	if !s.compress.NasWrite() && !s.anyBlobItem(r.Context(), list) && len(list) > 0 {
		unavailable(w, r, "no_write_mount", "The server has no write mount for NAS folders (NAS_WRITE_ROOT).")
		return
	}
	answers, kept := s.compress.PurgeItems(r.Context(), list)
	s.answerBatch(w, req, ids, answers, kept)
}

// anyBlobItem reports whether one of the compression items is a phone or backup item (they need no write mount).
func (s *Server) anyBlobItem(ctx context.Context, ids []int64) bool {
	for _, id := range ids {
		if it, err := s.store.CompressItem(ctx, id); err == nil && it.Source == "blob" {
			return true
		}
	}
	return false
}

// validRoots checks the roots of a phone or backup selection: mirror roots (a phone target's of the caller's own
// phone), each path a path on the root's volume inside the root.
func (s *Server) validRoots(w http.ResponseWriter, r *http.Request, target string, roots []compress.SelFolder) bool {
	for i, sr := range roots {
		if !uuidPattern.MatchString(sr.RootID) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "rootId is a phone backup root id.", map[string]any{"index": i})
			return false
		}
		rt, err := s.store.MirrorRoot(r.Context(), sr.RootID)
		if errors.Is(err, store.ErrNotFound) || err == nil && target == compress.TargetPhone && rt.PhysicalID != info(r).device.PhysicalID {
			fail(w, r, http.StatusNotFound, "not_found", "No such backup root (of this phone, for target phone).", map[string]any{"rootId": sr.RootID})
			return false
		}
		if err != nil {
			s.internal(w, r, err)
			return false
		}
		p, err := mirror.CleanPath(sr.Path, true)
		if err != nil || p != sr.Path || (p != "" && !mirror.Within(rt.Path, p)) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "A root's path is a path on its volume inside the root (\"\" = the whole root).",
				map[string]any{"index": i})
			return false
		}
	}
	return true
}

// compressItemsJSON serves compression items with their job's target and, for phone and backup items, the phone file
// they replace (root, volume, path), the original's SHA-256 and the output's (what the phone checks).
func (s *Server) compressItemsJSON(ctx context.Context, list []store.CompressItem) []map[string]any {
	targets := map[string]string{}
	var eids []int64
	for _, it := range list {
		if _, ok := targets[it.JobID]; !ok {
			if j, err := s.store.CompressJob(ctx, it.JobID); err == nil {
				targets[it.JobID] = j.Target
			}
		}
		if it.MirrorEntryID != nil {
			eids = append(eids, *it.MirrorEntryID)
		}
	}
	entries := map[int64]store.MirrorEntry{}
	if es, err := s.store.MirrorEntries(ctx, eids); err == nil {
		for _, e := range es {
			entries[e.ID] = e
		}
	}
	out := make([]map[string]any, 0, len(list))
	for _, it := range list {
		m := s.compressItemJSON(it)
		if t := targets[it.JobID]; t != "" {
			m["target"] = t
		}
		if it.Source == "blob" {
			if it.SHA256 != "" {
				m["sha256"] = it.SHA256
			}
			if it.OutSHA256 != "" {
				m["outSha256"] = it.OutSHA256
			}
			if it.ReplaceStep == store.StepRestorePhone || it.ReplaceStep == store.StepReswapPhone {
				m["direction"] = map[bool]string{true: "restore", false: "compress"}[it.ReplaceStep == store.StepRestorePhone]
			}
			if it.MirrorEntryID != nil {
				if e, ok := entries[*it.MirrorEntryID]; ok {
					m["rootId"], m["volume"], m["path"], m["name"], m["physicalDeviceId"] = e.RootID, e.Volume, e.Path, path.Base(e.Path), e.PhysicalID
					m["modifiedAt"] = e.ModifiedAt
				} else if it.RelPath != "" {
					m["path"], m["name"] = it.RelPath, path.Base(it.RelPath)
				}
			}
		}
		out = append(out, m)
	}
	return out
}

func (s *Server) handleCompressLookup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Fingerprints []string `json:"fingerprints"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Fingerprints) > maxLookup {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "At most 1000 fingerprints per lookup.", nil)
		return
	}
	for i, fp := range req.Fingerprints {
		if !fingerprint.Valid(fp) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Fingerprints are v1: + 64 lower-case hex.", map[string]any{"index": i})
			return
		}
	}
	list, err := s.store.CompressItemsByFingerprint(r.Context(), req.Fingerprints)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	jobs := map[string]store.CompressJob{}
	res := map[string]any{}
	for _, it := range list { // newest first: the first row of a fingerprint answers
		j, ok := jobs[it.JobID]
		if !ok {
			j, _ = s.store.CompressJob(r.Context(), it.JobID)
			jobs[it.JobID] = j
		}
		for _, side := range []struct{ fp, role, item string }{{it.Fingerprint, "original", it.ItemID}, {it.OutFingerprint, "output", items.ID(it.OutFingerprint)}} {
			if side.fp == "" {
				continue
			}
			if _, done := res[side.fp]; done {
				continue
			}
			want := false
			for _, f := range req.Fingerprints {
				want = want || f == side.fp
			}
			if !want || (side.role == "output" && it.State != store.CompressReplaced && it.State != store.CompressRestored && it.State != store.CompressPurged &&
				it.State != store.CompressReady && it.State != store.CompressReplacing) {
				continue
			}
			v := map[string]any{"role": side.role, "id": strconv.FormatInt(it.ID, 10), "itemId": side.item, "state": it.State, "jobId": it.JobID,
				"profile": j.Profile}
			if it.PurgeAfter != nil && (it.State == store.CompressReplaced || it.State == store.CompressRestored) {
				v["purgeAfter"] = *it.PurgeAfter
			}
			res[side.fp] = v
		}
	}
	missing := []string{}
	for _, fp := range req.Fingerprints {
		if _, ok := res[fp]; !ok {
			missing = append(missing, fp)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"fingerprints": res, "missing": missing})
}
