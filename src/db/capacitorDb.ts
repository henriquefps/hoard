import type { SQLiteDBConnection } from "@capacitor-community/sqlite"
import type { Db, RunResult, SqlValue } from "./db"

/**
 * {@link Db} over a `@capacitor-community/sqlite` connection. Statements inside `transaction` pass
 * `transaction = false`, otherwise the plugin wraps each one in its own transaction.
 */
export function capacitorDb(connection: SQLiteDBConnection): Db {
  let depth = 0
  return {
    async query<T>(sql: string, params: SqlValue[] = []) {
      return ((await connection.query(sql, params)).values ?? []) as T[]
    },
    async run(sql: string, params: SqlValue[] = []): Promise<RunResult> {
      const result = await connection.run(sql, params, depth === 0)
      return { changes: result.changes?.changes ?? 0 }
    },
    async execute(sql: string) {
      await connection.execute(sql, depth === 0)
    },
    async transaction<T>(fn: () => Promise<T>): Promise<T> {
      if (depth > 0) return fn()
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
    },
  }
}
