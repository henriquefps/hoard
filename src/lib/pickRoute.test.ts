import { describe, expect, it } from "vitest"
import type { ProbeResult } from "@/plugins/smb/definitions"
import { pickRoute } from "./pickRoute"

/** A probe whose answers we release by hand, to control ordering. */
function controlledProbe() {
  const pending = new Map<string, (r: ProbeResult) => void>()
  const probe = (address: { host: string }) =>
    new Promise<ProbeResult>((resolve) => pending.set(address.host, resolve))
  const answer = (host: string, reachable: boolean) =>
    pending.get(host)!(reachable ? { reachable, latencyMs: 5 } : { reachable })
  return { probe, answer }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

describe("pickRoute", () => {
  it("returns the first address as soon as it answers, without waiting for slower ones", async () => {
    const { probe, answer } = controlledProbe()
    const outcome = pickRoute([{ host: "a" }, { host: "b" }], probe)
    answer("a", true)
    const result = await outcome
    expect(result.winner?.address.host).toBe("a")
    expect(result.results[1]).toBeUndefined()
  })

  it("waits for a higher-priority address before accepting a lower one", async () => {
    const { probe, answer } = controlledProbe()
    let done = false
    const outcome = pickRoute([{ host: "a" }, { host: "b" }], probe).then((r) => {
      done = true
      return r
    })
    answer("b", true)
    await flush()
    expect(done).toBe(false)
    answer("a", false)
    expect((await outcome).winner?.address.host).toBe("b")
  })

  it("prefers the higher-priority address when both answer", async () => {
    const { probe, answer } = controlledProbe()
    const outcome = pickRoute([{ host: "a" }, { host: "b" }], probe)
    answer("b", true)
    answer("a", true)
    expect((await outcome).winner?.address.host).toBe("a")
  })

  it("returns null when nothing answers, and treats a rejected probe as unreachable", async () => {
    const outcome = await pickRoute([{ host: "a" }, { host: "b" }], (a) =>
      a.host === "a" ? Promise.reject(new Error("boom")) : Promise.resolve({ reachable: false })
    )
    expect(outcome.winner).toBeNull()
  })

  it("handles an empty list", async () => {
    expect((await pickRoute([], () => Promise.resolve({ reachable: true }))).winner).toBeNull()
  })
})
