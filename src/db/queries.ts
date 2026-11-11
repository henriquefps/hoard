import { detectAddressKind } from "@/lib/addressKind"
import type { TransferConflict, TransferOp, TransferProgressEvent } from "@/plugins/smb/definitions"
import { TERMINAL_TRANSFER_STATES } from "@/plugins/smb/definitions"
import type { Db } from "./db"
import type {
  CullAnalysisRecord,
  CullDecisionRecord,
  CullDestinationRecord,
  CullFlag,
  CullFolderKey,
  CullSessionRecord,
  CullSidecarRecord,
  FavoriteRecord,
  MachineAddressRecord,
  ManualShareRecord,
  MachineRecord,
  MachineWithAddresses,
  SettingKey,
  TransferRecord,
} from "./types"

const newId = () => crypto.randomUUID()
const now = () => new Date().toISOString()

// --- Machines ------------------------------------------------------------------------------

export async function listMachines(db: Db): Promise<MachineWithAddresses[]> {
  const machines = await db.query<MachineRecord>(
    "SELECT * FROM machines ORDER BY sort_order, created_at"
  )
  const addresses = await db.query<MachineAddressRecord>(
    "SELECT * FROM machine_addresses ORDER BY sort_order, rowid"
  )
  return machines.map((machine) => ({
    ...machine,
    addresses: addresses.filter((address) => address.machine_id === machine.id),
  }))
}

export async function getMachine(db: Db, id: string): Promise<MachineWithAddresses | null> {
  const [machine] = await db.query<MachineRecord>("SELECT * FROM machines WHERE id = ?", [id])
  if (!machine) return null
  const addresses = await db.query<MachineAddressRecord>(
    "SELECT * FROM machine_addresses WHERE machine_id = ? ORDER BY sort_order, rowid",
    [id]
  )
  return { ...machine, addresses }
}

export interface MachineInput {
  name: string
  icon?: string | null
  color?: string | null
  username: string
  domain?: string | null
  /** In priority order. `kind` is detected from the host. */
  addresses: { host: string; port?: number; label?: string | null }[]
}

/**
 * Creates (no `id`) or updates a machine and replaces its addresses atomically. Returns the id.
 * `create: true` inserts with the given id (the form picks the id up front so the password can be
 * stored natively under it before the row exists).
 */
export async function saveMachine(
  db: Db,
  input: MachineInput,
  id?: string,
  { create = false }: { create?: boolean } = {}
): Promise<string> {
  return db.transaction(async () => {
    const machineId = id ?? newId()
    if (id && !create) {
      const [existing] = await db.query<MachineRecord>("SELECT * FROM machines WHERE id = ?", [id])
      if (!existing) throw new Error(`Machine ${id} not found`)
      await db.run(
        "UPDATE machines SET name = ?, icon = ?, color = ?, username = ?, domain = ? WHERE id = ?",
        [
          input.name,
          input.icon ?? null,
          input.color ?? null,
          input.username,
          input.domain ?? null,
          id,
        ]
      )
      await db.run("DELETE FROM machine_addresses WHERE machine_id = ?", [id])
    } else {
      const [{ next }] = await db.query<{ next: number }>(
        "SELECT COALESCE(MAX(sort_order) + 1, 0) AS next FROM machines"
      )
      await db.run(
        "INSERT INTO machines (id, name, icon, color, username, domain, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
          machineId,
          input.name,
          input.icon ?? null,
          input.color ?? null,
          input.username,
          input.domain ?? null,
          next,
        ]
      )
    }
    for (const [index, address] of input.addresses.entries()) {
      const host = address.host.trim()
      await db.run(
        "INSERT INTO machine_addresses (id, machine_id, host, port, kind, label, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
          newId(),
          machineId,
          host,
          address.port ?? 445,
          detectAddressKind(host),
          address.label ?? null,
          index,
        ]
      )
    }
    return machineId
  })
}

/**
 * Deletes a machine with its addresses, favorites and transfer history (cascade). The caller also
 * deletes the credentials (`HoardSmb.deleteCredentials`).
 */
export async function deleteMachine(db: Db, id: string): Promise<void> {
  await db.transaction(async () => {
    await db.run("DELETE FROM machines WHERE id = ?", [id])
    // Culling rows have no foreign key (local locations are not rows): clear them by hand.
    await db.run("DELETE FROM cull_decisions WHERE location_id = ?", [id])
    await db.run("DELETE FROM cull_sidecars WHERE location_id = ?", [id])
    await db.run("DELETE FROM cull_sessions WHERE location_id = ?", [id])
    await db.run("DELETE FROM cull_analysis WHERE location_id = ?", [id])
    await db.run("DELETE FROM cull_destinations WHERE source_location_id = ? OR location_id = ?", [
      id,
      id,
    ])
  })
}

/** Pin (or clear, with null) the machine's SMB server GUID. */
export async function setServerGuid(db: Db, id: string, guid: string | null): Promise<void> {
  await db.run("UPDATE machines SET server_guid = ? WHERE id = ?", [
    guid?.toLowerCase() ?? null,
    id,
  ])
}

export async function reorderMachines(db: Db, ids: string[]): Promise<void> {
  await db.transaction(async () => {
    for (const [index, id] of ids.entries()) {
      await db.run("UPDATE machines SET sort_order = ? WHERE id = ?", [index, id])
    }
  })
}

// --- Favorites -----------------------------------------------------------------------------

export async function listFavorites(db: Db, machineId?: string): Promise<FavoriteRecord[]> {
  return machineId
    ? db.query<FavoriteRecord>(
        "SELECT * FROM favorites WHERE machine_id = ? ORDER BY sort_order, created_at",
        [machineId]
      )
    : db.query<FavoriteRecord>("SELECT * FROM favorites ORDER BY sort_order, created_at")
}

/**
 * Pins a folder in any location; pinning the same location + share + path again just renames it.
 * `machineId` is the machine for SMB (equal to `locationId`), null for local locations. Returns the id.
 */
export async function addFavorite(
  db: Db,
  favorite: {
    locationId: string
    machineId: string | null
    share: string
    path: string
    label: string
  }
): Promise<string> {
  return db.transaction(async () => {
    const [existing] = await db.query<FavoriteRecord>(
      "SELECT * FROM favorites WHERE location_id = ? AND share = ? AND path = ?",
      [favorite.locationId, favorite.share, favorite.path]
    )
    if (existing) {
      await db.run("UPDATE favorites SET label = ? WHERE id = ?", [favorite.label, existing.id])
      return existing.id
    }
    const id = newId()
    const [{ next }] = await db.query<{ next: number }>(
      "SELECT COALESCE(MAX(sort_order) + 1, 0) AS next FROM favorites"
    )
    await db.run(
      "INSERT INTO favorites (id, location_id, machine_id, share, path, label, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
      [
        id,
        favorite.locationId,
        favorite.machineId,
        favorite.share,
        favorite.path,
        favorite.label,
        next,
      ]
    )
    return id
  })
}

export async function renameFavorite(db: Db, id: string, label: string): Promise<void> {
  await db.run("UPDATE favorites SET label = ? WHERE id = ?", [label, id])
}

export async function removeFavorite(db: Db, id: string): Promise<void> {
  await db.run("DELETE FROM favorites WHERE id = ?", [id])
}

/** Persist a new favorites order (ids in display order). */
export async function reorderFavorites(db: Db, ids: string[]): Promise<void> {
  await db.transaction(async () => {
    for (const [index, id] of ids.entries()) {
      await db.run("UPDATE favorites SET sort_order = ? WHERE id = ?", [index, id])
    }
  })
}

export async function findFavorite(
  db: Db,
  locationId: string,
  share: string,
  path: string
): Promise<FavoriteRecord | null> {
  const [row] = await db.query<FavoriteRecord>(
    "SELECT * FROM favorites WHERE location_id = ? AND share = ? AND path = ?",
    [locationId, share, path]
  )
  return row ?? null
}

// --- Transfers -----------------------------------------------------------------------------

export async function listTransfers(db: Db): Promise<TransferRecord[]> {
  return db.query<TransferRecord>("SELECT * FROM transfers ORDER BY created_at DESC")
}

export interface NewTransfer {
  op: TransferOp
  sourceLocationId: string
  sourceMachineId: string | null
  sourceShare: string
  sourcePaths: string[]
  destLocationId: string
  destMachineId: string | null
  destShare: string
  destPath: string
  destTreeUri: string | null
  name: string
  conflict: TransferConflict
  /** Known up front (the selection's files); native reports the real total after its scan. */
  size: number
  itemsTotal: number
}

/** Records a transfer before `HoardSmb.startTransfer` is called with the same id. */
export async function createTransfer(db: Db, transfer: NewTransfer): Promise<string> {
  const id = newId()
  await db.run(
    `INSERT INTO transfers (id, op, source_location_id, source_machine_id, source_share, source_paths,
       dest_location_id, dest_machine_id, dest_share, dest_path, dest_tree_uri, name, conflict, size,
       items_total, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      transfer.op,
      transfer.sourceLocationId,
      transfer.sourceMachineId,
      transfer.sourceShare,
      JSON.stringify(transfer.sourcePaths),
      transfer.destLocationId,
      transfer.destMachineId,
      transfer.destShare,
      transfer.destPath,
      transfer.destTreeUri,
      transfer.name,
      transfer.conflict,
      transfer.size,
      transfer.itemsTotal,
      now(),
    ]
  )
  return id
}

/** The source paths of a row. */
export function transferSourcePaths(row: Pick<TransferRecord, "source_paths">): string[] {
  try {
    const value: unknown = JSON.parse(row.source_paths)
    return Array.isArray(value) ? value.filter((p): p is string => typeof p === "string") : []
  } catch {
    return []
  }
}

/** Applies a native progress event (or a `getTransfers` entry) to the row. */
export async function applyTransferProgress(db: Db, event: TransferProgressEvent): Promise<void> {
  const finished = TERMINAL_TRANSFER_STATES.includes(event.state)
  await db.run(
    `UPDATE transfers SET bytes_done = ?, size = ?, state = ?, error_code = ?, error = ?,
       items_total = ?, items_done = ?, items_failed = ?, items_skipped = ?,
       finished_at = CASE WHEN ? THEN COALESCE(finished_at, ?) ELSE NULL END
     WHERE id = ?`,
    [
      event.bytesDone,
      event.totalBytes,
      event.state,
      event.error?.code ?? null,
      event.error?.message ?? null,
      event.itemsTotal,
      event.itemsDone,
      event.itemsFailed,
      event.itemsSkipped,
      finished ? 1 : 0,
      now(),
      event.transferId,
    ]
  )
}

export async function getTransfer(db: Db, id: string): Promise<TransferRecord | null> {
  const [row] = await db.query<TransferRecord>("SELECT * FROM transfers WHERE id = ?", [id])
  return row ?? null
}

/** Removes one finished transfer from the history. */
export async function deleteTransfer(db: Db, id: string): Promise<void> {
  await db.run("DELETE FROM transfers WHERE id = ?", [id])
}

/** Removes finished transfers from the history. */
export async function clearFinishedTransfers(db: Db): Promise<number> {
  const placeholders = TERMINAL_TRANSFER_STATES.map(() => "?").join(", ")
  const { changes } = await db.run(`DELETE FROM transfers WHERE state IN (${placeholders})`, [
    ...TERMINAL_TRANSFER_STATES,
  ])
  return changes
}

// --- Manual shares -------------------------------------------------------------------------

/** Shares added by name for a machine (not listed by the server), by name. */
export async function listManualShares(db: Db, machineId: string): Promise<ManualShareRecord[]> {
  return db.query<ManualShareRecord>(
    "SELECT * FROM manual_shares WHERE machine_id = ? ORDER BY name",
    [machineId]
  )
}

/** Remembers a share opened by name; adding it again (any letter case) keeps the first entry. */
export async function addManualShare(db: Db, machineId: string, name: string): Promise<void> {
  await db.run(
    "INSERT INTO manual_shares (machine_id, name, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
    [machineId, name, now()]
  )
}

export async function removeManualShare(db: Db, machineId: string, name: string): Promise<void> {
  await db.run("DELETE FROM manual_shares WHERE machine_id = ? AND name = ?", [machineId, name])
}

// --- Settings ------------------------------------------------------------------------------

export async function getSetting<T>(db: Db, key: SettingKey, fallback: T): Promise<T> {
  const [row] = await db.query<{ value: string }>("SELECT value FROM settings WHERE key = ?", [key])
  if (!row) return fallback
  try {
    return JSON.parse(row.value) as T
  } catch {
    return fallback
  }
}

export async function setSetting(db: Db, key: SettingKey, value: unknown): Promise<void> {
  await db.run(
    "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
    [key, JSON.stringify(value)]
  )
}

// --- Culling (ADR 0008) ----------------------------------------------------------------------

const folderParams = (key: CullFolderKey) => [key.locationId, key.share, key.path]

export async function getCullSession(
  db: Db,
  key: CullFolderKey
): Promise<CullSessionRecord | null> {
  const [row] = await db.query<CullSessionRecord>(
    "SELECT * FROM cull_sessions WHERE location_id = ? AND share = ? AND path = ?",
    folderParams(key)
  )
  return row ?? null
}

/**
 * Start or continue the culling session of a folder: created on the first visit, `last_visited_at`
 * (and the shot count, and the last shot when given) updated on every later one.
 */
export async function touchCullSession(
  db: Db,
  key: CullFolderKey,
  update: { shotCount?: number; lastShotId?: string | null } = {}
): Promise<void> {
  const at = now()
  await db.run(
    `INSERT INTO cull_sessions (location_id, share, path, started_at, last_visited_at, last_shot_id, shot_count)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (location_id, share, path) DO UPDATE SET
       last_visited_at = excluded.last_visited_at,
       last_shot_id = CASE WHEN ? THEN excluded.last_shot_id ELSE cull_sessions.last_shot_id END,
       shot_count = CASE WHEN ? THEN excluded.shot_count ELSE cull_sessions.shot_count END`,
    [
      ...folderParams(key),
      at,
      at,
      update.lastShotId ?? null,
      update.shotCount ?? 0,
      update.lastShotId !== undefined ? 1 : 0,
      update.shotCount !== undefined ? 1 : 0,
    ]
  )
}

export async function listCullDecisions(db: Db, key: CullFolderKey): Promise<CullDecisionRecord[]> {
  return db.query<CullDecisionRecord>(
    "SELECT * FROM cull_decisions WHERE location_id = ? AND share = ? AND path = ?",
    folderParams(key)
  )
}

/** What a shot is marked as; `{ flag: null, rating: 0 }` is "unmarked" (no row). */
export interface CullDecisionInput {
  shotId: string
  flag: CullFlag | null
  rating: number
}

/**
 * Save decisions for some shots in one transaction (one for a pick, several for an undo or a bulk
 * change). An unmarked shot's row is deleted, so the table only holds what was decided.
 */
export async function saveCullDecisions(
  db: Db,
  key: CullFolderKey,
  decisions: readonly CullDecisionInput[]
): Promise<void> {
  if (decisions.length === 0) return
  const at = now()
  await db.transaction(async () => {
    for (const { shotId, flag, rating } of decisions) {
      const stars = Math.max(0, Math.min(5, Math.round(rating)))
      if (!flag && stars === 0) {
        await db.run(
          "DELETE FROM cull_decisions WHERE location_id = ? AND share = ? AND path = ? AND shot_id = ?",
          [...folderParams(key), shotId]
        )
      } else {
        await db.run(
          `INSERT INTO cull_decisions (location_id, share, path, shot_id, flag, rating, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (location_id, share, path, shot_id) DO UPDATE SET
             flag = excluded.flag, rating = excluded.rating, updated_at = excluded.updated_at`,
          [...folderParams(key), shotId, flag, stars, at]
        )
      }
    }
  })
}

/** The sidecar rows of a folder (ADR 0011). */
export async function listCullSidecars(db: Db, key: CullFolderKey): Promise<CullSidecarRecord[]> {
  return db.query<CullSidecarRecord>(
    "SELECT * FROM cull_sidecars WHERE location_id = ? AND share = ? AND path = ?",
    folderParams(key)
  )
}

/**
 * Update the sidecar rows of some shots (ADR 0011): a field left undefined keeps its stored value. `decidedAt` is set
 * by decisions, `sidecarMtime` + `synced` by writes and imports.
 */
export async function saveCullSidecars(
  db: Db,
  key: CullFolderKey,
  rows: readonly {
    shotId: string
    decidedAt?: number | null
    sidecarMtime?: number | null
    synced?: boolean
  }[]
): Promise<void> {
  if (rows.length === 0) return
  await db.transaction(async () => {
    for (const row of rows) {
      await db.run(
        `INSERT INTO cull_sidecars (location_id, share, path, shot_id, decided_at, sidecar_mtime, synced)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (location_id, share, path, shot_id) DO UPDATE SET
           decided_at = CASE WHEN ? THEN excluded.decided_at ELSE decided_at END,
           sidecar_mtime = CASE WHEN ? THEN excluded.sidecar_mtime ELSE sidecar_mtime END,
           synced = CASE WHEN ? THEN excluded.synced ELSE synced END`,
        [
          ...folderParams(key),
          row.shotId,
          row.decidedAt ?? null,
          row.sidecarMtime ?? null,
          row.synced ? 1 : 0,
          row.decidedAt !== undefined ? 1 : 0,
          row.sidecarMtime !== undefined ? 1 : 0,
          row.synced !== undefined ? 1 : 0,
        ]
      )
    }
  })
}

/** Forget the decisions of shots that no longer exist (after "Delete rejects"). */
export async function forgetCullShots(
  db: Db,
  key: CullFolderKey,
  shotIds: readonly string[]
): Promise<void> {
  if (shotIds.length === 0) return
  await db.transaction(async () => {
    for (const shotId of shotIds) {
      await db.run(
        "DELETE FROM cull_decisions WHERE location_id = ? AND share = ? AND path = ? AND shot_id = ?",
        [...folderParams(key), shotId]
      )
      await db.run(
        "DELETE FROM cull_sidecars WHERE location_id = ? AND share = ? AND path = ? AND shot_id = ?",
        [...folderParams(key), shotId]
      )
    }
  })
}

export async function getCullDestination(
  db: Db,
  source: { locationId: string; share: string }
): Promise<CullDestinationRecord | null> {
  const [row] = await db.query<CullDestinationRecord>(
    "SELECT * FROM cull_destinations WHERE source_location_id = ? AND source_share = ?",
    [source.locationId, source.share]
  )
  return row ?? null
}

/** Remember where the picks of a source location were copied last (and with which options). */
export async function setCullDestination(
  db: Db,
  source: { locationId: string; share: string },
  destination: CullFolderKey & { label?: string | null },
  options: unknown
): Promise<void> {
  await db.run(
    `INSERT INTO cull_destinations (source_location_id, source_share, location_id, share, path, label, options, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (source_location_id, source_share) DO UPDATE SET
       location_id = excluded.location_id, share = excluded.share, path = excluded.path,
       label = excluded.label, options = excluded.options, updated_at = excluded.updated_at`,
    [
      source.locationId,
      source.share,
      destination.locationId,
      destination.share,
      destination.path,
      destination.label ?? null,
      JSON.stringify(options ?? {}),
      now(),
    ]
  )
}

// --- Assisted culling (ADR 0009) -------------------------------------------------------------

/** Every cached analysis of a folder's files (the caller checks size, modified time and version). */
export async function listCullAnalyses(db: Db, key: CullFolderKey): Promise<CullAnalysisRecord[]> {
  return db.query<CullAnalysisRecord>(
    "SELECT * FROM cull_analysis WHERE location_id = ? AND share = ? AND folder = ?",
    folderParams(key)
  )
}

export interface CullAnalysisInput {
  /** The analysed file. */
  path: string
  size: number
  modifiedAt: string | null
  version: number
  /** JSON of `CullAnalysis`. */
  analysis: string
}

/** Cache analyses of a folder's files in one transaction (a newer one replaces a stale row). */
export async function saveCullAnalyses(
  db: Db,
  key: CullFolderKey,
  items: readonly CullAnalysisInput[]
): Promise<void> {
  if (items.length === 0) return
  const at = now()
  await db.transaction(async () => {
    for (const item of items) {
      await db.run(
        `INSERT INTO cull_analysis (location_id, share, path, folder, size, modified_at, version, analysis, analysed_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (location_id, share, path) DO UPDATE SET
           folder = excluded.folder, size = excluded.size, modified_at = excluded.modified_at,
           version = excluded.version, analysis = excluded.analysis, analysed_at = excluded.analysed_at`,
        [
          key.locationId,
          key.share,
          item.path,
          key.path,
          item.size,
          item.modifiedAt,
          item.version,
          item.analysis,
          at,
        ]
      )
    }
  })
}

/** Drop a folder's cached analyses (Settings › clear, or files that were deleted). */
export async function forgetCullAnalyses(
  db: Db,
  key: CullFolderKey,
  paths?: readonly string[]
): Promise<void> {
  if (!paths) {
    await db.run(
      "DELETE FROM cull_analysis WHERE location_id = ? AND share = ? AND folder = ?",
      folderParams(key)
    )
    return
  }
  await db.transaction(async () => {
    for (const path of paths) {
      await db.run("DELETE FROM cull_analysis WHERE location_id = ? AND share = ? AND path = ?", [
        key.locationId,
        key.share,
        path,
      ])
    }
  })
}
