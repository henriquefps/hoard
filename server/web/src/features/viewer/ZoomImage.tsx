import { CircleNotchIcon } from "@phosphor-icons/react"
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { previewMagnified, useFullRes, type FullSource } from "./fullres"
import { zoomTarget } from "./zoom"

export type Layer = "thumb" | "screen" | "original"

interface ZoomImageProps {
  /** Upright size of the photo when known (the index's w/h). */
  width?: number
  height?: number
  thumb: string
  screen: string
  /** The full-resolution still (/web/items/{id}/full), loaded only when the zoom magnifies the screen preview. */
  full: string | null
  zoomed: boolean
  onZoomedChange: (zoomed: boolean) => void
  onLayer?: (layer: Layer, source?: FullSource) => void
  alt: string
  dimmed?: boolean
}

/**
 * Clears a full-resolution <img> when it leaves the page, so the browser can drop its decoded bitmap (up to 250 MB for
 * 60 MP) instead of keeping it with the element (ADR 0018 "Full resolution", measured).
 */
const releaseOnUnmount = (el: HTMLImageElement | null) => {
  if (!el) return
  return () => {
    el.removeAttribute("src")
  }
}

/**
 * The viewer's image (ADR 0018 "Viewer"): the thumbnail at once, the screen preview (2560 px) over it, then, once the
 * zoom would magnify the preview, the full-resolution still (progress shown while the server makes it or it
 * downloads), swapped in when decoded: all layers fill the same box, so nothing moves. Fit to the screen, or 100 %
 * (one image pixel per device pixel) with Z, a double click or the wheel; drag to pan. Nothing animates
 * (keyboard-driven, guide "Motion").
 */
export function ZoomImage({ width, height, thumb, screen, full, zoomed, onZoomedChange, onLayer, alt, dimmed }: ZoomImageProps) {
  const { t } = useTranslation()
  const box = useRef<HTMLDivElement>(null)
  const [cw, setCw] = useState(0)
  const [ch, setCh] = useState(0)
  const [natural, setNatural] = useState<{ w: number; h: number } | null>(null)
  const [loaded, setLoaded] = useState<Record<Layer, boolean>>({
    thumb: false,
    screen: false,
    original: false,
  })
  const [previewWidth, setPreviewWidth] = useState(0)
  const [wantFull, setWantFull] = useState(false)
  const [view, setView] = useState({ s: 0, x: 0, y: 0 })
  const lastPointer = useRef<{ x: number; y: number } | null>(null)
  const drag = useRef<{ id: number; x: number; y: number; vx: number; vy: number } | null>(null)

  const W = width || natural?.w || 0
  const H = height || natural?.h || 0

  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(() => {
      setCw(el.clientWidth)
      setCh(el.clientHeight)
    })
    ro.observe(el)
    setCw(el.clientWidth)
    setCh(el.clientHeight)
    return () => ro.disconnect()
  }, [])

  const fit = W && H && cw && ch ? Math.min(cw / W, ch / H, 1) : 0
  const dpr = window.devicePixelRatio || 1
  const full100 = 1 / dpr
  // Ask for full resolution once the preview is magnified (and keep it for this item).
  const magnified = previewMagnified({ scale: view.s, dpr, width: W, height: H, previewWidth })
  if (magnified && !wantFull && full) setWantFull(true)
  const fullState = useFullRes(full, wantFull)

  const clamp = useCallback(
    (s: number, x: number, y: number) => {
      const w = W * s
      const h = H * s
      return {
        s,
        x: w <= cw ? (cw - w) / 2 : Math.min(0, Math.max(cw - w, x)),
        y: h <= ch ? (ch - h) / 2 : Math.min(0, Math.max(ch - h, y)),
      }
    },
    [W, H, cw, ch]
  )

  // Follow the zoomed prop (Z key, buttons) and size changes.
  useEffect(() => {
    if (!fit) return
    setView((v) => {
      if (!zoomed) return clamp(fit, 0, 0)
      if (v.s > fit + 1e-6) return clamp(v.s, v.x, v.y)
      const p = lastPointer.current ?? { x: cw / 2, y: ch / 2 }
      const t = zoomTarget({ ...v, s: v.s || fit }, Math.max(full100, fit), p)
      return clamp(t.s, t.x, t.y)
    })
  }, [zoomed, fit, full100, clamp, cw, ch])

  const report = (layer: Layer, source?: FullSource) => {
    setLoaded((l) => ({ ...l, [layer]: true }))
    onLayer?.(layer, source)
  }

  const onWheel = (e: React.WheelEvent) => {
    if (!fit) return
    const rect = box.current!.getBoundingClientRect()
    const p = { x: e.clientX - rect.left, y: e.clientY - rect.top }
    const factor = Math.exp(-e.deltaY * 0.0015)
    const max = Math.max(full100 * 4, fit)
    const s = Math.min(max, Math.max(fit, view.s * factor))
    const t = zoomTarget(view, s, p)
    const next = clamp(t.s, t.x, t.y)
    setView(next)
    const z = next.s > fit + 1e-6
    if (z !== zoomed) onZoomedChange(z)
  }

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0 || view.s <= fit + 1e-6) return
    e.currentTarget.setPointerCapture(e.pointerId)
    drag.current = { id: e.pointerId, x: e.clientX, y: e.clientY, vx: view.x, vy: view.y }
  }
  const onPointerMove = (e: React.PointerEvent) => {
    const rect = box.current!.getBoundingClientRect()
    lastPointer.current = { x: e.clientX - rect.left, y: e.clientY - rect.top }
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    setView(clamp(view.s, d.vx + e.clientX - d.x, d.vy + e.clientY - d.y))
  }
  const onPointerUp = (e: React.PointerEvent) => {
    if (drag.current?.id === e.pointerId) drag.current = null
  }
  const onDoubleClick = (e: React.MouseEvent) => {
    const rect = box.current!.getBoundingClientRect()
    lastPointer.current = { x: e.clientX - rect.left, y: e.clientY - rect.top }
    onZoomedChange(!zoomed)
  }

  const w = W * view.s
  const h = H * view.s
  const layerClass = "absolute inset-0 size-full select-none"
  const fullReady = fullState.status === "ready" ? fullState : null
  // Asked (the first answer not in yet), being made on the server, downloading, or decoding.
  const busy =
    (wantFull && fullState.status === "idle") ||
    fullState.status === "rendering" ||
    fullState.status === "loading" ||
    (fullReady && !loaded.original)
  return (
    <div
      ref={box}
      className={`relative size-full touch-none overflow-hidden ${view.s > fit + 1e-6 ? "cursor-grab active:cursor-grabbing" : "cursor-zoom-in"}`}
      onWheel={onWheel}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      onDoubleClick={onDoubleClick}
      data-testid="zoom-image"
      data-scale={view.s.toFixed(4)}
      data-full={fullReady && loaded.original ? "shown" : fullState.status}
    >
      {(W === 0 || H === 0) && (
        // Size unknown until the preview loads: show it fitted to learn it.
        <img
          src={screen}
          alt=""
          className="absolute inset-0 size-full object-contain opacity-0"
          onLoad={(e) => setNatural({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })}
        />
      )}
      {W > 0 && H > 0 && view.s > 0 && (
        <div
          role="img"
          aria-label={alt}
          className={dimmed ? "opacity-40" : undefined}
          style={{
            position: "absolute",
            width: w,
            height: h,
            transform: `translate(${view.x}px, ${view.y}px)`,
          }}
        >
          <img src={thumb} alt="" draggable={false} className={layerClass} onLoad={() => report("thumb")} />
          <img
            src={screen}
            alt=""
            draggable={false}
            className={layerClass}
            style={{ visibility: loaded.screen ? "visible" : "hidden" }}
            onLoad={(e) => {
              setPreviewWidth(e.currentTarget.naturalWidth)
              report("screen")
            }}
          />
          {fullReady && (
            <img
              src={fullReady.url}
              alt=""
              draggable={false}
              decoding="async"
              data-testid="full-image"
              ref={releaseOnUnmount}
              className={layerClass}
              style={{ visibility: loaded.original ? "visible" : "hidden" }}
              onLoad={() => report("original", fullReady.source)}
            />
          )}
        </div>
      )}
      {busy && (
        <div
          role="status"
          className="pointer-events-none absolute right-3 bottom-3 flex items-center gap-2 rounded-full bg-viewer/80 px-3 py-1.5 text-[0.8125rem] text-viewer-foreground tabular-nums"
        >
          <CircleNotchIcon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
          {fullState.status === "rendering"
            ? t("viewer.full.rendering")
            : fullState.status === "loading" && fullState.progress !== null
              ? t("viewer.full.loadingPercent", { percent: Math.round(fullState.progress * 100) })
              : t("viewer.full.loading")}
        </div>
      )}
    </div>
  )
}
