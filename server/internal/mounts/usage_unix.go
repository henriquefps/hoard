//go:build unix

package mounts

import "golang.org/x/sys/unix"

// Usage returns the total and free bytes of the filesystem holding p (0, 0 when unknown).
func Usage(p string) (total, free uint64) {
	var st unix.Statfs_t
	if err := unix.Statfs(p, &st); err != nil {
		return 0, 0
	}
	bsize := uint64(max(st.Bsize, 0)) //nolint:gosec,unconvert // Bsize is int64 on Linux, uint32 on Darwin; clamped first
	return st.Blocks * bsize, st.Bavail * bsize
}
