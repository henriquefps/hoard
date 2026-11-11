import { describe, expect, it } from "vitest"
import type { CullShot } from "@/plugins/smb/definitions"
import {
  needsConfirm,
  REVEAL_CONFIRM_ABOVE,
  revealOrder,
  selectionPaths,
  shotInitialPath,
  shotsPaths,
} from "./handoff"

const file = (path: string) => ({
  name: path.slice(path.lastIndexOf("/") + 1),
  path,
  size: 1,
  modifiedAt: null,
})
const entry = (path: string, isDirectory = false) => ({
  name: path.slice(path.lastIndexOf("/") + 1),
  path,
  isDirectory,
})

describe("Edit in Reveal: what is sent", () => {
  it("keeps a shot's files together, RAW first, at the place of the first one", () => {
    expect(
      revealOrder(["D/b.JPG", "D/a.ARW", "D/B.arw", "D/a.jpg", "D/c.heic", "D/c.JPG"])
    ).toEqual(["D/B.arw", "D/b.JPG", "D/a.ARW", "D/a.jpg", "D/c.JPG", "D/c.heic"])
  })

  it("drops duplicates and non-photos; the same stem in two folders is two photos", () => {
    expect(
      revealOrder(["x/IMG_1.jpg", "y/IMG_1.ARW", "x/IMG_1.jpg", "x/a.txt", "x/IMG_1.xmp"])
    ).toEqual(["x/IMG_1.jpg", "y/IMG_1.ARW"])
  })

  it("sends every file of the culling shots in grid order", () => {
    const pair: CullShot = {
      id: "dsc2",
      stem: "DSC2",
      kind: "pair",
      raw: file("D/DSC2.ARW"),
      rendered: file("D/DSC2.JPG"),
      others: [file("D/DSC2.HIF")],
    }
    const jpeg: CullShot = {
      id: "dsc1",
      stem: "DSC1",
      kind: "jpeg",
      rendered: file("D/DSC1.JPG"),
      others: [],
    }
    expect(shotsPaths([pair, jpeg])).toEqual([
      "D/DSC2.ARW",
      "D/DSC2.JPG",
      "D/DSC2.HIF",
      "D/DSC1.JPG",
    ])
    expect(shotInitialPath(pair)).toBe("D/DSC2.ARW")
    expect(shotInitialPath(jpeg)).toBe("D/DSC1.JPG")
  })

  it("expands a browser selection to both files of a pair, leaving folders and other files out", () => {
    const folder = [
      entry("D/Sub", true),
      entry("D/DSC1.ARW"),
      entry("D/dsc1.jpg"),
      entry("D/DSC2.JPG"),
      entry("D/DSC2.txt"),
      entry("D/readme.md"),
    ]
    expect(
      selectionPaths([entry("D/dsc1.jpg"), entry("D/Sub", true), entry("D/readme.md")], folder)
    ).toEqual(["D/DSC1.ARW", "D/dsc1.jpg"])
    expect(selectionPaths([entry("D/DSC2.JPG"), entry("D/DSC1.ARW")], folder)).toEqual([
      "D/DSC2.JPG",
      "D/DSC1.ARW",
      "D/dsc1.jpg",
    ])
  })

  it("asks first above the batch limit and for SMB photos", () => {
    expect(needsConfirm(REVEAL_CONFIRM_ABOVE, false)).toBe(false)
    expect(needsConfirm(REVEAL_CONFIRM_ABOVE + 1, false)).toBe(true)
    expect(needsConfirm(1, true)).toBe(true)
  })
})
