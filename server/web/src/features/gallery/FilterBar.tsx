import {
  CalendarBlankIcon,
  CameraIcon,
  CaretDownIcon,
  FolderSimpleIcon,
  ImagesIcon,
  PaintBrushIcon,
  SquaresFourIcon,
  StarIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useMemo } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Toggle } from "@/components/ui/toggle"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  activeCount,
  cameraFacets,
  FLAGS,
  KINDS,
  NO_FILTERS,
  UPLOADS,
  type FlagFilter,
  type Filters,
} from "@/lib/filters"
import { formatNumber } from "@/lib/format"
import type { Kind, Row } from "@/lib/types"
import { cn } from "@/lib/utils"
import { CELL_SIZES, type CellSize } from "@/lib/cellSize"

const toggle = <T,>(list: T[], v: T) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v])

/** A toolbar trigger (Radix passes the trigger's props and ref through `rest`). */
function FilterButton({
  active,
  icon: Icon,
  label,
  value,
  ...rest
}: { active: boolean; icon: typeof CameraIcon; label: string; value?: string } & React.ComponentProps<typeof Button>) {
  return (
    <Button
      variant="outline"
      {...rest}
      aria-label={value ? `${label}: ${value}` : label}
      className={cn("max-w-56", active && "border-primary/60 bg-primary/5")}
    >
      <Icon data-icon="inline-start" />
      <span className="truncate">{value ?? label}</span>
      <CaretDownIcon data-icon="inline-end" className="text-muted-foreground" />
    </Button>
  )
}

interface FilterBarProps {
  all: Row[]
  shown: number
  filters: Filters
  folders: Record<string, string>
  onChange: (f: Filters) => void
  cellSize: CellSize
  onCellSize: (s: CellSize) => void
}

/** The gallery's toolbar: kind, camera, rating, flag, folder, dates, edited; the count and the cell size. */
export function FilterBar({ all, shown, filters: f, folders, onChange, cellSize, onCellSize }: FilterBarProps) {
  const { t, i18n } = useTranslation()
  const cameras = useMemo(() => cameraFacets(all), [all])
  const set = (patch: Partial<Filters>) => onChange({ ...f, ...patch })
  const kindLabel = (k: Kind) => t(`kind.name.${k}`)
  const camLabel = (c: string) => c || t("filters.unknownCamera")
  const lang = i18n.language

  return (
    <div className="flex flex-wrap items-center gap-2 border-b px-4 py-3">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <FilterButton
            active={f.kinds.length > 0}
            icon={ImagesIcon}
            label={t("filters.kind")}
            value={f.kinds.length === 1 ? kindLabel(f.kinds[0]) : f.kinds.length ? t("filters.nSelected", { count: f.kinds.length }) : undefined}
          />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuLabel>{t("filters.kind")}</DropdownMenuLabel>
          {KINDS.map((k) => (
            <DropdownMenuCheckboxItem
              key={k}
              checked={f.kinds.includes(k)}
              onCheckedChange={() => set({ kinds: toggle(f.kinds, k) })}
              onSelect={(e) => e.preventDefault()}
            >
              {kindLabel(k)}
            </DropdownMenuCheckboxItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <FilterButton
            active={f.cameras.length > 0}
            icon={CameraIcon}
            label={t("filters.camera")}
            value={f.cameras.length === 1 ? camLabel(f.cameras[0]) : f.cameras.length ? t("filters.nSelected", { count: f.cameras.length }) : undefined}
          />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="max-h-80 overflow-y-auto">
          <DropdownMenuLabel>{t("filters.camera")}</DropdownMenuLabel>
          {cameras.map(({ camera, count }) => (
            <DropdownMenuCheckboxItem
              key={camera || "-"}
              checked={f.cameras.includes(camera)}
              onCheckedChange={() => set({ cameras: toggle(f.cameras, camera) })}
              onSelect={(e) => e.preventDefault()}
            >
              <span className="flex-1 truncate">{camLabel(camera)}</span>
              <span className="ml-4 text-xs text-muted-foreground tabular-nums">{formatNumber(count, lang)}</span>
            </DropdownMenuCheckboxItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <FilterButton
            active={f.minRating > 0}
            icon={StarIcon}
            label={t("filters.rating")}
            value={f.minRating ? t("filters.atLeast", { count: f.minRating }) : undefined}
          />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuRadioGroup value={String(f.minRating)} onValueChange={(v) => set({ minRating: Number(v) })}>
            <DropdownMenuRadioItem value="0">{t("filters.anyRating")}</DropdownMenuRadioItem>
            {[1, 2, 3, 4, 5].map((n) => (
              <DropdownMenuRadioItem key={n} value={String(n)}>
                {t("filters.atLeast", { count: n })}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>

      <ToggleGroup
        type="multiple"
        variant="outline"
        value={f.flags}
        onValueChange={(v) => set({ flags: v as FlagFilter[] })}
        aria-label={t("filters.flag")}
      >
        {FLAGS.map((fl) => (
          <ToggleGroupItem key={fl} value={fl} className="px-3">
            {t(`filters.flags.${fl}`)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>

      <Popover>
        <PopoverTrigger asChild>
          <FilterButton
            active={Boolean(f.from || f.to)}
            icon={CalendarBlankIcon}
            label={t("filters.dates")}
            value={f.from || f.to ? `${f.from ?? "…"} – ${f.to ?? "…"}` : undefined}
          />
        </PopoverTrigger>
        <PopoverContent align="start" className="flex w-72 flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="from">{t("filters.from")}</Label>
            <Input id="from" type="date" value={f.from ?? ""} onChange={(e) => set({ from: e.target.value || null })} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="to">{t("filters.to")}</Label>
            <Input id="to" type="date" value={f.to ?? ""} onChange={(e) => set({ to: e.target.value || null })} />
          </div>
          {(f.from || f.to) && (
            <Button variant="ghost" onClick={() => set({ from: null, to: null })}>
              {t("filters.clearDates")}
            </Button>
          )}
        </PopoverContent>
      </Popover>

      <Toggle variant="outline" pressed={f.edited} onPressedChange={(edited) => set({ edited })} aria-label={t("filters.edited")}>
        <PaintBrushIcon />
        <span className="max-xl:sr-only">{t("filters.edited")}</span>
      </Toggle>

      {f.folder && (
        <span className="inline-flex h-9 max-w-72 items-center gap-1 rounded-md border border-primary/60 bg-primary/5 pr-1 pl-3 text-sm">
          <FolderSimpleIcon className="size-4 shrink-0" />
          <span className="truncate">
            {f.folder.id === UPLOADS ? t("folders.uploads") : (folders[f.folder.id] ?? t("folders.unknown"))}
            {f.folder.dir ? ` / ${f.folder.dir}` : ""}
          </span>
          <button
            type="button"
            onClick={() => set({ folder: null })}
            aria-label={t("filters.clearFolder")}
            className="grid size-7 shrink-0 place-items-center rounded-md hover:bg-muted"
          >
            <XIcon className="size-3.5" />
          </button>
        </span>
      )}

      {activeCount(f) > 0 && (
        <Button variant="ghost" onClick={() => onChange({ ...NO_FILTERS, text: f.text })}>
          {t("filters.clear")}
        </Button>
      )}

      <div className="ml-auto flex items-center gap-3">
        <span className="text-sm text-muted-foreground tabular-nums" role="status" data-testid="count">
          {shown === all.length
            ? t("gallery.itemsCount", { count: all.length, formatted: formatNumber(all.length, lang) })
            : t("gallery.shownOf", { shown: formatNumber(shown, lang), total: formatNumber(all.length, lang) })}
        </span>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label={t("gallery.cellSize")}>
              <SquaresFourIcon />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuLabel>{t("gallery.cellSize")}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            {(Object.keys(CELL_SIZES) as CellSize[]).map((s) => (
              <DropdownMenuCheckboxItem key={s} checked={cellSize === s} onCheckedChange={() => onCellSize(s)}>
                {t(`gallery.sizes.${s}`)}
              </DropdownMenuCheckboxItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}
