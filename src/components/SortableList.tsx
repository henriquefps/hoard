import { ArrowDownIcon, ArrowUpIcon, DotsSixVerticalIcon } from "@phosphor-icons/react"
import { useRef, type PointerEvent as ReactPointerEvent, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

export interface SortableControls {
  index: number
  /** The drag handle (pointer only; keyboard and screen readers use the move buttons). */
  handle: ReactNode
  /** Move up / move down buttons, labelled with the item's name. */
  moveButtons: ReactNode
}

/** Pure: move the item at `from` to `to`. */
export function moveItem<T>(items: readonly T[], from: number, to: number): T[] {
  const next = [...items]
  const [item] = next.splice(from, 1)
  next.splice(Math.max(0, Math.min(to, next.length)), 0, item)
  return next
}

/**
 * A vertical list reordered by a drag handle and, for keyboard and screen-reader users, by Move
 * up / Move down buttons. The drag follows references/gestures.md: 1:1 tracking with direct
 * `transform` writes (no React state per pointermove), `touch-action: none` on the handle, a
 * rubber band past the ends, and no animation when rows settle (rows reordering never animate).
 */
export function SortableList<T>({
  items,
  getKey,
  getLabel,
  onReorder,
  renderItem,
  className,
  itemClassName,
}: {
  items: readonly T[]
  getKey: (item: T) => string
  /** Used in the move buttons' accessible names. */
  getLabel: (item: T) => string
  onReorder: (items: T[]) => void
  renderItem: (item: T, controls: SortableControls) => ReactNode
  className?: string
  itemClassName?: string
}) {
  const { t } = useTranslation()
  const listRef = useRef<HTMLUListElement>(null)
  const drag = useRef<{
    from: number
    to: number
    startY: number
    tops: number[]
    heights: number[]
    gap: number
    rows: HTMLElement[]
  } | null>(null)

  const rowsOf = () =>
    Array.from(listRef.current?.children ?? []).filter(
      (child): child is HTMLElement => child instanceof HTMLElement
    )

  const onPointerDown = (index: number) => (event: ReactPointerEvent<HTMLElement>) => {
    if (event.button !== 0 || items.length < 2) return
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    const rows = rowsOf()
    const rects = rows.map((row) => row.getBoundingClientRect())
    drag.current = {
      from: index,
      to: index,
      startY: event.clientY,
      tops: rects.map((r) => r.top),
      heights: rects.map((r) => r.height),
      gap: rects.length > 1 ? rects[1].top - rects[0].bottom : 0,
      rows,
    }
    rows[index].dataset.dragging = "true"
  }

  const onPointerMove = (event: ReactPointerEvent<HTMLElement>) => {
    const state = drag.current
    if (!state) return
    const { from, tops, heights, gap, rows } = state
    let dy = event.clientY - state.startY
    // Rubber band past the first and last slot.
    const minDy = tops[0] - tops[from]
    const maxDy = tops.at(-1)! + heights.at(-1)! - (tops[from] + heights[from])
    if (dy < minDy) dy = minDy + (dy - minDy) * 0.2
    if (dy > maxDy) dy = maxDy + (dy - maxDy) * 0.2
    rows[from].style.transform = `translate3d(0, ${dy}px, 0)`
    const center = tops[from] + heights[from] / 2 + dy
    let to = from
    for (let i = 0; i < tops.length; i++) {
      const middle = tops[i] + heights[i] / 2
      if (i < from && center < middle) {
        to = i
        break
      }
      if (i > from && center > middle) to = i
    }
    state.to = to
    const shift = heights[from] + gap
    rows.forEach((row, i) => {
      if (i === from) return
      const offset = i >= to && i < from ? shift : i <= to && i > from ? -shift : 0
      row.style.transform = offset ? `translate3d(0, ${offset}px, 0)` : ""
    })
  }

  const onPointerUp = () => {
    const state = drag.current
    if (!state) return
    drag.current = null
    state.rows.forEach((row) => {
      row.style.transform = ""
      delete row.dataset.dragging
    })
    if (state.to !== state.from) onReorder(moveItem(items, state.from, state.to))
  }

  return (
    <ul ref={listRef} className={cn("flex flex-col", className)}>
      {items.map((item, index) => {
        const label = getLabel(item)
        const handle = (
          <span
            aria-hidden
            onPointerDown={onPointerDown(index)}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerCancel={onPointerUp}
            className={cn(
              "relative inline-flex size-9 shrink-0 cursor-grab touch-none items-center justify-center rounded-md text-muted-foreground active:cursor-grabbing [&_svg]:size-5",
              "before:absolute before:-inset-1",
              items.length < 2 && "pointer-events-none opacity-40"
            )}
          >
            <DotsSixVerticalIcon weight="bold" />
          </span>
        )
        const moveButtons = (
          <span className="flex shrink-0 items-center">
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={index === 0}
              onClick={() => onReorder(moveItem(items, index, index - 1))}
              aria-label={t("common.moveUp", { name: label })}
            >
              <ArrowUpIcon />
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={index === items.length - 1}
              onClick={() => onReorder(moveItem(items, index, index + 1))}
              aria-label={t("common.moveDown", { name: label })}
            >
              <ArrowDownIcon />
            </Button>
          </span>
        )
        return (
          <li
            key={getKey(item)}
            className={cn(
              "relative bg-card data-[dragging=true]:z-10 data-[dragging=true]:rounded-lg data-[dragging=true]:shadow-lg data-[dragging=true]:ring-1 data-[dragging=true]:ring-border",
              itemClassName
            )}
          >
            {renderItem(item, { index, handle, moveButtons })}
          </li>
        )
      })}
    </ul>
  )
}
