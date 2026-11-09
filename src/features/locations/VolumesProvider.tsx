import { App } from "@capacitor/app"
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { diffVolumes } from "./ids"
import { HoardSmb } from "@/plugins/smb"
import type { StorageAccessResult, StorageVolumeInfo } from "@/plugins/smb/definitions"

import { type VolumeChange, VolumesContext, type VolumesContextValue } from "./volumesContext"

/**
 * The phone's volumes and the all files access state, kept live: `volumesChanged` from the plugin
 * (USB drive or SD card attached, mounted, ejected, removed) and a refresh when the app comes back
 * to the foreground (the user may have changed the permission in Settings meanwhile).
 */
export function VolumesProvider({ children }: { children: ReactNode }) {
  const [volumes, setVolumes] = useState<StorageVolumeInfo[]>()
  const [access, setAccess] = useState<StorageAccessResult>()
  const [listeners] = useState(
    () => new Set<(change: VolumeChange, volumes: StorageVolumeInfo[]) => void>()
  )

  const last = useRef<StorageVolumeInfo[] | undefined>(undefined)

  /** Every new list: notify attach/detach changes (none for the first list: drives already there). */
  const apply = useCallback(
    (list: StorageVolumeInfo[]) => {
      const previous = last.current
      last.current = list
      setVolumes(list)
      if (!previous) return
      for (const change of diffVolumes(previous, list)) {
        listeners.forEach((listener) => listener(change as VolumeChange, list))
      }
    },
    [listeners]
  )

  const refresh = useCallback(
    () =>
      readVolumes().then(([list, state]) => {
        apply(list)
        setAccess(state)
      }),
    [apply]
  )

  useEffect(() => {
    void readVolumes().then(([list, state]) => {
      apply(list)
      setAccess(state)
    })
    const handles = [
      HoardSmb.addListener("volumesChanged", (event) => {
        apply(event.volumes)
        void HoardSmb.getStorageAccess().then(setAccess, () => {})
      }),
      App.addListener("resume", () => void refresh()),
      App.addListener("appStateChange", ({ isActive }) => isActive && void refresh()),
    ]
    return () => handles.forEach((handle) => void handle.then((h) => h.remove()).catch(() => {}))
  }, [refresh, apply])

  const requestAccess = useCallback(async () => {
    const state = await HoardSmb.requestStorageAccess()
    setAccess(state)
    await refresh()
    return state
  }, [refresh])

  const onChange = useCallback<VolumesContextValue["onChange"]>(
    (listener) => {
      listeners.add(listener)
      return () => void listeners.delete(listener)
    },
    [listeners]
  )

  const value = useMemo(
    () => ({ volumes, access, refresh, requestAccess, onChange }),
    [volumes, access, refresh, requestAccess, onChange]
  )
  return <VolumesContext.Provider value={value}>{children}</VolumesContext.Provider>
}

function readVolumes() {
  return Promise.all([
    HoardSmb.listVolumes().then(
      (result) => result.volumes,
      () => [] as StorageVolumeInfo[]
    ),
    HoardSmb.getStorageAccess().catch((): StorageAccessResult => ({
      granted: false,
      mechanism: "allFiles",
    })),
  ])
}
