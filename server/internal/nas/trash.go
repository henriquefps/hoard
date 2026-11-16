package nas

import (
	"errors"
	"io/fs"
	"os"
	"path"
	"regexp"
	"strings"
	"time"

	"github.com/henriquefps/hoard/server/internal/media"
)

// The trash (ADR 0023): Hoard moves rejects to <P>/.hoard-trash/<yyyy-MM-dd(-n)>/<R> on the share, a rename that keeps
// their bytes, size and mtime. The walk reads those folders: a file there is a trash row whose origin is <P>/<R>.

// TrashDirName is the name of a trash folder; the only dot folder the walk enters.
const TrashDirName = ".hoard-trash"

var dayFolder = regexp.MustCompile(`^(\d{4}-\d{2}-\d{2})(-[1-9][0-9]{0,5})?$`)

// isDayFolder reports whether name is a trash's day folder: yyyy-MM-dd, a real date, with an optional -n (n ≥ 1).
func isDayFolder(name string) bool {
	m := dayFolder.FindStringSubmatch(name)
	if m == nil {
		return false
	}
	_, err := time.Parse("2006-01-02", m[1])
	return err == nil
}

// trashOrigin maps a folder-relative path in a trash, "<P>/.hoard-trash/<day>/<R>", to the original path "<P>/<R>". ok
// is false for a path in no trash, a malformed day folder, nothing under it, a hidden name, or a trash inside a trash.
func trashOrigin(rel string) (origin string, ok bool) {
	segs := strings.Split(rel, "/")
	for i, s := range segs {
		if s != TrashDirName {
			continue
		}
		if i+2 >= len(segs) || !isDayFolder(segs[i+1]) || strings.HasPrefix(segs[len(segs)-1], ".") {
			return "", false
		}
		for _, t := range segs[i+2:] {
			if t == TrashDirName || strings.HasPrefix(t, ".") {
				return "", false
			}
		}
		rest := append(append([]string{}, segs[:i]...), segs[i+2:]...)
		return strings.Join(rest, "/"), true
	}
	return "", false
}

// inTrash reports whether a folder-relative path lies under a .hoard-trash folder.
func inTrash(rel string) bool {
	return rel == TrashDirName || strings.HasPrefix(rel, TrashDirName+"/") || strings.Contains(rel, "/"+TrashDirName+"/") ||
		strings.HasSuffix(rel, "/"+TrashDirName)
}

// walkDecision says what the walk does with an entry whose name starts with a dot or that lies in a trash: enter is true
// for a directory to walk, keep for a file to index. Other entries are skipped (a directory with its content).
func walkDecision(p string, d fs.DirEntry) (enter, keep bool) {
	name := d.Name()
	parent := path.Dir(p)
	if d.IsDir() {
		if name == TrashDirName {
			return !inTrash(parent), false // a trash inside a trash is ignored
		}
		if strings.HasPrefix(name, ".") {
			return false, false
		}
		if path.Base(parent) == TrashDirName { // a trash's children are day folders, nothing else
			return isDayFolder(name), false
		}
		return true, false
	}
	if strings.HasPrefix(name, ".") {
		return false, false
	}
	if inTrash(parent) {
		_, ok := trashOrigin(p)
		return false, ok
	}
	return false, true
}

// lookAgain stats a folder-relative path now (the second look, ADR 0023): found is a regular media file at it; known is
// false when the answer is unknown (an error other than "does not exist"), so nothing is concluded.
func lookAgain(root *os.Root, rel string) (s seen, found, known bool) {
	info, err := root.Lstat(rel)
	if errors.Is(err, fs.ErrNotExist) {
		return seen{}, false, true
	}
	if err != nil {
		return seen{}, false, false
	}
	if !info.Mode().IsRegular() {
		return seen{}, false, true
	}
	mediaClass, _ := media.Classify(path.Base(rel))
	if mediaClass == "" {
		return seen{}, false, true
	}
	s = seen{rel: rel, size: info.Size(), mtime: info.ModTime().UnixNano(), media: mediaClass}
	if inTrash(rel) {
		origin, ok := trashOrigin(rel)
		if !ok {
			return seen{}, false, true
		}
		s.origin = origin
	}
	return s, true, true
}

// trashRoots lists, for a folder-relative path, each trash it could have been moved to with its path there: for
// "a/b/x.jpg", ".hoard-trash" (as "a/b/x.jpg"), "a/.hoard-trash" ("b/x.jpg") and "a/b/.hoard-trash" ("x.jpg").
func trashRoots(rel string) (roots, rest []string) {
	segs := strings.Split(rel, "/")
	for i := 0; i < len(segs); i++ {
		prefix := strings.Join(segs[:i], "/")
		root := TrashDirName
		if prefix != "" {
			root = prefix + "/" + TrashDirName
		}
		roots = append(roots, root)
		rest = append(rest, strings.Join(segs[i:], "/"))
	}
	return roots, rest
}

// secondLook re-reads the trash folders of a walk (once each) to find where a file went.
type secondLook struct {
	root *os.Root
	days map[string][]string // trash folder -> its day folders; nil entry: does not exist
	bad  map[string]bool     // trash folders that could not be read
}

func newSecondLook(root *os.Root) *secondLook {
	return &secondLook{root: root, days: map[string][]string{}, bad: map[string]bool{}}
}

func (l *secondLook) dayFolders(trash string) ([]string, bool) {
	if l.bad[trash] {
		return nil, false
	}
	if d, ok := l.days[trash]; ok {
		return d, true
	}
	entries, err := fs.ReadDir(l.root.FS(), trash)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		l.bad[trash] = true
		return nil, false
	}
	var out []string
	for _, e := range entries {
		if e.IsDir() && isDayFolder(e.Name()) {
			out = append(out, e.Name())
		}
	}
	l.days[trash] = out
	return out, true
}

// inTrashNow looks for a live file's copies in every trash that could hold it. known is false when a trash could not
// be read (nothing is concluded then).
func (l *secondLook) inTrashNow(rel string) (found []seen, known bool) {
	known = true
	roots, rest := trashRoots(rel)
	for i, r := range roots {
		days, ok := l.dayFolders(r)
		if !ok {
			known = false
			continue
		}
		for _, d := range days {
			s, ok, k := lookAgain(l.root, r+"/"+d+"/"+rest[i])
			known = known && k
			if ok {
				found = append(found, s)
			}
		}
	}
	return found, known
}
