package store

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"testing"
)

// Store-level tests for Reveal's review of ADRs 0016/0017 (findings A–D). The API halves are in
// internal/server/culling_review_test.go.

func addDevice(t *testing.T, s *Store, id string) {
	t.Helper()
	if _, err := s.db.ExecContext(context.Background(), `INSERT INTO physical_devices (id, created_at) VALUES (?, 0)`, id); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.ExecContext(context.Background(), `INSERT INTO devices (id, name, platform, app_version, key_id, key_hash, created_at, physical_id, app)
		VALUES (?, 'Pixel 8', 'android', '0.7.1', ?, x'00', 0, ?, 'reveal')`, id, "k"+id, id); err != nil {
		t.Fatal(err)
	}
}

func photoData(rating int, flag string, label any) []byte {
	b, _ := json.Marshal(map[string]any{"fingerprint": fpShot, "rating": rating, "flag": flag, "label": label})
	return b
}

// Finding A: the bridge skips culling-aware devices, on every push and in the one-time pass over existing records.
func TestReviewA_CullingAwareDevicesAreNotBridged(t *testing.T) {
	for _, c := range []struct {
		name    string
		aware   func(s *Store, id string) error
		bridged bool
	}{
		{"released Reveal", nil, true},
		{"declared", func(s *Store, id string) error { return s.MarkCullingAware(context.Background(), id) }, false},
		{"pushed culling", func(s *Store, id string) error {
			_, err := s.PushCulling(context.Background(), id, nil)
			return err
		}, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			s := openTest(t)
			ctx := context.Background()
			addDevice(t, s, devA)
			if c.aware != nil {
				if err := c.aware(s, devA); err != nil {
					t.Fatal(err)
				}
			}
			photo := "eeeeeeee-0000-4000-8000-000000000000"
			// The echo: Hoard sets 5 stars; then Reveal touches its record (an edit) with the old 3 stars and a new
			// modifiedAt. Only a released Reveal's record is bridged (and then its newer time wins, as before).
			if _, err := s.PushCulling(ctx, devB, []CullChange{{Fingerprint: fpShot, Field: CullRating, Value: json.RawMessage("5"), BaseRev: rev(0), ModifiedAt: 100}}); err != nil {
				t.Fatal(err)
			}
			if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, ModifiedAt: 200, Data: photoData(3, "PICK", nil)}}); err != nil {
				t.Fatal(err)
			}
			rec, _ := s.Culling(ctx, ItemID(fpShot))
			_, flagged := rec.Fields[CullFlag]
			if flagged != c.bridged || (string(rec.Fields[CullRating].Value) == "5") == c.bridged {
				t.Fatalf("%+v", rec.Fields)
			}
		})
	}

	// The one-time pass skips them too.
	s := openTest(t)
	ctx := context.Background()
	addDevice(t, s, devA)
	addDevice(t, s, devB)
	if err := s.MarkCullingAware(ctx, devB); err != nil {
		t.Fatal(err)
	}
	other := "v1:" + strings.Repeat("2", 64)
	b2, _ := json.Marshal(map[string]any{"fingerprint": other, "rating": 4})
	for i, r := range []struct {
		dev  string
		data []byte
	}{{devA, photoData(2, "NONE", nil)}, {devB, b2}} {
		if _, err := s.db.ExecContext(ctx, `INSERT INTO records (kind, id, rev, modified_at, device_id, data, updated_at)
			VALUES ('photo', ?, ?, 1, ?, ?, 0)`, "ffffffff-0000-4000-8000-00000000000"+string(rune('0'+i)), i+1, r.dev, string(r.data)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.BridgeExistingRecords(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Culling(ctx, ItemID(fpShot)); err != nil {
		t.Fatal("the released Reveal's record was not bridged")
	}
	if _, err := s.Culling(ctx, ItemID(other)); !errors.Is(err, ErrNotFound) {
		t.Fatal("a culling-aware device's record was bridged")
	}
}

// Finding B: Reveal ≤ 0.7.1 reads xmp:Rating="-1" as rating 0 + REJECT. A record write that turns to reject and drops
// the stars to 0 together keeps the stars; either change alone is bridged as before.
func TestReviewB_RejectFromReleasedRevealKeepsStars(t *testing.T) {
	for _, c := range []struct {
		name       string
		rating     int
		flag       string
		wantRating string
		wantFlag   string
		ratingAt   int64 // 60: the stars were left alone; 100: this record write set them
	}{
		{"reject read from -1 (0 + REJECT)", 0, "REJECT", "4", `"reject"`, 60},
		{"reject keeping stars", 4, "REJECT", "4", `"reject"`, 60},
		{"reject with other stars", 2, "REJECT", "2", `"reject"`, 100},
		{"stars cleared alone", 0, "NONE", "0", `"none"`, 100},
		{"stars cleared, pick", 0, "PICK", "0", `"pick"`, 100},
	} {
		t.Run(c.name, func(t *testing.T) {
			s := openTest(t)
			ctx := context.Background()
			item := ItemID(fpShot)
			photo := "eeeeeeee-0000-4000-8000-000000000000"
			// Hoard rated it 4 and Reveal's record says 4 / NONE.
			seed(t, s, item, CullRating, "4", 1, 50, devB)
			seed(t, s, item, CullFlag, `"none"`, 1, 50, devB)
			if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, ModifiedAt: 60, Data: photoData(4, "NONE", nil)}}); err != nil {
				t.Fatal(err)
			}
			if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, BaseRev: 1, ModifiedAt: 100, Data: photoData(c.rating, c.flag, nil)}}); err != nil {
				t.Fatal(err)
			}
			rec, _ := s.Culling(ctx, item)
			r, f := rec.Fields[CullRating], rec.Fields[CullFlag]
			if string(r.Value) != c.wantRating || string(f.Value) != c.wantFlag || r.ModifiedAt != c.ratingAt {
				t.Fatalf("rating %+v flag %+v", r, f)
			}
		})
	}
}

// Finding C (store half): an over-limit label is skipped alone, by the bridge and by the sidecar import; a label
// cleared in a sidecar imports as "".
func TestReviewC_Labels(t *testing.T) {
	long := strings.Repeat("Lightroom free text ", 5)
	t.Run("bridge skips the label alone", func(t *testing.T) {
		s := openTest(t)
		ctx := context.Background()
		photo := "eeeeeeee-0000-4000-8000-000000000000"
		if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, ModifiedAt: 100, Data: photoData(3, "PICK", long)}}); err != nil {
			t.Fatal(err)
		}
		rec, err := s.Culling(ctx, ItemID(fpShot))
		if _, hasLabel := rec.Fields[CullLabel]; err != nil || hasLabel || string(rec.Fields[CullRating].Value) != "3" || string(rec.Fields[CullFlag].Value) != `"pick"` {
			t.Fatalf("%+v %v", rec, err)
		}
		// The label becomes valid later: it is bridged then.
		if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, BaseRev: 1, ModifiedAt: 200, Data: photoData(3, "PICK", "Red")}}); err != nil {
			t.Fatal(err)
		}
		if f := field(t, s, ItemID(fpShot), CullLabel); string(f.Value) != `"Red"` {
			t.Fatalf("%+v", f)
		}
	})

	str := func(v string) *string { return &v }
	i := func(v int) *int { return &v }
	type read struct {
		label      *string
		unreadable bool
	}
	for _, c := range []struct {
		name  string
		reads []read
		want  string // the label field after the reads ("-" = never written)
	}{
		{"cleared in Lightroom", []read{{label: str("Red")}, {label: nil}}, `""`},
		{"never had one", []read{{label: nil}, {label: nil}}, "-"},
		{"over-limit label skipped, then cleared", []read{{label: str("Red")}, {label: str(long)}, {label: nil}}, `""`},
		{"over-limit label alone", []read{{label: str("Red")}, {label: str(long)}}, `"Red"`},
		{"half-written file is not a clear", []read{{label: str("Red")}, {unreadable: true}}, `"Red"`},
		{"cleared after a half-written read", []read{{label: str("Red")}, {unreadable: true}, {label: nil}}, `""`},
		{"label set again", []read{{label: str("Red")}, {label: nil}, {label: str("Blue")}}, `"Blue"`},
	} {
		t.Run(c.name, func(t *testing.T) {
			s := openTest(t)
			ctx := context.Background()
			if _, err := s.db.ExecContext(ctx, "INSERT INTO nas_folders (id, path, name, created_at) VALUES ('f', '/mnt/nas', 'nas', 0)"); err != nil {
				t.Fatal(err)
			}
			item := ItemID(fpShot)
			for k, r := range c.reads {
				sr := SidecarRead{RelPath: "a/DSC1.xmp", Size: int64(10 + k), MTime: int64(100+k) * 1e6, ItemID: item,
					Values: SidecarValues{Rating: i(2), Label: r.label}, Unreadable: r.unreadable}
				if r.unreadable {
					sr.Values = SidecarValues{}
				}
				if _, err := s.ImportSidecars(ctx, "f", []SidecarRead{sr}); err != nil {
					t.Fatal(err)
				}
			}
			rec, err := s.Culling(ctx, item)
			got := "-"
			if f, ok := rec.Fields[CullLabel]; ok {
				got = string(f.Value)
			}
			if err != nil || got != c.want || string(rec.Fields[CullRating].Value) != "2" {
				t.Fatalf("label %s, %+v %v", got, rec.Fields, err)
			}
		})
	}
}

// Finding D (store half): a culling record carries its item's member fingerprints (main first) or, for a replaced item,
// replacedBy; it is sent again when they change.
func TestReviewD_RecordsCarryMembersAndAreResentWhenTheyChange(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	raw, jpeg := "v1:"+strings.Repeat("3", 64), "v1:"+strings.Repeat("1", 64)
	jpegItem, pairItem := ItemID(jpeg), ItemID(raw)
	write := func(id, main string, files []ItemFile, tomb []byte) {
		t.Helper()
		err := s.ItemsTx(ctx, func(tx *ItemTx) error {
			var data []byte
			if tomb == nil {
				data, _ = json.Marshal(map[string]any{"files": files})
			}
			if _, err := tx.WriteItem(id, main, "photo", 1, data, files, tomb); err != nil {
				return err
			}
			if tomb != nil {
				return tx.CarryCulling(id, pairItem)
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	type want struct {
		item       string
		fps        []string
		replacedBy string
	}
	cursor := int64(0)
	for _, step := range []struct {
		name string
		do   func()
		want []want // the records sent since the previous step, in feed order
	}{
		{"a phone-only shot is rated: no member yet", func() {
			if _, err := s.PushCulling(ctx, devA, []CullChange{{Fingerprint: jpeg, Field: CullRating, Value: json.RawMessage("3"), BaseRev: rev(0), ModifiedAt: 1}}); err != nil {
				t.Fatal(err)
			}
		}, []want{{jpegItem, nil, ""}}},
		{"the NAS indexes the JPEG: sent again with its member", func() {
			write(jpegItem, jpeg, []ItemFile{{Fingerprint: jpeg, Role: "rendering"}}, nil)
		}, []want{{jpegItem, []string{jpeg}, ""}}},
		{"the item is rewritten with the same members: not sent", func() {
			err := s.ItemsTx(ctx, func(tx *ItemTx) error {
				_, err := tx.WriteItem(jpegItem, jpeg, "photo", 2, []byte(`{"other":1}`), []ItemFile{{Fingerprint: jpeg, Role: "rendering"}}, nil)
				return err
			})
			if err != nil {
				t.Fatal(err)
			}
		}, nil},
		{"the RAW appears: the pair takes the culling (raw first), the JPEG item names it", func() {
			write(pairItem, raw, []ItemFile{{Fingerprint: jpeg, Role: "rendering"}, {Fingerprint: raw, Role: "raw"}}, nil)
			write(jpegItem, "", nil, []byte(`{"replacedBy":"`+pairItem+`"}`))
		}, []want{{jpegItem, nil, pairItem}, {pairItem, []string{raw, jpeg}, ""}}},
	} {
		step.do()
		recs, _, err := s.CullingChanges(ctx, cursor, 100)
		if err != nil {
			t.Fatal(err)
		}
		if len(recs) != len(step.want) {
			t.Fatalf("%s: %d records %+v", step.name, len(recs), recs)
		}
		for k, w := range step.want {
			r := recs[k]
			if r.ItemID != w.item || !slices.Equal(r.Fingerprints, w.fps) || r.ReplacedBy != w.replacedBy {
				t.Fatalf("%s: record %d %+v", step.name, k, r)
			}
			cursor = r.Rev
		}
	}
	b, _ := json.Marshal(CullRecord{ItemID: jpegItem, Rev: 1, ReplacedBy: pairItem})
	if !strings.Contains(string(b), `"fingerprints":[]`) || !strings.Contains(string(b), `"replacedBy":"`+pairItem+`"`) {
		t.Fatalf("%s", b)
	}
}
