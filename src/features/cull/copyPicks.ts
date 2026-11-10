/**
 * The one place culling hands files to the transfer engine (ADR 0007 / 0008): picks are copied as
 * one batch job by the engine every other copy uses (progress, resume, conflicts and the
 * conflict question live there). Culling never copies bytes itself.
 */
import { useCallback } from "react"
import { useDb } from "@/db/DbProvider"
import { getMachine } from "@/db/queries"
import { localRefOf, parseLocationId } from "@/features/locations/ids"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { TREE_LOCATION_ID } from "@/features/transfers/service"
import { useStartTransfer } from "@/features/transfers/useStartTransfer"
import { HoardSmb } from "@/plugins/smb"
import type {
  CullFile,
  LocationRef,
  TransferConflict,
  TransferSidecar,
} from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"

/** A folder in any location, as routes, favorites and the destination picker name it. */
export interface CopyDestination {
  /** Machine id, `device`, `vol-<uuid>`, or `tree` (a folder Android granted). */
  locationId: string
  /** SMB share; `""` for local locations. */
  share: string
  /** Folder inside the share or location root. */
  path: string
  /** What the user sees ("Pictures", "photos › 2026"). */
  label: string
  /** For `tree` destinations. */
  treeUri?: string
}

export interface CopyPicksSource {
  locationId: string
  share: string
}

export interface CopyPicksAdapterOptions {
  source: CopyPicksSource
  /** Create this folder inside the destination and copy into it (not for `tree` destinations). */
  subfolder?: string
  conflict: TransferConflict
  /** XMP sidecars to write next to the copies at the destination (ADR 0009). */
  sidecars?: TransferSidecar[]
}

export type CopyPicksOutcome = { status: "queued"; count: number }

const join = (a: string, b: string) => (a ? `${a}/${b}` : b)

/** `copyPicks(items, destination, options)`: queue the picks' files as one copy job. */
export function useCopyPicks() {
  const db = useDb()
  const engine = useRouteEngine()
  const start = useStartTransfer()

  /** Runs `fn` with the destination's location (SMB through the route engine's session). */
  const onLocation = useCallback(
    async <T>(destination: CopyDestination, fn: (location: LocationRef) => Promise<T>) => {
      const parsed = parseLocationId(destination.locationId)
      if (parsed.kind !== "smb") return fn(localRefOf(destination.locationId)!)
      const machine = await getMachine(db, parsed.machineId)
      if (!machine) throw new Error("Unknown machine")
      return engine.withSession(machine, (sessionId) =>
        fn({ kind: "smb", sessionId, share: destination.share })
      )
    },
    [db, engine]
  )

  return useCallback(
    async (
      items: readonly CullFile[],
      destination: CopyDestination,
      options: CopyPicksAdapterOptions
    ): Promise<CopyPicksOutcome> => {
      let path = destination.path
      const subfolder = options.subfolder?.trim()
      if (subfolder && destination.locationId !== TREE_LOCATION_ID) {
        // The engine creates the folders it copies, not the one it copies into.
        try {
          await onLocation(destination, (location) =>
            HoardSmb.createFolder({ location, path: destination.path, name: subfolder })
          )
        } catch (error) {
          if (smbErrorCode(error) !== "ALREADY_EXISTS") throw error
        }
        path = join(destination.path, subfolder)
      }
      await start(
        "copy",
        { locationId: options.source.locationId, share: options.source.share || undefined },
        items.map((f) => ({ path: f.path, name: f.name, isDirectory: false, size: f.size })),
        {
          locationId: destination.locationId,
          share: destination.share || undefined,
          path,
          label: subfolder ? `${destination.label} › ${subfolder}` : destination.label,
          treeUri: destination.treeUri,
        },
        options.conflict,
        options.sidecars
      )
      return { status: "queued", count: items.length }
    },
    [onLocation, start]
  )
}
