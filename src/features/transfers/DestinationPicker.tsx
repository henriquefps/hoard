import {
  CaretLeftIcon,
  CaretRightIcon,
  ClockCounterClockwiseIcon,
  FolderIcon,
  FolderOpenIcon,
  FolderPlusIcon,
  HardDriveIcon,
  StarIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useState, type ReactNode } from "react"
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
import { Field, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { useLiveQuery } from "@/db/DbProvider"
import { listFavorites, listMachines } from "@/db/queries"
import type { RecentDestination } from "@/db/types"
import { isReadOnly } from "@/features/browse/access"
import { ReadOnlyBadge } from "@/features/browse/ReadOnlyBadge"
import { invalidateFolder, useFolder } from "@/features/browse/useFolder"
import { locationIdOfVolume, parseLocationId } from "@/features/locations/ids"
import { useBrowseLocation } from "@/features/locations/useBrowseLocation"
import { VolumeIcon } from "@/features/locations/VolumeIcon"
import { useVolumes } from "@/features/locations/volumesContext"
import { CONFLICT_POLICIES, useSetting } from "@/features/settings/settings"
import { isValidName } from "@/lib/files"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { DestinationFolder, TransferConflict } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { type FolderPlace, liveDestinations } from "./places"
import { TREE_LOCATION_ID } from "./service"

/** A picked folder, with what to call it. */
export type PickedFolder = FolderPlace & { label: string }

type View =
  | { kind: "root" }
  | { kind: "folder"; locationId: string; share?: string; path: string; name: string }

/**
 * "Copy to…" / "Move to…" / "Save to…" (ADR 0007): browse any location — recent destinations, this
 * phone (internal storage, USB drives, SD cards), favorites, machines and their shares, or a folder
 * Android grants through its own picker — and pick a folder. Folders only; read-only ones cannot
 * be picked (the server's answer stays final when the transfer writes). "New folder" on the way.
 */
export function DestinationPicker({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  startAt,
  problem,
  onPick,
  extra,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  /** "Copy here", "Move here", "Save here". */
  confirmLabel: string
  /** Open in this folder (e.g. the phone's Download for a download), else the root list. */
  startAt?: Exclude<View, { kind: "root" }>
  /** Why the folder cannot take the selection (into itself, same folder), or null. */
  problem?: (folder: FolderPlace) => string | null
  onPick: (folder: PickedFolder, conflict: TransferConflict) => Promise<void>
  /** Shown above the footer (Save to… lists the files there). */
  extra?: ReactNode
}) {
  const { t } = useTranslation()
  const [defaultConflict] = useSetting("defaultConflict")
  const [view, setView] = useState<View>(startAt ?? { kind: "root" })
  const [conflict, setConflict] = useState<TransferConflict>()
  const [here, setHere] = useState<{ readOnly: boolean; known: boolean }>({
    readOnly: false,
    known: false,
  })
  const [busy, setBusy] = useState(false)
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setView(startAt ?? { kind: "root" })
      setConflict(undefined)
    }
  }
  const policy = conflict ?? defaultConflict ?? "ask"
  const folder: PickedFolder | null =
    view.kind === "folder" && (parseLocationId(view.locationId).kind !== "smb" || view.share)
      ? { locationId: view.locationId, share: view.share, path: view.path, label: view.name }
      : null
  const issue = folder ? (problem?.(folder) ?? null) : null

  async function pick(target: PickedFolder) {
    setBusy(true)
    try {
      await onPick(target, policy)
      onOpenChange(false)
    } catch {
      // The caller showed the error; keep the picker open to choose another folder.
    } finally {
      setBusy(false)
    }
  }

  return (
    <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveDialogContent className="sm:max-w-lg">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{title}</ResponsiveDialogTitle>
          {description && (
            <ResponsiveDialogDescription className="[overflow-wrap:anywhere]">
              {description}
            </ResponsiveDialogDescription>
          )}
        </ResponsiveDialogHeader>
        <ResponsiveDialogBody className="flex min-h-[min(24rem,50dvh)] flex-col gap-4 px-0 sm:px-0">
          {view.kind === "root" ? (
            <RootList
              onOpen={setView}
              onPickTree={(tree) =>
                void pick({
                  locationId: TREE_LOCATION_ID,
                  treeUri: tree.uri,
                  path: "",
                  label: tree.name,
                })
              }
            />
          ) : (
            <FolderList
              key={`${view.locationId}/${view.share ?? ""}`}
              view={view}
              onOpen={setView}
              onAccess={setHere}
            />
          )}
          {extra}
        </ResponsiveDialogBody>
        <ResponsiveDialogFooter>
          <div className="flex w-full flex-col gap-3 md:flex-row md:items-end">
            <Field className="min-w-0 md:max-w-56">
              <FieldLabel htmlFor="picker-conflict">{t("transfer.conflictLabel")}</FieldLabel>
              <Select value={policy} onValueChange={(v) => setConflict(v as TransferConflict)}>
                <SelectTrigger id="picker-conflict" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CONFLICT_POLICIES.map((option) => (
                    <SelectItem key={option} value={option}>
                      {t(`conflict.${option}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <div className="flex flex-col gap-2 md:ml-auto md:flex-row-reverse">
              <Button
                className="h-11 md:h-9"
                disabled={!folder || busy || here.readOnly || Boolean(issue)}
                onClick={() => folder && void pick(folder)}
              >
                {busy && <Spinner data-icon="inline-start" />}
                {confirmLabel}
              </Button>
              <Button variant="outline" className="h-11 md:h-9" onClick={() => onOpenChange(false)}>
                {t("common.cancel")}
              </Button>
            </div>
          </div>
          {(issue || (folder && here.readOnly)) && (
            <p className="text-[0.8125rem] text-muted-foreground" role="status">
              {issue ?? t("transfer.readOnlyHere")}
            </p>
          )}
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

// --- Root: recent, this phone, favorites, machines, Android folders ---------------------------------

function RootList({
  onOpen,
  onPickTree,
}: {
  onOpen: (view: View) => void
  onPickTree: (tree: DestinationFolder) => void
}) {
  const { t } = useTranslation()
  const { volumes } = useVolumes()
  const machines = useLiveQuery(listMachines)
  const favorites = useLiveQuery((db) => listFavorites(db))
  const [recent] = useSetting("recentDestinations")
  const [trees, setTrees] = useState<DestinationFolder[]>()
  const [picking, setPicking] = useState(false)
  useEffect(() => {
    let cancelled = false
    HoardSmb.listDestinationFolders().then(
      ({ folders }) => !cancelled && setTrees(folders),
      () => !cancelled && setTrees([])
    )
    return () => {
      cancelled = true
    }
  }, [])

  const machineName = (id: string) => machines?.find((m) => m.id === id)?.name
  const volumeName = (id: string) => {
    const parsed = parseLocationId(id)
    if (parsed.kind === "device") return t("locations.internal")
    if (parsed.kind === "volume") return volumes?.find((v) => v.uuid === parsed.uuid)?.label
    return machineName(parsed.machineId)
  }
  const known = (id: string) => {
    if (id === TREE_LOCATION_ID) return true
    const parsed = parseLocationId(id)
    if (parsed.kind === "device") return true
    if (parsed.kind === "volume") return Boolean(volumes?.some((v) => v.uuid === parsed.uuid))
    return Boolean(machines?.some((m) => m.id === parsed.machineId))
  }
  const recents = liveDestinations(recent ?? [], known)
  /** "Test NAS › media › Photos" without repeating the row's own label. */
  const recentMeta = (d: RecentDestination) => {
    const parts = [
      volumeName(d.locationId) ??
        (d.locationId === TREE_LOCATION_ID ? t("transfer.androidFolder") : undefined),
      d.share,
      ...d.path.split("/").filter(Boolean),
    ].filter((p): p is string => Boolean(p))
    if (parts.at(-1) === d.label) parts.pop()
    return parts.join(" › ") || undefined
  }
  const openRecent = (d: RecentDestination) =>
    d.locationId === TREE_LOCATION_ID && d.treeUri
      ? onPickTree({ uri: d.treeUri, name: d.label })
      : onOpen({
          kind: "folder",
          locationId: d.locationId,
          share: d.share,
          path: d.path,
          name: d.label,
        })

  async function systemPicker() {
    setPicking(true)
    try {
      onPickTree(await HoardSmb.pickDestinationFolder())
    } catch (error) {
      if (smbErrorCode(error) !== "CANCELLED") toast.error(t("errors.UNKNOWN.title"))
    } finally {
      setPicking(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      {recents.length > 0 && (
        <Section title={t("transfer.recent")}>
          {recents.map((d) => (
            <Row
              key={`${d.locationId}/${d.share ?? ""}/${d.path}/${d.treeUri ?? ""}`}
              icon={<ClockCounterClockwiseIcon />}
              label={d.label}
              meta={recentMeta(d)}
              onClick={() => openRecent(d)}
            />
          ))}
        </Section>
      )}
      <Section title={t("locations.thisPhone")}>
        {volumes === undefined ? (
          <Skeleton className="mx-4 h-14" />
        ) : (
          volumes
            .filter((v) => v.state === "mounted" || v.state === "mountedReadOnly")
            .map((v) => (
              <Row
                key={v.uuid ?? "device"}
                icon={<VolumeIcon kind={v.primary ? "internal" : v.kind} weight="duotone" />}
                label={v.primary ? t("locations.internal") : v.label}
                badge={
                  v.state === "mountedReadOnly" ? (
                    <ReadOnlyBadge title={t("access.driveReadOnly")} />
                  ) : undefined
                }
                onClick={() =>
                  onOpen({
                    kind: "folder",
                    locationId: locationIdOfVolume(v),
                    path: "",
                    name: v.primary ? t("locations.internal") : v.label,
                  })
                }
              />
            ))
        )}
      </Section>
      {favorites && favorites.length > 0 && (
        <Section title={t("nav.favorites")}>
          {favorites.map((f) => (
            <Row
              key={f.id}
              icon={<StarIcon weight="duotone" />}
              label={f.label}
              meta={[volumeName(f.location_id), f.share || undefined].filter(Boolean).join(" · ")}
              onClick={() =>
                onOpen({
                  kind: "folder",
                  locationId: f.location_id,
                  share: f.share || undefined,
                  path: f.path,
                  name: f.label,
                })
              }
            />
          ))}
        </Section>
      )}
      {machines && machines.length > 0 && (
        <Section title={t("nav.machines")}>
          {machines.map((m) => (
            <Row
              key={m.id}
              icon={<MachineIcon icon={m.icon} color={m.color} className="size-10" />}
              plainIcon
              label={m.name}
              onClick={() => onOpen({ kind: "folder", locationId: m.id, path: "", name: m.name })}
            />
          ))}
        </Section>
      )}
      <Section title={t("transfer.androidFolders")}>
        {trees?.map((tree) => (
          <Row
            key={tree.uri}
            icon={<FolderOpenIcon weight="duotone" />}
            label={tree.name}
            meta={t("transfer.androidFolder")}
            onClick={() => onPickTree(tree)}
            chevron={false}
          />
        ))}
        <div className="px-4 pt-1 md:px-6">
          <Button variant="outline" onClick={() => void systemPicker()} disabled={picking}>
            {picking ? (
              <Spinner data-icon="inline-start" />
            ) : (
              <FolderPlusIcon data-icon="inline-start" />
            )}
            {t("transfer.systemPicker")}
          </Button>
        </div>
      </Section>
    </div>
  )
}

// --- Inside a location: shares or subfolders ------------------------------------------------------

function FolderList({
  view,
  onOpen,
  onAccess,
}: {
  view: Extract<View, { kind: "folder" }>
  onOpen: (view: View) => void
  onAccess: (state: { readOnly: boolean; known: boolean }) => void
}) {
  const { t, i18n } = useTranslation()
  const [showHidden] = useSetting("showHiddenFiles")
  const isSmb = parseLocationId(view.locationId).kind === "smb"
  const location = useBrowseLocation(view.locationId, view.share)
  const folder = useFolder(location, view.path)
  const [naming, setNaming] = useState(false)
  const data = folder.data
  const access = data?.kind === "entries" ? data.access : undefined
  const readOnly = isReadOnly(access)
  useEffect(() => {
    onAccess({ readOnly, known: Boolean(data) })
  }, [readOnly, data, onAccess])

  const segments = view.path.split("/").filter(Boolean)
  const up = (): View => {
    if (segments.length > 0) {
      const path = segments.slice(0, -1).join("/")
      return {
        ...view,
        path,
        name:
          segments.at(-2) ??
          (isSmb ? (view.share ?? location?.name ?? "") : (location?.name ?? "")),
      }
    }
    if (isSmb && view.share)
      return { ...view, share: undefined, path: "", name: location?.name ?? "" }
    return { kind: "root" }
  }
  const trail = [location?.name, view.share, ...segments].filter(Boolean).join(" › ")

  const folders = useMemo(() => {
    if (data?.kind !== "entries") return undefined
    return data.entries
      .filter((e) => e.isDirectory && !e.restricted && (showHidden || !e.isHidden))
      .sort((a, b) => a.name.localeCompare(b.name, i18n.language, { numeric: true }))
  }, [data, showHidden, i18n.language])

  return (
    <div className="flex min-h-0 flex-col">
      <div className="flex min-h-11 items-center gap-1 border-b px-2 pb-2 md:px-4">
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("common.back")}
          onClick={() => onOpen(up())}
        >
          <CaretLeftIcon />
        </Button>
        <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium" title={trail}>
          {trail || view.name}
        </span>
        {readOnly && <ReadOnlyBadge title={t("transfer.readOnlyHere")} />}
        {(!isSmb || view.share) && (
          <Button
            variant="ghost"
            size="icon"
            aria-label={t("browse.newFolder")}
            title={t("browse.newFolder")}
            disabled={readOnly || !folders}
            onClick={() => setNaming(true)}
          >
            <FolderPlusIcon />
          </Button>
        )}
      </div>
      {folder.error && !data ? (
        <p className="px-4 py-6 text-[0.9375rem] text-muted-foreground md:px-6" role="alert">
          {t(`route.problem.${folder.error.code}`, t("route.problem.UNKNOWN"))}
        </p>
      ) : !data ? (
        <div className="flex flex-col gap-2 px-4 py-3 md:px-6">
          <Skeleton className="h-12" />
          <Skeleton className="h-12" />
          <Skeleton className="h-12" />
        </div>
      ) : data.kind === "shares" ? (
        <ul>
          {data.shares
            .filter((s) => showHidden || !s.isHidden)
            .map((s) => (
              <Row
                key={s.name}
                as="li"
                icon={<HardDriveIcon weight="duotone" />}
                label={s.name}
                meta={s.comment}
                badge={
                  isReadOnly(s.access) ? (
                    <ReadOnlyBadge title={t("transfer.readOnlyHere")} />
                  ) : undefined
                }
                onClick={() => onOpen({ ...view, share: s.name, path: "", name: s.name })}
              />
            ))}
        </ul>
      ) : folders && folders.length === 0 ? (
        <p className="px-4 py-6 text-[0.9375rem] text-muted-foreground md:px-6">
          {t("transfer.noSubfolders")}
        </p>
      ) : (
        <ul>
          {folders?.map((e) => (
            <Row
              key={e.path}
              as="li"
              icon={<FolderIcon weight="duotone" />}
              label={e.name}
              onClick={() => onOpen({ ...view, path: e.path, name: e.name })}
            />
          ))}
        </ul>
      )}
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
            : data?.kind === "entries" &&
                data.entries.some((e) => e.name.toLowerCase() === value.trim().toLowerCase())
              ? t("browse.nameTaken")
              : null
        }
        onSubmit={async (name) => {
          if (!location) return
          const clean = name.trim()
          try {
            await location.run((loc) =>
              HoardSmb.createFolder({ location: loc, path: view.path, name: clean })
            )
          } catch (error) {
            throw new Error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")), {
              cause: error,
            })
          }
          invalidateFolder(location.cacheKey, view.path)
          onOpen({ ...view, path: view.path ? `${view.path}/${clean}` : clean, name: clean })
        }}
      />
    </div>
  )
}

// --- Rows --------------------------------------------------------------------------------------

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h3 className="px-4 pb-1 eyebrow md:px-6">{title}</h3>
      <ul>{children}</ul>
    </section>
  )
}

function Row({
  icon,
  label,
  meta,
  badge,
  onClick,
  plainIcon = false,
  chevron = true,
  as = "li",
}: {
  icon: ReactNode
  label: string
  meta?: string
  badge?: ReactNode
  onClick: () => void
  plainIcon?: boolean
  chevron?: boolean
  as?: "li"
}) {
  const Tag = as
  return (
    <Tag>
      <button
        type="button"
        onClick={onClick}
        className="flex min-h-14 w-full items-center gap-3 px-4 py-2 text-left transition-colors outline-none hover:bg-muted/50 focus-visible:bg-muted/50 md:px-6"
      >
        <span
          aria-hidden
          className={cn(
            "inline-flex size-10 shrink-0 items-center justify-center rounded-lg [&_svg]:size-5",
            !plainIcon && "bg-primary/10 text-primary"
          )}
        >
          {icon}
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="flex min-w-0 items-center gap-2">
            <span className="line-clamp-2 min-w-0 text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
              {label}
            </span>
            {badge}
          </span>
          {meta && <span className="truncate text-[0.8125rem] text-muted-foreground">{meta}</span>}
        </span>
        {chevron && (
          <CaretRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        )}
      </button>
    </Tag>
  )
}
