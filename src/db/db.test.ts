import initSqlJs from "sql.js"
import { beforeEach, describe, expect, it } from "vitest"
import type { Db } from "./db"
import { LATEST_VERSION, migrate, pendingMigrations } from "./migrations"
import {
  addFavorite,
  addManualShare,
  applyTransferProgress,
  clearFinishedTransfers,
  createTransfer,
  deleteMachine,
  getMachine,
  getSetting,
  listFavorites,
  listMachines,
  listManualShares,
  listTransfers,
  removeManualShare,
  saveMachine,
  setServerGuid,
  setSetting,
} from "./queries"
import { seedDemoData } from "./seed"
import { sqljsDb } from "./sqljsDb"

const SQL = await initSqlJs()

async function freshDb(): Promise<Db> {
  const db = sqljsDb(new SQL.Database())
  await db.execute("PRAGMA foreign_keys = ON")
  await migrate(db)
  return db
}

describe("migrations", () => {
  it("plans every migration for a new database and none at the latest version", () => {
    expect(pendingMigrations(0).map((m) => m.version)).toEqual([1, 2])
    expect(pendingMigrations(1).map((m) => m.version)).toEqual([2])
    expect(pendingMigrations(LATEST_VERSION)).toEqual([])
  })

  it("migrates a new database and is idempotent", async () => {
    const db = await freshDb()
    await migrate(db)
    const [{ user_version }] = await db.query<{ user_version: number }>("PRAGMA user_version")
    expect(user_version).toBe(LATEST_VERSION)
    const tables = (
      await db.query<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
      )
    ).map((t) => t.name)
    expect(tables).toEqual(
      expect.arrayContaining([
        "machines",
        "machine_addresses",
        "favorites",
        "transfers",
        "settings",
        "manual_shares",
      ])
    )
  })

  it("has no column that could hold a password", async () => {
    const db = await freshDb()
    const columns = await db.query<{ name: string }>(
      "SELECT name FROM pragma_table_info('machines')"
    )
    expect(columns.map((c) => c.name).filter((n) => /pass|secret/i.test(n))).toEqual([])
  })

  it("refuses a database from a newer app", async () => {
    const db = sqljsDb(new SQL.Database())
    await db.execute(`PRAGMA user_version = ${LATEST_VERSION + 1}`)
    await expect(migrate(db)).rejects.toThrow(/newer/)
  })
})

describe("queries", () => {
  let db: Db
  beforeEach(async () => {
    db = await freshDb()
  })

  it("saves a machine with ordered addresses and detected kinds", async () => {
    const id = await saveMachine(db, {
      name: "cachyos-fps",
      username: "henrique",
      addresses: [
        { host: "192.168.1.155" },
        { host: " 100.101.12.34 " },
        { host: "cachyos-fps.tail1234.ts.net", port: 4450 },
      ],
    })
    const machine = await getMachine(db, id)
    expect(machine?.addresses.map((a) => [a.host, a.kind, a.port])).toEqual([
      ["192.168.1.155", "local", 445],
      ["100.101.12.34", "tailscale", 445],
      ["cachyos-fps.tail1234.ts.net", "tailscale", 4450],
    ])
    expect(machine?.server_guid).toBeNull()
  })

  it("updates a machine, replacing its addresses, and pins the GUID lowercased", async () => {
    const id = await saveMachine(db, {
      name: "a",
      username: "u",
      addresses: [{ host: "10.0.0.1" }],
    })
    await saveMachine(db, { name: "b", username: "u", addresses: [{ host: "nas.local" }] }, id)
    await setServerGuid(db, id, "ABC-DEF")
    const machine = await getMachine(db, id)
    expect(machine?.name).toBe("b")
    expect(machine?.addresses.map((a) => a.host)).toEqual(["nas.local"])
    expect(machine?.server_guid).toBe("abc-def")
  })

  it("rolls back a failed save", async () => {
    await expect(
      saveMachine(db, { name: "x", username: "u", addresses: [{ host: "h" }] }, "missing")
    ).rejects.toThrow()
    expect(await listMachines(db)).toEqual([])
  })

  it("dedupes favorites and cascades machine deletion", async () => {
    const id = await saveMachine(db, { name: "m", username: "u", addresses: [] })
    const f1 = await addFavorite(db, {
      machineId: id,
      share: "media",
      path: "Movies",
      label: "Movies",
    })
    const f2 = await addFavorite(db, {
      machineId: id,
      share: "media",
      path: "Movies",
      label: "Films",
    })
    expect(f2).toBe(f1)
    expect((await listFavorites(db)).map((f) => f.label)).toEqual(["Films"])
    await createTransfer(db, {
      machineId: id,
      share: "media",
      remotePath: "a.mkv",
      destTreeUri: "content://t",
      destName: "a.mkv",
      size: 10,
    })
    await deleteMachine(db, id)
    expect(await listFavorites(db)).toEqual([])
    expect(await listTransfers(db)).toEqual([])
  })

  it("remembers shares added by name once per machine and cascades machine deletion", async () => {
    const id = await saveMachine(db, { name: "m", username: "u", addresses: [] })
    const other = await saveMachine(db, { name: "o", username: "u", addresses: [] })
    await addManualShare(db, id, "Pasta Pública")
    await addManualShare(db, id, "pasta pública") // same share, other case: kept once
    await addManualShare(db, id, "hidden$")
    await addManualShare(db, other, "hidden$")
    expect((await listManualShares(db, id)).map((s) => s.name)).toEqual([
      "hidden$",
      "Pasta Pública",
    ])
    await removeManualShare(db, id, "HIDDEN$")
    expect((await listManualShares(db, id)).map((s) => s.name)).toEqual(["Pasta Pública"])
    await deleteMachine(db, id)
    expect(await listManualShares(db, id)).toEqual([])
    expect((await listManualShares(db, other)).map((s) => s.name)).toEqual(["hidden$"])
  })

  it("applies transfer progress and clears finished ones", async () => {
    const machineId = await saveMachine(db, { name: "m", username: "u", addresses: [] })
    const id = await createTransfer(db, {
      machineId,
      share: "s",
      remotePath: "f",
      destTreeUri: "content://t",
      destName: "f",
      size: 100,
    })
    await applyTransferProgress(db, {
      transferId: id,
      bytesDone: 40,
      totalBytes: 100,
      state: "running",
    })
    let [row] = await listTransfers(db)
    expect([row.bytes_done, row.state, row.finished_at]).toEqual([40, "running", null])
    await applyTransferProgress(db, {
      transferId: id,
      bytesDone: 40,
      totalBytes: 100,
      state: "failed",
      error: { code: "NO_SPACE", message: "full" },
    })
    ;[row] = await listTransfers(db)
    expect([row.state, row.error_code]).toEqual(["failed", "NO_SPACE"])
    expect(row.finished_at).not.toBeNull()
    expect(await clearFinishedTransfers(db)).toBe(1)
  })

  it("stores settings as JSON", async () => {
    expect(await getSetting(db, "showHiddenFiles", false)).toBe(false)
    await setSetting(db, "showHiddenFiles", true)
    await setSetting(db, "showHiddenFiles", true)
    expect(await getSetting(db, "showHiddenFiles", false)).toBe(true)
  })

  it("seeds the demo machines once", async () => {
    await seedDemoData(db)
    await seedDemoData(db)
    const machines = await listMachines(db)
    expect(machines.map((m) => m.name)).toEqual(["cachyos-fps", "NAS da sala", "Office PC"])
    expect(machines[0].addresses.map((a) => a.kind)).toEqual(["local", "tailscale", "other"])
    expect((await listFavorites(db)).length).toBe(3)
  })
})
