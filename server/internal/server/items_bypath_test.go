package server

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"testing"
	"time"
)

// TestItemsByPath drives POST /v1/items/by-path against a real index (ADR 0014 "Items by path").
func TestItemsByPath(t *testing.T) {
	hs, key, folderID := itemsHarness(t)
	post := func(body any) map[string]any {
		t.Helper()
		w := hs.do("POST", "/v1/items/by-path", body, bearer(key))
		expect(t, w, 200, "")
		return decode(t, w)
	}
	// The truth, from the query endpoint: path of every main file and its item.
	byDir := page(t, hs, key, "/v1/items?folder="+folderID+"&path=2024-05-01&limit=50").Items
	if len(byDir) != 4 {
		t.Fatalf("%d items on day 1", len(byDir))
	}

	cases := []struct {
		name    string
		paths   []string
		found   []string // as sent
		missing []string
	}{
		{"found and missing", []string{"2024-05-01/DSC00100.ARW", "2024-05-01/DSC00100.JPG", "2024-05-01/nope.ARW"},
			[]string{"2024-05-01/DSC00100.ARW", "2024-05-01/DSC00100.JPG"}, []string{"2024-05-01/nope.ARW"}},
		{"normalised", []string{"./2024-05-01//DSC00101.JPG", "2024-05-02/./DSC00200.JPG/"},
			[]string{"./2024-05-01//DSC00101.JPG", "2024-05-02/./DSC00200.JPG/"}, nil},
		{"sub-directory", []string{"2024-05-03/sub/PXL_1.MP.jpg"}, []string{"2024-05-03/sub/PXL_1.MP.jpg"}, nil},
		{"case matters", []string{"2024-05-01/dsc00101.jpg"}, nil, []string{"2024-05-01/dsc00101.jpg"}},
		{"duplicates once", []string{"2024-05-01/DSC00101.JPG", "2024-05-01/DSC00101.JPG"}, []string{"2024-05-01/DSC00101.JPG"}, nil},
		{"none", []string{}, nil, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			out := post(map[string]any{"folder": folderID, "paths": c.paths})
			var found, missing []string
			for _, f := range out["found"].([]any) {
				m := f.(map[string]any)
				found = append(found, m["path"].(string))
				if m["itemId"] == "" || m["role"] == "" || m["size"].(float64) <= 0 || m["modifiedAt"].(float64) < 1e12 {
					t.Fatalf("entry %v", m)
				}
			}
			for _, m := range out["missing"].([]any) {
				missing = append(missing, m.(string))
			}
			if !slices.Equal(found, c.found) || !slices.Equal(missing, c.missing) {
				t.Fatalf("found %v missing %v", found, missing)
			}
		})
	}

	// The item and role agree with the item query; ARW and JPG of a pair are one item with two roles.
	out := post(map[string]any{"folder": folderID, "paths": []string{"2024-05-01/DSC00100.ARW", "2024-05-01/DSC00100.JPG"}})
	f := out["found"].([]any)
	a, b := f[0].(map[string]any), f[1].(map[string]any)
	if a["itemId"] != b["itemId"] || a["role"] != "raw" || b["role"] != "rendered" {
		t.Fatalf("%v %v", a, b)
	}
	one := decode(t, hs.do("GET", "/v1/items/"+a["itemId"].(string), nil, bearer(key)))
	if one["deleted"] != false || one["id"] != a["itemId"] {
		t.Fatalf("%v", one)
	}

	// Refused: traversal and other bad paths name the index; bad folder, unknown folder, too many, no key.
	for _, bad := range []string{"../x.JPG", "a/../../x.JPG", "/etc/passwd", "a\\b.JPG", "", ".", "a/..", "a\x00b", strings.Repeat("a", 1025)} {
		w := hs.do("POST", "/v1/items/by-path", map[string]any{"folder": folderID, "paths": []string{"ok.JPG", bad}}, bearer(key))
		expect(t, w, 422, "invalid_request")
		if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["index"] != float64(1) {
			t.Fatalf("%q: %v", bad, d)
		}
	}
	expect(t, hs.do("POST", "/v1/items/by-path", map[string]any{"folder": "x", "paths": []string{"a.JPG"}}, bearer(key)), 422, "invalid_request")
	expect(t, hs.do("POST", "/v1/items/by-path", map[string]any{"folder": "0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f", "paths": []string{"a.JPG"}}, bearer(key)), 404, "not_found")
	many := make([]string, maxLookup+1)
	for i := range many {
		many[i] = fmt.Sprintf("d/%d.JPG", i)
	}
	expect(t, hs.do("POST", "/v1/items/by-path", map[string]any{"folder": folderID, "paths": many}, bearer(key)), 422, "invalid_request")
	if got := post(map[string]any{"folder": folderID, "paths": many[:maxLookup]}); len(got["missing"].([]any)) != maxLookup {
		t.Fatal("1000 paths are allowed")
	}
	expect(t, hs.do("POST", "/v1/items/by-path", map[string]any{"folder": folderID, "paths": []string{"a.JPG"}}), 401, "unauthorized")

	// The feature is announced.
	feats := decode(t, hs.do("GET", "/v1/server", nil, bearer(key)))["features"].([]any)
	if !slices.Contains(feats, any("items-by-path")) {
		t.Fatalf("features %v", feats)
	}
}

// TestDeviceLiveness: GET /v1/device lists the live devices of the physical device (never revoked ones) with lastSeenAt.
func TestDeviceLiveness(t *testing.T) {
	hs := newHarness(t, false)
	reveal, revealID, _ := hs.pairApp("Pixel 8", "reveal", "Pixel 8")
	hoard, hoardID, _ := hs.pairApp("Pixel 8", "hoard", "Pixel 8")
	expect(t, hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": revealID}, bearer(hoard)), 200, "")

	entries := func(key string) map[string]map[string]any {
		t.Helper()
		out := map[string]map[string]any{}
		devs := decode(t, hs.do("GET", "/v1/device", nil, bearer(key)))["physicalDevice"].(map[string]any)["devices"].([]any)
		for _, d := range devs {
			m := d.(map[string]any)
			if _, ok := m["lastSeenAt"]; !ok {
				t.Fatalf("%v lacks lastSeenAt", m)
			}
			if _, ok := m["revoked"]; ok {
				t.Fatalf("%v has a revoked field", m)
			}
			out[m["id"].(string)] = m
		}
		return out
	}
	// Hoard just authenticated: listed, not revoked, seen now. Reveal only paired so far: never seen (null, not absent).
	now := time.Now().UnixMilli()
	e := entries(hoard)
	if len(e) != 2 || e[hoardID]["lastSeenAt"] == nil ||
		now-int64(e[hoardID]["lastSeenAt"].(float64)) > 60_000 || e[revealID]["lastSeenAt"] != nil {
		t.Fatalf("%v", e)
	}
	if e = entries(reveal); e[revealID]["lastSeenAt"] == nil { // its own request is a sighting
		t.Fatalf("%v", e)
	}
	// Never seen: null, not absent (authenticating always records a sighting, so this is the JSON shape).
	if js, _ := json.Marshal(physicalEntry{}); !strings.Contains(string(js), `"lastSeenAt":null`) {
		t.Fatalf("%s", js)
	}
	// lastSeenAt moves forward when the device authenticates again (writes are rate-limited, so only check it never goes back).
	first := int64(e[hoardID]["lastSeenAt"].(float64))
	if again := entries(hoard); int64(again[hoardID]["lastSeenAt"].(float64)) < first {
		t.Fatalf("lastSeenAt went back: %v", again)
	}
	// Revoked: no longer listed (devices is live-only, as before 0.10.0); Hoard keeps seeing itself.
	if err := hs.st.RevokeDevice(t.Context(), revealID); err != nil {
		t.Fatal(err)
	}
	e = entries(hoard)
	if _, listed := e[revealID]; len(e) != 1 || listed || e[hoardID] == nil {
		t.Fatalf("%v", e)
	}
	expect(t, hs.do("GET", "/v1/device", nil, bearer(reveal)), 401, "unauthorized")
	// A revoked device is not a candidate and not in GET /v1/devices (unchanged).
	for _, d := range decode(t, hs.do("GET", "/v1/devices", nil, bearer(hoard)))["devices"].([]any) {
		if d.(map[string]any)["id"] == revealID {
			t.Fatal("revoked device listed")
		}
	}
}
