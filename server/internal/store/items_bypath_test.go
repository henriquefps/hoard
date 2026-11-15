package store

import (
	"context"
	"slices"
	"testing"
)

// TestItemsByPath: found, missing, removed files, other folders' sources, and a tombstone followed once.
func TestItemsByPath(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	const (
		folder, other = "f0000000-0000-4000-8000-000000000001", "f0000000-0000-4000-8000-000000000002"
		live, old     = "aaaaaaaa-0000-4000-8000-000000000001", "aaaaaaaa-0000-4000-8000-000000000002"
		dead, gone    = "aaaaaaaa-0000-4000-8000-000000000003", "aaaaaaaa-0000-4000-8000-000000000004"
		fpRaw, fpJpg  = "v1:raw", "v1:jpg"
	)
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := s.db.ExecContext(ctx, q, args...); err != nil {
			t.Fatal(err)
		}
	}
	for _, f := range []string{folder, other} {
		exec("INSERT INTO nas_folders (id, path, name, created_at) VALUES (?, ?, ?, 0)", f, "/nas/"+f, f)
	}
	rev := 0
	file := func(f, path string, size, mtimeNs int64, fp string, removed int) {
		rev++
		exec("INSERT INTO nas_files (folder_id, rel_path, size, mtime, fingerprint, removed, rev, media) VALUES (?, ?, ?, ?, ?, ?, ?, 'image')",
			f, path, size, mtimeNs, fp, removed, rev)
	}
	item := func(id, fp string, deleted int, data string) {
		rev++
		exec("INSERT INTO items (id, main_fp, rev, deleted, kind, data, created_at, updated_at) VALUES (?, ?, ?, ?, 'photo', ?, 0, 0)", id, fp, rev, deleted, data)
	}
	member := func(id, fp, role string) {
		exec("INSERT INTO item_files (fingerprint, item_id, role) VALUES (?, ?, ?)", fp, id, role)
	}
	source := func(id, f string) {
		exec("INSERT INTO item_sources (source, folder_id, dir, key, item_id, main_fp, signature, members) VALUES ('nas', ?, '', ?, ?, '', '', '[]')", f, id, id)
	}

	item(live, fpRaw, 0, "{}")
	member(live, fpRaw, "raw")
	member(live, fpJpg, "rendered")
	source(live, folder)
	file(folder, "d/a.ARW", 100, 1_714_735_200_123_456_789, fpRaw, 0)
	file(folder, "d/a.JPG", 50, 2_000_000, fpJpg, 0)
	file(folder, "d/removed.ARW", 1, 1, fpRaw, 1)
	file(other, "d/a.ARW", 100, 1, fpRaw, 0) // the same bytes in a folder the item has no source in
	// A tombstone that still names a file (data from before a rebuild) follows replacedBy once; one with no live
	// replacement is missing.
	item(old, "v1:old", 1, `{"replacedBy":"`+live+`"}`)
	member(old, "v1:old", "raw")
	source(old, folder)
	file(folder, "d/old.ARW", 7, 3_000_000, "v1:old", 0)
	item(dead, "v1:dead", 1, `{}`)
	member(dead, "v1:dead", "raw")
	source(dead, folder)
	file(folder, "d/dead.ARW", 8, 4_000_000, "v1:dead", 0)
	item(gone, "v1:gone", 1, `{"replacedBy":"bbbbbbbb-0000-4000-8000-000000000009"}`)
	member(gone, "v1:gone", "raw")
	source(gone, folder)
	file(folder, "d/gone.ARW", 9, 5_000_000, "v1:gone", 0)

	cases := []struct {
		name   string
		folder string
		paths  []string
		want   []PathMatch
	}{
		{"found with size and ms", folder, []string{"d/a.ARW", "d/a.JPG"}, []PathMatch{
			{"d/a.ARW", live, "raw", 100, 1_714_735_200_123}, {"d/a.JPG", live, "rendered", 50, 2}}},
		{"missing and removed", folder, []string{"d/nope.ARW", "d/removed.ARW"}, nil},
		{"a folder the item has no source in", other, []string{"d/a.ARW"}, nil},
		{"tombstone followed once", folder, []string{"d/old.ARW"}, []PathMatch{{"d/old.ARW", live, "raw", 7, 3}}},
		{"tombstone without replacement", folder, []string{"d/dead.ARW", "d/gone.ARW"}, nil},
		{"no paths", folder, nil, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := s.ItemsByPath(ctx, c.folder, c.paths)
			if err != nil {
				t.Fatal(err)
			}
			if !slices.Equal(got, c.want) {
				t.Fatalf("got %+v want %+v", got, c.want)
			}
		})
	}
}
