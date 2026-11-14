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

export const previewUrl = (id: string, size: "thumb" | "screen", v?: string, format: "webp" | "jpeg" = "webp") =>
  `/web/items/${id}/preview?size=${size}&format=${format}${v ? `&v=${v}` : ""}`

export const proxyUrl = (id: string) => `/web/items/${id}/proxy`

export const fileUrl = (id: string, fingerprint: string, download = false) =>
  `/web/items/${id}/files/${encodeURIComponent(fingerprint)}${download ? "?download=1" : ""}`
