import initSqlJs from "sql.js"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { Db } from "@/db/db"
import { migrate } from "@/db/migrations"
import { createTransfer, listTransfers, transferSourcePaths } from "@/db/queries"
import { seedDemoData } from "@/db/seed"
import { sqljsDb } from "@/db/sqljsDb"
import { MOCK_SERVERS } from "@/plugins/smb/mockData"
import { HoardSmbWeb } from "@/plugins/smb/web"
import { type PlaceResolver, TransferService, TREE_LOCATION_ID } from "./service"

const store = new Map<string, string>()
vi.stubGlobal("localStorage", {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
})

const SQL = await initSqlJs()
const CACHY = "demo-cachyos-fps"
const GUID = MOCK_SERVERS.cachy.guid

let db: Db
let smb: HoardSmbWeb
let service: TransferService
let dest: string

/** Like the provider's resolver, with the demo machine's route. */
const resolve: PlaceResolver = async (place) => {
  if (place.locationId === "device") return { kind: "device" }
  if (place.locationId.startsWith("vol-"))
    return { kind: "volume", uuid: place.locationId.slice(4) }
  if (place.locationId === TREE_LOCATION_ID) return { kind: "tree", uri: place.treeUri! }
  if (place.locationId === "incoming") return { kind: "incoming", shareId: place.shareId! }
  return {
    kind: "smb",
    machineId: place.locationId,
    share: place.share ?? "",
    addresses: [{ host: "192.168.1.155" }, { host: "100.101.12.34" }],
    expectedServerGuid: GUID,
  }
}

beforeEach(async () => {
  store.clear()
  db = sqljsDb(new SQL.Database())
  await db.execute("PRAGMA foreign_keys = ON")
  await migrate(db)
  await seedDemoData(db)
  smb = new HoardSmbWeb()
  smb.latencyScale = 0
  smb.simulateStorageAccess(true)
  service = new TransferService(smb, db, resolve)
  await service.start()
  dest = (await smb.pickDestinationFolder()).uri
})

const until = async (check: () => Promise<boolean>) => {
  for (let i = 0; i < 2000; i++) {
    if (await check()) return
    await new Promise((r) => setTimeout(r, 1))
  }
  throw new Error("timed out")
}

const nasToTree = (paths: string[], op: "copy" | "move" = "copy") => ({
  op,
  source: { locationId: CACHY, share: "media" },
  paths,
  name: paths[0].split("/").at(-1)!,
  destination: { locationId: TREE_LOCATION_ID, treeUri: dest, path: "" },
  conflict: "rename" as const,
})

describe("TransferService", () => {
  it("records, runs, persists and acknowledges a folder copy", async () => {
    const ack = vi.spyOn(smb, "acknowledgeTransfers")
    const id = await service.transfer(nasToTree(["Music"]))
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    const [row] = await listTransfers(db)
    expect(row.id).toBe(id)
    expect(row).toMatchObject({
      op: "copy",
      source_location_id: CACHY,
      source_machine_id: CACHY,
      dest_location_id: "tree",
    })
    expect(transferSourcePaths(row)).toEqual(["Music"])
    expect(row.items_total).toBe(3)
    expect(row.items_done).toBe(3)
    expect(row.bytes_done).toBe(row.size)
    expect(row.finished_at).not.toBeNull()
    expect(ack).toHaveBeenCalledWith({ transferIds: [row.id] })
    expect(service.getLive(row.id)?.state).toBe("completed")
  })

  it("marks the row failed when native rejects the start", async () => {
    await smb.releaseDestinationFolder({ uri: dest })
    await expect(
      service.transfer(nasToTree(["Movies/Spirited Away (2001).srt"]))
    ).rejects.toMatchObject({
      code: "PERMISSION_REVOKED",
    })
    const [row] = await listTransfers(db)
    expect(row).toMatchObject({ state: "failed", error_code: "PERMISSION_REVOKED" })
  })

  it("shows failover on the active host for a dropping transfer", async () => {
    const hosts = new Set<string>()
    const listener = await smb.addListener("transferProgress", (e) => {
      if (e.activeHost) hosts.add(e.activeHost)
    })
    await service.transfer(nasToTree(["Movies/flaky-network-test.mp4"]))
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    expect([...hosts]).toEqual(["192.168.1.155", "100.101.12.34"])
    await listener.remove()
  })

  it("retries a cancelled transfer under the same id", async () => {
    await service.transfer(nasToTree(["Movies/Spirited Away (2001).mkv"]))
    const [first] = await listTransfers(db)
    await service.cancel(first.id)
    await until(async () => (await listTransfers(db))[0]?.state === "cancelled")
    const [cancelled] = await listTransfers(db)
    await service.retry(cancelled)
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    const rows = await listTransfers(db)
    expect(rows).toHaveLength(1)
    expect(rows[0].id).toBe(first.id)
  })

  it("moves from a USB drive to the phone", async () => {
    await service.transfer({
      op: "move",
      source: { locationId: "vol-1A2B-3C4D" },
      paths: ["PRIVATE"],
      name: "PRIVATE",
      destination: { locationId: "device", path: "Download" },
      conflict: "rename",
    })
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    const { entries } = await smb.list({
      location: { kind: "volume", uuid: "1A2B-3C4D" },
      path: "",
    })
    expect(entries.map((e) => e.name)).not.toContain("PRIVATE")
  })

  it("asks about conflicts and continues once answered", async () => {
    await service.transfer({ ...nasToTree(["Movies/Spirited Away (2001).srt"]) })
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    const id = await service.transfer({
      ...nasToTree(["Movies/Spirited Away (2001).srt"]),
      conflict: "ask",
    })
    await until(async () => service.getLive(id)?.state === "conflict")
    expect(service.getLive(id)?.conflicts).toBe(1)
    await service.resolveConflicts(id, { all: "skip" })
    await until(async () => (await listTransfers(db)).find((r) => r.id === id)?.state === "skipped")
  })

  it("fails rows native no longer knows on reconcile, but not this session's", async () => {
    const old = await createTransfer(db, {
      op: "copy",
      sourceLocationId: CACHY,
      sourceMachineId: CACHY,
      sourceShare: "media",
      sourcePaths: ["a"],
      destLocationId: "device",
      destMachineId: null,
      destShare: "",
      destPath: "",
      destTreeUri: null,
      name: "a",
      conflict: "rename",
      size: 10,
      itemsTotal: 1,
    })
    await db.run("UPDATE transfers SET created_at = ? WHERE id = ?", [
      "2020-01-01T00:00:00.000Z",
      old,
    ])
    await service.reconcile()
    const [row] = await listTransfers(db)
    expect(row).toMatchObject({ id: old, state: "failed", error_code: "UNKNOWN" })
  })
})
