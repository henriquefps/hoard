// Package items turns indexed files into items (ADR 0014): one item per shot — a photo, a RAW+JPEG pair, a video or
// a motion photo — with its member files, where each file lives, and the shot's metadata. The rules here are pure
// (no database, no files): the NAS indexer feeds them folder listings and stored metadata.
package items

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"path"
	"sort"
	"strings"

	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Kinds of item.
const (
	KindPhoto  = "photo"
	KindPair   = "pair"
	KindVideo  = "video"
	KindMotion = "motion"
)

// Sources of an item.
const (
	SourceNAS    = "nas"
	SourceBackup = "backup"
	// SourceTrash is a group of files in a NAS folder's .hoard-trash (ADR 0023): grouped by their original path, served
	// as NAS locations with a "trash" path.
	SourceTrash = "trash"
)

// Member is one file of a source (a NAS group or an uploaded original), as stored with the source.
type Member struct {
	Fingerprint string `json:"fp"`
	Role        string `json:"role"`
	Name        string `json:"name,omitempty"`  // NAS: the file name
	Path        string `json:"path,omitempty"`  // NAS: relative to the folder (in a trash: the original path)
	Trash       string `json:"trash,omitempty"` // in a trash (ADR 0023): where the file is, relative to the folder
	Size        int64  `json:"size"`
	MTime       int64  `json:"mtime"`            // NAS: modification time (ns); backup: upload time (ns)
	SHA256      string `json:"sha256,omitempty"` // backup
}

// Seen is a file a folder walk saw (the indexer's view).
type Seen struct {
	Path        string // relative to the folder, '/'-separated
	Size        int64
	MTime       int64
	Fingerprint string // "" = not readable yet
	// In a trash (ADR 0023): the path the file came from; "" for a live file.
	Origin string
}

// Group is one shot in a folder: the files of a directory that share a stem (case-insensitive).
type Group struct {
	Source  string // SourceNAS ("" too) or SourceTrash
	Dir     string // directory relative to the folder ("" = top); in a trash, the original directory
	Key     string // lower-case stem
	Main    string // the main file's fingerprint
	Members []Member
}

// Trash reports whether the group is files in a trash.
func (g Group) Trash() bool { return g.Source == SourceTrash }

// skippedDirs are NAS housekeeping folders whose files are never items (Synology thumbnails, recycle bins).
var skippedDirs = map[string]bool{"@eadir": true, "#recycle": true, "#snapshot": true, "@recycle": true, "$recycle.bin": true}

func skipped(rel string) bool {
	for _, seg := range strings.Split(path.Dir(rel), "/") {
		if skippedDirs[strings.ToLower(seg)] {
			return true
		}
	}
	return false
}

// stemKey is the grouping key of a file: its lower-case stem; a sidecar "X.xmp" or "X.ARW.xmp" belongs to stem X.
func stemKey(name, role string) string {
	stem := media.Stem(name)
	if role == media.RoleSidecar {
		if m, _ := media.Classify(stem); m == media.MediaImage || m == media.MediaVideo {
			stem = media.Stem(stem)
		}
	}
	return strings.ToLower(stem)
}

func roleOrder(role string) int {
	switch role {
	case media.RoleRaw:
		return 0
	case media.RoleRendered:
		return 1
	case media.RoleVideo:
		return 2
	}
	return 3
}

// GroupFolder groups a folder's files into shots (ADR 0014 "Pairing"): same directory and case-insensitive stem;
// RAW first (by name), then renderings (JPEG before HEIF, then name), videos, sidecars. The main file is the first
// readable RAW, else rendering, else video. Hidden files and housekeeping folders are skipped; a group of sidecars
// only, or with no readable still or video, is no item. Files in a trash (ADR 0023) group by their original directory
// and stem, as SourceTrash groups (whatever their day folder).
func GroupFolder(files []Seen) []Group {
	byKey := map[[3]string][]Member{}
	for _, f := range files {
		at := f.Path
		if f.Origin != "" {
			at = f.Origin
		}
		name := path.Base(at)
		mediaClass, role := media.Classify(name)
		if mediaClass == "" || skipped(at) || f.Fingerprint == "" {
			continue
		}
		dir := path.Dir(at)
		if dir == "." {
			dir = ""
		}
		k := [3]string{"", dir, stemKey(name, role)}
		m := Member{Fingerprint: f.Fingerprint, Role: role, Name: name, Path: at, Size: f.Size, MTime: f.MTime}
		if f.Origin != "" {
			k[0] = SourceTrash
			m.Trash = f.Path
		}
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
			return a.Trash < b.Trash
		})
		if ms[0].Role == media.RoleSidecar {
			continue
		}
		out = append(out, Group{Source: k[0], Dir: k[1], Key: k[2], Main: ms[0].Fingerprint, Members: ms})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Source != out[j].Source {
			return out[i].Source < out[j].Source
		}
		if out[i].Dir != out[j].Dir {
			return out[i].Dir < out[j].Dir
		}
		return out[i].Key < out[j].Key
	})
	return out
}

// Signature identifies a source's content: a source whose signature did not change does not touch its item.
func Signature(main string, members []Member) string {
	b, _ := json.Marshal(struct {
		M  string
		Ms []Member
	}{main, members})
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:16])
}

// ID is an item's id: a UUID derived from its main file's fingerprint (SHA-256 of "hoard-item\0" + fingerprint,
// version 8, RFC 9562 variant), so the same shot has the same id on every walk, after a move and on every server.
func ID(mainFingerprint string) string { return store.ItemID(mainFingerprint) }
