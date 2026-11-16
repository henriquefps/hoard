package server

import (
	"errors"
	"net/http"

	"github.com/henriquefps/hoard/server/internal/mirror"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The setup page's phone backups (ADR 0024 "Security", "Ownership and storage accounting"): per phone its folders,
// storage counted once, the purge (the only way to delete backed-up content) and the server setting "Keep a copy of
// NAS files a phone no longer has".

func (s *Server) routesAdminBackup(authed func(string, http.HandlerFunc)) {
	authed("GET /admin/api/backup", s.handleAdminBackup)
	authed("PUT /admin/api/backup/settings", s.handleAdminBackupSettings)
	authed("POST /admin/api/backup/{physicalId}/purge", s.handleAdminBackupPurge)
	authed("PUT /admin/api/backup/{physicalId}/folder", s.handleAdminBackupRename)
}

func (s *Server) handleAdminBackup(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	devices, err := s.store.MirrorDevices(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	roots, err := s.store.MirrorRoots(ctx, "")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	usage, err := s.store.MirrorUsageAll(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	lost, err := s.store.LostBackups(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	c, err := s.rootContext(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	phones := make([]map[string]any, 0, len(devices))
	for _, d := range devices {
		rs := []rootView{}
		for _, rt := range roots {
			if rt.PhysicalID == d.PhysicalID {
				rs = append(rs, c.view(rt, ""))
			}
		}
		name := c.names[d.PhysicalID]
		if name == "" {
			name = d.DirName
		}
		u := usage[d.PhysicalID]
		u.PhysicalID = d.PhysicalID
		phones = append(phones, map[string]any{"physicalDeviceId": d.PhysicalID, "name": name, "folder": d.DirName, "createdAt": d.CreatedAt,
			"live": c.live[d.PhysicalID], "roots": rs, "usage": u})
	}
	writeJSON(w, http.StatusOK, map[string]any{"phones": phones, "lost": lost, "keepNasCopies": s.keepNasCopies(ctx),
		"available": s.mirrorTree() != nil, "queue": s.backupStatusLine()})
}

func (s *Server) handleAdminBackupSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		KeepNasCopies *bool `json:"keepNasCopies"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	ctx := r.Context()
	if req.KeepNasCopies != nil {
		v := "false"
		if *req.KeepNasCopies {
			v = "true"
		}
		if err := s.store.SetSetting(ctx, settingKeepNasCopies, v); err != nil {
			s.internal(w, r, err)
			return
		}
		s.log.Info("setting changed", "backupKeepNasCopies", v)
		if *req.KeepNasCopies { // the references whose phone file is gone are copied now
			s.checkNasReferences(ctx)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"keepNasCopies": s.keepNasCopies(ctx)})
}

// handleAdminBackupPurge deletes a phone's backup. The page shows the number of pinned (freed) entries first; the
// request must name it (confirmPinned), so a purge never removes the only copies of files freed since the page
// looked. Contents another phone, a claim or a live photo record still name are kept.
func (s *Server) handleAdminBackupPurge(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("physicalId")
	var req struct {
		ConfirmPinned *int `json:"confirmPinned"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	ctx := r.Context()
	if !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No backup of this phone.", nil)
		return
	}
	if _, err := s.store.MirrorDeviceByID(ctx, id); errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No backup of this phone.", nil)
		return
	}
	_, pinned, err := s.store.MirrorPurgeInfo(ctx, id)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if req.ConfirmPinned == nil || *req.ConfirmPinned != pinned {
		fail(w, r, http.StatusConflict, "conflict", "Confirm the number of files that are only in this backup (freed on the phone).",
			map[string]any{"reason": "confirm_pinned", "pinned": pinned})
		return
	}
	s.backup.fsMu.Lock()
	s.completeMu.Lock()
	res, err := s.store.PurgeMirror(ctx, id)
	s.completeMu.Unlock()
	if err != nil {
		s.backup.fsMu.Unlock()
		s.internal(w, r, err)
		return
	}
	if t := s.mirrorTree(); t != nil {
		if err := t.RemoveDevice(res.DirName); err != nil {
			s.log.Warn("backup: purged phone's folder stays", "err", errorClassOf(err))
		}
	}
	s.backup.fsMu.Unlock()
	s.removeBlobFiles(res.Removed)
	s.indexer.TriggerBackup()
	s.log.Info("backup: phone purged", "physicalDevice", id, "entries", res.Entries, "pinned", res.Pinned, "blobs", len(res.Removed))
	writeJSON(w, http.StatusOK, map[string]any{"entries": res.Entries, "pinned": res.Pinned, "removedFiles": len(res.Removed)})
}

func (s *Server) handleAdminBackupRename(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("physicalId")
	var req struct {
		Folder string `json:"folder"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	ctx := r.Context()
	d, err := s.store.MirrorDeviceByID(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No backup of this phone.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if !mirror.ValidDirName(req.Folder) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", `A folder name has 1 to 120 characters, none of / \ : * ? " < > |, and does not start or end with a dot or a space.`, nil)
		return
	}
	t := s.mirrorTree()
	if t == nil {
		fail(w, r, http.StatusServiceUnavailable, "storage_not_configured", "The backup folder is not available.", nil)
		return
	}
	s.backup.fsMu.Lock()
	defer s.backup.fsMu.Unlock()
	if err := t.RenameDevice(d.DirName, req.Folder); err != nil {
		fail(w, r, http.StatusConflict, "conflict", "That name is taken in the backup folder.", map[string]any{"reason": "exists"})
		return
	}
	if err := s.store.RenameMirrorDevice(ctx, id, req.Folder); err != nil {
		_ = t.RenameDevice(req.Folder, d.DirName)
		fail(w, r, http.StatusConflict, "conflict", "That name is taken in the backup folder.", map[string]any{"reason": "exists"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"folder": req.Folder})
}
