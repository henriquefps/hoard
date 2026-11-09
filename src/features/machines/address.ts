import { type AddressKind, detectAddressKind } from "@/lib/addressKind"
import type { HoardSmbErrorCode, HoardSmbPlugin, ProbeResult } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"

/**
 * What people paste into a host field: `smb://nas.local/share`, `\\192.168.1.20\Public`,
 * `192.168.1.155:4450`, `[fd7a:115c:a1e0::1]:445`. Returns the bare host (and a port when one was
 * given), or null when nothing usable is left.
 */
export function parseHostInput(raw: string): { host: string; port?: number } | null {
  let text = raw.trim()
  if (!text) return null
  text = text.replace(/^smb:\/\//i, "").replace(/^\\\\/, "")
  text = text.split(/[\\/]/)[0] ?? ""
  if (!text) return null
  const bracketed = /^\[([^\]]+)\](?::(\d+))?$/.exec(text)
  if (bracketed) return withPort(bracketed[1], bracketed[2])
  const colons = text.split(":").length - 1
  if (colons === 1) {
    const [host, port] = text.split(":")
    return withPort(host, port)
  }
  if (/\s/.test(text)) return null
  return { host: text }
}

function withPort(host: string, port: string | undefined) {
  if (!host || /\s/.test(host)) return null
  if (port === undefined || port === "") return { host }
  const value = Number(port)
  return Number.isInteger(value) && value > 0 && value < 65536 ? { host, port: value } : { host }
}

export const isValidPort = (port: number) => Number.isInteger(port) && port > 0 && port < 65536

// --- Test connection --------------------------------------------------------------------------

export interface AddressTestResult {
  host: string
  port: number
  kind: AddressKind
  probe: ProbeResult
  /** Present when a connect was tried (the address answered on the port). */
  connect?: {
    ok: boolean
    code?: HoardSmbErrorCode
    serverName?: string
    serverGuid?: string
    /** `match`: same server as the pinned (or first seen) GUID; `mismatch`: another server. */
    guid?: "match" | "first" | "mismatch"
  }
}

/**
 * The machine form's "Test connection": probe every address in parallel, then connect to each one
 * that answered, in priority order, with the pinned GUID (or, before pinning, the GUID of the first
 * server that accepted the login). Reports every address, unlike the route engine which stops at
 * the first that works. Sessions are closed again; nothing is pinned.
 */
export async function testConnection(
  smb: Pick<HoardSmbPlugin, "probe" | "connect" | "disconnect">,
  options: {
    machineId: string
    addresses: { host: string; port?: number | null }[]
    pinnedGuid: string | null
    timeoutMs: number
  }
): Promise<AddressTestResult[]> {
  const results: AddressTestResult[] = await Promise.all(
    options.addresses.map(async ({ host, port }) => ({
      host,
      port: port || 445,
      kind: detectAddressKind(host),
      probe: await smb
        .probe({ host, port: port || 445, timeoutMs: options.timeoutMs })
        .catch((): ProbeResult => ({ reachable: false, reason: "UNREACHABLE" })),
    }))
  )
  let reference = options.pinnedGuid
  for (const result of results) {
    if (!result.probe.reachable) continue
    try {
      const session = await smb.connect({
        machineId: options.machineId,
        host: result.host,
        port: result.port,
        expectedServerGuid: reference,
      })
      result.connect = {
        ok: true,
        serverName: session.serverName,
        serverGuid: session.serverGuid,
        guid: reference ? "match" : "first",
      }
      reference ??= session.serverGuid
      void smb.disconnect({ sessionId: session.sessionId }).catch(() => {})
    } catch (error) {
      const code = smbErrorCode(error)
      const data = (error as { data?: { serverGuid?: unknown } }).data
      result.connect = {
        ok: false,
        code,
        guid: code === "GUID_MISMATCH" ? "mismatch" : undefined,
        serverGuid: typeof data?.serverGuid === "string" ? data.serverGuid : undefined,
      }
    }
  }
  return results
}
