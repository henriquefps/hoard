import { useCallback } from "react"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import { getSetting, setSetting } from "@/db/queries"
import type { SettingKey } from "@/db/types"
import { DEFAULT_SORT, isSortOrder, type SortOrder } from "@/lib/files"
import type { RecentDestination } from "@/db/types"
import type { TransferConflict } from "@/plugins/smb/definitions"
import { DEFAULT_ASSIST, type Sensitivity } from "@/features/cull/assist"

/** App settings kept in the `settings` table, with their defaults and validation. */
export interface Settings {
  showHiddenFiles: boolean
  defaultSort: SortOrder
  /** What happens when a name exists; `ask` shows one dialog with "apply to all". */
  defaultConflict: TransferConflict
  /** The destination picker's "Recent" (newest first, at most {@link MAX_RECENT_DESTINATIONS}). */
  recentDestinations: RecentDestination[]
  probeTimeoutMs: number
  onboarded: boolean
  /** The all files access screen was shown once (granted or "Not now"); ADR 0005. */
  storageIntroSeen: boolean
  /** The culling viewer moves to the next shot after a pick or reject (ADR 0008). */
  cullAutoAdvance: boolean
  /** How readily a shot is called blurry (ADR 0009). */
  cullBlurSensitivity: Sensitivity
  /** How readily eyes are called closed (ADR 0009). */
  cullEyesSensitivity: Sensitivity
  /** Frames at most this far apart start a burst (ADR 0009). */
  cullBurstGapMs: number
  /**
   * Culling decisions are written to XMP sidecars next to the photos in folders Hoard can write, and "Copy picks"
   * writes them at the destination (ADR 0011); on by default. Replaces 1.6-1.8's `cullXmpSidecars`.
   */
  cullSaveXmp: boolean
  /** New sidecars are hidden files (`.<stem>.xmp`): Reveal and digiKam read them, Lightroom does not. */
  cullXmpHidden: boolean
  /**
   * The viewer's vertical swipes pick (up) and reject (down) instead of opening the details sheet
   * (ADR 0008, 1.7.0: off by default, swipe up shows the details like the phone's gallery).
   */
  cullSwipeDecide: boolean
  /** "Edit in Reveal" on SMB photos stopped explaining that they stream through Hoard (ADR 0010). */
  revealStreamNoteSeen: boolean
}

const SENSITIVITIES: Sensitivity[] = ["low", "medium", "high"]

export const CONFLICT_POLICIES: TransferConflict[] = ["ask", "rename", "overwrite", "skip"]
export const MAX_RECENT_DESTINATIONS = 6
export const PROBE_TIMEOUTS = [800, 1500, 3000, 5000] as const

export const SETTING_DEFAULTS: Settings = {
  showHiddenFiles: false,
  defaultSort: DEFAULT_SORT,
  defaultConflict: "ask",
  recentDestinations: [],
  probeTimeoutMs: 1500,
  onboarded: false,
  storageIntroSeen: false,
  cullAutoAdvance: true,
  cullBlurSensitivity: DEFAULT_ASSIST.blur,
  cullEyesSensitivity: DEFAULT_ASSIST.eyes,
  cullBurstGapMs: DEFAULT_ASSIST.burstGapMs,
  cullSaveXmp: true,
  cullXmpHidden: false,
  cullSwipeDecide: false,
  revealStreamNoteSeen: false,
}

const VALIDATORS: { [K in keyof Settings]: (value: unknown) => boolean } = {
  showHiddenFiles: (v) => typeof v === "boolean",
  defaultSort: isSortOrder,
  defaultConflict: (v) => CONFLICT_POLICIES.includes(v as TransferConflict),
  recentDestinations: (v) =>
    Array.isArray(v) &&
    v.every(
      (d) =>
        typeof d === "object" &&
        d !== null &&
        typeof (d as RecentDestination).locationId === "string" &&
        typeof (d as RecentDestination).path === "string" &&
        typeof (d as RecentDestination).label === "string"
    ),
  probeTimeoutMs: (v) => typeof v === "number" && v >= 200 && v <= 30_000,
  onboarded: (v) => typeof v === "boolean",
  storageIntroSeen: (v) => typeof v === "boolean",
  cullAutoAdvance: (v) => typeof v === "boolean",
  cullBlurSensitivity: (v) => SENSITIVITIES.includes(v as Sensitivity),
  cullEyesSensitivity: (v) => SENSITIVITIES.includes(v as Sensitivity),
  cullBurstGapMs: (v) => typeof v === "number" && v >= 100 && v <= 10_000,
  cullSaveXmp: (v) => typeof v === "boolean",
  cullXmpHidden: (v) => typeof v === "boolean",
  cullSwipeDecide: (v) => typeof v === "boolean",
  revealStreamNoteSeen: (v) => typeof v === "boolean",
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
