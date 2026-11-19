package compress

import (
	"context"
	"errors"
	"os"

	"github.com/henriquefps/hoard/server/internal/store"
)

// settleUntracked, after a walk, forgets the trash path of a failed replace's original once that file is gone from the
// trash (Hoard's Empty or auto-empty took it): compress.attention.untrackedTrash then counts only what is still there.
// As for the purge by the trash, nothing is concluded when the folder looks unmounted (the item's path is missing).
func (m *Manager) settleUntracked(ctx context.Context) {
	list, err := m.cfg.Store.CompressItemsIn(ctx, store.CompressFailed)
	if err != nil {
		return
	}
	roots := map[string]*os.Root{}
	defer func() {
		for _, r := range roots {
			_ = r.Close()
		}
	}()
	for _, it := range list {
		if it.Source != "nas" || it.TrashPath == "" || ctx.Err() != nil {
			continue
		}
		r, ok := roots[it.FolderID]
		if !ok {
			if dir, err := m.cfg.FolderDir(ctx, it.FolderID); err == nil {
				r, _ = os.OpenRoot(dir)
			}
			roots[it.FolderID] = r
		}
		if r == nil {
			continue
		}
		if _, err := r.Lstat(it.RelPath); err != nil {
			continue
		}
		if _, err := r.Lstat(it.TrashPath); errors.Is(err, os.ErrNotExist) {
			it.TrashPath = ""
			if m.save(ctx, &it, store.CompressFailed) {
				m.log.Info("compress: an untracked original left the trash", "job", it.JobID, "item", it.ID)
			}
		}
	}
}
