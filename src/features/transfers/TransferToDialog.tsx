import { useTranslation } from "react-i18next"
import { useVolumes } from "@/features/locations/volumesContext"
import { formatBytes } from "@/lib/format"
import { DestinationPicker } from "./DestinationPicker"
import { type FolderPlace, pasteProblem, type PickedEntry } from "./places"
import type { TransferPlace } from "./service"
import { opOf, type TransferIntent, useProblemText, useStartTransfer } from "./useStartTransfer"

/** "Copy to…", "Move to…", "Download", "Upload": the destination picker for a selection. */
export function TransferToDialog({
  open,
  onOpenChange,
  intent,
  source,
  entries,
  onStarted,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  intent: Exclude<TransferIntent, "save">
  source: TransferPlace
  entries: PickedEntry[]
  onStarted?: () => void
}) {
  const { t, i18n } = useTranslation()
  const start = useStartTransfer()
  const problemText = useProblemText()
  const { volumes } = useVolumes()
  const op = opOf(intent)
  const size = entries.filter((e) => !e.isDirectory).reduce((s, e) => s + e.size, 0)
  const one = entries.length === 1 ? entries[0] : undefined
  const deviceReadable = volumes?.some((v) => v.primary && v.access === "path")
  return (
    <DestinationPicker
      open={open}
      onOpenChange={onOpenChange}
      title={t(`transfer.title.${intent}`, { count: entries.length })}
      description={
        one
          ? `${one.name}${one.isDirectory ? "" : ` · ${formatBytes(one.size, i18n.language)}`}`
          : t("transfer.summary", { count: entries.length, size: formatBytes(size, i18n.language) })
      }
      confirmLabel={t(op === "move" ? "transfer.moveHere" : "transfer.copyHere")}
      startAt={
        intent === "download" && deviceReadable
          ? { kind: "folder", locationId: "device", path: "Download", name: "Download" }
          : undefined
      }
      problem={(folder: FolderPlace) => problemText(pasteProblem(op, source, entries, folder))}
      onPick={async (folder, conflict) => {
        await start(op, source, entries, folder, conflict)
        onStarted?.()
      }}
    />
  )
}
