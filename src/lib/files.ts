import type { SmbEntry } from "@/plugins/smb/definitions"

/** What a file is, from its extension; drives the icon, the filter and thumbnails. */
export type FileKind =
  | "folder"
  | "image"
  | "video"
  | "audio"
  | "document"
  | "spreadsheet"
  | "pdf"
  | "archive"
  | "code"
  | "text"
  | "other"

const KINDS: Record<Exclude<FileKind, "folder" | "other">, string[]> = {
  image: [
    "jpg",
    "jpeg",
    "png",
    "webp",
    "gif",
    "heic",
    "heif",
    "bmp",
    "tif",
    "tiff",
    "svg",
    "raw",
    "dng",
    "avif",
  ],
  video: ["mp4", "mkv", "mov", "avi", "webm", "m4v", "wmv", "ts", "m2ts", "mpg", "mpeg", "3gp"],
  audio: ["mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac", "aiff"],
  pdf: ["pdf"],
  document: ["doc", "docx", "odt", "rtf", "pages", "epub", "ppt", "pptx", "odp", "key"],
  spreadsheet: ["xls", "xlsx", "ods", "csv", "numbers"],
  archive: ["zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "dmg", "apk"],
  code: [
    "js",
    "ts",
    "tsx",
    "jsx",
    "json",
    "py",
    "kt",
    "java",
    "sh",
    "c",
    "cpp",
    "h",
    "rs",
    "go",
    "html",
    "css",
    "yml",
    "yaml",
    "toml",
    "xml",
    "sql",
  ],
  text: ["txt", "md", "log", "srt", "ini", "conf", "nfo", "bashrc"],
}

const BY_EXTENSION = new Map<string, FileKind>(
  Object.entries(KINDS).flatMap(([kind, extensions]) =>
    extensions.map((extension) => [extension, kind as FileKind])
  )
)

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf(".")
  return dot >= 0 && dot < name.length - 1 ? name.slice(dot + 1).toLowerCase() : ""
}

export function fileKind(entry: Pick<SmbEntry, "name" | "isDirectory">): FileKind {
  if (entry.isDirectory) return "folder"
  return BY_EXTENSION.get(extensionOf(entry.name)) ?? "other"
}

/** Image types the native side can thumbnail (videos too natively, but the mock only does images). */
export const THUMBNAIL_EXTENSIONS = new Set(["jpg", "jpeg", "png", "webp", "gif", "heic", "bmp"])

export const hasThumbnail = (entry: Pick<SmbEntry, "name" | "isDirectory">) =>
  !entry.isDirectory && THUMBNAIL_EXTENSIONS.has(extensionOf(entry.name))

// --- Filter, search, sort ---------------------------------------------------------------------

export const FILTERS = [
  "all",
  "folders",
  "images",
  "videos",
  "audio",
  "documents",
  "archives",
] as const
export type FileFilter = (typeof FILTERS)[number]

export const isFileFilter = (value: unknown): value is FileFilter =>
  FILTERS.includes(value as FileFilter)

const FILTER_KINDS: Record<FileFilter, FileKind[] | null> = {
  all: null,
  folders: ["folder"],
  images: ["image"],
  videos: ["video"],
  audio: ["audio"],
  documents: ["document", "pdf", "spreadsheet", "text"],
  archives: ["archive"],
}

export const SORT_KEYS = ["name", "size", "modified"] as const
export type SortKey = (typeof SORT_KEYS)[number]
export type SortDirection = "asc" | "desc"
export interface SortOrder {
  key: SortKey
  direction: SortDirection
}
export const DEFAULT_SORT: SortOrder = { key: "name", direction: "asc" }

export const isSortOrder = (value: unknown): value is SortOrder =>
  typeof value === "object" &&
  value !== null &&
  SORT_KEYS.includes((value as SortOrder).key) &&
  ["asc", "desc"].includes((value as SortOrder).direction)

/** Case-insensitive, accent-insensitive "contains" (search inside the folder). */
export function normalizeForSearch(text: string): string {
  return text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
}

export interface ViewOptions {
  filter: FileFilter
  query: string
  showHidden: boolean
  sort: SortOrder
  /** Folders before files whatever the sort (default true). */
  foldersFirst?: boolean
}

/** Filter, search and sort a folder listing (what the browser shows). */
export function viewEntries(entries: readonly SmbEntry[], options: ViewOptions, locale: string) {
  const kinds = FILTER_KINDS[options.filter]
  const query = normalizeForSearch(options.query.trim())
  const collator = new Intl.Collator(locale, { numeric: true, sensitivity: "base" })
  const direction = options.sort.direction === "asc" ? 1 : -1
  const foldersFirst = options.foldersFirst ?? true
  const time = (entry: SmbEntry) => (entry.modifiedAt ? Date.parse(entry.modifiedAt) : 0)
  return entries
    .filter((entry) => options.showHidden || !entry.isHidden)
    .filter((entry) => !kinds || kinds.includes(fileKind(entry)))
    .filter((entry) => !query || normalizeForSearch(entry.name).includes(query))
    .sort((a, b) => {
      if (foldersFirst && a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1
      let order = 0
      if (options.sort.key === "size") order = a.size - b.size
      else if (options.sort.key === "modified") order = time(a) - time(b)
      return direction * (order || collator.compare(a.name, b.name))
    })
}

// --- Paths -----------------------------------------------------------------------------------

export const splitPath = (path: string) => path.split("/").filter(Boolean)

export const joinPath = (folder: string, name: string) =>
  [...splitPath(folder), name].filter(Boolean).join("/")

/** `a/b/c` → `a/b`; `a` → `""`; `""` → null (already at the share root). */
export function parentPath(path: string): string | null {
  const segments = splitPath(path)
  if (segments.length === 0) return null
  return segments.slice(0, -1).join("/")
}

/** A UNC-style path for "Copy path": `\\host\share\folder\file`. */
export function uncPath(host: string, share: string, path: string): string {
  return `\\\\${host}\\${[share, ...splitPath(path)].join("\\")}`
}

/** A file or folder name the server will accept (no separators, not empty, not `.`/`..`). */
export function isValidName(name: string): boolean {
  const trimmed = name.trim()
  return (
    trimmed.length > 0 &&
    trimmed.length <= 255 &&
    trimmed !== "." &&
    trimmed !== ".." &&
    !/[\\/:*?"<>|]/.test(trimmed)
  )
}
