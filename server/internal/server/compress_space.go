package server

import (
	"context"
	"os"
	"syscall"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/store"
)

// The space a compression needs (hoard-server 0.14.0, ADR 0025 Amendment (0.14.0)): every replaced original stays in
// the trash (a hard link) until it is emptied, so a job's outputs add to what the disk holds until then. A NAS replace
// needs its output's size + 64 MiB free when it runs; a whole job needs about the sum of its outputs.

const replaceMargin = 64 << 20

// compressSpace is the "space" object of an estimate: what the encodes of the selection need on the disk that takes
// their outputs, what is free there, and what the trash holds (emptying it frees that). fits is false when one disk
// lacks room; the client then says "compress in parts or empty the trash first".
func (s *Server) compressSpace(ctx context.Context, target string, est []compress.EstimateItem) map[string]any {
	type disk struct {
		needed, free, trash int64
		known               bool
	}
	disks := map[uint64]*disk{}
	devOf := map[string]uint64{}
	trashed := map[string]bool{}
	var devErr bool
	addTo := func(dev uint64, dir, folderID string, need int64) {
		d := disks[dev]
		if d == nil {
			d = &disk{}
			if free, err := s.freeBytes(dir); err == nil {
				d.free, d.known = free, true
			}
			disks[dev] = d
		}
		d.needed += need
		if folderID != "" && !trashed[folderID] {
			trashed[folderID] = true
			if b, err := s.store.NasTrashBytes(ctx, folderID); err == nil {
				d.trash += b
			}
		}
	}
	devOfDir := func(dir string) (uint64, bool) {
		fi, err := os.Stat(dir)
		if err != nil {
			return 0, false
		}
		st, ok := fi.Sys().(*syscall.Stat_t)
		if !ok {
			return 0, false
		}
		return uint64(st.Dev), true //nolint:unconvert // Dev is int32 on some platforms
	}
	for _, e := range est {
		if e.Decision != compress.DecisionEncode {
			continue
		}
		if target == compress.TargetNAS {
			dir, err := s.nasFolderDir(ctx, e.FolderID)
			if err != nil {
				devErr = true
				continue
			}
			dev, ok := devOf[e.FolderID]
			if !ok {
				if dev, ok = devOfDir(dir); !ok {
					devErr = true
					continue
				}
				devOf[e.FolderID] = dev
			}
			addTo(dev, dir, e.FolderID, e.EstSize)
			continue
		}
		bs := s.blobStore()
		if bs == nil {
			devErr = true
			continue
		}
		dev, ok := devOfDir(bs.Path())
		if !ok {
			devErr = true
			continue
		}
		addTo(dev, bs.Path(), "", e.EstSize)
	}
	var needed, free, trash int64
	fits, known := true, !devErr
	for _, d := range disks {
		d.needed += replaceMargin
		needed += d.needed
		free += d.free
		trash += d.trash
		known = known && d.known
		if d.known && d.needed > d.free {
			fits = false
		}
	}
	out := map[string]any{"target": target, "needed": needed, "free": free, "trash": trash, "fits": fits && known, "known": known,
		"disks": len(disks)}
	if len(disks) == 0 {
		out["needed"], out["fits"] = int64(0), true
	}
	return out
}

// noSpaceInfo is what an item waiting for room (reason no_space) needs and what its disk has now: a NAS replace
// (ready) waits for its output's size + 64 MiB on the NAS; an encode (queued) for room in the work directory on the
// backup volume.
func (s *Server) noSpaceInfo(ctx context.Context, it store.CompressItem) map[string]any {
	m := map[string]any{"where": "nas"}
	if it.State == store.CompressQueued {
		m["where"] = "work"
		if bs := s.blobStore(); bs != nil {
			if free, err := s.freeBytes(bs.Path()); err == nil {
				m["free"] = free
			}
		}
		return m
	}
	if it.OutSize != nil {
		m["needed"] = *it.OutSize + replaceMargin
	}
	if it.FolderID != "" {
		if dir, err := s.nasFolderDir(ctx, it.FolderID); err == nil {
			if free, err := s.freeBytes(dir); err == nil {
				m["free"] = free
			}
		}
	}
	return m
}

// freeBytes reads a filesystem's free bytes (tests replace it with freeBytesFn).
func (s *Server) freeBytes(dir string) (int64, error) {
	if s.freeBytesFn != nil {
		return s.freeBytesFn(dir)
	}
	return compress.FreeBytes(dir)
}
