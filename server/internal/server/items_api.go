package server

import (
	"bytes"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Items (ADR 0014): additions under /v1 that Reveal never calls (routed in routesAPI). The items feed has its own
// revision counter and cursors ("<server>:i<rev>"); the browse query has keyset cursors
// ("<server>:q<sortTime>.<id>.<order>").

// itemJSON writes an item as served: id, rev, deleted, createdAt, updatedAt, then the stored state (for a tombstone
// only replacedBy, when known).
func itemJSON(buf *bytes.Buffer, r store.ItemRow) {
	fmt.Fprintf(buf, `{"id":"%s","rev":%d,"deleted":%t,"createdAt":%d,"updatedAt":%d`, r.ID, r.Rev, r.Deleted, r.CreatedAt, r.UpdatedAt)
	data := bytes.TrimSpace(r.Data)
	if len(data) > 2 && data[0] == '{' {
		buf.WriteByte(',')
		buf.Write(data[1:])
		return
	}
	buf.WriteByte('}')
}

// writeItems answers {"items": [...], <extra fields>} without decoding the stored JSON.
func writeItems(w http.ResponseWriter, rows []store.ItemRow, extra string) {
	var buf bytes.Buffer
	buf.WriteString(`{"items":[`)
	for i, r := range rows {
		if i > 0 {
			buf.WriteByte(',')
		}
		itemJSON(&buf, r)
	}
	buf.WriteString("]")
	buf.WriteString(extra)
	buf.WriteString("}\n")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(buf.Bytes())
}

func jsonString(v string) string { return strconv.Quote(v) } // ids and cursors are ASCII

func (s *Server) handleItemChanges(w http.ResponseWriter, r *http.Request) {
	head, err := s.store.Counter(r.Context(), "items")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	since, ok := s.parseCursor(r.URL.Query().Get("cursor"), "i", head)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued. Pull from the start (empty cursor).", nil)
		return
	}
	limit, ok := pageLimit(r)
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "limit must be a positive integer.", nil)
		return
	}
	withItems, withCulling, ok := feedInclude(r.URL.Query().Get("include"))
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "include is a comma-separated list of items and culling.", nil)
		return
	}
	s.writeFeed(w, r, since, limit, withItems, withCulling)
}

var itemKinds = map[string]bool{items.KindPhoto: true, items.KindPair: true, items.KindVideo: true, items.KindMotion: true}

func (s *Server) handleItems(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	bad := func(msg string) { fail(w, r, http.StatusBadRequest, "invalid_request", msg, nil) }
	var iq store.ItemQuery
	if k := q.Get("kind"); k != "" {
		for _, v := range strings.Split(k, ",") {
			if !itemKinds[v] {
				bad("kind is a comma-separated list of photo, pair, video, motion.")
				return
			}
			iq.Kinds = append(iq.Kinds, v)
		}
	}
	for _, p := range []struct {
		name string
		dst  **int64
	}{{"from", &iq.From}, {"to", &iq.To}} {
		if v := q.Get(p.name); v != "" {
			n, err := strconv.ParseInt(v, 10, 64)
			if err != nil {
				bad(p.name + " is Unix milliseconds.")
				return
			}
			*p.dst = &n
		}
	}
	iq.FolderID = q.Get("folder")
	if iq.FolderID != "" && !uuidPattern.MatchString(iq.FolderID) {
		bad("folder is a NAS folder id.")
		return
	}
	if p := q.Get("path"); p != "" {
		if iq.FolderID == "" || strings.HasPrefix(p, "/") || strings.HasSuffix(p, "/") || strings.Contains(p, "//") || len(p) > 1024 {
			bad("path needs folder and is a directory relative to it, '/'-separated, without a leading or trailing '/'.")
			return
		}
		iq.Dir = p
	}
	switch q.Get("order") {
	case "", "desc":
		iq.Desc = true
	case "asc":
	default:
		bad("order is asc or desc.")
		return
	}
	limit, ok := pageLimit(r)
	if !ok {
		bad("limit must be a positive integer.")
		return
	}
	iq.Limit = limit
	if c := q.Get("cursor"); c != "" {
		key, desc, ok := s.parseItemCursor(c)
		if !ok || desc != iq.Desc {
			fail(w, r, http.StatusBadRequest, "invalid_cursor", "The cursor is not one this server issued for this order.", nil)
			return
		}
		iq.After = &key
	}
	rows, more, err := s.store.QueryItems(r.Context(), iq)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	next := "null"
	if more && len(rows) > 0 {
		last := rows[len(rows)-1]
		next = jsonString(s.itemCursor(store.ItemKey{SortTime: last.SortTime, ID: last.ID}, iq.Desc))
	}
	writeItems(w, rows, fmt.Sprintf(`,"cursor":%s,"hasMore":%t`, next, more))
}

func (s *Server) itemCursor(k store.ItemKey, desc bool) string {
	order := "a"
	if desc {
		order = "d"
	}
	return s.serverID[:8] + ":q" + strconv.FormatInt(k.SortTime, 10) + "." + k.ID + "." + order
}

func (s *Server) parseItemCursor(c string) (store.ItemKey, bool, bool) {
	sid, rest, ok := strings.Cut(c, ":q")
	if !ok || sid != s.serverID[:8] {
		return store.ItemKey{}, false, false
	}
	parts := strings.Split(rest, ".")
	if len(parts) != 3 || !uuidPattern.MatchString(parts[1]) || (parts[2] != "a" && parts[2] != "d") {
		return store.ItemKey{}, false, false
	}
	t, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return store.ItemKey{}, false, false
	}
	return store.ItemKey{SortTime: t, ID: parts[1]}, parts[2] == "d", true
}

// itemIDPattern accepts any RFC 9562 layout in lower case (item ids are version 8).
var itemIDPattern = uuidPattern

func (s *Server) handleItem(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("itemId")
	if !itemIDPattern.MatchString(id) {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	}
	row, err := s.store.Item(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No such item.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	var buf bytes.Buffer
	itemJSON(&buf, row)
	buf.WriteByte('\n')
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(buf.Bytes())
}

func (s *Server) handleItemLookup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Fingerprints []string `json:"fingerprints"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Fingerprints) > maxLookup {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("At most %d fingerprints per lookup.", maxLookup), nil)
		return
	}
	for i, fp := range req.Fingerprints {
		if !fingerprint.Valid(fp) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "Fingerprints are v1: + 64 lower-case hex.", map[string]any{"index": i})
			return
		}
	}
	matches, err := s.store.ItemFilesByFingerprint(r.Context(), req.Fingerprints)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	found := map[string]bool{}
	var rows []store.ItemRow
	seenItem := map[string]bool{}
	for _, m := range matches {
		found[m.Fingerprint] = true
		if seenItem[m.ItemID] {
			continue
		}
		seenItem[m.ItemID] = true
		row, err := s.store.Item(r.Context(), m.ItemID)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		rows = append(rows, row)
	}
	var extra bytes.Buffer
	extra.WriteString(`,"matches":[`)
	for i, m := range matches {
		if i > 0 {
			extra.WriteByte(',')
		}
		fmt.Fprintf(&extra, `{"fingerprint":%s,"itemId":%s,"role":%s}`, jsonString(m.Fingerprint), jsonString(m.ItemID), jsonString(m.Role))
	}
	extra.WriteString(`],"missing":[`)
	n := 0
	dup := map[string]bool{}
	for _, fp := range req.Fingerprints {
		if found[fp] || dup[fp] {
			continue
		}
		dup[fp] = true
		if n > 0 {
			extra.WriteByte(',')
		}
		extra.WriteString(jsonString(fp))
		n++
	}
	extra.WriteString("]")
	writeItems(w, rows, extra.String())
}
