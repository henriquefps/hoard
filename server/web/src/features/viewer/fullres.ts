import { useEffect, useState } from "react"

/**
 * Full resolution for the viewer's zoom (ADR 0018 "Full resolution"). GET /web/items/{id}/full answers the still at
 * full size: the original file when a browser shows it (X-Hoard-Full: original), else a JPEG the server makes on demand
 * (converted: a HEIF; raw: a lone RAW), 202 + Retry-After while it is being made. The viewer asks for it only once the
 * screen preview would be magnified, downloads it with progress, and swaps it in when decoded.
 */

export type FullSource = "original" | "converted" | "raw"

export type FullState =
  | { status: "idle" }
  | { status: "rendering" }
  | { status: "loading"; progress: number | null }
  | { status: "ready"; url: string; source: FullSource }
  | { status: "unavailable" }

/** Above this many pixels the viewer stays at the preview (a decoded 150 MP image is 600 MB). */
export const MAX_FULL_PIXELS = 150_000_000

/**
 * Whether the screen preview is magnified at this view: the image's width on screen in device pixels is larger than
 * the preview's own width (with 5 % of slack, so a fit view of a preview-sized photo never asks).
 */
export function previewMagnified(o: { scale: number; dpr: number; width: number; height: number; previewWidth: number }) {
  if (!o.width || !o.height || !o.previewWidth || o.width * o.height > MAX_FULL_PIXELS) return false
  if (o.previewWidth >= o.width) return false // the preview is the full size already
  return o.width * o.scale * o.dpr > o.previewWidth * 1.05
}

const sources: Record<string, FullSource> = {
  original: "original",
  converted: "converted",
  raw: "raw",
}

const sleep = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const t = window.setTimeout(resolve, ms)
    signal.addEventListener("abort", () => {
      window.clearTimeout(t)
      reject(signal.reason)
    })
  })

/** Fetches the full-resolution still, reporting each state; resolves with a blob URL (the caller revokes it). */
export async function loadFull(url: string, signal: AbortSignal, onState: (s: FullState) => void): Promise<string | null> {
  for (let attempt = 0; attempt < 120; attempt++) {
    const res = await fetch(url, { credentials: "same-origin", signal })
    if (res.status === 202) {
      onState({ status: "rendering" })
      const after = Number(res.headers.get("Retry-After")) || 2
      await res.body?.cancel()
      await sleep(Math.min(Math.max(after, 1), 10) * 1000, signal)
      continue
    }
    if (!res.ok || !res.body) {
      onState({ status: "unavailable" })
      return null
    }
    const total = Number(res.headers.get("Content-Length")) || 0
    const source = sources[res.headers.get("X-Hoard-Full") ?? ""] ?? "original"
    const reader = res.body.getReader()
    const chunks: Uint8Array<ArrayBuffer>[] = []
    let got = 0
    onState({ status: "loading", progress: total ? 0 : null })
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      chunks.push(value)
      got += value.byteLength
      onState({ status: "loading", progress: total ? Math.min(got / total, 1) : null })
    }
    const blob = new Blob(chunks, { type: res.headers.get("Content-Type") ?? "image/jpeg" })
    const objectUrl = URL.createObjectURL(blob)
    onState({ status: "ready", url: objectUrl, source })
    return objectUrl
  }
  onState({ status: "unavailable" })
  return null
}

/** The full-resolution state of one item: starts loading when `wanted` becomes true, aborts and frees on unmount. */
export function useFullRes(url: string | null, wanted: boolean): FullState {
  const [state, setState] = useState<FullState>({ status: "idle" })
  useEffect(() => {
    if (!url || !wanted) return
    const ctl = new AbortController()
    let objectUrl: string | null = null
    loadFull(url, ctl.signal, (s) => {
      if (!ctl.signal.aborted) setState(s)
    })
      .then((u) => {
        objectUrl = u
        if (ctl.signal.aborted && u) URL.revokeObjectURL(u)
      })
      .catch(() => {
        if (!ctl.signal.aborted) setState({ status: "unavailable" })
      })
    return () => {
      ctl.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [url, wanted])
  return state
}
