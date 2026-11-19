package compress

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/store"
)

func testManager(t *testing.T, dir string) (*Manager, *store.Store) {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	m := New(Config{Store: st, DataDir: t.TempDir(), Mount: &WriteMount{ReadRoot: dir, WriteRoot: dir},
		FolderDir: func(context.Context, string) (string, error) { return dir, nil }})
	return m, st
}

// Re-review N6: a purge whose journal another goroutine (the housekeeping) runs right now is answered busy, not
// not_writable.
func TestPurgeBusyWhileClaimed(t *testing.T) {
	m, st := testManager(t, t.TempDir())
	ctx := context.Background()
	j := store.CompressJob{ID: store.NewUUID(), Target: "nas", CreatedBy: "d", Selection: json.RawMessage(`{}`), Profile: DefaultProfile,
		Options: json.RawMessage(`{}`), Priority: 2, State: store.JobActive}
	if _, _, _, err := st.CreateCompressJob(ctx, j, []store.CompressItem{{ItemID: "i", Fingerprint: "v1:x", Size: 1, Source: "nas", FolderID: "f",
		RelPath: "A.MP4", State: store.CompressReplaced}}); err != nil {
		t.Fatal(err)
	}
	list, _, _ := st.CompressItems(ctx, store.CompressItemQuery{Limit: 10})
	id := list[0].ID
	if !m.claim(id) {
		t.Fatal("claim")
	}
	if a := m.Purge(ctx, []int64{id}); a[0].Error != "busy" {
		t.Fatalf("%+v", a)
	}
	m.release(id)
}

// Re-review N6: a file gone during a rename is not cached as unreadable by the background probe.
func TestProbeLaterSkipsAVanishedFile(t *testing.T) {
	dir := t.TempDir()
	m, st := testManager(t, dir)
	m.cfg.Prober = &Prober{FFprobe: "ffprobe"}
	m.probeLater([]Candidate{{FolderID: "f", RelPath: "gone.mp4", Size: 1, Fingerprint: "v1:gone"}})
	deadline := time.Now().Add(5 * time.Second)
	for m.ProbesPending() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got, _ := st.VideoProbes(context.Background(), ProbeVersion, []string{"v1:gone"}); len(got) != 0 {
		t.Fatalf("cached %s", got["v1:gone"])
	}
	if _, err := os.Stat(filepath.Join(dir, "gone.mp4")); !os.IsNotExist(err) {
		t.Fatal("setup")
	}
}
