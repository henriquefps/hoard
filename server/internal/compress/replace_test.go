package compress

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func writeFile(t *testing.T, p, s string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(s), 0o600); err != nil {
		t.Fatal(err)
	}
}

func mount(t *testing.T) (*WriteMount, string) {
	t.Helper()
	base := t.TempDir()
	nas := filepath.Join(base, "nas")
	if err := os.MkdirAll(filepath.Join(nas, "Photos"), 0o700); err != nil {
		t.Fatal(err)
	}
	return &WriteMount{ReadRoot: nas, WriteRoot: nas}, nas
}

// The trash root: the nearest existing .hoard-trash among the ancestors inside the folder, else the folder's root; the
// day folder gets -2, -3 when the path is taken.
func TestTrashRootAndDayFolders(t *testing.T) {
	m, nas := mount(t)
	f, err := m.Folder(filepath.Join(nas, "Photos"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	day := time.Date(2026, 11, 19, 12, 0, 0, 0, time.Local)
	if p, _ := f.TrashPath("2024/Trip/A.MP4", day); p != ".hoard-trash/2026-11-19/2024/Trip/A.MP4" {
		t.Fatalf("folder root: %s", p)
	}
	if err := os.MkdirAll(filepath.Join(nas, "Photos/2024/.hoard-trash"), 0o700); err != nil {
		t.Fatal(err)
	}
	if p, _ := f.TrashPath("2024/Trip/A.MP4", day); p != "2024/.hoard-trash/2026-11-19/Trip/A.MP4" {
		t.Fatalf("nearest ancestor: %s", p)
	}
	writeFile(t, filepath.Join(nas, "Photos/2024/.hoard-trash/2026-11-19/Trip/A.MP4"), "x")
	writeFile(t, filepath.Join(nas, "Photos/2024/.hoard-trash/2026-11-19-2/Trip/A.MP4"), "x")
	if p, _ := f.TrashPath("2024/Trip/A.MP4", day); p != "2024/.hoard-trash/2026-11-19-3/Trip/A.MP4" {
		t.Fatalf("taken: %s", p)
	}
	if err := f.ProbeTrash("2024/Trip/A.MP4"); err != nil {
		t.Fatal(err)
	}
	if entries, _ := os.ReadDir(filepath.Join(nas, "Photos/2024/.hoard-trash")); len(entries) != 2 {
		t.Fatalf("the probe file stayed: %v", entries)
	}
}

// The write mount must be the same directory as the read mount; a folder outside the read mount is not writable.
func TestWriteMountProof(t *testing.T) {
	base := t.TempDir()
	read, write := filepath.Join(base, "read"), filepath.Join(base, "write")
	for _, d := range []string{filepath.Join(read, "Photos"), filepath.Join(write, "Photos")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	m := &WriteMount{ReadRoot: read, WriteRoot: write}
	if _, err := m.Folder(filepath.Join(read, "Photos")); !errors.Is(err, ErrNotWritable) {
		t.Fatalf("different directories accepted: %v", err)
	}
	if _, err := m.Folder(filepath.Join(base, "elsewhere")); !errors.Is(err, ErrNotWritable) {
		t.Fatalf("outside the read mount: %v", err)
	}
	// The same directory through a bind-like second path (a symlink here): proven by st_dev/st_ino.
	if err := os.RemoveAll(write); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(read, write); err != nil {
		t.Fatal(err)
	}
	f, err := m.Folder(filepath.Join(read, "Photos"))
	if err != nil {
		t.Fatal(err)
	}
	f.Close()
	if (&WriteMount{ReadRoot: read, WriteRoot: filepath.Join(base, "missing")}).Available() {
		t.Fatal("a missing write mount is available")
	}
}

// os.Root containment: no path escapes the folder, by "..", an absolute path or a symlink.
func TestReplacerContainment(t *testing.T) {
	m, nas := mount(t)
	outside := filepath.Join(filepath.Dir(nas), "outside.mp4")
	writeFile(t, outside, "secret")
	if err := os.Symlink(outside, filepath.Join(nas, "Photos/link.mp4")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Dir(nas), filepath.Join(nas, "Photos/up")); err != nil {
		t.Fatal(err)
	}
	f, err := m.Folder(filepath.Join(nas, "Photos"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := f.ToTrash("../outside.mp4", ".hoard-trash/2026-11-19/x.mp4"); err == nil {
		t.Fatal("linked a file outside the folder")
	}
	if err := f.Swap("up/outside.mp4", "y.mp4"); err == nil {
		t.Fatal("renamed through a symlink out of the folder")
	}
	if err := f.Purge("../outside.mp4"); err == nil {
		t.Fatal("purged outside")
	}
	if err := f.Purge(".hoard-trash/../../outside.mp4"); err == nil {
		t.Fatal("purged outside through a trash prefix")
	}
	if st, _ := f.Stat("link.mp4", false); st.Size != 0 {
		t.Fatal("a symlink was followed")
	}
	ofi, _ := os.Stat(outside)
	if err := f.CopyIn(outside, "/abs.mp4", "x", ofi, time.Now()); err == nil {
		t.Fatal("wrote an absolute path")
	}
	if b, _ := os.ReadFile(outside); string(b) != "secret" {
		t.Fatal("the outside file changed")
	}
	if !strings.HasPrefix(TempName("a/b/C.MP4", 7), "a/b/.C.MP4.hoard-compress-7") {
		t.Fatal(TempName("a/b/C.MP4", 7))
	}
}

// EPERM: renamed into the trash instead; EXDEV: nothing moved.
func TestToTrashFallbacks(t *testing.T) {
	m, nas := mount(t)
	writeFile(t, filepath.Join(nas, "Photos/A.MP4"), "a")
	writeFile(t, filepath.Join(nas, "Photos/B.MP4"), "b")
	m.Link = func(*os.Root, string, string) error { return &os.LinkError{Op: "link", Err: syscall.EPERM} }
	f, _ := m.Folder(filepath.Join(nas, "Photos"))
	renamed, err := f.ToTrash("A.MP4", ".hoard-trash/2026-11-19/A.MP4")
	if err != nil || !renamed || exists(filepath.Join(nas, "Photos/A.MP4")) || !exists(filepath.Join(nas, "Photos/.hoard-trash/2026-11-19/A.MP4")) {
		t.Fatalf("EPERM: %v %v", renamed, err)
	}
	f.Close()
	m.Link = func(*os.Root, string, string) error { return &os.LinkError{Op: "link", Err: syscall.EXDEV} }
	f, _ = m.Folder(filepath.Join(nas, "Photos"))
	defer f.Close()
	if _, err := f.ToTrash("B.MP4", ".hoard-trash/2026-11-19/B.MP4"); !errors.Is(err, ErrCrossDevice) || !exists(filepath.Join(nas, "Photos/B.MP4")) {
		t.Fatalf("EXDEV: %v", err)
	}
}

func exists(p string) bool { _, err := os.Lstat(p); return err == nil }

func TestMoveFile(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "a"), "x")
	if err := moveFile(filepath.Join(dir, "a"), filepath.Join(dir, "b")); err != nil || exists(filepath.Join(dir, "a")) {
		t.Fatal(err)
	}
	// Across filesystems (/dev/shm is a tmpfs on Linux): a copy.
	shm, err := os.MkdirTemp("/dev/shm", "hoard-move-")
	if err != nil {
		t.Skip("no /dev/shm")
	}
	defer os.RemoveAll(shm)
	if err := moveFile(filepath.Join(dir, "b"), filepath.Join(shm, "c")); err != nil || exists(filepath.Join(dir, "b")) {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(shm, "c")); string(b) != "x" {
		t.Fatal("content")
	}
}

// Writability is probed on the library's folders, not on the mount's root: a root the server cannot write (root-owned
// on the host) over folders it can write keeps the feature on; no writable folder turns it off.
func TestWriteMountWritableProbesFolders(t *testing.T) {
	if os.Getuid() == 0 {
		t.Skip("root writes everything")
	}
	nas := t.TempDir()
	photos, other := filepath.Join(nas, "Photos"), filepath.Join(nas, "Other")
	for _, d := range []string{photos, other} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Chmod(nas, 0o555); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(nas, 0o700) })
	if p := (&WriteMount{ReadRoot: nas, WriteRoot: nas}).Usable(); p != "write_mount_read_only" {
		t.Fatalf("no folder known, a read-only root: %q", p)
	}
	m := &WriteMount{ReadRoot: nas, WriteRoot: nas, Folders: func() []string { return []string{other, photos} }}
	if p := m.Usable(); p != "" {
		t.Fatalf("writable folders under a read-only root: %q", p)
	}
	for _, d := range []string{photos, other} {
		if err := os.Chmod(d, 0o500); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = os.Chmod(d, 0o700) })
	}
	if p := m.Usable(); p != "write_mount_read_only" {
		t.Fatalf("no writable folder: %q", p)
	}
}
