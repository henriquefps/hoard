import {
  ArrowCounterClockwiseIcon,
  ArrowsInIcon,
  CaretDownIcon,
  CaretRightIcon,
  DownloadSimpleIcon,
  FileTextIcon,
  PauseIcon,
  PlayIcon,
  TrashIcon,
  WarningIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useCallback, useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Spinner } from "@/components/ui/spinner"
import {
  type BatchAnswer,
  cancelJob,
  type CItem,
  failKey,
  IN_TRASH_STATES,
  type Job,
  jobFraction,
  jobTotals,
  listItems,
  listJobs,
  logUrl,
  metaUrl,
  patchJob,
  purgeItems,
  refreshCompress,
  restoreItems,
  skipKey,
  splitJobs,
  type Target,
  targetGate,
  unavailableKey,
  useCompress,
} from "@/lib/compress"
import { formatBytes, formatDateTime, formatNumber } from "@/lib/format"
import { useLibraryState } from "@/lib/store"
import { cn } from "@/lib/utils"
import { formatEta, isPhoneJob, percent, selectionLabel } from "./format"
import { Confirm, Progress } from "./parts"

const TARGETS: Target[] = ["nas", "backup", "phone"]

/**
 * The Compression view (ADR 0025 "Web gallery"): the server's state, totals (saved = emptied originals, what is in the
 * trash), jobs in progress (progress, the current item, ETA, Pause/Resume, Cancel), the folders compressed
 * automatically (their switch), and the history; each job opens its items: state with reason, sizes, q, VMAF, timings,
 * the log, the metadata download, Restore and Empty now. Phone jobs are listed read-only (the phone does their replace).
 */
export function CompressionPage() {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { info, roots, tick, loaded } = useCompress()
  const { library } = useLibraryState()
  const [jobs, setJobs] = useState<Job[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [confirm, setConfirm] = useState<Job | null>(null)

  useEffect(() => {
    if (!info) return
    let stop = false
    listJobs()
      .then((j) => {
        if (stop) return
        setJobs(j)
        setFailed(false)
      })
      .catch(() => !stop && setFailed(true))
    return () => {
      stop = true
    }
  }, [info, tick])

  const split = useMemo(() => splitJobs(jobs ?? []), [jobs])
  const totals = useMemo(() => jobTotals(jobs ?? []), [jobs])
  const label = (j: Job) => j.name || selectionLabel(j.select, library.folders, roots, t)

  const pause = async (j: Job) => {
    try {
      await patchJob(j.id, { paused: !j.paused })
      void refreshCompress()
    } catch {
      toast.error(t("compress.actionFailed"))
    }
  }

  if (!loaded) {
    return (
      <div className="grid flex-1 place-items-center" role="status">
        <Spinner className="size-6 text-muted-foreground" />
      </div>
    )
  }
  if (!info) {
    return (
      <Empty className="flex-1">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <ArrowsInIcon />
          </EmptyMedia>
          <EmptyTitle>{t("compress.title")}</EmptyTitle>
          <EmptyDescription>{t("compress.unavailable.no_gpu")}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }

  return (
    <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain" data-testid="compression">
      <div className="flex flex-wrap items-end gap-3 border-b px-4 py-4 md:px-6">
        <div className="min-w-0">
          <p className="eyebrow">{t("compress.eyebrow")}</p>
          <h1 className="text-lg font-semibold">{t("compress.title")}</h1>
        </div>
        <p className="ml-auto text-sm text-muted-foreground">
          {info.gpu ? t("compress.status.gpu", { gpu: info.gpu }) : t(unavailableKey(info.reason))}
          {" · "}
          {info.offerAv1 ? t("compress.status.av1On") : t("compress.status.av1Off")}
        </p>
      </div>

      <div className="grid gap-px border-b bg-border sm:grid-cols-3" data-testid="compress-totals">
        <Stat label={t("compress.history.saved")} value={formatBytes(totals.saved, lang)} />
        <Stat label={t("compress.history.inTrash")} value={formatBytes(totals.inTrash, lang)} hint={t("compress.trash.autoEmpty", { count: info.trashDays ?? 30 })} />
        <Stat label={t("compress.history.compressed")} value={formatNumber(totals.replaced, lang)} />
      </div>

      {(TARGETS.some((tg) => tg !== "phone" && !targetGate(info, tg).ok) || (info.attention?.stalled ?? 0) > 0) && (
        <div className="flex flex-col gap-1.5 border-b px-4 py-3 md:px-6" role="status">
          {TARGETS.filter((tg) => tg !== "phone").map((tg) => {
            const g = targetGate(info, tg)
            return g.ok ? null : (
              <p key={tg} className="flex items-start gap-2 text-sm text-muted-foreground">
                <WarningIcon className="mt-0.5 size-4 shrink-0 text-warning" />
                <span>
                  <span className="font-medium text-foreground">{t(`compress.targetName.${tg}`)}</span>: {t(unavailableKey(g.reason))}
                </span>
              </p>
            )
          })}
          {(info.attention?.stalled ?? 0) > 0 && (
            <p className="flex items-start gap-2 text-sm text-muted-foreground" data-testid="compress-stalled">
              <WarningIcon className="mt-0.5 size-4 shrink-0 text-warning" />
              {t("compress.status.stalled", { count: info.attention?.stalled ?? 0 })}
            </p>
          )}
        </div>
      )}

      {!jobs ? (
        <div className="grid place-items-center py-10" role="status">
          {failed ? <p className="text-muted-foreground">{t("compress.loadFailed")}</p> : <Spinner className="size-6 text-muted-foreground" />}
        </div>
      ) : (
        <>
          <Section title={t("compress.jobs.title")} testId="compress-running">
            {split.running.length === 0 ? (
              <p className="px-4 py-3 text-sm text-muted-foreground md:px-6">{t("compress.jobs.none")}</p>
            ) : (
              split.running.map((j) => (
                <JobRow key={j.id} job={j} label={label(j)}>
                  <div className="flex flex-col gap-1.5">
                    <Progress value={jobFraction(j)} label={t("compress.jobs.progressLabel", { name: label(j) })} />
                    <p className="text-sm text-muted-foreground tabular-nums">
                      {t("compress.jobs.progress", { done: j.progress.done, total: j.progress.done + j.progress.toDo })}
                      {j.progress.current?.percent !== undefined && ` · ${t("compress.jobs.current", { percent: j.progress.current.percent })}`}
                      {j.progress.toDo > 0 && ` · ${t("compress.jobs.eta", { time: formatEta(j.progress.etaMs, t) })}`}
                      {j.paused && ` · ${t("compress.jobs.paused")}`}
                    </p>
                  </div>
                  {!isPhoneJob(j) && (
                    <div className="flex gap-2">
                      <Button variant="outline" size="sm" onClick={() => void pause(j)} data-testid="job-pause">
                        {j.paused ? <PlayIcon data-icon="inline-start" /> : <PauseIcon data-icon="inline-start" />}
                        {j.paused ? t("compress.jobs.resume") : t("compress.jobs.pause")}
                      </Button>
                      {!j.auto && (
                        <Button variant="outline" size="sm" onClick={() => setConfirm(j)} data-testid="job-cancel">
                          <XIcon data-icon="inline-start" />
                          {t("compress.jobs.cancel")}
                        </Button>
                      )}
                    </div>
                  )}
                </JobRow>
              ))
            )}
          </Section>

          <Section title={t("compress.auto.list")} testId="compress-auto">
            {split.auto.length === 0 ? (
              <p className="px-4 py-3 text-sm text-muted-foreground md:px-6">{t("compress.auto.none")}</p>
            ) : (
              split.auto.map((j) => (
                <div key={j.id} className="flex flex-wrap items-center gap-3 border-b px-4 py-3 last:border-b-0 md:px-6">
                  <ArrowsInIcon className="size-4 shrink-0 text-primary" />
                  <span className="min-w-0 flex-1 truncate text-[0.9375rem]">{label(j)}</span>
                  <span className="text-sm text-muted-foreground">{t(`compress.targetName.${j.target}`)}</span>
                  {!isPhoneJob(j) && (
                    <Button
                      variant="outline"
                      size="sm"
                      data-testid="auto-off"
                      onClick={async () => {
                        try {
                          await patchJob(j.id, { auto: false })
                          toast.success(t("compress.auto.off"))
                          void refreshCompress()
                        } catch {
                          toast.error(t("compress.actionFailed"))
                        }
                      }}
                    >
                      {t("compress.auto.turnOff")}
                    </Button>
                  )}
                </div>
              ))
            )}
          </Section>

          <Section title={t("compress.history.title")} testId="compress-history">
            {split.history.length === 0 ? (
              <p className="px-4 py-3 text-sm text-muted-foreground md:px-6">{t("compress.history.none")}</p>
            ) : (
              split.history.map((j) => (
                <JobRow key={j.id} job={j} label={label(j)}>
                  <p className="text-sm text-muted-foreground">
                    {t(`compress.jobs.state.${j.state}`)}
                    {j.finishedAt ? ` · ${formatDateTime(j.finishedAt, lang)}` : ""}
                    {` · ${t("compress.history.savedValue", { size: formatBytes(j.bytes.saved, lang) })}`}
                    {j.bytes.inTrash > 0 && ` · ${t("compress.history.inTrashValue", { size: formatBytes(j.bytes.inTrash, lang) })}`}
                  </p>
                </JobRow>
              ))
            )}
          </Section>
        </>
      )}

      <Confirm
        open={!!confirm}
        title={t("compress.jobs.cancelTitle")}
        body={t("compress.jobs.cancelBody")}
        action={t("compress.jobs.cancel")}
        onClose={() => setConfirm(null)}
        onConfirm={async () => {
          if (!confirm) return
          try {
            await cancelJob(confirm.id)
            toast.success(t("compress.jobs.cancelled"))
            void refreshCompress()
          } catch {
            toast.error(t("compress.actionFailed"))
          }
        }}
      />
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex flex-col gap-0.5 bg-card px-4 py-3 md:px-6">
      <p className="eyebrow">{label}</p>
      <p className="text-xl font-semibold tabular-nums">{value}</p>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  )
}

function Section({ title, children, testId }: { title: string; children: React.ReactNode; testId: string }) {
  return (
    <section className="border-b" data-testid={testId} aria-label={title}>
      <h2 className="eyebrow px-4 pt-4 pb-2 md:px-6">{title}</h2>
      {children}
    </section>
  )
}

/** A job: its name, target, profile and counts; opens its items. */
function JobRow({ job, label, children }: { job: Job; label: string; children: React.ReactNode }) {
  const { t, i18n } = useTranslation()
  const [open, setOpen] = useState(false)
  const skipped = Object.entries(job.skipped).filter(([, n]) => n > 0)
  const failed = Object.entries(job.failed).filter(([, n]) => n > 0)
  return (
    <div className="border-b last:border-b-0" data-testid="job" data-job={job.id}>
      <div className="flex flex-col gap-2 px-4 py-3 md:px-6">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            aria-expanded={open}
            className="flex min-w-0 items-center gap-1.5 rounded-md text-left text-[0.9375rem] font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            data-testid="job-open"
          >
            {open ? <CaretDownIcon className="size-4 shrink-0" /> : <CaretRightIcon className="size-4 shrink-0" />}
            <span className="truncate">{label}</span>
          </button>
          <Badge variant="outline">{t(`compress.targetName.${job.target}`)}</Badge>
          <Badge variant="secondary">{t(`compress.profile.${job.profile === "hevc-cq30" ? "smaller" : job.profile.startsWith("av1") ? "av1" : "same"}`)}</Badge>
          {job.auto && <Badge variant="secondary">{t("compress.auto.badge")}</Badge>}
          {isPhoneJob(job) && <Badge variant="outline">{t("compress.jobs.readOnly")}</Badge>}
          <span className="ml-auto text-sm text-muted-foreground">{formatDateTime(job.createdAt, i18n.language)}</span>
        </div>
        {children}
        {(skipped.length > 0 || failed.length > 0) && (
          <p className="text-sm text-muted-foreground">
            {skipped.map(([r, n]) => t("compress.estimate.skipped", { count: n, reason: t(skipKey(r), { count: n }) })).join(" · ")}
            {skipped.length > 0 && failed.length > 0 && " · "}
            {failed.map(([r, n]) => t("compress.history.failed", { count: n, reason: t(failKey(r)) })).join(" · ")}
          </p>
        )}
      </div>
      {open && <JobItems job={job} />}
    </div>
  )
}

function JobItems({ job }: { job: Job }) {
  const { t } = useTranslation()
  const { tick } = useCompress()
  const [items, setItems] = useState<CItem[] | null>(null)
  const [log, setLog] = useState<CItem | null>(null)
  const [purge, setPurge] = useState<CItem | null>(null)

  const load = useCallback(() => {
    listItems({ jobId: job.id })
      .then(setItems)
      .catch(() => setItems([]))
  }, [job.id])
  useEffect(load, [load, tick])

  const report = (ans: BatchAnswer, ok: string) => {
    const errors = Object.values(ans.items).filter((v) => v.error)
    if (errors.length) toast.error(t("compress.trash.refused", { count: errors.length, reason: t(`compress.trash.refusal.${errors[0].error}`, { defaultValue: errors[0].error }) }))
    else toast.success(ok)
    void refreshCompress()
    load()
  }

  if (!items) {
    return (
      <div className="px-4 pb-3 md:px-6" role="status">
        <Spinner className="size-4 text-muted-foreground" />
      </div>
    )
  }
  return (
    <>
      <ul className="border-t bg-muted/30" data-testid="job-items">
        {items.map((it) => (
          <ItemRow
            key={it.id}
            item={it}
            readOnly={isPhoneJob(job)}
            onLog={() => setLog(it)}
            onRestore={async () => {
              try {
                report(await restoreItems([it.id]), t("compress.trash.restored"))
              } catch {
                toast.error(t("compress.actionFailed"))
              }
            }}
            onPurge={() => setPurge(it)}
          />
        ))}
      </ul>
      <LogDialog item={log} onClose={() => setLog(null)} />
      <Confirm
        open={!!purge}
        title={t("compress.trash.emptyNowTitle")}
        body={t("compress.trash.emptyNowBody", { name: purge?.name ?? "" })}
        action={t("compress.trash.emptyNow")}
        onClose={() => setPurge(null)}
        onConfirm={async () => {
          if (!purge) return
          try {
            report(await purgeItems([purge.id]), t("compress.trash.purged", { count: 1 }))
          } catch {
            toast.error(t("compress.actionFailed"))
          }
        }}
      />
    </>
  )
}

function ItemRow({ item, readOnly, onLog, onRestore, onPurge }: { item: CItem; readOnly: boolean; onLog: () => void; onRestore: () => void; onPurge: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const reason = item.reason ? (item.state === "skipped" ? t(skipKey(item.reason), { count: 1 }) : t(failKey(item.reason))) : ""
  const inTrash = IN_TRASH_STATES.includes(item.state)
  const saving = item.outSize !== undefined && item.size ? 1 - item.outSize / item.size : null
  return (
    <li className="flex flex-col gap-1 border-b px-4 py-2.5 last:border-b-0 md:pl-12" data-testid="job-item" data-state={item.state}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="min-w-0 truncate font-mono text-[0.8125rem]">{item.name || item.itemId}</span>
        <Badge variant={item.state === "failed" ? "destructive" : item.state === "replaced" || item.state === "purged" ? "default" : "secondary"}>
          {t(`compress.item.state.${item.state}`, { defaultValue: item.state })}
        </Badge>
        {reason && <span className="text-sm text-muted-foreground">{reason}</span>}
        {item.state === "encoding" && item.progress !== undefined && <span className="text-sm text-muted-foreground tabular-nums">{percent(item.progress, lang)}</span>}
      </div>
      <p className="text-sm text-muted-foreground tabular-nums">
        {item.outSize !== undefined
          ? t("compress.item.sizes", { from: formatBytes(item.size, lang), to: formatBytes(item.outSize, lang), saving: percent(saving ?? 0, lang) })
          : formatBytes(item.size, lang)}
        {item.q !== undefined && ` · ${t("compress.item.q", { q: item.q })}`}
        {item.vmaf && ` · ${t("compress.item.vmaf", { mean: item.vmaf.mean, low: item.vmaf.low, mode: t(`compress.item.vmafMode.${item.vmaf.mode}`, { defaultValue: item.vmaf.mode }) })}`}
        {item.encodeMs !== undefined && ` · ${t("compress.item.timings", { encode: Math.round(item.encodeMs / 100) / 10, verify: Math.round((item.verifyMs ?? 0) / 100) / 10 })}`}
        {item.purgeAfter && inTrash ? ` · ${t("compress.trash.until", { date: formatDateTime(item.purgeAfter, lang) })}` : ""}
      </p>
      {item.metaSidecar && <p className="text-sm text-muted-foreground">{t("compress.item.metaSaved")}</p>}
      <div className="flex flex-wrap gap-1.5">
        {item.logAvailable && (
          <Button variant="ghost" size="sm" onClick={onLog} data-testid="item-log">
            <FileTextIcon data-icon="inline-start" />
            {t("compress.item.log")}
          </Button>
        )}
        {item.outFingerprint && ["replaced", "restored", "purged", "ready", "replacing"].includes(item.state) && (
          <Button variant="ghost" size="sm" asChild>
            <a href={metaUrl(item.id)} download data-testid="item-meta">
              <DownloadSimpleIcon data-icon="inline-start" />
              {t("compress.item.meta")}
            </a>
          </Button>
        )}
        {inTrash && !readOnly && (
          <>
            <Button variant="ghost" size="sm" onClick={onRestore} data-testid="item-restore">
              <ArrowCounterClockwiseIcon data-icon="inline-start" />
              {item.state === "restored" ? t("compress.trash.restoreAgain") : t("compress.trash.restore")}
            </Button>
            <Button variant="ghost" size="sm" onClick={onPurge} className="text-destructive" data-testid="item-purge">
              <TrashIcon data-icon="inline-start" />
              {t("compress.trash.emptyNow")}
            </Button>
          </>
        )}
      </div>
    </li>
  )
}

function LogDialog({ item, onClose }: { item: CItem | null; onClose: () => void }) {
  const { t } = useTranslation()
  const [text, setText] = useState<{ id: string; body: string } | null>(null)
  useEffect(() => {
    if (!item) return
    let stop = false
    fetch(logUrl(item.id), { credentials: "same-origin" })
      .then((r) => (r.ok ? r.text() : Promise.reject(new Error(String(r.status)))))
      .then((body) => !stop && setText({ id: item.id, body }))
      .catch(() => !stop && setText({ id: item.id, body: t("compress.item.logFailed") }))
    return () => {
      stop = true
    }
  }, [item, t])
  const body = item && text?.id === item.id ? text.body : null
  return (
    <Dialog open={!!item} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[calc(100dvh-2rem)] sm:max-w-3xl" data-testid="log-dialog">
        <DialogHeader>
          <p className="eyebrow">{t("compress.item.log")}</p>
          <DialogTitle className="truncate font-mono text-base">{item?.name ?? ""}</DialogTitle>
        </DialogHeader>
        {body === null ? (
          <Spinner className="size-5 text-muted-foreground" />
        ) : (
          <pre className={cn("max-h-[60dvh] overflow-auto rounded-lg bg-muted p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-all")}>{body}</pre>
        )}
      </DialogContent>
    </Dialog>
  )
}
