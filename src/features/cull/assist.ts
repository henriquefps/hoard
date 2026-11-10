/**
 * Assisted culling (ADR 0009), pure and unit tested in `assist.test.ts`: flags from native
 * measurements and the user's sensitivity, burst grouping by capture time and visual similarity,
 * ranking inside a burst, and suggestions (keep the best of each burst, reject the rest and every
 * flagged shot). Suggestions never decide anything by themselves: the UI shows them and the user
 * accepts them.
 */
import type { CullAnalysis, CullFile, CullMetadata, CullShot } from "@/plugins/smb/definitions"
import type { BurstPosition, Decisions } from "./model"

/** Native `ShotAnalysis.VERSION`: cached rows with another version are measured again. */
export const ANALYSIS_VERSION = 1

export type Sensitivity = "low" | "medium" | "high"

export interface AssistSettings {
  blur: Sensitivity
  eyes: Sensitivity
  /** Frames at most this far apart (capture time) start as one burst. */
  burstGapMs: number
}

export const DEFAULT_ASSIST: AssistSettings = { blur: "medium", eyes: "medium", burstGapMs: 1000 }

export const BURST_GAPS_MS = [500, 1000, 2000, 3000] as const

/**
 * "Blurry" when the subject's Crété-Roffet blur (0 sharp .. 1 flat) reaches this. Calibrated on
 * 31 ZV-E10 shots and synthetic blur of them (ADR 0009): with faces as the subject, medium flags
 * 0 of 31 real frames, 30 of 31 with a Gaussian blur of σ = 1.5 px and 31 of 31 with a 12 px
 * motion blur at analysis size.
 */
export const BLUR_THRESHOLDS: Record<Sensitivity, number> = { low: 0.68, medium: 0.6, high: 0.53 }

/**
 * "Eyes closed" when a prominent face has both eye-open probabilities (ML Kit) below this.
 * Higher sensitivity flags half-closed eyes too.
 */
export const EYES_CLOSED_THRESHOLDS: Record<Sensitivity, number> = {
  low: 0.15,
  medium: 0.3,
  high: 0.5,
}

/** Exposure rules (shares of pixels and mean luma, 0..1), calibrated with ±1 and ±2 EV. */
export const EXPOSURE_RULES = {
  /** Over: at least this share of blown highlights (luma ≥ 250)… */
  overHighlights: 0.1,
  /** …or a mean this bright. */
  overMean: 0.7,
  /** Under: at least this share of crushed shadows (luma ≤ 5)… */
  underShadows: 0.08,
  /** …or a mean this dark. */
  underMean: 0.2,
} as const

/** Burst similarity rules on the 64-bit dHash and the 4 × 4 colour signature. */
export const SIMILARITY_RULES = {
  /** A new scene inside the time gap: the hashes differ this much… */
  sceneChangeHamming: 21,
  /** …and the colours at least this much (mean absolute difference, 0..1)… */
  sceneChangeColor: 0.04,
  /** …or the colours alone differ this much. */
  sceneChangeColorAlone: 0.15,
  /** Near-identical frames (joined even a little over the gap): hashes this close… */
  nearHamming: 12,
  /** …and colours this close. */
  nearColor: 0.06,
  /** Near-identical frames join when at most this many gaps apart. */
  joinGapFactor: 4,
} as const

export type ShotFlag = "blurry" | "eyesClosed" | "overexposed" | "underexposed"
export const SHOT_FLAGS: ShotFlag[] = ["blurry", "eyesClosed", "overexposed", "underexposed"]

/** The shot's file that is analysed and keys the cache: its RAW (preview), else its JPEG. */
export const analysedFile = (shot: CullShot): CullFile => (shot.raw ?? shot.rendered)!

/** 0..100 for display: 100 × (1 − subject blur). Comparable across scenes far better than raw variance. */
export const sharpnessScore = (analysis: CullAnalysis) =>
  Math.round(100 * (1 - analysis.sharpness.subjectBlur))

/** Both eyes of a prominent face below the threshold (unknown eyes never count as closed). */
export function eyesClosed(analysis: CullAnalysis, sensitivity: Sensitivity): boolean {
  const limit = EYES_CLOSED_THRESHOLDS[sensitivity]
  return analysis.faces.some(
    (f) =>
      f.prominent &&
      f.leftEyeOpen !== undefined &&
      f.rightEyeOpen !== undefined &&
      f.leftEyeOpen < limit &&
      f.rightEyeOpen < limit
  )
}

/** Prominent faces whose eyes are known and open. */
export function eyesOpen(analysis: CullAnalysis, sensitivity: Sensitivity): boolean {
  const prominent = analysis.faces.filter(
    (f) => f.prominent && f.leftEyeOpen !== undefined && f.rightEyeOpen !== undefined
  )
  return prominent.length > 0 && !eyesClosed(analysis, sensitivity)
}

export function flagsOf(analysis: CullAnalysis, settings: AssistSettings): ShotFlag[] {
  const flags: ShotFlag[] = []
  if (analysis.sharpness.subjectBlur >= BLUR_THRESHOLDS[settings.blur]) flags.push("blurry")
  if (eyesClosed(analysis, settings.eyes)) flags.push("eyesClosed")
  const e = analysis.exposure
  if (e.highlights >= EXPOSURE_RULES.overHighlights || e.mean >= EXPOSURE_RULES.overMean)
    flags.push("overexposed")
  else if (e.shadows >= EXPOSURE_RULES.underShadows || e.mean <= EXPOSURE_RULES.underMean)
    flags.push("underexposed")
  return flags
}

// --- Similarity and bursts -------------------------------------------------------------------

const POPCOUNT = Array.from(
  { length: 16 },
  (_, n) => (n & 1) + ((n >> 1) & 1) + ((n >> 2) & 1) + ((n >> 3) & 1)
)

/** Differing bits of two 16-digit hex hashes (64 when malformed). */
export function hammingDistance(a: string, b: string): number {
  if (a.length !== b.length || a.length === 0) return 64
  let bits = 0
  for (let i = 0; i < a.length; i++) {
    const x = parseInt(a[i], 16)
    const y = parseInt(b[i], 16)
    if (Number.isNaN(x) || Number.isNaN(y)) return 64
    bits += POPCOUNT[x ^ y]
  }
  return bits
}

/** Mean absolute difference of two colour signatures (hex bytes), 0..1 (1 when malformed). */
export function colorDistance(a: string, b: string): number {
  if (a.length !== b.length || a.length === 0 || a.length % 2) return 1
  let sum = 0
  for (let i = 0; i < a.length; i += 2) {
    sum += Math.abs(parseInt(a.slice(i, i + 2), 16) - parseInt(b.slice(i, i + 2), 16))
  }
  return sum / (a.length / 2) / 255
}

export interface Similarity {
  hamming: number
  color: number
}

export const similarityOf = (a: CullAnalysis, b: CullAnalysis): Similarity => ({
  hamming: hammingDistance(a.hash, b.hash),
  color: colorDistance(a.colors, b.colors),
})

export const isSceneChange = (s: Similarity) =>
  (s.hamming >= SIMILARITY_RULES.sceneChangeHamming &&
    s.color >= SIMILARITY_RULES.sceneChangeColor) ||
  s.color >= SIMILARITY_RULES.sceneChangeColorAlone

export const isNearIdentical = (s: Similarity) =>
  s.hamming <= SIMILARITY_RULES.nearHamming && s.color <= SIMILARITY_RULES.nearColor

/**
 * Bursts in capture order. Each shot joins the previous one when
 * 1. it was taken at most `gapMs` later and the frames are not a scene change (unknown
 *    similarity — not analysed yet — never splits), or
 * 2. it was taken at most `joinGapFactor × gapMs` later and the frames are near-identical.
 * Shots without a capture time never join. Runs of 2+ are bursts (id = first shot's id).
 */
export function groupBursts(
  ordered: readonly CullShot[],
  metadata: ReadonlyMap<string, CullMetadata | undefined>,
  analyses: ReadonlyMap<string, CullAnalysis>,
  gapMs: number
): Map<string, BurstPosition> {
  const result = new Map<string, BurstPosition>()
  let run: string[] = []
  const flush = () => {
    if (run.length >= 2)
      run.forEach((id, index) => result.set(id, { burst: run[0], index, size: run.length }))
    run = []
  }
  let previous: CullShot | undefined
  for (const shot of ordered) {
    const time = metadata.get(shot.id)?.captureMillis
    const before = previous ? metadata.get(previous.id)?.captureMillis : undefined
    let join = false
    if (previous && time !== undefined && before !== undefined && time >= before) {
      const gap = time - before
      const a = analyses.get(previous.id)
      const b = analyses.get(shot.id)
      const similarity = a && b ? similarityOf(a, b) : undefined
      if (gap <= gapMs) join = !similarity || !isSceneChange(similarity)
      else if (gap <= gapMs * SIMILARITY_RULES.joinGapFactor)
        join = Boolean(similarity && isNearIdentical(similarity))
    }
    if (!join) flush()
    run.push(shot.id)
    previous = shot
  }
  flush()
  return result
}

/** Burst id → its shot ids in order. */
export function burstMembers(
  ordered: readonly CullShot[],
  bursts: ReadonlyMap<string, BurstPosition>
): Map<string, string[]> {
  const members = new Map<string, string[]>()
  for (const shot of ordered) {
    const position = bursts.get(shot.id)
    if (!position) continue
    const list = members.get(position.burst) ?? []
    list.push(shot.id)
    members.set(position.burst, list)
  }
  return members
}

// --- Ranking and suggestions -----------------------------------------------------------------

/** Penalties in the ranking score (sharpness relative to the burst's sharpest is 0..1). */
export const RANK_PENALTIES: Record<ShotFlag, number> = {
  eyesClosed: 1,
  blurry: 0.6,
  overexposed: 0.4,
  underexposed: 0.4,
}

/** A frame sharper than this share of the best one still counts as "as sharp". */
export const BLURRIER_RATIO = 0.8

export interface Ranked {
  shotId: string
  /** Subject Laplacian variance relative to the burst's sharpest, 0..1. */
  relativeSharpness: number
  score: number
  flags: ShotFlag[]
}

/**
 * Frames of one burst, best first: score = subject sharpness relative to the sharpest frame of
 * the burst (Laplacian variance, comparable inside one scene) minus a penalty per flag. Ties keep
 * capture order. Frames without an analysis are left out.
 */
export function rankBurst(
  shotIds: readonly string[],
  analyses: ReadonlyMap<string, CullAnalysis>,
  settings: AssistSettings
): Ranked[] {
  const known = shotIds.filter((id) => analyses.has(id))
  const max = Math.max(0, ...known.map((id) => analyses.get(id)!.sharpness.subject))
  return known
    .map((shotId, order) => {
      const a = analyses.get(shotId)!
      const flags = flagsOf(a, settings)
      const relativeSharpness = max > 0 ? a.sharpness.subject / max : 1
      const score = relativeSharpness - flags.reduce((sum, f) => sum + RANK_PENALTIES[f], 0)
      return { shotId, relativeSharpness, score, flags, order }
    })
    .sort((a, b) => b.score - a.score || a.order - b.order)
    .map(({ order: _order, ...r }) => r)
}

export type SuggestionReason =
  | { kind: "sharpest" }
  | { kind: "eyesOpen" }
  | { kind: "flag"; flag: ShotFlag }
  | { kind: "blurrierThan"; than: string }
  | { kind: "notBest"; than: string }
  | { kind: "allFlagged" }

export interface Suggestion {
  shotId: string
  /** `keep` → accepting picks it; `reject` → accepting rejects it. */
  action: "keep" | "reject"
  reasons: SuggestionReason[]
  /** The burst it belongs to (id of its first shot), when it is in one. */
  burst?: string
}

/**
 * Suggestions for `ordered` shots: in every fully analysed burst, keep the best frame (unless
 * every frame is flagged: then none is kept) and reject the others with why; outside bursts,
 * reject every flagged shot. Unflagged single shots get none. Already decided shots are left out
 * (the user's decision always wins). `stems` names shots in reasons ("blurrier than DSC07705").
 */
export function suggest(
  ordered: readonly CullShot[],
  bursts: ReadonlyMap<string, BurstPosition>,
  analyses: ReadonlyMap<string, CullAnalysis>,
  decisions: Decisions,
  settings: AssistSettings
): Map<string, Suggestion> {
  const out = new Map<string, Suggestion>()
  const stems = new Map(ordered.map((s) => [s.id, s.stem]))
  const undecided = (id: string) => !decisions.get(id)?.flag
  for (const [burst, ids] of burstMembers(ordered, bursts)) {
    if (!ids.every((id) => analyses.has(id))) continue
    const ranked = rankBurst(ids, analyses, settings)
    const best = ranked[0]
    const allFlagged = ranked.every((r) => r.flags.length > 0)
    const bestStem = stems.get(best.shotId) ?? best.shotId
    ranked.forEach((r, i) => {
      if (!undecided(r.shotId)) return
      if (i === 0 && !allFlagged) {
        const reasons: SuggestionReason[] = [{ kind: "sharpest" }]
        if (eyesOpen(analyses.get(r.shotId)!, settings.eyes)) reasons.push({ kind: "eyesOpen" })
        out.set(r.shotId, { shotId: r.shotId, action: "keep", reasons, burst })
        return
      }
      const reasons: SuggestionReason[] = r.flags.map((flag) => ({ kind: "flag", flag }))
      if (allFlagged && i === 0) reasons.push({ kind: "allFlagged" })
      else if (r.relativeSharpness < BLURRIER_RATIO * best.relativeSharpness)
        reasons.push({ kind: "blurrierThan", than: bestStem })
      else if (reasons.length === 0) reasons.push({ kind: "notBest", than: bestStem })
      out.set(r.shotId, { shotId: r.shotId, action: "reject", reasons, burst })
    })
  }
  for (const shot of ordered) {
    if (bursts.has(shot.id) || !undecided(shot.id)) continue
    const a = analyses.get(shot.id)
    if (!a) continue
    const flags = flagsOf(a, settings)
    if (flags.length)
      out.set(shot.id, {
        shotId: shot.id,
        action: "reject",
        reasons: flags.map((flag) => ({ kind: "flag", flag })),
      })
  }
  return out
}

/** The best frame of each burst (fully analysed or not: the best of what is known). */
export function bestInBursts(
  ordered: readonly CullShot[],
  bursts: ReadonlyMap<string, BurstPosition>,
  analyses: ReadonlyMap<string, CullAnalysis>,
  settings: AssistSettings
): Map<string, string> {
  const best = new Map<string, string>()
  for (const [burst, ids] of burstMembers(ordered, bursts)) {
    const top = rankBurst(ids, analyses, settings)[0]
    if (top) best.set(burst, top.shotId)
  }
  return best
}

/** The two frames compare opens with: the burst's top two, else the shot and its neighbour. */
export function comparePair(
  shotId: string,
  order: readonly string[],
  bursts: ReadonlyMap<string, BurstPosition>,
  members: ReadonlyMap<string, string[]>,
  analyses: ReadonlyMap<string, CullAnalysis>,
  settings: AssistSettings
): [string, string] | null {
  const burst = bursts.get(shotId)?.burst
  const ids = burst ? members.get(burst) : undefined
  if (ids && ids.length >= 2) {
    const ranked = rankBurst(ids, analyses, settings).map((r) => r.shotId)
    const rest = ids.filter((id) => !ranked.includes(id))
    const [a, b] = [...ranked, ...rest]
    return [a, b]
  }
  const at = order.indexOf(shotId)
  const other = order[at + 1] ?? order[at - 1]
  return other ? [shotId, other] : null
}

// --- Order and filters -----------------------------------------------------------------------

export type CullSort = "time" | "sharpness"

/** Sharpest first (lowest subject blur); shots not analysed yet keep their order at the end. */
export function sortBySharpness(
  shots: readonly CullShot[],
  analyses: ReadonlyMap<string, CullAnalysis>
): CullShot[] {
  const index = new Map(shots.map((s, i) => [s.id, i]))
  return [...shots].sort((a, b) => {
    const x = analyses.get(a.id)?.sharpness.subjectBlur
    const y = analyses.get(b.id)?.sharpness.subjectBlur
    if (x !== undefined && y !== undefined && x !== y) return x - y
    if (x === undefined && y !== undefined) return 1
    if (x !== undefined && y === undefined) return -1
    return index.get(a.id)! - index.get(b.id)!
  })
}

export type AssistFilter = "any" | ShotFlag | "flagged" | "clean"

export function matchesAssist(flags: readonly ShotFlag[] | undefined, filter: AssistFilter) {
  if (filter === "any") return true
  if (!flags) return false
  if (filter === "flagged") return flags.length > 0
  if (filter === "clean") return flags.length === 0
  return flags.includes(filter)
}

// --- Grid with stacks ------------------------------------------------------------------------

export type GridItem =
  | { kind: "shot"; shotId: string; index: number }
  | { kind: "stack"; burst: string; shotId: string; index: number; size: number }

/**
 * Grid cells for `shots` (already filtered and sorted): with `stack`, the frames of a burst
 * collapse into one cell showing its best frame (or the first), unless the burst is in
 * `expanded`. `index` is the shot's position in `shots` (what the viewer opens).
 */
export function gridItems(
  shots: readonly CullShot[],
  bursts: ReadonlyMap<string, BurstPosition>,
  best: ReadonlyMap<string, string>,
  expanded: ReadonlySet<string>,
  stack: boolean
): GridItem[] {
  if (!stack) return shots.map((s, index) => ({ kind: "shot", shotId: s.id, index }))
  const count = new Map<string, number>()
  for (const s of shots) {
    const b = bursts.get(s.id)?.burst
    if (b) count.set(b, (count.get(b) ?? 0) + 1)
  }
  const items: GridItem[] = []
  const seen = new Set<string>()
  shots.forEach((s, index) => {
    const b = bursts.get(s.id)?.burst
    if (!b || (count.get(b) ?? 0) < 2 || expanded.has(b)) {
      items.push({ kind: "shot", shotId: s.id, index })
      return
    }
    if (seen.has(b)) return
    seen.add(b)
    const cover = best.get(b)
    const coverIndex = cover ? shots.findIndex((x) => x.id === cover) : -1
    const at = coverIndex >= 0 ? coverIndex : index
    items.push({ kind: "stack", burst: b, shotId: shots[at].id, index: at, size: count.get(b)! })
  })
  return items
}
