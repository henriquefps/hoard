import type { Flag, Row } from "./types"

/**
 * Culling in the web gallery, with Hoard's semantics (ADR 0008, src/features/cull/model.ts and CullViewer):
 * P and X toggle pick and reject, U clears the flag, 1-5 set the stars (the same number again clears them), 0 clears
 * them; rating a rejected shot makes it a pick (stars mean "keep, this much"). Stars survive a reject (ADR 0016).
 * Writes go to POST /web/culling per field, with the field's revision as baseRev.
 */
export interface Decision {
  flag: Flag | null
  rating: number
}

export type CullField = "rating" | "flag"

export interface FieldChange {
  itemId: string
  field: CullField
  value: number | "pick" | "reject" | "none"
  baseRev: number
  modifiedAt: number
}

export type CullAction =
  | { kind: "flag"; flag: Flag; mode: "toggle" | "set" }
  | { kind: "unflag" }
  | { kind: "rate"; stars: number; mode: "toggle" | "set" }

export const decisionOf = (row: Row): Decision => ({ flag: row.x ?? null, rating: row.r ?? 0 })

/** The decision after an action. */
export function apply(d: Decision, a: CullAction): Decision {
  switch (a.kind) {
    case "flag":
      return { flag: a.mode === "toggle" && d.flag === a.flag ? null : a.flag, rating: d.rating }
    case "unflag":
      return { flag: null, rating: d.rating }
    case "rate": {
      const stars = Math.max(0, Math.min(5, Math.round(a.stars)))
      const rating = a.mode === "toggle" && d.rating === stars ? 0 : stars
      return { flag: rating > 0 && d.flag === "reject" ? "pick" : d.flag, rating }
    }
  }
}

/** Keyboard keys to actions (lower-case `key` of a KeyboardEvent); null for other keys. */
export function actionForKey(key: string): CullAction | null {
  const k = key.toLowerCase()
  if (k === "p") return { kind: "flag", flag: "pick", mode: "toggle" }
  if (k === "x") return { kind: "flag", flag: "reject", mode: "toggle" }
  if (k === "u") return { kind: "unflag" }
  if (k === "0") return { kind: "rate", stars: 0, mode: "set" }
  if (/^[1-5]$/.test(k)) return { kind: "rate", stars: Number(k), mode: "toggle" }
  return null
}

/** The fields' revisions from a row's "rating.flag.label" (0 when never written). */
export function fieldRevs(row: Row): { rating: number; flag: number } {
  const [rating, flag] = (row.cr ?? "").split(".").map((v) => Number(v) || 0)
  return { rating: rating ?? 0, flag: flag ?? 0 }
}

/** The per-field changes that take `row` from `before` to `after` (nothing for a field that stays). */
export function changesFor(row: Row, before: Decision, after: Decision, now: number): FieldChange[] {
  const revs = fieldRevs(row)
  const out: FieldChange[] = []
  if (before.rating !== after.rating)
    out.push({ itemId: row.id, field: "rating", value: after.rating, baseRev: revs.rating, modifiedAt: now })
  if (before.flag !== after.flag)
    out.push({ itemId: row.id, field: "flag", value: after.flag ?? "none", baseRev: revs.flag, modifiedAt: now })
  return out
}

/** A row showing a decision (optimistic, before the server answers). */
export const withDecision = (row: Row, d: Decision): Row => ({
  ...row,
  x: d.flag ?? undefined,
  r: d.rating || undefined,
})

/** Applies the server's field revisions to a row's "cr" after a push. */
export function withRevs(row: Row, revs: Partial<Record<"rating" | "flag" | "label", number>>): Row {
  const [r, f, l] = (row.cr ?? "0.0.0").split(".").map((v) => Number(v) || 0)
  return { ...row, cr: `${revs.rating ?? r ?? 0}.${revs.flag ?? f ?? 0}.${revs.label ?? l ?? 0}` }
}

/** Pending changes collapse per item and field to the last one (ADR 0016 "Client rules" 2). */
export function collapse(queue: FieldChange[]): FieldChange[] {
  const last = new Map<string, FieldChange>()
  for (const c of queue) {
    const key = `${c.itemId}:${c.field}`
    const prev = last.get(key)
    // The first change's baseRev is what the device saw; later ones keep it (the server has not answered yet).
    last.delete(key)
    last.set(key, prev ? { ...c, baseRev: prev.baseRev } : c)
  }
  return [...last.values()]
}
