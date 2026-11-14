package items

import (
	"context"
	"encoding/json"
	"sort"

	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
)

// chunk is how many source changes one transaction applies (with the items they touch).
const chunk = 500

// Stats counts what a sync changed.
type Stats struct {
	Sources int // source rows written or removed
	Items   int // item revisions written (new, changed, tombstoned)
}

// Syncer keeps item_sources and items in step with the indexed files (ADR 0014 "Indexer").
type Syncer struct {
	Store *store.Store
	// FolderNames names NAS folders in item locations.
	FolderNames map[string]string
}

type sourceOp struct {
	put      *store.ItemSource
	del      int64
	affected []string
}

// SyncFolder makes the NAS sources of a folder equal to groups (nil: the folder is no longer indexed) and rebuilds
// every item a changed source belongs or belonged to. Unchanged groups cost nothing.
func (y *Syncer) SyncFolder(ctx context.Context, folderID string, groups []Group) (Stats, error) {
	existing, err := y.Store.ItemSources(ctx, SourceNAS, folderID)
	if err != nil {
		return Stats{}, err
	}
	old := make(map[[2]string]store.ItemSource, len(existing))
	for _, r := range existing {
		old[[2]string{r.Dir, r.Key}] = r
	}
	var ops []sourceOp
	for _, g := range groups {
		k := [2]string{g.Dir, g.Key}
		id := ID(g.Main)
		sig := Signature(g.Main, g.Members)
		prev, had := old[k]
		delete(old, k)
		if had && prev.Signature == sig && prev.ItemID == id {
			continue
		}
		members, err := json.Marshal(g.Members)
		if err != nil {
			return Stats{}, err
		}
		op := sourceOp{put: &store.ItemSource{Source: SourceNAS, FolderID: folderID, Dir: g.Dir, Key: g.Key, ItemID: id, MainFP: g.Main,
			Signature: sig, Members: members}, affected: []string{id}}
		if had && prev.ItemID != id {
			op.affected = append(op.affected, prev.ItemID)
		}
		ops = append(ops, op)
	}
	gone := make([]store.ItemSource, 0, len(old))
	for _, r := range old {
		gone = append(gone, r)
	}
	sort.Slice(gone, func(i, j int) bool { return gone[i].ID < gone[j].ID })
	for _, r := range gone {
		ops = append(ops, sourceOp{del: r.ID, affected: []string{r.ItemID}})
	}
	return y.apply(ctx, ops)
}

// SyncBackup makes the backup sources equal to the uploaded originals. An original joins the live NAS item that has
// its fingerprint as a member (a RAW+JPEG pair the NAS also holds); otherwise it is an item of its own. roles gives
// each fingerprint's role (from its content); originals without one are not items.
func (y *Syncer) SyncBackup(ctx context.Context, originals []store.UploadedOriginal, roles map[string]string) (Stats, error) {
	existing, err := y.Store.ItemSources(ctx, SourceBackup, "")
	if err != nil {
		return Stats{}, err
	}
	old := make(map[string]store.ItemSource, len(existing))
	for _, r := range existing {
		old[r.Key] = r
	}
	var ops []sourceOp
	err = y.Store.ItemsTx(ctx, func(tx *store.ItemTx) error {
		for _, o := range originals {
			role := roles[o.Fingerprint]
			if role == "" {
				continue
			}
			prev, had := old[o.Fingerprint]
			delete(old, o.Fingerprint)
			target, main := ID(o.Fingerprint), o.Fingerprint
			owner, err := tx.LiveItemWithFile(o.Fingerprint, "", SourceNAS)
			if err != nil {
				return err
			}
			if owner != "" {
				if main, err = tx.ItemMain(owner); err != nil {
					return err
				}
				target = owner
			}
			members := []Member{{Fingerprint: o.Fingerprint, Role: role, Size: o.Size, MTime: o.CreatedAt * 1e6, SHA256: o.SHA256}}
			sig := Signature(main, members)
			if had && prev.Signature == sig && prev.ItemID == target {
				continue
			}
			js, err := json.Marshal(members)
			if err != nil {
				return err
			}
			op := sourceOp{put: &store.ItemSource{Source: SourceBackup, Key: o.Fingerprint, ItemID: target, MainFP: main, Signature: sig,
				Members: js}, affected: []string{target}}
			if had && prev.ItemID != target {
				op.affected = append(op.affected, prev.ItemID)
			}
			ops = append(ops, op)
		}
		return nil
	})
	if err != nil {
		return Stats{}, err
	}
	for _, r := range old {
		ops = append(ops, sourceOp{del: r.ID, affected: []string{r.ItemID}})
	}
	return y.apply(ctx, ops)
}

func (y *Syncer) apply(ctx context.Context, ops []sourceOp) (Stats, error) {
	// Changes to one item go into the same transaction (a move is a removed and a new source of the same item: one
	// revision, not two).
	sort.SliceStable(ops, func(i, j int) bool { return ops[i].affected[0] < ops[j].affected[0] })
	var st Stats
	for start := 0; start < len(ops); start += chunk {
		part := ops[start:min(start+chunk, len(ops))]
		err := y.Store.ItemsTx(ctx, func(tx *store.ItemTx) error {
			var ids []string
			seen := map[string]bool{}
			for _, op := range part {
				var err error
				if op.put != nil {
					err = tx.PutSource(*op.put)
				} else {
					err = tx.DeleteSource(op.del)
				}
				if err != nil {
					return err
				}
				for _, id := range op.affected {
					if !seen[id] {
						seen[id] = true
						ids = append(ids, id)
					}
				}
			}
			n, err := y.rebuild(tx, ids)
			st.Items += n
			return err
		})
		if err != nil {
			return st, err
		}
		st.Sources += len(part)
	}
	return st, nil
}

// rebuild recomputes items from their sources: live items first, then tombstones (which then can name the item that
// took their main file over, "replacedBy").
func (y *Syncer) rebuild(tx *store.ItemTx, ids []string) (int, error) {
	n := 0
	var dead []string
	for _, id := range ids {
		rows, err := tx.SourcesOf(id)
		if err != nil {
			return n, err
		}
		main := ""
		if len(rows) > 0 {
			main = rows[0].MainFP
		}
		var sources []Source
		var fps []string
		hasMain := false
		for _, r := range rows {
			var ms []Member
			if err := json.Unmarshal(r.Members, &ms); err != nil {
				return n, err
			}
			for _, m := range ms {
				fps = append(fps, m.Fingerprint)
				hasMain = hasMain || m.Fingerprint == main
			}
			sources = append(sources, Source{Type: r.Source, FolderID: r.FolderID, FolderName: y.FolderNames[r.FolderID], Members: ms})
		}
		if !hasMain { // no file of the shot's main is left (an upload still pointing here moves on the next backup pass)
			dead = append(dead, id)
			continue
		}
		raw, err := tx.FileMetas(fps)
		if err != nil {
			return n, err
		}
		metas := make(map[string]media.Meta, len(raw))
		for fp, b := range raw {
			var m media.Meta
			if json.Unmarshal(b, &m) == nil {
				metas[fp] = m
			}
		}
		d, ok := Build(main, sources, metas)
		if !ok {
			dead = append(dead, id)
			continue
		}
		js, err := json.Marshal(d)
		if err != nil {
			return n, err
		}
		files := make([]store.ItemFile, 0, len(d.Files))
		for _, f := range d.Files {
			files = append(files, store.ItemFile{Fingerprint: f.Fingerprint, Role: f.Role})
		}
		changed, err := tx.WriteItem(id, main, d.Kind, d.SortTime, js, files, nil)
		if err != nil {
			return n, err
		}
		if changed {
			n++
		}
	}
	for _, id := range dead {
		tomb := []byte("{}")
		heir := "" // the live item that took the shot over: its culling moves there (ADR 0016)
		if main, err := tx.ItemMain(id); err == nil && main != "" {
			if other, err := tx.LiveItemWithFile(main, id, ""); err == nil && other != "" {
				tomb, _ = json.Marshal(map[string]string{"replacedBy": other})
				heir = other
			}
		}
		if heir == "" {
			fps, err := tx.ItemFileFingerprints(id)
			if err != nil {
				return n, err
			}
			for _, fp := range fps {
				if other, err := tx.LiveItemWithFile(fp, id, ""); err == nil && other != "" {
					heir = other
					break
				}
			}
		}
		changed, err := tx.WriteItem(id, "", "", 0, nil, nil, tomb)
		if err != nil {
			return n, err
		}
		if changed {
			n++
			if err := tx.CarryCulling(id, heir); err != nil {
				return n, err
			}
		}
	}
	return n, nil
}
