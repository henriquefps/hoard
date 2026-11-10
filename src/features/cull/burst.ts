/**
 * Burst mode (ADR 0008 "Burst mode", 1.7.0), pure and unit tested in `burst.test.ts`: which frame
 * a burst shows (grid cover, folder viewer), whether it is resolved, how the folder viewer steps
 * over a burst as one item, and "Keep this one".
 */
import type { BurstPosition, CullDecision, Decisions, DecisionChange } from "./model"
import { changeOf, withFlag } from "./model"

/** A frame the user kept explicitly: picked, or given stars (stars mean "keep, this much"). */
export const keptExplicitly = (d: CullDecision | undefined) =>
  d?.flag === "pick" || (d?.rating ?? 0) > 0

/**
 * The frame a burst shows (grid stack, folder viewer): the first picked frame in capture order
 * (the user's choice), else the crowned best (ADR 0009: top-ranked and unflagged), else the first
 * frame that is not rejected, else the first frame.
 */
export function burstCover(
  ids: readonly string[],
  decisions: Decisions,
  best: string | undefined
): string {
  const picked = ids.find((id) => decisions.get(id)?.flag === "pick")
  if (picked) return picked
  if (best && ids.includes(best) && decisions.get(best)?.flag !== "reject") return best
  return ids.find((id) => decisions.get(id)?.flag !== "reject") ?? ids[0]
}

export interface BurstStatus {
  size: number
  /** Frames picked. */
  kept: number
  /** Frames picked or rejected. */
  decided: number
  /** Every frame is picked or rejected. */
  resolved: boolean
}

export function burstStatus(ids: readonly string[], decisions: Decisions): BurstStatus {
  let kept = 0
  let decided = 0
  for (const id of ids) {
    const flag = decisions.get(id)?.flag
    if (flag) decided++
    if (flag === "pick") kept++
  }
  return { size: ids.length, kept, decided, resolved: ids.length > 0 && decided === ids.length }
}

/**
 * "Keep this one": picks `keep` and rejects the other frames of the burst, as one undo step.
 *
 * Frames the user kept explicitly — picked, or rated with stars — are left alone: several keepers
 * stay possible (pick another frame with ✓ before or after), and "Keep this one" never un-picks a
 * frame the user chose. Frames already rejected stay rejected. `keep` keeps its stars. Returns
 * null when nothing would change.
 */
export function keepThisOne(
  decisions: Decisions,
  ids: readonly string[],
  keep: string
): DecisionChange | null {
  if (!ids.includes(keep)) return null
  return changeOf(decisions, ids, (d, id) => {
    if (id === keep) return withFlag(d, "pick")
    if (keptExplicitly(d)) return d
    return withFlag(d, "reject")
  })
}

/** One step of the viewer: a single shot, or a whole burst (shown by its cover). */
export type ViewerStep =
  { kind: "shot"; shotId: string } | { kind: "burst"; burst: string; shotIds: string[] }

/**
 * The folder viewer's steps for `order` (the grid's shots, filtered and sorted): with `collapse`,
 * the frames of a burst become one step where its first frame was (a burst whose frames are not
 * all in `order` is never collapsed: filters such as "Rejected" show individual frames). Without
 * `collapse`, one step per shot.
 */
export function viewerSteps(
  order: readonly string[],
  bursts: ReadonlyMap<string, BurstPosition>,
  members: ReadonlyMap<string, readonly string[]>,
  collapse: boolean
): ViewerStep[] {
  if (!collapse) return order.map((shotId) => ({ kind: "shot", shotId }))
  const inOrder = new Set(order)
  const steps: ViewerStep[] = []
  const seen = new Set<string>()
  for (const shotId of order) {
    const burst = bursts.get(shotId)?.burst
    const ids = burst ? members.get(burst) : undefined
    if (!burst || !ids || ids.length < 2 || !ids.every((id) => inOrder.has(id))) {
      steps.push({ kind: "shot", shotId })
      continue
    }
    if (seen.has(burst)) continue
    seen.add(burst)
    steps.push({ kind: "burst", burst, shotIds: [...ids] })
  }
  return steps
}

/** The step showing `shotId` (its own step, or its burst's). */
export const stepOf = (steps: readonly ViewerStep[], shotId: string) =>
  steps.findIndex((s) => (s.kind === "shot" ? s.shotId === shotId : s.shotIds.includes(shotId)))

/** The shot a step shows now (a burst: its cover, which follows the decisions). */
export function stepShot(
  step: ViewerStep,
  decisions: Decisions,
  best: ReadonlyMap<string, string>
): string {
  return step.kind === "shot"
    ? step.shotId
    : burstCover(step.shotIds, decisions, best.get(step.burst))
}

/**
 * The other frame quick compare opens with in burst mode: the burst's top-ranked frame, or — when
 * the frame on screen is the top one — the runner-up, else the next frame.
 */
export function comparePartner(
  current: string,
  ids: readonly string[],
  ranked: readonly string[]
): string | undefined {
  const other = ranked.find((id) => id !== current && ids.includes(id))
  if (other) return other
  const at = ids.indexOf(current)
  return ids[at + 1] ?? ids[at - 1]
}
