import { HardDrivesIcon, WarningIcon } from "@phosphor-icons/react"
import { useState, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Spinner } from "@/components/ui/spinner"
import { type Space, spaceVerdict } from "@/lib/compress"
import { formatBytes } from "@/lib/format"
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

/**
 * Whether the disk that takes the outputs has room (0.14.0): every replaced original stays in the trash until it is
 * emptied, so a job needs about the sum of its outputs free meanwhile. "Needs ~X free while the originals stay in the
 * trash; you have Y: compress in parts or empty the trash first."
 */
export function SpaceNote({ space, lang }: { space: Space | undefined; lang: string }) {
  const { t } = useTranslation()
  const v = spaceVerdict(space)
  if (!space || space.disks === 0) return null
  const where = t(`compress.space.where.${space.target === "nas" ? "nas" : "backup"}`)
  if (v.state === "unknown") {
    return (
      <p className="flex items-start gap-2 text-sm text-muted-foreground" data-testid="space-note" data-state="unknown">
        <WarningIcon className="mt-0.5 size-4 shrink-0 text-warning" />
        {t("compress.space.unknown", { where })}
      </p>
    )
  }
  const args = { needed: formatBytes(space.needed, lang), free: formatBytes(space.free, lang), trash: formatBytes(space.trash, lang), where }
  if (v.state === "fits") {
    return (
      <p className="flex items-start gap-2 text-sm text-muted-foreground" data-testid="space-note" data-state="fits">
        <HardDrivesIcon className="mt-0.5 size-4 shrink-0" />
        {t("compress.space.fits", args)}
      </p>
    )
  }
  return (
    <div className="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 p-3 text-sm" role="alert" data-testid="space-note" data-state="short">
      <WarningIcon className="mt-0.5 size-4 shrink-0 text-warning" />
      <div className="flex flex-col gap-1">
        <p className="font-medium">{t("compress.space.short", args)}</p>
        <p className="text-muted-foreground">{v.trashHelps ? t("compress.space.adviceTrash", args) : t("compress.space.adviceParts", args)}</p>
      </div>
    </div>
  )
}
