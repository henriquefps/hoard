/**
 * Where a link or image reference inside a Markdown file points, relative to that file on the
 * share. Relative references resolve like a browser would (`./`, `../`, `/` = share root); a
 * reference that climbs above the share root, or uses another scheme, is never followed.
 */
export type LinkTarget =
  /** http(s) and mailto: open outside the app. */
  | { kind: "external"; url: string }
  /** `#heading` in the same document. */
  | { kind: "anchor"; id: string }
  /** A file or folder on the same share (path without leading slash, `""` = root). */
  | { kind: "share"; path: string; hash?: string; isFolder: boolean }
  /** `data:` image (allowed for images only). */
  | { kind: "data"; url: string }
  /** Anything else (javascript:, file:, above the share root, malformed). */
  | { kind: "blocked" }

const EXTERNAL = /^(https?:|mailto:)/i
const SCHEME = /^[a-z][a-z0-9+.-]*:/i

/** Resolve `href` found in the Markdown file at `filePath` (share-relative). */
export function resolveLink(filePath: string, href: string): LinkTarget {
  const raw = href.trim()
  if (!raw) return { kind: "blocked" }
  if (EXTERNAL.test(raw)) return { kind: "external", url: raw }
  if (/^data:image\//i.test(raw)) return { kind: "data", url: raw }
  if (SCHEME.test(raw) || raw.startsWith("//")) return { kind: "blocked" }
  if (raw.startsWith("#")) return { kind: "anchor", id: safeDecode(raw.slice(1)) }

  const hashAt = raw.indexOf("#")
  const hash = hashAt >= 0 ? safeDecode(raw.slice(hashAt + 1)) : undefined
  const withoutHash = hashAt >= 0 ? raw.slice(0, hashAt) : raw
  const queryAt = withoutHash.indexOf("?")
  const pathPart = queryAt >= 0 ? withoutHash.slice(0, queryAt) : withoutHash
  const isFolder = pathPart.endsWith("/")

  const base = pathPart.startsWith("/") ? [] : filePath.split("/").filter(Boolean).slice(0, -1)
  const segments = [...base]
  for (const segment of pathPart.split("/")) {
    const name = safeDecode(segment)
    if (name === "" || name === ".") continue
    if (name === "..") {
      if (segments.length === 0) return { kind: "blocked" }
      segments.pop()
    } else if (name.includes("\\")) {
      return { kind: "blocked" }
    } else segments.push(name)
  }
  return { kind: "share", path: segments.join("/"), hash: hash || undefined, isFolder }
}

function safeDecode(text: string): string {
  try {
    return decodeURIComponent(text)
  } catch {
    return text
  }
}
