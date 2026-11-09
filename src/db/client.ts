import { Capacitor } from "@capacitor/core"
import { withChangeNotifications } from "./changes"
import type { Db } from "./db"
import { migrate } from "./migrations"

const DATABASE_NAME = "hoard"
const WEB_STORAGE_KEY = "hoard.devdb"

async function openNative(): Promise<Db> {
  const { CapacitorSQLite, SQLiteConnection } = await import("@capacitor-community/sqlite")
  const { capacitorDb } = await import("./capacitorDb")
  const sqlite = new SQLiteConnection(CapacitorSQLite)
  const exists = (await sqlite.isConnection(DATABASE_NAME, false)).result
  const connection = exists
    ? await sqlite.retrieveConnection(DATABASE_NAME, false)
    : await sqlite.createConnection(DATABASE_NAME, false, "no-encryption", 1, false)
  await connection.open()
  const db = capacitorDb(connection)
  await db.execute("PRAGMA foreign_keys = ON")
  return db
}

function toBase64(bytes: Uint8Array): string {
  let binary = ""
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  return btoa(binary)
}

function fromBase64(text: string): Uint8Array {
  return Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
}

/**
 * Browser fallback: sql.js (SQLite in WebAssembly), saved to localStorage shortly after each write.
 * Only demo data lives here (no passwords ever reach the database). Clear it with
 * `localStorage.removeItem("hoard.devdb")` to start over.
 */
async function openWeb(): Promise<Db> {
  const [{ default: initSqlJs }, { default: wasmUrl }, { sqljsDb }] = await Promise.all([
    import("sql.js"),
    import("sql.js/dist/sql-wasm.wasm?url"),
    import("./sqljsDb"),
  ])
  const SQL = await initSqlJs({ locateFile: () => wasmUrl })
  let saved: string | null = null
  try {
    saved = localStorage.getItem(WEB_STORAGE_KEY)
  } catch {
    // storage blocked: in-memory only
  }
  const database = saved ? new SQL.Database(fromBase64(saved)) : new SQL.Database()
  let timer: ReturnType<typeof setTimeout> | undefined
  const persist = () => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      try {
        localStorage.setItem(WEB_STORAGE_KEY, toBase64(database.export()))
      } catch {
        // quota or blocked storage: keep going in memory
      }
    }, 200)
  }
  const db = sqljsDb(database, persist)
  await db.execute("PRAGMA foreign_keys = ON")
  return db
}

let dbPromise: Promise<Db> | null = null

/** The app database, opened and migrated once. */
export function getDb(): Promise<Db> {
  dbPromise ??= (async () => {
    const native = Capacitor.isNativePlatform()
    const db = native ? await openNative() : await openWeb()
    await migrate(db)
    if (!native) {
      const { seedDemoData } = await import("./seed")
      await seedDemoData(db)
    }
    return withChangeNotifications(db)
  })()
  return dbPromise
}
