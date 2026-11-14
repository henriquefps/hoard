package edits

import (
	"os"
	"testing"
)

// BenchmarkCheck measures Check on a real render (opt-in: HOARD_EDIT_BENCH=<file>, a JPEG or WebP; ADR 0020
// "Measurements").
func BenchmarkCheck(b *testing.B) {
	p := os.Getenv("HOARD_EDIT_BENCH")
	if p == "" {
		b.Skip("set HOARD_EDIT_BENCH")
	}
	body, err := os.ReadFile(p)
	if err != nil {
		b.Fatal(err)
	}
	ct := "image/jpeg"
	if len(body) > 12 && string(body[8:12]) == "WEBP" {
		ct = "image/webp"
	}
	b.SetBytes(int64(len(body)))
	for b.Loop() {
		if _, err := Check(body, ct); err != nil {
			b.Fatal(err)
		}
	}
}
