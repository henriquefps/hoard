import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { Spinner } from "@/components/ui/spinner"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type {
  CullImageResult,
  CullMetadata,
  CullShot,
  CullZoomResult,
} from "@/plugins/smb/definitions"
import {
  cachedScreen,
  cachedZoom,
  cullTimings,
  loadScreen,
  loadZoom,
  peekThumbnail,
  shotKey,
  tiles as tileQueue,
} from "./images"
import { sampleForScale, type Tile, tilesFor } from "./model"
import type { CullFolder } from "./useCullFolder"
import type { SharedView, ViewLink } from "./viewLink"

/** Image top-left offset (CSS px, in the container) and scale (CSS px per full-image px). */
interface View {
  s: number
  x: number
  y: number
}

export type SwipePhase = "move" | "end" | "cancel"

export interface ZoomableImageHandle {
  zoomed: boolean
}

const DOUBLE_TAP_MS = 280
const TAP_SLOP = 10
/** Tiles start when the screen image would be stretched more than this. */
const TILE_THRESHOLD = 1.15

/**
 * One shot, fitted to its box, zoomable to 100% and beyond (pinch, double tap, wheel), panned with
 * a finger. Shows the fast screen image (the RAW's preview), swaps in the final one when ready,
 * and above the screen image's resolution decodes 512 px region tiles of the 100% image
 * (`cullTile`), center first. At fit, one-finger drags are handed to `onSwipe` (the pager: next /
 * previous, pick / reject). Gestures write transforms directly (no React render per move); the
 * view is committed to React when a gesture ends, which re-lays out the image crisply and loads
 * tiles. With a `link`, two panes keep the same zoom and image point (compare).
 */
export function ZoomableImage({
  folder,
  shot,
  metadata,
  longSide,
  active,
  link,
  paneId = "main",
  onSwipe,
  onTap,
  onZoomedChange,
  onSource,
  focusRequest,
  className,
}: {
  folder: CullFolder
  shot: CullShot
  metadata?: CullMetadata
  longSide: number
  /** The shot on screen: loads the final image and 100% info, takes gestures, loads tiles. */
  active: boolean
  link?: ViewLink
  paneId?: string
  onSwipe?: (phase: SwipePhase, dx: number, dy: number, vx: number, vy: number) => void
  onTap?: () => void
  onZoomedChange?: (zoomed: boolean) => void
  onSource?: (image: CullImageResult | undefined, zoom: CullZoomResult | undefined) => void
  /** Zoom to this region (0..1 of the upright image, e.g. a face); `seq` changes for every request. */
  focusRequest?: { x: number; y: number; width: number; height: number; seq: number }
  className?: string
}) {
  const { t } = useTranslation()
  const box = useRef<HTMLDivElement>(null)
  const layer = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })
  const [image, setImage] = useState<CullImageResult | undefined>(() =>
    cachedScreen(folder, shot, longSide)
  )
  const [failed, setFailed] = useState(false)
  const [zoom, setZoom] = useState<CullZoomResult | undefined>(() => cachedZoom(folder, shot))
  const [zoomPending, setZoomPending] = useState(false)
  const [committed, setCommitted] = useState<View | null>(null)
  const [loadedTiles, setLoadedTiles] = useState<Map<string, Tile & { src: string }>>(new Map())
  const view = useRef<View | null>(null)
  const committedRef = useRef<View | null>(null)
  const dpr = typeof window === "undefined" ? 1 : window.devicePixelRatio || 1
  const placeholder = peekThumbnail(folder, shot)

  // Full image size: the 100% image once known, else EXIF, else the screen image's own.
  const fullW = zoom?.width ?? metadata?.width ?? image?.width ?? 0
  const fullH = zoom?.height ?? metadata?.height ?? image?.height ?? 0
  const sizeRef = useRef({ W: fullW, H: fullH, cw: 0, ch: 0 })
  // Declared first: every later layout effect and gesture handler reads the current sizes.
  useLayoutEffect(() => {
    sizeRef.current = { W: fullW, H: fullH, cw: size.w, ch: size.h }
  })

  // --- Loading -------------------------------------------------------------------------------

  // Callers key this component by shot, so state starts fresh for every shot.
  useEffect(() => {
    let cancelled = false
    const cached = cachedScreen(folder, shot, longSide)
    if (cached?.final) return
    void (async () => {
      try {
        const fast = cached ?? (await loadScreen(folder, shot, longSide, "fast", !active))
        if (cancelled) return
        setImage(fast)
        if (fast.final) return
        const final = await loadScreen(folder, shot, longSide, "final", !active)
        if (!cancelled) setImage(final)
      } catch {
        if (!cancelled) setFailed((current) => current || !cachedScreen(folder, shot, longSide))
      }
    })()
    return () => {
      cancelled = true
    }
    // The folder object changes on every decision; its key and `call` are what matter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder.key, shot, longSide, active])

  // The 100% source is prepared as soon as the shot is on screen (LibRaw takes ~1 s for a RAW-only
  // shot), so a double tap finds it ready.
  useEffect(() => {
    if (!active || !image?.final || zoom) return
    let cancelled = false
    void loadZoom(folder, shot).then(
      (result) => !cancelled && setZoom(result),
      () => {}
    )
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, image?.final, zoom, folder.key, shot])

  useEffect(() => {
    onSource?.(image, zoom)
  }, [image, zoom, onSource])

  // --- Geometry ------------------------------------------------------------------------------

  useLayoutEffect(() => {
    const element = box.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => {
      setSize({ w: entry.contentRect.width, h: entry.contentRect.height })
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const fitScale = useCallback(() => {
    const { W, H, cw, ch } = sizeRef.current
    return W > 0 && H > 0 && cw > 0 && ch > 0 ? Math.min(cw / W, ch / H) : 1
  }, [])

  const fitView = useCallback((): View => {
    const { W, H, cw, ch } = sizeRef.current
    const s = fitScale()
    return { s, x: (cw - W * s) / 2, y: (ch - H * s) / 2 }
  }, [fitScale])

  const maxScale = useCallback(() => Math.max(2 / dpr, fitScale() * 3), [dpr, fitScale])

  /** Keeps the image covering the box (or centered when smaller), optionally with rubber band. */
  const clamp = useCallback((v: View, rubber = 0): View => {
    const { W, H, cw, ch } = sizeRef.current
    const axis = (offset: number, content: number, container: number) => {
      if (content <= container) return (container - content) / 2
      const min = container - content
      if (offset > 0) return rubber ? offset * rubber : 0
      if (offset < min) return rubber ? min + (offset - min) * rubber : min
      return offset
    }
    return { s: v.s, x: axis(v.x, W * v.s, cw), y: axis(v.y, H * v.s, ch) }
  }, [])

  const isZoomed = (v: View | null) => Boolean(v && v.s > fitScale() * 1.02)

  const shared = useCallback(
    (v: View): SharedView => {
      const { W, H, cw, ch } = sizeRef.current
      return {
        fit: !isZoomed(v),
        zoom: v.s * dpr,
        cx: W ? (cw / 2 - v.x) / (W * v.s) : 0.5,
        cy: H ? (ch / 2 - v.y) / (H * v.s) : 0.5,
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dpr, fitScale]
  )

  const fromShared = useCallback(
    (v: SharedView): View => {
      if (v.fit) return fitView()
      const { W, H, cw, ch } = sizeRef.current
      const s = v.zoom / dpr
      return clamp({ s, x: cw / 2 - v.cx * W * s, y: ch / 2 - v.cy * H * s })
    },
    [clamp, dpr, fitView]
  )

  /** Writes a view to the DOM (transform only), relative to the committed layout. */
  const paint = useCallback((v: View, animate = false) => {
    view.current = v
    const element = layer.current
    const base = committedRef.current
    if (!element || !base) return
    element.style.transition = animate ? "transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1)" : "none"
    element.style.transform = `translate3d(${v.x}px, ${v.y}px, 0) scale(${v.s / base.s})`
  }, [])

  const zoomedRef = useRef(false)
  /** Makes a view the layout (crisp re-layout, tiles). */
  const commit = useCallback(
    (v: View) => {
      view.current = v
      committedRef.current = v
      setCommitted(v)
      const zoomed = isZoomed(v)
      if (zoomed !== zoomedRef.current) {
        zoomedRef.current = zoomed
        onZoomedChange?.(zoomed)
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [onZoomedChange, fitScale]
  )

  // Fit when the box or the image size changes (keep the zoom when only the size estimate changed).
  const lastSizes = useRef({ W: 0, H: 0, cw: 0, ch: 0 })
  useLayoutEffect(() => {
    if (!fullW || !fullH || !size.w || !size.h) return
    const previous = lastSizes.current
    lastSizes.current = { W: fullW, H: fullH, cw: size.w, ch: size.h }
    const current = view.current
    if (
      current &&
      isZoomed(current) &&
      previous.cw === size.w &&
      previous.ch === size.h &&
      previous.W
    ) {
      // Same box, refined image size (EXIF → 100% image): keep the same CSS size.
      commit(clamp({ s: (current.s * previous.W) / fullW, x: current.x, y: current.y }))
      return
    }
    const fitted = link?.last && !link.last.fit ? fromShared(link.last) : fitView()
    commit(fitted)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fullW, fullH, size.w, size.h])

  // After a commit, React lays out at the new scale: reset the transform to translation only.
  useLayoutEffect(() => {
    if (committed) paint(committed)
  }, [committed, paint])

  // Compare: follow the other pane.
  useEffect(() => {
    if (!link) return
    return link.subscribe(paneId, (shared, settled) => {
      const v = fromShared(shared)
      if (settled) commit(v)
      else paint(v)
    })
  }, [link, paneId, fromShared, commit, paint])

  const publish = useCallback(
    (v: View, settled: boolean) => link?.publish(paneId, shared(v), settled),
    [link, paneId, shared]
  )

  // Leaving the screen resets the zoom.
  useEffect(() => {
    if (!active && view.current && isZoomed(view.current)) commit(fitView())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  /** Animate to `v` (clamped), then commit it. */
  const settle = useCallback(
    (v: View) => {
      const target = clamp({ ...v, s: Math.min(Math.max(v.s, fitScale()), maxScale()) })
      const done = () => {
        commit(target)
        publish(target, true)
      }
      paint(target, true)
      publish(target, false)
      window.setTimeout(done, 190)
    },
    [clamp, commit, fitScale, maxScale, paint, publish]
  )

  /** Zoom to scale `s` keeping the image point under (px, py) in place. */
  const zoomAt = useCallback((from: View, s: number, px: number, py: number): View => {
    const ix = (px - from.x) / from.s
    const iy = (py - from.y) / from.s
    return { s, x: px - ix * s, y: py - iy * s }
  }, [])

  /** Double tap: fit ↔ 100% (or 2.5× fit when 100% is barely larger than fit). */
  const toggleZoom = useCallback(
    (px: number, py: number) => {
      const current = view.current ?? fitView()
      if (isZoomed(current)) {
        settle(fitView())
        return
      }
      const fit = fitScale()
      const hundred = 1 / dpr
      const target = hundred > fit * 1.3 ? hundred : fit * 2.5
      const started = performance.now()
      if (!zoom) {
        setZoomPending(true)
        void loadZoom(folder, shot)
          .then(setZoom, () => {})
          .finally(() => {
            setZoomPending(false)
            cullTimings.record("doubleTapToZoomReady", performance.now() - started)
          })
      }
      settle(zoomAt(current, target, px, py))
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dpr, fitScale, fitView, settle, zoomAt, zoom, folder.key, shot]
  )

  // Jump to a face (ADR 0009): the region about 40% of the box, centred, never past the max zoom.
  useEffect(() => {
    if (!focusRequest || !active) return
    const { W, H, cw, ch } = sizeRef.current
    if (!W || !H || !cw || !ch) return
    const fw = Math.max(1, focusRequest.width * W)
    const fh = Math.max(1, focusRequest.height * H)
    const s = Math.min(
      maxScale(),
      Math.max(fitScale() * 1.5, Math.min(cw / (fw * 2.5), ch / (fh * 2.5)))
    )
    const cx = (focusRequest.x + focusRequest.width / 2) * W
    const cy = (focusRequest.y + focusRequest.height / 2) * H
    if (!zoom) void loadZoom(folder, shot).then(setZoom, () => {})
    settle({ s, x: cw / 2 - cx * s, y: ch / 2 - cy * s })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusRequest?.seq])

  // --- Gestures ------------------------------------------------------------------------------

  const gesture = useRef<{
    pointers: Map<number, { x: number; y: number }>
    start: { x: number; y: number; t: number }
    startView: View
    mode: "none" | "pan" | "swipe" | "pinch"
    pinch?: { distance: number; mid: { x: number; y: number }; view: View }
    moved: boolean
    last: { x: number; y: number; t: number }
    velocity: { x: number; y: number }
  } | null>(null)
  const lastTap = useRef<{ x: number; y: number; t: number } | null>(null)
  const tapTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(tapTimer.current), [])

  const local = (event: { clientX: number; clientY: number }) => {
    const rect = box.current!.getBoundingClientRect()
    return { x: event.clientX - rect.left, y: event.clientY - rect.top }
  }

  const onPointerDown = (event: React.PointerEvent) => {
    if (!active || event.button > 0) return
    box.current?.setPointerCapture(event.pointerId)
    const point = local(event)
    const current = view.current ?? fitView()
    const g = gesture.current
    if (!g || g.pointers.size === 0) {
      gesture.current = {
        pointers: new Map([[event.pointerId, point]]),
        start: { ...point, t: performance.now() },
        startView: current,
        mode: "none",
        moved: false,
        last: { ...point, t: performance.now() },
        velocity: { x: 0, y: 0 },
      }
      return
    }
    g.pointers.set(event.pointerId, point)
    if (g.pointers.size === 2) {
      if (g.mode === "swipe") onSwipe?.("cancel", 0, 0, 0, 0)
      const [a, b] = [...g.pointers.values()]
      g.mode = "pinch"
      g.moved = true
      g.pinch = {
        distance: Math.hypot(a.x - b.x, a.y - b.y) || 1,
        mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
        view: view.current ?? current,
      }
    }
  }

  const onPointerMove = (event: React.PointerEvent) => {
    const g = gesture.current
    if (!g || !g.pointers.has(event.pointerId)) return
    const point = local(event)
    g.pointers.set(event.pointerId, point)
    const now = performance.now()
    const dt = Math.max(1, now - g.last.t)
    g.velocity = { x: (point.x - g.last.x) / dt, y: (point.y - g.last.y) / dt }
    g.last = { ...point, t: now }
    if (g.mode === "pinch" && g.pinch && g.pointers.size >= 2) {
      const [a, b] = [...g.pointers.values()]
      const distance = Math.hypot(a.x - b.x, a.y - b.y)
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
      const fit = fitScale()
      const s = Math.min(
        maxScale() * 1.3,
        Math.max(fit * 0.6, (g.pinch.view.s * distance) / g.pinch.distance)
      )
      const zoomed = zoomAt(g.pinch.view, s, g.pinch.mid.x, g.pinch.mid.y)
      const next = { s, x: zoomed.x + mid.x - g.pinch.mid.x, y: zoomed.y + mid.y - g.pinch.mid.y }
      paint(next)
      publish(next, false)
      if (!zoom && s > fit * 1.05) void loadZoom(folder, shot).then(setZoom, () => {})
      return
    }
    const dx = point.x - g.start.x
    const dy = point.y - g.start.y
    if (!g.moved && Math.hypot(dx, dy) < TAP_SLOP) return
    if (!g.moved) {
      g.moved = true
      g.mode = isZoomed(g.startView) ? "pan" : "swipe"
    }
    if (g.mode === "pan") {
      const next = clamp({ s: g.startView.s, x: g.startView.x + dx, y: g.startView.y + dy }, 0.35)
      paint(next)
      publish(next, false)
    } else if (g.mode === "swipe") {
      onSwipe?.("move", dx, dy, g.velocity.x, g.velocity.y)
    }
  }

  const onPointerUp = (event: React.PointerEvent) => {
    const g = gesture.current
    if (!g || !g.pointers.has(event.pointerId)) return
    g.pointers.delete(event.pointerId)
    if (g.mode === "pinch") {
      if (g.pointers.size === 0) {
        gesture.current = null
        settle(view.current ?? g.startView)
      }
      return
    }
    gesture.current = null
    const point = local(event)
    if (event.type === "pointercancel") {
      if (g.mode === "swipe") onSwipe?.("cancel", 0, 0, 0, 0)
      else if (g.mode === "pan") settle(view.current ?? g.startView)
      return
    }
    if (g.mode === "swipe") {
      onSwipe?.("end", point.x - g.start.x, point.y - g.start.y, g.velocity.x, g.velocity.y)
      return
    }
    if (g.mode === "pan") {
      // A short glide in the direction of the fling, then clamp.
      const v = view.current ?? g.startView
      settle({ s: v.s, x: v.x + g.velocity.x * 120, y: v.y + g.velocity.y * 120 })
      return
    }
    // A tap: a second one soon after is a double tap (zoom), else it toggles the chrome.
    const now = performance.now()
    const previous = lastTap.current
    if (
      previous &&
      now - previous.t < DOUBLE_TAP_MS &&
      Math.hypot(point.x - previous.x, point.y - previous.y) < 40
    ) {
      clearTimeout(tapTimer.current)
      lastTap.current = null
      toggleZoom(point.x, point.y)
      return
    }
    lastTap.current = { ...point, t: now }
    clearTimeout(tapTimer.current)
    tapTimer.current = setTimeout(() => {
      lastTap.current = null
      onTap?.()
    }, DOUBLE_TAP_MS)
  }

  const wheelTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => {
    const element = box.current
    if (!element || !active) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const current = view.current ?? fitView()
      const point = local(event)
      const s = Math.min(
        maxScale(),
        Math.max(fitScale(), current.s * Math.exp(-event.deltaY * 0.0025))
      )
      const next = clamp(zoomAt(current, s, point.x, point.y))
      paint(next)
      publish(next, false)
      if (!zoom && s > fitScale() * 1.05) void loadZoom(folder, shot).then(setZoom, () => {})
      clearTimeout(wheelTimer.current)
      wheelTimer.current = setTimeout(() => {
        commit(next)
        publish(next, true)
      }, 140)
    }
    element.addEventListener("wheel", onWheel, { passive: false })
    return () => {
      element.removeEventListener("wheel", onWheel)
      clearTimeout(wheelTimer.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, zoom, clamp, commit, fitScale, fitView, maxScale, paint, publish, zoomAt])

  // --- Tiles ---------------------------------------------------------------------------------

  const shotTilesKey = shotKey(folder, shot)

  const wanted = (() => {
    if (!active || !committed || !zoom || !image || !size.w) return [] as Tile[]
    const devicePerImage = committed.s * dpr
    const baseResolution = image.width / zoom.width
    if (devicePerImage <= baseResolution * TILE_THRESHOLD) return []
    const visible = {
      x: -committed.x / committed.s,
      y: -committed.y / committed.s,
      width: size.w / committed.s,
      height: size.h / committed.s,
    }
    return tilesFor(visible, zoom.width, zoom.height, sampleForScale(devicePerImage))
  })()
  const wantedKey = wanted.map((t) => t.key).join(",")

  useEffect(() => {
    if (wanted.length === 0) return
    const controller = new AbortController()
    const started = performance.now()
    let first = true
    for (const tile of wanted) {
      const key = `${shotTilesKey}|${tile.key}`
      if (tileQueue.peek(key)) continue
      void tileQueue
        .get(
          key,
          () =>
            folder
              .call((ref) =>
                HoardSmb.cullTile({
                  ...ref,
                  shotId: shot.id,
                  x: tile.x,
                  y: tile.y,
                  width: tile.width,
                  height: tile.height,
                  sample: tile.sample,
                })
              )
              .then((r) => r.src),
          controller.signal
        )
        .then(
          (src) => {
            if (controller.signal.aborted) return
            if (first) {
              first = false
              cullTimings.record(`tile:first:s${tile.sample}`, performance.now() - started)
            }
            setLoadedTiles((current) => new Map(current).set(tile.key, { ...tile, src }))
          },
          () => {}
        )
    }
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wantedKey, shotTilesKey])

  // Tiles to draw: loaded ones that are on screen, coarse first so finer ones cover them.
  const visibleTiles = (() => {
    if (!committed || !zoom || wanted.length === 0) return []
    const wantedSample = wanted[0]?.sample
    const left = -committed.x / committed.s
    const top = -committed.y / committed.s
    const right = left + size.w / committed.s
    const bottom = top + size.h / committed.s
    // Loaded in this view, plus tiles of this shot cached from an earlier visit.
    const all = new Map(loadedTiles)
    for (const tile of wanted) {
      const src = tileQueue.peek(`${shotTilesKey}|${tile.key}`)
      if (src && !all.has(tile.key)) all.set(tile.key, { ...tile, src })
    }
    return [...all.values()]
      .filter((t) => t.x < right && t.x + t.width > left && t.y < bottom && t.y + t.height > top)
      .filter((t) => t.sample >= wantedSample)
      .sort((a, b) => b.sample - a.sample)
      .slice(-120)
  })()

  const fitNow = fullW && fullH && size.w && size.h ? Math.min(size.w / fullW, size.h / fullH) : 1
  const zoomedNow = Boolean(committed && committed.s > fitNow * 1.02)
  const rendering = zoomedNow && (zoomPending || !zoom) && active
  const s = committed?.s ?? 0

  return (
    <div
      ref={box}
      className={cn("relative size-full touch-none overflow-hidden select-none", className)}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      data-zoomed={zoomedNow || undefined}
    >
      {committed && fullW > 0 ? (
        <div
          ref={layer}
          className="absolute top-0 left-0 origin-top-left"
          style={{ width: fullW * s, height: fullH * s }}
        >
          {image ? (
            <img
              src={image.src}
              alt=""
              draggable={false}
              decoding="async"
              className="pointer-events-none absolute inset-0 size-full"
            />
          ) : placeholder ? (
            <img
              src={placeholder}
              alt=""
              draggable={false}
              className="pointer-events-none absolute inset-0 size-full"
            />
          ) : null}
          {visibleTiles.map((tile) => (
            <img
              key={tile.key}
              src={tile.src}
              alt=""
              draggable={false}
              className="pointer-events-none absolute max-w-none"
              style={{
                left: tile.x * s,
                top: tile.y * s,
                width: tile.width * s + 0.5,
                height: tile.height * s + 0.5,
              }}
            />
          ))}
        </div>
      ) : null}
      {!image && !failed ? (
        <div className="absolute inset-0 flex items-center justify-center">
          <Spinner className="size-6 text-viewer-foreground/70" />
        </div>
      ) : null}
      {failed && !image ? (
        <div className="absolute inset-0 flex items-center justify-center px-6 text-center text-sm text-viewer-foreground/70">
          {t("cull.viewer.imageFailed")}
        </div>
      ) : null}
      {rendering ? (
        <div className="pointer-events-none absolute top-1/2 left-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center gap-2 rounded-full bg-viewer/80 px-3 py-1.5 text-sm text-viewer-foreground">
          <Spinner className="size-4" />
          {shot.kind === "raw" ? t("cull.viewer.renderingRaw") : t("cull.viewer.loadingFull")}
        </div>
      ) : null}
    </div>
  )
}
