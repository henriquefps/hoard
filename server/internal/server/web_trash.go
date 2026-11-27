package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"path"
	"sort"
	"strings"
	"time"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The trash from the web gallery (ADR 0023 Amendment (0.14.0)), admin session only:
//
//	POST /web/trash/move     {itemIds, dryRun?}    move a selection's shots to the trash (≤ 500 a request)
//	POST /web/trash/restore  {units}               put trashed shots back where they were
//	POST /web/trash/delete   {units}               delete trashed shots for good
//	POST /web/trash/empty    {confirm: {count, size}}  everything in the trash, after the count the page showed
//	GET  /web/trash                                every trashed shot, with its units (GET in web_compress.go)
//
// A NAS shot moves through the write mount (the same os.Root and operations as compression's journals) into
// <trash root>/.hoard-trash/<yyyy-MM-dd(-n)>/<path from the trash root>, the layout Hoard writes, so Hoard's Trash lists
// and restores it; an item that is not on the NAS (phone backups, uploads) goes to the server trash: it is trashed while
// its server_trash row exists, its blobs stay until the purge. Held entries (compression journals) and items whose
// compression is in flight are never touched; a compressed original is restored and emptied through compression's own
// journals.

const maxTrashBatch = 500

func (s *Server) routesWebTrash(authed func(string, http.HandlerFunc)) {
	authed("POST /web/trash/move", s.handleTrashMove)
	authed("POST /web/trash/restore", s.handleTrashRestore)
	authed("POST /web/trash/delete", s.handleTrashDelete)
	authed("POST /web/trash/empty", s.handleTrashEmpty)
}

// Skip and failure reasons of the trash routes.
const (
	trashNotFound      = "not_found"       // no such item, or a tombstone
	trashAlready       = "trashed"         // already in the trash
	trashHeld          = "held"            // a compression journal holds one of its files
	trashBusy          = "busy"            // a compression of one of its files is in flight
	trashOnPhone       = "on_phone"        // only a phone backup whose phone still has the file: delete it on the phone
	trashNothing       = "nothing"         // no copy the server can trash (no NAS file, no server copy)
	trashChanged       = "changed"         // the file at the path is not the indexed one any more
	trashPathTaken     = "path_taken"      // restore: another file took the original path
	trashNotWritable   = "not_writable"    // the folder cannot be written through the write mount
	trashNotSameDevice = "not_same_device" // the trash would be on another filesystem
	trashPartial       = "partial"         // some files of the shot moved, the others failed (the moved ones are in the trash)
	trashUnreadable    = "unreadable"      // a directory could not be listed
	trashState         = "state"           // a compressed original the compression cannot restore or empty now
)

// unfinishedCompress are the compression states during which an item's files must not move.
var unfinishedCompress = map[string]bool{store.CompressQueued: true, store.CompressEstimating: true, store.CompressEncoding: true,
	store.CompressVerifying: true, store.CompressReady: true, store.CompressReplacing: true}

type nasFileRef struct {
	fp      string
	rel     string
	size    int64
	mtimeMs int64
	sidecar bool
}

// nasUnit is one shot's files of one directory of one NAS folder (the unit that moves together, ADR 0023).
type nasUnit struct {
	folderID string
	dir      string
	stem     string // lower-case
	files    []nasFileRef
}

type trashPlan struct {
	id       string
	row      store.ItemRow
	d        items.Data
	units    []nasUnit
	server   bool // its server copies go to the server trash
	skip     string
	onlyCopy bool
	edits    bool
	onPhone  bool
	size     int64
	fps      []string
}

func (s *Server) planTrashMove(ctx context.Context, id string) (trashPlan, error) {
	p := trashPlan{id: id}
	row, err := s.store.Item(ctx, id)
	if errors.Is(err, store.ErrNotFound) || err == nil && row.Deleted {
		p.skip = trashNotFound
		return p, nil
	}
	if err != nil {
		return p, err
	}
	p.row = row
	if row.Trashed() {
		p.skip = trashAlready
		return p, nil
	}
	if err := json.Unmarshal(row.Data, &p.d); err != nil {
		return p, err
	}
	units := map[[4]string]*nasUnit{}
	var order [][4]string
	serverCopy := false
	for _, f := range p.d.Files {
		p.fps = append(p.fps, f.Fingerprint)
		counted := false
		for _, l := range f.Locations {
			if l.Held {
				p.skip = trashHeld
				return p, nil
			}
			switch l.Source {
			case items.SourceNAS:
				if l.Trash != "" {
					continue
				}
				dir := path.Dir(l.Path)
				if dir == "." {
					dir = ""
				}
				name := path.Base(l.Path)
				stem := strings.ToLower(media.Stem(name))
				sidecar := f.Role == media.RoleSidecar
				if sidecar {
					stem = sidecarStem(name)
				}
				k := [4]string{l.FolderID, dir, stem}
				u := units[k]
				if u == nil {
					u = &nasUnit{folderID: l.FolderID, dir: dir, stem: stem}
					units[k] = u
					order = append(order, k)
				}
				u.files = append(u.files, nasFileRef{fp: f.Fingerprint, rel: l.Path, size: f.Size, mtimeMs: l.ModifiedAt, sidecar: sidecar})
				if !counted {
					p.size += f.Size
					counted = true
				}
			case items.SourceMirror:
				if l.OnDevice != nil && *l.OnDevice {
					p.onPhone = true
				}
				if l.Gone == store.RemovedCompressed {
					continue
				}
				serverCopy = true
			case items.SourceBackup:
				serverCopy = true
			}
		}
		if !counted && serverCopy {
			p.size += f.Size
		}
	}
	recs, err := s.store.CompressItemsByFingerprint(ctx, p.fps)
	if err != nil {
		return p, err
	}
	for _, it := range recs {
		if unfinishedCompress[it.State] {
			p.skip = trashBusy
			return p, nil
		}
	}
	for _, k := range order {
		u := units[k]
		// A unit of sidecars only is no shot: its sidecars go with the media of the same stem (the shot rule below).
		media := false
		for _, f := range u.files {
			media = media || !f.sidecar
		}
		if media {
			p.units = append(p.units, *u)
		}
	}
	switch {
	case p.onPhone && len(p.units) == 0:
		p.skip = trashOnPhone
		return p, nil
	case len(p.units) == 0 && !serverCopy:
		p.skip = trashNothing
		return p, nil
	}
	p.server = serverCopy && !p.onPhone
	if len(p.units) == 0 && p.server {
		named := false
		for _, fp := range p.fps {
			if ok, err := s.store.RecordNames(ctx, fp); err != nil {
				return p, err
			} else if ok {
				named = true
				break
			}
		}
		p.onlyCopy = !named
	}
	p.edits = len(row.Edits) > 2 // "[]" is no edit
	return p, nil
}

// sidecarStem is the stem a sidecar belongs to: "X.xmp" and "X.ARW.xmp" belong to x (lower-case).
func sidecarStem(name string) string {
	stem := media.Stem(name)
	if m, _ := media.Classify(stem); m == media.MediaImage || m == media.MediaVideo {
		stem = media.Stem(stem)
	}
	return strings.ToLower(strings.TrimPrefix(stem, "."))
}

// sidecarCandidates are the lower-case sidecar names of a shot of stem whose files are names (ADR 0011 "Naming", the
// same list as Hoard's SidecarNames.candidates): <stem>.xmp, <file name>.xmp, and both hidden.
func sidecarCandidates(stem string, names []string) map[string]bool {
	out := map[string]bool{}
	add := func(n string) {
		n = strings.ToLower(n)
		out[n] = true
		out["."+n] = true
	}
	add(stem + ".xmp")
	for _, n := range names {
		add(n + ".xmp")
	}
	return out
}

func isSidecarName(name string) bool {
	return strings.HasSuffix(strings.ToLower(name), ".xmp") && !strings.HasPrefix(name, "._")
}

// unitFiles completes a unit from its directory's listing (the shot rule, ADR 0023 Amendment (A2) item 8): the
// selected media files, then the shot's sidecars (X.xmp, X.ARW.xmp, hidden .X.xmp) only when no other media file of
// that stem stays in the directory; a sidecar stays with the media member that stays.
func unitFiles(u nasUnit, listing []string) (mediaRels, sidecarRels []string) {
	moving := map[string]bool{}
	var names []string
	for _, f := range u.files {
		if f.sidecar {
			continue
		}
		mediaRels = append(mediaRels, f.rel)
		moving[strings.ToLower(path.Base(f.rel))] = true
		names = append(names, path.Base(f.rel))
	}
	last := true
	for _, n := range listing {
		if moving[strings.ToLower(n)] {
			continue
		}
		if m, role := media.Classify(n); m != "" && role != media.RoleSidecar && strings.ToLower(media.Stem(n)) == u.stem {
			last = false
			break
		}
	}
	if !last {
		return mediaRels, nil
	}
	cands := sidecarCandidates(u.stem, names)
	for _, n := range listing {
		if isSidecarName(n) && cands[strings.ToLower(n)] {
			sidecarRels = append(sidecarRels, joinRel(u.dir, n))
		}
	}
	sort.Strings(sidecarRels)
	return mediaRels, sidecarRels
}

func joinRel(dir, name string) string {
	if dir == "" {
		return name
	}
	return dir + "/" + name
}

type trashMoveAnswer struct {
	Moved    []string          `json:"moved"`
	Skipped  map[string]string `json:"skipped"`
	Failed   map[string]string `json:"failed"`
	Counts   map[string]int    `json:"counts"`
	Warnings map[string]any    `json:"warnings,omitempty"`
	// Dry run: what a move would do.
	NasShots    int    `json:"nasShots"`
	NasFiles    int    `json:"nasFiles"`
	ServerItems int    `json:"serverItems"`
	Size        int64  `json:"size"`
	NasBlocked  string `json:"nasBlocked,omitempty"` // the write mount's problem: NAS shots cannot move now
}

// handleTrashMove serves POST /web/trash/move and POST /v1/trash/move.
func (s *Server) handleTrashMove(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ItemIDs []string `json:"itemIds"`
		DryRun  bool     `json:"dryRun"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.ItemIDs) == 0 || len(req.ItemIDs) > maxTrashBatch {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("itemIds lists 1 to %d items.", maxTrashBatch), nil)
		return
	}
	ans, problem, err := s.trashMove(r.Context(), req.ItemIDs, req.DryRun, trashActor(r))
	switch {
	case err != nil:
		s.internal(w, r, err)
	case problem != "":
		// Nothing moves (the NAS shots cannot, and a half-done selection is harder to reason about than a refusal).
		unavailable(w, r, problem, "The server cannot write the NAS folders now: nothing was moved.")
	default:
		writeJSON(w, http.StatusOK, ans)
	}
}

// trashMove plans (dryRun) or moves items' shots to the trash for by ("admin" or a device id). problem is the write
// mount's problem when the request has NAS shots that cannot move (nothing moved).
func (s *Server) trashMove(ctx context.Context, ids []string, dryRun bool, by string) (trashMoveAnswer, string, error) {
	ans := trashMoveAnswer{Moved: []string{}, Skipped: map[string]string{}, Failed: map[string]string{}, Counts: map[string]int{}}
	var plans []trashPlan
	seen := map[string]bool{}
	var onlyCopy, edits []string
	for _, id := range ids {
		if seen[id] {
			continue
		}
		seen[id] = true
		if !itemIDPattern.MatchString(id) {
			ans.Skipped[id] = trashNotFound
			continue
		}
		var p trashPlan
		if err := store.RetryBusy(ctx, func() (err error) { p, err = s.planTrashMove(ctx, id); return err }); err != nil {
			return ans, "", err
		}
		if p.skip != "" {
			ans.Skipped[id] = p.skip
			continue
		}
		plans = append(plans, p)
		if p.onlyCopy {
			onlyCopy = append(onlyCopy, id)
		}
		if p.edits {
			edits = append(edits, id)
		}
		if len(p.units) > 0 {
			ans.NasShots++
			for _, u := range p.units {
				ans.NasFiles += len(u.files)
			}
		}
		if p.server {
			ans.ServerItems++
		}
		ans.Size += p.size
	}
	problem := ""
	if ans.NasShots > 0 {
		problem = s.compress.WriteProblem()
	}
	ans.Warnings = map[string]any{"onlyCopy": nonNil(onlyCopy), "edits": nonNil(edits)}
	if dryRun {
		ans.NasBlocked = problem
		s.countTrashAnswer(&ans)
		return ans, "", nil
	}
	if problem != "" {
		return ans, problem, nil
	}
	s.trashMu.Lock()
	defer s.trashMu.Unlock()
	now := s.store.Now()
	purgeAfter := now.Add(time.Duration(s.compress.TrashDays(ctx)) * 24 * time.Hour).UnixMilli()
	folders := map[string]bool{}
	open := map[string]*compress.Folder{}
	defer func() {
		for _, f := range open {
			_ = f.Close()
		}
	}()
	var rebuild []string
	servers := 0
	for _, p := range plans {
		var moved []movedFile // this item's moved files, to roll the shot back
		failed := ""
		for _, u := range p.units {
			f := open[u.folderID]
			if f == nil {
				var err error
				if f, err = s.compress.WriteFolder(ctx, u.folderID); err != nil {
					failed = trashNotWritable
					break
				}
				open[u.folderID] = f
			}
			got, reason := s.moveUnit(ctx, f, u, p.id, now, purgeAfter, by)
			moved = append(moved, got...)
			if reason != "" {
				failed = reason
				break
			}
		}
		// The server trash in the same step as the NAS moves: a shot is trashed whole or not at all.
		if failed == "" && p.server {
			row := store.ServerTrash{ItemID: p.id, MainFP: p.d.MainFingerprint, Fingerprints: p.fps, TrashedAt: now.UnixMilli(),
				PurgeAfter: purgeAfter, TrashedBy: by, Size: p.size}
			if err := s.store.AddServerTrash(ctx, []store.ServerTrash{row}); err != nil {
				s.log.Error("trash: server trash", "err", err)
				failed = "error"
			}
		}
		if failed != "" {
			// Roll the shot back: a shot is never left half in the trash. Only what cannot go back stays (partial).
			if left := s.unmoveFiles(ctx, open, moved); left > 0 {
				failed = trashPartial
				for _, m := range moved {
					folders[m.folderID] = true
				}
			}
			ans.Failed[p.id] = failed
			continue
		}
		for _, m := range moved {
			folders[m.folderID] = true
		}
		if p.server {
			servers++
			rebuild = append(rebuild, p.id)
		}
		ans.Moved = append(ans.Moved, p.id)
	}
	if len(rebuild) > 0 {
		if _, err := s.indexer.Rebuild(ctx, rebuild); err != nil {
			s.log.Error("trash: rebuild items", "err", err)
		}
	}
	s.settleTrashMoves(ctx) // a row this batch could not settle (the index busy) is settled now, not at the housekeeping
	s.walkFolders(folders)
	s.countTrashAnswer(&ans)
	s.log.Info("trash: moved", "by", by, "moved", len(ans.Moved), "skipped", len(ans.Skipped), "failed", len(ans.Failed),
		"server", servers, "reasons", reasonCounts(ans.Counts))
	return ans, "", nil
}

// reasonCounts is the skip and failure counts of an answer as one log value ("failed.not_writable=3 skipped.held=1").
func reasonCounts(counts map[string]int) string {
	var parts []string
	for k, n := range counts {
		if k != "moved" && n > 0 {
			parts = append(parts, fmt.Sprintf("%s=%d", k, n))
		}
	}
	sort.Strings(parts)
	return strings.Join(parts, " ")
}

func nonNil(v []string) []string {
	if v == nil {
		return []string{}
	}
	return v
}

func (s *Server) countTrashAnswer(a *trashMoveAnswer) {
	for _, why := range a.Skipped {
		a.Counts["skipped."+why]++
	}
	for _, why := range a.Failed {
		a.Counts["failed."+why]++
	}
	a.Counts["moved"] = len(a.Moved)
}

// movedFile is one file a request moved (into the trash, or out of it on a restore), with its journal row.
type movedFile struct {
	folderID string
	from, to string
	row      int64
	restore  bool // a restore: rolled back into the trash, the row put back as it was
	hadRow   bool // restore: the row was the server's move (back to trashed); else there is none to put back
}

// unmoveFiles rolls moved files back, newest first; it returns how many could not go back (they stay where they are,
// their journal rows saying so).
func (s *Server) unmoveFiles(ctx context.Context, open map[string]*compress.Folder, moved []movedFile) int {
	left := 0
	for i := len(moved) - 1; i >= 0; i-- {
		m := moved[i]
		f := open[m.folderID]
		if f == nil {
			left++
			continue
		}
		if err := f.Move(m.to, m.from); err != nil {
			s.log.Warn("trash: a file could not be put back", "err", errorClassOf(err))
			left++
			continue
		}
		var err error
		switch {
		case !m.restore:
			err = s.store.DeleteTrashMove(ctx, m.row) // back at its path: the server's move never happened
		case m.hadRow:
			err = s.store.SetTrashMoveState(ctx, m.row, store.MoveTrashed)
		}
		if err != nil {
			s.log.Error("trash: journal after a roll back", "err", err)
		}
		if m.restore {
			s.forgetTrashSettled(m.folderID, m.from)
		}
	}
	return left
}

// moveUnit moves one shot's files of one directory into the trash (the journal row of each file first). It returns the
// files it moved and why it stopped ("" when every file moved); the caller rolls a failed shot back.
func (s *Server) moveUnit(ctx context.Context, f *compress.Folder, u nasUnit, itemID string, now time.Time, purgeAfter int64, by string) ([]movedFile, string) {
	listing, err := f.List(u.dir)
	if err != nil {
		return nil, trashUnreadable
	}
	mediaRels, sidecars := unitFiles(u, listing)
	byRel := map[string]nasFileRef{}
	for _, fr := range u.files {
		byRel[fr.rel] = fr
	}
	// The files must still be the indexed ones (size and mtime).
	for _, rel := range mediaRels {
		fi, err := f.Lstat(rel)
		if err != nil || !fi.Mode().IsRegular() || !s.stillIndexed(ctx, u.folderID, byRel[rel], fi.Size(), fi.ModTime()) {
			return nil, trashChanged
		}
	}
	all := append(append([]string{}, mediaRels...), sidecars...)
	if err := f.ProbeTrash(all[0]); err != nil {
		if errors.Is(err, compress.ErrUnstableInode) {
			s.compress.NoteMountProblem("unstable_inode")
		}
		return nil, trashNotWritable
	}
	targets, err := f.TrashUnitPaths(all, now)
	if err != nil {
		return nil, trashNotWritable
	}
	var moved []movedFile
	for i, rel := range all {
		fi, err := f.Lstat(rel)
		if err != nil {
			if i < len(mediaRels) {
				return moved, trashChanged
			}
			continue // a sidecar gone meanwhile
		}
		id, err := s.store.PutTrashMove(ctx, store.TrashMove{FolderID: u.folderID, RelPath: rel, TrashPath: targets[i], State: store.MoveMoving,
			Size: fi.Size(), MTime: fi.ModTime().UnixNano(), ItemID: itemID, MovedAt: now.UnixMilli(), PurgeAfter: purgeAfter, MovedBy: by})
		if err != nil {
			s.log.Error("trash: journal", "err", err)
			return moved, "error"
		}
		if err := f.Move(rel, targets[i]); err != nil {
			if derr := s.store.DeleteTrashMove(ctx, id); derr != nil {
				s.log.Error("trash: journal", "err", derr) // settled after the batch (the file is still at its path)
			}
			s.log.Warn("trash: a file did not move", "err", errorClassOf(err))
			switch {
			case errors.Is(err, compress.ErrCrossDevice):
				return moved, trashNotSameDevice
			case errors.Is(err, compress.ErrTaken):
				return moved, trashPathTaken
			case errors.Is(err, fs.ErrNotExist):
				return moved, trashChanged
			}
			return moved, trashNotWritable
		}
		moved = append(moved, movedFile{folderID: u.folderID, from: rel, to: targets[i], row: id})
		if err := s.store.SetTrashMoveState(ctx, id, store.MoveTrashed); err != nil {
			// The file is in the trash; the row stays moving and the settle after the batch makes it trashed.
			s.log.Error("trash: journal", "err", err)
		}
		s.forgetTrashSettled(u.folderID, targets[i]) // trashed again at a path restored a moment ago
	}
	return moved, ""
}

// stillIndexed reports whether a file on disk is the one the item names (size and mtime). The item may lag behind the
// index while a walk rebuilds it (a walk the previous batch queued): then the folder's index row decides, when it is
// the same content (the member's fingerprint) at the size and mtime on disk.
func (s *Server) stillIndexed(ctx context.Context, folderID string, fr nasFileRef, size int64, mtime time.Time) bool {
	if size == fr.size && (fr.mtimeMs == 0 || mtime.UnixMilli() == fr.mtimeMs) {
		return true
	}
	row, err := s.store.NasFileAt(ctx, folderID, fr.rel)
	return err == nil && !row.Removed && row.Fingerprint != "" && row.Fingerprint == fr.fp && row.Size == size && row.MTime == mtime.UnixNano()
}

func (s *Server) forgetTrashSettled(folderID, trash string) {
	s.touchMu.Lock()
	delete(s.trashSettled, folderID+"|"+trash)
	s.touchMu.Unlock()
}

// trashUnitView is one unit of a trashed item in GET /web/trash: what Restore and Delete permanently act on.
type trashUnitView struct {
	Key        string `json:"key"`    // nas:<folderId>:<day folder> | server
	Source     string `json:"source"` // nas | server
	Day        string `json:"day"`    // yyyy-MM-dd
	FolderID   string `json:"folderId,omitempty"`
	FolderName string `json:"folderName,omitempty"`
	Dir        string `json:"dir"` // the original directory ("" = the folder's root); server: the phone path's directory
	Device     string `json:"device,omitempty"`
	Files      int    `json:"files"`
	Size       int64  `json:"size"`
	Held       bool   `json:"held,omitempty"`
	PurgeAfter *int64 `json:"purgeAfter,omitempty"` // when the server's auto-empty takes it (what the server trashed)
	ByServer   bool   `json:"byServer,omitempty"`   // the server moved it (the web gallery, a device or a compression)
	// By is who asked the server to move it (0.15.0): "admin" (the web gallery) or a device id; "" when the server did
	// not (Hoard over SMB).
	By string `json:"by,omitempty"`
	// Group is what Empty confirms separately (review of #84): "web" (moved from the web gallery: its NAS moves and
	// the server trash), "hoard" (trashed by Hoard on a device: through /v1 since 0.15.0, or over SMB), "compressed"
	// (an original of a compressed video, or an output a restore put in the trash).
	Group string `json:"group"`
}

// Groups of the trash for Empty.
const (
	trashGroupWeb        = "web"
	trashGroupHoard      = "hoard"
	trashGroupCompressed = "compressed"
)

// trashUnitsOf lists an item's trash units: its NAS trash locations by folder and day folder, and its server trash.
func (s *Server) trashUnitsOf(ctx context.Context, d items.Data, row store.ItemRow) []trashUnitView {
	type key struct{ folder, day string }
	byKey := map[key]*trashUnitView{}
	var order []key
	var srv, kept *trashUnitView
	for _, f := range d.Files {
		for _, l := range f.Locations {
			switch {
			case l.Source == items.SourceNAS && l.Trash != "":
				day := trashDayDir(l.Trash)
				if day == "" || s.trashSettledNow(l.FolderID, l.Trash) {
					continue
				}
				k := key{l.FolderID, day}
				u := byKey[k]
				if u == nil {
					dir := path.Dir(l.Path)
					if dir == "." {
						dir = ""
					}
					u = &trashUnitView{Key: "nas:" + l.FolderID + ":" + day, Source: "nas", Day: dayOf(day), FolderID: l.FolderID,
						FolderName: l.FolderName, Dir: dir}
					byKey[k] = u
					order = append(order, k)
				}
				u.Files++
				u.Size += f.Size
				u.Held = u.Held || l.Held
				if m, err := s.store.TrashMoveAt(ctx, l.FolderID, l.Trash); err == nil && m.Kind == store.MoveKindMove {
					u.ByServer = true
					u.By = m.MovedBy
					pa := m.PurgeAfter
					u.PurgeAfter = &pa
				}
			case d.ServerTrash != nil && (l.Source == items.SourceBackup || l.Source == items.SourceMirror && l.Gone != store.RemovedCompressed):
				if srv == nil {
					pa := d.ServerTrash.PurgeAfter
					srv = &trashUnitView{Key: "server", Source: "server", Day: time.UnixMilli(d.ServerTrash.Since).Format("2006-01-02"),
						PurgeAfter: &pa, ByServer: true}
				}
				if l.Source == items.SourceMirror && srv.Device == "" {
					srv.Device, srv.Dir = l.DeviceName, path.Dir(l.Path)
					if srv.Dir == "." {
						srv.Dir = ""
					}
				}
			case d.CompressedTo != "" && l.Source == items.SourceMirror && l.Gone == store.RemovedCompressed:
				// A phone or backup compression's kept side (ADR 0025 S2): restored and emptied by compression's routes.
				if kept == nil {
					dir := path.Dir(l.Path)
					if dir == "." {
						dir = ""
					}
					day := ""
					if row.TrashedAt != nil {
						day = time.UnixMilli(*row.TrashedAt).Format("2006-01-02")
					}
					kept = &trashUnitView{Key: "compressed", Source: "server", Day: day, Dir: dir, Device: l.DeviceName, ByServer: true}
				}
				kept.Files++
				kept.Size += f.Size
				kept.Held = kept.Held || l.Held
			}
		}
	}
	out := make([]trashUnitView, 0, len(order)+2)
	for _, k := range order {
		u := byKey[k]
		switch {
		case d.CompressedTo != "":
			u.Group = trashGroupCompressed
		case u.ByServer && u.By == "admin":
			u.Group = trashGroupWeb
		default:
			u.Group = trashGroupHoard
		}
		out = append(out, *u)
	}
	if kept != nil {
		kept.Group = trashGroupCompressed
		out = append(out, *kept)
	}
	if srv != nil {
		// Emptied already: the item leaves at the backup pass that drops its sources.
		if st, err := s.store.ServerTrashOf(ctx, row.ID); err != nil || st.PurgedAt != nil {
			srv = nil
		} else {
			srv.By = st.TrashedBy
		}
	}
	if srv != nil {
		for _, f := range d.Files {
			srv.Files++
			srv.Size += f.Size
		}
		srv.Group = trashGroupWeb
		if srv.By != "admin" {
			srv.Group = trashGroupHoard
		}
		out = append(out, *srv)
	}
	return out
}

// trashDayDir is the day folder of a path in a trash ("<P>/.hoard-trash/<day>"), "" for another path.
func trashDayDir(rel string) string {
	segs := strings.Split(rel, "/")
	for i, seg := range segs {
		if seg == compress.TrashDirName && i+2 < len(segs) {
			return strings.Join(segs[:i+2], "/")
		}
	}
	return ""
}

// dayOf is the date of a day folder ("…/.hoard-trash/2026-11-26-2" → "2026-11-26").
func dayOf(dayDir string) string {
	d := path.Base(dayDir)
	if len(d) >= 10 {
		return d[:10]
	}
	return d
}

// trashOriginOf maps "<P>/.hoard-trash/<day>/<R>" to "<P>/<R>".
func trashOriginOf(trash string) (string, bool) {
	segs := strings.Split(trash, "/")
	for i, seg := range segs {
		if seg == compress.TrashDirName && i+2 < len(segs) {
			return strings.Join(append(append([]string{}, segs[:i]...), segs[i+2:]...), "/"), true
		}
	}
	return "", false
}

type trashUnitRef struct {
	ItemID string `json:"itemId"`
	Key    string `json:"key"`
}

type trashActionAnswer struct {
	Done    []trashUnitRef    `json:"done"`
	Refused map[string]string `json:"refused"`         // "<itemId>|<key>" -> reason
	Names   map[string]string `json:"names,omitempty"` // path_taken: the file in the way
	// What a server purge kept (Reveal's library, a phone that has the file again, a compression).
	KeptBy []string `json:"keptBy,omitempty"`
	Size   int64    `json:"size"`
}

func refKey(u trashUnitRef) string { return u.ItemID + "|" + u.Key }

func (s *Server) readUnits(w http.ResponseWriter, r *http.Request) ([]trashUnitRef, bool) {
	var req struct {
		Units []trashUnitRef `json:"units"`
	}
	if !readJSON(w, r, &req) {
		return nil, false
	}
	if len(req.Units) == 0 || len(req.Units) > maxTrashBatch {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("units lists 1 to %d trashed shots.", maxTrashBatch), nil)
		return nil, false
	}
	for i, u := range req.Units {
		if !itemIDPattern.MatchString(u.ItemID) || u.Key != "server" && u.Key != "compressed" && !strings.HasPrefix(u.Key, "nas:") {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "A unit is {itemId, key} from GET /web/trash.", map[string]any{"index": i})
			return nil, false
		}
	}
	return req.Units, true
}

// handleTrashRestore serves POST /web/trash/restore and POST /v1/trash/restore.
func (s *Server) handleTrashRestore(w http.ResponseWriter, r *http.Request) {
	s.serveTrashAction(w, r, false)
}

// handleTrashDelete serves POST /web/trash/delete and POST /v1/trash/delete.
func (s *Server) handleTrashDelete(w http.ResponseWriter, r *http.Request) {
	s.serveTrashAction(w, r, true)
}

func (s *Server) serveTrashAction(w http.ResponseWriter, r *http.Request, purge bool) {
	units, ok := s.readUnits(w, r)
	if !ok {
		return
	}
	s.trashMu.Lock()
	defer s.trashMu.Unlock()
	ans := s.trashAction(r.Context(), units, purge)
	what := "restored"
	if purge {
		what = "deleted"
	}
	s.log.Info("trash: "+what, "by", trashActor(r), "units", len(ans.Done), "refused", len(ans.Refused), "reasons", refusalCounts(ans.Refused))
	writeJSON(w, http.StatusOK, ans)
}

// refusalCounts is the refusal reasons of an answer as one log value ("not_writable=2 path_taken=1").
func refusalCounts(refused map[string]string) string {
	counts := map[string]int{}
	for _, why := range refused {
		counts[why]++
	}
	return reasonCounts(counts)
}

// handleTrashEmpty (POST /web/trash/empty, POST /v1/trash/empty) deletes everything in the trash that is not held, after the page's confirmation: the count and
// size it showed must still be the trash's (else 409 confirm, with the current figures).
func (s *Server) handleTrashEmpty(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Confirm *struct {
			Count int   `json:"count"`
			Size  int64 `json:"size"`
		} `json:"confirm"`
		// Groups to empty (review of #84): web, hoard, compressed; absent = every group.
		Groups []string `json:"groups"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	want := map[string]bool{}
	for _, g := range req.Groups {
		if g != trashGroupWeb && g != trashGroupHoard && g != trashGroupCompressed {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "groups are web, hoard and compressed.", nil)
			return
		}
		want[g] = true
	}
	if req.Groups == nil {
		want = map[string]bool{trashGroupWeb: true, trashGroupHoard: true, trashGroupCompressed: true}
	}
	ctx := r.Context()
	s.trashMu.Lock()
	defer s.trashMu.Unlock()
	all, count, size, groups, err := s.allTrashUnits(ctx, want)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if req.Confirm == nil || req.Confirm.Count != count || req.Confirm.Size != size {
		fail(w, r, http.StatusConflict, "conflict", "Confirm the number of shots and the size the trash holds now.",
			map[string]any{"reason": "confirm", "count": count, "size": size, "groups": groups})
		return
	}
	ans := trashActionAnswer{Done: []trashUnitRef{}, Refused: map[string]string{}, Names: map[string]string{}}
	for start := 0; start < len(all); start += maxTrashBatch {
		part := s.trashAction(ctx, all[start:min(start+maxTrashBatch, len(all))], true)
		ans.Done = append(ans.Done, part.Done...)
		for k, v := range part.Refused {
			ans.Refused[k] = v
		}
		ans.Size += part.Size
		for _, k := range part.KeptBy {
			if !contains(ans.KeptBy, k) {
				ans.KeptBy = append(ans.KeptBy, k)
			}
		}
	}
	s.log.Info("trash: emptied", "by", trashActor(r), "groups", strings.Join(sortedKeys(want), ","), "units", len(ans.Done),
		"refused", len(ans.Refused), "reasons", refusalCounts(ans.Refused))
	writeJSON(w, http.StatusOK, ans)
}

func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}

type groupFigures struct {
	Count int   `json:"count"`
	Size  int64 `json:"size"`
}

// allTrashUnits lists every unit Empty takes (not held) in the wanted groups, with the number of shots and their size,
// and the figures of every group.
func (s *Server) allTrashUnits(ctx context.Context, want map[string]bool) ([]trashUnitRef, int, int64, map[string]groupFigures, error) {
	var out []trashUnitRef
	var size int64
	shots := map[string]bool{}
	groups := map[string]groupFigures{trashGroupWeb: {}, trashGroupHoard: {}, trashGroupCompressed: {}}
	groupShots := map[string]map[string]bool{}
	iq := store.ItemQuery{Trashed: true, Limit: 500}
	for {
		rows, more, err := s.store.QueryItems(ctx, iq)
		if err != nil {
			return nil, 0, 0, nil, err
		}
		for _, row := range rows {
			var d items.Data
			if json.Unmarshal(row.Data, &d) != nil {
				continue
			}
			for _, u := range s.trashUnitsOf(ctx, d, row) {
				if u.Held {
					continue
				}
				g := groups[u.Group]
				if groupShots[u.Group] == nil {
					groupShots[u.Group] = map[string]bool{}
				}
				if !groupShots[u.Group][row.ID] {
					groupShots[u.Group][row.ID] = true
					g.Count++
				}
				g.Size += u.Size
				groups[u.Group] = g
				if !want[u.Group] {
					continue
				}
				out = append(out, trashUnitRef{ItemID: row.ID, Key: u.Key})
				shots[row.ID] = true
				size += u.Size
			}
		}
		if !more || len(rows) == 0 {
			break
		}
		last := rows[len(rows)-1]
		iq.After = &store.ItemKey{SortTime: last.SortTime, ID: last.ID}
	}
	return out, len(shots), size, groups, nil
}

// trashAction restores (or, with purge, deletes for good) trashed units. Called with s.trashMu held.
func (s *Server) trashAction(ctx context.Context, units []trashUnitRef, purge bool) trashActionAnswer {
	ans := trashActionAnswer{Done: []trashUnitRef{}, Refused: map[string]string{}, Names: map[string]string{}}
	folders := map[string]bool{}
	open := map[string]*compress.Folder{}
	defer func() {
		for _, f := range open {
			_ = f.Close()
		}
	}()
	var serverIDs []string
	var serverRefs []trashUnitRef
	var compressIDs []int64
	compressRefs := map[int64][]trashUnitRef{}
	for _, u := range units {
		row, err := s.store.Item(ctx, u.ItemID)
		if err != nil || row.Deleted {
			ans.Refused[refKey(u)] = trashNotFound
			continue
		}
		var d items.Data
		if json.Unmarshal(row.Data, &d) != nil {
			ans.Refused[refKey(u)] = trashNotFound
			continue
		}
		var view *trashUnitView
		for _, v := range s.trashUnitsOf(ctx, d, row) {
			if v.Key == u.Key {
				view = &v
				break
			}
		}
		if view == nil {
			ans.Refused[refKey(u)] = trashNotFound
			continue
		}
		if view.Held {
			ans.Refused[refKey(u)] = trashHeld
			continue
		}
		// A compressed original (or a restored output in the trash) goes through compression's journal: only the
		// server's compression empties it, and its restore swaps the output back (ADR 0025). The server trash unit of
		// the same item is the server trash's, never compression's (0.15.0: an item in both).
		if id, ok := s.compressedTrashItem(ctx, row, d); ok && u.Key != "server" {
			compressIDs = append(compressIDs, id)
			compressRefs[id] = append(compressRefs[id], u)
			continue
		}
		if u.Key == "server" {
			serverIDs = append(serverIDs, u.ItemID)
			serverRefs = append(serverRefs, u)
			ans.Size += view.Size
			continue
		}
		if p := s.compress.WriteProblem(); p != "" {
			ans.Refused[refKey(u)] = p
			continue
		}
		f := open[view.FolderID]
		if f == nil {
			if f, err = s.compress.WriteFolder(ctx, view.FolderID); err != nil {
				ans.Refused[refKey(u)] = trashNotWritable
				continue
			}
			open[view.FolderID] = f
		}
		dayDir := strings.TrimPrefix(u.Key, "nas:"+view.FolderID+":")
		why, name := s.nasTrashUnit(ctx, f, d, view.FolderID, dayDir, purge)
		if why != "" {
			ans.Refused[refKey(u)] = why
			if name != "" {
				ans.Names[refKey(u)] = name
			}
			continue
		}
		folders[view.FolderID] = true
		ans.Done = append(ans.Done, u)
		ans.Size += view.Size
	}
	if len(serverIDs) > 0 {
		if purge {
			kept, err := s.purgeServerTrash(ctx, serverIDs)
			if err != nil {
				s.log.Error("trash: purge the server trash", "err", err)
				for _, u := range serverRefs {
					ans.Refused[refKey(u)] = "error"
				}
			} else {
				ans.Done = append(ans.Done, serverRefs...)
				ans.KeptBy = kept
			}
		} else {
			if _, err := s.store.RemoveServerTrash(ctx, serverIDs); err != nil {
				s.log.Error("trash: restore from the server trash", "err", err)
				for _, u := range serverRefs {
					ans.Refused[refKey(u)] = "error"
				}
			} else {
				if _, err := s.indexer.Rebuild(ctx, serverIDs); err != nil {
					s.log.Error("trash: rebuild items", "err", err)
				}
				ans.Done = append(ans.Done, serverRefs...)
			}
		}
	}
	if len(compressIDs) > 0 {
		var answers []compress.RestoreAnswer
		var kept map[int64][]string
		if purge {
			answers, kept = s.compress.PurgeItems(ctx, compressIDs)
		} else {
			answers = s.compress.RestoreItems(ctx, compressIDs)
		}
		answered := map[int64]bool{}
		for _, a := range answers {
			answered[a.ID] = true
			for _, u := range compressRefs[a.ID] {
				if a.Error != "" {
					ans.Refused[refKey(u)] = a.Error
				} else {
					ans.Done = append(ans.Done, u)
				}
			}
			for _, k := range kept[a.ID] {
				if !contains(ans.KeptBy, k) {
					ans.KeptBy = append(ans.KeptBy, k)
				}
			}
		}
		for id, refs := range compressRefs { // never silently dropped: an id compression did not answer
			if !answered[id] {
				for _, u := range refs {
					ans.Refused[refKey(u)] = "error"
				}
			}
		}
	}
	s.settleTrashMoves(ctx) // a row this batch could not settle (the index busy) is settled now
	s.walkFolders(folders)
	if len(ans.Names) == 0 {
		ans.Names = nil
	}
	return ans
}

// compressedTrashItem is the compression record that owns a trashed item's file (a compressed original, or an output a
// restore put in the trash): its restore and its purge are compression's.
func (s *Server) compressedTrashItem(ctx context.Context, row store.ItemRow, d items.Data) (int64, bool) {
	if d.CompressedTo == "" {
		return 0, false
	}
	recs, err := s.store.CompressItemsByFingerprint(ctx, []string{d.MainFingerprint})
	if err != nil {
		return 0, false
	}
	for _, it := range recs {
		if it.State == store.CompressReplaced || it.State == store.CompressRestored {
			return it.ID, true
		}
	}
	return 0, false
}

// nasTrashUnit restores (or deletes) the files of an item that are in one day folder of a NAS folder, with the hidden
// sidecars of the shot next to them. A restore is refused, never renamed, when a file is at an original path (name:
// that path); missing directories are recreated. Directories left empty go, up to the .hoard-trash (which stays).
func (s *Server) nasTrashUnit(ctx context.Context, f *compress.Folder, d items.Data, folderID, dayDir string, purge bool) (string, string) {
	type pair struct{ trash, origin string }
	var files []pair
	stems := map[string]map[string]bool{} // trash dir -> lower-case stems of the shot's files there
	names := map[string][]string{}
	for _, fl := range d.Files {
		for _, l := range fl.Locations {
			if l.Source != items.SourceNAS || l.FolderID != folderID || !strings.HasPrefix(l.Trash, dayDir+"/") {
				continue
			}
			files = append(files, pair{l.Trash, l.Path})
			dir := path.Dir(l.Trash)
			if stems[dir] == nil {
				stems[dir] = map[string]bool{}
			}
			base := path.Base(l.Trash)
			if fl.Role == media.RoleSidecar {
				stems[dir][sidecarStem(base)] = true
			} else {
				stems[dir][strings.ToLower(media.Stem(base))] = true
				names[dir] = append(names[dir], base)
			}
		}
	}
	// The hidden sidecars (".X.xmp") the walk never indexes travel with the shot.
	have := map[string]bool{}
	for _, p := range files {
		have[p.trash] = true
	}
	for dir, st := range stems {
		listing, err := f.List(dir)
		if err != nil {
			return trashUnreadable, ""
		}
		for stem := range st {
			cands := sidecarCandidates(stem, names[dir])
			for _, n := range listing {
				t := dir + "/" + n
				if have[t] || !isSidecarName(n) || !strings.HasPrefix(n, ".") || !cands[strings.ToLower(n)] {
					continue
				}
				if origin, ok := trashOriginOf(t); ok {
					files = append(files, pair{t, origin})
					have[t] = true
				}
			}
		}
	}
	if len(files) == 0 {
		return trashNotFound, ""
	}
	sort.Slice(files, func(i, j int) bool { return files[i].trash < files[j].trash })
	now := s.store.Now().UnixMilli()
	if !purge {
		for _, p := range files {
			if _, err := f.Lstat(p.origin); err == nil {
				return trashPathTaken, p.origin
			} else if !errors.Is(err, fs.ErrNotExist) {
				return trashNotWritable, ""
			}
		}
	}
	trashRoot := dayDir[:strings.LastIndex(dayDir, "/")]
	var restored []movedFile // a restore that fails half-way puts these back into the trash (0.15.0)
	open := map[string]*compress.Folder{folderID: f}
	undo := func() {
		if left := s.unmoveFiles(ctx, open, restored); left > 0 {
			s.log.Warn("trash: a restore stopped half-way and some files could not go back", "files", left)
		}
	}
	for _, p := range files {
		fi, err := f.Lstat(p.trash)
		if errors.Is(err, fs.ErrNotExist) {
			continue // gone already (emptied elsewhere): the walk follows
		}
		if err != nil {
			undo()
			return trashNotWritable, ""
		}
		state := store.MoveRestoring
		if purge {
			state = store.MovePurging
		}
		// The journal row: the server's own move keeps its row (kind move, its purge_after); a file the server did not
		// move (Hoard's) gets a row only for this operation (kind op: never due for the auto-empty, forgotten when the
		// operation ends or fails, never left behind as a trashed row).
		prior, perr := s.store.TrashMoveAt(ctx, folderID, p.trash)
		hadRow := perr == nil && prior.Kind == store.MoveKindMove && prior.State == store.MoveTrashed
		id, err := s.store.PutTrashMove(ctx, store.TrashMove{FolderID: folderID, RelPath: p.origin, TrashPath: p.trash, State: state,
			Size: fi.Size(), MTime: fi.ModTime().UnixNano(), MovedAt: now, PurgeAfter: now, Kind: store.MoveKindOp})
		if err != nil {
			s.log.Error("trash: journal", "err", err)
			undo()
			return trashNotWritable, ""
		}
		if purge {
			err = f.Purge(p.trash)
		} else {
			err = f.Move(p.trash, p.origin)
		}
		if err != nil {
			// Put the row back exactly as it was: the server's move trashed again with its own purge_after (the upsert
			// kept it), a row made for this operation forgotten.
			var jerr error
			if hadRow {
				jerr = s.store.SetTrashMoveState(ctx, id, store.MoveTrashed)
			} else {
				jerr = s.store.DeleteTrashMove(ctx, id)
			}
			if jerr != nil {
				s.log.Error("trash: journal", "err", jerr) // settled after the batch
			}
			undo()
			if errors.Is(err, compress.ErrTaken) {
				return trashPathTaken, p.origin
			}
			s.log.Warn("trash: a file was not restored or deleted", "err", errorClassOf(err))
			return trashNotWritable, ""
		}
		// The server's own row is kept until the whole shot is restored (a later failure puts the file back with it);
		// an operation's row goes at once (Hoard's file has no row, in the trash or not).
		if purge || !hadRow {
			if err := s.store.DeleteTrashMove(ctx, id); err != nil {
				s.log.Error("trash: journal", "err", err) // settled after the batch (the file left the trash)
			}
		}
		if !purge {
			m := movedFile{folderID: folderID, from: p.trash, to: p.origin, restore: true, hadRow: hadRow}
			if hadRow {
				m.row = id
			}
			restored = append(restored, m)
		}
		s.noteTrashSettled(folderID, p.trash)
	}
	if !purge {
		for _, m := range restored {
			if m.row != 0 {
				if err := s.store.DeleteTrashMove(ctx, m.row); err != nil {
					s.log.Error("trash: journal", "err", err)
				}
			}
		}
	}
	for _, p := range files {
		f.RemoveEmptyDirs(path.Dir(p.trash), trashRoot)
	}
	return "", ""
}

// trashSettledFor is how long a restored or deleted trash file is left out of GET /web/trash before the walk (asked at
// once, WalkGap after the folder's last one) updates its item.
const trashSettledFor = 10 * time.Minute

func (s *Server) noteTrashSettled(folderID, trash string) {
	s.touchMu.Lock()
	defer s.touchMu.Unlock()
	if s.trashSettled == nil {
		s.trashSettled = map[string]time.Time{}
	}
	now := time.Now()
	for k, at := range s.trashSettled {
		if now.Sub(at) > trashSettledFor {
			delete(s.trashSettled, k)
		}
	}
	s.trashSettled[folderID+"|"+trash] = now
}

func (s *Server) trashSettledNow(folderID, trash string) bool {
	s.touchMu.Lock()
	defer s.touchMu.Unlock()
	at, ok := s.trashSettled[folderID+"|"+trash]
	return ok && time.Since(at) <= trashSettledFor
}

// purgeServerTrash empties items from the server trash: the backup's entries and links, claims, and the blobs nothing
// else names. It returns what kept some bytes.
func (s *Server) purgeServerTrash(ctx context.Context, ids []string) ([]string, error) {
	s.backup.fsMu.Lock()
	res, err := s.store.PurgeServerTrash(ctx, ids)
	if err != nil {
		s.backup.fsMu.Unlock()
		return nil, err
	}
	var kept, removed []string
	t := s.mirrorTree()
	for _, p := range res {
		for _, e := range p.Links {
			if t != nil {
				if err := t.Remove(e.DirName, e.TreePath, e.SHA256); err != nil {
					s.log.Warn("trash: a backup link stays", "err", errorClassOf(err))
				}
			}
		}
		removed = append(removed, p.Removed...)
		for _, k := range p.KeptBy {
			if !contains(kept, k) {
				kept = append(kept, k)
			}
		}
	}
	s.backup.fsMu.Unlock()
	if err := s.removeReleasedBlobs(ctx, removed); err != nil {
		s.log.Warn("trash: a released blob's file stays", "err", errorClassOf(err))
	}
	s.indexer.TriggerBackup()
	s.log.Info("trash: server trash emptied", "items", len(res), "blobs", len(removed))
	return kept, nil
}

// trashHousekeeping settles the journal after a restart and runs the server's auto-empty (ADR 0023 Amendment
// (0.14.0)): what the server moved into a NAS trash, and the server trash, once their purge_after passed
// (compress.trashDays, 30 by default). Hoard's own auto-empty keeps covering every day folder on the shares.
func (s *Server) trashHousekeeping(ctx context.Context) {
	if !s.trashMu.TryLock() {
		return
	}
	defer s.trashMu.Unlock()
	now := s.store.Now()
	s.settleTrashMoves(ctx)
	if due, err := s.store.ServerTrashDue(ctx, now.UnixMilli()); err == nil && len(due) > 0 {
		ids := make([]string, 0, len(due))
		for _, d := range due {
			ids = append(ids, d.ItemID)
		}
		if _, err := s.purgeServerTrash(ctx, ids); err != nil {
			s.log.Error("trash: auto-empty of the server trash", "err", err)
		}
	}
	if _, err := s.store.DropPurgedServerTrash(ctx, now.Add(-24*time.Hour).UnixMilli()); err != nil {
		s.log.Error("trash: forget emptied server trash rows", "err", err)
	}
	due, err := s.store.TrashMovesDue(ctx, now.UnixMilli())
	if err != nil || len(due) == 0 || s.compress.WriteProblem() != "" {
		return
	}
	held := map[string]map[string]bool{}
	_ = s.store.ItemsTx(ctx, func(tx *store.ItemTx) error {
		for _, m := range due {
			if _, ok := held[m.FolderID]; !ok {
				h, err := tx.HeldTrash(m.FolderID)
				if err != nil {
					return err
				}
				held[m.FolderID] = h
			}
		}
		return nil
	})
	protected, err := s.compressedTrashPaths(ctx)
	if err != nil {
		return
	}
	folders := map[string]bool{}
	open := map[string]*compress.Folder{}
	defer func() {
		for _, f := range open {
			_ = f.Close()
		}
	}()
	n := 0
	for _, m := range due {
		if held[m.FolderID][m.TrashPath] || protected[m.FolderID+"|"+m.TrashPath] {
			continue
		}
		f := open[m.FolderID]
		if f == nil {
			if f, err = s.compress.WriteFolder(ctx, m.FolderID); err != nil {
				continue
			}
			open[m.FolderID] = f
		}
		fi, err := f.Lstat(m.TrashPath)
		if errors.Is(err, fs.ErrNotExist) {
			_ = s.store.DeleteTrashMove(ctx, m.ID) // emptied elsewhere
			continue
		}
		if err != nil || fi.Size() != m.Size || fi.ModTime().UnixNano() != m.MTime {
			continue // not the file the server moved: leave it (Hoard's rules decide)
		}
		_ = s.store.SetTrashMoveState(ctx, m.ID, store.MovePurging)
		if err := f.Purge(m.TrashPath); err != nil {
			_ = s.store.SetTrashMoveState(ctx, m.ID, store.MoveTrashed)
			continue
		}
		_ = s.store.DeleteTrashMove(ctx, m.ID)
		if day := trashDayDir(m.TrashPath); day != "" {
			f.RemoveEmptyDirs(path.Dir(m.TrashPath), day[:strings.LastIndex(day, "/")])
		}
		folders[m.FolderID] = true
		n++
	}
	if n > 0 {
		s.log.Info("trash: auto-empty", "files", n)
		s.walkFolders(folders)
	}
}

// compressedTrashPaths are the trash entries of compressed originals (and restored outputs) in NAS folders: only
// compression's own purge deletes them.
func (s *Server) compressedTrashPaths(ctx context.Context) (map[string]bool, error) {
	out := map[string]bool{}
	recs, err := s.store.CompressItemsIn(ctx, store.CompressReplaced, store.CompressRestored, store.CompressReplacing)
	if err != nil {
		return nil, err
	}
	for _, it := range recs {
		if it.TrashPath != "" {
			out[it.FolderID+"|"+it.TrashPath] = true
		}
	}
	return out, nil
}

// settleTrashMoves settles journal rows a restart left: a move whose file is in the trash is trashed, one whose file
// is still at its path is forgotten; a restore or a delete whose trashed file is gone is forgotten, else trashed again.
func (s *Server) settleTrashMoves(ctx context.Context) {
	rows, err := s.store.TrashMoves(ctx, store.MoveMoving, store.MoveRestoring, store.MovePurging)
	if err != nil || len(rows) == 0 {
		return
	}
	open := map[string]*compress.Folder{}
	defer func() {
		for _, f := range open {
			_ = f.Close()
		}
	}()
	folders := map[string]bool{}
	for _, m := range rows {
		f := open[m.FolderID]
		if f == nil {
			if f, err = s.compress.WriteFolder(ctx, m.FolderID); err != nil {
				continue // tried again later
			}
			open[m.FolderID] = f
		}
		_, terr := f.Lstat(m.TrashPath)
		inTrash := terr == nil
		if terr != nil && !errors.Is(terr, fs.ErrNotExist) {
			continue
		}
		switch {
		case m.Kind == store.MoveKindOp:
			// A restore or a delete of a file the server did not move, interrupted: whatever happened to the file, the
			// row was only the operation's; the file left in the trash stays Hoard's (never due for the auto-empty).
			_ = s.store.DeleteTrashMove(ctx, m.ID)
		case m.State == store.MoveMoving && inTrash:
			// A kill between the link and the unlink leaves both names of one file: the move is finished.
			if err := f.FinishMove(m.RelPath, m.TrashPath); err != nil {
				continue
			}
			_ = s.store.SetTrashMoveState(ctx, m.ID, store.MoveTrashed)
		case m.State == store.MoveMoving:
			_ = s.store.DeleteTrashMove(ctx, m.ID)
		case inTrash:
			_ = s.store.SetTrashMoveState(ctx, m.ID, store.MoveTrashed)
		default:
			_ = s.store.DeleteTrashMove(ctx, m.ID)
		}
		folders[m.FolderID] = true
	}
	s.walkFolders(folders)
}
