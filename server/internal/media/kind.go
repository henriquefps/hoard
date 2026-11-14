package media

import (
	"io"
	"path"
	"strings"
)

// Role of a file in an item (ADR 0014).
const (
	RoleRaw      = "raw"
	RoleRendered = "rendered"
	RoleVideo    = "video"
	RoleSidecar  = "sidecar"
)

// Rendered still formats, in the order a shot prefers them as its rendering (Hoard's ShotPairing: JPEG, then HEIF).
var renderedExt = map[string]string{"jpg": "jpeg", "jpeg": "jpeg", "jpe": "jpeg", "jfif": "jpeg", "heic": "heif", "heif": "heif", "hif": "heif"}

var rawExt = map[string]bool{"arw": true, "srf": true, "sr2": true, "cr2": true, "cr3": true, "nef": true, "nrw": true,
	"raf": true, "dng": true, "orf": true, "rw2": true, "pef": true, "srw": true}

var videoExt = map[string]bool{"mp4": true, "m4v": true, "mov": true, "3gp": true, "3g2": true, "mts": true, "m2ts": true,
	"mkv": true, "webm": true, "avi": true}

// Ext is the lower-case extension of a name without the dot ("" when there is none).
func Ext(name string) string {
	base := path.Base(name)
	dot := strings.LastIndexByte(base, '.')
	if dot <= 0 {
		return ""
	}
	return strings.ToLower(base[dot+1:])
}

// Stem is the name without its extension.
func Stem(name string) string {
	base := path.Base(name)
	if dot := strings.LastIndexByte(base, '.'); dot > 0 {
		return base[:dot]
	}
	return base
}

// Hidden reports names that are never items: dot files, including macOS "._X" resource forks and .DS_Store.
func Hidden(name string) bool { return strings.HasPrefix(path.Base(name), ".") }

// Classify gives a file's media class and role by its name ("" when the server does not index it).
func Classify(name string) (mediaClass, role string) {
	if Hidden(name) {
		return "", ""
	}
	ext := Ext(name)
	switch {
	case rawExt[ext]:
		return MediaImage, RoleRaw
	case renderedExt[ext] != "":
		return MediaImage, RoleRendered
	case videoExt[ext]:
		return MediaVideo, RoleVideo
	case ext == "xmp":
		return MediaSidecar, RoleSidecar
	}
	return "", ""
}

// RenderedRank orders renderings of one shot: JPEG before HEIF.
func RenderedRank(name string) int {
	if renderedExt[Ext(name)] == "jpeg" {
		return 0
	}
	return 1
}

// Sniff classifies content without a name (uploaded originals): its media class, role and format.
func Sniff(r io.ReaderAt) (mediaClass, role, format string) {
	var b [16]byte
	n, _ := r.ReadAt(b[:], 0)
	h := b[:n]
	switch {
	case len(h) >= 3 && h[0] == 0xFF && h[1] == 0xD8 && h[2] == 0xFF:
		return MediaImage, RoleRendered, "jpeg"
	case len(h) >= 4 && (string(h[:4]) == "II*\x00" || string(h[:4]) == "MM\x00*" || string(h[:4]) == "IIRO" ||
		string(h[:4]) == "MMOR" || string(h[:4]) == "IIU\x00" || string(h[:4]) == "IIRS"):
		return MediaImage, RoleRaw, "tiff"
	case len(h) >= 15 && string(h[:15]) == "FUJIFILMCCD-RAW":
		return MediaImage, RoleRaw, "raf"
	case len(h) >= 12 && string(h[4:8]) == "ftyp":
		brand := string(h[8:12])
		switch {
		case brand == "crx ":
			return MediaImage, RoleRaw, "cr3"
		case heifBrands[brand]:
			return MediaImage, RoleRendered, "heif"
		case brand == "qt  ":
			return MediaVideo, RoleVideo, "mov"
		default:
			return MediaVideo, RoleVideo, "mp4"
		}
	case len(h) >= 4 && string(h[:4]) == "\x1aE\xdf\xa3":
		return MediaVideo, RoleVideo, "mkv"
	case len(h) >= 12 && string(h[:4]) == "RIFF" && string(h[8:12]) == "AVI ":
		return MediaVideo, RoleVideo, "avi"
	}
	return "", "", ""
}
