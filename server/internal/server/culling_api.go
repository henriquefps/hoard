package server

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Culling (ADR 0016, hoard-server 0.7.0): additions under /v1 (routed in routesAPI). Records travel in the items feed
// (include=culling) and are pushed per field.

type cullPush struct {
	ItemID      string          `json:"itemId"`
	Fingerprint string          `json:"fingerprint"`
	Field       string          `json:"field"`
	Value       json.RawMessage `json:"value"`
	BaseRev     *int64          `json:"baseRev"`
	ModifiedAt  *int64          `json:"modifiedAt"`
}

func validateCull(c cullPush) (store.CullChange, string) {
	out := store.CullChange{ItemID: c.ItemID, Fingerprint: c.Fingerprint, Field: c.Field}
	switch {
	case (c.ItemID == "") == (c.Fingerprint == ""):
		return out, "name the shot with exactly one of itemId and fingerprint"
	case c.ItemID != "" && !uuidPattern.MatchString(c.ItemID):
		return out, "itemId must be a lower-case UUID"
	case c.Fingerprint != "" && !fingerprint.Valid(c.Fingerprint):
		return out, "fingerprint is v1: + 64 lower-case hex"
	}
	if c.Field != store.CullRating && c.Field != store.CullFlag && c.Field != store.CullLabel {
		return out, "field must be rating, flag or label"
	}
	v, ok := store.CullValue(c.Field, c.Value)
	if !ok {
		return out, fmt.Sprintf("value: rating is an integer 0..5, flag is pick, reject or none, label a string of at most %d characters", store.MaxLabel)
	}
	if c.BaseRev == nil || *c.BaseRev < 0 {
		return out, "baseRev is required (0 when the field was never seen)"
	}
	if c.ModifiedAt == nil || *c.ModifiedAt <= 0 || *c.ModifiedAt > 1<<53 {
		return out, "modifiedAt (Unix milliseconds) is required"
	}
	out.Value, out.BaseRev, out.ModifiedAt = v, c.BaseRev, *c.ModifiedAt
	return out, ""
}

func (s *Server) handleCullingPush(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Changes []cullPush `json:"changes"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Changes) == 0 || len(req.Changes) > maxPushChanges {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_change", fmt.Sprintf("Send 1 to %d changes.", maxPushChanges), nil)
		return
	}
	changes := make([]store.CullChange, len(req.Changes))
	for i, c := range req.Changes {
		v, problem := validateCull(c)
		if problem != "" {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_change", problem, map[string]any{"index": i})
			return
		}
		changes[i] = v
	}
	results, err := s.store.PushCulling(r.Context(), info(r).device.ID, changes)
	var ce *store.CullError
	if errors.As(err, &ce) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_change", ce.Problem, map[string]any{"index": ce.Index})
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.touch(r.Context(), info(r).device.ID, true)
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

func (s *Server) handleCulling(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("itemId")
	if !uuidPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No culling for this item.", nil)
		return
	}
	rec, err := s.store.Culling(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No culling for this item.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

// feedInclude parses include= of the items feed: items (default) and/or culling.
func feedInclude(v string) (items, culling, ok bool) {
	if v == "" {
		return true, false, true
	}
	for _, p := range strings.Split(v, ",") {
		switch p {
		case "items":
			items = true
		case "culling":
			culling = true
		default:
			return false, false, false
		}
	}
	return items, culling, true
}

// writeFeed answers the items feed with items and culling records merged in revision order, at most limit in all.
func (s *Server) writeFeed(w http.ResponseWriter, r *http.Request, since int64, limit int, withItems, withCulling bool) {
	var rows []store.ItemRow
	var recs []store.CullRecord
	var moreItems, moreCulling bool
	var err error
	if withItems {
		if rows, moreItems, err = s.store.ItemChanges(r.Context(), since, limit); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if withCulling {
		if recs, moreCulling, err = s.store.CullingChanges(r.Context(), since, limit); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	// Merge by revision and keep the first limit entries; whatever is left over comes on the next page.
	var keptItems []store.ItemRow
	var keptRecs []store.CullRecord
	i, j := 0, 0
	last := since
	for len(keptItems)+len(keptRecs) < limit && (i < len(rows) || j < len(recs)) {
		if j >= len(recs) || (i < len(rows) && rows[i].Rev < recs[j].Rev) {
			keptItems = append(keptItems, rows[i])
			last = rows[i].Rev
			i++
		} else {
			keptRecs = append(keptRecs, recs[j])
			last = recs[j].Rev
			j++
		}
	}
	more := moreItems || moreCulling || i < len(rows) || j < len(recs)
	extra := ""
	if withCulling {
		b, err := json.Marshal(keptRecs)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		if keptRecs == nil {
			b = []byte("[]")
		}
		extra = `,"culling":` + string(bytes.TrimSpace(b))
	}
	if !withItems {
		keptItems = nil
	}
	writeItems(w, keptItems, extra+fmt.Sprintf(`,"cursor":%s,"hasMore":%t`, jsonString(s.cursor("i", last)), more))
}
