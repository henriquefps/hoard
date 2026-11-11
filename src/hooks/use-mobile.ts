import { useSyncExternalStore } from "react"

const MOBILE_BREAKPOINT = 768
const QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px)`

const subscribe = (onChange: () => void) => {
  const mql = window.matchMedia(QUERY)
  mql.addEventListener("change", onChange)
  return () => mql.removeEventListener("change", onChange)
}

/** True below 768px. Read synchronously, so phones never render one desktop frame first. */
export function useIsMobile() {
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(QUERY).matches,
    () => false
  )
}

/** Short screens: the `short` Tailwind variant (src/index.css), e.g. a phone in landscape. */
export const SHORT_QUERY = "(max-height: 500px)"

const subscribeShort = (onChange: () => void) => {
  const mql = window.matchMedia(SHORT_QUERY)
  mql.addEventListener("change", onChange)
  return () => mql.removeEventListener("change", onChange)
}

/** True when the viewport is at most 500px tall (landscape phones): sidebar rail, no app bar. */
export function useIsShort() {
  return useSyncExternalStore(
    subscribeShort,
    () => window.matchMedia(SHORT_QUERY).matches,
    () => false
  )
}
