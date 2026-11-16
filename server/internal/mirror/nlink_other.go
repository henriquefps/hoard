//go:build !unix

package mirror

import "io/fs"

// nlink is unknown here: a file in the way is never replaced.
func nlink(fs.FileInfo) uint64 { return 0 }
