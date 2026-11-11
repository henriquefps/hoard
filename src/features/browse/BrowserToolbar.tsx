import {
  ArrowDownIcon,
  ArrowsClockwiseIcon,
  ArrowUpIcon,
  FilePlusIcon,
  FolderPlusIcon,
  FunnelSimpleIcon,
  MagnifyingGlassIcon,
  SortAscendingIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  FILTERS,
  type FileFilter,
  isFileFilter,
  SORT_KEYS,
  type SortKey,
  type SortOrder,
} from "@/lib/files"
import { useIsShort } from "@/hooks/use-mobile"
import { cn } from "@/lib/utils"
import { TABLE_COLUMNS } from "./FileRow"
import { ReadOnlyMenuNote } from "./ReadOnlyBadge"

interface ToolbarProps {
  query: string
  onQuery: (query: string) => void
  filter: FileFilter
  onFilter: (filter: FileFilter) => void
  sort: SortOrder
  onSort: (sort: SortOrder) => void
  onNewFolder: () => void
  onNewTextFile: () => void
  /** Set in a read-only folder: the New items stay visible, disabled, with this explanation. */
  readOnlyReason?: string
  onRefresh: () => void
  refreshing: boolean
  disabled: boolean
}

/**
 * Search, filter, sort and folder actions. Never wraps on desktop: the filter is a ToggleGroup
 * from `@2xl`, a Select below; below `@md` (phones) search gets its own full-width row. In the
 * page's compact bar the same controls are `CompactSearch` + `BrowserMenuItems`.
 */
export function BrowserToolbar({
  query,
  onQuery,
  filter,
  onFilter,
  sort,
  onSort,
  onNewFolder,
  onNewTextFile,
  readOnlyReason,
  onRefresh,
  refreshing,
  disabled,
}: ToolbarProps) {
  const { t } = useTranslation()
  // Short screens (landscape): the filter is the Select, so the search keeps its width.
  const short = useIsShort()
  return (
    <div
      className="flex flex-wrap items-center gap-2 @md:flex-nowrap"
      role="toolbar"
      aria-label={t("browse.toolbar")}
    >
      <InputGroup className="w-full min-w-0 @md:w-auto @md:flex-1">
        <InputGroupAddon>
          <MagnifyingGlassIcon />
        </InputGroupAddon>
        <InputGroupInput
          type="search"
          value={query}
          onChange={(e) => onQuery(e.target.value)}
          placeholder={t("browse.search")}
          aria-label={t("browse.search")}
          enterKeyHint="search"
          disabled={disabled}
        />
        {query && (
          <InputGroupAddon align="inline-end">
            <InputGroupButton
              size="icon-sm"
              onClick={() => onQuery("")}
              aria-label={t("browse.clearSearch")}
            >
              <XIcon />
            </InputGroupButton>
          </InputGroupAddon>
        )}
      </InputGroup>

      <Select
        value={filter}
        onValueChange={(v) => isFileFilter(v) && onFilter(v)}
        disabled={disabled}
      >
        <SelectTrigger
          className={cn("w-40", !short && "@2xl:hidden")}
          aria-label={t("browse.filter")}
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {FILTERS.map((option) => (
            <SelectItem key={option} value={option}>
              {t(`browse.filters.${option}`)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <ToggleGroup
        type="single"
        variant="outline"
        value={filter}
        onValueChange={(v) => isFileFilter(v) && onFilter(v)}
        aria-label={t("browse.filter")}
        className={cn("hidden shrink-0", !short && "@2xl:flex")}
        spacing={0}
        disabled={disabled}
      >
        {FILTERS.map((option) => (
          <ToggleGroupItem key={option} value={option}>
            {t(`browse.filters.${option}`)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>

      <div className="ml-auto flex shrink-0 items-center gap-2 @md:ml-0">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="outline"
              size="icon"
              aria-label={t("browse.sort")}
              title={t("browse.sort")}
              disabled={disabled}
            >
              <SortAscendingIcon />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="min-w-48">
            <SortItems sort={sort} onSort={onSort} />
          </DropdownMenuContent>
        </DropdownMenu>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="outline"
              size="icon"
              aria-label={t("browse.newMenu")}
              title={t("browse.newMenu")}
              disabled={disabled}
            >
              <FolderPlusIcon />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="min-w-48">
            <DropdownMenuItem onSelect={onNewFolder} disabled={Boolean(readOnlyReason)}>
              <FolderPlusIcon />
              {t("browse.newFolder")}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={onNewTextFile} disabled={Boolean(readOnlyReason)}>
              <FilePlusIcon />
              {t("browse.newTextFile")}
            </DropdownMenuItem>
            {readOnlyReason && (
              <>
                <DropdownMenuSeparator />
                <ReadOnlyMenuNote reason={readOnlyReason} />
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
        <Button
          variant="outline"
          size="icon"
          onClick={onRefresh}
          aria-label={t("browse.refresh")}
          title={t("browse.refresh")}
          disabled={refreshing}
        >
          <ArrowsClockwiseIcon
            className={cn(refreshing && "animate-spin motion-reduce:animate-none")}
          />
        </Button>
      </div>
    </div>
  )
}

/** Sort key and direction as radio items (the sort menu, and the compact bar's ⋯ sub-menu). */
function SortItems({ sort, onSort }: { sort: SortOrder; onSort: (sort: SortOrder) => void }) {
  const { t } = useTranslation()
  return (
    <>
      <DropdownMenuLabel>{t("browse.sortBy")}</DropdownMenuLabel>
      <DropdownMenuRadioGroup
        value={sort.key}
        onValueChange={(key) => onSort({ ...sort, key: key as SortKey })}
      >
        {SORT_KEYS.map((key) => (
          <DropdownMenuRadioItem key={key} value={key}>
            {t(`browse.sortKeys.${key}`)}
          </DropdownMenuRadioItem>
        ))}
      </DropdownMenuRadioGroup>
      <DropdownMenuSeparator />
      <DropdownMenuRadioGroup
        value={sort.direction}
        onValueChange={(direction) =>
          onSort({ ...sort, direction: direction as SortOrder["direction"] })
        }
      >
        <DropdownMenuRadioItem value="asc">
          <ArrowUpIcon />
          {t(`browse.directions.${sort.key}.asc`)}
        </DropdownMenuRadioItem>
        <DropdownMenuRadioItem value="desc">
          <ArrowDownIcon />
          {t(`browse.directions.${sort.key}.desc`)}
        </DropdownMenuRadioItem>
      </DropdownMenuRadioGroup>
    </>
  )
}

/**
 * The toolbar's controls as ⋯ menu items, for the page's compact bar (the search is its own
 * `CompactSearch` icon there): filter, sort, new folder / text file, refresh.
 */
export function BrowserMenuItems({
  filter,
  onFilter,
  sort,
  onSort,
  onNewFolder,
  onNewTextFile,
  readOnlyReason,
  onRefresh,
  refreshing,
  disabled,
}: Omit<ToolbarProps, "query" | "onQuery">) {
  const { t } = useTranslation()
  return (
    <>
      <DropdownMenuSub>
        <DropdownMenuSubTrigger disabled={disabled}>
          <FunnelSimpleIcon />
          {t("browse.filter")}
          <span className="ml-auto pl-2 text-muted-foreground">
            {t(`browse.filters.${filter}`)}
          </span>
        </DropdownMenuSubTrigger>
        <DropdownMenuSubContent>
          <DropdownMenuRadioGroup
            value={filter}
            onValueChange={(v) => isFileFilter(v) && onFilter(v)}
          >
            {FILTERS.map((option) => (
              <DropdownMenuRadioItem key={option} value={option}>
                {t(`browse.filters.${option}`)}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuSubContent>
      </DropdownMenuSub>
      <DropdownMenuSub>
        <DropdownMenuSubTrigger disabled={disabled}>
          <SortAscendingIcon />
          {t("browse.sort")}
        </DropdownMenuSubTrigger>
        <DropdownMenuSubContent className="min-w-48">
          <SortItems sort={sort} onSort={onSort} />
        </DropdownMenuSubContent>
      </DropdownMenuSub>
      <DropdownMenuSeparator />
      <DropdownMenuItem onSelect={onNewFolder} disabled={disabled || Boolean(readOnlyReason)}>
        <FolderPlusIcon />
        {t("browse.newFolder")}
      </DropdownMenuItem>
      <DropdownMenuItem onSelect={onNewTextFile} disabled={disabled || Boolean(readOnlyReason)}>
        <FilePlusIcon />
        {t("browse.newTextFile")}
      </DropdownMenuItem>
      <DropdownMenuItem onSelect={onRefresh} disabled={refreshing}>
        <ArrowsClockwiseIcon />
        {t("browse.refresh")}
      </DropdownMenuItem>
      {readOnlyReason && <ReadOnlyMenuNote reason={readOnlyReason} />}
    </>
  )
}

/** Desktop table header (from `@xl`): select all and sortable columns. Sticky inside the list. */
export function SortHeader({
  sort,
  onSort,
  allSelected,
  someSelected,
  onSelectAll,
}: {
  sort: SortOrder
  onSort: (sort: SortOrder) => void
  allSelected: boolean
  someSelected: boolean
  onSelectAll: (all: boolean) => void
}) {
  const { t } = useTranslation()
  const column = (key: SortKey, className?: string) => {
    const active = sort.key === key
    const Arrow = sort.direction === "asc" ? ArrowUpIcon : ArrowDownIcon
    return (
      <button
        type="button"
        onClick={() =>
          onSort({ key, direction: active && sort.direction === "asc" ? "desc" : "asc" })
        }
        className={cn(
          "inline-flex h-9 items-center gap-1 rounded-md text-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50",
          active && "font-medium text-foreground",
          className
        )}
        aria-sort={active ? (sort.direction === "asc" ? "ascending" : "descending") : undefined}
      >
        {t(`browse.sortKeys.${key}`)}
        {active && <Arrow aria-hidden className="size-3.5" />}
      </button>
    )
  }
  return (
    <div
      className={cn(
        // Under the page's compact bar (Page sets --page-sticky-top on the scroller). On short
        // screens it scrolls away with the header: sorting is in the compact bar's ⋯ menu.
        "sticky top-[var(--page-sticky-top,0px)] z-10 hidden h-11 items-center border-b bg-card @xl:grid short:static short:h-9",
        TABLE_COLUMNS
      )}
    >
      <div className="flex items-center justify-center pl-2">
        <Checkbox
          checked={allSelected ? true : someSelected ? "indeterminate" : false}
          onCheckedChange={(checked) => onSelectAll(checked === true)}
          aria-label={t("browse.selectAll")}
          className="size-5"
        />
      </div>
      <div className="pl-[3.25rem]">{column("name")}</div>
      <div className="flex justify-end pr-3">{column("size")}</div>
      <div className="pr-3">{column("modified")}</div>
      <div className="hidden pr-3 text-sm text-muted-foreground @3xl:block">{t("browse.kind")}</div>
      <div />
    </div>
  )
}
