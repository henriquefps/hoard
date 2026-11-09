# ADR 0007: Transfers between any two locations; share in and out

- **Date**: 2026-11-09
- **Status**: Accepted (1.4.0)

## Context

Up to 1.3 a transfer was one thing: an SMB file → a phone folder picked with the Storage Access
Framework (`startDownload`). ADR 0005 made every place files live a _location_ (SMB share, the
phone's storage, USB drives and SD cards). The owner's workflow needs bytes to move between any of
them: a Sony card in a USB-C reader → cull on the phone (phase 2b) → copy the keepers to the phone,
a USB drive or a NAS/Mac share over LAN or Tailscale. Hoard should also behave like a file manager:
copy / move / paste anywhere, share files to other apps, and accept files other apps share.

Phase 2b's "copy picks to <any location>" calls the API this ADR defines, so it is the **one**
engine for moving bytes.

## Decision

### One contract: `startTransfer`

`startDownload` is gone; its callers moved to:

```ts
startTransfer({
  transferId,                     // the app's `transfers.id`, row created first
  op: "copy" | "move",
  sources: { location: TransferSourceLocation; path }[],   // files and folders
  destination: { location: TransferLocation; path },       // an existing folder
  conflict: "ask" | "rename" | "overwrite" | "skip",
  resume?: boolean,               // Retry under the same id
})
```

`TransferLocation` is `SmbStreamLocation | LocalLocationRef | TreeLocation`:

| Kind              | Shape                                                 | Notes                                                                     |
| ----------------- | ----------------------------------------------------- | ------------------------------------------------------------------------- |
| `smb`             | `{ machineId, addresses, expectedServerGuid, share }` | Native opens **its own** connection (like streams): never a session id    |
| `device`/`volume` | ADR 0005 refs                                         | by path with all files access, else the volume's SAF grants               |
| `tree`            | `{ kind: "tree", uri }`                               | a SAF folder from `pickDestinationFolder` (phones without the permission) |
| `incoming`        | `{ kind: "incoming", shareId }` (source only)         | files another app shared; `path` is the item index                        |

The rest of the contract: `resolveTransferConflicts`, `cancelTransfer`, `getTransfers` (job
summaries: `op`, `state`, bytes, `itemsTotal/Done/Failed/Skipped`, `conflicts`, `currentName`,
`activeHost`), `getTransferItems` (per-item states and errors, paged), `acknowledgeTransfers`,
`shareFiles`, `getSharedFiles`, `clearSharedFiles`; events `transferProgress` (the summary plus
`bytesPerSecond`) and `filesShared`. Two new states: `scanning` and `conflict`.

In the app the entry point is `useTransferActions().start(request)` (`TransferService.transfer`):
places are app ids (machine id + share, `device`, `vol-<uuid>`, `tree` + uri, `incoming` + share
id) and are turned into native locations only when the job starts, so SMB endpoints carry the
route that is current then (`nativeRoute`: the reachable address first, then the rest).

```ts
// Phase 2b, "copy picks":
await actions.start({
  op: "copy",
  source: { locationId: "vol-1A2B-3C4D" },
  paths: picks.map((p) => p.path),
  name: picks[0].name,
  size: picks.reduce((s, p) => s + p.size, 0),
  destination: { locationId: machineId, share: "photos", path: "2026/Sony" },
  conflict: "ask",
})
```

### Native engine

`android/app/src/main/java/dev/hfps/hoard/smb/transfer/`:

- `TransferFs` — what an endpoint must do (stat, list, mkdir, sequential read and write from an
  offset, delete, rename, set modified time, free space, server-side copy, reconnect). Plain Kotlin,
  so the engine is JVM-tested against an in-memory implementation.
- `TransferEndpoints` — `PathTransferFs` (local paths, `rename(2)`, `StatFs`),
  `DocumentTransferFs` (SAF: a volume's grants or a picked tree; external-storage document ids are
  composed, other providers walked by name), `SmbTransferFs` (own smbj connection, 4 READs and 4
  WRITEs of 1 MiB in flight, FSCTL_SRV_COPYCHUNK through `remoteCopyTo`, free space from
  `getShareInformation`), `IncomingTransferFs` (content URIs, read-only).
- `TransferRunner` — the job, single-threaded on the worker: **scan → conflicts → free space →
  items in order → empty source folders after a move**. `TransferWorker` adds WorkManager, the
  `dataSync` foreground service, notifications, persistence and events; `TransferManager` accepts,
  answers conflicts, cancels and reports; `TransferStore` persists each job (items included) to
  `files/transfers/<id>.json`.

**Scan.** Sources are statted; folders are walked depth-first into items: a `folder` item (create,
merged when it exists) before its children, `file` items with size and modified time. Totals grow
while `scanning` (events every 250 ms). Our own partial files are skipped. A folder into itself is
refused (also up front in `startTransfer`).

**Conflicts.** Folders always merge; the policy applies to files. It is applied when each file
runs (so races and two sources with the same name are handled the same way): `rename` →
`name (1).ext` against the destination folder's names (case-insensitive, FAT/exFAT/SMB rules),
`overwrite` → the old file is replaced only when the new copy is complete, `skip` → the item is
skipped. `ask`: the scan lists each destination folder once and marks clashing files; the job then
stops in `conflict` (and posts a notification). The UI asks once with "Do the same for the other N"
(`resolveTransferConflicts` with `all`, or per item) and the job is queued again.

**Free space.** Before the first byte: the bytes still to write (minus partial files, minus moves
that are renames) against the destination's free space (`StatFs`; SAF roots or `fstatvfs`; SMB
`FileFsFullSizeInformation` caller-available). `NO_SPACE` with `data.needed` / `data.free`, nothing
written.

**Copying a file.** Bytes go to a hidden partial file next to the target, `.<name>.hoardpart`
(cut to 255 characters), then: verify its size equals the source's → rename it over the target
(`rename(2)`; SMB `FileRenameInformation` with replace; SAF delete + `renameDocument`) → set the
source's modified time (paths, SMB; SAF cannot) → for a move, delete the source **only if it still
has the size and modified time that were copied** (else it is kept and logged). A partial file is
never visible under the real name, so a gallery or another client never sees half a photo.

**Moves.** Inside one location (same machine + share, same volume) a move is one rename of the
source, however big the folder: `rename(2)`, SMB rename, `DocumentsContract.moveDocument`. Never
copy + delete when a cheap atomic move exists. When the endpoint cannot (a SAF provider without
move support, or the name exists and folders must merge), the item falls back to copy + verify +
delete. After a move, source folders whose every item moved are deleted bottom-up, only when empty.
Device ↔ volume and anything ↔ SMB are different filesystems: copy, verify, delete.

**Within one SMB share,** a copy uses server-side copy (FSCTL_SRV_COPYCHUNK) in 32 MiB slices with
progress between them; servers that refuse it (`STATUS_NOT_SUPPORTED`, `INVALID_DEVICE_REQUEST`)
are streamed through the phone instead.

**Errors** have three scopes (`FailureScope`, unit tested):

- _retry_ — a dropped route on an SMB endpoint (`UNREACHABLE`, `TIMEOUT`, `GUID_MISMATCH`, I/O):
  the endpoint's `FailoverPlan` (ADR 0001: same address with backoff, then the next, two rounds)
  reconnects, the job shows `retrying` with the host, and the copy resumes at the byte written.
  A READ or WRITE unanswered for 15 s counts as a dead route.
- _job_ — nothing else can succeed now: `NO_SPACE`, `UNAVAILABLE` (a drive unplugged mid-copy),
  `PERMISSION_REVOKED`, `STORAGE_PERMISSION`, `AUTH_FAILED`. The job fails and every unfinished
  item fails with the same error.
- _item_ — this file is the problem (`NOT_FOUND`, `ACCESS_DENIED` on a read-only destination,
  invalid name, size mismatch): it fails, the others go on. The job ends `failed` when any item
  failed, `skipped` when every item was skipped.

**Resume.** The worker persists items every 2 s and on every state change; WorkManager reruns a
job after a process death or a system stop. Per file (`ResumeRules`, unit tested): a source whose
size or modified time changed restarts at 0. Local and SAF destinations write in order, so the
partial file's size is the resume point. SMB writes are pipelined, so after a crash the server file
may hold bytes past a hole: only the persisted, acknowledged-in-order offset is trusted, and only
while the server file still has at least that many bytes ("append at the server size if it
matches"); the writer truncates to it and appends, else restarts the file. A move whose source is
gone and whose target already has the right size counts as done (the source was deleted right
after the last persist).

**Retry** is `startTransfer` with the same id and `resume: true`: completed and skipped items
stay, failed and cancelled ones run again (partial files resume). Native keeps finished jobs (hidden
from `getTransfers` once acknowledged) — the newest 100 — so Retry and the per-item error list
work after the app restarted. If native lost the job, it scans again and counts a destination file
of the same size as already copied (`resumeExisting`).

**Cancel** marks the job and its unfinished items `cancelled` and stops the worker. The partial
file of the file in progress stays under its hidden name so Retry continues it; finished items stay.

1.3 jobs (`hoard_transfers.json`) are migrated once into one-item jobs that keep writing to the
document they had created (`direct`, no partial name).

### Share out

`shareFiles` hands files to the system share sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE` for
several, `ClipData` + read grant, a common MIME type like `image/*`):

- local files are shared **in place** (FileProvider for paths, the SAF document otherwise);
- SMB files up to 16 MiB are **copied to the cache first**: the receiving app (mail, messaging,
  Drive) often reads later, in the background, when Hoard may be frozen or killed; a cached file
  needs nothing from Hoard by then;
- larger SMB files **stream** through the proxy file descriptor provider (ADR 0004): nothing is
  downloaded before the sheet shows and the other app reads (and seeks) on demand while Hoard's
  streaming foreground service serves it. A multi-GB video shared to a player or an uploader that
  reads immediately works; an app that reads hours later after Hoard was killed gets read errors —
  the trade-off for not downloading gigabytes up front.

### Share in

`MainActivity` accepts `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for any MIME type. `IncomingShares`
resolves each URI's display name (made safe for FAT/SMB) and size through the ContentResolver,
takes a persistable grant when the provider offers one, remembers the share (also in a small file,
so a job resumed after a restart finds its URIs) and emits `filesShared`; the launch intent is read
in `load()`, later ones in `handleOnNewIntent` (`singleTask`). **Only other apps' content is read** (`SharedUris`, JVM-tested): another app picks what goes in
the intent and Hoard would open it with its own identity, so a `file://` URI (Hoard's private
`/data/data/dev.hfps.hoard/…` databases, preferences and files, or anything all files access can
read) or a URI of one of Hoard's own providers (authority starting with the application id, or any
provider Android resolves to Hoard's package: FileProvider with its `storage/` root, the stream
provider) would turn "Save to…" into a way to copy Hoard's private data out ("share intent file
theft"). Such URIs are dropped when the share arrives (counted in `SharedFiles.rejected`, the Save
to… screen says "n items could not be received") and checked again before every read of a
persisted share. The UI opens **Save to Hoard**
(`/save`): the files, then the destination picker (any location, SMB shares included), then a
normal job with `incoming` sources. Bytes never cross the bridge. Limits: Android's read grant
from a share intent lasts while Hoard's task lives; a job resumed after the task was removed fails
with `PERMISSION_REVOKED` unless the other app gave a persistable grant. Shared text without files
is not saved.

### UI

- **Browser:** multi-select → Copy / Move (clipboard), Share, Delete, and in "⋯": Copy to…, Move
  to…, Download (SMB) / Upload (local). The row menu has the same. Move is disabled where the
  folder is read-only.
- **Clipboard:** "Copy" / "Move" put the selection aside; a bar at the bottom of the content
  surface, on every screen, shows "3 items to copy · From Camera" with **Paste here** (off when no
  folder is open, the folder is read-only, a folder would go into itself, or a move would land in
  its own folder) and ×. In memory only, like a desktop clipboard. One-shot for moves.
- **Destination picker** (a Drawer on phones, a Dialog on desktop): Recent destinations (the last
  6, `recentDestinations` setting), This phone (internal storage and every mounted drive), Favorites,
  Machines → shares → folders, and "Folders Android gave Hoard" plus the system picker (a `tree`
  destination). Folders only, "New folder" on the way, read-only folders and shares marked and not
  pickable, the conflict policy in the footer (default from Settings, `ask` for new installs).
  Download opens in Internal storage › Download when it is readable.
- **Transfers page and drawer:** per job "DCIM and 2 more", from → to, a progress bar, "120 of
  340 files · 1.2 GB of 4 GB · 9 MB/s · 31 s left", the current file, the host in use, "Choose
  what to do…" for a job waiting on conflicts, "Show N failed files" (from `getTransferItems`),
  Retry (failed items only), Cancel, Open destination, Remove. The browser refreshes a folder when
  a job into it (or a move out of it) makes progress.
- **Conflict dialog:** one question per job ("“DSC00012.ARW” already exists", Keep both / Replace /
  Skip, "Do the same for the other N files" checked), "Later" leaves the job waiting.

### Storage (schema v4, ADR 0003)

`transfers` is rebuilt: `op`, `source_location_id` / `source_machine_id` / `source_share` /
`source_paths` (JSON), `dest_location_id` / `dest_machine_id` / `dest_share` / `dest_path` /
`dest_tree_uri`, `name`, `conflict`, `size`, `bytes_done`, `items_total/done/failed/skipped`,
`state` (with `scanning`, `conflict`). Both machine columns cascade. 1.3 rows become copies
machine → `tree`. Per-item states live natively (thousands of rows of churn would buy nothing the
native store does not already have).

## Consequences

- One engine for downloads, uploads, local copies, moves, "Save to…" and culling's "copy picks".
- The engine is testable on the JVM (`TransferRunnerTest`: folder scan, renames, ask, moves as
  renames and with fallback, verify before delete, emptied folders, failover resume, partial-file
  resume, no space, an unplugged destination and Retry, lost-job resume, JSON and 1.3 migration).
- SMB uploads cost one connection per job (sessions are not shared with the UI); two jobs run at
  once, the rest wait.
- SAF destinations cannot keep modified times, and a provider without move support turns a
  same-volume move into copy + delete.
- Device testing: `docs/testing/transfers.md`.
