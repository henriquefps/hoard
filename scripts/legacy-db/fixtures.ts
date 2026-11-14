/**
 * Capacitor databases for the native importer's tests (ADR 0019), produced by the app's own code: the real
 * migrations (`src/db/migrations.ts`) and write paths (`src/db/queries.ts`) over sql.js, the adapter the JS tests
 * use (the Android app runs the same SQL through @capacitor-community/sqlite). Synthetic data only.
 *
 *   bun scripts/legacy-db/fixtures.ts
 *
 * Writes to android/core/data/fixtures/legacy/:
 *  - `hoard-v7.db`: the latest schema with rows in every table, written through `queries.ts`;
 *  - `hoard-v1.db`: a 1.0-era database (migration 1 only) with rows, so the importer must run migrations 2-7;
 *  - `hoard-v1-upgraded.db`: that same file after the JS `migrate()`, what the native upgrade must reproduce;
 *  - `hoard-v99.db`: a database from a newer Capacitor app than the importer knows (it must refuse it).
 */
import { mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import initSqlJs, { type Database } from "sql.js"
import { MIGRATIONS, migrate } from "../../src/db/migrations"
import {
  addFavorite,
  addManualShare,
  applyTransferProgress,
  createTransfer,
  saveCullAnalyses,
  saveCullDecisions,
  saveCullSidecars,
  saveMachine,
  setCullDestination,
  setServerGuid,
  setSetting,
  touchCullSession,
} from "../../src/db/queries"
import { sqljsDb } from "../../src/db/sqljsDb"

const OUT = "android/core/data/fixtures/legacy"

async function v7(SQL: initSqlJs.SqlJsStatic): Promise<Database> {
  const database = new SQL.Database()
  const db = sqljsDb(database)
  await db.execute("PRAGMA foreign_keys = ON")
  await migrate(db)

  const nas = await saveMachine(db, {
    name: "NAS",
    icon: "hard-drives",
    color: "blue",
    username: "photos",
    addresses: [
      { host: "192.168.1.10" },
      { host: "100.101.102.103", label: "Tailscale" },
      { host: "nas.tail1234.ts.net", port: 4450 },
    ],
  })
  await setServerGuid(db, nas, "6f1c2e8a-0b7d-4c55-9a51-3d2f8e9b1c07")
  const desktop = await saveMachine(db, {
    name: "Família — desktop ✓",
    icon: "desktop-tower",
    color: "violet",
    username: "henrique",
    domain: "WORKGROUP",
    addresses: [{ host: "desktop.local" }],
  })
  await saveMachine(db, { name: "Old laptop", username: "", addresses: [] })

  await addManualShare(db, nas, "Hidden$")
  await addManualShare(db, nas, "photos")

  await addFavorite(db, {
    locationId: nas,
    machineId: nas,
    share: "photos",
    path: "2026/Lisboa",
    label: "Lisboa",
  })
  await addFavorite(db, {
    locationId: desktop,
    machineId: desktop,
    share: "Users",
    path: "",
    label: "Users",
  })
  await addFavorite(db, {
    locationId: "device",
    machineId: null,
    share: "",
    path: "DCIM/Camera",
    label: "Camera",
  })
  await addFavorite(db, {
    locationId: "vol-1234-ABCD",
    machineId: null,
    share: "",
    path: "DCIM/100MSDCF",
    label: "Sony card",
  })

  const copied = await createTransfer(db, {
    op: "copy",
    sourceLocationId: "vol-1234-ABCD",
    sourceMachineId: null,
    sourceShare: "",
    sourcePaths: ["DCIM/100MSDCF/DSC01234.ARW", 'DCIM/100MSDCF/quote " and \\ slash.JPG'],
    destLocationId: nas,
    destMachineId: nas,
    destShare: "photos",
    destPath: "2026/Lisboa",
    destTreeUri: null,
    name: "DSC01234.ARW",
    conflict: "rename",
    size: 52_428_800,
    itemsTotal: 2,
  })
  await applyTransferProgress(db, {
    transferId: copied,
    state: "completed",
    bytesDone: 52_428_800,
    totalBytes: 52_428_800,
    itemsTotal: 2,
    itemsDone: 2,
    itemsFailed: 0,
    itemsSkipped: 0,
  } as Parameters<typeof applyTransferProgress>[1])
  const moved = await createTransfer(db, {
    op: "move",
    sourceLocationId: "device",
    sourceMachineId: null,
    sourceShare: "",
    sourcePaths: ["Download/report.pdf"],
    destLocationId: "tree",
    destMachineId: null,
    destShare: "",
    destPath: "",
    destTreeUri: "content://com.android.externalstorage.documents/tree/primary%3ABackup",
    name: "report.pdf",
    conflict: "ask",
    size: 1024,
    itemsTotal: 1,
  })
  await applyTransferProgress(db, {
    transferId: moved,
    state: "failed",
    bytesDone: 512,
    totalBytes: 1024,
    itemsTotal: 1,
    itemsDone: 0,
    itemsFailed: 1,
    itemsSkipped: 0,
    error: { code: "NETWORK", message: "Connection reset" },
  } as Parameters<typeof applyTransferProgress>[1])

  await setSetting(db, "showHiddenFiles", true)
  await setSetting(db, "defaultSort", { key: "modified", direction: "desc" })
  await setSetting(db, "defaultConflict", "ask")
  await setSetting(db, "recentDestinations", [
    { locationId: nas, share: "photos", path: "2026", label: "2026" },
  ])
  await setSetting(db, "cullSaveXmp", true)
  await setSetting(db, "cullBurstGapMs", 1500)
  await setSetting(db, "onboarded", true)

  const card = { locationId: "vol-1234-ABCD", share: "", path: "DCIM/100MSDCF" }
  await touchCullSession(db, card, { shotCount: 120, lastShotId: "dsc01234" })
  await saveCullDecisions(db, card, [
    { shotId: "dsc01234", flag: "pick", rating: 0 },
    { shotId: "dsc01235", flag: "reject", rating: 0 },
    { shotId: "dsc01236", flag: null, rating: 5 },
    { shotId: "dsc01237", flag: "pick", rating: 3 },
  ])
  await saveCullSidecars(db, card, [
    {
      shotId: "dsc01234",
      decidedAt: 1_762_000_000_000,
      sidecarMtime: 1_762_000_000_500,
      synced: true,
    },
    { shotId: "dsc01299", decidedAt: null, sidecarMtime: 1_761_000_000_000, synced: false },
  ])
  await setCullDestination(
    db,
    { locationId: "vol-1234-ABCD", share: "" },
    { ...card, locationId: nas, share: "photos", label: "photos" },
    {
      pairs: "both",
      sidecars: true,
    }
  )
  await saveCullAnalyses(db, card, [
    {
      path: "DCIM/100MSDCF/DSC01234.ARW",
      size: 26_214_400,
      modifiedAt: "2026-11-01T10:00:00.000Z",
      version: 3,
      analysis: JSON.stringify({ sharpness: 0.82, faces: [{ eyesOpen: 0.97 }], note: 'ação "ok"' }),
    },
    {
      path: "DCIM/100MSDCF/DSC01235.JPG",
      size: 8_388_608,
      modifiedAt: null,
      version: 3,
      analysis: JSON.stringify({ sharpness: 0.12, faces: [] }),
    },
  ])

  const nasCulled = { locationId: nas, share: "photos", path: "2026/Lisboa" }
  await touchCullSession(db, nasCulled, { shotCount: 3 })
  await saveCullDecisions(db, nasCulled, [{ shotId: "img_0001", flag: "pick", rating: 4 }])
  return database
}

/** A database as Hoard 1.0 left it: migration 1 only, rows in the v1 shapes. */
async function v1(SQL: initSqlJs.SqlJsStatic): Promise<Database> {
  const database = new SQL.Database()
  const db = sqljsDb(database)
  await db.execute("PRAGMA foreign_keys = ON")
  const first = MIGRATIONS.find((m) => m.version === 1)
  if (!first) throw new Error("migration 1 missing")
  await db.execute(first.sql)
  await db.execute("PRAGMA user_version = 1")
  await db.execute(`
    INSERT INTO machines (id, name, icon, color, username, domain, server_guid, sort_order, created_at) VALUES
      ('m-nas', 'NAS', NULL, NULL, 'photos', NULL, 'guid-nas', 0, '2025-12-01T09:00:00.000Z'),
      ('m-pc', 'PC', 'desktop-tower', 'rose', 'me', 'HOME', NULL, 1, '2025-12-02T09:00:00.000Z');
    INSERT INTO machine_addresses (id, machine_id, host, port, kind, label, sort_order) VALUES
      ('a1', 'm-nas', '192.168.1.10', 445, 'local', NULL, 0),
      ('a2', 'm-nas', '100.64.0.7', 445, 'tailscale', 'TS', 1),
      ('a3', 'm-pc', 'pc.example.com', 4450, 'other', NULL, 0);
    INSERT INTO favorites (id, machine_id, share, path, label, sort_order, created_at) VALUES
      ('f1', 'm-nas', 'photos', '2025', '2025', 0, '2025-12-03T09:00:00.000Z'),
      ('f2', 'm-pc', 'C$', '', 'C drive', 1, '2025-12-03T09:01:00.000Z');
    INSERT INTO transfers (id, machine_id, share, remote_path, dest_tree_uri, dest_name, dest_uri, size, bytes_done,
        state, error_code, error, created_at, finished_at) VALUES
      ('t1', 'm-nas', 'photos', '2025/a "quoted" \\ name.jpg', 'content://tree/primary%3ADownload', 'a.jpg',
        'content://doc/1', 100, 100, 'completed', NULL, NULL, '2025-12-04T09:00:00.000Z', '2025-12-04T09:00:05.000Z'),
      ('t2', 'm-pc', 'C$', 'big.iso', 'content://tree/primary%3ADownload', 'big.iso', NULL, 1000, 10, 'failed',
        'NETWORK', 'reset', '2025-12-04T10:00:00.000Z', '2025-12-04T10:01:00.000Z'),
      ('t3', 'm-pc', 'C$', 'skip.txt', 'content://tree/primary%3ADownload', 'skip.txt', NULL, 5, 0, 'skipped',
        NULL, NULL, '2025-12-04T11:00:00.000Z', '2025-12-04T11:00:01.000Z');
    INSERT INTO settings (key, value) VALUES
      ('showHiddenFiles', 'false'),
      ('lastDestination', '{"treeUri":"content://tree/primary%3ADownload"}'),
      ('cullXmpSidecars', 'true');
  `)
  return database
}

async function v99(SQL: initSqlJs.SqlJsStatic): Promise<Database> {
  const database = await v7(SQL)
  database.exec(
    "CREATE TABLE future_things (id TEXT PRIMARY KEY NOT NULL); PRAGMA user_version = 99"
  )
  return database
}

const SQL = await initSqlJs()
mkdirSync(OUT, { recursive: true })
const write = (name: string, database: Database) => {
  writeFileSync(join(OUT, name), database.export())
  console.log(`wrote ${join(OUT, name)}`)
}
write("hoard-v7.db", await v7(SQL))
const old = await v1(SQL)
write("hoard-v1.db", old)
await migrate(sqljsDb(old))
write("hoard-v1-upgraded.db", old)
write("hoard-v99.db", await v99(SQL))
