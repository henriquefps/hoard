import { FolderIcon } from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
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
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { useDb } from "@/db/DbProvider"
import { getCullDestination, setCullDestination } from "@/db/queries"
import { useSetting } from "@/features/settings/settings"
import { DestinationPicker } from "@/features/transfers/DestinationPicker"
import { TREE_LOCATION_ID } from "@/features/transfers/service"
import { formatBytes, formatNumber } from "@/lib/format"
import { isValidName } from "@/lib/files"
import type { CullShot, TransferConflict } from "@/plugins/smb/definitions"
import { type CopyDestination, useCopyPicks } from "./copyPicks"
import {
  type CopyFiles,
  type CopyPicksOptions,
  copyTargetPath,
  DEFAULT_COPY_OPTIONS,
  type Decisions,
  planCopy,
} from "./model"
import { sidecarsFor } from "./xmp"

/**
 * "Copy picks to…" (ADR 0008): where (any location, through the transfer engine's destination
 * picker; the last destination of this source is remembered), which files of a pair, and the
 * folder name; with "Save ratings next to photos (XMP)" on (Settings › Culling), each copied shot's sidecar carries its
 * stars and pick at the destination (ADR 0011). The copy is one batch job of the transfer engine (`useCopyPicks`): its progress,
 * conflict question and retries are the engine's.
 */
export function CopyPicksDialog({
  open,
  onOpenChange,
  source,
  sourcePath,
  order,
  decisions,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  source: { locationId: string; share: string }
  sourcePath: string
  order: CullShot[]
  decisions: Decisions
}) {
  const { t, i18n } = useTranslation()
  const db = useDb()
  const copyPicks = useCopyPicks()
  const [defaultConflict] = useSetting("defaultConflict")
  const xmp = useSetting("cullSaveXmp")[0] ?? true
  const hiddenXmp = useSetting("cullXmpHidden")[0] ?? false
  const [destination, setDestination] = useState<CopyDestination | null>(null)
  const [conflict, setConflict] = useState<TransferConflict | undefined>()
  const [options, setOptions] = useState<CopyPicksOptions>(DEFAULT_COPY_OPTIONS)
  const [picking, setPicking] = useState(false)
  const [busy, setBusy] = useState(false)

  // The last destination and options used for this source.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    void getCullDestination(db, source).then((row) => {
      if (cancelled || !row) return
      let saved: Partial<CopyPicksOptions & { treeUri: string; conflict: TransferConflict }> = {}
      try {
        saved = JSON.parse(row.options)
      } catch {
        // Keep the defaults.
      }
      setDestination({
        locationId: row.location_id,
        share: row.share,
        path: row.path,
        label: row.label ?? row.path,
        treeUri: saved.treeUri,
      })
      setConflict(saved.conflict)
      setOptions({
        ...DEFAULT_COPY_OPTIONS,
        ...(saved.files ? { files: saved.files } : {}),
        ...(saved.folder ? { folder: saved.folder } : {}),
        ...(saved.subfolder ? { subfolder: saved.subfolder } : {}),
      })
    })
    return () => {
      cancelled = true
    }
  }, [open, db, source])

  const plan = useMemo(
    () => planCopy(order, decisions, options.files),
    [order, decisions, options.files]
  )
  // A folder Android granted (tree) takes the files as they are: no subfolder is made in it.
  const isTree = destination?.locationId === TREE_LOCATION_ID
  const effective: CopyPicksOptions = isTree ? { ...options, folder: "none" } : options
  const targetPath = destination ? copyTargetPath(destination.path, sourcePath, effective) : ""
  const subfolder =
    effective.folder === "keep"
      ? sourcePath.split("/").filter(Boolean).at(-1)
      : effective.folder === "new"
        ? effective.subfolder?.trim()
        : undefined
  const subfolderInvalid =
    effective.folder === "new" && !isValidName((options.subfolder ?? "").trim())

  const start = async () => {
    if (!destination) return
    setBusy(true)
    try {
      const policy = conflict ?? defaultConflict ?? "ask"
      await setCullDestination(
        db,
        source,
        {
          locationId: destination.locationId,
          share: destination.share,
          path: destination.path,
          label: destination.label,
        },
        { ...options, treeUri: destination.treeUri, conflict: policy }
      )
      const sidecars = xmp ? sidecarsFor(plan.items, order, decisions) : undefined
      await copyPicks(
        plan.items.map((i) => i.file),
        destination,
        { source, subfolder, conflict: policy, sidecars, hiddenSidecars: xmp && hiddenXmp }
      )
      onOpenChange(false)
    } catch {
      // The transfer layer showed why; keep the dialog open.
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <ResponsiveDialog open={open && !picking} onOpenChange={onOpenChange}>
        <ResponsiveDialogContent className="md:max-w-lg">
          <ResponsiveDialogHeader>
            <ResponsiveDialogTitle>{t("cull.copy.title")}</ResponsiveDialogTitle>
            <ResponsiveDialogDescription>
              {t("cull.copy.summary", {
                shots: formatNumber(plan.shots, i18n.language),
                files: formatNumber(plan.items.length, i18n.language),
                size: formatBytes(plan.bytes, i18n.language),
                count: plan.items.length,
              })}
            </ResponsiveDialogDescription>
          </ResponsiveDialogHeader>
          <ResponsiveDialogBody className="flex flex-col gap-5">
            <div className="flex flex-col gap-2">
              <Label>{t("cull.copy.destination")}</Label>
              <button
                type="button"
                onClick={() => setPicking(true)}
                className="flex min-h-14 items-center gap-3 rounded-lg border px-3 py-2 text-left transition-colors hover:bg-muted/50"
              >
                <FolderIcon className="size-5 shrink-0 text-muted-foreground" />
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
                    {destination ? destination.label : t("cull.copy.chooseDestination")}
                  </span>
                  {destination ? (
                    <span className="truncate font-mono text-xs text-muted-foreground">
                      {targetPath ? `/${targetPath}` : "/"}
                    </span>
                  ) : null}
                </span>
                <span className="text-sm text-primary">{t("cull.copy.change")}</span>
              </button>
            </div>

            <fieldset className="flex flex-col gap-2">
              <legend className="mb-2 text-sm font-medium">{t("cull.copy.files")}</legend>
              <RadioGroup
                value={options.files}
                onValueChange={(value) => setOptions((o) => ({ ...o, files: value as CopyFiles }))}
              >
                {(["both", "raw", "jpeg"] as const).map((value) => (
                  <Label key={value} className="flex items-center gap-3 font-normal">
                    <RadioGroupItem value={value} />
                    {t(`cull.copy.files_${value}`)}
                  </Label>
                ))}
              </RadioGroup>
              {plan.fallbacks > 0 ? (
                <p className="text-[0.8125rem] text-muted-foreground">
                  {t(options.files === "raw" ? "cull.copy.fallbackRaw" : "cull.copy.fallbackJpeg", {
                    count: plan.fallbacks,
                  })}
                </p>
              ) : null}
            </fieldset>

            <fieldset className="flex flex-col gap-2">
              <legend className="mb-2 text-sm font-medium">{t("cull.copy.folder")}</legend>
              <RadioGroup
                value={effective.folder}
                disabled={isTree}
                onValueChange={(value) =>
                  setOptions((o) => ({ ...o, folder: value as CopyPicksOptions["folder"] }))
                }
              >
                {(["keep", "new", "none"] as const).map((value) => (
                  <Label key={value} className="flex items-center gap-3 font-normal">
                    <RadioGroupItem value={value} />
                    {value === "keep"
                      ? t("cull.copy.folder_keep", {
                          name: sourcePath.split("/").filter(Boolean).at(-1) ?? "/",
                        })
                      : t(`cull.copy.folder_${value}`)}
                  </Label>
                ))}
              </RadioGroup>
              {options.folder === "new" ? (
                <Input
                  aria-label={t("cull.copy.subfolderName")}
                  placeholder={t("cull.copy.subfolderName")}
                  value={options.subfolder ?? ""}
                  onChange={(event) => setOptions((o) => ({ ...o, subfolder: event.target.value }))}
                />
              ) : null}
            </fieldset>

            {xmp ? <p className="text-sm text-muted-foreground">{t("cull.copy.xmpNote")}</p> : null}
          </ResponsiveDialogBody>
          <ResponsiveDialogFooter>
            <Button
              className="h-11 md:h-9"
              disabled={!destination || plan.items.length === 0 || subfolderInvalid || busy}
              onClick={() => void start()}
            >
              {t("cull.copy.start", { count: plan.items.length })}
            </Button>
            <Button variant="outline" className="h-11 md:h-9" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
          </ResponsiveDialogFooter>
        </ResponsiveDialogContent>
      </ResponsiveDialog>
      <DestinationPicker
        open={open && picking}
        onOpenChange={(next) => !next && setPicking(false)}
        title={t("cull.copy.destination")}
        description={t("cull.copy.destinationHelp")}
        confirmLabel={t("cull.copy.useFolder")}
        startAt={
          destination && destination.locationId !== TREE_LOCATION_ID
            ? {
                kind: "folder",
                locationId: destination.locationId,
                share: destination.share || undefined,
                path: destination.path,
                name: destination.label,
              }
            : undefined
        }
        onPick={async (folder, policy) => {
          setDestination({
            locationId: folder.locationId,
            share: folder.share ?? "",
            path: folder.path,
            label: folder.label,
            treeUri: folder.treeUri,
          })
          setConflict(policy)
          setPicking(false)
        }}
      />
    </>
  )
}
