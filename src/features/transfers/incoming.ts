import { useSyncExternalStore } from "react"
import type { SharedFiles } from "@/plugins/smb/definitions"

/**
 * What another app shared to Hoard and the user has not saved or dismissed yet (ADR 0007, "Share
 * in"). Filled by {@link IncomingShareWatcher} from `getSharedFiles()` (launch, resume) and the
 * `filesShared` event; read by the Save to… page.
 */
let current: SharedFiles | null = null
const listeners = new Set<() => void>()

export const incomingShare = {
  get: () => current,
  set(next: SharedFiles | null) {
    current = next
    listeners.forEach((l) => l())
  },
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },
}

export function useIncomingShare() {
  return useSyncExternalStore(incomingShare.subscribe, incomingShare.get, incomingShare.get)
}
