import { ArrowsInIcon, InfoIcon, TrashIcon, WarningIcon } from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Spinner } from "@/components/ui/spinner"
import { ApiError } from "@/lib/api"
import {
  type CompressInfo,
  defaultProfile,
  type Estimate,
  estimatePart,
  type Job,
  mergeEstimates,
  offeredProfiles,
  type Part,
  patchJob,
  refreshCompress,
  skipKey,
  skipList,
  startJob,
  type Target,
  targetGate,
  unavailableKey,
} from "@/lib/compress"
import { formatBytes, formatNumber } from "@/lib/format"
import { cn } from "@/lib/utils"
import { formatEta } from "./format"
import { SpaceNote } from "./parts"

export interface CompressRequest {
  /** What to compress: one part per target (a selection can make a nas and a backup part). */
  parts: Part[]
  /** Where it comes from, for the title ("DJI_0001.MP4 and 2 more", "Videos/Drone"). */
  label: string
  /** "auto": the folder's "Compress automatically" switch (one folder, the default profile, never AV1). */
  mode: "once" | "auto"
  /** The folder's standing job when it is on (auto mode offers Turn off). */
  autoJob?: Job | null
  /** Videos of the selection that no target takes (left out). */
  leftOut?: number
  /** The target a folder action needs (its gate decides before any estimate). */
  target?: Target
}

type Quality = "floor" | "fixed" | "target"

const MAX_RETRIES = 15

/**
 * The estimate sheet (ADR 0025 "UI", Hoard 2.7.0's wording): what will be compressed and where, the profile, then the
 * estimate ("3 of 4 videos · 19.9 MB → about 2.2 MB · about 1 min"), skips by reason, warnings, the per-frame data and
 * trash notes, Start. Advanced (web only): the quality mode (floor 93, fixed, target VMAF). A target that is not ok
 * shows the server's reason instead of the estimate.
 */
export function CompressDialog({ request, info, onClose }: { request: CompressRequest | null; info: CompressInfo | null; onClose: () => void }) {
  return (
    <Dialog open={!!request} onOpenChange={(o) => !o && onClose()}>
      {request && info && <CompressBody key={JSON.stringify(request.parts) + request.mode} request={request} info={info} onClose={onClose} />}
    </Dialog>
  )
}

function CompressBody({ request, info, onClose }: { request: CompressRequest; info: CompressInfo; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const auto = request.mode === "auto"
  const profiles = useMemo(() => offeredProfiles(info, auto), [info, auto])
  const [profile, setProfile] = useState(() => defaultProfile(info))
  const [quality, setQuality] = useState<Quality>("floor")
  const [vmaf, setVmaf] = useState(95)
  const [advanced, setAdvanced] = useState(false)
  const [est, setEst] = useState<{ key: string; estimate: Estimate | null; failed: string | null } | null>(null)
  const [also, setAlso] = useState(false)
  const [busy, setBusy] = useState(false)

  const gates = request.parts.map((p) => ({ part: p, gate: targetGate(info, p.target) }))
  const blocked = request.target ? targetGate(info, request.target) : gates.find((g) => !g.gate.ok)?.gate
  const parts = gates.filter((g) => g.gate.ok).map((g) => g.part)
  const options = quality === "floor" ? undefined : { quality: quality === "fixed" ? { mode: "fixed" } : { mode: "target", vmaf } }
  const optionsKey = JSON.stringify(options ?? null)
  const estKey = profile + optionsKey
  // What belongs to another profile or option set is not shown (state keyed instead of reset in the effect).
  const estimate = est?.key === estKey ? est.estimate : null
  const failed = est?.key === estKey ? est.failed : null

  // The estimate: every page of every part, asked again while probes are pending (Retry-After 2 s, 15 times at most).
  useEffect(() => {
    if (blocked && !blocked.ok) return
    if (!parts.length) return
    let stop = false
    let timer: number | undefined
    const run = async (attempt: number) => {
      try {
        const list = await Promise.all(parts.map((p) => estimatePart(p, profile, options)))
        if (stop) return
        const e = mergeEstimates(list)
        setEst({ key: estKey, estimate: e, failed: null })
        if (e.pending > 0 && attempt < MAX_RETRIES) timer = window.setTimeout(() => void run(attempt + 1), 2000)
      } catch (err) {
        if (!stop) setEst({ key: estKey, estimate: null, failed: err instanceof ApiError && err.status === 503 ? "unavailable" : "load" })
      }
    }
    void run(0)
    return () => {
      stop = true
      window.clearTimeout(timer)
    }
    // parts are fixed for this dialog (keyed by them)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile, optionsKey])

  const start = async () => {
    setBusy(true)
    try {
      let queued = 0
      let busyCount = 0
      if (auto) {
        // One standing job for the folder, then, when asked, a one-off job over what is there now.
        const p = parts[0]
        if (!request.autoJob) {
          try {
            await startJob(p, defaultProfile(info), undefined, true)
          } catch (err) {
            if (!(err instanceof ApiError && err.status === 409)) throw err
            toast.message(t("compress.auto.exists"))
          }
        }
        if (also && estimate && estimate.totals.encode > 0) {
          const j = await startJob(p, profile, options)
          queued += j.queued ?? 0
        }
        toast.success(queued > 0 ? t("compress.started", { count: queued }) : t("compress.auto.on"))
      } else {
        for (const p of parts) {
          try {
            const j = await startJob(p, profile, options)
            queued += j.queued ?? 0
            busyCount += j.busy?.length ?? 0
          } catch (err) {
            if (err instanceof ApiError && err.status === 409) busyCount++
            else throw err
          }
        }
        if (queued > 0) toast.success(t("compress.started", { count: queued }))
        else toast.message(t("compress.nothingQueued"))
        if (busyCount > 0) toast.message(t("compress.busy", { count: busyCount }))
      }
      void refreshCompress()
      onClose()
    } catch (err) {
      toast.error(err instanceof ApiError && err.status === 503 ? err.message : t("compress.startFailed"))
    } finally {
      setBusy(false)
    }
  }

  const turnOff = async () => {
    if (!request.autoJob) return
    setBusy(true)
    try {
      await patchJob(request.autoJob.id, { auto: false })
      toast.success(t("compress.auto.off"))
      void refreshCompress()
      onClose()
    } catch {
      toast.error(t("compress.startFailed"))
    } finally {
      setBusy(false)
    }
  }

  const tot = estimate?.totals
  const skips = tot ? skipList(tot.skip) : []
  const leftOut = (request.leftOut ?? 0) + (tot?.skip.not_in_target ?? 0)
  const targets = [...new Set(request.parts.map((p) => p.target))]
  const canStart = !busy && !!estimate && (!blocked || blocked.ok) && parts.length > 0 && (auto || estimate.totals.encode > 0 || estimate.pending > 0)

  return (
    <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-lg" data-testid="compress-dialog">
      <DialogHeader>
        <p className="eyebrow">{auto ? t("compress.auto.title") : t("compress.title")}</p>
        <DialogTitle className="text-lg">{auto ? t("compress.auto.heading", { name: request.label }) : t("compress.heading", { name: request.label })}</DialogTitle>
        <DialogDescription>
          {auto ? t("compress.auto.desc") : targets.map((tg) => t(`compress.target.${tg}`)).join(" ")}
        </DialogDescription>
      </DialogHeader>

      {blocked && !blocked.ok ? (
        <p role="alert" className="flex items-start gap-2 rounded-lg border bg-muted/40 p-3 text-[0.9375rem]" data-testid="compress-unavailable">
          <WarningIcon className="mt-0.5 size-4 shrink-0 text-warning" />
          {t(unavailableKey(blocked.reason))}
        </p>
      ) : (
        <>
          {!auto && profiles.length > 1 && (
            <fieldset className="flex flex-col gap-2">
              <legend className="eyebrow mb-2">{t("compress.profile.label")}</legend>
              <div className="grid gap-2 sm:grid-cols-2" role="radiogroup">
                {profiles.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    role="radio"
                    aria-checked={profile === p.id}
                    onClick={() => setProfile(p.id)}
                    className={cn(
                      "flex flex-col items-start gap-0.5 rounded-lg border p-3 text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                      profile === p.id ? "border-primary bg-primary/5" : "hover:bg-muted/50"
                    )}
                  >
                    <span className="text-[0.9375rem] font-medium">{t(`compress.profile.${profileKey(p.id)}`)}</span>
                    <span className="text-sm text-muted-foreground">{t(`compress.profile.${profileKey(p.id)}Desc`)}</span>
                  </button>
                ))}
              </div>
            </fieldset>
          )}

          <section aria-live="polite" className="flex flex-col gap-2" data-testid="compress-estimate">
            <p className="eyebrow">{t("compress.estimate.title")}</p>
            {failed ? (
              <p className="text-[0.9375rem] text-destructive">{failed === "load" ? t("compress.estimate.failed") : t(unavailableKey(failed))}</p>
            ) : !tot ? (
              <p className="flex items-center gap-2 text-[0.9375rem] text-muted-foreground">
                <Spinner className="size-4" />
                {t("compress.estimate.estimating")}
              </p>
            ) : (
              <>
                <p className="text-[0.9375rem] font-medium" data-testid="compress-summary">
                  {tot.encode > 0
                    ? t("compress.estimate.summary", {
                        count: tot.items - (tot.skip.not_in_target ?? 0),
                        encode: formatNumber(tot.encode, lang),
                        total: formatNumber(tot.items - (tot.skip.not_in_target ?? 0), lang),
                        size: formatBytes(tot.size, lang),
                        est: formatBytes(tot.estSize, lang),
                        time: formatEta(tot.estMs, t),
                      })
                    : t("compress.estimate.nothing")}
                </p>
                {estimate.pending > 0 && <p className="text-sm text-muted-foreground">{t("compress.estimate.pending", { count: estimate.pending })}</p>}
                {skips.length > 0 && (
                  <ul className="flex flex-col gap-0.5 text-sm text-muted-foreground" data-testid="compress-skips">
                    {skips.map((s) => (
                      <li key={s.reason}>{t("compress.estimate.skipped", { count: s.count, reason: t(skipKey(s.reason), { count: s.count }) })}</li>
                    ))}
                  </ul>
                )}
                {leftOut > 0 && <p className="text-sm text-muted-foreground">{t("compress.estimate.leftOut", { count: leftOut })}</p>}
                {(tot?.encode ?? 0) > 0 && <SpaceNote space={estimate.space} lang={lang} />}
                {estimate.warnings.includes("hdr10plus") && <Note icon="warn">{t("compress.estimate.warnings.hdr10plus")}</Note>}
                {estimate.warnings.includes("pcm_audio") && <Note>{t("compress.estimate.warnings.pcm_audio")}</Note>}
                {estimate.warnings.includes("av1_playback") && <Note icon="warn">{t("compress.estimate.warnings.av1_playback")}</Note>}
                {estimate.warnings.includes("per_frame_metadata") && <Note>{t("compress.estimate.warnings.per_frame_metadata")}</Note>}
              </>
            )}
          </section>

          {auto ? (
            <>
              {tot && tot.encode > 0 && (
                <label className="flex items-start gap-2 text-[0.9375rem]">
                  <Checkbox checked={also} onCheckedChange={(v) => setAlso(v === true)} className="mt-0.5" data-testid="compress-also" />
                  {t("compress.auto.existing", { count: tot.encode })}
                </label>
              )}
            </>
          ) : (
            <details open={advanced} onToggle={(e) => setAdvanced((e.target as HTMLDetailsElement).open)} className="rounded-lg border px-3 py-2">
              <summary className="cursor-pointer text-sm font-medium">{t("compress.quality.advanced")}</summary>
              <div className="mt-2 flex flex-col gap-2" role="radiogroup" aria-label={t("compress.quality.label")}>
                {(["floor", "fixed", "target"] as const).map((q) => (
                  <label key={q} className="flex items-start gap-2 text-sm">
                    <input type="radio" name="quality" checked={quality === q} onChange={() => setQuality(q)} className="mt-0.5 accent-primary" />
                    <span>
                      <span className="font-medium">{t(`compress.quality.${q}`)}</span>
                      <span className="block text-muted-foreground">{t(`compress.quality.${q}Desc`)}</span>
                    </span>
                  </label>
                ))}
                {quality === "target" && (
                  <label className="ml-6 flex items-center gap-2 text-sm">
                    {t("compress.quality.vmaf")}
                    <input
                      type="number"
                      min={90}
                      max={99}
                      value={vmaf}
                      onChange={(e) => setVmaf(Math.min(99, Math.max(90, Number(e.target.value) || 95)))}
                      className="h-8 w-20 rounded-md border border-input bg-card px-2 tabular-nums"
                    />
                  </label>
                )}
              </div>
            </details>
          )}

          <Note icon="trash">{t("compress.trash.note", { count: info.trashDays ?? 30 })}</Note>
        </>
      )}

      <DialogFooter>
        {auto && request.autoJob && (
          <Button variant="outline" className="sm:mr-auto" disabled={busy} onClick={() => void turnOff()} data-testid="compress-turn-off">
            {t("compress.auto.turnOff")}
          </Button>
        )}
        <Button variant="outline" onClick={onClose}>
          {t("compress.close")}
        </Button>
        {(!blocked || blocked.ok) && (
          <Button disabled={!canStart || (auto && !!request.autoJob && !also)} onClick={() => void start()} data-testid="compress-start">
            {busy ? <Spinner data-icon="inline-start" /> : <ArrowsInIcon data-icon="inline-start" />}
            {auto ? (request.autoJob ? t("compress.auto.compressExisting") : t("compress.auto.turnOn")) : t("compress.start")}
          </Button>
        )}
      </DialogFooter>
    </DialogContent>
  )
}

const profileKey = (id: string) => (id === "hevc-cq30" ? "smaller" : id.startsWith("av1") ? "av1" : "same")

function Note({ children, icon }: { children: React.ReactNode; icon?: "warn" | "trash" }) {
  const Icon = icon === "warn" ? WarningIcon : icon === "trash" ? TrashIcon : InfoIcon
  return (
    <p className="flex items-start gap-2 text-sm text-muted-foreground">
      <Icon className={cn("mt-0.5 size-4 shrink-0", icon === "warn" && "text-warning")} />
      <span>{children}</span>
    </p>
  )
}
