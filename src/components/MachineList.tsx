import { CaretRightIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import type { MachineWithAddresses } from "@/db/types"
import { AddressKindBadge } from "./AddressKindBadge"
import { MachineIcon } from "./MachineIcon"

/**
 * Placeholder machine list (one card-list row per machine: name, then mono addresses joined by
 * " · "). The real list with route status and row actions comes with the machines screen.
 */
export function MachineList({ machines }: { machines: MachineWithAddresses[] }) {
  const { t } = useTranslation()
  return (
    <ul className="-mx-4 border-t md:-mx-6">
      {machines.map((machine) => {
        const kinds = [...new Set(machine.addresses.map((a) => a.kind))]
        return (
          <li key={machine.id} className="border-b">
            <Link
              to="/browse/$machineId"
              params={{ machineId: machine.id }}
              className="flex min-h-14 items-center gap-3 px-4 py-3 transition-colors outline-none hover:bg-muted/50 focus-visible:bg-muted/50 md:px-6"
            >
              <MachineIcon icon={machine.icon} color={machine.color} />
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="line-clamp-2 text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
                  {machine.name}
                </span>
                <span
                  className="truncate font-mono text-[0.8125rem] text-muted-foreground"
                  title={machine.addresses.map((a) => a.host).join(" · ")}
                >
                  {machine.addresses.length
                    ? machine.addresses.map((a) => a.host).join(" · ")
                    : t("machines.addressCount", { count: 0 })}
                </span>
              </div>
              <div className="hidden shrink-0 gap-1 @md:flex">
                {kinds.map((kind) => (
                  <AddressKindBadge key={kind} kind={kind} />
                ))}
              </div>
              <CaretRightIcon aria-hidden className="shrink-0 text-muted-foreground" />
            </Link>
          </li>
        )
      })}
    </ul>
  )
}
