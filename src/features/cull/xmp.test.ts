import { describe, expect, it } from "vitest"
import type { CullShot } from "@/plugins/smb/definitions"
import { planCopy, type CullDecision } from "./model"
import {
  importAfter,
  importChanges,
  importedDecision,
  revealCullingFor,
  sidecarsFor,
  sidecarStateOf,
  wireOf,
} from "./xmp"

const shot = (stem: string, raw: boolean, jpeg: boolean): CullShot => ({
  id: stem.toLowerCase(),
  stem,
  kind: raw && jpeg ? "pair" : raw ? "raw" : "jpeg",
  raw: raw ? { name: `${stem}.ARW`, path: `D/${stem}.ARW`, size: 10, modifiedAt: null } : undefined,
  rendered: jpeg
    ? { name: `${stem}.JPG`, path: `D/${stem}.JPG`, size: 5, modifiedAt: null }
    : undefined,
  others: [],
})

describe("XMP sidecars: the JavaScript side (ADR 0011)", () => {
  it("puts decisions on the wire, unmarked as none and a reject with its stars", () => {
    expect(wireOf({ flag: null, rating: 0 })).toEqual({ rating: 0, flag: "none" })
    expect(wireOf({ flag: "reject", rating: 3 })).toEqual({ rating: 3, flag: "reject" })
  })

  it("asks Copy picks for one sidecar per copied shot, on the RAW when it is copied", () => {
    const order = [shot("A", true, true), shot("B", false, true), shot("C", true, true)]
    const decisions = new Map<string, CullDecision>([
      ["a", { flag: "pick", rating: 5 }],
      ["b", { flag: "pick", rating: 0 }],
      ["c", { flag: "reject", rating: 0 }],
    ])
    expect(sidecarsFor(planCopy(order, decisions, "both").items, order, decisions)).toEqual([
      { sourcePath: "D/A.ARW", rating: 5, flag: "pick" },
      { sourcePath: "D/B.JPG", rating: 0, flag: "pick" },
    ])
    // JPEG only: the sidecar follows the JPEG.
    const jpeg = sidecarsFor(planCopy(order, decisions, "jpeg").items, order, decisions)
    expect(jpeg.map((s) => s.sourcePath)).toEqual(["D/A.JPG", "D/B.JPG"])
  })

  it("gives Reveal the decision of every file sent whose shot has one", () => {
    const decisions = new Map<string, CullDecision>([
      ["dsc1", { flag: "pick", rating: 4 }],
      ["dsc2", { flag: "reject", rating: 2 }],
      ["dsc3", { flag: null, rating: 0 }],
    ])
    expect(
      revealCullingFor(
        ["D/DSC1.ARW", "D/dsc1.jpg", "D/DSC2.JPG", "D/DSC3.JPG", "D/DSC4.ARW"],
        decisions
      )
    ).toEqual({
      "D/DSC1.ARW": { rating: 4, flag: "pick" },
      "D/dsc1.jpg": { rating: 4, flag: "pick" },
      "D/DSC2.JPG": { rating: 2, flag: "reject" },
    })
  })

  describe("precedence", () => {
    it("a sidecar wins when Hoard never decided on the shot", () => {
      expect(importAfter(sidecarStateOf(undefined, undefined))).toBeNull()
    })

    it("after a write, only a sidecar changed since wins (file-system clock only)", () => {
      const state = sidecarStateOf(
        { decided_at: 5_000, sidecar_mtime: 4_000, synced: 1 },
        undefined
      )
      // The server's clock is behind the phone's: its mtime is earlier than the decision, and still counts.
      expect(importAfter(state)).toBe(4_000)
    })

    it("a decision not written (read-only folder, failed write, before 1.9.0) wins until a later sidecar", () => {
      expect(
        importAfter(
          sidecarStateOf({ decided_at: 5_000, sidecar_mtime: 4_000, synced: 0 }, undefined)
        )
      ).toBe(5_000)
      expect(importAfter(sidecarStateOf(undefined, "2026-11-01T00:00:00.000Z"))).toBe(
        Date.parse("2026-11-01T00:00:00.000Z")
      )
      expect(
        importAfter(
          sidecarStateOf({ decided_at: 1_000, sidecar_mtime: 9_000, synced: 0 }, undefined)
        )
      ).toBe(9_000)
    })

    it("imports what the sidecar says, keeps a reject's stars, ignores develop-only sidecars", () => {
      const current: CullDecision = { flag: "reject", rating: 3 }
      expect(importedDecision(current, { rating: 0, flag: "reject" }, true)).toEqual(current)
      expect(importedDecision(current, { rating: 5, flag: "pick" }, true)).toEqual({
        flag: "pick",
        rating: 5,
      })
      expect(importedDecision(current, { rating: 2, flag: "none", label: "Red" }, true)).toEqual({
        flag: null,
        rating: 2,
      })
      expect(importedDecision(current, { rating: 0, flag: "none" }, false)).toBeNull()
      expect(importChanges(current, current)).toBe(false)
    })
  })
})
