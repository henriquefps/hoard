import { useVirtualizer } from "@tanstack/react-virtual"
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { CELL_SIZES, type CellSize } from "@/lib/cellSize"
import { formatDay, formatMonth, formatNumber } from "@/lib/format"
import { columnsFor, layout, monthAt, yearsOf, type Entry } from "@/lib/layout"
import type { Row } from "@/lib/types"
import { cn } from "@/lib/utils"
import { Cell } from "./Cell"
import { useRowLabel } from "./rowLabel"

const GAP = 4
const HEADER = 44
const PAD = 16

interface GridProps {
  rows: Row[]
  cellSize: CellSize
  selected: ReadonlySet<string>
  focus: number
  onFocus: (i: number) => void
  onOpen: (i: number) => void
  onSelect: (i: number, mode: "toggle" | "range") => void
  /** An index to bring into view (the item the viewer closed on). */
  reveal: number | null
}

/**
 * The virtualized grid (ADR 0018 "Gallery"): day headers and rows of square cells, a sticky header with the day at the
 * top, and the months/years jump bar. Only what is on screen is in the DOM (about 100 cells at 1440 × 900), so 20,000
 * items scroll like 200.
 */
export function Grid({ rows, cellSize, selected, focus, onFocus, onOpen, onSelect, reveal }: GridProps) {
  const { t, i18n } = useTranslation()
  const rowLabel = useRowLabel()
  const scrollRef = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)

  useLayoutEffect(() => {
    const el = scrollRef.current
    if (!el) return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    setWidth(el.clientWidth)
    return () => ro.disconnect()
  }, [])

  const inner = Math.max(0, width - 2 * PAD)
  const cols = columnsFor(inner, CELL_SIZES[cellSize], GAP)
  const size = Math.max(40, Math.floor((inner - (cols - 1) * GAP) / cols))
  const grid = useMemo(() => layout(rows, cols), [rows, cols])
  const entries = grid.entries

  const virtualizer = useVirtualizer({
    count: entries.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: (i) => (entries[i].type === "header" ? HEADER : size + GAP),
    overscan: 3,
    paddingEnd: PAD,
  })
  useEffect(() => {
    virtualizer.measure()
  }, [size, entries, virtualizer])

  const items = virtualizer.getVirtualItems()
  const top = items[0]?.index ?? 0
  const scrollTop = virtualizer.scrollOffset ?? 0
  const current = monthAt(grid.months, top)
  const stickyDay = entries[top]?.day

  // Bring an item into view (keyboard focus, closing the viewer).
  const show = useCallback(
    (i: number) => {
      const e = grid.entryOf[i]
      if (e === undefined) return
      virtualizer.scrollToIndex(e, { align: "auto" })
    },
    [grid, virtualizer]
  )
  useEffect(() => {
    if (reveal !== null && reveal < rows.length) show(reveal)
  }, [reveal, rows.length, show])

  // Move focus to a cell once it is rendered.
  const pendingFocus = useRef<number | null>(null)
  useEffect(() => {
    const i = pendingFocus.current
    if (i === null) return
    const el = scrollRef.current?.querySelector<HTMLButtonElement>(`[data-cell="${i}"]`)
    if (el) {
      el.focus({ preventScroll: true })
      pendingFocus.current = null
    }
  })

  const move = (i: number) => {
    const next = Math.max(0, Math.min(rows.length - 1, i))
    onFocus(next)
    pendingFocus.current = next
    show(next)
  }

  // Up/down keep the column inside the day's rows; across days the column is clamped.
  const vertical = (from: number, dir: 1 | -1) => {
    let e = grid.entryOf[from] + dir
    const cur = entries[grid.entryOf[from]] as Extract<Entry, { type: "cells" }>
    const col = from - cur.start
    while (e >= 0 && e < entries.length && entries[e].type !== "cells") e += dir
    const target = entries[e]
    if (!target || target.type !== "cells") return from
    return Math.min(target.start + col, target.end - 1)
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.metaKey || e.ctrlKey || e.altKey || rows.length === 0) return
    let next: number
    switch (e.key) {
      case "ArrowRight":
        next = focus + 1
        break
      case "ArrowLeft":
        next = focus - 1
        break
      case "ArrowDown":
        next = vertical(focus, 1)
        break
      case "ArrowUp":
        next = vertical(focus, -1)
        break
      case "Home":
        next = 0
        break
      case "End":
        next = rows.length - 1
        break
      case "PageDown":
        next = vertical(vertical(vertical(focus, 1), 1), 1)
        break
      case "PageUp":
        next = vertical(vertical(vertical(focus, -1), -1), -1)
        break
      case "Enter":
        onOpen(focus)
        e.preventDefault()
        return
      case " ":
        onSelect(focus, e.shiftKey ? "range" : "toggle")
        e.preventDefault()
        return
      default:
        return
    }
    e.preventDefault()
    move(next)
  }

  const jump = (entry: number) => {
    virtualizer.scrollToIndex(entry, { align: "start" })
  }

  const years = useMemo(() => yearsOf(grid.months), [grid.months])

  const activate = useCallback(
    (i: number, e: React.MouseEvent) => {
      if (e.shiftKey) onSelect(i, "range")
      else if (e.metaKey || e.ctrlKey) onSelect(i, "toggle")
      else onOpen(i)
    },
    [onOpen, onSelect]
  )
  const toggle = useCallback(
    (i: number, e: React.MouseEvent) => {
      e.stopPropagation()
      onSelect(i, e.shiftKey ? "range" : "toggle")
    },
    [onSelect]
  )

  return (
    <div className="flex min-h-0 flex-1">
      <div
        ref={scrollRef}
        role="grid"
        aria-label={t("gallery.gridLabel", { count: rows.length })}
        aria-rowcount={entries.length}
        onKeyDown={onKeyDown}
        data-testid="grid"
        className="relative min-h-0 flex-1 overflow-y-auto overscroll-contain [overflow-anchor:none]"
      >
        {stickyDay && scrollTop > 0 && (
          <div
            aria-hidden
            className="pointer-events-none sticky top-0 z-10 -mb-11 flex h-11 items-center bg-card/95 px-4 text-[0.9375rem] font-semibold"
          >
            {formatDay(stickyDay, i18n.language)}
          </div>
        )}
        <div style={{ height: virtualizer.getTotalSize(), position: "relative" }}>
          {items.map((v) => {
            const entry = entries[v.index]
            return (
              <div
                key={v.key}
                role="row"
                aria-rowindex={v.index + 1}
                className="absolute inset-x-0 px-4"
                style={{ top: v.start, height: v.size }}
              >
                {entry.type === "header" ? (
                  <h2 className="flex h-11 items-center gap-2 text-[0.9375rem] font-semibold">
                    {formatDay(entry.day, i18n.language)}
                    <span className="text-sm font-normal text-muted-foreground tabular-nums">
                      {formatNumber(entry.count, i18n.language)}
                    </span>
                  </h2>
                ) : (
                  <div className="flex" style={{ gap: GAP }}>
                    {rows.slice(entry.start, entry.end).map((row, k) => {
                      const i = entry.start + k
                      return (
                        <div role="gridcell" key={row.id}>
                          <Cell
                            row={row}
                            index={i}
                            size={size}
                            selected={selected.has(row.id)}
                            selecting={selected.size > 0}
                            focused={i === focus}
                            label={rowLabel(row)}
                            onActivate={activate}
                            onToggle={toggle}
                            onFocusCell={onFocus}
                          />
                        </div>
                      )
                    })}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
      {years.length > 0 && (
        <nav
          aria-label={t("gallery.jumpBar")}
          className="flex w-16 shrink-0 flex-col overflow-y-auto border-l py-2 text-xs md:w-20"
        >
          {years.map((y) => {
            const activeYear = current?.year === y.year
            return (
              <div key={y.year} className="flex flex-col">
                <button
                  type="button"
                  onClick={() => jump(y.months[0].entry)}
                  aria-current={activeYear ? "true" : undefined}
                  title={t("gallery.itemsCount", { count: y.count })}
                  className={cn(
                    "mx-1 rounded-md px-2 py-1.5 text-left font-semibold tabular-nums hover:bg-muted",
                    activeYear ? "text-primary" : "text-foreground"
                  )}
                >
                  {y.year}
                </button>
                {activeYear &&
                  y.months.map((m) => (
                    <button
                      key={m.key}
                      type="button"
                      onClick={() => jump(m.entry)}
                      aria-current={current?.key === m.key ? "true" : undefined}
                      title={t("gallery.itemsCount", { count: m.count })}
                      className={cn(
                        "mx-1 rounded-md px-2 py-1 text-left hover:bg-muted",
                        current?.key === m.key ? "bg-muted font-medium text-foreground" : "text-muted-foreground"
                      )}
                    >
                      {formatMonth(m.year, m.month, i18n.language)}
                    </button>
                  ))}
              </div>
            )
          })}
        </nav>
      )}
    </div>
  )
}
