//go:build !unix

package mounts

// Usage is unknown on this platform.
func Usage(string) (total, free uint64) { return 0, 0 }
