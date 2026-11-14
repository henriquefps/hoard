package nas

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/henriquefps/hoard/server/internal/media/mediatest"
	"github.com/henriquefps/hoard/server/internal/store"
)

func sidecar(attrs string) []byte {
	return []byte(`<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">` +
		`<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:digiKam="http://www.digikam.org/ns/1.0/" ` + attrs +
		`/></rdf:RDF></x:xmpmeta>`)
}

func (e *env) touch(rel string, at time.Time) {
	e.t.Helper()
	if err := os.Chtimes(filepath.Join(e.nas, rel), at, at); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) culling(id string) map[string]store.CullField {
	e.t.Helper()
	rec, err := e.st.Culling(context.Background(), id)
	if err != nil {
		e.t.Fatalf("culling of %s: %v", id, err)
	}
	return rec.Fields
}

func TestSidecarsImportedOnWalk(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	t0 := time.UnixMilli(1_700_000_000_000)
	e.write("2024/DSC1.ARW", mediatest.RAW(shotAt(1, "2024:05:01 10:00:00")))
	e.write("2024/DSC1.JPG", mediatest.JPEG(shotAt(2, "2024:05:01 10:00:00"), ""))
	e.write("2024/DSC1.xmp", sidecar(`xmp:Rating="-1" digiKam:PickLabel="1"`))
	e.write("2024/DSC1.ARW.xmp", sidecar(`xmp:Rating="5"`)) // darktable's: <stem>.xmp wins
	e.touch("2024/DSC1.xmp", t0)
	e.write("2024/DSC2.JPG", mediatest.JPEG(shotAt(3, "2024:05:01 10:00:01"), ""))
	e.write("2024/DSC2.JPG.xmp", sidecar(`xmp:Rating="3" xmp:Label="Green"`))
	e.touch("2024/DSC2.JPG.xmp", t0)
	e.write("2024/DSC3.JPG", mediatest.JPEG(shotAt(4, "2024:05:01 10:00:02"), ""))
	e.write("2024/DSC3.xmp", []byte("not xml"))
	e.write("2024/DSC4.JPG", mediatest.JPEG(shotAt(5, "2024:05:01 10:00:03"), ""))
	e.write("2024/DSC4.xmp", sidecar(`xmp:CreatorTool="Lightroom"`)) // no culling value

	st := e.walk()
	if st.Culling != 3 {
		t.Fatalf("culling fields written %d", st.Culling)
	}
	live := e.live()
	pair := e.culling(live["DSC1.ARW"].ID)
	if string(pair[store.CullFlag].Value) != `"reject"` || pair[store.CullFlag].DeviceID != store.WriterNAS ||
		pair[store.CullFlag].ModifiedAt != t0.UnixMilli() {
		t.Fatalf("pair %+v", pair)
	}
	if _, ok := pair[store.CullRating]; ok {
		t.Fatal("a reject in a sidecar set stars")
	}
	two := e.culling(live["DSC2.JPG"].ID)
	if string(two[store.CullRating].Value) != "3" || string(two[store.CullLabel].Value) != `"Green"` {
		t.Fatalf("%+v", two)
	}
	for _, name := range []string{"DSC3.JPG", "DSC4.JPG"} {
		if _, err := e.st.Culling(ctx, live[name].ID); err == nil {
			t.Fatalf("%s got culling", name)
		}
	}

	// Unchanged: nothing read again. A device rates DSC2 later; the old sidecar is not re-asserted.
	if st := e.walk(); st.Culling != 0 {
		t.Fatalf("second walk wrote %d", st.Culling)
	}
	r := two[store.CullRating].Rev
	if _, err := e.st.PushCulling(ctx, "dev", []store.CullChange{{ItemID: live["DSC2.JPG"].ID, Field: store.CullRating, Value: json.RawMessage("1"),
		BaseRev: &r, ModifiedAt: t0.UnixMilli() + 1000}}); err != nil {
		t.Fatal(err)
	}
	e.touch("2024/DSC2.JPG.xmp", t0.Add(500*time.Millisecond)) // touched, older than the device's change, same values
	if st := e.walk(); st.Culling != 0 {
		t.Fatalf("a touched sidecar re-asserted its values: %d", st.Culling)
	}
	// Another app changes DSC2's sidecar later: it wins.
	e.write("2024/DSC2.JPG.xmp", sidecar(`xmp:Rating="4" xmp:Label="Green"`))
	e.touch("2024/DSC2.JPG.xmp", t0.Add(time.Hour))
	if st := e.walk(); st.Culling != 1 {
		t.Fatalf("newer sidecar: %d", st.Culling)
	}
	if v := e.culling(live["DSC2.JPG"].ID)[store.CullRating]; string(v.Value) != "4" || v.DeviceID != store.WriterNAS {
		t.Fatalf("%+v", v)
	}
	// Nothing was written to the folder.
	if b, _ := os.ReadFile(filepath.Join(e.nas, "2024/DSC3.xmp")); string(b) != "not xml" {
		t.Fatal("a sidecar was written")
	}
}

func TestCullingFollowsTheShotWhenTheRawAppears(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	e.write("DSC9.JPG", mediatest.JPEG(shotAt(9, "2024:05:01 10:00:00"), ""))
	e.walk()
	jpeg := e.live()["DSC9.JPG"].ID
	if _, err := e.st.PushCulling(ctx, "dev", []store.CullChange{
		{ItemID: jpeg, Field: store.CullRating, Value: json.RawMessage("4"), BaseRev: new(int64), ModifiedAt: 1000},
		{ItemID: jpeg, Field: store.CullFlag, Value: json.RawMessage(`"pick"`), BaseRev: new(int64), ModifiedAt: 1000},
	}); err != nil {
		t.Fatal(err)
	}
	e.write("DSC9.ARW", mediatest.RAW(shotAt(10, "2024:05:01 10:00:00")))
	e.walk()
	pair := e.live()["DSC9.ARW"].ID
	if pair == jpeg || !e.item(jpeg).Deleted {
		t.Fatal("the RAW did not make a new item")
	}
	got := e.culling(pair)
	if string(got[store.CullRating].Value) != "4" || string(got[store.CullFlag].Value) != `"pick"` || got[store.CullRating].ModifiedAt != 1000 ||
		got[store.CullRating].DeviceID != "dev" {
		t.Fatalf("not carried over: %+v", got)
	}
	// A device that still names the old item lands on the new one.
	res, err := e.st.PushCulling(ctx, "dev", []store.CullChange{{ItemID: jpeg, Field: store.CullLabel, Value: json.RawMessage(`"Red"`),
		BaseRev: new(int64), ModifiedAt: 2000}})
	if err != nil || res[0].ItemID != pair {
		t.Fatalf("%+v %v", res, err)
	}
	// The RAW goes away: the shot falls back to its JPEG item, with the pair's culling.
	if err := os.Remove(filepath.Join(e.nas, "DSC9.ARW")); err != nil {
		t.Fatal(err)
	}
	e.walk()
	if e.live()["DSC9.JPG"].ID != jpeg {
		t.Fatal("the JPEG item did not come back")
	}
	if got := e.culling(jpeg); string(got[store.CullLabel].Value) != `"Red"` {
		t.Fatalf("not carried back: %+v", got)
	}
}
