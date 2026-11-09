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

Schema (v1), ids are TEXT UUIDs created by the app and shared with the native plugin:

| Table               | Columns                                                                                                                                                          |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `machines`          | id, name, icon, color, username, domain, server_guid (nullable), sort_order, created_at                                                                          |
| `machine_addresses` | id, machine_id → machines (cascade), host, port (445), kind (`local`/`tailscale`/`other`), label, sort_order                                                     |
| `favorites`         | id, machine_id → machines (cascade), share, path (`""` = root), label, sort_order, created_at; unique (machine_id, share, path)                                  |
| `transfers`         | id, machine_id → machines (cascade), share, remote_path, dest_tree_uri, dest_name, dest_uri, size, bytes_done, state, error_code, error, created_at, finished_at |
| `settings`          | key, value (JSON)                                                                                                                                                |

Known `settings` keys (no migration needed, the table is key/value; validated with defaults in
`src/features/settings/settings.ts`): `showHiddenFiles`, `defaultSort` (`{ key, direction }`),
`defaultConflict`, `lastDestination` (the SAF tree used for the last download), `probeTimeoutMs`,
`onboarded`, and in the browser build `demoSeeded` / `worstCaseSeeded`.

The transfer service writes progress to `transfers` at most once a second per job (plus every
state change); speed and the active host live only in memory, fed by `transferProgress`.

**Passwords never touch SQLite, localStorage or any JS-side storage.** `HoardSmb.setCredentials`
sends the password once to native code, which encrypts it with an AES-GCM key held in the
**Android Keystore** (non-exportable) and keeps only the ciphertext in app-private storage. No
plugin method returns a password; `connect` and `startDownload` look credentials up natively by
`machineId`. Deleting a machine also calls `deleteCredentials`.

Theme, palette and language are per-device conveniences in `localStorage`, as in other hfps apps.

## Consequences

- A database export or backup never contains secrets; restoring onto a new phone asks for passwords
  again (Keystore keys do not migrate).
- The transfers table mirrors native jobs. After the app was killed the UI reconciles it with
  `HoardSmb.getTransfers()` and then `acknowledgeTransfers`.
- The web build ships the sql.js WebAssembly file (about 650 KB) in `dist/`; Android loads it only on
  the web code path, so it costs APK size but no startup time.
