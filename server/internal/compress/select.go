package compress

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Selection is what a job or an estimate names (ADR 0025 "Selections").
type Selection struct {
	Folders []SelFolder `json:"folders,omitempty"`
	Roots   []SelFolder `json:"roots,omitempty"` // phone and backup targets (phase S2)
	Filter  *SelFilter  `json:"filter,omitempty"`
	ItemIDs []string    `json:"itemIds,omitempty"`
}

// SelFolder is a folder (or a sub-folder) of a selection.
type SelFolder struct {
	FolderID  string `json:"folderId,omitempty"`
	RootID    string `json:"rootId,omitempty"`
	Path      string `json:"path,omitempty"`
	Recursive *bool  `json:"recursive,omitempty"` // default true
}

// SelFilter narrows a folder selection.
type SelFilter struct {
	From     *int64 `json:"from,omitempty"` // sort time, ms
	To       *int64 `json:"to,omitempty"`
	MinBytes int64  `json:"minBytes,omitempty"`
}

// Validate checks a selection's shape.
func (s Selection) Validate() error {
	if len(s.ItemIDs) == 0 && len(s.Folders) == 0 && len(s.Roots) == 0 {
		return errors.New("select names folders, roots or itemIds")
	}
	if len(s.ItemIDs) > 0 && (len(s.Folders) > 0 || len(s.Roots) > 0) {
		return errors.New("select names either folders or itemIds")
	}
	for _, f := range s.Folders {
		if f.FolderID == "" {
			return errors.New("every folder has a folderId")
		}
		p := f.Path
		if strings.HasPrefix(p, "/") || strings.HasSuffix(p, "/") || strings.Contains(p, "//") || strings.Contains(p, "\\") || len(p) > 1024 {
			return errors.New("a folder's path is relative, '/'-separated, without a leading or trailing '/'")
		}
		for _, seg := range strings.Split(p, "/") {
			if seg == ".." || seg == "." {
				return errors.New("a folder's path has no '.' or '..'")
			}
		}
	}
	return nil
}

// Candidate is one video of a selection.
type Candidate struct {
	ItemID      string
	Kind        string
	FolderID    string
	RelPath     string
	Fingerprint string
	Size        int64
	SortTime    int64
	Others      []string // the item's other NAS copies, left as they are
	Skip        string   // not_in_target, not_video, not_writable (decided before the rules)
}

// Resolve lists the videos of a NAS selection, newest first: one candidate per item (the copy under the selection).
func (m *Manager) Resolve(ctx context.Context, sel Selection) ([]Candidate, error) {
	byItem := map[string][]store.NasVideoFile{}
	var order []string
	add := func(list []store.NasVideoFile) {
		for _, f := range list {
			if _, ok := byItem[f.ItemID]; !ok {
				order = append(order, f.ItemID)
			}
			byItem[f.ItemID] = append(byItem[f.ItemID], f)
		}
	}
	var out []Candidate
	if len(sel.ItemIDs) > 0 {
		seen := map[string]bool{}
		var ids []string
		for _, id := range sel.ItemIDs {
			if !seen[id] {
				seen[id] = true
				ids = append(ids, id)
			}
		}
		for start := 0; start < len(ids); start += 500 {
			list, err := m.cfg.Store.NasVideoFiles(ctx, "", "", true, ids[start:min(start+500, len(ids))])
			if err != nil {
				return nil, err
			}
			add(list)
		}
		for _, id := range ids {
			if _, ok := byItem[id]; ok {
				continue
			}
			c := Candidate{ItemID: id, Skip: ReasonNotInTarget}
			if row, err := m.cfg.Store.Item(ctx, id); err == nil && !row.Deleted {
				c.Kind = row.Kind
				switch {
				case row.Trashed():
					c.Skip = ReasonTrashed
				case row.Kind != "video":
					c.Skip = ReasonNotVideo
				}
			}
			out = append(out, c)
		}
	} else {
		for _, f := range sel.Folders {
			rec := f.Recursive == nil || *f.Recursive
			list, err := m.cfg.Store.NasVideoFiles(ctx, f.FolderID, f.Path, rec, nil)
			if err != nil {
				return nil, err
			}
			add(list)
		}
	}
	writable := map[string]bool{}
	for _, id := range order {
		copies := byItem[id]
		sort.SliceStable(copies, func(i, j int) bool {
			if copies[i].FolderID != copies[j].FolderID {
				return copies[i].FolderID < copies[j].FolderID
			}
			return copies[i].RelPath < copies[j].RelPath
		})
		pick := copies[0]
		if sel.Filter != nil {
			if (sel.Filter.From != nil && pick.SortTime < *sel.Filter.From) || (sel.Filter.To != nil && pick.SortTime >= *sel.Filter.To) ||
				pick.Size < sel.Filter.MinBytes {
				continue
			}
		}
		c := Candidate{ItemID: id, Kind: pick.Kind, FolderID: pick.FolderID, RelPath: pick.RelPath, Fingerprint: pick.Fingerprint, Size: pick.Size,
			SortTime: pick.SortTime}
		for _, o := range copies[1:] {
			if o.FolderID != pick.FolderID || o.RelPath != pick.RelPath {
				c.Others = append(c.Others, o.FolderID+":"+o.RelPath)
			}
		}
		if c.Kind != "video" {
			c.Skip = ReasonNotVideo
		}
		ok, known := writable[c.FolderID]
		if !known {
			ok = m.underReadRoot(ctx, c.FolderID)
			writable[c.FolderID] = ok
		}
		if !ok && c.Skip == "" {
			c.Skip = ReasonNotWritable
		}
		out = append(out, c)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].SortTime > out[j].SortTime })
	return out, nil
}

// underReadRoot reports a NAS folder under the read mount the write mount mirrors.
func (m *Manager) underReadRoot(ctx context.Context, folderID string) bool {
	if m.cfg.Mount == nil || m.cfg.Mount.ReadRoot == "" {
		return false
	}
	dir, err := m.cfg.FolderDir(ctx, folderID)
	if err != nil {
		return false
	}
	root, err := filepath.EvalSymlinks(m.cfg.Mount.ReadRoot)
	if err != nil {
		return false
	}
	rel, err := filepath.Rel(root, dir)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, "../")
}

// EstimateItem is one row of an estimate.
type EstimateItem struct {
	Candidate
	Estimate
	DurationMs int64
}

// EstimateAll decides every candidate: probes are read from video_probe; a missing one is "pending" and read in the
// background (at most probeBatch at a time).
func (m *Manager) EstimateAll(ctx context.Context, cands []Candidate, prof Profile) ([]EstimateItem, error) {
	fps := make([]string, 0, len(cands))
	for _, c := range cands {
		if c.Fingerprint != "" {
			fps = append(fps, c.Fingerprint)
		}
	}
	probes, err := m.cfg.Store.VideoProbes(ctx, ProbeVersion, fps)
	if err != nil {
		return nil, err
	}
	held, outs, err := m.cfg.Store.CompressHeldFingerprints(ctx, fps)
	if err != nil {
		return nil, err
	}
	named, err := m.cfg.Store.NamedFingerprints(ctx)
	if err != nil {
		return nil, err
	}
	metas, err := m.cfg.Store.FileMetas(ctx, fps)
	if err != nil {
		return nil, err
	}
	var missing []Candidate
	out := make([]EstimateItem, 0, len(cands))
	for _, c := range cands {
		ei := EstimateItem{Candidate: c}
		if c.Skip != "" {
			ei.Estimate = Estimate{Decision: DecisionSkip, Reason: c.Skip}
			out = append(out, ei)
			continue
		}
		in := Input{Kind: c.Kind, Paths: []string{c.RelPath}, Size: c.Size, Busy: held[c.Fingerprint], IsOutput: outs[c.Fingerprint],
			RevealNamed: named[c.Fingerprint]}
		var meta media.Meta
		if json.Unmarshal(metas[c.Fingerprint], &meta) == nil && meta.Video != nil {
			in.HDR = meta.Video.HDR
			ei.DurationMs = meta.Video.DurationMs
		}
		if b, ok := probes[c.Fingerprint]; ok {
			var p Probe
			if json.Unmarshal(b, &p) == nil {
				in.Probe = &p
				if p.DurationMs > 0 {
					ei.DurationMs = p.DurationMs
				}
			}
		}
		ei.Estimate = Decide(in, prof)
		if ei.Decision == DecisionPending {
			missing = append(missing, c)
		}
		out = append(out, ei)
	}
	if len(missing) > 0 {
		m.probeLater(missing)
	}
	return out, nil
}

// probeLater reads missing probes in the background (two at a time).
func (m *Manager) probeLater(cands []Candidate) {
	m.mu.Lock()
	var todo []Candidate
	for _, c := range cands {
		if !m.probing[c.Fingerprint] {
			m.probing[c.Fingerprint] = true
			todo = append(todo, c)
		}
	}
	m.mu.Unlock()
	if len(todo) == 0 {
		return
	}
	go func() {
		ctx := context.Background()
		sem := make(chan struct{}, 2)
		var wg sync.WaitGroup
		for _, c := range todo {
			wg.Add(1)
			sem <- struct{}{}
			go func(c Candidate) {
				defer wg.Done()
				defer func() { <-sem }()
				defer func() {
					m.mu.Lock()
					delete(m.probing, c.Fingerprint)
					m.mu.Unlock()
				}()
				f, _, err := m.openNas(ctx, store.CompressItem{FolderID: c.FolderID, RelPath: c.RelPath, Size: c.Size, Fingerprint: c.Fingerprint})
				if err != nil {
					b, _ := json.Marshal(Probe{Unreadable: true})
					if errors.Is(err, ErrChanged) {
						return // the walk will see the change
					}
					_ = m.cfg.Store.PutVideoProbe(ctx, c.Fingerprint, ProbeVersion, b)
					return
				}
				defer f.Close()
				if _, err := m.ProbeOf(ctx, c.Fingerprint, f); err != nil {
					m.log.Warn("compress: probe", "err", err.Error())
				}
			}(c)
		}
		wg.Wait()
	}()
}

// ProbesPending reports whether background probes are running.
func (m *Manager) ProbesPending() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.probing) > 0
}

// JobRequest is a job to create.
type JobRequest struct {
	Target    string
	Selection Selection
	RawSel    json.RawMessage
	Profile   Profile
	Options   json.RawMessage
	Auto      bool
	Name      string
	RequestID string
	CreatedBy string
}

// JobResult is a created job.
type JobResult struct {
	Job     store.CompressJob
	Existed bool
	Queued  int
	Skipped map[string]int
	Busy    []string // item ids already in an unfinished job
	Items   []EstimateItem
}

// ErrExists is a folder already compressed automatically; ErrBusy a selection whose every video is in an unfinished
// job (no job is made).
var (
	ErrExists = errors.New("exists")
	ErrBusy   = errors.New("busy")
)

// CreateJob estimates again and stores the job with its items (those to encode queued; the others recorded as
// skipped, a busy one only listed). An automatic job covers only videos that appear after it: none is queued now.
func (m *Manager) CreateJob(ctx context.Context, r JobRequest) (JobResult, error) {
	var res JobResult
	if r.RequestID != "" {
		if old, err := m.jobByRequest(ctx, r.RequestID); err == nil {
			res.Job, res.Existed = old, true
			return res, nil
		}
	}
	priority := 2
	if r.Auto {
		priority = 3
	}
	j := store.CompressJob{ID: store.NewUUID(), Target: r.Target, CreatedBy: r.CreatedBy, Name: r.Name, Selection: r.RawSel,
		Profile: r.Profile.ID, Options: r.Options, Auto: r.Auto, Priority: priority, State: store.JobActive, RequestID: r.RequestID}
	var rows []store.CompressItem
	res.Skipped = map[string]int{}
	if !r.Auto {
		cands, err := m.Resolve(ctx, r.Selection)
		if err != nil {
			return res, err
		}
		est, err := m.EstimateAll(ctx, cands, r.Profile)
		if err != nil {
			return res, err
		}
		res.Items = est
		for _, e := range est {
			if e.Fingerprint == "" { // not a NAS video of this server: answered, not recorded
				res.Skipped[e.Reason]++
				continue
			}
			it := store.CompressItem{ItemID: e.ItemID, Fingerprint: e.Fingerprint, Size: e.Size, Source: "nas", FolderID: e.FolderID,
				RelPath: e.RelPath, Priority: priority, OrderKey: e.SortTime}
			if e.DurationMs > 0 {
				it.DurationMs = i64(e.DurationMs)
			}
			switch e.Decision {
			case DecisionSkip:
				if e.Reason == ReasonBusy {
					res.Busy = append(res.Busy, e.ItemID)
					continue
				}
				it.State, it.Reason = store.CompressSkipped, e.Reason
				res.Skipped[e.Reason]++
			default: // encode, or pending: the worker estimates again
				it.State = store.CompressQueued
				if e.EstSize > 0 {
					it.EstSize, it.EstMs = i64(e.EstSize), i64(e.EstMs)
				}
				res.Queued++
			}
			rows = append(rows, it)
		}
	}
	if !r.Auto && res.Queued == 0 && len(res.Busy) > 0 {
		hasRow := false
		for _, it := range rows {
			hasRow = hasRow || it.State == store.CompressQueued
		}
		if !hasRow {
			return res, ErrBusy
		}
	}
	job, busyFps, existed, err := m.cfg.Store.CreateCompressJob(ctx, j, rows)
	if errors.Is(err, store.ErrConflict) {
		return res, ErrExists
	}
	if err != nil {
		return res, err
	}
	res.Job, res.Existed = job, existed
	if len(busyFps) > 0 {
		byFP := map[string]string{}
		for _, it := range rows {
			byFP[it.Fingerprint] = it.ItemID
		}
		for _, fp := range busyFps {
			res.Busy = append(res.Busy, byFP[fp])
			res.Queued--
		}
	}
	if !existed {
		m.log.Info("compress: job", "job", job.ID, "target", job.Target, "profile", job.Profile, "auto", job.Auto, "queued", res.Queued,
			"busy", len(res.Busy))
		m.Wake()
	}
	return res, nil
}

func (m *Manager) jobByRequest(ctx context.Context, id string) (store.CompressJob, error) {
	return m.cfg.Store.CompressJobByRequest(ctx, id)
}

// autoAppend adds the new videos of folders compressed automatically: live NAS video items under the job's folder that
// appeared after the switch was turned on and were never part of a compression.
func (m *Manager) autoAppend(ctx context.Context) error {
	jobs, err := m.cfg.Store.AutoCompressJobs(ctx, "nas")
	if err != nil {
		return err
	}
	for _, j := range jobs {
		var sel Selection
		if json.Unmarshal(j.Selection, &sel) != nil || len(sel.Folders) != 1 {
			continue
		}
		f := sel.Folders[0]
		rec := f.Recursive == nil || *f.Recursive
		list, err := m.cfg.Store.NasVideoFiles(ctx, f.FolderID, f.Path, rec, nil)
		if err != nil {
			return err
		}
		var fresh []store.NasVideoFile
		var fps []string
		seen := map[string]bool{}
		for _, v := range list {
			if v.CreatedAt < j.CreatedAt || v.Kind != "video" || seen[v.ItemID] || IsSyncArtifact(v.RelPath) || inHoardDir(v.RelPath) {
				continue
			}
			seen[v.ItemID] = true
			fresh = append(fresh, v)
			fps = append(fps, v.Fingerprint)
		}
		if len(fresh) == 0 {
			continue
		}
		known, err := m.cfg.Store.CompressItemsByFingerprint(ctx, fps)
		if err != nil {
			return err
		}
		had := map[string]bool{}
		for _, it := range known {
			had[it.Fingerprint] = true
			had[it.OutFingerprint] = true
		}
		var rows []store.CompressItem
		for _, v := range fresh {
			if had[v.Fingerprint] {
				continue
			}
			rows = append(rows, store.CompressItem{ItemID: v.ItemID, Fingerprint: v.Fingerprint, Size: v.Size, Source: "nas", FolderID: v.FolderID,
				RelPath: v.RelPath, Priority: 3, OrderKey: v.SortTime, State: store.CompressQueued})
		}
		if len(rows) == 0 {
			continue
		}
		if _, err := m.cfg.Store.AppendCompressItems(ctx, j.ID, rows); err != nil {
			return err
		}
		m.log.Info("compress: automatic folder", "job", j.ID, "appended", len(rows))
		m.Wake()
	}
	return nil
}

// SidecarAvailable reports whether the per-frame sidecar of an output is on disk.
func (m *Manager) SidecarAvailable(outFP string) bool {
	_, err := os.Stat(m.MetaPath(outFP))
	return err == nil
}
