//go:build unix

package mirror

import (
	"io/fs"
	"syscall"
)

// nlink is the number of names a file has (a blob's hard link has at least two).
func nlink(fi fs.FileInfo) uint64 {
	if st, ok := fi.Sys().(*syscall.Stat_t); ok {
		return uint64(st.Nlink) //nolint:unconvert // uint16 on some platforms
	}
	return 0
}
