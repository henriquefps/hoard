import { useState, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Spinner } from "@/components/ui/spinner"
import { cn } from "@/lib/utils"

/** A confirmation (cancel a job, empty an original now): says what happens, one destructive button. */
export function Confirm({
  open,
  title,
  body,
  action,
  destructive = true,
  onConfirm,
  onClose,
}: {
  open: boolean
  title: string
  body: ReactNode
  action: string
  destructive?: boolean
  onConfirm: () => Promise<void> | void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent showCloseButton={false} data-testid="confirm-dialog">
        <DialogHeader>
          <DialogTitle className="text-lg">{title}</DialogTitle>
          <DialogDescription className="text-[0.9375rem]">{body}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("compress.close")}
          </Button>
          <Button
            variant={destructive ? "destructive" : "default"}
            disabled={busy}
            data-testid="confirm-action"
            onClick={async () => {
              setBusy(true)
              try {
                await onConfirm()
              } finally {
                setBusy(false)
                onClose()
              }
            }}
          >
            {busy && <Spinner data-icon="inline-start" />}
            {action}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** A thin progress bar (0–1). */
export function Progress({ value, className, label }: { value: number; className?: string; label?: string }) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(value * 100)}
      className={cn("h-1.5 w-full overflow-hidden rounded-full bg-muted", className)}
    >
      <div className="h-full rounded-full bg-primary transition-[width] duration-500 motion-reduce:transition-none" style={{ width: `${Math.round(value * 100)}%` }} />
    </div>
  )
}
