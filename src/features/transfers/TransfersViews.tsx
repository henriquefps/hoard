import { ArrowsDownUpIcon, BroomIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import { clearFinishedTransfers, listMachines } from "@/db/queries"
import { cn } from "@/lib/utils"
import { setTransfersDrawerOpen, useTransfersDrawerOpen } from "./drawerState"
import { TransferRow } from "./TransferRow"
import { type TransferView, useTransfers } from "./TransfersProvider"

/** Overall progress of the active transfers (0-100), or null when nothing runs. */
export function overallProgress(transfers: TransferView[] | undefined) {
  const active = transfers?.filter((t) => t.active) ?? []
  if (active.length === 0) return null
  const total = active.reduce((sum, t) => sum + t.total, 0)
  const done = active.reduce((sum, t) => sum + t.bytesDone, 0)
  return { count: active.length, percent: total > 0 ? (done / total) * 100 : 0 }
}

/** Active transfers first, then the history; "Clear finished". Used by the page and the drawer. */
export function TransfersList({
  limitFinished,
  compact = false,
}: {
  limitFinished?: number
  compact?: boolean
}) {
  const { t } = useTranslation()
  const db = useDb()
  const transfers = useTransfers()
  const machines = useLiveQuery(listMachines)
  if (!transfers) {
    return (
      <div className="flex flex-col gap-2 p-4">
        <Skeleton className="h-16" />
        <Skeleton className="h-16" />
      </div>
    )
  }
  const active = transfers.filter((x) => x.active)
  const finished = transfers.filter((x) => !x.active)
  const shown = limitFinished ? finished.slice(0, limitFinished) : finished
  const machine = (id: string) => machines?.find((m) => m.id === id)
  if (transfers.length === 0) {
    return (
      <Empty className={cn("border", compact && "m-4")}>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <ArrowsDownUpIcon />
          </EmptyMedia>
          <EmptyTitle>{t("transfers.emptyTitle")}</EmptyTitle>
          <EmptyDescription>{t("transfers.emptyBody")}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }
  return (
    <div className="flex flex-col">
      {active.length > 0 && (
        <section aria-labelledby="transfers-active">
          <h2
            id="transfers-active"
            className={cn("px-4 pt-2 pb-2 eyebrow md:px-6", compact && "md:px-4")}
          >
            {t("transfers.active", { count: active.length })}
          </h2>
          <ul className="border-t">
            {active.map((transfer) => (
              <li key={transfer.id}>
                <TransferRow
                  transfer={transfer}
                  machine={machine(transfer.machine_id)}
                  compact={compact}
                />
              </li>
            ))}
          </ul>
        </section>
      )}
      {finished.length > 0 && (
        <section aria-labelledby="transfers-finished" className={cn(active.length > 0 && "mt-4")}>
          <div
            className={cn(
              "flex items-center justify-between gap-2 px-4 pb-1 md:px-6",
              compact && "md:px-4"
            )}
          >
            <h2 id="transfers-finished" className="eyebrow">
              {t("transfers.finished")}
            </h2>
            <Button variant="ghost" onClick={() => void clearFinishedTransfers(db)}>
              <BroomIcon data-icon="inline-start" />
              {t("transfers.clearFinished")}
            </Button>
          </div>
          <ul className="border-t">
            {shown.map((transfer) => (
              <li key={transfer.id}>
                <TransferRow
                  transfer={transfer}
                  machine={machine(transfer.machine_id)}
                  compact={compact}
                />
              </li>
            ))}
          </ul>
          {shown.length < finished.length && (
            <p className="px-4 py-3 text-[0.8125rem] text-muted-foreground">
              {t("transfers.more", { count: finished.length - shown.length })}
            </p>
          )}
        </section>
      )}
    </div>
  )
}

/**
 * Phones: the queue in a bottom Drawer (Vaul: drags down to dismiss, flick, catchable), reachable
 * from the top bar's transfer indicator on every screen and from the "View" action of toasts.
 */
export function TransfersDrawer() {
  const { t } = useTranslation()
  const open = useTransfersDrawerOpen()
  const transfers = useTransfers()
  const progress = overallProgress(transfers)
  return (
    <Drawer open={open} onOpenChange={setTransfersDrawerOpen}>
      <DrawerContent className="gap-0 data-[vaul-drawer-direction=bottom]:max-h-[85dvh] [&>div:first-child]:mt-2 [&>div:first-child]:h-1 [&>div:first-child]:w-10 [&>div:first-child]:bg-muted-foreground/30">
        <DrawerHeader className="shrink-0 px-4 pt-4 pb-3 text-left">
          <DrawerTitle className="text-lg font-semibold">{t("transfers.title")}</DrawerTitle>
          <DrawerDescription>
            {progress
              ? t("transfers.activeSummary", {
                  count: progress.count,
                  percent: Math.floor(progress.percent),
                })
              : t("transfers.description")}
          </DrawerDescription>
        </DrawerHeader>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          <TransfersList limitFinished={5} compact />
        </div>
        <DrawerFooter className="mt-0 shrink-0 border-t p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <Button asChild variant="outline" className="h-11 w-full">
            <Link to="/transfers" onClick={() => setTransfersDrawerOpen(false)}>
              {t("transfers.openPage")}
            </Link>
          </Button>
        </DrawerFooter>
      </DrawerContent>
    </Drawer>
  )
}
