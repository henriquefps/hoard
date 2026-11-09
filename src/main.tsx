import { RouterProvider } from "@tanstack/react-router"
import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { TooltipProvider } from "@/components/ui/tooltip"
import { DbProvider } from "@/db/DbProvider"
import { RouteProvider } from "@/features/route/RouteProvider"
import { TransfersProvider } from "@/features/transfers/TransfersProvider"
import "@/i18n"
import { initTheme } from "@/lib/theme/theme"
import { router } from "@/router"
import "./index.css"

initTheme()

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <TooltipProvider delayDuration={300}>
      <DbProvider>
        <RouteProvider>
          <TransfersProvider>
            <RouterProvider router={router} />
          </TransfersProvider>
        </RouteProvider>
      </DbProvider>
    </TooltipProvider>
  </StrictMode>
)
