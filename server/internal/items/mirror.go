package items

import (
	"context"
	"encoding/json"
	"path"
	"sort"
	"strconv"
	"strings"

	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
)

// GroupMirror groups a phone backup root's member entries into shots (ADR 0024 "Mirror items"): the pairing rule of
// ADR 0014 (same directory relative to the root, case-insensitive stem). A previous version of a rewritten file
// (replaced) is a shot of its own: a new fingerprint is a new item. names gives the phone's name.
func GroupMirror(rootPath string, members []store.MirrorMember, deviceName string) []Group {
	byKey := map[[2]string][]Member{}
	for _, e := range members {
		name := path.Base(e.Path)
		mediaClass, role := media.Classify(name)
		if mediaClass == "" {
			continue
		}
		rel := e.Path
		if rootPath != "" {
			rel = strings.TrimPrefix(strings.TrimPrefix(e.Path, rootPath), "/")
		}
		dir := path.Dir(rel)
		if dir == "." {
			dir = ""
		}
		key := stemKey(name, role)
		if e.RemovedReason == store.RemovedReplaced {
			key += "~" + strconv.FormatInt(e.ID, 10)
		}
		ref := &MirrorRef{PhysicalID: e.PhysicalID, DeviceName: deviceName, RootID: e.RootID, Volume: e.Volume, OnDevice: e.OnDevice}
		if !e.OnDevice {
			ref.Gone = "deleted"
			if e.RemovedReason == store.RemovedFreed {
				ref.Gone = "freed"
			}
		}
		m := Member{Fingerprint: e.Fingerprint, Role: role, Name: name, Path: e.Path, Size: e.Size, MTime: e.ModifiedAt * 1e6, Mirror: ref}
		if e.Store == store.StoreBlob {
			m.SHA256 = e.SHA256
		} else if e.NasPath != "" {
			ref.NasFolderID, ref.NasPath, ref.NasMTime = e.NasFolderID, e.NasPath, e.NasMTime
		}
		k := [2]string{dir, key}
		byKey[k] = append(byKey[k], m)
	}
	out := make([]Group, 0, len(byKey))
	for k, ms := range byKey {
		sort.Slice(ms, func(i, j int) bool {
			a, b := ms[i], ms[j]
			if roleOrder(a.Role) != roleOrder(b.Role) {
				return roleOrder(a.Role) < roleOrder(b.Role)
			}
			if a.Role == media.RoleRendered {
				if ra, rb := media.RenderedRank(a.Name), media.RenderedRank(b.Name); ra != rb {
					return ra < rb
				}
			}
			if a.Name != b.Name {
				return a.Name < b.Name
			}
			return a.Fingerprint < b.Fingerprint
		})
		if ms[0].Role == media.RoleSidecar {
			continue
		}
		out = append(out, Group{Source: SourceMirror, Dir: k[0], Key: k[1], Main: ms[0].Fingerprint, Members: ms})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Dir != out[j].Dir {
			return out[i].Dir < out[j].Dir
		}
		return out[i].Key < out[j].Key
	})
	return out
}

// SyncMirrors makes the mirror sources equal to the phone backups' member entries (ADR 0024): every root's groups,
// and no sources for roots that are gone (purged, absorbed, merged). A group joins the live NAS item that has its main
// file (the shot the NAS already holds), else it is an item of its main file.
func (y *Syncer) SyncMirrors(ctx context.Context) (Stats, error) {
	members, err := y.Store.MirrorMembers(ctx)
	if err != nil {
		return Stats{}, err
	}
	roots, err := y.Store.MirrorRoots(ctx, "")
	if err != nil {
		return Stats{}, err
	}
	names, err := y.Store.PhysicalNames(ctx)
	if err != nil {
		return Stats{}, err
	}
	existing, err := y.Store.ItemSourcesOf(ctx, SourceMirror)
	if err != nil {
		return Stats{}, err
	}
	old := make(map[[3]string]store.ItemSource, len(existing))
	for _, r := range existing {
		old[[3]string{r.FolderID, r.Dir, r.Key}] = r
	}
	var ops []sourceOp
	err = y.Store.ItemsTx(ctx, func(tx *store.ItemTx) error {
		for _, root := range roots {
			name := names[root.PhysicalID]
			if name == "" {
				name = root.DirName
			}
			for _, g := range GroupMirror(root.Path, members[root.ID], name) {
				k := [3]string{root.ID, g.Dir, g.Key}
				prev, had := old[k]
				delete(old, k)
				target, main := ID(g.Main), g.Main
				owner, err := tx.LiveItemWithFile(g.Main, "", SourceNAS)
				if err != nil {
					return err
				}
				if owner != "" {
					if main, err = tx.ItemMain(owner); err != nil {
						return err
					}
					target = owner
				}
				sig := Signature(main, g.Members)
				if had && prev.Signature == sig && prev.ItemID == target {
					continue
				}
				js, err := json.Marshal(g.Members)
				if err != nil {
					return err
				}
				op := sourceOp{put: &store.ItemSource{Source: SourceMirror, FolderID: root.ID, Dir: g.Dir, Key: g.Key, ItemID: target, MainFP: main,
					Signature: sig, Members: js}, affected: []string{target}}
				if had && prev.ItemID != target {
					op.affected = append(op.affected, prev.ItemID)
				}
				ops = append(ops, op)
			}
		}
		return nil
	})
	if err != nil {
		return Stats{}, err
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
