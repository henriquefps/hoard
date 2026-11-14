package previews

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
)

// TestPlanFull: the full-resolution job exists only for stills a browser cannot show (a lone RAW, a HEIF); a JPEG
// photo and a pair with its camera JPEG are shown as they are (FullOriginal), videos have none.
func TestPlanFull(t *testing.T) {
	file := func(fp, role, name, format string) items.File {
		return items.File{Fingerprint: fp, Role: role, Name: name, Format: format}
	}
	cases := []struct {
		name     string
		d        items.Data
		full     bool
		original string
	}{
		{"jpeg photo", items.Data{Kind: items.KindPhoto, MainFingerprint: "a", Files: []items.File{file("a", media.RoleRendered, "A.JPG", "jpeg")}}, false, "a"},
		{"pair", items.Data{Kind: items.KindPair, MainFingerprint: "r", Width: 6000, Height: 4000, Files: []items.File{
			file("r", media.RoleRaw, "A.ARW", "tiff"), file("h", media.RoleRendered, "A.HEIC", "heif"), file("j", media.RoleRendered, "A.JPG", "jpeg"),
			file("x", media.RoleSidecar, "A.xmp", "")}}, false, "j"},
		{"heif photo", items.Data{Kind: items.KindPhoto, MainFingerprint: "h", Files: []items.File{file("h", media.RoleRendered, "A.HEIC", "heif")}}, true, ""},
		{"lone raw", items.Data{Kind: items.KindPhoto, MainFingerprint: "r", Width: 6000, Height: 4000, Orientation: 6,
			Files: []items.File{file("r", media.RoleRaw, "A.ARW", "tiff")}}, true, ""},
		{"video", items.Data{Kind: items.KindVideo, MainFingerprint: "v", Files: []items.File{file("v", media.RoleVideo, "A.MP4", "mp4")}}, false, ""},
	}
	for _, c := range cases {
		plan := Plan("id", c.d)
		j, ok := plan[JobFull]
		if ok != c.full {
			t.Errorf("%s: full job %v, want %v", c.name, ok, c.full)
		}
		if ok && (j.Source != plan[JobImage].Source || j.Key == plan[JobImage].Key) {
			t.Errorf("%s: full job reads %+v (image %+v), key %s", c.name, j.Source, plan[JobImage].Source, j.Key)
		}
		got := ""
		if f := FullOriginal(c.d); f != nil {
			got = f.Fingerprint
		}
		if got != c.original {
			t.Errorf("%s: original %q, want %q", c.name, got, c.original)
		}
	}
	// The image job's key does not change with the full job (no preview is made again on upgrade).
	raw := cases[3].d
	if Plan("id", raw)[JobImage].Key != Key(JobImage, Source{Fingerprint: "r", Role: media.RoleRaw, Format: "tiff", Orientation: 6}) {
		t.Fatal("image key changed")
	}
}

// TestCPUFull: a RAW whose embedded JPEG is full size gives that JPEG at full size (upright); a RAW LibRaw cannot read
// with an embedded JPEG larger than the screen preview falls back to it; a smaller one is unsupported.
func TestCPUFull(t *testing.T) {
	c := testCPU(t, false)
	full := func(t *testing.T, embW, embH, longEdge, orientation int) (Result, string, error) {
		t.Helper()
		dir := t.TempDir()
		preview, err := os.ReadFile(testJPEG(t, c, dir, embW, embH))
		if err != nil {
			t.Fatal(err)
		}
		raw := filepath.Join(dir, "shot.arw")
		if err := os.WriteFile(raw, syntheticRAW(preview), 0o600); err != nil {
			t.Fatal(err)
		}
		f, err := os.Open(raw)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		out := t.TempDir()
		res, err := c.Generate(context.Background(), Job{Job: JobFull, Source: Source{Role: media.RoleRaw, Format: "tiff",
			Orientation: orientation, LongEdge: longEdge}}, f, out)
		return res, out, err
	}
	res, out, err := full(t, 3000, 2000, 3000, 8)
	if err != nil {
		t.Fatal(err)
	}
	if o := res[FileFull]; o.Width != 2000 || o.Height != 3000 || len(res) != 1 {
		t.Fatalf("full-size embedded: %+v", res)
	}
	if entries, _ := os.ReadDir(out); len(entries) != 1 {
		t.Fatalf("left behind: %d files", len(entries))
	}
	// Embedded 3000 px of a 6000 px RAW: LibRaw first (it cannot read this synthetic file), then the embedded JPEG.
	res, _, err = full(t, 3000, 2000, 6000, 1)
	if err != nil || res[FileFull].Width != 3000 {
		t.Fatalf("fallback: %+v %v", res, err)
	}
	// Embedded 1616 px (Sony) and LibRaw fails: nothing sharper than the screen preview.
	if _, _, err = full(t, 1616, 1080, 6000, 1); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("small embedded: %v", err)
	}
}

// TestCPUFullHEIF converts a HEIF rendering to a full-size JPEG.
func TestCPUFullHEIF(t *testing.T) {
	c := testCPU(t, false)
	if !hasLoader(t, c, "heifload") {
		t.Skip("libvips without HEIF")
	}
	dir := t.TempDir()
	heic := filepath.Join(dir, "a.heic")
	src := testJPEG(t, c, dir, 1200, 800)
	if b, err := exec.Command(c.vips, "copy", src, heic+"[Q=60]").CombinedOutput(); err != nil {
		t.Skipf("no HEIF encoder: %v %s", err, b)
	}
	res, _ := generate(t, c, Job{Job: JobFull, Source: Source{Role: media.RoleRendered, Format: "heif"}}, heic)
	if o := res[FileFull]; o.Width != 1200 || o.Height != 800 {
		t.Fatalf("heif full: %+v", res)
	}
}

// TestCPUFullRealSamples renders every RAW of HOARD_SAMPLES_DIR at full resolution and logs the size and time (opt-in;
// HOARD_PREVIEW_OUT keeps the files).
func TestCPUFullRealSamples(t *testing.T) {
	dir := os.Getenv("HOARD_SAMPLES_DIR")
	if dir == "" {
		t.Skip("HOARD_SAMPLES_DIR not set")
	}
	c := testCPU(t, false)
	keep := os.Getenv("HOARD_PREVIEW_OUT")
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if _, role := media.Classify(e.Name()); role != media.RoleRaw {
			continue
		}
		p := filepath.Join(dir, e.Name())
		f, _ := os.Open(p)
		fi, _ := f.Stat()
		m, _ := media.ReadImage(f, fi.Size())
		emb := media.RawPreviews(f, fi.Size())
		f.Close()
		j := Job{Job: JobFull, Source: Source{Role: media.RoleRaw, Format: m.Format, Orientation: m.Orientation, LongEdge: max(m.Width, m.Height)}}
		start := time.Now()
		res, out := generate(t, c, j, p)
		embedded := 0
		if len(emb) > 0 {
			embedded = emb[0].LongEdge()
		}
		t.Logf("%s: RAW %dx%d, embedded %d px → full %dx%d, %.1f MB in %v", e.Name(), m.Width, m.Height, embedded,
			res[FileFull].Width, res[FileFull].Height, float64(res[FileFull].Bytes)/1e6, time.Since(start).Round(time.Millisecond))
		if keep != "" {
			b, _ := os.ReadFile(filepath.Join(out, FileFull))
			_ = os.WriteFile(filepath.Join(keep, e.Name()+"."+FileFull), b, 0o600)
		}
	}
}
