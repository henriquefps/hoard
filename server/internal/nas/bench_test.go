package nas

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"syscall"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/mounts"
	"github.com/henriquefps/hoard/server/internal/store"
)

// TestIndexLargeTree measures indexing a synthetic library (ADR 0014 "Measurements"). Opt-in:
//
//	HOARD_BENCH_FILES=20000 go test -run TestIndexLargeTree -v -timeout 30m ./internal/nas/
//
// Files are real headers (EXIF stills, ffmpeg clips) extended with holes to camera-like sizes, so they take little
// disk. HOARD_BENCH_DIR keeps the tree and database somewhere else than a temporary folder.
func TestIndexLargeTree(t *testing.T) {
	want, _ := strconv.Atoi(os.Getenv("HOARD_BENCH_FILES"))
	if want <= 0 {
		t.Skip("set HOARD_BENCH_FILES (e.g. 20000)")
	}
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is needed for the video clips")
	}
	base := os.Getenv("HOARD_BENCH_DIR")
	if base == "" {
		base = t.TempDir()
	}
	mnt := filepath.Join(base, "mnt")
	lib := filepath.Join(mnt, "library")
	if err := os.MkdirAll(lib, 0o700); err != nil {
		t.Fatal(err)
	}
	tmp := t.TempDir()
	mkclip := func(name string, args ...string) []byte {
		p := filepath.Join(tmp, name)
		cmd := exec.Command("ffmpeg", append(append([]string{"-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30:duration=2",
			"-pix_fmt", "yuv420p", "-c:v", "libx264", "-movflags", "+faststart"}, args...), p)...)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("ffmpeg %v %s", err, out)
		}
		b, _ := os.ReadFile(p)
		return b
	}
	mp4 := mkclip("v.mp4", "-metadata", "creation_time=2024-06-01T10:00:00Z")
	mov := mkclip("v.mov", "-metadata", "creation_time=2024-06-01T10:00:00Z")
	small := mkclip("s.mp4", "-t", "1")

	rng := rand.New(rand.NewPCG(1, 2))
	files, apparent := 0, int64(0)
	// sparse writes head, then extends the file to size with a hole.
	sparse := func(rel string, head []byte, size int64) {
		p := filepath.Join(lib, rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, head, 0o600); err != nil {
			t.Fatal(err)
		}
		if size > int64(len(head)) {
			if err := os.Truncate(p, size); err != nil {
				t.Fatal(err)
			}
		} else {
			size = int64(len(head))
		}
		files++
		apparent += size
	}
	// video keeps the clip valid: the hole and a unique tail live inside a trailing "free" box.
	video := func(rel string, clip []byte, size int64, unique uint64) {
		hdr := make([]byte, 8)
		binary.BigEndian.PutUint32(hdr, uint32(size-int64(len(clip)))) //nolint:gosec // sizes below 4 GiB
		copy(hdr[4:], "free")
		u := make([]byte, 8)
		binary.BigEndian.PutUint64(u, unique)
		sparse(rel, append(append(append([]byte{}, clip...), hdr...), u...), size)
	}
	mb := func(lo, hi int) int64 { return int64(lo+rng.IntN(hi-lo+1)) << 20 }
	start := time.Date(2019, 1, 1, 8, 0, 0, 0, time.UTC)
	kinds := map[string]int{}
	for i := 0; files < want; i++ {
		when := start.Add(time.Duration(i) * 17 * time.Minute)
		dir := when.Format("2006/2006-01-02")
		s := mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", Lens: "FE 35mm F1.8", DateTime: when.Format("2006:01:02 15:04:05"),
			Offset: "+01:00", Width: 7008, Height: 4672, Unique: uint64(i) + 1}
		switch r := rng.IntN(100); {
		case r < 40: // RAW + JPEG (a tenth with a sidecar)
			stem := fmt.Sprintf("DSC%05d", i%100000)
			sparse(dir+"/"+stem+".ARW", mediatest.RAW(s), mb(20, 60))
			s.Unique += 1 << 40
			sparse(dir+"/"+stem+".JPG", mediatest.JPEG(s, ""), mb(6, 14))
			if rng.IntN(10) == 0 {
				sparse(dir+"/"+stem+".ARW.xmp", fmt.Appendf(nil, "<x:xmpmeta id='%d'/>", i), 0)
			}
			kinds["pair"]++
		case r < 62: // JPEG
			sparse(fmt.Sprintf("%s/IMG_%05d.JPG", dir, i%100000), mediatest.JPEG(s, ""), mb(3, 9))
			kinds["photo"]++
		case r < 72: // HEIC
			sparse(fmt.Sprintf("%s/IMG_%05d.HEIC", dir, i%100000), mediatest.HEIF(s, ""), mb(2, 5))
			kinds["photo"]++
		case r < 82: // video
			name := fmt.Sprintf("%s/C%04d.MP4", dir, i%10000)
			clip := mp4
			if rng.IntN(2) == 0 {
				name, clip = fmt.Sprintf("%s/IMG_%05d.MOV", dir, i%100000), mov
			}
			video(name, clip, mb(30, 400), uint64(i))
			kinds["video"]++
		case r < 90: // live photo: HEIC + MOV
			stem := fmt.Sprintf("%s/IMG_%05d", dir, i%100000)
			sparse(stem+".HEIC", mediatest.HEIF(s, ""), mb(2, 4))
			video(stem+".MOV", mov, mb(2, 4), uint64(i))
			kinds["motion"]++
		case r < 95: // Google motion photo (the MP4 is real, so no hole)
			sparse(fmt.Sprintf("%s/PXL_%05d.MP.jpg", dir, i), mediatest.MotionJPEG(s, small), 0)
			kinds["motion"]++
		default: // macOS litter next to a photo
			sparse(fmt.Sprintf("%s/IMG_%05d.JPG", dir, i%100000), mediatest.JPEG(s, ""), mb(3, 9))
			sparse(fmt.Sprintf("%s/._IMG_%05d.JPG", dir, i%100000), []byte("AppleDouble"), 4096)
			kinds["photo"]++
		}
	}
	t.Logf("tree: %d files, %.1f GiB apparent, shots %v", files, float64(apparent)/(1<<30), kinds)

	st, err := store.Open(filepath.Join(base, "bench.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	roots, _ := mounts.New([]string{mnt})
	real, _ := roots.Resolve(lib)
	ctx := context.Background()
	folder, err := st.AddNasFolder(ctx, real, "library")
	if err != nil {
		t.Fatal(err)
	}
	ix := &Indexer{Store: st, Roots: roots, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Prober: media.NewProber("", 2, 30*time.Second)}
	run := func(label string) Stats {
		runtime.GC()
		t0 := time.Now()
		if err := ix.Folder(ctx, folder); err != nil {
			t.Fatal(err)
		}
		el := time.Since(t0)
		s := ix.LastStats()
		var ms runtime.MemStats
		runtime.ReadMemStats(&ms)
		var self, kids syscall.Rusage
		_ = syscall.Getrusage(syscall.RUSAGE_SELF, &self)
		_ = syscall.Getrusage(syscall.RUSAGE_CHILDREN, &kids)
		n, _ := st.ItemCount(ctx)
		t.Logf("%s: %.1fs, %+v; items %d; heap in use %.0f MiB, Go sys %.0f MiB, peak RSS %.0f MiB (ffprobe peak %.0f MiB); db %s",
			label, el.Seconds(), s, n, float64(ms.HeapInuse)/(1<<20), float64(ms.Sys)/(1<<20), float64(self.Maxrss)/1024,
			float64(kids.Maxrss)/1024, dbSize(base))
		return s
	}
	first := run("first walk")
	if first.Files == 0 || first.Hashed != first.Files {
		t.Fatal("nothing indexed")
	}
	if s := run("unchanged walk"); s.Hashed != 0 || s.Items != 0 {
		t.Fatalf("unchanged walk changed things: %+v", s)
	}
	// Move a month, delete a day, add a day.
	moved := 0
	months, _ := filepath.Glob(filepath.Join(lib, "2019", "2019-02-*"))
	for _, d := range months {
		entries, _ := os.ReadDir(d)
		moved += len(entries)
		if err := os.MkdirAll(filepath.Join(lib, "moved"), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.Rename(d, filepath.Join(lib, "moved", filepath.Base(d))); err != nil {
			t.Fatal(err)
		}
	}
	gone, _ := filepath.Glob(filepath.Join(lib, "2019", "2019-03-0[1-3]"))
	removed := 0
	for _, d := range gone {
		entries, _ := os.ReadDir(d)
		removed += len(entries)
		_ = os.RemoveAll(d)
	}
	for j := range 100 {
		s := mediatest.Shot{Make: "SONY", DateTime: "2030:01:01 10:00:00", Unique: 1<<50 + uint64(j)}
		sparse(fmt.Sprintf("2030/new/DSC%05d.JPG", j), mediatest.JPEG(s, ""), mb(3, 9))
	}
	s := run(fmt.Sprintf("after moving %d files, deleting %d and adding 100", moved, removed))
	if s.Hashed != moved+100 || s.MetaRead != 100 {
		t.Logf("note: hashed %d (moved files are hashed again at their new path, metadata is not read again)", s.Hashed)
	}
}

func dbSize(base string) string {
	var total int64
	for _, suffix := range []string{"", "-wal", "-shm"} {
		if fi, err := os.Stat(filepath.Join(base, "bench.db"+suffix)); err == nil {
			total += fi.Size()
		}
	}
	return fmt.Sprintf("%.1f MiB", float64(total)/(1<<20))
}
