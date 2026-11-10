import { DOMParser } from "@xmldom/xmldom"
import { describe, expect, it } from "vitest"
import type { CullShot } from "@/plugins/smb/definitions"
import { planCopy, type CullDecision } from "./model"
import { escapeXmlAttribute, sidecarsFor, xmpSidecar } from "./xmp"

const XMP = "http://ns.adobe.com/xap/1.0/"
const RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

function parse(xml: string) {
  const errors: string[] = []
  const doc = new DOMParser({
    onError: (level, message) => {
      if (level !== "warning") errors.push(message)
    },
  }).parseFromString(xml, "text/xml")
  expect(errors).toEqual([])
  const description = doc.getElementsByTagNameNS(RDF, "Description")[0]
  return {
    root: doc.documentElement!,
    rating: description.getAttributeNS(XMP, "Rating"),
    label: description.getAttributeNS(XMP, "Label"),
    tool: description.getAttributeNS(XMP, "CreatorTool"),
  }
}

describe("XMP sidecars", () => {
  it("is well-formed XMP with the rating in the xmp namespace", () => {
    const xml = xmpSidecar({ rating: 4, creatorTool: "Hoard 1.6.0", date: "2026-11-10T12:00:00Z" })
    const parsed = parse(xml)
    expect(parsed.root.localName).toBe("xmpmeta")
    expect(parsed.root.namespaceURI).toBe("adobe:ns:meta/")
    expect(parsed.rating).toBe("4")
    expect(parsed.tool).toBe("Hoard 1.6.0")
    expect(xml.startsWith('<?xml version="1.0" encoding="UTF-8"?>')).toBe(true)
    expect(xml).not.toContain("xmpDM")
  })

  it("clamps ratings and escapes every attribute", () => {
    expect(parse(xmpSidecar({ rating: 9, creatorTool: "H", date: "d" })).rating).toBe("5")
    expect(parse(xmpSidecar({ rating: 0.4, creatorTool: "H", date: "d" })).rating).toBe("0")
    const nasty = `Tom & "Jerry" <b>'s\tpick\n\u0001￿ ✓ 🙂`
    const parsed = parse(xmpSidecar({ rating: 3, label: nasty, creatorTool: nasty, date: "d" }))
    expect(parsed.label).toBe(`Tom & "Jerry" <b>'s\tpick\n ✓ 🙂`)
    expect(parsed.tool).toBe(parsed.label)
    expect(escapeXmlAttribute(`a&b<c>"'`)).toBe("a&amp;b&lt;c&gt;&quot;&apos;")
  })

  it("one sidecar per copied pick, on the RAW when it is copied", () => {
    const shot = (stem: string, raw: boolean, jpeg: boolean): CullShot => ({
      id: stem.toLowerCase(),
      stem,
      kind: raw && jpeg ? "pair" : raw ? "raw" : "jpeg",
      raw: raw
        ? { name: `${stem}.ARW`, path: `D/${stem}.ARW`, size: 10, modifiedAt: null }
        : undefined,
      rendered: jpeg
        ? { name: `${stem}.JPG`, path: `D/${stem}.JPG`, size: 5, modifiedAt: null }
        : undefined,
      others: [],
    })
    const order = [shot("A", true, true), shot("B", false, true), shot("C", true, true)]
    const decisions = new Map<string, CullDecision>([
      ["a", { flag: "pick", rating: 5 }],
      ["b", { flag: "pick", rating: 0 }],
      ["c", { flag: "reject", rating: 0 }],
    ])
    const both = sidecarsFor(
      planCopy(order, decisions, "both").items,
      order,
      decisions,
      "Hoard",
      "now"
    )
    expect(both.map((s) => s.sourcePath)).toEqual(["D/A.ARW", "D/B.JPG"])
    expect(parse(both[0].content).rating).toBe("5")
    expect(parse(both[1].content).rating).toBe("0")
    // JPEG only: the sidecar follows the JPEG.
    const jpeg = sidecarsFor(
      planCopy(order, decisions, "jpeg").items,
      order,
      decisions,
      "Hoard",
      "now"
    )
    expect(jpeg.map((s) => s.sourcePath)).toEqual(["D/A.JPG", "D/B.JPG"])
  })
})
