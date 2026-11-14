//go:build unix

package previews

import "golang.org/x/sys/unix"

// lowerPriority makes a preview process yield the CPU to the server and to the host's other work (nice 10).
func lowerPriority(pid int) { _ = unix.Setpriority(unix.PRIO_PROCESS, pid, 10) }
