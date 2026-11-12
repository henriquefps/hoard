import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type {
  ConnectOptions,
  ConnectResult,
  HoardSmbErrorCode,
  ProbeOptions,
  ProbeResult,
} from "@/plugins/smb/definitions"
import {
  type EngineMachine,
  nativeMachines,
  orderForTransfer,
  RouteEngine,
  type SmbForEngine,
} from "./engine"

class FakeError extends Error {
  readonly code: HoardSmbErrorCode
  readonly data?: Record<string, unknown>
  constructor(code: HoardSmbErrorCode, data?: Record<string, unknown>) {
    super(code)
    this.code = code
    this.data = data
  }
}

interface HostBehavior {
  /** Probe answers after this many ms (fake timers); undefined = reachable after 1 ms. */
  delay?: number
  reachable?: boolean
  reason?: "UNREACHABLE" | "TIMEOUT"
  latencyMs?: number
  /** Server GUID behind this host. */
  guid?: string
  /** Connect fails with this code (after the GUID check). */
  connectError?: HoardSmbErrorCode
}

const GUID = "6f1c2b9e-3a5d-4c7e-9b21-0d8e4f7a1c35"
const FOREIGN = "ffffffff-0000-4000-8000-000000000001"

function fakeSmb(hosts: Record<string, HostBehavior>) {
  let sessions = 0
  const live = new Set<string>()
  const calls = {
    probe: [] as string[],
    connect: [] as ConnectOptions[],
    disconnect: [] as string[],
  }
  const smb: SmbForEngine = {
    probe: vi.fn(async ({ host }: ProbeOptions): Promise<ProbeResult> => {
      calls.probe.push(host)
      const behavior = hosts[host] ?? { reachable: false, reason: "UNREACHABLE" }
      await new Promise((r) => setTimeout(r, behavior.delay ?? 1))
      return behavior.reachable === false
        ? { reachable: false, reason: behavior.reason ?? "UNREACHABLE" }
        : { reachable: true, latencyMs: behavior.latencyMs ?? behavior.delay ?? 1 }
    }),
    connect: vi.fn(async (options: ConnectOptions): Promise<ConnectResult> => {
      calls.connect.push(options)
      const behavior = hosts[options.host] ?? {}
      await new Promise((r) => setTimeout(r, 1))
      const guid = behavior.guid ?? GUID
      if (options.expectedServerGuid && options.expectedServerGuid !== guid) {
        throw new FakeError("GUID_MISMATCH", { serverGuid: guid })
      }
      if (behavior.connectError) throw new FakeError(behavior.connectError)
      const sessionId = `s${++sessions}`
      live.add(sessionId)
      return { sessionId, serverGuid: guid, dialect: "SMB_3_1_1", serverName: "SERVER" }
    }),
    disconnect: vi.fn(async ({ sessionId }) => {
      calls.disconnect.push(sessionId)
      live.delete(sessionId)
    }),
  }
  return { smb, calls, live }
}

const machine = (hosts: string[], guid: string | null = GUID): EngineMachine => ({
  id: "m1",
  server_guid: guid,
  addresses: hosts.map((host) => ({ host })),
})

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

async function settle<T>(promise: Promise<T>): Promise<T> {
  let done = false
  let value: T | undefined
  void promise.then((v) => {
    done = true
    value = v
  })
  for (let i = 0; i < 50 && !done; i++) await vi.advanceTimersByTimeAsync(100)
  if (!done) throw new Error("did not settle")
  return value as T
}

describe("RouteEngine.check", () => {
  it("connects to the first reachable address in priority order and reports the route", async () => {
    const { smb } = fakeSmb({
      "192.168.1.155": { delay: 4, latencyMs: 4 },
      "100.101.12.34": { delay: 70 },
    })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const state = await settle(engine.check(machine(["192.168.1.155", "100.101.12.34"])))
    expect(state.status).toBe("online")
    expect(state.route).toMatchObject({ host: "192.168.1.155", kind: "local", latencyMs: 4 })
    expect(engine.getState("m1")).toBe(state)
  })

  it("does not wait for a slow dead address once a higher-priority one answers", async () => {
    const { smb } = fakeSmb({
      "192.168.1.155": { delay: 5 },
      "100.88.7.5": { delay: 1500, reachable: false, reason: "TIMEOUT" },
    })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const promise = engine.check(machine(["192.168.1.155", "100.88.7.5"]))
    await vi.advanceTimersByTimeAsync(50)
    const state = engine.getState("m1")
    expect(state.status).toBe("online")
    await settle(promise)
  })

  it("fails over when the first address is unreachable", async () => {
    const { smb } = fakeSmb({
      "10.0.0.40": { delay: 30, reachable: false },
      "office.example.com": { delay: 160 },
    })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const state = await settle(engine.check(machine(["10.0.0.40", "office.example.com"])))
    expect(state.route?.host).toBe("office.example.com")
    expect(state.route?.kind).toBe("other")
    expect(state.attempts[0].probe).toEqual({ reachable: false, reason: "UNREACHABLE" })
  })

  it("treats a GUID mismatch as unreachable and continues with the next reachable address", async () => {
    const { smb, calls } = fakeSmb({
      "192.168.0.10": { delay: 2, guid: FOREIGN },
      "100.101.12.34": { delay: 70 },
    })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const state = await settle(engine.check(machine(["192.168.0.10", "100.101.12.34"])))
    expect(state.status).toBe("online")
    expect(state.route?.host).toBe("100.101.12.34")
    expect(state.attempts[0].connect).toBe("GUID_MISMATCH")
    expect(calls.connect.map((c) => c.expectedServerGuid)).toEqual([GUID, GUID])
  })

  it("reports GUID_MISMATCH with the foreign GUID when every reachable address is another server", async () => {
    const { smb } = fakeSmb({ "192.168.0.10": { guid: FOREIGN } })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const state = await settle(engine.check(machine(["192.168.0.10"])))
    expect(state).toMatchObject({
      status: "error",
      errorCode: "GUID_MISMATCH",
      foreignGuid: FOREIGN,
    })
  })

  it("pins the GUID on the first successful connect and uses it afterwards", async () => {
    const { smb, calls } = fakeSmb({ "192.168.1.155": {} })
    const saveGuid = vi.fn(async () => undefined)
    const engine = new RouteEngine({ smb, saveGuid })
    const m = machine(["192.168.1.155"], null)
    await settle(engine.check(m))
    expect(saveGuid).toHaveBeenCalledWith("m1", GUID)
    expect(engine.knownGuid("m1")).toBe(GUID)
    // The caller still holds the stale row (server_guid null): the pinned GUID is used anyway.
    await settle(engine.check(m, { force: true }))
    expect(calls.connect.map((c) => c.expectedServerGuid)).toEqual([null, GUID])
  })

  it("stops on AUTH_FAILED (not an address problem) and keeps the route", async () => {
    const { smb, calls } = fakeSmb({
      "192.168.1.155": { connectError: "AUTH_FAILED" },
      "100.101.12.34": {},
    })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const state = await settle(engine.check(machine(["192.168.1.155", "100.101.12.34"])))
    expect(state).toMatchObject({ status: "error", errorCode: "AUTH_FAILED" })
    expect(state.route?.host).toBe("192.168.1.155")
    expect(calls.connect).toHaveLength(1)
  })

  it("is offline (TIMEOUT) when every address times out, UNREACHABLE otherwise", async () => {
    const { smb } = fakeSmb({
      a: { reachable: false, reason: "TIMEOUT" },
      b: { reachable: false, reason: "TIMEOUT" },
    })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    expect(await settle(engine.check(machine(["a", "b"])))).toMatchObject({
      status: "offline",
      errorCode: "TIMEOUT",
    })
    expect(await settle(engine.check({ ...machine(["a", "zzz"]), id: "m2" }))).toMatchObject({
      status: "offline",
      errorCode: "UNREACHABLE",
    })
    expect(await settle(engine.check({ ...machine([]), id: "m3" }))).toMatchObject({
      status: "offline",
    })
  })

  it("passes the probe timeout setting to every probe", async () => {
    const { smb } = fakeSmb({ "192.168.1.155": {} })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn(), probeTimeoutMs: () => 900 })
    await settle(engine.check(machine(["192.168.1.155"])))
    expect(smb.probe).toHaveBeenCalledWith(expect.objectContaining({ timeoutMs: 900 }))
  })

  it("shares one in-flight check between concurrent callers and reuses the session", async () => {
    const { smb, calls } = fakeSmb({ "192.168.1.155": {} })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const m = machine(["192.168.1.155"])
    const [a, b] = await settle(Promise.all([engine.check(m), engine.check(m)]))
    expect(a).toBe(b)
    await settle(engine.check(m))
    expect(calls.connect).toHaveLength(1)
  })
})

describe("RouteEngine sessions", () => {
  it("withSession reconnects once when the session is gone (NOT_FOUND)", async () => {
    const { smb, calls, live } = fakeSmb({ "192.168.1.155": {} })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const m = machine(["192.168.1.155"])
    await settle(engine.check(m))
    live.clear() // native dropped every session
    const list = vi.fn(async (sessionId: string) => {
      if (!live.has(sessionId)) throw new FakeError("NOT_FOUND")
      return sessionId
    })
    expect(await settle(engine.withSession(m, list))).toBe("s2")
    expect(list).toHaveBeenCalledTimes(2)
    expect(calls.connect).toHaveLength(2)
  })

  it("withSession rethrows a second NOT_FOUND (the path really is gone)", async () => {
    const { smb } = fakeSmb({ "192.168.1.155": {} })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const m = machine(["192.168.1.155"])
    const result = engine
      .withSession(m, async () => {
        throw new FakeError("NOT_FOUND")
      })
      .catch((error: FakeError) => error.code)
    expect(await settle(result)).toBe("NOT_FOUND")
  })

  it("session() rejects with the route's error code when offline", async () => {
    const { smb } = fakeSmb({})
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const result = engine.session(machine(["203.0.113.9"])).catch((e: { code: string }) => e.code)
    expect(await settle(result)).toBe("UNREACHABLE")
  })
})

describe("RouteEngine.recheckAll (resume / network change)", () => {
  it("drops sessions and rechecks every known machine once, debounced", async () => {
    const hosts: Record<string, HostBehavior> = {
      "192.168.1.155": {},
      "100.101.12.34": { delay: 70 },
    }
    const { smb, calls } = fakeSmb(hosts)
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const m = machine(["192.168.1.155", "100.101.12.34"])
    await settle(engine.check(m))
    expect(engine.getState("m1").route?.kind).toBe("local")

    // Left home: Wi-Fi gone, Tailscale up.
    hosts["192.168.1.155"] = { reachable: false }
    engine.recheckAll()
    engine.recheckAll() // resume + networkChanged arrive together
    await vi.advanceTimersByTimeAsync(260)
    expect(engine.getState("m1").status).toBe("checking")
    // While checking, the last known route stays visible.
    expect(engine.getState("m1").route?.host).toBe("192.168.1.155")
    await vi.advanceTimersByTimeAsync(500)
    expect(engine.getState("m1")).toMatchObject({
      status: "online",
      route: { host: "100.101.12.34", kind: "tailscale" },
    })
    expect(calls.disconnect).toEqual(["s1"])
    expect(calls.connect).toHaveLength(2)
  })

  it("ignores a stale check that finishes after a newer one", async () => {
    const hosts: Record<string, HostBehavior> = { slow: { delay: 500 }, fast: { delay: 5 } }
    const { smb } = fakeSmb(hosts)
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    const first = engine.check(machine(["slow"]))
    await vi.advanceTimersByTimeAsync(10)
    const second = engine.check(machine(["fast"]), { force: true })
    await settle(Promise.all([first, second]))
    expect(engine.getState("m1").route?.host).toBe("fast")
  })

  it("forget() clears state and disconnects", async () => {
    const { smb, calls } = fakeSmb({ "192.168.1.155": {} })
    const engine = new RouteEngine({ smb, saveGuid: vi.fn() })
    await settle(engine.check(machine(["192.168.1.155"])))
    engine.forget("m1")
    expect(engine.getState("m1").status).toBe("idle")
    expect(calls.disconnect).toEqual(["s1"])
  })
})

describe("orderForTransfer", () => {
  const addresses = [{ host: "192.168.1.155" }, { host: "100.101.12.34" }, { host: "cachyos-fps" }]
  it("puts the route in use first and keeps the rest in priority order", () => {
    expect(
      orderForTransfer(addresses, { host: "100.101.12.34", port: 445 }).map((a) => a.host)
    ).toEqual(["100.101.12.34", "192.168.1.155", "cachyos-fps"])
  })
  it("keeps the order without a route", () => {
    expect(orderForTransfer(addresses, undefined)).toEqual(addresses)
  })
})

describe("nativeMachines", () => {
  it("keeps the priority order, the pinned GUID, and ports only when set", () => {
    expect(
      nativeMachines([
        {
          id: "m1",
          server_guid: "g",
          addresses: [
            { host: "192.168.1.2", port: 445 },
            { host: "nas", port: null },
            { host: "100.64.0.2" },
          ],
        },
        { id: "m2", server_guid: null, addresses: [] },
      ])
    ).toEqual([
      {
        machineId: "m1",
        serverGuid: "g",
        addresses: [{ host: "192.168.1.2", port: 445 }, { host: "nas" }, { host: "100.64.0.2" }],
      },
      { machineId: "m2", serverGuid: null, addresses: [] },
    ])
  })
})
