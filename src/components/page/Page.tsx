import { ArrowLeftIcon } from "@phosphor-icons/react"
import { Link, useRouter, type LinkOptions } from "@tanstack/react-router"
import { Fragment, type MouseEvent, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { cn } from "@/lib/utils"
import { goBackTo } from "@/lib/historyTrail"
import { type Crumb, parentOf } from "./breadcrumbs"

/**
 * A page inside the card surface: a header (breadcrumb, back button, title, description, actions)
 * and a body. Pages below the top level pass their `breadcrumbs`; the back button goes to the
 * parent level of the trail, not back in history. A `fill` page (the room) is exactly as tall as
 * the screen below the top bar: its body does not grow the page, its parts scroll inside.
 */
export function Page({
  title,
  titleActions,
  description,
  meta,
  actions,
  breadcrumbs,
  fill = false,
  children,
}: {
  /** A live line under the title (the route in use, item counts). */
  meta?: ReactNode
  title: string
  /** Buttons right after the title (the room's Go live and chat). */
  titleActions?: ReactNode
  description?: string
  actions?: ReactNode
  breadcrumbs?: Crumb[]
  fill?: boolean
  children: ReactNode
}) {
  const parent = breadcrumbs && parentOf(breadcrumbs)
  const backLabel = useBackLabel()
  const upClick = useUpClick()
  return (
    <div
      className={cn(
        "flex flex-1 flex-col",
        // The inset card (SidebarInset) is a fixed-height flex column: flex-1 + min-h-0 makes the
        // page exactly as tall as the card, and its parts scroll inside.
        fill && "min-h-0 overflow-hidden"
      )}
      data-fill={fill ? "true" : undefined}
    >
      <header className="flex shrink-0 flex-wrap items-start justify-between gap-x-4 gap-y-2 border-b px-4 py-4 md:px-6">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          {breadcrumbs && <PageBreadcrumb trail={breadcrumbs} />}
          <div className="flex min-w-0 items-center gap-1">
            {parent?.link && (
              // Below 768 px the collapsed breadcrumb ("← Parent") is the back control.
              <Button variant="ghost" size="icon" className="-ml-2 hidden md:inline-flex" asChild>
                <Link
                  {...parent.link}
                  activeOptions={ANCESTOR}
                  aria-label={backLabel(parent)}
                  onClick={upClick(parent.link)}
                >
                  <ArrowLeftIcon />
                </Link>
              </Button>
            )}
            <h1 className="min-w-0 truncate text-lg font-semibold">{title}</h1>
            {titleActions && (
              <div className="ml-1 flex shrink-0 items-center gap-1">{titleActions}</div>
            )}
          </div>
          {description && <p className="text-[0.9375rem] text-muted-foreground">{description}</p>}
          {meta}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-1">{actions}</div>}
      </header>
      <div
        className={cn(
          "flex flex-1 flex-col gap-6 px-4 py-4 md:px-6 md:py-6",
          fill && "min-h-0 gap-3 overflow-hidden pb-3 md:pb-4"
        )}
      >
        {children}
      </div>
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
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    if (goBackTo(router, link)) event.preventDefault()
  }
}

/** The full trail from 768 px; below it, only "← <parent>". */
function PageBreadcrumb({ trail }: { trail: Crumb[] }) {
  const parent = parentOf(trail)
  const backLabel = useBackLabel()
  const upClick = useUpClick()
  return (
    <Breadcrumb aria-label="Breadcrumb">
      {parent?.link && (
        <Link
          {...parent.link}
          activeOptions={ANCESTOR}
          aria-label={backLabel(parent)}
          onClick={upClick(parent.link)}
          data-slot="breadcrumb-back"
          className="-ml-1 inline-flex max-w-full items-center gap-1.5 rounded-md px-1 py-0.5 text-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 md:hidden"
        >
          <ArrowLeftIcon aria-hidden className="shrink-0" />
          <CrumbLabel label={parent.label} />
        </Link>
      )}
      <BreadcrumbList className="hidden md:flex">
        {trail.map((crumb, index) => {
          const current = index === trail.length - 1
          return (
            <Fragment key={index}>
              {index > 0 && <BreadcrumbSeparator />}
              <BreadcrumbItem className="min-w-0">
                {current ? (
                  <BreadcrumbPage>
                    <CrumbLabel label={crumb.label} />
                  </BreadcrumbPage>
                ) : !crumb.link ? (
                  <CrumbLabel label={crumb.label} />
                ) : (
                  <BreadcrumbLink asChild>
                    <Link
                      {...crumb.link}
                      activeOptions={ANCESTOR}
                      onClick={upClick(crumb.link)}
                      className="rounded-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                    >
                      <CrumbLabel label={crumb.label} />
                    </Link>
                  </BreadcrumbLink>
                )}
              </BreadcrumbItem>
            </Fragment>
          )
        })}
      </BreadcrumbList>
    </Breadcrumb>
  )
}

function CrumbLabel({ label }: { label: string | undefined }) {
  if (label === undefined) {
    return (
      <Skeleton data-slot="breadcrumb-skeleton" className="inline-block h-4 w-20 align-middle" />
    )
  }
  return <span className="block max-w-56 truncate">{label}</span>
}
