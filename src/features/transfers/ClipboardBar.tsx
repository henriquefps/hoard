import { ClipboardTextIcon, CopyIcon, ScissorsIcon, XIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { useSetting } from "@/features/settings/settings"
import { clipboard, useClipboard, usePasteTarget } from "./clipboard"
import { pasteProblem } from "./places"
import { useProblemText, useStartTransfer } from "./useStartTransfer"

/**
 * The clipboard bar (ADR 0007): what "Copy" / "Move" put aside, and "Paste here" for the folder the
 * user is looking at (any location). Sits at the bottom of the content surface on every screen
 * while something is on the clipboard; "Paste here" is off where it cannot work (no folder open, a
 * read-only folder, a folder into itself, a move into its own folder).
 */
export function ClipboardBar() {
  const { t } = useTranslation()
  const content = useClipboard()
  const target = usePasteTarget()
  const start = useStartTransfer()
  const problemText = useProblemText()
  const [defaultConflict] = useSetting("defaultConflict")
  const [busy, setBusy] = useState(false)
  if (!content) return null

  const problem = target
    ? problemText(pasteProblem(content.op, content.source, content.entries, target))
    : null
  const reason = !target ? t("transfer.clipboard.openFolder") : (target.readOnlyReason ?? problem)
  const Icon = content.op === "move" ? ScissorsIcon : CopyIcon

  async function paste() {
    if (!content || !target) return
    setBusy(true)
    try {
      await start(content.op, content.source, content.entries, target, defaultConflict ?? "ask")
      // A move cannot be pasted twice; a copy can (into another folder).
      if (content.op === "move") clipboard.clear()
    } catch {
      // Toasted by useStartTransfer.
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      role="region"
      aria-label={t("transfer.clipboard.label")}
      className="sticky bottom-0 z-10 flex shrink-0 items-center gap-2 border-t bg-card px-4 py-2 md:px-6"
    >
      <span
        aria-hidden
        className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5"
      >
        <Icon weight="duotone" />
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-[0.9375rem] font-medium" aria-live="polite">
          {t(`transfer.clipboard.${content.op}`, { count: content.entries.length })}
        </span>
        <span
          className="truncate text-[0.8125rem] text-muted-foreground"
          title={reason ?? undefined}
        >
          {reason ?? t("transfer.clipboard.from", { name: content.sourceLabel })}
        </span>
      </span>
      <Button onClick={() => void paste()} disabled={!target || Boolean(reason) || busy}>
        {busy ? (
          <Spinner data-icon="inline-start" />
        ) : (
          <ClipboardTextIcon data-icon="inline-start" />
        )}
        {t("transfer.paste")}
      </Button>
      <Button
        variant="ghost"
        size="icon"
        aria-label={t("transfer.clipboard.clear")}
        title={t("transfer.clipboard.clear")}
        onClick={() => clipboard.clear()}
      >
        <XIcon />
      </Button>
    </div>
  )
}
