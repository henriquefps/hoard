import { describe, expect, it } from "vitest"
import { MACHINE_COLORS, MACHINE_ICON_IDS } from "@/components/MachineIcon"
import { FILTERS, SORT_KEYS, type FileKind } from "@/lib/files"
import { HOARD_SMB_ERROR_CODES } from "@/plugins/smb/definitions"
import en from "./locales/en.json"
import ptBR from "./locales/pt-BR.json"

type Tree = { [key: string]: string | Tree }

function flatten(tree: Tree, prefix = ""): string[] {
  return Object.entries(tree).flatMap(([key, value]) =>
    typeof value === "string" ? [`${prefix}${key}`] : flatten(value, `${prefix}${key}.`)
  )
}

const enKeys = new Set(flatten(en as Tree))
const ptKeys = new Set(flatten(ptBR as Tree))
/** A key exists as-is or as a plural form (`_one` / `_other`). */
const has = (key: string) =>
  enKeys.has(key) || (enKeys.has(`${key}_one`) && enKeys.has(`${key}_other`))

// Every app source file as text (Vite's glob import, so no Node APIs are needed).
const SOURCES = import.meta.glob(["../**/*.{ts,tsx}", "!../**/*.test.ts"], {
  query: "?raw",
  import: "default",
  eager: true,
}) as Record<string, string>

describe("i18n", () => {
  it("has the same keys in English and Portuguese", () => {
    expect([...enKeys].filter((k) => !ptKeys.has(k))).toEqual([])
    expect([...ptKeys].filter((k) => !enKeys.has(k))).toEqual([])
  })

  it("defines every literal key the code uses", () => {
    const used = new Set<string>()
    expect(Object.keys(SOURCES).length).toBeGreaterThan(40)
    for (const text of Object.values(SOURCES)) {
      for (const match of text.matchAll(/\bt\(\s*"([a-zA-Z0-9_.-]+)"/g)) used.add(match[1])
      for (const match of text.matchAll(/label: "((?:nav)\.[a-zA-Z]+)"/g)) used.add(match[1])
    }
    expect([...used].filter((key) => !has(key))).toEqual([])
  })

  it("defines every key built from a value", () => {
    const kinds: FileKind[] = [
      "folder",
      "image",
      "video",
      "audio",
      "document",
      "spreadsheet",
      "pdf",
      "archive",
      "code",
      "text",
      "other",
    ]
    const keys = [
      ...HOARD_SMB_ERROR_CODES.flatMap((c) => [
        `errors.${c}.title`,
        `errors.${c}.body`,
        `route.problem.${c}`,
      ]),
      ...["NO_APP", "ACCESS_DENIED", "NOT_FOUND", "UNKNOWN"].map((c) => `browse.openError.${c}`),
      ...kinds.map((k) => `fileKind.${k}`),
      ...FILTERS.map((f) => `browse.filters.${f}`),
      ...SORT_KEYS.flatMap((k) => [
        `browse.sortKeys.${k}`,
        `browse.directions.${k}.asc`,
        `browse.directions.${k}.desc`,
      ]),
      ...["ask", "rename", "overwrite", "skip"].flatMap((c) => [
        `conflict.${c}`,
        `conflict.${c}Hint`,
      ]),
      ...[
        ...["queued", "scanning", "conflict", "running", "retrying", "completed", "failed"],
        ...["cancelled", "skipped", "running_copy", "running_move"],
      ].map((s) => `transfers.states.${s}`),
      ...["copy", "move", "download", "upload"].map((i) => `transfer.title.${i}`),
      ...["copy", "move"].map((op) => `transfer.clipboard.${op}`),
      ...["Copy", "Move"].map((op) => `transfer.started${op}`),
      ...["copyHere", "moveHere", "clipboard.copied", "clipboard.cut"].map((k) => `transfer.${k}`),
      ...["retry", "retryFailed", "copiedTo", "movedTo"].map((k) => `transfers.${k}`),
      ...MACHINE_ICON_IDS.map((i) => `machines.icons.${i}`),
      ...MACHINE_COLORS.map((c) => `machines.colors.${c}`),
      ...["local", "tailscale", "other"].map((k) => `addressKind.${k}`),
      ...["home", "away", "offline"].map((n) => `settings.networks.${n}`),
    ]
    expect(keys.filter((key) => !has(key))).toEqual([])
  })
})
