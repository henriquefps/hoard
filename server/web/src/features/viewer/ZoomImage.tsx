import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import { zoomTarget } from "./zoom"

export type Layer = "thumb" | "screen" | "original"

interface ZoomImageProps {
  /** Upright size of the photo when known (the index's w/h). */
  width?: number
  height?: number
  thumb: string
  screen: string
  /** The original, when the browser can show it (a JPEG); loaded after the screen preview. */
  original: string | null
  zoomed: boolean
  onZoomedChange: (zoomed: boolean) => void
  onLayer?: (layer: Layer) => void
  alt: string
  dimmed?: boolean
}

/**
 * The viewer's image (ADR 0018 "Viewer"): the thumbnail at once, the screen preview (2560 px) over it, then the
 * original. Fit to the screen, or 100 % (one image pixel per device pixel) with Z, a double click or the wheel; drag to
 * pan. Nothing animates (keyboard-driven, guide "Motion").
 */
export function ZoomImage({ width, height, thumb, screen, original, zoomed, onZoomedChange, onLayer, alt, dimmed }: ZoomImageProps) {
  const box = useRef<HTMLDivElement>(null)
  const [cw, setCw] = useState(0)
  const [ch, setCh] = useState(0)
  const [natural, setNatural] = useState<{ w: number; h: number } | null>(null)
  const [loaded, setLoaded] = useState<Record<Layer, boolean>>({ thumb: false, screen: false, original: false })
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
  const full = 1 / (window.devicePixelRatio || 1)

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
      const t = zoomTarget({ ...v, s: v.s || fit }, Math.max(full, fit), p)
      return clamp(t.s, t.x, t.y)
    })
  }, [zoomed, fit, full, clamp, cw, ch])

  const report = (layer: Layer) => {
    setLoaded((l) => ({ ...l, [layer]: true }))
    onLayer?.(layer)
  }

  const onWheel = (e: React.WheelEvent) => {
    if (!fit) return
    const rect = box.current!.getBoundingClientRect()
    const p = { x: e.clientX - rect.left, y: e.clientY - rect.top }
    const factor = Math.exp(-e.deltaY * 0.0015)
    const max = Math.max(full * 4, fit)
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
          style={{ position: "absolute", width: w, height: h, transform: `translate(${view.x}px, ${view.y}px)` }}
        >
          <img src={thumb} alt="" draggable={false} className={layerClass} onLoad={() => report("thumb")} />
          <img
            src={screen}
            alt=""
            draggable={false}
            className={layerClass}
            style={{ visibility: loaded.screen ? "visible" : "hidden" }}
            onLoad={() => report("screen")}
          />
          {original && loaded.screen && (
            <img
              src={original}
              alt=""
              draggable={false}
              decoding="async"
              className={layerClass}
              style={{ visibility: loaded.original ? "visible" : "hidden" }}
              onLoad={() => report("original")}
            />
          )}
        </div>
      )}
    </div>
  )
}
