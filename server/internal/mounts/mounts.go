// Package mounts confines reveal-sync to its mount roots: the directories the setup page may offer as the backup
// root and NAS folders (Docker: the volumes mounted under /mnt). Every path from a request, the database or the setup
// page goes through Resolve.
package mounts

import (
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// ErrOutside is returned for a path that is not inside a mount root (after resolving symbolic links).
var ErrOutside = errors.New("path is outside the mount roots")

// Roots is the set of mount roots.
type Roots struct {
	roots []string // absolute, symlinks resolved
}

// New resolves the given roots; missing ones are skipped and returned in missing.
func New(paths []string) (r Roots, missing []string) {
	for _, p := range paths {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		abs, err := filepath.Abs(p)
		if err == nil {
			abs, err = filepath.EvalSymlinks(abs)
		}
		if err != nil {
			missing = append(missing, p)
			continue
		}
		if fi, err := os.Stat(abs); err != nil || !fi.IsDir() {
			missing = append(missing, p)
			continue
		}
		r.roots = append(r.roots, abs)
	}
	return r, missing
}

// List returns the roots.
func (r Roots) List() []string { return append([]string(nil), r.roots...) }

func within(root, p string) bool {
	if p == root {
		return true
	}
	rel, err := filepath.Rel(root, p)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

// Resolve makes p absolute, resolves symbolic links and checks that the result is an existing directory inside a
// root. A root itself is allowed (a single-volume setup).
func (r Roots) Resolve(p string) (string, error) {
	if p == "" || strings.ContainsRune(p, 0) || !filepath.IsAbs(p) {
		return "", ErrOutside
	}
	clean := filepath.Clean(p)
	real, err := filepath.EvalSymlinks(clean)
	if err != nil {
		return "", ErrOutside
	}
	for _, root := range r.roots {
		if within(root, real) {
			fi, err := os.Stat(real)
			if err != nil || !fi.IsDir() {
				return "", ErrOutside
			}
			return real, nil
		}
	}
	return "", ErrOutside
}

// Candidate is a directory the setup page offers.
type Candidate struct {
	Path     string `json:"path"`
	Name     string `json:"name"`
	Writable bool   `json:"writable"`
	Total    uint64 `json:"totalBytes"`
	Free     uint64 `json:"freeBytes"`
}

// Candidates lists each root's direct subdirectories (not hidden), or the root itself when it has none.
func (r Roots) Candidates() []Candidate {
	var out []Candidate
	for _, root := range r.roots {
		entries, err := os.ReadDir(root)
		found := false
		if err == nil {
			for _, e := range entries {
				if strings.HasPrefix(e.Name(), ".") {
					continue
				}
				p := filepath.Join(root, e.Name())
				real, err := r.Resolve(p)
				if err != nil {
					continue
				}
				out = append(out, describe(real, e.Name()))
				found = true
			}
		}
		if !found {
			out = append(out, describe(root, filepath.Base(root)))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

func describe(p, name string) Candidate {
	c := Candidate{Path: p, Name: name, Writable: Writable(p)}
	c.Total, c.Free = Usage(p)
	return c
}

// Writable reports whether a file can be created in dir.
func Writable(dir string) bool {
	f, err := os.CreateTemp(dir, ".reveal-sync-probe-*")
	if err != nil {
		return false
	}
	name := f.Name()
	f.Close()
	os.Remove(name)
	return true
}
