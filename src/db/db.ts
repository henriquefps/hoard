/**
 * The one query API the app uses, so screens never know which SQLite they talk to:
 *  - Android: `@capacitor-community/sqlite` (`capacitorDb.ts`), a real file database.
 *  - Browser (`bun run dev`) and unit tests: sql.js, SQLite compiled to WebAssembly (`sqljsDb.ts`).
 *    The browser copy is saved to localStorage after each write; it only ever holds demo data.
 * Both run the same SQL and the same migrations (`migrations.ts`).
 */
export type SqlValue = string | number | null

export interface RunResult {
  /** Rows changed by the statement. */
  changes: number
}

export interface Db {
  /** SELECT; rows as plain objects keyed by column name. */
  query<T>(sql: string, params?: SqlValue[]): Promise<T[]>
  /** One INSERT/UPDATE/DELETE with bound parameters. */
  run(sql: string, params?: SqlValue[]): Promise<RunResult>
  /** Several `;`-separated statements without parameters (migrations). */
  execute(sql: string): Promise<void>
  /** All-or-nothing. Nested calls join the outer transaction. */
  transaction<T>(fn: () => Promise<T>): Promise<T>
}
