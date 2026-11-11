import {
  ApertureIcon,
  EjectIcon,
  GearSixIcon,
  CheckSquareIcon,
  CopyIcon,
  DotsThreeVerticalIcon,
  ArrowSquareOutIcon,
  ArrowsLeftRightIcon,
  DownloadSimpleIcon,
  FolderSimpleIcon,
  ScissorsIcon,
  ShareNetworkIcon,
  SlidersHorizontalIcon,
  UploadSimpleIcon,
  EyeIcon,
  FilePlusIcon,
  FileTextIcon,
  FolderPlusIcon,
  HardDriveIcon,
  PencilSimpleIcon,
  PlusIcon,
  StarIcon,
  TrashIcon,
  XIcon,
} from "@phosphor-icons/react"
import { Link, useNavigate, useParams, useRouter, useSearch } from "@tanstack/react-router"
import { useVirtualizer } from "@tanstack/react-virtual"
import { useEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { NameDialog } from "@/components/NameDialog"
import { ActionLabel, CompactSearch, Page, PageScroll } from "@/components/page/Page"
import { useScrollMargin } from "@/components/page/useScrollMargin"
import { browseTrail } from "@/components/page/breadcrumbs"
import { SmbErrorState } from "@/components/SmbErrorState"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import {
  addFavorite,
  addManualShare,
  findFavorite,
  listManualShares,
  removeFavorite,
  removeManualShare,
} from "@/db/queries"
import type { MachineWithAddresses } from "@/db/types"
import { LocationErrorState } from "@/features/locations/LocationErrorState"
import { LocationStatus } from "@/features/locations/LocationStatus"
import { type BrowseLocation, useBrowseLocation } from "@/features/locations/useBrowseLocation"
import { useLeaveWhenUnplugged } from "@/features/locations/useLeaveWhenUnplugged"
import { parseLocationId } from "@/features/locations/ids"
import { BrowserMenuItems, BrowserToolbar, SortHeader } from "@/features/browse/BrowserToolbar"
import { hasPhotos, selectionPaths } from "@/features/reveal/handoff"
import { useReveal } from "@/features/reveal/revealContext"
import { accountName, isReadOnly, readOnlyReasonKey } from "@/features/browse/access"
import { FileRow } from "@/features/browse/FileRow"
import { ReadOnlyBadge, ReadOnlyMenuNote } from "@/features/browse/ReadOnlyBadge"
import { invalidateFolder, useFolder } from "@/features/browse/useFolder"
import { useMachineRoute, useRouteEngine } from "@/features/route/RouteProvider"
import { RouteStatus } from "@/features/route/RouteStatus"
import { useSetting } from "@/features/settings/settings"
import { clipboard, pasteTarget } from "@/features/transfers/clipboard"
import type { PickedEntry } from "@/features/transfers/places"
import { TransferToDialog } from "@/features/transfers/TransferToDialog"
import { useFolderTransferSignal } from "@/features/transfers/TransfersProvider"
import type { TransferIntent } from "@/features/transfers/useStartTransfer"
import { useBackHandler } from "@/hooks/useAndroidBackButton"
import {
  DEFAULT_SORT,
  type FileFilter,
  isValidName,
  openStrategy,
  parentPath,
  type SortOrder,
  splitPath,
  uncPath,
  viewEntries,
} from "@/lib/files"
import { formatBytes, formatNumber } from "@/lib/format"
import { goUp } from "@/lib/historyTrail"
import { SCROLL_IDS, registerScrollAnchors } from "@/lib/scrollRestoration"
import { CULL_MIN_PHOTOS, countPhotos } from "@/lib/photos"
import { isTextFile, opensInViewer } from "@/lib/text/textFiles"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { SmbAccess, SmbEntry, SmbShare } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"

// The sort the user picked lasts for the session, across folders (the default is a setting).
let sessionSort: SortOrder | null = null

/**
 * `/browse/$locationId?share=&path=`: any location (ADR 0005). A machine shows its shares, then
 * folders and files; the phone's storage (`device`) and USB drives / SD cards (`vol-<uuid>`) show
 * their folders from the root.
 */
export function BrowsePage() {
  const { t } = useTranslation()
  const { locationId } = useParams({ from: "/browse/$locationId" })
  const { share, path = "" } = useSearch({ from: "/browse/$locationId" })
  const isSmb = parseLocationId(locationId).kind === "smb"
  const location = useBrowseLocation(locationId, isSmb ? share : undefined)
  const machine = location?.machine
  const route = useMachineRoute(machine)
  useLeaveWhenUnplugged(location)
  const segments = splitPath(path)
  const title = segments.at(-1) ?? (isSmb ? share : undefined) ?? location?.name ?? ""
  const showsFolder = !isSmb || Boolean(share)
  // The open folder's access, reported by FolderView once listed (keyed so a stale answer from
  // the previous folder never shows on the next one).
  const folderKey = showsFolder ? `${isSmb ? share : ""}/${path}` : ""
  const [access, setAccess] = useState<{ key: string; access?: SmbAccess; photos?: number }>({
    key: "",
  })
  const photos = showsFolder && access.key === folderKey ? (access.photos ?? 0) : 0
  const readOnly = showsFolder && access.key === folderKey && isReadOnly(access.access)
  const readOnlyReason = useReadOnlyReason(location, path)
  const trailLocation = {
    id: locationId,
    kind: location?.kind ?? parseLocationId(locationId).kind,
    name: location?.name,
  }

  if (location === null) {
    return (
      <Page
        title={t("machines.notFound")}
        breadcrumbs={browseTrail(t, { ...trailLocation, name: "?" })}
      >
        <SmbErrorState code="NOT_FOUND" machineId={locationId} />
      </Page>
    )
  }

  const status = isSmb ? (
    <RouteStatus state={route} className="max-w-full" />
  ) : (
    <LocationStatus location={location} />
  )
  return (
    <Page
      fill
      title={title}
      breadcrumbs={browseTrail(t, trailLocation, isSmb ? share : undefined, path)}
      meta={
        <>
          {status}
          {readOnly && <ReadOnlyBadge title={readOnlyReason} />}
        </>
      }
      actions={location && <HeaderActions location={location} path={path} photos={photos} />}
      menu={location && <HeaderMenu location={location} />}
    >
      {location === undefined ? (
        <ListSkeleton className="-mt-4 md:-mt-6" />
      ) : showsFolder ? (
        // Keyed by folder: search, filter and selection start fresh in every folder.
        <FolderView
          key={`${location.cacheKey}/${path}`}
          location={location}
          path={path}
          onAccess={(value, count) => setAccess({ key: folderKey, access: value, photos: count })}
        />
      ) : (
        <SharesView machine={machine!} />
      )}
    </Page>
  )
}

/**
 * Why writing is off. SMB: "This folder is read-only for henrique." (the share root says
 * "share"). Local storage: the drive is mounted read-only, or Android refuses writes there.
 */
function useReadOnlyReason(
  location: Pick<BrowseLocation, "kind" | "machine"> | null | undefined,
  path: string
) {
  const { t } = useTranslation()
  if (location && location.kind !== "smb") {
    return t(path === "" ? "access.driveReadOnly" : "access.localFolderReadOnly")
  }
  const machine = location?.machine
  const user = accountName(machine?.username, machine?.domain)
  return t(readOnlyReasonKey(path, user !== null), { user })
}

function HeaderActions({
  location,
  path,
  photos,
}: {
  location: BrowseLocation
  path: string
  /** Photo files in the folder: from CULL_MIN_PHOTOS up, "Cull" opens the culling mode. */
  photos: number
}) {
  const { t } = useTranslation()
  const db = useDb()
  const { machine, share } = location
  const isSmb = location.kind === "smb"
  const canPin = !isSmb || Boolean(share)
  const favorite = useLiveQuery(
    (d) => (canPin ? findFavorite(d, location.id, share ?? "", path) : Promise.resolve(null)),
    [location.id, share, path, canPin]
  )
  const label = splitPath(path).at(-1) ?? share ?? location.name ?? ""
  return (
    <>
      {photos >= CULL_MIN_PHOTOS && (
        <Button asChild variant="outline">
          <Link
            to="/cull/$locationId"
            params={{ locationId: location.id }}
            search={{ share, path: path || undefined }}
            aria-label={t("cull.open")}
            title={t("cull.open")}
          >
            <ApertureIcon data-icon="inline-start" />
            <ActionLabel className="max-sm:sr-only">{t("cull.open")}</ActionLabel>
          </Link>
        </Button>
      )}
      {canPin && (
        <Button
          variant="ghost"
          size="icon"
          aria-pressed={Boolean(favorite)}
          aria-label={favorite ? t("favorites.remove") : t("favorites.add")}
          title={favorite ? t("favorites.remove") : t("favorites.add")}
          onClick={async () => {
            if (favorite) {
              await removeFavorite(db, favorite.id)
              toast(t("favorites.removed", { name: favorite.label }))
            } else {
              await addFavorite(db, {
                locationId: location.id,
                machineId: machine?.id ?? null,
                share: share ?? "",
                path,
                label,
              })
              toast.success(t("favorites.added", { name: label }))
            }
          }}
        >
          <StarIcon
            weight={favorite ? "fill" : "regular"}
            className={cn(favorite && "text-primary")}
          />
        </Button>
      )}
    </>
  )
}

/** The page's ⋯ items: hidden files, then the machine's or the storage's settings. */
function HeaderMenu({ location }: { location: BrowseLocation }) {
  const { t } = useTranslation()
  const { machine } = location
  const [showHidden, setShowHidden] = useSetting("showHiddenFiles")
  return (
    <>
      <DropdownMenuCheckboxItem
        checked={showHidden ?? false}
        onCheckedChange={(checked) => void setShowHidden(checked)}
      >
        <EyeIcon />
        {t("browse.showHidden")}
      </DropdownMenuCheckboxItem>
      <DropdownMenuSeparator />
      {machine ? (
        <DropdownMenuItem asChild>
          <Link to="/machines/$machineId/edit" params={{ machineId: machine.id }}>
            <PencilSimpleIcon />
            {t("machines.edit")}
          </Link>
        </DropdownMenuItem>
      ) : (
        <DropdownMenuItem
          onSelect={() =>
            void HoardSmb.openStorageSettings(
              location.volume?.uuid ? { uuid: location.volume.uuid } : undefined
            ).catch(() => toast.error(t("errors.UNKNOWN.title")))
          }
        >
          {location.kind === "volume" ? <EjectIcon /> : <GearSixIcon />}
          {location.kind === "volume"
            ? t("locations.ejectInSettings")
            : t("locations.storageSettings")}
        </DropdownMenuItem>
      )}
    </>
  )
}

// --- Shares ------------------------------------------------------------------------------------

function SharesView({ machine }: { machine: MachineWithAddresses }) {
  const { t } = useTranslation()
  const db = useDb()
  const navigate = useNavigate()
  const location = useBrowseLocation(machine.id)
  const folder = useFolder(location, "")
  const engine = useRouteEngine()
  const [showHidden] = useSetting("showHiddenFiles")
  const [askName, setAskName] = useState(false)
  const manual = useLiveQuery((d) => listManualShares(d, machine.id), [machine.id])
  const listed = folder.data?.kind === "shares" ? folder.data.shares : undefined
  // Shares added by name show up next to the listed ones until the server lists them itself.
  const shares = listed
    ? [
        ...listed.map((s) => ({ ...s, manual: false })),
        ...(manual ?? [])
          .filter((m) => !listed.some((s) => sameShare(s.name, m.name)))
          .map((m) => ({ name: m.name, isHidden: false, manual: true })),
      ]
        .filter((s) => showHidden || !s.isHidden)
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
    : undefined

  const openByName = (
    <>
      <Button variant="outline" onClick={() => setAskName(true)}>
        <PlusIcon />
        {t("browse.openShareByName")}
      </Button>
      <NameDialog
        open={askName}
        onOpenChange={setAskName}
        title={t("browse.openShareByNameTitle")}
        description={t("browse.openShareByNameBody")}
        label={t("browse.shareName")}
        initialValue=""
        submitLabel={t("browse.open")}
        validate={(value) => (isValidShareName(value) ? null : t("browse.shareNameInvalid"))}
        onSubmit={async (value) => {
          const name = value.trim()
          try {
            // Opening the root proves the share exists and this account may use it.
            await engine.withSession(machine, (sessionId) =>
              HoardSmb.list({ location: { kind: "smb", sessionId, share: name }, path: "" })
            )
          } catch (error) {
            const code = smbErrorCode(error)
            throw new Error(
              code === "NOT_FOUND"
                ? t("browse.shareNotFound")
                : t(`errors.${code}.title`, t("errors.UNKNOWN.title")),
              { cause: error }
            )
          }
          if (!listed?.some((s) => sameShare(s.name, name))) {
            await addManualShare(db, machine.id, name)
          }
          void navigate({
            to: "/browse/$locationId",
            params: { locationId: machine.id },
            search: { share: name },
          })
        }}
      />
    </>
  )

  if (folder.error && !shares) {
    return (
      <SmbErrorState
        code={folder.error.code}
        machineId={machine.id}
        attempts={folder.error.attempts}
        retrying={folder.loading}
        onRetry={() => {
          engine.forget(machine.id)
          void folder.refresh()
        }}
      />
    )
  }
  if (!shares) return <ListSkeleton className="-mt-4 md:-mt-6" />
  if (shares.length === 0) {
    return (
      <Empty className="border">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <HardDriveIcon />
          </EmptyMedia>
          <EmptyTitle>{t("browse.noSharesTitle")}</EmptyTitle>
          <EmptyDescription>{t("browse.noSharesBody")}</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>{openByName}</EmptyContent>
      </Empty>
    )
  }
  return (
    <PageScroll
      scrollId={SCROLL_IDS.browseShares}
      className="-mx-4 -mt-4 -mb-3 md:-mx-6 md:-mt-6 md:-mb-4"
    >
      <h2 className="px-4 pt-4 pb-2 eyebrow md:px-6">{t("browse.shares")}</h2>
      <ul className="border-t">
        {shares.map((s) => (
          <ShareRow key={s.name} machine={machine} share={s} manual={s.manual} />
        ))}
      </ul>
      <div className="px-4 py-4 md:px-6">{openByName}</div>
    </PageScroll>
  )
}

/** Share names compare case-insensitively (SMB servers treat them so). */
const sameShare = (a: string, b: string) =>
  a.localeCompare(b, undefined, { sensitivity: "accent" }) === 0

const isValidShareName = (value: string) => {
  const name = value.trim()
  return (
    name.length > 0 &&
    name.length <= 80 &&
    ![...name].some((c) => c === "\\" || c === "/" || c.charCodeAt(0) < 32)
  )
}

function ShareRow({
  machine,
  share,
  manual,
}: {
  machine: MachineWithAddresses
  share: SmbShare
  manual: boolean
}) {
  const { t } = useTranslation()
  const db = useDb()
  const engine = useRouteEngine()
  const subtitle = share.comment ?? (manual ? t("browse.addedByName") : undefined)
  const readOnly = isReadOnly(share.access)
  const reason = useReadOnlyReason({ kind: "smb", machine }, "")
  return (
    <li className="relative flex min-h-16 items-center gap-3 border-b px-4 py-2 transition-colors hover:bg-muted/50 md:px-6">
      <span
        aria-hidden
        className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5"
      >
        <HardDriveIcon weight="duotone" />
      </span>
      <Link
        to="/browse/$locationId"
        params={{ locationId: machine.id }}
        search={{ share: share.name }}
        className="flex min-w-0 flex-1 flex-col gap-0.5 outline-none after:absolute after:inset-0 focus-visible:after:bg-muted/50"
      >
        <span className="flex min-w-0 items-start gap-2">
          <span className="line-clamp-2 min-w-0 text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
            {share.name}
          </span>
          {readOnly && <ReadOnlyBadge title={reason} className="mt-0.5" />}
        </span>
        {subtitle && (
          <span className="truncate text-[0.8125rem] text-muted-foreground" title={subtitle}>
            {subtitle}
          </span>
        )}
      </Link>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="relative z-10"
            aria-label={t("browse.actionsFor", { name: share.name })}
          >
            <DotsThreeVerticalIcon weight="bold" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            onSelect={async () => {
              await addFavorite(db, {
                locationId: machine.id,
                machineId: machine.id,
                share: share.name,
                path: "",
                label: share.name,
              })
              toast.success(t("favorites.added", { name: share.name }))
            }}
          >
            <StarIcon />
            {t("favorites.add")}
          </DropdownMenuItem>
          <DropdownMenuItem
            onSelect={() =>
              copyText(
                uncPath(
                  engine.activeRoute(machine.id)?.host ?? machine.addresses[0]?.host ?? "",
                  share.name,
                  ""
                ),
                t
              )
            }
          >
            <CopyIcon />
            {t("browse.copyPath")}
          </DropdownMenuItem>
          {manual && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                variant="destructive"
                onSelect={async () => {
                  await removeManualShare(db, machine.id, share.name)
                  toast.success(t("browse.shareRemoved", { name: share.name }))
                }}
              >
                <TrashIcon />
                {t("browse.removeShare")}
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  )
}

// --- Folder ------------------------------------------------------------------------------------

type Dialog =
  | { kind: "newFolder" }
  | { kind: "newTextFile" }
  | { kind: "rename"; entry: SmbEntry }
  | { kind: "delete"; entries: SmbEntry[] }
  | { kind: "transferTo"; intent: Exclude<TransferIntent, "save">; entries: PickedEntry[] }
  | null

function FolderView({
  location,
  path,
  onAccess,
}: {
  location: BrowseLocation
  path: string
  /**
   * Called with the folder's access and its number of photo files whenever a listing arrives (the
   * header's read-only badge and its "Cull" action).
   */
  onAccess: (access: SmbAccess | undefined, photos: number) => void
}) {
  const { t, i18n } = useTranslation()
  const db = useDb()
  const router = useRouter()
  const navigate = useNavigate()
  const engine = useRouteEngine()
  const { machine, share } = location
  const isSmb = location.kind === "smb"
  const folder = useFolder(location, path)
  // Write actions stay visible but disabled where the server said this account may only read; a
  // write the server refuses anyway (access changed) still shows its error.
  const access = folder.data?.kind === "entries" ? folder.data.access : undefined
  const readOnly = isReadOnly(access)
  const readOnlyReason = useReadOnlyReason(location, path)
  const onAccessRef = useRef(onAccess)
  useEffect(() => {
    onAccessRef.current = onAccess
  })
  useEffect(() => {
    if (folder.data)
      onAccessRef.current(
        access,
        folder.data.kind === "entries" ? countPhotos(folder.data.entries) : 0
      )
  }, [folder.data, access])
  const [showHidden] = useSetting("showHiddenFiles")
  const [defaultSort] = useSetting("defaultSort")
  const [sort, setSortState] = useState<SortOrder | null>(sessionSort)
  const [filter, setFilter] = useState<FileFilter>("all")
  const [query, setQuery] = useState("")
  const [selected, setSelected] = useState<Set<string>>(() => new Set())
  const [selectionMode, setSelectionMode] = useState(false)
  const [opening, setOpening] = useState<string | null>(null)
  const [dialog, setDialog] = useState<Dialog>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const locale = i18n.language
  const effectiveSort = sort ?? defaultSort ?? DEFAULT_SORT
  const setSort = (next: SortOrder) => {
    sessionSort = next
    setSortState(next)
  }

  const entries = useMemo(
    () => (folder.data?.kind === "entries" ? folder.data.entries : undefined),
    [folder.data]
  )
  const visible = useMemo(
    () =>
      entries
        ? viewEntries(
            entries,
            { filter, query, showHidden: showHidden ?? false, sort: effectiveSort },
            locale
          )
        : [],
    [entries, filter, query, showHidden, effectiveSort, locale]
  )
  const selectedEntries = visible.filter((e) => selected.has(e.path))
  const inSelection = selectionMode || selected.size > 0
  const reveal = useReveal()

  // The list sits under the page header and the table header, inside the same scroller.
  const scrollMargin = useScrollMargin(listRef, visible.length > 0)
  // eslint-disable-next-line react-hooks/incompatible-library -- the virtualizer is read during render on purpose
  const virtualizer = useVirtualizer({
    count: visible.length,
    scrollMargin,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 64,
    overscan: 10,
    getItemKey: (index) => visible[index]?.path ?? index,
  })

  // Restore by file, not only by pixels: the list may have changed since (lib/scrollRestoration.ts).
  // The adapter reads the latest list and virtualizer through a ref.
  const anchorList = useRef({ visible, virtualizer })
  useEffect(() => {
    anchorList.current = { visible, virtualizer }
  })
  useEffect(
    () =>
      registerScrollAnchors(SCROLL_IDS.browseList, {
        capture(element) {
          const { visible: rows, virtualizer: v } = anchorList.current
          const top = element.scrollTop
          const first = v.getVirtualItems().find((item) => item.end > top)
          const entry = first && rows[first.index]
          return entry ? { id: entry.path, offset: top - first.start } : undefined
        },
        resolve(_element, anchor) {
          const { visible: rows, virtualizer: v } = anchorList.current
          const index = rows.findIndex((e) => e.path === anchor.id)
          const start = index >= 0 ? v.measurementsCache[index]?.start : undefined
          return start === undefined ? undefined : start + anchor.offset
        },
      }),
    []
  )

  const parent = parentPath(path)
  useBackHandler(() => {
    if (inSelection) {
      clearSelection()
      return true
    }
    if (parent === null && !isSmb) goUp(router, { to: "/" })
    else {
      goUp(router, {
        to: "/browse/$locationId",
        params: { locationId: location.id },
        search: parent === null ? {} : { share, path: parent || undefined },
      })
    }
    return true
  })

  // Escape leaves selection mode (keyboard parity with Android back).
  useEffect(() => {
    if (!inSelection) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !document.querySelector("[role=dialog],[role=menu]"))
        clearSelection()
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [inSelection])

  function clearSelection() {
    setSelected(new Set())
    setSelectionMode(false)
  }

  function toggle(entry: SmbEntry) {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(entry.path)) next.delete(entry.path)
      else next.add(entry.path)
      if (next.size === 0) setSelectionMode(false)
      return next
    })
  }

  const run = location.run
  const refresh = async () => {
    invalidateFolder(location.cacheKey, path)
    await folder.refresh()
  }

  function view(entry: SmbEntry, edit?: boolean) {
    void navigate({
      to: "/view/$locationId",
      params: { locationId: location.id },
      search: { share, path: entry.path, edit },
    })
  }

  /**
   * Tap: text files open in the built-in viewer, pages open in the web viewer with their folder served locally, media and large files
   * stream to the chosen app, small files are cached then opened. "Open with…" (`withChooser`)
   * always shows the system chooser.
   */
  async function open(entry: SmbEntry, withChooser = false) {
    if (entry.restricted) {
      toast(t("locations.restricted.title"), { description: t("locations.restricted.body") })
      return
    }
    if (entry.isDirectory) {
      void navigate({
        to: "/browse/$locationId",
        params: { locationId: location.id },
        search: { share, path: entry.path },
      })
      return
    }
    // Text files up to the viewer's limit open in Hoard; "Open with…" in the row menu.
    if (!withChooser && opensInViewer(entry)) {
      view(entry)
      return
    }
    if (opening) return
    setOpening(entry.path)
    try {
      const strategy = openStrategy(entry, withChooser)
      if (strategy === "cache") {
        await run((loc) =>
          HoardSmb.openFile({ location: loc, path: entry.path, chooser: withChooser })
        )
      } else {
        const source = { location: await location.streamLocation(), path: entry.path }
        if (strategy === "web") await HoardSmb.openWebPage(source)
        else await HoardSmb.streamFile({ ...source, chooser: withChooser })
      }
    } catch (error) {
      const code = smbErrorCode(error)
      if (code !== "CANCELLED") {
        toast.error(t(`browse.openError.${code}`, t("browse.openError.UNKNOWN")), {
          description: entry.name,
        })
      }
    } finally {
      setOpening(null)
    }
  }

  const source = { locationId: location.id, share }
  const folderLabel = splitPath(path).at(-1) ?? share ?? location.name ?? ""
  const picked = (list: SmbEntry[]): PickedEntry[] =>
    list
      .filter((e) => !e.restricted)
      .map((e) => ({ path: e.path, name: e.name, isDirectory: e.isDirectory, size: e.size }))

  /** "Copy" / "Move": put the selection on the clipboard, then "Paste here" anywhere (ADR 0007). */
  function toClipboard(op: "copy" | "move", list: SmbEntry[]) {
    const entries = picked(list)
    if (entries.length === 0) return
    clipboard.set({ op, source: { ...source, path }, sourceLabel: folderLabel, entries })
    clearSelection()
    toast(
      t(op === "move" ? "transfer.clipboard.cut" : "transfer.clipboard.copied", {
        count: entries.length,
      }),
      {
        description: t("transfer.clipboard.hint"),
      }
    )
  }

  /** "Copy to…", "Move to…", "Download", "Upload": the destination picker. */
  function transferTo(intent: Exclude<TransferIntent, "save">, list: SmbEntry[]) {
    const entries = picked(list)
    if (entries.length) setDialog({ kind: "transferTo", intent, entries })
  }

  /** The system share sheet with the selected files (folders are left out). */
  async function shareOut(list: SmbEntry[]) {
    const files = list.filter((e) => !e.isDirectory)
    if (files.length === 0) {
      toast(t("transfer.shareFoldersOnly"))
      return
    }
    try {
      await HoardSmb.shareFiles({
        location: await location.streamLocation(),
        paths: files.map((e) => e.path),
      })
      if (files.length < list.length)
        toast(t("transfer.shareFoldersSkipped", { count: list.length - files.length }))
    } catch (error) {
      const code = smbErrorCode(error)
      if (code !== "CANCELLED") {
        toast.error(t(`browse.openError.${code}`, t("browse.openError.UNKNOWN")))
      }
    }
  }

  /** "Edit in Reveal" (ADR 0010): the photos among `list`, each with its pair (RAW + JPEG). */
  function editInReveal(list: SmbEntry[]) {
    const paths = selectionPaths(list, entries ?? [])
    if (paths.length === 0) return
    const one = list.length === 1 && !list[0].isDirectory ? list[0].path : undefined
    reveal.edit({
      location,
      paths,
      title: folderLabel,
      initialPath: one,
      cullFolder: { locationId: location.id, share: isSmb ? (share ?? "") : "", path },
    })
    if (list.length > 1) clearSelection()
  }

  // Files landing here (or leaving with a move) show up without a manual refresh.
  const transferSignal = useFolderTransferSignal(location.id, share, path)
  const lastSignal = useRef(transferSignal)
  useEffect(() => {
    if (lastSignal.current === transferSignal) return
    lastSignal.current = transferSignal
    const timer = setTimeout(() => void refresh(), 400)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refresh is rebuilt every render
  }, [transferSignal])

  // "Paste here" targets the folder on screen while it is open.
  useEffect(() => {
    const target = {
      ...source,
      path,
      label: folderLabel,
      readOnlyReason: readOnly ? readOnlyReason : undefined,
    }
    pasteTarget.set(target)
    return () => {
      if (
        pasteTarget.get()?.locationId === target.locationId &&
        pasteTarget.get()?.path === target.path
      ) {
        pasteTarget.set(null)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- source is rebuilt every render
  }, [location.id, share, path, folderLabel, readOnly, readOnlyReason])

  async function copyPath(entry: SmbEntry) {
    await copyText(location.displayPath(entry.path), t)
  }

  async function favorite(entry: SmbEntry) {
    await addFavorite(db, {
      locationId: location.id,
      machineId: machine?.id ?? null,
      share: share ?? "",
      path: entry.path,
      label: entry.name,
    })
    toast.success(t("favorites.added", { name: entry.name }))
  }

  const existing = (name: string, except?: string) =>
    entries?.some((e) => e.name.toLowerCase() === name.trim().toLowerCase() && e.name !== except)

  const validateName = (except?: string) => (value: string) =>
    !isValidName(value)
      ? t("browse.nameInvalid")
      : existing(value, except)
        ? t("browse.nameTaken")
        : null

  const rowMenu = (entry: SmbEntry) => (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("browse.actionsFor", { name: entry.name })}
        >
          <DotsThreeVerticalIcon weight="bold" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-48">
        {!entry.isDirectory && isTextFile(entry.name) && (
          <>
            <DropdownMenuItem onSelect={() => view(entry)}>
              <FileTextIcon />
              {t("browse.openInViewer")}
            </DropdownMenuItem>
          </>
        )}
        {!entry.isDirectory && (
          <DropdownMenuItem onSelect={() => void open(entry, true)}>
            <ArrowSquareOutIcon />
            {t("browse.openWith")}
          </DropdownMenuItem>
        )}
        {!entry.restricted && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => toClipboard("copy", [entry])}>
              <CopyIcon />
              {t("transfer.copy")}
            </DropdownMenuItem>
            <DropdownMenuItem disabled={readOnly} onSelect={() => toClipboard("move", [entry])}>
              <ScissorsIcon />
              {t("transfer.move")}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => transferTo("copy", [entry])}>
              <FolderSimpleIcon />
              {t("transfer.copyTo")}
            </DropdownMenuItem>
            <DropdownMenuItem disabled={readOnly} onSelect={() => transferTo("move", [entry])}>
              <ArrowsLeftRightIcon />
              {t("transfer.moveTo")}
            </DropdownMenuItem>
            {isSmb ? (
              <DropdownMenuItem onSelect={() => transferTo("download", [entry])}>
                <DownloadSimpleIcon />
                {t("browse.download")}
              </DropdownMenuItem>
            ) : (
              <DropdownMenuItem onSelect={() => transferTo("upload", [entry])}>
                <UploadSimpleIcon />
                {t("transfer.upload")}
              </DropdownMenuItem>
            )}
            {!entry.isDirectory && (
              <DropdownMenuItem onSelect={() => void shareOut([entry])}>
                <ShareNetworkIcon />
                {t("transfer.share")}
              </DropdownMenuItem>
            )}
            {reveal.available && hasPhotos([entry]) && (
              <DropdownMenuItem onSelect={() => editInReveal([entry])}>
                <SlidersHorizontalIcon />
                {t("reveal.edit")}
              </DropdownMenuItem>
            )}
            <DropdownMenuSeparator />
          </>
        )}
        {entry.isDirectory && !entry.restricted && (
          <DropdownMenuItem onSelect={() => void favorite(entry)}>
            <StarIcon />
            {t("favorites.add")}
          </DropdownMenuItem>
        )}
        <DropdownMenuItem onSelect={() => void copyPath(entry)}>
          <CopyIcon />
          {t("browse.copyPath")}
        </DropdownMenuItem>
        <DropdownMenuItem disabled={readOnly} onSelect={() => setDialog({ kind: "rename", entry })}>
          <PencilSimpleIcon />
          {t("browse.rename")}
        </DropdownMenuItem>
        <DropdownMenuItem
          onSelect={() => {
            setSelectionMode(true)
            setSelected(new Set([entry.path]))
          }}
        >
          <CheckSquareIcon />
          {t("browse.selectMode")}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          variant="destructive"
          disabled={readOnly}
          onSelect={() => setDialog({ kind: "delete", entries: [entry] })}
        >
          <TrashIcon />
          {t("common.delete")}
        </DropdownMenuItem>
        {readOnly && <ReadOnlyMenuNote reason={readOnlyReason} />}
      </DropdownMenuContent>
    </DropdownMenu>
  )

  // --- Render ---
  let body
  if (folder.error && !entries) {
    body = (
      <div className="flex flex-col p-4 md:p-6">
        <LocationErrorState
          code={folder.error.code}
          location={location}
          attempts={folder.error.attempts}
          restricted={folder.error.restricted}
          upLink={
            parent === null
              ? { to: "/" }
              : {
                  to: "/browse/$locationId",
                  params: { locationId: location.id },
                  search: { share, path: parent || undefined },
                }
          }
          retrying={folder.loading}
          onRetry={() => {
            if (folder.error?.attempts && machine) engine.forget(machine.id)
            void folder.refresh()
          }}
        />
      </div>
    )
  } else if (!entries) {
    body = <ListSkeleton className="mx-0 border-t-0 md:mx-0" />
  } else if (entries.length === 0) {
    body = (
      <Empty className="m-4 border md:m-6">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <FolderPlusIcon />
          </EmptyMedia>
          <EmptyTitle>{t("browse.emptyTitle")}</EmptyTitle>
          <EmptyDescription>{readOnly ? readOnlyReason : t("browse.emptyBody")}</EmptyDescription>
        </EmptyHeader>
        <EmptyContent className="flex-row flex-wrap justify-center">
          <Button
            variant="outline"
            disabled={readOnly}
            onClick={() => setDialog({ kind: "newFolder" })}
          >
            <FolderPlusIcon data-icon="inline-start" />
            {t("browse.newFolder")}
          </Button>
          <Button
            variant="outline"
            disabled={readOnly}
            onClick={() => setDialog({ kind: "newTextFile" })}
          >
            <FilePlusIcon data-icon="inline-start" />
            {t("browse.newTextFile")}
          </Button>
        </EmptyContent>
      </Empty>
    )
  } else {
    body = (
      <>
        <SortHeader
          sort={effectiveSort}
          onSort={setSort}
          allSelected={visible.length > 0 && selectedEntries.length === visible.length}
          someSelected={selectedEntries.length > 0}
          onSelectAll={(all) => setSelected(all ? new Set(visible.map((e) => e.path)) : new Set())}
        />
        {visible.length === 0 ? (
          <Empty className="m-4 border">
            <EmptyHeader>
              <EmptyTitle>{t("browse.noMatchesTitle")}</EmptyTitle>
              <EmptyDescription>
                {entries.every((e) => e.isHidden) && !showHidden
                  ? t("browse.onlyHidden")
                  : t("browse.noMatchesBody")}
              </EmptyDescription>
            </EmptyHeader>
            {(query || filter !== "all") && (
              <EmptyContent>
                <Button
                  variant="outline"
                  onClick={() => {
                    setQuery("")
                    setFilter("all")
                  }}
                >
                  {t("browse.clearFilters")}
                </Button>
              </EmptyContent>
            )}
          </Empty>
        ) : (
          <div
            ref={listRef}
            role="list"
            className="relative w-full"
            style={{ height: virtualizer.getTotalSize() }}
          >
            {virtualizer.getVirtualItems().map((item) => {
              const entry = visible[item.index]
              return (
                <div
                  key={item.key}
                  role="listitem"
                  data-index={item.index}
                  ref={virtualizer.measureElement}
                  className="absolute top-0 left-0 w-full"
                  style={{ transform: `translateY(${item.start - scrollMargin}px)` }}
                >
                  <FileRow
                    entry={entry}
                    location={location}
                    selected={selected.has(entry.path)}
                    selectionMode={inSelection}
                    opening={opening === entry.path}
                    onOpen={() => void open(entry)}
                    onToggleSelect={() => toggle(entry)}
                    onLongPress={() => {
                      setSelectionMode(true)
                      toggle(entry)
                    }}
                    menu={inSelection ? null : rowMenu(entry)}
                  />
                </div>
              )
            })}
          </div>
        )}
      </>
    )
  }

  const totalSize = visible.reduce((sum, e) => sum + e.size, 0)
  const tools = {
    filter,
    onFilter: setFilter,
    sort: effectiveSort,
    onSort: setSort,
    onNewFolder: () => setDialog({ kind: "newFolder" }),
    onNewTextFile: () => setDialog({ kind: "newTextFile" }),
    readOnlyReason: readOnly ? readOnlyReason : undefined,
    onRefresh: () => void refresh(),
    refreshing: folder.loading && Boolean(entries),
    disabled: !entries,
  }
  return (
    // Full bleed in the page body: the header scrolls inside the list (PageScroll).
    <div className="-mx-4 -mt-4 -mb-3 flex min-h-0 flex-1 flex-col md:-mx-6 md:-mt-6 md:-mb-4">
      {inSelection && (
        // Like Android's contextual action bar: the selection's actions stay on top, outside the
        // scrolling header, so they never scroll away.
        <div className="@container shrink-0 border-b px-2 py-1.5 md:px-4">
          <SelectionBar
            count={selectedEntries.length}
            size={formatBytes(
              selectedEntries.reduce((s, e) => s + e.size, 0),
              locale
            )}
            onClear={clearSelection}
            onSelectAll={() => setSelected(new Set(visible.map((e) => e.path)))}
            isSmb={isSmb}
            onClipboard={(op) => toClipboard(op, selectedEntries)}
            onTransferTo={(intent) => transferTo(intent, selectedEntries)}
            onShare={() => void shareOut(selectedEntries)}
            onReveal={
              reveal.available && hasPhotos(selectedEntries)
                ? () => editInReveal(selectedEntries)
                : undefined
            }
            onDelete={() =>
              selectedEntries.length && setDialog({ kind: "delete", entries: selectedEntries })
            }
            readOnlyReason={readOnly ? readOnlyReason : undefined}
          />
        </div>
      )}
      <PageScroll
        ref={scrollRef}
        scrollId={entries && entries.length > 0 ? SCROLL_IDS.browseList : undefined}
        className="@container"
        toolbar={
          inSelection ? undefined : <BrowserToolbar query={query} onQuery={setQuery} {...tools} />
        }
        compactTools={
          inSelection ? undefined : (
            <CompactSearch
              value={query}
              onChange={setQuery}
              label={t("browse.search")}
              disabled={!entries}
            />
          )
        }
        compactMenu={inSelection ? undefined : <BrowserMenuItems {...tools} />}
      >
        {body}
        {entries && entries.length > 0 && (
          // At the end of the list: no fixed line taking height from it (1.9.1).
          <p
            className="px-4 py-3 text-[0.8125rem] text-muted-foreground tabular-nums md:px-6"
            aria-live="polite"
          >
            {t("browse.count", {
              count: visible.length,
              formatted: formatNumber(visible.length, locale),
            })}
            {totalSize > 0 ? ` · ${formatBytes(totalSize, locale)}` : ""}
          </p>
        )}
      </PageScroll>

      <NameDialog
        open={dialog?.kind === "newFolder"}
        onOpenChange={(o) => !o && setDialog(null)}
        title={t("browse.newFolder")}
        label={t("browse.folderName")}
        initialValue={t("browse.newFolderDefault")}
        submitLabel={t("common.create")}
        validate={validateName()}
        onSubmit={async (name) => {
          try {
            await run((loc) => HoardSmb.createFolder({ location: loc, path, name }))
          } catch (error) {
            throw new Error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")), {
              cause: error,
            })
          }
          await refresh()
          toast.success(t("browse.folderCreated", { name }))
        }}
      />
      <NameDialog
        open={dialog?.kind === "newTextFile"}
        onOpenChange={(o) => !o && setDialog(null)}
        title={t("browse.newTextFile")}
        label={t("browse.fileName")}
        initialValue={t("browse.newTextFileDefault")}
        submitLabel={t("common.create")}
        selectBaseName
        validate={validateName()}
        onSubmit={async (name) => {
          const clean = name.trim()
          let created: SmbEntry
          try {
            const result = await run((loc) =>
              HoardSmb.writeTextFile({
                location: loc,
                path: path ? `${path}/${clean}` : clean,
                text: "",
                mode: "create",
              })
            )
            created = result.entry
          } catch (error) {
            throw new Error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")), {
              cause: error,
            })
          }
          invalidateFolder(location.cacheKey, path)
          toast.success(t("browse.fileCreated", { name: clean }))
          view(created, true)
        }}
      />
      <NameDialog
        open={dialog?.kind === "rename"}
        onOpenChange={(o) => !o && setDialog(null)}
        title={t("browse.rename")}
        description={dialog?.kind === "rename" ? dialog.entry.name : undefined}
        label={t("browse.newName")}
        initialValue={dialog?.kind === "rename" ? dialog.entry.name : ""}
        submitLabel={t("browse.rename")}
        selectBaseName={dialog?.kind === "rename" && !dialog.entry.isDirectory}
        validate={validateName(dialog?.kind === "rename" ? dialog.entry.name : undefined)}
        onSubmit={async (newName) => {
          if (dialog?.kind !== "rename") return
          const entry = dialog.entry
          try {
            await run((loc) => HoardSmb.rename({ location: loc, path: entry.path, newName }))
          } catch (error) {
            throw new Error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")), {
              cause: error,
            })
          }
          await refresh()
        }}
      />
      <DeleteDialog
        entries={dialog?.kind === "delete" ? dialog.entries : []}
        open={dialog?.kind === "delete"}
        onOpenChange={(o) => !o && setDialog(null)}
        onConfirm={async (list) => {
          let failed = 0
          for (const entry of list) {
            try {
              await run((loc) =>
                HoardSmb.delete({
                  location: loc,
                  path: entry.path,
                  recursive: entry.isDirectory,
                })
              )
            } catch {
              failed++
            }
          }
          clearSelection()
          await refresh()
          if (failed) toast.error(t("browse.deleteFailed", { count: failed }))
          else toast.success(t("browse.deleted", { count: list.length }))
        }}
      />
      {dialog?.kind === "transferTo" && (
        <TransferToDialog
          open
          onOpenChange={(o) => !o && setDialog(null)}
          intent={dialog.intent}
          source={source}
          entries={dialog.entries}
          onStarted={clearSelection}
        />
      )}
    </div>
  )
}

function SelectionBar({
  count,
  size,
  isSmb,
  onClear,
  onSelectAll,
  onClipboard,
  onTransferTo,
  onShare,
  onReveal,
  onDelete,
  readOnlyReason,
}: {
  count: number
  size: string
  isSmb: boolean
  onClear: () => void
  onSelectAll: () => void
  onClipboard: (op: "copy" | "move") => void
  onTransferTo: (intent: Exclude<TransferIntent, "save">) => void
  onShare: () => void
  /** "Edit in Reveal": set only when Reveal is installed and photos are selected (ADR 0010). */
  onReveal?: () => void
  onDelete: () => void
  /** Set in a read-only folder: Move and Delete stay visible, disabled, with this as their explanation. */
  readOnlyReason?: string
}) {
  const { t } = useTranslation()
  const none = count === 0
  return (
    <div
      className="flex min-h-9 items-center gap-1"
      role="toolbar"
      aria-label={t("browse.selection")}
    >
      <Button variant="ghost" size="icon" onClick={onClear} aria-label={t("browse.clearSelection")}>
        <XIcon />
      </Button>
      <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium" aria-live="polite">
        {t("browse.selected", { count })}
        {count > 0 && (
          <span className="font-normal text-muted-foreground @max-sm:hidden"> · {size}</span>
        )}
      </span>
      <Button variant="ghost" onClick={onSelectAll} className="@max-2xl:hidden">
        {t("browse.selectAll")}
      </Button>
      <Button
        variant="ghost"
        onClick={() => onClipboard("copy")}
        disabled={none}
        aria-label={t("transfer.copy")}
        title={t("transfer.copy")}
      >
        <CopyIcon data-icon="inline-start" />
        <span className="@max-xl:sr-only">{t("transfer.copy")}</span>
      </Button>
      <span title={readOnlyReason} className="inline-flex">
        <Button
          variant="ghost"
          onClick={() => onClipboard("move")}
          disabled={none || Boolean(readOnlyReason)}
          aria-label={t("transfer.move")}
          aria-description={readOnlyReason}
          title={t("transfer.move")}
        >
          <ScissorsIcon data-icon="inline-start" />
          <span className="@max-xl:sr-only">{t("transfer.move")}</span>
        </Button>
      </span>
      <Button
        variant="ghost"
        size="icon"
        onClick={onShare}
        disabled={none}
        aria-label={t("transfer.share")}
        title={t("transfer.share")}
        className="@max-sm:hidden"
      >
        <ShareNetworkIcon />
      </Button>
      {/* A disabled button gets no pointer events: the wrapper carries the explanation. */}
      <span title={readOnlyReason} className="inline-flex">
        <Button
          variant="ghost"
          size="icon"
          className="text-destructive hover:text-destructive"
          onClick={onDelete}
          disabled={none || Boolean(readOnlyReason)}
          aria-label={t("common.delete")}
          aria-description={readOnlyReason}
        >
          <TrashIcon />
        </Button>
      </span>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" aria-label={t("common.more")}>
            <DotsThreeVerticalIcon weight="bold" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-48">
          <DropdownMenuItem disabled={none} onSelect={() => onTransferTo("copy")}>
            <FolderSimpleIcon />
            {t("transfer.copyTo")}
          </DropdownMenuItem>
          <DropdownMenuItem
            disabled={none || Boolean(readOnlyReason)}
            onSelect={() => onTransferTo("move")}
          >
            <ArrowsLeftRightIcon />
            {t("transfer.moveTo")}
          </DropdownMenuItem>
          {isSmb ? (
            <DropdownMenuItem disabled={none} onSelect={() => onTransferTo("download")}>
              <DownloadSimpleIcon />
              {t("browse.download")}
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem disabled={none} onSelect={() => onTransferTo("upload")}>
              <UploadSimpleIcon />
              {t("transfer.upload")}
            </DropdownMenuItem>
          )}
          <DropdownMenuItem disabled={none} onSelect={onShare}>
            <ShareNetworkIcon />
            {t("transfer.share")}
          </DropdownMenuItem>
          {onReveal ? (
            <DropdownMenuItem onSelect={onReveal}>
              <SlidersHorizontalIcon />
              {t("reveal.edit")}
            </DropdownMenuItem>
          ) : null}
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={onSelectAll}>{t("browse.selectAll")}</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

function DeleteDialog({
  entries,
  open,
  onOpenChange,
  onConfirm,
}: {
  entries: SmbEntry[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (entries: SmbEntry[]) => Promise<void>
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const one = entries.length === 1 ? entries[0] : undefined
  const folders = entries.filter((e) => e.isDirectory).length
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto">
        <AlertDialogHeader>
          <AlertDialogTitle className="[overflow-wrap:anywhere]">
            {one
              ? t("browse.deleteOne", { name: one.name })
              : t("browse.deleteMany", { count: entries.length })}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {folders > 0
              ? t("browse.deleteFolderBody", { count: folders })
              : t("browse.deleteBody")}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={busy}
            onClick={async (event) => {
              event.preventDefault()
              setBusy(true)
              try {
                await onConfirm(entries)
                onOpenChange(false)
              } finally {
                setBusy(false)
              }
            }}
          >
            {t("common.delete")}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function ListSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn("-mx-4 flex flex-col border-t md:-mx-6", className)} aria-hidden>
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} className="flex min-h-16 items-center gap-3 border-b px-4 py-2 md:px-6">
          <Skeleton className="size-10 rounded-lg" />
          <div className="flex flex-1 flex-col gap-1.5">
            <Skeleton className="h-4" style={{ width: `${40 + ((i * 37) % 45)}%` }} />
            <Skeleton className="h-3 w-24" />
          </div>
        </div>
      ))}
    </div>
  )
}

async function copyText(text: string, t: (key: string) => string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success(t("browse.pathCopied"), { description: text })
  } catch {
    toast.error(t("browse.copyFailed"))
  }
}
