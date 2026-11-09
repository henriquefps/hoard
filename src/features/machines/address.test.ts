import { beforeEach, describe, expect, it, vi } from "vitest"
import { MOCK_SERVERS } from "@/plugins/smb/mockData"
import { HoardSmbWeb } from "@/plugins/smb/web"
import { parseHostInput, testConnection } from "./address"

vi.stubGlobal("localStorage", { getItem: () => null, setItem: () => {}, removeItem: () => {} })

describe("parseHostInput", () => {
  it.each([
    ["192.168.1.155", { host: "192.168.1.155" }],
    ["  nas.local ", { host: "nas.local" }],
    ["smb://nas.local/Public/x", { host: "nas.local" }],
    ["\\\\192.168.1.20\\Public", { host: "192.168.1.20" }],
    ["192.168.1.155:4450", { host: "192.168.1.155", port: 4450 }],
    ["host:99999", { host: "host" }],
    ["[fd7a:115c:a1e0::1]:445", { host: "fd7a:115c:a1e0::1", port: 445 }],
    ["fd7a:115c:a1e0::1", { host: "fd7a:115c:a1e0::1" }],
    ["", null],
    ["two words", null],
  ])("%s", (input, expected) => {
    expect(parseHostInput(input)).toEqual(expected)
  })
})

describe("testConnection", () => {
  let smb: HoardSmbWeb
  beforeEach(() => {
    smb = new HoardSmbWeb()
    smb.latencyScale = 0
  })

  it("reports every address: reachable + GUID, someone else's server, unreachable", async () => {
    const results = await testConnection(smb, {
      machineId: "demo-cachyos-fps",
      addresses: [
        { host: "192.168.1.155" },
        { host: "192.168.0.10" },
        { host: "100.101.12.34" },
        { host: "203.0.113.9" },
      ],
      pinnedGuid: null,
      timeoutMs: 1500,
    })
    expect(results.map((r) => [r.host, r.probe.reachable, r.connect?.ok, r.connect?.guid])).toEqual(
      [
        ["192.168.1.155", true, true, "first"],
        ["192.168.0.10", true, false, "mismatch"],
        ["100.101.12.34", true, true, "match"],
        ["203.0.113.9", false, undefined, undefined],
      ]
    )
    expect(results[0].connect?.serverGuid).toBe(MOCK_SERVERS.cachy.guid)
    expect(results[2].kind).toBe("tailscale")
  })

  it("reports AUTH_FAILED with the wrong password", async () => {
    const [result] = await testConnection(smb, {
      machineId: "demo-office-pc",
      addresses: [{ host: "office.example.com" }],
      pinnedGuid: null,
      timeoutMs: 1500,
    })
    expect(result.connect).toMatchObject({ ok: false, code: "AUTH_FAILED" })
  })
})
