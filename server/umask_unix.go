//go:build unix

package main

import "syscall"

// restrictUmask makes every file and directory the server creates private (0600 / 0700).
func restrictUmask() { syscall.Umask(0o077) }
