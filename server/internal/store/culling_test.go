package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func openTest(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	s.Now = func() time.Time { return time.UnixMilli(1_000_000) }
	return s
}

func rev(n int64) *int64 { return &n }

const (
	devA   = "aaaaaaaa-0000-4000-8000-000000000000"
	devB   = "bbbbbbbb-0000-4000-8000-000000000000"
	fpShot = "v1:" + "1111111111111111111111111111111111111111111111111111111111111111"
)

// seed sets a field directly (value, rev, modifiedAt, writer) so each case starts from a known state.
func seed(t *testing.T, s *Store, item, field, value string, r, at int64, dev string) {
	t.Helper()
	ctx := context.Background()
	if _, err := s.db.ExecContext(ctx, `INSERT INTO culling_fields (item_id, field, value, rev, modified_at, device_id, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, 0) ON CONFLICT (item_id, field) DO UPDATE SET value = excluded.value, rev = excluded.rev,
		modified_at = excluded.modified_at, device_id = excluded.device_id`, item, field, value, r, at, dev); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.ExecContext(ctx, `INSERT INTO culling (item_id, rev) VALUES (?, ?) ON CONFLICT (item_id) DO UPDATE SET rev = max(rev, excluded.rev)`, item, r); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.ExecContext(ctx, "UPDATE counters SET value = max(value, ?) WHERE name = 'items'", r); err != nil {
		t.Fatal(err)
	}
}

func field(t *testing.T, s *Store, item, f string) CullField {
	t.Helper()
	rec, err := s.Culling(context.Background(), item)
	if err != nil {
		t.Fatal(err)
	}
	return rec.Fields[f]
}

// TestCullingConflictRule is ADR 0016's table, one case per row.
func TestCullingConflictRule(t *testing.T) {
	item := ItemID(fpShot)
	type state struct {
		value string
		rev   int64
		at    int64
		dev   string
	}
	cases := []struct {
		name     string
		cur      *state
		value    string
		baseRev  *int64
		at       int64
		dev      string
		status   string
		winner   string
		want     state // rev 0 = a new revision
		wantSame bool  // rev unchanged
		clipped  bool
	}{
		{name: "1 absent", value: "3", baseRev: rev(0), at: 100, dev: devA, status: "applied", want: state{"3", 0, 100, devA}},
		{name: "2 same value, newer", cur: &state{"3", 10, 100, devA}, value: "3", baseRev: rev(4), at: 200, dev: devB, status: "unchanged",
			want: state{"3", 10, 200, devB}, wantSame: true},
		{name: "3 same value, older", cur: &state{"3", 10, 200, devB}, value: "3", baseRev: rev(4), at: 100, dev: devA, status: "unchanged",
			want: state{"3", 10, 200, devB}, wantSame: true},
		{name: "4 baseRev matches, clock behind", cur: &state{"3", 10, 200, devA}, value: "5", baseRev: rev(10), at: 100, dev: devB,
			status: "applied", want: state{"5", 0, 100, devB}},
		{name: "5 conflict, incoming newer", cur: &state{"3", 10, 100, devA}, value: "5", baseRev: rev(7), at: 200, dev: devB,
			status: "conflict", winner: "incoming", want: state{"5", 0, 200, devB}},
		{name: "6 conflict, server newer", cur: &state{"3", 10, 200, devA}, value: "5", baseRev: rev(7), at: 100, dev: devB,
			status: "conflict", winner: "server", want: state{"3", 10, 200, devA}, wantSame: true},
		{name: "7 tie, greater device wins", cur: &state{"3", 10, 100, devA}, value: "5", baseRev: rev(7), at: 100, dev: devB,
			status: "conflict", winner: "incoming", want: state{"5", 0, 100, devB}},
		{name: "8 tie, smaller device loses", cur: &state{"3", 10, 100, devB}, value: "5", baseRev: rev(7), at: 100, dev: devA,
			status: "conflict", winner: "server", want: state{"3", 10, 100, devB}, wantSame: true},
		{name: "9 future clipped", cur: &state{"3", 10, 100, devA}, value: "5", baseRev: rev(7), at: 1_000_000 + 24*3600*1000, dev: devB,
			status: "conflict", winner: "incoming", want: state{"5", 0, 1_000_000, devB}, clipped: true},
		{name: "9b ten minutes ahead is not clipped", value: "5", baseRev: rev(0), at: 1_000_000 + MaxClockAheadMs, dev: devB,
			status: "applied", want: state{"5", 0, 1_000_000 + MaxClockAheadMs, devB}},
		{name: "11 nas sidecar newer", cur: &state{`"pick"`, 10, 100, devA}, value: `"reject"`, at: 300, dev: WriterNAS,
			status: "conflict", winner: "incoming", want: state{`"reject"`, 0, 300, WriterNAS}},
		{name: "12 nas sidecar older", cur: &state{`"pick"`, 10, 300, devA}, value: `"reject"`, at: 200, dev: WriterNAS,
			status: "conflict", winner: "server", want: state{`"pick"`, 10, 300, devA}, wantSame: true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := openTest(t)
			ctx := context.Background()
			f := CullRating
			if c.value[0] == '"' {
				f = CullFlag
			}
			if c.cur != nil {
				seed(t, s, item, f, c.cur.value, c.cur.rev, c.cur.at, c.cur.dev)
			}
			var res CullResult
			err := s.tx(ctx, func(tx *sql.Tx) error {
				var err error
				res, err = applyCull(ctx, tx, item, f, json.RawMessage(c.value), c.baseRev, c.at, c.dev, s.nowMs())
				return err
			})
			if err != nil {
				t.Fatal(err)
			}
			if res.Status != c.status || res.Winner != c.winner || res.Clipped != c.clipped {
				t.Fatalf("result %+v", res)
			}
			got := field(t, s, item, f)
			if string(got.Value) != c.want.value || got.ModifiedAt != c.want.at || got.DeviceID != c.want.dev {
				t.Fatalf("field %+v, want %+v", got, c.want)
			}
			if c.wantSame && got.Rev != c.cur.rev {
				t.Fatalf("rev moved: %d", got.Rev)
			}
			if !c.wantSame && (c.cur != nil && got.Rev <= c.cur.rev) {
				t.Fatalf("no new revision: %d", got.Rev)
			}
			if res.Rev != got.Rev {
				t.Fatalf("result rev %d, field rev %d", res.Rev, got.Rev)
			}
		})
	}
}

// Case 10: fields are independent; a rating and a pick made at the same time both survive.
func TestCullingFieldsAreIndependent(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	item := ItemID(fpShot)
	seed(t, s, item, CullRating, "3", 10, 100, devA)
	seed(t, s, item, CullFlag, `"none"`, 10, 100, devA)
	res, err := s.PushCulling(ctx, devB, []CullChange{{ItemID: item, Field: CullFlag, Value: json.RawMessage(`"pick"`), BaseRev: rev(3), ModifiedAt: 120}})
	if err != nil || res[0].Status != "conflict" || res[0].Winner != "incoming" {
		t.Fatalf("%+v %v", res, err)
	}
	res, err = s.PushCulling(ctx, devA, []CullChange{{ItemID: item, Field: CullRating, Value: json.RawMessage("4"), BaseRev: rev(10), ModifiedAt: 110}})
	if err != nil || res[0].Status != "applied" {
		t.Fatalf("%+v %v", res, err)
	}
	rec, _ := s.Culling(ctx, item)
	if string(rec.Fields[CullRating].Value) != "4" || rec.Fields[CullRating].DeviceID != devA ||
		string(rec.Fields[CullFlag].Value) != `"pick"` || rec.Fields[CullFlag].DeviceID != devB {
		t.Fatalf("%+v", rec)
	}
}

// Case 13: a push sent twice changes nothing the second time.
func TestCullingRetriedPush(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	changes := []CullChange{
		{Fingerprint: fpShot, Field: CullRating, Value: json.RawMessage("2"), BaseRev: rev(0), ModifiedAt: 500},
		{Fingerprint: fpShot, Field: CullLabel, Value: json.RawMessage(`"Red"`), BaseRev: rev(0), ModifiedAt: 500},
	}
	first, err := s.PushCulling(ctx, devA, changes)
	if err != nil {
		t.Fatal(err)
	}
	head, _ := s.Counter(ctx, "items")
	second, err := s.PushCulling(ctx, devA, changes)
	if err != nil {
		t.Fatal(err)
	}
	for i := range second {
		if second[i].Status != "unchanged" || second[i].Rev != first[i].Rev {
			t.Fatalf("%+v vs %+v", second[i], first[i])
		}
	}
	if h, _ := s.Counter(ctx, "items"); h != head {
		t.Fatalf("a retry used revisions: %d → %d", head, h)
	}
	if first[0].ItemID != ItemID(fpShot) {
		t.Fatalf("a fingerprint nobody indexes is kept under the derived id: %s", first[0].ItemID)
	}
}

func TestCullingUnknownItemAndReplacedBy(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	unknown := "cccccccc-0000-4000-8000-000000000000"
	_, err := s.PushCulling(ctx, devA, []CullChange{
		{Fingerprint: fpShot, Field: CullRating, Value: json.RawMessage("1"), BaseRev: rev(0), ModifiedAt: 1},
		{ItemID: unknown, Field: CullRating, Value: json.RawMessage("1"), BaseRev: rev(0), ModifiedAt: 1},
	})
	var ce *CullError
	if !errors.As(err, &ce) || ce.Index != 1 {
		t.Fatalf("%v", err)
	}
	if _, err := s.Culling(ctx, ItemID(fpShot)); !errors.Is(err, ErrNotFound) {
		t.Fatal("a rejected push applied its first change")
	}
	// A tombstone with replacedBy forwards to the replacement.
	newID := "dddddddd-0000-4000-8000-000000000000"
	if _, err := s.db.ExecContext(ctx, `INSERT INTO items (id, main_fp, rev, deleted, kind, data, created_at, updated_at)
		VALUES (?, 'v1:x', 1, 1, 'photo', ?, 0, 0), (?, 'v1:y', 2, 0, 'pair', '{}', 0, 0)`, unknown, `{"replacedBy":"`+newID+`"}`, newID); err != nil {
		t.Fatal(err)
	}
	res, err := s.PushCulling(ctx, devA, []CullChange{{ItemID: unknown, Field: CullFlag, Value: json.RawMessage(`"pick"`), BaseRev: rev(0), ModifiedAt: 1}})
	if err != nil || res[0].ItemID != newID {
		t.Fatalf("%+v %v", res, err)
	}
}

func TestCullValueValidation(t *testing.T) {
	for _, c := range []struct {
		field, in, out string
	}{
		{CullRating, "0", "0"}, {CullRating, "5", "5"}, {CullRating, "5.0", "5"}, {CullRating, "6", ""}, {CullRating, "-1", ""},
		{CullRating, "2.5", ""}, {CullRating, `"3"`, ""},
		{CullFlag, `"pick"`, `"pick"`}, {CullFlag, `"none"`, `"none"`}, {CullFlag, `"PICK"`, ""}, {CullFlag, "1", ""},
		{CullLabel, `""`, `""`}, {CullLabel, `" Red "`, `"Red"`}, {CullLabel, `"a\nb"`, ""},
		{CullLabel, `"` + string(make([]byte, 0)) + `0123456789012345678901234567890123456789012345678901234567890123"`, `"0123456789012345678901234567890123456789012345678901234567890123"`},
		{CullLabel, `"01234567890123456789012345678901234567890123456789012345678901234"`, ""},
		{"stars", "1", ""},
	} {
		got, ok := CullValue(c.field, json.RawMessage(c.in))
		if (c.out == "") == ok || string(got) != c.out {
			t.Errorf("%s %s: %q %v", c.field, c.in, got, ok)
		}
	}
}

func TestSidecarImportRule(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	if _, err := s.db.ExecContext(ctx, "INSERT INTO nas_folders (id, path, name, created_at) VALUES ('f', '/mnt/nas', 'nas', 0)"); err != nil {
		t.Fatal(err)
	}
	item := ItemID(fpShot)
	i := func(v int) *int { return &v }
	str := func(v string) *string { return &v }
	ms := func(v int64) int64 { return v * 1e6 }

	// First read: every field the sidecar holds is a candidate.
	n, err := s.ImportSidecars(ctx, "f", []SidecarRead{{RelPath: "a/DSC1.xmp", Size: 10, MTime: ms(100), ItemID: item,
		Values: SidecarValues{Rating: i(3), Flag: str("pick")}}})
	if err != nil || n != 2 {
		t.Fatalf("%d %v", n, err)
	}
	if f := field(t, s, item, CullRating); string(f.Value) != "3" || f.DeviceID != WriterNAS || f.ModifiedAt != 100 {
		t.Fatalf("%+v", f)
	}
	// A device changes the rating later.
	if _, err := s.PushCulling(ctx, devA, []CullChange{{ItemID: item, Field: CullRating, Value: json.RawMessage("5"),
		BaseRev: rev(field(t, s, item, CullRating).Rev), ModifiedAt: 200}}); err != nil {
		t.Fatal(err)
	}
	// Lightroom changes only the label (mtime 300): the old rating in the file is not re-asserted.
	n, err = s.ImportSidecars(ctx, "f", []SidecarRead{{RelPath: "a/DSC1.xmp", Size: 12, MTime: ms(300), ItemID: item,
		Values: SidecarValues{Rating: i(3), Flag: str("pick"), Label: str("Red")}}})
	if err != nil || n != 1 {
		t.Fatalf("%d %v", n, err)
	}
	if f := field(t, s, item, CullRating); string(f.Value) != "5" {
		t.Fatalf("rating re-asserted: %+v", f)
	}
	if f := field(t, s, item, CullLabel); string(f.Value) != `"Red"` || f.ModifiedAt != 300 {
		t.Fatalf("%+v", f)
	}
	// A sidecar written by an app with the value the server already has: no revision.
	head, _ := s.Counter(ctx, "items")
	n, err = s.ImportSidecars(ctx, "f", []SidecarRead{{RelPath: "a/DSC1.xmp", Size: 12, MTime: ms(400), ItemID: item,
		Values: SidecarValues{Rating: i(5), Flag: str("pick"), Label: str("Red")}}})
	if h, _ := s.Counter(ctx, "items"); err != nil || n != 0 || h != head {
		t.Fatalf("own write read back: %d %v %d→%d", n, err, head, h)
	}
	// An old sidecar (stale mtime) that changed its value loses to the newer device write.
	if _, err := s.PushCulling(ctx, devA, []CullChange{{ItemID: item, Field: CullFlag, Value: json.RawMessage(`"none"`),
		BaseRev: rev(field(t, s, item, CullFlag).Rev), ModifiedAt: 900}}); err != nil {
		t.Fatal(err)
	}
	n, err = s.ImportSidecars(ctx, "f", []SidecarRead{{RelPath: "a/DSC1.xmp", Size: 13, MTime: ms(500), ItemID: item,
		Values: SidecarValues{Rating: i(5), Flag: str("reject"), Label: str("Red")}}})
	if err != nil || n != 0 || string(field(t, s, item, CullFlag).Value) != `"none"` {
		t.Fatalf("%d %v %+v", n, err, field(t, s, item, CullFlag))
	}
	st, _ := s.SidecarStates(ctx, "f")
	if st["a/DSC1.xmp"] != (SidecarState{Size: 13, MTime: ms(500)}) {
		t.Fatalf("%+v", st)
	}
}

func TestBridgeFromPhotoRecords(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	photo := "eeeeeeee-0000-4000-8000-000000000000"
	item := ItemID(fpShot)
	data := func(rating int, flag string, label any) []byte {
		b, _ := json.Marshal(map[string]any{"fingerprint": fpShot, "rating": rating, "flag": flag, "label": label})
		return b
	}
	// A new record with defaults bridges nothing (a device pairing late must not clear ratings).
	if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, ModifiedAt: 100, Data: data(0, "NONE", nil)}}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Culling(ctx, item); !errors.Is(err, ErrNotFound) {
		t.Fatalf("defaults bridged: %v", err)
	}
	// Reveal rates it: only the rating is bridged, with the record's time and device.
	if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, BaseRev: 1, ModifiedAt: 200, Data: data(4, "NONE", nil)}}); err != nil {
		t.Fatal(err)
	}
	rec, err := s.Culling(ctx, item)
	if err != nil || len(rec.Fields) != 1 || string(rec.Fields[CullRating].Value) != "4" || rec.Fields[CullRating].ModifiedAt != 200 ||
		rec.Fields[CullRating].DeviceID != devA {
		t.Fatalf("%+v %v", rec, err)
	}
	// Hoard picks through the culling API; a later Reveal record that only changes the label keeps the pick.
	if _, err := s.PushCulling(ctx, devB, []CullChange{{Fingerprint: fpShot, Field: CullFlag, Value: json.RawMessage(`"pick"`), BaseRev: rev(0), ModifiedAt: 250}}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, BaseRev: 2, ModifiedAt: 300, Data: data(4, "NONE", "Red")}}); err != nil {
		t.Fatal(err)
	}
	rec, _ = s.Culling(ctx, item)
	if string(rec.Fields[CullFlag].Value) != `"pick"` || string(rec.Fields[CullLabel].Value) != `"Red"` {
		t.Fatalf("%+v", rec)
	}
	// Reveal rejects it: the flag goes to reject, the stars stay.
	if _, _, err := s.Push(ctx, devA, []Change{{Kind: KindPhoto, ID: photo, BaseRev: 3, ModifiedAt: 400, Data: data(4, "REJECT", "Red")}}); err != nil {
		t.Fatal(err)
	}
	rec, _ = s.Culling(ctx, item)
	if string(rec.Fields[CullFlag].Value) != `"reject"` || string(rec.Fields[CullRating].Value) != "4" {
		t.Fatalf("%+v", rec)
	}
	// The one-time pass over existing records is idempotent and runs once.
	if done, err := s.BridgeExistingRecords(ctx); err != nil || !done {
		t.Fatalf("%v %v", done, err)
	}
	if done, err := s.BridgeExistingRecords(ctx); err != nil || done {
		t.Fatalf("ran twice: %v %v", done, err)
	}
}

func TestBridgeExistingRecordsAtUpgrade(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	b, _ := json.Marshal(map[string]any{"fingerprint": fpShot, "rating": 2, "flag": "PICK", "label": nil})
	if _, err := s.db.ExecContext(ctx, `INSERT INTO records (kind, id, rev, modified_at, device_id, data, updated_at)
		VALUES ('photo', 'ffffffff-0000-4000-8000-000000000000', 1, 777, ?, ?, 0)`, devA, string(b)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.BridgeExistingRecords(ctx); err != nil {
		t.Fatal(err)
	}
	rec, err := s.Culling(ctx, ItemID(fpShot))
	if err != nil || string(rec.Fields[CullRating].Value) != "2" || string(rec.Fields[CullFlag].Value) != `"pick"` ||
		rec.Fields[CullFlag].ModifiedAt != 777 || rec.Fields[CullFlag].DeviceID != devA {
		t.Fatalf("%+v %v", rec, err)
	}
	if _, ok := rec.Fields[CullLabel]; ok {
		t.Fatal("a default label was bridged")
	}
}

func TestCullingFeedPages(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	var changes []CullChange
	for i := range 5 {
		fp := "v1:" + string(rune('a'+i)) + fpShot[4:]
		changes = append(changes, CullChange{Fingerprint: fp, Field: CullRating, Value: json.RawMessage("1"), BaseRev: rev(0), ModifiedAt: 1})
	}
	// The first item is written twice: it appears once, at its latest state.
	changes = append(changes, CullChange{Fingerprint: changes[0].Fingerprint, Field: CullFlag, Value: json.RawMessage(`"reject"`), BaseRev: rev(0), ModifiedAt: 2})
	if _, err := s.PushCulling(ctx, devA, changes); err != nil {
		t.Fatal(err)
	}
	page, more, err := s.CullingChanges(ctx, 0, 3)
	if err != nil || !more || len(page) != 3 || page[0].Rev != 2 {
		t.Fatalf("%+v %v %v", page, more, err)
	}
	page, more, err = s.CullingChanges(ctx, page[2].Rev, 3)
	if err != nil || more || len(page) != 2 || page[1].Rev != 6 || len(page[1].Fields) != 2 {
		t.Fatalf("%+v %v %v", page, more, err)
	}
	b, _ := json.Marshal(page[1])
	var back map[string]any
	if json.Unmarshal(b, &back) != nil || back["flag"].(map[string]any)["value"] != "reject" || back["label"] != nil {
		t.Fatalf("%s", b)
	}
}
