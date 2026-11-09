import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import {
  ResponsiveDialog,
  ResponsiveDialogBody,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ResponsiveDialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldLabel,
  FieldTitle,
} from "@/components/ui/field"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import type { ConflictPolicy, TransferItem } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { dismissConflict, useDismissedConflicts } from "./conflictPrompt"
import { type TransferView, useTransferActions, useTransfers } from "./TransfersProvider"

const POLICIES: ConflictPolicy[] = ["rename", "overwrite", "skip"]

/**
 * The one question for `conflict: "ask"` (ADR 0007): a job that found existing names waits in state
 * `conflict`; this asks about the first name with "Do this for the other N" checked, so a conflict
 * storm (a re-copied camera card) is one tap. Mounted once in the app shell.
 */
export function TransferConflicts() {
  const transfers = useTransfers()
  const closed = useDismissedConflicts()
  const waiting = transfers?.find((x) => x.state === "conflict" && !closed.has(x.id))
  if (!waiting) return null
  return (
    <ConflictDialog
      key={waiting.id}
      transfer={waiting}
      onLater={() => dismissConflict(waiting.id)}
    />
  )
}

function ConflictDialog({ transfer, onLater }: { transfer: TransferView; onLater: () => void }) {
  const { t } = useTranslation()
  const actions = useTransferActions()
  const [item, setItem] = useState<TransferItem | null>()
  const [left, setLeft] = useState(0)
  const [policy, setPolicy] = useState<ConflictPolicy>("rename")
  const [all, setAll] = useState(true)
  const [busy, setBusy] = useState(false)
  const [round, setRound] = useState(0)

  useEffect(() => {
    let cancelled = false
    actions.items({ transferId: transfer.id, conflictsOnly: true, limit: 1 }).then(
      (result) => {
        if (cancelled) return
        setItem(result.items[0] ?? null)
        setLeft(result.total)
      },
      () => !cancelled && setItem(null)
    )
    return () => {
      cancelled = true
    }
  }, [actions, transfer.id, round, transfer.conflicts])

  const name = item?.destPath.split("/").at(-1) ?? transfer.name
  const folder = item?.destPath.split("/").slice(0, -1).join("/")

  async function answer() {
    if (!item) return
    setBusy(true)
    try {
      await actions.resolveConflicts(
        transfer.id,
        all || left <= 1 ? { all: policy } : { items: [{ index: item.index, policy }] }
      )
      if (!all && left > 1) setRound((r) => r + 1)
    } catch (error) {
      toast.error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")))
    } finally {
      setBusy(false)
    }
  }

  return (
    <ResponsiveDialog open onOpenChange={(open) => !open && onLater()}>
      <ResponsiveDialogContent className="sm:max-w-md">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>
            <span className="[overflow-wrap:anywhere]">
              {t("transfer.conflictDialog.title", { name })}
            </span>
          </ResponsiveDialogTitle>
          <ResponsiveDialogDescription className="[overflow-wrap:anywhere]">
            {folder
              ? t("transfer.conflictDialog.inFolder", { folder })
              : t("transfer.conflictDialog.inRoot")}
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>
        <ResponsiveDialogBody className="flex flex-col gap-4">
          {item === undefined ? (
            <Skeleton className="h-32" />
          ) : (
            <>
              <RadioGroup value={policy} onValueChange={(v) => setPolicy(v as ConflictPolicy)}>
                {POLICIES.map((option) => (
                  <FieldLabel key={option} htmlFor={`answer-${option}`}>
                    <Field orientation="horizontal">
                      <RadioGroupItem value={option} id={`answer-${option}`} />
                      <FieldContent>
                        <FieldTitle>{t(`conflict.${option}`)}</FieldTitle>
                        <FieldDescription>{t(`conflict.${option}Hint`)}</FieldDescription>
                      </FieldContent>
                    </Field>
                  </FieldLabel>
                ))}
              </RadioGroup>
              {left > 1 && (
                <Field orientation="horizontal">
                  <Checkbox
                    id="answer-all"
                    checked={all}
                    onCheckedChange={(v) => setAll(v === true)}
                  />
                  <FieldLabel htmlFor="answer-all" className="font-normal">
                    {t("transfer.conflictDialog.applyAll", { count: left - 1 })}
                  </FieldLabel>
                </Field>
              )}
            </>
          )}
        </ResponsiveDialogBody>
        <ResponsiveDialogFooter>
          <Button onClick={() => void answer()} disabled={!item || busy} className="md:order-last">
            {busy && <Spinner data-icon="inline-start" />}
            {t("transfer.conflictDialog.continue")}
          </Button>
          <Button variant="outline" onClick={onLater}>
            {t("transfer.conflictDialog.later")}
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
