import { describe, expect, it } from "vitest"
import { clipboard } from "./clipboard"
import { pasteProblem, rememberDestination, sameLocation, transferRequest } from "./places"

const device = { locationId: "device" }
const nas = { locationId: "m1", share: "Media" }

describe("paste rules", () => {
  it("refuses a folder into itself and a move into its own folder", () => {
    const dcim = [{ path: "DCIM", isDirectory: true }]
    expect(pasteProblem("copy", device, dcim, { ...device, path: "DCIM/Camera" })).toBe(
      "intoItself"
    )
    expect(pasteProblem("copy", device, dcim, { ...device, path: "DCIM" })).toBe("intoItself")
    expect(pasteProblem("copy", device, dcim, { ...device, path: "DCIMX" })).toBeNull()
    const files = [{ path: "DCIM/a.jpg", isDirectory: false }]
    expect(pasteProblem("move", device, files, { ...device, path: "DCIM" })).toBe("sameFolder")
    // A copy into the same folder makes "a (1).jpg".
    expect(pasteProblem("copy", device, files, { ...device, path: "DCIM" })).toBeNull()
    // Another location never conflicts.
    expect(pasteProblem("move", device, dcim, { locationId: "vol-1A2B", path: "DCIM" })).toBeNull()
  })

  it("compares shares case-insensitively", () => {
    expect(sameLocation(nas, { locationId: "m1", share: "media" })).toBe(true)
    expect(sameLocation(nas, { locationId: "m1", share: "photos" })).toBe(false)
  })

  it("builds a request with the known size and count", () => {
    const request = transferRequest(
      "move",
      device,
      [
        { path: "DCIM/a.jpg", name: "a.jpg", isDirectory: false, size: 10 },
        { path: "DCIM/b", name: "b", isDirectory: true, size: 0 },
      ],
      { ...nas, path: "Photos" },
      "ask"
    )
    expect(request).toMatchObject({
      op: "move",
      name: "a.jpg",
      size: 10,
      itemsTotal: 2,
      paths: ["DCIM/a.jpg", "DCIM/b"],
    })
  })
})

describe("recent destinations", () => {
  it("moves a destination to the front without duplicates and caps the list", () => {
    let list = rememberDestination([], { locationId: "device", path: "DCIM", label: "DCIM" }, 3)
    list = rememberDestination(
      list,
      { locationId: "m1", share: "media", path: "", label: "media" },
      3
    )
    list = rememberDestination(list, { locationId: "device", path: "/DCIM/", label: "DCIM" }, 3)
    expect(list.map((d) => d.locationId)).toEqual(["device", "m1"])
    list = rememberDestination(list, { locationId: "a", path: "", label: "a" }, 3)
    list = rememberDestination(list, { locationId: "b", path: "", label: "b" }, 3)
    expect(list.map((d) => d.locationId)).toEqual(["b", "a", "device"])
  })
})

describe("clipboard", () => {
  it("holds one entry until cleared and notifies", () => {
    let calls = 0
    const stop = clipboard.subscribe(() => calls++)
    clipboard.set({
      op: "copy",
      source: { ...device, path: "DCIM" },
      sourceLabel: "DCIM",
      entries: [{ path: "DCIM/a.jpg", name: "a.jpg", isDirectory: false, size: 1 }],
    })
    expect(clipboard.get()?.entries).toHaveLength(1)
    clipboard.clear()
    expect(clipboard.get()).toBeNull()
    expect(calls).toBe(2)
    stop()
  })
})
