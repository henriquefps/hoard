package server

import (
	"context"
	"errors"
	"net/http"
	"os"
	"strconv"

	"github.com/henriquefps/hoard/server/internal/blobstore"
	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Video compression of phone backups (ADR 0025 phase S2): the manager's view of the blob store, the NAS copies of ADR
// 0024 and the mirror tree (compressBlobs), and the phone's four routes.

type compressBlobs struct{ s *Server }

var _ compress.BlobHost = compressBlobs{}

func (b compressBlobs) Open(ctx context.Context, e store.MirrorEntry) (*os.File, error) {
	if e.Store == store.StoreBlob {
		if !blobstore.ValidSHA256(e.SHA256) {
			return nil, store.ErrNotFound
		}
		return b.s.openBlob(e.SHA256)
	}
	nf, err := b.s.store.NasFileForVerify(ctx, e.Fingerprint)
	if err != nil {
		return nil, err
	}
	f, err := b.s.openNasFile(nf.Original)
	if err != nil {
		b.s.indexer.Trigger()
	}
	return f, err
}

func (b compressBlobs) Ingest(ctx context.Context, entryID int64) error {
	if reason := b.s.copyNasEntry(ctx, entryID); reason != "" {
		return errors.New(reason)
	}
	return nil
}

func (b compressBlobs) Adopt(_ context.Context, path, sha, _ string, _ int64) (func(), error) {
	bs := b.s.blobStore()
	if bs == nil {
		return nil, compress.ErrNoBlobs
	}
	unlock := b.s.lockContent(sha)
	if err := bs.Adopt(path, sha); err != nil {
		unlock()
		return nil, err
	}
	return unlock, nil
}

func (b compressBlobs) Changed(ctx context.Context, ids []int64) {
	b.s.reconcile(ctx, ids)
	b.s.indexer.TriggerBackup()
}

func (b compressBlobs) Release(ctx context.Context, links []store.MirrorEntry, shas []string) {
	s := b.s
	if t := s.mirrorTree(); t != nil && len(links) > 0 {
		var roots []string
		s.backup.fsMu.Lock()
		for _, e := range links {
			if e.TreePath != "" && e.DirName != "" {
				if err := t.Remove(e.DirName, e.TreePath, e.SHA256); err != nil {
					s.log.Warn("compress: a link stays", "err", errorClassOf(err))
				}
			}
			roots = append(roots, e.RootID)
		}
		s.backup.fsMu.Unlock()
		s.markDirty(roots...)
	}
	if err := s.removeReleasedBlobs(ctx, shas); err != nil {
		s.log.Warn("compress: a released blob's file stays", "err", errorClassOf(err))
	}
	s.indexer.TriggerBackup()
}

// phoneItem reads {id} and answers 404 unless it is a phone item of the caller's phone.
func (s *Server) phoneItem(w http.ResponseWriter, r *http.Request) (store.CompressItem, bool) {
	id, err := strconv.ParseInt(r.PathValue("compressItemId"), 10, 64)
	if err != nil || id <= 0 {
		fail(w, r, http.StatusNotFound, "not_found", "No such compression item of this phone.", nil)
		return store.CompressItem{}, false
	}
	it, err := s.compress.PhoneItem(r.Context(), id, info(r).device.PhysicalID)
	switch {
	case errors.Is(err, store.ErrNotFound), errors.Is(err, compress.ErrNotPhoneItem):
		fail(w, r, http.StatusNotFound, "not_found", "No such compression item of this phone.", nil)
		return it, false
	case err != nil:
		s.internal(w, r, err)
		return it, false
	}
	return it, true
}

// handleCompressOutput serves a phone item's output (GET /v1/compress/items/{id}/output) by range, while the phone may
// still need it: ready, or a restore again by the phone.
func (s *Server) handleCompressOutput(w http.ResponseWriter, r *http.Request) {
	it, ok := s.phoneItem(w, r)
	if !ok {
		return
	}
	if it.State != store.CompressReady && it.State != store.CompressReplacing || it.OutSHA256 == "" {
		fail(w, r, http.StatusNotFound, "not_found", "This item has no output to download now.", map[string]any{"state": it.State})
		return
	}
	f, err := s.compress.OpenBlob(r.Context(), it.OutSHA256)
	if errors.Is(err, os.ErrNotExist) {
		fail(w, r, http.StatusNotFound, "not_found", "The output is not in the store.", nil)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	defer f.Close()
	s.compress.MarkServed(r.Context(), it)
	h := w.Header()
	h.Set("X-Hoard-Sha256", it.OutSHA256)
	h.Set("X-Hoard-Fingerprint", it.OutFingerprint)
	serveFile(w, r, f, it.OutSHA256)
}

type phoneFileIn struct {
	Fingerprint string `json:"fingerprint"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
	ModifiedAt  int64  `json:"modifiedAt"`
}

// handleCompressPhoneReport is POST …/replaced (toOutput) and …/restored: the phone wrote the file at its path.
func (s *Server) handleCompressPhoneReport(toOutput bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		it, ok := s.phoneItem(w, r)
		if !ok {
			return
		}
		var in phoneFileIn
		if !readJSON(w, r, &in) {
			return
		}
		if !fingerprint.Valid(in.Fingerprint) || !blobstore.ValidSHA256(in.SHA256) || in.Size <= 0 || in.ModifiedAt <= 0 {
			fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "fingerprint, sha256, size and modifiedAt (Unix ms) are required.", nil)
			return
		}
		got, err := s.compress.PhoneReported(r.Context(), it, toOutput, store.PhoneFile{Fingerprint: in.Fingerprint, SHA256: in.SHA256, Size: in.Size,
			ModifiedAt: in.ModifiedAt}, info(r).device.ID)
		switch {
		case errors.Is(err, store.ErrCompressMismatch):
			fail(w, r, http.StatusConflict, "conflict", "This is not the file the server expects at that path.", map[string]any{"reason": "mismatch"})
			return
		case errors.Is(err, store.ErrCompressPath):
			fail(w, r, http.StatusConflict, "conflict", "The backup holds another file at that path.", map[string]any{"reason": "mismatch"})
			return
		case errors.Is(err, store.ErrConflict):
			fail(w, r, http.StatusConflict, "conflict", "The item is not waiting for this report.", map[string]any{"reason": "state", "state": it.State})
			return
		case err != nil:
			s.internal(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, s.compressItemsJSON(r.Context(), []store.CompressItem{got})[0])
	}
}

// handleCompressAbandon is POST …/abandon: the phone gave the item up.
func (s *Server) handleCompressAbandon(w http.ResponseWriter, r *http.Request) {
	it, ok := s.phoneItem(w, r)
	if !ok {
		return
	}
	var in struct {
		Reason string `json:"reason"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	switch in.Reason {
	case compress.AbandonVerify, compress.AbandonChanged, compress.AbandonGone, compress.AbandonRefused:
	default:
		fail(w, r, http.StatusUnprocessableEntity, "invalid_request", "reason is verify, changed, gone or refused.", nil)
		return
	}
	got, err := s.compress.Abandon(r.Context(), it, in.Reason)
	switch {
	case errors.Is(err, store.ErrConflict):
		fail(w, r, http.StatusConflict, "conflict", "The item is not waiting for the phone.", map[string]any{"reason": "state", "state": it.State})
		return
	case err != nil && !errors.Is(err, store.ErrCompressPath):
		s.internal(w, r, err)
		return
	}
	if fresh, err := s.store.CompressItem(r.Context(), got.ID); err == nil {
		got = fresh
	}
	writeJSON(w, http.StatusOK, s.compressItemsJSON(r.Context(), []store.CompressItem{got})[0])
}
