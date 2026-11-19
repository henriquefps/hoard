package compress

import (
	"compress/gzip"
	"encoding/json"
	"errors"
	"io"
	"os"
)

// SidecarVersion is the format of the metadata sidecar (ADR 0025 Amendment (N4)).
const SidecarVersion = 2

// sidecarHead is the metadata sidecar's first part; the per-frame data, when the source has it, follows as "perFrame".
type sidecarHead struct {
	Version  int `json:"version"`
	Original struct {
		Fingerprint string `json:"fingerprint"`
		SHA256      string `json:"sha256,omitempty"`
		Size        int64  `json:"size"`
	} `json:"original"`
	Output struct {
		Fingerprint string `json:"fingerprint"`
		SHA256      string `json:"sha256"`
		Size        int64  `json:"size"`
	} `json:"output"`
	Camera struct {
		Make  string `json:"make,omitempty"`
		Model string `json:"model,omitempty"`
	} `json:"camera"`
	NotRestored  []string        `json:"notRestored"`
	ExpectedLost []string        `json:"expectedLost"`
	Exiftool     json.RawMessage `json:"exiftool"` // `exiftool -a -G1 -j` of the original (the object), null without exiftool
	FFprobe      json.RawMessage `json:"ffprobe"`
}

// writeSidecar writes the metadata sidecar of an output as gzip JSON: the source's static tags, what was not restored,
// the camera and, when perFrameGz names a file (exiftool -ee3 -j -G3 -a of the source, gzip), the per-frame data.
func writeSidecar(w io.Writer, h sidecarHead, perFrameGz string) error {
	h.Version = SidecarVersion
	if h.NotRestored == nil {
		h.NotRestored = []string{}
	}
	if h.ExpectedLost == nil {
		h.ExpectedLost = []string{}
	}
	if len(h.Exiftool) == 0 {
		h.Exiftool = json.RawMessage("null")
	}
	if len(h.FFprobe) == 0 {
		h.FFprobe = json.RawMessage("null")
	}
	head, err := json.Marshal(h)
	if err != nil {
		return err
	}
	zw := gzip.NewWriter(w)
	if perFrameGz == "" {
		_, err = zw.Write(head)
	} else {
		err = appendPerFrame(zw, head, perFrameGz)
	}
	if cerr := zw.Close(); err == nil {
		err = cerr
	}
	return err
}

// appendPerFrame writes head with `,"perFrame":<the decompressed file>` before its closing brace.
func appendPerFrame(w io.Writer, head []byte, perFrameGz string) error {
	if len(head) < 2 || head[len(head)-1] != '}' {
		return errors.New("sidecar head is not an object")
	}
	f, err := os.Open(perFrameGz)
	if err != nil {
		return err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer func() { _ = zr.Close() }()
	if _, err := w.Write(head[:len(head)-1]); err != nil {
		return err
	}
	if _, err := io.WriteString(w, `,"perFrame":`); err != nil {
		return err
	}
	// Our own exiftool output, already capped at 512 MiB when it was written.
	if _, err := io.CopyN(w, zr, 512<<20); err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	_, err = io.WriteString(w, "}")
	return err
}
