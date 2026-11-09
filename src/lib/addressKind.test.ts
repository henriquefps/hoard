import { describe, expect, it } from "vitest"
import { detectAddressKind, sortByKind } from "./addressKind"

describe("detectAddressKind", () => {
  it.each([
    ["100.64.0.1", "tailscale"],
    ["100.101.12.34", "tailscale"],
    ["100.127.255.254", "tailscale"],
    ["cachyos-fps.tail1234.ts.net", "tailscale"],
    ["CachyOS-FPS.tail1234.TS.NET.", "tailscale"],
    ["fd7a:115c:a1e0::1", "tailscale"],
    ["192.168.1.155", "local"],
    ["10.0.0.40", "local"],
    ["172.16.0.1", "local"],
    ["172.31.255.1", "local"],
    ["169.254.10.2", "local"],
    ["nas.local", "local"],
    ["fe80::1", "local"],
    ["[fd12:3456::1]", "local"],
    ["100.63.255.255", "other"],
    ["100.128.0.1", "other"],
    ["172.32.0.1", "other"],
    ["8.8.8.8", "other"],
    ["cachyos-fps", "other"],
    ["office.example.com", "other"],
    ["999.1.1.1", "other"],
  ])("%s → %s", (host, kind) => {
    expect(detectAddressKind(host)).toBe(kind)
  })
})

describe("sortByKind", () => {
  it("puts local first, then tailscale, then other, keeping order inside a kind", () => {
    const sorted = sortByKind([
      { host: "office.example.com" },
      { host: "100.101.12.34" },
      { host: "192.168.1.155" },
      { host: "cachyos-fps.tail1234.ts.net" },
      { host: "10.0.0.2" },
    ])
    expect(sorted.map((a) => a.host)).toEqual([
      "192.168.1.155",
      "10.0.0.2",
      "100.101.12.34",
      "cachyos-fps.tail1234.ts.net",
      "office.example.com",
    ])
  })
})
