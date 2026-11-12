import type { CullShot, SmbEntry } from "@/plugins/smb/definitions"
import { photoKind, photoStem, type PhotoKind } from "@/lib/photos"

/**
 * "Edit in Reveal" (ADR 0010): which files a hand-off sends, in which order. Pure (tested); the
 * native side applies the same order again (`RevealHandoff.order`), so the mock records what the
 * phone would send.
 */

/** Batches above this many files ask first (Reveal lists them all in one hand-off). */
export const REVEAL_CONFIRM_ABOVE = 200

const RANK: Record<PhotoKind, number> = { raw: 0, jpeg: 1, heif: 2, png: 3, webp: 3 }

const parentOf = (path: string) => {
  const slash = path.lastIndexOf("/")
  return slash < 0 ? "" : path.slice(0, slash)
}
const nameOf = (path: string) => path.slice(path.lastIndexOf("/") + 1)
const shotKey = (path: string) => `${parentOf(path)}/${photoStem(nameOf(path)).toLowerCase()}`

/**
 * Photos only, duplicates dropped, and the files of one shot (same folder, same case-insensitive
 * stem) together at the place of the first one, RAW first, then JPEG, HEIF, PNG/WebP.
 */
export function revealOrder(paths: readonly string[]): string[] {
  const groups = new Map<string, string[]>()
  const seen = new Set<string>()
  for (const path of paths) {
    if (!photoKind(nameOf(path)) || seen.has(path)) continue
    seen.add(path)
    const key = shotKey(path)
    const group = groups.get(key)
    if (group) group.push(path)
    else groups.set(key, [path])
  }
  const rank = (path: string) => RANK[photoKind(nameOf(path))!]
  return [...groups.values()]
    .flatMap((files) =>
      files.map((path, i) => ({ path, i })).sort((a, b) => rank(a.path) - rank(b.path) || a.i - b.i)
    )
    .map((f) => f.path)
}

/** Every file of a culling shot: RAW, the rendering, and the others with the same stem. */
export function shotPaths(shot: CullShot): string[] {
  return [shot.raw, shot.rendered, ...shot.others].flatMap((f) => (f ? [f.path] : []))
}

/** The files of several shots, in the shots' order (the culling grid's order). */
export function shotsPaths(shots: readonly CullShot[]): string[] {
  return revealOrder(shots.flatMap(shotPaths))
}

/** The path Reveal opens first for a shot: its RAW, else the rendering. */
export function shotInitialPath(shot: CullShot): string | undefined {
  return (shot.raw ?? shot.rendered)?.path
}

/**
 * A browser selection as a hand-off: the selected photo files, each expanded to every photo of
 * the folder with the same stem (selecting `DSC1.ARW` also sends `DSC1.JPG`). Folders and other
 * files are left out.
 */
export function selectionPaths(
  selected: readonly Pick<SmbEntry, "name" | "path" | "isDirectory">[],
  folder: readonly Pick<SmbEntry, "name" | "path" | "isDirectory">[]
): string[] {
  const byKey = new Map<string, string[]>()
  for (const entry of folder) {
    if (entry.isDirectory || !photoKind(entry.name)) continue
    const key = shotKey(entry.path)
    byKey.set(key, [...(byKey.get(key) ?? []), entry.path])
  }
  const paths = selected
    .filter((e) => !e.isDirectory && photoKind(e.name))
    .flatMap((e) => [e.path, ...(byKey.get(shotKey(e.path)) ?? [])])
  return revealOrder(paths)
}

/** Any photo among these entries (the browser shows the action only then). */
export const hasPhotos = (entries: readonly Pick<SmbEntry, "name" | "isDirectory">[]) =>
  entries.some((e) => !e.isDirectory && Boolean(photoKind(e.name)))

/** Ask before sending: big batches, and SMB once (the photos are read through Hoard, while the machine is reachable). */
export function needsConfirm(count: number, smb: boolean): boolean {
  return smb || count > REVEAL_CONFIRM_ABOVE
}
