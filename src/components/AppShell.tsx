import { Outlet } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar"
import { Toaster } from "@/components/ui/sonner"
import { useAndroidBackButton } from "@/hooks/useAndroidBackButton"
import { useTheme } from "@/lib/theme/theme"
import { TransfersIndicator } from "@/features/transfers/TransfersIndicator"
import { TransfersDrawer } from "@/features/transfers/TransfersViews"
import { VolumeNotices } from "@/features/locations/VolumeNotices"
import { AppLogo } from "./AppLogo"
import { AppSidebar } from "./AppSidebar"
import { ThemeMenu } from "./ThemeMenu"

/**
 * hfps inset layout: top bar and sidebar on the tinted background, every page inside ONE raised
 * card surface (SidebarInset). Below 768 px the sidebar becomes shadcn's navigation Sheet and the
 * card keeps a small margin (`max-md:*`). The page scrolls inside the card, not the document.
 */
export function AppShell() {
  const { t } = useTranslation()
  const theme = useTheme()
  useAndroidBackButton()
  return (
    <div className="pt-[env(safe-area-inset-top)] [--header-height:--spacing(14)]">
      <SidebarProvider className="flex min-h-0 flex-col">
        <header className="sticky top-0 z-20 flex h-(--header-height) w-full shrink-0 items-center gap-2 bg-background pr-[max(0.75rem,env(safe-area-inset-right))] pl-[max(0.75rem,env(safe-area-inset-left))] md:px-4">
          <SidebarTrigger className="size-9 md:hidden" aria-label={t("nav.openMenu")} />
          <AppLogo />
          <div className="ml-auto flex items-center gap-1">
            <TransfersIndicator />
            <ThemeMenu />
          </div>
        </header>
        <div className="flex h-[calc(100dvh-var(--header-height)-env(safe-area-inset-top))] min-h-0">
          <AppSidebar />
          <SidebarInset className="min-h-0 min-w-0 overflow-y-auto overscroll-contain bg-card max-md:mx-2 max-md:mb-[max(0.5rem,env(safe-area-inset-bottom))] max-md:rounded-xl max-md:border max-md:shadow-xs md:peer-data-[variant=inset]:mt-0 md:peer-data-[variant=inset]:border md:peer-data-[variant=inset]:shadow-xs">
            <Outlet />
          </SidebarInset>
        </div>
      </SidebarProvider>
      <TransfersDrawer />
      <VolumeNotices />
      <Toaster
        theme={theme}
        position="bottom-center"
        mobileOffset={{ bottom: "calc(env(safe-area-inset-bottom) + 1rem)" }}
      />
    </div>
  )
}
