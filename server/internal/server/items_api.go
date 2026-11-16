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

// itemJSON writes an item as served: id, rev, deleted, createdAt, updatedAt, its edits (ADR 0020) when it has any,
// then the stored state (for a tombstone only replacedBy, when known). withTrash (ADR 0023, include=trash): a trashed
// item is served whole with "trashed": {"since"} and locations in a trash keep their "trash" path; without it, a
// trashed item is a tombstone and a live item lists only its live locations.
func itemJSON(buf *bytes.Buffer, r store.ItemRow, withTrash bool) {
	if r.Trashed() && !withTrash {
		tombstoneJSON(buf, r)
		return
	}
	fmt.Fprintf(buf, `{"id":"%s","rev":%d,"deleted":%t,"createdAt":%d,"updatedAt":%d`, r.ID, r.Rev, r.Deleted, r.CreatedAt, r.UpdatedAt)
	if r.Trashed() {
		fmt.Fprintf(buf, `,"trashed":{"since":%d}`, *r.TrashedAt)
	}
	if len(r.Edits) > 0 { // Reveal's edited previews (ADR 0020)
		buf.WriteString(`,"edits":`)
		buf.Write(r.Edits)
	}
	data := bytes.TrimSpace(r.Data)
	if !withTrash && !r.Deleted {
		data, _ = withoutTrash(data)
	}
	if len(data) > 2 && data[0] == '{' {
		buf.WriteByte(',')
		buf.Write(data[1:])
		return
	}
	buf.WriteByte('}')
}

// writeItems answers {"items": [...], <extra fields>} without decoding the stored JSON (but for live items with files
// in a trash, served without them unless withTrash).
func writeItems(w http.ResponseWriter, rows []store.ItemRow, extra string, withTrash bool) {
	var buf bytes.Buffer
	buf.WriteString(`{"items":[`)
	for i, r := range rows {
		if i > 0 {
			buf.WriteByte(',')
		}
		itemJSON(&buf, r, withTrash)
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
	inc, ok := feedInclude(r.URL.Query().Get("include"))
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "include is a comma-separated list of items, culling and trash.", nil)
		return
	}
	s.writeFeed(w, r, since, limit, inc)
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
	switch q.Get("trashed") { // ADR 0023: the trashed items instead of the live ones
	case "":
	case "only":
		iq.Trashed = true
	default:
		bad("trashed is only.")
		return
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
	writeItems(w, rows, fmt.Sprintf(`,"cursor":%s,"hasMore":%t`, next, more), iq.Trashed)
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
	withTrash, ok := parseTrashInclude(r.URL.Query().Get("include"))
	if !ok {
		fail(w, r, http.StatusBadRequest, "invalid_request", "include is trash or absent.", nil)
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
	itemJSON(&buf, row, withTrash)
	buf.WriteByte('\n')
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(buf.Bytes())
}

func (s *Server) handleItemLookup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Fingerprints []string `json:"fingerprints"`
		Include      []string `json:"include"` // ADR 0023: ["trash"] also matches trashed items and files in a trash
	}
	if !readJSON(w, r, &req) {
		return
	}
	withTrash := false
	for _, v := range req.Include {
		if v != "trash" {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "include lists trash or nothing.", nil)
			return
		}
		withTrash = true
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
	loaded := map[string]*store.ItemRow{}
	listed := map[string]bool{}
	live := map[string]map[string]bool{} // item -> members with a live location
	kept := matches[:0]
	for _, m := range matches {
		row := loaded[m.ItemID]
		if row == nil {
			r2, err := s.store.Item(r.Context(), m.ItemID)
			if err != nil {
				s.internal(w, r, err)
				return
			}
			row = &r2
			loaded[m.ItemID] = row
			if !withTrash {
				live[m.ItemID] = liveFingerprints(r2.Data)
			}
		}
		// Without the trash, a trashed item and a file only in a trash are missing (ADR 0023).
		if !withTrash && (row.Trashed() || !live[m.ItemID][m.Fingerprint]) {
			continue
		}
		found[m.Fingerprint] = true
		kept = append(kept, m)
		if !listed[row.ID] {
			listed[row.ID] = true
			rows = append(rows, *row)
		}
	}
	matches = kept
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
	writeItems(w, rows, extra.String(), withTrash)
}

// cleanItemPath normalises a relative NAS path ("a//b/./c" becomes "a/b/c"); ok is false for an absolute path, a ".."
// segment, a backslash, a control character or a path that is empty once normalised.
func cleanItemPath(p string) (string, bool) {
	if len(p) > 1024 || strings.HasPrefix(p, "/") || strings.ContainsAny(p, "\\") {
		return "", false
	}
	for _, c := range p {
		if c < 0x20 || c == 0x7f {
			return "", false
		}
	}
	var parts []string
	for _, seg := range strings.Split(p, "/") {
		switch seg {
		case "", ".":
		case "..":
			return "", false
		default:
			parts = append(parts, seg)
		}
	}
	if len(parts) == 0 {
		return "", false
	}
	return strings.Join(parts, "/"), true
}

// handleItemsByPath resolves NAS paths to items (ADR 0014 "Items by path", hoard-server 0.10.0).
func (s *Server) handleItemsByPath(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Folder string   `json:"folder"`
		Paths  []string `json:"paths"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if !uuidPattern.MatchString(req.Folder) {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "folder is a NAS folder id.", nil)
		return
	}
	if len(req.Paths) > maxLookup {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("At most %d paths per request.", maxLookup), nil)
		return
	}
	clean := make([]string, len(req.Paths))
	for i, p := range req.Paths {
		c, ok := cleanItemPath(p)
		if !ok {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request",
				"Paths are relative to the folder, '/'-separated, without '..', backslashes or a leading '/'.", map[string]any{"index": i})
			return
		}
		clean[i] = c
	}
	if _, err := s.store.NasFolder(r.Context(), req.Folder); errors.Is(err, store.ErrNotFound) {
		fail(w, r, http.StatusNotFound, "not_found", "No such NAS folder.", nil)
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	matches, err := s.store.ItemsByPath(r.Context(), req.Folder, clean)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	byPath := make(map[string]store.PathMatch, len(matches))
	for _, m := range matches {
		byPath[m.Path] = m
	}
	var buf bytes.Buffer
	buf.WriteString(`{"found":[`)
	var missing []string
	done := map[string]bool{}
	n := 0
	for i, p := range req.Paths { // in request order, each requested path once
		if done[p] {
			continue
		}
		done[p] = true
		m, ok := byPath[clean[i]]
		if !ok {
			missing = append(missing, p)
			continue
		}
		if n > 0 {
			buf.WriteByte(',')
		}
		n++
		fmt.Fprintf(&buf, `{"path":%s,"itemId":%s,"role":%s,"size":%d,"modifiedAt":%d}`, jsonString(p), jsonString(m.ItemID), jsonString(m.Role), m.Size, m.ModifiedAt)
	}
	buf.WriteString(`],"missing":[`)
	for i, p := range missing {
		if i > 0 {
			buf.WriteByte(',')
		}
		buf.WriteString(jsonString(p))
	}
	buf.WriteString("]}\n")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(buf.Bytes())
}
