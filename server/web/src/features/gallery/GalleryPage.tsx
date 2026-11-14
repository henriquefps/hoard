import { CheckIcon, DownloadSimpleIcon, ImagesIcon, MinusCircleIcon, StarIcon, XIcon } from "@phosphor-icons/react"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Spinner } from "@/components/ui/spinner"
import { actionForKey, type CullAction } from "@/lib/culling"
import { expandStacks } from "@/lib/edits"
import { applyFilters, filtersToSearch, NO_FILTERS } from "@/lib/filters"
import { formatBytes, formatNumber } from "@/lib/format"
import { navigate, useLocation } from "@/lib/router"
import { decide, downloadZip, undo, useLibraryState } from "@/lib/store"
import { useFilters } from "@/lib/useFilters"
import { FilterBar } from "./FilterBar"
import { Grid } from "./Grid"
import { CELL_SIZES, type CellSize } from "@/lib/cellSize"
import { takeLastViewed } from "@/lib/viewState"

const SIZE_KEY = "hoard-web.cellSize"
const readSize = (): CellSize => {
  try {
    const v = localStorage.getItem(SIZE_KEY)
    if (v && v in CELL_SIZES) return v as CellSize
  } catch {
    // storage unavailable
  }
  return "m"
}

const isTyping = (t: EventTarget | null) => {
  const el = t as HTMLElement | null
  return !!el && (/input|textarea|select/i.test(el.tagName) || el.isContentEditable)
}

export function GalleryPage({ previews }: { previews: boolean }) {
  const { t, i18n } = useTranslation()
  const loc = useLocation()
  const { library, status, error } = useLibraryState()
  const [filters, setFilters] = useFilters()
  const [cellSize, setCellSize] = useState<CellSize>(readSize)
  const [chosen, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [focus, setFocus] = useState(0)
  const [reveal, setReveal] = useState<number | null>(null)
  // Items whose stack of Reveal's edits (virtual copies, ADR 0020) is open.
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set())
  const anchor = useRef<number | null>(null)
  const viewerOpen = loc.path.startsWith("/items/")

  const rows = useMemo(() => applyFilters(library.rows, filters, library.folders), [library.rows, filters, library.folders])
  const cells = useMemo(() => expandStacks(rows, expanded), [rows, expanded])
  const focusIndex = Math.min(focus, Math.max(0, cells.length - 1))
  // The selection is what is chosen and still in the list (filters changed, items removed).
  const selected = useMemo<ReadonlySet<string>>(() => {
    if (chosen.size === 0) return chosen
    const present = new Set(rows.map((r) => r.id))
    return [...chosen].every((id) => present.has(id)) ? chosen : new Set([...chosen].filter((id) => present.has(id)))
  }, [chosen, rows])

  // Back from the viewer: show and focus the item it closed on (state adjusted while rendering).
  const [wasOpen, setWasOpen] = useState(viewerOpen)
  if (wasOpen !== viewerOpen) {
    setWasOpen(viewerOpen)
    const id = viewerOpen ? null : takeLastViewed()
    const i = id ? cells.findIndex((c) => c.row.id === id && !c.edit) : -1
    if (i >= 0) {
      setFocus(i)
      setReveal(i)
    }
  }
  useEffect(() => {
    if (viewerOpen || reveal === null) return
    const timer = window.setTimeout(
      () => document.querySelector<HTMLButtonElement>(`[data-cell="${reveal}"]`)?.focus({ preventScroll: true }),
      50
    )
    return () => window.clearTimeout(timer)
  }, [viewerOpen, reveal])

  const changeSize = (s: CellSize) => {
    setCellSize(s)
    try {
      localStorage.setItem(SIZE_KEY, s)
    } catch {
      // this page load only
    }
  }

  const open = useCallback(
    (i: number) => {
      const cell = cells[i]
      if (!cell) return
      const search = filtersToSearch(filters)
      if (cell.edit) search.set("edit", cell.edit.id) // a copy of the stack opens on that edit
      navigate(`/items/${cell.row.id}`, search)
    },
    [cells, filters]
  )

  const toggleStack = useCallback((id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const select = useCallback(
    (i: number, mode: "toggle" | "range") => {
      setSelected((prev) => {
        const next = new Set(prev)
        if (mode === "range" && anchor.current !== null) {
          const [a, b] = [Math.min(anchor.current, i), Math.max(anchor.current, i)]
          for (let k = a; k <= b; k++) if (cells[k]) next.add(cells[k].row.id)
        } else {
          const id = cells[i]?.row.id
          if (!id) return prev
          if (next.has(id)) next.delete(id)
          else next.add(id)
          anchor.current = i
        }
        return next
      })
      setFocus(i)
    },
    [cells]
  )

  const targets = useCallback(
    () => (selected.size ? [...selected] : cells[focusIndex] ? [cells[focusIndex].row.id] : []),
    [selected, cells, focusIndex]
  )

  const act = useCallback(
    (a: CullAction) => {
      const ids = targets()
      const n = decide(ids, a)
      if (n > 1) toast.success(t("cull.applied", { count: n }), { action: { label: t("cull.undo"), onClick: () => undo() } })
    },
    [targets, t]
  )

  const download = async (ids: string[]) => {
    try {
      const d = await downloadZip(ids)
      toast.success(t("download.started", { count: d.files, size: formatBytes(d.bytes, i18n.language) }))
    } catch {
      toast.error(t("download.failed"))
    }
  }

  // Culling keys in the grid act on the selection, else on the focused item.
  useEffect(() => {
    if (viewerOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (isTyping(e.target)) return
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "z") {
        e.preventDefault()
        undo()
        return
      }
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "a") {
        e.preventDefault()
        setSelected(new Set(rows.map((r) => r.id)))
        return
      }
      if (e.metaKey || e.ctrlKey || e.altKey) return
      if (e.key === "Escape" && selected.size) {
        setSelected(new Set())
        return
      }
      if (e.key.toLowerCase() === "s" && cells[focusIndex]) {
        // Open or close the focused item's stack of edits.
        e.preventDefault()
        toggleStack(cells[focusIndex].row.id)
        return
      }
      const a = actionForKey(e.key)
      if (a && rows.length) {
        e.preventDefault()
        act(a)
      }
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [viewerOpen, rows, cells, focusIndex, selected, act, toggleStack])


  if (status === "loading") {
    return (
      <div className="grid flex-1 place-items-center" role="status" aria-label={t("common.loading")}>
        <Spinner className="size-6 text-muted-foreground" />
      </div>
    )
  }
  if (status === "error") {
    return (
      <Empty className="flex-1">
        <EmptyHeader>
          <EmptyTitle>{t("gallery.loadFailed")}</EmptyTitle>
          <EmptyDescription>{error}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }

  const selectedSize = [...selected].reduce((s, id) => s + (library.byId.get(id)?.size ?? 0), 0)

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {selected.size > 0 ? (
        <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-3" role="toolbar" aria-label={t("select.toolbar")}>
          <Button variant="ghost" size="icon" onClick={() => setSelected(new Set())} aria-label={t("select.clear")}>
            <XIcon />
          </Button>
          <span className="mr-2 text-[0.9375rem] font-medium tabular-nums" role="status">
            {t("select.count", { count: selected.size, formatted: formatNumber(selected.size, i18n.language) })}
            <span className="ml-2 text-sm font-normal text-muted-foreground">{formatBytes(selectedSize, i18n.language)}</span>
          </span>
          <Button variant="outline" onClick={() => act({ kind: "flag", flag: "pick", mode: "set" })}>
            <CheckIcon data-icon="inline-start" />
            {t("cull.pick")}
          </Button>
          <Button variant="outline" onClick={() => act({ kind: "flag", flag: "reject", mode: "set" })}>
            <XIcon data-icon="inline-start" />
            {t("cull.reject")}
          </Button>
          <Button variant="outline" onClick={() => act({ kind: "unflag" })}>
            <MinusCircleIcon data-icon="inline-start" />
            {t("cull.unflag")}
          </Button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline">
                <StarIcon data-icon="inline-start" />
                {t("cull.rate")}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              {[0, 1, 2, 3, 4, 5].map((n) => (
                <DropdownMenuItem key={n} onSelect={() => act({ kind: "rate", stars: n, mode: "set" })}>
                  {n === 0 ? t("cull.noStars") : t("cull.stars", { count: n })}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
          <Button className="ml-auto" onClick={() => void download([...selected])}>
            <DownloadSimpleIcon data-icon="inline-start" />
            {t("download.zip")}
          </Button>
        </div>
      ) : (
        <FilterBar
          all={library.rows}
          shown={rows.length}
          filters={filters}
          folders={library.folders}
          onChange={(f) => setFilters(f)}
          cellSize={cellSize}
          onCellSize={changeSize}
        />
      )}
      {rows.length === 0 ? (
        <Empty className="flex-1">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <ImagesIcon />
            </EmptyMedia>
            <EmptyTitle>{library.rows.length ? t("gallery.noMatch") : t("gallery.emptyTitle")}</EmptyTitle>
            <EmptyDescription>{library.rows.length ? t("gallery.noMatchHint") : t("gallery.emptyHint")}</EmptyDescription>
          </EmptyHeader>
          {library.rows.length > 0 && (
            <Button variant="outline" onClick={() => setFilters(NO_FILTERS)}>
              {t("filters.clear")}
            </Button>
          )}
        </Empty>
      ) : (
        <Grid
          cells={cells}
          cellSize={cellSize}
          selected={selected}
          focus={focusIndex}
          onFocus={setFocus}
          onOpen={open}
          onSelect={select}
          reveal={reveal}
          expanded={expanded}
          onStack={toggleStack}
        />
      )}
      {!previews && (
        <p className="border-t px-4 py-2 text-sm text-muted-foreground" role="note">
          {t("gallery.noPreviews")}
        </p>
      )}
    </div>
  )
}
