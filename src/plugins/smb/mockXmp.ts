/**
 * The web mock's XMP sidecars (ADR 0011). Native has the real reader/merger (`dev.hfps.hoard.xmp`, a DOM merge,
 * JVM-tested); the mock only needs to produce and read the same fields in its in-memory files, so it works on
 * attributes with regular expressions and treats anything that does not look like an XMP document as malformed.
 */
import type { SidecarFields } from "./definitions"

/** Characters XML 1.0 forbids (control characters except tab, newline, carriage return; U+FFFE/FFFF). */
// eslint-disable-next-line no-control-regex
const INVALID_XML = /[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g

/** Text safe inside a double-quoted XML attribute. */
export function escapeXmlAttribute(value: string): string {
  return value
    .replace(INVALID_XML, "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;")
    .replace(/\t/g, "&#x9;")
    .replace(/\n/g, "&#xA;")
    .replace(/\r/g, "&#xD;")
}

const PICK_LABEL = { none: "0", pick: "3", reject: "1" } as const

/** Malformed for the mock: not an `x:xmpmeta` / `rdf:RDF` document with a description. */
function isXmp(text: string): boolean {
  const t = text.trim()
  return (
    /<(x:xmpmeta|rdf:RDF)[\s>]/.test(t) &&
    /<\/(x:xmpmeta|rdf:RDF)>\s*(<\?xpacket[^>]*\?>)?$/.test(t) &&
    /<rdf:Description[\s>/]/.test(t)
  )
}

function property(text: string, name: string): string | undefined {
  const attr = new RegExp(`\\s${name}="([^"]*)"`).exec(text)
  if (attr) return attr[1]
  return new RegExp(`<${name}>([^<]*)</${name}>`).exec(text)?.[1]
}

/** The culling fields of a sidecar (Reveal's reading rules), or null when it is malformed. */
export function readMockXmp(text: string): { fields: SidecarFields; hasValues: boolean } | null {
  if (!isXmp(text)) return null
  const rating = property(text, "xmp:Rating")
  const pick = property(text, "digiKam:PickLabel")
  const tool = property(text, "xmp:CreatorTool")
  const label = property(text, "xmp:Label")
  const stars = rating === undefined ? 0 : Math.trunc(Number(rating)) || 0
  const hoard = tool?.trim().startsWith("Hoard") ?? false
  const flag: SidecarFields["flag"] =
    stars < 0 || pick === "1"
      ? "reject"
      : pick === "3"
        ? "pick"
        : pick !== undefined
          ? "none"
          : hoard
            ? "pick"
            : "none"
  return {
    fields: {
      rating: Math.max(0, Math.min(5, stars)),
      flag,
      ...(label?.trim() ? { label } : {}),
    },
    hasValues: rating !== undefined || pick !== undefined || hoard,
  }
}

/** A new sidecar, or `existing` with Rating / PickLabel / MetadataDate replaced; null when `existing` is malformed. */
export function writeMockXmp(
  existing: string | null,
  fields: { rating: number; flag: SidecarFields["flag"] },
  creatorTool: string,
  date: string
): string | null {
  const rating = fields.flag === "reject" ? -1 : Math.max(0, Math.min(5, Math.round(fields.rating)))
  const attributes = `xmp:Rating="${rating}" digiKam:PickLabel="${PICK_LABEL[fields.flag]}" xmp:MetadataDate="${escapeXmlAttribute(date)}"`
  if (existing === null || existing.trim() === "") {
    const tool = escapeXmlAttribute(creatorTool)
    return [
      `<?xml version="1.0" encoding="UTF-8"?>`,
      `<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="${tool}">`,
      ` <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">`,
      `  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:digiKam="http://www.digikam.org/ns/1.0/" xmp:CreatorTool="${tool}" ${attributes}/>`,
      ` </rdf:RDF>`,
      `</x:xmpmeta>`,
      ``,
    ].join("\n")
  }
  if (!isXmp(existing)) return null
  let text = existing
  for (const name of ["xmp:Rating", "digiKam:PickLabel", "xmp:MetadataDate"]) {
    text = text
      .replace(new RegExp(`\\s+${name}="[^"]*"`, "g"), "")
      .replace(new RegExp(`\\s*<${name}>[^<]*</${name}>`, "g"), "")
  }
  const declare =
    (text.includes('xmlns:xmp="') ? "" : ' xmlns:xmp="http://ns.adobe.com/xap/1.0/"') +
    (text.includes('xmlns:digiKam="') ? "" : ' xmlns:digiKam="http://www.digikam.org/ns/1.0/"')
  return text.replace(/<rdf:Description/, `<rdf:Description${declare} ${attributes}`)
}

/** Lower-case candidate sidecar names of a shot, best first (ADR 0011 "Naming", as native `SidecarNames`). */
export function sidecarCandidates(stem: string, fileNames: readonly string[]): string[] {
  const normal = [
    ...new Set([`${stem}.xmp`, ...fileNames.map((n) => `${n}.xmp`)].map((n) => n.toLowerCase())),
  ]
  return [...normal, ...normal.map((n) => `.${n}`)]
}

/** The existing sidecar of a shot among `names` (any case; `._` AppleDouble files ignored), or undefined. */
export function findSidecar(
  stem: string,
  fileNames: readonly string[],
  names: readonly string[]
): string | undefined {
  const byLower = new Map<string, string>()
  for (const name of names) {
    if (
      name.toLowerCase().endsWith(".xmp") &&
      !name.startsWith("._") &&
      !byLower.has(name.toLowerCase())
    )
      byLower.set(name.toLowerCase(), name)
  }
  for (const candidate of sidecarCandidates(stem, fileNames)) {
    const found = byLower.get(candidate)
    if (found) return found
  }
  return undefined
}
