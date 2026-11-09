import { Capacitor, registerPlugin } from "@capacitor/core"

/**
 * Native status/navigation bar styling (`android/app/src/main/java/dev/hfps/hoard/SystemUiPlugin.kt`).
 * In the browser there are no system bars, so every call is a no-op.
 */
export interface SystemUiPlugin {
  /** Paint the bars' background and pick light or dark icons for the app's resolved theme. */
  setTheme(options: { dark: boolean; background?: string }): Promise<void>
}

export const SystemUi = registerPlugin<SystemUiPlugin>("SystemUi", {
  web: () => ({ setTheme: async () => {} }),
})

export const hasSystemBars = () => Capacitor.isNativePlatform()
