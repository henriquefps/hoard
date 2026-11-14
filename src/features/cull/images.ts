/**
 * Image loading for the culling views: screen images (fast, then final), grid thumbnails and 100%
 * zoom info, cached per folder + shot so paging back and forth never asks native twice. Native
 * returns file URLs to cached JPEGs (ADR 0008); `decode()` warms them before they are shown, so a
 * swap never flashes.
 */
import { TaskQueue } from "@/lib/taskQueue"
import { HoardSmb } from "@/plugins/smb"
import type { CullImageResult, CullShot, CullZoomResult } from "@/plugins/smb/definitions"
import type { CullFolder } from "./useCullFolder"

const folderPrefix = (folder: Pick<CullFolder, "key">) =>
  `${folder.key.locationId}|${folder.key.share}|${folder.key.path}`

/** Identity of a shot's files: a changed file (size, modified time) gets new images. */
export const shotKey = (folder: Pick<CullFolder, "key">, shot: CullShot) =>
  `${folderPrefix(folder)}|${shot.id}|${shot.raw?.size ?? ""}:${shot.raw?.modifiedAt ?? ""}|${shot.rendered?.size ?? ""}:${shot.rendered?.modifiedAt ?? ""}`

/** Screen long side to ask for, in device pixels (S24 Ultra: 3120; capped for desktop screens). */
export function screenLongSide(): number {
  const dpr = window.devicePixelRatio || 1
  const side = Math.max(
    window.screen?.width ?? 0,
    window.screen?.height ?? 0,
    window.innerWidth,
    window.innerHeight
  )
  return Math.max(1024, Math.min(4096, Math.round(side * dpr)))
}

/** Warm an image in the browser's decode cache (resolves even when decoding fails). */
export async function warm(src: string): Promise<void> {
  const image = new Image()
  image.decoding = "async"
  image.src = src
  try {
    await image.decode()
  } catch {
    // Shown anyway; the <img> reports its own error.
  }
}

// --- Screen images ---------------------------------------------------------------------------

const screens = new Map<string, CullImageResult>()
const SCREEN_ENTRIES = 400

function remember(key: string, value: CullImageResult) {
  screens.set(key, value)
  if (screens.size > SCREEN_ENTRIES) screens.delete(screens.keys().next().value!)
}

const screenKey = (
  folder: CullFolder,
  shot: CullShot,
  longSide: number,
  quality: "fast" | "final"
) => `${shotKey(folder, shot)}|${longSide}|${quality}`

/** The best screen image already loaded (final, else fast), without asking native. */
export function cachedScreen(folder: CullFolder, shot: CullShot, longSide: number) {
  return (
    screens.get(screenKey(folder, shot, longSide, "final")) ??
    screens.get(screenKey(folder, shot, longSide, "fast"))
  )
}

const pending = new Map<string, { promise: Promise<CullImageResult>; background: boolean }>()

/** A screen image, decoded and ready to show. Shared by every caller asking for the same one. */
export function loadScreen(
  folder: CullFolder,
  shot: CullShot,
  longSide: number,
  quality: "fast" | "final",
  background = false
): Promise<CullImageResult> {
  const key = screenKey(folder, shot, longSide, quality)
  const cached = screens.get(key)
  if (cached) return Promise.resolve(cached)
  const running = pending.get(key)
  if (running) {
    // A neighbour came on screen: ask again in the foreground. Native joins the running job and
    // raises its priority; the first answer serves both.
    if (running.background && !background) {
      running.background = false
      void folder
        .call((ref) => HoardSmb.cullImage({ ...ref, shotId: shot.id, longSide, quality }))
        .catch(() => {})
    }
    return running.promise
  }
  {
    const started = performance.now()
    const promise = folder
      .call((ref) => HoardSmb.cullImage({ ...ref, shotId: shot.id, longSide, quality, background }))
      .then(async (result) => {
        await warm(result.src)
        remember(key, result)
        if (result.final) remember(screenKey(folder, shot, longSide, "final"), result)
        cullTimings.record(`image:${quality}`, performance.now() - started)
        return result
      })
      .finally(() => pending.delete(key))
    pending.set(key, { promise, background })
    return promise
  }
}

// --- Thumbnails ------------------------------------------------------------------------------

/** Grid and filmstrip thumbnails: 4 at a time, newest request first, cached. */
export const thumbnails = new TaskQueue<string>(4, 3000)
export const THUMB_SIZE = 320

export const thumbKey = (folder: Pick<CullFolder, "key">, shot: CullShot) =>
  `${shotKey(folder, shot)}|thumb`

export function loadThumbnail(folder: CullFolder, shot: CullShot, signal?: AbortSignal) {
  return thumbnails.get(
    thumbKey(folder, shot),
    () =>
      folder
        .call((ref) => HoardSmb.cullThumbnail({ ...ref, shotId: shot.id, maxSize: THUMB_SIZE }))
        .then((r) => r.src),
    signal
  )
}

export const peekThumbnail = (folder: CullFolder, shot: CullShot) =>
  thumbnails.peek(thumbKey(folder, shot))

/**
 * The `<img>` could not show the cached URL (its file is gone from native's cache, 1.10.1): forget
 * it and ask native again, which writes the thumbnail anew. Called once per tile; a second failure
 * goes to `thumbnailFailed`.
 */
export function reloadThumbnail(folder: CullFolder, shot: CullShot, signal?: AbortSignal) {
  const key = thumbKey(folder, shot)
  thumbnails.forget(key)
  const started = performance.now()
  return thumbnails.get(
    key,
    () =>
      folder
        .call((ref) => HoardSmb.cullThumbnail({ ...ref, shotId: shot.id, maxSize: THUMB_SIZE }))
        // Native writes it again under the same name: a fresh URL, so the WebView never answers
        // from the failed request.
        .then((r) => reloadedSrc(r.src, Date.now()))
        .then((src) => {
          cullTimings.record("thumb:reload", performance.now() - started)
          return src
        }),
    signal
  )
}

/** `src` with a cache-busting query (file URLs served by Capacitor ignore the query). */
export const reloadedSrc = (src: string, stamp: number) =>
  `${src}${src.includes("?") ? "&" : "?"}r=${stamp}`

/** The reloaded thumbnail failed to show too: the tile keeps its failed state (no more retries). */
export function thumbnailFailed(folder: CullFolder, shot: CullShot) {
  thumbnails.fail(thumbKey(folder, shot), new Error("thumbnail did not load"))
  cullTimings.record("thumb:failed", 0)
}

// --- 100% ------------------------------------------------------------------------------------

const zooms = new Map<string, Promise<CullZoomResult>>()
const zoomResults = new Map<string, CullZoomResult>()

export const cachedZoom = (folder: CullFolder, shot: CullShot) =>
  zoomResults.get(shotKey(folder, shot))

/** Prepare the 100% image (stage the JPEG, or render a RAW-only shot) and learn its size. */
export function loadZoom(folder: CullFolder, shot: CullShot): Promise<CullZoomResult> {
  const key = shotKey(folder, shot)
  let promise = zooms.get(key)
  if (!promise) {
    const started = performance.now()
    promise = folder
      .call((ref) => HoardSmb.cullZoom({ ...ref, shotId: shot.id }))
      .then(
        (result) => {
          zoomResults.set(key, result)
          cullTimings.record(`zoom:${result.source}`, performance.now() - started)
          return result
        },
        (error: unknown) => {
          zooms.delete(key)
          throw error
        }
      )
    zooms.set(key, promise)
    if (zooms.size > 200) zooms.delete(zooms.keys().next().value!)
  }
  return promise
}

/** Tiles: 3 at a time, newest first (the area the user is looking at now), cached by tile. */
export const tiles = new TaskQueue<string>(3, 1500)

// --- Timings (ADR 0008 measurements; read from the console or over CDP) -----------------------

export const cullTimings = {
  samples: new Map<string, number[]>(),
  record(name: string, ms: number) {
    const list = this.samples.get(name) ?? []
    list.push(Math.round(ms))
    if (list.length > 500) list.shift()
    this.samples.set(name, list)
    if (import.meta.env.DEV || (globalThis as { hoardCullLog?: boolean }).hoardCullLog) {
      console.info(`[cull] ${name} ${Math.round(ms)} ms`)
    }
  },
  summary() {
    return Object.fromEntries(
      [...this.samples].map(([name, list]) => {
        const sorted = [...list].sort((a, b) => a - b)
        return [
          name,
          { n: list.length, median: sorted[Math.floor(sorted.length / 2)], max: sorted.at(-1) },
        ]
      })
    )
  },
}
;(globalThis as { hoardCull?: typeof cullTimings }).hoardCull = cullTimings
