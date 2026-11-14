package nas

import (
	"context"
	"os"
	"strings"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
	"github.com/henriquefps/hoard/server/internal/xmp"
)

// sidecarOf picks the sidecar that speaks for a shot, as the apps read it (Hoard ADR 0011, Reveal ADR 0006):
// <stem>.xmp, else <file name>.xmp of the main file, then of the other files (in the group's order). nil: none.
func sidecarOf(g items.Group) *items.Member {
	byName := map[string]*items.Member{}
	var files []string
	for i := range g.Members {
		m := &g.Members[i]
		if m.Role == media.RoleSidecar {
			byName[strings.ToLower(m.Name)] = m
		} else {
			files = append(files, strings.ToLower(m.Name))
		}
	}
	if len(byName) == 0 {
		return nil
	}
	if m := byName[g.Key+".xmp"]; m != nil {
		return m
	}
	for _, f := range files {
		if m := byName[f+".xmp"]; m != nil {
			return m
		}
	}
	return nil
}

// importSidecars reads the sidecars that are new or changed since the server last read them and imports their
// culling values (ADR 0016 "The NAS sidecars"). It never writes to the folder. Returns the fields written.
func (ix *Indexer) importSidecars(ctx context.Context, root *os.Root, folderID string, groups []items.Group) (int, error) {
	states, err := ix.Store.SidecarStates(ctx, folderID)
	if err != nil {
		return 0, err
	}
	var reads []store.SidecarRead
	for _, g := range groups {
		if ctx.Err() != nil {
			return 0, ctx.Err()
		}
		m := sidecarOf(g)
		if m == nil {
			continue
		}
		if st, ok := states[m.Path]; ok && st.Size == m.Size && st.MTime == m.MTime {
			continue
		}
		read, ok := readSidecar(root, m)
		if !ok {
			continue // changed while read, or unreadable now: the next walk tries again
		}
		read.ItemID = items.ID(g.Main)
		reads = append(reads, read)
	}
	n := 0
	for start := 0; start < len(reads); start += 500 {
		k, err := ix.Store.ImportSidecars(ctx, folderID, reads[start:min(start+500, len(reads))])
		if err != nil {
			return n, err
		}
		n += k
	}
	return n, nil
}

func readSidecar(root *os.Root, m *items.Member) (store.SidecarRead, bool) {
	f, err := root.Open(m.Path)
	if err != nil {
		return store.SidecarRead{}, false
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil || fi.Size() != m.Size || fi.ModTime().UnixNano() != m.MTime {
		return store.SidecarRead{}, false
	}
	r := store.SidecarRead{RelPath: m.Path, Size: m.Size, MTime: m.MTime}
	c, ok := xmp.Read(f)
	if fi2, err := f.Stat(); err != nil || fi2.Size() != m.Size || fi2.ModTime().UnixNano() != m.MTime {
		return store.SidecarRead{}, false
	}
	if ok { // a file that is not XMP is recorded with no values, so it is not read again until it changes
		r.Values = store.SidecarValues{Rating: c.Rating, Flag: c.Flag, Label: c.Label}
	}
	return r, true
}
