/**
 * Versioned schema migrations, tracked with `PRAGMA user_version` (same pattern as home-apps).
 * Append a new entry for every schema change; never edit a released one. Each migration runs in a
 * transaction together with its version bump, so a crash leaves the database at the old version.
 * Ids are TEXT (UUIDs from `crypto.randomUUID()`): the UI creates rows and hands the same id to the
 * native plugin (credentials by `machineId`, jobs by `transferId`). Passwords are never stored here.
 */
export interface Migration {
  version: number
  sql: string
}

export const MIGRATIONS: Migration[] = [
  {
    version: 1,
    sql: `
      CREATE TABLE machines (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        icon TEXT,
        color TEXT,
        username TEXT NOT NULL DEFAULT '',
        domain TEXT,
        server_guid TEXT,
        sort_order INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
      );

      CREATE TABLE machine_addresses (
        id TEXT PRIMARY KEY NOT NULL,
        machine_id TEXT NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
        host TEXT NOT NULL,
        port INTEGER NOT NULL DEFAULT 445,
        kind TEXT NOT NULL CHECK (kind IN ('local', 'tailscale', 'other')),
        label TEXT,
        sort_order INTEGER NOT NULL DEFAULT 0
      );
      CREATE INDEX idx_machine_addresses_machine ON machine_addresses(machine_id, sort_order);

      CREATE TABLE favorites (
        id TEXT PRIMARY KEY NOT NULL,
        machine_id TEXT NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
        share TEXT NOT NULL,
        path TEXT NOT NULL DEFAULT '',
        label TEXT NOT NULL,
        sort_order INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        UNIQUE (machine_id, share, path)
      );

      CREATE TABLE transfers (
        id TEXT PRIMARY KEY NOT NULL,
        machine_id TEXT NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
        share TEXT NOT NULL,
        remote_path TEXT NOT NULL,
        dest_tree_uri TEXT NOT NULL,
        dest_name TEXT NOT NULL,
        dest_uri TEXT,
        size INTEGER NOT NULL DEFAULT 0,
        bytes_done INTEGER NOT NULL DEFAULT 0,
        state TEXT NOT NULL DEFAULT 'queued'
          CHECK (state IN ('queued', 'running', 'retrying', 'completed', 'failed', 'cancelled', 'skipped')),
        error_code TEXT,
        error TEXT,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        finished_at TEXT
      );
      CREATE INDEX idx_transfers_created ON transfers(created_at);

      CREATE TABLE settings (
        key TEXT PRIMARY KEY NOT NULL,
        value TEXT NOT NULL
      );
    `,
  },
  {
    // Shares opened by name that the server does not list (NetShareEnum leaves them out).
    version: 2,
    sql: `
      CREATE TABLE manual_shares (
        machine_id TEXT NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
        name TEXT NOT NULL COLLATE NOCASE,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        PRIMARY KEY (machine_id, name)
      );
    `,
  },
  {
    // Locations (ADR 0005): favorites anywhere, not only on machines. `location_id` is the
    // machine id for SMB (so every existing favorite keeps working as it was), `device` for the
    // phone's storage or `vol-<uuid>` for a USB drive / SD card (machine_id NULL, share '').
    // SQLite cannot drop NOT NULL in place, so the table is rebuilt; ids, labels and order are kept.
    version: 3,
    sql: `
      CREATE TABLE favorites_v3 (
        id TEXT PRIMARY KEY NOT NULL,
        location_id TEXT NOT NULL,
        machine_id TEXT REFERENCES machines(id) ON DELETE CASCADE,
        share TEXT NOT NULL DEFAULT '',
        path TEXT NOT NULL DEFAULT '',
        label TEXT NOT NULL,
        sort_order INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        UNIQUE (location_id, share, path),
        CHECK ((machine_id IS NULL) = (location_id = 'device' OR location_id LIKE 'vol-%')),
        CHECK (machine_id IS NULL OR machine_id = location_id)
      );
      INSERT INTO favorites_v3 (id, location_id, machine_id, share, path, label, sort_order, created_at)
        SELECT id, machine_id, machine_id, share, path, label, sort_order, created_at FROM favorites;
      DROP TABLE favorites;
      ALTER TABLE favorites_v3 RENAME TO favorites;
      CREATE INDEX idx_favorites_machine ON favorites(machine_id);
    `,
  },
]

export const LATEST_VERSION = MIGRATIONS.at(-1)?.version ?? 0

/** Migrations still to apply to a database at `currentVersion`, in order. Pure, so it is unit tested. */
export function pendingMigrations(currentVersion: number): Migration[] {
  return MIGRATIONS.filter((m) => m.version > currentVersion)
}

/** Bring `db` to LATEST_VERSION. */
export async function migrate(db: import("./db").Db): Promise<number> {
  const rows = await db.query<{ user_version: number }>("PRAGMA user_version")
  const current = rows[0]?.user_version ?? 0
  if (current > LATEST_VERSION) {
    throw new Error(`Database version ${current} is newer than this app (${LATEST_VERSION})`)
  }
  for (const migration of pendingMigrations(current)) {
    await db.transaction(async () => {
      await db.execute(migration.sql)
      await db.execute(`PRAGMA user_version = ${migration.version}`)
    })
  }
  return LATEST_VERSION
}
