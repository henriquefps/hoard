/**
 * XMP sidecars for "Copy picks" (ADR 0009): a standard `<stem>.xmp` written next to each copied
 * shot **at the destination only**, with the stars as `xmp:Rating` (and an optional `xmp:Label`),
 * readable by Lightroom Classic, darktable, digiKam, Capture One and other editors. The hand-off
 * point to an editing app: nothing else (no develop settings, no xmpDM).
 */
import type { CullFile, CullShot, TransferSidecar } from "@/plugins/smb/definitions"
import type { CopyPlanItem, Decisions } from "./model"

export interface XmpFields {
  /** 0..5 stars (0 = unrated); -1 is "rejected" in XMP, but rejects are never copied. */
  rating: number
  /** Free text label (Lightroom shows known colour names as colours). */
  label?: string
  /** `Hoard 1.6.0`. */
  creatorTool: string
  /** ISO 8601 date-time of writing. */
  date: string
}

/** Characters XML 1.0 forbids (control characters except tab, newline, carriage return; lone surrogates; U+FFFE/FFFF). */
const INVALID_XML = /[^\t\n\r -퟿-�\u{10000}-\u{10FFFF}]/gu

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

/** The sidecar document (UTF-8, no BOM). */
export function xmpSidecar(fields: XmpFields): string {
  const rating = Math.max(-1, Math.min(5, Math.round(fields.rating)))
  const attributes = [
    `xmp:Rating="${rating}"`,
    fields.label ? `xmp:Label="${escapeXmlAttribute(fields.label)}"` : null,
    `xmp:CreatorTool="${escapeXmlAttribute(fields.creatorTool)}"`,
    `xmp:MetadataDate="${escapeXmlAttribute(fields.date)}"`,
  ].filter(Boolean)
  return [
    `<?xml version="1.0" encoding="UTF-8"?>`,
    `<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="${escapeXmlAttribute(fields.creatorTool)}">`,
    ` <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">`,
    `  <rdf:Description rdf:about=""`,
    `    xmlns:xmp="http://ns.adobe.com/xap/1.0/"`,
    ...attributes.map((a, i) => `    ${a}${i === attributes.length - 1 ? "/>" : ""}`),
    ` </rdf:RDF>`,
    `</x:xmpmeta>`,
    ``,
  ].join("\n")
}

/**
 * One sidecar per copied shot, attached to its RAW when the RAW is copied, else to the first file
 * copied (the engine names it after that file's destination stem, so a RAW + JPEG pair shares
 * one `<stem>.xmp`, as Lightroom expects).
 */
export function sidecarsFor(
  items: readonly CopyPlanItem[],
  order: readonly CullShot[],
  decisions: Decisions,
  creatorTool: string,
  date: string
): TransferSidecar[] {
  const byShot = new Map<string, CullFile[]>()
  for (const item of items) {
    const list = byShot.get(item.shotId) ?? []
    list.push(item.file)
    byShot.set(item.shotId, list)
  }
  const shots = new Map(order.map((s) => [s.id, s]))
  const out: TransferSidecar[] = []
  for (const [shotId, files] of byShot) {
    const shot = shots.get(shotId)
    const decision = decisions.get(shotId)
    if (!shot || decision?.flag === "reject") continue
    const target = files.find((f) => f.path === shot.raw?.path) ?? files[0]
    out.push({
      sourcePath: target.path,
      content: xmpSidecar({ rating: decision?.rating ?? 0, creatorTool, date }),
    })
  }
  return out
}
