# ADR 0003: Storage — SQLite for data, Android Keystore for passwords

- **Date**: 2026-11-08
- **Status**: Accepted

## Context

Hoard stores machines, their addresses, favorite folders, a transfer history and a few settings.
It also needs SMB passwords, which must survive app restarts but should never be readable by the
WebView or end up in a backup of the database.

## Decision

**SQLite via `@capacitor-community/sqlite`** on Android, database `hoard`, with versioned
migrations tracked by `PRAGMA user_version` (`src/db/migrations.ts`, the pattern from home-apps).
Each migration runs in a transaction with its version bump; released migrations are never edited.
`PRAGMA foreign_keys = ON` on every connection.

Every screen goes through one small query interface, `Db` (`src/db/db.ts`: `query`, `run`,
`execute`, `transaction`), with two implementations:

- `capacitorDb.ts` on Android;
- `sqljsDb.ts` in the browser (`bun run dev`) and in unit tests: **sql.js**, SQLite compiled to
  WebAssembly, so the same SQL and migrations run everywhere. The browser copy is saved to
  `localStorage` (`hoard.devdb`) and seeded once with the web mock's demo machines. We chose sql.js
  directly over jeep-sqlite (which wraps it in a web component plus IndexedDB) because it is less
  machinery and also runs in Node for the tests.

Schema (v6), ids are TEXT UUIDs created by the app and shared with the native plugin:

| Table               | Columns                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `machines`          | id, name, icon, color, username, domain, server_guid (nullable), sort_order, created_at                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `machine_addresses` | id, machine_id → machines (cascade), host, port (445), kind (`local`/`tailscale`/`other`), label, sort_order                                                                                                                                                                                                                                                                                                                                                                                                       |
| `favorites`         | id, location_id (machine id, `device` or `vol-<uuid>`), machine_id → machines (cascade; null for local locations), share (`''` for local), path (`""` = root), label, sort_order, created_at; unique (location_id, share, path). v3, [ADR 0005](0005-locations.md)                                                                                                                                                                                                                                                 |
| `transfers`         | id, op (`copy`/`move`), source_location_id, source_machine_id → machines (cascade, null unless a machine), source_share, source_paths (JSON array; `incoming`: item indexes), dest_location_id (machine id, `device`, `vol-<uuid>`, `tree`), dest_machine_id → machines (cascade), dest_share, dest_path, dest_tree_uri, name, conflict, size, bytes_done, items_total, items_done, items_failed, items_skipped, state, error_code, error, created_at, finished_at. v4, [ADR 0007](0007-transfers-any-location.md) |
| `settings`          | key, value (JSON)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `manual_shares`     | machine_id → machines (cascade), name (`COLLATE NOCASE`), created_at; primary key (machine_id, name). v2: shares opened by name that the server does not list                                                                                                                                                                                                                                                                                                                                                      |
| `cull_sessions`     | location_id, share (`''` for local), path, started_at, last_visited_at, last_shot_id, shot_count; primary key (location_id, share, path). v5: one culled folder, [ADR 0008](0008-culling.md)                                                                                                                                                                                                                                                                                                                       |
| `cull_decisions`    | location_id, share, path, shot_id (lowercase stem: a RAW + JPEG pair is one shot), flag (`pick`/`reject`/null), rating (0..5), updated_at; primary key (location_id, share, path, shot_id). A row exists only while a shot is flagged or rated. v5                                                                                                                                                                                                                                                                 |
| `cull_destinations` | source_location_id, source_share, location_id, share, path, label, options (JSON), updated_at; primary key (source_location_id, source_share): the last "Copy picks" destination per source. v5                                                                                                                                                                                                                                                                                                                    |
| `cull_analysis`     | location_id, share, path (the analysed file: the shot's RAW, else its JPEG), folder, size, modified_at (as listed), version, analysis (JSON `CullAnalysis`), analysed_at; primary key (location_id, share, path), index (location_id, share, folder). A cache: valid while size, modified time and the analysis version match. v6, [ADR 0009](0009-assisted-culling.md)                                                                                                                                            |

Known `settings` keys (no migration needed, the table is key/value; validated with defaults in
`src/features/settings/settings.ts`): `showHiddenFiles`, `defaultSort` (`{ key, direction }`),
`defaultConflict` (`ask` / `rename` / `overwrite` / `skip`), `recentDestinations` (the destination picker's "Recent", newest first; 1.4), `lastDestination` (≤ 1.3, no longer read), `probeTimeoutMs`,
`onboarded`, `storageIntroSeen` (the all files access screen was shown, ADR 0005), `cullAutoAdvance`
(ADR 0008), `cullBlurSensitivity` / `cullEyesSensitivity` (`low` / `medium` / `high`), `cullBurstGapMs` and
`cullXmpSidecars` (ADR 0009), `cullSwipeDecide` (ADR 0008, 1.7), `revealStreamNoteSeen` (ADR 0010, 1.8: the SMB note before "Edit in Reveal" was dismissed for good), and in the browser build `demoSeeded` / `worstCaseSeeded`.

The culling tables (v5, v6) have no foreign keys: local locations are not rows. `deleteMachine` clears a
machine's culling rows (decisions, sessions, destinations, analyses) in the same transaction. The SQLite adapters treat a transaction that begins
while another is open as nested (they cannot tell the two apart), so callers that write in quick
succession (culling decisions) serialize their writes.

The transfer service writes progress (bytes and item counts) to `transfers` at most once a second per job (plus every
state change); speed and the active host live only in memory, fed by `transferProgress`.

**Passwords never touch SQLite, localStorage or any JS-side storage.** `HoardSmb.setCredentials`
sends the password once to native code, which encrypts it with an AES-GCM key held in the
**Android Keystore** (non-exportable) and keeps only the ciphertext in app-private storage. No
plugin method returns a password; `connect`, `startTransfer` and streams look credentials up natively by
`machineId`. Deleting a machine also calls `deleteCredentials`.

Theme, palette and language are per-device conveniences in `localStorage`, as in other hfps apps.

## Consequences

- No secret ever reaches a backup. The app opts out of Android backup and device-to-device
  transfer entirely (`allowBackup="false"` plus `data_extraction_rules.xml`): the Keystore key would
  not migrate anyway, and SAF grants do not survive a move. A new phone starts empty.
- The transfers table mirrors native jobs (per-item states stay native, `getTransferItems`). After
  the app was killed the UI reconciles it with `HoardSmb.getTransfers()` and then
  `acknowledgeTransfers`.
- The web build ships the sql.js WebAssembly file (about 650 KB) in `dist/`; Android loads it only on
  the web code path, so it costs APK size but no startup time.
