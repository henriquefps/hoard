import {
  CheckSquareIcon,
  CopyIcon,
  DotsThreeVerticalIcon,
  ArrowSquareOutIcon,
  DownloadSimpleIcon,
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
import { Page } from "@/components/page/Page"
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
  getMachine,
  listManualShares,
  removeFavorite,
  removeManualShare,
} from "@/db/queries"
import type { MachineWithAddresses } from "@/db/types"
import { BrowserToolbar, SortHeader } from "@/features/browse/BrowserToolbar"
import { accountName, isReadOnly, readOnlyReasonKey } from "@/features/browse/access"
import { FileRow } from "@/features/browse/FileRow"
import { ReadOnlyBadge, ReadOnlyMenuNote } from "@/features/browse/ReadOnlyBadge"
import { invalidateFolder, useFolder } from "@/features/browse/useFolder"
import { nativeRoute } from "@/features/route/engine"
import { useMachineRoute, useRouteEngine } from "@/features/route/RouteProvider"
import { RouteStatus } from "@/features/route/RouteStatus"
import { useSetting } from "@/features/settings/settings"
import { DownloadDialog } from "@/features/transfers/DownloadDialog"
import type { DownloadFile } from "@/features/transfers/service"
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
import { isTextFile, opensInViewer } from "@/lib/text/textFiles"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { SmbAccess, SmbEntry, SmbShare } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"

// The sort the user picked lasts for the session, across folders (the default is a setting).
let sessionSort: SortOrder | null = null

/** `/browse/$machineId?share=&path=`: the machine's shares, then folders and files. */
export function BrowsePage() {
  const { t } = useTranslation()
  const { machineId } = useParams({ from: "/browse/$machineId" })
  const { share, path = "" } = useSearch({ from: "/browse/$machineId" })
  const machine = useLiveQuery((db) => getMachine(db, machineId), [machineId])
  const route = useMachineRoute(machine)
  const segments = splitPath(path)
  const title = segments.at(-1) ?? share ?? machine?.name ?? ""
  // The open folder's access, reported by FolderView once listed (keyed so a stale answer from
  // the previous folder never shows on the next one).
  const folderKey = share ? `${share}/${path}` : ""
  const [access, setAccess] = useState<{ key: string; access?: SmbAccess }>({ key: "" })
  const readOnly = share !== undefined && access.key === folderKey && isReadOnly(access.access)
  const readOnlyReason = useReadOnlyReason(machine, path)

  if (machine === null) {
    return (
      <Page
        title={t("machines.notFound")}
        breadcrumbs={browseTrail(t, { id: machineId, name: "?" })}
      >
        <SmbErrorState code="NOT_FOUND" machineId={machineId} />
      </Page>
    )
  }

  return (
    <Page
      fill
      title={title}
      breadcrumbs={browseTrail(t, { id: machineId, name: machine?.name }, share, path)}
      meta={
        readOnly ? (
          <span className="flex max-w-full min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <RouteStatus state={route} className="max-w-full" />
            <ReadOnlyBadge title={readOnlyReason} />
          </span>
        ) : (
          <RouteStatus state={route} className="max-w-full" />
        )
      }
      actions={machine && <HeaderActions machine={machine} share={share} path={path} />}
    >
      {machine === undefined ? (
        <ListSkeleton className="-mt-4 md:-mt-6" />
      ) : share ? (
        // Keyed by folder: search, filter and selection start fresh in every folder.
        <FolderView
          key={folderKey}
          machine={machine}
          share={share}
          path={path}
          onAccess={(value) => setAccess({ key: folderKey, access: value })}
        />
      ) : (
        <SharesView machine={machine} />
      )}
    </Page>
  )
}

/** "This folder is read-only for henrique." for the folder at `path` (the share root says "share"). */
function useReadOnlyReason(machine: MachineWithAddresses | null | undefined, path: string) {
  const { t } = useTranslation()
  const user = accountName(machine?.username, machine?.domain)
  return t(readOnlyReasonKey(path, user !== null), { user })
}

function HeaderActions({
  machine,
  share,
  path,
}: {
  machine: MachineWithAddresses
  share: string | undefined
  path: string
}) {
  const { t } = useTranslation()
  const db = useDb()
  const favorite = useLiveQuery(
    (d) => (share ? findFavorite(d, machine.id, share, path) : Promise.resolve(null)),
    [machine.id, share, path]
  )
  const [showHidden, setShowHidden] = useSetting("showHiddenFiles")
  const label = splitPath(path).at(-1) ?? share ?? ""
  return (
    <>
      {share && (
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
              await addFavorite(db, { machineId: machine.id, share, path, label })
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
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" aria-label={t("common.more")}>
            <DotsThreeVerticalIcon weight="bold" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-52">
          <DropdownMenuCheckboxItem
            checked={showHidden ?? false}
            onCheckedChange={(checked) => void setShowHidden(checked)}
          >
            <EyeIcon />
            {t("browse.showHidden")}
          </DropdownMenuCheckboxItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem asChild>
            <Link to="/machines/$machineId/edit" params={{ machineId: machine.id }}>
              <PencilSimpleIcon />
              {t("machines.edit")}
            </Link>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  )
}

// --- Shares ------------------------------------------------------------------------------------

function SharesView({ machine }: { machine: MachineWithAddresses }) {
  const { t } = useTranslation()
  const db = useDb()
  const navigate = useNavigate()
  const folder = useFolder(machine, undefined, "")
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
              HoardSmb.list({ sessionId, share: name, path: "" })
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
            to: "/browse/$machineId",
            params: { machineId: machine.id },
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
    <div className="-mx-4 -mt-4 min-h-0 flex-1 overflow-y-auto overscroll-contain md:-mx-6 md:-mt-6">
      <h2 className="px-4 pt-4 pb-2 eyebrow md:px-6">{t("browse.shares")}</h2>
      <ul className="border-t">
        {shares.map((s) => (
          <ShareRow key={s.name} machine={machine} share={s} manual={s.manual} />
        ))}
      </ul>
      <div className="px-4 py-4 md:px-6">{openByName}</div>
    </div>
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
  const reason = useReadOnlyReason(machine, "")
  return (
    <li className="relative flex min-h-16 items-center gap-3 border-b px-4 py-2 transition-colors hover:bg-muted/50 md:px-6">
      <span
        aria-hidden
        className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5"
      >
        <HardDriveIcon weight="duotone" />
      </span>
      <Link
        to="/browse/$machineId"
        params={{ machineId: machine.id }}
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
  | { kind: "download"; files: DownloadFile[]; folders: number }
  | null

function FolderView({
  machine,
  share,
  path,
  onAccess,
}: {
  machine: MachineWithAddresses
  share: string
  path: string
  /** Called with the folder's access whenever a listing arrives (for the header badge). */
  onAccess: (access: SmbAccess | undefined) => void
}) {
  const { t, i18n } = useTranslation()
  const db = useDb()
  const router = useRouter()
  const navigate = useNavigate()
  const engine = useRouteEngine()
  const folder = useFolder(machine, share, path)
  // Write actions stay visible but disabled where the server said this account may only read; a
  // write the server refuses anyway (access changed) still shows its error.
  const access = folder.data?.kind === "entries" ? folder.data.access : undefined
  const readOnly = isReadOnly(access)
  const readOnlyReason = useReadOnlyReason(machine, path)
  const onAccessRef = useRef(onAccess)
  useEffect(() => {
    onAccessRef.current = onAccess
  })
  useEffect(() => {
    if (folder.data) onAccessRef.current(access)
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

  // eslint-disable-next-line react-hooks/incompatible-library -- the virtualizer is read during render on purpose
  const virtualizer = useVirtualizer({
    count: visible.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 64,
    overscan: 10,
    getItemKey: (index) => visible[index]?.path ?? index,
  })

  const parent = parentPath(path)
  useBackHandler(() => {
    if (inSelection) {
      clearSelection()
      return true
    }
    goUp(router, {
      to: "/browse/$machineId",
      params: { machineId: machine.id },
      search: parent === null ? {} : { share, path: parent || undefined },
    })
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

  const run = <T,>(fn: (sessionId: string) => Promise<T>) => engine.withSession(machine, fn)
  const refresh = async () => {
    invalidateFolder(machine.id, share, path)
    await folder.refresh()
  }

  function view(entry: SmbEntry, edit?: boolean) {
    void navigate({
      to: "/view/$machineId",
      params: { machineId: machine.id },
      search: { share, path: entry.path, edit },
    })
  }

  /**
   * Tap: text files open in the built-in viewer, pages open in the web viewer with their folder served locally, media and large files
   * stream to the chosen app, small files are cached then opened. "Open with…" (`withChooser`)
   * always shows the system chooser.
   */
  async function open(entry: SmbEntry, withChooser = false) {
    if (entry.isDirectory) {
      void navigate({
        to: "/browse/$machineId",
        params: { machineId: machine.id },
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
        await run((sessionId) =>
          HoardSmb.openFile({ sessionId, share, path: entry.path, chooser: withChooser })
        )
      } else {
        const source = {
          machineId: machine.id,
          share,
          path: entry.path,
          ...(await nativeRoute(engine, machine)),
        }
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

  function download(list: SmbEntry[]) {
    const files = list.filter((e) => !e.isDirectory)
    if (files.length === 0) {
      toast(t("download.onlyFolders"))
      return
    }
    setDialog({
      kind: "download",
      folders: list.length - files.length,
      files: files.map((e) => ({ share, path: e.path, name: e.name, size: e.size })),
    })
  }

  async function copyPath(entry: SmbEntry) {
    const host = engine.activeRoute(machine.id)?.host ?? machine.addresses[0]?.host ?? ""
    await copyText(uncPath(host, share, entry.path), t)
  }

  async function favorite(entry: SmbEntry) {
    await addFavorite(db, { machineId: machine.id, share, path: entry.path, label: entry.name })
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
        {!entry.isDirectory && (
          <DropdownMenuItem onSelect={() => download([entry])}>
            <DownloadSimpleIcon />
            {t("browse.download")}
          </DropdownMenuItem>
        )}
        {entry.isDirectory && (
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
      <SmbErrorState
        code={folder.error.code}
        machineId={machine.id}
        attempts={folder.error.attempts}
        retrying={folder.loading}
        onRetry={() => {
          if (folder.error?.attempts) engine.forget(machine.id)
          void folder.refresh()
        }}
      />
    )
  } else if (!entries) {
    body = <ListSkeleton />
  } else if (entries.length === 0) {
    body = (
      <Empty className="border">
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
      <div
        ref={scrollRef}
        className="@container -mx-4 min-h-0 flex-1 overflow-y-auto overscroll-contain border-t md:-mx-6"
      >
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
                  style={{ transform: `translateY(${item.start}px)` }}
                >
                  <FileRow
                    entry={entry}
                    machine={machine}
                    share={share}
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
      </div>
    )
  }

  const totalSize = visible.reduce((sum, e) => sum + e.size, 0)
  return (
    <div className="@container flex min-h-0 flex-1 flex-col gap-3">
      {inSelection ? (
        <SelectionBar
          count={selectedEntries.length}
          size={formatBytes(
            selectedEntries.reduce((s, e) => s + e.size, 0),
            locale
          )}
          onClear={clearSelection}
          onSelectAll={() => setSelected(new Set(visible.map((e) => e.path)))}
          onDownload={() => download(selectedEntries)}
          onDelete={() =>
            selectedEntries.length && setDialog({ kind: "delete", entries: selectedEntries })
          }
          readOnlyReason={readOnly ? readOnlyReason : undefined}
        />
      ) : (
        <BrowserToolbar
          query={query}
          onQuery={setQuery}
          filter={filter}
          onFilter={setFilter}
          sort={effectiveSort}
          onSort={setSort}
          onNewFolder={() => setDialog({ kind: "newFolder" })}
          onNewTextFile={() => setDialog({ kind: "newTextFile" })}
          readOnlyReason={readOnly ? readOnlyReason : undefined}
          onRefresh={() => void refresh()}
          refreshing={folder.loading && Boolean(entries)}
          disabled={!entries}
        />
      )}
      {body}
      {entries && entries.length > 0 && (
        <p className="-mb-1 text-[0.8125rem] text-muted-foreground tabular-nums" aria-live="polite">
          {t("browse.count", {
            count: visible.length,
            formatted: formatNumber(visible.length, locale),
          })}
          {totalSize > 0 ? ` · ${formatBytes(totalSize, locale)}` : ""}
        </p>
      )}

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
            await run((sessionId) => HoardSmb.createFolder({ sessionId, share, path, name }))
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
            const result = await run((sessionId) =>
              HoardSmb.writeTextFile({
                sessionId,
                share,
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
          invalidateFolder(machine.id, share, path)
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
            await run((sessionId) =>
              HoardSmb.rename({ sessionId, share, path: entry.path, newName })
            )
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
              await run((sessionId) =>
                HoardSmb.delete({
                  sessionId,
                  share,
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
      {dialog?.kind === "download" && (
        <DownloadDialog
          open
          onOpenChange={(o) => !o && setDialog(null)}
          machine={machine}
          files={dialog.files}
          skippedFolders={dialog.folders}
          onStarted={clearSelection}
        />
      )}
    </div>
  )
}

function SelectionBar({
  count,
  size,
  onClear,
  onSelectAll,
  onDownload,
  onDelete,
  readOnlyReason,
}: {
  count: number
  size: string
  onClear: () => void
  onSelectAll: () => void
  onDownload: () => void
  onDelete: () => void
  /** Set in a read-only folder: Delete stays visible, disabled, with this as its explanation. */
  readOnlyReason?: string
}) {
  const { t } = useTranslation()
  return (
    <div
      className="flex min-h-9 items-center gap-2"
      role="toolbar"
      aria-label={t("browse.selection")}
    >
      <Button variant="ghost" size="icon" onClick={onClear} aria-label={t("browse.clearSelection")}>
        <XIcon />
      </Button>
      <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium" aria-live="polite">
        {t("browse.selected", { count })}
        {count > 0 && <span className="font-normal text-muted-foreground"> · {size}</span>}
      </span>
      <Button variant="ghost" onClick={onSelectAll} className="max-@md:hidden">
        {t("browse.selectAll")}
      </Button>
      <Button variant="outline" onClick={onDownload} disabled={count === 0}>
        <DownloadSimpleIcon data-icon="inline-start" />
        <span className="max-@md:sr-only">{t("browse.download")}</span>
      </Button>
      {/* A disabled button gets no pointer events: the wrapper carries the explanation. */}
      <span title={readOnlyReason} className="inline-flex">
        <Button
          variant="destructive"
          size="icon"
          onClick={onDelete}
          disabled={count === 0 || Boolean(readOnlyReason)}
          aria-label={t("common.delete")}
          aria-description={readOnlyReason}
        >
          <TrashIcon />
        </Button>
      </span>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" className="@md:hidden" aria-label={t("common.more")}>
            <DotsThreeVerticalIcon weight="bold" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
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
