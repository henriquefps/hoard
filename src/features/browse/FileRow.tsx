import { CaretRightIcon } from "@phosphor-icons/react"
import { useRef, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { MiddleTruncate } from "@/components/MiddleTruncate"
import { Checkbox } from "@/components/ui/checkbox"
import { Spinner } from "@/components/ui/spinner"
import type { MachineWithAddresses } from "@/db/types"
import { fileKind } from "@/lib/files"
import { formatBytes, formatFullDate, formatRelativeDate } from "@/lib/format"
import { cn } from "@/lib/utils"
import type { SmbEntry } from "@/plugins/smb/definitions"
import { Thumbnail } from "./Thumbnail"

/** Columns of the desktop table (from `@xl` of the list); the card layout ignores them. */
export const TABLE_COLUMNS =
  "@xl:grid @xl:grid-cols-[3.5rem_minmax(0,1fr)_6.5rem_8.5rem_3.75rem] @3xl:grid-cols-[3.5rem_minmax(0,1fr)_6.5rem_9rem_8rem_3.75rem]"

const LONG_PRESS_MS = 450

/**
 * One entry: a card row below `@xl` of the list (thumbnail, name on up to two lines, muted meta,
 * "⋯"), a table row from `@xl` up (checkbox, name truncated in the middle, size, modified, kind).
 * The name button stretches over the whole row; a long press (touch) starts selection mode.
 */
export function FileRow({
  entry,
  machine,
  share,
  selected,
  selectionMode,
  opening,
  onOpen,
  onToggleSelect,
  onLongPress,
  menu,
}: {
  entry: SmbEntry
  machine: MachineWithAddresses
  share: string
  selected: boolean
  selectionMode: boolean
  opening: boolean
  onOpen: () => void
  onToggleSelect: () => void
  onLongPress: () => void
  menu: ReactNode
}) {
  const { t, i18n } = useTranslation()
  const kind = fileKind(entry)
  const locale = i18n.language
  const press = useRef<{ timer: ReturnType<typeof setTimeout>; x: number; y: number } | null>(null)
  const longPressed = useRef(false)
  const date = formatRelativeDate(entry.modifiedAt, locale)
  const fullDate = formatFullDate(entry.modifiedAt, locale)
  const size = entry.isDirectory ? t("browse.folder") : formatBytes(entry.size, locale)

  const cancelPress = () => {
    if (press.current) clearTimeout(press.current.timer)
    press.current = null
  }

  return (
    <div
      className={cn(
        "group/row relative flex min-h-16 items-center gap-3 border-b px-4 py-2 transition-colors hover:bg-muted/50 has-[[data-primary]:focus-visible]:bg-muted/50 md:px-6",
        "@xl:min-h-13 @xl:gap-0 @xl:px-0 @xl:py-1.5",
        TABLE_COLUMNS,
        selected && "bg-muted hover:bg-muted"
      )}
      data-selected={selected || undefined}
    >
      <div
        className={cn(
          "relative z-10 shrink-0 items-center justify-center",
          selectionMode ? "flex" : "hidden",
          "@xl:flex @xl:pl-2"
        )}
      >
        <Checkbox
          checked={selected}
          onCheckedChange={onToggleSelect}
          aria-label={t("browse.select", { name: entry.name })}
          className="size-5"
        />
      </div>

      <div className="flex min-w-0 flex-1 items-center gap-3 @xl:pr-3">
        <Thumbnail machine={machine} share={share} entry={entry} kind={kind} />
        <button
          type="button"
          data-primary
          onClick={() => {
            if (longPressed.current) {
              longPressed.current = false
              return
            }
            if (selectionMode) onToggleSelect()
            else onOpen()
          }}
          onPointerDown={(event) => {
            if (event.pointerType !== "touch") return
            longPressed.current = false
            const timer = setTimeout(() => {
              longPressed.current = true
              press.current = null
              navigator.vibrate?.(10)
              onLongPress()
            }, LONG_PRESS_MS)
            press.current = { timer, x: event.clientX, y: event.clientY }
          }}
          onPointerMove={(event) => {
            const start = press.current
            if (start && Math.hypot(event.clientX - start.x, event.clientY - start.y) > 8) {
              cancelPress()
            }
          }}
          onPointerUp={cancelPress}
          onPointerCancel={cancelPress}
          onContextMenu={(event) => {
            if (press.current || longPressed.current) event.preventDefault()
          }}
          className="flex min-w-0 flex-1 flex-col items-start gap-0.5 text-left outline-none after:absolute after:inset-0 after:content-['']"
          aria-label={entry.isDirectory ? t("browse.openFolder", { name: entry.name }) : undefined}
        >
          <span className="line-clamp-2 text-[0.9375rem] font-medium [overflow-wrap:anywhere] @xl:hidden">
            {entry.name}
          </span>
          <MiddleTruncate text={entry.name} className="hidden w-full text-[0.9375rem] @xl:flex" />
          <span className="flex max-w-full items-center gap-1 truncate text-[0.8125rem] text-muted-foreground @xl:hidden">
            <span className="truncate" title={fullDate || undefined}>
              {[entry.isDirectory ? t("browse.folder") : size, date].join(" · ")}
            </span>
          </span>
        </button>
        {opening && <Spinner className="shrink-0 text-primary" aria-label={t("browse.opening")} />}
        {entry.isDirectory && !selectionMode && (
          <CaretRightIcon
            aria-hidden
            className="size-4 shrink-0 text-muted-foreground @xl:hidden"
          />
        )}
      </div>

      <div className="hidden pr-3 text-right text-[0.8125rem] text-muted-foreground tabular-nums @xl:block">
        {entry.isDirectory ? "" : size}
      </div>
      <div
        className="hidden truncate pr-3 text-[0.8125rem] text-muted-foreground @xl:block"
        title={fullDate || undefined}
      >
        {date}
      </div>
      <div className="hidden truncate pr-3 text-[0.8125rem] text-muted-foreground @3xl:block">
        {t(`fileKind.${kind}`)}
      </div>
      <div className="relative z-10 flex shrink-0 justify-end @xl:pr-4">{menu}</div>
    </div>
  )
}
