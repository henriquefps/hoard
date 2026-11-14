/** A view of an image: scale (CSS px per image px) and the image's top-left corner in the box. */
export interface View {
  s: number
  x: number
  y: number
}

/** The view at scale `s` that keeps the image point under `p` (box coordinates) where it is. */
export function zoomTarget(v: View, s: number, p: { x: number; y: number }): View {
  if (v.s <= 0) return { s, x: p.x, y: p.y }
  const ix = (p.x - v.x) / v.s
  const iy = (p.y - v.y) / v.s
  return { s, x: p.x - ix * s, y: p.y - iy * s }
}

/** Which of an item's files the browser can show as the full-resolution layer (JPEG, PNG, WebP, AVIF). */
export interface ItemFile {
  fingerprint: string
  role: string
  name?: string
  format?: string
  size: number
}

const SHOWABLE = /\.(jpe?g|png|webp|avif)$/i
export const MAX_ORIGINAL_BYTES = 80_000_000

export function displayableOriginal(kind: string, mainFingerprint: string, files: readonly ItemFile[]): ItemFile | null {
  if (kind === "video") return null
  const candidates =
    kind === "pair" ? files.filter((f) => f.role === "rendered") : files.filter((f) => f.fingerprint === mainFingerprint)
  return candidates.find((f) => SHOWABLE.test(f.name ?? "") && f.size <= MAX_ORIGINAL_BYTES) ?? null
}
