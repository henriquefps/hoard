// Package mirror is the phone backup's tree on disk (ADR 0024 "The mirror"): one folder per physical device under the
// backup root, holding hard links into the blob store at each file's path on the phone, the manifests of its roots
// and the previous versions of rewritten files. Names come from the database, never from a request directly: this
// package validates the paths a phone sends (Path, Volume) and writes only through os.Root.
package mirror

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

// Limits of a phone path (ADR 0024 "Paths").
const (
	MaxPath    = 1024
	MaxSegment = 255
)

// ErrPath is the class of every path refusal; the wrapped message says why (for the API's message, never logged with
// the path).
var ErrPath = errors.New("invalid path")

func pathErr(format string, args ...any) error {
	return fmt.Errorf("%w: %s", ErrPath, fmt.Sprintf(format, args...))
}

// CleanPath validates a relative, '/'-separated phone path and returns it normalised ("a//b" is "a/b"). It refuses
// (ErrPath) an absolute path, an empty one (unless allowEmpty: a root's "" is the whole volume), "." or ".." segments,
// a segment starting with ".", a backslash, NUL or another control character, text that is not UTF-8 in NFC, more
// than MaxPath bytes or a segment of more than MaxSegment bytes. A trailing "/" is refused too.
func CleanPath(p string, allowEmpty bool) (string, error) {
	if !utf8.ValidString(p) {
		return "", pathErr("not UTF-8")
	}
	if strings.HasPrefix(p, "/") {
		return "", pathErr("absolute")
	}
	if strings.HasSuffix(p, "/") {
		return "", pathErr("trailing '/'")
	}
	for strings.Contains(p, "//") {
		p = strings.ReplaceAll(p, "//", "/")
	}
	if p == "" {
		if allowEmpty {
			return "", nil
		}
		return "", pathErr("empty")
	}
	if len(p) > MaxPath {
		return "", pathErr("longer than %d bytes", MaxPath)
	}
	if !norm.NFC.IsNormalString(p) {
		return "", pathErr("not in Unicode NFC")
	}
	for _, r := range p {
		if r < 0x20 || r == 0x7f || (r >= 0x80 && r < 0xa0) || r == '\\' {
			return "", pathErr("backslash or control character")
		}
	}
	for _, seg := range strings.Split(p, "/") {
		switch {
		case seg == "." || seg == "..":
			return "", pathErr("'.' or '..' segment")
		case strings.HasPrefix(seg, "."):
			return "", pathErr("a segment starts with '.'")
		case len(seg) > MaxSegment:
			return "", pathErr("a segment is longer than %d bytes", MaxSegment)
		}
	}
	return p, nil
}

var (
	shortVolume = regexp.MustCompile(`^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$`)
	longVolume  = regexp.MustCompile(`^[0-9a-f-]{36}$`)
)

// Primary is the phone's primary storage volume.
const Primary = "primary"

// CleanVolume validates a volume: "primary" or a volume UUID as Android shows it ("1234-ABCD", or a 36-character
// lower-case UUID), stored upper-case.
func CleanVolume(v string) (string, bool) {
	switch {
	case v == Primary:
		return v, true
	case shortVolume.MatchString(v), longVolume.MatchString(v):
		return strings.ToUpper(v), true
	}
	return "", false
}

// Within reports whether path p lies in root (a root's path; "" = the whole volume): equal or under it at a '/'.
func Within(root, p string) bool {
	return root == "" || p == root || strings.HasPrefix(p, root+"/")
}

// Relative is p relative to root ("" when equal); p must be Within root.
func Relative(root, p string) string {
	if root == "" {
		return p
	}
	return strings.TrimPrefix(strings.TrimPrefix(p, root), "/")
}

// VolumeDir is the folder of a volume inside a device folder: "" for the primary volume, "SD card <UUID>" otherwise.
func VolumeDir(volume string) string {
	if volume == Primary {
		return ""
	}
	return "SD card " + volume
}

// sdCardDir matches a first segment that names another volume's folder in the tree ("SD card 1234-ABCD", any case).
var sdCardDir = regexp.MustCompile(`(?i)^sd card ([0-9a-f]{4}-[0-9a-f]{4}|[0-9a-f-]{36})$`)

// CheckVolumePath refuses (ErrPath) a primary-volume path whose first segment is an SD card's folder name in the tree
// ("SD card <UUID>", in any case): the tree puts other volumes there, so such a path would mix two volumes' files.
// Paths on other volumes have their own folder and are never refused.
func CheckVolumePath(volume, p string) error {
	if volume != Primary {
		return nil
	}
	first, _, _ := strings.Cut(p, "/")
	if sdCardDir.MatchString(first) {
		return pathErr("%q is reserved for an SD card's folder in the backup", first)
	}
	return nil
}

// TreePath is where a phone file lives inside its device folder.
func TreePath(volume, p string) string {
	if d := VolumeDir(volume); d != "" {
		return d + "/" + p
	}
	return p
}

// MetaDir is the server's folder inside a device folder: manifests and previous versions (a dot folder, so no phone
// path can name it).
const MetaDir = ".hoard-mirror"

// PreviousPath is where a rewritten file's earlier content is linked: .hoard-mirror/previous/<tree path>~<sha8>.
func PreviousPath(volume, p, contentID string) string {
	id := contentID
	if len(id) > 8 {
		id = id[:8]
	}
	return MetaDir + "/previous/" + TreePath(volume, p) + "~" + id
}

// ManifestPath is a root's manifest inside its device folder.
func ManifestPath(rootID string) string { return MetaDir + "/manifest-" + rootID + ".tsv" }

// DirName derives a device folder's name (ADR 0024 "Layout on disk"): the device's name in NFC, with / \ : * ? " < > |
// and control characters replaced by '_', leading and trailing dots and spaces trimmed, cut to 48 bytes at a
// character boundary ("Device" when empty), then " (" + the first 8 hex digits of the physical device id + ")".
func DirName(name, physicalID string) string {
	name = norm.NFC.String(name)
	var b strings.Builder
	for _, r := range name {
		switch {
		case r < 0x20 || r == 0x7f || (r >= 0x80 && r < 0xa0) || strings.ContainsRune(`/\:*?"<>|`, r):
			b.WriteByte('_')
		case r == utf8.RuneError:
			b.WriteByte('_')
		default:
			b.WriteRune(r)
		}
	}
	s := strings.Trim(b.String(), ". ")
	if len(s) > 48 {
		cut := 48
		for cut > 0 && !utf8.RuneStart(s[cut]) {
			cut--
		}
		s = strings.TrimRight(s[:cut], ". ")
	}
	if s == "" {
		s = "Device"
	}
	id := strings.ReplaceAll(physicalID, "-", "")
	if len(id) > 8 {
		id = id[:8]
	}
	return s + " (" + id + ")"
}

// ValidDirName reports whether a device folder name (the admin's rename) is one segment the tree can use.
func ValidDirName(n string) bool {
	if n == "" || len(n) > 120 || n == "reveal-sync" || !utf8.ValidString(n) || !norm.NFC.IsNormalString(n) {
		return false
	}
	if strings.HasPrefix(n, ".") || strings.HasSuffix(n, ".") || strings.TrimSpace(n) != n {
		return false
	}
	for _, r := range n {
		if r < 0x20 || r == 0x7f || strings.ContainsRune(`/\:*?"<>|`, r) {
			return false
		}
	}
	return true
}
