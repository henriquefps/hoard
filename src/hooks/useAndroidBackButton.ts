import { App } from "@capacitor/app"
import { Capacitor } from "@capacitor/core"
import { useRouter } from "@tanstack/react-router"
import { useEffect, useRef } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"

const EXIT_CONFIRM_WINDOW_MS = 2000

// Every overlay that closes on Escape: dialogs, drawers, sheets (the mobile sidebar), menus.
const OVERLAY_SELECTOR = [
  '[data-slot="alert-dialog-content"]',
  '[data-slot="dialog-content"]',
  '[data-slot="drawer-content"]',
  '[data-slot="sheet-content"]',
  '[data-slot="dropdown-menu-content"]',
  '[data-slot="select-content"]',
].join(", ")

/**
 * Android back button / gesture (from home-apps): without a listener Capacitor exits the app on
 * every screen. Order: close the top overlay (Radix closes on Escape), else go back in history, else
 * on Home ask for a second press within 2 s before exiting.
 */
export function useAndroidBackButton(): void {
  const { t } = useTranslation()
  const router = useRouter()
  const lastPress = useRef(0)

  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return
    const listener = App.addListener("backButton", () => {
      if (document.querySelector(OVERLAY_SELECTOR)) {
        document.dispatchEvent(
          new KeyboardEvent("keydown", { key: "Escape", code: "Escape", bubbles: true })
        )
        return
      }
      if (router.state.location.pathname !== "/") {
        if (router.history.canGoBack()) router.history.back()
        else void router.navigate({ to: "/" })
        return
      }
      const now = Date.now()
      if (now - lastPress.current < EXIT_CONFIRM_WINDOW_MS) {
        void App.exitApp()
        return
      }
      lastPress.current = now
      toast(t("backButton.exitHint"))
    })
    return () => {
      void listener.then((handle) => handle.remove())
    }
  }, [router, t])
}
