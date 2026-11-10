import { beforeEach, describe, expect, it, vi } from "vitest"
import type { LocationRef, TransferProgressEvent, TransferState } from "./definitions"
import { isHoardSmbError, smbErrorCode, TERMINAL_TRANSFER_STATES } from "./definitions"
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
    expect(shares.map((s) => s.name)).toEqual(["media", "photos", "homes", "raiz"])
    // An unlisted share is left out of the listing but opens by name (any letter case).
    expect(
      (
        await smb.list({ location: { kind: "smb", sessionId, share: "pasta pública" }, path: "" })
      ).entries.map((e) => e.name)
    ).toEqual(["Leia-me.txt"])
    const { entries } = await smb.list({
      location: { kind: "smb", sessionId, share: "media" },
      path: "Movies",
    })
    expect(entries.find((e) => e.name.startsWith("Blade"))?.size).toBeGreaterThan(8 * 1024 ** 3)
    expect(entries.every((e) => e.path.startsWith("Movies/"))).toBe(true)
    expect(
      (await smb.list({ location: { kind: "smb", sessionId, share: "photos" }, path: "Scans" }))
        .entries
    ).toHaveLength(1284)
    expect(
      (
        await smb.list({
          location: { kind: "smb", sessionId, share: "media" },
          path: "Empty folder",
        })
      ).entries
    ).toEqual([])
    expect(
      smbErrorCode(
        await smb
          .list({ location: { kind: "smb", sessionId, share: "media" }, path: "Nope" })
          .catch((e) => e)
      )
    ).toBe("NOT_FOUND")
    expect(
      smbErrorCode(
        await smb
          .list({ location: { kind: "smb", sessionId: "gone", share: "media" }, path: "" })
          .catch((e) => e)
      )
    ).toBe("NOT_FOUND")
  })

  it("creates, renames and deletes", async () => {
    const { sessionId } = await connectCachy()
    await smb.createFolder({
      location: { kind: "smb", sessionId, share: "media" },
      path: "",
      name: "New",
    })
    expect(
      smbErrorCode(
        await smb
          .createFolder({
            location: { kind: "smb", sessionId, share: "media" },
            path: "",
            name: "New",
          })
          .catch((e) => e)
      )
    ).toBe("ALREADY_EXISTS")
    const { entry } = await smb.rename({
      location: { kind: "smb", sessionId, share: "media" },
      path: "New",
      newName: "Renamed",
    })
    expect(entry.path).toBe("Renamed")
    await smb.delete({ location: { kind: "smb", sessionId, share: "media" }, path: "Renamed" })
    expect(
      smbErrorCode(
        await smb
          .stat({ location: { kind: "smb", sessionId, share: "media" }, path: "Renamed" })
          .catch((e) => e)
      )
    ).toBe("NOT_FOUND")
  })

  it("reports share and folder access, and refuses writes where it is read-only", async () => {
    const { sessionId } = await connectCachy()
    const { shares } = await smb.listShares({ sessionId })
    expect(Object.fromEntries(shares.map((s) => [s.name, s.access]))).toEqual({
      media: "readWrite",
      photos: "readWrite",
      homes: "readWrite",
      raiz: "read",
    })
    const nas = await smb.connect({ machineId: "demo-nas-sala", host: "192.168.1.20" })
    const nasShares = (await smb.listShares({ sessionId: nas.sessionId })).shares
    // A share the account cannot open has no access answer at all.
    expect(nasShares.find((s) => s.name === "admin")?.access).toBeUndefined()
    expect(nasShares.find((s) => s.name === "Public")?.access).toBe("read")

    expect(
      (await smb.list({ location: { kind: "smb", sessionId, share: "media" }, path: "" })).access
    ).toBe("readWrite")
    expect(
      (await smb.list({ location: { kind: "smb", sessionId, share: "raiz" }, path: "etc" })).access
    ).toBe("read")
    // A read-only folder on a writable share, and everything inside it.
    expect(
      (await smb.list({ location: { kind: "smb", sessionId, share: "homes" }, path: "henrique" }))
        .access
    ).toBe("readWrite")
    expect(
      (
        await smb.list({
          location: { kind: "smb", sessionId, share: "homes" },
          path: "henrique/Archive",
        })
      ).access
    ).toBe("read")
    expect(
      (
        await smb.list({
          location: { kind: "smb", sessionId, share: "homes" },
          path: "henrique/Archive/Old projects",
        })
      ).access
    ).toBe("read")
    const review = "henrique/Archive/2025 review.md"
    expect(
      (
        await smb.readTextFile({
          location: { kind: "smb", sessionId, share: "homes" },
          path: review,
        })
      ).access
    ).toBe("read")
    expect(
      (
        await smb.readTextFile({
          location: { kind: "smb", sessionId, share: "homes" },
          path: "henrique/Documents/notes.md",
        })
      ).access
    ).toBe("readWrite")

    const code = (promise: Promise<unknown>) => promise.then(() => "OK", smbErrorCode)
    expect(
      await code(
        smb.createFolder({
          location: { kind: "smb", sessionId, share: "raiz" },
          path: "",
          name: "x",
        })
      )
    ).toBe("ACCESS_DENIED")
    expect(
      await code(
        smb.createFolder({
          location: { kind: "smb", sessionId, share: "homes" },
          path: "henrique/Archive",
          name: "x",
        })
      )
    ).toBe("ACCESS_DENIED")
    expect(
      await code(
        smb.rename({
          location: { kind: "smb", sessionId, share: "homes" },
          path: review,
          newName: "renamed.md",
        })
      )
    ).toBe("ACCESS_DENIED")
    // The read-only folder itself cannot be renamed or deleted from its writable parent either.
    expect(
      await code(
        smb.delete({
          location: { kind: "smb", sessionId, share: "homes" },
          path: "henrique/Archive",
          recursive: true,
        })
      )
    ).toBe("ACCESS_DENIED")
    expect(
      await code(
        smb.delete({ location: { kind: "smb", sessionId, share: "raiz" }, path: "README.txt" })
      )
    ).toBe("ACCESS_DENIED")
    expect(
      await code(
        smb.writeTextFile({
          location: { kind: "smb", sessionId, share: "homes" },
          path: review,
          text: "x",
        })
      )
    ).toBe("ACCESS_DENIED")
    expect(
      await code(
        smb.writeTextFile({
          location: { kind: "smb", sessionId, share: "homes" },
          path: "henrique/Archive/new.txt",
          text: "",
          mode: "create",
        })
      )
    ).toBe("ACCESS_DENIED")
    // Writable places still work next to them.
    expect(
      await code(
        smb.createFolder({
          location: { kind: "smb", sessionId, share: "homes" },
          path: "henrique",
          name: "Fresh",
        })
      )
    ).toBe("OK")
  })

  it("returns an <img>-ready thumbnail for images and videos only", async () => {
    const { sessionId } = await connectCachy()
    const thumb = await smb.thumbnail({
      location: { kind: "smb", sessionId, share: "photos" },
      path: "screenshot.webp",
      maxSize: 96,
    })
    expect(thumb.src.startsWith("data:image/svg+xml")).toBe(true)
    expect(Math.max(thumb.width, thumb.height)).toBe(96)
    const movies = await smb.list({
      location: { kind: "smb", sessionId, share: "media" },
      path: "Movies",
    })
    const movie = movies.entries.find((e) => !e.isDirectory && /\.(mkv|mp4)$/.test(e.name))!
    const frame = await smb.thumbnail({
      location: { kind: "smb", sessionId, share: "media" },
      path: movie.path,
      maxSize: 96,
    })
    expect(frame.src.startsWith("data:image/svg+xml")).toBe(true)
    expect(
      smbErrorCode(
        await smb
          .thumbnail({
            location: { kind: "smb", sessionId, share: "homes" },
            path: "henrique/Documents/notes.md",
            maxSize: 96,
          })
          .catch((e) => e)
      )
    ).toBe("UNKNOWN")
  })

  describe("transfers (ADR 0007)", () => {
    const nas = {
      kind: "smb" as const,
      machineId: CACHY,
      addresses: [{ host: "192.168.1.155" }, { host: "100.101.12.34" }],
      expectedServerGuid: MOCK_SERVERS.cachy.guid,
      share: "media",
    }
    const device = { kind: "device" as const }
    const usb = { kind: "volume" as const, uuid: "1A2B-3C4D" }

    const events: TransferProgressEvent[] = []
    const settle = async (id: string, states: TransferState[] = [...TERMINAL_TRANSFER_STATES]) => {
      for (let i = 0; i < 20_000; i++) {
        const last = events.filter((e) => e.transferId === id).at(-1)
        if (last && states.includes(last.state)) return last
        await new Promise((r) => setTimeout(r, 0))
      }
      throw new Error(
        `${id} did not settle: ${events.filter((e) => e.transferId === id).at(-1)?.state}`
      )
    }
    const names = async (location: LocationRef, path: string) =>
      (await smb.list({ location, path })).entries.map((e) => e.name)

    beforeEach(async () => {
      events.length = 0
      smb.simulateStorageAccess(true)
      await smb.addListener("transferProgress", (e) => events.push(e))
    })

    it("copies an SMB folder to the phone with a scan, progress and the files in place", async () => {
      await smb.startTransfer({
        transferId: "t1",
        op: "copy",
        sources: [{ location: nas, path: "Music" }],
        destination: { location: device, path: "Download" },
        conflict: "rename",
      })
      const last = await settle("t1")
      expect(last.state).toBe("completed")
      const states = [...new Set(events.map((e) => e.state))]
      expect(states).toEqual(expect.arrayContaining(["queued", "scanning", "running", "completed"]))
      expect(last.itemsTotal).toBeGreaterThan(0)
      expect(last.itemsDone).toBe(last.itemsTotal)
      expect(last.bytesDone).toBe(last.totalBytes)
      expect(await names(device, "Download")).toContain("Music")
      const { sessionId } = await connectCachy()
      expect(await names({ kind: "smb", sessionId, share: "media" }, "")).toContain("Music")
      expect((await smb.getTransfers()).transfers[0]).toMatchObject({
        transferId: "t1",
        op: "copy",
      })
      await smb.acknowledgeTransfers({ transferIds: ["t1"] })
      expect((await smb.getTransfers()).transfers).toEqual([])
      // Items stay for the transfers page (and Retry) after the acknowledgement.
      expect((await smb.getTransferItems({ transferId: "t1" })).total).toBe(last.itemsTotal)
    })

    it("writes XMP sidecars at the destination only, and only for copies", async () => {
      const files = (await smb.list({ location: usb, path: "DCIM/102MSDCF" })).entries
      const arw = files.filter((e) => e.name.endsWith(".ARW")).slice(0, 2)
      const sidecars = arw.map((e) => ({
        sourcePath: e.path,
        content: `<x:xmpmeta>${e.name}</x:xmpmeta>`,
      }))
      await smb.startTransfer({
        transferId: "x1",
        op: "copy",
        sources: arw.map((e) => ({ location: usb, path: e.path })),
        destination: { location: device, path: "Download" },
        conflict: "rename",
        sidecars,
      })
      expect((await settle("x1")).state).toBe("completed")
      const stem = arw[0].name.replace(/\.ARW$/, "")
      expect(await names(device, "Download")).toEqual(
        expect.arrayContaining([arw[0].name, `${stem}.xmp`])
      )
      const text = await smb.readTextFile({ location: device, path: `Download/${stem}.xmp` })
      expect(text.text).toBe(sidecars[0].content)
      expect((await names(usb, "DCIM/102MSDCF")).some((n) => n.endsWith(".xmp"))).toBe(false)
      expect(
        await smb
          .startTransfer({
            transferId: "x2",
            op: "move",
            sources: [{ location: usb, path: arw[0].path }],
            destination: { location: device, path: "Download" },
            conflict: "rename",
            sidecars,
          })
          .then(
            () => "OK",
            (e: unknown) => smbErrorCode(e)
          )
      ).toBe("UNKNOWN")
    })

    it("fails over when the connection drops mid-file and completes", async () => {
      await smb.startTransfer({
        transferId: "t2",
        op: "copy",
        sources: [{ location: nas, path: "Movies/flaky-network-test.mp4" }],
        destination: { location: usb, path: "" },
        conflict: "rename",
      })
      const last = await settle("t2")
      expect(last.state).toBe("completed")
      expect(events.some((e) => e.state === "retrying")).toBe(true)
      expect(last.activeHost).toBe("100.101.12.34")
    })

    it("asks about existing names once, then continues with the answer", async () => {
      const options = {
        op: "copy" as const,
        sources: [{ location: nas, path: "Movies/flaky-network-test.mp4" }],
        destination: { location: usb, path: "" },
      }
      await smb.startTransfer({ ...options, transferId: "a1", conflict: "rename" })
      await settle("a1")
      await smb.startTransfer({ ...options, transferId: "a2", conflict: "ask" })
      const waiting = await settle("a2", ["conflict"])
      expect(waiting.conflicts).toBe(1)
      const { items } = await smb.getTransferItems({ transferId: "a2", conflictsOnly: true })
      expect(items.map((i) => i.destPath)).toEqual(["flaky-network-test.mp4"])
      await smb.resolveTransferConflicts({ transferId: "a2", all: "rename" })
      expect((await settle("a2")).state).toBe("completed")
      expect(await names(usb, "")).toEqual(
        expect.arrayContaining(["flaky-network-test.mp4", "flaky-network-test (1).mp4"])
      )
    })

    it("moves inside the phone with one rename, and between locations deletes the sources", async () => {
      await smb.createFolder({ location: device, path: "", name: "Moved" })
      await smb.startTransfer({
        transferId: "m1",
        op: "move",
        sources: [{ location: device, path: "Documents" }],
        destination: { location: device, path: "Moved" },
        conflict: "rename",
      })
      const local = await settle("m1")
      expect(local.state).toBe("completed")
      expect(local.itemsTotal).toBe(1)
      expect(await names(device, "")).not.toContain("Documents")
      expect(await names(device, "Moved")).toEqual(["Documents"])

      await smb.startTransfer({
        transferId: "m2",
        op: "move",
        sources: [{ location: device, path: "Moved/Documents" }],
        destination: { location: usb, path: "" },
        conflict: "rename",
      })
      expect((await settle("m2")).state).toBe("completed")
      expect(await names(device, "Moved")).toEqual([])
      expect(await names(usb, "")).toContain("Documents")
    })

    it("a 'broken' file fails mid-way; Retry resumes it", async () => {
      const options = {
        transferId: "b1",
        op: "copy" as const,
        sources: [
          {
            location: {
              kind: "smb" as const,
              machineId: "worst-lab",
              addresses: [{ host: "192.168.1.77" }],
              expectedServerGuid: MOCK_SERVERS.lab.guid,
              share: "Arquivo morto — 2019–2026 (não apagar)",
            },
            path: "broken-transfer-demo.iso",
          },
        ],
        destination: { location: device, path: "Download" },
        conflict: "rename" as const,
      }
      await smb.startTransfer(options)
      const failed = await settle("b1")
      expect(failed.state).toBe("failed")
      expect(failed.error?.code).toBe("UNREACHABLE")
      expect(failed.itemsFailed).toBe(1)
      const { items } = await smb.getTransferItems({ transferId: "b1", states: ["failed"] })
      expect(items[0].bytesDone).toBeGreaterThan(0)
      events.length = 0
      await smb.startTransfer({ ...options, resume: true })
      expect((await settle("b1")).state).toBe("completed")
    })

    it("saves files another app shared, and refuses a folder into itself", async () => {
      const shared = smb.simulateIncomingShare([
        { name: "IMG_0001.jpg", size: 3_000_000, mimeType: "image/jpeg" },
        { name: "notes.pdf", size: 80_000 },
      ])
      expect((await smb.getSharedFiles()).shared?.shareId).toBe(shared.shareId)
      await smb.startTransfer({
        transferId: "s1",
        op: "copy",
        sources: shared.files.map((f) => ({
          location: { kind: "incoming" as const, shareId: shared.shareId },
          path: String(f.index),
        })),
        destination: { location: usb, path: "" },
        conflict: "rename",
      })
      expect((await settle("s1")).state).toBe("completed")
      expect(await names(usb, "")).toEqual(expect.arrayContaining(["IMG_0001.jpg", "notes.pdf"]))
      await smb.clearSharedFiles({ shareId: shared.shareId })
      expect((await smb.getSharedFiles()).shared).toBeNull()
      // Items native refused (file://, Hoard's own providers) are only counted.
      const refused = smb.simulateIncomingShare([], 2)
      expect((await smb.getSharedFiles()).shared).toMatchObject({
        shareId: refused.shareId,
        files: [],
        rejected: 2,
      })

      const error = await smb
        .startTransfer({
          transferId: "s2",
          op: "copy",
          sources: [{ location: device, path: "DCIM" }],
          destination: { location: device, path: "DCIM/Camera" },
          conflict: "rename",
        })
        .catch((e: unknown) => e)
      expect(smbErrorCode(error)).toBe("UNKNOWN")
    })

    it("cancels, and rejects a SAF folder that was never granted", async () => {
      const error = await smb
        .startTransfer({
          transferId: "c0",
          op: "copy",
          sources: [{ location: nas, path: "Music" }],
          destination: { location: { kind: "tree", uri: "content://nope" }, path: "" },
          conflict: "rename",
        })
        .catch((e: unknown) => e)
      expect(smbErrorCode(error)).toBe("PERMISSION_REVOKED")
      await smb.startTransfer({
        transferId: "c1",
        op: "copy",
        sources: [{ location: nas, path: "Movies" }],
        destination: { location: device, path: "Download" },
        conflict: "rename",
      })
      await smb.cancelTransfer({ transferId: "c1" })
      expect((await smb.getTransfers()).transfers[0].state).toBe("cancelled")
    })
  })

  it("lists the 5,000-entry worst-case folder", async () => {
    const { sessionId } = await smb.connect({ machineId: "worst-lab", host: "192.168.1.77" })
    const { entries } = await smb.list({
      location: { kind: "smb", sessionId, share: "Arquivo morto — 2019–2026 (não apagar)" },
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
    const base = {
      kind: "smb" as const,
      machineId: CACHY,
      // A dead Tailscale address first: the stream fails over to the local one.
      addresses: [{ host: "100.88.7.5" }, { host: "192.168.1.155" }],
      expectedServerGuid: MOCK_SERVERS.cachy.guid,
    }
    const at = (share: string, extra: Partial<typeof base> = {}) => ({
      location: { ...base, share, ...extra },
    })

    it("streams a video as a content:// URI with its size and type", async () => {
      const result = await smb.streamFile({
        ...at("media"),
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
        ...at("media"),
        path: "Movies/Spirited Away (2001).mkv",
        transport: "http",
        mimeType: "video/*",
      })
      expect(result.uri).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/[0-9a-f]{64}\//)
      expect(result.mimeType).toBe("video/*")
    })

    it("rejects folders, missing files, unknown types and another server", async () => {
      const code = (p: Promise<unknown>) => p.then(() => "OK", smbErrorCode)
      expect(await code(smb.streamFile({ ...at("media"), path: "Movies" }))).toBe("UNKNOWN")
      expect(await code(smb.streamFile({ ...at("media"), path: "Movies/nope.mkv" }))).toBe(
        "NOT_FOUND"
      )
      expect(await code(smb.streamFile({ ...at("media"), path: ".DS_Store" }))).toBe("NO_APP")
      expect(
        await code(
          smb.streamFile({
            ...at("media", { addresses: [{ host: "192.168.0.10" }] }),
            path: "Movies/Spirited Away (2001).mkv",
          })
        )
      ).toBe("GUID_MISMATCH")
      expect(
        await code(
          smb.streamFile({
            ...at("media", { addresses: [{ host: "100.88.7.5" }] }),
            path: "Movies/Spirited Away (2001).mkv",
          })
        )
      ).toBe("TIMEOUT")
    })

    it("serves an HTML page's folder on 127.0.0.1 and stops every stream", async () => {
      const { url } = await smb.openWebPage({
        ...at("homes"),
        path: "henrique/Documents/Site/index.html",
      })
      expect(url).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/[0-9a-f]{64}\/index\.html$/)
      expect(
        await smb
          .openWebPage({ ...at("homes"), path: "henrique/Documents/Site" })
          .then(() => "OK", smbErrorCode)
      ).toBe("UNKNOWN")
      expect(smb.streams.size).toBe(1)
      await smb.stopStreams()
      expect(smb.streams.size).toBe(0)
    })
  })
})

describe("HoardSmbWeb text files", () => {
  const read = async (path: string, extra: { maxBytes?: number; truncate?: boolean } = {}) => {
    const { sessionId } = await connectCachy()
    return smb.readTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path,
      ...extra,
    })
  }
  const project = (name: string) => `henrique/Projects/hoard/${name}`

  it("reads text with its encoding, size and modified time", async () => {
    const notes = await read("henrique/Documents/notes.md")
    expect(notes.text.startsWith("# Notes")).toBe(true)
    expect(notes).toMatchObject({ encoding: "utf-8", truncated: false })
    expect(notes.size).toBe(new TextEncoder().encode(notes.text).length)
    expect(notes.modifiedAt).toMatch(/^2026-/)
    expect((await read(project("latin1-relatorio.txt"))).encoding).toBe("windows-1252")
    expect((await read(project("latin1-relatorio.txt"))).text).toContain(
      "Relatório de manutenção — €"
    )
    expect((await read(project("utf16-notes.txt"))).encoding).toBe("utf-16le")
    expect((await read(project("windows-crlf.txt"))).text).toContain("\r\n")
    expect((await read(project("empty.txt"))).text).toBe("")
  })

  it("rejects binary files and files above the limit, or reads their first part", async () => {
    expect(smbErrorCode(await read(project("firmware.txt")).catch((e) => e))).toBe("NOT_TEXT")
    const tooLarge = await read(project("server-12mb.log")).catch((e) => e)
    expect(smbErrorCode(tooLarge)).toBe("TOO_LARGE")
    expect(tooLarge.data.size).toBeGreaterThan(12 * 1024 * 1024 - 100)
    const head = await read(project("server-12mb.log"), { maxBytes: 1000, truncate: true })
    expect(head.truncated).toBe(true)
    expect(head.text.length).toBeLessThanOrEqual(1000)
    expect(head.size).toBe(tooLarge.data.size)
    const big = await read(project("100k-lines.log"))
    expect(big.text.split("\n").length).toBe(100_001)
  })

  it("writes with conflict detection, create-only mode and the original encoding", async () => {
    const { sessionId } = await connectCachy()
    const path = project("windows-crlf.txt")
    const before = await smb.readTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path,
    })
    const saved = await smb.writeTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path,
      text: "new\r\ntext\r\n",
      expectedModifiedAt: before.modifiedAt,
    })
    expect(saved.entry.modifiedAt).not.toBe(before.modifiedAt)
    expect(
      (await smb.readTextFile({ location: { kind: "smb", sessionId, share: "homes" }, path })).text
    ).toBe("new\r\ntext\r\n")
    // A second save with the old modified time is a conflict and changes nothing.
    const conflict = await smb
      .writeTextFile({
        location: { kind: "smb", sessionId, share: "homes" },
        path,
        text: "x",
        expectedModifiedAt: before.modifiedAt,
      })
      .catch((e) => e)
    expect(smbErrorCode(conflict)).toBe("CONFLICT")
    expect(conflict.data.modifiedAt).toBe(saved.entry.modifiedAt)
    // Overwrite without a check always works.
    await smb.writeTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path,
      text: "forced",
    })
    expect(
      (await smb.readTextFile({ location: { kind: "smb", sessionId, share: "homes" }, path })).text
    ).toBe("forced")

    const latin = project("latin1-relatorio.txt")
    const original = await smb.readTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path: latin,
    })
    await smb.writeTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path: latin,
      text: original.text + "ç",
      encoding: original.encoding,
      expectedModifiedAt: original.modifiedAt,
    })
    expect(
      await smb.readTextFile({ location: { kind: "smb", sessionId, share: "homes" }, path: latin })
    ).toMatchObject({
      encoding: "windows-1252",
      text: original.text + "ç",
    })
    const unencodable = await smb
      .writeTextFile({
        location: { kind: "smb", sessionId, share: "homes" },
        path: latin,
        text: "日本",
        encoding: "windows-1252",
      })
      .catch((e) => e)
    expect(smbErrorCode(unencodable)).toBe("UNKNOWN")

    const created = await smb.writeTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path: project("new.md"),
      text: "",
      mode: "create",
    })
    expect(created.entry).toMatchObject({ name: "new.md", size: 0, isDirectory: false })
    const taken = await smb
      .writeTextFile({
        location: { kind: "smb", sessionId, share: "homes" },
        path: project("new.md"),
        text: "",
        mode: "create",
      })
      .catch((e) => e)
    expect(smbErrorCode(taken)).toBe("ALREADY_EXISTS")
    const gone = await smb
      .writeTextFile({
        location: { kind: "smb", sessionId, share: "homes" },
        path: project("missing.md"),
        text: "",
        expectedModifiedAt: "2026-01-01T00:00:00.000Z",
      })
      .catch((e) => e)
    expect(smbErrorCode(gone)).toBe("CONFLICT")
    expect(gone.data.modifiedAt).toBeNull()
  })

  it("simulates someone else editing the conflict demo file, and read-only shares", async () => {
    const { sessionId } = await connectCachy()
    const path = "henrique/Documents/shared-with-conflict.md"
    const opened = await smb.readTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path,
    })
    await new Promise((resolve) => setTimeout(resolve, 0)) // the colleague saves
    const error = await smb
      .writeTextFile({
        location: { kind: "smb", sessionId, share: "homes" },
        path,
        text: "mine",
        expectedModifiedAt: opened.modifiedAt,
      })
      .catch((e) => e)
    expect(smbErrorCode(error)).toBe("CONFLICT")
    const reloaded = await smb.readTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path,
    })
    expect(reloaded.text).toContain("someone else")
    await smb.writeTextFile({
      location: { kind: "smb", sessionId, share: "homes" },
      path,
      text: "mine",
      expectedModifiedAt: reloaded.modifiedAt,
    })

    const nas = await smb.connect({ machineId: "demo-nas-sala", host: "192.168.1.20" })
    const readme = await smb.readTextFile({
      location: { kind: "smb", sessionId: nas.sessionId, share: "Public" },
      path: "README.txt",
    })
    expect(readme.text).toContain("Read-only")
    const denied = await smb
      .writeTextFile({
        location: { kind: "smb", sessionId: nas.sessionId, share: "Public" },
        path: "README.txt",
        text: "x",
      })
      .catch((e) => e)
    expect(smbErrorCode(denied)).toBe("ACCESS_DENIED")
  })
})

describe("HoardSmbWeb local locations (ADR 0005)", () => {
  const device = { kind: "device" } as const
  const usb = { kind: "volume", uuid: "1A2B-3C4D" } as const
  const code = (p: Promise<unknown>) => p.then(() => "OK", smbErrorCode)

  it("refuses device and volume locations until all files access is granted", async () => {
    expect(await smb.getStorageAccess()).toEqual({ granted: false, mechanism: "allFiles" })
    expect(await code(smb.list({ location: device, path: "" }))).toBe("STORAGE_PERMISSION")
    expect(await code(smb.list({ location: usb, path: "" }))).toBe("STORAGE_PERMISSION")
    expect((await smb.listVolumes()).volumes.map((v) => v.access)).toEqual(["none", "none"])
    expect((await smb.requestStorageAccess()).granted).toBe(true)
    expect((await smb.list({ location: device, path: "DCIM" })).entries.length).toBeGreaterThan(0)
    // Remembered like the Android setting.
    expect(await new HoardSmbWeb().getStorageAccess()).toMatchObject({ granted: true })
  })

  it("lists the primary storage first, then attached volumes with sizes and kinds", async () => {
    await smb.requestStorageAccess()
    const { volumes } = await smb.listVolumes()
    expect(volumes.map((v) => [v.uuid, v.kind, v.primary, v.access])).toEqual([
      [null, "internal", true, "path"],
      ["1A2B-3C4D", "usb", false, "path"],
    ])
    expect(volumes[1].freeBytes).toBeLessThan(volumes[1].totalBytes!)
  })

  it("shows Android/data and Android/obb as restricted and refuses to open them", async () => {
    await smb.requestStorageAccess()
    const { entries } = await smb.list({ location: device, path: "Android" })
    expect(entries.filter((e) => e.restricted).map((e) => e.name)).toEqual(["data", "obb"])
    expect(entries.find((e) => e.name === "media")?.restricted).toBeUndefined()
    const error = await smb.list({ location: device, path: "Android/data" }).catch((e) => e)
    expect(smbErrorCode(error)).toBe("ACCESS_DENIED")
    expect(isHoardSmbError(error) && error.data?.restricted).toBe(true)
  })

  it("browses, edits and thumbnails local files like SMB ones", async () => {
    await smb.requestStorageAccess()
    await smb.createFolder({ location: usb, path: "", name: "Keepers" })
    expect(await code(smb.createFolder({ location: usb, path: "", name: "Keepers" }))).toBe(
      "ALREADY_EXISTS"
    )
    await smb.rename({ location: usb, path: "Keepers", newName: "Selects" })
    const { entry } = await smb.writeTextFile({
      location: device,
      path: "Documents/new.md",
      text: "# hi",
      mode: "create",
    })
    expect((await smb.readTextFile({ location: device, path: entry.path })).text).toBe("# hi")
    const thumb = await smb.thumbnail({
      location: usb,
      path: "DCIM/100MSDCF/DSC00001.JPG",
      maxSize: 96,
    })
    expect(thumb.src).toMatch(/^data:image\/svg\+xml/)
    await smb.delete({ location: usb, path: "Selects" })
    expect(await code(smb.stat({ location: usb, path: "Selects" }))).toBe("NOT_FOUND")
  })

  it("handles the worst-case folder: 20,000 entries", async () => {
    await smb.requestStorageAccess()
    const { entries } = await smb.list({ location: usb, path: "DCIM/101MSDCF" })
    expect(entries.length).toBeGreaterThanOrEqual(20_000)
  })

  it("opens and streams local files in place, without stream tokens", async () => {
    await smb.requestStorageAccess()
    const result = await smb.streamFile({
      location: device,
      path: "Music/podcast.mp3",
    })
    expect(result.uri).toMatch(/^content:\/\/dev\.hfps\.hoard\.fileprovider\//)
    expect(result.mimeType).toBe("audio/mpeg")
    expect(smb.streams.size).toBe(0)
    await smb.openFile({ location: device, path: "Documents/receipt-2026-11.pdf" })
    await smb.openWebPage({ location: device, path: "Download/site/index.html" })
    expect(smb.streams.size).toBe(1)
  })

  it("emits volumesChanged on attach and detach; a detached volume is UNAVAILABLE", async () => {
    await smb.requestStorageAccess()
    const events: { uuid: string | null; state: string }[] = []
    await smb.addListener("volumesChanged", (event) => {
      if (event.change) events.push(event.change)
    })
    smb.simulateVolume("1A2B-3C4D", false)
    expect(await code(smb.list({ location: usb, path: "" }))).toBe("UNAVAILABLE")
    smb.simulateVolume("6C3E-11F0", true)
    await new Promise((resolve) => setTimeout(resolve, 5))
    expect(events).toEqual([
      { uuid: "1A2B-3C4D", state: "removed" },
      { uuid: "6C3E-11F0", state: "checking" },
      { uuid: "6C3E-11F0", state: "mountedReadOnly" },
    ])
    const sd = { kind: "volume", uuid: "6C3E-11F0" } as const
    expect((await smb.list({ location: sd, path: "" })).access).toBe("read")
    expect(await code(smb.createFolder({ location: sd, path: "", name: "x" }))).toBe(
      "ACCESS_DENIED"
    )
  })

  it("falls back to a SAF grant per volume without all files access", async () => {
    expect(await code(smb.grantVolumeAccess({ uuid: "nope" }))).toBe("NOT_FOUND")
    const info = await smb.grantVolumeAccess({ uuid: "1A2B-3C4D" })
    expect(info.access).toBe("saf")
    expect((await smb.list({ location: usb, path: "" })).entries.length).toBeGreaterThan(0)
    expect(await code(smb.list({ location: device, path: "" }))).toBe("STORAGE_PERMISSION")
  })
})

describe("HoardSmbWeb culling (ADR 0008)", () => {
  const usb = { kind: "volume", uuid: "1A2B-3C4D" } as const
  const code = (p: Promise<unknown>) =>
    p.then(
      () => "OK",
      (e: unknown) => smbErrorCode(e)
    )

  it("opens a Sony folder as shots: pairs, RAW-only and JPEG-only", async () => {
    smb.simulateStorageAccess(true)
    const { cullId, shots, libRaw } = await smb.cullOpen({ location: usb, path: "DCIM/102MSDCF" })
    expect(cullId).toBeTruthy()
    expect(libRaw).toBe(true)
    expect(shots).toHaveLength(36)
    const kinds = new Set(shots.map((s) => s.kind))
    expect(kinds).toEqual(new Set(["pair", "raw", "jpeg"]))
    const pair = shots.find((s) => s.kind === "pair")!
    expect(pair.id).toBe(pair.stem.toLowerCase())
    expect(pair.raw?.name).toMatch(/\.ARW$/)
    expect(pair.rendered?.name).toMatch(/\.JPG$/)
    expect(pair.raw?.path).toBe(`DCIM/102MSDCF/${pair.raw?.name}`)
  })

  it("reports EXIF essentials with sub-second capture times and bursts", async () => {
    smb.simulateStorageAccess(true)
    const { cullId, shots } = await smb.cullOpen({ location: usb, path: "DCIM/102MSDCF" })
    const { shots: meta } = await smb.cullMetadata({
      cullId,
      location: usb,
      shotIds: [...shots.map((s) => s.id), "nope"],
    })
    expect(meta).toHaveLength(37)
    const first = meta[0].metadata!
    expect(first.captureTime).toMatch(/^2026-08-09T10:\d\d:\d\d\.\d{3}$/)
    expect(first.model).toBe("ZV-E10")
    expect(first.exposure).toMatch(/^1\/\d+$/)
    expect(first.captureMillis).toBeGreaterThan(0)
    expect(meta.at(-1)!.error?.code).toBe("NOT_FOUND")
    const times = meta.slice(0, -1).map((m) => m.metadata!.captureMillis!)
    const gaps = times.slice(1).map((t, i) => t - times[i])
    expect(gaps.some((g) => g < 1000)).toBe(true)
    expect(meta.some((m) => m.metadata?.orientation === 8)).toBe(true)
  })

  it("serves the embedded preview first, then the final image, and 100% tiles", async () => {
    smb.simulateStorageAccess(true)
    const { cullId, shots } = await smb.cullOpen({ location: usb, path: "DCIM/102MSDCF" })
    const pair = shots.find((s) => s.kind === "pair")!
    const rawOnly = shots.find((s) => s.kind === "raw")!
    const jpegOnly = shots.find((s) => s.kind === "jpeg")!
    const ref = { cullId, location: usb }
    const fast = await smb.cullImage({ ...ref, shotId: pair.id, longSide: 2400, quality: "fast" })
    expect([fast.source, fast.final, Math.max(fast.width, fast.height)]).toEqual([
      "rawPreview",
      false,
      1616,
    ])
    expect(fast.src).toMatch(/^data:image\/svg\+xml/)
    const final = await smb.cullImage({ ...ref, shotId: pair.id, longSide: 2400, quality: "final" })
    expect([final.source, final.final, Math.max(final.width, final.height)]).toEqual([
      "rendered",
      true,
      2400,
    ])
    expect(
      (await smb.cullImage({ ...ref, shotId: rawOnly.id, longSide: 2400, quality: "final" })).source
    ).toBe("rawHalf")
    expect(
      (await smb.cullImage({ ...ref, shotId: jpegOnly.id, longSide: 2400, quality: "fast" })).final
    ).toBe(true)

    expect(await smb.cullZoom({ ...ref, shotId: rawOnly.id })).toMatchObject({ source: "rawFull" })
    const zoom = await smb.cullZoom({ ...ref, shotId: pair.id })
    expect(zoom.source).toBe("rendered")
    const tile = await smb.cullTile({
      ...ref,
      shotId: pair.id,
      x: 512,
      y: 0,
      width: 512,
      height: 512,
      sample: 1,
    })
    expect([tile.width, tile.height]).toEqual([512, 512])
    const edge = await smb.cullTile({
      ...ref,
      shotId: pair.id,
      x: zoom.width - 100,
      y: 0,
      width: 512,
      height: 512,
      sample: 2,
    })
    expect([edge.width, edge.height]).toEqual([50, 256])
    expect(
      await code(
        smb.cullTile({ ...ref, shotId: pair.id, x: 0, y: 0, width: 512, height: 512, sample: 3 })
      )
    ).toBe("UNKNOWN")
    expect(
      await code(
        smb.cullTile({
          ...ref,
          shotId: pair.id,
          x: 99_999,
          y: 0,
          width: 512,
          height: 512,
          sample: 1,
        })
      )
    ).toBe("UNKNOWN")
  })

  it("scores sharpness, prefetches, thumbnails and closes", async () => {
    smb.simulateStorageAccess(true)
    const { cullId, shots } = await smb.cullOpen({ location: usb, path: "DCIM/102MSDCF" })
    const ref = { cullId, location: usb }
    const { scores } = await smb.cullSharpness({
      ...ref,
      shotIds: shots.slice(0, 6).map((s) => s.id),
    })
    expect(scores).toHaveLength(6)
    expect(scores.every((s) => (s.best ?? 0) > 0 && (s.global ?? 0) > 0)).toBe(true)
    const thumb = await smb.cullThumbnail({ ...ref, shotId: shots[0].id, maxSize: 320 })
    expect(Math.max(thumb.width, thumb.height)).toBe(320)
    await smb.cullPrefetch({
      ...ref,
      shotIds: shots.slice(0, 5).map((s) => s.id),
      current: 1,
      longSide: 2400,
    })
    expect(
      await code(smb.cullPrefetch({ ...ref, shotIds: ["missing"], current: 0, longSide: 2400 }))
    ).toBe("NOT_FOUND")
    await smb.cullClose({ cullId })
    await smb.cullClose({ cullId })
    expect(
      await code(smb.cullImage({ ...ref, shotId: shots[0].id, longSide: 100, quality: "fast" }))
    ).toBe("NOT_FOUND")
  })

  it("keeps edge swipes in bands for the viewer, validates them, clears with none", async () => {
    await smb.setGestureExclusion({ bands: [{ top: 200, height: 300 }] })
    expect(smb.gestureExclusion).toEqual([{ top: 200, height: 300 }])
    expect(await code(smb.setGestureExclusion({ bands: [{ top: -1, height: 10 }] }))).toBe(
      "UNKNOWN"
    )
    expect(await code(smb.setGestureExclusion({ bands: [{ top: 0, height: NaN }] }))).toBe(
      "UNKNOWN"
    )
    await smb.setGestureExclusion({ bands: [] })
    expect(smb.gestureExclusion).toEqual([])
  })

  it("analyses shots: sharpness, faces, exposure and burst signatures (ADR 0009)", async () => {
    smb.simulateStorageAccess(true)
    const { cullId, shots } = await smb.cullOpen({ location: usb, path: "DCIM/102MSDCF" })
    const ref = { cullId, location: usb }
    const ids = shots.map((s) => s.id)
    const { results, faces } = await smb.cullAnalyze({
      ...ref,
      shotIds: [...ids.slice(0, 19), "nope"],
    })
    expect(faces).toBe(true)
    expect(results).toHaveLength(20)
    expect(results.at(-1)?.error?.code).toBe("NOT_FOUND")
    for (const { analysis } of results.slice(0, 19)) {
      expect(analysis?.version).toBe(2)
      expect(analysis?.sharpness.tiles).toHaveLength(16)
      expect(analysis?.exposure.histogram).toHaveLength(64)
      expect(analysis!.exposure.histogram.reduce((a, b) => a + b, 0)).toBeCloseTo(1, 3)
      expect(analysis?.hash).toMatch(/^[0-9a-f]{16}$/)
      expect(analysis?.colors).toMatch(/^[0-9a-f]{96}$/)
      expect(Math.max(analysis!.width, analysis!.height)).toBe(1536)
      expect(analysis!.exposure.blownArea).toBeLessThanOrEqual(analysis!.exposure.highlights)
      expect(analysis!.exposure.faceHighlights).toBeGreaterThanOrEqual(0)
    }
    // Deterministic, and a JPEG-only shot is measured on its JPEG.
    const again = await smb.cullAnalyze({ ...ref, shotIds: [ids[0]] })
    expect(again.results[0].analysis).toEqual(results[0].analysis)
    const jpeg = shots.find((s) => s.kind === "jpeg")!
    expect(
      (await smb.cullAnalyze({ ...ref, shotIds: [jpeg.id] })).results[0].analysis?.source
    ).toBe("rendered")
    expect(await code(smb.cullAnalyze({ ...ref, shotIds: ids.slice(0, 21) }))).toBe("UNKNOWN")
    await smb.cullClose({ cullId })
    expect(await code(smb.cullAnalyze({ ...ref, shotIds: [ids[0]] }))).toBe("NOT_FOUND")
  })

  it("culls an SMB folder through the session, and refuses a dropped one", async () => {
    const { sessionId } = await connectCachy()
    const location = { kind: "smb", sessionId, share: "photos" } as const
    const { cullId, shots } = await smb.cullOpen({ location, path: "2026/2026-08-07 Park" })
    expect(shots).toHaveLength(30)
    expect(shots.every((s) => s.kind === "pair")).toBe(true)
    const image = await smb.cullImage({
      cullId,
      location,
      shotId: shots[0].id,
      longSide: 3120,
      quality: "final",
    })
    expect(image.source).toBe("rendered")
    await smb.disconnect({ sessionId })
    expect(
      await code(
        smb.cullImage({ cullId, location, shotId: shots[0].id, longSide: 3120, quality: "final" })
      )
    ).toBe("NOT_FOUND")
    // A reconnect continues the same culling handle with the new session.
    const again = await connectCachy()
    const moved = { ...location, sessionId: again.sessionId }
    expect(
      (await smb.cullMetadata({ cullId, location: moved, shotIds: [shots[0].id] })).shots[0]
        .metadata
    ).toBeTruthy()
    expect(await code(smb.cullMetadata({ cullId, location: usb, shotIds: [] }))).toBe("UNKNOWN")
  })

  it("rejects a folder that cannot be listed like list does", async () => {
    smb.simulateStorageAccess(true)
    expect(await code(smb.cullOpen({ location: usb, path: "DCIM/nope" }))).toBe("NOT_FOUND")
  })
})
