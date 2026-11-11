import {
  ArrowCounterClockwiseIcon,
  ArrowLeftIcon,
  CheckCircleIcon,
  CheckIcon,
  ColumnsIcon,
  CrownSimpleIcon,
  DotsThreeVerticalIcon,
  EyeClosedIcon,
  InfoIcon,
  SlidersHorizontalIcon,
  SparkleIcon,
  StackIcon,
  StarIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useVirtualizer } from "@tanstack/react-virtual"
import {
  type ReactNode,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Drawer as DrawerPrimitive } from "vaul"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Spinner } from "@/components/ui/spinner"
import type { CullFlag } from "@/db/types"
import { useSetting } from "@/features/settings/settings"
import { useBackHandler } from "@/hooks/useAndroidBackButton"
import { useIsMobile } from "@/hooks/use-mobile"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { CullFace, CullImageResult, CullShot, CullZoomResult } from "@/plugins/smb/definitions"
import { eyesSummary, rankBurst, sharpnessScore, suggest } from "./assist"
import {
  burstStatus,
  comparePartner,
  keepThisOne,
  stepOf,
  stepShot,
  type ViewerStep,
} from "./burst"
import type { SuggestionState } from "./CullPage"
import { CompareView } from "./CompareView"
import { InfoChips, ShotDetails } from "./details"
import { cullTimings, screenLongSide } from "./images"
import { decisionOf, pageTarget, prefetchSlice, withFlag, withRating } from "./model"
import { FlagMark, ShotThumb } from "./parts"
import type { CullAssist } from "./useCullAssist"
import type { CullFolder } from "./useCullFolder"
import { ZoomableImage } from "./ZoomableImage"

/** A vertical swipe longer than this (CSS px) acts (details, or pick / reject). */
const FLAG_DISTANCE = 90
const FLING = 0.45
/** Details sheet snap points on phones (share of the screen height). */
const SNAPS = [0.5, 0.94]
/** Height of the band at each screen edge kept from Android's back gesture (CSS px ≈ dp). */
const EDGE_BAND = 200

/** Where the viewer opens: a folder step, optionally straight into that burst at one frame. */
export interface ViewerStart {
  step: number
  burst?: { burst: string; shotIds: string[]; shotId: string }
}

interface BurstMode {
  burst: string
  ids: string[]
  index: number
}

/**
 * The full-screen culling viewer (ADR 0008).
 *
 * **Folder mode** walks `steps`: a shot, or a whole burst shown by its cover (the frame the user
 * picked, else the suggested best) with "View burst (n)". **Burst mode** walks one burst's frames
 * only, with "Keep this one" (pick this frame, reject the others but never un-pick an explicit
 * keeper), a quick compare against the suggested best and the burst's suggestion.
 *
 * Swipe left/right to move, swipe up for the details (or pick/reject, a setting), pinch or double
 * tap to 100%, stars, undo, auto-advance, a filmstrip, the compact chips on the photo and a
 * details sheet (phones) or side panel (wider screens). The steps are a snapshot taken when it
 * opens (deciding a shot never makes it jump away under a filter).
 */
export function CullViewer({
  folder,
  assist,
  suggestions,
  steps,
  start,
  onEditInReveal,
  onClose,
}: {
  folder: CullFolder
  assist: CullAssist
  suggestions: SuggestionState
  steps: ViewerStep[]
  start: ViewerStart
  /** "Edit in Reveal" on the current shot; only while Reveal is installed (ADR 0010). */
  onEditInReveal?: (shot: CullShot) => void
  onClose: (lastShotId: string | undefined) => void
}) {
  const { t } = useTranslation()
  const isMobile = useIsMobile()
  const byId = useMemo(() => new Map(folder.shots.map((s) => [s.id, s])), [folder.shots])
  const [stepIndex, setStepIndex] = useState(() =>
    Math.max(0, Math.min(start.step, steps.length - 1))
  )
  const [burstMode, setBurstMode] = useState<BurstMode | null>(() =>
    start.burst
      ? {
          burst: start.burst.burst,
          ids: start.burst.shotIds,
          index: Math.max(0, start.burst.shotIds.indexOf(start.burst.shotId)),
        }
      : null
  )
  const order = useMemo(() => {
    const ids = burstMode
      ? burstMode.ids
      : steps.map((step) => stepShot(step, folder.decisions, assist.best))
    return ids.map((id) => byId.get(id)).filter((s): s is CullShot => Boolean(s))
  }, [burstMode, steps, folder.decisions, assist.best, byId])
  const rawIndex = burstMode ? burstMode.index : stepIndex
  // Stays valid when shots disappear (deleted elsewhere).
  const index = Math.min(rawIndex, Math.max(0, order.length - 1))
  const setIndex = useCallback(
    (next: number) => {
      if (burstMode) setBurstMode((b) => (b ? { ...b, index: next } : b))
      else setStepIndex(next)
    },
    [burstMode]
  )
  const current = order[index]
  const step = burstMode ? undefined : steps[index]
  const [chrome, setChrome] = useState(true)
  const [details, setDetails] = useState(() => !isMobile)
  const [snap, setSnap] = useState<number | string | null>(SNAPS[0])
  const [zoomed, setZoomed] = useState(false)
  const [compare, setCompare] = useState<{ b: string; ids: string[]; face?: CullFace } | null>(null)
  const [source, setSource] = useState<{ image?: CullImageResult; zoom?: CullZoomResult }>({})
  const [autoAdvanceSetting, setAutoAdvance] = useSetting("cullAutoAdvance")
  const autoAdvance = autoAdvanceSetting ?? true
  const [swipeDecideSetting, setSwipeDecide] = useSetting("cullSwipeDecide")
  const swipeDecide = swipeDecideSetting ?? false
  const longSide = useMemo(() => screenLongSide(), [])
  const pager = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const currentSlot = useRef<HTMLDivElement>(null)
  const pickHint = useRef<HTMLDivElement>(null)
  const rejectHint = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const sheetOpen = isMobile && details && !compare

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

  // The burst on screen: in burst mode the one being walked; in folder mode the step's burst, or
  // the burst of the shot shown (filters on: frames are separate steps).
  const burstOfCurrent = current ? assist.bursts.get(current.id) : undefined
  const burstIds = useMemo(
    () =>
      burstMode
        ? burstMode.ids
        : step?.kind === "burst"
          ? step.shotIds
          : burstOfCurrent
            ? (assist.members.get(burstOfCurrent.burst) ?? [])
            : [],
    [burstMode, step, burstOfCurrent, assist.members]
  )
  const burstId = burstMode?.burst ?? (step?.kind === "burst" ? step.burst : burstOfCurrent?.burst)

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
  }, [compare])

  // Swipes that start at the screen edges page instead of triggering Android's back gesture, in
  // a band across the middle of the photo (Android honours 200 dp per edge). Back still works from
  // the rest of the edge and with the back button.
  useEffect(() => {
    const element = pager.current
    if (!element || compare) return
    const rect = element.getBoundingClientRect()
    const top = Math.max(0, Math.round(rect.top + rect.height / 2 - EDGE_BAND / 2))
    void HoardSmb.setGestureExclusion({ bands: [{ top, height: EDGE_BAND }] }).catch(() => {})
    return () => void HoardSmb.setGestureExclusion({ bands: [] }).catch(() => {})
  }, [compare, sheetOpen, width])

  // Vaul (non-modal, controlled) can leave `pointer-events: none` on body after the sheet closes
  // from outside (the i button, a swipe on the photo, leaving the viewer): give it back unless a
  // real modal dialog is open.
  useEffect(() => {
    if (sheetOpen) return
    const timer = window.setTimeout(() => {
      const modalOpen = document.querySelector(
        "[role=alertdialog][data-state=open], [role=dialog][data-state=open]:not([data-vaul-drawer])"
      )
      if (!modalOpen && document.body.style.pointerEvents === "none")
        document.body.style.pointerEvents = ""
    }, 550)
    return () => window.clearTimeout(timer)
  }, [sheetOpen])

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
  }, [index, width, burstMode?.burst])

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
    [index, order.length, width, setIndex]
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

  /** Undo, and show the shot it changed (in this mode, or the folder step that holds it). */
  const undo = useCallback(() => {
    const change = folder.undo()
    const shotId = change?.shotIds[0]
    if (!shotId) return
    if (burstMode) {
      const at = burstMode.ids.indexOf(shotId)
      if (at >= 0 && at !== index) goTo(at, false)
      return
    }
    const at = stepOf(steps, shotId)
    if (at >= 0 && at !== index) goTo(at, false)
  }, [folder, burstMode, steps, index, goTo])

  // --- Burst mode ------------------------------------------------------------------------------

  const enterBurst = useCallback(() => {
    if (!current || !burstId || burstIds.length < 2) return
    setCompare(null)
    setBurstMode({
      burst: burstId,
      ids: [...burstIds],
      index: Math.max(0, burstIds.indexOf(current.id)),
    })
  }, [current, burstId, burstIds])

  /** Back to the folder, on the step that holds `shotId` (the burst's step when collapsed). */
  const exitBurst = useCallback(
    (advance = false) => {
      const shotId = current?.id
      setBurstMode(null)
      setCompare(null)
      const at = shotId ? stepOf(steps, shotId) : -1
      const base = at >= 0 ? at : stepIndex
      setStepIndex(Math.min(steps.length - 1, base + (advance ? 1 : 0)))
    },
    [current, steps, stepIndex]
  )

  /** Keep this one (`shotId`, default the frame on screen): see `keepThisOne`. */
  const keepFrame = useCallback(
    (shot: CullShot | undefined = current) => {
      if (!shot || burstIds.length < 2) return
      const plan = keepThisOne(folder.decisions, burstIds, shot.id)
      if (!plan) {
        toast(t("cull.burstMode.alreadyKept"))
        return
      }
      const after = new Map(plan.shotIds.map((id, i) => [id, plan.after[i]]))
      const change = folder.decide(plan.shotIds, (d, id) => after.get(id) ?? d)
      const rejected = change
        ? change.shotIds.filter((id, i) => id !== shot.id && change.after[i].flag === "reject")
            .length
        : 0
      toast.success(t("cull.burstMode.kept", { name: shot.stem, count: rejected }), {
        action: { label: t("cull.undo"), onClick: () => folder.undo() },
      })
      setCompare(null)
      if (burstMode && autoAdvance) exitBurst(true)
    },
    [current, burstIds, folder, t, burstMode, autoAdvance, exitBurst]
  )
  const keepCurrent = useCallback(() => keepFrame(), [keepFrame])

  // The burst's own suggestion (ADR 0009), whether or not "Suggest" is on for the folder.
  const burstSuggestions = useMemo(() => {
    if (!burstMode) return []
    const shots = burstMode.ids.map((id) => byId.get(id)).filter((s): s is CullShot => Boolean(s))
    return [
      ...suggest(shots, assist.bursts, assist.analyses, folder.decisions, assist.settings).values(),
    ].filter((s) => s.burst === burstMode.burst)
  }, [burstMode, byId, assist.bursts, assist.analyses, folder.decisions, assist.settings])

  const ranked = useMemo(
    () =>
      burstIds.length >= 2
        ? rankBurst(burstIds, assist.analyses, assist.settings).map((r) => r.shotId)
        : [],
    [burstIds, assist.analyses, assist.settings]
  )

  // --- Gestures ------------------------------------------------------------------------------

  const axis = useRef<"x" | "y" | null>(null)
  const hint = (dy: number) => {
    const show = swipeDecide && !sheetOpen
    if (pickHint.current)
      pickHint.current.style.opacity = show
        ? String(Math.min(1, Math.max(0, -dy / FLAG_DISTANCE)))
        : "0"
    if (rejectHint.current)
      rejectHint.current.style.opacity = show
        ? String(Math.min(1, Math.max(0, dy / FLAG_DISTANCE)))
        : "0"
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
        setSlotY(dy * (swipeDecide && !sheetOpen ? 0.6 : 0.35), false)
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
      const next = pageTarget(dx, vx, width, index, order.length)
      if (next !== index) {
        swipeStarted.current = performance.now()
        goTo(next)
      } else setTrack(0, true)
      return
    }
    setSlotY(0, true)
    const up = dy < -FLAG_DISTANCE || vy < -FLING
    const down = dy > FLAG_DISTANCE || vy > FLING
    if (sheetOpen) {
      if (down) setDetails(false)
      return
    }
    if (swipeDecide) {
      if (up) setFlag("pick", "set")
      else if (down) setFlag("reject", "set")
      return
    }
    // Like the phone's gallery: swipe up for the details.
    if (up) {
      setSnap(SNAPS[0])
      setDetails(true)
    }
  }

  // --- Compare -------------------------------------------------------------------------------

  /**
   * Quick compare: in a burst, this frame against the suggested best (or the runner-up when this
   * is the best), synchronized zoom starting on the main face; elsewhere this shot and the next.
   */
  const toggleCompare = () => {
    if (compare) {
      setCompare(null)
      return
    }
    if (!current) return
    const analysis = assist.analyses.get(current.id)
    const face = analysis ? eyesSummary(analysis, assist.settings.eyes).focus : undefined
    if (burstIds.length >= 2) {
      const b = comparePartner(current.id, burstIds, ranked)
      if (b) setCompare({ b, ids: burstIds, face })
      return
    }
    const other = order[index + 1] ?? order[index - 1]
    if (other) setCompare({ b: other.id, ids, face })
  }

  // --- Keys and back -------------------------------------------------------------------------

  useBackHandler(() => {
    if (compare) setCompare(null)
    else if (sheetOpen) setDetails(false)
    else if (burstMode) exitBurst()
    else onClose(current?.id)
    return true
  })

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
        if (compare) setCompare(null)
        else if (sheetOpen) setDetails(false)
        else if (burstMode) exitBurst()
        else onClose(current?.id)
      } else if (key === "arrowright") goTo(index + 1)
      else if (key === "arrowleft") goTo(index - 1)
      else if (key === "p") setFlag("pick", "toggle")
      else if (key === "x") setFlag("reject", "toggle")
      else if (key === "u") setFlag(null, "set")
      else if (/^[1-5]$/.test(key)) rate(Number(key))
      else if (key === "0" && current) folder.decide([current.id], (d) => withRating(d, 0))
      else if (key === "i") setDetails((v) => !v)
      else if (key === "c") toggleCompare()
      else if (key === "b") {
        if (burstMode) exitBurst()
        else enterBurst()
      } else if (key === "k" && burstIds.length >= 2) keepCurrent()
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
  const suggestion = suggestions.on
    ? suggestions.items.get(current.id)
    : burstSuggestions.find((s) => s.shotId === current.id)
  const jumpToFace = (face: CullFace) => {
    if (sheetOpen) setSnap(SNAPS[0])
    setFocusRequest((previous) => ({
      x: face.x,
      y: face.y,
      width: face.width,
      height: face.height,
      seq: (previous?.seq ?? 0) + 1,
      shotId: current.id,
    }))
  }
  const decision = decisionOf(folder.decisions, current.id)
  const loadingFinal = source.image && !source.image.final
  const slots = [index - 1, index, index + 1].filter((i) => i >= 0 && i < order.length)
  const analysis = assist.analyses.get(current.id)
  const isBest = Boolean(burstOfCurrent) && assist.bestShots.has(current.id)
  const status = burstIds.length >= 2 ? burstStatus(burstIds, folder.decisions) : undefined
  const acceptSuggestion = (shotIds: string[]) =>
    suggestions.accept(shotIds, suggestions.on ? undefined : burstSuggestions)
  const burstSuggestionIds = suggestion?.burst
    ? (suggestions.on ? [...suggestions.items.values()] : burstSuggestions)
        .filter((s) => s.burst === suggestion.burst)
        .map((s) => s.shotId)
    : []
  const mobileInset = sheetOpen ? Math.round(window.innerHeight * SNAPS[0]) : 0

  const detailsContent = (
    <ShotDetails
      shot={current}
      metadata={folder.metadata.get(current.id)}
      analysis={analysis}
      flags={assist.flags.get(current.id)}
      best={isBest}
      burst={burstOfCurrent}
      suggestion={suggestion}
      sensitivity={assist.settings.eyes}
      facesAvailable={assist.facesAvailable}
      image={source.image}
      zoom={zoomed ? source.zoom : undefined}
      onJumpToFace={jumpToFace}
      onAccept={suggestion ? () => acceptSuggestion([current.id]) : undefined}
      onAcceptBurst={
        burstSuggestionIds.length > 1 ? () => acceptSuggestion(burstSuggestionIds) : undefined
      }
    />
  )

  return (
    // `pointer-events-auto`: while the details sheet is open, Radix marks `body` with
    // `pointer-events: none` (a dialog layer); the photo and its controls must keep working.
    <div
      className="dark pointer-events-auto fixed inset-0 z-50 flex bg-viewer text-viewer-foreground"
      style={{ colorScheme: "dark" }}
      role="dialog"
      aria-modal="true"
      aria-label={t("cull.viewer.title", { name: current.stem })}
    >
      {compare && byId.get(compare.b) ? (
        <CompareView
          folder={folder}
          order={compare.ids.map((id) => byId.get(id)).filter((s): s is CullShot => Boolean(s))}
          assist={assist}
          a={current}
          b={byId.get(compare.b)!}
          longSide={longSide}
          focusFace={compare.face}
          onKeep={burstIds.length >= 2 ? (winner) => keepFrame(winner) : undefined}
          onB={(b) => setCompare((c) => (c ? { ...c, b } : c))}
          onClose={() => setCompare(null)}
          onExit={() => onClose(current.id)}
        />
      ) : (
        <>
          <div className="relative flex min-w-0 flex-1 flex-col">
            {/* Pager: previous, current and next shots side by side; the track moves. */}
            <div
              ref={pager}
              className="absolute inset-x-0 top-0 overflow-hidden"
              style={{ bottom: mobileInset }}
            >
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
                {burstMode ? (
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={t("cull.burstMode.exit")}
                    title={t("cull.burstMode.exit")}
                    onClick={() => exitBurst()}
                  >
                    <ArrowLeftIcon />
                  </Button>
                ) : (
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={t("common.close")}
                    onClick={() => onClose(current.id)}
                  >
                    <XIcon />
                  </Button>
                )}
                <div className="flex min-w-0 flex-col">
                  <span className="truncate text-[0.9375rem] font-medium">{current.stem}</span>
                  <span className="truncate text-xs text-viewer-foreground/70 tabular-nums">
                    {burstMode
                      ? t("cull.burstMode.position", {
                          index: index + 1,
                          total: order.length,
                        })
                      : t("cull.viewer.position", { index: index + 1, total: order.length })}
                  </span>
                </div>
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
                aria-label={t("cull.details.toggle")}
                aria-pressed={details}
                onClick={() => {
                  setSnap(SNAPS[0])
                  setDetails((v) => !v)
                }}
                className={cn(details && "text-primary")}
              >
                <InfoIcon weight={details ? "fill" : "regular"} />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                aria-label={t("cull.compare.open")}
                disabled={order.length < 2 && burstIds.length < 2}
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
                  <DropdownMenuCheckboxItem
                    checked={swipeDecide}
                    onCheckedChange={(v) => void setSwipeDecide(v)}
                  >
                    {t("cull.viewer.swipeDecide")}
                  </DropdownMenuCheckboxItem>
                  {onEditInReveal ? (
                    <>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem onSelect={() => onEditInReveal(current)}>
                        <SlidersHorizontalIcon />
                        {t("reveal.edit")}
                      </DropdownMenuItem>
                    </>
                  ) : null}
                </DropdownMenuContent>
              </DropdownMenu>
            </div>

            {chrome && !sheetOpen ? (
              <div className="pointer-events-none relative z-10 mx-3 mt-2 flex">
                <InfoChips
                  shot={current}
                  analysis={analysis}
                  flags={assist.flags.get(current.id)}
                  best={isBest}
                  burst={burstMode ? undefined : burstOfCurrent}
                  sensitivity={assist.settings.eyes}
                  onOpen={() => {
                    setSnap(SNAPS[0])
                    setDetails(true)
                  }}
                />
              </div>
            ) : null}

            <div className="flex-1" />

            {/* Bottom: burst actions, decide, then the filmstrip. */}
            <div
              className={cn(
                "relative z-10 flex flex-col bg-viewer/70 pb-[env(safe-area-inset-bottom)] backdrop-blur-sm transition-opacity duration-150",
                !chrome && "pointer-events-none opacity-0"
              )}
            >
              {burstMode ? (
                <BurstActions
                  status={status}
                  onKeep={keepCurrent}
                  onCompare={toggleCompare}
                  suggestionCount={burstSuggestions.length}
                  onAcceptSuggestion={() => acceptSuggestion(burstSuggestions.map((s) => s.shotId))}
                />
              ) : status ? (
                <FolderBurstRow status={status} onView={enterBurst} />
              ) : null}
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
                marks={
                  burstMode
                    ? (shot) => (
                        <FrameMarks
                          score={
                            assist.analyses.get(shot.id)
                              ? sharpnessScore(assist.analyses.get(shot.id)!)
                              : undefined
                          }
                          best={assist.bestShots.has(shot.id)}
                          eyesClosed={assist.flags.get(shot.id)?.includes("eyesClosed") ?? false}
                        />
                      )
                    : undefined
                }
              />
            </div>
          </div>

          {details && !isMobile ? (
            <aside
              aria-label={t("cull.details.title")}
              className="flex w-[22rem] shrink-0 flex-col overflow-y-auto border-l bg-card pt-[env(safe-area-inset-top)] text-card-foreground"
            >
              <div className="flex h-14 shrink-0 items-center justify-between border-b pr-2 pl-4">
                <h2 className="text-lg font-semibold">{t("cull.details.title")}</h2>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={t("cull.details.close")}
                  onClick={() => setDetails(false)}
                >
                  <XIcon />
                </Button>
              </div>
              {detailsContent}
            </aside>
          ) : null}
        </>
      )}

      <DrawerPrimitive.Root
        open={sheetOpen}
        onOpenChange={(open) => !open && setDetails(false)}
        modal={false}
        snapPoints={SNAPS}
        activeSnapPoint={snap}
        setActiveSnapPoint={setSnap}
      >
        <DrawerPrimitive.Portal>
          <DrawerPrimitive.Content
            aria-describedby={undefined}
            onOpenAutoFocus={(event) => event.preventDefault()}
            onInteractOutside={(event) => event.preventDefault()}
            className="dark pointer-events-auto fixed inset-x-0 bottom-0 z-[60] flex h-[100dvh] flex-col rounded-t-xl border-t bg-card text-card-foreground outline-none"
            style={{ colorScheme: "dark" }}
          >
            <div className="mx-auto mt-2 h-1.5 w-12 shrink-0 rounded-full bg-muted-foreground/40" />
            <DrawerPrimitive.Title className="sr-only">
              {t("cull.details.title")}
            </DrawerPrimitive.Title>
            <div className="shrink-0 border-b">
              <DecisionBar
                compact
                decision={decision}
                onReject={() => setFlag("reject", "toggle")}
                onPick={() => setFlag("pick", "toggle")}
                onRate={rate}
              />
            </div>
            <div
              className={cn(
                "min-h-0 flex-1 overscroll-contain pb-[env(safe-area-inset-bottom)]",
                snap === SNAPS[SNAPS.length - 1] ? "overflow-y-auto" : "overflow-hidden"
              )}
            >
              {detailsContent}
            </div>
          </DrawerPrimitive.Content>
        </DrawerPrimitive.Portal>
      </DrawerPrimitive.Root>
    </div>
  )
}

/** Burst mode, above the decision bar: Keep this one, compare with the best, the suggestion. */
function BurstActions({
  status,
  onKeep,
  onCompare,
  suggestionCount,
  onAcceptSuggestion,
}: {
  status?: ReturnType<typeof burstStatus>
  onKeep: () => void
  onCompare: () => void
  suggestionCount: number
  onAcceptSuggestion: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-2 border-b border-viewer-foreground/10 px-3 py-2">
      <Button className="h-11 flex-1 sm:flex-none" onClick={onKeep}>
        <CheckCircleIcon data-icon="inline-start" weight="fill" />
        {t("cull.burstMode.keep")}
      </Button>
      <Button
        variant="secondary"
        className="h-11"
        onClick={onCompare}
        aria-label={t("cull.burstMode.compare")}
        title={t("cull.burstMode.compare")}
      >
        <ColumnsIcon data-icon="inline-start" />
        <span className="max-sm:sr-only">{t("cull.burstMode.compare")}</span>
      </Button>
      {suggestionCount > 0 ? (
        <Button
          variant="secondary"
          className="h-11"
          onClick={onAcceptSuggestion}
          aria-label={t("cull.burstMode.acceptSuggestion")}
          title={t("cull.burstMode.acceptSuggestion")}
        >
          <SparkleIcon data-icon="inline-start" weight="fill" />
          <span className="max-sm:sr-only">{t("cull.burstMode.acceptSuggestion")}</span>
        </Button>
      ) : null}
      {status ? <BurstStatusText status={status} className="ml-auto max-sm:hidden" /> : null}
    </div>
  )
}

/** "2 of 5 kept" (resolved) or "3 of 5 decided" (pending). */
export function BurstStatusText({
  status,
  className,
}: {
  status: ReturnType<typeof burstStatus>
  className?: string
}) {
  const { t } = useTranslation()
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center gap-1 text-[0.8125rem] tabular-nums [&_svg]:size-4",
        status.resolved ? "text-primary" : "text-viewer-foreground/75",
        className
      )}
    >
      {status.resolved ? <CheckIcon weight="bold" /> : null}
      {status.resolved
        ? t("cull.burstMode.resolved", { kept: status.kept, size: status.size })
        : t("cull.burstMode.pending", { decided: status.decided, size: status.size })}
    </span>
  )
}

/** Folder mode on a burst: its status and "View burst (n)". */
function FolderBurstRow({
  status,
  onView,
}: {
  status: ReturnType<typeof burstStatus>
  onView: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-2 border-b border-viewer-foreground/10 px-3 py-2">
      <Button variant="secondary" className="h-11" onClick={onView}>
        <StackIcon data-icon="inline-start" weight="fill" />
        {t("cull.burstMode.view", { count: status.size })}
      </Button>
      <BurstStatusText status={status} className="ml-auto" />
    </div>
  )
}

/** Burst-mode filmstrip marks: crown on the suggested best, closed eyes, the sharpness number. */
function FrameMarks({
  score,
  best,
  eyesClosed,
}: {
  score?: number
  best: boolean
  eyesClosed: boolean
}) {
  const { t } = useTranslation()
  return (
    <>
      {best ? (
        <span
          role="img"
          aria-label={t("cull.assist.best")}
          className="absolute top-0.5 left-0.5 inline-flex size-4 items-center justify-center rounded-full bg-primary text-primary-foreground [&_svg]:size-3"
        >
          <CrownSimpleIcon weight="fill" />
        </span>
      ) : null}
      {eyesClosed ? (
        <span
          role="img"
          aria-label={t("cull.assist.flag.eyesClosed")}
          className="absolute top-0.5 right-0.5 inline-flex size-4 items-center justify-center rounded-full bg-warning text-viewer [&_svg]:size-3"
        >
          <EyeClosedIcon weight="bold" />
        </span>
      ) : null}
      {score !== undefined ? (
        <span className="absolute bottom-0 left-0 rounded-tr-md bg-viewer/80 px-1 text-xs leading-4 text-viewer-foreground tabular-nums">
          {score}
        </span>
      ) : null}
    </>
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
        className={cn(compact ? "size-11" : "h-11 min-w-11 gap-2 px-3")}
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
              className="inline-flex size-11 items-center justify-center rounded-md text-current opacity-90 transition-colors hover:bg-foreground/10 [&_svg]:size-6"
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
        className={cn(compact ? "size-11" : "h-11 min-w-11 gap-2 px-3")}
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
  marks,
}: {
  folder: CullFolder
  order: CullShot[]
  index: number
  onSelect: (index: number) => void
  /** Also outline this shot (compare: the second pane). */
  highlight?: string
  /** Extra marks drawn over a thumbnail (burst mode). */
  marks?: (shot: CullShot) => ReactNode
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
                {marks?.(shot)}
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
