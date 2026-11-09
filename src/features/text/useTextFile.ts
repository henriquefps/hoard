import { useCallback, useEffect, useRef, useState } from "react"
import { type AddressAttempt, RouteError } from "@/features/route/engine"
import type { BrowseLocation } from "@/features/locations/useBrowseLocation"
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

/** Read a text file in any location (SMB through the route engine's session). */
export function useTextFile(
  location: BrowseLocation | null | undefined,
  path: string
): TextFileState {
  const [state, setState] = useState<Omit<TextFileState, "load">>({ loading: true })
  const request = useRef(0)
  const locationRef = useRef(location)
  useEffect(() => {
    locationRef.current = location
  })

  const load = useCallback(
    async (options: { truncate?: boolean } = {}) => {
      const current = locationRef.current
      if (!current) return undefined
      const id = ++request.current
      setState((s) => ({ ...s, loading: true }))
      try {
        const data = await current.run((loc) =>
          HoardSmb.readTextFile({ location: loc, path, truncate: options.truncate })
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
    [path]
  )

  const ready = Boolean(location)
  const locationKey = location?.cacheKey
  useEffect(() => {
    if (ready) void load()
  }, [load, ready, locationKey])

  return { ...state, load }
}
