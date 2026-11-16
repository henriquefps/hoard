package items

import (
	"testing"

	"github.com/henriquefps/hoard/server/internal/store"
)

func TestGroupMirror(t *testing.T) {
	e := func(id int64, path, fp, storeKind, removed string, on bool) store.MirrorMember {
		return store.MirrorMember{MirrorEntry: store.MirrorEntry{ID: id, PhysicalID: "p", Volume: "primary", Path: path, RootID: "r",
			Fingerprint: fp, SHA256: "sha-" + fp, Size: 10, ModifiedAt: 1000, Store: storeKind, OnDevice: on, RemovedReason: removed}}
	}
	nas := e(5, "DCIM/Camera/nas.jpg", "v1:n", store.StoreNAS, "", true)
	nas.NasFolderID, nas.NasPath, nas.NasMTime = "f", "2024/nas.jpg", 7e9
	gs := GroupMirror("DCIM", []store.MirrorMember{
		e(1, "DCIM/Camera/PXL_1.jpg", "v1:j", store.StoreBlob, "", true),
		e(2, "DCIM/Camera/PXL_1.dng", "v1:d", store.StoreBlob, "", true),
		e(3, "DCIM/Camera/PXL_1.jpg", "v1:old", store.StoreBlob, store.RemovedReplaced, false),
		e(4, "DCIM/Camera/freed.jpg", "v1:f", store.StoreBlob, store.RemovedFreed, false),
		nas,
		e(6, "DCIM/Camera/notes.txt", "v1:t", store.StoreBlob, "", true),
		e(7, "DCIM/Camera/lone.xmp", "v1:x", store.StoreBlob, "", true),
	}, "Pixel 8")
	if len(gs) != 4 {
		t.Fatalf("%d groups: %+v", len(gs), gs)
	}
	byKey := map[string]Group{}
	for _, g := range gs {
		if g.Dir != "Camera" || g.Source != SourceMirror {
			t.Fatalf("%+v", g)
		}
		byKey[g.Key] = g
	}
	pair := byKey["pxl_1"]
	if pair.Main != "v1:d" || len(pair.Members) != 2 || pair.Members[0].Mirror.DeviceName != "Pixel 8" || pair.Members[0].SHA256 != "sha-v1:d" {
		t.Fatalf("%+v", pair)
	}
	if old := byKey["pxl_1~3"]; old.Main != "v1:old" || old.Members[0].Mirror.Gone != "deleted" || old.Members[0].Mirror.OnDevice {
		t.Fatalf("a previous version is a shot of its own: %+v", byKey)
	}
	if f := byKey["freed"]; f.Members[0].Mirror.Gone != "freed" {
		t.Fatalf("%+v", f)
	}
	n := byKey["nas"].Members[0]
	if n.SHA256 != "" || n.Mirror.NasPath != "2024/nas.jpg" || n.Mirror.NasFolderID != "f" {
		t.Fatalf("%+v", n)
	}
	// Build: the mirror location, next to the bytes (backup for stored, nas for a reference), no duplicates.
	d, ok := Build("v1:n", []Source{{Type: SourceMirror, FolderID: "r", Members: byKey["nas"].Members, Names: map[string]string{"f": "Photos"}},
		{Type: SourceNAS, FolderID: "f", FolderName: "Photos", Members: []Member{{Fingerprint: "v1:n", Role: "rendered", Name: "nas.jpg", Path: "2024/nas.jpg", Size: 10, MTime: 7e9}}}}, nil)
	if !ok || len(d.Files) != 1 || len(d.Files[0].Locations) != 2 {
		t.Fatalf("%+v", d)
	}
	l := d.Files[0].Locations
	if l[0].Source != SourceNAS || l[0].FolderName != "Photos" || l[1].Source != SourceMirror || l[1].Path != "DCIM/Camera/nas.jpg" ||
		l[1].OnDevice == nil || !*l[1].OnDevice || l[1].DeviceName != "Pixel 8" {
		t.Fatalf("%+v", l)
	}
	d, _ = Build("v1:f", []Source{{Type: SourceMirror, FolderID: "r", Members: byKey["freed"].Members}}, nil)
	l = d.Files[0].Locations
	if len(l) != 2 || l[0].Source != SourceMirror || *l[0].OnDevice || l[0].Gone != "freed" || l[1].Source != SourceBackup || l[1].SHA256 != "sha-v1:f" {
		t.Fatalf("%+v", l)
	}
}
