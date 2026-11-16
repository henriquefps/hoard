package server

import (
	"errors"
	"fmt"
	"net/http"
	"path"
	"slices"
	"strconv"
	"time"

	"github.com/henriquefps/hoard/server/internal/blobstore"
	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/mirror"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Phone backup (ADR 0024, hoard-server 0.12.0): additions under /v1 (routed in routesAPI). A device writes only its
// own physical device's roots: a root of another phone answers 404, like an unknown one. Logs never carry phone paths
// or file names.

type rootView struct {
	ID               string          `json:"id"`
	PhysicalDeviceID string          `json:"physicalDeviceId"`
	DeviceName       string          `json:"deviceName"`
	Mine             bool            `json:"mine"`
	Folder           string          `json:"folder"`
	Volume           string          `json:"volume"`
	Path             string          `json:"path"`
	CreatedBy        string          `json:"createdBy"`
	App              *string         `json:"app"`
	CreatedAt        int64           `json:"createdAt"`
	Types            []string        `json:"types"`
	Paused           bool            `json:"paused"`
	Stopped          bool            `json:"stopped"`
	Active           bool            `json:"active"`
	LastReportAt     *int64          `json:"lastReportAt"`
	Stats            store.RootStats `json:"stats"`
	Details          map[string]any  `json:"details,omitempty"`
}

type rootContext struct {
	names map[string]string
	live  map[string]bool
	stats map[string]store.RootStats
	now   time.Time
}

func (s *Server) rootContext(r *http.Request) (rootContext, error) {
	ctx := r.Context()
	names, err := s.store.PhysicalNames(ctx)
	if err != nil {
		return rootContext{}, err
	}
	live, err := s.store.PhysicalLive(ctx)
	if err != nil {
		return rootContext{}, err
	}
	stats, err := s.store.MirrorRootStats(ctx)
	if err != nil {
		return rootContext{}, err
	}
	return rootContext{names: names, live: live, stats: stats, now: s.store.Now()}, nil
}

func (c rootContext) view(rt store.MirrorRoot, mine string) rootView {
	name := c.names[rt.PhysicalID]
	if name == "" {
		name = rt.DirName
	}
	v := rootView{ID: rt.ID, PhysicalDeviceID: rt.PhysicalID, DeviceName: name, Mine: rt.PhysicalID == mine, Folder: rt.DirName,
		Volume: rt.Volume, Path: rt.Path, CreatedBy: rt.CreatedBy, CreatedAt: rt.CreatedAt, Types: rt.Types, Paused: rt.Paused(),
		Stopped: rt.Stopped(), Active: rootActive(rt, c.live, c.now), LastReportAt: rt.LastReportAt, Stats: c.stats[rt.ID]}
	if rt.App != "" {
		app := rt.App
		v.App = &app
	}
	return v
}

func (s *Server) handleBackupRoots(w http.ResponseWriter, r *http.Request) {
	roots, err := s.store.MirrorRoots(r.Context(), "")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	c, err := s.rootContext(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	out := make([]rootView, 0, len(roots))
	for _, rt := range roots {
		out = append(out, c.view(rt, info(r).device.PhysicalID))
	}
	writeJSON(w, http.StatusOK, map[string]any{"roots": out})
}

// readTypes validates a root's types: a non-empty subset of image, video, raw, sidecar.
func readTypes(in []string) ([]string, bool) {
	if len(in) == 0 || len(in) > len(backupTypes) {
		return nil, false
	}
	out := []string{}
	for _, t := range in {
		if !backupTypes[t] || slices.Contains(out, t) {
			return nil, false
		}
		out = append(out, t)
	}
	order := map[string]int{"image": 0, "video": 1, "raw": 2, "sidecar": 3}
	slices.SortFunc(out, func(a, b string) int { return order[a] - order[b] })
	return out, true
}

const typesProblem = "types is a non-empty list of image, video, raw and sidecar."

func (s *Server) handleBackupRootCreate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Volume string   `json:"volume"`
		Path   *string  `json:"path"`
		Types  []string `json:"types"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	volume, ok := mirror.CleanVolume(req.Volume)
	if !ok {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "volume is primary or the volume's UUID (1234-ABCD).", map[string]any{"field": "volume"})
		return
	}
	if req.Path == nil {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "path is required (\"\" = the whole volume).", map[string]any{"field": "path"})
		return
	}
	p, err := mirror.CleanPath(*req.Path, true)
	if err != nil {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "path: "+err.Error()+".", map[string]any{"field": "path"})
		return
	}
	var types []string
	if req.Types != nil {
		if types, ok = readTypes(req.Types); !ok {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", typesProblem, map[string]any{"field": "types"})
			return
		}
	}
	t := s.mirrorTree()
	if t == nil {
		fail(w, r, http.StatusServiceUnavailable, "storage_not_configured", "Choose a backup folder on the server's setup page first.", nil)
		return
	}
	ctx := r.Context()
	dev := info(r).device
	names, err := s.store.PhysicalNames(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	res, err := s.store.CreateMirrorRoot(ctx, store.NewMirrorRoot{PhysicalID: dev.PhysicalID, DeviceID: dev.ID, Volume: volume, Path: p,
		Types: types, DirName: mirror.DirName(names[dev.PhysicalID], dev.PhysicalID), MaxRoots: backupMaxRoots})
	var covered *store.CoveredError
	switch {
	case errors.As(err, &covered):
		fail(w, r, http.StatusConflict, "conflict", "This folder is inside a folder this phone already backs up.",
			map[string]any{"reason": "covered", "rootId": covered.RootID})
		return
	case errors.Is(err, store.ErrTooManyRoots):
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("A phone backs up at most %d folders.", backupMaxRoots),
			map[string]any{"maxRoots": backupMaxRoots})
		return
	case err != nil:
		s.internal(w, r, err)
		return
	}
	s.backup.fsMu.Lock()
	if err := t.EnsureDevice(res.Root.DirName); err != nil {
		s.log.Warn("backup: device folder", "err", errorClassOf(err))
	}
	s.backup.fsMu.Unlock()
	if len(res.Absorbed) > 0 {
		s.markGone(res.Root.DirName, res.Absorbed...)
		s.indexer.TriggerBackup()
	}
	s.markDirty(res.Root.ID)
	c, err := s.rootContext(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	v := c.view(res.Root, dev.PhysicalID)
	if len(res.Absorbed) > 0 {
		v.Details = map[string]any{"absorbed": res.Absorbed}
	}
	status := http.StatusOK
	if res.Created {
		status = http.StatusCreated
		s.log.Info("backup: root added", "device", dev.ID, "root", res.Root.ID)
	}
	writeJSON(w, status, v)
}

// ownRoot finds a root of the caller's physical device; another phone's root, or an unknown one, is 404.
func (s *Server) ownRoot(w http.ResponseWriter, r *http.Request, id string, details map[string]any) (store.MirrorRoot, bool) {
	if uuidPattern.MatchString(id) {
		rt, err := s.store.MirrorRoot(r.Context(), id)
		if err == nil && rt.PhysicalID == info(r).device.PhysicalID {
			return rt, true
		}
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			s.internal(w, r, err)
			return rt, false
		}
	}
	fail(w, r, http.StatusNotFound, "not_found", "No such backup folder on this phone.", details)
	return store.MirrorRoot{}, false
}

func (s *Server) handleBackupRootUpdate(w http.ResponseWriter, r *http.Request) {
	rt, ok := s.ownRoot(w, r, r.PathValue("rootId"), nil)
	if !ok {
		return
	}
	var req struct {
		Paused *bool    `json:"paused"`
		Types  []string `json:"types"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	var types []string
	if req.Types != nil {
		if types, ok = readTypes(req.Types); !ok {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", typesProblem, map[string]any{"field": "types"})
			return
		}
	}
	rt, err := s.store.UpdateMirrorRoot(r.Context(), rt.ID, req.Paused, types)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	c, err := s.rootContext(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, c.view(rt, info(r).device.PhysicalID))
}

// handleBackupRootStop stops a root: no new entries. Nothing a device sends deletes a backup (ADR 0024 "Security").
func (s *Server) handleBackupRootStop(w http.ResponseWriter, r *http.Request) {
	rt, ok := s.ownRoot(w, r, r.PathValue("rootId"), nil)
	if !ok {
		return
	}
	if err := s.store.StopMirrorRoot(r.Context(), rt.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	s.log.Info("backup: root stopped", "device", info(r).device.ID, "root", rt.ID)
	writeJSON(w, http.StatusOK, map[string]any{"status": "stopped"})
}

// phonePath validates a reported path: a phone path (mirror.CleanPath) inside the root, of a photo, video or sidecar.
func phonePath(rt store.MirrorRoot, p string, needMedia bool) (string, string) {
	c, err := mirror.CleanPath(p, false)
	if err != nil {
		return "", err.Error()
	}
	if !mirror.Within(rt.Path, c) || c == rt.Path {
		return "", "the path is not inside the root (paths are relative to the volume)"
	}
	if needMedia {
		if class, _ := media.Classify(path.Base(c)); class == "" {
			return "", "not a photo, video or sidecar"
		}
	}
	return c, ""
}

type reportEntryIn struct {
	Path        string `json:"path"`
	Fingerprint string `json:"fingerprint"`
	Size        int64  `json:"size"`
	ModifiedAt  int64  `json:"modifiedAt"`
	SHA256      string `json:"sha256"`
}

type reportRemovalIn struct {
	Path   string `json:"path"`
	Reason string `json:"reason"`
}

func (s *Server) handleBackupReport(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RootID  string            `json:"rootId"`
		Entries []reportEntryIn   `json:"entries"`
		Removed []reportRemovalIn `json:"removed"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Entries) > backupMaxReport || len(req.Removed) > backupMaxReport {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("At most %d entries and %d removals per report.", backupMaxReport, backupMaxReport), nil)
		return
	}
	rt, ok := s.ownRoot(w, r, req.RootID, nil)
	if !ok {
		return
	}
	bad := func(field string, i int, msg string) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", msg, map[string]any{"index": i, "field": field})
	}
	entries := make([]store.ReportEntry, len(req.Entries))
	for i, e := range req.Entries {
		p, problem := phonePath(rt, e.Path, true)
		switch {
		case problem != "":
			bad("entries", i, "path: "+problem+".")
			return
		case !fingerprint.Valid(e.Fingerprint):
			bad("entries", i, "fingerprint is v1: + 64 lower-case hex.")
			return
		case e.Size <= 0 || e.Size > 1<<50:
			bad("entries", i, "size must be positive.")
			return
		case e.ModifiedAt <= 0 || e.ModifiedAt > 1<<53:
			bad("entries", i, "modifiedAt (Unix milliseconds) is required.")
			return
		case e.SHA256 != "" && !blobstore.ValidSHA256(e.SHA256):
			bad("entries", i, "sha256 is 64 lower-case hex characters.")
			return
		}
		entries[i] = store.ReportEntry{Path: p, Fingerprint: e.Fingerprint, SHA256: e.SHA256, Size: e.Size, ModifiedAt: e.ModifiedAt}
	}
	removed := make([]store.ReportRemoval, len(req.Removed))
	for i, rm := range req.Removed {
		p, problem := phonePath(rt, rm.Path, false)
		if problem != "" {
			bad("removed", i, "path: "+problem+".")
			return
		}
		if rm.Reason != store.RemovedDeleted && rm.Reason != store.RemovedMoved && rm.Reason != store.RemovedFreed {
			bad("removed", i, "reason is deleted, moved or freed.")
			return
		}
		removed[i] = store.ReportRemoval{Path: p, Reason: rm.Reason}
	}
	if rt.Stopped() {
		fail(w, r, http.StatusConflict, "conflict", "This backup folder is stopped; add it again to resume.", map[string]any{"reason": "stopped"})
		return
	}
	if s.mirrorTree() == nil {
		fail(w, r, http.StatusServiceUnavailable, "storage_not_configured", "Choose a backup folder on the server's setup page first.", nil)
		return
	}
	ctx := r.Context()
	out, err := s.store.ApplyReport(ctx, rt, entries, removed, store.ReportOptions{DeviceID: info(r).device.ID,
		UploadLiveSince: s.store.Now().Add(-uploadLiveWindow).UnixMilli()})
	if err != nil {
		s.internal(w, r, err)
		return
	}
	unlinked, err := s.store.MirrorUnlinked(ctx, rt.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.reconcile(ctx, append(out.Touched, unlinked...))
	s.markDirty(rt.ID)
	if len(out.NasOnly) > 0 && s.keepNasCopies(ctx) {
		for _, id := range out.NasOnly {
			s.enqueue("copy:" + strconv.FormatInt(id, 10))
		}
	}
	s.indexer.TriggerBackup()
	// Item ids: the live item that holds each file now (absent until the items pass has made it).
	fps := make([]string, 0, len(out.Results))
	for _, res := range out.Results {
		fps = append(fps, res.Fingerprint)
	}
	files, err := s.store.ItemFilesByFingerprint(ctx, fps)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	itemOf := map[string]string{}
	for _, f := range files {
		if _, ok := itemOf[f.Fingerprint]; !ok {
			itemOf[f.Fingerprint] = f.ItemID
		}
	}
	results := make([]map[string]any, 0, len(out.Results))
	for _, res := range out.Results {
		m := map[string]any{"index": res.Index, "path": res.Path, "state": res.State}
		if id := itemOf[res.Fingerprint]; id != "" {
			m["itemId"] = id
		}
		verified := res.Verified
		if verified != "yes" && res.SHA256 != "" && s.pendingJob("hash:"+res.SHA256) {
			verified = "pending"
		}
		m["verified"] = verified
		if res.State == store.StateInProgress {
			m["retryAfter"] = uploadRetryAfter
		}
		results = append(results, m)
	}
	removedOut := make([]map[string]any, 0, len(out.Removed))
	for _, rm := range out.Removed {
		removedOut = append(removedOut, map[string]any{"index": rm.Index, "path": rm.Path, "state": rm.State})
	}
	s.touch(ctx, info(r).device.ID, true)
	writeJSON(w, http.StatusOK, map[string]any{"results": results, "removed": removedOut})
}

type verifyIn struct {
	RootID string `json:"rootId"`
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

// handleBackupVerify answers, for free up, whether the server's copy of each file is byte-identical to the phone's,
// proven by the server's own re-hash (ADR 0024 "POST /v1/backup/verify"). Re-hashes run in the background queue;
// until one is done the answer is pending.
func (s *Server) handleBackupVerify(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Entries []verifyIn `json:"entries"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Entries) == 0 || len(req.Entries) > backupMaxVerify {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("Send 1 to %d entries.", backupMaxVerify), nil)
		return
	}
	ctx := r.Context()
	roots := map[string]store.MirrorRoot{}
	paths := make([]string, len(req.Entries))
	for i, e := range req.Entries {
		rt, ok := roots[e.RootID]
		if !ok {
			if rt, ok = s.ownRoot(w, r, e.RootID, map[string]any{"index": i}); !ok {
				return
			}
			roots[e.RootID] = rt
		}
		p, problem := phonePath(rt, e.Path, false)
		bad := func(msg string) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", msg, map[string]any{"index": i})
		}
		switch {
		case problem != "":
			bad("path: " + problem + ".")
			return
		case !blobstore.ValidSHA256(e.SHA256):
			bad("sha256 is 64 lower-case hex characters.")
			return
		case e.Size <= 0:
			bad("size must be positive.")
			return
		}
		paths[i] = p
	}
	named, err := s.store.NamedFingerprints(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	keepCopies := s.keepNasCopies(ctx)
	now := s.store.Now()
	results := make([]map[string]any, 0, len(req.Entries))
	for i, in := range req.Entries {
		rt := roots[in.RootID]
		res := map[string]any{"index": i, "namedByRecord": false}
		no := func(reason string) {
			res["verified"], res["reason"] = "no", reason
		}
		pending := func() {
			res["verified"], res["retryAfter"] = "pending", verifyRetryAfter
		}
		e, err := s.store.MirrorEntryAt(ctx, rt.PhysicalID, rt.Volume, paths[i])
		if errors.Is(err, store.ErrNotFound) {
			no("not_found")
			results = append(results, res)
			continue
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		res["namedByRecord"] = named[e.Fingerprint]
		switch {
		case e.Size != in.Size || e.SHA256 != "" && e.SHA256 != in.SHA256:
			no("differs")
		case e.Store == store.StoreNone:
			no("missing")
		case e.Store == store.StoreNAS && keepCopies:
			// The server makes its own copy first: a phone file is never freed onto a share it cannot keep.
			key := "copy:" + strconv.FormatInt(e.ID, 10)
			if reason, failed := s.takeFailure(key); failed {
				no(reason)
				break
			}
			s.enqueue(key)
			pending()
		case e.Store == store.StoreNAS:
			key := "nas:" + e.Fingerprint
			if reason, failed := s.takeFailure(key); failed {
				no(reason)
				break
			}
			if e.VerifiedSHA != "" && e.VerifiedAt != nil && now.Sub(time.UnixMilli(*e.VerifiedAt)) < verifyValidFor && !s.pendingJob(key) {
				nf, err := s.store.NasFileForVerify(ctx, e.Fingerprint)
				switch {
				case errors.Is(err, store.ErrNotFound):
					no("missing")
				case err != nil:
					s.internal(w, r, err)
					return
				case nf.SHAMTime != nf.ModTime:
					s.indexer.Trigger()
					no("changed_on_nas")
				case e.VerifiedSHA == in.SHA256:
					res["verified"], res["verifiedAt"] = "yes", *e.VerifiedAt
				default:
					no("differs")
				}
				break
			}
			s.enqueue(key)
			pending()
		default: // stored bytes
			key := "hash:" + e.SHA256
			result, at, ok, err := s.store.BlobVerification(ctx, e.SHA256)
			if err != nil {
				s.internal(w, r, err)
				return
			}
			if !ok || now.Sub(time.UnixMilli(at)) >= verifyValidFor || s.pendingJob(key) {
				s.enqueue(key)
				pending()
				break
			}
			switch result {
			case in.SHA256:
				res["verified"], res["verifiedAt"] = "yes", at
			case "":
				no("missing")
			default:
				no("differs")
			}
		}
		results = append(results, res)
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

// backupView is physicalDevice.backup of GET /v1/device (ADR 0024): the caller's phone's roots, for Reveal's
// delegation and Hoard's settings.
type backupView struct {
	Roots []backupRootRef `json:"roots"`
}

type backupRootRef struct {
	ID     string   `json:"id"`
	Volume string   `json:"volume"`
	Path   string   `json:"path"`
	App    *string  `json:"app"`
	Active bool     `json:"active"`
	Since  int64    `json:"since"`
	Types  []string `json:"types"`
}

func (s *Server) physicalBackup(r *http.Request, physicalID string) (*backupView, error) {
	ctx := r.Context()
	roots, err := s.store.MirrorRoots(ctx, physicalID)
	if err != nil {
		return nil, err
	}
	live, err := s.store.PhysicalLive(ctx)
	if err != nil {
		return nil, err
	}
	v := &backupView{Roots: []backupRootRef{}}
	now := s.store.Now()
	for _, rt := range roots {
		ref := backupRootRef{ID: rt.ID, Volume: rt.Volume, Path: rt.Path, Active: rootActive(rt, live, now), Since: rt.CreatedAt, Types: rt.Types}
		if rt.App != "" {
			app := rt.App
			ref.App = &app
		}
		v.Roots = append(v.Roots, ref)
	}
	return v, nil
}
