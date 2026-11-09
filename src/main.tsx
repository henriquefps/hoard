import { RouterProvider } from "@tanstack/react-router"
import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { TooltipProvider } from "@/components/ui/tooltip"
import { DbProvider } from "@/db/DbProvider"
import { VolumesProvider } from "@/features/locations/VolumesProvider"
import { RouteProvider } from "@/features/route/RouteProvider"
import { TransfersProvider } from "@/features/transfers/TransfersProvider"
import "@/i18n"
import { syncSystemBars } from "@/lib/theme/systemBars"
import { initTheme } from "@/lib/theme/theme"
import { router } from "@/router"
import "./index.css"

initTheme()
syncSystemBars()

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <TooltipProvider delayDuration={300}>
      <DbProvider>
        <RouteProvider>
          <VolumesProvider>
            <TransfersProvider>
              <RouterProvider router={router} />
            </TransfersProvider>
          </VolumesProvider>
        </RouteProvider>
      </DbProvider>
    </TooltipProvider>
  </StrictMode>
)
