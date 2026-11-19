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

// SyncFolder makes the NAS and trash sources of a folder equal to groups (nil: the folder is no longer indexed) and
// rebuilds every item a changed source belongs or belonged to. Unchanged groups cost nothing.
func (y *Syncer) SyncFolder(ctx context.Context, folderID string, groups []Group) (Stats, error) {
	existing, err := y.Store.ItemSources(ctx, SourceNAS, folderID)
	if err != nil {
		return Stats{}, err
	}
	trash, err := y.Store.ItemSources(ctx, SourceTrash, folderID)
	if err != nil {
		return Stats{}, err
	}
	existing = append(existing, trash...)
	old := make(map[[3]string]store.ItemSource, len(existing))
	for _, r := range existing {
		old[[3]string{r.Source, r.Dir, r.Key}] = r
	}
	var ops []sourceOp
	for _, g := range groups {
		// A trash group is an item of its own main file, like any group (ADR 0023): it joins a live group, or another
		// trashed copy, only through the same main fingerprint, so an unrelated file that later takes the trashed
		// shot's path never takes its item over.
		source, main := SourceNAS, g.Main
		if g.Trash() {
			source = SourceTrash
		}
		k := [3]string{source, g.Dir, g.Key}
		id := ID(main)
		sig := Signature(main, g.Members)
		prev, had := old[k]
		delete(old, k)
		if had && prev.Signature == sig && prev.ItemID == id {
			continue
		}
		members, err := json.Marshal(g.Members)
		if err != nil {
			return Stats{}, err
		}
		op := sourceOp{put: &store.ItemSource{Source: source, FolderID: folderID, Dir: g.Dir, Key: g.Key, ItemID: id, MainFP: main,
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

// SyncBackup makes the backup sources equal to the uploaded originals. An original joins the live NAS or mirror item
// (ADR 0024) that has its fingerprint as a member (a RAW+JPEG pair the NAS also holds); otherwise it is an item of its own. roles gives
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
	// A blob a compression holds out of the gallery (an output not on the phone yet, a kept side) is no backup item of
	// its own (ADR 0025): the kept source shows the kept side.
	hidden, err := y.Store.CompressBlobHidden(ctx)
	if err != nil {
		return Stats{}, err
	}
	var ops []sourceOp
	err = y.Store.ItemsTx(ctx, func(tx *store.ItemTx) error {
		for _, o := range originals {
			role := roles[o.Fingerprint]
			if role == "" || hidden[o.Fingerprint] {
				continue
			}
			prev, had := old[o.Fingerprint]
			delete(old, o.Fingerprint)
			target, main := ID(o.Fingerprint), o.Fingerprint
			// The live NAS or phone backup item that holds the file (ADR 0024: a phone's upload never becomes a second,
			// ungrouped item).
			owner, err := tx.LiveItemWithFile(o.Fingerprint, "", SourceNAS, SourceMirror)
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

// Rebuild recomputes the given items from their sources in one transaction: what a compression's state change needs
// (ADR 0025: the heir rule and the "compressed" object depend on compress_items, not only on sources).
func (y *Syncer) Rebuild(ctx context.Context, ids []string) (int, error) {
	n := 0
	err := y.Store.ItemsTx(ctx, func(tx *store.ItemTx) error {
		var err error
		n, err = y.rebuild(tx, ids)
		return err
	})
	return n, err
}

// rebuild recomputes items from their sources: live items first, then trashed ones and tombstones (which then can name
// the item that took their main file over, "replacedBy"). An item whose every source is a trash (or a compression's
// kept side, ADR 0025) is trashed (ADR 0023),
// not a tombstone: it keeps its id, edits, previews and culling; when a compression replaced its file (ADR 0025, the
// heir rule) it also names the live item that took it over. A live video item that is a compression's output (or an
// original a restore brought back) adds the other side's item to the rebuild, so the link is made whichever side the
// walk sees first.
func (y *Syncer) rebuild(tx *store.ItemTx, ids []string) (int, error) {
	n := 0
	var dead []string
	type trashedItem struct {
		id, main string
		d        Data
	}
	var trashedLater []trashedItem
	held := map[string]map[string]bool{} // folder -> trash paths an unfinished compression journal holds
	markHeld := func(d *Data) error {
		for i := range d.Files {
			for j := range d.Files[i].Locations {
				l := &d.Files[i].Locations[j]
				if l.Trash == "" {
					continue
				}
				h, ok := held[l.FolderID]
				if !ok {
					var err error
					if h, err = tx.HeldTrash(l.FolderID); err != nil {
						return err
					}
					held[l.FolderID] = h
				}
				l.Held = h[l.Trash]
			}
		}
		return nil
	}
	queued := make(map[string]bool, len(ids))
	for _, id := range ids {
		queued[id] = true
	}
	for i := 0; i < len(ids); i++ {
		id := ids[i]
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
		trashed := len(rows) > 0
		for _, r := range rows {
			trashed = trashed && (r.Source == SourceTrash || r.Source == SourceKept)
			var ms []Member
			if err := json.Unmarshal(r.Members, &ms); err != nil {
				return n, err
			}
			for _, m := range ms {
				fps = append(fps, m.Fingerprint)
				hasMain = hasMain || m.Fingerprint == main
			}
			sources = append(sources, Source{Type: r.Source, FolderID: r.FolderID, FolderName: y.FolderNames[r.FolderID], Members: ms, Names: y.FolderNames})
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
		if err := markHeld(&d); err != nil {
			return n, err
		}
		if trashed && d.Kind == KindVideo { // after the live ones: its heir may be one of them
			trashedLater = append(trashedLater, trashedItem{id, main, d})
			continue
		}
		if trashed {
			if err := y.write(tx, id, main, d, true, &n); err != nil {
				return n, err
			}
			continue
		}
		if d.Kind == KindVideo {
			links, err := tx.CompressLinks(main)
			if err != nil {
				return n, err
			}
			d.Compressed = compressedOf(main, links)
			for _, l := range links { // the other side of each link is rebuilt too
				for _, other := range []string{ID(l.Fingerprint), l.ItemID, ID(l.OutFingerprint)} {
					if other != "" && !queued[other] {
						queued[other] = true
						ids = append(ids, other)
					}
				}
			}
		}
		if err := y.write(tx, id, main, d, false, &n); err != nil {
			return n, err
		}
	}
	for _, t := range trashedLater {
		heir := ""
		if t.d.Kind == KindVideo {
			var err error
			if heir, err = compressHeir(tx, t.id, t.main); err != nil {
				return n, err
			}
		}
		before, err := tx.ItemReplacedBy(t.id)
		if err != nil {
			return n, err
		}
		t.d.ReplacedBy, t.d.CompressedTo = heir, heir
		if err := y.write(tx, t.id, t.main, t.d, true, &n); err != nil {
			return n, err
		}
		if heir != "" && heir != before {
			// As for a tombstone's replacedBy: its edits move, its culling goes along and its record is sent again
			// naming the heir (ADR 0025 "What the walk and the items do").
			if err := tx.MoveEdits(t.id, heir); err != nil {
				return n, err
			}
			if err := tx.CarryCulling(t.id, heir); err != nil {
				return n, err
			}
			if err := tx.TouchCulling(t.id); err != nil {
				return n, err
			}
		}
	}
	for _, id := range dead {
		tomb := []byte("{}")
		heir := "" // the live item that took the shot over: its culling moves there (ADR 0016)
		main, err := tx.ItemMain(id)
		if err != nil {
			return n, err
		}
		if main != "" {
			// A compression's original (or a restored output) purged from the trash, or replaced at its path by a copy of
			// the output: its heir is the other side's live item (ADR 0025).
			if other, err := compressHeir(tx, id, main); err != nil {
				return n, err
			} else if other != "" {
				tomb, _ = json.Marshal(map[string]string{"replacedBy": other})
				heir = other
			}
		}
		if heir == "" && main != "" {
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
			// A trashed item whose main file was purged: its culling goes to the trashed item of the same shot's other
			// files (same bytes), never to a live one (ADR 0023).
			if trashed, err := tx.ItemTrashed(id); err != nil {
				return n, err
			} else if heir == "" && trashed {
				for _, fp := range fps {
					if other, err := tx.TrashedItemWithFile(fp, id); err == nil && other != "" {
						heir = other
						break
					}
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

func (y *Syncer) write(tx *store.ItemTx, id, main string, d Data, trashed bool, n *int) error {
	js, err := json.Marshal(d)
	if err != nil {
		return err
	}
	files := make([]store.ItemFile, 0, len(d.Files))
	for _, f := range d.Files {
		files = append(files, store.ItemFile{Fingerprint: f.Fingerprint, Role: f.Role})
	}
	changed, err := tx.WriteItemState(id, main, d.Kind, d.SortTime, js, files, trashed)
	if changed {
		*n++
	}
	return err
}

// compressHeir is the heir rule (ADR 0025): an item whose main file is the original of a compression that replaced it
// (ready, replacing, replaced or purged) is taken over by the live item holding the output; an item whose main file is
// the output of a restored compression is taken over by the live item holding the original again.
func compressHeir(tx *store.ItemTx, id, main string) (string, error) {
	links, err := tx.CompressLinks(main)
	if err != nil {
		return "", err
	}
	for _, l := range links {
		var other string
		switch {
		case l.Fingerprint == main && l.State != store.CompressRestored:
			other = l.OutFingerprint
		case l.OutFingerprint == main && l.State == store.CompressRestored:
			other = l.Fingerprint
		default:
			continue
		}
		heir, err := tx.LiveItemWithFile(other, id, "")
		if err != nil {
			return "", err
		}
		if heir != "" {
			return heir, nil
		}
	}
	return "", nil
}

// compressedOf builds the "compressed" object of an output item from its record: the newest replaced or purged
// compression whose output is main.
func compressedOf(main string, links []store.CompressLink) *Compressed {
	for _, l := range links {
		if l.OutFingerprint != main || (l.State != store.CompressReplaced && l.State != store.CompressPurged) {
			continue
		}
		c := &Compressed{From: l.Fingerprint, FromItem: l.ItemID, Profile: l.Profile, Q: l.Q, At: l.ReplacedAt, Original: "trashed",
			OriginalSize: l.Size, MetaSidecar: l.MetaSidecar}
		if l.State == store.CompressPurged {
			c.Original = "purged"
		}
		return c
	}
	return nil
}
