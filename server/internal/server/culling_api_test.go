package server

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"testing"

	"github.com/henriquefps/hoard/server/internal/store"
)

const fpA = "v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

// pairApp pairs a device with ADR 0017's fields and returns key, id and the whole answer.
func (hs *harness) pairApp(name, app, model string) (string, string, map[string]any) {
	hs.t.Helper()
	if hs.cookie == nil {
		hs.signIn()
	}
	w := hs.do("POST", "/admin/api/pairings", map[string]any{"url": "https://sync.example.test"}, adminHeaders())
	expect(hs.t, w, 201, "")
	token := decode(hs.t, w)["token"].(string)
	saved := hs.cookie
	hs.cookie = nil
	w = hs.do("POST", "/v1/pair", map[string]any{"token": token, "deviceName": name, "platform": "android", "appVersion": "1.0",
		"app": app, "manufacturer": "Google", "model": model})
	hs.cookie = saved
	expect(hs.t, w, 201, "")
	m := decode(hs.t, w)
	return m["apiKey"].(string), m["deviceId"].(string), m
}

func TestCullingPushAndFeed(t *testing.T) {
	hs := newHarness(t, false)
	hoard, _ := hs.pair("Hoard")
	reveal, revealID := hs.pair("Reveal")

	// Validation: the whole push is refused with the index of the bad change.
	for i, bad := range []map[string]any{
		{"field": "rating", "value": 3, "baseRev": 0, "modifiedAt": 1},                                                                       // no shot
		{"itemId": "x", "field": "rating", "value": 3, "baseRev": 0, "modifiedAt": 1},                                                        // bad id
		{"fingerprint": fpA, "itemId": "0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f", "field": "rating", "value": 3, "baseRev": 0, "modifiedAt": 1}, // both
		{"fingerprint": fpA, "field": "stars", "value": 3, "baseRev": 0, "modifiedAt": 1},
		{"fingerprint": fpA, "field": "rating", "value": 6, "baseRev": 0, "modifiedAt": 1},
		{"fingerprint": fpA, "field": "flag", "value": "maybe", "baseRev": 0, "modifiedAt": 1},
		{"fingerprint": fpA, "field": "label", "value": strings.Repeat("x", 65), "baseRev": 0, "modifiedAt": 1},
		{"fingerprint": fpA, "field": "rating", "value": 3, "modifiedAt": 1},
		{"fingerprint": fpA, "field": "rating", "value": 3, "baseRev": 0},
		{"itemId": "0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f", "field": "rating", "value": 3, "baseRev": 0, "modifiedAt": 1}, // unknown item
	} {
		w := hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
			map[string]any{"fingerprint": fpA, "field": "rating", "value": 1, "baseRev": 0, "modifiedAt": 1}, bad}}, bearer(hoard))
		expect(t, w, 422, "invalid_change")
		if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["index"] != float64(1) {
			t.Fatalf("case %d: %v", i, d)
		}
	}
	expect(t, hs.do("POST", "/v1/culling", map[string]any{"changes": []any{}}, bearer(hoard)), 422, "invalid_change")
	expect(t, hs.do("GET", "/v1/culling/0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f", nil, bearer(hoard)), 404, "not_found")

	// Hoard rates; Reveal picks at the same moment from an older state: both survive.
	w := hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
		map[string]any{"fingerprint": fpA, "field": "rating", "value": 4, "baseRev": 0, "modifiedAt": 1000}}}, bearer(hoard))
	expect(t, w, 200, "")
	res := decode(t, w)["results"].([]any)[0].(map[string]any)
	item := res["itemId"].(string)
	if res["status"] != "applied" {
		t.Fatalf("%v", res)
	}
	w = hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
		map[string]any{"itemId": item, "field": "flag", "value": "pick", "baseRev": 0, "modifiedAt": 1000},
		map[string]any{"itemId": item, "field": "rating", "value": 2, "baseRev": 0, "modifiedAt": 900}}}, bearer(reveal))
	expect(t, w, 200, "")
	rs := decode(t, w)["results"].([]any)
	if rs[0].(map[string]any)["status"] != "applied" || rs[1].(map[string]any)["status"] != "conflict" || rs[1].(map[string]any)["winner"] != "server" {
		t.Fatalf("%v", rs)
	}
	// A clock far ahead is clipped.
	w = hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
		map[string]any{"itemId": item, "field": "label", "value": "Red", "baseRev": 0, "modifiedAt": int64(1) << 52}}}, bearer(reveal))
	if r := decode(t, w)["results"].([]any)[0].(map[string]any); r["clipped"] != true {
		t.Fatalf("%v", r)
	}

	rec := decode(t, hs.do("GET", "/v1/culling/"+item, nil, bearer(hoard)))
	if rec["rating"].(map[string]any)["value"] != float64(4) || rec["flag"].(map[string]any)["value"] != "pick" ||
		rec["flag"].(map[string]any)["deviceId"] != revealID || rec["label"].(map[string]any)["value"] != "Red" {
		t.Fatalf("%v", rec)
	}

	// The feed: without include, items only (and no culling key); with culling, the record once.
	plain := decode(t, hs.do("GET", "/v1/items/changes", nil, bearer(hoard)))
	if _, ok := plain["culling"]; ok || len(plain["items"].([]any)) != 0 {
		t.Fatalf("%v", plain)
	}
	feed := decode(t, hs.do("GET", "/v1/items/changes?include=culling", nil, bearer(hoard)))
	if c := feed["culling"].([]any); len(c) != 1 || c[0].(map[string]any)["itemId"] != item || feed["hasMore"] != false {
		t.Fatalf("%v", feed)
	}
	again := decode(t, hs.do("GET", "/v1/items/changes?include=items,culling&cursor="+feed["cursor"].(string), nil, bearer(hoard)))
	if len(again["culling"].([]any)) != 0 || again["cursor"] != feed["cursor"] {
		t.Fatalf("%v", again)
	}
	expect(t, hs.do("GET", "/v1/items/changes?include=photos", nil, bearer(hoard)), 400, "invalid_request")
}

func TestItemsFeedMergesItemsAndCulling(t *testing.T) {
	hs := newHarness(t, false)
	key, _ := hs.pair("Hoard")
	// Interleave culling writes with item revisions (items written through the store, as the indexer does).
	for i := range 3 {
		fp := fmt.Sprintf("v1:%064x", i+1)
		err := hs.st.ItemsTx(context.Background(), func(tx *store.ItemTx) error {
			_, err := tx.WriteItem(store.ItemID(fp), fp, "photo", int64(i), []byte(`{"kind":"photo"}`), nil, nil)
			return err
		})
		if err != nil {
			t.Fatal(err)
		}
		w := hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
			map[string]any{"fingerprint": fp, "field": "rating", "value": i, "baseRev": 0, "modifiedAt": 10}}}, bearer(key))
		expect(t, w, 200, "")
	}
	var revs []float64
	kinds := ""
	cursor := ""
	for range 10 {
		page := decode(t, hs.do("GET", "/v1/items/changes?include=items,culling&limit=2&cursor="+cursor, nil, bearer(key)))
		var pageRevs []float64
		for _, it := range page["items"].([]any) {
			pageRevs = append(pageRevs, it.(map[string]any)["rev"].(float64))
		}
		for _, c := range page["culling"].([]any) {
			pageRevs = append(pageRevs, c.(map[string]any)["rev"].(float64))
		}
		if len(pageRevs) > 2 {
			t.Fatalf("page over the limit: %v", page)
		}
		kinds += fmt.Sprintf("%d/%d ", len(page["items"].([]any)), len(page["culling"].([]any)))
		revs = append(revs, pageRevs...)
		cursor = page["cursor"].(string)
		if page["hasMore"] == false {
			break
		}
	}
	sort.Float64s(revs)
	if len(revs) != 6 || revs[0] != 1 || revs[5] != 6 {
		t.Fatalf("%v (%s)", revs, kinds)
	}
	// Items only: three items, the culling revisions skipped.
	plain := decode(t, hs.do("GET", "/v1/items/changes", nil, bearer(key)))
	if len(plain["items"].([]any)) != 3 || plain["cursor"] != hs.srv.cursor("i", 5) {
		t.Fatalf("%v", plain)
	}
}

func TestDeviceIdentity(t *testing.T) {
	hs := newHarness(t, false)
	// An older client pairs without the new fields: its own physical device, id = device id.
	old, oldID := hs.pair("Tablet")
	v := decode(t, hs.do("GET", "/v1/device", nil, bearer(old)))
	if v["physicalDevice"].(map[string]any)["id"] != oldID || v["app"] != nil {
		t.Fatalf("%v", v)
	}

	reveal, revealID, first := hs.pairApp("Pixel 8", "reveal", "Pixel 8")
	if first["physicalDeviceId"] != revealID {
		t.Fatalf("%v", first)
	}
	// Reveal is alone of its model: the tablet is a candidate, but not a model match.
	if c := first["candidates"].([]any); len(c) != 1 || c[0].(map[string]any)["match"] != "other" {
		t.Fatalf("%v", c)
	}

	hoard, hoardID, second := hs.pairApp("Pixel 8", "hoard", "pixel 8")
	c := second["candidates"].([]any)
	if len(c) != 2 || c[0].(map[string]any)["physicalDeviceId"] != revealID || c[0].(map[string]any)["match"] != "model" {
		t.Fatalf("candidates %v", c)
	}

	// One tap: Hoard claims Reveal's physical device.
	w := hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": revealID}, bearer(hoard))
	expect(t, w, 200, "")
	v = decode(t, w)
	pd := v["physicalDevice"].(map[string]any)
	if pd["id"] != revealID || len(pd["devices"].([]any)) != 2 {
		t.Fatalf("%v", v)
	}
	// Hoard's own physical device is gone; Reveal sees the group too, and no longer offers it.
	rv := decode(t, hs.do("GET", "/v1/device", nil, bearer(reveal)))
	if len(rv["physicalDevice"].(map[string]any)["devices"].([]any)) != 2 {
		t.Fatalf("%v", rv)
	}
	devs := decode(t, hs.do("GET", "/v1/devices", nil, bearer(reveal)))["devices"].([]any)
	for _, d := range devs {
		m := d.(map[string]any)
		if (m["id"] == hoardID || m["id"] == revealID) && m["physicalDeviceId"] != revealID {
			t.Fatalf("%v", m)
		}
	}

	// A second Hoard cannot join a phone that has one.
	hoard2, _, _ := hs.pairApp("Pixel 8", "hoard", "Pixel 8")
	expect(t, hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": revealID}, bearer(hoard2)), 409, "conflict")
	expect(t, hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": "0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f"}, bearer(hoard2)), 404, "not_found")
	expect(t, hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": "nope"}, bearer(hoard2)), 400, "invalid_request")

	// "Another device": Hoard moves out again, to a physical device with its own id.
	w = hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": nil}, bearer(hoard))
	expect(t, w, 200, "")
	if decode(t, w)["physicalDevice"].(map[string]any)["id"] != hoardID {
		t.Fatalf("%s", w.Body)
	}

	// The tablet's client reports itself later.
	w = hs.do("PATCH", "/v1/device", map[string]any{"app": "reveal", "manufacturer": "Samsung", "model": "Tab S9"}, bearer(old))
	expect(t, w, 200, "")
	if decode(t, w)["model"] != "Tab S9" {
		t.Fatalf("%s", w.Body)
	}
	expect(t, hs.do("PATCH", "/v1/device", map[string]any{"app": "Bad App"}, bearer(old)), 400, "invalid_request")

	// The admin page moves Hoard back to Reveal's phone.
	expect(t, hs.do("POST", "/admin/api/devices/"+hoardID+"/physical", map[string]any{"physicalDeviceId": revealID}, adminHeaders()), 200, "")
	list := decode(t, hs.do("GET", "/admin/api/devices", nil, adminHeaders()))["devices"].([]any)
	for _, d := range list {
		m := d.(map[string]any)
		if m["id"] == hoardID && (m["physicalDeviceId"] != revealID || m["app"] != "hoard" || m["model"] != "pixel 8") {
			t.Fatalf("%v", m)
		}
	}
	expect(t, hs.do("POST", "/admin/api/devices/"+hoardID+"/physical", map[string]any{"physicalDeviceId": "0b6f0b6f-0b6f-4b6f-8b6f-0b6f0b6f0b6f"}, adminHeaders()), 404, "not_found")
}
