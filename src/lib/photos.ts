import type { CullFile, CullShot, SmbEntry } from "@/plugins/smb/definitions"

/**
 * Photo files for culling (ADR 0008), mirroring the native `ImageFormats` + `ShotPairing`
 * (android/…/image/format/): the web mock pairs with this, and the browser uses it to decide when a
 * folder offers "Cull photos".
 */
export type PhotoKind = "raw" | "jpeg" | "heif" | "png" | "webp"

const RAW_EXTENSIONS = [
  ...["arw", "srf", "sr2", "cr2", "cr3", "nef", "nrw", "raf", "dng", "orf", "rw2", "pef", "srw"],
]

const KINDS = new Map<string, PhotoKind>([
  ...["jpg", "jpeg", "jpe", "jfif"].map((e) => [e, "jpeg"] as const),
  ...["heic", "heif", "hif", "avif"].map((e) => [e, "heif"] as const),
  ["png", "png"],
  ["webp", "webp"],
  ...RAW_EXTENSIONS.map((e) => [e, "raw"] as const),
])

/** Extension after the last dot, lowercase; `""` for `.hidden` or `name.` (like the native side). */
export function photoExtension(name: string): string {
  const dot = name.lastIndexOf(".")
  return dot <= 0 || dot === name.length - 1 ? "" : name.slice(dot + 1).toLowerCase()
}

export function photoKind(name: string): PhotoKind | undefined {
  return KINDS.get(photoExtension(name))
}

export const isRawName = (name: string) => photoKind(name) === "raw"

/** File name without its extension (`DSC07668.ARW` → `DSC07668`). */
export function photoStem(name: string): string {
  const dot = name.lastIndexOf(".")
  return dot <= 0 ? name : name.slice(0, dot)
}

/** `DSC2` < `DSC10`, case-insensitive (same rule as the native `NaturalOrder`). */
export function naturalCompare(a: string, b: string): number {
  return a.localeCompare(b, "en", { numeric: true, sensitivity: "base" })
}

const RENDERED_RANK: Record<PhotoKind, number> = { jpeg: 0, heif: 1, png: 2, webp: 2, raw: 9 }

/**
 * Groups a folder listing into shots by case-insensitive stem: RAW + camera rendering is one shot,
 * RAW-only and JPEG-only shots stand alone. With several RAWs or renderings for one stem the first
 * by preference (JPEG over HEIF over PNG/WebP, then name) wins and the rest go to `others`.
 */
export function pairShots(
  entries: readonly Pick<SmbEntry, "name" | "path" | "size" | "modifiedAt" | "isDirectory">[]
): CullShot[] {
  const groups = new Map<string, { file: CullFile; kind: PhotoKind }[]>()
  for (const entry of entries) {
    if (entry.isDirectory) continue
    const kind = photoKind(entry.name)
    if (!kind) continue
    const key = photoStem(entry.name).toLowerCase()
    const file: CullFile = {
      name: entry.name,
      path: entry.path,
      size: entry.size,
      modifiedAt: entry.modifiedAt,
    }
    const group = groups.get(key)
    if (group) group.push({ file, kind })
    else groups.set(key, [{ file, kind }])
  }
  const byName = (a: { file: CullFile }, b: { file: CullFile }) =>
    a.file.name < b.file.name ? -1 : a.file.name > b.file.name ? 1 : 0
  const shots = [...groups.values()].map((members): CullShot => {
    const raws = members.filter((m) => m.kind === "raw").sort(byName)
    const rendered = members
      .filter((m) => m.kind !== "raw")
      .sort((a, b) => RENDERED_RANK[a.kind] - RENDERED_RANK[b.kind] || byName(a, b))
    const raw = raws[0]?.file
    const main = rendered[0]?.file
    const stem = photoStem((raw ?? main)!.name)
    return {
      id: stem.toLowerCase(),
      stem,
      kind: raw && main ? "pair" : raw ? "raw" : "jpeg",
      ...(raw ? { raw } : {}),
      ...(main ? { rendered: main } : {}),
      others: [...raws.slice(1), ...rendered.slice(1)].map((m) => m.file),
    }
  })
  return shots.sort((a, b) => naturalCompare(a.stem, b.stem))
}

/** At least this many photos (RAW, JPEG, HEIF…) make the browser offer "Cull photos". */
export const CULL_MIN_PHOTOS = 2

/** Number of photo files in a listing (cheap; no pairing). */
export function countPhotos(entries: readonly Pick<SmbEntry, "name" | "isDirectory">[]): number {
  let count = 0
  for (const entry of entries) if (!entry.isDirectory && photoKind(entry.name)) count++
  return count
}
