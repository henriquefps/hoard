import {
  createContext,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react"
import { getDb } from "./client"
import { getDbVersion, subscribeDbChanges } from "./changes"
import type { Db } from "./db"

const DbContext = createContext<Db | null>(null)

/** Opens and migrates the database, then renders the app (nothing until ready: it takes a few ms). */
export function DbProvider({ children }: { children: ReactNode }) {
  const [db, setDb] = useState<Db | null>(null)
  const [error, setError] = useState<Error | null>(null)
  useEffect(() => {
    getDb().then(setDb, setError)
  }, [])
  if (error) throw error
  if (!db) return null
  return <DbContext.Provider value={db}>{children}</DbContext.Provider>
}

export function useDb(): Db {
  const db = useContext(DbContext)
  if (!db) throw new Error("useDb must be used inside <DbProvider>")
  return db
}

/**
 * Runs `query` and re-runs it after every committed write (see changes.ts) and when `deps` change.
 * Returns undefined while the first result loads.
 */
export function useLiveQuery<T>(
  query: (db: Db) => Promise<T>,
  deps: unknown[] = []
): T | undefined {
  const db = useDb()
  const current = useSyncExternalStore(subscribeDbChanges, getDbVersion, getDbVersion)
  const [state, setState] = useState<{ key: string; value: T } | undefined>(undefined)
  const key = JSON.stringify([current, ...deps])
  useEffect(() => {
    let cancelled = false
    query(db).then((value) => {
      if (!cancelled) setState({ key, value })
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `query` is re-created every render; `key` covers its inputs
  }, [db, key])
  return state?.value
}
