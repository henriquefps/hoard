import type { AnyRouter, NavigateOptions } from "@tanstack/react-router"

// The href of every history entry by its TanStack index, so "up one level" can be a real
// history.back() when the entry before is the parent (no duplicate entries), and a replace when
// the screen was opened directly (a favorite, a deep link).
const hrefs = new Map<number, string>()

const indexOf = (state: unknown) =>
  typeof state === "object" && state !== null && "__TSR_index" in state
    ? Number((state as { __TSR_index: unknown }).__TSR_index)
    : 0

export function trackHistory(router: AnyRouter): () => void {
  const record = () => {
    const { location } = router.history
    hrefs.set(indexOf(location.state), location.href)
  }
  record()
  return router.history.subscribe(record)
}

/** Go to `parent`: back in history if that is where we came from, else replace this entry. */
export function goUp(router: AnyRouter, parent: NavigateOptions): void {
  const { location } = router.history
  const previous = hrefs.get(indexOf(location.state) - 1)
  const target = router.buildLocation(parent as Parameters<AnyRouter["buildLocation"]>[0]).href
  if (previous === target && router.history.canGoBack()) router.history.back()
  else void router.navigate({ ...parent, replace: true } as Parameters<AnyRouter["navigate"]>[0])
}

/**
 * A breadcrumb or back arrow towards a page that is already behind us in history: go back to that
 * entry (so it comes back with its scroll position) instead of pushing a new one. False when the
 * target is not in the history, then the link navigates as usual.
 */
export function goBackTo(router: AnyRouter, target: NavigateOptions): boolean {
  const current = indexOf(router.history.location.state)
  const href = router.buildLocation(target as Parameters<AnyRouter["buildLocation"]>[0]).href
  for (let index = current - 1; index >= 0; index--) {
    if (hrefs.get(index) === href) {
      router.history.go(index - current)
      return true
    }
  }
  return false
}
