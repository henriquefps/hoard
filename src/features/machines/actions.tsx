import { TrashIcon } from "@phosphor-icons/react"
import { useState, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { useDb } from "@/db/DbProvider"
import { deleteMachine } from "@/db/queries"
import type { TransferRecord } from "@/db/types"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { HoardSmb } from "@/plugins/smb"
import { TERMINAL_TRANSFER_STATES } from "@/plugins/smb/definitions"

/**
 * Delete a machine everywhere: cancel its running transfers, forget its credentials in the Keystore,
 * drop its route/session, then the row (addresses, favorites and transfer history cascade).
 */
export function useDeleteMachine() {
  const db = useDb()
  const engine = useRouteEngine()
  return async (machineId: string) => {
    const running = await db.query<TransferRecord>(
      `SELECT * FROM transfers WHERE (source_machine_id = ? OR dest_machine_id = ?) AND state NOT IN (${TERMINAL_TRANSFER_STATES.map(() => "?").join(", ")})`,
      [machineId, machineId, ...TERMINAL_TRANSFER_STATES]
    )
    await Promise.all(
      running.map((row) => HoardSmb.cancelTransfer({ transferId: row.id }).catch(() => {}))
    )
    await HoardSmb.deleteCredentials({ machineId }).catch(() => {})
    engine.forget(machineId)
    await deleteMachine(db, machineId)
  }
}

/** Confirmation for deleting a machine; controlled, so menus and buttons can open it. */
export function DeleteMachineDialog({
  machine,
  open,
  onOpenChange,
  onDeleted,
}: {
  machine: { id: string; name: string }
  open: boolean
  onOpenChange: (open: boolean) => void
  onDeleted?: () => void
}) {
  const { t } = useTranslation()
  const remove = useDeleteMachine()
  const [busy, setBusy] = useState(false)
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto">
        <AlertDialogHeader>
          <AlertDialogMedia className="bg-destructive/10 text-destructive">
            <TrashIcon />
          </AlertDialogMedia>
          <AlertDialogTitle className="[overflow-wrap:anywhere]">
            {t("machines.delete.title", { name: machine.name })}
          </AlertDialogTitle>
          <AlertDialogDescription>{t("machines.delete.body")}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={busy}
            onClick={async (event) => {
              event.preventDefault()
              setBusy(true)
              try {
                await remove(machine.id)
                toast.success(t("machines.delete.done", { name: machine.name }))
                onOpenChange(false)
                onDeleted?.()
              } catch {
                toast.error(t("errors.UNKNOWN.title"))
              } finally {
                setBusy(false)
              }
            }}
          >
            {t("common.delete")}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** Small helper: a section with an eyebrow label (form sections, settings). */
export function Section({
  title,
  description,
  children,
  action,
}: {
  title: string
  description?: string
  children: ReactNode
  action?: ReactNode
}) {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex min-h-9 items-center justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <h2 className="eyebrow">{title}</h2>
          {description && <p className="text-[0.9375rem] text-muted-foreground">{description}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}
