/** The gallery's API (/web/, ADR 0018). Writes carry X-Hoard-Web: 1 (CSRF); the session is an HttpOnly cookie. */

export class ApiError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

/** Called on 401 so the app can show the sign-in page. */
let onUnauthorized: () => void = () => {}
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const { json, ...rest } = init
  const headers = new Headers(rest.headers)
  if (rest.method && rest.method !== "GET") headers.set("X-Hoard-Web", "1")
  if (json !== undefined) headers.set("Content-Type", "application/json")
  const res = await fetch(path, {
    ...rest,
    headers,
    body: json !== undefined ? JSON.stringify(json) : rest.body,
    credentials: "same-origin",
  })
  if (!res.ok) {
    let code = "http_" + res.status
    let message = res.statusText
    try {
      const body = (await res.json()) as { error?: { code: string; message: string } }
      if (body.error) ({ code, message } = body.error)
    } catch {
      // not JSON
    }
    if (res.status === 401 && !path.startsWith("/web/session")) onUnauthorized()
    throw new ApiError(res.status, code, message)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export interface Session {
  signedIn: boolean
  setup: boolean
  version: string
  writer: string
  previews: boolean
}

/** An item's preview, or with `edit` (a version id) the preview of Reveal's edit of that version (ADR 0020). */
export const previewUrl = (id: string, size: "thumb" | "screen", v?: string, format: "webp" | "jpeg" = "webp", edit?: string) =>
  `/web/items/${id}/preview?size=${size}&format=${format}${edit ? `&edit=${edit}` : ""}${v ? `&v=${v}` : ""}`

/** Reveal's uploaded edit of a version, as stored (the viewer's "Download edited JPEG"); the URL ends in its name. */
export const editFileUrl = (id: string, version: string, name: string, download = false) =>
  `/web/items/${id}/edits/${version}/${encodeURIComponent(name)}${download ? "?download=1" : ""}`

export const proxyUrl = (id: string) => `/web/items/${id}/proxy`

/**
 * One member file. The URL ends in the file's name (the server ignores it): a browser that names a download by its
 * URL (a blocked download, "Save link as") keeps the name and extension.
 */
export const fileUrl = (id: string, fingerprint: string, download = false, name?: string) =>
  `/web/items/${id}/files/${encodeURIComponent(fingerprint)}${name ? `/${encodeURIComponent(name)}` : ""}${download ? "?download=1" : ""}`

/** The still at full resolution (the original, or a JPEG the server makes from a RAW or HEIF; 202 while it does). */
export const fullUrl = (id: string) => `/web/items/${id}/full`

/**
 * Chrome blocks downloads from a plain-HTTP page that is not on this computer ("Insecure download blocked"). The
 * gallery says so, and points at its HTTPS address (tailscale serve, ADR 0018).
 */
export function insecureDownloads(loc: { protocol: string; hostname: string }): boolean {
  if (loc.protocol !== "http:") return false
  const h = loc.hostname.replace(/^\[|\]$/g, "")
  return !(h === "localhost" || h.endsWith(".localhost") || /^127\./.test(h) || h === "::1")
}
