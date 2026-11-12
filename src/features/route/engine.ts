import type { AddressKind } from "@/lib/addressKind"
import { detectAddressKind } from "@/lib/addressKind"
import { pickRoute } from "@/lib/pickRoute"
import type {
  ConnectResult,
  HoardSmbErrorCode,
  HoardSmbPlugin,
  NativeMachine,
  ProbeResult,
} from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"

/**
 * The reachability engine (ADR 0002), framework-free so it is unit tested with a fake plugin.
 *
 * Per machine: probe every address in parallel (`pickRoute`), connect to the winner with the pinned
 * `expectedServerGuid`, and on `GUID_MISMATCH` (somebody else's server on that address) or a
 * connect-time `UNREACHABLE`/`TIMEOUT` move on to the next reachable address. The first successful
 * connect of a machine without a GUID pins it (`saveGuid`). The session is cached per machine and
 * reused by every browse call (`withSession`), which reconnects once when the native side says the
 * session is gone (`NOT_FOUND`). `recheckAll` (app resume, network change) drops the sessions and
 * checks again every machine the app has looked at.
 */

export interface EngineAddress {
  host: string
  port?: number | null
  kind?: AddressKind
}

export interface EngineMachine {
  id: string
  server_guid: string | null
  /** In priority order. */
  addresses: readonly EngineAddress[]
}

export type RouteStatus = "idle" | "checking" | "online" | "offline" | "error"

/** What happened with one address during the last check. */
export interface AddressAttempt {
  host: string
  port: number
  kind: AddressKind
  /** Probe answer; undefined while pending or when the decision came before it. */
  probe?: ProbeResult
  /** Connect outcome when one was tried: "ok" or the error code. */
  connect?: "ok" | HoardSmbErrorCode
}

export interface ActiveRoute {
  host: string
  port: number
  kind: AddressKind
  latencyMs?: number
}

export interface RouteState {
  status: RouteStatus
  /** The address in use (online), or the one that answered before an auth error. */
  route?: ActiveRoute
  /** Set when status is "offline" (UNREACHABLE/TIMEOUT) or "error" (AUTH_FAILED, GUID_MISMATCH…). */
  errorCode?: HoardSmbErrorCode
  errorMessage?: string
  /** The GUID the mismatching server reported, for the "re-pin" explanation. */
  foreignGuid?: string
  serverName?: string
  attempts: AddressAttempt[]
  /** `Date.now()` of the last finished check. */
  checkedAt?: number
}

export type SmbForEngine = Pick<HoardSmbPlugin, "probe" | "connect" | "disconnect">

export interface RouteEngineDeps {
  smb: SmbForEngine
  /** Persist the GUID pinned on first connect (`setServerGuid`). */
  saveGuid: (machineId: string, guid: string) => Promise<void>
  /** Probe timeout in ms (a setting); read on every check. */
  probeTimeoutMs?: () => number
  /** Connect timeout in ms. */
  connectTimeoutMs?: number
  now?: () => number
}

const IDLE: RouteState = Object.freeze({ status: "idle", attempts: [] }) as RouteState

interface Session {
  sessionId: string
  route: ActiveRoute
}

const portOf = (address: EngineAddress) => address.port || 445

export class RouteEngine {
  private readonly states = new Map<string, RouteState>()
  private readonly sessions = new Map<string, Session>()
  private readonly inflight = new Map<string, Promise<RouteState>>()
  /** Last machine object seen per id (for rechecks) with any GUID pinned since. */
  private readonly machines = new Map<string, EngineMachine>()
  private readonly generation = new Map<string, number>()
  private readonly listeners = new Set<() => void>()
  private recheckTimer: ReturnType<typeof setTimeout> | undefined

  private readonly deps: RouteEngineDeps

  constructor(deps: RouteEngineDeps) {
    this.deps = deps
  }

  // --- Store API (useSyncExternalStore) ------------------------------------------------------

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getState = (machineId: string): RouteState => this.states.get(machineId) ?? IDLE

  private set(machineId: string, state: RouteState) {
    this.states.set(machineId, state)
    this.listeners.forEach((listener) => listener())
  }

  // --- Checks --------------------------------------------------------------------------------

  /** Remember the latest machine data (addresses or GUID changed in the DB). */
  private remember(machine: EngineMachine): EngineMachine {
    const known = this.machines.get(machine.id)
    const merged = {
      ...machine,
      // A GUID pinned by this engine wins over a stale row the caller still holds.
      server_guid: machine.server_guid ?? known?.server_guid ?? null,
    }
    this.machines.set(machine.id, merged)
    return merged
  }

  /**
   * Find the route and open a session. Concurrent calls share one check; `force` starts a new one
   * even when a session exists (a stale in-flight check is then ignored).
   */
  check(machine: EngineMachine, { force = false } = {}): Promise<RouteState> {
    const current = this.remember(machine)
    const running = this.inflight.get(machine.id)
    if (running && !force) return running
    const state = this.states.get(machine.id)
    if (!force && state?.status === "online" && this.sessions.has(machine.id)) {
      return Promise.resolve(state)
    }
    const generation = (this.generation.get(machine.id) ?? 0) + 1
    this.generation.set(machine.id, generation)
    const promise = this.run(current, generation).finally(() => {
      if (this.inflight.get(machine.id) === promise) this.inflight.delete(machine.id)
    })
    this.inflight.set(machine.id, promise)
    return promise
  }

  /** Check only when nothing is known yet (or the last check failed long enough ago). */
  ensure(machine: EngineMachine): Promise<RouteState> {
    const state = this.getState(machine.id)
    if (state.status === "idle") return this.check(machine)
    this.remember(machine)
    return this.inflight.get(machine.id) ?? Promise.resolve(state)
  }

  private async run(machine: EngineMachine, generation: number): Promise<RouteState> {
    const stale = () => this.generation.get(machine.id) !== generation
    const previous = this.states.get(machine.id)
    const old = this.sessions.get(machine.id)
    if (old) {
      this.sessions.delete(machine.id)
      void this.deps.smb.disconnect({ sessionId: old.sessionId }).catch(() => undefined)
    }
    const addresses = machine.addresses.map((address) => ({
      host: address.host,
      port: portOf(address),
      kind: address.kind ?? detectAddressKind(address.host),
    }))
    const attempts: AddressAttempt[] = addresses.map((a) => ({ ...a }))
    // Keep showing the last known route while checking (the badge says "checking").
    this.set(machine.id, {
      ...(previous ?? IDLE),
      status: "checking",
      attempts,
    })

    const timeoutMs = this.deps.probeTimeoutMs?.() ?? 1500
    const probes = new Map<number, Promise<ProbeResult>>()
    const probeAt = (index: number) => {
      let promise = probes.get(index)
      if (!promise) {
        const { host, port } = addresses[index]
        promise = this.deps.smb
          .probe({ host, port, timeoutMs })
          .catch((): ProbeResult => ({ reachable: false, reason: "UNREACHABLE" }))
          .then((result) => {
            attempts[index] = { ...attempts[index], probe: result }
            return result
          })
        probes.set(index, promise)
      }
      return promise
    }

    let guid = machine.server_guid
    let lastError: { code: HoardSmbErrorCode; message?: string; foreignGuid?: string } | undefined
    let start = 0
    while (start < addresses.length) {
      const candidates = addresses.map((a, index) => ({ ...a, index })).slice(start)
      const outcome = await pickRoute(candidates, (candidate) => probeAt(candidate.index))
      if (stale()) return this.getState(machine.id)
      if (!outcome.winner) break
      const index = outcome.winner.address.index
      const address = addresses[index]
      const route: ActiveRoute = { ...address, latencyMs: outcome.winner.latencyMs }
      let result: ConnectResult
      try {
        result = await this.deps.smb.connect({
          machineId: machine.id,
          host: address.host,
          port: address.port,
          expectedServerGuid: guid,
          timeoutMs: this.deps.connectTimeoutMs,
        })
      } catch (error) {
        if (stale()) return this.getState(machine.id)
        const code = smbErrorCode(error)
        attempts[index] = { ...attempts[index], connect: code }
        const data = (error as { data?: { serverGuid?: unknown } }).data
        lastError = {
          code,
          message: error instanceof Error ? error.message : undefined,
          foreignGuid: typeof data?.serverGuid === "string" ? data.serverGuid : undefined,
        }
        if (code === "GUID_MISMATCH" || code === "UNREACHABLE" || code === "TIMEOUT") {
          start = index + 1
          continue
        }
        // AUTH_FAILED and anything else is not an address problem: failing over would not help.
        return this.finish(machine.id, {
          status: "error",
          route,
          errorCode: code,
          errorMessage: lastError.message,
          attempts,
        })
      }
      if (stale()) {
        void this.deps.smb.disconnect({ sessionId: result.sessionId }).catch(() => undefined)
        return this.getState(machine.id)
      }
      attempts[index] = { ...attempts[index], connect: "ok" }
      if (!guid && result.serverGuid) {
        guid = result.serverGuid.toLowerCase()
        this.machines.set(machine.id, { ...machine, server_guid: guid })
        await this.deps.saveGuid(machine.id, guid).catch(() => undefined)
      }
      this.sessions.set(machine.id, { sessionId: result.sessionId, route })
      return this.finish(machine.id, {
        status: "online",
        route,
        serverName: result.serverName,
        attempts,
      })
    }

    if (stale()) return this.getState(machine.id)
    if (lastError?.code === "GUID_MISMATCH") {
      return this.finish(machine.id, {
        status: "error",
        errorCode: "GUID_MISMATCH",
        errorMessage: lastError.message,
        foreignGuid: lastError.foreignGuid,
        attempts,
      })
    }
    const timedOut =
      attempts.length > 0 && attempts.every((attempt) => attempt.probe?.reason === "TIMEOUT")
    return this.finish(machine.id, {
      status: "offline",
      errorCode: lastError?.code ?? (timedOut ? "TIMEOUT" : "UNREACHABLE"),
      errorMessage: lastError?.message,
      attempts,
    })
  }

  private finish(machineId: string, state: Omit<RouteState, "checkedAt">): RouteState {
    const full = { ...state, checkedAt: (this.deps.now ?? Date.now)() }
    this.set(machineId, full)
    return full
  }

  // --- Sessions ------------------------------------------------------------------------------

  /** An open session for the machine, checking the route first when needed. */
  async session(machine: EngineMachine): Promise<Session> {
    const existing = this.sessions.get(machine.id)
    if (existing) {
      this.remember(machine)
      return existing
    }
    const state = await this.check(machine)
    const session = this.sessions.get(machine.id)
    if (session) return session
    throw routeError(state)
  }

  /**
   * Run `fn` with the machine's session. When native reports `NOT_FOUND` (the session expired, or
   * the path really is gone), reconnect once and retry; a second `NOT_FOUND` is real.
   */
  async withSession<T>(machine: EngineMachine, fn: (sessionId: string) => Promise<T>): Promise<T> {
    const session = await this.session(machine)
    try {
      return await fn(session.sessionId)
    } catch (error) {
      if (smbErrorCode(error) !== "NOT_FOUND") throw error
      if (this.sessions.get(machine.id)?.sessionId === session.sessionId) {
        this.sessions.delete(machine.id)
      }
      const fresh = await this.session(machine)
      return fn(fresh.sessionId)
    }
  }

  /** Current route, for ordering transfer addresses (current first). */
  activeRoute(machineId: string): ActiveRoute | undefined {
    return this.sessions.get(machineId)?.route ?? this.states.get(machineId)?.route
  }

  /** The machine's GUID as far as the engine knows (pinned during this run included). */
  knownGuid(machineId: string): string | null {
    return this.machines.get(machineId)?.server_guid ?? null
  }

  // --- Invalidation ---------------------------------------------------------------------------

  /** Forget a machine (edited, deleted, re-pinned): next use checks from scratch. */
  forget(machineId: string) {
    const session = this.sessions.get(machineId)
    if (session) void this.deps.smb.disconnect({ sessionId: session.sessionId }).catch(() => {})
    this.sessions.delete(machineId)
    this.machines.delete(machineId)
    this.inflight.delete(machineId)
    this.generation.set(machineId, (this.generation.get(machineId) ?? 0) + 1)
    this.states.delete(machineId)
    this.listeners.forEach((listener) => listener())
  }

  /** Recheck every machine seen so far (resume, network change). Debounced: both fire together. */
  recheckAll(delayMs = 250): void {
    clearTimeout(this.recheckTimer)
    this.recheckTimer = setTimeout(() => {
      for (const machine of this.machines.values()) void this.check(machine, { force: true })
    }, delayMs)
  }

  /** Every machine id the engine has state for (tests, debugging). */
  knownMachines(): string[] {
    return [...this.machines.keys()]
  }
}

/** An Error carrying the route failure's code, so callers handle it like a plugin rejection. */
export class RouteError extends Error {
  readonly code: HoardSmbErrorCode
  readonly state: RouteState
  constructor(state: RouteState) {
    super(state.errorMessage ?? state.errorCode ?? "UNREACHABLE")
    this.name = "RouteError"
    this.code = state.errorCode ?? "UNREACHABLE"
    this.state = state
  }
}

const routeError = (state: RouteState) => new RouteError(state)

/** Addresses for `startDownload`: the route in use first, then the rest in priority order. */
export function orderForTransfer<T extends EngineAddress>(
  addresses: readonly T[],
  active: { host: string; port: number } | undefined
): T[] {
  if (!active) return [...addresses]
  const index = addresses.findIndex(
    (address) =>
      address.host.toLowerCase() === active.host.toLowerCase() && portOf(address) === active.port
  )
  if (index <= 0) return [...addresses]
  return [addresses[index], ...addresses.slice(0, index), ...addresses.slice(index + 1)]
}

/**
 * What native needs to open its own connection (`startDownload`, `streamFile`, `openWebPage`): the
 * pinned GUID (connecting first when it is not pinned yet) and the addresses, route in use first.
 */
export async function nativeRoute(
  engine: Pick<RouteEngine, "knownGuid" | "session" | "getState" | "activeRoute">,
  machine: EngineMachine
): Promise<{ expectedServerGuid: string; addresses: { host: string; port?: number }[] }> {
  let guid = machine.server_guid ?? engine.knownGuid(machine.id)
  if (!guid) {
    await engine.session(machine)
    guid = engine.knownGuid(machine.id)
  }
  if (!guid) throw new RouteError(engine.getState(machine.id))
  const active = engine.activeRoute(machine.id)
  return {
    expectedServerGuid: guid,
    addresses: orderForTransfer(machine.addresses, active).map((a) => ({
      host: a.host,
      ...(a.port == null ? {} : { port: a.port }),
    })),
  }
}

/**
 * The machines as the native mirror keeps them (`syncMachines`, ADR 0010 "Cold start"): id, pinned GUID and the
 * addresses in priority order (port only when set). Pure (tested).
 */
export function nativeMachines(machines: readonly EngineMachine[]): NativeMachine[] {
  return machines.map((m) => ({
    machineId: m.id,
    serverGuid: m.server_guid,
    addresses: m.addresses.map((a) => ({
      host: a.host,
      ...(a.port == null ? {} : { port: a.port }),
    })),
  }))
}
