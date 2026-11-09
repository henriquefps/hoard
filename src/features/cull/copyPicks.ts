/**
 * The one place culling hands files to the transfer engine (ADR 0008). Phase 2a builds the general
 * engine (any location → any location, batches, resume, conflicts); culling does not get a copy
 * engine of its own. Until 2a is merged this adapter reports `ENGINE_UNAVAILABLE` and the dialog
 * says so; wiring it is replacing the body of {@link copyPicks}.
 */
import type { ConflictPolicy, CullFile } from "@/plugins/smb/definitions"

/** A folder in any location, as routes and favorites name it. */
export interface CopyDestination {
  /** Machine id, `device` or `vol-<uuid>`. */
  locationId: string
  /** SMB share; `""` for local locations. */
  share: string
  /** Folder inside the share or location root. */
  path: string
  /** What the user sees ("Internal storage › Pictures", "NAS › photos › 2026"). */
  label: string
}

export interface CopyPicksSource {
  locationId: string
  share: string
}

export interface CopyPicksAdapterOptions {
  source: CopyPicksSource
  /** Folder inside the destination location the files go to (subfolder already applied). */
  targetPath: string
  /** Create `targetPath` when missing. */
  createFolders: boolean
  conflict: ConflictPolicy
}

export type CopyPicksOutcome =
  /** Queued in the transfer engine; progress shows in Transfers. */
  | { status: "queued"; count: number }
  /** The transfer engine is not in this build yet (phase 2a). */
  | { status: "unavailable" }

/** Copy `items` (files of the picked shots, from `planCopy`) into `destination`. */
export async function copyPicks(
  items: readonly CullFile[],
  destination: CopyDestination,
  options: CopyPicksAdapterOptions
): Promise<CopyPicksOutcome> {
  void items
  void destination
  void options
  return { status: "unavailable" }
}
