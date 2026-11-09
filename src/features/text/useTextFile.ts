import { useCallback, useEffect, useRef, useState } from "react"
import type { MachineWithAddresses } from "@/db/types"
import { type AddressAttempt, RouteError } from "@/features/route/engine"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { HoardSmb } from "@/plugins/smb"
import type { HoardSmbErrorCode, ReadTextFileResult } from "@/plugins/smb/definitions"
import { isHoardSmbError, smbErrorCode } from "@/plugins/smb/definitions"

export interface TextFileState {
  data?: ReadTextFileResult
  loading: boolean
  error?: { code: HoardSmbErrorCode; attempts?: AddressAttempt[]; size?: number }
  /** Read again; `truncate` loads the first part of a file above the limit (read-only). */
  load: (options?: { truncate?: boolean }) => Promise<ReadTextFileResult | undefined>
}

/** Read a text file through the route engine's session (reconnects once on an expired session). */
export function useTextFile(
  machine: MachineWithAddresses | undefined,
  share: string,
  path: string
): TextFileState {
  const engine = useRouteEngine()
  const [state, setState] = useState<Omit<TextFileState, "load">>({ loading: true })
  const request = useRef(0)
  const machineRef = useRef(machine)
  useEffect(() => {
    machineRef.current = machine
  })

  const load = useCallback(
    async (options: { truncate?: boolean } = {}) => {
      const current = machineRef.current
      if (!current) return undefined
      const id = ++request.current
      setState((s) => ({ ...s, loading: true }))
      try {
        const data = await engine.withSession(current, (sessionId) =>
          HoardSmb.readTextFile({ sessionId, share, path, truncate: options.truncate })
        )
        if (id === request.current) setState({ data, loading: false })
        return data
      } catch (error) {
        if (id !== request.current) return undefined
        const code = smbErrorCode(error)
        const attempts = error instanceof RouteError ? error.state.attempts : undefined
        const size =
          isHoardSmbError(error) && typeof error.data?.size === "number"
            ? error.data.size
            : undefined
        setState({ loading: false, error: { code, attempts, size } })
        return undefined
      }
    },
    [engine, share, path]
  )

  const ready = Boolean(machine)
  useEffect(() => {
    if (ready) void load()
  }, [load, ready])

  return { ...state, load }
}
