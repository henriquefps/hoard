import { useCallback } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { MAX_RECENT_DESTINATIONS, useSetting } from "@/features/settings/settings"
import type { TransferConflict, TransferOp, TransferSidecar } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import type { PickedFolder } from "./DestinationPicker"
import { openTransfers } from "./drawerState"
import { pasteProblem, type PickedEntry, rememberDestination, transferRequest } from "./places"
import type { TransferPlace } from "./service"
import { useTransferActions } from "./TransfersProvider"

/** Why the user is picking: the words differ, the engine is the same (ADR 0007). */
export type TransferIntent = "copy" | "move" | "download" | "upload" | "save"

export const opOf = (intent: TransferIntent): TransferOp => (intent === "move" ? "move" : "copy")

/**
 * Start a transfer from the UI (picker, clipboard, Save to…): records the row, starts the job,
 * remembers the destination as recent, and toasts with a "View" action. Throws after its own
 * error toast so callers can stay open.
 */
export function useStartTransfer() {
  const { t } = useTranslation()
  const actions = useTransferActions()
  const [recent, setRecent] = useSetting("recentDestinations")
  return useCallback(
    async (
      op: TransferOp,
      source: TransferPlace,
      entries: PickedEntry[],
      destination: PickedFolder,
      conflict: TransferConflict,
      /** XMP sidecars for copied files (Copy picks, ADR 0011). */
      sidecars?: TransferSidecar[],
      hiddenSidecars?: boolean
    ) => {
      try {
        await actions.start({
          ...transferRequest(op, source, entries, destination, conflict),
          ...(sidecars?.length ? { sidecars, ...(hiddenSidecars ? { hiddenSidecars } : {}) } : {}),
        })
      } catch (error) {
        toast.error(t("transfer.startFailed"), {
          description: t(`route.problem.${smbErrorCode(error)}`, t("route.problem.UNKNOWN")),
        })
        throw error
      }
      await setRecent(
        rememberDestination(
          recent ?? [],
          {
            locationId: destination.locationId,
            share: destination.share,
            path: destination.path,
            label: destination.label,
            treeUri: destination.treeUri,
          },
          MAX_RECENT_DESTINATIONS
        )
      ).catch(() => {})
      toast.success(
        t(op === "move" ? "transfer.startedMove" : "transfer.startedCopy", {
          count: entries.length,
          name: destination.label,
        }),
        { action: { label: t("transfers.view"), onClick: openTransfers } }
      )
    },
    [actions, recent, setRecent, t]
  )
}

/** Explains {@link pasteProblem} in words. */
export function useProblemText() {
  const { t } = useTranslation()
  return (problem: ReturnType<typeof pasteProblem>) =>
    problem === "intoItself"
      ? t("transfer.intoItself")
      : problem === "sameFolder"
        ? t("transfer.sameFolder")
        : null
}
