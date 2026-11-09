import { beforeEach, describe, expect, it, vi } from "vitest"
import type { TransferProgressEvent } from "./definitions"
import { isHoardSmbError, smbErrorCode } from "./definitions"
import { MOCK_SERVERS } from "./mockData"
import { HoardSmbWeb } from "./web"

const CACHY = "demo-cachyos-fps"

// The mock persists picked SAF folders in localStorage; node has none.
const store = new Map<string, string>()
vi.stubGlobal("localStorage", {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
})

let smb: HoardSmbWeb
beforeEach(() => {
  store.clear()
  smb = new HoardSmbWeb()
  smb.latencyScale = 0
})

const connectCachy = () => smb.connect({ machineId: CACHY, host: "192.168.1.155" })

describe("HoardSmbWeb", () => {
  it("probes: local reachable, tailscale down address times out, unknown refused", async () => {
    expect((await smb.probe({ host: "192.168.1.155" })).reachable).toBe(true)
    expect(await smb.probe({ host: "100.88.7.5" })).toEqual({ reachable: false, reason: "TIMEOUT" })
    expect(await smb.probe({ host: "203.0.113.9" })).toEqual({
      reachable: false,
      reason: "UNREACHABLE",
    })
  })

  it("connects and reports the server GUID", async () => {
    const session = await connectCachy()
    expect(session.serverGuid).toBe(MOCK_SERVERS.cachy.guid)
    expect(session.dialect).toBe("SMB_3_1_1")
  })

  it("rejects GUID_MISMATCH before authenticating on someone else's server", async () => {
    const error = await smb
      .connect({
        machineId: CACHY,
        host: "192.168.0.10",
        expectedServerGuid: MOCK_SERVERS.cachy.guid,
      })
      .catch((e: unknown) => e)
    expect(smbErrorCode(error)).toBe("GUID_MISMATCH")
    expect(isHoardSmbError(error) && error.data?.serverGuid).toBe(MOCK_SERVERS.neighbor.guid)
  })

  it("rejects AUTH_FAILED for the office PC until the right password is saved", async () => {
    const attempt = () => smb.connect({ machineId: "demo-office-pc", host: "office.example.com" })
    expect(smbErrorCode(await attempt().catch((e) => e))).toBe("AUTH_FAILED")
    await smb.setCredentials({
      machineId: "demo-office-pc",
      username: "henrique",
      password: "office",
    })
    await expect(attempt()).resolves.toMatchObject({ serverName: "OFFICE-PC" })
  })

  it("never returns a password", async () => {
    await smb.setCredentials({ machineId: "m1", username: "u", password: "secret" })
    const result = await smb.hasCredentials({ machineId: "m1" })
    expect(JSON.stringify(result)).not.toContain("secret")
    expect(result.value).toBe(true)
    await smb.deleteCredentials({ machineId: "m1" })
    expect((await smb.hasCredentials({ machineId: "m1" })).value).toBe(false)
  })

  it("lists shares (no IPC$), folders, and maps errors", async () => {
    const { sessionId } = await connectCachy()
    const { shares } = await smb.listShares({ sessionId })
    expect(shares.map((s) => s.name)).toEqual(["media", "photos", "homes"])
    const { entries } = await smb.list({ sessionId, share: "media", path: "Movies" })
    expect(entries.find((e) => e.name.startsWith("Blade"))?.size).toBeGreaterThan(8 * 1024 ** 3)
    expect(entries.every((e) => e.path.startsWith("Movies/"))).toBe(true)
    expect((await smb.list({ sessionId, share: "photos", path: "Scans" })).entries).toHaveLength(
      1284
    )
    expect((await smb.list({ sessionId, share: "media", path: "Empty folder" })).entries).toEqual(
      []
    )
    expect(
      smbErrorCode(await smb.list({ sessionId, share: "media", path: "Nope" }).catch((e) => e))
    ).toBe("NOT_FOUND")
    expect(
      smbErrorCode(await smb.list({ sessionId: "gone", share: "media", path: "" }).catch((e) => e))
    ).toBe("NOT_FOUND")
  })

  it("creates, renames and deletes", async () => {
    const { sessionId } = await connectCachy()
    await smb.createFolder({ sessionId, share: "media", path: "", name: "New" })
    expect(
      smbErrorCode(
        await smb.createFolder({ sessionId, share: "media", path: "", name: "New" }).catch((e) => e)
      )
    ).toBe("ALREADY_EXISTS")
    const { entry } = await smb.rename({
      sessionId,
      share: "media",
      path: "New",
      newName: "Renamed",
    })
    expect(entry.path).toBe("Renamed")
    await smb.delete({ sessionId, share: "media", path: "Renamed" })
    expect(
      smbErrorCode(await smb.stat({ sessionId, share: "media", path: "Renamed" }).catch((e) => e))
    ).toBe("NOT_FOUND")
  })

  it("returns an <img>-ready thumbnail for images and videos only", async () => {
    const { sessionId } = await connectCachy()
    const thumb = await smb.thumbnail({
      sessionId,
      share: "photos",
      path: "screenshot.webp",
      maxSize: 96,
    })
    expect(thumb.src.startsWith("data:image/svg+xml")).toBe(true)
    expect(Math.max(thumb.width, thumb.height)).toBe(96)
    const movies = await smb.list({ sessionId, share: "media", path: "Movies" })
    const movie = movies.entries.find((e) => !e.isDirectory && /\.(mkv|mp4)$/.test(e.name))!
    const frame = await smb.thumbnail({ sessionId, share: "media", path: movie.path, maxSize: 96 })
    expect(frame.src.startsWith("data:image/svg+xml")).toBe(true)
    expect(
      smbErrorCode(
        await smb
          .thumbnail({
            sessionId,
            share: "homes",
            path: "henrique/Documents/notes.md",
            maxSize: 96,
          })
          .catch((e) => e)
      )
    ).toBe("UNKNOWN")
  })

  it("runs a download with progress events, failing over when the connection drops", async () => {
    vi.useFakeTimers()
    try {
      smb.latencyScale = 0
      const folder = await smb.pickDestinationFolder()
      const events: TransferProgressEvent[] = []
      await smb.addListener("transferProgress", (e) => events.push(e))
      await smb.startDownload({
        transferId: "t1",
        machineId: CACHY,
        addresses: [{ host: "192.168.1.155" }, { host: "100.101.12.34" }],
        expectedServerGuid: MOCK_SERVERS.cachy.guid,
        share: "media",
        remotePath: "Movies/flaky-network-test.mp4",
        destTreeUri: folder.uri,
        conflict: "rename",
      })
      for (let i = 0; i < 5000 && events.at(-1)?.state !== "completed"; i++)
        await vi.advanceTimersByTimeAsync(10)
      const states = [...new Set(events.map((e) => e.state))]
      expect(states).toEqual(expect.arrayContaining(["queued", "running", "retrying", "completed"]))
      expect(events.at(-1)?.activeHost).toBe("100.101.12.34")
      expect(events.at(-1)?.bytesDone).toBe(events.at(-1)?.totalBytes)
      const { transfers } = await smb.getTransfers()
      expect(transfers[0]).toMatchObject({ transferId: "t1", state: "completed" })
      await smb.acknowledgeTransfers({ transferIds: ["t1"] })
      expect((await smb.getTransfers()).transfers).toEqual([])
    } finally {
      vi.useRealTimers()
    }
  })

  it("rejects PERMISSION_REVOKED for a folder that was never granted, and cancels", async () => {
    const base = {
      transferId: "t2",
      machineId: CACHY,
      addresses: [{ host: "192.168.1.155" }],
      expectedServerGuid: MOCK_SERVERS.cachy.guid,
      share: "media",
      remotePath: "Movies/Spirited Away (2001).mkv",
      conflict: "overwrite" as const,
    }
    expect(
      smbErrorCode(
        await smb.startDownload({ ...base, destTreeUri: "content://nope" }).catch((e) => e)
      )
    ).toBe("PERMISSION_REVOKED")
    const folder = await smb.pickDestinationFolder()
    await smb.startDownload({ ...base, destTreeUri: folder.uri })
    await smb.cancelTransfer({ transferId: "t2" })
    expect((await smb.getTransfers()).transfers[0].state).toBe("cancelled")
  })

  it("fails a 'broken' transfer for good mid-way (no address left)", async () => {
    const folder = await smb.pickDestinationFolder()
    const events: TransferProgressEvent[] = []
    await smb.addListener("transferProgress", (e) => events.push(e))
    await smb.startDownload({
      transferId: "t3",
      machineId: "worst-lab",
      addresses: [{ host: "192.168.1.77" }],
      expectedServerGuid: MOCK_SERVERS.lab.guid,
      share: "Arquivo morto — 2019–2026 (não apagar)",
      remotePath: "broken-transfer-demo.iso",
      destTreeUri: folder.uri,
      conflict: "overwrite",
    })
    for (let i = 0; i < 200 && events.at(-1)?.state !== "failed"; i++) {
      await new Promise((r) => setTimeout(r, 2))
    }
    const last = events.at(-1)!
    expect(last.state).toBe("failed")
    expect(last.error?.code).toBe("UNREACHABLE")
    expect(last.bytesDone).toBeGreaterThan(0)
    expect(last.bytesDone).toBeLessThan(last.totalBytes)
  })

  it("lists the 5,000-entry worst-case folder", async () => {
    const { sessionId } = await smb.connect({ machineId: "worst-lab", host: "192.168.1.77" })
    const { entries } = await smb.list({
      sessionId,
      share: "Arquivo morto — 2019–2026 (não apagar)",
      path: "5000 itens",
    })
    expect(entries).toHaveLength(5000)
    expect(entries.some((e) => e.modifiedAt === null)).toBe(true)
  })

  it("simulates leaving home and going offline, emitting networkChanged", async () => {
    const changes: unknown[] = []
    await smb.addListener("networkChanged", (e) => changes.push(e))
    try {
      smb.simulateNetwork("away")
      expect((await smb.probe({ host: "192.168.1.155", timeoutMs: 10 })).reachable).toBe(false)
      expect((await smb.probe({ host: "100.101.12.34" })).reachable).toBe(true)
      smb.simulateNetwork("offline")
      expect((await smb.probe({ host: "100.101.12.34" })).reachable).toBe(false)
      expect(changes).toEqual([
        { transport: "cellular", vpn: true },
        { transport: "none", vpn: false },
      ])
    } finally {
      smb.simulateNetwork("home")
    }
  })
  describe("streaming and served pages", () => {
    const source = {
      machineId: CACHY,
      // A dead Tailscale address first: the stream fails over to the local one.
      addresses: [{ host: "100.88.7.5" }, { host: "192.168.1.155" }],
      expectedServerGuid: MOCK_SERVERS.cachy.guid,
    }

    it("streams a video as a content:// URI with its size and type", async () => {
      const result = await smb.streamFile({
        ...source,
        share: "media",
        path: "Movies/Spirited Away (2001).mkv",
      })
      expect(result.uri).toMatch(
        /^content:\/\/dev\.hfps\.hoard\.stream\/[0-9a-f]{64}\/Spirited%20Away%20\(2001\)\.mkv$/
      )
      expect(result.mimeType).toBe("video/x-matroska")
      expect(result.size).toBeGreaterThan(4 * 1024 ** 3)
      expect(smb.streams.size).toBe(1)
    })

    it("offers the http transport and honours mimeType", async () => {
      const result = await smb.streamFile({
        ...source,
        share: "media",
        path: "Movies/Spirited Away (2001).mkv",
        transport: "http",
        mimeType: "video/*",
      })
      expect(result.uri).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/[0-9a-f]{64}\//)
      expect(result.mimeType).toBe("video/*")
    })

    it("rejects folders, missing files, unknown types and another server", async () => {
      const code = (p: Promise<unknown>) => p.then(() => "OK", smbErrorCode)
      expect(await code(smb.streamFile({ ...source, share: "media", path: "Movies" }))).toBe(
        "UNKNOWN"
      )
      expect(
        await code(smb.streamFile({ ...source, share: "media", path: "Movies/nope.mkv" }))
      ).toBe("NOT_FOUND")
      expect(await code(smb.streamFile({ ...source, share: "media", path: ".DS_Store" }))).toBe(
        "NO_APP"
      )
      expect(
        await code(
          smb.streamFile({
            ...source,
            addresses: [{ host: "192.168.0.10" }],
            share: "media",
            path: "Movies/Spirited Away (2001).mkv",
          })
        )
      ).toBe("GUID_MISMATCH")
      expect(
        await code(
          smb.streamFile({
            ...source,
            addresses: [{ host: "100.88.7.5" }],
            share: "media",
            path: "Movies/Spirited Away (2001).mkv",
          })
        )
      ).toBe("TIMEOUT")
    })

    it("serves an HTML page's folder on 127.0.0.1 and stops every stream", async () => {
      const { url } = await smb.openWebPage({
        ...source,
        share: "homes",
        path: "henrique/Documents/Site/index.html",
      })
      expect(url).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/[0-9a-f]{64}\/index\.html$/)
      expect(
        await smb
          .openWebPage({ ...source, share: "homes", path: "henrique/Documents/Site" })
          .then(() => "OK", smbErrorCode)
      ).toBe("UNKNOWN")
      expect(smb.streams.size).toBe(1)
      await smb.stopStreams()
      expect(smb.streams.size).toBe(0)
    })
  })
})
