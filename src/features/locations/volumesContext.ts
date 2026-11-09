import { createContext, useContext } from "react"
import type {
  StorageAccessResult,
  StorageVolumeInfo,
  VolumesChangedEvent,
} from "@/plugins/smb/definitions"

export type VolumeChange = NonNullable<VolumesChangedEvent["change"]>

export interface VolumesContextValue {
  /** Primary storage first, then removable volumes; undefined until the first answer. */
  volumes: StorageVolumeInfo[] | undefined
  access: StorageAccessResult | undefined
  refresh: () => Promise<void>
  /** Ask for all files access (Settings on Android 11+); resolves with the new state. */
  requestAccess: () => Promise<StorageAccessResult>
  /** Subscribe to attach/detach changes (the notice toast, leaving a browser of an unplugged drive). */
  onChange: (listener: (change: VolumeChange, volumes: StorageVolumeInfo[]) => void) => () => void
}

export const VolumesContext = createContext<VolumesContextValue | null>(null)

/** Volumes and all files access, kept live by `VolumesProvider`. */
export function useVolumes(): VolumesContextValue {
  const value = useContext(VolumesContext)
  if (!value) throw new Error("useVolumes must be used inside <VolumesProvider>")
  return value
}

/** The volume of a local location id's uuid (null = the primary storage). */
export function findVolume(volumes: StorageVolumeInfo[] | undefined, uuid: string | null) {
  return volumes?.find((v) => (uuid === null ? v.primary : v.uuid === uuid))
}
