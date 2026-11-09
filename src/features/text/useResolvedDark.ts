import { useSyncExternalStore } from "react"
import { useTheme } from "@/lib/theme/theme"

const query = () => window.matchMedia("(prefers-color-scheme: dark)")

function subscribe(callback: () => void) {
  const media = query()
  media.addEventListener("change", callback)
  return () => media.removeEventListener("change", callback)
}

/** Whether the app is dark right now (the theme choice, or the system for "system"). */
export function useResolvedDark(): boolean {
  const theme = useTheme()
  const systemDark = useSyncExternalStore(subscribe, () => query().matches)
  return theme === "dark" || (theme === "system" && systemDark)
}
