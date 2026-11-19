package compress

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
)

// The write mount (ADR 0025 "Where the output goes"): the only place the server writes the owner's NAS folders. Every
// operation goes through an os.Root opened on a folder's write path, from paths stored in the database, and there are
// only these: create a hidden temporary file next to a target, create trash day folders, hard-link (or rename) a file
// into the trash, rename the temporary file over the target, rename a trashed file back over its path, and unlink a
// trashed file. Nothing else in the server opens the write mount (TestOnlyReplacerWrites).

// TrashDirName is ADR 0023's trash folder.
const TrashDirName = ".hoard-trash"

// Linker makes a hard link inside a root (tests inject EPERM and EXDEV).
type Linker func(root *os.Root, oldname, newname string) error

// Errors of the write mount.
var (
	ErrNoWriteMount = errors.New("no write mount")
	ErrNotWritable  = errors.New("folder not writable")
	ErrChanged      = errors.New("source changed")
	ErrCrossDevice  = errors.New("trash on another filesystem")
)

// WriteMount maps NAS folders under ReadRoot (the read-only mount the walk uses) to the same relative path under
// WriteRoot (the read-write mount of the same host directory).
type WriteMount struct {
	ReadRoot, WriteRoot string
	Link                Linker // nil: os.Root.Link
}

// Available reports whether the write mount exists.
func (m *WriteMount) Available() bool {
	if m == nil || m.WriteRoot == "" || m.ReadRoot == "" {
		return false
	}
	fi, err := os.Stat(m.WriteRoot)
	return err == nil && fi.IsDir()
}

// Folder opens the write path of a NAS folder whose read path is readDir, after proving it: the same directory
// (st_dev/st_ino) as the read path, and a writable trash root (a probe file created and removed in .hoard-trash).
func (m *WriteMount) Folder(readDir string) (*Folder, error) {
	if !m.Available() {
		return nil, ErrNoWriteMount
	}
	readRoot, err := filepath.EvalSymlinks(m.ReadRoot)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrNotWritable, err)
	}
	if real, err := filepath.EvalSymlinks(readDir); err == nil {
		readDir = real
	}
	rel, err := filepath.Rel(readRoot, readDir)
	if err != nil || rel == ".." || strings.HasPrefix(rel, "../") || filepath.IsAbs(rel) {
		return nil, fmt.Errorf("%w: the folder is not under the read mount", ErrNotWritable)
	}
	writeRoot, err := os.OpenRoot(m.WriteRoot)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrNotWritable, err)
	}
	defer writeRoot.Close()
	var root *os.Root
	if rel == "." {
		root, err = os.OpenRoot(m.WriteRoot)
	} else {
		root, err = writeRoot.OpenRoot(filepath.ToSlash(rel))
	}
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrNotWritable, err)
	}
	rs, err1 := os.Stat(readDir)
	ws, err2 := root.Stat(".")
	if err1 != nil || err2 != nil || !sameFile(rs, ws) {
		root.Close()
		return nil, fmt.Errorf("%w: the write mount is not the same directory as the read mount", ErrNotWritable)
	}
	link := m.Link
	if link == nil {
		link = func(r *os.Root, o, n string) error { return r.Link(o, n) }
	}
	return &Folder{root: root, link: link}, nil
}

func sameFile(a, b fs.FileInfo) bool {
	sa, ok1 := a.Sys().(*syscall.Stat_t)
	sb, ok2 := b.Sys().(*syscall.Stat_t)
	if !ok1 || !ok2 {
		return os.SameFile(a, b)
	}
	return sa.Dev == sb.Dev && sa.Ino == sb.Ino
}

// Folder is one NAS folder's write path.
type Folder struct {
	root   *os.Root
	link   Linker
	probed map[string]bool
}

// Close closes the folder.
func (f *Folder) Close() error { return f.root.Close() }

// FileState is what a path holds.
type FileState struct {
	Exists      bool
	Size        int64
	MTime       time.Time
	Fingerprint string
	Ino         uint64
}

// Stat reads a path (its fingerprint too, a 2 MiB read, when withFP).
func (f *Folder) Stat(rel string, withFP bool) (FileState, error) {
	fi, err := f.root.Lstat(rel)
	if errors.Is(err, fs.ErrNotExist) {
		return FileState{}, nil
	}
	if err != nil {
		return FileState{}, err
	}
	if !fi.Mode().IsRegular() {
		return FileState{Exists: true}, nil
	}
	st := FileState{Exists: true, Size: fi.Size(), MTime: fi.ModTime()}
	if s, ok := fi.Sys().(*syscall.Stat_t); ok {
		st.Ino = s.Ino
	}
	if withFP {
		file, err := f.root.Open(rel)
		if err != nil {
			return st, err
		}
		defer file.Close()
		if st.Fingerprint, err = fingerprint.Of(file, fi.Size()); err != nil {
			return st, err
		}
	}
	return st, nil
}

// TrashRoot is the directory (relative to the folder, "" = its root) whose .hoard-trash takes rel: the nearest existing
// .hoard-trash among rel's ancestor directories, else the folder's root (ADR 0025 "The trash root").
func (f *Folder) TrashRoot(rel string) string {
	dir := path.Dir(rel)
	for dir != "." && dir != "/" && dir != "" {
		if fi, err := f.root.Lstat(dir + "/" + TrashDirName); err == nil && fi.IsDir() {
			return dir
		}
		dir = path.Dir(dir)
	}
	return ""
}

// TrashPath picks where rel goes in the trash today: <trash root>/.hoard-trash/<yyyy-MM-dd(-n)>/<path from the trash
// root>, the first day folder where that path is free.
func (f *Folder) TrashPath(rel string, now time.Time) (string, error) {
	troot := f.TrashRoot(rel)
	inner := rel
	base := TrashDirName
	if troot != "" {
		inner = strings.TrimPrefix(rel, troot+"/")
		base = troot + "/" + TrashDirName
	}
	day := now.Format("2006-01-02")
	for n := 1; n < 1000; n++ {
		d := day
		if n > 1 {
			d = day + "-" + strconv.Itoa(n)
		}
		p := base + "/" + d + "/" + inner
		if _, err := f.root.Lstat(p); errors.Is(err, fs.ErrNotExist) {
			return p, nil
		} else if err != nil {
			return "", err
		}
	}
	return "", errors.New("no free day folder")
}

// ProbeTrash checks the trash root of rel is writable (a probe file created and removed in its .hoard-trash), once per
// trash root.
func (f *Folder) ProbeTrash(rel string) error {
	troot := f.TrashRoot(rel)
	if f.probed[troot] {
		return nil
	}
	base := TrashDirName
	if troot != "" {
		base = troot + "/" + TrashDirName
	}
	if err := f.mkdirLikeParent(base); err != nil {
		return fmt.Errorf("%w: %w", ErrNotWritable, err)
	}
	probe := base + "/.hoard-compress-probe"
	file, err := f.root.OpenFile(probe, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil && !errors.Is(err, fs.ErrExist) {
		return fmt.Errorf("%w: %w", ErrNotWritable, err)
	}
	if file != nil {
		file.Close()
	}
	if err := f.root.Remove(probe); err != nil {
		return fmt.Errorf("%w: %w", ErrNotWritable, err)
	}
	if f.probed == nil {
		f.probed = map[string]bool{}
	}
	f.probed[troot] = true
	return nil
}

// TempName is the hidden temporary file of a replace, next to its target (a dot name the walk and Hoard never list).
func TempName(rel string, itemID int64) string {
	dir, name := path.Split(rel)
	return dir + "." + name + ".hoard-compress-" + strconv.FormatInt(itemID, 10)
}

// CopyIn copies src (the verified work file) to the temporary file tmp, fsyncs it, reads it back for its SHA-256
// (which must be wantSHA), and gives it the original's mode bits, group (when the server's user may) and mtime.
func (f *Folder) CopyIn(src, tmp, wantSHA string, orig fs.FileInfo, mtime time.Time) error {
	mode := orig.Mode()
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	_ = f.root.Remove(tmp)
	out, err := f.root.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	if err == nil {
		err = out.Sync()
	}
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = f.root.Remove(tmp)
		return err
	}
	back, err := f.root.Open(tmp)
	if err != nil {
		return err
	}
	h := sha256.New()
	_, err = io.Copy(h, back)
	back.Close()
	if err != nil {
		_ = f.root.Remove(tmp)
		return err
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != wantSHA {
		_ = f.root.Remove(tmp)
		return fmt.Errorf("the copy reads back as %s", got[:12])
	}
	if st, ok := orig.Sys().(*syscall.Stat_t); ok {
		_ = f.root.Lchown(tmp, -1, int(st.Gid)) // best effort: a group the server's user is not in is refused
	}
	if err := f.root.Chmod(tmp, mode.Perm()); err != nil {
		_ = f.root.Remove(tmp)
		return err
	}
	return f.root.Chtimes(tmp, mtime, mtime)
}

// Remove unlinks a temporary file of a replace (never a live file: callers pass TempName paths only).
func (f *Folder) Remove(tmp string) error {
	err := f.root.Remove(tmp)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return err
}

// ToTrash puts the file at rel into the trash at trash: a hard link (the path never goes missing); renamed is true
// when hard links are refused (EPERM) and the file was renamed instead. EXDEV is ErrCrossDevice: nothing moved.
func (f *Folder) ToTrash(rel, trash string) (renamed bool, err error) {
	if err := f.mkdirLikeParent(path.Dir(trash)); err != nil {
		return false, err
	}
	defer f.syncDirs(path.Dir(trash), path.Dir(rel))
	err = f.link(f.root, rel, trash)
	if err == nil {
		return false, nil
	}
	if errors.Is(err, syscall.EXDEV) {
		return false, ErrCrossDevice
	}
	if errors.Is(err, syscall.EPERM) || errors.Is(err, syscall.ENOTSUP) || errors.Is(err, syscall.EOPNOTSUPP) || errors.Is(err, syscall.EMLINK) {
		if err := f.root.Rename(rel, trash); err != nil {
			if errors.Is(err, syscall.EXDEV) {
				return false, ErrCrossDevice
			}
			return false, err
		}
		return true, nil
	}
	return false, err
}

// Swap renames from over to (atomic: readers see the old or the new file), then fsyncs both directories.
func (f *Folder) Swap(from, to string) error {
	if err := f.root.Rename(from, to); err != nil {
		return err
	}
	f.syncDirs(path.Dir(from), path.Dir(to))
	return nil
}

// syncDirs fsyncs directories (a link or rename is durable once its directory is).
func (f *Folder) syncDirs(dirs ...string) {
	done := map[string]bool{}
	for _, d := range dirs {
		if done[d] {
			continue
		}
		done[d] = true
		if dir, err := f.root.Open(d); err == nil {
			_ = dir.Sync()
			_ = dir.Close()
		}
	}
}

// mkdirLikeParent creates dir and its missing parents, each with the mode bits of the nearest existing ancestor (the
// owner's folder): the server's umask is private, and Hoard's Empty over SMB must be able to remove trash folders.
func (f *Folder) mkdirLikeParent(dir string) error {
	var missing []string
	d := dir
	for d != "." && d != "/" && d != "" {
		if fi, err := f.root.Lstat(d); err == nil {
			if !fi.IsDir() {
				return fmt.Errorf("%s is not a directory", d)
			}
			break
		} else if !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		missing = append(missing, d)
		d = path.Dir(d)
	}
	parent := d
	if parent == "" || parent == "/" {
		parent = "."
	}
	pfi, err := f.root.Stat(parent)
	if err != nil {
		return err
	}
	for i := len(missing) - 1; i >= 0; i-- {
		if err := f.root.Mkdir(missing[i], pfi.Mode().Perm()); err != nil && !errors.Is(err, fs.ErrExist) {
			return err
		}
		_ = f.root.Chmod(missing[i], pfi.Mode().Perm())
	}
	return nil
}

// Purge unlinks a trashed file: only a clean relative path with a .hoard-trash segment followed by at least a day
// folder and a name.
func (f *Folder) Purge(trash string) error {
	if !IsTrashPath(trash) {
		return errors.New("not a trash path")
	}
	err := f.root.Remove(trash)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err == nil {
		f.syncDirs(path.Dir(trash))
	}
	return err
}

// IsTrashPath reports a clean, relative path inside a .hoard-trash day folder.
func IsTrashPath(p string) bool {
	if p == "" || path.IsAbs(p) || path.Clean(p) != p || strings.ContainsRune(p, '\\') {
		return false
	}
	segs := strings.Split(p, "/")
	for i, s := range segs {
		if s == "." || s == ".." {
			return false
		}
		if s == TrashDirName && i+2 < len(segs) {
			return true
		}
	}
	return false
}

// Free returns the free bytes of the folder's filesystem.
func (f *Folder) Free() (int64, error) {
	file, err := f.root.Open(".")
	if err != nil {
		return 0, err
	}
	defer file.Close()
	return freeBytesFile(file)
}
