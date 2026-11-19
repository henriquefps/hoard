//go:build unix

package compress

import (
	"os"

	"golang.org/x/sys/unix"
)

func freeBytesFile(f *os.File) (int64, error) {
	var st unix.Statfs_t
	if err := unix.Fstatfs(int(f.Fd()), &st); err != nil {
		return 0, err
	}
	return int64(min(st.Bavail*uint64(st.Bsize), 1<<62)), nil //nolint:gosec // bounded above
}

// FreeBytes returns the free bytes of the filesystem holding dir.
func FreeBytes(dir string) (int64, error) {
	var st unix.Statfs_t
	if err := unix.Statfs(dir, &st); err != nil {
		return 0, err
	}
	return int64(min(st.Bavail*uint64(st.Bsize), 1<<62)), nil //nolint:gosec // bounded above
}

// LowerPriority makes a tool yield the CPU (nice 10).
func LowerPriority(pid int) { _ = unix.Setpriority(unix.PRIO_PROCESS, pid, 10) }
