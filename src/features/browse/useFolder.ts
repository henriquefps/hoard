import { useCallback, useEffect, useRef, useState } from "react"
import { RouteError, type AddressAttempt } from "@/features/route/engine"
import { useRouteEngine } from "@/features/route/RouteProvider"
import type { BrowseLocation } from "@/features/locations/useBrowseLocation"
import { HoardSmb } from "@/plugins/smb"
import type { HoardSmbErrorCode, SmbAccess, SmbEntry, SmbShare } from "@/plugins/smb/definitions"
import { isHoardSmbError, smbErrorCode } from "@/plugins/smb/definitions"

export type FolderData =
  | { kind: "shares"; shares: SmbShare[] }
  | { kind: "entries"; entries: SmbEntry[]; access?: SmbAccess }

export interface FolderState {
  data?: FolderData
  /** True while (re)loading; `data` keeps the previous listing for this folder meanwhile. */
  loading: boolean
  error?: {
    code: HoardSmbErrorCode
    attempts?: AddressAttempt[]
    /** `ACCESS_DENIED` on Android/data or Android/obb (local storage, Android 11+). */
    restricted?: boolean
  }
  refresh: () => Promise<void>
}

// Listings by location + path, so going back up a folder is instant (then refreshed).
const cache = new Map<string, FolderData>()
const MAX_CACHED = 40

const keyOf = (locationKey: string, path: string) => JSON.stringify([locationKey, path])

/** Forget a cached listing (`locationKey` is {@link BrowseLocation.cacheKey}). */
export function invalidateFolder(locationKey: string, path: string) {
  cache.delete(keyOf(locationKey, path))
}

/**
 * A machine's shares (SMB location without a share) or a folder's entries in any location. Shows a
 * cached listing right away and refreshes it in the background.
 */
export function useFolder(location: BrowseLocation | null | undefined, path: string): FolderState {
  const engine = useRouteEngine()
  const key = location ? keyOf(location.cacheKey, path) : ""
  const [state, setState] = useState<{
    key: string
    data?: FolderData
    loading: boolean
    error?: FolderState["error"]
  }>(() => ({ key, data: cache.get(key), loading: true }))
  const request = useRef(0)
  const locationRef = useRef(location)
  useEffect(() => {
    locationRef.current = location
  })

  const load = useCallback(async () => {
    const current = locationRef.current
    if (!current) return
    const id = ++request.current
    setState((s) => ({
      key,
      data: s.key === key ? s.data : cache.get(key),
      loading: true,
    }))
    try {
      const data: FolderData =
        current.kind === "smb" && !current.share
          ? {
              kind: "shares",
              shares: (
                await engine.withSession(current.machine!, (sessionId) =>
                  HoardSmb.listShares({ sessionId })
                )
              ).shares,
            }
          : {
              kind: "entries",
              ...(await current.run((loc) => HoardSmb.list({ location: loc, path }))),
            }
      if (id !== request.current) return
      cache.set(key, data)
      if (cache.size > MAX_CACHED) cache.delete(cache.keys().next().value!)
      setState({ key, data, loading: false })
    } catch (error) {
      if (id !== request.current) return
      const code = smbErrorCode(error)
      const attempts = error instanceof RouteError ? error.state.attempts : undefined
      const restricted = isHoardSmbError(error) && error.data?.restricted === true
      cache.delete(key)
      setState({ key, data: undefined, loading: false, error: { code, attempts, restricted } })
    }
  }, [engine, key, path])

  const ready = Boolean(location)
  useEffect(() => {
    if (ready) void load()
  }, [load, ready])

  const visible = state.key === key ? state : { key, data: cache.get(key), loading: true }
  return { data: visible.data, loading: visible.loading, error: visible.error, refresh: load }
}
