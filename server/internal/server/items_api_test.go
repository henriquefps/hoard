package server

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"testing"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
)

type itemsPage struct {
	Items   []map[string]any `json:"items"`
	Cursor  *string          `json:"cursor"`
	HasMore bool             `json:"hasMore"`
	Matches []map[string]any `json:"matches"`
	Missing []string         `json:"missing"`
}

func page(t *testing.T, hs *harness, key, path string) itemsPage {
	t.Helper()
	w := hs.do("GET", path, nil, bearer(key))
	expect(t, w, 200, "")
	var p itemsPage
	if err := json.Unmarshal(w.Body.Bytes(), &p); err != nil {
		t.Fatal(err)
	}
	return p
}

// itemsHarness indexes a NAS folder with photos from three days, a pair and a video-less motion photo.
func itemsHarness(t *testing.T) (*harness, string, string) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Tablet")
	write := func(rel string, b []byte) {
		p := filepath.Join(hs.nas, rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for day := 1; day <= 3; day++ {
		for i := range 4 {
			s := mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", DateTime: fmt.Sprintf("2024:05:%02d 10:%02d:00", day, i), Offset: "+00:00",
				Width: 600, Height: 400, Unique: uint64(day*100 + i)}
			write(fmt.Sprintf("2024-05-%02d/DSC%05d.JPG", day, day*100+i), mediatest.JPEG(s, ""))
			if i == 0 {
				s.Unique += 50
				write(fmt.Sprintf("2024-05-%02d/DSC%05d.ARW", day, day*100+i), mediatest.RAW(s))
			}
		}
	}
	write("2024-05-03/sub/PXL_1.MP.jpg", mediatest.MotionJPEG(mediatest.Shot{DateTime: "2024:05:03 23:00:00", Offset: "+00:00", Unique: 999},
		mediatest.MiniMP4(1)))
	w := hs.do("POST", "/admin/api/nas", map[string]any{"path": hs.nas}, adminHeaders())
	expect(t, w, 201, "")
	folderID := decode(t, w)["id"].(string)
	f, _ := hs.st.NasFolder(t.Context(), folderID)
	if err := hs.srv.indexer.Folder(t.Context(), f); err != nil {
		t.Fatal(err)
	}
	return hs, key, folderID
}

func TestItemsFeedQueryAndLookup(t *testing.T) {
	hs, key, folderID := itemsHarness(t)

	// The feed: every item once, oldest revision first, with paging and its own cursors.
	p := page(t, hs, key, "/v1/items/changes?limit=5")
	if len(p.Items) != 5 || !p.HasMore || p.Cursor == nil {
		t.Fatalf("first page %+v", p)
	}
	all := p.Items
	for p.HasMore {
		p = page(t, hs, key, "/v1/items/changes?limit=5&cursor="+url.QueryEscape(*p.Cursor))
		all = append(all, p.Items...)
	}
	if len(all) != 13 {
		t.Fatalf("feed has %d items", len(all))
	}
	ids := map[string]map[string]any{}
	for _, it := range all {
		ids[it["id"].(string)] = it
		if it["deleted"] != false || it["rev"] == nil || it["files"] == nil || it["kind"] == nil {
			t.Fatalf("item %v", it)
		}
	}
	expect(t, hs.do("GET", "/v1/items/changes?cursor="+url.QueryEscape(*p.Cursor)+"0", nil, bearer(key)), 400, "invalid_cursor")
	expect(t, hs.do("GET", "/v1/items/changes?cursor=abc:i1", nil, bearer(key)), 400, "invalid_cursor")
	// The records feed and the items feed do not share cursors.
	expect(t, hs.do("GET", "/v1/changes?cursor="+url.QueryEscape(*p.Cursor), nil, bearer(key)), 400, "invalid_cursor")
	expect(t, hs.do("GET", "/v1/items/changes", nil), 401, "unauthorized")

	// Query: newest first by default, keyset paging.
	q := page(t, hs, key, "/v1/items?limit=4")
	if len(q.Items) != 4 || !q.HasMore || q.Items[0]["kind"] != "motion" {
		t.Fatalf("query %+v", q.Items)
	}
	seen := len(q.Items)
	prev := q.Items[len(q.Items)-1]["sortTime"].(float64)
	for q.HasMore {
		q = page(t, hs, key, "/v1/items?limit=4&cursor="+url.QueryEscape(*q.Cursor))
		for _, it := range q.Items {
			if it["sortTime"].(float64) > prev {
				t.Fatal("not newest first")
			}
			prev = it["sortTime"].(float64)
		}
		seen += len(q.Items)
	}
	if seen != 13 || q.Cursor != nil {
		t.Fatalf("query saw %d, last cursor %v", seen, q.Cursor)
	}
	// Filters: kind, time range, folder + path.
	if n := len(page(t, hs, key, "/v1/items?kind=pair").Items); n != 3 {
		t.Fatalf("pairs: %d", n)
	}
	if n := len(page(t, hs, key, "/v1/items?kind=pair,motion&order=asc").Items); n != 4 {
		t.Fatalf("pairs and motion: %d", n)
	}
	from := "1714557600000" // 2024-05-01T10:00:00Z
	to := "1714644000000"   // 2024-05-02T10:00:00Z
	if n := len(page(t, hs, key, "/v1/items?from="+from+"&to="+to).Items); n != 4 {
		t.Fatalf("one day: %d", n)
	}
	if n := len(page(t, hs, key, "/v1/items?folder="+folderID+"&path=2024-05-03").Items); n != 5 {
		t.Fatalf("folder path (with sub-folders): %d", n)
	}
	if n := len(page(t, hs, key, "/v1/items?folder="+folderID+"&path=2024-05-0").Items); n != 0 {
		t.Fatalf("a path prefix is not a directory: %d", n)
	}
	for _, bad := range []string{"kind=raw", "from=yesterday", "path=x", "folder=x", "order=up", "limit=0",
		"folder=" + folderID + "&path=/abs"} {
		expect(t, hs.do("GET", "/v1/items?"+bad, nil, bearer(key)), 400, "invalid_request")
	}
	asc := page(t, hs, key, "/v1/items?order=asc&limit=2")
	expect(t, hs.do("GET", "/v1/items?order=desc&cursor="+url.QueryEscape(*asc.Cursor), nil, bearer(key)), 400, "invalid_cursor")

	// One item; a tombstone stays readable.
	var pair map[string]any
	for _, it := range all {
		if it["kind"] == "pair" {
			pair = it
			break
		}
	}
	w := hs.do("GET", "/v1/items/"+pair["id"].(string), nil, bearer(key))
	expect(t, w, 200, "")
	if one := decode(t, w); one["mainFingerprint"] != pair["mainFingerprint"] || len(one["files"].([]any)) != 2 {
		t.Fatalf("one item %v", one)
	}
	expect(t, hs.do("GET", "/v1/items/00000000-0000-4000-8000-000000000000", nil, bearer(key)), 404, "not_found")
	expect(t, hs.do("GET", "/v1/items/..%2f..%2fetc", nil, bearer(key)), 404, "not_found")

	// Lookup by the JPEG of the pair: the pair, with the role of the match.
	files := pair["files"].([]any)
	jpg := files[1].(map[string]any)["fingerprint"].(string)
	unknown := fingerprint.Bytes([]byte("nothing"))
	w = hs.do("POST", "/v1/items/lookup", map[string]any{"fingerprints": []string{jpg, unknown, unknown}}, bearer(key))
	expect(t, w, 200, "")
	var lk itemsPage
	_ = json.Unmarshal(w.Body.Bytes(), &lk)
	if len(lk.Items) != 1 || lk.Items[0]["id"] != pair["id"] || len(lk.Matches) != 1 || lk.Matches[0]["role"] != "rendered" ||
		len(lk.Missing) != 1 || lk.Missing[0] != unknown {
		t.Fatalf("lookup %s", w.Body.String())
	}
	expect(t, hs.do("POST", "/v1/items/lookup", map[string]any{"fingerprints": []string{"v1:XYZ"}}, bearer(key)), 422, "invalid_request")

	// Health counts items; /v1/server announces the feature.
	if c := decode(t, hs.do("GET", "/v1/health", nil))["counts"].(map[string]any); c["items"] != float64(13) {
		t.Fatalf("health %v", c)
	}
	if f := decode(t, hs.do("GET", "/v1/server", nil, bearer(key)))["features"].([]any); len(f) != 1 || f[0] != "items" {
		t.Fatalf("features %v", f)
	}

	// Removing the folder: the feed delivers tombstones from the last cursor.
	expect(t, hs.do("DELETE", "/admin/api/nas/"+folderID, nil, adminHeaders()), 200, "")
	hs.srv.indexer.All(t.Context())
	p = page(t, hs, key, "/v1/items/changes?limit=500&cursor="+url.QueryEscape(*p.Cursor))
	if len(p.Items) != 13 {
		t.Fatalf("tombstones: %d", len(p.Items))
	}
	for _, it := range p.Items {
		if it["deleted"] != true || it["files"] != nil {
			t.Fatalf("tombstone %v", it)
		}
	}
	if n := len(page(t, hs, key, "/v1/items").Items); n != 0 {
		t.Fatalf("query after removal: %d", n)
	}
}

func TestUploadedOriginalsBecomeItems(t *testing.T) {
	hs := newHarness(t, true)
	key, _ := hs.pair("Phone")
	jpg := mediatest.JPEG(mediatest.Shot{Make: "Google", Model: "Pixel 9", DateTime: "2024:07:01 08:00:00", Offset: "-03:00", Unique: 42}, "")
	_, fp := uploadOriginal(t, hs, key, jpg)
	hs.srv.indexer.Backup(t.Context())
	w := hs.do("POST", "/v1/items/lookup", map[string]any{"fingerprints": []string{fp}}, bearer(key))
	var lk itemsPage
	_ = json.Unmarshal(w.Body.Bytes(), &lk)
	if len(lk.Items) != 1 || lk.Items[0]["make"] != "Google" {
		t.Fatalf("upload item %s", w.Body.String())
	}
	loc := lk.Items[0]["files"].([]any)[0].(map[string]any)["locations"].([]any)[0].(map[string]any)
	if loc["source"] != "backup" || loc["sha256"] != sum(jpg) {
		t.Fatalf("location %v", loc)
	}
	// Deleting the original (no record names it): the item becomes a tombstone.
	expect(t, hs.do("DELETE", "/v1/originals/"+fp, nil, bearer(key)), 204, "")
	hs.srv.indexer.Backup(t.Context())
	w = hs.do("GET", "/v1/items/"+lk.Items[0]["id"].(string), nil, bearer(key))
	if decode(t, w)["deleted"] != true {
		t.Fatalf("after delete %s", w.Body.String())
	}
}
