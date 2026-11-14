package store

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"
	"unicode/utf8"
)

// Culling (ADR 0016): per item, three independent fields (rating, flag, label), each with its own value, revision,
// modifiedAt and writer. Revisions come from the items counter, so the items feed carries culling records too.

// Culling fields.
const (
	CullRating = "rating"
	CullFlag   = "flag"
	CullLabel  = "label"
)

// CullFields lists the fields in their wire order.
var CullFields = []string{CullRating, CullFlag, CullLabel}

// WriterNAS is the writer of a change imported from a NAS sidecar.
const WriterNAS = "nas"

// MaxClockAheadMs is how far ahead of the server's clock a modifiedAt may be before it is clipped (ADR 0016, step 1).
const MaxClockAheadMs = 10 * 60 * 1000

// MaxLabel is the longest colour label, in characters.
const MaxLabel = 64

// ItemID is an item's id: a UUID derived from its main file's fingerprint (SHA-256 of "hoard-item\0" + fingerprint,
// version 8, RFC 9562 variant; ADR 0014 "Item id").
func ItemID(mainFingerprint string) string {
	h := sha256.Sum256([]byte("hoard-item\x00" + mainFingerprint))
	b := h[:16]
	b[6] = b[6]&0x0f | 0x80
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// Codes of a change that cannot be applied (ADR 0016 "Wire format": status "invalid").
const (
	CullInvalidTarget     = "invalid_target"      // no itemId or fingerprint, both, or a malformed one
	CullInvalidField      = "invalid_field"       // not rating, flag or label
	CullInvalidValue      = "invalid_value"       // not a value of the field
	CullLabelTooLong      = "label_too_long"      // a label over MaxLabel characters (e.g. Lightroom free text)
	CullInvalidBaseRev    = "invalid_base_rev"    // missing or negative
	CullInvalidModifiedAt = "invalid_modified_at" // missing, ≤ 0 or above 2^53
	CullUnknownItem       = "unknown_item"        // an itemId the server does not know
)

// CullValue validates a field's value and returns its canonical JSON (4, "pick", "Red").
func CullValue(field string, raw json.RawMessage) (json.RawMessage, bool) {
	v, code := CullValueCode(field, raw)
	return v, code == ""
}

// CullValueCode is CullValue with the reason a value is refused ("" when valid).
func CullValueCode(field string, raw json.RawMessage) (json.RawMessage, string) {
	switch field {
	case CullRating:
		var n float64
		if json.Unmarshal(raw, &n) != nil || n != float64(int(n)) || n < 0 || n > 5 {
			return nil, CullInvalidValue
		}
		return json.RawMessage(fmt.Sprintf("%d", int(n))), ""
	case CullFlag:
		var v string
		if json.Unmarshal(raw, &v) != nil || (v != "pick" && v != "reject" && v != "none") {
			return nil, CullInvalidValue
		}
		return json.RawMessage(`"` + v + `"`), ""
	case CullLabel:
		var v string
		if json.Unmarshal(raw, &v) != nil {
			return nil, CullInvalidValue
		}
		v = strings.TrimSpace(v)
		if !utf8.ValidString(v) {
			return nil, CullInvalidValue
		}
		for _, r := range v {
			if r < 0x20 || r == 0x7f {
				return nil, CullInvalidValue
			}
		}
		if utf8.RuneCountInString(v) > MaxLabel {
			return nil, CullLabelTooLong
		}
		b, _ := json.Marshal(v)
		return b, ""
	}
	return nil, CullInvalidField
}

// CullChange is one write of one field. BaseRev nil = a writer without a revision (sidecar import, bridge): last
// writer wins only.
type CullChange struct {
	ItemID      string // or Fingerprint
	Fingerprint string
	Field       string
	Value       json.RawMessage // canonical (CullValue)
	BaseRev     *int64
	ModifiedAt  int64
}

// CullResult is the outcome of one change. An invalid change has Code and Message, no Rev, and echoes the itemId or
// fingerprint and field it was sent with.
type CullResult struct {
	Index       int    `json:"index"`
	ItemID      string `json:"itemId,omitempty"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Field       string `json:"field"`
	Status      string `json:"status"` // applied | unchanged | conflict | invalid
	Winner      string `json:"winner,omitempty"`
	Rev         int64  `json:"rev,omitempty"`
	Clipped     bool   `json:"clipped,omitempty"`
	Code        string `json:"code,omitempty"`
	Message     string `json:"message,omitempty"`
}

// InvalidCull is the result of a change that cannot be applied.
func InvalidCull(index int, itemID, fingerprint, field, code, message string) CullResult {
	return CullResult{Index: index, ItemID: itemID, Fingerprint: fingerprint, Field: field, Status: "invalid", Code: code, Message: message}
}

// CullField is a field's stored state.
type CullField struct {
	Value      json.RawMessage `json:"value"`
	Rev        int64           `json:"rev"`
	ModifiedAt int64           `json:"modifiedAt"`
	DeviceID   string          `json:"deviceId"`
}

// CullRecord is an item's culling at its latest state, with what a client needs to map it to its own photos (ADR
// 0016 "Mapping a record"): the item's member fingerprints (main first; none when the server has no live item with
// this id) and, for an item that was replaced, the live item that took it over.
type CullRecord struct {
	ItemID       string
	Rev          int64
	Fields       map[string]CullField
	Fingerprints []string
	ReplacedBy   string
}

// MarshalJSON writes {"itemId", "rev", "fingerprints", "replacedBy"?, "rating"?, "flag"?, "label"?}.
func (r CullRecord) MarshalJSON() ([]byte, error) {
	var buf bytes.Buffer
	fps := r.Fingerprints
	if fps == nil {
		fps = []string{}
	}
	b, err := json.Marshal(fps)
	if err != nil {
		return nil, err
	}
	fmt.Fprintf(&buf, `{"itemId":%q,"rev":%d,"fingerprints":%s`, r.ItemID, r.Rev, b)
	if r.ReplacedBy != "" {
		fmt.Fprintf(&buf, `,"replacedBy":%q`, r.ReplacedBy)
	}
	for _, f := range CullFields {
		v, ok := r.Fields[f]
		if !ok {
			continue
		}
		b, err := json.Marshal(v)
		if err != nil {
			return nil, err
		}
		fmt.Fprintf(&buf, `,%q:`, f)
		buf.Write(b)
	}
	buf.WriteByte('}')
	return buf.Bytes(), nil
}

// PushCulling applies changes from deviceID in order, in one transaction (ADR 0016 "Conflict rule"), and marks the
// device culling-aware (its photo records are no longer bridged). A change naming an unknown itemId is an "invalid"
// result; the others apply. Results are in the changes' order, their Index the change's position in the slice.
func (s *Store) PushCulling(ctx context.Context, deviceID string, changes []CullChange) ([]CullResult, error) {
	var results []CullResult
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		results = make([]CullResult, 0, len(changes))
		if _, err := tx.ExecContext(ctx, "UPDATE devices SET culling_since = coalesce(culling_since, ?) WHERE id = ?", now, deviceID); err != nil {
			return err
		}
		for i, c := range changes {
			id, err := resolveCullItem(ctx, tx, c.ItemID, c.Fingerprint)
			if err != nil {
				return err
			}
			if id == "" {
				results = append(results, InvalidCull(i, c.ItemID, c.Fingerprint, c.Field, CullUnknownItem,
					"Unknown itemId: send the fingerprint of a shot the server does not know."))
				continue
			}
			r, err := applyCull(ctx, tx, id, c.Field, c.Value, c.BaseRev, c.ModifiedAt, deviceID, now)
			if err != nil {
				return err
			}
			r.Index = i
			results = append(results, r)
		}
		return nil
	})
	return results, err
}

// resolveCullItem finds the item a change is about: by fingerprint, the live item holding that file (main first),
// else the id derived from it; by id, a known item (a tombstone's replacedBy followed) or an existing record. ""
// means unknown.
func resolveCullItem(ctx context.Context, tx *sql.Tx, itemID, fp string) (string, error) {
	if fp != "" {
		var id string
		err := tx.QueryRowContext(ctx, `SELECT f.item_id FROM item_files f JOIN items i ON i.id = f.item_id
			WHERE f.fingerprint = ? AND i.deleted = 0 ORDER BY (i.main_fp = f.fingerprint) DESC, f.item_id LIMIT 1`, fp).Scan(&id)
		if err == nil {
			return id, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return "", err
		}
		itemID = ItemID(fp)
		return followReplaced(ctx, tx, itemID, true)
	}
	return followReplaced(ctx, tx, itemID, false)
}

type rowQueryer interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func followReplaced(ctx context.Context, tx rowQueryer, id string, derived bool) (string, error) {
	for range 8 {
		var deleted bool
		var data string
		err := tx.QueryRowContext(ctx, "SELECT deleted, data FROM items WHERE id = ?", id).Scan(&deleted, &data)
		if errors.Is(err, sql.ErrNoRows) {
			if derived {
				return id, nil
			}
			var n int
			if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM culling WHERE item_id = ?", id).Scan(&n); err != nil {
				return "", err
			}
			if n > 0 {
				return id, nil
			}
			return "", nil
		}
		if err != nil {
			return "", err
		}
		var tomb struct {
			ReplacedBy string `json:"replacedBy"`
		}
		if !deleted || json.Unmarshal([]byte(data), &tomb) != nil || tomb.ReplacedBy == "" {
			return id, nil
		}
		id = tomb.ReplacedBy
	}
	return id, nil
}

// later reports whether (t1, d1) is after (t2, d2): modifiedAt, then device id (ADR 0013's order).
func later(t1 int64, d1 string, t2 int64, d2 string) bool {
	return t1 > t2 || (t1 == t2 && d1 > d2)
}

// applyCull applies one write to one field (ADR 0016 "Conflict rule", steps 1–5).
func applyCull(ctx context.Context, tx *sql.Tx, itemID, field string, value json.RawMessage, baseRev *int64, t int64, writer string, now int64) (CullResult, error) {
	res := CullResult{ItemID: itemID, Field: field}
	if t > now+MaxClockAheadMs {
		t, res.Clipped = now, true
	}
	var cur struct {
		value    string
		rev, at  int64
		deviceID string
	}
	err := tx.QueryRowContext(ctx, "SELECT value, rev, modified_at, device_id FROM culling_fields WHERE item_id = ? AND field = ?", itemID, field).
		Scan(&cur.value, &cur.rev, &cur.at, &cur.deviceID)
	exists := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return res, err
	}
	write := func() error {
		rev, err := next(ctx, tx, "items")
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO culling_fields (item_id, field, value, rev, modified_at, device_id, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (item_id, field) DO UPDATE SET value = excluded.value, rev = excluded.rev,
			modified_at = excluded.modified_at, device_id = excluded.device_id, updated_at = excluded.updated_at`,
			itemID, field, string(value), rev, t, writer, now); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "INSERT INTO culling (item_id, rev) VALUES (?, ?) ON CONFLICT (item_id) DO UPDATE SET rev = excluded.rev", itemID, rev)
		res.Rev = rev
		return err
	}
	switch {
	case !exists:
		res.Status = "applied"
		return res, write()
	case cur.value == string(value):
		res.Status, res.Rev = "unchanged", cur.rev
		if later(t, writer, cur.at, cur.deviceID) {
			_, err := tx.ExecContext(ctx, "UPDATE culling_fields SET modified_at = ?, device_id = ?, updated_at = ? WHERE item_id = ? AND field = ?",
				t, writer, now, itemID, field)
			return res, err
		}
		return res, nil
	case baseRev != nil && *baseRev == cur.rev:
		res.Status = "applied"
		return res, write()
	}
	res.Status = "conflict"
	if later(t, writer, cur.at, cur.deviceID) {
		res.Winner = "incoming"
		return res, write()
	}
	res.Winner, res.Rev = "server", cur.rev
	return res, nil
}

func scanCullFields(rows *sql.Rows, into map[string]*CullRecord) error {
	defer rows.Close()
	for rows.Next() {
		var id, field, value string
		var f CullField
		if err := rows.Scan(&id, &field, &value, &f.Rev, &f.ModifiedAt, &f.DeviceID); err != nil {
			return err
		}
		f.Value = json.RawMessage(value)
		if r := into[id]; r != nil {
			r.Fields[field] = f
		}
	}
	return rows.Err()
}

// CullingChanges returns the culling records with rev > since, oldest first, and whether more follow.
func (s *Store) CullingChanges(ctx context.Context, since int64, limit int) ([]CullRecord, bool, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT item_id, rev FROM culling WHERE rev > ? ORDER BY rev LIMIT ?", since, limit+1)
	if err != nil {
		return nil, false, err
	}
	var out []CullRecord
	for rows.Next() {
		r := CullRecord{Fields: map[string]CullField{}}
		if err := rows.Scan(&r.ItemID, &r.Rev); err != nil {
			rows.Close()
			return nil, false, err
		}
		out = append(out, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	if err := s.fillCulling(ctx, out); err != nil {
		return nil, false, err
	}
	return out, more, nil
}

// fillCulling reads the fields of each record, the member fingerprints of its item and, for a replaced item, the live
// item that took it over.
func (s *Store) fillCulling(ctx context.Context, recs []CullRecord) error {
	for start := 0; start < len(recs); start += 500 {
		part := recs[start:min(start+500, len(recs))]
		byID := make(map[string]*CullRecord, len(part))
		args := make([]any, len(part))
		for i := range part {
			byID[part[i].ItemID] = &part[i]
			args[i] = part[i].ItemID
		}
		//nolint:gosec // placeholders only; the ids are arguments
		rows, err := s.db.QueryContext(ctx, "SELECT item_id, field, value, rev, modified_at, device_id FROM culling_fields WHERE item_id IN ("+
			placeholders(len(part))+")", args...)
		if err != nil {
			return err
		}
		if err := scanCullFields(rows, byID); err != nil {
			return err
		}
		//nolint:gosec // placeholders only; the ids are arguments
		rows, err = s.db.QueryContext(ctx, `SELECT f.item_id, f.fingerprint FROM item_files f JOIN items i ON i.id = f.item_id
			WHERE i.deleted = 0 AND f.item_id IN (`+placeholders(len(part))+`) ORDER BY f.item_id, (f.fingerprint = i.main_fp) DESC, f.fingerprint`, args...)
		if err != nil {
			return err
		}
		if err := scanRecordFingerprints(rows, byID); err != nil {
			return err
		}
		//nolint:gosec // placeholders only; the ids are arguments
		rows, err = s.db.QueryContext(ctx, "SELECT id FROM items WHERE deleted = 1 AND id IN ("+placeholders(len(part))+")", args...)
		if err != nil {
			return err
		}
		var tombs []string
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return err
			}
			tombs = append(tombs, id)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, id := range tombs {
			to, err := followReplaced(ctx, s.db, id, false)
			if err != nil {
				return err
			}
			if to != id {
				byID[id].ReplacedBy = to
			}
		}
	}
	return nil
}

func scanRecordFingerprints(rows *sql.Rows, into map[string]*CullRecord) error {
	defer rows.Close()
	for rows.Next() {
		var id, fp string
		if err := rows.Scan(&id, &fp); err != nil {
			return err
		}
		if r := into[id]; r != nil {
			r.Fingerprints = append(r.Fingerprints, fp)
		}
	}
	return rows.Err()
}

// touchCulling gives an item's culling record a new revision without changing a field, so it is sent again in the
// feed: its item's member files changed (or the item was replaced), and clients map records by them.
func touchCulling(ctx context.Context, tx *sql.Tx, itemID string) error {
	var n int
	if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM culling WHERE item_id = ?", itemID).Scan(&n); err != nil || n == 0 {
		return err
	}
	rev, err := next(ctx, tx, "items")
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "UPDATE culling SET rev = ? WHERE item_id = ?", rev, itemID)
	return err
}

// Culling returns one item's culling record (ErrNotFound when no field was ever written).
func (s *Store) Culling(ctx context.Context, itemID string) (CullRecord, error) {
	r := CullRecord{ItemID: itemID, Fields: map[string]CullField{}}
	err := s.db.QueryRowContext(ctx, "SELECT rev FROM culling WHERE item_id = ?", itemID).Scan(&r.Rev)
	if errors.Is(err, sql.ErrNoRows) {
		return r, ErrNotFound
	}
	if err != nil {
		return r, err
	}
	recs := []CullRecord{r}
	err = s.fillCulling(ctx, recs)
	return recs[0], err
}

// --- NAS sidecars (ADR 0016 "The NAS sidecars") ---------------------------------------------------------------------

// SidecarValues are the culling values a sidecar holds (nil: absent from the file).
type SidecarValues struct {
	Rating *int
	Flag   *string
	Label  *string
}

// SidecarState is what the server last read from a sidecar file.
type SidecarState struct {
	Size  int64
	MTime int64 // ns
}

// SidecarStates returns, per relative path, the size and mtime of the sidecars of a folder the server read.
func (s *Store) SidecarStates(ctx context.Context, folderID string) (map[string]SidecarState, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT rel_path, size, mtime FROM culling_sidecars WHERE folder_id = ?", folderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]SidecarState{}
	for rows.Next() {
		var p string
		var st SidecarState
		if err := rows.Scan(&p, &st.Size, &st.MTime); err != nil {
			return nil, err
		}
		out[p] = st
	}
	return out, rows.Err()
}

// SidecarRead is one sidecar file read by a walk.
type SidecarRead struct {
	RelPath string
	Size    int64
	MTime   int64 // ns
	ItemID  string
	Values  SidecarValues
	// Unreadable: the file is not an XMP packet (now). Its size and mtime are recorded (not read again until it
	// changes) and what it held the last time is kept, so a file caught half-written does not count as "cleared".
	Unreadable bool
}

// ImportSidecars records what each sidecar holds and imports the fields that changed since the server last read it
// as writes by "nas" dated by the file's mtime. A label the sidecar held last time and no longer holds (cleared in
// Lightroom) imports as "". It returns the number of fields written.
func (s *Store) ImportSidecars(ctx context.Context, folderID string, reads []SidecarRead) (int, error) {
	n := 0
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		n = 0
		for _, r := range reads {
			var prev struct {
				rating      sql.NullInt64
				flag, label sql.NullString
			}
			err := tx.QueryRowContext(ctx, "SELECT rating, flag, label FROM culling_sidecars WHERE folder_id = ? AND rel_path = ?", folderID, r.RelPath).
				Scan(&prev.rating, &prev.flag, &prev.label)
			seen := err == nil
			if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			if r.Unreadable {
				if _, err := tx.ExecContext(ctx, `INSERT INTO culling_sidecars (folder_id, rel_path, size, mtime, item_id) VALUES (?, ?, ?, ?, ?)
					ON CONFLICT (folder_id, rel_path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime, item_id = excluded.item_id`,
					folderID, r.RelPath, r.Size, r.MTime, r.ItemID); err != nil {
					return err
				}
				continue
			}
			type cand struct {
				field string
				value json.RawMessage
			}
			var cands []cand
			v := r.Values
			if v.Rating != nil && (!seen || !prev.rating.Valid || prev.rating.Int64 != int64(*v.Rating)) {
				cands = append(cands, cand{CullRating, json.RawMessage(fmt.Sprintf("%d", *v.Rating))})
			}
			if v.Flag != nil && (!seen || !prev.flag.Valid || prev.flag.String != *v.Flag) {
				b, _ := json.Marshal(*v.Flag)
				cands = append(cands, cand{CullFlag, b})
			}
			if v.Label != nil && (!seen || !prev.label.Valid || prev.label.String != *v.Label) {
				if b, ok := CullValue(CullLabel, mustJSON(*v.Label)); ok {
					cands = append(cands, cand{CullLabel, b})
				}
			}
			if v.Label == nil && seen && prev.label.Valid && prev.label.String != "" {
				cands = append(cands, cand{CullLabel, json.RawMessage(`""`)}) // present last time, gone now: cleared
			}
			for _, c := range cands {
				res, err := applyCull(ctx, tx, r.ItemID, c.field, c.value, nil, r.MTime/1e6, WriterNAS, now)
				if err != nil {
					return err
				}
				if res.Status != "unchanged" && res.Winner != "server" {
					n++
				}
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO culling_sidecars (folder_id, rel_path, size, mtime, item_id, rating, flag, label)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (folder_id, rel_path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime,
				item_id = excluded.item_id, rating = excluded.rating, flag = excluded.flag, label = excluded.label`,
				folderID, r.RelPath, r.Size, r.MTime, r.ItemID, nullInt(v.Rating), nullStr(v.Flag), nullStr(v.Label)); err != nil {
				return err
			}
		}
		return nil
	})
	return n, err
}

func contains(list []string, v string) bool { return slices.Contains(list, v) }

func mustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func nullInt(v *int) any {
	if v == nil {
		return nil
	}
	return *v
}

func nullStr(v *string) any {
	if v == nil {
		return nil
	}
	return *v
}

// --- Reveal's photo records (ADR 0016 "Reveal's records") ------------------------------------------------------------

type photoCulling struct {
	Fingerprint string          `json:"fingerprint"`
	Rating      json.RawMessage `json:"rating"`
	Flag        json.RawMessage `json:"flag"`
	Label       json.RawMessage `json:"label"`
}

// values returns the record's culling values with Reveal's defaults (0, NONE, null). A field whose value cannot be
// read (a label over MaxLabel characters, a rating that is not a number) is left out, alone: the other fields still
// bridge (ADR 0016 "Reveal's records").
func (p photoCulling) values() map[string]json.RawMessage {
	out := map[string]json.RawMessage{CullRating: json.RawMessage("0"), CullFlag: json.RawMessage(`"none"`), CullLabel: json.RawMessage(`""`)}
	if len(p.Rating) > 0 && string(p.Rating) != "null" {
		var n float64
		if json.Unmarshal(p.Rating, &n) != nil {
			delete(out, CullRating)
		} else {
			n = max(0, min(5, float64(int(n))))
			out[CullRating] = json.RawMessage(fmt.Sprintf("%d", int(n)))
		}
	}
	if len(p.Flag) > 0 && string(p.Flag) != "null" {
		var f string
		if json.Unmarshal(p.Flag, &f) != nil {
			delete(out, CullFlag)
		} else {
			switch strings.ToUpper(f) {
			case "PICK":
				out[CullFlag] = json.RawMessage(`"pick"`)
			case "REJECT":
				out[CullFlag] = json.RawMessage(`"reject"`)
			}
		}
	}
	if len(p.Label) > 0 && string(p.Label) != "null" {
		if v, ok := CullValue(CullLabel, p.Label); ok {
			out[CullLabel] = v
		} else {
			delete(out, CullLabel)
		}
	}
	return out
}

var cullDefaults = map[string]string{CullRating: "0", CullFlag: `"none"`, CullLabel: `""`}

// bridgePhoto turns the culling fields a photo record changed into culling writes by its device, dated by the
// record's modifiedAt. before is the record's previous data (nil: new or a tombstone). The caller skips devices that
// are culling-aware (ADR 0016 "Reveal's records").
func bridgePhoto(ctx context.Context, tx *sql.Tx, deviceID string, modifiedAt int64, before, after []byte, now int64) error {
	var a photoCulling
	if json.Unmarshal(after, &a) != nil || a.Fingerprint == "" {
		return nil
	}
	av := a.values()
	var bv map[string]json.RawMessage
	if len(before) > 0 {
		var b photoCulling
		if json.Unmarshal(before, &b) == nil {
			bv = b.values()
		}
	}
	var fields []string
	for _, f := range CullFields {
		v, ok := av[f]
		switch {
		case !ok: // unreadable in this record: skipped alone
		case bv == nil:
			if string(v) != cullDefaults[f] {
				fields = append(fields, f)
			}
		default:
			if prev, had := bv[f]; !had || string(v) != string(prev) {
				fields = append(fields, f)
			}
		}
	}
	// Reveal ≤ 0.7.1 reads xmp:Rating="-1" as rating 0 + REJECT: a record that turns to reject and drops its stars to 0
	// in the same write is that reading, not the user clearing the stars. A reject keeps its stars (finding B).
	if bv != nil && contains(fields, CullFlag) && string(av[CullFlag]) == `"reject"` && contains(fields, CullRating) && string(av[CullRating]) == "0" {
		fields = slices.DeleteFunc(fields, func(f string) bool { return f == CullRating })
	}
	if len(fields) == 0 {
		return nil
	}
	id, err := resolveCullItem(ctx, tx, "", a.Fingerprint)
	if err != nil || id == "" {
		return err
	}
	for _, f := range fields {
		if _, err := applyCull(ctx, tx, id, f, av[f], nil, modifiedAt, deviceID, now); err != nil {
			return err
		}
	}
	return nil
}

const settingBridged = "culling.bridgedRecords"

// BridgeExistingRecords bridges every live photo record once (the first start of 0.7.0), so culling made by Reveal
// before appears in the culling records.
func (s *Store) BridgeExistingRecords(ctx context.Context) (bool, error) {
	if _, done, err := s.Setting(ctx, settingBridged); err != nil || done {
		return false, err
	}
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, "SELECT device_id, modified_at, data FROM records WHERE kind = 'photo' AND deleted = 0 ORDER BY rev")
		if err != nil {
			return err
		}
		type rec struct {
			device string
			at     int64
			data   string
		}
		var recs []rec
		for rows.Next() {
			var r rec
			if err := rows.Scan(&r.device, &r.at, &r.data); err != nil {
				rows.Close()
				return err
			}
			recs = append(recs, r)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		aware := map[string]bool{}
		for _, r := range recs {
			a, ok := aware[r.device]
			if !ok {
				if a, err = cullingAware(ctx, tx, r.device); err != nil {
					return err
				}
				aware[r.device] = a
			}
			if a {
				continue
			}
			if err := bridgePhoto(ctx, tx, r.device, r.at, nil, []byte(r.data), now); err != nil {
				return err
			}
		}
		_, err = tx.ExecContext(ctx, "INSERT INTO settings (key, value) VALUES (?, '1') ON CONFLICT (key) DO UPDATE SET value = '1'", settingBridged)
		return err
	})
	return err == nil, err
}

// --- items that change identity (ADR 0016 "Items that change identity") ----------------------------------------------

// ItemFileFingerprints lists the member files of an item (live items only have rows).
func (t *ItemTx) ItemFileFingerprints(id string) ([]string, error) {
	rows, err := t.tx.QueryContext(t.ctx, "SELECT fingerprint FROM item_files WHERE item_id = ? ORDER BY fingerprint", id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var fp string
		if err := rows.Scan(&fp); err != nil {
			return nil, err
		}
		out = append(out, fp)
	}
	return out, rows.Err()
}

// CarryCulling writes every culling field of item from onto item to, each with its own modifiedAt and writer, through
// the conflict rule (a newer value already on to stays).
func (t *ItemTx) CarryCulling(from, to string) error {
	if from == to || to == "" {
		return nil
	}
	rows, err := t.tx.QueryContext(t.ctx, "SELECT field, value, modified_at, device_id FROM culling_fields WHERE item_id = ? ORDER BY field", from)
	if err != nil {
		return err
	}
	type f struct {
		field, value, device string
		at                   int64
	}
	var fs []f
	for rows.Next() {
		var x f
		if err := rows.Scan(&x.field, &x.value, &x.at, &x.device); err != nil {
			rows.Close()
			return err
		}
		fs = append(fs, x)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, x := range fs {
		if _, err := applyCull(t.ctx, t.tx, to, x.field, json.RawMessage(x.value), nil, x.at, x.device, t.now); err != nil {
			return err
		}
	}
	return nil
}
