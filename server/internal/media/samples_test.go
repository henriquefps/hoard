package media

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// TestRealSamples reads every file of HOARD_SAMPLES_DIR (opt-in: real camera files, e.g. CC0 samples from
// raw.pixls.us, never committed) and fails when a still yields no camera, capture time or size.
func TestRealSamples(t *testing.T) {
	dir := os.Getenv("HOARD_SAMPLES_DIR")
	if dir == "" {
		t.Skip("set HOARD_SAMPLES_DIR to a folder of real photos")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if m, _ := Classify(e.Name()); m != MediaImage {
			continue
		}
		f, err := os.Open(filepath.Join(dir, e.Name()))
		if err != nil {
			t.Fatal(err)
		}
		fi, _ := f.Stat()
		m, err := ReadImage(f, fi.Size())
		f.Close()
		js, _ := json.Marshal(m)
		t.Logf("%s: %s", e.Name(), js)
		if err != nil || m.Make == "" || m.Capture == nil || m.Width == 0 {
			t.Errorf("%s: incomplete (%v)", e.Name(), err)
		}
	}
}
