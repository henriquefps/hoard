import {
  ArrowCounterClockwiseIcon,
  CheckIcon,
  ColumnsIcon,
  DotsThreeVerticalIcon,
  InfoIcon,
  StarIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useVirtualizer } from "@tanstack/react-virtual"
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Spinner } from "@/components/ui/spinner"
import type { CullFlag } from "@/db/types"
import { useSetting } from "@/features/settings/settings"
import { useBackHandler } from "@/hooks/useAndroidBackButton"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { CullFace, CullImageResult, CullShot, CullZoomResult } from "@/plugins/smb/definitions"
import { comparePair } from "./assist"
import { AnalysisPanel } from "./assistParts"
import type { SuggestionState } from "./CullPage"
import { CompareView } from "./CompareView"
import { cullTimings, screenLongSide } from "./images"
import { decisionOf, prefetchSlice, withFlag, withRating } from "./model"
import { ExifPanel, FlagMark, KindBadge, ShotThumb } from "./parts"
import type { CullAssist } from "./useCullAssist"
import type { CullFolder } from "./useCullFolder"
import { ZoomableImage } from "./ZoomableImage"

/** A swipe longer than this share of the width pages; shorter ones spring back. */
const PAGE_DISTANCE = 0.22
/** A vertical swipe longer than this (CSS px) picks (up) or rejects (down). */
const FLAG_DISTANCE = 90
const FLING = 0.45

/**
 * The full-screen culling viewer (ADR 0008): one shot at a time over the WebView, swipe left/right
 * to move, swipe up to pick and down to reject (at fit), pinch or double tap to 100%, stars, undo,
 * auto-advance, a filmstrip, the EXIF overlay, and a side-by-side compare. The order is a snapshot
 * taken when it opens (deciding a shot never makes it jump away under a filter).
 */
export function CullViewer({
  folder,
  assist,
  suggestions,
  orderIds,
  startIndex,
  onClose,
}: {
  folder: CullFolder
  assist: CullAssist
  suggestions: SuggestionState
  orderIds: string[]
  startIndex: number
  onClose: (lastShotId: string | undefined) => void
}) {
  const { t } = useTranslation()
  const byId = useMemo(() => new Map(folder.shots.map((s) => [s.id, s])), [folder.shots])
  const order = useMemo(
    () => orderIds.map((id) => byId.get(id)).filter((s): s is CullShot => Boolean(s)),
    [orderIds, byId]
  )
  const [rawIndex, setIndex] = useState(() => Math.max(0, startIndex))
  // Stays valid when shots disappear (deleted elsewhere).
  const index = Math.min(rawIndex, Math.max(0, order.length - 1))
  const current = order[Math.min(index, order.length - 1)]
  const [chrome, setChrome] = useState(true)
  const [info, setInfo] = useState(true)
  const [zoomed, setZoomed] = useState(false)
  const [compareWith, setCompareWith] = useState<string | null>(null)
  const [source, setSource] = useState<{ image?: CullImageResult; zoom?: CullZoomResult }>({})
  const [autoAdvanceSetting, setAutoAdvance] = useSetting("cullAutoAdvance")
  const autoAdvance = autoAdvanceSetting ?? true
  const longSide = useMemo(() => screenLongSide(), [])
  const pager = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const currentSlot = useRef<HTMLDivElement>(null)
  const pickHint = useRef<HTMLDivElement>(null)
  const rejectHint = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)

  // "Zoom to face" requests, for the shot they were made on.
  const [focusRequest, setFocusRequest] = useState<
    (Pick<CullFace, "x" | "y" | "width" | "height"> & { seq: number; shotId: string }) | undefined
  >()
  useEffect(() => {
    if (current) folder.setLastShot(current.id)
    // Analysis follows the viewer: the shot on screen first, then its neighbours.
    if (current) assist.focus(current.id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.id])

  // Native prepares the screen images around the current shot (and the next one's 100% source).
  const ids = useMemo(() => order.map((s) => s.id), [order])
  useEffect(() => {
    if (!current) return
    const slice = prefetchSlice(ids, index, 6, 1)
    void folder
      .call((ref) =>
        HoardSmb.cullPrefetch({ ...ref, ...slice, longSide, ahead: 2, fastAhead: 6, zoomAhead: 1 })
      )
      .catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, ids, longSide, folder.key])

  useLayoutEffect(() => {
    const element = pager.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [compareWith])

  // --- Paging --------------------------------------------------------------------------------

  const paging = useRef(false)
  const swipeStarted = useRef<number | null>(null)
  const setTrack = (dx: number, animate: boolean) => {
    const element = track.current
    if (!element) return
    element.style.transition = animate ? "transform 220ms cubic-bezier(0.2, 0.8, 0.2, 1)" : "none"
    element.style.transform = `translate3d(${-width + dx}px, 0, 0)`
  }
  useLayoutEffect(() => {
    setTrack(0, false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, width])

  const goTo = useCallback(
    (next: number, animate = true) => {
      if (paging.current || next < 0 || next >= order.length || next === index) return
      if (!animate || Math.abs(next - index) !== 1 || !width) {
        setIndex(next)
        return
      }
      paging.current = true
      setTrack(next > index ? -width : width, true)
      window.setTimeout(() => {
        paging.current = false
        setIndex(next)
        if (swipeStarted.current !== null) {
          cullTimings.record("swipe", performance.now() - swipeStarted.current)
          swipeStarted.current = null
        }
      }, 220)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [index, order.length, width]
  )

  const setFlag = useCallback(
    (flag: CullFlag | null, mode: "toggle" | "set") => {
      if (!current) return
      let applied: CullFlag | null = null
      folder.decide([current.id], (d) => {
        applied = mode === "toggle" && d.flag === flag ? null : flag
        return withFlag(d, applied)
      })
      if (applied && autoAdvance && index < order.length - 1)
        window.setTimeout(() => goTo(index + 1), 120)
    },
    [current, folder, autoAdvance, index, order.length, goTo]
  )

  const rate = useCallback(
    (stars: number) => {
      if (!current) return
      folder.decide([current.id], (d) => withRating(d, d.rating === stars ? 0 : stars))
    },
    [current, folder]
  )

  const undo = useCallback(() => {
    const change = folder.undo()
    const shotId = change?.shotIds[0]
    const at = shotId ? order.findIndex((s) => s.id === shotId) : -1
    if (at >= 0 && at !== index) goTo(at, false)
  }, [folder, order, index, goTo])

  const axis = useRef<"x" | "y" | null>(null)
  const hint = (dy: number) => {
    if (pickHint.current)
      pickHint.current.style.opacity = String(Math.min(1, Math.max(0, -dy / FLAG_DISTANCE)))
    if (rejectHint.current)
      rejectHint.current.style.opacity = String(Math.min(1, Math.max(0, dy / FLAG_DISTANCE)))
  }
  const setSlotY = (dy: number, animate: boolean) => {
    const element = currentSlot.current
    if (!element) return
    element.style.transition = animate ? "transform 200ms cubic-bezier(0.2, 0.8, 0.2, 1)" : "none"
    element.style.transform = dy ? `translate3d(0, ${dy}px, 0)` : ""
  }
  const onSwipe = (
    phase: "move" | "end" | "cancel",
    dx: number,
    dy: number,
    vx: number,
    vy: number
  ) => {
    if (paging.current) return
    if (phase === "move") {
      axis.current ??= Math.abs(dx) >= Math.abs(dy) ? "x" : "y"
      if (axis.current === "x") {
        const atEdge = (dx > 0 && index === 0) || (dx < 0 && index === order.length - 1)
        setTrack(atEdge ? dx * 0.3 : dx, false)
      } else {
        setSlotY(dy * 0.6, false)
        hint(dy)
      }
      return
    }
    const along = axis.current
    axis.current = null
    hint(0)
    if (phase === "cancel" || !along) {
      setTrack(0, true)
      setSlotY(0, true)
      return
    }
    if (along === "x") {
      if ((dx < -width * PAGE_DISTANCE || vx < -FLING) && index < order.length - 1) {
        swipeStarted.current = performance.now()
        goTo(index + 1)
      } else if ((dx > width * PAGE_DISTANCE || vx > FLING) && index > 0) {
        swipeStarted.current = performance.now()
        goTo(index - 1)
      } else setTrack(0, true)
      return
    }
    setSlotY(0, true)
    if (dy < -FLAG_DISTANCE || vy < -FLING) setFlag("pick", "set")
    else if (dy > FLAG_DISTANCE || vy > FLING) setFlag("reject", "set")
  }

  // --- Keys and back -------------------------------------------------------------------------

  useBackHandler(() => {
    if (compareWith) setCompareWith(null)
    else onClose(current?.id)
    return true
  })

  /** Compare opens on the burst's two best frames (ADR 0009), else this shot and the next. */
  const toggleCompare = () => {
    if (compareWith) {
      setCompareWith(null)
      return
    }
    if (!current) return
    const pair = comparePair(
      current.id,
      ids,
      assist.bursts,
      assist.members,
      assist.analyses,
      assist.settings
    )
    if (!pair) return
    const [a, b] = pair
    const at = order.findIndex((s) => s.id === a)
    if (at >= 0 && at !== index && order.some((s) => s.id === b)) goTo(at, false)
    else if (a !== current.id || !order.some((s) => s.id === b)) {
      const other = order[index + 1] ?? order[index - 1]
      if (other) setCompareWith(other.id)
      return
    }
    setCompareWith(b)
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (
        event.target instanceof HTMLElement &&
        event.target.closest("input, textarea, [role=menu]")
      )
        return
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "z") {
        event.preventDefault()
        undo()
        return
      }
      if (event.metaKey || event.ctrlKey || event.altKey) return
      const key = event.key.toLowerCase()
      if (key === "escape") {
        if (compareWith) setCompareWith(null)
        else onClose(current?.id)
      } else if (key === "arrowright") goTo(index + 1)
      else if (key === "arrowleft") goTo(index - 1)
      else if (key === "p") setFlag("pick", "toggle")
      else if (key === "x") setFlag("reject", "toggle")
      else if (key === "u") setFlag(null, "set")
      else if (/^[1-5]$/.test(key)) rate(Number(key))
      else if (key === "0" && current) folder.decide([current.id], (d) => withRating(d, 0))
      else if (key === "i") setInfo((v) => !v)
      else if (key === "c") toggleCompare()
      else return
      event.preventDefault()
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  })

  // Measurement (ADR 0008): from a page change to the final image on screen (0 when prefetched).
  const pageStart = useRef<number | null>(null)
  const pageFirst = useRef<number | null>(null)
  useLayoutEffect(() => {
    pageStart.current = performance.now()
    pageFirst.current = pageStart.current
  }, [index])
  const onSource = useCallback((image?: CullImageResult, zoom?: CullZoomResult) => {
    setSource({ image, zoom })
    if (image && pageFirst.current !== null) {
      cullTimings.record("pageToImage", performance.now() - pageFirst.current)
      pageFirst.current = null
    }
    if (image?.final && pageStart.current !== null) {
      cullTimings.record("pageToFinal", performance.now() - pageStart.current)
      pageStart.current = null
    }
  }, [])

  if (!current) return null
  const suggestion = suggestions.on ? suggestions.items.get(current.id) : undefined
  const jumpToFace = (face: CullFace) =>
    setFocusRequest((previous) => ({
      x: face.x,
      y: face.y,
      width: face.width,
      height: face.height,
      seq: (previous?.seq ?? 0) + 1,
      shotId: current.id,
    }))
  const decision = decisionOf(folder.decisions, current.id)
  const metadata = folder.metadata.get(current.id)
  const loadingFinal = source.image && !source.image.final
  const slots = [index - 1, index, index + 1].filter((i) => i >= 0 && i < order.length)

  return (
    <div
      className="dark fixed inset-0 z-50 flex flex-col bg-viewer text-viewer-foreground"
      style={{ colorScheme: "dark" }}
      role="dialog"
      aria-modal="true"
      aria-label={t("cull.viewer.title", { name: current.stem })}
    >
      {compareWith && byId.get(compareWith) ? (
        <CompareView
          folder={folder}
          order={order}
          assist={assist}
          a={current}
          b={byId.get(compareWith)!}
          longSide={longSide}
          onB={setCompareWith}
          onClose={() => setCompareWith(null)}
          onExit={() => onClose(current.id)}
        />
      ) : (
        <>
          {/* Pager: previous, current and next shots side by side; the track moves. */}
          <div ref={pager} className="absolute inset-0 overflow-hidden">
            <div
              ref={track}
              className="absolute inset-0"
              style={{ transform: `translate3d(${-width}px, 0, 0)` }}
            >
              {slots.map((i) => {
                const shot = order[i]
                const active = i === index
                return (
                  <div
                    key={shot.id}
                    ref={active ? currentSlot : undefined}
                    className="absolute top-0 h-full"
                    style={{ left: (i - index + 1) * width, width }}
                    aria-hidden={!active}
                  >
                    <ZoomableImage
                      folder={folder}
                      shot={shot}
                      metadata={folder.metadata.get(shot.id)}
                      longSide={longSide}
                      active={active}
                      onSwipe={active ? onSwipe : undefined}
                      onTap={active ? () => setChrome((v) => !v) : undefined}
                      onZoomedChange={active ? setZoomed : undefined}
                      onSource={active ? onSource : undefined}
                      focusRequest={
                        active && focusRequest?.shotId === shot.id ? focusRequest : undefined
                      }
                    />
                  </div>
                )
              })}
            </div>
            <div
              ref={pickHint}
              aria-hidden
              className="pointer-events-none absolute top-1/3 left-1/2 flex -translate-x-1/2 items-center gap-2 rounded-full bg-primary px-4 py-2 text-base font-semibold text-primary-foreground opacity-0"
            >
              <CheckIcon weight="bold" /> {t("cull.pick")}
            </div>
            <div
              ref={rejectHint}
              aria-hidden
              className="pointer-events-none absolute bottom-1/3 left-1/2 flex -translate-x-1/2 items-center gap-2 rounded-full bg-destructive px-4 py-2 text-base font-semibold text-viewer-foreground opacity-0"
            >
              <XIcon weight="bold" /> {t("cull.reject")}
            </div>
          </div>

          {/* Top bar */}
          <div
            className={cn(
              "relative z-10 flex items-center gap-1 bg-viewer/70 px-2 pt-[env(safe-area-inset-top)] backdrop-blur-sm transition-opacity duration-150",
              !chrome && "pointer-events-none opacity-0"
            )}
          >
            <div className="flex h-14 min-w-0 flex-1 items-center gap-1">
              <Button
                variant="ghost"
                size="icon"
                aria-label={t("common.close")}
                onClick={() => onClose(current.id)}
              >
                <XIcon />
              </Button>
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-[0.9375rem] font-medium">{current.stem}</span>
                <span className="text-xs text-viewer-foreground/70 tabular-nums">
                  {t("cull.viewer.position", { index: index + 1, total: order.length })}
                </span>
              </div>
              <KindBadge shot={current} className="ml-1 shrink-0" />
              <FlagMark decision={decision} className="ml-1 shrink-0" />
              {loadingFinal ? (
                <Spinner className="ml-1 size-4 shrink-0 text-viewer-foreground/70" />
              ) : null}
            </div>
            <Button
              variant="ghost"
              size="icon"
              aria-label={t("cull.undo")}
              disabled={!folder.canUndo}
              onClick={undo}
            >
              <ArrowCounterClockwiseIcon />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label={t("cull.viewer.info")}
              aria-pressed={info}
              onClick={() => setInfo((v) => !v)}
              className={cn(info && "text-primary")}
            >
              <InfoIcon weight={info ? "fill" : "regular"} />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label={t("cull.compare.open")}
              disabled={order.length < 2}
              onClick={toggleCompare}
            >
              <ColumnsIcon />
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" aria-label={t("common.more")}>
                  <DotsThreeVerticalIcon weight="bold" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-56">
                <DropdownMenuCheckboxItem
                  checked={autoAdvance}
                  onCheckedChange={(v) => void setAutoAdvance(v)}
                >
                  {t("cull.autoAdvance")}
                </DropdownMenuCheckboxItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>

          {info && chrome ? (
            <div className="pointer-events-none relative z-10 mt-2 ml-3 self-start">
              <ExifPanel shot={current} metadata={metadata} burst={assist.bursts.get(current.id)} />
              <SourceNote image={source.image} zoom={zoomed ? source.zoom : undefined} />
              <AnalysisPanel
                className="mt-2"
                analysis={assist.analyses.get(current.id)}
                flags={assist.flags.get(current.id)}
                best={assist.bursts.has(current.id) && assist.bestShots.has(current.id)}
                facesAvailable={assist.facesAvailable}
                suggestion={suggestion}
                onAccept={suggestion ? () => suggestions.accept([current.id]) : undefined}
                onAcceptBurst={
                  suggestion?.burst
                    ? () =>
                        suggestions.accept(
                          [...suggestions.items.values()]
                            .filter((x) => x.burst === suggestion.burst)
                            .map((x) => x.shotId)
                        )
                    : undefined
                }
                onJumpToFace={jumpToFace}
              />
            </div>
          ) : null}

          <div className="flex-1" />

          {/* Bottom: decide, then the filmstrip. */}
          <div
            className={cn(
              "relative z-10 flex flex-col bg-viewer/70 pb-[env(safe-area-inset-bottom)] backdrop-blur-sm transition-opacity duration-150",
              !chrome && "pointer-events-none opacity-0"
            )}
          >
            <DecisionBar
              decision={decision}
              onReject={() => setFlag("reject", "toggle")}
              onPick={() => setFlag("pick", "toggle")}
              onRate={rate}
            />
            <Filmstrip
              folder={folder}
              order={order}
              index={index}
              onSelect={(i) => goTo(i, false)}
            />
          </div>
        </>
      )}
    </div>
  )
}

/** "Embedded preview · 1616 px" while the better image loads; "100% from LibRaw" when zoomed. */
function SourceNote({ image, zoom }: { image?: CullImageResult; zoom?: CullZoomResult }) {
  const { t } = useTranslation()
  if (!image) return null
  const text = zoom ? t(`cull.source.zoom.${zoom.source}`) : t(`cull.source.screen.${image.source}`)
  return (
    <span className="mt-1 inline-flex rounded-md bg-viewer/75 px-2 py-0.5 text-xs text-viewer-foreground/80">
      {text}
    </span>
  )
}

/** Reject · 1-5 stars · Pick. */
export function DecisionBar({
  decision,
  onReject,
  onPick,
  onRate,
  compact = false,
}: {
  decision: { flag: CullFlag | null; rating: number }
  onReject: () => void
  onPick: () => void
  onRate?: (stars: number) => void
  compact?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className={cn("flex items-center justify-between gap-1 px-2", compact ? "h-12" : "h-16")}>
      <Button
        variant={decision.flag === "reject" ? "destructive" : "ghost"}
        size={compact ? "icon" : "lg"}
        aria-pressed={decision.flag === "reject"}
        aria-label={t("cull.reject")}
        onClick={onReject}
        className={cn(!compact && "h-11 min-w-11 gap-2 px-3")}
      >
        <XIcon weight="bold" />
        {compact ? null : <span className="max-sm:sr-only">{t("cull.reject")}</span>}
      </Button>
      {onRate ? (
        <div className="flex items-center" role="group" aria-label={t("cull.rating")}>
          {[1, 2, 3, 4, 5].map((stars) => (
            <button
              key={stars}
              type="button"
              aria-label={t("cull.stars", { count: stars })}
              aria-pressed={decision.rating >= stars}
              onClick={() => onRate(stars)}
              className="inline-flex size-11 items-center justify-center rounded-md text-viewer-foreground/80 transition-colors hover:bg-viewer-foreground/10 [&_svg]:size-6"
            >
              <StarIcon
                weight={decision.rating >= stars ? "fill" : "regular"}
                className={cn(decision.rating >= stars && "text-warning")}
              />
            </button>
          ))}
        </div>
      ) : null}
      <Button
        variant={decision.flag === "pick" ? "default" : "ghost"}
        size={compact ? "icon" : "lg"}
        aria-pressed={decision.flag === "pick"}
        aria-label={t("cull.pick")}
        onClick={onPick}
        className={cn(!compact && "h-11 min-w-11 gap-2 px-3")}
      >
        <CheckIcon weight="bold" />
        {compact ? null : <span className="max-sm:sr-only">{t("cull.pick")}</span>}
      </Button>
    </div>
  )
}

const FILM_ITEM = 56

/** Thumbnails of the viewing order; the current one is outlined and kept centered. */
export function Filmstrip({
  folder,
  order,
  index,
  onSelect,
  highlight,
}: {
  folder: CullFolder
  order: CullShot[]
  index: number
  onSelect: (index: number) => void
  /** Also outline this shot (compare: the second pane). */
  highlight?: string
}) {
  const { t } = useTranslation()
  const scroller = useRef<HTMLDivElement>(null)
  // eslint-disable-next-line react-hooks/incompatible-library -- the virtualizer is read during render on purpose
  const virtualizer = useVirtualizer({
    horizontal: true,
    count: order.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => FILM_ITEM,
    overscan: 8,
  })
  useEffect(() => {
    virtualizer.scrollToIndex(index, { align: "center" })
  }, [index, virtualizer])
  return (
    <div
      ref={scroller}
      className="h-16 [scrollbar-width:none] overflow-x-auto overflow-y-hidden overscroll-x-contain"
      aria-label={t("cull.filmstrip")}
    >
      <div className="relative h-full" style={{ width: virtualizer.getTotalSize() }}>
        {virtualizer.getVirtualItems().map((item) => {
          const shot = order[item.index]
          const decision = decisionOf(folder.decisions, shot.id)
          const selected = item.index === index || shot.id === highlight
          return (
            <button
              key={shot.id}
              type="button"
              aria-label={shot.stem}
              aria-current={item.index === index || undefined}
              onClick={() => onSelect(item.index)}
              className="absolute top-2 flex h-12 items-center justify-center"
              style={{ left: item.start, width: FILM_ITEM }}
            >
              <span
                className={cn(
                  "relative block size-12 overflow-hidden rounded-md ring-offset-2 ring-offset-viewer",
                  selected && "ring-2 ring-primary",
                  decision.flag === "reject" && "opacity-45"
                )}
              >
                <ShotThumb folder={folder} shot={shot} className="size-12" />
                {decision.flag ? (
                  <span
                    className={cn(
                      "absolute right-0.5 bottom-0.5 size-2.5 rounded-full ring-1 ring-viewer",
                      decision.flag === "pick" ? "bg-primary" : "bg-destructive"
                    )}
                  />
                ) : null}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
