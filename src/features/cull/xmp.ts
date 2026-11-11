/**
 * XMP sidecars of culling decisions, the JavaScript side (ADR 0011). Native reads, merges and writes the files
 * (`dev.hfps.hoard.xmp`, one module for culling and "Copy picks"); here: decisions ↔ wire fields, what "Copy picks"
 * asks the transfer engine to write, and the precedence rule that decides whether a sidecar changed elsewhere wins
 * over Hoard's database. Pure (tested in `xmp.test.ts`).
 */
import type { CullFlag, CullSidecarRecord } from "@/db/types"
import type {
  CullFile,
  CullShot,
  CullWireFlag,
  SidecarFields,
  TransferSidecar,
} from "@/plugins/smb/definitions"
import { photoStem } from "@/lib/photos"
import {
  type CopyPlanItem,
  type CullDecision,
  type Decisions,
  decisionOf,
  sameDecision,
} from "./model"

/** A decision on the wire: `{ rating, flag }` (`none` = unmarked; a reject keeps its stars here). */
export function wireOf(decision: CullDecision): { rating: number; flag: CullWireFlag } {
  return { rating: decision.rating, flag: decision.flag ?? "none" }
}

/**
 * One sidecar per copied shot, attached to its RAW when the RAW is copied, else to the first file copied (the engine
 * names a new one after that file's destination stem, so a RAW + JPEG pair shares one `<stem>.xmp`, as Lightroom
 * expects). Rejects are never copied.
 */
export function sidecarsFor(
  items: readonly CopyPlanItem[],
  order: readonly CullShot[],
  decisions: Decisions
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
    const decision = decisionOf(decisions, shotId)
    if (!shot || decision.flag === "reject") continue
    const target = files.find((f) => f.path === shot.raw?.path) ?? files[0]
    out.push({ sourcePath: target.path, ...wireOf(decision) })
  }
  return out
}

/**
 * Reveal's `CULLING` values (ADR 0011) for the files sent: each photo whose shot (its lower-case stem, as culling
 * keys decisions) has a decision gets it, the RAW and the JPEG alike. A reject sends its stars too.
 */
export function revealCullingFor(
  paths: readonly string[],
  decisions: Decisions
): Record<string, { rating: number; flag: CullWireFlag }> {
  const out: Record<string, { rating: number; flag: CullWireFlag }> = {}
  for (const path of paths) {
    const decision = decisions.get(photoStem(path.slice(path.lastIndexOf("/") + 1)).toLowerCase())
    if (!decision || (!decision.flag && decision.rating === 0)) continue
    out[path] = wireOf(decision)
  }
  return out
}

// --- Precedence (ADR 0011 "Reading sidecars") ---------------------------------------------------------------------

/** What Hoard knows about a shot's sidecar and decision (the `cull_sidecars` row, plus a pre-1.9.0 decision time). */
export interface SidecarState {
  /** Hoard's last decision on the shot (ms); for decisions older than 1.9.0, `cull_decisions.updated_at`. */
  decidedAt: number | null
  /** The sidecar's mtime (ms) when Hoard last wrote or imported it. */
  sidecarMtime: number | null
  /** The sidecar held Hoard's latest decision at `sidecarMtime` (it was written, or imported, after the decision). */
  synced: boolean
}

export function sidecarStateOf(
  row: Pick<CullSidecarRecord, "decided_at" | "sidecar_mtime" | "synced"> | undefined,
  decisionUpdatedAt: string | undefined
): SidecarState {
  const legacy = decisionUpdatedAt ? Date.parse(decisionUpdatedAt) : NaN
  return {
    decidedAt: row?.decided_at ?? (Number.isFinite(legacy) ? legacy : null),
    sidecarMtime: row?.sidecar_mtime ?? null,
    synced: Boolean(row?.synced),
  }
}

/**
 * The mtime a shot's sidecar must be **later than** to win over Hoard's database, or null when any sidecar wins (Hoard
 * never decided on the shot).
 *
 * - The sidecar holds Hoard's latest decision (`synced`): it wins only when it changed since (its mtime is later
 *   than the one Hoard wrote or imported). Both times come from the same clock (the file system's), so a phone
 *   whose clock differs from an SMB server's does not matter.
 * - Otherwise (a decision not written: a read-only folder, a failed write, a decision older than 1.9.0): it wins
 *   when it is later than that decision (the phone's clock against the file's).
 */
export function importAfter(state: SidecarState): number | null {
  if (state.synced && state.sidecarMtime !== null) return state.sidecarMtime
  if (state.decidedAt === null) return state.sidecarMtime
  return Math.max(state.decidedAt, state.sidecarMtime ?? -Infinity)
}

/**
 * The decision a sidecar newer than {@link importAfter} brings, or null when it brings nothing (no culling value in
 * it: develop settings only) — Hoard's decision then stays. A reject keeps Hoard's stars (the file cannot hold
 * them); `xmp:Label` is not a Hoard decision.
 */
export function importedDecision(
  current: CullDecision,
  fields: SidecarFields,
  hasValues: boolean
): CullDecision | null {
  if (!hasValues) return null
  const flag: CullFlag | null = fields.flag === "none" ? null : fields.flag
  const rating =
    flag === "reject" ? current.rating : Math.max(0, Math.min(5, Math.round(fields.rating)))
  return { flag, rating }
}

/** The import changes the decision (else only the stored mtime is updated). */
export const importChanges = (current: CullDecision, next: CullDecision) =>
  !sameDecision(current, next)
