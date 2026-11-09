import { registerPlugin } from "@capacitor/core"
import type { HoardSmbPlugin } from "./definitions"

/** The native SMB bridge (Android: `dev.hfps.hoard.smb.HoardSmbPlugin`; browser: the in-memory mock). */
export const HoardSmb = registerPlugin<HoardSmbPlugin>("HoardSmb", {
  web: () => import("./web").then((m) => new m.HoardSmbWeb()),
})

export * from "./definitions"
