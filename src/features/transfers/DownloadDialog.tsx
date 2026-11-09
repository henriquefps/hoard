import { FolderOpenIcon, FolderPlusIcon } from "@phosphor-icons/react"
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
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldLabel,
  FieldLegend,
  FieldSet,
  FieldTitle,
} from "@/components/ui/field"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import type { MachineWithAddresses } from "@/db/types"
import { CONFLICT_POLICIES, useSetting } from "@/features/settings/settings"
import { formatBytes } from "@/lib/format"
import { HoardSmb } from "@/plugins/smb"
import type { ConflictPolicy, DestinationFolder } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { openTransfers } from "./drawerState"
import type { DownloadFile } from "./service"
import { useTransferActions } from "./TransfersProvider"

/**
 * Download flow: pick a phone folder (persisted SAF trees, or "Choose folder…" for a new one; the
 * last one used is preselected), pick what happens on a name clash, then one transfer per file.
 */
export function DownloadDialog({
  open,
  onOpenChange,
  machine,
  files,
  skippedFolders = 0,
  onStarted,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  machine: MachineWithAddresses
  files: DownloadFile[]
  /** Folders in the selection (folder downloads come later). */
  skippedFolders?: number
  onStarted?: () => void
}) {
  const { t, i18n } = useTranslation()
  const actions = useTransferActions()
  const [lastDestination, setLastDestination] = useSetting("lastDestination")
  const [defaultConflict] = useSetting("defaultConflict")
  const [folders, setFolders] = useState<DestinationFolder[]>()
  const [destination, setDestination] = useState<string>()
  const [conflict, setConflict] = useState<ConflictPolicy>()
  const [picking, setPicking] = useState(false)
  const [busy, setBusy] = useState(false)

  const [wasOpen, setWasOpen] = useState(false)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setFolders(undefined)
      setConflict(undefined)
      setDestination(undefined)
    }
  }

  useEffect(() => {
    if (!open) return
    let cancelled = false
    HoardSmb.listDestinationFolders().then(
      ({ folders }) => !cancelled && setFolders(folders),
      () => !cancelled && setFolders([])
    )
    return () => {
      cancelled = true
    }
  }, [open])

  const chosen =
    destination && folders?.some((f) => f.uri === destination)
      ? destination
      : (folders?.find((f) => f.uri === lastDestination)?.uri ?? folders?.[0]?.uri)
  const policy = conflict ?? defaultConflict ?? "rename"
  const total = files.reduce((sum, file) => sum + file.size, 0)

  async function pick() {
    setPicking(true)
    try {
      const folder = await HoardSmb.pickDestinationFolder()
      setFolders((list) => [...(list ?? []).filter((f) => f.uri !== folder.uri), folder])
      setDestination(folder.uri)
    } catch (error) {
      if (smbErrorCode(error) !== "CANCELLED") toast.error(t("errors.UNKNOWN.title"))
    } finally {
      setPicking(false)
    }
  }

  async function start() {
    if (!chosen) return
    setBusy(true)
    try {
      await setLastDestination(chosen)
      const outcome = await actions.download(machine, files, chosen, policy)
      onOpenChange(false)
      onStarted?.()
      if (outcome.failed.length === 0) {
        toast.success(t("transfers.started", { count: outcome.started }), {
          action: { label: t("transfers.view"), onClick: openTransfers },
        })
      } else {
        toast.error(
          t("transfers.startFailed", {
            count: outcome.failed.length,
            reason: t(`route.problem.${outcome.failed[0].code}`, t("route.problem.UNKNOWN")),
          }),
          { action: { label: t("transfers.view"), onClick: openTransfers } }
        )
      }
    } catch (error) {
      toast.error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")))
    } finally {
      setBusy(false)
    }
  }

  const single = files.length === 1 ? files[0] : undefined
  return (
    <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveDialogContent className="sm:max-w-lg">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>
            {single ? t("download.titleOne") : t("download.title", { count: files.length })}
          </ResponsiveDialogTitle>
          <ResponsiveDialogDescription className="[overflow-wrap:anywhere]">
            {single
              ? `${single.name} · ${formatBytes(single.size, i18n.language)}`
              : t("download.summary", {
                  count: files.length,
                  size: formatBytes(total, i18n.language),
                })}
            {skippedFolders > 0
              ? ` ${t("download.foldersSkipped", { count: skippedFolders })}`
              : ""}
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>
        <ResponsiveDialogBody className="flex flex-col gap-6">
          <FieldSet>
            <FieldLegend variant="label">{t("download.destination")}</FieldLegend>
            {folders === undefined ? (
              <Skeleton className="h-16 w-full" />
            ) : folders.length === 0 ? (
              <p className="text-[0.9375rem] text-muted-foreground">{t("download.noFolders")}</p>
            ) : (
              <RadioGroup value={chosen} onValueChange={setDestination}>
                {folders.map((folder) => (
                  <FieldLabel key={folder.uri} htmlFor={`dest-${folder.uri}`}>
                    <Field orientation="horizontal">
                      <RadioGroupItem value={folder.uri} id={`dest-${folder.uri}`} />
                      <FolderOpenIcon
                        aria-hidden
                        weight="duotone"
                        className="size-5 shrink-0 text-primary"
                      />
                      <FieldContent className="min-w-0">
                        <FieldTitle className="w-full truncate">{folder.name}</FieldTitle>
                      </FieldContent>
                    </Field>
                  </FieldLabel>
                ))}
              </RadioGroup>
            )}
            <div>
              <Button type="button" variant="outline" onClick={pick} disabled={picking}>
                {picking ? (
                  <Spinner data-icon="inline-start" />
                ) : (
                  <FolderPlusIcon data-icon="inline-start" />
                )}
                {t("download.chooseFolder")}
              </Button>
            </div>
          </FieldSet>
          <FieldSet>
            <FieldLegend variant="label">{t("download.conflict")}</FieldLegend>
            <RadioGroup value={policy} onValueChange={(v) => setConflict(v as ConflictPolicy)}>
              {CONFLICT_POLICIES.map((option) => (
                <FieldLabel key={option} htmlFor={`conflict-${option}`}>
                  <Field orientation="horizontal">
                    <RadioGroupItem value={option} id={`conflict-${option}`} />
                    <FieldContent>
                      <FieldTitle>{t(`conflict.${option}`)}</FieldTitle>
                      <FieldDescription>{t(`conflict.${option}Hint`)}</FieldDescription>
                    </FieldContent>
                  </Field>
                </FieldLabel>
              ))}
            </RadioGroup>
          </FieldSet>
        </ResponsiveDialogBody>
        <ResponsiveDialogFooter>
          <Button
            onClick={start}
            disabled={!chosen || busy || files.length === 0}
            className="md:order-last"
          >
            {busy && <Spinner data-icon="inline-start" />}
            {t("download.start")}
          </Button>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("common.cancel")}
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
