import { hasSystemBars, SystemUi } from "@/plugins/systemUi"
import { getTheme, subscribeTheme } from "./theme"

/**
 * Keep the Android status and navigation bars matching the app theme (light/dark chosen in the app
 * or followed from the system, any palette). Call once at startup, after `initTheme()`.
 */
export function syncSystemBars(): void {
  if (!hasSystemBars()) return
  const media = window.matchMedia("(prefers-color-scheme: dark)")
  const sync = () => {
    const theme = getTheme()
    const dark = theme === "dark" || (theme === "system" && media.matches)
    void SystemUi.setTheme({
      dark,
      background: toHex(getComputedStyle(document.body).backgroundColor),
    })
  }
  // After the class change has been styled.
  const schedule = () => requestAnimationFrame(sync)
  subscribeTheme(schedule)
  media.addEventListener("change", schedule)
  schedule()
}

/** Any CSS color (oklch included) as `#rrggbb`, through a 1×1 canvas. */
function toHex(color: string): string | undefined {
  const canvas = document.createElement("canvas")
  canvas.width = canvas.height = 1
  const context = canvas.getContext("2d")
  if (!context) return undefined
  context.fillStyle = color
  context.fillRect(0, 0, 1, 1)
  const [r, g, b] = context.getImageData(0, 0, 1, 1).data
  return `#${[r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")}`
}
