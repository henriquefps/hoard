import { useCallback } from "react"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import { getSetting, setSetting } from "@/db/queries"
import type { SettingKey } from "@/db/types"
import { DEFAULT_SORT, isSortOrder, type SortOrder } from "@/lib/files"
import type { ConflictPolicy } from "@/plugins/smb/definitions"

/** App settings kept in the `settings` table, with their defaults and validation. */
export interface Settings {
  showHiddenFiles: boolean
  defaultSort: SortOrder
  defaultConflict: ConflictPolicy
  lastDestination: string | null
  probeTimeoutMs: number
  onboarded: boolean
}

export const CONFLICT_POLICIES: ConflictPolicy[] = ["rename", "overwrite", "skip"]
export const PROBE_TIMEOUTS = [800, 1500, 3000, 5000] as const

export const SETTING_DEFAULTS: Settings = {
  showHiddenFiles: false,
  defaultSort: DEFAULT_SORT,
  defaultConflict: "rename",
  lastDestination: null,
  probeTimeoutMs: 1500,
  onboarded: false,
}

const VALIDATORS: { [K in keyof Settings]: (value: unknown) => boolean } = {
  showHiddenFiles: (v) => typeof v === "boolean",
  defaultSort: isSortOrder,
  defaultConflict: (v) => CONFLICT_POLICIES.includes(v as ConflictPolicy),
  lastDestination: (v) => v === null || typeof v === "string",
  probeTimeoutMs: (v) => typeof v === "number" && v >= 200 && v <= 30_000,
  onboarded: (v) => typeof v === "boolean",
}

export async function readSetting<K extends keyof Settings>(
  db: Parameters<typeof getSetting>[0],
  key: K
): Promise<Settings[K]> {
  const value = await getSetting<unknown>(db, key as SettingKey, SETTING_DEFAULTS[key])
  return VALIDATORS[key](value) ? (value as Settings[K]) : SETTING_DEFAULTS[key]
}

/**
 * A setting and its setter. The value is undefined only while the first read is in flight (a few
 * ms); `useSetting(key)[0] ?? default` when a value is needed right away.
 */
export function useSetting<K extends keyof Settings>(
  key: K
): [Settings[K] | undefined, (value: Settings[K]) => Promise<void>] {
  const db = useDb()
  const value = useLiveQuery((d) => readSetting(d, key), [key])
  const set = useCallback((next: Settings[K]) => setSetting(db, key as SettingKey, next), [db, key])
  return [value, set]
}
