import {
  ArrowLeftIcon,
  CaretLeftIcon,
  CaretRightIcon,
  CheckIcon,
  DotsThreeIcon,
  DownloadSimpleIcon,
  InfoIcon,
  MagnifyingGlassMinusIcon,
  MagnifyingGlassPlusIcon,
  PlayIcon,
  StarIcon,
  StopIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Kbd } from "@/components/ui/kbd"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { api, fileUrl, previewUrl } from "@/lib/api"
import { actionForKey, type CullAction } from "@/lib/culling"
import { applyFilters, filtersToSearch } from "@/lib/filters"
import { formatBytes, formatDateTime } from "@/lib/format"
import { navigate } from "@/lib/router"
import { decide, downloadZip, startDownload, undo, useLibraryState } from "@/lib/store"
import type { Row } from "@/lib/types"
import { wallClock } from "@/lib/types"
import { cn } from "@/lib/utils"
import { setLastViewed } from "@/lib/viewState"
import { useFilters } from "@/lib/useFilters"
import { InfoPanel, type Exposure, type ItemDetail } from "./InfoPanel"
import { VideoView } from "./VideoView"
import { displayableOriginal } from "./zoom"
import { ZoomImage, type Layer } from "./ZoomImage"

const ADVANCE_KEY = "hoard-web.autoAdvance"
const INFO_KEY = "hoard-web.info"
const readBool = (key: string, fallback: boolean) => {
  try {
    const v = localStorage.getItem(key)
    return v === null ? fallback : v === "1"
  } catch {
    return fallback
  }
}
const writeBool = (key: string, v: boolean) => {
  try {
    localStorage.setItem(key, v ? "1" : "0")
  } catch {
    // this page load only
  }
}

const isTyping = (t: EventTarget | null) => {
  const el = t as HTMLElement | null
  return !!el && (/input|textarea|select/i.test(el.tagName) || el.isContentEditable)
}

/**
 * The full-screen viewer (ADR 0018 "Viewer"), dark in both themes like Hoard's: ←/→, 0-5, P, X, U (Hoard's culling
 * semantics), Z zoom, I info, Esc, Ctrl/Cmd+Z undo. It walks the gallery's filtered list as it was when it opened, so
 * an item does not jump away when a decision makes it leave the filter.
 */
export function Viewer({ itemId }: { itemId: string }) {
  const { t, i18n } = useTranslation()
  const { library } = useLibraryState()
  const [filters] = useFilters()
  const filtered = useMemo(() => applyFilters(library.rows, filters, library.folders), [library.rows, filters, library.folders])
  // The order it opened with; items that appear (live) are added at the end, items that are gone are dropped.
  const [snapshot] = useState<string[]>(() => filtered.map((r) => r.id))
  const order = useMemo(() => {
    const kept = snapshot.filter((id) => library.byId.has(id))
    if (kept.length === 0) return filtered.map((r) => r.id)
    const known = new Set(kept)
    const added = filtered.filter((r) => !known.has(r.id)).map((r) => r.id)
    return added.length ? [...kept, ...added] : kept
  }, [snapshot, filtered, library.byId])
  const [zoomed, setZoomed] = useState(false)
  const [layer, setLayer] = useState<Layer>("thumb")
  const [info, setInfo] = useState(() => readBool(INFO_KEY, true))
  const [advance, setAdvance] = useState(() => readBool(ADVANCE_KEY, true))
  const [loadedDetail, setDetail] = useState<ItemDetail | null>(null)
  const [loadedExposure, setExposure] = useState<{ id: string; exposure: Exposure } | null>(null)
  const [motion, setMotion] = useState(false)
  // Another item: reset what belongs to the previous one (state adjusted while rendering).
  const [shown, setShown] = useState(itemId)
  if (shown !== itemId) {
    setShown(itemId)
    setMotion(false)
    setLayer("thumb")
  }
  const detail = loadedDetail?.item.id === itemId ? loadedDetail : null
  const exposure = loadedExposure?.id === itemId ? loadedExposure.exposure : null
  const dialog = useRef<HTMLDivElement>(null)

  const index = order.indexOf(itemId)
  const row: Row | undefined = library.byId.get(itemId)
  const search = filtersToSearch(filters)

  const goTo = useCallback(
    (i: number) => {
      const id = order[i]
      if (!id || id === itemId) return
      setZoomed(false)
      navigate(`/items/${id}`, search, true)
    },
    [order, itemId, search]
  )
  const close = useCallback(() => {
    setLastViewed(itemId)
    navigate("/", search)
  }, [itemId, search])

  // Details (again after a decision: who wrote each field) and exposure of the item on screen.
  const revs = row?.cr ?? ""
  useEffect(() => {
    let live = true
    api<ItemDetail>(`/web/items/${itemId}`)
      .then((d) => live && setDetail(d))
      .catch(() => {})
    return () => {
      live = false
    }
  }, [itemId, revs])
  useEffect(() => {
    let live = true
    api<{ exposure: Exposure }>(`/web/items/${itemId}/exif`)
      .then((d) => live && setExposure({ id: itemId, exposure: d.exposure }))
      .catch(() => {})
    return () => {
      live = false
    }
  }, [itemId])

  // Warm the neighbours' screen previews.
  useEffect(() => {
    for (const d of [1, -1, 2]) {
      const id = order[index + d]
      const r = id ? library.byId.get(id) : undefined
      if (r && r.k !== "video") new Image().src = previewUrl(r.id, "screen", r.v)
    }
  }, [index, order, library.byId])

  useEffect(() => {
    dialog.current?.focus()
  }, [])

  const act = useCallback(
    (a: CullAction) => {
      if (!row) return
      const before = row.x
      decide([row.id], a)
      // Auto-advance after a pick or reject that was set (not cleared), as Hoard does.
      if (advance && a.kind === "flag" && !(a.mode === "toggle" && before === a.flag) && index < order.length - 1) {
        window.setTimeout(() => goTo(index + 1), 120)
      }
    },
    [row, advance, index, order.length, goTo]
  )

  const download = useCallback(async () => {
    if (!detail) return
    const files = detail.item.files
    if (files.length === 1) {
      startDownload(fileUrl(detail.item.id, files[0].fingerprint, true))
      return
    }
    try {
      const d = await downloadZip([detail.item.id])
      toast.success(t("download.started", { count: d.files, size: formatBytes(d.bytes, i18n.language) }))
    } catch {
      toast.error(t("download.failed"))
    }
  }, [detail, t, i18n.language])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isTyping(e.target)) return
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "z") {
        e.preventDefault()
        const ids = undo()
        if (ids[0] && ids[0] !== itemId && order.includes(ids[0])) goTo(order.indexOf(ids[0]))
        return
      }
      if (e.metaKey || e.ctrlKey || e.altKey) return
      const k = e.key.toLowerCase()
      if (k === "escape") {
        if (zoomed) setZoomed(false)
        else close()
      } else if (k === "arrowright") goTo(index + 1)
      else if (k === "arrowleft") goTo(index - 1)
      else if (k === "z") setZoomed((z) => !z)
      else if (k === "i")
        setInfo((v) => {
          writeBool(INFO_KEY, !v)
          return !v
        })
      else if (k === "home") goTo(0)
      else if (k === "end") goTo(order.length - 1)
      else {
        const a = actionForKey(e.key)
        if (!a) return
        act(a)
      }
      e.preventDefault()
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [index, order, itemId, zoomed, goTo, close, act])

  if (!row) {
    return (
      <div className="dark fixed inset-0 z-50 grid place-items-center bg-viewer text-viewer-foreground" role="dialog" aria-modal>
        <div className="flex flex-col items-center gap-4">
          <p className="text-[0.9375rem]">{library.rows.length ? t("viewer.gone") : t("common.loading")}</p>
          <Button variant="outline" onClick={close}>
            {t("viewer.back")}
          </Button>
        </div>
      </div>
    )
  }

  const original = detail ? displayableOriginal(detail.item.kind, detail.item.mainFingerprint, detail.item.files) : null
  const isVideo = row.k === "video"
  const layerLabel = layer === "original" ? t("viewer.layer.original") : layer === "screen" ? t("viewer.layer.screen") : t("viewer.layer.thumb")

  return (
    <div
      ref={dialog}
      tabIndex={-1}
      role="dialog"
      aria-modal
      aria-label={row.n}
      data-testid="viewer"
      className="dark fixed inset-0 z-50 flex flex-col bg-viewer text-viewer-foreground outline-none"
    >
      <header className="flex h-14 shrink-0 items-center gap-2 px-3">
        <Button variant="ghost" size="icon" onClick={close} aria-label={t("viewer.close")}>
          <ArrowLeftIcon />
        </Button>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[0.9375rem] font-medium" title={row.n}>
            {row.n}
          </p>
          <p className="truncate text-[0.8125rem] text-muted-foreground tabular-nums">
            {formatDateTime(wallClock(row), i18n.language)}
            {index >= 0 && ` · ${t("viewer.position", { index: index + 1, total: order.length })}`}
            {!isVideo && ` · ${layerLabel}`}
          </p>
        </div>
        {!isVideo && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="ghost" size="icon" onClick={() => setZoomed((z) => !z)} aria-pressed={zoomed} aria-label={t("viewer.zoom")}>
                {zoomed ? <MagnifyingGlassMinusIcon /> : <MagnifyingGlassPlusIcon />}
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              {t("viewer.zoom")} <Kbd>Z</Kbd>
            </TooltipContent>
          </Tooltip>
        )}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              aria-pressed={info}
              aria-label={t("viewer.info")}
              onClick={() =>
                setInfo((v) => {
                  writeBool(INFO_KEY, !v)
                  return !v
                })
              }
            >
              <InfoIcon />
            </Button>
          </TooltipTrigger>
          <TooltipContent>
            {t("viewer.info")} <Kbd>I</Kbd>
          </TooltipContent>
        </Tooltip>
        <Button variant="ghost" size="icon" onClick={() => void download()} disabled={!detail} aria-label={t("download.item")}>
          <DownloadSimpleIcon />
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label={t("viewer.more")}>
              <DotsThreeIcon />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="dark">
            <DropdownMenuCheckboxItem
              checked={advance}
              onCheckedChange={(v) => {
                setAdvance(v)
                writeBool(ADVANCE_KEY, v)
              }}
            >
              {t("viewer.autoAdvance")}
            </DropdownMenuCheckboxItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </header>
      <div className="flex min-h-0 flex-1">
        <div className="relative min-w-0 flex-1">
          {isVideo || motion ? (
            <VideoView key={row.id} itemId={row.id} poster={previewUrl(row.id, "screen", row.v)} loop={motion} />
          ) : (
            <ZoomImage
              key={row.id}
              width={row.w}
              height={row.h}
              thumb={previewUrl(row.id, "thumb", row.v)}
              screen={previewUrl(row.id, "screen", row.v)}
              original={original ? fileUrl(row.id, original.fingerprint) : null}
              zoomed={zoomed}
              onZoomedChange={setZoomed}
              onLayer={(l) => setLayer((cur) => (l === "original" || (l === "screen" && cur === "thumb") ? l : cur))}
              alt={row.n}
              dimmed={row.x === "reject"}
            />
          )}
          {index > 0 && (
            <Button
              variant="ghost"
              size="icon"
              className="absolute top-1/2 left-2 size-11 -translate-y-1/2 rounded-full bg-viewer/60"
              onClick={() => goTo(index - 1)}
              aria-label={t("viewer.previous")}
            >
              <CaretLeftIcon />
            </Button>
          )}
          {index >= 0 && index < order.length - 1 && (
            <Button
              variant="ghost"
              size="icon"
              className="absolute top-1/2 right-2 size-11 -translate-y-1/2 rounded-full bg-viewer/60"
              onClick={() => goTo(index + 1)}
              aria-label={t("viewer.next")}
            >
              <CaretRightIcon />
            </Button>
          )}
          {row.k === "motion" && (
            <Button variant="secondary" className="absolute bottom-3 left-3" onClick={() => setMotion((m) => !m)} aria-pressed={motion}>
              {motion ? <StopIcon data-icon="inline-start" /> : <PlayIcon data-icon="inline-start" />}
              {motion ? t("viewer.stopMotion") : t("viewer.playMotion")}
            </Button>
          )}
        </div>
        {info && (
          <aside aria-label={t("viewer.info")} className="w-80 shrink-0 overflow-y-auto border-l border-border bg-card max-lg:hidden">
            <InfoPanel row={row} detail={detail} exposure={exposure} />
          </aside>
        )}
      </div>
      <footer className="flex h-16 shrink-0 items-center justify-center gap-2 px-3" aria-label={t("viewer.culling")}>
        <Button
          variant={row.x === "reject" ? "destructive" : "ghost"}
          size="icon"
          className="size-11 rounded-full"
          aria-pressed={row.x === "reject"}
          aria-label={t("cull.reject")}
          onClick={() => act({ kind: "flag", flag: "reject", mode: "toggle" })}
        >
          <XIcon weight="bold" />
        </Button>
        <div className="flex items-center" role="group" aria-label={t("cull.rate")}>
          {[1, 2, 3, 4, 5].map((n) => (
            <Button
              key={n}
              variant="ghost"
              size="icon"
              className="size-11"
              aria-pressed={(row.r ?? 0) === n}
              aria-label={t("cull.stars", { count: n })}
              onClick={() => act({ kind: "rate", stars: n, mode: "toggle" })}
            >
              <StarIcon weight={(row.r ?? 0) >= n ? "fill" : "regular"} className={cn((row.r ?? 0) >= n && "text-warning")} />
            </Button>
          ))}
        </div>
        <Button
          variant={row.x === "pick" ? "default" : "ghost"}
          size="icon"
          className="size-11 rounded-full"
          aria-pressed={row.x === "pick"}
          aria-label={t("cull.pick")}
          onClick={() => act({ kind: "flag", flag: "pick", mode: "toggle" })}
        >
          <CheckIcon weight="bold" />
        </Button>
      </footer>
    </div>
  )
}
