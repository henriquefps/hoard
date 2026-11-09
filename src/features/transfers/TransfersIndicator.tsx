import { ArrowsDownUpIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { openTransfers } from "./drawerState"
import { overallProgress } from "./TransfersViews"
import { useTransfers } from "./TransfersProvider"

/**
 * Top-bar button with a progress ring while transfers run: opens the Drawer on phones and the
 * Transfers page from 768 px. Hidden when nothing is running and nothing failed recently.
 */
export function TransfersIndicator() {
  const { t } = useTranslation()
  const transfers = useTransfers()
  const progress = overallProgress(transfers)
  const failed = transfers?.some((x) => x.state === "failed" && isRecent(x.finished_at)) ?? false
  if (!progress && !failed) return null
  const radius = 15
  const circumference = 2 * Math.PI * radius
  const label = progress
    ? t("transfers.indicator", { count: progress.count, percent: Math.floor(progress.percent) })
    : t("transfers.indicatorFailed")
  return (
    <Button
      variant="ghost"
      size="icon"
      className="relative size-10"
      onClick={openTransfers}
      aria-label={label}
      title={label}
    >
      <svg viewBox="0 0 36 36" className="absolute inset-0 size-10 -rotate-90" aria-hidden>
        <circle cx="18" cy="18" r={radius} fill="none" strokeWidth="2.5" className="stroke-muted" />
        {progress && (
          <circle
            cx="18"
            cy="18"
            r={radius}
            fill="none"
            strokeWidth="2.5"
            strokeLinecap="round"
            className="stroke-primary transition-[stroke-dashoffset] duration-500 ease-out motion-reduce:transition-none"
            strokeDasharray={circumference}
            strokeDashoffset={circumference * (1 - progress.percent / 100)}
          />
        )}
      </svg>
      <ArrowsDownUpIcon className={cn("relative", failed && !progress && "text-destructive")} />
      {progress && progress.count > 1 && (
        <span className="absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-xs leading-none font-semibold text-primary-foreground tabular-nums">
          {progress.count}
        </span>
      )}
      {failed && !progress && (
        <span className="absolute top-1 right-1 size-2 rounded-full bg-destructive" aria-hidden />
      )}
    </Button>
  )
}

const isRecent = (iso: string | null) => Boolean(iso) && Date.now() - Date.parse(iso!) < 30 * 60_000
