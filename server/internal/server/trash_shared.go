package server

import (
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// The trash for paired devices (ADR 0023 Amendment (0.15.0), hoard-server 0.15.0). The web gallery's trash operations
// (web_trash.go) serve both APIs with the same handlers: under /web/ with the gallery's session, under /v1/ with the
// device key.
//
//	GET  /v1/trash?cursor=     the trash (as GET /web/trash), the first page with each group's figures
//	POST /v1/trash/move        {itemIds, dryRun?}
//	POST /v1/trash/restore     {units}
//	POST /v1/trash/delete      {units}
//	POST /v1/trash/empty       {confirm: {count, size}, groups?}
//
// Who asked is recorded (trash_moves.moved_by, server_trash.trashed_by) and logged: "admin" for the web gallery, the
// device id for a device. The group of a unit follows it: web for the admin, hoard for a device (or Hoard over SMB).

// trashActor is who makes a trash request: the device of a /v1 request (device key), else the web gallery's admin
// (whose session carries a pseudo-device of its own).
func trashActor(r *http.Request) string {
	if d := info(r).device; d.ID != "" && strings.HasPrefix(r.URL.Path, "/v1/") {
		return d.ID
	}
	return "admin"
}

// trashWalkQuiet is how long the folders a trash request touched wait for the next request of the selection before
// their walk is queued (the gallery and Hoard send a selection in batches of 500: one walk after the last, not one per
// batch writing the index while the next batch writes the journal); trashWalkMax caps the wait after the first.
const (
	trashWalkQuiet = 5 * time.Second
	trashWalkMax   = time.Minute
)

type trashWalkState struct {
	mu      sync.Mutex
	pending map[string]bool
	first   time.Time
	timer   *time.Timer
}

// walkFolders queues a walk of the folders a trash operation touched, once the trash has been quiet for a moment
// (Config.TrashWalkQuiet): every request touching a folder pushes the walk back, up to trashWalkMax after the first.
func (s *Server) walkFolders(folders map[string]bool) {
	if len(folders) == 0 {
		return
	}
	quiet := s.cfg.TrashWalkQuiet
	if quiet == 0 {
		quiet = trashWalkQuiet
	}
	if quiet < 0 {
		s.indexer.WalkNow(sortedKeys(folders))
		return
	}
	tw := &s.trashWalk
	tw.mu.Lock()
	defer tw.mu.Unlock()
	if tw.pending == nil {
		tw.pending = map[string]bool{}
	}
	if len(tw.pending) == 0 {
		tw.first = time.Now()
	}
	for id := range folders {
		tw.pending[id] = true
	}
	wait := quiet
	if left := time.Until(tw.first.Add(trashWalkMax)); left < wait {
		wait = max(left, 0)
	}
	if tw.timer != nil {
		tw.timer.Stop()
	}
	tw.timer = time.AfterFunc(wait, s.flushTrashWalk)
}

// flushTrashWalk queues the walks the trash has been holding back.
func (s *Server) flushTrashWalk() {
	tw := &s.trashWalk
	tw.mu.Lock()
	ids := sortedKeys(tw.pending)
	tw.pending = map[string]bool{}
	tw.timer = nil
	tw.mu.Unlock()
	if len(ids) > 0 {
		s.indexer.WalkNow(ids)
	}
}

// pendingTrashWalks lists the folders whose walk waits for the trash to be quiet (tests).
func (s *Server) pendingTrashWalks() []string {
	tw := &s.trashWalk
	tw.mu.Lock()
	defer tw.mu.Unlock()
	return sortedKeys(tw.pending)
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
