import {
  ArrowsDownUpIcon,
  FolderSimpleStarIcon,
  GearSixIcon,
  HardDrivesIcon,
  HouseIcon,
  type Icon,
} from "@phosphor-icons/react"
import { Link, useRouterState } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { useLiveQuery } from "@/db/DbProvider"
import { listFavorites } from "@/db/queries"
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar"
import { AppLogo } from "./AppLogo"

const NAV: { to: "/" | "/machines" | "/transfers" | "/settings"; label: string; icon: Icon }[] = [
  { to: "/", label: "nav.home", icon: HouseIcon },
  { to: "/machines", label: "nav.machines", icon: HardDrivesIcon },
  { to: "/transfers", label: "nav.transfers", icon: ArrowsDownUpIcon },
  { to: "/settings", label: "nav.settings", icon: GearSixIcon },
]

export function AppSidebar() {
  const { t } = useTranslation()
  const { isMobile, setOpenMobile } = useSidebar()
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const search = useRouterState({
    select: (s) => s.location.search as { share?: string; path?: string },
  })
  const favorites = useLiveQuery(listFavorites)
  const close = () => isMobile && setOpenMobile(false)

  const isActive = (to: string) =>
    to === "/"
      ? pathname === "/"
      : pathname === to ||
        pathname.startsWith(`${to}/`) ||
        (to === "/machines" && pathname.startsWith("/browse/"))

  return (
    <Sidebar
      variant="inset"
      className="top-[calc(var(--header-height)+env(safe-area-inset-top))] h-[calc(100svh-var(--header-height)-env(safe-area-inset-top))]! max-md:top-0 max-md:h-svh!"
    >
      {isMobile && (
        <SidebarHeader className="px-4 pt-[max(1rem,env(safe-area-inset-top))]">
          <AppLogo />
        </SidebarHeader>
      )}
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu aria-label={t("nav.main")}>
              {NAV.map((item) => (
                <SidebarMenuItem key={item.to}>
                  <SidebarMenuButton asChild isActive={isActive(item.to)}>
                    <Link to={item.to} onClick={close}>
                      <item.icon />
                      <span>{t(item.label)}</span>
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
        <SidebarGroup>
          <SidebarGroupLabel>{t("nav.favorites")}</SidebarGroupLabel>
          <SidebarGroupContent>
            {favorites && favorites.length === 0 && (
              <p className="px-2 text-sm text-muted-foreground">{t("nav.noFavorites")}</p>
            )}
            <SidebarMenu>
              {favorites?.map((favorite) => {
                const active =
                  pathname === `/browse/${favorite.machine_id}` &&
                  search.share === favorite.share &&
                  (search.path ?? "") === favorite.path
                return (
                  <SidebarMenuItem key={favorite.id}>
                    <SidebarMenuButton asChild isActive={active}>
                      <Link
                        to="/browse/$machineId"
                        params={{ machineId: favorite.machine_id }}
                        search={{ share: favorite.share, path: favorite.path || undefined }}
                        onClick={close}
                        title={favorite.label}
                      >
                        <FolderSimpleStarIcon />
                        <span className="truncate">{favorite.label}</span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                )
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
    </Sidebar>
  )
}
