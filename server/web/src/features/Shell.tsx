import {
  CircleNotchIcon,
  FolderSimpleIcon,
  GearSixIcon,
  ImagesIcon,
  MagnifyingGlassIcon,
  SignOutIcon,
  TranslateIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useEffect, useRef } from "react"
import { useTranslation } from "react-i18next"
import { AppLogo } from "@/components/AppLogo"
import { ThemeMenu } from "@/components/ThemeMenu"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { isLanguage, LANGUAGES, setLanguage } from "@/i18n"
import { api, type Session } from "@/lib/api"
import { filtersToSearch } from "@/lib/filters"
import { useFilters } from "@/lib/useFilters"
import { navigate, useLocation, viewerItem } from "@/lib/router"
import { startPolling, useLibraryState } from "@/lib/store"
import { cn } from "@/lib/utils"
import { GalleryPage } from "./gallery/GalleryPage"
import { FoldersPage } from "./folders/FoldersPage"
import { Viewer } from "./viewer/Viewer"

/**
 * hfps shell: a fixed 56px header on the tinted background, one card filling the rest of the screen, content scrolling
 * inside the card (the document never scrolls). The viewer opens over the gallery, which stays mounted (scroll kept).
 */
export function Shell({ session, onSignedOut }: { session: Session; onSignedOut: () => void }) {
  const { t, i18n } = useTranslation()
  const loc = useLocation()
  const { pending } = useLibraryState()
  const [filters, setFilters] = useFilters()
  const searchRef = useRef<HTMLInputElement>(null)
  const itemId = viewerItem(loc.path)
  const onFolders = loc.path.startsWith("/folders")

  useEffect(() => startPolling(), [])
  useEffect(() => {
    // "/" focuses search (not while typing or in the viewer).
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (e.key === "/" && !itemId && !/input|textarea|select/i.test(target.tagName) && !target.isContentEditable) {
        e.preventDefault()
        searchRef.current?.focus()
      }
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [itemId])

  const signOut = async () => {
    await api("/web/session", { method: "DELETE" }).catch(() => {})
    onSignedOut()
  }

  const search = (text: string) => {
    if (onFolders) navigate("/", filtersToSearch({ ...filters, text }))
    else setFilters({ ...filters, text }, true)
  }

  const navLink = (to: string, active: boolean, Icon: typeof ImagesIcon, label: string) => (
    <a
      href={to}
      aria-current={active ? "page" : undefined}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey) return
        e.preventDefault()
        navigate(to, to === "/" ? filtersToSearch(filters) : undefined)
      }}
      className={cn(
        "inline-flex h-9 items-center gap-2 rounded-md px-3 text-sm font-medium transition-colors hover:bg-sidebar-accent",
        active ? "bg-sidebar-accent text-foreground" : "text-muted-foreground"
      )}
    >
      <Icon className="size-4" />
      <span className="max-md:sr-only">{label}</span>
    </a>
  )

  return (
    <div className="flex h-dvh flex-col bg-background pt-[env(safe-area-inset-top)] [--header-height:--spacing(14)]">
      <header className="flex h-(--header-height) shrink-0 items-center gap-2 px-3 md:px-4">
        <AppLogo />
        <nav aria-label={t("nav.label")} className="ml-2 flex items-center gap-1 md:ml-4">
          {navLink("/", !onFolders, ImagesIcon, t("nav.gallery"))}
          {navLink("/folders", onFolders, FolderSimpleIcon, t("nav.folders"))}
        </nav>
        <div className="mx-auto flex w-full max-w-md min-w-0 flex-1 items-center px-2">
          <label className="relative flex w-full items-center">
            <span className="sr-only">{t("search.label")}</span>
            <MagnifyingGlassIcon className="pointer-events-none absolute left-3 size-4 text-muted-foreground" />
            <input
              ref={searchRef}
              type="search"
              value={filters.text}
              onChange={(e) => search(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  search("")
                  e.currentTarget.blur()
                }
              }}
              placeholder={t("search.placeholder")}
              className="h-9 w-full rounded-md border border-input bg-card pr-9 pl-9 text-sm shadow-xs outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 [&::-webkit-search-cancel-button]:hidden"
            />
            {filters.text && (
              <button
                type="button"
                onClick={() => search("")}
                aria-label={t("search.clear")}
                className="absolute right-1 grid size-7 place-items-center rounded-md text-muted-foreground hover:bg-muted"
              >
                <XIcon className="size-4" />
              </button>
            )}
          </label>
        </div>
        <div className="ml-auto flex items-center gap-1">
          {pending > 0 && (
            <Tooltip>
              <TooltipTrigger asChild>
                <span role="status" className="grid size-9 place-items-center text-muted-foreground">
                  <CircleNotchIcon className="size-4 animate-spin motion-reduce:animate-none" />
                  <span className="sr-only">{t("sync.pending", { count: pending })}</span>
                </span>
              </TooltipTrigger>
              <TooltipContent>{t("sync.pending", { count: pending })}</TooltipContent>
            </Tooltip>
          )}
          <ThemeMenu />
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" aria-label={t("account.menu")}>
                <GearSixIcon />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuLabel className="font-normal text-muted-foreground">
                {t("account.version", { version: session.version })}
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuLabel>
                <span className="inline-flex items-center gap-2">
                  <TranslateIcon className="size-4" />
                  {t("account.language")}
                </span>
              </DropdownMenuLabel>
              <DropdownMenuRadioGroup value={i18n.language} onValueChange={(v) => isLanguage(v) && setLanguage(v)}>
                {LANGUAGES.map((l) => (
                  <DropdownMenuRadioItem key={l} value={l}>
                    {t(`languages.${l}`)}
                  </DropdownMenuRadioItem>
                ))}
              </DropdownMenuRadioGroup>
              <DropdownMenuSeparator />
              <DropdownMenuItem asChild>
                <a href="/admin/">
                  <GearSixIcon />
                  {t("account.setup")}
                </a>
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => void signOut()}>
                <SignOutIcon />
                {t("account.signOut")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      <main className="mx-2 mb-[max(0.5rem,env(safe-area-inset-bottom))] flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border bg-card shadow-xs md:mx-3 md:mb-3">
        {onFolders ? <FoldersPage /> : <GalleryPage previews={session.previews} />}
      </main>
      {itemId && <Viewer itemId={itemId} />}
    </div>
  )
}
