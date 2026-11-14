package nas

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/mounts"
	"github.com/henriquefps/hoard/server/internal/store"
)

type itemView struct {
	ID       string `json:"id"`
	Rev      int64  `json:"rev"`
	Deleted  bool   `json:"deleted"`
	Replaced string `json:"replacedBy"`
	items.Data
}

type env struct {
	t      *testing.T
	st     *store.Store
	ix     *Indexer
	nas    string
	folder store.NasFolder
	blobs  map[string]string // sha256 -> path
}

func newEnv(t *testing.T) *env {
	t.Helper()
	base := t.TempDir()
	mnt := filepath.Join(base, "mnt")
	nasDir := filepath.Join(mnt, "nas")
	if err := os.MkdirAll(nasDir, 0o700); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(filepath.Join(base, "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	roots, _ := mounts.New([]string{mnt})
	real, err := roots.Resolve(nasDir)
	if err != nil {
		t.Fatal(err)
	}
	folder, err := st.AddNasFolder(context.Background(), real, "nas")
	if err != nil {
		t.Fatal(err)
	}
	e := &env{t: t, st: st, nas: real, folder: folder, blobs: map[string]string{}}
	e.ix = &Indexer{Store: st, Roots: roots, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Prober: media.NewProber("", 2, 20*time.Second),
		OpenBlob: func(sha string) (*os.File, error) {
			return os.Open(e.blobs[sha])
		}}
	return e
}

func (e *env) write(rel string, b []byte) {
	e.t.Helper()
	write(e.t, filepath.Join(e.nas, rel), b)
}

func (e *env) walk() Stats {
	e.t.Helper()
	if err := e.ix.Folder(context.Background(), e.folder); err != nil {
		e.t.Fatal(err)
	}
	return e.ix.LastStats()
}

// live returns the live items keyed by main file name (or main fingerprint for upload-only items).
func (e *env) live() map[string]itemView {
	e.t.Helper()
	rows, _, err := e.st.ItemChanges(context.Background(), 0, 10000)
	if err != nil {
		e.t.Fatal(err)
	}
	out := map[string]itemView{}
	for _, r := range rows {
		if r.Deleted {
			continue
		}
		var v itemView
		if err := json.Unmarshal(r.Data, &v); err != nil {
			e.t.Fatal(err)
		}
		v.ID, v.Rev = r.ID, r.Rev
		name := v.Files[0].Name
		if name == "" {
			name = v.MainFingerprint
		}
		out[name] = v
	}
	return out
}

func (e *env) item(id string) itemView {
	e.t.Helper()
	r, err := e.st.Item(context.Background(), id)
	if err != nil {
		e.t.Fatal(err)
	}
	var v itemView
	_ = json.Unmarshal(r.Data, &v)
	v.ID, v.Rev, v.Deleted = r.ID, r.Rev, r.Deleted
	return v
}

func shotAt(unique uint64, dt string) mediatest.Shot {
	return mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", DateTime: dt, SubSec: "5", Offset: "-03:00", Width: 6000, Height: 4000, Unique: unique}
}

func clip(t *testing.T, args ...string) []byte {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return mediatest.MiniMP4(uint64(len(args)))
	}
	p := filepath.Join(t.TempDir(), "c.mov")
	cmd := exec.Command("ffmpeg", append(append([]string{"-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=30:duration=1",
		"-pix_fmt", "yuv420p", "-c:v", "libx264", "-movflags", "use_metadata_tags"}, args...), p)...)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v %s", err, out)
	}
	b, _ := os.ReadFile(p)
	return b
}

func TestItemsFromAFolder(t *testing.T) {
	e := newEnv(t)
	ffmpeg := e.ix.Prober.Available()
	e.write("2024/DSC00001.ARW", mediatest.RAW(shotAt(1, "2024:05:01 10:00:00")))
	e.write("2024/dsc00001.jpg", mediatest.JPEG(shotAt(2, "2024:05:01 10:00:00"), ""))
	e.write("2024/DSC00001.ARW.xmp", []byte("<x:xmpmeta/>"))
	e.write("2024/DSC00002.JPG", mediatest.JPEG(shotAt(3, "2024:05:01 11:00:00"), ""))
	e.write("2024/DSC00002.HEIC", mediatest.HEIF(shotAt(4, "2024:05:01 11:00:00"), ""))
	e.write("2024/DSC00003.ARW", mediatest.RAW(shotAt(5, "2024:05:01 12:00:00")))
	e.write("2024/._DSC00003.ARW", []byte("apple double"))
	e.write("2024/.DS_Store", []byte("x"))
	e.write("2024/orphan.xmp", []byte("<x:xmpmeta/>"))
	e.write("phone/IMG_0001.HEIC", mediatest.HEIF(shotAt(6, "2024:06:01 09:00:00"), ""))
	e.write("phone/IMG_0001.MOV", clip(t, "-metadata", "com.apple.quicktime.creationdate=2024-06-01T09:00:00-0300"))
	e.write("phone/PXL_0002.MP.jpg", mediatest.MotionJPEG(shotAt(7, "2024:06:02 09:00:00"), clip(t, "-metadata", "creation_time=2024-06-02T12:00:00Z")))
	e.write("phone/20240603_090000.jpg", mediatest.SamsungMotionJPEG(shotAt(8, "2024:06:03 09:00:00"), clip(t, "-t", "0.5")))
	e.write("phone/VID_0004.mp4", clip(t, "-metadata", "creation_time=2024-06-04T12:00:00Z", "-metadata", "location=+35.6586+139.7454/"))
	e.write("@eaDir/DSC00002.JPG/SYNOPHOTO_THUMB_S.jpg", mediatest.JPEG(shotAt(9, "2024:05:01 11:00:00"), ""))
	e.write("2024/notes.txt", []byte("not a photo"))

	s := e.walk()
	if s.Files != 13 || s.Hashed != 13 || s.MetaRead != 12 {
		t.Fatalf("stats %+v", s)
	}
	got := e.live()
	if len(got) != 7 {
		names := []string{}
		for k := range got {
			names = append(names, k)
		}
		t.Fatalf("want 7 items, got %v", names)
	}
	pair := got["DSC00001.ARW"]
	if pair.Kind != items.KindPair || len(pair.Files) != 3 || pair.Files[0].Role != "raw" || pair.Files[1].Name != "dsc00001.jpg" ||
		pair.Files[2].Role != "sidecar" || pair.Files[0].Locations[0].Path != "2024/DSC00001.ARW" || pair.Width != 6000 {
		t.Fatalf("pair %+v", pair)
	}
	if c := pair.Capture; c == nil || c.Offset != "-03:00" || c.Local != "2024-05-01T10:00:00.500" || *c.UTC != time.Date(2024, 5, 1, 13, 0, 0, 5e8, time.UTC).UnixMilli() {
		t.Fatalf("pair capture %+v", pair.Capture)
	}
	if pair.ID != items.ID(pair.MainFingerprint) || pair.MainFingerprint != pair.Files[0].Fingerprint {
		t.Fatal("item id is not derived from the main fingerprint")
	}
	two := got["DSC00002.JPG"]
	if two.Kind != items.KindPhoto || len(two.Files) != 2 || two.Files[1].Format != "heif" {
		t.Fatalf("jpeg+heif %+v", two)
	}
	if got["DSC00003.ARW"].Kind != items.KindPhoto || len(got["DSC00003.ARW"].Files) != 1 {
		t.Fatalf("raw only %+v", got["DSC00003.ARW"])
	}
	live := got["IMG_0001.HEIC"]
	if live.Kind != items.KindMotion || live.Motion == nil || live.Motion.Source != "file" || live.Files[1].Role != "video" {
		t.Fatalf("live photo %+v", live)
	}
	mp := got["PXL_0002.MP.jpg"]
	if mp.Kind != items.KindMotion || mp.Motion.Source != "embedded" || mp.Motion.Format != "google" || len(mp.Files) != 1 {
		t.Fatalf("motion photo %+v", mp)
	}
	if sm := got["20240603_090000.jpg"]; sm.Kind != items.KindMotion || sm.Motion.Format != "samsung" {
		t.Fatalf("samsung %+v", sm)
	}
	vid := got["VID_0004.mp4"]
	if vid.Kind != items.KindVideo {
		t.Fatalf("video %+v", vid)
	}
	if ffmpeg {
		if vid.Video == nil || vid.Video.Codec != "h264" || vid.Video.FPS != 30 || vid.GPS == nil || vid.Capture.Source != "quicktime" ||
			*vid.Capture.UTC != time.Date(2024, 6, 4, 12, 0, 0, 0, time.UTC).UnixMilli() || vid.Width != 64 {
			t.Fatalf("video metadata %+v %+v", vid, vid.Video)
		}
		if live.Video == nil || live.Video.DurationMs < 900 || mp.Video == nil || mp.Video.Codec != "h264" {
			t.Fatalf("motion video metadata %+v / %+v", live.Video, mp.Video)
		}
	}
	// The NAS feed (Reveal) still lists photos only.
	files, _, _ := e.st.NasChanges(context.Background(), 0, 100)
	for _, f := range files {
		if m, _ := media.Classify(f.Path); m != media.MediaImage {
			t.Fatalf("the NAS feed lists %s", f.Path)
		}
	}
	if len(files) != 9 {
		t.Fatalf("NAS feed has %d photos", len(files))
	}

	// An unchanged walk hashes nothing, reads nothing, writes no item.
	if s := e.walk(); s.Hashed != 0 || s.MetaRead != 0 || s.Items != 0 || s.Sources != 0 {
		t.Fatalf("unchanged walk %+v", s)
	}

	// Move: same content, new path → same item id, new location, new revision.
	if err := os.MkdirAll(filepath.Join(e.nas, "moved"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(e.nas, "2024/DSC00003.ARW"), filepath.Join(e.nas, "moved/DSC00003.ARW")); err != nil {
		t.Fatal(err)
	}
	before := got["DSC00003.ARW"]
	s = e.walk()
	moved := e.item(before.ID)
	if moved.Deleted || moved.Rev <= before.Rev || moved.Files[0].Locations[0].Path != "moved/DSC00003.ARW" || len(moved.Files[0].Locations) != 1 {
		t.Fatalf("moved %+v (stats %+v)", moved, s)
	}
	if s.MetaRead != 0 {
		t.Fatalf("a move read metadata again: %+v", s)
	}

	// A copy in another directory: one item, two locations.
	e.write("backup/DSC00003.ARW", mediatest.RAW(shotAt(5, "2024:05:01 12:00:00")))
	e.walk()
	if c := e.item(before.ID); len(c.Files[0].Locations) != 2 {
		t.Fatalf("copy %+v", c.Files[0].Locations)
	}
	if err := os.Remove(filepath.Join(e.nas, "backup/DSC00003.ARW")); err != nil {
		t.Fatal(err)
	}

	// The JPEG of a RAW-only shot appears: still one item (the RAW's), now a pair.
	e.write("moved/DSC00003.JPG", mediatest.JPEG(shotAt(10, "2024:05:01 12:00:00"), ""))
	e.walk()
	if p := e.item(before.ID); p.Kind != items.KindPair || len(p.Files) != 2 {
		t.Fatalf("raw + new jpeg %+v", p)
	}

	// The RAW of a JPEG-only shot appears: the JPEG item is replaced by the pair.
	jpegOnly := got["DSC00002.JPG"]
	e.write("2024/DSC00002.ARW", mediatest.RAW(shotAt(11, "2024:05:01 11:00:00")))
	e.walk()
	gone := e.item(jpegOnly.ID)
	now := e.live()["DSC00002.ARW"]
	if !gone.Deleted || gone.Replaced != now.ID || now.Kind != items.KindPair || len(now.Files) != 3 {
		t.Fatalf("replaced %+v by %+v", gone, now)
	}

	// Edited in place: a new fingerprint is a new shot; the old item is a tombstone.
	orphanSidecarItem := got["PXL_0002.MP.jpg"]
	e.write("phone/PXL_0002.MP.jpg", mediatest.JPEG(shotAt(12, "2024:06:02 09:00:00"), ""))
	future := time.Now().Add(time.Hour)
	_ = os.Chtimes(filepath.Join(e.nas, "phone/PXL_0002.MP.jpg"), future, future)
	s = e.walk()
	if old := e.item(orphanSidecarItem.ID); !old.Deleted {
		t.Fatalf("rewritten file kept its old item: %+v", old)
	}
	if n := e.live()["PXL_0002.MP.jpg"]; n.ID == orphanSidecarItem.ID || n.Kind != items.KindPhoto {
		t.Fatalf("rewritten %+v", n)
	}

	// Deleted: tombstone.
	if err := os.Remove(filepath.Join(e.nas, "phone/VID_0004.mp4")); err != nil {
		t.Fatal(err)
	}
	e.walk()
	if v := e.item(vid.ID); !v.Deleted {
		t.Fatalf("deleted video %+v", v)
	}

	// Lookup by any member's fingerprint.
	m, err := e.st.ItemFilesByFingerprint(context.Background(), []string{pair.Files[1].Fingerprint, pair.Files[2].Fingerprint})
	if err != nil || len(m) != 2 || m[0].ItemID != pair.ID {
		t.Fatalf("lookup %+v %v", m, err)
	}

	// The folder stops being indexed: every item goes.
	if err := e.st.RemoveNasFolder(context.Background(), e.folder.ID); err != nil {
		t.Fatal(err)
	}
	e.ix.All(context.Background())
	if left := e.live(); len(left) != 0 {
		t.Fatalf("items left after the folder was removed: %d", len(left))
	}
}

func TestItemsFromUploads(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	raw := mediatest.RAW(shotAt(1, "2024:05:01 10:00:00"))
	jpg := mediatest.JPEG(shotAt(2, "2024:05:01 10:00:00"), "")
	other := mediatest.JPEG(shotAt(3, "2024:05:02 10:00:00"), "")
	upload := func(b []byte) string {
		sha := sha256hex(b)
		p := filepath.Join(t.TempDir(), sha)
		if err := os.WriteFile(p, b, 0o600); err != nil {
			t.Fatal(err)
		}
		e.blobs[sha] = p
		if err := e.st.AddBlob(ctx, store.Blob{SHA256: sha, Size: int64(len(b)), Kind: store.BlobOriginal}, fingerprint.Bytes(b), ""); err != nil {
			t.Fatal(err)
		}
		return sha
	}
	upload(jpg)
	upload(other)
	e.ix.Backup(ctx)
	got := e.live()
	if len(got) != 2 {
		t.Fatalf("uploads: %d items", len(got))
	}
	jpgItem := got[fingerprint.Bytes(jpg)]
	if jpgItem.Kind != items.KindPhoto || jpgItem.Files[0].Locations[0].Source != "backup" || jpgItem.Capture.Source != "exif" {
		t.Fatalf("upload item %+v", jpgItem)
	}
	// The NAS has the pair: the uploaded JPEG joins the NAS item, its own item is replaced.
	e.write("DSC00001.ARW", raw)
	e.write("DSC00001.JPG", jpg)
	e.ix.All(ctx)
	got = e.live()
	pair := got["DSC00001.ARW"]
	if len(got) != 2 || pair.Kind != items.KindPair || len(pair.Files[1].Locations) != 2 || pair.Files[1].Locations[1].Source != "backup" {
		t.Fatalf("pair with an uploaded member %+v", pair)
	}
	if old := e.item(jpgItem.ID); !old.Deleted || old.Replaced != pair.ID {
		t.Fatalf("upload item %+v", old)
	}
	// The NAS copy goes: the upload is an item of its own again (same id as before).
	_ = os.Remove(filepath.Join(e.nas, "DSC00001.ARW"))
	_ = os.Remove(filepath.Join(e.nas, "DSC00001.JPG"))
	e.ix.All(ctx)
	if back := e.item(jpgItem.ID); back.Deleted || back.Files[0].Locations[0].Source != "backup" {
		t.Fatalf("upload back on its own %+v", back)
	}
	if p := e.item(pair.ID); !p.Deleted {
		t.Fatalf("pair without NAS files %+v", p)
	}
}

func sha256hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
