import { useSyncExternalStore } from "react"
import type { TransferOp } from "@/plugins/smb/definitions"
import type { FolderPlace, PickedEntry } from "./places"

/**
 * The file manager clipboard (ADR 0007): "Copy" or "Move" puts the selection here, the user
 * navigates anywhere and "Paste here" starts the transfer. In memory only (like a desktop
 * clipboard, it does not survive the app being killed); one entry at a time.
 */
export interface ClipboardContent {
  op: TransferOp
  /** The folder the entries were picked in. */
  source: FolderPlace
  /** Shown in the bar: the folder's name ("Camera"), or the location's. */
  sourceLabel: string
  entries: PickedEntry[]
}

/** The folder the user is looking at, where "Paste here" would put things (set by the browser). */
export interface PasteTarget extends FolderPlace {
  label: string
  /** Why pasting is off here (read-only folder), or undefined. */
  readOnlyReason?: string
}

type Listener = () => void

function store<T>(initial: T) {
  let value = initial
  const listeners = new Set<Listener>()
  return {
    get: () => value,
    set(next: T) {
      value = next
      listeners.forEach((l) => l())
    },
    subscribe(listener: Listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}

const clip = store<ClipboardContent | null>(null)
const target = store<PasteTarget | null>(null)

export const clipboard = {
  get: clip.get,
  set: (content: ClipboardContent) => clip.set(content),
  clear: () => clip.set(null),
  subscribe: clip.subscribe,
}

export const pasteTarget = {
  get: target.get,
  set: (value: PasteTarget | null) => target.set(value),
  subscribe: target.subscribe,
}

export function useClipboard(): ClipboardContent | null {
  return useSyncExternalStore(clip.subscribe, clip.get, clip.get)
}

export function usePasteTarget(): PasteTarget | null {
  return useSyncExternalStore(target.subscribe, target.get, target.get)
}
