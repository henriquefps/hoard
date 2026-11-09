import {
  ArrowClockwiseIcon,
  ArrowRightIcon,
  CheckCircleIcon,
  DotsThreeVerticalIcon,
  FileArrowDownIcon,
  FileArrowUpIcon,
  FolderOpenIcon,
  MinusCircleIcon,
  QuestionIcon,
  TrashIcon,
  WarningCircleIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useNavigate } from "@tanstack/react-router"
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
import { deleteTransfer, transferSourcePaths } from "@/db/queries"
import { detectAddressKind } from "@/lib/addressKind"
import {
  etaSeconds,
  formatBytes,
  formatDuration,
  formatNumber,
  formatRelativeDate,
  formatSpeed,
} from "@/lib/format"
import { cn } from "@/lib/utils"
import type { TransferItem } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { askAgain } from "./conflictPrompt"
import { setTransfersDrawerOpen } from "./drawerState"
import { INCOMING_LOCATION_ID, INTERRUPTED, isMachine, TREE_LOCATION_ID } from "./service"
import { type TransferView, useTransferActions } from "./TransfersProvider"

/** Names a place for a row: machine, drive, Android folder, "Shared from another app". */
export type PlaceNamer = (
  locationId: string,
  share?: string,
  path?: string,
  treeUri?: string | null
) => string

/**
 * One job: what ("DCIM and 2 more"), from → to, batch progress ("120 of 340 files · 1.2 GB of
 * 4 GB · 9 MB/s · 31 s left"), the file being copied, the host the bytes travel through (so a
 * failover shows), per-item errors on demand, and cancel / retry failed / open destination.
 */
export function TransferRow({
  transfer,
  placeName,
  compact = false,
}: {
  transfer: TransferView
  placeName: PlaceNamer
  compact?: boolean
}) {
  const { t, i18n } = useTranslation()
  const db = useDb()
  const navigate = useNavigate()
  const actions = useTransferActions()
  const [busy, setBusy] = useState(false)
  const [failedItems, setFailedItems] = useState<TransferItem[] | null>()
  const locale = i18n.language
  const { state, bytesDone, total, live } = transfer
  const percent =
    total > 0 ? Math.min(100, (bytesDone / total) * 100) : state === "completed" ? 100 : 0
  const speed = state === "running" ? live?.bytesPerSecond : undefined
  const eta = etaSeconds(bytesDone, total, speed)
  const host = live?.activeHost
  const failed = state === "failed"
  const canRetry = state === "failed" || state === "cancelled"
  const paths = transferSourcePaths(transfer)
  const many = transfer.itemsTotal > 1
  const upload = isMachine(transfer.dest_location_id)
  const finishedItems = transfer.itemsDone + transfer.itemsFailed + transfer.itemsSkipped

  const title =
    paths.length > 1
      ? t("transfers.nameMore", { name: transfer.name, count: paths.length - 1 })
      : transfer.name
  const from = placeName(
    transfer.source_location_id,
    transfer.source_share,
    parentOf(paths[0] ?? "")
  )
  const to = placeName(
    transfer.dest_location_id,
    transfer.dest_share,
    transfer.dest_path,
    transfer.dest_tree_uri
  )

  let detail: string
  if (state === "scanning") {
    detail = t("transfers.scanning", {
      count: transfer.itemsTotal,
      formatted: formatNumber(transfer.itemsTotal, locale),
    })
  } else if (state === "conflict") {
    detail = t("transfers.conflicts", { count: transfer.conflicts })
  } else if (transfer.active) {
    detail = [
      many
        ? t("transfers.itemsProgress", {
            done: formatNumber(finishedItems, locale),
            total: formatNumber(transfer.itemsTotal, locale),
            count: transfer.itemsTotal,
          })
        : null,
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
      many
        ? t("transfers.itemsDone", {
            done: formatNumber(transfer.itemsDone, locale),
            count: transfer.itemsTotal,
            total: formatNumber(transfer.itemsTotal, locale),
          })
        : null,
      formatBytes(state === "completed" ? total : bytesDone, locale) +
        (state !== "completed" && total ? ` / ${formatBytes(total, locale)}` : ""),
      formatRelativeDate(transfer.finished_at ?? transfer.created_at, locale),
    ]
      .filter(Boolean)
      .join(" · ")
  }

  async function retry() {
    setBusy(true)
    try {
      await actions.retry(transfer)
      setFailedItems(undefined)
    } catch (error) {
      toast.error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")))
    } finally {
      setBusy(false)
    }
  }

  async function toggleFailed() {
    if (failedItems) {
      setFailedItems(undefined)
      return
    }
    setFailedItems(null)
    try {
      const { items } = await actions.items({
        transferId: transfer.id,
        states: ["failed", "cancelled"],
        limit: 50,
      })
      setFailedItems(items)
    } catch {
      setFailedItems([])
    }
  }

  const openable =
    transfer.dest_location_id !== TREE_LOCATION_ID &&
    transfer.dest_location_id !== INCOMING_LOCATION_ID
  function openDestination() {
    setTransfersDrawerOpen(false)
    void navigate({
      to: "/browse/$locationId",
      params: { locationId: transfer.dest_location_id },
      search: { share: transfer.dest_share || undefined, path: transfer.dest_path || undefined },
    })
  }

  const StateIcon =
    state === "completed"
      ? CheckCircleIcon
      : failed
        ? WarningCircleIcon
        : state === "conflict"
          ? QuestionIcon
          : state === "cancelled" || state === "skipped"
            ? MinusCircleIcon
            : upload
              ? FileArrowUpIcon
              : FileArrowDownIcon

  return (
    <div className={cn("flex items-start gap-3 border-b px-4 py-3 md:px-6", compact && "md:px-4")}>
      <span
        aria-hidden
        className={cn(
          "mt-0.5 inline-flex size-10 shrink-0 items-center justify-center rounded-lg [&_svg]:size-5",
          state === "completed" && "bg-primary/10 text-primary",
          failed && "bg-destructive/10 text-destructive",
          state === "conflict" && "bg-warning/10 text-warning",
          transfer.active && state !== "conflict" && "bg-primary/10 text-primary",
          (state === "cancelled" || state === "skipped") && "bg-muted text-muted-foreground"
        )}
      >
        <StateIcon weight={transfer.active ? "duotone" : "fill"} />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex items-start justify-between gap-2">
          <span className="line-clamp-2 text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
            {title}
          </span>
          <StateBadge state={state} op={transfer.op} />
        </div>
        <p
          className="line-clamp-2 text-[0.8125rem] [overflow-wrap:anywhere] text-muted-foreground"
          title={`${from} → ${to}`}
        >
          {from}
          <ArrowRightIcon
            role="img"
            aria-label={t(transfer.op === "move" ? "transfers.movedTo" : "transfers.copiedTo")}
            className="mx-1 inline size-3.5 align-[-0.125em]"
          />
          {to}
        </p>
        {transfer.active && state !== "conflict" && (
          <Progress
            value={percent}
            tone={
              state === "retrying"
                ? "warning"
                : state === "queued" || state === "scanning"
                  ? "muted"
                  : "default"
            }
            aria-label={t("transfers.progressLabel", { name: title })}
          />
        )}
        <p className="text-[0.8125rem] text-muted-foreground tabular-nums">{detail}</p>
        {transfer.active && many && live?.currentName && state === "running" && (
          <p className="truncate text-[0.8125rem] text-muted-foreground" title={live.currentName}>
            {t("transfers.current", { name: live.currentName })}
          </p>
        )}
        {host && transfer.active && (
          <p className="flex min-w-0 items-center gap-1.5 text-[0.8125rem] text-muted-foreground">
            <span className="truncate font-mono">{host}</span>
            <AddressKindBadge kind={detectAddressKind(host)} />
          </p>
        )}
        {state === "conflict" && (
          <div>
            <Button variant="outline" onClick={() => askAgain(transfer.id)}>
              {t("transfers.answer")}
            </Button>
          </div>
        )}
        {(failed || state === "retrying") && transfer.error_code && (
          <p className={cn("text-[0.8125rem]", failed ? "text-destructive" : "text-warning")}>
            {state === "retrying"
              ? t("transfers.retryingHint")
              : many && transfer.itemsFailed > 0 && transfer.itemsFailed < transfer.itemsTotal
                ? t("transfers.someFailed", { count: transfer.itemsFailed })
                : transfer.error === INTERRUPTED
                  ? t("transfers.interrupted")
                  : t(`route.problem.${transfer.error_code}`, t("route.problem.UNKNOWN"))}
          </p>
        )}
        {!transfer.active && transfer.itemsFailed > 0 && (
          <div className="flex flex-col gap-1">
            <div>
              <Button variant="ghost" className="-ml-3" onClick={() => void toggleFailed()}>
                {failedItems === null && <Spinner data-icon="inline-start" />}
                {failedItems
                  ? t("transfers.hideFailed")
                  : t("transfers.showFailed", { count: transfer.itemsFailed })}
              </Button>
            </div>
            {failedItems && (
              <ul className="flex flex-col gap-1 rounded-lg border px-3 py-2">
                {failedItems.length === 0 && (
                  <li className="text-[0.8125rem] text-muted-foreground">
                    {t("transfers.detailsGone")}
                  </li>
                )}
                {failedItems.map((item) => (
                  <li key={item.index} className="flex min-w-0 flex-col text-[0.8125rem]">
                    <span className="truncate font-medium" title={item.sourcePath}>
                      {item.destPath.split("/").at(-1) || item.sourcePath}
                    </span>
                    <span className="text-destructive">
                      {item.error
                        ? t(`route.problem.${item.error.code}`, t("route.problem.UNKNOWN"))
                        : t("transfers.states.cancelled")}
                    </span>
                  </li>
                ))}
                {transfer.itemsFailed > failedItems.length && failedItems.length > 0 && (
                  <li className="text-[0.8125rem] text-muted-foreground">
                    {t("transfers.moreFailed", {
                      count: transfer.itemsFailed - failedItems.length,
                    })}
                  </li>
                )}
              </ul>
            )}
          </div>
        )}
      </div>
      <div className="flex shrink-0 items-center">
        {transfer.active ? (
          <Button
            variant="ghost"
            size="icon"
            onClick={() => void actions.cancel(transfer.id)}
            aria-label={t("transfers.cancel", { name: title })}
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
                aria-label={t(many ? "transfers.retryFailed" : "transfers.retry", { name: title })}
                title={t(many ? "transfers.retryFailed" : "transfers.retry", { name: title })}
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
                {openable && (
                  <DropdownMenuItem onSelect={openDestination}>
                    <FolderOpenIcon />
                    {t("transfers.openDestination")}
                  </DropdownMenuItem>
                )}
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

const parentOf = (path: string) => path.split("/").filter(Boolean).slice(0, -1).join("/")

function StateBadge({ state, op }: { state: TransferView["state"]; op: TransferView["op"] }) {
  const { t } = useTranslation()
  return (
    <span
      className={cn(
        "shrink-0 rounded-md border px-1.5 py-0.5 text-xs font-medium whitespace-nowrap",
        state === "running" && "border-primary/30 text-primary",
        (state === "retrying" || state === "conflict") && "border-warning/40 text-warning",
        state === "failed" && "border-destructive/40 text-destructive",
        (state === "queued" ||
          state === "scanning" ||
          state === "cancelled" ||
          state === "skipped" ||
          state === "completed") &&
          "text-muted-foreground"
      )}
    >
      {state === "running" ? t(`transfers.states.running_${op}`) : t(`transfers.states.${state}`)}
    </span>
  )
}
