import { useSyncExternalStore } from "react"
import { api } from "./api"
import type { Row } from "./types"

/**
 * Video compression in the web gallery (ADR 0025 phase S3): the server's `compress` object, estimates, jobs, items and
 * the trash, through the gallery's twins of /v1/compress/* under /web/compress. The rules are Hoard 2.7.0's
 * (Amendment (A1)): every action needs the feature; a library (nas) action needs `targets.nas.ok`, a backup action
 * `targets.backup.ok`, otherwise the server's reason is shown instead of the estimate. Phone jobs need the phone itself:
 * the gallery lists them read-only.
 */

export type Target = "nas" | "phone" | "backup"

export interface TargetState {
  ok: boolean
  reason?: string
}

export interface Profile {
  id: string
  codec: string
  q: number
  default?: boolean
  optIn?: boolean
}

export interface CompressInfo {
  available: boolean
  reason: string | null
  targets?: Partial<Record<Target, TargetState>>
  gpu?: string
  attention?: { stalled: number; stalledSince: number | null; untrackedTrash: number }
  codecs?: string[]
  vmaf?: boolean
  nasWrite?: boolean
  offerAv1?: boolean
  trashDays?: number
  profiles?: Profile[]
}

export interface BackupRoot {
  id: string
  device: string
  physicalId: string
  volume: string
  path: string
  stopped?: boolean
}

export interface WebCompress {
  compress: CompressInfo | null
  roots: BackupRoot[]
}

export interface SelFolder {
  folderId?: string
  rootId?: string
  path: string
  recursive?: boolean
}

export interface Selection {
  folders?: SelFolder[]
  roots?: SelFolder[]
  itemIds?: string[]
}

/** One job to estimate and start: a target and what it covers. */
export interface Part {
  target: Target
  select: Selection
}

export interface EstimateItem {
  itemId: string
  fingerprint?: string
  name?: string
  path?: string
  size?: number
  durationMs?: number
  decision: "encode" | "skip" | "pending"
  reason?: string
  estSize?: number
  estSaving?: number
  estMs?: number
  warnings?: string[]
}

export interface EstimateTotals {
  items: number
  encode: number
  skip: Record<string, number>
  size: number
  estSize: number
  estMs: number
}

export interface EstimateAnswer {
  items: EstimateItem[]
  totals: EstimateTotals
  pending: number
  next: string | null
}

export interface Estimate {
  totals: EstimateTotals
  pending: number
  warnings: string[]
}

export interface Job {
  id: string
  target: Target
  name: string
  profile: string
  options?: { quality?: { mode: string; vmaf?: number }; set?: string }
  select: Selection
  auto: boolean
  priority: number
  state: "active" | "paused" | "cancelled" | "done"
  paused: boolean
  createdAt: number
  finishedAt: number | null
  createdBy: string
  counts: Record<string, number>
  skipped: Record<string, number>
  failed: Record<string, number>
  bytes: { original: number; output: number; saved: number; inTrash: number }
  progress: { done: number; toDo: number; etaMs: number; current?: { id: string; itemId: string; state: string; percent?: number } }
}

export interface CItem {
  id: string
  jobId: string
  itemId: string
  fingerprint: string
  target: Target
  source: string
  state: string
  reason: string
  size: number
  name?: string
  path?: string
  folderId?: string
  estSize?: number
  durationMs?: number
  q?: number
  outFingerprint?: string
  outSize?: number
  vmaf?: { mean: number; low: number; mode: string }
  encodeMs?: number
  verifyMs?: number
  decoder?: string
  metaSidecar: boolean
  purgeAfter?: number
  replacedAt?: number
  purgedAt?: number
  restoredAt?: number
  stalledAt?: number
  progress?: number
  logAvailable: boolean
  updatedAt: number
}

export interface TrashPlace {
  source: string
  folder?: string
  path?: string
  trash?: string
  device?: string
  held?: boolean
}

export interface TrashEntry {
  id: string
  kind: string
  name: string
  size: number
  trashedAt?: number
  places: TrashPlace[]
  held?: boolean
  live?: boolean
  compressedTo?: string
  durationMs?: number
  compression?: {
    id: string
    jobId: string
    state: string
    role: "original" | "output"
    size: number
    outSize?: number
    source: string
    purgeAfter?: number
    reason?: string
  }
}

/** Unfinished states of a compression item ("Compressing"). */
export const ACTIVE_STATES = ["queued", "estimating", "encoding", "verifying", "ready", "replacing"]
/** States whose trashed side can be restored or emptied. */
export const IN_TRASH_STATES = ["replaced", "restored"]

/** The known skip and failure reasons (others show as "other"). */
export const SKIP_REASONS = [
  "already_compressed",
  "older_compression",
  "efficient",
  "small_saving",
  "unreadable",
  "not_video",
  "dolby_vision",
  "reveal_record",
  "sync_artifact",
  "not_writable",
  "trashed",
  "busy",
  "not_in_target",
]
export const FAIL_REASONS = ["encoder", "source_changed", "no_space", "unreadable", "trash_failed", "not_writable"]
export const UNAVAILABLE_REASONS = [
  "no_gpu",
  "no_tools",
  "no_write_mount",
  "write_mount_mismatch",
  "write_mount_read_only",
  "unstable_inode",
  "no_backup_root",
]

/** i18n key of a skip reason. */
export const skipKey = (reason: string) => `compress.item.skip.${SKIP_REASONS.includes(reason) ? reason : "other"}`
/** i18n key of a failure reason (verify_* are one key). */
export const failKey = (reason: string) =>
  `compress.item.reason.${reason.startsWith("verify") ? "verify" : FAIL_REASONS.includes(reason) ? reason : "other"}`
/** i18n key of an unavailable reason. */
export const unavailableKey = (reason: string | undefined | null) =>
  `compress.unavailable.${reason && UNAVAILABLE_REASONS.includes(reason) ? reason : "other"}`

/** Whether the gallery shows compression at all: the feature, or a server that still reports jobs without it. */
export const compressShown = (info: CompressInfo | null | undefined): info is CompressInfo => !!info

/** Whether a target can take new jobs, and why not (the server's reason, Amendment (S2) "Targets"). */
export function targetGate(info: CompressInfo | null | undefined, target: Target): TargetState {
  if (!info) return { ok: false, reason: "no_gpu" }
  const t = info.targets?.[target]
  if (t) return t.ok ? { ok: true } : { ok: false, reason: t.reason ?? info.reason ?? undefined }
  // A server before targets (S1): the feature alone, nas also the write mount.
  if (!info.available) return { ok: false, reason: info.reason ?? "no_gpu" }
  if (target === "nas" && info.nasWrite === false) return { ok: false, reason: "no_write_mount" }
  return { ok: target !== "phone" }
}

/** The profiles a job may use: the server's list (the default first), AV1 only when offered, never for automatic folders. */
export function offeredProfiles(info: CompressInfo, auto: boolean): Profile[] {
  const list = info.profiles?.length
    ? info.profiles
    : [
        { id: "hevc-cq28", codec: "hevc", q: 28, default: true },
        { id: "hevc-cq30", codec: "hevc", q: 30 },
      ]
  return list.filter((p) => !(p.optIn || p.codec === "av1") || (!auto && info.offerAv1))
}

export const defaultProfile = (info: CompressInfo) => offeredProfiles(info, true).find((p) => p.default)?.id ?? "hevc-cq28"

/**
 * A gallery selection's parts: its videos in a NAS folder are a library (nas) job; its videos without one (uploads,
 * phone backups) a backup job, whose server answers `not_in_target` for those still on a phone. Photos are left out.
 */
export function selectionParts(rows: (Row | undefined)[], info: CompressInfo | null): { parts: Part[]; leftOut: number; videos: number } {
  const nas: string[] = []
  const backup: string[] = []
  let leftOut = 0
  for (const r of rows) {
    if (!r || r.k !== "video") continue
    if (r.fo) {
      if (targetGate(info, "nas").ok) nas.push(r.id)
      else leftOut++
    } else if (targetGate(info, "backup").ok) backup.push(r.id)
    else leftOut++
  }
  const parts: Part[] = []
  if (nas.length) parts.push({ target: "nas", select: { itemIds: nas } })
  if (backup.length) parts.push({ target: "backup", select: { itemIds: backup } })
  return { parts, leftOut, videos: nas.length + backup.length + leftOut }
}

/** The totals of several estimates (one per part), with the warnings of the videos that will be encoded. */
export function mergeEstimates(list: Estimate[]): Estimate {
  const totals: EstimateTotals = { items: 0, encode: 0, skip: {}, size: 0, estSize: 0, estMs: 0 }
  const warnings = new Set<string>()
  let pending = 0
  for (const e of list) {
    totals.items += e.totals.items
    totals.encode += e.totals.encode
    totals.size += e.totals.size
    totals.estSize += e.totals.estSize
    totals.estMs += e.totals.estMs
    for (const [k, v] of Object.entries(e.totals.skip ?? {})) totals.skip[k] = (totals.skip[k] ?? 0) + v
    for (const w of e.warnings) warnings.add(w)
    pending += e.pending
  }
  return { totals, pending, warnings: [...warnings].sort() }
}

/** Warnings of the encoded videos of an estimate page. */
export const pageWarnings = (items: EstimateItem[]) => [...new Set(items.filter((i) => i.decision === "encode").flatMap((i) => i.warnings ?? []))]

/** Skips sorted by count (largest first), "not_in_target" apart (shown as left out). */
export function skipList(skip: Record<string, number>): { reason: string; count: number }[] {
  return Object.entries(skip)
    .filter(([r, n]) => n > 0 && r !== "not_in_target")
    .map(([reason, count]) => ({ reason, count }))
    .sort((a, b) => b.count - a.count || a.reason.localeCompare(b.reason))
}

/** Whether a job's selection is the one standing job (auto) for a folder or root. */
export function sameFolder(sel: Selection, f: SelFolder): boolean {
  const list = f.rootId ? sel.roots : sel.folders
  if (!list || list.length !== 1) return false
  const s = list[0]
  return (f.rootId ? s.rootId === f.rootId : s.folderId === f.folderId) && (s.path ?? "") === (f.path ?? "")
}

/** Running jobs, automatic folders and the history (Settings › Compress videos, A1). */
export function splitJobs(jobs: Job[]): { running: Job[]; auto: Job[]; history: Job[] } {
  const running: Job[] = []
  const auto: Job[] = []
  const history: Job[] = []
  for (const j of jobs) {
    if (j.auto && j.state !== "cancelled") auto.push(j)
    if (j.auto) {
      if (j.progress.toDo > 0 && j.state !== "cancelled") running.push(j)
      else if (j.state === "cancelled") history.push(j)
      continue
    }
    if (j.state === "active" || j.state === "paused") running.push(j)
    else history.push(j)
  }
  return { running, auto, history }
}

/** Totals over every job: saved (purged), in the trash, items done. */
export function jobTotals(jobs: Job[]): { saved: number; inTrash: number; replaced: number } {
  let saved = 0
  let inTrash = 0
  let replaced = 0
  for (const j of jobs) {
    saved += j.bytes.saved
    inTrash += j.bytes.inTrash
    replaced += (j.counts.replaced ?? 0) + (j.counts.purged ?? 0)
  }
  return { saved, inTrash, replaced }
}

/** 0–1 progress of a job (the current item's percent counted in). */
export function jobFraction(j: Job): number {
  const total = j.progress.done + j.progress.toDo
  if (!total) return 1
  const cur = (j.progress.current?.percent ?? 0) / 100
  return Math.min(1, (j.progress.done + cur) / total)
}

/** What the trash can do with an entry: a compressed original is restored and emptied through the server's routes; a held
 * entry is never touched; a plain trashed file belongs to Hoard (the server never writes the NAS for it). */
export function trashActions(e: TrashEntry): "compressed" | "held" | "plain" | "busy" {
  if (e.held) return "held"
  if (!e.compressedTo || !e.compression) return "plain"
  return IN_TRASH_STATES.includes(e.compression.state) ? "compressed" : "busy"
}

/** Bytes emptying a compressed original saves: the trashed side's size. */
export const trashSaves = (e: TrashEntry) =>
  e.compression ? (e.compression.role === "output" ? (e.compression.outSize ?? e.size) : e.compression.size) : e.size

export const newRequestId = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`

// --- API --------------------------------------------------------------------------------------------------------------

/** Reads every estimate page of a part (warnings from the pages, totals from the server). */
export async function estimatePart(part: Part, profile: string, options?: unknown): Promise<Estimate> {
  let cursor: string | null = null
  let first: EstimateAnswer | null = null
  const warnings = new Set<string>()
  do {
    const ans: EstimateAnswer = await api<EstimateAnswer>("/web/compress/estimate", {
      method: "POST",
      json: { ...part, profile, options, cursor: cursor ?? undefined },
    })
    first ??= ans
    pageWarnings(ans.items).forEach((w) => warnings.add(w))
    cursor = ans.next
  } while (cursor)
  return { totals: first.totals, pending: first.pending, warnings: [...warnings] }
}

export interface JobAnswer extends Job {
  queued?: number
  busy?: string[]
}

export const startJob = (part: Part, profile: string, options: unknown, auto = false, name = "") =>
  api<JobAnswer>("/web/compress/jobs", { method: "POST", json: { ...part, profile, options, auto, name, requestId: newRequestId() } })

export const patchJob = (id: string, body: { paused?: boolean; auto?: false }) => api<Job>(`/web/compress/jobs/${id}`, { method: "PATCH", json: body })

export const cancelJob = (jobId: string) => api<{ items: Record<string, { state?: string; error?: string }> }>("/web/compress/cancel", { method: "POST", json: { jobId } })

export type BatchAnswer = { items: Record<string, { id?: string; state?: string; error?: string; alsoKeptBy?: string[] }> }

export const restoreItems = (itemIds: string[]) => api<BatchAnswer>("/web/compress/restore", { method: "POST", json: { itemIds } })
export const purgeItems = (itemIds: string[]) => api<BatchAnswer>("/web/compress/purge", { method: "POST", json: { itemIds } })

export async function listJobs(): Promise<Job[]> {
  const out: Job[] = []
  let cursor = ""
  for (let i = 0; i < 20; i++) {
    const page = await api<{ jobs: Job[]; cursor: string | null; hasMore: boolean }>(`/web/compress/jobs?limit=100${cursor ? `&cursor=${cursor}` : ""}`)
    out.push(...page.jobs)
    if (!page.hasMore || !page.cursor) break
    cursor = page.cursor
  }
  return out
}

export async function listItems(q: { jobId?: string; states?: string[] }): Promise<CItem[]> {
  const out: CItem[] = []
  let cursor = ""
  for (let i = 0; i < 50; i++) {
    const qs = new URLSearchParams({ limit: "500" })
    if (q.jobId) qs.set("jobId", q.jobId)
    if (q.states?.length) qs.set("state", q.states.join(","))
    if (cursor) qs.set("cursor", cursor)
    const page = await api<{ items: CItem[]; cursor: string | null; hasMore: boolean }>(`/web/compress/items?${qs}`)
    out.push(...page.items)
    if (!page.hasMore || !page.cursor) break
    cursor = page.cursor
  }
  return out
}

export async function listTrash(): Promise<TrashEntry[]> {
  const out: TrashEntry[] = []
  let cursor = ""
  for (let i = 0; i < 50; i++) {
    const page = await api<{ items: TrashEntry[]; cursor: string | null; hasMore: boolean }>(`/web/trash${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`)
    out.push(...page.items)
    if (!page.hasMore || !page.cursor) break
    cursor = page.cursor
  }
  return out
}

export const logUrl = (id: string) => `/web/compress/items/${id}/log`
export const metaUrl = (id: string) => `/web/compress/items/${id}/meta`

// --- store: the compress object, the backup roots and the items being compressed (badges) ----------------------------

export interface CompressState {
  loaded: boolean
  info: CompressInfo | null
  roots: BackupRoot[]
  /** Gallery items with an unfinished compression ("Compressing"). */
  compressing: ReadonlySet<string>
  /** Changes on each refresh (pages reload their lists on it). */
  tick: number
}

let cstate: CompressState = { loaded: false, info: null, roots: [], compressing: new Set(), tick: 0 }
const clisteners = new Set<() => void>()
const cset = (next: Partial<CompressState>) => {
  cstate = { ...cstate, ...next }
  clisteners.forEach((l) => l())
}

export const useCompress = () =>
  useSyncExternalStore(
    (l) => {
      clisteners.add(l)
      return () => clisteners.delete(l)
    },
    () => cstate
  )

let crefreshing = false
export async function refreshCompress(): Promise<void> {
  if (crefreshing) return
  crefreshing = true
  try {
    const w = await api<WebCompress>("/web/compress")
    let compressing: ReadonlySet<string> = new Set()
    if (w.compress) {
      const active = await listItems({ states: ACTIVE_STATES })
      compressing = new Set(active.map((i) => i.itemId))
    }
    const same = cstate.compressing.size === compressing.size && [...compressing].every((id) => cstate.compressing.has(id))
    cset({ loaded: true, info: w.compress, roots: w.roots ?? [], compressing: same ? cstate.compressing : compressing, tick: cstate.tick + 1 })
  } catch {
    if (!cstate.loaded) cset({ loaded: true })
  } finally {
    crefreshing = false
  }
}

export const COMPRESS_POLL_MS = 5000

/** Polls while the tab is visible (as the library, ADR 0018 "Live"). */
export function startCompressPolling(): () => void {
  let timer: number | undefined
  const tick = () => {
    if (document.visibilityState === "visible") void refreshCompress()
    timer = window.setTimeout(tick, COMPRESS_POLL_MS)
  }
  tick()
  const onVisible = () => document.visibilityState === "visible" && void refreshCompress()
  document.addEventListener("visibilitychange", onVisible)
  return () => {
    window.clearTimeout(timer)
    document.removeEventListener("visibilitychange", onVisible)
  }
}

/** Whether a gallery item has an unfinished compression (re-renders only when that changes: grid cells use it). */
export const useIsCompressing = (itemId: string) =>
  useSyncExternalStore(
    (l) => {
      clisteners.add(l)
      return () => clisteners.delete(l)
    },
    () => cstate.compressing.has(itemId)
  )
