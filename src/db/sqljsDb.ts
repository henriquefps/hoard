import type { Database } from "sql.js"
import type { Db, RunResult, SqlValue } from "./db"

/** {@link Db} over a sql.js database. `onWrite` runs after every committed write (persistence). */
export function sqljsDb(database: Database, onWrite: () => void = () => {}): Db {
  let depth = 0

  const afterWrite = () => {
    if (depth === 0) onWrite()
  }

  const db: Db = {
    async query<T>(sql: string, params: SqlValue[] = []) {
      const statement = database.prepare(sql)
      try {
        statement.bind(params)
        const rows: T[] = []
        while (statement.step()) rows.push(statement.getAsObject() as T)
        return rows
      } finally {
        statement.free()
      }
    },
    async run(sql: string, params: SqlValue[] = []): Promise<RunResult> {
      database.run(sql, params)
      const changes = database.getRowsModified()
      afterWrite()
      return { changes }
    },
    async execute(sql: string) {
      database.exec(sql)
      afterWrite()
    },
    async transaction<T>(fn: () => Promise<T>): Promise<T> {
      if (depth > 0) return fn()
      database.exec("BEGIN")
      depth++
      try {
        const result = await fn()
        depth--
        database.exec("COMMIT")
        afterWrite()
        return result
      } catch (error) {
        depth--
        database.exec("ROLLBACK")
        throw error
      }
    },
  }
  return db
}
