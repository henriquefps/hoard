package server

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The trash (ADR 0023, hoard-server 0.11.0): an item whose every file is in a NAS folder's .hoard-trash is trashed, not
// deleted. The stored item keeps everything; what a client sees is filtered when serving. A client that does not ask
// for the trash (include=trash) sees exactly what older servers answered: a trashed item is a tombstone, and a live
// item lists only its live locations.

// maxWalkFolders is how many folders one POST /v1/nas/walk names at most; walkRate and walkBurst its per-device rate.
const (
	maxWalkFolders = 100
	walkRate       = 1.0 / 10
	walkBurst      = 6
)

// tombstoneJSON writes the tombstone shape of an item (what a client that did not ask for the trash sees of a
// trashed one), with the replacedBy of a trashed item a compression replaced (ADR 0025), so every client follows it.
func tombstoneJSON(buf *bytes.Buffer, r store.ItemRow) {
	fmt.Fprintf(buf, `{"id":"%s","rev":%d,"deleted":true,"createdAt":%d,"updatedAt":%d`, r.ID, r.Rev, r.CreatedAt, r.UpdatedAt)
	if bytes.Contains(r.Data, []byte(`"replacedBy"`)) {
		var d struct {
			ReplacedBy string `json:"replacedBy"`
		}
		if json.Unmarshal(r.Data, &d) == nil && d.ReplacedBy != "" {
			fmt.Fprintf(buf, `,"replacedBy":%s`, jsonString(d.ReplacedBy))
		}
	}
	buf.WriteByte('}')
}

// withoutTrash returns an item's stored state without its locations in a trash, and without the files left with no
// location. ok is false when the data cannot be read (it is then served as stored).
func withoutTrash(data []byte) ([]byte, bool) {
	if !bytes.Contains(data, []byte(`"trash":`)) {
		return data, true
	}
	var d items.Data
	if err := json.Unmarshal(data, &d); err != nil {
		return data, false
	}
	d = d.Live()
	b, err := json.Marshal(d)
	if err != nil {
		return data, false
	}
	return b, true
}

// liveFingerprints lists the members of an item that have a live location (not only in a trash).
func liveFingerprints(data []byte) map[string]bool {
	var d struct {
		Files []struct {
			Fingerprint string `json:"fingerprint"`
			Locations   []struct {
				Trash string `json:"trash"`
			} `json:"locations"`
		} `json:"files"`
	}
	out := map[string]bool{}
	if json.Unmarshal(data, &d) != nil {
		return nil
	}
	for _, f := range d.Files {
		for _, l := range f.Locations {
			if l.Trash == "" {
				out[f.Fingerprint] = true
				break
			}
		}
	}
	return out
}

// parseTrashInclude reads the include list of the item reads (GET /v1/items/{id}): "trash" is the only value.
func parseTrashInclude(v string) (trash, ok bool) {
	if v == "" {
		return false, true
	}
	for _, p := range strings.Split(v, ",") {
		if p != "trash" {
			return false, false
		}
		trash = true
	}
	return trash, true
}

// webTrashed reports whether a request on /web/ names a trashed item: the web gallery shows no trash yet (ADR 0023),
// while /v1 serves a trashed item's previews and edits.
func webTrashed(r *http.Request, row store.ItemRow) bool {
	return row.Trashed() && strings.HasPrefix(r.URL.Path, "/web/")
}

// handleNasWalk queues a walk of NAS folders (ADR 0023): Hoard asks for one after it moved files into or out of a
// trash, so the gallery follows at once.
func (s *Server) handleNasWalk(w http.ResponseWriter, r *http.Request) {
	// Per device: a few requests in a burst, then one per 10 s (429 with Retry-After beyond). The walks themselves
	// are budgeted per folder, whoever asks (Indexer.RequestGap).
	if ok, wait := s.walkLimit.Allow(info(r).device.ID); !ok {
		rateLimited(w, r, wait)
		return
	}
	var req struct {
		Folders []string `json:"folders"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Folders) == 0 || len(req.Folders) > maxWalkFolders {
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", fmt.Sprintf("folders lists 1 to %d NAS folder ids.", maxWalkFolders), nil)
		return
	}
	ids := make([]string, 0, len(req.Folders))
	seen := map[string]bool{}
	for i, id := range req.Folders {
		if !uuidPattern.MatchString(id) {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "folders are NAS folder ids.", map[string]any{"index": i})
			return
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		f, err := s.store.NasFolder(r.Context(), id)
		if errors.Is(err, store.ErrNotFound) || err == nil && !f.Enabled {
			fail(w, r, http.StatusNotFound, "not_found", "No such NAS folder.", map[string]any{"folder": id})
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		ids = append(ids, id)
	}
	deferred := s.indexer.RequestWalk(ids)
	later := map[string]int64{}
	for id, at := range deferred {
		later[id] = at.UnixMilli()
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"queued": ids, "deferred": later})
}
