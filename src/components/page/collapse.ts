/**
 * Quick-return header (1.9.1): the page header scrolls away with the content and comes back as
 * soon as the user scrolls up, 1:1 with the finger (no animation, no snapping). The header is
 * `position: sticky` inside the scroller with `top: -collapse`, so on screen it sits at
 * `-min(scrollTop, collapse)`: in its place at the top of the content, fully hidden after scrolling
 * down by its height, back by as much as the user scrolls up.
 */

/** How far the header is pushed up (0 = fully shown, `max` = fully hidden) after a scroll. */
export function nextCollapse(previous: number, lastTop: number, top: number, max: number): number {
  const scrollTop = Math.max(0, top)
  const delta = scrollTop - Math.max(0, lastTop)
  const next = Math.min(Math.max(previous + delta, 0), Math.max(0, max))
  // Near the top the header is never hidden by more than the content has scrolled: scrolled to
  // the top it is fully expanded, and the next scroll down starts from there.
  return Math.min(next, scrollTop)
}

/** How many pixels of the header are on screen (its bottom edge, from the scroller's top). */
export const visibleHeader = (height: number, top: number, collapse: number) =>
  Math.max(0, height - Math.min(Math.max(0, top), collapse))
