import { useSyncExternalStore } from "react"

// Jobs whose question the user closed without answering ("Later"); the row's "Choose…" reopens it.
let dismissed = new Set<string>()
const listeners = new Set<() => void>()
const notify = () => listeners.forEach((l) => l())

export function askAgain(transferId: string) {
  dismissed = new Set([...dismissed].filter((id) => id !== transferId))
  notify()
}

export function useDismissedConflicts() {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => dismissed,
    () => dismissed
  )
}

export function dismissConflict(transferId: string) {
  dismissed = new Set([...dismissed, transferId])
  notify()
}
