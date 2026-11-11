import { createContext, useContext } from "react"
import type { CullFolderKey } from "@/db/types"
import type { Decisions } from "@/features/cull/model"
import type { BrowseLocation } from "@/features/locations/useBrowseLocation"

/** One "Edit in Reveal" request: photos of one location (ADR 0010). */
export interface RevealRequest {
  location: BrowseLocation
  /** Photo files, every file of a shot included, in the order Reveal should list them. */
  paths: string[]
  /** Names the batch in Reveal (the folder's name). */
  title?: string
  /** One of `paths`: Reveal opens it in the editor at once. */
  initialPath?: string
  /** Culling decisions by shot id, sent as Reveal's `CULLING` extra (ADR 0011): the culling page's own. */
  decisions?: Decisions
  /** Elsewhere (the browser): the folder whose saved culling decisions to send. */
  cullFolder?: CullFolderKey
}

export interface RevealContextValue {
  /** Reveal is installed (rechecked whenever Hoard comes back to the foreground). */
  available: boolean
  /** Hand the photos over; asks first for big batches and SMB photos. */
  edit: (request: RevealRequest) => void
}

export const RevealContext = createContext<RevealContextValue>({ available: false, edit: () => {} })

/** "Edit in Reveal": availability and the hand-off, from `RevealProvider`. */
export const useReveal = () => useContext(RevealContext)
