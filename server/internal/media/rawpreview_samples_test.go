package media

import (
	"os"
	"path/filepath"
	"testing"
)

// TestRawPreviewsRealSamples lists the embedded JPEGs of real camera files (opt-in: HOARD_SAMPLES_DIR, never
// committed).
func TestRawPreviewsRealSamples(t *testing.T) {
	dir := os.Getenv("HOARD_SAMPLES_DIR")
	if dir == "" {
		t.Skip("HOARD_SAMPLES_DIR not set")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if _, role := Classify(e.Name()); role != RoleRaw {
			continue
		}
		f, err := os.Open(filepath.Join(dir, e.Name()))
		if err != nil {
			t.Fatal(err)
		}
		fi, _ := f.Stat()
		p := RawPreviews(f, fi.Size())
		m, _ := ReadImage(f, fi.Size())
		f.Close()
		t.Logf("%s: %dx%d orientation %d previews %v", e.Name(), m.Width, m.Height, m.Orientation, p)
		if len(p) == 0 {
			t.Errorf("%s: no embedded preview", e.Name())
		}
	}
}
