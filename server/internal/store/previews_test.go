package store

import (
	"context"
	"path/filepath"
	"testing"
)

func TestPreviewQueue(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ctx := context.Background()
	want := func(id, key string, prio int, order int64) int {
		t.Helper()
		n, err := s.WantPreviews(ctx, []PreviewWant{{ItemID: id, Job: "image", WantKey: key, Priority: prio, OrderKey: order}})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	lease := func() Preview {
		t.Helper()
		p, ok, err := s.LeasePreview(ctx, "image")
		if err != nil || !ok {
			t.Fatalf("lease: %v %v", ok, err)
		}
		return p
	}
	// Backlog: newest shot first; a waiting client jumps ahead; the same want twice changes nothing.
	want("a", "ka", PriorityBacklog, 100)
	want("b", "kb", PriorityBacklog, 200)
	want("c", "kc", PriorityRecent, 50)
	if n := want("a", "ka", PriorityBacklog, 100); n != 0 {
		t.Fatalf("same want changed %d rows", n)
	}
	want("a", "ka", PriorityWaiting, 1)
	if p := lease(); p.ItemID != "a" || p.Attempts != 1 || p.State != PreviewRunning {
		t.Fatalf("first %+v", p)
	}
	if p := lease(); p.ItemID != "c" {
		t.Fatalf("second %+v (recent before backlog)", p)
	}
	if p := lease(); p.ItemID != "b" {
		t.Fatalf("third %+v", p)
	}
	if _, ok, _ := s.LeasePreview(ctx, "image"); ok {
		t.Fatal("nothing left")
	}

	// Finish a: done; its files' key recorded.
	if old, err := s.FinishPreview(ctx, "a", "image", "ka", []byte(`{}`), 10, "cpu", 5); err != nil || old != "" {
		t.Fatalf("finish %q %v", old, err)
	}
	rows, _ := s.Previews(ctx, "a")
	if r := rows["image"]; r.State != PreviewDone || r.Key != "ka" || r.Bytes != 10 {
		t.Fatalf("done row %+v", r)
	}
	// A new key (recipe/source change): pending again, the old files still named until the new ones replace them.
	if n := want("a", "ka2", PriorityBacklog, 100); n != 1 {
		t.Fatal("new key not queued")
	}
	rows, _ = s.Previews(ctx, "a")
	if r := rows["image"]; r.State != PreviewPending || r.Key != "ka" || r.WantKey != "ka2" {
		t.Fatalf("stale row %+v", r)
	}
	lease()
	old, err := s.FinishPreview(ctx, "a", "image", "ka2", []byte(`{}`), 11, "cpu", 5)
	if err != nil || old != "ka" {
		t.Fatalf("replaced %q %v", old, err)
	}

	// Failures: retry with a delay, then failed; a new key forgets the failure.
	if err := s.RetryPreview(ctx, "b", "image", "source_unavailable", s.nowMs()+60_000); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := s.LeasePreview(ctx, "image"); ok {
		t.Fatal("a delayed job ran early")
	}
	if at, _ := s.NextPreviewAt(ctx, "image"); at == 0 {
		t.Fatal("next run time")
	}
	if err := s.FailPreview(ctx, "c", "image", "unsupported: x"); err != nil {
		t.Fatal(err)
	}
	if n := want("c", "kc", PriorityWaiting, 1); n != 0 {
		t.Fatal("a failed key was queued again")
	}
	if n := want("c", "kc2", PriorityWaiting, 1); n != 1 {
		t.Fatal("a new key after a failure was not queued")
	}
	errs, _ := s.PreviewErrors(ctx, 5)
	if len(errs) != 0 {
		t.Fatalf("errors %v (c was requeued)", errs)
	}

	// Restart: running rows go back to pending.
	lease()
	if n, err := s.ResetRunningPreviews(ctx); err != nil || n != 1 {
		t.Fatalf("reset %d %v", n, err)
	}

	// Delete: the rows go, and keys no other row uses are returned.
	want("d", "ka2", PriorityBacklog, 1) // wants a's key, but has no files yet: a's files go with a
	keys, err := s.DeletePreviews(ctx, "a", "")
	if err != nil || len(keys) != 1 || keys[0] != "ka2" {
		t.Fatalf("keys %v %v", keys, err)
	}
	stats, err := s.PreviewStats(ctx)
	if err != nil || len(stats) == 0 {
		t.Fatalf("stats %v %v", stats, err)
	}
	all, _ := s.PreviewKeys(ctx)
	if all["ka2"] {
		t.Fatal("deleted key still listed")
	}
}
