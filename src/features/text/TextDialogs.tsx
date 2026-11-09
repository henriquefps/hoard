import { useTranslation } from "react-i18next"
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { formatFullDate } from "@/lib/format"

const CONTENT = "max-h-[calc(100dvh-2rem)] overflow-y-auto"
const FOOTER = "flex-col gap-2 sm:flex-row sm:flex-wrap sm:justify-end"

/**
 * Saving found a newer file on the server: keep theirs (reload), keep mine (overwrite), or keep
 * both (save mine as a copy next to it). Cancel goes back to editing with nothing lost.
 */
export function ConflictDialog({
  open,
  name,
  remoteModifiedAt,
  busy,
  onCancel,
  onReload,
  onOverwrite,
  onSaveCopy,
}: {
  open: boolean
  name: string
  remoteModifiedAt: string | null
  busy: boolean
  onCancel: () => void
  onReload: () => void
  onOverwrite: () => void
  onSaveCopy: () => void
}) {
  const { t, i18n } = useTranslation()
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && !busy && onCancel()}>
      <AlertDialogContent className={CONTENT}>
        <AlertDialogHeader>
          <AlertDialogTitle className="[overflow-wrap:anywhere]">
            {remoteModifiedAt
              ? t("text.conflictTitle", { name })
              : t("text.deletedTitle", { name })}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {remoteModifiedAt
              ? t("text.conflictBody", { date: formatFullDate(remoteModifiedAt, i18n.language) })
              : t("text.deletedBody")}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter className={FOOTER}>
          <AlertDialogCancel disabled={busy}>{t("common.cancel")}</AlertDialogCancel>
          {remoteModifiedAt && (
            <Button variant="outline" disabled={busy} onClick={onReload}>
              {t("text.reloadTheirs")}
            </Button>
          )}
          <Button variant="outline" disabled={busy} onClick={onSaveCopy}>
            {t("text.saveCopy")}
          </Button>
          <Button
            variant={remoteModifiedAt ? "destructive" : "default"}
            disabled={busy}
            onClick={onOverwrite}
          >
            {busy && <Spinner data-icon="inline-start" />}
            {remoteModifiedAt ? t("text.overwrite") : t("text.saveAgain")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** Unsaved changes when leaving edit mode or the screen: save, discard or keep editing. */
export function UnsavedDialog({
  open,
  name,
  busy,
  leaving = false,
  onCancel,
  onDiscard,
  onSave,
}: {
  open: boolean
  name: string
  busy: boolean
  /** Leaving the screen (navigation) rather than edit mode. */
  leaving?: boolean
  onCancel: () => void
  onDiscard: () => void
  onSave: () => void
}) {
  const { t } = useTranslation()
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && !busy && onCancel()}>
      <AlertDialogContent className={CONTENT}>
        <AlertDialogHeader>
          <AlertDialogTitle className="[overflow-wrap:anywhere]">
            {t("text.unsavedTitle", { name })}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {leaving ? t("text.unsavedLeaveBody") : t("text.unsavedBody")}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter className={FOOTER}>
          <AlertDialogCancel disabled={busy}>{t("text.keepEditing")}</AlertDialogCancel>
          <Button variant="destructive" disabled={busy} onClick={onDiscard}>
            {t("text.discard")}
          </Button>
          <Button disabled={busy} onClick={onSave}>
            {busy && <Spinner data-icon="inline-start" />}
            {t("text.save")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
