import type { AnyRouter, ParsedLocation } from "@tanstack/react-router"
import { getElementScrollRestorationEntry } from "@tanstack/router-core"

/**
 * Scroll restoration for the whole app. TanStack Router keeps one saved offset per scroll container
 * per history entry (`scrollRestoration: true`, containers marked `data-scroll-restoration-id`),
 * but it applies the offset once, right after the route renders: too early for lists that load
 * asynchronously (a folder over SMB) and for virtualized lists whose rows are still estimates.
 * `startScrollRestoration` finishes the job: it re-applies the saved offset until the content is
 * tall enough, stops as soon as the user touches the screen, and can place virtual lists by item
 * (a file path, a shot id) instead of by pixels so a list that changed still lands on the right
 * row. New navigation (a fresh history entry) starts at the top; back/forward restore.
 */

/** The scroll containers of the app, as `data-scroll-restoration-id` values. */
export const SCROLL_IDS = {
  /** The card every page scrolls in (home, machines, favorites, transfers, settings…). */
  shell: "shell",
  browseList: "browse-list",
  browseShares: "browse-shares",
  cullGrid: "cull-grid",
} as const
export type ScrollId = (typeof SCROLL_IDS)[keyof typeof SCROLL_IDS]

const ALL_IDS: readonly ScrollId[] = Object.values(SCROLL_IDS)

/**
 * Containers whose element only exists once its content is loaded: restore at once and clamp.
 * The others (the shell) wait until the page has grown tall enough.
 */
const EAGER: ReadonlySet<ScrollId> = new Set([
  SCROLL_IDS.browseList,
  SCROLL_IDS.browseShares,
  SCROLL_IDS.cullGrid,
])

export const scrollSelector = (id: ScrollId) => `[data-scroll-restoration-id="${id}"]`

/** One saved offset per history entry (not per URL): opening a folder again starts at the top. */
export const scrollKey = (location: Pick<ParsedLocation, "href" | "state">): string =>
  location.state.__TSR_key || location.href

/** The offset to apply: the saved one, kept inside what the content can currently scroll to. */
export function clampScroll(target: number, scrollHeight: number, clientHeight: number): number {
  const max = Math.max(0, scrollHeight - clientHeight)
  return Math.min(Math.max(0, target), max)
}

/** True when `target` can be shown as it is (the content is at least that tall). */
export const isReachable = (target: number, scrollHeight: number, clientHeight: number) =>
  target <= Math.max(0, scrollHeight - clientHeight) + 1

/** Which item of a virtual list sits at the top edge, and how far into it the list is scrolled. */
export interface ScrollAnchor {
  /** A stable identity (file path, shot id). */
  id: string
  /** Pixels between the top of that item and the top edge of the viewport. */
  offset: number
}

export interface ScrollAnchorAdapter {
  /** At the moment of leaving the page: the first visible item. */
  capture(element: HTMLElement): ScrollAnchor | undefined
  /** Back on the page: the scrollTop that puts the item at the same place; undefined if it is gone. */
  resolve(element: HTMLElement, anchor: ScrollAnchor): number | undefined
}

const adapters = new Map<ScrollId, ScrollAnchorAdapter>()
const anchors = new Map<string, ScrollAnchor>()
const anchorKey = (key: string, id: ScrollId) => `${key}|${id}`

/** A virtual list tells how to find its first visible item and how to scroll back to one. */
export function registerScrollAnchors(id: ScrollId, adapter: ScrollAnchorAdapter): () => void {
  adapters.set(id, adapter)
  return () => {
    if (adapters.get(id) === adapter) adapters.delete(id)
  }
}

/**
 * The offsets this module wrote, per container: the page header (components/page) treats such a
 * scroll as a jump, not as the user scrolling up, so a restored list keeps its header collapsed.
 */
const written = new WeakMap<Element, number>()

function writeScroll(element: HTMLElement, top: number) {
  written.set(element, top)
  element.scrollTop = top
}

/** Containers a restore job is still working on (until it settles or the user touches the screen). */
const restoring = new Set<string>()

/**
 * True while scroll restoration is placing `element` (every scroll then is the job's, or the list
 * settling under it), or while it still sits where the job put it.
 */
export function isRestoredScroll(element: Element): boolean {
  const id = element.getAttribute("data-scroll-restoration-id")
  if (id && restoring.has(id)) return true
  const top = written.get(element)
  if (top === undefined) return false
  if (Math.abs(element.scrollTop - top) <= 1) return true
  written.delete(element)
  return false
}

/** Test hook. */
export function resetScrollAnchors() {
  adapters.clear()
  anchors.clear()
}

const GRACE_MS = 5000
const SETTLE_FRAMES = 24
const USER_EVENTS = ["pointerdown", "touchstart", "wheel", "keydown"] as const

let cancelJob: (() => void) | undefined

/** Wire the router: remember anchors on leaving a page, finish restoring on arriving at one. */
export function startScrollRestoration(router: AnyRouter): () => void {
  const offBefore = router.subscribe("onBeforeLoad", (event) => {
    if (!event.fromLocation) return
    const key = scrollKey(event.fromLocation)
    for (const [id, adapter] of adapters) {
      const element = document.querySelector<HTMLElement>(scrollSelector(id))
      const anchor = element && adapter.capture(element)
      if (anchor) anchors.set(anchorKey(key, id), anchor)
      else anchors.delete(anchorKey(key, id))
    }
  })
  const offRendered = router.subscribe("onRendered", (event) => {
    cancelJob?.()
    cancelJob = restore(router, scrollKey(event.toLocation))
  })
  return () => {
    offBefore()
    offRendered()
    cancelJob?.()
  }
}

function restore(router: AnyRouter, key: string): () => void {
  type Pending = { id: ScrollId; top: number; anchor?: ScrollAnchor; since: number; stable: number }
  const pending: Pending[] = []
  for (const id of ALL_IDS) {
    const entry = getElementScrollRestorationEntry(router, { id, getKey: scrollKey })
    const anchor = anchors.get(anchorKey(key, id))
    if (entry || anchor) {
      pending.push({ id, top: entry?.scrollY ?? 0, anchor, since: performance.now(), stable: 0 })
    } else {
      // A fresh entry starts at the top, also in a container the previous page left scrolled.
      const element = document.querySelector<HTMLElement>(scrollSelector(id))
      if (element && element.scrollTop !== 0) writeScroll(element, 0)
    }
  }
  if (pending.length === 0) return () => {}
  for (const job of pending) restoring.add(job.id)

  let frame = 0
  let stopped = false
  const stop = () => {
    stopped = true
    for (const job of pending) restoring.delete(job.id)
    cancelAnimationFrame(frame)
    for (const name of USER_EVENTS) document.removeEventListener(name, stop, true)
  }
  for (const name of USER_EVENTS)
    document.addEventListener(name, stop, { capture: true, passive: true })

  const step = () => {
    if (stopped) return
    const now = performance.now()
    for (let i = pending.length - 1; i >= 0; i--) {
      const job = pending[i]!
      const element = document.querySelector<HTMLElement>(scrollSelector(job.id))
      if (element) {
        const adapter = adapters.get(job.id)
        const wanted = (job.anchor && adapter?.resolve(element, job.anchor)) ?? job.top
        const { scrollHeight, clientHeight } = element
        const reachable = isReachable(wanted, scrollHeight, clientHeight)
        const expired = now - job.since > GRACE_MS
        if (reachable || expired || EAGER.has(job.id)) {
          const next = clampScroll(wanted, scrollHeight, clientHeight)
          if (Math.abs(element.scrollTop - next) > 0.5) {
            writeScroll(element, next)
            job.stable = 0
          } else if (++job.stable >= SETTLE_FRAMES) {
            restoring.delete(job.id)
            pending.splice(i, 1)
          }
        }
      } else if (now - job.since > GRACE_MS * 2) {
        restoring.delete(job.id)
        pending.splice(i, 1)
      }
    }
    if (pending.length === 0) stop()
    else frame = requestAnimationFrame(step)
  }
  step()
  return stop
}
