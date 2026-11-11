import {
  ArrowLeftIcon,
  CaretRightIcon,
  DotsThreeIcon,
  DotsThreeVerticalIcon,
  MagnifyingGlassIcon,
  XIcon,
} from "@phosphor-icons/react"
import { Link, useRouter, type LinkOptions } from "@tanstack/react-router"
import {
  createContext,
  Fragment,
  type MouseEvent,
  type ReactNode,
  type Ref,
  type RefObject,
  useCallback,
  useContext,
  useLayoutEffect,
  useRef,
  useState,
} from "react"
import { useTranslation } from "react-i18next"
import { MiddleTruncate } from "@/components/MiddleTruncate"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { Skeleton } from "@/components/ui/skeleton"
import { cn } from "@/lib/utils"
import { goBackTo } from "@/lib/historyTrail"
import {
  isRestoredScroll,
  SCROLL_IDS,
  type ScrollId,
  scrollSelector,
} from "@/lib/scrollRestoration"
import { type Crumb, fitCrumbs, headerCrumbs, parentOf } from "./breadcrumbs"
import { nextCollapse, visibleHeader } from "./collapse"

/** What a page shows in its header; rendered by `Page`, or by a `PageScroll` inside a fill page. */
interface Chrome {
  title: string
  titleActions?: ReactNode
  description?: string
  meta?: ReactNode
  actions?: ReactNode
  menu?: ReactNode
  breadcrumbs?: Crumb[]
}

const PageContext = createContext<{ chrome: Chrome; claim: () => () => void } | null>(null)

/**
 * A page inside the card surface: a header (breadcrumbs, back button, title, description, meta
 * line, actions) and a body. Pages below the top level pass their `breadcrumbs`; the back button
 * goes to the parent level of the trail, not back in history.
 *
 * The header collapses on scroll (1.9.1): it scrolls away with the content, a compact bar stays
 * on top (back, the title on one line, the actions as icons, one ⋯ menu), and scrolling up brings
 * the full header back at once (quick-return, `collapse.ts`). A regular page scrolls in the card
 * (the shell). A `fill` page (its parts scroll on their own) puts its scrolling part in a
 * `PageScroll`, which then carries the header, so the header scrolls away with that list; a fill
 * page without one keeps a static header.
 */
export function Page({
  title,
  titleActions,
  description,
  meta,
  actions,
  menu,
  breadcrumbs,
  fill = false,
  children,
}: {
  /** A live line under the title (the route in use, item counts): one line, wraps if it must. */
  meta?: ReactNode
  title: string
  /** Buttons right after the title. */
  titleActions?: ReactNode
  description?: string
  /** Buttons on the right of the title. Wrap their text in `ActionLabel` (icons when compact). */
  actions?: ReactNode
  /** Items of the page's ⋯ menu (`DropdownMenuItem`s); merged with the list's in the compact bar. */
  menu?: ReactNode
  breadcrumbs?: Crumb[]
  fill?: boolean
  children: ReactNode
}) {
  const [claimed, setClaimed] = useState(0)
  const claim = useCallback(() => {
    setClaimed((n) => n + 1)
    return () => setClaimed((n) => n - 1)
  }, [])
  const chrome: Chrome = { title, titleActions, description, meta, actions, menu, breadcrumbs }
  return (
    <PageContext.Provider value={{ chrome, claim }}>
      <div
        className={cn(
          "flex flex-1 flex-col",
          // The inset card (SidebarInset) is a fixed-height flex column: flex-1 + min-h-0 makes the
          // page exactly as tall as the card, and its parts scroll inside.
          fill && "min-h-0 overflow-hidden"
        )}
        data-fill={fill ? "true" : undefined}
      >
        {!fill ? (
          <PageChrome chrome={chrome} scroller={shellOf} />
        ) : claimed === 0 ? (
          <header className="shrink-0 border-b">
            <LargeHeader chrome={chrome} />
          </header>
        ) : null}
        <div
          className={cn(
            "flex flex-1 flex-col gap-6 px-4 py-4 md:px-6 md:py-6",
            fill && "min-h-0 gap-3 overflow-hidden pb-3 md:pb-4"
          )}
        >
          {children}
        </div>
      </div>
    </PageContext.Provider>
  )
}

const shellOf = (element: HTMLElement) =>
  element.closest<HTMLElement>(scrollSelector(SCROLL_IDS.shell))
const scrollerOf = (element: HTMLElement) => element.closest<HTMLElement>("[data-page-scroll]")

/**
 * The scrolling part of a `fill` page (a file list, the culling grid): it carries the page header,
 * which scrolls away with the content, plus the page's `toolbar` (search, filters), which collapses
 * with it. `compactTools` (e.g. `CompactSearch`) and `compactMenu` (⋯ items: filter, sort…) are
 * what the compact bar offers in their place. Use negative margins to undo the page's padding.
 */
export function PageScroll({
  ref,
  scrollId,
  toolbar,
  compactTools,
  compactMenu,
  className,
  children,
  ...props
}: {
  ref?: Ref<HTMLDivElement>
  /** Scroll restoration id; set it only once the list is there (lib/scrollRestoration.ts). */
  scrollId?: ScrollId
  toolbar?: ReactNode
  compactTools?: ReactNode
  compactMenu?: ReactNode
  className?: string
  children: ReactNode
} & Omit<React.ComponentProps<"div">, "ref" | "children">) {
  const context = useContext(PageContext)
  const claim = context?.claim
  useLayoutEffect(() => claim?.(), [claim])
  return (
    <div
      ref={ref}
      data-scroll-restoration-id={scrollId}
      data-page-scroll=""
      className={cn("relative min-h-0 flex-1 overflow-y-auto overscroll-contain", className)}
      {...props}
    >
      {context && (
        <PageChrome
          chrome={context.chrome}
          // By DOM, not by ref: the child's layout effect runs before this div's ref is attached.
          scroller={scrollerOf}
          toolbar={toolbar}
          compactTools={compactTools}
          compactMenu={compactMenu}
        />
      )}
      {children}
    </div>
  )
}

/** While true (the compact search has focus) the full header stays away. */
const CompactContext = createContext<{ setPinned: (pinned: boolean) => void }>({
  setPinned: () => {},
})

/** Height of the compact bar; sticky parts of a list (table header) stick right under it. */
const COMPACT_HEIGHT = "3rem"

function PageChrome({
  chrome,
  scroller,
  toolbar,
  compactTools,
  compactMenu,
}: {
  chrome: Chrome
  scroller: (header: HTMLElement) => HTMLElement | null
  toolbar?: ReactNode
  compactTools?: ReactNode
  compactMenu?: ReactNode
}) {
  const headerRef = useRef<HTMLElement>(null)
  const compactRef = useRef<HTMLDivElement>(null)
  const [pinned, setPinned] = useState(false)
  useCollapsingHeader(headerRef, compactRef, scroller, pinned)
  const { menu } = chrome
  return (
    <>
      <div data-slot="page-compact" className="sticky top-0 z-20 h-0">
        <div
          ref={compactRef}
          inert
          aria-hidden
          className="group/compact absolute inset-x-0 top-0 flex h-12 items-center gap-1 border-b bg-card px-2 md:px-4 [&_[data-action-label]]:sr-only"
        >
          <CompactContext.Provider value={{ setPinned }}>
            <div className="contents group-has-[[data-search-open]]/compact:hidden">
              <BackButton trail={chrome.breadcrumbs} />
              <span
                className={cn(
                  "min-w-0 flex-1 text-[0.9375rem] font-semibold",
                  !parentOf(chrome.breadcrumbs ?? [])?.link && "pl-2"
                )}
              >
                <MiddleTruncate text={chrome.title} tail={12} />
              </span>
            </div>
            {compactTools}
            <div className="flex shrink-0 items-center gap-1 group-has-[[data-search-open]]/compact:hidden">
              {chrome.actions}
              {(compactMenu || menu) && (
                <MoreMenu>
                  {compactMenu}
                  {compactMenu && menu && <DropdownMenuSeparator />}
                  {menu}
                </MoreMenu>
              )}
            </div>
          </CompactContext.Provider>
        </div>
      </div>
      <header
        ref={headerRef}
        data-slot="page-header"
        className="sticky top-0 z-30 shrink-0 border-b bg-card"
        style={{ "--page-compact-height": COMPACT_HEIGHT } as React.CSSProperties}
      >
        <LargeHeader chrome={chrome} />
        {toolbar && <div className="px-4 pb-3 md:px-6 short:pb-2">{toolbar}</div>}
      </header>
    </>
  )
}

/**
 * Quick-return: writes the header's sticky `top` on every scroll (no React state per frame), and
 * keeps whichever of the two bars is covered out of the tab order and the accessibility tree.
 */
function useCollapsingHeader(
  headerRef: RefObject<HTMLElement | null>,
  compactRef: RefObject<HTMLElement | null>,
  getScroller: (header: HTMLElement) => HTMLElement | null,
  pinned: boolean
) {
  const pinnedRef = useRef(pinned)
  const applyRef = useRef<() => void>(() => {})
  useLayoutEffect(() => {
    const header = headerRef.current
    const compact = compactRef.current
    const scroller = header && getScroller(header)
    if (!header || !compact || !scroller) return
    // A list's sticky parts (table header) stick under the compact bar.
    scroller.style.setProperty("--page-sticky-top", COMPACT_HEIGHT)
    let collapse = 0
    let last = scroller.scrollTop
    const apply = () => {
      const height = header.offsetHeight
      const top = scroller.scrollTop
      collapse = pinnedRef.current
        ? height
        : isRestoredScroll(scroller)
          ? // Scroll restoration is a jump, not the user scrolling up: header stays collapsed.
            Math.min(height, Math.max(0, top))
          : nextCollapse(collapse, last, top, height)
      last = top
      header.style.top = `${-collapse}px`
      header.style.visibility = pinnedRef.current ? "hidden" : ""
      const shown = pinnedRef.current ? 0 : visibleHeader(height, top, collapse)
      const compactShown = shown < compact.offsetHeight
      setHidden(compact, !compactShown)
      setHidden(header, shown === 0)
    }
    applyRef.current = apply
    apply()
    scroller.addEventListener("scroll", apply, { passive: true })
    const observer = new ResizeObserver(apply)
    observer.observe(header)
    return () => {
      scroller.removeEventListener("scroll", apply)
      observer.disconnect()
      scroller.style.removeProperty("--page-sticky-top")
    }
    // The scroller is found once per mount; a page keeps its scroller.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useLayoutEffect(() => {
    pinnedRef.current = pinned
    applyRef.current()
  }, [pinned])
}

function setHidden(element: HTMLElement, hidden: boolean) {
  if (element.inert === hidden) return
  element.inert = hidden
  if (hidden) element.setAttribute("aria-hidden", "true")
  else element.removeAttribute("aria-hidden")
}

/**
 * Breadcrumbs (one line), back + title + actions, description, meta line. From `sm` the actions
 * sit at the end of the title row; on phones they move up to the breadcrumb row, so the title
 * keeps the whole width.
 */
function LargeHeader({ chrome }: { chrome: Chrome }) {
  const { title, titleActions, description, meta, actions, menu, breadcrumbs } = chrome
  const crumbs = breadcrumbs ? headerCrumbs(breadcrumbs, title) : []
  const hasCrumbs = crumbs.length > 0
  return (
    <div className="flex min-w-0 flex-col gap-1 px-4 pt-3 pb-3 md:px-6 md:pt-4 short:gap-0.5 short:pt-2 short:pb-2 short:md:pt-2">
      <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-2 gap-y-1 short:gap-y-0.5">
        {hasCrumbs && (
          <div className="col-start-1 row-start-1 min-w-0 sm:col-span-2">
            <CrumbRow crumbs={crumbs} />
          </div>
        )}
        <div
          className={cn(
            "flex min-h-9 min-w-0 items-center gap-1",
            hasCrumbs ? "col-span-2 row-start-2 sm:col-span-1" : "col-start-1 row-start-1"
          )}
        >
          <BackButton trail={breadcrumbs} className="-ml-2" />
          <h1 className="min-w-0 text-lg font-semibold">
            <MiddleTruncate text={title} tail={12} />
          </h1>
          {titleActions && (
            <div className="ml-1 flex shrink-0 items-center gap-1">{titleActions}</div>
          )}
        </div>
        {(actions || menu) && (
          <div
            className={cn(
              "col-start-2 row-start-1 flex shrink-0 items-center justify-end gap-1",
              hasCrumbs && "sm:row-start-2"
            )}
          >
            {actions}
            {menu && <MoreMenu>{menu}</MoreMenu>}
          </div>
        )}
      </div>
      {description && <p className="text-[0.9375rem] text-muted-foreground">{description}</p>}
      {meta && (
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-[0.8125rem] text-muted-foreground">
          {meta}
        </div>
      )}
    </div>
  )
}

/**
 * The text of a labelled header action (e.g. "Cull photos"): visible in the full header, an icon
 * (screen-reader text) in the compact bar and on short screens. Give the button a `title` too.
 */
export function ActionLabel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span data-action-label="" className={cn("short:sr-only", className)}>
      {children}
    </span>
  )
}

function MoreMenu({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={t("common.more")} title={t("common.more")}>
          <DotsThreeVerticalIcon weight="bold" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-52">
        {children}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * The search of a list, in the compact bar: an icon that expands into the field (over the title
 * and the actions). While it is open the full header stays away (it would cover the field);
 * closing it (×, Escape) clears the search.
 */
export function CompactSearch({
  value,
  onChange,
  label,
  disabled,
}: {
  value: string
  onChange: (value: string) => void
  label: string
  disabled?: boolean
}) {
  const { t } = useTranslation()
  const { setPinned } = useContext(CompactContext)
  const [open, setOpen] = useState(false)
  useLayoutEffect(() => {
    if (!open) return
    setPinned(true)
    return () => setPinned(false)
  }, [open, setPinned])
  if (!open) {
    return (
      <Button
        variant="ghost"
        size="icon"
        aria-label={label}
        title={label}
        disabled={disabled}
        onClick={() => setOpen(true)}
        className={cn("shrink-0", value && "text-primary")}
      >
        <MagnifyingGlassIcon weight={value ? "bold" : "regular"} />
      </Button>
    )
  }
  const close = () => {
    onChange("")
    setOpen(false)
  }
  return (
    <div data-search-open="" className="flex min-w-0 flex-1 items-center gap-1">
      <InputGroup className="min-w-0 flex-1">
        <InputGroupAddon>
          <MagnifyingGlassIcon />
        </InputGroupAddon>
        <InputGroupInput
          type="search"
          autoFocus
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && close()}
          placeholder={label}
          aria-label={label}
          enterKeyHint="search"
        />
      </InputGroup>
      <Button
        variant="ghost"
        size="icon"
        className="shrink-0"
        onClick={close}
        aria-label={t("page.closeSearch")}
        title={t("page.closeSearch")}
      >
        <XIcon />
      </Button>
    </div>
  )
}

// A crumb links to an ancestor of the current page, which TanStack would otherwise mark active
// (aria-current="page") by prefix; only the last crumb is the current page.
const ANCESTOR = { exact: true, includeSearch: true }

function useBackLabel() {
  const { t } = useTranslation()
  return (parent: Crumb) =>
    parent.label === undefined ? t("common.back") : t("common.backTo", { name: parent.label })
}

/**
 * Going up to a page we came from is a history step, so the page returns with its scroll position
 * (lib/historyTrail.ts); other clicks (new tab, modifier keys) keep the link's own behaviour.
 */
function useUpClick() {
  const router = useRouter()
  return (link: LinkOptions) => (event: MouseEvent<HTMLAnchorElement>) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)
      return
    if (goBackTo(router, link)) event.preventDefault()
  }
}

/** ← to the parent level of the trail (every width). */
function BackButton({ trail, className }: { trail?: Crumb[]; className?: string }) {
  const backLabel = useBackLabel()
  const upClick = useUpClick()
  const parent = trail && parentOf(trail)
  if (!parent?.link) return null
  return (
    <Button variant="ghost" size="icon" className={cn("shrink-0", className)} asChild>
      <Link
        {...parent.link}
        activeOptions={ANCESTOR}
        aria-label={backLabel(parent)}
        title={backLabel(parent)}
        onClick={upClick(parent.link)}
      >
        <ArrowLeftIcon />
      </Link>
    </Button>
  )
}

/** Gap between crumbs and separators (`gap-1`), in px. */
const CRUMB_GAP = 4
const CRUMB_CLASS = "block max-w-48 truncate"

/**
 * The trail above the title on one line, never wrapping: the tail that fits (`fitCrumbs`), the
 * earlier levels behind a "…" menu. Widths come from an invisible copy of the trail.
 */
function CrumbRow({ crumbs }: { crumbs: Crumb[] }) {
  const { t } = useTranslation()
  const upClick = useUpClick()
  const rowRef = useRef<HTMLOListElement>(null)
  const measureRef = useRef<HTMLDivElement>(null)
  const [shown, setShown] = useState(crumbs.length)
  const key = crumbs.map((c) => c.label ?? "\u0000").join("\u0001")
  useLayoutEffect(() => {
    const row = rowRef.current
    const measure = measureRef.current
    if (!row || !measure) return
    const update = () => {
      const width = (selector: string) =>
        measure.querySelector(selector)?.getBoundingClientRect().width ?? 0
      const widths = [...measure.querySelectorAll("[data-measure=crumb]")].map(
        (e) => e.getBoundingClientRect().width
      )
      setShown(
        fitCrumbs(
          widths,
          row.clientWidth,
          width("[data-measure=sep]") + 2 * CRUMB_GAP,
          width("[data-measure=more]")
        )
      )
    }
    update()
    const observer = new ResizeObserver(update)
    observer.observe(row)
    observer.observe(measure)
    return () => observer.disconnect()
  }, [key])
  const hidden = crumbs.slice(0, crumbs.length - shown)
  const visible = crumbs.slice(crumbs.length - shown)
  return (
    <nav aria-label={t("page.breadcrumb")} className="relative min-w-0">
      <div
        ref={measureRef}
        aria-hidden
        className="pointer-events-none invisible absolute top-0 left-0 flex h-0 items-center overflow-hidden text-sm whitespace-nowrap"
      >
        {crumbs.map((crumb, index) => (
          <span key={index} data-measure="crumb" className="shrink-0">
            <CrumbLabel label={crumb.label} />
          </span>
        ))}
        <CaretRightIcon data-measure="sep" className="size-3.5 shrink-0" />
        <span data-measure="more" className="inline-flex h-7 w-8 shrink-0" />
      </div>
      <ol
        ref={rowRef}
        className="flex min-h-7 min-w-0 items-center gap-1 overflow-hidden text-sm whitespace-nowrap text-muted-foreground"
      >
        {hidden.length > 0 && (
          <>
            <li className="shrink-0">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    type="button"
                    aria-label={t("page.earlierLevels", { count: hidden.length })}
                    title={t("page.earlierLevels", { count: hidden.length })}
                    className="relative inline-flex h-7 w-8 items-center justify-center rounded-md outline-none before:absolute before:-inset-2 hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                  >
                    <DotsThreeIcon weight="bold" className="size-4" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start" className="max-w-72 min-w-48">
                  {hidden.map((crumb, index) =>
                    crumb.link ? (
                      <DropdownMenuItem key={index} asChild>
                        <Link
                          {...crumb.link}
                          activeOptions={ANCESTOR}
                          onClick={upClick(crumb.link)}
                        >
                          <span className="truncate">{crumb.label}</span>
                        </Link>
                      </DropdownMenuItem>
                    ) : (
                      <DropdownMenuItem key={index} disabled>
                        <span className="truncate">{crumb.label}</span>
                      </DropdownMenuItem>
                    )
                  )}
                </DropdownMenuContent>
              </DropdownMenu>
            </li>
            <Separator />
          </>
        )}
        {visible.map((crumb, index) => (
          <Fragment key={hidden.length + index}>
            {index > 0 && <Separator />}
            <li className={cn("min-w-0", index < visible.length - 1 && "shrink-0")}>
              {crumb.link ? (
                <Link
                  {...crumb.link}
                  activeOptions={ANCESTOR}
                  onClick={upClick(crumb.link)}
                  className="block rounded-sm outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  <CrumbLabel label={crumb.label} />
                </Link>
              ) : (
                <CrumbLabel label={crumb.label} />
              )}
            </li>
          </Fragment>
        ))}
      </ol>
    </nav>
  )
}

function Separator() {
  return (
    <li role="presentation" aria-hidden className="shrink-0">
      <CaretRightIcon className="size-3.5" />
    </li>
  )
}

function CrumbLabel({ label }: { label: string | undefined }) {
  if (label === undefined) {
    return (
      <Skeleton data-slot="breadcrumb-skeleton" className="inline-block h-4 w-20 align-middle" />
    )
  }
  return (
    <span className={CRUMB_CLASS} title={label}>
      {label}
    </span>
  )
}
