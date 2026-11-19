package compress

import (
	"errors"
	"fmt"
	"io"
	"os"
	"runtime"
)

// fdInput is how a child process names the inherited source (ExtraFiles[0] is descriptor 3).
var fdInput = func() string {
	if runtime.GOOS == "linux" {
		return "/proc/self/fd/3"
	}
	return "/dev/fd/3"
}()

// toolEnv is the environment of every tool: no shell, a fixed PATH, no home (ADR 0025, as previews/cpu.go).
func toolEnv() []string {
	env := []string{"PATH=/usr/bin:/bin", "HOME=/nonexistent", "LD_LIBRARY_PATH=" + os.Getenv("LD_LIBRARY_PATH"), "LC_ALL=C"}
	for _, k := range []string{"NVIDIA_VISIBLE_DEVICES", "NVIDIA_DRIVER_CAPABILITIES", "CUDA_VISIBLE_DEVICES"} {
		if v, ok := os.LookupEnv(k); ok {
			env = append(env, k+"="+v)
		}
	}
	return env
}

// limited keeps at most n bytes written (the rest is dropped, the writer never fails).
type limited struct {
	w io.Writer
	n int
}

func (l *limited) Write(p []byte) (int, error) {
	if l.n <= 0 {
		return len(p), nil
	}
	q := p
	if len(q) > l.n {
		q = q[:l.n]
	}
	n, err := l.w.Write(q)
	l.n -= n
	if err != nil {
		return n, err
	}
	return len(p), nil
}

// errCapped is what a capped writer answers once a write would pass its cap.
var errCapped = errors.New("output over the size limit")

// capped is a writer that fails at its cap instead of dropping the rest: what it keeps must be whole (the per-frame
// metadata, ADR 0025 Amendment (N4)).
type capped struct {
	w    io.Writer
	n    int64
	over bool
}

func (c *capped) Write(p []byte) (int, error) {
	if int64(len(p)) > c.n {
		c.over = true
		return 0, fmt.Errorf("%w (%d bytes)", errCapped, c.n)
	}
	n, err := c.w.Write(p)
	c.n -= int64(n)
	return n, err
}

// tail keeps the last n bytes written (ffmpeg's stderr in the item's log).
type tail struct {
	b []byte
	n int
}

func (t *tail) Write(p []byte) (int, error) {
	t.b = append(t.b, p...)
	if len(t.b) > t.n {
		t.b = append([]byte(nil), t.b[len(t.b)-t.n:]...)
	}
	return len(p), nil
}

func (t *tail) String() string { return string(t.b) }
