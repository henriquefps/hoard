# ADR 0002: Machines with addresses and automatic route detection

- **Date**: 2026-11-08
- **Status**: Accepted

## Context

The same computer is reachable in different ways depending on where the phone is: its LAN IP at
home (`192.168.1.155`), its Tailscale IP (`100.101.12.34`) or MagicDNS name (`cachyos-fps`,
`cachyos-fps.tail1234.ts.net`) elsewhere. Users should not pick a route by hand, and shares,
favorites and credentials should not be duplicated per address. The `home-apps` project solved
the same problem for HTTP URLs (its ADR 0003: probe every URL in parallel, take the first in
priority order).

## Decision

**Model.** A _machine_ (name, icon, color, SMB username, optional domain, pinned server GUID) has an
**ordered list of addresses** (host, port 445 by default, kind, optional label). Credentials,
shares and favorites belong to the machine, never to an address.

**Address kind is detected from the host** (`src/lib/addressKind.ts`), never asked:

| Kind        | Rule                                                                                                                                                       |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tailscale` | IPv4 in `100.64.0.0/10`, IPv6 in `fd7a:115c:a1e0::/48`, or a name ending in `.ts.net`                                                                      |
| `local`     | RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254/16`, `fe80::/10`), IPv6 ULA, loopback, names ending in `.local`, `.lan` or `.home.arpa` |
| `other`     | everything else, including bare names such as `cachyos-fps` (could be MagicDNS or a LAN name)                                                              |

Default order is local, then Tailscale, then other (`sortByKind`, stable within a kind); the user
can reorder. The UI always shows which route is in use.

**Route detection** (`src/lib/pickRoute.ts`):

1. Probe every address **in parallel** with a native TCP connect to the SMB port
   (`HoardSmb.probe`), not `fetch` (SMB is not HTTP, and a WebView fetch cannot reach port 445).
2. Decide **as soon as the result is certain**: address _i_ wins the moment it answers reachable and
   every higher-priority address has already answered unreachable. A reachable LAN address wins in
   its own round-trip time; a dead Tailscale address never delays it. (home-apps always waited for
   the slowest probe.)
3. Recheck when the app returns to the foreground (`@capacitor/app` `appStateChange`) and when the
   plugin emits `networkChanged` (Wi-Fi joined/left, VPN up/down).

**GUID pinning.** SMB2 NEGOTIATE returns a server GUID before any authentication. On the first
successful connect the app stores it in `machines.server_guid`. Every later `connect` and
`startDownload` passes `expectedServerGuid`; the native side compares it right after NEGOTIATE and,
on mismatch, rejects `GUID_MISMATCH` **before SESSION_SETUP**, so the password is never sent to a
server that is not the saved machine. The app then treats that address as unreachable and tries the
next one. This protects against a `192.168.1.x` on someone else's Wi-Fi that happens to run SMB.
Re-pinning (the server was reinstalled) is an explicit user action that clears the GUID.

**Transfers** get the whole address list in priority order; native retries a dropped connection on
the same address, then fails over to the next (each one checked against the GUID), resuming from
the byte offset already written.

## Consequences

- A TCP connect only proves something listens on 445; the GUID check at connect time is what proves
  it is the right machine. Both are needed.
- A bare MagicDNS name sorts as `other`; users who want it before the Tailscale IP reorder it.
- Servers that randomize their GUID per boot (rare; some appliances) would need re-pinning; the UI
  should offer it with a clear explanation when `GUID_MISMATCH` comes back on every address.
