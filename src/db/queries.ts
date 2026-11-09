import { detectAddressKind } from "@/lib/addressKind"
import type { TransferProgressEvent } from "@/plugins/smb/definitions"
import { TERMINAL_TRANSFER_STATES } from "@/plugins/smb/definitions"
import type { Db } from "./db"
import type {
  FavoriteRecord,
  MachineAddressRecord,
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

/** Creates (no `id`) or updates a machine and replaces its addresses atomically. Returns the id. */
export async function saveMachine(db: Db, input: MachineInput, id?: string): Promise<string> {
  return db.transaction(async () => {
    const machineId = id ?? newId()
    if (id) {
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
  await db.run("DELETE FROM machines WHERE id = ?", [id])
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

/** Pins a folder; pinning the same machine + share + path again just renames it. Returns the id. */
export async function addFavorite(
  db: Db,
  favorite: { machineId: string; share: string; path: string; label: string }
): Promise<string> {
  return db.transaction(async () => {
    const [existing] = await db.query<FavoriteRecord>(
      "SELECT * FROM favorites WHERE machine_id = ? AND share = ? AND path = ?",
      [favorite.machineId, favorite.share, favorite.path]
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
      "INSERT INTO favorites (id, machine_id, share, path, label, sort_order) VALUES (?, ?, ?, ?, ?, ?)",
      [id, favorite.machineId, favorite.share, favorite.path, favorite.label, next]
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

// --- Transfers -----------------------------------------------------------------------------

export async function listTransfers(db: Db): Promise<TransferRecord[]> {
  return db.query<TransferRecord>("SELECT * FROM transfers ORDER BY created_at DESC")
}

/** Records a transfer before `HoardSmb.startDownload` is called with the same id. */
export async function createTransfer(
  db: Db,
  transfer: {
    machineId: string
    share: string
    remotePath: string
    destTreeUri: string
    destName: string
    size: number
  }
): Promise<string> {
  const id = newId()
  await db.run(
    "INSERT INTO transfers (id, machine_id, share, remote_path, dest_tree_uri, dest_name, size, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    [
      id,
      transfer.machineId,
      transfer.share,
      transfer.remotePath,
      transfer.destTreeUri,
      transfer.destName,
      transfer.size,
      now(),
    ]
  )
  return id
}

/** Applies a native progress event (or a `getTransfers` entry) to the row. */
export async function applyTransferProgress(db: Db, event: TransferProgressEvent): Promise<void> {
  const finished = TERMINAL_TRANSFER_STATES.includes(event.state)
  await db.run(
    `UPDATE transfers SET bytes_done = ?, size = ?, state = ?, error_code = ?, error = ?,
       finished_at = CASE WHEN ? THEN COALESCE(finished_at, ?) ELSE NULL END
     WHERE id = ?`,
    [
      event.bytesDone,
      event.totalBytes,
      event.state,
      event.error?.code ?? null,
      event.error?.message ?? null,
      finished ? 1 : 0,
      now(),
      event.transferId,
    ]
  )
}

export async function setTransferDestination(
  db: Db,
  id: string,
  destName: string,
  destUri: string
) {
  await db.run("UPDATE transfers SET dest_name = ?, dest_uri = ? WHERE id = ?", [
    destName,
    destUri,
    id,
  ])
}

/** Removes finished transfers from the history. */
export async function clearFinishedTransfers(db: Db): Promise<number> {
  const placeholders = TERMINAL_TRANSFER_STATES.map(() => "?").join(", ")
  const { changes } = await db.run(`DELETE FROM transfers WHERE state IN (${placeholders})`, [
    ...TERMINAL_TRANSFER_STATES,
  ])
  return changes
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
