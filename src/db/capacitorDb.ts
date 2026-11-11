import type { SQLiteDBConnection } from "@capacitor-community/sqlite"
import type { Db, RunResult, SqlValue } from "./db"

/**
 * {@link Db} over a `@capacitor-community/sqlite` connection. Statements inside `transaction` pass
 * `transaction = false`, otherwise the plugin wraps each one in its own transaction.
 *
 * Top-level transactions and standalone writes run one after another: the plugin refuses
 * `beginTransaction` while another write's own transaction is open ("Already in transaction"), which
 * happened when two parts of the app wrote at once (culling's sidecar import on resume, 1.9.0).
 */
export function capacitorDb(connection: SQLiteDBConnection): Db {
  let depth = 0
  let tail: Promise<unknown> = Promise.resolve()
  const exclusive = <T>(fn: () => Promise<T>): Promise<T> => {
    const next = tail.then(fn, fn)
    tail = next.catch(() => {})
    return next
  }
  async function topLevel<T>(fn: () => Promise<T>): Promise<T> {
    await connection.beginTransaction()
    depth++
    try {
      const result = await fn()
      depth--
      await connection.commitTransaction()
      return result
    } catch (error) {
      depth--
      try {
        await connection.rollbackTransaction()
      } catch {
        // keep the original error
      }
      throw error
    }
  }
  return {
    async query<T>(sql: string, params: SqlValue[] = []) {
      return ((await connection.query(sql, params)).values ?? []) as T[]
    },
    async run(sql: string, params: SqlValue[] = []): Promise<RunResult> {
      const result =
        depth > 0
          ? await connection.run(sql, params, false)
          : await exclusive(() => connection.run(sql, params, true))
      return { changes: result.changes?.changes ?? 0 }
    },
    async execute(sql: string) {
      if (depth > 0) await connection.execute(sql, false)
      else await exclusive(() => connection.execute(sql, true))
    },
    async transaction<T>(fn: () => Promise<T>): Promise<T> {
      if (depth > 0) return fn()
      return exclusive(() => topLevel(fn))
    },
  }
}
