import {
  ArrowLeftIcon,
  CaretRightIcon,
  FolderIcon,
  FolderPlusIcon,
  FolderSimpleStarIcon,
  ClockCounterClockwiseIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { MachineIcon } from "@/components/MachineIcon"
import { NameDialog } from "@/components/NameDialog"
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
import { Skeleton } from "@/components/ui/skeleton"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import { getCullDestination, listFavorites, listMachines, setCullDestination } from "@/db/queries"
import { invalidateFolder, useFolder } from "@/features/browse/useFolder"
import { locationIdOfVolume } from "@/features/locations/ids"
import { useBrowseLocation } from "@/features/locations/useBrowseLocation"
import { VolumeIcon } from "@/features/locations/VolumeIcon"
import { useVolumes } from "@/features/locations/volumesContext"
import { useSetting } from "@/features/settings/settings"
import { formatBytes, formatNumber } from "@/lib/format"
import { isValidName } from "@/lib/files"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { type CopyDestination, copyPicks } from "./copyPicks"
import {
  type CopyFiles,
  type CopyPicksOptions,
  copyTargetPath,
  DEFAULT_COPY_OPTIONS,
  type Decisions,
  planCopy,
} from "./model"
import type { CullShot } from "@/plugins/smb/definitions"

/**
 * "Copy picks to…" (ADR 0008): where (any location; the last destination of this source is
 * remembered), which files of a pair, and the folder name. The copy itself goes through the
 * transfer engine's adapter (`copyPicks`); culling never copies on its own.
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
  const [defaultConflict] = useSetting("defaultConflict")
  const [destination, setDestination] = useState<CopyDestination | null>(null)
  const [options, setOptions] = useState<CopyPicksOptions>(DEFAULT_COPY_OPTIONS)
  const [browsing, setBrowsing] = useState(false)
  const [busy, setBusy] = useState(false)

  // The last destination and options used for this source.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    void getCullDestination(db, source).then((row) => {
      if (cancelled || !row) return
      setDestination({
        locationId: row.location_id,
        share: row.share,
        path: row.path,
        label: row.label ?? row.path,
      })
      try {
        const saved = JSON.parse(row.options) as Partial<CopyPicksOptions>
        setOptions({ ...DEFAULT_COPY_OPTIONS, ...saved })
      } catch {
        // Keep the defaults.
      }
    })
    return () => {
      cancelled = true
    }
  }, [open, db, source])

  const plan = useMemo(
    () => planCopy(order, decisions, options.files),
    [order, decisions, options.files]
  )
  const targetPath = destination ? copyTargetPath(destination.path, sourcePath, options) : ""
  const subfolderInvalid =
    options.folder === "new" && !isValidName((options.subfolder ?? "").trim())

  const start = async () => {
    if (!destination) return
    setBusy(true)
    try {
      await setCullDestination(db, source, { ...destination }, options)
      const outcome = await copyPicks(
        plan.items.map((i) => i.file),
        destination,
        { source, targetPath, createFolders: true, conflict: defaultConflict ?? "rename" }
      )
      if (outcome.status === "queued") {
        toast.success(t("cull.copy.queued", { count: outcome.count }))
        onOpenChange(false)
      } else {
        toast.info(t("cull.copy.unavailable"))
      }
    } catch (error) {
      toast.error(t(`errors.${smbErrorCode(error)}.title`))
    } finally {
      setBusy(false)
    }
  }

  return (
    <ResponsiveDialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setBrowsing(false)
        onOpenChange(next)
      }}
    >
      <ResponsiveDialogContent className="md:max-w-lg">
        {browsing ? (
          <DestinationBrowser
            initial={destination}
            onCancel={() => setBrowsing(false)}
            onChoose={(choice) => {
              setDestination(choice)
              setBrowsing(false)
            }}
          />
        ) : (
          <>
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
                  onClick={() => setBrowsing(true)}
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
                  onValueChange={(value) =>
                    setOptions((o) => ({ ...o, files: value as CopyFiles }))
                  }
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
                    {t(
                      options.files === "raw" ? "cull.copy.fallbackRaw" : "cull.copy.fallbackJpeg",
                      {
                        count: plan.fallbacks,
                      }
                    )}
                  </p>
                ) : null}
              </fieldset>

              <fieldset className="flex flex-col gap-2">
                <legend className="mb-2 text-sm font-medium">{t("cull.copy.folder")}</legend>
                <RadioGroup
                  value={options.folder}
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
                    onChange={(event) =>
                      setOptions((o) => ({ ...o, subfolder: event.target.value }))
                    }
                  />
                ) : null}
              </fieldset>
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
          </>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

interface Place {
  locationId: string
  share?: string
  path: string
}

/**
 * Picks a folder in any location: the phone, USB drives and SD cards, machines and their shares,
 * favorites. Interim until the transfer engine's destination sheet (phase 2a) replaces it.
 */
function DestinationBrowser({
  initial,
  onCancel,
  onChoose,
}: {
  initial: CopyDestination | null
  onCancel: () => void
  onChoose: (destination: CopyDestination) => void
}) {
  const { t } = useTranslation()
  const [place, setPlace] = useState<Place | null>(null)
  return (
    <>
      <ResponsiveDialogHeader>
        <ResponsiveDialogTitle>{t("cull.copy.destination")}</ResponsiveDialogTitle>
        <ResponsiveDialogDescription>{t("cull.copy.destinationHelp")}</ResponsiveDialogDescription>
      </ResponsiveDialogHeader>
      <ResponsiveDialogBody className="flex min-h-72 flex-col gap-1 px-0">
        {place ? (
          <FolderLevel place={place} onPlace={setPlace} onChoose={onChoose} />
        ) : (
          <Places initial={initial} onPlace={setPlace} />
        )}
      </ResponsiveDialogBody>
      <ResponsiveDialogFooter>
        <Button variant="outline" className="h-11 md:h-9" onClick={onCancel}>
          {t("common.back")}
        </Button>
      </ResponsiveDialogFooter>
    </>
  )
}

const rowClass =
  "flex min-h-14 w-full items-center gap-3 px-4 py-2 text-left transition-colors hover:bg-muted/50 md:px-6"

function Places({
  initial,
  onPlace,
}: {
  initial: CopyDestination | null
  onPlace: (p: Place) => void
}) {
  const { t } = useTranslation()
  const { volumes } = useVolumes()
  const favorites = useLiveQuery((db) => listFavorites(db), [])
  const machines = useLiveQuery((db) => listMachines(db), [])
  const readable = (volumes ?? []).filter((v) => v.state === "mounted")
  return (
    <div className="flex flex-col">
      {initial ? (
        <button
          type="button"
          className={rowClass}
          onClick={() =>
            onPlace({
              locationId: initial.locationId,
              share: initial.share || undefined,
              path: initial.path,
            })
          }
        >
          <ClockCounterClockwiseIcon className="size-5 shrink-0 text-muted-foreground" />
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="truncate text-[0.9375rem] font-medium">{initial.label}</span>
            <span className="text-[0.8125rem] text-muted-foreground">
              {t("cull.copy.lastUsed")}
            </span>
          </span>
        </button>
      ) : null}
      <p className="px-4 pt-3 pb-1 eyebrow text-muted-foreground md:px-6">
        {t("locations.thisPhone")}
      </p>
      {readable.map((volume) => (
        <button
          key={volume.uuid ?? "device"}
          type="button"
          className={rowClass}
          onClick={() => onPlace({ locationId: locationIdOfVolume(volume), path: "" })}
        >
          <VolumeIcon kind={volume.kind} className="size-5 shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium">
            {volume.primary ? t("locations.internal") : volume.label}
          </span>
          <CaretRightIcon className="size-4 text-muted-foreground" />
        </button>
      ))}
      {favorites && favorites.length > 0 ? (
        <p className="px-4 pt-3 pb-1 eyebrow text-muted-foreground md:px-6">{t("nav.favorites")}</p>
      ) : null}
      {favorites?.map((favorite) => (
        <button
          key={favorite.id}
          type="button"
          className={rowClass}
          onClick={() =>
            onPlace({
              locationId: favorite.location_id,
              share: favorite.share || undefined,
              path: favorite.path,
            })
          }
        >
          <FolderSimpleStarIcon className="size-5 shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium">
            {favorite.label}
          </span>
          <CaretRightIcon className="size-4 text-muted-foreground" />
        </button>
      ))}
      {machines && machines.length > 0 ? (
        <p className="px-4 pt-3 pb-1 eyebrow text-muted-foreground md:px-6">{t("nav.machines")}</p>
      ) : null}
      {machines?.map((machine) => (
        <button
          key={machine.id}
          type="button"
          className={rowClass}
          onClick={() => onPlace({ locationId: machine.id, path: "" })}
        >
          <MachineIcon
            icon={machine.icon}
            color={machine.color}
            className="size-8 [&_svg]:size-4"
          />
          <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium">
            {machine.name}
          </span>
          <CaretRightIcon className="size-4 text-muted-foreground" />
        </button>
      ))}
    </div>
  )
}

function FolderLevel({
  place,
  onPlace,
  onChoose,
}: {
  place: Place
  onPlace: (p: Place | null) => void
  onChoose: (destination: CopyDestination) => void
}) {
  const { t } = useTranslation()
  const location = useBrowseLocation(place.locationId, place.share)
  const folder = useFolder(location, place.path)
  const [naming, setNaming] = useState(false)
  const segments = place.path.split("/").filter(Boolean)
  const atShares = location?.kind === "smb" && !place.share
  const label = [location?.name, place.share, ...segments].filter(Boolean).join(" › ")
  const up = () => {
    if (segments.length > 0) onPlace({ ...place, path: segments.slice(0, -1).join("/") })
    else if (place.share) onPlace({ locationId: place.locationId, path: "" })
    else onPlace(null)
  }
  const folders =
    folder.data?.kind === "entries"
      ? folder.data.entries
          .filter((e) => e.isDirectory && !e.isHidden && !e.restricted)
          .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
      : []
  const shares = folder.data?.kind === "shares" ? folder.data.shares.filter((s) => !s.isHidden) : []
  const readOnly = folder.data?.kind === "entries" && folder.data.access === "read"

  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-2 border-b px-2 pb-2 md:px-4">
        <Button variant="ghost" size="icon" aria-label={t("common.back")} onClick={up}>
          <ArrowLeftIcon />
        </Button>
        <span className="min-w-0 flex-1 truncate text-sm font-medium [overflow-wrap:anywhere]">
          {label}
        </span>
        {!atShares ? (
          <Button
            variant="ghost"
            size="icon"
            aria-label={t("browse.newFolder")}
            disabled={readOnly || !location}
            onClick={() => setNaming(true)}
          >
            <FolderPlusIcon />
          </Button>
        ) : null}
      </div>
      {folder.loading && !folder.data ? (
        <div className="flex flex-col gap-2 p-4">
          <Skeleton className="h-10" />
          <Skeleton className="h-10" />
        </div>
      ) : folder.error ? (
        <p className="p-4 text-sm text-destructive">{t(`errors.${folder.error.code}.title`)}</p>
      ) : (
        <div className="flex flex-col">
          {atShares
            ? shares.map((share) => (
                <button
                  key={share.name}
                  type="button"
                  className={rowClass}
                  onClick={() => onPlace({ ...place, share: share.name, path: "" })}
                >
                  <FolderIcon className="size-5 shrink-0 text-muted-foreground" />
                  <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium">
                    {share.name}
                  </span>
                  <CaretRightIcon className="size-4 text-muted-foreground" />
                </button>
              ))
            : folders.map((entry) => (
                <button
                  key={entry.path}
                  type="button"
                  className={rowClass}
                  onClick={() => onPlace({ ...place, path: entry.path })}
                >
                  <FolderIcon className="size-5 shrink-0 text-muted-foreground" />
                  <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium">
                    {entry.name}
                  </span>
                  <CaretRightIcon className="size-4 text-muted-foreground" />
                </button>
              ))}
          {!atShares && folders.length === 0 ? (
            <p className="px-4 py-3 text-sm text-muted-foreground md:px-6">
              {t("cull.copy.noFolders")}
            </p>
          ) : null}
        </div>
      )}
      {!atShares && location ? (
        <div className={cn("sticky bottom-0 border-t bg-popover px-4 pt-3 md:px-6")}>
          {readOnly ? (
            <p className="mb-2 text-[0.8125rem] text-muted-foreground">{t("cull.copy.readOnly")}</p>
          ) : null}
          <Button
            className="h-11 w-full md:h-9"
            disabled={readOnly}
            onClick={() =>
              onChoose({
                locationId: place.locationId,
                share: place.share ?? "",
                path: place.path,
                label,
              })
            }
          >
            {t("cull.copy.useFolder")}
          </Button>
        </div>
      ) : null}
      <NameDialog
        open={naming}
        onOpenChange={setNaming}
        title={t("browse.newFolder")}
        label={t("browse.folderName")}
        initialValue={t("browse.newFolderDefault")}
        submitLabel={t("common.create")}
        validate={(value) =>
          !isValidName(value)
            ? t("browse.nameInvalid")
            : folders.some((f) => f.name.toLowerCase() === value.trim().toLowerCase())
              ? t("browse.nameTaken")
              : null
        }
        onSubmit={async (name) => {
          if (!location) return
          try {
            await location.run((loc) =>
              HoardSmb.createFolder({ location: loc, path: place.path, name })
            )
            invalidateFolder(location.cacheKey, place.path)
            void folder.refresh()
            onPlace({ ...place, path: place.path ? `${place.path}/${name}` : name })
          } catch (error) {
            toast.error(t(`errors.${smbErrorCode(error)}.title`))
            throw error
          }
        }}
      />
    </div>
  )
}
