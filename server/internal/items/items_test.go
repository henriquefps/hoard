package items

import (
	"strings"
	"testing"

	"github.com/henriquefps/hoard/server/internal/media"
)

func fp(c byte) string { return "v1:" + strings.Repeat(string(c), 64) }

func TestGroupFolder(t *testing.T) {
	groups := GroupFolder([]Seen{
		{Path: "a/DSC1.ARW", Fingerprint: fp('1')},
		{Path: "a/dsc1.jpg", Fingerprint: fp('2')},
		{Path: "a/DSC1.HEIC", Fingerprint: fp('3')},
		{Path: "a/DSC1.ARW.xmp", Fingerprint: fp('4')},
		{Path: "a/DSC1.xmp", Fingerprint: fp('5')},
		{Path: "a/IMG2.HEIC", Fingerprint: fp('6')},
		{Path: "a/IMG2.JPG", Fingerprint: fp('7')},
		{Path: "a/IMG3.xmp", Fingerprint: fp('8')},              // a sidecar alone is no item
		{Path: "a/IMG4.JPG"},                                    // not readable yet
		{Path: "b/DSC1.JPG", Fingerprint: fp('9')},              // another directory, another shot
		{Path: "a/@eaDir/DSC1.JPG/x.jpg", Fingerprint: fp('a')}, // Synology thumbnails
		{Path: "a/._DSC1.ARW", Fingerprint: fp('b')},
		{Path: "a/C0001.MP4", Fingerprint: fp('c')},
		{Path: "a/notes.txt", Fingerprint: fp('d')},
	})
	var got []string
	for _, g := range groups {
		var names []string
		for _, m := range g.Members {
			names = append(names, m.Role+":"+m.Name)
		}
		got = append(got, g.Dir+"|"+g.Key+"|"+strings.Join(names, ","))
	}
	want := []string{
		"a|c0001|video:C0001.MP4",
		"a|dsc1|raw:DSC1.ARW,rendered:dsc1.jpg,rendered:DSC1.HEIC,sidecar:DSC1.ARW.xmp,sidecar:DSC1.xmp",
		"a|img2|rendered:IMG2.JPG,rendered:IMG2.HEIC",
		"b|dsc1|rendered:DSC1.JPG",
	}
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Fatalf("groups:\n%s\nwant:\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
	if groups[1].Main != fp('1') || groups[2].Main != fp('7') {
		t.Fatal("main file")
	}
}

func TestID(t *testing.T) {
	id := ID(fp('1'))
	if id != ID(fp('1')) || id == ID(fp('2')) || len(id) != 36 || id[14] != '8' || !strings.ContainsAny(id[19:20], "89ab") {
		t.Fatalf("id %s", id)
	}
}

func TestBuild(t *testing.T) {
	nas := func(members ...Member) Source {
		return Source{Type: SourceNAS, FolderID: "f", FolderName: "Photos", Members: members}
	}
	exif := &media.Capture{Local: "2024-05-01T10:00:00", Source: "exif"}
	metas := map[string]media.Meta{
		fp('1'): {Media: media.MediaImage, Format: "tiff", Make: "SONY", Capture: exif, Orientation: 1},
		fp('2'): {Media: media.MediaImage, Format: "jpeg", Width: 6000, Height: 4000, Lens: "FE 35mm"},
		fp('3'): {Media: media.MediaVideo, Format: "mov", Video: &media.Video{DurationMs: 2000, Codec: "hevc"}, Width: 1920, Height: 1080},
	}
	d, ok := Build(fp('1'), []Source{nas(
		Member{Fingerprint: fp('1'), Role: media.RoleRaw, Name: "A.ARW", Path: "A.ARW", MTime: 5e15},
		Member{Fingerprint: fp('2'), Role: media.RoleRendered, Name: "A.JPG", Path: "A.JPG"},
	)}, metas)
	if !ok || d.Kind != KindPair || d.Width != 6000 || d.Lens != "FE 35mm" || d.Make != "SONY" || d.SortTime == 0 {
		t.Fatalf("pair %+v", d)
	}
	d, _ = Build(fp('1'), []Source{nas(
		Member{Fingerprint: fp('1'), Role: media.RoleRaw, Name: "A.ARW"},
		Member{Fingerprint: fp('2'), Role: media.RoleRendered, Name: "A.JPG"},
		Member{Fingerprint: fp('3'), Role: media.RoleVideo, Name: "A.MOV"},
	)}, metas)
	if d.Kind != KindMotion || d.Motion.Source != "file" || d.Motion.Fingerprint != fp('3') || d.Video.Codec != "hevc" {
		t.Fatalf("raw + jpeg + mov %+v", d)
	}
	d, _ = Build(fp('3'), []Source{nas(Member{Fingerprint: fp('3'), Role: media.RoleVideo, Name: "C.MOV", Path: "C.MOV", MTime: 1714557600e9})}, metas)
	if d.Kind != KindVideo || d.Width != 1920 || d.Capture.Source != "mtime" || d.SortTime != 1714557600000 || d.Orientation != 0 {
		t.Fatalf("video %+v", d)
	}
	if _, ok := Build(fp('1'), nil, metas); ok {
		t.Fatal("no sources is no item")
	}
	// The same file in two places: one file, two locations, NAS before backup.
	d, _ = Build(fp('2'), []Source{
		{Type: SourceBackup, Members: []Member{{Fingerprint: fp('2'), Role: media.RoleRendered, SHA256: "ab"}}},
		nas(Member{Fingerprint: fp('2'), Role: media.RoleRendered, Name: "A.JPG", Path: "x/A.JPG"}),
	}, metas)
	if len(d.Files) != 1 || len(d.Files[0].Locations) != 2 || d.Files[0].Locations[0].Source != "nas" || d.Files[0].Name != "A.JPG" {
		t.Fatalf("two locations %+v", d.Files)
	}
}
