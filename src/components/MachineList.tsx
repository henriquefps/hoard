import {
  ArrowsClockwiseIcon,
  DotsThreeVerticalIcon,
  PencilSimpleIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import type { MachineWithAddresses } from "@/db/types"
import { DeleteMachineDialog } from "@/features/machines/actions"
import { useMachineRoute, useRouteEngine } from "@/features/route/RouteProvider"
import { RouteStatus } from "@/features/route/RouteStatus"
import { cn } from "@/lib/utils"
import { AddressKindBadge } from "./AddressKindBadge"
import { MachineIcon } from "./MachineIcon"

const COLUMNS =
  "@xl:grid @xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_3.75rem] @3xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_14rem_3.75rem]"

/**
 * Machines with their live route ("Local · 192.168.1.155 · 4 ms"). Card rows below `@xl` of the
 * container, a table from `@xl` (name, route in use, address kinds, actions). Tapping a row opens
 * the machine's shares; edit, recheck and delete live in the "⋯" menu.
 */
export function MachineList({
  machines,
  showHeader = true,
}: {
  machines: MachineWithAddresses[]
  showHeader?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="@container">
      {showHeader && (
        <div
          className={cn(
            "hidden h-11 items-center border-b px-4 text-sm text-muted-foreground md:px-6",
            COLUMNS
          )}
        >
          <span className="pl-[3.25rem]">{t("machines.columns.name")}</span>
          <span className="pr-3">{t("machines.columns.route")}</span>
          <span className="hidden pr-3 @3xl:block">{t("machines.columns.addresses")}</span>
          <span />
        </div>
      )}
      <ul className={cn(!showHeader && "border-t")}>
        {machines.map((machine) => (
          <MachineRow key={machine.id} machine={machine} />
        ))}
      </ul>
    </div>
  )
}

function MachineRow({ machine }: { machine: MachineWithAddresses }) {
  const { t } = useTranslation()
  const route = useMachineRoute(machine)
  const engine = useRouteEngine()
  const [deleting, setDeleting] = useState(false)
  const kinds = [...new Set(machine.addresses.map((a) => a.kind))]
  return (
    <li
      className={cn(
        "relative flex min-h-16 items-center gap-3 border-b px-4 py-2 transition-colors hover:bg-muted/50 has-[a:focus-visible]:bg-muted/50 md:px-6",
        "@xl:gap-0",
        COLUMNS
      )}
    >
      <div className="flex min-w-0 flex-1 items-center gap-3 @xl:pr-3">
        <MachineIcon icon={machine.icon} color={machine.color} />
        <Link
          to="/browse/$locationId"
          params={{ locationId: machine.id }}
          className="flex min-w-0 flex-1 flex-col gap-0.5 outline-none after:absolute after:inset-0"
        >
          <span className="line-clamp-2 text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
            {machine.name}
          </span>
          <RouteStatus state={route} className="max-w-full @xl:hidden" />
        </Link>
      </div>
      <div className="hidden min-w-0 pr-3 @xl:flex">
        <RouteStatus state={route} className="max-w-full" />
      </div>
      <div className="hidden flex-wrap gap-1 pr-3 @3xl:flex">
        {kinds.map((kind) => (
          <AddressKindBadge key={kind} kind={kind} />
        ))}
      </div>
      <div className="relative z-10 flex shrink-0 items-center justify-end @xl:pr-4">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              aria-label={t("machines.actionsFor", { name: machine.name })}
            >
              <DotsThreeVerticalIcon weight="bold" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="min-w-48">
            <DropdownMenuItem asChild>
              <Link to="/machines/$machineId/edit" params={{ machineId: machine.id }}>
                <PencilSimpleIcon />
                {t("common.edit")}
              </Link>
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => void engine.check(machine, { force: true })}>
              <ArrowsClockwiseIcon />
              {t("route.recheck")}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem variant="destructive" onSelect={() => setDeleting(true)}>
              <TrashIcon />
              {t("common.delete")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <DeleteMachineDialog machine={machine} open={deleting} onOpenChange={setDeleting} />
    </li>
  )
}
