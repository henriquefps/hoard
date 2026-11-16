package mirror

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func TestCleanPathRefusesEveryTraversal(t *testing.T) {
	for _, ok := range []struct{ in, want string }{
		{"DCIM/Camera/PXL_1.jpg", "DCIM/Camera/PXL_1.jpg"},
		{"DCIM//Camera///a.jpg", "DCIM/Camera/a.jpg"},
		{"Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg"},
		{"Pictures/Café/é.jpg", "Pictures/Café/é.jpg"},
		{"a..b/c.jpg", "a..b/c.jpg"},
	} {
		got, err := CleanPath(ok.in, false)
		if err != nil || got != ok.want {
			t.Errorf("CleanPath(%q) = %q, %v", ok.in, got, err)
		}
	}
	bad := []string{
		"", "/DCIM/a.jpg", "DCIM/", "../a.jpg", "DCIM/../../etc/passwd", "DCIM/./a.jpg", "./a.jpg", "..",
		".hidden/a.jpg", "DCIM/.thumbnails/a.jpg", "DCIM/.a.jpg", `DCIM\a.jpg`, "DCIM/a\x00.jpg", "DCIM/a\n.jpg",
		"DCIM/a\x7f.jpg", "DCIM/a\u0085.jpg", "Pictures/Café.jpg", "\xff\xfe.jpg",
		strings.Repeat("a/", 600) + "b.jpg", strings.Repeat("x", 256) + ".jpg",
	}
	for _, in := range bad {
		if got, err := CleanPath(in, false); !errors.Is(err, ErrPath) {
			t.Errorf("CleanPath(%q) = %q, %v: want refused", in, got, err)
		}
	}
	if got, err := CleanPath("", true); err != nil || got != "" {
		t.Fatalf("a root's whole volume: %q %v", got, err)
	}
}

func TestVolumesAndPaths(t *testing.T) {
	for in, want := range map[string]string{"primary": "primary", "1234-abcd": "1234-ABCD", "ABCD-0001": "ABCD-0001",
		"0123abcd-0123-4567-89ab-0123456789ab": "0123ABCD-0123-4567-89AB-0123456789AB"} {
		if got, ok := CleanVolume(in); !ok || got != want {
			t.Errorf("CleanVolume(%q) = %q %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "Primary", "external", "12345-ABCD", "../x", "1234-ABCD/..", "0123ABCD-0123-4567-89AB-0123456789AB"} {
		if _, ok := CleanVolume(in); ok {
			t.Errorf("CleanVolume(%q) accepted", in)
		}
	}
	if !Within("", "a/b") || !Within("DCIM", "DCIM/a.jpg") || Within("DCIM", "DCIMX/a.jpg") || !Within("DCIM/Camera", "DCIM/Camera") {
		t.Fatal("Within")
	}
	if Relative("DCIM", "DCIM/Camera/a.jpg") != "Camera/a.jpg" || Relative("", "a/b") != "a/b" {
		t.Fatal("Relative")
	}
	if TreePath("primary", "DCIM/a.jpg") != "DCIM/a.jpg" || TreePath("1234-ABCD", "DCIM/a.jpg") != "SD card 1234-ABCD/DCIM/a.jpg" {
		t.Fatal("TreePath")
	}
	if PreviousPath("primary", "DCIM/a.jpg", "abcdef0123456789") != ".hoard-mirror/previous/DCIM/a.jpg~abcdef01" {
		t.Fatal(PreviousPath("primary", "DCIM/a.jpg", "abcdef0123456789"))
	}
}

func TestDirName(t *testing.T) {
	id := "a0e4c1d2-1111-4222-8333-444455556666"
	for in, want := range map[string]string{
		"Pixel 8":                    "Pixel 8 (a0e4c1d2)",
		"  ..My/Phone: \"x\" <y>|*?": "My_Phone_ _x_ _y____ (a0e4c1d2)",
		"":                           "Device (a0e4c1d2)",
		"...":                        "Device (a0e4c1d2)",
		"tab\there":                  "tab_here (a0e4c1d2)",
		"reveal-sync":                "reveal-sync (a0e4c1d2)",
		strings.Repeat("é", 30):      strings.Repeat("é", 24) + " (a0e4c1d2)", // 48 bytes at a character boundary
	} {
		if got := DirName(in, id); got != want {
			t.Errorf("DirName(%q) = %q, want %q", in, got, want)
		}
		if !ValidDirName(DirName(in, id)) {
			t.Errorf("DirName(%q) is not a valid folder name", in)
		}
	}
	for _, bad := range []string{"", "reveal-sync", ".x", "a/b", "x.", " x", "a\x00"} {
		if ValidDirName(bad) {
			t.Errorf("ValidDirName(%q)", bad)
		}
	}
}

func blob(t *testing.T, root string, data []byte) string {
	t.Helper()
	h := sha256.Sum256(data)
	sha := hex.EncodeToString(h[:])
	p := filepath.Join(root, "reveal-sync", "blobs", sha[:2], sha[2:4], sha)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return sha
}

func TestTreeLinksNeverCopies(t *testing.T) {
	root := t.TempDir()
	sha := blob(t, root, []byte("a photo"))
	other := blob(t, root, []byte("another photo"))
	tr, err := OpenTree(root, "reveal-sync")
	if err != nil {
		t.Fatal(err)
	}
	defer tr.Close()
	dev := "Pixel 8 (a0e4c1d2)"
	if err := tr.Place(dev, "DCIM/Camera/a.jpg", sha); err != nil {
		t.Fatal(err)
	}
	bp := filepath.Join(root, "reveal-sync", "blobs", sha[:2], sha[2:4], sha)
	bi, _ := os.Stat(bp)
	li, err := os.Stat(filepath.Join(root, dev, "DCIM/Camera/a.jpg"))
	if err != nil || !os.SameFile(bi, li) || bi.Mode().Perm() != 0o400 {
		t.Fatalf("hard link %v, mode %v", err, bi.Mode().Perm())
	}
	if err := tr.Place(dev, "DCIM/Camera/a.jpg", sha); err != nil { // again: kept
		t.Fatal(err)
	}
	// Another blob's link at the path is replaced; a file that is not a link stays (ErrOccupied).
	if err := tr.Place(dev, "DCIM/Camera/a.jpg", other); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, dev, "DCIM/mine.jpg"), []byte("the owner's"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := tr.Place(dev, "DCIM/mine.jpg", sha); !errors.Is(err, ErrOccupied) {
		t.Fatalf("a plain file in the way: %v", err)
	}
	// Remove takes only the blob's own link, then the directories it leaves empty.
	if err := tr.Remove(dev, "DCIM/Camera/a.jpg", sha); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, dev, "DCIM/Camera/a.jpg")); err != nil {
		t.Fatal("another blob's link was removed")
	}
	if err := tr.Remove(dev, "DCIM/Camera/a.jpg", other); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, dev, "DCIM/Camera")); !os.IsNotExist(err) {
		t.Fatal("empty directory left")
	}
	// No name can leave the tree.
	for _, bad := range []string{"../reveal-sync/x", "/etc/x", "a/../../x", ""} {
		if err := tr.Place(dev, bad, sha); !errors.Is(err, ErrPath) {
			t.Errorf("Place(%q): %v", bad, err)
		}
	}
	if err := tr.Place("reveal-sync", "x.jpg", sha); !errors.Is(err, ErrPath) {
		t.Fatalf("the blob store as a device folder: %v", err)
	}
	// A symbolic link leaving the backup root is refused by os.Root.
	if err := os.Symlink(t.TempDir(), filepath.Join(root, dev, "escape")); err != nil {
		t.Fatal(err)
	}
	if err := tr.Place(dev, "escape/a.jpg", sha); err == nil {
		t.Fatal("a link through a symbolic link out of the root")
	}
}

func TestTreeLinkFailureIsReturnedNeverCopied(t *testing.T) {
	root := t.TempDir()
	sha := blob(t, root, []byte("a photo"))
	tr, err := OpenTree(root, "reveal-sync")
	if err != nil {
		t.Fatal(err)
	}
	defer tr.Close()
	tr.Link = func(_ *os.Root, oldname, newname string) error {
		return &os.LinkError{Op: "link", Old: oldname, New: newname, Err: syscall.EXDEV}
	}
	err = tr.Place("P (1)", "DCIM/a.jpg", sha)
	if !errors.Is(err, syscall.EXDEV) || !IsLinkError(err) {
		t.Fatalf("%v", err)
	}
	if _, err := os.Stat(filepath.Join(root, "P (1)", "DCIM/a.jpg")); !os.IsNotExist(err) {
		t.Fatal("a file was made without a link")
	}
}

func TestManifest(t *testing.T) {
	b := string(Manifest("1234-ABCD", "DCIM", []ManifestRow{
		{Path: "DCIM/a.jpg", Size: 3, SHA256: "ab", Fingerprint: "v1:x", Where: "blob", OnDevice: true},
		{Path: "DCIM/b.jpg", Size: 4, Fingerprint: "v1:y", Where: "nas:Photos/2024\tx/b.jpg", Removed: "deleted"},
	}))
	want := "SD card 1234-ABCD/DCIM/a.jpg\t3\tab\tv1:x\tblob\t1\t\n" + "SD card 1234-ABCD/DCIM/b.jpg\t4\t\tv1:y\tnas:Photos/2024\\tx/b.jpg\t0\tdeleted\n"
	if !strings.HasSuffix(b, want) || !strings.HasPrefix(b, "# hoard-server phone backup manifest") {
		t.Fatalf("%q", b)
	}
}

// A path on main storage can never land in an SD card's folder of the tree.
func TestSDCardPrefixIsReservedOnMainStorage(t *testing.T) {
	for _, p := range []string{"SD card 1234-ABCD", "SD card 1234-abcd/DCIM/a.jpg", "sd CARD 0123abcd-0123-4567-89ab-0123456789ab/x.jpg"} {
		if err := CheckVolumePath(Primary, p); !errors.Is(err, ErrPath) {
			t.Errorf("%q accepted on primary", p)
		}
		if err := CheckVolumePath("1234-ABCD", p); err != nil {
			t.Errorf("%q refused on an SD card: %v", p, err)
		}
	}
	for _, p := range []string{"", "DCIM/SD card 1234-ABCD/a.jpg", "SD card backup/a.jpg", "SD card 1234-ABCDE/a.jpg", "SD cards/a.jpg"} {
		if err := CheckVolumePath(Primary, p); err != nil {
			t.Errorf("%q refused: %v", p, err)
		}
	}
}
