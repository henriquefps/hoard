import type { ProbeResult } from "@/plugins/smb/definitions"

export interface RouteCandidate {
  host: string
  port?: number
}

export interface RouteOutcome<T extends RouteCandidate> {
  /** The first reachable address in priority order, or null when none answered. */
  winner: { address: T; latencyMs?: number } | null
  /** Every probe answer that arrived before the decision (index-aligned, undefined = still pending). */
  results: (ProbeResult | undefined)[]
}

/**
 * Probe every address in parallel and pick the first reachable one in priority order (ADR 0002).
 *
 * Unlike home-apps, which always waits for every probe, this decides as soon as the outcome is
 * certain: address `i` wins the moment it answers reachable and every higher-priority address
 * `j < i` has already answered unreachable. So a reachable first address wins in its own RTT, and a
 * slow, dead Tailscale probe never delays a working local one.
 *
 * Only TCP reachability is decided here. The caller then connects with `expectedServerGuid`; a
 * `GUID_MISMATCH` means "not this machine" and the caller moves on to the next reachable address.
 */
export function pickRoute<T extends RouteCandidate>(
  addresses: readonly T[],
  probe: (address: T) => Promise<ProbeResult>
): Promise<RouteOutcome<T>> {
  return new Promise((resolve) => {
    const results: (ProbeResult | undefined)[] = addresses.map(() => undefined)
    let settled = false

    const decide = () => {
      if (settled) return
      for (let i = 0; i < addresses.length; i++) {
        const result = results[i]
        if (result === undefined) return // a higher-priority address could still win
        if (result.reachable) {
          settled = true
          resolve({
            winner: { address: addresses[i], latencyMs: result.latencyMs },
            results: [...results],
          })
          return
        }
      }
      settled = true
      resolve({ winner: null, results: [...results] })
    }

    if (addresses.length === 0) {
      decide()
      return
    }
    addresses.forEach((address, index) => {
      probe(address)
        .catch((): ProbeResult => ({ reachable: false, reason: "UNREACHABLE" }))
        .then((result) => {
          results[index] = result
          decide()
        })
    })
  })
}
