package mounts

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestResolveConfinesPaths(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "mnt")
	outside := filepath.Join(base, "secret")
	for _, d := range []string{filepath.Join(root, "backup"), filepath.Join(root, "nas", "2026"), outside} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Symlink(outside, filepath.Join(root, "escape")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "file"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	r, missing := New([]string{root, filepath.Join(base, "missing")})
	if len(missing) != 1 {
		t.Fatalf("missing %v", missing)
	}
	realRoot, _ := filepath.EvalSymlinks(root)
	for _, ok := range []string{root, filepath.Join(root, "backup"), filepath.Join(root, "nas", "2026"), filepath.Join(root, "nas", "..", "backup")} {
		if _, err := r.Resolve(ok); err != nil {
			t.Errorf("%s: %v", ok, err)
		}
	}
	for _, bad := range []string{"", "backup", outside, filepath.Join(root, ".."), filepath.Join(root, "..", "secret"),
		filepath.Join(root, "escape"), filepath.Join(root, "file"), filepath.Join(root, "nope"), "/", "/etc",
		filepath.Join(root, "backup") + "\x00/etc", realRoot + "-sibling"} {
		if _, err := r.Resolve(bad); !errors.Is(err, ErrOutside) {
			t.Errorf("%q: want ErrOutside, got %v", bad, err)
		}
	}
	names := map[string]bool{}
	for _, c := range r.Candidates() {
		names[c.Name] = true
		if c.Path == outside {
			t.Fatal("a symlink out of the root must not be offered")
		}
	}
	if !names["backup"] || !names["nas"] || names["escape"] || names["file"] {
		t.Fatalf("candidates %v", names)
	}
}
