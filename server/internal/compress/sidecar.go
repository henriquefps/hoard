package compress

import (
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
)

// perFrameCap bounds the per-frame metadata (exiftool -ee3 of the source, uncompressed): over it the sidecar is not
// written, the item fails verify_metadata and the original stays. A variable for the tests.
var perFrameCap int64 = 512 << 20

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
	// Our own exiftool output, refused over perFrameCap when it was written; a file past it is not cut short here.
	n, err := io.CopyN(w, zr, perFrameCap+1)
	if err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	if n > perFrameCap {
		return fmt.Errorf("the per-frame metadata is over %d MiB: %w", perFrameCap>>20, errCapped)
	}
	_, err = io.WriteString(w, "}")
	return err
}

// checkGzipJSON reads a gzip file through and checks that it holds exactly one well-formed JSON value, without keeping
// it in memory (the per-frame data can be hundreds of MiB).
func checkGzipJSON(p string) error {
	f, err := os.Open(p)
	if err != nil {
		return err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer func() { _ = zr.Close() }()
	return checkJSON(zr)
}

// checkJSON checks that r holds exactly one well-formed JSON value (whitespace around it allowed).
func checkJSON(r io.Reader) error {
	dec := json.NewDecoder(r)
	depth := 0
	for {
		tok, err := dec.Token()
		if err != nil {
			if errors.Is(err, io.EOF) {
				err = io.ErrUnexpectedEOF
			}
			return fmt.Errorf("not valid JSON: %w", err)
		}
		if d, ok := tok.(json.Delim); ok {
			if d == '[' || d == '{' {
				depth++
			} else {
				depth--
			}
		}
		if depth == 0 {
			break
		}
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return errors.New("not valid JSON: data after the value")
	}
	return nil
}
