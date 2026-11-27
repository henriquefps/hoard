package server

import (
	"context"
	"errors"
	"net/http"
	"syscall"
)

// The backup volume running out of space (hoard-server 0.14.0): an upload is refused before anything is written, with
// 507 storage_full and what is free and needed, instead of failing half-way with a half-written part. Its bytes are
// checked when the upload is created or resumed (all that is left of it, and of the other live uploads) and before each
// chunk; a write that still meets ENOSPC (another process filled the disk) answers the same, the part kept as far as it
// was written.

// storageReserve stays free on the backup volume: the database, compression's work files and the mirror tree's
// directories need room too.
const storageReserve = 512 << 20

// uploadSpace reports whether the backup volume has room for need more bytes (plus the reserve and what the other live
// uploads still have to send); free is -1 when it cannot be read (the upload then goes on: ENOSPC is the backstop).
func (s *Server) uploadSpace(ctx context.Context, need int64, uploadID string) (ok bool, free, needed int64) {
	bs := s.blobStore()
	if bs == nil {
		return true, -1, need
	}
	free, err := s.freeBytes(bs.Path())
	if err != nil {
		return true, -1, need
	}
	others, err := s.store.UploadsRemaining(ctx, s.store.Now().Add(-uploadLiveWindow).UnixMilli(), uploadID)
	if err != nil {
		others = 0
	}
	needed = need + others + storageReserve
	return free >= needed, free, needed
}

func storageFull(w http.ResponseWriter, r *http.Request, free, needed int64, offset *int64) {
	d := map[string]any{"reason": "storage_full", "needed": needed}
	if free >= 0 {
		d["free"] = free
	}
	if offset != nil {
		d["offset"] = *offset
	}
	w.Header().Set("Retry-After", "3600")
	fail(w, r, http.StatusInsufficientStorage, "storage_full", "The server's backup folder is full: free some space on its disk, then try again.", d)
}

func isNoSpace(err error) bool {
	return errors.Is(err, syscall.ENOSPC) || errors.Is(err, syscall.EDQUOT)
}
