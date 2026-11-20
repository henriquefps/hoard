import type { TFunction } from "i18next"
import type { BackupRoot, Job, Selection } from "@/lib/compress"

/** "about 35 min", "about 2 h 10 min", "under a minute" (estimates and ETAs are rough: the UI says "about"). */
export function formatEta(ms: number, t: TFunction): string {
  const min = Math.round(ms / 60000)
  if (min < 1) return t("compress.duration.underMinute")
  if (min < 60) return t("compress.duration.minutes", { count: min })
  const h = Math.floor(min / 60)
  const m = min % 60
  return m ? t("compress.duration.hoursMinutes", { h, m }) : t("compress.duration.hours", { count: h })
}

/** "82 %" of a saving fraction. */
export const percent = (f: number, locale: string) => new Intl.NumberFormat(locale, { style: "percent", maximumFractionDigits: 0 }).format(f)

/** A short name for what a job covers: its folder or backup path, or "N videos". */
export function selectionLabel(sel: Selection, folders: Record<string, string>, roots: BackupRoot[], t: TFunction): string {
  const f = sel.folders?.[0]
  if (f) {
    const name = folders[f.folderId ?? ""] ?? t("folders.unknown")
    const more = (sel.folders?.length ?? 1) > 1 ? ` +${(sel.folders?.length ?? 1) - 1}` : ""
    return (f.path ? `${name}/${f.path}` : name) + more
  }
  const r = sel.roots?.[0]
  if (r) {
    const root = roots.find((x) => x.id === r.rootId)
    const name = root ? `${root.device || t("compress.backup.phone")} · ${root.path}` : t("compress.backup.phone")
    return r.path && root && r.path !== root.path ? `${name}/${r.path.slice(root.path.length).replace(/^\//, "")}` : name
  }
  return t("compress.jobs.videos", { count: sel.itemIds?.length ?? 0 })
}

export const isPhoneJob = (j: Job) => j.target === "phone"
