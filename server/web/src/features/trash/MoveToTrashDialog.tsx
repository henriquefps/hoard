import { ArrowClockwiseIcon, TrashIcon, WarningIcon } from "@phosphor-icons/react"
import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Spinner } from "@/components/ui/spinner"
import { unavailableKey } from "@/lib/compress"
import { formatBytes, formatNumber } from "@/lib/format"
import { navigate } from "@/lib/router"
import { hideItems } from "@/lib/store"
import { type MovePlan, type MoveResult, moveToTrash, planMove, reasonsByCount } from "@/lib/trash"
import { Progress } from "../compress/parts"

/**
 * Move to trash on a selection (ADR 0023 Amendment (0.14.0)): a dry run first, in batches of 500 (Select all of
 * thousands), that says what goes where (NAS shots into the .hoard-trash Hoard uses, the others into the server
 * trash), what is skipped and why, and warns before an only copy or a photo with Reveal's edits goes; then the move,
 * batch after batch, with progress. Moved items leave the grid at once. When some fail (0.15.0), the dialog stays open
 * with how many failed for each reason and Retry the failed ones, which sends exactly those again.
 */
export function MoveToTrashDialog({ ids, onClose, onMoved }: { ids: string[] | null; onClose: () => void; onMoved: (ids: string[]) => void }) {
  return (
    <Dialog open={!!ids} onOpenChange={(o) => !o && onClose()}>
      {ids && <Body key={ids.join(",")} ids={ids} onClose={onClose} onMoved={onMoved} />}
    </Dialog>
  )
}

function Body({ ids, onClose, onMoved }: { ids: string[]; onClose: () => void; onMoved: (ids: string[]) => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [plan, setPlan] = useState<MovePlan | null>(null)
  const [failed, setFailed] = useState(false)
  const [progress, setProgress] = useState(0)
  const [moving, setMoving] = useState<number | null>(null)
  // After a move with failures: what failed (per reason and per item), and how many moved so far.
  const [result, setResult] = useState<{ failed: MoveResult["failed"]; ids: string[]; moved: number } | null>(null)

  useEffect(() => {
    let stop = false
    planMove(ids, (f) => !stop && setProgress(f))
      .then((p) => !stop && setPlan(p))
      .catch(() => !stop && setFailed(true))
    return () => {
      stop = true
    }
  }, [ids])

  const move = async (which: string[] = ids) => {
    setMoving(0)
    try {
      const res = await moveToTrash(which, (f) => setMoving(f))
      if (res.moved.length) {
        hideItems(res.moved)
        onMoved(res.moved)
      }
      const skipped = Object.values(res.skipped).reduce((s, n) => s + n, 0)
      const failedIds = Object.keys(res.failedIds)
      const moved = (result?.moved ?? 0) + res.moved.length
      if (res.refused) toast.error(t("trash.move.refused", { reason: t("trash.reason.no_write_mount") }))
      if (res.moved.length) {
        toast.success(t("trash.move.done", { count: res.moved.length, formatted: formatNumber(res.moved.length, lang) }), {
          description: skipped ? t("trash.move.skippedNote", { count: skipped }) : undefined,
          action: { label: t("trash.open"), onClick: () => navigate("/trash") },
        })
      }
      if (failedIds.length && !res.refused) {
        // Stay open: say why, per reason, and offer to send the failed ones again.
        setResult({ failed: res.failed, ids: failedIds, moved })
        setMoving(null)
        return
      }
      onClose()
    } catch {
      toast.error(t("trash.move.failed"))
      setMoving(null)
    }
  }

  const skipped = plan ? Object.entries(plan.skipped).sort((a, b) => b[1] - a[1]) : []
  return (
    <DialogContent className="sm:max-w-lg" data-testid="trash-move-dialog">
      <DialogHeader>
        <p className="eyebrow">{t("trash.title")}</p>
        <DialogTitle className="text-lg">{t("trash.move.title", { count: ids.length, formatted: formatNumber(ids.length, lang) })}</DialogTitle>
        <DialogDescription className="text-[0.9375rem]">{t("trash.move.description")}</DialogDescription>
      </DialogHeader>

      {!plan && !failed && (
        <div className="flex flex-col gap-2" role="status">
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner />
            {t("trash.move.checking")}
          </p>
          {ids.length > 500 && <Progress value={progress} label={t("trash.move.checking")} />}
        </div>
      )}
      {failed && <p className="text-sm text-destructive">{t("trash.move.planFailed")}</p>}
      {plan && (
        <div className="flex flex-col gap-2" data-testid="trash-move-plan">
          {plan.shots > 0 ? (
            <p className="text-[0.9375rem] font-medium tabular-nums" data-testid="trash-move-summary">
              {t("trash.move.summary", { count: plan.shots, formatted: formatNumber(plan.shots, lang), size: formatBytes(plan.size, lang) })}
            </p>
          ) : (
            <p className="text-[0.9375rem] font-medium">{t("trash.move.nothing")}</p>
          )}
          {plan.nasShots > 0 && <p className="text-sm text-muted-foreground">{t("trash.move.nas", { count: plan.nasShots, files: plan.nasFiles })}</p>}
          {plan.serverItems > 0 && <p className="text-sm text-muted-foreground">{t("trash.move.server", { count: plan.serverItems })}</p>}
          {skipped.length > 0 && (
            <ul className="list-disc pl-5 text-sm text-muted-foreground" data-testid="trash-move-skipped">
              {skipped.map(([why, n]) => (
                <li key={why}>{t("trash.move.skipped", { count: n, reason: t(`trash.reason.${why}`, { defaultValue: why }) })}</li>
              ))}
            </ul>
          )}
          {plan.nasBlocked && plan.nasShots > 0 && (
            <Warn testId="trash-move-blocked">{t("trash.move.blocked", { reason: t(`trash.reason.${plan.nasBlocked}`, { defaultValue: t(unavailableKey(plan.nasBlocked)) }) })}</Warn>
          )}
          {plan.onlyCopy.length > 0 && <Warn testId="trash-move-only-copy">{t("trash.move.onlyCopy", { count: plan.onlyCopy.length })}</Warn>}
          {plan.edits.length > 0 && <Warn testId="trash-move-edits">{t("trash.move.edits", { count: plan.edits.length })}</Warn>}
          <p className="text-sm text-muted-foreground">{t("trash.move.restoreNote")}</p>
        </div>
      )}
      {result && moving === null && (
        <div className="flex flex-col gap-1.5 rounded-lg border border-destructive/40 bg-destructive/5 p-2.5" role="alert" data-testid="trash-move-failed">
          <p className="text-sm font-medium">
            {t("trash.move.failedTitle", { count: result.ids.length, formatted: formatNumber(result.ids.length, lang), moved: formatNumber(result.moved, lang) })}
          </p>
          <ul className="list-disc pl-5 text-sm text-muted-foreground">
            {reasonsByCount(result.failed).map(([why, n]) => (
              <li key={why} data-reason={why}>
                {t("trash.move.someFailed", { count: n, reason: t(`trash.reason.${why}`, { defaultValue: why }) })}
              </li>
            ))}
          </ul>
        </div>
      )}
      {moving !== null && <Progress value={moving} label={t("trash.move.moving")} />}

      <DialogFooter>
        <Button variant="outline" onClick={onClose}>
          {t("compress.close")}
        </Button>
        {result ? (
          <Button disabled={moving !== null} onClick={() => void move(result.ids)} data-testid="trash-move-retry">
            {moving !== null ? <Spinner data-icon="inline-start" /> : <ArrowClockwiseIcon data-icon="inline-start" />}
            {t("trash.move.retry", { count: result.ids.length })}
          </Button>
        ) : (
          <Button
            disabled={!plan || plan.shots === 0 || moving !== null || (!!plan.nasBlocked && plan.nasShots > 0)}
            onClick={() => void move()}
            data-testid="trash-move-confirm"
          >
            {moving !== null ? <Spinner data-icon="inline-start" /> : <TrashIcon data-icon="inline-start" />}
            {t("trash.move.action")}
          </Button>
        )}
      </DialogFooter>
    </DialogContent>
  )
}

function Warn({ children, testId }: { children: React.ReactNode; testId: string }) {
  return (
    <p className="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 p-2.5 text-sm" role="alert" data-testid={testId}>
      <WarningIcon className="mt-0.5 size-4 shrink-0 text-warning" />
      <span>{children}</span>
    </p>
  )
}
