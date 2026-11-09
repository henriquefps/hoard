import { CopyIcon, FlagCheckeredIcon, PlayIcon, TrashIcon } from "@phosphor-icons/react"
import { useParams, useSearch } from "@tanstack/react-router"
import { useVirtualizer } from "@tanstack/react-virtual"
import { useLayoutEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Page } from "@/components/page/Page"
import { cullTrail } from "@/components/page/breadcrumbs"
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
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty"
import { Progress } from "@/components/ui/progress"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { invalidateFolder } from "@/features/browse/useFolder"
import { LocationErrorState } from "@/features/locations/LocationErrorState"
import { parseLocationId } from "@/features/locations/ids"
import { type BrowseLocation, useBrowseLocation } from "@/features/locations/useBrowseLocation"
import { useLeaveWhenUnplugged } from "@/features/locations/useLeaveWhenUnplugged"
import { formatBytes, formatNumber } from "@/lib/format"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { CullShot } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { CopyPicksDialog } from "./CopyPicksDialog"
import { CullViewer } from "./CullViewer"
import {
  ALL_SHOTS,
  type CullFilter,
  type CullFlagFilter,
  decisionOf,
  filterShots,
  nextUnmarked,
  planDeleteRejects,
  progressOf,
} from "./model"
import { BurstMark, FlagMark, KindBadge, ShotThumb, StarsMark } from "./parts"
import { type CullFolder, useCullFolder } from "./useCullFolder"

/**
 * `/cull/$locationId?share=&path=` (ADR 0008): a folder of any location as shots (RAW + JPEG pairs
 * collapsed), a dense thumbnail grid in capture order with burst hints, filters, progress, the
 * full-screen viewer, and the finish actions (copy picks, delete rejects).
 */
export function CullPage() {
  const { t } = useTranslation()
  const { locationId } = useParams({ from: "/cull/$locationId" })
  const { share, path = "" } = useSearch({ from: "/cull/$locationId" })
  const isSmb = parseLocationId(locationId).kind === "smb"
  const location = useBrowseLocation(locationId, isSmb ? share : undefined)
  useLeaveWhenUnplugged(location)
  const trailLocation = {
    id: locationId,
    kind: location?.kind ?? parseLocationId(locationId).kind,
    name: location?.name,
  }
  const title = path.split("/").filter(Boolean).at(-1) ?? share ?? location?.name ?? ""
  if (location === null) {
    return (
      <Page
        title={t("machines.notFound")}
        breadcrumbs={cullTrail(t, { ...trailLocation, name: "?" }, share, path)}
      >
        <SmbErrorState code="NOT_FOUND" machineId={locationId} />
      </Page>
    )
  }
  if (!location) {
    return (
      <Page fill title={title} breadcrumbs={cullTrail(t, trailLocation, share, path)}>
        <GridSkeleton />
      </Page>
    )
  }
  return (
    <CullFolderPage
      key={`${location.cacheKey}/${path}`}
      location={location}
      path={path}
      title={title}
      trail={cullTrail(t, trailLocation, share, path)}
    />
  )
}

function CullFolderPage({
  location,
  path,
  title,
  trail,
}: {
  location: BrowseLocation
  path: string
  title: string
  trail: ReturnType<typeof cullTrail>
}) {
  const { t, i18n } = useTranslation()
  const folder = useCullFolder(location, path, () => toast.error(t("cull.saveFailed")))
  const [filter, setFilter] = useState<CullFilter>(ALL_SHOTS)
  const [viewer, setViewer] = useState<{ ids: string[]; index: number } | null>(null)
  const [copying, setCopying] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const visible = useMemo(
    () => filterShots(folder.shots, folder.decisions, filter),
    [folder.shots, folder.decisions, filter]
  )
  const progress = useMemo(
    () => progressOf(folder.shots, folder.decisions),
    [folder.shots, folder.decisions]
  )
  const locale = i18n.language
  const readOnly = folder.access === "read"

  const openAt = (index: number) => setViewer({ ids: visible.map((s) => s.id), index })
  const resume = () => {
    const ids = visible.map((s) => s.id)
    if (ids.length === 0) return
    const last = folder.session?.last_shot_id ? ids.indexOf(folder.session.last_shot_id) : -1
    const next = nextUnmarked(visible, folder.decisions, last >= 0 ? last - 1 : -1)
    setViewer({ ids, index: next >= 0 ? next : Math.max(0, last) })
  }

  const meta =
    folder.status === "ready" ? (
      <span className="text-[0.8125rem] text-muted-foreground tabular-nums">
        {t("cull.progress", {
          decided: formatNumber(progress.decided, locale),
          total: formatNumber(progress.total, locale),
          picked: formatNumber(progress.picked, locale),
          rejected: formatNumber(progress.rejected, locale),
        })}
      </span>
    ) : undefined

  const actions =
    folder.status === "ready" && folder.shots.length > 0 ? (
      <>
        <Button onClick={resume} disabled={visible.length === 0}>
          <PlayIcon data-icon="inline-start" weight="fill" />
          {progress.decided > 0 ? t("cull.resume") : t("cull.start")}
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline">
              <FlagCheckeredIcon data-icon="inline-start" />
              <span className="max-sm:sr-only">{t("cull.finish")}</span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="min-w-60">
            <DropdownMenuItem disabled={progress.picked === 0} onSelect={() => setCopying(true)}>
              <CopyIcon />
              {t("cull.copy.menu", { count: progress.picked })}
            </DropdownMenuItem>
            <DropdownMenuItem
              variant="destructive"
              disabled={progress.rejected === 0 || readOnly}
              onSelect={() => setDeleting(true)}
            >
              <TrashIcon />
              {readOnly
                ? t("cull.delete.readOnly")
                : t("cull.delete.menu", { count: progress.rejected })}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </>
    ) : undefined

  return (
    <Page fill title={title} breadcrumbs={trail} meta={meta} actions={actions}>
      {folder.status === "loading" ? (
        <GridSkeleton />
      ) : folder.status === "error" ? (
        <LocationErrorState
          code={smbErrorCode(folder.error)}
          location={location}
          onRetry={folder.reload}
        />
      ) : folder.shots.length === 0 ? (
        <Empty className="flex-1">
          <EmptyHeader>
            <EmptyTitle>{t("cull.emptyTitle")}</EmptyTitle>
            <EmptyDescription>{t("cull.emptyBody")}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="-mx-4 -mt-4 flex min-h-0 flex-1 flex-col md:-mx-6 md:-mt-6">
          <Toolbar
            folder={folder}
            filter={filter}
            onFilter={setFilter}
            visibleCount={visible.length}
          />
          {visible.length === 0 ? (
            <Empty className="flex-1">
              <EmptyHeader>
                <EmptyTitle>{t("cull.noMatchTitle")}</EmptyTitle>
                <EmptyDescription>{t("cull.noMatchBody")}</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <ShotGrid folder={folder} shots={visible} onOpen={openAt} />
          )}
        </div>
      )}
      {viewer ? (
        <CullViewer
          folder={folder}
          orderIds={viewer.ids}
          startIndex={viewer.index}
          onClose={() => setViewer(null)}
        />
      ) : null}
      <CopyPicksDialog
        open={copying}
        onOpenChange={setCopying}
        source={{ locationId: folder.key.locationId, share: folder.key.share }}
        sourcePath={path}
        order={folder.shots}
        decisions={folder.decisions}
      />
      <DeleteRejectsDialog
        open={deleting}
        onOpenChange={setDeleting}
        folder={folder}
        location={location}
        path={path}
      />
    </Page>
  )
}

const FLAG_FILTERS: CullFlagFilter[] = ["all", "picked", "rejected", "unmarked"]

function Toolbar({
  folder,
  filter,
  onFilter,
  visibleCount,
}: {
  folder: CullFolder
  filter: CullFilter
  onFilter: (filter: CullFilter) => void
  visibleCount: number
}) {
  const { t, i18n } = useTranslation()
  const progress = progressOf(folder.shots, folder.decisions)
  const counts: Record<CullFlagFilter, number> = {
    all: progress.total,
    picked: progress.picked,
    rejected: progress.rejected,
    unmarked: progress.unmarked,
  }
  const label = (value: CullFlagFilter) =>
    `${t(`cull.filter.${value}`)} · ${formatNumber(counts[value], i18n.language)}`
  return (
    <div className="@container flex flex-col gap-2 border-b px-4 py-3 md:px-6">
      <div className="flex items-center gap-2">
        <ToggleGroup
          type="single"
          variant="outline"
          value={filter.flag}
          onValueChange={(value) => value && onFilter({ ...filter, flag: value as CullFlagFilter })}
          className="hidden @2xl:flex"
        >
          {FLAG_FILTERS.map((value) => (
            <ToggleGroupItem key={value} value={value} className="px-3">
              {label(value)}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        <Select
          value={filter.flag}
          onValueChange={(value) => onFilter({ ...filter, flag: value as CullFlagFilter })}
        >
          <SelectTrigger className="min-w-0 flex-1 @2xl:hidden" aria-label={t("cull.filter.label")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {FLAG_FILTERS.map((value) => (
              <SelectItem key={value} value={value}>
                {label(value)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={String(filter.minStars)}
          onValueChange={(value) => onFilter({ ...filter, minStars: Number(value) })}
        >
          <SelectTrigger className="w-36 shrink-0" aria-label={t("cull.filter.stars")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {[0, 1, 2, 3, 4, 5].map((stars) => (
              <SelectItem key={stars} value={String(stars)}>
                {stars === 0
                  ? t("cull.filter.anyStars")
                  : t("cull.filter.minStars", { count: stars })}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <span className="ml-auto hidden text-[0.8125rem] text-muted-foreground tabular-nums @2xl:inline">
          {t("cull.showing", {
            count: visibleCount,
            shown: formatNumber(visibleCount, i18n.language),
          })}
        </span>
      </div>
      <div className="flex items-center gap-3">
        <Progress
          value={progress.total ? (progress.decided / progress.total) * 100 : 0}
          className="h-1.5 flex-1"
          aria-label={t("cull.progressLabel")}
        />
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
          {progress.total ? Math.round((progress.decided / progress.total) * 100) : 0}%
        </span>
        {!folder.metadataDone ? (
          <span className="shrink-0 text-xs text-muted-foreground">{t("cull.readingExif")}</span>
        ) : null}
      </div>
    </div>
  )
}

/** Cells are at least this wide (CSS px): 3 columns on a phone, more on wider screens. */
const MIN_CELL = 112
const GAP = 2

function ShotGrid({
  folder,
  shots,
  onOpen,
}: {
  folder: CullFolder
  shots: CullShot[]
  onOpen: (index: number) => void
}) {
  const { t } = useTranslation()
  const scroller = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  useLayoutEffect(() => {
    const element = scroller.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  const columns = Math.max(3, Math.floor((width + GAP) / (MIN_CELL + GAP)))
  const cell = width ? (width - GAP * (columns - 1)) / columns : MIN_CELL
  const rows = Math.ceil(shots.length / columns)
  // eslint-disable-next-line react-hooks/incompatible-library -- the virtualizer is read during render on purpose
  const virtualizer = useVirtualizer({
    count: rows,
    getScrollElement: () => scroller.current,
    estimateSize: () => cell + GAP,
    overscan: 4,
  })
  useLayoutEffect(() => {
    virtualizer.measure()
  }, [cell, virtualizer])

  return (
    <div
      ref={scroller}
      className="min-h-0 flex-1 overflow-y-auto overscroll-contain"
      role="grid"
      aria-label={t("cull.grid")}
    >
      <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
        {virtualizer.getVirtualItems().map((row) => (
          <div
            key={row.key}
            role="row"
            className="absolute left-0 flex w-full"
            style={{ top: row.start, height: cell, gap: GAP }}
          >
            {shots.slice(row.index * columns, row.index * columns + columns).map((shot, column) => {
              const index = row.index * columns + column
              const decision = decisionOf(folder.decisions, shot.id)
              const burst = folder.bursts.get(shot.id)
              return (
                <button
                  key={shot.id}
                  type="button"
                  role="gridcell"
                  aria-label={t("cull.cellLabel", {
                    name: shot.stem,
                    kind: t(`cull.kind.${shot.kind}`),
                    state: decision.flag
                      ? t(decision.flag === "pick" ? "cull.flag.picked" : "cull.flag.rejected")
                      : t("cull.flag.unmarked"),
                  })}
                  onClick={() => onOpen(index)}
                  className="group relative shrink-0 overflow-hidden bg-muted focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring"
                  style={{ width: cell, height: cell }}
                >
                  <ShotThumb
                    folder={folder}
                    shot={shot}
                    className={cn(
                      "size-full transition-opacity",
                      decision.flag === "reject" && "opacity-40"
                    )}
                  />
                  {burst ? <BurstMark position={burst} className="absolute top-1 left-1" /> : null}
                  <FlagMark decision={decision} className="absolute top-1 right-1" />
                  <KindBadge shot={shot} className="absolute bottom-1 left-1" />
                  <StarsMark rating={decision.rating} className="absolute right-1 bottom-1" />
                </button>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}

function GridSkeleton() {
  return (
    <div className="grid grid-cols-3 gap-0.5 md:grid-cols-6">
      {Array.from({ length: 18 }, (_, i) => (
        <Skeleton key={i} className="aspect-square rounded-none" />
      ))}
    </div>
  )
}

/**
 * Deletes every file of every rejected shot, after a confirmation that says how many and how
 * much, and that it is permanent (SMB shares and USB drives have no trash). Stops at the first
 * refusal and says why (a read-only share answers ACCESS_DENIED).
 */
function DeleteRejectsDialog({
  open,
  onOpenChange,
  folder,
  location,
  path,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  folder: CullFolder
  location: BrowseLocation
  path: string
}) {
  const { t, i18n } = useTranslation()
  const [busy, setBusy] = useState(false)
  const plan = useMemo(
    () => planDeleteRejects(folder.shots, folder.decisions),
    [folder.shots, folder.decisions]
  )
  const where =
    location.kind === "smb"
      ? t("cull.delete.whereSmb", { name: location.name ?? "" })
      : location.kind === "volume"
        ? t("cull.delete.whereDrive", { name: location.name ?? "" })
        : t("cull.delete.wherePhone")

  const run = async () => {
    setBusy(true)
    const deleted = new Set<string>()
    let error: unknown
    for (const file of plan.files) {
      try {
        await location.run((loc) => HoardSmb.delete({ location: loc, path: file.path }))
        deleted.add(file.path)
      } catch (e) {
        if (smbErrorCode(e) === "NOT_FOUND") {
          deleted.add(file.path)
          continue
        }
        error = e
        break
      }
    }
    // A shot is gone when every one of its files is (a pair half-deleted stays, with what is left).
    const byId = new Map(folder.shots.map((s) => [s.id, s]))
    const gone = plan.shots.filter((id) => {
      const shot = byId.get(id)
      return (
        shot && [shot.raw, shot.rendered, ...shot.others].every((f) => !f || deleted.has(f.path))
      )
    })
    await folder.removeShots(gone)
    invalidateFolder(location.cacheKey, path)
    setBusy(false)
    onOpenChange(false)
    if (error) {
      const code = smbErrorCode(error)
      toast.error(code === "ACCESS_DENIED" ? t("cull.delete.refused") : t(`errors.${code}.title`), {
        description: t("cull.delete.partial", { count: gone.length }),
      })
    } else {
      toast.success(t("cull.delete.done", { count: gone.length }))
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={(next) => !busy && onOpenChange(next)}>
      <AlertDialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto">
        <AlertDialogHeader>
          <AlertDialogTitle>
            {t("cull.delete.title", { count: plan.shots.length })}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {t("cull.delete.body", {
              shots: formatNumber(plan.shots.length, i18n.language),
              files: formatNumber(plan.files.length, i18n.language),
              size: formatBytes(plan.bytes, i18n.language),
              where,
              count: plan.files.length,
            })}
          </AlertDialogDescription>
          <p className="text-sm font-medium text-destructive">{t("cull.delete.permanent")}</p>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>{t("common.cancel")}</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={busy || plan.files.length === 0}
            onClick={(event) => {
              event.preventDefault()
              void run()
            }}
          >
            {busy
              ? t("cull.delete.deleting")
              : t("cull.delete.confirm", { count: plan.files.length })}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
