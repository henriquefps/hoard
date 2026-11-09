import { describe, expect, it } from "vitest"
import {
  diffVolumes,
  localDisplayPath,
  localRefOf,
  locationIdOfVolume,
  parseLocationId,
  usedFraction,
  volumeLocationId,
} from "./ids"

describe("location ids", () => {
  it("routes device, volumes and machines (old machine links unchanged)", () => {
    expect(parseLocationId("device")).toEqual({ kind: "device" })
    expect(parseLocationId("vol-1A2B-3C4D")).toEqual({ kind: "volume", uuid: "1A2B-3C4D" })
    expect(parseLocationId("4f6e2b1c-0d6a-4f53-9a51-1d2b3c4d5e6f")).toEqual({
      kind: "smb",
      machineId: "4f6e2b1c-0d6a-4f53-9a51-1d2b3c4d5e6f",
    })
    // Demo machine ids and a bare "vol-" are machines, not volumes.
    expect(parseLocationId("demo-cachyos-fps").kind).toBe("smb")
    expect(parseLocationId("vol-").kind).toBe("smb")
  })

  it("round-trips volumes and maps the primary volume to device", () => {
    expect(parseLocationId(volumeLocationId("6C3E-11F0"))).toEqual({
      kind: "volume",
      uuid: "6C3E-11F0",
    })
    expect(locationIdOfVolume({ primary: true, uuid: null })).toBe("device")
    expect(locationIdOfVolume({ primary: false, uuid: "6C3E-11F0" })).toBe("vol-6C3E-11F0")
    expect(localRefOf("vol-6C3E-11F0")).toEqual({ kind: "volume", uuid: "6C3E-11F0" })
    expect(localRefOf("device")).toEqual({ kind: "device" })
    expect(localRefOf("some-machine")).toBeUndefined()
  })

  it("builds display paths and used fractions", () => {
    expect(localDisplayPath("/storage/emulated/0", "DCIM/Camera")).toBe(
      "/storage/emulated/0/DCIM/Camera"
    )
    expect(localDisplayPath("/storage/1A2B-3C4D/", "")).toBe("/storage/1A2B-3C4D")
    expect(usedFraction({ totalBytes: 100, freeBytes: 25 })).toBe(0.75)
    expect(usedFraction({ totalBytes: 0, freeBytes: 0 })).toBeUndefined()
    expect(usedFraction({})).toBeUndefined()
  })
})

describe("diffVolumes", () => {
  it("reports attached, state changes and removed volumes, never the primary", () => {
    const usb = { uuid: "1A2B", state: "mounted" }
    const primary = { uuid: null, state: "mounted" }
    expect(diffVolumes(undefined, [primary, usb])).toEqual([{ uuid: "1A2B", state: "mounted" }])
    expect(diffVolumes([primary, usb], [primary, usb])).toEqual([])
    expect(diffVolumes([primary, usb], [primary])).toEqual([{ uuid: "1A2B", state: "removed" }])
    expect(diffVolumes([primary, { uuid: "1A2B", state: "checking" }], [primary, usb])).toEqual([
      { uuid: "1A2B", state: "mounted" },
    ])
  })
})
