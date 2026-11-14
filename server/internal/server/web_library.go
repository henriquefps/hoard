package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"path"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/previews"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The library index (ADR 0018 "Library index"): one compact row per live item with what the grid shows and filters
// on, kept in memory and brought up to date from the items feed (items and culling share its counter), Reveal's
// records (the edited badge) and the previews table (thumbnail keys). A browser loads it once and then asks for the
// rows changed since its cursor. Filtering and search run in the browser over these rows.

// libRow is one item in the index. Short keys: the index of a 20,000-item library is about 4 MB of JSON (400 KB
// gzipped).
type libRow struct {
	ID     string `json:"id"`
	T      int64  `json:"t"`           // sortTime (ADR 0014), ms
	L      int64  `json:"l,omitempty"` // the camera's wall clock read as UTC, ms, when it differs from t (date headers)
	K      string `json:"k"`           // photo | pair | video | motion
	W      int    `json:"w,omitempty"` // upright size (orientation and video rotation applied)
	H      int    `json:"h,omitempty"`
	N      string `json:"n"`            // the main file's name
	Fo     string `json:"fo,omitempty"` // NAS folder id ("" = uploaded by a device)
	D      string `json:"d,omitempty"`  // directory in the folder, '/'-separated
	Cam    string `json:"cam,omitempty"`
	Lens   string `json:"lens,omitempty"`
	Raw    bool   `json:"raw,omitempty"`  // the main file is a RAW
	Dur    int64  `json:"dur,omitempty"`  // video duration, ms
	R      int    `json:"r,omitempty"`    // rating 1..5
	X      string `json:"x,omitempty"`    // flag: pick | reject
	Lb     string `json:"lb,omitempty"`   // colour label
	E      bool   `json:"e,omitempty"`    // edited in Reveal
	V      string `json:"v,omitempty"`    // image preview key when one is on disk (immutable thumbnail URLs)
	Fields string `json:"cr,omitempty"`   // culling field revisions "rating.flag.label" (baseRev of the next push)
	Size   int64  `json:"size,omitempty"` // bytes of all files
}

type libEntry struct {
	row    libRow
	gen    int64
	mainFP string
}

type cullState struct {
	rating, flag, label string // JSON values
	revs                [3]int64
}

type libraryIndex struct {
	st   *store.Store
	boot string

	mu          sync.Mutex
	gen         int64
	itemsRev    int64
	recordsRev  int64
	previewsAt  int64
	lastPreview time.Time
	rows        map[string]*libEntry
	removed     map[string]int64
	culling     map[string]cullState
	edited      map[string]bool
	previewKey  map[string]string
	folders     map[string]string // id → name (from item locations)
}

func newLibraryIndex(st *store.Store) *libraryIndex {
	return &libraryIndex{st: st, boot: strconv.FormatInt(time.Now().UnixNano()%1e12, 36), recordsRev: -1,
		rows: map[string]*libEntry{}, removed: map[string]int64{}, culling: map[string]cullState{}, edited: map[string]bool{},
		previewKey: map[string]string{}, folders: map[string]string{}}
}

// refresh applies everything that changed since the last call.
func (x *libraryIndex) refresh(ctx context.Context) error {
	head, err := x.st.Counter(ctx, "items")
	if err != nil {
		return err
	}
	if head != x.itemsRev {
		if err := x.applyFeed(ctx, head); err != nil {
			return err
		}
	}
	rec, err := x.st.Counter(ctx, "records")
	if err != nil {
		return err
	}
	if rec != x.recordsRev {
		edited, err := x.st.EditedFingerprints(ctx)
		if err != nil {
			return err
		}
		x.edited = edited
		x.recordsRev = rec
		for _, e := range x.rows {
			if v := edited[e.mainFP]; v != e.row.E {
				e.row.E = v
				x.touch(e)
			}
		}
	}
	if time.Since(x.lastPreview) >= 2*time.Second {
		x.lastPreview = time.Now()
		keys, err := x.st.PreviewKeysSince(ctx, previews.JobImage, x.previewsAt)
		if err != nil {
			return err
		}
		for _, k := range keys {
			x.previewsAt = max(x.previewsAt, k.UpdatedAt)
			if x.previewKey[k.ItemID] == k.Key {
				continue
			}
			x.previewKey[k.ItemID] = k.Key
			if e := x.rows[k.ItemID]; e != nil && e.row.V != k.Key {
				e.row.V = k.Key
				x.touch(e)
			}
		}
	}
	return nil
}

func (x *libraryIndex) touch(e *libEntry) {
	x.gen++
	e.gen = x.gen
}

func (x *libraryIndex) applyFeed(ctx context.Context, head int64) error {
	since := x.itemsRev
	for {
		rows, more, err := x.st.ItemChanges(ctx, since, 500)
		if err != nil {
			return err
		}
		for _, r := range rows {
			x.applyItem(r)
			since = r.Rev
		}
		if !more || len(rows) == 0 {
			break
		}
	}
	since = x.itemsRev
	for {
		recs, more, err := x.st.CullingChanges(ctx, since, 500)
		if err != nil {
			return err
		}
		for _, c := range recs {
			x.applyCulling(c)
			since = c.Rev
		}
		if !more || len(recs) == 0 {
			break
		}
	}
	x.itemsRev = head
	return nil
}

func (x *libraryIndex) applyItem(r store.ItemRow) {
	if r.Deleted {
		if _, ok := x.rows[r.ID]; ok {
			delete(x.rows, r.ID)
			x.gen++
			x.removed[r.ID] = x.gen
		}
		return
	}
	var d items.Data
	if err := json.Unmarshal(r.Data, &d); err != nil {
		return
	}
	row := libRow{ID: r.ID, T: r.SortTime, K: r.Kind, W: d.Width, H: d.Height, Lens: d.Lens}
	row.Cam = cameraName(d.Make, d.Model)
	if d.Capture != nil && d.Capture.Local != "" {
		if t, err := time.Parse("2006-01-02T15:04:05.000", padMs(d.Capture.Local)); err == nil && t.UnixMilli() != r.SortTime {
			row.L = t.UnixMilli()
		}
	}
	if d.Orientation >= 5 && d.Orientation <= 8 {
		row.W, row.H = row.H, row.W
	}
	if d.Video != nil {
		row.Dur = d.Video.DurationMs
		if row.W == 0 && d.Video.Width > 0 {
			row.W, row.H = d.Video.Width, d.Video.Height
			if d.Video.Rotation == 90 || d.Video.Rotation == 270 || d.Video.Rotation == -90 {
				row.W, row.H = row.H, row.W
			}
		}
	}
	for _, f := range d.Files {
		row.Size += f.Size
		if f.Fingerprint != d.MainFingerprint {
			continue
		}
		row.N = f.Name
		row.Raw = f.Role == media.RoleRaw
		for _, l := range f.Locations {
			if l.Source == "nas" {
				row.Fo, row.D = l.FolderID, path.Dir(l.Path)
				if row.D == "." {
					row.D = ""
				}
				if l.FolderName != "" {
					x.folders[l.FolderID] = l.FolderName
				}
				break
			}
		}
	}
	e := x.rows[r.ID]
	if e == nil {
		e = &libEntry{}
		x.rows[r.ID] = e
		delete(x.removed, r.ID)
	}
	e.mainFP = d.MainFingerprint
	row.E = x.edited[d.MainFingerprint]
	row.V = x.previewKey[r.ID]
	e.row = row
	x.setCulling(e, x.culling[r.ID])
	x.touch(e)
}

func (x *libraryIndex) applyCulling(c store.CullRecord) {
	var cs cullState
	for i, f := range store.CullFields {
		v, ok := c.Fields[f]
		if !ok {
			continue
		}
		cs.revs[i] = v.Rev
		switch f {
		case store.CullRating:
			cs.rating = string(v.Value)
		case store.CullFlag:
			cs.flag = string(v.Value)
		case store.CullLabel:
			cs.label = string(v.Value)
		}
	}
	x.culling[c.ItemID] = cs
	if e := x.rows[c.ItemID]; e != nil {
		x.setCulling(e, cs)
		x.touch(e)
	}
}

func (x *libraryIndex) setCulling(e *libEntry, cs cullState) {
	e.row.R, e.row.X, e.row.Lb, e.row.Fields = 0, "", "", ""
	if cs.rating != "" {
		_ = json.Unmarshal([]byte(cs.rating), &e.row.R)
	}
	if cs.flag != "" {
		var f string
		_ = json.Unmarshal([]byte(cs.flag), &f)
		if f == "pick" || f == "reject" {
			e.row.X = f
		}
	}
	if cs.label != "" {
		_ = json.Unmarshal([]byte(cs.label), &e.row.Lb)
	}
	if cs.revs != [3]int64{} {
		e.row.Fields = fmt.Sprintf("%d.%d.%d", cs.revs[0], cs.revs[1], cs.revs[2])
	}
}

func cameraName(make, model string) string {
	make, model = strings.TrimSpace(make), strings.TrimSpace(model)
	if make == "" {
		return model
	}
	// "Canon Canon EOS R5", "NIKON CORPORATION NIKON Z 6": keep the model when it already names the maker.
	first := strings.ToLower(strings.Fields(make)[0])
	if strings.HasPrefix(strings.ToLower(model), first) {
		return model
	}
	if model == "" {
		return make
	}
	return make + " " + model
}

func padMs(local string) string {
	if len(local) == 19 {
		return local + ".000"
	}
	return local
}

type libraryAnswer struct {
	Cursor  string            `json:"cursor"`
	Full    bool              `json:"full"`
	Rows    []libRow          `json:"rows"`
	Removed []string          `json:"removed"`
	Folders map[string]string `json:"folders"`
	Total   int               `json:"total"`
}

// answer returns the whole index (cursor from another boot, malformed or empty) or the changes after cursor.
func (x *libraryIndex) answer(cursor string) libraryAnswer {
	since := int64(-1)
	if b, g, ok := strings.Cut(cursor, ":"); ok && b == x.boot {
		if n, err := strconv.ParseInt(g, 10, 64); err == nil && n >= 0 && n <= x.gen {
			since = n
		}
	}
	out := libraryAnswer{Cursor: x.boot + ":" + strconv.FormatInt(x.gen, 10), Full: since < 0, Rows: []libRow{}, Removed: []string{},
		Folders: x.folders, Total: len(x.rows)}
	for _, e := range x.rows {
		if e.gen > since {
			out.Rows = append(out.Rows, e.row)
		}
	}
	if since >= 0 {
		for id, g := range x.removed {
			if g > since {
				out.Removed = append(out.Removed, id)
			}
		}
	}
	sort.Slice(out.Rows, func(i, j int) bool {
		a, b := out.Rows[i], out.Rows[j]
		if a.T != b.T {
			return a.T > b.T
		}
		return a.ID > b.ID
	})
	return out
}

func (s *Server) handleWebLibrary(w http.ResponseWriter, r *http.Request) {
	x := s.web.index
	x.mu.Lock()
	if err := x.refresh(r.Context()); err != nil {
		x.mu.Unlock()
		s.internal(w, r, err)
		return
	}
	ans := x.answer(r.URL.Query().Get("cursor"))
	body, err := json.Marshal(ans)
	x.mu.Unlock()
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeCompressed(w, r, body)
}

type folderDir struct {
	Path  string `json:"path"`
	Items int    `json:"items"`
}

type folderEntry struct {
	ID    string      `json:"id"`
	Name  string      `json:"name"`
	State string      `json:"state"`
	Items int         `json:"items"`
	Dirs  []folderDir `json:"dirs"`
}

// handleWebFolders lists the NAS folders (every folder the server indexes, also empty ones) with the number of items
// per directory (the browser builds the tree), and the items uploaded by devices.
func (s *Server) handleWebFolders(w http.ResponseWriter, r *http.Request) {
	folders, err := s.store.NasFolders(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	x := s.web.index
	x.mu.Lock()
	if err := x.refresh(r.Context()); err != nil {
		x.mu.Unlock()
		s.internal(w, r, err)
		return
	}
	counts := map[string]map[string]int{}
	uploads := 0
	for _, e := range x.rows {
		if e.row.Fo == "" {
			uploads++
			continue
		}
		if counts[e.row.Fo] == nil {
			counts[e.row.Fo] = map[string]int{}
		}
		counts[e.row.Fo][e.row.D]++
	}
	known := map[string]string{}
	for id, name := range x.folders {
		known[id] = name
	}
	x.mu.Unlock()
	out := []folderEntry{}
	seen := map[string]bool{}
	add := func(id, name, state string) {
		seen[id] = true
		fe := folderEntry{ID: id, Name: name, State: state, Dirs: []folderDir{}}
		for d, n := range counts[id] {
			fe.Dirs = append(fe.Dirs, folderDir{d, n})
			fe.Items += n
		}
		sort.Slice(fe.Dirs, func(i, j int) bool { return fe.Dirs[i].Path < fe.Dirs[j].Path })
		out = append(out, fe)
	}
	for _, f := range folders {
		state := f.State
		if !f.Enabled {
			state = "disabled"
		}
		add(f.ID, f.Name, state)
	}
	for id := range counts { // items whose folder was removed but which are still live (until the next walk)
		if !seen[id] {
			add(id, known[id], "removed")
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"folders": out, "uploads": uploads})
}
