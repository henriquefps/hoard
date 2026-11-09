import { useCallback, useEffect, useRef, useState } from "react"
import type { MachineWithAddresses } from "@/db/types"
import { RouteError, type AddressAttempt } from "@/features/route/engine"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { HoardSmb } from "@/plugins/smb"
import type { HoardSmbErrorCode, SmbEntry, SmbShare } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"

export type FolderData =
  { kind: "shares"; shares: SmbShare[] } | { kind: "entries"; entries: SmbEntry[] }

export interface FolderState {
  data?: FolderData
  /** True while (re)loading; `data` keeps the previous listing for this folder meanwhile. */
  loading: boolean
  error?: { code: HoardSmbErrorCode; attempts?: AddressAttempt[] }
  refresh: () => Promise<void>
}

// Listings by machine + share + path, so going back up a folder is instant (then refreshed).
const cache = new Map<string, FolderData>()
const MAX_CACHED = 40

const keyOf = (machineId: string, share: string | undefined, path: string) =>
  JSON.stringify([machineId, share ?? null, path])

export function invalidateFolder(machineId: string, share: string | undefined, path: string) {
  cache.delete(keyOf(machineId, share, path))
}

/**
 * The shares of a machine (no `share`) or a folder's entries, through the route engine's cached
 * session (reconnects once on an expired session). Shows a cached listing right away and refreshes
 * it in the background.
 */
export function useFolder(
  machine: MachineWithAddresses | null | undefined,
  share: string | undefined,
  path: string
): FolderState {
  const engine = useRouteEngine()
  const key = machine ? keyOf(machine.id, share, path) : ""
  const [state, setState] = useState<{
    key: string
    data?: FolderData
    loading: boolean
    error?: FolderState["error"]
  }>(() => ({ key, data: cache.get(key), loading: true }))
  const request = useRef(0)
  const machineRef = useRef(machine)
  useEffect(() => {
    machineRef.current = machine
  })

  const load = useCallback(async () => {
    const current = machineRef.current
    if (!current) return
    const id = ++request.current
    setState((s) => ({
      key,
      data: s.key === key ? s.data : cache.get(key),
      loading: true,
    }))
    try {
      const data: FolderData = await engine.withSession(current, async (sessionId) =>
        share
          ? { kind: "entries", entries: (await HoardSmb.list({ sessionId, share, path })).entries }
          : { kind: "shares", shares: (await HoardSmb.listShares({ sessionId })).shares }
      )
      if (id !== request.current) return
      cache.set(key, data)
      if (cache.size > MAX_CACHED) cache.delete(cache.keys().next().value!)
      setState({ key, data, loading: false })
    } catch (error) {
      if (id !== request.current) return
      const code = smbErrorCode(error)
      const attempts = error instanceof RouteError ? error.state.attempts : undefined
      setState({ key, data: undefined, loading: false, error: { code, attempts } })
    }
  }, [engine, key, share, path])

  const machineReady = Boolean(machine)
  useEffect(() => {
    if (machineReady) void load()
  }, [load, machineReady])

  const visible = state.key === key ? state : { key, data: cache.get(key), loading: true }
  return { data: visible.data, loading: visible.loading, error: visible.error, refresh: load }
}
