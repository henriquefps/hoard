import { useSyncExternalStore } from "react"
import { router } from "@/router"

// Whether the phone's transfers Drawer is open; any screen (toasts included) can open it.
let open = false
const listeners = new Set<() => void>()

export function setTransfersDrawerOpen(next: boolean) {
  open = next
  listeners.forEach((listener) => listener())
}

/** Phones: the bottom Drawer; from 768 px: the Transfers page. */
export function openTransfers() {
  if (window.matchMedia("(max-width: 767px)").matches) setTransfersDrawerOpen(true)
  else void router.navigate({ to: "/transfers" })
}

export function useTransfersDrawerOpen() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => open,
    () => false
  )
}
