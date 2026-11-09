import { registerPlugin } from "@capacitor/core"
import type { HoardSmbPlugin } from "./definitions"
import type { HoardSmbWeb } from "./web"

// Capacitor calls the lazy web loader on every call until the first one resolves, so concurrent
// first calls (route probes, listeners, getTransfers at startup) would each get their own mock,
// and a progress listener could sit on a different instance than the download. One promise, one mock.
let web: Promise<HoardSmbWeb> | undefined

/** The native SMB bridge (Android: `dev.hfps.hoard.smb.HoardSmbPlugin`; browser: the in-memory mock). */
export const HoardSmb = registerPlugin<HoardSmbPlugin>("HoardSmb", {
  web: () => (web ??= import("./web").then((m) => new m.HoardSmbWeb())),
})

export * from "./definitions"
