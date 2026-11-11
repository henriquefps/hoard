import initSqlJs from "sql.js"
import { beforeEach, describe, expect, it } from "vitest"
import type { Db } from "./db"
import { LATEST_VERSION, migrate, pendingMigrations } from "./migrations"
import {
  addFavorite,
  addManualShare,
  forgetCullAnalyses,
  forgetCullShots,
  listCullAnalyses,
  saveCullAnalyses,
  getCullDestination,
  getCullSession,
  listCullDecisions,
  listCullSidecars,
  saveCullSidecars,
  saveCullDecisions,
  setCullDestination,
  touchCullSession,
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
  type NewTransfer,
  removeManualShare,
  saveMachine,
  transferSourcePaths,
  setServerGuid,
  setSetting,
} from "./queries"
import { seedDemoData } from "./seed"
import { sqljsDb } from "./sqljsDb"

const SQL = await initSqlJs()

const newTransfer = (machineId: string, over: Partial<NewTransfer> = {}): NewTransfer => ({
  op: "copy",
  sourceLocationId: machineId,
  sourceMachineId: machineId,
  sourceShare: "media",
  sourcePaths: ["a.mkv"],
  destLocationId: "device",
  destMachineId: null,
  destShare: "",
  destPath: "Download",
  destTreeUri: null,
  name: "a.mkv",
  conflict: "ask",
  size: 100,
  itemsTotal: 1,
  ...over,
})

const progress = { itemsTotal: 1, itemsDone: 0, itemsFailed: 0, itemsSkipped: 0 }

async function freshDb(): Promise<Db> {
  const db = sqljsDb(new SQL.Database())
  await db.execute("PRAGMA foreign_keys = ON")
  await migrate(db)
  return db
}

describe("migrations", () => {
  it("plans every migration for a new database and none at the latest version", () => {
    expect(pendingMigrations(0).map((m) => m.version)).toEqual([1, 2, 3, 4, 5, 6, 7])
    expect(pendingMigrations(1).map((m) => m.version)).toEqual([2, 3, 4, 5, 6, 7])
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
        "cull_sessions",
        "cull_decisions",
        "cull_destinations",
        "cull_analysis",
        "cull_sidecars",
      ])
    )
  })

  it("v3 keeps every v2 favorite as an SMB location favorite and adds local ones", async () => {
    // A database as 1.2.x left it: version 2, two favorites on one machine.
    const db = sqljsDb(new SQL.Database())
    await db.execute("PRAGMA foreign_keys = ON")
    for (const migration of pendingMigrations(0).filter((m) => m.version <= 2)) {
      await db.execute(migration.sql)
      await db.execute(`PRAGMA user_version = ${migration.version}`)
    }
    await db.run("INSERT INTO machines (id, name) VALUES ('m1', 'cachyos-fps')")
    await db.run(
      "INSERT INTO favorites (id, machine_id, share, path, label, sort_order, created_at) VALUES ('f1', 'm1', 'media', 'Movies', 'Films', 1, '2026-01-01T00:00:00.000Z'), ('f2', 'm1', 'media', '', 'media', 0, '2026-01-02T00:00:00.000Z')"
    )
    await migrate(db)
    const rows = await listFavorites(db)
    expect(rows.map((f) => [f.id, f.location_id, f.machine_id, f.share, f.path, f.label])).toEqual([
      ["f2", "m1", "m1", "media", "", "media"],
      ["f1", "m1", "m1", "media", "Movies", "Films"],
    ])
    // Local favorites: no machine; the same folder twice is one favorite.
    const usb = await addFavorite(db, {
      locationId: "vol-1A2B-3C4D",
      machineId: null,
      share: "",
      path: "DCIM/100MSDCF",
      label: "100MSDCF",
    })
    await addFavorite(db, {
      locationId: "vol-1A2B-3C4D",
      machineId: null,
      share: "",
      path: "DCIM/100MSDCF",
      label: "Sony",
    })
    await addFavorite(db, {
      locationId: "device",
      machineId: null,
      share: "",
      path: "DCIM",
      label: "DCIM",
    })
    expect((await listFavorites(db)).find((f) => f.id === usb)?.label).toBe("Sony")
    expect((await listFavorites(db)).length).toBe(4)
    // A local location id with a machine, or an SMB one without, is refused.
    await expect(
      db.run(
        "INSERT INTO favorites (id, location_id, machine_id, share, path, label) VALUES ('x', 'device', 'm1', '', 'a', 'a')"
      )
    ).rejects.toThrow()
    await expect(
      db.run(
        "INSERT INTO favorites (id, location_id, machine_id, share, path, label) VALUES ('y', 'm1', NULL, 'media', 'a', 'a')"
      )
    ).rejects.toThrow()
    // Deleting the machine removes only its favorites.
    await deleteMachine(db, "m1")
    expect((await listFavorites(db)).map((f) => f.location_id).sort()).toEqual([
      "device",
      "vol-1A2B-3C4D",
    ])
  })

  it("v4 turns 1.3 downloads into copies from the machine to a SAF folder", async () => {
    const db = sqljsDb(new SQL.Database())
    await db.execute("PRAGMA foreign_keys = ON")
    for (const migration of pendingMigrations(0).filter((m) => m.version <= 3)) {
      await db.execute(migration.sql)
      await db.execute(`PRAGMA user_version = ${migration.version}`)
    }
    await db.run("INSERT INTO machines (id, name) VALUES ('m1', 'nas')")
    await db.run(
      `INSERT INTO transfers (id, machine_id, share, remote_path, dest_tree_uri, dest_name, dest_uri, size, bytes_done, state)
       VALUES ('t1', 'm1', 'media', 'Movies/"Quoted" \\ name.mkv', 'content://tree', 'x (1).mkv', 'content://doc', 100, 100, 'completed'),
              ('t2', 'm1', 'media', 'b.iso', 'content://tree', 'b.iso', NULL, 900, 30, 'failed')`
    )
    await migrate(db)
    const rows = await listTransfers(db)
    const done = rows.find((r) => r.id === "t1")!
    expect(done).toMatchObject({
      op: "copy",
      source_location_id: "m1",
      source_machine_id: "m1",
      source_share: "media",
      dest_location_id: "tree",
      dest_tree_uri: "content://tree",
      name: "x (1).mkv",
      items_total: 1,
      items_done: 1,
      state: "completed",
    })
    expect(transferSourcePaths(done)).toEqual(['Movies/"Quoted" \\ name.mkv'])
    expect(rows.find((r) => r.id === "t2")).toMatchObject({
      items_failed: 1,
      items_done: 0,
      bytes_done: 30,
    })
    await deleteMachine(db, "m1")
    expect(await listTransfers(db)).toEqual([])
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
      locationId: id,
      machineId: id,
      share: "media",
      path: "Movies",
      label: "Movies",
    })
    const f2 = await addFavorite(db, {
      locationId: id,
      machineId: id,
      share: "media",
      path: "Movies",
      label: "Films",
    })
    expect(f2).toBe(f1)
    expect((await listFavorites(db)).map((f) => f.label)).toEqual(["Films"])
    await createTransfer(db, newTransfer(id))
    // An upload to the machine goes with it too.
    await createTransfer(
      db,
      newTransfer("device", {
        sourceMachineId: null,
        destLocationId: id,
        destMachineId: id,
        destShare: "media",
      })
    )
    expect(await listTransfers(db)).toHaveLength(2)
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
    const id = await createTransfer(db, newTransfer(machineId))
    await applyTransferProgress(db, {
      ...progress,
      transferId: id,
      bytesDone: 40,
      totalBytes: 100,
      itemsTotal: 12,
      itemsDone: 5,
      state: "running",
    })
    let [row] = await listTransfers(db)
    expect([row.bytes_done, row.state, row.finished_at, row.items_total, row.items_done]).toEqual([
      40,
      "running",
      null,
      12,
      5,
    ])
    await applyTransferProgress(db, {
      ...progress,
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

  it("keeps culling sessions, decisions and the last destination per source (v5)", async () => {
    const key = { locationId: "vol-1A2B-3C4D", share: "", path: "DCIM/100MSDCF" }
    expect(await getCullSession(db, key)).toBeNull()
    await touchCullSession(db, key, { shotCount: 36 })
    const first = (await getCullSession(db, key))!
    expect([first.shot_count, first.last_shot_id]).toEqual([36, null])
    await touchCullSession(db, key, { lastShotId: "dsc07651" })
    const second = (await getCullSession(db, key))!
    expect([second.shot_count, second.last_shot_id, second.started_at]).toEqual([
      36,
      "dsc07651",
      first.started_at,
    ])
    expect(second.last_visited_at >= first.last_visited_at).toBe(true)

    await saveCullDecisions(db, key, [
      { shotId: "dsc07650", flag: "pick", rating: 4 },
      { shotId: "dsc07651", flag: "reject", rating: 0 },
      { shotId: "dsc07652", flag: null, rating: 2 },
      { shotId: "dsc07653", flag: null, rating: 0 },
    ])
    const rows = async () =>
      (await listCullDecisions(db, key))
        .map((r) => [r.shot_id, r.flag, r.rating])
        .sort((a, b) => String(a[0]).localeCompare(String(b[0])))
    expect(await rows()).toEqual([
      ["dsc07650", "pick", 4],
      ["dsc07651", "reject", 0],
      ["dsc07652", null, 2],
    ])
    // Unmarking deletes the row; another folder or location is separate; stars are clamped.
    await saveCullDecisions(db, key, [
      { shotId: "dsc07652", flag: null, rating: 0 },
      { shotId: "dsc07650", flag: "pick", rating: 9 },
    ])
    await saveCullDecisions(db, { ...key, path: "DCIM/101MSDCF" }, [
      { shotId: "dsc07650", flag: "reject", rating: 0 },
    ])
    expect(await rows()).toEqual([
      ["dsc07650", "pick", 5],
      ["dsc07651", "reject", 0],
    ])
    await expect(
      db.run(
        "INSERT INTO cull_decisions (location_id, share, path, shot_id, flag) VALUES ('device', '', '', 'x', 'maybe')"
      )
    ).rejects.toThrow()
    await forgetCullShots(db, key, ["dsc07651"])
    expect(await rows()).toEqual([["dsc07650", "pick", 5]])

    const source = { locationId: key.locationId, share: "" }
    expect(await getCullDestination(db, source)).toBeNull()
    await setCullDestination(
      db,
      source,
      { locationId: "device", share: "", path: "Pictures" },
      { files: "raw" }
    )
    await setCullDestination(
      db,
      source,
      { locationId: "m1", share: "photos", path: "2026", label: "photos" },
      { files: "both", folder: "keep" }
    )
    const destination = (await getCullDestination(db, source))!
    expect([
      destination.location_id,
      destination.share,
      destination.path,
      destination.label,
    ]).toEqual(["m1", "photos", "2026", "photos"])
    expect(JSON.parse(destination.options)).toEqual({ files: "both", folder: "keep" })
  })

  it("caches analyses per file, size, modified time and version (v6)", async () => {
    const key = { locationId: "vol-1A2B-3C4D", share: "", path: "DCIM/100MSDCF" }
    expect(await listCullAnalyses(db, key)).toEqual([])
    const item = {
      path: "DCIM/100MSDCF/DSC07650.ARW",
      size: 25_000_000,
      modifiedAt: "2026-08-09T10:00:00.000Z",
      version: 1,
      analysis: JSON.stringify({ hash: "00ff00ff00ff00ff" }),
    }
    await saveCullAnalyses(db, key, [
      item,
      { ...item, path: "DCIM/100MSDCF/DSC07651.JPG", modifiedAt: null },
    ])
    await saveCullAnalyses(db, { ...key, path: "DCIM/101MSDCF" }, [
      { ...item, path: "DCIM/101MSDCF/X.ARW" },
    ])
    const rows = await listCullAnalyses(db, key)
    expect(rows.map((r) => [r.path, r.size, r.modified_at, r.version]).sort()).toEqual([
      ["DCIM/100MSDCF/DSC07650.ARW", 25_000_000, "2026-08-09T10:00:00.000Z", 1],
      ["DCIM/100MSDCF/DSC07651.JPG", 25_000_000, null, 1],
    ])
    // A newer result replaces the row of the same file.
    await saveCullAnalyses(db, key, [{ ...item, size: 1, version: 2, analysis: "{}" }])
    const replaced = (await listCullAnalyses(db, key)).find((r) => r.path === item.path)!
    expect([replaced.size, replaced.version, replaced.analysis]).toEqual([1, 2, "{}"])
    await forgetCullAnalyses(db, key, [item.path])
    expect(await listCullAnalyses(db, key)).toHaveLength(1)
    await forgetCullAnalyses(db, key)
    expect(await listCullAnalyses(db, key)).toEqual([])
    expect(await listCullAnalyses(db, { ...key, path: "DCIM/101MSDCF" })).toHaveLength(1)
  })

  it("keeps sidecar times per shot, field by field, and forgets them with the shot (v7)", async () => {
    const key = { locationId: "device", share: "", path: "DCIM/100MSDCF" }
    await saveCullSidecars(db, key, [{ shotId: "a", decidedAt: 1000, synced: false }])
    await saveCullSidecars(db, key, [{ shotId: "a", sidecarMtime: 2000, synced: true }])
    await saveCullSidecars(db, key, [{ shotId: "b", sidecarMtime: 3000, synced: true }])
    await saveCullSidecars(db, { ...key, path: "other" }, [{ shotId: "a", decidedAt: 5 }])
    const rows = await listCullSidecars(db, key)
    expect(rows.map((r) => [r.shot_id, r.decided_at, r.sidecar_mtime, r.synced]).sort()).toEqual([
      ["a", 1000, 2000, 1],
      ["b", null, 3000, 1],
    ])
    // A later decision keeps the stored mtime and clears `synced`.
    await saveCullSidecars(db, key, [{ shotId: "a", decidedAt: 4000, synced: false }])
    const a = (await listCullSidecars(db, key)).find((r) => r.shot_id === "a")!
    expect([a.decided_at, a.sidecar_mtime, a.synced]).toEqual([4000, 2000, 0])
    await forgetCullShots(db, key, ["a"])
    expect((await listCullSidecars(db, key)).map((r) => r.shot_id)).toEqual(["b"])
    await expect(
      db.run("INSERT INTO cull_sidecars (location_id, shot_id, synced) VALUES ('device', 'x', 2)")
    ).rejects.toThrow()
  })

  it("drops the old Copy picks switch so the new default applies (v7)", async () => {
    const old = sqljsDb(new SQL.Database())
    for (const migration of pendingMigrations(0).filter((m) => m.version <= 6)) {
      await old.execute(migration.sql)
      await old.execute(`PRAGMA user_version = ${migration.version}`)
    }
    await setSetting(old, "cullXmpSidecars", false)
    await setSetting(old, "cullAutoAdvance", false)
    await migrate(old)
    expect(await getSetting(old, "cullXmpSidecars", "gone")).toBe("gone")
    expect(await getSetting(old, "cullAutoAdvance", true)).toBe(false)
  })

  it("clears a machine's culling rows when the machine is deleted", async () => {
    const id = await saveMachine(db, {
      name: "nas",
      username: "u",
      addresses: [{ host: "10.0.0.2" }],
    })
    const key = { locationId: id, share: "photos", path: "2026" }
    await touchCullSession(db, key, { shotCount: 3 })
    await saveCullDecisions(db, key, [{ shotId: "a", flag: "pick", rating: 0 }])
    await saveCullDecisions(db, { locationId: "device", share: "", path: "" }, [
      { shotId: "a", flag: "pick", rating: 0 },
    ])
    await setCullDestination(db, { locationId: "device", share: "" }, { ...key }, {})
    await saveCullAnalyses(db, key, [
      { path: "2026/a.jpg", size: 1, modifiedAt: null, version: 1, analysis: "{}" },
    ])
    await saveCullSidecars(db, key, [{ shotId: "a", decidedAt: 1 }])
    await deleteMachine(db, id)
    expect(await listCullSidecars(db, key)).toEqual([])
    expect(await listCullAnalyses(db, key)).toEqual([])
    expect(await getCullSession(db, key)).toBeNull()
    expect(await listCullDecisions(db, key)).toEqual([])
    expect(await getCullDestination(db, { locationId: "device", share: "" })).toBeNull()
    expect(await listCullDecisions(db, { locationId: "device", share: "", path: "" })).toHaveLength(
      1
    )
  })
})
