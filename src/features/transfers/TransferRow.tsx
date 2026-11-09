import {
  ArrowClockwiseIcon,
  CheckCircleIcon,
  DotsThreeVerticalIcon,
  FileArrowDownIcon,
  MinusCircleIcon,
  TrashIcon,
  WarningCircleIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { AddressKindBadge } from "@/components/AddressKindBadge"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Progress } from "@/components/ui/progress"
import { Spinner } from "@/components/ui/spinner"
import { useDb } from "@/db/DbProvider"
import { deleteTransfer } from "@/db/queries"
import type { MachineWithAddresses } from "@/db/types"
import { detectAddressKind } from "@/lib/addressKind"
import {
  etaSeconds,
  formatBytes,
  formatDuration,
  formatRelativeDate,
  formatSpeed,
} from "@/lib/format"
import { cn } from "@/lib/utils"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { type TransferView, useTransferActions } from "./TransfersProvider"

/**
 * One transfer: name, progress bar, "120 MB of 380 MB · 9 MB/s · 31 s left", the host the bytes
 * come from (with its kind, so a failover shows), and cancel / retry.
 */
export function TransferRow({
  transfer,
  machine,
  compact = false,
}: {
  transfer: TransferView
  machine: MachineWithAddresses | undefined
  compact?: boolean
}) {
  const { t, i18n } = useTranslation()
  const db = useDb()
  const actions = useTransferActions()
  const [busy, setBusy] = useState(false)
  const locale = i18n.language
  const { state, bytesDone, total, live } = transfer
  const percent =
    total > 0 ? Math.min(100, (bytesDone / total) * 100) : state === "completed" ? 100 : 0
  const speed = state === "running" ? live?.bytesPerSecond : undefined
  const eta = etaSeconds(bytesDone, total, speed)
  const host = live?.activeHost
  const failed = state === "failed"
  const canRetry = state === "failed" || state === "cancelled"

  let detail: string
  if (state === "running" || state === "retrying" || state === "queued") {
    detail = [
      t("transfers.progress", {
        done: formatBytes(bytesDone, locale),
        total: formatBytes(total, locale),
      }),
      speed ? formatSpeed(speed, locale) : null,
      eta !== undefined ? t("transfers.left", { time: formatDuration(eta) }) : null,
    ]
      .filter(Boolean)
      .join(" · ")
  } else {
    detail = [
      formatBytes(state === "completed" ? total : bytesDone, locale) +
        (state !== "completed" && total ? ` / ${formatBytes(total, locale)}` : ""),
      formatRelativeDate(transfer.finished_at ?? transfer.created_at, locale),
    ].join(" · ")
  }

  async function retry() {
    setBusy(true)
    try {
      await actions.retry(transfer)
    } catch (error) {
      toast.error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")))
    } finally {
      setBusy(false)
    }
  }

  const StateIcon =
    state === "completed"
      ? CheckCircleIcon
      : failed
        ? WarningCircleIcon
        : state === "cancelled" || state === "skipped"
          ? MinusCircleIcon
          : FileArrowDownIcon

  return (
    <div className={cn("flex items-start gap-3 border-b px-4 py-3 md:px-6", compact && "md:px-4")}>
      <span
        aria-hidden
        className={cn(
          "mt-0.5 inline-flex size-10 shrink-0 items-center justify-center rounded-lg [&_svg]:size-5",
          state === "completed" && "bg-primary/10 text-primary",
          failed && "bg-destructive/10 text-destructive",
          transfer.active && "bg-primary/10 text-primary",
          (state === "cancelled" || state === "skipped") && "bg-muted text-muted-foreground"
        )}
      >
        <StateIcon weight={transfer.active ? "duotone" : "fill"} />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex items-start justify-between gap-2">
          <span className="line-clamp-2 text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
            {transfer.dest_name}
          </span>
          <StateBadge state={state} />
        </div>
        {transfer.active && (
          <Progress
            value={percent}
            tone={state === "retrying" ? "warning" : state === "queued" ? "muted" : "default"}
            aria-label={t("transfers.progressLabel", { name: transfer.dest_name })}
          />
        )}
        <p className="text-[0.8125rem] text-muted-foreground tabular-nums">{detail}</p>
        <p className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem] text-muted-foreground">
          <span className="truncate">
            {machine?.name ?? t("transfers.unknownMachine")} · {transfer.share}
          </span>
          {host && transfer.active && (
            <span className="inline-flex min-w-0 items-center gap-1.5">
              <span className="truncate font-mono">{host}</span>
              <AddressKindBadge kind={detectAddressKind(host)} />
            </span>
          )}
        </p>
        {(failed || state === "retrying") && transfer.error_code && (
          <p className={cn("text-[0.8125rem]", failed ? "text-destructive" : "text-warning")}>
            {state === "retrying"
              ? t("transfers.retryingHint")
              : t(`route.problem.${transfer.error_code}`, t("route.problem.UNKNOWN"))}
          </p>
        )}
      </div>
      <div className="flex shrink-0 items-center">
        {transfer.active ? (
          <Button
            variant="ghost"
            size="icon"
            onClick={() => void actions.cancel(transfer.id)}
            aria-label={t("transfers.cancel", { name: transfer.dest_name })}
          >
            <XIcon />
          </Button>
        ) : (
          <>
            {canRetry && (
              <Button
                variant="ghost"
                size="icon"
                onClick={retry}
                disabled={busy}
                aria-label={t("transfers.retry", { name: transfer.dest_name })}
              >
                {busy ? <Spinner /> : <ArrowClockwiseIcon />}
              </Button>
            )}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" aria-label={t("common.more")}>
                  <DotsThreeVerticalIcon weight="bold" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={() => void deleteTransfer(db, transfer.id)}>
                  <TrashIcon />
                  {t("transfers.remove")}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        )}
      </div>
    </div>
  )
}

function StateBadge({ state }: { state: TransferView["state"] }) {
  const { t } = useTranslation()
  return (
    <span
      className={cn(
        "shrink-0 rounded-md border px-1.5 py-0.5 text-xs font-medium whitespace-nowrap",
        state === "running" && "border-primary/30 text-primary",
        state === "retrying" && "border-warning/40 text-warning",
        state === "failed" && "border-destructive/40 text-destructive",
        (state === "queued" ||
          state === "cancelled" ||
          state === "skipped" ||
          state === "completed") &&
          "text-muted-foreground"
      )}
    >
      {t(`transfers.states.${state}`)}
    </span>
  )
}
