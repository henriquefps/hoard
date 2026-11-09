/**
 * Address kind detection (ADR 0002). The kind is derived from the host, never asked from the user:
 *  - `tailscale`: 100.64.0.0/10 (CGNAT range Tailscale uses), `*.ts.net`, or a bare single-label name
 *    is NOT assumed to be MagicDNS (it could be a LAN name), so it is `other` unless it ends in `.ts.net`.
 *  - `local`: RFC 1918 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), IPv6 ULA
 *    (fc00::/7), loopback, and `.local` / `.lan` / `.home.arpa` names.
 *  - `other`: everything else (public IPs, public DNS names, bare hostnames).
 */
export type AddressKind = "local" | "tailscale" | "other"

/** Default route priority: local first, then Tailscale, then anything else. */
export const KIND_PRIORITY: Record<AddressKind, number> = { local: 0, tailscale: 1, other: 2 }

function parseIpv4(host: string): number[] | null {
  const parts = host.split(".")
  if (parts.length !== 4) return null
  const octets = parts.map((p) => (/^\d{1,3}$/.test(p) ? Number(p) : NaN))
  return octets.every((o) => o >= 0 && o <= 255) ? octets : null
}

export function detectAddressKind(rawHost: string): AddressKind {
  const host = rawHost
    .trim()
    .toLowerCase()
    .replace(/^\[|\]$/g, "")
    .replace(/\.$/, "")
  const v4 = parseIpv4(host)
  if (v4) {
    const [a, b] = v4
    if (a === 100 && b >= 64 && b <= 127) return "tailscale"
    if (a === 10 || a === 127) return "local"
    if (a === 172 && b >= 16 && b <= 31) return "local"
    if (a === 192 && b === 168) return "local"
    if (a === 169 && b === 254) return "local"
    return "other"
  }
  if (host.includes(":")) {
    // Tailscale's IPv6 range is fd7a:115c:a1e0::/48 (inside ULA): check it first.
    if (host.startsWith("fd7a:115c:a1e0:")) return "tailscale"
    if (host === "::1" || /^fe[89ab]/.test(host) || /^f[cd]/.test(host)) return "local"
    return "other"
  }
  if (host === "ts.net" || host.endsWith(".ts.net")) return "tailscale"
  if (host === "localhost" || /\.(local|lan|home\.arpa)$/.test(host)) return "local"
  return "other"
}

/**
 * Sort addresses into the default priority order (stable inside a kind, so the user's order among
 * e.g. two local addresses is kept).
 */
export function sortByKind<T extends { host: string }>(addresses: readonly T[]): T[] {
  return addresses
    .map((address, index) => ({
      address,
      index,
      rank: KIND_PRIORITY[detectAddressKind(address.host)],
    }))
    .sort((x, y) => x.rank - y.rank || x.index - y.index)
    .map(({ address }) => address)
}
