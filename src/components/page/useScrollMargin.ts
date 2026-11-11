import { type RefObject, useLayoutEffect, useState } from "react"

/**
 * Where a virtual list starts inside its `PageScroll` (below the page header, the toolbar, a table
 * header): the virtualizer's `scrollMargin`. Re-measured whenever one of those changes size.
 */
export function useScrollMargin(ref: RefObject<HTMLElement | null>, active: boolean) {
  const [margin, setMargin] = useState(0)
  useLayoutEffect(() => {
    const element = ref.current
    const scroller = element?.closest<HTMLElement>("[data-page-scroll]")
    if (!element || !scroller || !active) return
    const update = () => setMargin(element.offsetTop)
    update()
    const observer = new ResizeObserver(update)
    for (const child of scroller.children) if (child !== element) observer.observe(child)
    return () => observer.disconnect()
  }, [ref, active])
  return margin
}
