import initSqlJs from "sql.js"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { Db } from "@/db/db"
import { migrate } from "@/db/migrations"
import { createTransfer, listTransfers } from "@/db/queries"
import { seedDemoData } from "@/db/seed"
import { sqljsDb } from "@/db/sqljsDb"
import { MOCK_SERVERS } from "@/plugins/smb/mockData"
import { HoardSmbWeb } from "@/plugins/smb/web"
import { TransferService } from "./service"

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

beforeEach(async () => {
  store.clear()
  db = sqljsDb(new SQL.Database())
  await db.execute("PRAGMA foreign_keys = ON")
  await migrate(db)
  await seedDemoData(db)
  smb = new HoardSmbWeb()
  smb.latencyScale = 0
  service = new TransferService(smb, db)
  await service.start()
  dest = (await smb.pickDestinationFolder()).uri
})

const until = async (check: () => Promise<boolean>) => {
  for (let i = 0; i < 400; i++) {
    if (await check()) return
    await new Promise((r) => setTimeout(r, 5))
  }
  throw new Error("timed out")
}

const request = (files: { path: string; name: string; size: number }[]) => ({
  machineId: CACHY,
  addresses: [{ host: "192.168.1.155" }, { host: "100.101.12.34" }],
  expectedServerGuid: GUID,
  destTreeUri: dest,
  conflict: "rename" as const,
  files: files.map((f) => ({ share: "media", ...f })),
})

describe("TransferService", () => {
  it("records, runs, persists and acknowledges a download", async () => {
    const ack = vi.spyOn(smb, "acknowledgeTransfers")
    const outcome = await service.download(
      request([
        {
          path: "Music/Caetano Veloso - Transa (1972)/cover.jpg",
          name: "cover.jpg",
          size: 1_258_291,
        },
      ])
    )
    expect(outcome).toEqual({ started: 1, failed: [] })
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    const [row] = await listTransfers(db)
    expect(row.bytes_done).toBe(row.size)
    expect(row.dest_uri).toContain("cover.jpg")
    expect(row.finished_at).not.toBeNull()
    expect(ack).toHaveBeenCalledWith({ transferIds: [row.id] })
    expect(service.getLive(row.id)?.state).toBe("completed")
  })

  it("marks the row failed when native rejects the start", async () => {
    await smb.releaseDestinationFolder({ uri: dest })
    const outcome = await service.download(
      request([{ path: "Movies/Spirited Away (2001).srt", name: "a.srt", size: 1 }])
    )
    expect(outcome.failed).toEqual([{ name: "a.srt", code: "PERMISSION_REVOKED" }])
    const [row] = await listTransfers(db)
    expect(row).toMatchObject({ state: "failed", error_code: "PERMISSION_REVOKED" })
  })

  it("shows failover on the active host for a dropping transfer", async () => {
    const hosts = new Set<string>()
    const listener = await smb.addListener("transferProgress", (e) => {
      if (e.activeHost) hosts.add(e.activeHost)
    })
    await service.download(
      request([{ path: "Movies/flaky-network-test.mp4", name: "flaky.mp4", size: 1 }])
    )
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    expect([...hosts]).toEqual(["192.168.1.155", "100.101.12.34"])
    await listener.remove()
  })

  it("retries a cancelled transfer under the same id", async () => {
    await service.download(
      request([{ path: "Movies/Spirited Away (2001).mkv", name: "s.mkv", size: 1 }])
    )
    const [first] = await listTransfers(db)
    await service.cancel(first.id)
    await until(async () => (await listTransfers(db))[0]?.state === "cancelled")
    const [cancelled] = await listTransfers(db)
    const start = vi.spyOn(smb, "startDownload")
    await service.retry(cancelled, {
      addresses: [{ host: "192.168.1.155" }],
      expectedServerGuid: GUID,
    })
    expect(start).toHaveBeenCalledWith(
      expect.objectContaining({ transferId: first.id, conflict: "overwrite", resumeFromBytes: 0 })
    )
    await until(async () => (await listTransfers(db))[0]?.state === "completed")
    expect(await listTransfers(db)).toHaveLength(1)
  })

  it("keeps the resume offset when a retry fails to start", async () => {
    const id = await createTransfer(db, {
      machineId: CACHY,
      share: "media",
      remotePath: "Movies/x.mkv",
      destTreeUri: dest,
      destName: "x.mkv",
      size: 100,
    })
    await db.run("UPDATE transfers SET state = 'failed', bytes_done = 40 WHERE id = ?", [id])
    const [row] = await listTransfers(db)
    vi.spyOn(smb, "startDownload").mockRejectedValueOnce(
      Object.assign(new Error("revoked"), { code: "PERMISSION_REVOKED" })
    )
    await expect(
      service.retry(row, { addresses: [{ host: "192.168.1.155" }], expectedServerGuid: GUID })
    ).rejects.toThrow("revoked")
    expect((await listTransfers(db))[0]).toMatchObject({
      state: "failed",
      bytes_done: 40,
      error_code: "PERMISSION_REVOKED",
    })
  })

  it("reconcile fails rows native no longer knows and keeps fresh ones", async () => {
    const old = await createTransfer(db, {
      machineId: CACHY,
      share: "media",
      remotePath: "Movies/x.mkv",
      destTreeUri: dest,
      destName: "x.mkv",
      size: 100,
    })
    await db.run(
      "UPDATE transfers SET created_at = ?, state = 'running', bytes_done = 40 WHERE id = ?",
      ["2020-01-01T00:00:00.000Z", old]
    )
    const fresh = await createTransfer(db, {
      machineId: CACHY,
      share: "media",
      remotePath: "Movies/y.mkv",
      destTreeUri: dest,
      destName: "y.mkv",
      size: 100,
    })
    await service.reconcile()
    const rows = await listTransfers(db)
    expect(rows.find((r) => r.id === old)).toMatchObject({
      state: "failed",
      bytes_done: 40,
      error_code: "UNKNOWN",
    })
    expect(rows.find((r) => r.id === fresh)?.state).toBe("queued")
  })
})
