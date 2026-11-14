// Package items turns indexed files into items (ADR 0014): one item per shot — a photo, a RAW+JPEG pair, a video or
// a motion photo — with its member files, where each file lives, and the shot's metadata. The rules here are pure
// (no database, no files): the NAS indexer feeds them folder listings and stored metadata.
package items

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"path"
	"sort"
	"strings"

	"github.com/henriquefps/hoard/server/internal/media"
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
)

// Member is one file of a source (a NAS group or an uploaded original), as stored with the source.
type Member struct {
	Fingerprint string `json:"fp"`
	Role        string `json:"role"`
	Name        string `json:"name,omitempty"` // NAS: the file name
	Path        string `json:"path,omitempty"` // NAS: relative to the folder
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
}

// Group is one shot in a folder: the files of a directory that share a stem (case-insensitive).
type Group struct {
	Dir     string // directory relative to the folder ("" = top)
	Key     string // lower-case stem
	Main    string // the main file's fingerprint
	Members []Member
}

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
// only, or with no readable still or video, is no item.
func GroupFolder(files []Seen) []Group {
	byKey := map[[2]string][]Member{}
	for _, f := range files {
		name := path.Base(f.Path)
		mediaClass, role := media.Classify(name)
		if mediaClass == "" || skipped(f.Path) || f.Fingerprint == "" {
			continue
		}
		dir := path.Dir(f.Path)
		if dir == "." {
			dir = ""
		}
		k := [2]string{dir, stemKey(name, role)}
		byKey[k] = append(byKey[k], Member{Fingerprint: f.Fingerprint, Role: role, Name: name, Path: f.Path, Size: f.Size, MTime: f.MTime})
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
			return a.Name < b.Name
		})
		if ms[0].Role == media.RoleSidecar {
			continue
		}
		out = append(out, Group{Dir: k[0], Key: k[1], Main: ms[0].Fingerprint, Members: ms})
	}
	sort.Slice(out, func(i, j int) bool {
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
func ID(mainFingerprint string) string {
	h := sha256.Sum256([]byte("hoard-item\x00" + mainFingerprint))
	b := h[:16]
	b[6] = b[6]&0x0f | 0x80
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
