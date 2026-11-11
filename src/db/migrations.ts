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
  {
    // Transfers between any two locations (ADR 0007): copy or move, many sources (files and
    // folders), one destination folder, per-job item counts. Locations are ids like favorites'
    // (machine id, `device`, `vol-<uuid>`), plus `tree` (a SAF folder, `dest_tree_uri`) and
    // `incoming` (files another app shared). The machine columns cascade, so deleting a machine
    // still deletes its history. 1.3 downloads become copies SMB → tree with one source.
    version: 4,
    sql: `
      CREATE TABLE transfers_v4 (
        id TEXT PRIMARY KEY NOT NULL,
        op TEXT NOT NULL DEFAULT 'copy' CHECK (op IN ('copy', 'move')),
        source_location_id TEXT NOT NULL,
        source_machine_id TEXT REFERENCES machines(id) ON DELETE CASCADE,
        source_share TEXT NOT NULL DEFAULT '',
        source_paths TEXT NOT NULL DEFAULT '[]',
        dest_location_id TEXT NOT NULL,
        dest_machine_id TEXT REFERENCES machines(id) ON DELETE CASCADE,
        dest_share TEXT NOT NULL DEFAULT '',
        dest_path TEXT NOT NULL DEFAULT '',
        dest_tree_uri TEXT,
        name TEXT NOT NULL,
        conflict TEXT NOT NULL DEFAULT 'rename'
          CHECK (conflict IN ('ask', 'rename', 'overwrite', 'skip')),
        size INTEGER NOT NULL DEFAULT 0,
        bytes_done INTEGER NOT NULL DEFAULT 0,
        items_total INTEGER NOT NULL DEFAULT 0,
        items_done INTEGER NOT NULL DEFAULT 0,
        items_failed INTEGER NOT NULL DEFAULT 0,
        items_skipped INTEGER NOT NULL DEFAULT 0,
        state TEXT NOT NULL DEFAULT 'queued'
          CHECK (state IN ('queued', 'scanning', 'conflict', 'running', 'retrying', 'completed', 'failed', 'cancelled', 'skipped')),
        error_code TEXT,
        error TEXT,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        finished_at TEXT
      );
      INSERT INTO transfers_v4 (id, op, source_location_id, source_machine_id, source_share, source_paths,
          dest_location_id, dest_tree_uri, name, size, bytes_done, items_total, items_done, items_failed,
          items_skipped, state, error_code, error, created_at, finished_at)
        SELECT id, 'copy', machine_id, machine_id, share,
          '["' || replace(replace(remote_path, '\\', '\\\\'), '"', '\\"') || '"]',
          'tree', dest_tree_uri, dest_name, size, bytes_done, 1,
          CASE WHEN state = 'completed' THEN 1 ELSE 0 END,
          CASE WHEN state IN ('failed', 'cancelled') THEN 1 ELSE 0 END,
          CASE WHEN state = 'skipped' THEN 1 ELSE 0 END,
          state, error_code, error, created_at, finished_at
        FROM transfers;
      DROP TABLE transfers;
      ALTER TABLE transfers_v4 RENAME TO transfers;
      CREATE INDEX idx_transfers_created ON transfers(created_at);
      CREATE INDEX idx_transfers_source_machine ON transfers(source_machine_id);
      CREATE INDEX idx_transfers_dest_machine ON transfers(dest_machine_id);
    `,
  },
  {
    // Photo culling (ADR 0008). A culled folder is keyed like a favorite: location id (machine id,
    // `device`, `vol-<uuid>`), share ('' for local) and path. Decisions are per shot, keyed by its
    // lowercase stem (a RAW + JPEG pair is one shot); a row exists only while a shot is picked,
    // rejected or rated. No foreign keys: local locations are not rows, and deleting a machine
    // clears its rows in `deleteMachine`. The last destination of "Copy picks" is per source
    // location (the same card reader, the same NAS share).
    version: 5,
    sql: `
      CREATE TABLE cull_sessions (
        location_id TEXT NOT NULL,
        share TEXT NOT NULL DEFAULT '',
        path TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        last_visited_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        last_shot_id TEXT,
        shot_count INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (location_id, share, path)
      );

      CREATE TABLE cull_decisions (
        location_id TEXT NOT NULL,
        share TEXT NOT NULL DEFAULT '',
        path TEXT NOT NULL DEFAULT '',
        shot_id TEXT NOT NULL,
        flag TEXT CHECK (flag IS NULL OR flag IN ('pick', 'reject')),
        rating INTEGER NOT NULL DEFAULT 0 CHECK (rating BETWEEN 0 AND 5),
        updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        PRIMARY KEY (location_id, share, path, shot_id)
      );

      CREATE TABLE cull_destinations (
        source_location_id TEXT NOT NULL,
        source_share TEXT NOT NULL DEFAULT '',
        location_id TEXT NOT NULL,
        share TEXT NOT NULL DEFAULT '',
        path TEXT NOT NULL DEFAULT '',
        label TEXT,
        options TEXT NOT NULL DEFAULT '{}',
        updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        PRIMARY KEY (source_location_id, source_share)
      );
    `,
  },
  {
    // Assisted culling (ADR 0009): what native measured on a shot (sharpness, faces, exposure,
    // similarity), cached so reopening a folder does not recompute. Keyed by the analysed file
    // (the shot's RAW, else its JPEG): location id + share + path, valid while its size, modified
    // time and the analysis version still match (a stale row is replaced on the next save).
    // `folder` lists a folder's rows at once. No foreign keys, like the other culling tables.
    version: 6,
    sql: `
      CREATE TABLE cull_analysis (
        location_id TEXT NOT NULL,
        share TEXT NOT NULL DEFAULT '',
        path TEXT NOT NULL,
        folder TEXT NOT NULL DEFAULT '',
        size INTEGER NOT NULL,
        modified_at TEXT,
        version INTEGER NOT NULL,
        analysis TEXT NOT NULL,
        analysed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        PRIMARY KEY (location_id, share, path)
      );
      CREATE INDEX idx_cull_analysis_folder ON cull_analysis(location_id, share, folder);
    `,
  },
  {
    // Culling ratings as XMP sidecars (ADR 0011): per shot, when Hoard last decided (ms, kept when a shot is
    // unmarked and its `cull_decisions` row deleted), the sidecar's mtime (ms) when Hoard last wrote or imported
    // it, and whether that sidecar holds Hoard's latest decision (`synced`). The import rule compares a sidecar's
    // mtime with these. The 1.6-1.8 "Copy picks" switch (`cullXmpSidecars`, off by default) is folded into
    // "Save ratings next to photos" (`cullSaveXmp`, on by default): the old key is dropped.
    version: 7,
    sql: `
      CREATE TABLE cull_sidecars (
        location_id TEXT NOT NULL,
        share TEXT NOT NULL DEFAULT '',
        path TEXT NOT NULL DEFAULT '',
        shot_id TEXT NOT NULL,
        decided_at INTEGER,
        sidecar_mtime INTEGER,
        synced INTEGER NOT NULL DEFAULT 0 CHECK (synced IN (0, 1)),
        PRIMARY KEY (location_id, share, path, shot_id)
      );
      DELETE FROM settings WHERE key = 'cullXmpSidecars';
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
