import {
  CopyIcon,
  FlagCheckeredIcon,
  GearSixIcon,
  PlayIcon,
  SparkleIcon,
  StackIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { useParams, useSearch } from "@tanstack/react-router"
import { useVirtualizer } from "@tanstack/react-virtual"
import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "react"
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
import { Spinner } from "@/components/ui/spinner"
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
import {
  type AssistFilter,
  type CullSort,
  gridItems,
  matchesAssist,
  SHOT_FLAGS,
  sortBySharpness,
  suggest,
  type Suggestion,
} from "./assist"
import { AssistSettingsDialog, FlagMarks, SuggestionMark } from "./assistParts"
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
  withFlag,
} from "./model"
import { BurstMark, FlagMark, KindBadge, ShotThumb, StarsMark } from "./parts"
import { type CullAssist, useCullAssist } from "./useCullAssist"
import { type CullFolder, useCullFolder } from "./useCullFolder"

/**
 * `/cull/$locationId?share=&path=` (ADR 0008, 0009): a folder of any location as shots (RAW + JPEG
 * pairs collapsed), a dense thumbnail grid in capture order with bursts stacked, analysis flags
 * and suggestions, filters, progress, the full-screen viewer, and the finish actions (copy
 * picks, delete rejects).
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

/** Everything the grid, toolbar and viewer show about the folder's suggestions. */
export interface SuggestionState {
  /** Suggestions are shown (the user pressed Suggest). */
  on: boolean
  items: ReadonlyMap<string, Suggestion>
  /** Accept some (one undo step); returns how many shots changed. */
  accept: (shotIds: readonly string[]) => number
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
  const assist = useCullAssist(folder)
  const [filter, setFilter] = useState<CullFilter>(ALL_SHOTS)
  const [assistFilter, setAssistFilter] = useState<AssistFilter>("any")
  const [sort, setSort] = useState<CullSort>("time")
  const [stack, setStack] = useState(true)
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set())
  const [suggesting, setSuggesting] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [viewer, setViewer] = useState<{ ids: string[]; index: number } | null>(null)
  const [copying, setCopying] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const visible = useMemo(() => {
    const byDecision = filterShots(folder.shots, folder.decisions, filter)
    const byAnalysis =
      assistFilter === "any"
        ? byDecision
        : byDecision.filter((s) => matchesAssist(assist.flags.get(s.id), assistFilter))
    return sort === "sharpness" ? sortBySharpness(byAnalysis, assist.analyses) : byAnalysis
  }, [folder.shots, folder.decisions, filter, assistFilter, sort, assist.flags, assist.analyses])
  const progress = useMemo(
    () => progressOf(folder.shots, folder.decisions),
    [folder.shots, folder.decisions]
  )
  const locale = i18n.language
  const readOnly = folder.access === "read"

  const suggestionItems = useMemo(
    () =>
      suggesting
        ? suggest(folder.shots, assist.bursts, assist.analyses, folder.decisions, assist.settings)
        : new Map<string, Suggestion>(),
    [suggesting, folder.shots, assist.bursts, assist.analyses, folder.decisions, assist.settings]
  )
  const accept = useCallback(
    (shotIds: readonly string[]) => {
      const chosen = shotIds
        .map((id) => suggestionItems.get(id))
        .filter((s): s is Suggestion => Boolean(s))
      if (chosen.length === 0) return 0
      const actions = new Map(chosen.map((s) => [s.shotId, s.action]))
      const change = folder.decide([...actions.keys()], (d, id) =>
        withFlag(d, actions.get(id) === "keep" ? "pick" : "reject")
      )
      const count = change?.shotIds.length ?? 0
      if (count > 0) {
        toast.success(t("cull.assist.accepted", { count }), {
          action: { label: t("cull.undo"), onClick: () => folder.undo() },
        })
      }
      return count
    },
    [suggestionItems, folder, t]
  )
  const suggestions: SuggestionState = { on: suggesting, items: suggestionItems, accept }

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
        <Button
          variant={suggesting ? "secondary" : "outline"}
          aria-pressed={suggesting}
          onClick={() => setSuggesting((v) => !v)}
        >
          <SparkleIcon data-icon="inline-start" weight={suggesting ? "fill" : "regular"} />
          <span className="max-sm:sr-only">{t("cull.assist.suggest")}</span>
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
            assist={assist}
            filter={filter}
            onFilter={setFilter}
            assistFilter={assistFilter}
            onAssistFilter={setAssistFilter}
            sort={sort}
            onSort={setSort}
            stack={stack}
            onStack={(next) => {
              setStack(next)
              setExpanded(new Set())
            }}
            onSettings={() => setSettingsOpen(true)}
            visibleCount={visible.length}
          />
          {suggesting ? (
            <SuggestionBar
              suggestions={suggestions}
              assist={assist}
              onHide={() => setSuggesting(false)}
            />
          ) : null}
          {visible.length === 0 ? (
            <Empty className="flex-1">
              <EmptyHeader>
                <EmptyTitle>{t("cull.noMatchTitle")}</EmptyTitle>
                <EmptyDescription>{t("cull.noMatchBody")}</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <ShotGrid
              folder={folder}
              assist={assist}
              suggestions={suggestions}
              shots={visible}
              stack={stack && sort === "time"}
              expanded={expanded}
              onToggleBurst={(burst) =>
                setExpanded((current) => {
                  const next = new Set(current)
                  if (next.has(burst)) next.delete(burst)
                  else next.add(burst)
                  return next
                })
              }
              onOpen={openAt}
            />
          )}
        </div>
      )}
      {viewer ? (
        <CullViewer
          folder={folder}
          assist={assist}
          suggestions={suggestions}
          orderIds={viewer.ids}
          startIndex={viewer.index}
          onClose={() => {
            assist.focus(null)
            setViewer(null)
          }}
        />
      ) : null}
      <AssistSettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />
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

/** Under the toolbar while suggestions show: what they would do, Accept all, Hide. */
function SuggestionBar({
  suggestions,
  assist,
  onHide,
}: {
  suggestions: SuggestionState
  assist: CullAssist
  onHide: () => void
}) {
  const { t, i18n } = useTranslation()
  let keep = 0
  let reject = 0
  for (const s of suggestions.items.values()) {
    if (s.action === "keep") keep++
    else reject++
  }
  const analysing = assist.progress.done + assist.progress.failed < assist.progress.total
  return (
    <div className="@container flex flex-col gap-2 border-b px-4 py-3 md:px-6">
      <div className="flex items-start gap-3">
        <SparkleIcon weight="fill" className="mt-0.5 size-5 shrink-0 text-primary" />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-[0.9375rem] font-medium">
            {suggestions.items.size === 0
              ? t("cull.assist.suggestNone")
              : t("cull.assist.suggestSummary", {
                  keep: formatNumber(keep, i18n.language),
                  reject: formatNumber(reject, i18n.language),
                })}
          </span>
          <span className="text-[0.8125rem] text-muted-foreground">
            {analysing ? t("cull.assist.suggestPartial") : t("cull.assist.suggestHint")}
          </span>
        </div>
        <div className="hidden shrink-0 items-center gap-2 @lg:flex">
          <Button variant="ghost" onClick={onHide}>
            {t("cull.assist.dismiss")}
          </Button>
          <Button
            disabled={suggestions.items.size === 0}
            onClick={() => suggestions.accept([...suggestions.items.keys()])}
          >
            {t("cull.assist.acceptAll", { count: suggestions.items.size })}
          </Button>
        </div>
      </div>
      <div className="flex gap-2 @lg:hidden">
        <Button
          className="flex-1"
          disabled={suggestions.items.size === 0}
          onClick={() => suggestions.accept([...suggestions.items.keys()])}
        >
          {t("cull.assist.acceptAll", { count: suggestions.items.size })}
        </Button>
        <Button variant="outline" onClick={onHide}>
          {t("cull.assist.dismiss")}
        </Button>
      </div>
    </div>
  )
}

const FLAG_FILTERS: CullFlagFilter[] = ["all", "picked", "rejected", "unmarked"]
const ASSIST_FILTERS: AssistFilter[] = ["any", "flagged", ...SHOT_FLAGS, "clean"]

function Toolbar({
  folder,
  assist,
  filter,
  onFilter,
  assistFilter,
  onAssistFilter,
  sort,
  onSort,
  stack,
  onStack,
  onSettings,
  visibleCount,
}: {
  folder: CullFolder
  assist: CullAssist
  filter: CullFilter
  onFilter: (filter: CullFilter) => void
  assistFilter: AssistFilter
  onAssistFilter: (filter: AssistFilter) => void
  sort: CullSort
  onSort: (sort: CullSort) => void
  stack: boolean
  onStack: (stack: boolean) => void
  onSettings: () => void
  visibleCount: number
}) {
  const { t, i18n } = useTranslation()
  const locale = i18n.language
  const progress = progressOf(folder.shots, folder.decisions)
  const counts: Record<CullFlagFilter, number> = {
    all: progress.total,
    picked: progress.picked,
    rejected: progress.rejected,
    unmarked: progress.unmarked,
  }
  const label = (value: CullFlagFilter) =>
    `${t(`cull.filter.${value}`)} · ${formatNumber(counts[value], locale)}`
  const assistCount = (value: AssistFilter) =>
    value === "any"
      ? folder.shots.length
      : folder.shots.filter((s) => matchesAssist(assist.flags.get(s.id), value)).length
  const assistLabel = (value: AssistFilter) =>
    value === "any"
      ? t("cull.assist.filter.any")
      : `${t(`cull.assist.filter.${value}`)} · ${formatNumber(assistCount(value), locale)}`
  const a = assist.progress
  const analysing = a.done + a.failed < a.total
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
            shown: formatNumber(visibleCount, locale),
          })}
        </span>
      </div>
      <div className="flex items-center gap-2">
        <Select value={assistFilter} onValueChange={(v) => onAssistFilter(v as AssistFilter)}>
          <SelectTrigger
            className="min-w-0 flex-1 @2xl:w-56 @2xl:flex-none"
            aria-label={t("cull.assist.filter.label")}
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ASSIST_FILTERS.map((value) => (
              <SelectItem key={value} value={value}>
                {assistLabel(value)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={sort} onValueChange={(v) => onSort(v as CullSort)}>
          <SelectTrigger
            className="w-auto shrink-0 @md:w-44"
            aria-label={t("cull.assist.sort.label")}
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="time">{t("cull.assist.sort.time")}</SelectItem>
            <SelectItem value="sharpness">{t("cull.assist.sort.sharpness")}</SelectItem>
          </SelectContent>
        </Select>
        <Button
          variant="outline"
          size="icon"
          aria-pressed={stack}
          aria-label={t("cull.assist.stack")}
          title={t("cull.assist.stack")}
          disabled={sort !== "time"}
          onClick={() => onStack(!stack)}
          className={cn(stack && sort === "time" && "bg-muted text-primary")}
        >
          <StackIcon weight={stack && sort === "time" ? "fill" : "regular"} />
        </Button>
        <Button
          variant="outline"
          size="icon"
          aria-label={t("cull.assist.settings")}
          title={t("cull.assist.settings")}
          onClick={onSettings}
        >
          <GearSixIcon />
        </Button>
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
        ) : (
          <span
            className="inline-flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground tabular-nums"
            role="status"
          >
            {analysing && a.running ? <Spinner className="size-3" /> : null}
            {analysing
              ? t("cull.assist.analysing", {
                  done: formatNumber(a.done, locale),
                  total: formatNumber(a.total, locale),
                })
              : a.failed > 0
                ? t("cull.assist.failed", { count: a.failed })
                : t("cull.assist.analysed")}
          </span>
        )}
      </div>
    </div>
  )
}

/** Cells are at least this wide (CSS px): 3 columns on a phone, more on wider screens. */
const MIN_CELL = 112
const GAP = 2

function ShotGrid({
  folder,
  assist,
  suggestions,
  shots,
  stack,
  expanded,
  onToggleBurst,
  onOpen,
}: {
  folder: CullFolder
  assist: CullAssist
  suggestions: SuggestionState
  shots: CullShot[]
  stack: boolean
  expanded: ReadonlySet<string>
  onToggleBurst: (burst: string) => void
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
  const items = useMemo(
    () => gridItems(shots, assist.bursts, assist.best, expanded, stack),
    [shots, assist.bursts, assist.best, expanded, stack]
  )
  const byId = useMemo(() => new Map(shots.map((s) => [s.id, s])), [shots])
  const columns = Math.max(3, Math.floor((width + GAP) / (MIN_CELL + GAP)))
  const cell = width ? (width - GAP * (columns - 1)) / columns : MIN_CELL
  const rows = Math.ceil(items.length / columns)
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
            {items.slice(row.index * columns, row.index * columns + columns).map((item) => {
              const shot = byId.get(item.shotId)!
              const decision = decisionOf(folder.decisions, shot.id)
              const burst = assist.bursts.get(shot.id)
              const suggestion = suggestions.on ? suggestions.items.get(shot.id) : undefined
              const isStack = item.kind === "stack"
              return (
                <div
                  key={isStack ? `stack:${item.burst}` : shot.id}
                  role="gridcell"
                  className="relative shrink-0"
                  style={{ width: cell, height: cell }}
                >
                  <button
                    type="button"
                    aria-label={t("cull.cellLabel", {
                      name: shot.stem,
                      kind: t(`cull.kind.${shot.kind}`),
                      state: decision.flag
                        ? t(decision.flag === "pick" ? "cull.flag.picked" : "cull.flag.rejected")
                        : t("cull.flag.unmarked"),
                    })}
                    onClick={() => onOpen(item.index)}
                    className={cn(
                      "group relative size-full overflow-hidden bg-muted focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring"
                    )}
                  >
                    <ShotThumb
                      folder={folder}
                      shot={shot}
                      className={cn(
                        "size-full transition-opacity",
                        decision.flag === "reject" && "opacity-40",
                        suggestion?.action === "reject" && !decision.flag && "opacity-60"
                      )}
                    />
                    {isStack ? (
                      <span
                        aria-hidden
                        className="pointer-events-none absolute inset-0 border-2 border-primary/70"
                      />
                    ) : null}
                    {suggestion ? (
                      <span
                        aria-hidden
                        className={cn(
                          "pointer-events-none absolute inset-0 border-2 border-dashed",
                          suggestion.action === "keep" ? "border-primary" : "border-destructive"
                        )}
                      />
                    ) : null}
                    <span className="absolute top-1 right-1 flex flex-col items-end gap-1">
                      <FlagMark decision={decision} />
                      <FlagMarks
                        flags={assist.flags.get(shot.id)}
                        best={Boolean(burst) && assist.bestShots.has(shot.id)}
                      />
                    </span>
                    {suggestion ? (
                      <SuggestionMark
                        suggestion={suggestion}
                        className="absolute bottom-7 left-1"
                      />
                    ) : null}
                    <KindBadge shot={shot} className="absolute bottom-1 left-1" />
                    <StarsMark rating={decision.rating} className="absolute right-1 bottom-1" />
                  </button>
                  {isStack ? (
                    <button
                      type="button"
                      onClick={() => onToggleBurst(item.burst)}
                      aria-label={t("cull.assist.expand", { count: item.size })}
                      aria-expanded={false}
                      className="absolute top-1 left-1 inline-flex h-6 items-center gap-1 rounded-md bg-primary px-1.5 text-xs leading-none font-medium text-primary-foreground tabular-nums before:absolute before:-inset-2 [&_svg]:size-3.5"
                    >
                      <StackIcon weight="fill" />
                      {item.size}
                    </button>
                  ) : burst ? (
                    stack && burst.index === 0 && expanded.has(burst.burst) ? (
                      <button
                        type="button"
                        onClick={() => onToggleBurst(burst.burst)}
                        aria-label={t("cull.assist.collapse")}
                        aria-expanded
                        className="absolute top-1 left-1 before:absolute before:-inset-2"
                      >
                        <BurstMark
                          position={burst}
                          className="bg-primary text-primary-foreground"
                        />
                      </button>
                    ) : (
                      <BurstMark
                        position={burst}
                        className="pointer-events-none absolute top-1 left-1"
                      />
                    )
                  ) : null}
                </div>
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
