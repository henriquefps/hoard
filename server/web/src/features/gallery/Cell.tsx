import {
  CheckCircleIcon,
  CheckIcon,
  CircleIcon,
  ImageBrokenIcon,
  PaintBrushIcon,
  PlayIcon,
  StarIcon,
  XIcon,
} from "@phosphor-icons/react"
import { memo, useState } from "react"
import { useTranslation } from "react-i18next"
import { previewUrl } from "@/lib/api"
import { formatDuration } from "@/lib/format"
import type { Row } from "@/lib/types"
import { cn } from "@/lib/utils"

/**
 * A thumbnail from GET /web/items/{id}/preview (ADR 0015). With the index's key `v` the URL is immutable (cached for
 * good); without one the server may answer 202 (not made yet, queued first): the image errors and is asked again.
 */
export function Thumb({ row, size = "thumb", className }: { row: Row; size?: "thumb" | "screen"; className?: string }) {
  const base = previewUrl(row.id, size, row.v)
  const [state, setState] = useState({ base, attempt: 0, failed: false, loaded: false })
  // Another item or preview key: start over (state adjusted while rendering, not in an effect).
  if (state.base !== base) setState({ base, attempt: 0, failed: false, loaded: false })
  const { attempt, failed, loaded } = state
  const src = attempt ? `${base}&retry=${attempt}` : base
  if (failed) {
    return (
      <div className={cn("grid place-items-center bg-muted text-muted-foreground", className)}>
        <ImageBrokenIcon className="size-6" aria-hidden />
      </div>
    )
  }
  return (
    <img
      src={src}
      alt=""
      decoding="async"
      draggable={false}
      onLoad={() => setState((st) => (st.base === base ? { ...st, loaded: true } : st))}
      onError={() => {
        if (attempt >= 30) setState((st) => (st.base === base ? { ...st, failed: true } : st))
        else
          window.setTimeout(
            () => setState((st) => (st.base === base ? { ...st, attempt: st.attempt + 1 } : st)),
            Math.min(2000 + attempt * 1000, 10000)
          )
      }}
      className={cn("bg-muted object-cover", !loaded && "animate-pulse motion-reduce:animate-none", className)}
    />
  )
}

export function KindBadge({ row }: { row: Row }) {
  const { t } = useTranslation()
  const chip = "inline-flex h-6 items-center gap-1 rounded-md bg-viewer/75 px-1.5 font-mono text-xs leading-none font-medium text-viewer-foreground"
  if (row.k === "video")
    return (
      <span className={chip}>
        <PlayIcon weight="fill" className="size-3" aria-hidden />
        {row.dur ? formatDuration(row.dur) : t("kind.video")}
      </span>
    )
  if (row.k === "motion")
    return (
      <span className={chip}>
        <PlayIcon className="size-3" aria-hidden />
        {t("kind.motion")}
      </span>
    )
  if (row.k === "pair") return <span className={chip}>{t("kind.rawJpg")}</span>
  if (row.raw) return <span className={chip}>{t("kind.raw")}</span>
  return null
}

/** Read-only stars, as Hoard's grid shows them: a star and the number. */
export function Stars({ rating, className }: { rating: number; className?: string }) {
  if (!rating) return null
  return (
    <span
      aria-hidden
      className={cn(
        "inline-flex h-6 items-center gap-0.5 rounded-md bg-viewer/75 px-1.5 text-xs leading-none text-viewer-foreground tabular-nums",
        className
      )}
    >
      <StarIcon weight="fill" className="size-3" />
      {rating}
    </span>
  )
}

interface CellProps {
  row: Row
  index: number
  size: number
  selected: boolean
  selecting: boolean
  focused: boolean
  label: string
  onActivate: (index: number, e: React.MouseEvent) => void
  onToggle: (index: number, e: React.MouseEvent) => void
  onFocusCell: (index: number) => void
}

export const Cell = memo(function Cell({
  row,
  index,
  size,
  selected,
  selecting,
  focused,
  label,
  onActivate,
  onToggle,
  onFocusCell,
}: CellProps) {
  const { t } = useTranslation()
  return (
    <div className="group relative" style={{ width: size, height: size }}>
      <button
        type="button"
        data-cell={index}
        tabIndex={focused ? 0 : -1}
        aria-label={label}
        aria-pressed={selecting ? selected : undefined}
        onClick={(e) => onActivate(index, e)}
        onFocus={() => onFocusCell(index)}
        className={cn(
          "block size-full overflow-hidden rounded-md outline-none focus-visible:ring-3 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card",
          selected && "ring-3 ring-primary ring-offset-2 ring-offset-card"
        )}
      >
        <Thumb row={row} className={cn("size-full", row.x === "reject" && "opacity-40")} />
      </button>
      <div className="pointer-events-none absolute inset-x-1.5 top-1.5 flex items-start gap-1">
        <KindBadge row={row} />
        {row.e && (
          <span
            className="inline-flex size-6 items-center justify-center rounded-md bg-viewer/75 text-viewer-foreground"
            title={t("badges.edited")}
          >
            <PaintBrushIcon className="size-3.5" aria-hidden />
          </span>
        )}
      </div>
      <button
        type="button"
        tabIndex={-1}
        aria-label={selected ? t("select.remove") : t("select.add")}
        onClick={(e) => onToggle(index, e)}
        className={cn(
          "absolute top-1 right-1 grid size-8 place-items-center rounded-full text-viewer-foreground transition-opacity",
          selecting || selected ? "opacity-100" : "opacity-0 group-hover:opacity-100"
        )}
      >
        {selected ? (
          <CheckCircleIcon weight="fill" className="size-6 rounded-full bg-viewer-foreground text-primary" />
        ) : (
          <CircleIcon weight="bold" className="size-6 drop-shadow" />
        )}
      </button>
      <div className="pointer-events-none absolute inset-x-1.5 bottom-1.5 flex items-end justify-between gap-1">
        {row.r ? <Stars rating={row.r} /> : <span />}
        {row.x === "pick" && (
          <span className="grid size-6 place-items-center rounded-full bg-primary text-primary-foreground">
            <CheckIcon weight="bold" className="size-3.5" aria-hidden />
          </span>
        )}
        {row.x === "reject" && (
          <span className="grid size-6 place-items-center rounded-full bg-destructive text-viewer-foreground">
            <XIcon weight="bold" className="size-3.5" aria-hidden />
          </span>
        )}
      </div>
    </div>
  )
})
