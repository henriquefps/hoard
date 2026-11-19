package store

import (
	"context"
	"path/filepath"
	"testing"
)

// LiveNasCopy counts live, indexed NAS files only: never a row in a trash or a Hoard folder (re-review N1).
func TestLiveNasCopy(t *testing.T) {
	ctx := context.Background()
	s, err := Open(filepath.Join(t.TempDir(), "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	f, err := s.AddNasFolder(ctx, "/mnt/nas/x", "x")
	if err != nil {
		t.Fatal(err)
	}
	fp := "v1:00000000000000000000000000000000000000000000000000000000000000ab"
	put := func(rel string, origin string) {
		if err := s.ApplyNasUpdates(ctx, f.ID, []NasFileUpdate{{RelPath: rel, Size: 1, MTime: 1, Fingerprint: fp, Media: "video", Origin: origin}}); err != nil {
			t.Fatal(err)
		}
	}
	put("A.MP4", "")
	for _, rel := range []string{".hoard-trash/2026-11-20/A.MP4", "sub/.hoard-trash/2026-11-20/A.MP4", ".hoard-compress/x/A.MP4"} {
		put(rel, "") // even without an origin (a row the walk could not map)
	}
	put(".hoard-trash/2026-11-21/A.MP4", "A.MP4")
	if ok, err := s.LiveNasCopy(ctx, fp, f.ID, "A.MP4"); err != nil || ok {
		t.Fatalf("a trash or Hoard-folder row counted as live: %v %v", ok, err)
	}
	// A removed original whose file went to a trash (trash_path set) is no live copy either.
	if err := s.ApplyNasUpdates(ctx, f.ID, []NasFileUpdate{{RelPath: "B.MP4", Size: 1, MTime: 1, Fingerprint: fp, Media: "video"}}); err != nil {
		t.Fatal(err)
	}
	if ok, _ := s.LiveNasCopy(ctx, fp, f.ID, "A.MP4"); !ok {
		t.Fatal("a real second copy not counted")
	}
	if err := s.ApplyNasUpdates(ctx, f.ID, []NasFileUpdate{{RelPath: "B.MP4", Removed: true, Media: "video", TrashPath: ".hoard-trash/2026-11-20/B.MP4"}}); err != nil {
		t.Fatal(err)
	}
	if ok, _ := s.LiveNasCopy(ctx, fp, f.ID, "A.MP4"); ok {
		t.Fatal("a trashed copy counted")
	}
}
