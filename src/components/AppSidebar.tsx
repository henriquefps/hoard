import {
  ArrowsDownUpIcon,
  FolderSimpleStarIcon,
  GearSixIcon,
  HardDrivesIcon,
  HouseIcon,
  type Icon,
  PlusIcon,
} from "@phosphor-icons/react"
import { Link, useRouterState } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { useLiveQuery } from "@/db/DbProvider"
import { listFavorites, listMachines } from "@/db/queries"
import type { MachineWithAddresses } from "@/db/types"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupAction,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarSeparator,
  useSidebar,
} from "@/components/ui/sidebar"
import { locationIdOfVolume } from "@/features/locations/ids"
import { VolumeIcon } from "@/features/locations/VolumeIcon"
import { useVolumes } from "@/features/locations/volumesContext"
import { useMachineRoute } from "@/features/route/RouteProvider"
import { overallProgress } from "@/features/transfers/TransfersViews"
import { useTransfers } from "@/features/transfers/TransfersProvider"
import { TransfersIndicator } from "@/features/transfers/TransfersIndicator"
import { useIsShort } from "@/hooks/use-mobile"
import { cn } from "@/lib/utils"
import { AppLogo, HoardMark } from "./AppLogo"
import { MACHINE_ICONS, type MachineIconId } from "./MachineIcon"
import { ThemeMenu } from "./ThemeMenu"

/**
 * Rail items (short screens, `collapsible="icon"`): 44px touch targets, icon centered. The label
 * stays in the DOM (accessible name) and shows as a tooltip.
 */
const RAIL = "group-data-[collapsible=icon]:size-11! group-data-[collapsible=icon]:justify-center"
/** A label in the rail: kept for screen readers, shown by the tooltip. */
const RAIL_TEXT = "group-data-[collapsible=icon]:sr-only"
/** Between groups in the rail, where the group labels are hidden. */
const RailSeparator = () => (
  <SidebarSeparator className="hidden group-data-[collapsible=icon]:block" />
)

const NAV: { to: "/" | "/machines" | "/transfers" | "/settings"; label: string; icon: Icon }[] = [
  { to: "/", label: "nav.home", icon: HouseIcon },
  { to: "/machines", label: "nav.machines", icon: HardDrivesIcon },
  { to: "/transfers", label: "nav.transfers", icon: ArrowsDownUpIcon },
  { to: "/settings", label: "nav.settings", icon: GearSixIcon },
]

export function AppSidebar() {
  const { t } = useTranslation()
  const { isMobile, setOpenMobile } = useSidebar()
  const short = useIsShort()
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const search = useRouterState({
    select: (s) => s.location.search as { share?: string; path?: string },
  })
  const favorites = useLiveQuery(listFavorites)
  const machines = useLiveQuery(listMachines)
  const { volumes } = useVolumes()
  const transfers = useTransfers()
  const active = overallProgress(transfers)?.count ?? 0
  const close = () => isMobile && setOpenMobile(false)

  const isActive = (to: string) =>
    to === "/" ? pathname === "/" : pathname === to || pathname.startsWith(`${to}/`)

  return (
    <Sidebar
      variant="inset"
      collapsible="icon"
      className="top-[calc(var(--header-height)+env(safe-area-inset-top))] h-[calc(100svh-var(--header-height)-env(safe-area-inset-top))]! max-md:top-0 max-md:h-svh!"
    >
      {isMobile && (
        <SidebarHeader className="px-4 pt-[max(1rem,env(safe-area-inset-top))]">
          <AppLogo />
        </SidebarHeader>
      )}
      {short && (
        // The top bar's logo, on top of the rail (the bar itself is gone on short screens).
        <SidebarHeader className="items-center py-2">
          <Link to="/" onClick={close} aria-label={t("app.name")} title={t("app.name")}>
            <HoardMark className="size-6 text-primary" />
          </Link>
        </SidebarHeader>
      )}
      <SidebarContent className="group-data-[collapsible=icon]:overflow-auto">
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu aria-label={t("nav.main")}>
              {NAV.map((item) => (
                <SidebarMenuItem key={item.to}>
                  <SidebarMenuButton
                    asChild
                    isActive={isActive(item.to)}
                    tooltip={t(item.label)}
                    className={RAIL}
                  >
                    <Link to={item.to} onClick={close}>
                      <item.icon />
                      <span className={RAIL_TEXT}>{t(item.label)}</span>
                    </Link>
                  </SidebarMenuButton>
                  {item.to === "/transfers" && active > 0 && (
                    <SidebarMenuBadge
                      className="tabular-nums"
                      aria-label={t("transfers.indicator", {
                        count: active,
                        percent: Math.floor(overallProgress(transfers)!.percent),
                      })}
                    >
                      {active}
                    </SidebarMenuBadge>
                  )}
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        <RailSeparator />
        <SidebarGroup>
          <SidebarGroupLabel>{t("locations.thisPhone")}</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {(volumes ?? []).map((volume) => {
                const id = locationIdOfVolume(volume)
                const label = volume.primary ? t("locations.internal") : volume.label
                return (
                  <SidebarMenuItem key={id}>
                    <SidebarMenuButton
                      asChild
                      isActive={pathname === `/browse/${id}` && !search.path}
                      tooltip={label}
                      className={RAIL}
                    >
                      <Link
                        to="/browse/$locationId"
                        params={{ locationId: id }}
                        onClick={close}
                        title={label}
                      >
                        <VolumeIcon kind={volume.kind} />
                        <span className={cn("truncate", RAIL_TEXT)}>{label}</span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                )
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        <RailSeparator />
        <SidebarGroup>
          <SidebarGroupLabel>{t("nav.favorites")}</SidebarGroupLabel>
          <SidebarGroupContent>
            {favorites && favorites.length === 0 && (
              <p className="px-2 text-sm text-muted-foreground group-data-[collapsible=icon]:hidden">
                {t("nav.noFavorites")}
              </p>
            )}
            <SidebarMenu>
              {favorites?.map((favorite) => {
                const active =
                  pathname === `/browse/${favorite.location_id}` &&
                  (search.share ?? "") === favorite.share &&
                  (search.path ?? "") === favorite.path
                return (
                  <SidebarMenuItem key={favorite.id}>
                    <SidebarMenuButton
                      asChild
                      isActive={active}
                      tooltip={favorite.label}
                      className={RAIL}
                    >
                      <Link
                        to="/browse/$locationId"
                        params={{ locationId: favorite.location_id }}
                        search={{
                          share: favorite.share || undefined,
                          path: favorite.path || undefined,
                        }}
                        onClick={close}
                        title={favorite.label}
                      >
                        <FolderSimpleStarIcon />
                        <span className={cn("truncate", RAIL_TEXT)}>{favorite.label}</span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                )
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
        <RailSeparator />
        <SidebarGroup>
          <SidebarGroupLabel>{t("nav.machines")}</SidebarGroupLabel>
          <SidebarGroupAction asChild title={t("machines.add")}>
            <Link to="/machines/new" onClick={close} aria-label={t("machines.add")}>
              <PlusIcon />
            </Link>
          </SidebarGroupAction>
          <SidebarGroupContent>
            {machines && machines.length === 0 && (
              <p className="px-2 text-sm text-muted-foreground group-data-[collapsible=icon]:hidden">
                {t("nav.noMachines")}
              </p>
            )}
            <SidebarMenu>
              {machines?.map((machine) => (
                <SidebarMachine
                  key={machine.id}
                  machine={machine}
                  active={pathname === `/browse/${machine.id}` && !search.share}
                  onNavigate={close}
                />
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      {short && (
        // The top bar's actions: transfers in progress and the theme menu.
        <SidebarFooter className="items-center gap-1">
          <TransfersIndicator />
          <ThemeMenu side="right" />
        </SidebarFooter>
      )}
    </Sidebar>
  )
}

function SidebarMachine({
  machine,
  active,
  onNavigate,
}: {
  machine: MachineWithAddresses
  active: boolean
  onNavigate: () => void
}) {
  const { t } = useTranslation()
  const route = useMachineRoute(machine)
  const Icon = MACHINE_ICONS[machine.icon as MachineIconId] ?? HardDrivesIcon
  const status =
    route.status === "online" && route.route
      ? t(`addressKind.${route.route.kind}`)
      : route.status === "checking" || route.status === "idle"
        ? t("route.checking")
        : t(`route.problem.${route.errorCode ?? "UNREACHABLE"}`, t("route.problem.UNKNOWN"))
  return (
    <SidebarMenuItem>
      <SidebarMenuButton
        asChild
        isActive={active}
        tooltip={`${machine.name} · ${status}`}
        className={cn("h-auto min-h-8 py-1.5", RAIL)}
      >
        <Link
          to="/browse/$locationId"
          params={{ locationId: machine.id }}
          onClick={onNavigate}
          title={`${machine.name} · ${status}`}
        >
          <span data-machine-color={machine.color ?? ""} className="text-(--machine)">
            <Icon weight="duotone" className="size-4" />
          </span>
          <span className={cn("min-w-0 flex-1 truncate", RAIL_TEXT)}>{machine.name}</span>
          <span className="sr-only">{status}</span>
          <span
            aria-hidden
            className={cn(
              "size-2 shrink-0 rounded-full group-data-[collapsible=icon]:hidden",
              route.status === "online" && "bg-primary",
              route.status === "error" && "bg-destructive",
              route.status === "offline" && "bg-muted-foreground/50",
              (route.status === "checking" || route.status === "idle") &&
                "animate-pulse bg-muted-foreground/30 motion-reduce:animate-none"
            )}
          />
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  )
}
