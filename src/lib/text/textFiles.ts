import { extensionOf } from "@/lib/files"
import { TEXT_FILE_MAX_BYTES } from "@/plugins/smb/definitions"

/** Extensions the built-in viewer opens on tap (plain text, config, data and source code). */
const TEXT_EXTENSIONS = new Set([
  ...["txt", "text", "md", "markdown", "mdown", "mkd", "log", "out", "nfo", "srt", "vtt"],
  ...["json", "jsonc", "json5", "ndjson", "geojson", "yaml", "yml", "toml", "xml", "plist"],
  ...["csv", "tsv", "ini", "cfg", "conf", "config", "properties", "env", "editorconfig"],
  ...["sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "py", "rb", "pl", "php", "lua", "r"],
  ...["js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts", "vue", "svelte", "astro"],
  ...["kt", "kts", "java", "gradle", "groovy", "scala", "swift", "go", "rs", "c", "h", "cpp"],
  ...["hpp", "cc", "cs", "m", "mm", "dart", "zig", "nim", "ex", "exs", "erl", "hs", "clj"],
  ...["html", "htm", "css", "scss", "sass", "less", "svg", "sql", "graphql", "gql", "proto"],
  ...["tex", "bib", "rst", "adoc", "org", "diff", "patch", "gitignore", "gitattributes"],
  ...["dockerfile", "makefile", "cmake", "nix", "tf", "hcl", "lock", "service", "rules"],
  ...["bashrc", "zshrc", "profile", "vimrc", "npmrc", "nvmrc", "htaccess", "m3u", "m3u8", "cue"],
])

/** Extension-less names that are text by convention. */
const TEXT_NAMES = new Set([
  ...["readme", "license", "licence", "copying", "authors", "changelog", "notice", "todo"],
  ...["makefile", "dockerfile", "containerfile", "vagrantfile", "gemfile", "procfile"],
  ...["hosts", "fstab", "crontab", "passwd", "group", "exports", "smb.conf"],
])

export const MARKDOWN_EXTENSIONS = new Set(["md", "markdown", "mdown", "mkd"])

/** Whether the viewer handles this file (by name; content may still turn out binary). */
export function isTextFile(name: string): boolean {
  const ext = extensionOf(name)
  if (ext && TEXT_EXTENSIONS.has(ext)) return true
  const lower = name.toLowerCase()
  return TEXT_NAMES.has(lower) || TEXT_NAMES.has(lower.replace(/^\./, ""))
}

/** Text files whose tap does something else: pages open in the web viewer, SVG is an image. */
const TAP_ELSEWHERE = new Set(["html", "htm", "svg"])

/**
 * Tapping a file opens the viewer only for text files up to the size the viewer loads whole
 * (pages and SVG keep their own viewers; the row menu still offers "Open in Hoard" for source).
 */
export const opensInViewer = (entry: { name: string; size: number; isDirectory: boolean }) =>
  !entry.isDirectory &&
  isTextFile(entry.name) &&
  !TAP_ELSEWHERE.has(extensionOf(entry.name)) &&
  entry.size <= TEXT_FILE_MAX_BYTES

export type TextFormat = "markdown" | "json" | "csv" | "tsv" | "plain"

export function textFormat(name: string): TextFormat {
  const ext = extensionOf(name)
  if (MARKDOWN_EXTENSIONS.has(ext)) return "markdown"
  if (ext === "json" || ext === "geojson") return "json"
  if (ext === "csv") return "csv"
  if (ext === "tsv") return "tsv"
  return "plain"
}

/** Prose files soft-wrap by default; source code does not (see `prefs.ts`). */
export function isProse(name: string): boolean {
  const ext = extensionOf(name)
  return textFormat(name) !== "plain" && textFormat(name) !== "json"
    ? true
    : ["txt", "text", "log", "out", "nfo", "srt", "vtt", "rst", "adoc", "org", ""].includes(ext)
}

// --- Line endings ----------------------------------------------------------------------------

export type LineEnding = "lf" | "crlf" | "cr"

/** The file's line ending: the most common one (LF for a single-line or empty file). */
export function detectLineEnding(text: string): LineEnding {
  let crlf = 0
  let lf = 0
  let cr = 0
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    if (c === 13) {
      if (text.charCodeAt(i + 1) === 10) {
        crlf++
        i++
      } else cr++
    } else if (c === 10) lf++
  }
  if (crlf > lf && crlf >= cr) return "crlf"
  if (cr > lf && cr > crlf) return "cr"
  return "lf"
}

export const LINE_SEPARATORS: Record<LineEnding, string> = { lf: "\n", crlf: "\r\n", cr: "\r" }

/** Join editor lines (always `\n` inside the editor) with the file's own line ending. */
export function applyLineEnding(text: string, ending: LineEnding): string {
  const normalized = text.replace(/\r\n?/g, "\n")
  return ending === "lf" ? normalized : normalized.replace(/\n/g, LINE_SEPARATORS[ending])
}

/** Count lines quickly (no split): what the viewer shows and the worst-case guard uses. */
export function countLines(text: string): number {
  let lines = 1
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    if (c === 10) lines++
    else if (c === 13 && text.charCodeAt(i + 1) !== 10) lines++
  }
  return lines
}

/** Length of the longest line (minified JSON, a log without newlines). */
export function longestLine(text: string): number {
  let longest = 0
  let start = 0
  for (let i = 0; i <= text.length; i++) {
    const c = i < text.length ? text.charCodeAt(i) : 10
    if (c === 10 || c === 13) {
      longest = Math.max(longest, i - start)
      start = i + 1
    }
  }
  return longest
}

/**
 * Above these the rendered views (Markdown preview, CSV table, pretty JSON) are not built by
 * default: the source view (CodeMirror, which only draws what is on screen) opens instead.
 */
export const RENDER_LIMITS = { bytes: 1024 * 1024, csvRows: 5000 }

/** Pretty-print JSON for the view toggle; null when it does not parse. */
export function prettyJson(text: string): string | null {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return null
  }
}

// --- CSV --------------------------------------------------------------------------------------

/** RFC 4180 CSV (quoted fields, doubled quotes, newlines inside quotes); stops after `maxRows`. */
export function parseDelimited(text: string, delimiter = ",", maxRows = Infinity): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ""
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"'
          i++
        } else quoted = false
      } else field += c
      continue
    }
    if (c === '"' && field === "") quoted = true
    else if (c === delimiter) {
      row.push(field)
      field = ""
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++
      row.push(field)
      rows.push(row)
      row = []
      field = ""
      if (rows.length >= maxRows) return rows
    } else field += c
  }
  if (field !== "" || row.length > 0) {
    row.push(field)
    rows.push(row)
  }
  return rows
}

/** `notes.md` → `notes (1).md`, `notes (1).md` → `notes (2).md` (save as copy, new file). */
export function nextFreeName(name: string, taken: (candidate: string) => boolean): string {
  const dot = name.lastIndexOf(".")
  const hasExt = dot > 0
  const base = (hasExt ? name.slice(0, dot) : name).replace(/ \(\d+\)$/, "")
  const ext = hasExt ? name.slice(dot) : ""
  for (let n = 1; n < 1000; n++) {
    const candidate = `${base} (${n})${ext}`
    if (!taken(candidate)) return candidate
  }
  return `${base} (${Date.now()})${ext}`
}
