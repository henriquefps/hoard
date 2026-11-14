package ratelimit

import (
	"testing"
	"time"
)

func TestBucket(t *testing.T) {
	now := time.Unix(0, 0)
	l := New(1, 3)
	l.Now = func() time.Time { return now }
	for i := range 3 {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatalf("burst %d", i)
		}
	}
	ok, wait := l.Allow("a")
	if ok || wait <= 0 || wait > time.Second {
		t.Fatalf("over the burst: %v %v", ok, wait)
	}
	if ok, _ := l.Allow("b"); !ok {
		t.Fatal("keys are independent")
	}
	now = now.Add(time.Second)
	if ok, _ := l.Allow("a"); !ok {
		t.Fatal("refilled")
	}
	now = now.Add(time.Hour)
	l.Allow("c")
	if len(l.buckets) != 1 {
		t.Fatalf("sweep left %d buckets", len(l.buckets))
	}
}
