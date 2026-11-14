package xmp

import (
	"strings"
	"testing"
)

const head = `<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">`
const tail = `</rdf:RDF></x:xmpmeta><?xpacket end="w"?>`

func desc(attrs, body string) string {
	return head + `<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:digiKam="http://www.digikam.org/ns/1.0/" ` +
		`xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" ` + attrs + `>` + body + `</rdf:Description>` + tail
}

func TestRead(t *testing.T) {
	cases := []struct {
		name, doc   string
		ok          bool
		rating      int // -9 = absent
		flag, label string
	}{
		{"hoard write", desc(`xmp:Rating="4" digiKam:PickLabel="3" xmp:MetadataDate="2026-11-11T10:00:00Z"`, ""), true, 4, "pick", ""},
		{"reject keeps stars unknown", desc(`xmp:Rating="-1" digiKam:PickLabel="1"`, ""), true, -9, "reject", ""},
		{"negative rating alone is a reject", desc(`xmp:Rating="-1"`, ""), true, -9, "reject", ""},
		{"pick label 0", desc(`xmp:Rating="2" digiKam:PickLabel="0"`, ""), true, 2, "none", ""},
		{"unknown pick label", desc(`digiKam:PickLabel="2"`, ""), true, -9, "none", ""},
		{"lightroom label", desc(`xmp:Rating="5" xmp:Label="Red" crs:Exposure2012="+0.70"`, ""), true, 5, "", "Red"},
		{"element form (digiKam)", desc(``, `<xmp:Rating>3</xmp:Rating><digiKam:PickLabel>3</digiKam:PickLabel>`), true, 3, "pick", ""},
		{"fraction truncates, over 5 clamps", desc(`xmp:Rating="3.9"`, ""), true, 3, "", ""},
		{"over 5", desc(`xmp:Rating="7"`, ""), true, 5, "", ""},
		{"old hoard copy picks", desc(`xmp:CreatorTool="Hoard 1.6.0" xmp:Rating="2"`, ""), true, 2, "pick", ""},
		{"old hoard reject", desc(`xmp:CreatorTool="Hoard 1.6.0" xmp:Rating="-1"`, ""), true, -9, "reject", ""},
		{"reveal creator is not a pick", desc(`xmp:CreatorTool="Reveal 0.7.0" xmp:Rating="2"`, ""), true, 2, "", ""},
		{"develop settings only", desc(`crs:Exposure2012="+0.70"`, ""), true, -9, "", ""},
		{"prefix xap", head + `<rdf:Description xmlns:xap="http://ns.adobe.com/xap/1.0/" xap:Rating="1"/>` + tail, true, 1, "", ""},
		{"rdf root", `<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2"/></rdf:RDF>`, true, 2, "", ""},
		{"not xml", "garbage <<<", false, -9, "", ""},
		{"not xmp", `<html><body/></html>`, false, -9, "", ""},
		{"no description", head + tail, false, -9, "", ""},
		{"doctype", `<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>` + desc(`xmp:Rating="1"`, ""), false, -9, "", ""},
		{"entity", desc(`xmp:Label="&e;"`, ""), false, -9, "", ""},
		{"control character (not XML 1.0)", desc(`xmp:Label="a&#x1;b"`, ""), false, -9, "", ""},
		{"label over 64 characters ignored", desc(`xmp:Rating="1" xmp:Label="`+strings.Repeat("r", 65)+`"`, ""), true, 1, "", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, ok := Read(strings.NewReader(c.doc))
			if ok != c.ok {
				t.Fatalf("ok %v", ok)
			}
			if (got.Rating == nil) != (c.rating == -9) || (got.Rating != nil && *got.Rating != c.rating) {
				t.Fatalf("rating %v", got.Rating)
			}
			if (got.Flag == nil) != (c.flag == "") || (got.Flag != nil && *got.Flag != c.flag) {
				t.Fatalf("flag %v", got.Flag)
			}
			if (got.Label == nil) != (c.label == "") || (got.Label != nil && *got.Label != c.label) {
				t.Fatalf("label %v", got.Label)
			}
		})
	}
}

func TestReadRefusesLargeFiles(t *testing.T) {
	doc := desc(`xmp:Rating="1"`, "<!--"+strings.Repeat("x", MaxSize)+"-->")
	if _, ok := Read(strings.NewReader(doc)); ok {
		t.Fatal("a sidecar over 4 MiB was read")
	}
}

func FuzzParse(f *testing.F) {
	f.Add([]byte(desc(`xmp:Rating="4" digiKam:PickLabel="3"`, "")))
	f.Add([]byte(desc(``, `<xmp:Rating>3</xmp:Rating>`)))
	f.Fuzz(func(t *testing.T, b []byte) {
		c, _ := Parse(b)
		if c.Rating != nil && (*c.Rating < 0 || *c.Rating > 5) {
			t.Fatalf("rating %d", *c.Rating)
		}
	})
}
