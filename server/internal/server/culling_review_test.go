package server

import (
	"testing"

	"github.com/henriquefps/hoard/server/internal/store"
)

// Tests for Reveal's review of ADRs 0016/0017 (findings A–E) that go through the API. The store-level halves are in
// internal/store/culling_review_test.go.

// Finding A: a device that declared the "culling" capability, or was seen using POST /v1/culling, is not bridged:
// its photo records' rating / flag / label no longer become culling writes.
func TestReviewA_BridgeIgnoresCullingAwareDevices(t *testing.T) {
	for _, c := range []struct {
		name    string
		setup   func(hs *harness, key string)
		bridged bool
		feature bool
	}{
		{"released Reveal (no capability)", func(*harness, string) {}, true, false},
		{"unknown feature only", func(hs *harness, key string) {
			expect(t, hs.do("PATCH", "/v1/device", map[string]any{"features": []string{"albums-v2"}}, bearer(key)), 200, "")
		}, true, false},
		{"declared with PATCH /v1/device", func(hs *harness, key string) {
			w := hs.do("PATCH", "/v1/device", map[string]any{"app": "reveal", "features": []string{"culling"}}, bearer(key))
			expect(t, w, 200, "")
			if f := decode(t, w)["features"].([]any); len(f) != 1 || f[0] != "culling" {
				t.Fatalf("features %v", f)
			}
		}, false, true},
		{"seen using POST /v1/culling", func(hs *harness, key string) {
			expect(t, hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
				map[string]any{"fingerprint": fpB, "field": "rating", "value": 1, "baseRev": 0, "modifiedAt": 1}}}, bearer(key)), 200, "")
		}, false, true},
		{"seen using POST /v1/culling, every change invalid", func(hs *harness, key string) {
			expect(t, hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
				map[string]any{"fingerprint": fpB, "field": "label", "value": "x\u0001"}}}, bearer(key)), 200, "")
		}, false, true},
	} {
		t.Run(c.name, func(t *testing.T) {
			hs := newHarness(t, false)
			key, _ := hs.pair("Reveal")
			c.setup(hs, key)
			fp := photo()["fingerprint"].(string)
			item := store.ItemID(fp)
			// Reveal rates the photo (a new record: non-default values bridge), then touches it again for an edit with
			// the old values and a new modifiedAt (the echo of finding A).
			push(t, hs, key, change("photo", "11111111-0000-4000-8000-000000000001", 0, 100, map[string]any{"fingerprint": fp, "rating": 3, "flag": "PICK"}))
			w := hs.do("GET", "/v1/culling/"+item, nil, bearer(key))
			if c.bridged {
				expect(t, w, 200, "")
				if r := decode(t, w); r["rating"].(map[string]any)["value"] != float64(3) || r["flag"].(map[string]any)["value"] != "pick" {
					t.Fatalf("%v", r)
				}
			} else {
				expect(t, w, 404, "not_found")
			}
			if f := decode(t, hs.do("GET", "/v1/device", nil, bearer(key)))["features"].([]any); (len(f) == 1) != c.feature {
				t.Fatalf("features %v", f)
			}
		})
	}

	// A declared capability is added, never removed; malformed features are refused.
	hs := newHarness(t, false)
	key, _ := hs.pair("Reveal")
	expect(t, hs.do("PATCH", "/v1/device", map[string]any{"features": []string{"culling"}}, bearer(key)), 200, "")
	w := hs.do("PATCH", "/v1/device", map[string]any{"features": []string{}}, bearer(key))
	if f := decode(t, w)["features"].([]any); len(f) != 1 {
		t.Fatalf("capability dropped: %v", f)
	}
	expect(t, hs.do("PATCH", "/v1/device", map[string]any{"features": []string{"Culling!"}}, bearer(key)), 400, "invalid_request")
}

// Finding C (API half): a label over 64 characters is an invalid result of its own; the batch answers 200 and the
// other changes of the same shot apply.
func TestReviewC_OverLimitLabelNeverFailsTheBatch(t *testing.T) {
	hs := newHarness(t, false)
	key, _ := hs.pair("Reveal")
	long := "Lightroom free text that someone typed into the colour label field, well over the limit"
	for _, c := range []struct {
		name  string
		label string
		want  []string
	}{
		{"valid label", "Red", []string{"applied", "applied", "applied"}},
		{"over-limit label", long, []string{"applied", "invalid", "applied"}},
	} {
		t.Run(c.name, func(t *testing.T) {
			fp := fpA
			if c.label != "Red" {
				fp = fpB
			}
			w := hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
				map[string]any{"fingerprint": fp, "field": "rating", "value": 4, "baseRev": 0, "modifiedAt": 10},
				map[string]any{"fingerprint": fp, "field": "label", "value": c.label, "baseRev": 0, "modifiedAt": 10},
				map[string]any{"fingerprint": fp, "field": "flag", "value": "pick", "baseRev": 0, "modifiedAt": 10},
			}}, bearer(key))
			expect(t, w, 200, "")
			rs := decode(t, w)["results"].([]any)
			for i, want := range c.want {
				r := rs[i].(map[string]any)
				if r["status"] != want || r["index"] != float64(i) {
					t.Fatalf("change %d: %v", i, r)
				}
				if want == "invalid" && (r["code"] != "label_too_long" || r["fingerprint"] != fp || r["field"] != "label") {
					t.Fatalf("%v", r)
				}
			}
		})
	}
}

// Finding D (API half): culling records in the items feed carry the item's member fingerprints, main first.
func TestReviewD_FeedRecordsCarryMemberFingerprints(t *testing.T) {
	hs := newHarness(t, false)
	key, _ := hs.pair("Reveal")
	raw, jpeg := fpA, fpB
	item := store.ItemID(raw)
	err := hs.st.ItemsTx(t.Context(), func(tx *store.ItemTx) error {
		_, err := tx.WriteItem(item, raw, "pair", 1, []byte(`{"kind":"pair"}`),
			[]store.ItemFile{{Fingerprint: jpeg, Role: "rendering"}, {Fingerprint: raw, Role: "raw"}}, nil)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	// Reveal only has the JPEG (its own main fingerprint): the push finds the pair, and the feed names both files.
	w := hs.do("POST", "/v1/culling", map[string]any{"changes": []any{
		map[string]any{"fingerprint": jpeg, "field": "rating", "value": 5, "baseRev": 0, "modifiedAt": 10}}}, bearer(key))
	if r := decode(t, w)["results"].([]any)[0].(map[string]any); r["itemId"] != item {
		t.Fatalf("%v", r)
	}
	feed := decode(t, hs.do("GET", "/v1/items/changes?include=culling", nil, bearer(key)))
	rec := feed["culling"].([]any)[0].(map[string]any)
	fps := rec["fingerprints"].([]any)
	if len(fps) != 2 || fps[0] != raw || fps[1] != jpeg || rec["replacedBy"] != nil {
		t.Fatalf("%v", rec)
	}
	if got := decode(t, hs.do("GET", "/v1/culling/"+item, nil, bearer(key)))["fingerprints"].([]any); len(got) != 2 {
		t.Fatalf("%v", got)
	}
}

// Finding E: build variants are distinct apps of one phone (reveal, reveal-debug, reveal-bench, hoard, hoard-debug);
// the same variant twice is still refused.
func TestReviewE_BuildVariantsShareAPhone(t *testing.T) {
	hs := newHarness(t, false)
	_, revealID, _ := hs.pairApp("Pixel 8", "reveal", "Pixel 8")
	for _, c := range []struct {
		app    string
		status int
	}{
		{"reveal-debug", 200},
		{"reveal-bench", 200},
		{"hoard", 200},
		{"hoard-debug", 200},
		{"reveal", 409},       // a second release Reveal: revoke the old one first
		{"reveal-debug", 409}, // a second debug build too
	} {
		t.Run(c.app, func(t *testing.T) {
			key, id, paired := hs.pairApp("Pixel 8", c.app, "Pixel 8")
			if c.status == 200 {
				// The variant's pairing offers the phone that has the release (no device of this exact app there).
				cs := paired["candidates"].([]any)
				if len(cs) == 0 || cs[0].(map[string]any)["physicalDeviceId"] != revealID || cs[0].(map[string]any)["match"] != "model" {
					t.Fatalf("candidates %v", cs)
				}
			}
			w := hs.do("POST", "/v1/device/claim", map[string]any{"physicalDeviceId": revealID}, bearer(key))
			if c.status == 409 {
				expect(t, w, 409, "conflict")
				if d := decode(t, w)["error"].(map[string]any)["details"].(map[string]any); d["reason"] != "same_app" {
					t.Fatalf("%v", d)
				}
				return
			}
			expect(t, w, 200, "")
			if v := decode(t, w); v["deviceId"] != id || v["app"] != c.app || v["physicalDevice"].(map[string]any)["id"] != revealID {
				t.Fatalf("%v", v)
			}
		})
	}
	// Five apps on one phone; the release Reveal keeps its deviceId (= the physical device id).
	n := 0
	for _, d := range decode(t, hs.do("GET", "/admin/api/devices", nil, adminHeaders()))["devices"].([]any) {
		if d.(map[string]any)["physicalDeviceId"] == revealID {
			n++
		}
	}
	if n != 5 {
		t.Fatalf("%d apps on the phone", n)
	}
	// An app id is a name with an optional variant: "reveal-" or "-debug" are not.
	spare, _ := hs.pair("Spare")
	for _, bad := range []string{"reveal-", "-debug", "reveal--debug", "Reveal"} {
		expect(t, hs.do("PATCH", "/v1/device", map[string]any{"app": bad}, bearer(spare)), 400, "invalid_request")
	}
}
