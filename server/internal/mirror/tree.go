package mirror

import (
	"bytes"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"strings"
	"syscall"
)

// Linker makes a hard link inside root (tests inject a failing one: EXDEV, EPERM). The default is os.Root.Link.
type Linker func(root *os.Root, oldname, newname string) error

// DefaultLinker links through os.Root, which refuses names leaving the root.
func DefaultLinker(root *os.Root, oldname, newname string) error { return root.Link(oldname, newname) }

// ErrOccupied: the tree path holds a file that is not a hard link of a stored blob (the owner put it there, or a
// restore without -H made it a plain copy). It is never removed; the entry stays index-only.
var ErrOccupied = errors.New("tree path holds another file")

// Tree is the mirror tree of one backup root. Every write goes through os.Root opened on the backup root (blob paths
// and device folders are both inside it, so a hard link is possible and no name can leave it).
type Tree struct {
	root *os.Root
	// BlobDir is the blob store's folder inside the backup root ("reveal-sync").
	BlobDir string
	// Link makes the hard links (DefaultLinker unless a test sets another).
	Link Linker
}

// OpenTree opens the tree of a backup root (blobDir: the blob store's folder name in it).
func OpenTree(backupRoot, blobDir string) (*Tree, error) {
	r, err := os.OpenRoot(backupRoot)
	if err != nil {
		return nil, err
	}
	return &Tree{root: r, BlobDir: blobDir, Link: DefaultLinker}, nil
}

// Close releases the backup root.
func (t *Tree) Close() error { return t.root.Close() }

func (t *Tree) blobPath(sha string) string {
	return path.Join(t.BlobDir, "blobs", sha[0:2], sha[2:4], sha)
}

func checkName(dir, rel string) error {
	if !ValidDirName(dir) {
		return fmt.Errorf("%w: device folder", ErrPath)
	}
	if rel == "" || strings.HasPrefix(rel, "/") || hasDotDot(rel) {
		return fmt.Errorf("%w: tree path", ErrPath)
	}
	return nil
}

func hasDotDot(rel string) bool {
	for _, s := range strings.Split(rel, "/") {
		if s == ".." || s == "." || s == "" {
			return true
		}
	}
	return false
}

// EnsureDevice creates a device folder (0700, like the blob store's).
func (t *Tree) EnsureDevice(dir string) error {
	if !ValidDirName(dir) {
		return fmt.Errorf("%w: device folder", ErrPath)
	}
	err := t.root.Mkdir(dir, 0o700)
	if errors.Is(err, fs.ErrExist) {
		return nil
	}
	return err
}

// Place makes rel (inside the device folder dir) a hard link of the blob sha: the blob becomes read-only (0400) first.
// A link that is already there is kept. A file of another blob at rel (a hard link: more than one name) is replaced;
// any other file is left alone (ErrOccupied). A failing link (EXDEV, EPERM, a filesystem without hard links) is
// returned as is: the caller keeps the entry index-only and never copies.
func (t *Tree) Place(dir, rel, sha string) error {
	if err := checkName(dir, rel); err != nil {
		return err
	}
	blob := t.blobPath(sha)
	bi, err := t.root.Stat(blob)
	if err != nil {
		return err
	}
	if bi.Mode().Perm() != 0o400 {
		if err := t.root.Chmod(blob, 0o400); err != nil {
			return err
		}
	}
	if err := t.EnsureDevice(dir); err != nil {
		return err
	}
	target := dir + "/" + rel
	if fi, err := t.root.Lstat(target); err == nil {
		if os.SameFile(fi, bi) {
			return nil
		}
		if !fi.Mode().IsRegular() || nlink(fi) < 2 {
			return ErrOccupied
		}
		if err := t.root.Remove(target); err != nil {
			return err
		}
	}
	if d := path.Dir(target); d != dir {
		if err := t.root.MkdirAll(d, 0o700); err != nil {
			return err
		}
	}
	return t.Link(t.root, blob, target)
}

// Remove removes rel from the device folder when it is the blob sha's link (anything else stays), then the
// directories it leaves empty.
func (t *Tree) Remove(dir, rel, sha string) error {
	if err := checkName(dir, rel); err != nil {
		return err
	}
	target := dir + "/" + rel
	fi, err := t.root.Lstat(target)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	bi, err := t.root.Stat(t.blobPath(sha))
	if err != nil || !os.SameFile(fi, bi) {
		return nil // not this blob's link: never touched
	}
	if err := t.root.Remove(target); err != nil {
		return err
	}
	t.pruneEmpty(dir, path.Dir(target))
	return nil
}

// pruneEmpty removes empty directories from d up to (not including) the device folder.
func (t *Tree) pruneEmpty(dir, d string) {
	for d != dir && strings.HasPrefix(d, dir+"/") {
		if t.root.Remove(d) != nil { // not empty (or gone): stop
			return
		}
		d = path.Dir(d)
	}
}

// WriteManifest replaces a file of the device folder atomically (a temporary file, synced, then renamed).
func (t *Tree) WriteManifest(dir, rel string, body []byte) error {
	if err := checkName(dir, rel); err != nil {
		return err
	}
	if err := t.EnsureDevice(dir); err != nil {
		return err
	}
	target := dir + "/" + rel
	if err := t.root.MkdirAll(path.Dir(target), 0o700); err != nil {
		return err
	}
	tmp := target + ".tmp"
	f, err := t.root.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(body); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return t.root.Rename(tmp, target)
}

// RemoveFile removes one server file of a device folder (a manifest); a missing file is not an error.
func (t *Tree) RemoveFile(dir, rel string) error {
	if err := checkName(dir, rel); err != nil {
		return err
	}
	err := t.root.Remove(dir + "/" + rel)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return err
}

// RemoveDevice deletes a device folder and everything in it (the admin's purge: its blobs are decided by the database).
func (t *Tree) RemoveDevice(dir string) error {
	if !ValidDirName(dir) {
		return fmt.Errorf("%w: device folder", ErrPath)
	}
	return t.root.RemoveAll(dir)
}

// RenameDevice renames a device folder (the admin's rename); the new name must not exist.
func (t *Tree) RenameDevice(from, to string) error {
	if !ValidDirName(from) || !ValidDirName(to) {
		return fmt.Errorf("%w: device folder", ErrPath)
	}
	if _, err := t.root.Lstat(to); err == nil {
		return fs.ErrExist
	}
	err := t.root.Rename(from, to)
	if errors.Is(err, fs.ErrNotExist) {
		return nil // nothing on disk yet
	}
	return err
}

// PruneDevice removes a device folder's manifests, then the directories left empty (a folder whose entries moved to
// another phone's folder). Files that are not the server's stay, and so does the folder then.
func (t *Tree) PruneDevice(dir string) {
	if !ValidDirName(dir) {
		return
	}
	_ = t.root.RemoveAll(dir + "/" + MetaDir)
	var dirs []string
	_ = fs.WalkDir(t.root.FS(), dir, func(p string, d fs.DirEntry, err error) error {
		if err == nil && d.IsDir() {
			dirs = append(dirs, p)
		}
		return nil
	})
	for i := len(dirs) - 1; i >= 0; i-- {
		_ = t.root.Remove(dirs[i])
	}
}

// IsLinkError reports a failure of the link itself (the entry then stays index-only), as opposed to a missing blob.
func IsLinkError(err error) bool {
	var le *os.LinkError
	return errors.As(err, &le) || errors.Is(err, syscall.EXDEV) || errors.Is(err, syscall.EPERM) || errors.Is(err, ErrOccupied)
}

// ManifestRow is one line of a root's manifest.
type ManifestRow struct {
	Path        string // relative to the volume
	Size        int64
	SHA256      string
	Fingerprint string
	Where       string // blob | blob-unlinked | nas:<folder name>/<path> | none | previous:<path>
	OnDevice    bool
	Removed     string // '' | deleted | moved | freed | replaced
}

func field(s string) string {
	r := strings.NewReplacer(`\`, `\\`, "\t", `\t`, "\n", `\n`, "\r", `\r`)
	return r.Replace(s)
}

// Manifest renders a root's manifest: a header, then one tab-separated line per entry (fields escaped with \t, \n,
// \r and \\), so the tree plus its manifests describe a phone's backup without the database.
func Manifest(volume, rootPath string, rows []ManifestRow) []byte {
	var b bytes.Buffer
	fmt.Fprintf(&b, "# hoard-server phone backup manifest (ADR 0024). volume=%s root=%s\n", field(volume), field(rootPath))
	b.WriteString("# path\tsize\tsha256\tfingerprint\twhere\ton_device\tremoved\n")
	for _, r := range rows {
		on := "0"
		if r.OnDevice {
			on = "1"
		}
		fmt.Fprintf(&b, "%s\t%d\t%s\t%s\t%s\t%s\t%s\n", field(TreePath(volume, r.Path)), r.Size, r.SHA256, r.Fingerprint, field(r.Where), on, r.Removed)
	}
	return b.Bytes()
}
