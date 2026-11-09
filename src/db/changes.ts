import type { Db } from "./db"

// A global "data changed" counter: every committed write bumps it, live queries re-run.
let version = 0
const listeners = new Set<() => void>()

export function notifyDbChanged(): void {
  version++
  listeners.forEach((listener) => listener())
}

export const subscribeDbChanges = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export const getDbVersion = () => version

/** Wraps a Db so every committed write (outside or at the end of a transaction) notifies. */
export function withChangeNotifications(db: Db): Db {
  let depth = 0
  const after = <T>(result: T) => {
    if (depth === 0) notifyDbChanged()
    return result
  }
  return {
    query: (sql, params) => db.query(sql, params),
    run: (sql, params) => db.run(sql, params).then(after),
    execute: (sql) => db.execute(sql).then(after),
    async transaction(fn) {
      depth++
      try {
        return await db.transaction(fn)
      } finally {
        depth--
        if (depth === 0) notifyDbChanged()
      }
    },
  }
}
