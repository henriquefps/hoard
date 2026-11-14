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

/** A member file of an item as the item answer lists it. */
export interface ItemFile {
  fingerprint: string
  role: string
  name?: string
  format?: string
  size: number
}
