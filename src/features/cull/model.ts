/**
 * Pure culling logic (ADR 0008), unit tested in `model.test.ts`: ordering and burst hints, filters
 * and progress, decisions with undo, what "Copy picks" and "Delete rejects" touch, the prefetch
 * window and the 100% tile grid. No React, no plugin calls.
 */
import type { CullFlag } from "@/db/types"
import { naturalCompare } from "@/lib/photos"
import type { CullFile, CullMetadata, CullShot } from "@/plugins/smb/definitions"

// --- Decisions --------------------------------------------------------------------------------

export interface CullDecision {
  flag: CullFlag | null
  /** 0 (none) .. 5. */
  rating: number
}

export const UNMARKED: CullDecision = Object.freeze({ flag: null, rating: 0 })

export const isUnmarked = (d: CullDecision | undefined) => !d || (!d.flag && d.rating === 0)

export const sameDecision = (a: CullDecision | undefined, b: CullDecision | undefined) =>
  (a?.flag ?? null) === (b?.flag ?? null) && (a?.rating ?? 0) === (b?.rating ?? 0)

/** Shot id → decision (unmarked shots are absent). */
export type Decisions = ReadonlyMap<string, CullDecision>

export const decisionOf = (decisions: Decisions, shotId: string) =>
  decisions.get(shotId) ?? UNMARKED

/** One undoable step: what some shots were and became (a pick, a rating, a bulk change). */
export interface DecisionChange {
  shotIds: string[]
  before: CullDecision[]
  after: CullDecision[]
}

/** Set the flag (null = unmark) of a shot; stars are kept. */
export function withFlag(decision: CullDecision, flag: CullFlag | null): CullDecision {
  return { flag, rating: decision.rating }
}

/**
 * Set the stars (0 clears). Rating a rejected shot makes it a pick: stars mean "keep, this much"
 * (Lightroom does the same).
 */
export function withRating(decision: CullDecision, rating: number): CullDecision {
  const stars = Math.max(0, Math.min(5, Math.round(rating)))
  return { flag: stars > 0 && decision.flag === "reject" ? "pick" : decision.flag, rating: stars }
}

/** A change that sets `next(decision)` on each shot, or null when nothing would change. */
export function changeOf(
  decisions: Decisions,
  shotIds: readonly string[],
  next: (decision: CullDecision, shotId: string) => CullDecision
): DecisionChange | null {
  const ids: string[] = []
  const before: CullDecision[] = []
  const after: CullDecision[] = []
  for (const id of shotIds) {
    const current = decisionOf(decisions, id)
    const updated = next(current, id)
    if (sameDecision(current, updated)) continue
    ids.push(id)
    before.push(current)
    after.push(updated)
  }
  return ids.length ? { shotIds: ids, before, after } : null
}

/** Decisions after applying (or, with `undo`, reverting) a change. */
export function applyChange(
  decisions: Decisions,
  change: DecisionChange,
  undo = false
): Map<string, CullDecision> {
  const next = new Map(decisions)
  change.shotIds.forEach((id, i) => {
    const value = undo ? change.before[i] : change.after[i]
    if (isUnmarked(value)) next.delete(id)
    else next.set(id, { flag: value.flag, rating: value.rating })
  })
  return next
}

/** Undo history: newest last, bounded. */
export const UNDO_LIMIT = 200

export function pushUndo(
  stack: readonly DecisionChange[],
  change: DecisionChange
): DecisionChange[] {
  const next = [...stack, change]
  return next.length > UNDO_LIMIT ? next.slice(next.length - UNDO_LIMIT) : next
}

// --- Order and bursts -----------------------------------------------------------------------

/**
 * Capture-time order (EXIF time with sub-seconds, camera clock), name order for ties and for shots
 * without a time (they go last). Until metadata has loaded, callers keep the native name order.
 */
export function sortByCapture(
  shots: readonly CullShot[],
  metadata: ReadonlyMap<string, CullMetadata | undefined>
): CullShot[] {
  return [...shots].sort((a, b) => {
    const ta = metadata.get(a.id)?.captureMillis
    const tb = metadata.get(b.id)?.captureMillis
    if (ta !== undefined && tb !== undefined && ta !== tb) return ta - tb
    if (ta === undefined && tb !== undefined) return 1
    if (ta !== undefined && tb === undefined) return -1
    return naturalCompare(a.stem, b.stem)
  })
}

/** Frames closer than this to the previous one belong to the same burst (hint only; phase 3 groups for real). */
export const BURST_GAP_MS = 1000

export interface BurstPosition {
  /** Id of the burst's first shot. */
  burst: string
  /** 0-based position in the burst. */
  index: number
  size: number
}

/**
 * Burst hints: runs of 2+ consecutive shots (in the given order) each less than
 * {@link BURST_GAP_MS} after the previous one: what a camera's continuous drive produces, and what
 * the grid marks so a whole run can be compared.
 */
export function burstHints(
  ordered: readonly CullShot[],
  metadata: ReadonlyMap<string, CullMetadata | undefined>
): Map<string, BurstPosition> {
  const result = new Map<string, BurstPosition>()
  let run: string[] = []
  const flush = () => {
    if (run.length >= 2)
      run.forEach((id, index) => result.set(id, { burst: run[0], index, size: run.length }))
    run = []
  }
  let previous: number | undefined
  for (const shot of ordered) {
    const time = metadata.get(shot.id)?.captureMillis
    if (time === undefined) {
      flush()
      previous = undefined
      continue
    }
    if (previous !== undefined && time >= previous && time - previous < BURST_GAP_MS)
      run.push(shot.id)
    else {
      flush()
      run = [shot.id]
    }
    previous = time
  }
  flush()
  return result
}

/** The shots of the burst `shotId` is in (itself alone when not in one). */
export function burstOf(
  ordered: readonly CullShot[],
  hints: ReadonlyMap<string, BurstPosition>,
  shotId: string
): string[] {
  const position = hints.get(shotId)
  if (!position) return [shotId]
  return ordered.filter((s) => hints.get(s.id)?.burst === position.burst).map((s) => s.id)
}

// --- Filters and progress --------------------------------------------------------------------

export type CullFlagFilter = "all" | "picked" | "rejected" | "unmarked"

export interface CullFilter {
  flag: CullFlagFilter
  /** Only shots with at least this many stars (0 = any). */
  minStars: number
}

export const ALL_SHOTS: CullFilter = Object.freeze({ flag: "all", minStars: 0 })

export function matchesFilter(decision: CullDecision, filter: CullFilter): boolean {
  if (filter.minStars > 0 && decision.rating < filter.minStars) return false
  switch (filter.flag) {
    case "picked":
      return decision.flag === "pick"
    case "rejected":
      return decision.flag === "reject"
    case "unmarked":
      return decision.flag === null
    default:
      return true
  }
}

export function filterShots(
  shots: readonly CullShot[],
  decisions: Decisions,
  filter: CullFilter
): CullShot[] {
  if (filter.flag === "all" && filter.minStars === 0) return [...shots]
  return shots.filter((s) => matchesFilter(decisionOf(decisions, s.id), filter))
}

export interface CullProgress {
  total: number
  /** Picked or rejected (stars alone do not decide a shot). */
  decided: number
  picked: number
  rejected: number
  unmarked: number
  rated: number
}

export function progressOf(shots: readonly CullShot[], decisions: Decisions): CullProgress {
  let picked = 0
  let rejected = 0
  let rated = 0
  for (const shot of shots) {
    const d = decisions.get(shot.id)
    if (!d) continue
    if (d.flag === "pick") picked++
    else if (d.flag === "reject") rejected++
    if (d.rating > 0) rated++
  }
  return {
    total: shots.length,
    decided: picked + rejected,
    picked,
    rejected,
    unmarked: shots.length - picked - rejected,
    rated,
  }
}

/** Index of the next shot without a flag after `from` (wrapping), or -1 when all are decided. */
export function nextUnmarked(
  order: readonly CullShot[],
  decisions: Decisions,
  from: number
): number {
  for (let step = 1; step <= order.length; step++) {
    const index = (from + step) % order.length
    if (!decisions.get(order[index].id)?.flag) return index
  }
  return -1
}

// --- Copy picks and delete rejects ------------------------------------------------------------

/** Which files of a picked shot to copy. */
export type CopyFiles = "both" | "raw" | "jpeg"

export interface CopyPicksOptions {
  files: CopyFiles
  /** `keep`: into a folder named like the source folder; `new`: into `subfolder`; `none`: straight into the destination. */
  folder: "keep" | "new" | "none"
  /** Name of the new subfolder (`folder: "new"`). */
  subfolder?: string
}

export const DEFAULT_COPY_OPTIONS: CopyPicksOptions = { files: "both", folder: "keep" }

export interface CopyPlanItem {
  shotId: string
  file: CullFile
  /** The file is not the kind the user asked for: the shot had nothing else (a JPEG-only pick with "RAW only"). */
  fallback: boolean
}

export interface CopyPlan {
  items: CopyPlanItem[]
  shots: number
  bytes: number
  /** Picks copied with their other file because the chosen kind is missing. */
  fallbacks: number
}

/**
 * The files to copy for the picked shots, in `order`. `both`: every file of the shot (RAW, JPEG
 * and any other file with the same stem); `raw` / `jpeg`: that file, or the shot's only file when it
 * lacks that kind (counted in `fallbacks` so the dialog can say so).
 */
export function planCopy(
  order: readonly CullShot[],
  decisions: Decisions,
  files: CopyFiles
): CopyPlan {
  const items: CopyPlanItem[] = []
  let shots = 0
  let fallbacks = 0
  for (const shot of order) {
    if (decisions.get(shot.id)?.flag !== "pick") continue
    shots++
    const all = [shot.raw, shot.rendered, ...shot.others].filter((f): f is CullFile => Boolean(f))
    let chosen: CullFile[]
    let fallback = false
    if (files === "both") chosen = all
    else {
      const wanted = files === "raw" ? shot.raw : shot.rendered
      if (wanted) chosen = [wanted]
      else {
        chosen = all
        fallback = true
      }
    }
    if (fallback) fallbacks++
    for (const file of chosen) items.push({ shotId: shot.id, file, fallback })
  }
  return { items, shots, bytes: items.reduce((sum, i) => sum + i.file.size, 0), fallbacks }
}

/** The destination folder of a copy: `base`, `base/<source folder>` or `base/<subfolder>`. */
export function copyTargetPath(
  base: string,
  sourcePath: string,
  options: CopyPicksOptions
): string {
  const join = (a: string, b: string) => (a ? `${a}/${b}` : b)
  if (options.folder === "none") return base
  const name =
    options.folder === "keep"
      ? (sourcePath.split("/").filter(Boolean).at(-1) ?? "")
      : (options.subfolder ?? "").trim()
  return name ? join(base, name) : base
}

export interface DeletePlan {
  /** Every file of every rejected shot (a pair deletes both, plus same-stem extras). */
  files: CullFile[]
  shots: string[]
  bytes: number
}

export function planDeleteRejects(order: readonly CullShot[], decisions: Decisions): DeletePlan {
  const files: CullFile[] = []
  const shots: string[] = []
  for (const shot of order) {
    if (decisions.get(shot.id)?.flag !== "reject") continue
    shots.push(shot.id)
    for (const file of [shot.raw, shot.rendered, ...shot.others]) if (file) files.push(file)
  }
  return { files, shots, bytes: files.reduce((sum, f) => sum + f.size, 0) }
}

// --- Prefetch --------------------------------------------------------------------------------

/**
 * The ids sent to `cullPrefetch` for the shot at `index`: a slice of the viewing order around it
 * (native prepares `ahead` after and `behind` before), so a swipe never ships the whole folder.
 */
export function prefetchSlice(
  order: readonly string[],
  index: number,
  ahead: number,
  behind: number
): { shotIds: string[]; current: number } {
  if (order.length === 0 || index < 0 || index >= order.length) return { shotIds: [], current: 0 }
  const start = Math.max(0, index - behind)
  const end = Math.min(order.length, index + ahead + 1)
  return { shotIds: order.slice(start, end), current: index - start }
}

// --- 100% zoom tiles -------------------------------------------------------------------------

/** Output size of a tile in pixels (a tile at sample s covers TILE_SIZE·s image pixels). */
export const TILE_SIZE = 512

/** Largest sample (power of two, ≤ 16) that still gives at least one image pixel per device pixel. */
export function sampleForScale(devicePxPerImagePx: number): number {
  if (!(devicePxPerImagePx > 0)) return 16
  let sample = 1
  while (sample < 16 && 1 / (sample * 2) >= devicePxPerImagePx) sample *= 2
  return sample
}

export interface Tile {
  key: string
  x: number
  y: number
  width: number
  height: number
  sample: number
}

/**
 * Tiles covering the visible rectangle (image pixels) at `sample`, aligned to a grid of
 * TILE_SIZE·sample so the same tiles come back while panning (and hit the disk cache). Ordered
 * from the center outward, so the middle of the screen sharpens first.
 */
export function tilesFor(
  visible: { x: number; y: number; width: number; height: number },
  imageWidth: number,
  imageHeight: number,
  sample: number
): Tile[] {
  const step = TILE_SIZE * sample
  const left = Math.max(0, Math.floor(visible.x / step))
  const top = Math.max(0, Math.floor(visible.y / step))
  const right = Math.min(
    Math.ceil(imageWidth / step),
    Math.ceil((visible.x + visible.width) / step)
  )
  const bottom = Math.min(
    Math.ceil(imageHeight / step),
    Math.ceil((visible.y + visible.height) / step)
  )
  const cx = visible.x + visible.width / 2
  const cy = visible.y + visible.height / 2
  const tiles: (Tile & { distance: number })[] = []
  for (let row = top; row < bottom; row++) {
    for (let col = left; col < right; col++) {
      const x = col * step
      const y = row * step
      const width = Math.min(step, imageWidth - x)
      const height = Math.min(step, imageHeight - y)
      tiles.push({
        key: `${sample}:${x}:${y}`,
        x,
        y,
        width,
        height,
        sample,
        distance: Math.hypot(x + width / 2 - cx, y + height / 2 - cy),
      })
    }
  }
  return tiles.sort((a, b) => a.distance - b.distance).map(({ distance: _d, ...tile }) => tile)
}
