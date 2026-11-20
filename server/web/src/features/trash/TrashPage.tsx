import { ArrowCounterClockwiseIcon, ArrowsInIcon, FilmStripIcon, ImageIcon, LockSimpleIcon, TrashIcon } from "@phosphor-icons/react"
import { useCallback, useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Spinner } from "@/components/ui/spinner"
import { type BatchAnswer, listTrash, purgeItems, refreshCompress, restoreItems, type TrashEntry, trashActions, trashSaves, useCompress } from "@/lib/compress"
import { formatBytes, formatDateTime, formatDuration } from "@/lib/format"
import { Confirm } from "../compress/parts"

/**
 * The trash (ADR 0023) as the server sees it. Originals of compressed videos (ADR 0025 "Originals: the trash") are
 * restored (the compression undone) and emptied here through the server's routes, which only the server may do; an
 * entry a compression journal holds is shown as held and never emptied; any other trashed file is listed for
 * reference: Hoard restores and empties those over the share.
 */
export function TrashPage() {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { tick, info } = useCompress()
  const [list, setList] = useState<TrashEntry[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [purge, setPurge] = useState<TrashEntry[] | null>(null)

  const load = useCallback(() => {
    listTrash()
      .then((l) => {
        setList(l)
        setFailed(false)
      })
      .catch(() => setFailed(true))
  }, [])
  useEffect(load, [load, tick])

  const groups = useMemo(() => {
    const g = { compressed: [] as TrashEntry[], held: [] as TrashEntry[], busy: [] as TrashEntry[], plain: [] as TrashEntry[] }
    for (const e of list ?? []) g[trashActions(e)].push(e)
    return g
  }, [list])

  const report = (ans: BatchAnswer, ok: string) => {
    const errors = Object.values(ans.items).filter((v) => v.error)
    const kept = Object.values(ans.items).filter((v) => v.alsoKeptBy?.length)
    if (errors.length) toast.error(t("compress.trash.refused", { count: errors.length, reason: t(`compress.trash.refusal.${errors[0].error}`, { defaultValue: errors[0].error }) }))
    else toast.success(ok)
    if (kept.length) toast.message(t("compress.trash.alsoKept", { count: kept.length }))
    void refreshCompress()
    load()
  }

  const restore = async (e: TrashEntry) => {
    if (!e.compression) return
    try {
      report(await restoreItems([e.compression.id]), t("compress.trash.restored"))
    } catch {
      toast.error(t("compress.actionFailed"))
    }
  }

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
      </Empty>
    )
  }
  const purgeable = groups.compressed
  const purgeBytes = (purge ?? []).reduce((s, e) => s + trashSaves(e), 0)

  return (
    <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain" data-testid="trash">
      <div className="flex flex-wrap items-end gap-3 border-b px-4 py-4 md:px-6">
        <div className="min-w-0">
          <p className="eyebrow">{t("trash.eyebrow")}</p>
          <h1 className="text-lg font-semibold">{t("trash.title")}</h1>
        </div>
        {purgeable.length > 0 && (
          <Button variant="outline" className="ml-auto" onClick={() => setPurge(purgeable)} data-testid="trash-empty-compressed">
            <TrashIcon data-icon="inline-start" />
            {t("compress.trash.emptyAll", { count: purgeable.length })}
          </Button>
        )}
      </div>

      {groups.compressed.length + groups.busy.length > 0 && (
        <Group title={t("compress.trash.section")} hint={info ? t("compress.trash.autoEmpty", { count: info.trashDays ?? 30 }) : undefined}>
          {[...groups.compressed, ...groups.busy].map((e) => (
            <Entry key={e.id} entry={e}>
              <p className="text-sm text-muted-foreground" data-testid="trash-label">
                {e.compression?.role === "output" ? t("compress.trash.labelOutput") : t("compress.trash.label")}
                {" · "}
                {t("compress.trash.saves", { size: formatBytes(trashSaves(e), lang) })}
                {e.compression?.purgeAfter ? ` · ${t("compress.trash.until", { date: formatDateTime(e.compression.purgeAfter, lang) })}` : ""}
              </p>
              {trashActions(e) === "compressed" ? (
                <div className="flex flex-wrap gap-1.5">
                  <Button variant="ghost" size="sm" onClick={() => void restore(e)} data-testid="trash-restore">
                    <ArrowCounterClockwiseIcon data-icon="inline-start" />
                    {e.compression?.role === "output" ? t("compress.trash.restoreAgain") : t("compress.trash.restore")}
                  </Button>
                  <Button variant="ghost" size="sm" className="text-destructive" onClick={() => setPurge([e])} data-testid="trash-purge">
                    <TrashIcon data-icon="inline-start" />
                    {t("compress.trash.deletePermanently")}
                  </Button>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">{t("compress.trash.busy")}</p>
              )}
            </Entry>
          ))}
        </Group>
      )}

      {groups.held.length > 0 && (
        <Group title={t("compress.trash.heldSection")} hint={t("compress.trash.heldNote")}>
          {groups.held.map((e) => (
            <Entry key={e.id} entry={e}>
              <p className="flex items-center gap-1.5 text-sm text-muted-foreground" data-testid="trash-held">
                <LockSimpleIcon className="size-4 text-warning" />
                {t("compress.trash.held")}
              </p>
            </Entry>
          ))}
        </Group>
      )}

      {groups.plain.length > 0 && (
        <Group title={t("trash.otherSection")} hint={t("trash.otherHint")}>
          {groups.plain.map((e) => (
            <Entry key={e.id} entry={e} />
          ))}
        </Group>
      )}

      <Confirm
        open={!!purge}
        title={t("compress.trash.purgeTitle", { count: purge?.length ?? 0 })}
        body={t("compress.trash.purgeBody", { count: purge?.length ?? 0, size: formatBytes(purgeBytes, lang) })}
        action={t("compress.trash.deletePermanently")}
        onClose={() => setPurge(null)}
        onConfirm={async () => {
          if (!purge) return
          try {
            const ids = purge.flatMap((e) => (e.compression ? [e.compression.id] : []))
            report(await purgeItems(ids), t("compress.trash.purged", { count: ids.length }))
          } catch {
            toast.error(t("compress.actionFailed"))
          }
        }}
      />
    </div>
  )
}

function Group({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="border-b" aria-label={title}>
      <div className="px-4 pt-4 pb-2 md:px-6">
        <h2 className="eyebrow">{title}</h2>
        {hint && <p className="mt-1 text-sm text-muted-foreground">{hint}</p>}
      </div>
      <ul>{children}</ul>
    </section>
  )
}

function Entry({ entry, children }: { entry: TrashEntry; children?: React.ReactNode }) {
  const { t, i18n } = useTranslation()
  const place = entry.places[0]
  const where = place ? (place.source === "nas" ? `${place.folder ?? ""}/${place.path ?? ""}` : `${place.device || t("compress.backup.phone")} · ${place.path ?? ""}`) : ""
  const Icon = entry.kind === "video" ? FilmStripIcon : ImageIcon
  return (
    <li className="flex gap-3 border-b px-4 py-3 last:border-b-0 md:px-6" data-testid="trash-entry" data-name={entry.name}>
      <span className="grid size-10 shrink-0 place-items-center rounded-md bg-muted text-muted-foreground">
        <Icon className="size-5" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-[0.9375rem] font-medium">{entry.name}</span>
          {entry.compressedTo && (
            <Badge variant="secondary">
              <ArrowsInIcon />
              {t("compress.badge.original")}
            </Badge>
          )}
          {entry.durationMs ? <span className="text-sm text-muted-foreground tabular-nums">{formatDuration(entry.durationMs)}</span> : null}
          <span className="text-sm text-muted-foreground tabular-nums">{formatBytes(entry.size, i18n.language)}</span>
        </div>
        {where && <p className="truncate font-mono text-[0.8125rem] text-muted-foreground">{where}</p>}
        {children}
      </div>
    </li>
  )
}
