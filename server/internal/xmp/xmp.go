// Package xmp reads the culling values of an XMP sidecar (ADR 0016 "The NAS sidecars"), with the rules of Hoard ADR
// 0011 and Reveal ADR 0006: xmp:Rating (negative = reject), digiKam:PickLabel, xmp:Label, and the old Hoard "Copy
// picks" sidecar that is a pick. Read only: the server never writes sidecars.
package xmp

import (
	"bytes"
	"encoding/xml"
	"errors"
	"io"
	"math"
	"strconv"
	"strings"
	"unicode/utf8"
)

// MaxSize is the largest sidecar read (larger ones are ignored, as by the apps).
const MaxSize = 4 << 20

// MaxLabel is the longest label kept, in characters.
const MaxLabel = 64

// Namespaces.
const (
	nsMeta    = "adobe:ns:meta/"
	nsRDF     = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
	nsXMP     = "http://ns.adobe.com/xap/1.0/"
	nsDigiKam = "http://www.digikam.org/ns/1.0/"
)

// Culling is what a sidecar says. Nil fields are absent from the file.
type Culling struct {
	Rating *int    // 0..5; a negative xmp:Rating leaves it nil and sets Flag to reject
	Flag   *string // pick | reject | none
	Label  *string
}

// Empty reports whether the sidecar holds no culling value.
func (c Culling) Empty() bool { return c.Rating == nil && c.Flag == nil && c.Label == nil }

type raw struct {
	rating, pickLabel, label, creator *string
	description                       bool
}

func (r *raw) set(local, space, value string) {
	value = strings.TrimSpace(value)
	pick := func(p **string) {
		if *p == nil {
			v := value
			*p = &v
		}
	}
	switch {
	case space == nsXMP && local == "Rating":
		pick(&r.rating)
	case space == nsXMP && local == "Label":
		pick(&r.label)
	case space == nsXMP && local == "CreatorTool":
		pick(&r.creator)
	case space == nsDigiKam && local == "PickLabel":
		pick(&r.pickLabel)
	}
}

// Read parses a sidecar. ok is false when the file is not a readable XMP packet (not XML, a DTD, no x:xmpmeta or
// rdf:RDF root, no rdf:Description, over MaxSize): such a file holds nothing for the server.
func Read(r io.Reader) (Culling, bool) {
	b, err := io.ReadAll(io.LimitReader(r, MaxSize+1))
	if err != nil || len(b) > MaxSize {
		return Culling{}, false
	}
	return Parse(b)
}

// Parse is Read over bytes.
func Parse(b []byte) (Culling, bool) {
	d := xml.NewDecoder(bytes.NewReader(b))
	d.Strict = true
	var rw raw
	depth := 0
	var text *strings.Builder // collecting a property element's text
	var textName xml.Name
	for {
		tok, err := d.Token()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return Culling{}, false
		}
		switch t := tok.(type) {
		case xml.Directive:
			if bytes.HasPrefix(bytes.TrimSpace(t), []byte("DOCTYPE")) {
				return Culling{}, false
			}
		case xml.StartElement:
			isRoot := (t.Name.Space == nsMeta && t.Name.Local == "xmpmeta") || (t.Name.Space == nsRDF && t.Name.Local == "RDF")
			if depth == 0 && !isRoot {
				return Culling{}, false
			}
			depth++
			if t.Name.Space == nsRDF && t.Name.Local == "Description" {
				rw.description = true
				for _, a := range t.Attr {
					rw.set(a.Name.Local, a.Name.Space, a.Value)
				}
			}
			if (t.Name.Space == nsXMP || t.Name.Space == nsDigiKam) && text == nil {
				text, textName = &strings.Builder{}, t.Name
			}
		case xml.CharData:
			if text != nil && text.Len() < 4096 {
				text.Write(t)
			}
		case xml.EndElement:
			depth--
			if text != nil && t.Name == textName {
				rw.set(textName.Local, textName.Space, text.String())
				text = nil
			}
		}
	}
	if !rw.description {
		return Culling{}, false
	}
	return rw.culling(), true
}

func (r raw) culling() Culling {
	var c Culling
	reject := false
	if r.rating != nil {
		if f, err := strconv.ParseFloat(*r.rating, 64); err == nil && !math.IsNaN(f) && !math.IsInf(f, 0) {
			if f < 0 {
				reject = true
			} else {
				n := int(math.Min(5, math.Trunc(f)))
				c.Rating = &n
			}
		}
	}
	flag := func(v string) { c.Flag = &v }
	switch {
	case r.pickLabel != nil:
		switch strings.TrimSpace(*r.pickLabel) {
		case "1":
			flag("reject")
		case "3":
			flag("pick")
		default:
			flag("none")
		}
	case reject:
		flag("reject")
	case r.creator != nil && strings.HasPrefix(*r.creator, "Hoard"):
		flag("pick") // an old Hoard "Copy picks" sidecar (Hoard ADR 0011)
	}
	if r.label != nil {
		if v := *r.label; utf8.ValidString(v) && utf8.RuneCountInString(v) <= MaxLabel && !strings.ContainsFunc(v, func(r rune) bool {
			return r < 0x20 || r == 0x7f
		}) {
			c.Label = &v
		}
	}
	return c
}
