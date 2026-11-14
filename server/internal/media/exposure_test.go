package media_test

import (
	"bytes"
	"testing"

	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/media/mediatest"
)

func TestReadExposure(t *testing.T) {
	shot := mediatest.Shot{Make: "SONY", Model: "ILCE-7M4", DateTime: "2024:05:01 10:00:00", Width: 6000, Height: 4000,
		ExposureTime: [2]uint32{1, 250}, FNumber: [2]uint32{18, 10}, FocalLength: [2]uint32{35, 1}, ISO: 400, Focal35: 35}
	for name, b := range map[string][]byte{"jpeg": mediatest.JPEG(shot, ""), "raw": mediatest.RAW(shot), "heif": mediatest.HEIF(shot, "")} {
		e, err := media.ReadExposure(bytes.NewReader(b), int64(len(b)))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if e.ExposureTime != 1.0/250 || e.FNumber != 1.8 || e.ISO != 400 || e.FocalLength != 35 || e.FocalLength35 != 35 || e.Bias != nil {
			t.Fatalf("%s: %+v", name, e)
		}
	}
	// No exposure tags: zero values, no error; not an image: ErrUnsupported.
	b := mediatest.JPEG(mediatest.Shot{Make: "X", DateTime: "2024:05:01 10:00:00"}, "")
	if e, err := media.ReadExposure(bytes.NewReader(b), int64(len(b))); err != nil || e != (media.Exposure{}) {
		t.Fatalf("empty: %+v %v", e, err)
	}
	if _, err := media.ReadExposure(bytes.NewReader([]byte("hello world, not a photo")), 24); err == nil {
		t.Fatal("text accepted")
	}
}
