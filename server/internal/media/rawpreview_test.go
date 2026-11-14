package media_test

import (
	"bytes"
	"encoding/binary"
	"testing"

	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
)

// rawWithPreviews builds a TIFF-based RAW whose IFD0 points at one JPEG (JPEGInterchangeFormat) and whose SubIFD
// holds another as a single strip (compression 7), both appended after the TIFF stream.
func rawWithPreviews(t *testing.T, a, b []byte, orientation uint16) []byte {
	t.Helper()
	build := func(offA, offB uint32) []byte {
		d := &mediatest.IFD{Tags: []mediatest.Tag{
			{ID: 0x0112, Val: []uint16{orientation}},
			{ID: 0x0201, Val: []uint32{offA}}, {ID: 0x0202, Val: []uint32{uint32(len(a))}},
		}}
		d.Subs = []*mediatest.IFD{{Tags: []mediatest.Tag{
			{ID: 0x0103, Val: []uint16{7}},
			{ID: 0x0111, Val: []uint32{offB}}, {ID: 0x0117, Val: []uint32{uint32(len(b))}},
		}}}
		return mediatest.TIFF(d, binary.LittleEndian, 42)
	}
	head := build(1, 1)
	offA := uint32(len(head))
	head = build(offA, offA+uint32(len(a)))
	if uint32(len(head)) != offA {
		t.Fatal("TIFF size changed")
	}
	return append(append(head, a...), b...)
}

// padded makes a synthetic JPEG long enough to count as a preview.
func padded(s mediatest.Shot) []byte {
	j := mediatest.JPEG(s, "")
	return append(j[:len(j)-2], append(bytes.Repeat([]byte{0}, 2048), 0xFF, 0xD9)...)
}

func TestRawPreviewsTIFF(t *testing.T) {
	small := padded(mediatest.Shot{Width: 1616, Height: 1080, Unique: 1})
	full := padded(mediatest.Shot{Width: 6000, Height: 4000, Unique: 2})
	raw := rawWithPreviews(t, small, full, 6)
	got := media.RawPreviews(bytes.NewReader(raw), int64(len(raw)))
	if len(got) != 2 || got[0].Width != 6000 || got[0].Height != 4000 || got[1].Width != 1616 {
		t.Fatalf("previews = %+v", got)
	}
	if !bytes.Equal(raw[got[0].Offset:got[0].Offset+got[0].Length], full) {
		t.Fatal("largest preview: wrong bytes")
	}
	if got[0].LongEdge() != 6000 {
		t.Fatal("long edge")
	}
}

func TestRawPreviewsRejectsLosslessAndBadOffsets(t *testing.T) {
	lossless := padded(mediatest.Shot{Width: 6000, Height: 4000, Unique: 3})
	i := bytes.Index(lossless, []byte{0xFF, 0xC0})
	lossless[i+1] = 0xC3 // a DNG's raw data: lossless JPEG
	preview := padded(mediatest.Shot{Width: 1024, Height: 768, Unique: 4})
	raw := rawWithPreviews(t, preview, lossless, 1)
	got := media.RawPreviews(bytes.NewReader(raw), int64(len(raw)))
	if len(got) != 1 || got[0].Width != 1024 {
		t.Fatalf("previews = %+v", got)
	}
	// Truncated files: never a panic, never a preview past the end.
	for n := 0; n < len(raw); n += 37 {
		for _, p := range media.RawPreviews(bytes.NewReader(raw[:n]), int64(n)) {
			if p.Offset+p.Length > int64(n) {
				t.Fatalf("preview past the end at %d", n)
			}
		}
	}
	if got := media.RawPreviews(bytes.NewReader([]byte("not a raw")), 9); len(got) != 0 {
		t.Fatalf("garbage: %+v", got)
	}
}

func TestRawPreviewsRAF(t *testing.T) {
	jpeg := padded(mediatest.Shot{Width: 1920, Height: 1280, Unique: 5})
	head := make([]byte, 160)
	copy(head, "FUJIFILMCCD-RAW 0201FF383501")
	binary.BigEndian.PutUint32(head[84:], 160)
	binary.BigEndian.PutUint32(head[88:], uint32(len(jpeg)))
	raf := append(head, jpeg...)
	got := media.RawPreviews(bytes.NewReader(raf), int64(len(raf)))
	if len(got) != 1 || got[0].Offset != 160 || got[0].Width != 1920 {
		t.Fatalf("previews = %+v", got)
	}
}

func TestWithOrientation(t *testing.T) {
	src := mediatest.JPEG(mediatest.Shot{Width: 64, Height: 32, Orientation: 3, Make: "Synthetic", Unique: 6}, "")
	out := media.WithOrientation(src, 6)
	m, err := media.ReadImage(bytes.NewReader(out), int64(len(out)))
	if err != nil {
		t.Fatal(err)
	}
	if m.Orientation != 6 || m.Make != "" || m.Width != 64 || m.Height != 32 {
		t.Fatalf("meta = %+v (the old EXIF must be gone, the frame kept)", m)
	}
	if w, h, ok := media.JPEGSize(bytes.NewReader(out), int64(len(out))); !ok || w != 64 || h != 32 {
		t.Fatalf("JPEGSize = %d %d %v", w, h, ok)
	}
	if got := media.WithOrientation([]byte("nope"), 6); string(got) != "nope" {
		t.Fatal("not a JPEG: unchanged")
	}
}
