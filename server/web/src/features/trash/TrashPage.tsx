import {
  ArrowClockwiseIcon,
  ArrowCounterClockwiseIcon,
  ArrowsInIcon,
  CaretDownIcon,
  CaretRightIcon,
  DeviceMobileIcon,
  FilmStripIcon,
  FolderSimpleIcon,
  ImageIcon,
  LockSimpleIcon,
  PaintBrushIcon,
  TrashIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Spinner } from "@/components/ui/spinner"
import { listTrash, refreshCompress, type TrashEntry, trashSaves, useCompress } from "@/lib/compress"
import { formatBytes, formatDateTime, formatDuration, formatNumber } from "@/lib/format"
import { refreshLibrary } from "@/lib/store"
import {
  type ActionAnswer,
  coalesced,
  deleteUnits,
  emptyFigures,
  emptyTrash,
  groupTrash,
  restoreUnits,
  TRASH_GROUPS,
  type TrashGroup,
  type TrashGroupKind,
  type UnitRef,
  unitsOf,
} from "@/lib/trash"
import { Confirm } from "../compress/parts"

type Pending = { kind: "delete" | "empty"; units: UnitRef[]; count: number; size: number } | null

const refKey = (u: UnitRef) => `${u.itemId}|${u.key}`

/**
 * The trash (ADR 0023, Amendment (0.14.0)): every trashed shot by day, then by place (a NAS folder's directory, or a
 * phone's backup in the server trash), with counts and sizes. Restore puts a shot back exactly where it was (refused,
 * with the file in the way named, when another file took its place); Delete permanently and Empty trash ask first and
 * say how much they free. NAS shots are the .hoard-trash files Hoard lists too; originals of compressed videos are
 * restored and emptied through compression's own journals; an entry a compression holds is never touched.
 */
export function TrashPage() {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { info } = useCompress()
  const [list, setList] = useState<TrashEntry[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set())
  const [pending, setPending] = useState<Pending>(null)
  const [busy, setBusy] = useState(false)

  // Loaded on open, after this page's own actions and on Refresh only, one load at a time (0.15.0: no polling; the
  // whole trash, page after page, used to be fetched on every compression poll and on a timer).
  const load = useMemo(
    () =>
      coalesced(
        listTrash,
        (l) => {
          setList(l)
          setFailed(false)
        },
        () => setFailed(true)
      ),
    []
  )
  useEffect(() => load(), [load])

  const groups = useMemo(() => groupTrash(list ?? []), [list])
  const days = useMemo(() => {
    const out: { day: string; groups: TrashGroup[]; shots: number; size: number }[] = []
    for (const g of groups) {
      let d = out[out.length - 1]
      if (!d || d.day !== g.day) out.push((d = { day: g.day, groups: [], shots: 0, size: 0 }))
      d.groups.push(g)
      d.shots += g.shots.length
      d.size += g.size
    }
    return out
  }, [groups])
  const figures = useMemo(() => emptyFigures(list ?? []), [list])
  // The selection: units still in the trash.
  const selected = useMemo(() => {
    const present = new Map<string, { ref: UnitRef; size: number }>()
    for (const e of list ?? []) for (const u of unitsOf(e)) if (!u.held) present.set(`${e.id}|${u.key}`, { ref: { itemId: e.id, key: u.key }, size: u.size })
    return [...chosen].flatMap((k) => (present.has(k) ? [present.get(k)!] : []))
  }, [chosen, list])

  const report = (ans: ActionAnswer, kind: "restore" | "delete") => {
    const refused = Object.entries(ans.refused)
    if (ans.done.length) {
      toast.success(kind === "restore" ? t("trash.restored", { count: ans.done.length }) : t("trash.deleted", { count: ans.done.length, size: formatBytes(ans.size, lang) }))
    }
    if (refused.length) {
      const [k, why] = refused[0]
      const name = ans.names?.[k]
      toast.error(
        name
          ? t("trash.refusedTaken", { count: refused.length, name })
          : t("trash.refused", { count: refused.length, reason: t(`trash.reason.${why}`, { defaultValue: t(`compress.unavailable.${why}`, { defaultValue: why }) }) })
      )
    }
    if (ans.keptBy?.length) toast.message(t("trash.keptBy", { by: ans.keptBy.map((b) => t(`trash.kept.${b}`, { defaultValue: b })).join(", ") }))
    setChosen(new Set())
    void refreshCompress()
    void refreshLibrary()
    load()
  }

  const restore = async (units: UnitRef[]) => {
    if (!units.length) return
    setBusy(true)
    try {
      report(await restoreUnits(units), "restore")
    } catch {
      toast.error(t("trash.actionFailed"))
    } finally {
      setBusy(false)
    }
  }

  const toggle = (keys: string[], on: boolean) =>
    setChosen((prev) => {
      const next = new Set(prev)
      for (const k of keys) {
        if (on) next.add(k)
        else next.delete(k)
      }
      return next
    })

  if (!list) {
    return (
      <div className="grid flex-1 place-items-center" role="status">
        {failed ? <p className="text-muted-foreground">{t("trash.loadFailed")}</p> : <Spinner className="size-6 text-muted-foreground" />}
      </div>
    )
  }
  if (list.length === 0) {
    return (
      <Empty className="flex-1">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <TrashIcon />
          </EmptyMedia>
          <EmptyTitle>{t("trash.emptyTitle")}</EmptyTitle>
          <EmptyDescription>{t("trash.emptyHint")}</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button variant="outline" onClick={load} data-testid="trash-refresh">
            <ArrowClockwiseIcon data-icon="inline-start" />
            {t("trash.refresh")}
          </Button>
        </EmptyContent>
      </Empty>
    )
  }
  const selSize = selected.reduce((s, x) => s + x.size, 0)
  const trashDays = info?.trashDays ?? 30

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {selected.length > 0 ? (
        <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-3" role="toolbar" aria-label={t("select.toolbar")} data-testid="trash-toolbar">
          <Button variant="ghost" size="icon" onClick={() => setChosen(new Set())} aria-label={t("select.clear")}>
            <XIcon />
          </Button>
          <span className="mr-2 text-[0.9375rem] font-medium tabular-nums" role="status">
            {t("select.count", { count: selected.length, formatted: formatNumber(selected.length, lang) })}
            <span className="ml-2 text-sm font-normal text-muted-foreground">{formatBytes(selSize, lang)}</span>
          </span>
          <Button variant="outline" disabled={busy} onClick={() => void restore(selected.map((x) => x.ref))} data-testid="trash-restore-selection">
            <ArrowCounterClockwiseIcon data-icon="inline-start" />
            {t("trash.restore")}
          </Button>
          <Button
            variant="outline"
            className="text-destructive"
            disabled={busy}
            onClick={() => setPending({ kind: "delete", units: selected.map((x) => x.ref), count: selected.length, size: selSize })}
            data-testid="trash-delete-selection"
          >
            <TrashIcon data-icon="inline-start" />
            {t("trash.deletePermanently")}
          </Button>
        </div>
      ) : null}
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain" data-testid="trash">
        <div className="flex flex-wrap items-end gap-3 border-b px-4 py-4 md:px-6">
          <div className="min-w-0">
            <p className="eyebrow">{t("trash.eyebrow")}</p>
            <h1 className="text-lg font-semibold">{t("trash.title")}</h1>
            <p className="mt-1 text-sm text-muted-foreground tabular-nums" data-testid="trash-totals">
              {t("trash.totals", { count: figures.count, formatted: formatNumber(figures.count, lang), size: formatBytes(figures.size, lang) })}
              {" · "}
              {t("trash.autoEmpty", { count: trashDays })}
            </p>
          </div>
          <Button variant="ghost" className="ml-auto" disabled={busy} onClick={load} data-testid="trash-refresh">
            <ArrowClockwiseIcon data-icon="inline-start" />
            {t("trash.refresh")}
          </Button>
          {figures.count > 0 && (
            <Button
              variant="outline"
              className="text-destructive"
              disabled={busy}
              onClick={() => setPending({ kind: "empty", units: [], ...figures })}
              data-testid="trash-empty"
            >
              <TrashIcon data-icon="inline-start" />
              {t("trash.empty")}
            </Button>
          )}
        </div>

        {days.map((d) => (
          <section key={d.day} className="border-b" aria-label={d.day} data-testid="trash-day" data-day={d.day}>
            <div className="flex flex-wrap items-baseline gap-2 px-4 pt-4 pb-2 md:px-6">
              <h2 className="eyebrow">{formatDay(d.day, lang)}</h2>
              <span className="text-sm text-muted-foreground tabular-nums">
                {t("trash.groupCount", { count: d.shots, formatted: formatNumber(d.shots, lang), size: formatBytes(d.size, lang) })}
              </span>
            </div>
            <ul>
              {d.groups.map((g) => (
                <GroupRow
                  key={g.key}
                  group={g}
                  open={open.has(g.key)}
                  onToggle={() => setOpen((prev) => toggleSet(prev, g.key))}
                  chosen={chosen}
                  onChoose={toggle}
                  busy={busy}
                  onRestore={() => void restore(g.units)}
                  onDelete={() => setPending({ kind: "delete", units: g.units, count: g.shots.length - g.held, size: groupFreeable(g) })}
                />
              ))}
            </ul>
          </section>
        ))}
      </div>

      <Confirm
        open={pending?.kind === "delete"}
        title={t("trash.deleteTitle", { count: pending?.count ?? 0 })}
        body={t("trash.deleteBody", { count: pending?.count ?? 0, size: formatBytes(pending?.size ?? 0, lang) })}
        action={t("trash.deletePermanently")}
        onClose={() => setPending(null)}
        onConfirm={async () => {
          if (!pending) return
          setBusy(true)
          try {
            report(await deleteUnits(pending.units), "delete")
          } catch {
            toast.error(t("trash.actionFailed"))
          } finally {
            setBusy(false)
          }
        }}
      />
      <EmptyDialog
        open={pending?.kind === "empty"}
        entries={list}
        onClose={() => setPending(null)}
        onEmpty={async (groups) => {
          const f = emptyFigures(list, groups)
          setBusy(true)
          try {
            const ans = await emptyTrash(f.count, f.size, groups)
            if ("done" in ans) report(ans, "delete")
            else {
              toast.message(t("trash.changed"))
              load()
            }
          } catch {
            toast.error(t("trash.actionFailed"))
          } finally {
            setBusy(false)
          }
        }}
      />
    </div>
  )
}

const toggleSet = (s: ReadonlySet<string>, k: string) => {
  const next = new Set(s)
  if (next.has(k)) next.delete(k)
  else next.add(k)
  return next
}

const groupFreeable = (g: TrashGroup) =>
  g.shots.reduce((s, e) => s + unitsOf(e).filter((u) => !u.held && g.units.some((r) => r.itemId === e.id && r.key === u.key)).reduce((a, u) => a + u.size, 0), 0)

/** "Wednesday, 26 November 2026" from "2026-11-26" (a calendar date: no time zone shift). */
function formatDay(day: string, lang: string): string {
  const [y, m, d] = day.split("-").map(Number)
  if (!y || !m || !d) return day
  return new Intl.DateTimeFormat(lang, { weekday: "long", day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, d)))
}

function GroupRow({
  group: g,
  open,
  onToggle,
  chosen,
  onChoose,
  busy,
  onRestore,
  onDelete,
}: {
  group: TrashGroup
  open: boolean
  onToggle: () => void
  chosen: ReadonlySet<string>
  onChoose: (keys: string[], on: boolean) => void
  busy: boolean
  onRestore: () => void
  onDelete: () => void
}) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const keys = g.units.map(refKey)
  const all = keys.length > 0 && keys.every((k) => chosen.has(k))
  const some = keys.some((k) => chosen.has(k))
  const Icon = g.source === "server" ? DeviceMobileIcon : FolderSimpleIcon
  const place = g.source === "server" ? t("trash.serverPlace", { device: g.device || t("compress.backup.phone") }) : g.folderName || t("trash.nasFolder")
  return (
    <li className="border-t first:border-t-0" data-testid="trash-group" data-source={g.source}>
      <div className="flex flex-wrap items-center gap-3 px-4 py-2.5 md:px-6">
        {keys.length > 0 ? (
          <Checkbox checked={all ? true : some ? "indeterminate" : false} onCheckedChange={(v) => onChoose(keys, v === true)} aria-label={t("trash.selectGroup", { place })} />
        ) : (
          <span className="size-4" />
        )}
        <button type="button" className="flex min-w-0 flex-1 items-center gap-2 text-left" onClick={onToggle} aria-expanded={open} data-testid="trash-group-toggle">
          {open ? <CaretDownIcon className="size-4 shrink-0" /> : <CaretRightIcon className="size-4 shrink-0" />}
          <Icon className="size-4 shrink-0 text-muted-foreground" />
          <span className="min-w-0 truncate">
            <span className="font-medium">{place}</span>
            {g.dir && <span className="font-mono text-[0.8125rem] text-muted-foreground"> / {g.dir}</span>}
          </span>
        </button>
        <span className="text-sm text-muted-foreground tabular-nums" data-testid="trash-group-count">
          {t("trash.groupCount", { count: g.shots.length, formatted: formatNumber(g.shots.length, lang), size: formatBytes(g.size, lang) })}
          {g.held > 0 && ` · ${t("trash.heldCount", { count: g.held })}`}
        </span>
        {g.units.length > 0 && (
          <div className="flex gap-1.5">
            <Button variant="ghost" size="sm" disabled={busy} onClick={onRestore} data-testid="trash-group-restore">
              <ArrowCounterClockwiseIcon data-icon="inline-start" />
              {t("trash.restore")}
            </Button>
            <Button variant="ghost" size="sm" className="text-destructive" disabled={busy} onClick={onDelete} data-testid="trash-group-delete">
              <TrashIcon data-icon="inline-start" />
              {t("trash.deletePermanently")}
            </Button>
          </div>
        )}
      </div>
      {open && (
        <ul className="border-t bg-muted/30">
          {g.shots.map((e) => (
            <EntryRow key={e.id} entry={e} group={g} chosen={chosen} onChoose={onChoose} />
          ))}
        </ul>
      )}
    </li>
  )
}

function EntryRow({ entry: e, group: g, chosen, onChoose }: { entry: TrashEntry; group: TrashGroup; chosen: ReadonlySet<string>; onChoose: (keys: string[], on: boolean) => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const unit = unitsOf(e).find((u) => g.units.some((r) => r.itemId === e.id && r.key === u.key)) ?? unitsOf(e).find((u) => u.held)
  const key = unit ? `${e.id}|${unit.key}` : ""
  const held = !!unit?.held
  const Icon = e.kind === "video" ? FilmStripIcon : ImageIcon
  return (
    <li className="flex gap-3 border-b px-4 py-2.5 last:border-b-0 md:pl-12" data-testid="trash-entry" data-name={e.name}>
      {held ? (
        <LockSimpleIcon className="mt-0.5 size-4 shrink-0 text-warning" aria-label={t("compress.trash.held")} />
      ) : (
        <Checkbox checked={chosen.has(key)} onCheckedChange={(v) => onChoose([key], v === true)} aria-label={e.name} className="mt-0.5" />
      )}
      <Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-[0.9375rem] font-medium">{e.name}</span>
          {e.compressedTo && (
            <Badge variant="secondary">
              <ArrowsInIcon />
              {t("compress.badge.original")}
            </Badge>
          )}
          {e.edits && (
            <Badge variant="secondary" data-testid="trash-edits">
              <PaintBrushIcon />
              {t("trash.edited")}
            </Badge>
          )}
          {e.durationMs ? <span className="text-sm text-muted-foreground tabular-nums">{formatDuration(e.durationMs)}</span> : null}
          <span className="text-sm text-muted-foreground tabular-nums">{formatBytes(unit?.size ?? e.size, lang)}</span>
        </div>
        {held && <p className="text-sm text-muted-foreground" data-testid="trash-held">{t("compress.trash.held")}</p>}
        {e.compression && !held && (
          <p className="text-sm text-muted-foreground" data-testid="trash-label">
            {e.compression.role === "output" ? t("compress.trash.labelOutput") : t("compress.trash.label")}
            {" · "}
            {t("compress.trash.saves", { size: formatBytes(trashSaves(e), lang) })}
          </p>
        )}
        {unit?.purgeAfter && !held ? (
          <p className="text-sm text-muted-foreground">{t("trash.until", { date: formatDateTime(unit.purgeAfter, lang) })}</p>
        ) : null}
      </div>
    </li>
  )
}

/**
 * Empty trash, group by group (review of #84): what the web gallery moved, what Hoard trashed on your devices (before
 * 0.14.0 too), and the originals of compressed videos, each with its count and size and a checkbox (all on); only the
 * checked groups are emptied.
 */
function EmptyDialog({ open, entries, onClose, onEmpty }: { open: boolean; entries: TrashEntry[]; onClose: () => void; onEmpty: (g: TrashGroupKind[]) => Promise<void> }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [off, setOff] = useState<ReadonlySet<TrashGroupKind>>(new Set())
  const [busy, setBusy] = useState(false)
  const groups = TRASH_GROUPS.map((g) => ({ g, ...emptyFigures(entries, [g]) })).filter((x) => x.count > 0)
  const chosen = groups.filter((x) => !off.has(x.g)).map((x) => x.g)
  const total = emptyFigures(entries, chosen)
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent showCloseButton={false} data-testid="empty-dialog">
        <DialogHeader>
          <DialogTitle className="text-lg">{t("trash.emptyTitleConfirm")}</DialogTitle>
          <DialogDescription className="text-[0.9375rem]">{t("trash.emptyChoose")}</DialogDescription>
        </DialogHeader>
        <ul className="flex flex-col gap-3">
          {groups.map(({ g, count, size }) => (
            <li key={g} className="flex items-start gap-3" data-testid="empty-group" data-group={g}>
              <Checkbox
                id={`empty-${g}`}
                checked={!off.has(g)}
                onCheckedChange={(v) => setOff((prev) => { const n = new Set(prev); if (v === true) n.delete(g); else n.add(g); return n })}
                className="mt-0.5"
              />
              <label htmlFor={`empty-${g}`} className="flex flex-col">
                <span className="font-medium">{t(`trash.group.${g}`)}</span>
                <span className="text-sm text-muted-foreground tabular-nums">
                  {t("trash.groupCount", { count, formatted: formatNumber(count, lang), size: formatBytes(size, lang) })}
                  {" · "}
                  {t(`trash.groupHint.${g}`)}
                </span>
              </label>
            </li>
          ))}
        </ul>
        <p className="text-[0.9375rem]" data-testid="empty-total">
          {total.count > 0
            ? t("trash.emptyBody", { count: total.count, formatted: formatNumber(total.count, lang), size: formatBytes(total.size, lang) })
            : t("trash.emptyNone")}
        </p>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("compress.close")}
          </Button>
          <Button
            variant="destructive"
            disabled={busy || total.count === 0}
            data-testid="empty-action"
            onClick={async () => {
              setBusy(true)
              try {
                await onEmpty(chosen)
              } finally {
                setBusy(false)
                onClose()
              }
            }}
          >
            {busy && <Spinner data-icon="inline-start" />}
            {t("trash.empty")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
