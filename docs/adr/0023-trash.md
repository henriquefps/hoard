# ADR 0023: Trash for rejects

- **Date**: 2026-11-15
- **Status**: Accepted 2026-11-15 (Hoard 2.3.0; needs hoard-server 0.11.0, feature `trash`)
- **Builds on**: ADR 0005 (locations, `HoardFs`), 0007 (transfers), 0008 (culling, "Delete rejects"), 0011 (sidecars),
  0014 (items, the NAS walk), 0016 (culling sync), 0020 (edited previews, "Lifecycle"), 0021 (Gallery), 0022 (SMB ↔
  NAS mappings).
- **Amends**: ADR 0013 and `server/api/openapi.yaml` (additions under `/v1`, below), ADR 0014 ("Indexer behaviour":
  `.hoard-trash` is walked), ADR 0020 ("Lifecycle": a trashed item is not deleted), ADR 0008 ("Delete rejects" becomes
  "Move rejects to trash"), ADR 0003 (`hoard.db` version 4), ADR 0021 (`gallery.db` version 3).

## Context

The owner culls many folders and wants to get rid of every reject at the end, safely. Today "Delete rejects"
(`planDeleteRejects` in `cull/CullLogic.kt`, `DeleteRejectsDialog` in `ui/cull/CullDialogs.kt`) deletes the shot's
files of one folder **permanently**, leaves its `.xmp` behind, and the Gallery cannot delete anything.

Deleting a NAS photo also costs server data that only Reveal can make: the walk sees the file gone, the item becomes
a tombstone without `replacedBy`, and `editsOfTombstone` (`server/internal/store/edits.go`) drops its `item_edits`
(ADR 0020 "Lifecycle"). A trash must let a photo come back **with** its edits and culling.

Facts from the code that shape the design:

- The NAS walk (`server/internal/nas/indexer.go`, `Folder`) already skips every name starting with `.`, so a
  `.hoard-trash` folder is invisible today: a file moved into it looks deleted.
- The walk applies removals (`store.ApplyNasUpdates` with `Removed`) and additions of one folder before its item sync
  (`items.Syncer.SyncFolder`), so a file moved within one folder in one walk keeps its item (same fingerprint, same
  id, ADR 0014 "moved"); a move is re-hashed (2 MiB) at its new path.
- `errEmptyMount`: a walk that sees no media file while the index holds live files fails as `unavailable`.
- SMB rename (`SmbBrowser.rename`, `SmbSessions.kt`) opens the file with `DELETE` and sends `FileRenameInformation`
  through smbj's `DiskEntry.rename`, whose target is a path **from the share root**: SMB2 renames across directories
  of one share in one call; the target's parent must exist. Samba implements it with `rename(2)`, so it keeps size
  and mtime and fails with `STATUS_NOT_SAME_DEVICE` when the share spans two filesystems (a bind mount inside it).
  `HoardFs.rename` only renames in place today; `SafFs` moves with `DocumentsContract.moveDocument`
  (`TransferEndpoints.kt`).
- `minSdk` is 24 (`android/variables.gradle`); MediaStore's trash exists from API 30.

## Decision

### What goes to the trash

A **shot** (ADR 0008: folder + case-insensitive stem): every file of it (RAW, JPEG, HEIF, the video of a Live Photo)
**and its sidecars** (`X.xmp`, `X.ARW.xmp`, hidden `.X.xmp`). Sidecars go with the shot and come back with it: left
behind, a sidecar is an orphan that the server would import onto nothing and that would be stale when the shot
returns; Hoard no longer writes sidecars on its own (ADR 0011 "export on demand"), so one next to a reject was made
on purpose (Export XMP, Lightroom, Reveal) and is worth keeping as long as the photo. Empty deletes them with it.

Nothing else changes for the shot's culling: the folder's `cull_decisions` row (and `cull_analysis`) stays while the
shot is in the trash, so a restored shot opens with its decision; the server's culling record stays forever (ADR
0016). Purging forgets the folder rows (`CullStore.forgetShots`, as "Delete rejects" does now).

### Where the trash is, per location

| Location | Trash | Restore | Why |
| --- | --- | --- | --- |
| SMB share | `<trash root>/.hoard-trash/<yyyy-MM-dd>/<path from the trash root>`, a rename on the same share | rename back | instant, no bytes move, self-describing, seen by the server |
| Phone, API ≥ 30 | MediaStore trash (`IS_TRASHED`, Android deletes after its 30 days) | untrash | the system trash the owner knows (Files, Photos see it) |
| Phone, API 24–29 | `<volume root>/.hoard-trash/<day>/<path>` with a `.nomedia`, `PathFs` rename | rename back | no MediaStore trash below API 30 |
| USB drive, SD card (`vol-<uuid>`, `PathFs` or `SafFs`) | `<volume root>/.hoard-trash/<day>/<path>`, with a `.nomedia` | rename back | a removable volume has no system trash Hoard can rely on (USB through SAF is not in MediaStore; an SD card's rows go when it is out); the trash travels with the drive and restores on any device; one code path with SMB |

`<day>` is the phone's local date of the trash operation; when the target path is taken (the same original path
trashed twice the same day), the day folder becomes `<yyyy-MM-dd>-2`, `-3`… Directories under the day folder are
created as needed (`mkdir`, `ALREADY_EXISTS` ignored).

**When the trash is not possible** the shot is refused, never silently deleted, and the dialog then offers the
current permanent delete (with today's warning) for those shots only:

- the trash root cannot be created (`ACCESS_DENIED`: a read-only share root or prefix);
- `STATUS_NOT_SAME_DEVICE` (the share spans filesystems) → mapped to `ErrorCode.UNAVAILABLE` with its own message;
- a SAF tree whose provider does not support move (`moveDocument` unsupported);
- the shot is already inside a `.hoard-trash`.

### The SMB trash root (so the server sees it)

The server walks only its NAS folders. The trash root of a file is chosen from the ADR 0022 mappings
(`smb_mappings`, the most specific covering the file):

1. **Share root = NAS folder root** (`smbPrefix` and `serverPrefix` empty): the share root. Server path
   `.hoard-trash/…`.
2. **Share root inside the NAS folder** (the share exports `Imagens/2024`, the folder is `Imagens`; `serverPrefix`
   = `2024`): the share root. Server path `2024/.hoard-trash/…`, inside the folder.
3. **Share root above the NAS folder** (the share exports the disk, the folder is `Imagens`; `smbPrefix` =
   `Imagens`): the **mapping's `smbPrefix`**, i.e. `Imagens/.hoard-trash/…` on the share, the folder's root on the
   server. Files of that share outside every mapping use the share root. One share can then have two trash roots;
   the Trash screen lists both.
4. **Not on the server** (no paired server, or `smb_mapping_misses` says the folder is not there): the share root.
5. **Paired, not known yet** (no mapping, no miss): Hoard learns first (ADR 0022 learning, the same two reads that
   opening the folder already does). If the server cannot be reached, the shot is refused ("Hoard needs the photo
   server to place the trash"), because guessing case 4 for a case 3 folder would make the server see the files as
   deleted and drop their edits.

In general: **the trash root is the mapping's `smbPrefix`** (empty = the share root), so on the server it is always
`<serverPrefix>/.hoard-trash`, inside the folder. Hoard never writes outside the trash root's `.hoard-trash`.

### No manifest: the path is the manifest

`.hoard-trash/<day>/<original path from the trash root>` says what, from where and when; size and mtime are the
file's own (a rename keeps them). Another device, a reinstall or the server rebuild everything by listing. A manifest
file would be a second truth that a hand-made move (Finder, a shell) makes stale, and two devices would race to
rewrite it. The MediaStore trash has no path of its own: Hoard's index is the only record of which trashed phone files
it trashed (after a reinstall the system's Files or Photos app restores them).

### Index on the phone

`hoard.db` version 4 (`HoardMigrations.MIGRATION_3_4`, schema `4.json`, `MigrationTest`; ADR 0003 updated):

- `trash_entries` (id; location id (machine id, `device`, `vol-<uuid>`), share, trash root, day folder, original
  path, trashed path, shot key (folder + stem), size, mtime, trashed at, MediaStore URI and expiry for phone files,
  server item id when mapped, state `moving` | `trashed` | `restoring` | `purging`).
- `trash_roots` (location id, share, root path, last listed): every root Hoard trashed into or found.

It is an index for SMB and volumes (rebuilt by listing a root's `.hoard-trash`: on Trash screen refresh, and when a
saved share or a mapping's prefix is first checked) and the record for MediaStore entries. Rows are written
**before** each move (`moving`) and settled after, so a killed process resumes or rolls back from the rows (a
`moving` row is settled by a `stat` of both paths).

`gallery.db` version 3 (`GalleryMigrationTest`): `server_items.trashed_since` (null = live), filled from the feed
with `include=trash` (below), so the Trash screen also shows server photos trashed by another device.

### Moves: `HoardFs.move` and `TrashService`

- `HoardFs.move(path, targetPath)` (new, `smb/location/`): `SmbFs` opens with `DELETE` and renames to the full share
  path (the code of `SmbBrowser.rename` without the same-parent rule, `replaceIfExists = false`); `PathFs`
  `File.renameTo` on the same volume; `SafFs` `DocumentsContract.moveDocument` then `renameDocument` if needed.
  Never a copy: a move that cannot be a rename fails.
- `TrashService` (`:native` `trash/`, a foreground job like transfers, with progress and Cancel) runs a `TrashPlan`.
  It is **not** a transfer: no bytes move (CLAUDE.md "one engine" is about bytes). Per shot: rows, then each file's
  move, then the sidecars; a shot whose first file fails is left as it was; a half-moved shot is reported and its
  moved files stay in the trash (restorable). At the end, for each NAS folder touched, it asks the server for a walk
  (`POST /v1/nas/walk`) and hides the shots from the Gallery at once (local `trash_entries`), before the feed confirms.
- **Phone, API ≥ 30**: files Hoard owns (its `Pictures/Hoard/` copies) get `IS_TRASHED = 1` by `update`; the others
  go through `MediaStore.createTrashRequest(uris, true)`, **one consent dialog per request**, at most **500 URIs per
  request** (the URIs travel in the `PendingIntent`'s binder transaction). With **Media management**
  (`MANAGE_MEDIA`, API 31+, `MediaStore.canManageMedia`) the system skips the dialog; the trash dialog offers to turn
  it on once. Sidecars are trashed through `MediaStore.Files` (All files access lets Hoard see non-media files; one
  without a row is scanned first). Whether All files access alone allows a direct `IS_TRASHED` update without
  consent is checked on the emulator in phase A3; the design does not depend on it.

### Restore

Back to the **exact original path**. On a conflict (a file exists there) the shot is **refused**, never renamed: the
server keeps the item's identity by fingerprint either way, but Reveal re-links a returning NAS file by its **folder +
stem**, ADR 0022 matches by path, size and mtime, and a file now at that path may be a newer one the owner
wants. The message names the file; the owner moves it and restores again. Missing directories are recreated. All
files of a shot are restored together (a shot whose file fails reports which). Phone: `createTrashRequest(uris,
false)` (the same consent rule), after checking that the original path is free.

### Empty and auto-empty

- **Empty**: everything, a day, a folder or a selection, after a confirmation that shows the count and the **size**
  and says it cannot be undone. SMB and volumes: delete the files, then empty day folders (recursive
  `SmbBrowser.delete`); phone: `MediaStore.createDeleteRequest` (the same consent rule).
- **Auto-empty**: Settings › Trash › "Empty the trash automatically", **on by default, after 30 days** (7 / 30 / 90).
  `TrashCleanupWorker` (daily, network not required for local volumes) deletes day folders older than the setting in
  every reachable trash root, including those another device made (the day folder dates them; with two devices the
  shorter setting wins). Phone trash follows Android's own expiry (`DATE_EXPIRES`, 30 days), which Hoard cannot
  change. Each purge asks the server for a walk.

### Screens

- **Files culling**: "Delete rejects" becomes **"Move rejects to trash"** (`planTrashRejects`: the shot's files plus
  sidecars from the listing, the trash path of each). The confirmation says how many shots, files and how much, and
  where the trash is; it is not destructive-styled. "Delete permanently" stays in the dialog only for shots that
  cannot be trashed.
- **Gallery**: selection (long press, day header), the existing **Rejected** filter, **Select all** (the filtered
  list: the whole library, a folder search, a date range), **Move to trash**. A shot on the phone and the server moves
  every copy Hoard can reach. Server shots whose folder has no mapping ("open the folder in Files once"), and shots
  that exist only as server uploads, are left out and counted in the dialog (decided with Reveal: upload-only shots
  never go to the trash; deleting them is Reveal's "Delete from server").
- **Trash screen** (Files and Gallery overflow, Settings › Trash): grouped by **day**, then **folder**, with the
  shot count and size per group; the place (share, phone, drive) as an eyebrow; thumbnails (phone files, the server's
  preview of a trashed item, else none). Actions: **Restore**, **Delete permanently**, **Empty trash**. Unreachable
  roots show greyed "Not reachable". Shots trashed on another device appear from `.hoard-trash` listings and from the
  feed.
- Files shows `.hoard-trash` only with "Show hidden files" (it is a dot folder); no badges there (trashed items are
  not live, so `items-by-path` misses them), "Move to trash" is refused inside it, and mapping learning never starts
  from it.

### Server: `.hoard-trash` is a trashed state, not a removal

Two candidates:

- **(A) The walk recognises the trash.** Files under `.hoard-trash/<day>/` are indexed as **trashed copies** of
  their original path; an item whose every location is trashed is **trashed**, not a tombstone. Its id, edits,
  previews and culling stay; a restore makes it live again; the files leaving the trash (Empty) make it a tombstone
  and ADR 0020 drops the edits then.
- **(B) Tombstones keep their edits for 30 days**, reattached if the id comes back live.

**Chosen: A.** B cannot tell a trash from a deletion (every deletion would keep edits for 30 days, and the server
would guess the retention Hoard set); the trash would be invisible to the server, so the web gallery, another
Hoard and Reveal could never show "in trash"; previews would go with the tombstone; and the empty-mount guard would
misfire: trashing every file of a folder leaves it with no visible media, the walk fails `unavailable` and the
rejects stay live in every gallery. With A the trashed files are files the walk sees, so the guard holds.

**The walk** (`nas/indexer.go`, `items/`):

1. A directory named exactly `.hoard-trash` is walked (other dot names are still skipped). Its children must match
   `yyyy-MM-dd(-n)?`; anything else inside is ignored, as is a `.hoard-trash` inside a trash. A file
   `<P>/.hoard-trash/<day>/<R>` is a **trash row** whose **origin** is `<P>/<R>` (folder-relative).
2. **No re-hash for trash moves**: a new trash row whose size and mtime equal its origin row's (live or just
   removed) takes the origin's fingerprint; the same for a restored file and its trash row. Trashing 5,000 rejects
   costs no fingerprint reads.
3. **Second look** before removing: a file missing from the listing whose counterpart (its trash row's origin, or a
   live file's `.hoard-trash/*/<R>` paths) is also missing is `stat`ed again at its path, then the counterpart, then
   its path again. A rename is atomic, so one move during the walk is always seen at one end; only a file found at
   neither is removed. (The walk reads `.hoard-trash` first by name order; without this a restore between the two
   reads would tombstone the item and drop its edits.)
4. **Grouping**: trash rows group by their origin's directory + stem as a source of their own (`item_sources.trash`);
   an item is rebuilt from all its sources. **Live** = any non-trash location (NAS or upload); **trashed** = only
   trash locations (`items.trashed_at` = when first seen so); **tombstone** = none left. Trashed is not deleted:
   `editsOfTombstone` does not run.
5. **Restore** (the origin path seen again with the same fingerprint): live again, same id, a new revision. **Purge**
   (trash rows gone, origin absent): tombstone without `replacedBy`, edits and their previews removed (ADR 0020),
   culling record kept. A trashed file moved anywhere else in the folder is a move (live, same id).
6. **Empty-mount guard**: trash rows count as files seen. If the owner trashes and then empties every file of a
   folder, the guard fires and the items stay trashed (not purged) until the folder shows files again: hidden
   everywhere, only their edits kept longer. Accepted, as the guard's existing trade-off.
7. An unreadable `.hoard-trash` (permissions) keeps its rows as they were (nothing purged from what was not read).
8. **Sidecars in the trash are never imported**: `importSidecars` (`nas/culling.go`) and `store.ImportSidecars` skip
   trash rows, so a trashed `.xmp` changes no culling. Its first-read state (`culling_sidecars`, ADR 0016 step 2b)
   **carries over with the origin path**, like the fingerprint: the row stays keyed by the original path while the
   sidecar is in the trash (not dropped as "file gone"), and on restore the sidecar is read against it. Its size and
   mtime are unchanged by the renames, so nothing is read again and a restore is never a first read. The row is
   dropped only at purge, with the trash row.

The server never writes the NAS (its mount stays read-only, ADR 0013); every move is Hoard's over SMB.

### `/v1` additions (ADR 0013, `openapi.yaml`)

`GET /v1/server` `features` gains `"trash"`. Old clients see exactly today's answers: a trashed item is a tombstone
to them, and a restored one comes back live with the same id, as a shot that reappears does today.

- **Items feed and reads** take `include=…,trash` (`/v1/items/changes`, `GET /v1/items/{id}?include=trash`,
  `POST /v1/items/lookup {"include": ["trash"]}`). Without it:
  - a trashed item is served as the tombstone shape `{"id", "rev", "deleted": true, "createdAt", "updatedAt"}`;
  - a live item lists only live locations (a member file whose every location is trashed is left out).

  With it, a trashed item is served whole with `"deleted": false` and `"trashed": {"since": <ms>}`, and every
  location in a trash carries `"trash": "<folder-relative path in .hoard-trash>"` (`path` stays the original).
  Filtering is done when serving; the stored item keeps everything.
- `GET /v1/items` excludes trashed items; `?trashed=only` lists them (`include=trash` shape). `by-path` stays live
  only. `POST /v1/items/lookup` without `trash`: trashed items in `missing` (Reveal's edit rule 3 then skips them).
- **Edits**: `PUT` on a trashed item answers `404 not_found` (as a tombstone; Reveal's rule 7 retries after a later
  lookup); `GET` and `DELETE` work (a version deleted in Reveal is deleted). Previews of a trashed item are kept and
  served (the Trash screen's thumbnails).
- **Culling**: records are untouched by trash and restore (no new revision, `fingerprints` unchanged); pushes on a
  trashed item apply. The server never writes or tombstones Reveal's `photo` records.
- **NAS feed** (`GET /v1/nas/files`, Reveal): trash rows never appear. A file moved to the trash is its original
  row with `"removed": true` plus **`"trashed": true`**; a restore is `"removed": false` (same path and fingerprint);
  a purge sends the row again with `"removed": true` and no `trashed`. `counts.nasFiles` ignores trash rows.
- `GET /v1/originals/{fingerprint}` and `POST /v1/fingerprints/lookup` still find a trashed file until it is purged (the
  bytes exist).
- **`POST /v1/nas/walk`** `{"folders": ["<id>", …]}` (device key, ≤ 100): queues a walk of each folder, coalesced (a
  queued or running walk is not queued twice; at most one per folder per 10 s), `202 {"queued": [...]}`, `404` for
  an unknown folder. Added to `server/testdata/v1-routes.txt`.
- The web gallery's library excludes trashed items (a web Trash view is later).

Migration `0009_trash.sql` (schema 9, forward only): `nas_files.origin_path` (set on trash rows),
`nas_files.trash_path` (set on a removed original that is in a trash), `items.trashed_at`, `item_sources.trash`,
partial indexes on live items updated to `deleted = 0 AND trashed_at IS NULL`.

### Older servers

Before `trash` is listed, Hoard still trashes (the files are safe), but a shot whose files the server holds (mapped)
gets a warning in the dialog: "this photo server will forget Reveal's edits of these photos until you update it".
`POST /v1/nas/walk` is not called.

## What Reveal sees (for Reveal's orchestrator)

Reveal reads the NAS feed (`/v1/nas/files`), culling (`include=culling`) and edits.

- **A trashed NAS photo Reveal edited.** The NAS feed sends each file's row `removed: true, trashed: true`. Reveal
  0.9.0 deletes the file row as for any removal; a photo with an edit, a hand-off or a record survives (W5) and stays
  in its grid, unopenable, with its versions; unedited ones leave it.
  On the server, the item, its edits (edited previews) and its culling record stay; `PUT` of an edit gets `404`
  (rule 7: look up again, `missing`, retry on a later pass); `GET /v1/originals/{fp}` still serves the file.
- **A restore.** The NAS feed sends `removed: false` at the **same path** with the **same fingerprint** (a rename
  keeps the bytes; restore refuses a taken path), so Reveal re-links the photo by **folder + stem**, which the exact-path
  restore keeps. Same item id, edits never dropped; held uploads succeed.
- **An Empty.** The NAS feed sends the row again, `removed: true` without `trashed`; the item becomes a tombstone and
  its server edits are removed (ADR 0020). Reveal's own photo, versions and records are untouched (the server never
  tombstones them); its original is no longer served, as for any deleted NAS file.
- **Reveal must change: nothing for safety; 0.10.0 adds the in-trash state.** Reveal 0.10.0's plan: with feature
  `trash` listed, read `trashed` on NAS feed entries, hide or badge those photos as in trash (instead of
  unopenable), and on a purge (the row again without `trashed`) clear `original_on_server`. It already keeps the
  photo's server edits link across a `404` on `PUT`.

## Build plan

| PR | Content | Tests |
| --- | --- | --- |
| **S1** server (hoard-server 0.11.0, tag `server-v0.11.0`) | migration 0009; walk: `.hoard-trash`, no re-hash, second look, grouping, trashed state; serve-time filtering, `include=trash`, `trashed=only`, NAS feed `trashed`, edits/previews rules, `POST /v1/nas/walk`, `features`; ADR 0013 + `openapi.yaml`; web library excludes trashed | Go: `TestTrashWalk` (trash, restore, purge, both in one walk, `-2` day folders, nested and malformed trash ignored, unreadable trash), `TestTrashSecondLook` (a rename between the two reads), `TestTrashNoRehash`, `TestTrashEditsAndCulling` (survive trash + restore, dropped at purge), `TestTrashEmptyMount` (every file trashed), `TestTrashOldClients` (tombstone then live without `include`; NAS feed fields), `TestNasWalkRoute`; `TestRevealProtocolContract`, `TestUpgradesHoardServer040DataDir`, `testdata/compat` unchanged |
| **A1** app core | `HoardFs.move` (Smb, Path, Saf), `hoard.db` v4 (`trash_entries`, `trash_roots`), `TrashPlanner` (pure: shot + sidecars → trash paths, the trash root rule from mappings), `TrashService` (resume from rows), restore with conflict refusal, Empty, `TrashCleanupWorker` | JVM: `TrashPlannerTest` (the five root cases, day `-n`, sidecar conventions), `TrashServiceTest` over an in-memory fs (kill mid-shot, resume, refusal), `MigrationTest` 3 → 4 |
| **A2** Files + Trash screen | "Move rejects to trash", Trash screen, Settings › Trash, strings en + pt-BR | Roborazzi goldens; emulator against the Samba container: trash, list, restore, conflict, Empty, auto-empty with a back-dated day folder, a read-only share root |
| **A3** phone trash | MediaStore trash and restore, batches of 500, Media management, API 24–29 `.hoard-trash` | emulator (API 36): consent dialog, `MANAGE_MEDIA` path, untrash; an API 29 image for the fallback |
| **A4** Gallery | `gallery.db` v3, `include=trash`, selection + Select all + Move to trash, walk request, server shots trashed elsewhere in the Trash screen | `SyncEngineTest` with the fake server (trashed, restored, purged items); E2E: own hoard-server container on a temp dir mounted read-only and the same dir served by the Samba container; a device-key edit upload; trash from the Gallery → item trashed, edit kept → restore → live with the edit → Empty → tombstone. Written in `docs/testing/trash.md` |

Order: S1 first and released; A1 → A2 → A3 → A4 (A2 and A3 work without a server; A4 needs S1).

## CHANGELOG draft (2.3.0)

- **A trash for rejects.** "Delete rejects" in culling is now **Move rejects to trash**: every file of each rejected
  shot (RAW, JPEG and its `.xmp`) goes to a trash instead of being deleted, so nothing is lost until you empty it.
  - On a share or a USB drive, the trash is a hidden `.hoard-trash` folder on that share or drive, by date and with
    the original folders. Moving is instant.
  - On the phone, photos go to Android's trash (Android may ask you to confirm; allow **Media management** to skip
    that).
- **Clear rejects from the Gallery.** Select photos (long press, or **Select all** with the **Rejected** filter, a
  folder or a date range) and **Move to trash**, wherever they are: on the phone or on your shares.
- **Trash** (in Files, the Gallery and Settings): your trashed photos by date and folder, with sizes. **Restore**
  puts a photo back exactly where it was (if another file took its place, Hoard tells you instead of overwriting).
  **Empty trash** asks first and shows how much space it frees. The trash empties itself after 30 days (Settings ›
  Trash).
- **Edits survive the trash.** With hoard-server 0.11.0, trashed photos leave the Gallery and the web gallery (Reveal
  keeps the ones you edited until Reveal 0.10.0 shows them as in trash) but keep their Reveal edits and ratings on the server; a restored photo comes back with them.

## Consequences

- Trash and restore cost no data on the share and no hashing on the server; the server learns of them at the walk
  Hoard requests (or the next scheduled one); Hoard hides trashed shots at once.
- The server stores trashed items until their files leave the trash; their edits and previews count in its storage.
- `.hoard-trash` folders appear on shares and drives (hidden by dot, `.nomedia` on volumes); other tools see them.
- A share whose root sits above the NAS folder has two trash roots.

## Owner's decisions (2026-11-15)

Henrique accepted this ADR with these answers to its open questions; where they differ from a line above, they win.

1. **The plain Files delete goes to the trash too.** Deleting a selection in Files (outside culling) moves it to the
   trash by the same rules (whole shots with their sidecars when the selection names a shot; other files as they are);
   permanent delete stays only where the trash is not possible, with today's warning.
2. **Auto-empty is on by default after 30 days** (Settings › Trash: 7 / 30 / 90 days, or off).
3. **Case 5 refuses**: when the folder's mapping is not known yet and the server cannot be reached, the shot is not
   trashed: "Try again when the photo server is reachable".
4. **Media management is asked in the first trash dialog** (once), and offered again in Settings › Trash.

## Amendment (S1, hoard-server 0.11.0)

Small decisions made while building phase S1, within the design above:

1. **`item_sources.trash` is the source value `trash`**, not a column: trash groups are `item_sources` rows with
   `source = 'trash'`, one per **trashed copy of a shot**: original directory + stem **within one day folder** (key
   `<day folder>/<stem>`), so the table's unique key is unchanged. Each trash group is the item of its own main file,
   like any group: it joins a live group, or another trashed copy, **only through the same main fingerprint**
   (PR #57 review). So a different file that later takes a trashed shot's path (an export, a reused camera number)
   is a new item and never takes the trashed one over, and the same path trashed on two days with different content
   is two trashed items. A trashed item becomes a tombstone only when its main file leaves the trash. A file trashed
   alone (the JPEG of a pair) is a trashed item of its own (by the ADR 0014 "Item id" rule; restored, it joins the
   pair again, which takes its edits through `replacedBy`).
2. **The second look keeps what it cannot read, everywhere**: a file under a folder the walk could not read, or whose
   trash counterpart cannot be read, is kept as it is (point 7 for `.hoard-trash`). This also applies to ordinary
   sub-folders, whose files a walk used to remove when the sub-folder was unreadable (a permission change would
   tombstone their items and drop their edits).
3. **No re-hash** reuses a fingerprint when the counterpart has the same size, mtime and media class: for a trash row,
   its original's row (live or removed); for a file at an original path, its removed row when that row is marked as in
   a trash, or a trash row whose origin it is.
4. **`POST /v1/nas/walk`**: a request for a folder being walked queues it once more (the running walk may have read
   the folder before Hoard's moves); a disabled (removed) folder is `404` like an unknown one; an empty list, more than
   100 ids or a malformed id is `422 invalid_request`. The walk of a folder starts at least 10 s after its previous
   walk started (scheduled walks count); see point 10 for the budget.
5. **`include`**: `include=trash` alone on the items feed means `items,trash`; `GET /v1/items/{itemId}` accepts only
   `include=trash` (`400 invalid_request` otherwise). `GET /v1/health` `counts.items` counts live items only.
6. **Web gallery**: besides the library, the item routes (`/web/items/{itemId}` and its files) answer `404` for a
   trashed item (a web Trash view is later).
7. Inside a day folder, hidden names are skipped as everywhere else, and so is a file directly in `.hoard-trash`.
8. **A trashed item is never an heir** (PR #57 review): it is not the `replacedBy` of a live item that goes, nor does it
   take its culling. The one exception is a trashed item whose main file is purged while others of its files stay in
   the trash: its culling goes to the trashed item of those files (same bytes), never to a live one.
9. **The web gallery serves nothing in a trash** (PR #57 review): trashed items are absent from the library, item,
   file, preview, edit and download routes under `/web/`, and a live item's members and locations that are only in a
   trash are left out of everything the web derives from item data (files, downloads and their sizes, the library's
   name, folder, directory and size, so its search and folder counts). `/v1` still serves a trashed item's previews
   and edits (point "Edits").
10. **`POST /v1/nas/walk` budget** (PR #57 review): requested walks are coalesced per folder and limited server-wide to
    one per folder per minute (and 10 s after any walk of it), whoever asks; a request beyond that is still `202`, with
    `"deferred": {"<folder id>": <ms>}` saying when the walk may start. Per device the route takes a burst of 6, then
    one request per 10 s; beyond that `429 rate_limited` with `Retry-After`.

## Amendment (A1)

Phase A1 (`HoardFs.move`, `hoard.db` v4, `TrashPlanner`, `TrashRunner` / `TrashService`, `TrashCleanupWorker`) follows
the decision above with these small, justified deviations:

1. **A SAF volume's trash root is its granted folder**, not the volume root: a SAF tree reaches nothing outside its
   grant (Android 11+ refuses the volume root in the picker), so `HoardFs.moveRootOf` answers the grant covering the
   path (`""` for path access) and the trash is `<grant>/.hoard-trash`. A path outside every grant is refused
   (`MOVE_UNSUPPORTED`), as a provider without move support is.
2. **`trash_entries` has three more columns** than listed: `run_id` (the run that wrote a row; `scan` for rows found by
   listing), `is_directory` (a plain folder of a Files selection moves as one rename, one row) and `server_folder_id`
   (the NAS folder to ask a walk for); `trash_roots` keeps `server_folder_id` too. Times are Unix ms. ADR 0003 lists
   the tables.
3. **Case 5 needs learning failures to stay unknown.** Until 2.2.2 an unreadable sample or a failed lookup stored a
   `smb_mapping_misses` row (as if the folder were not on the server) for a day; that would turn case 5 into case 4.
   `SmbServerPaths.mappingState` (and learning when a folder opens) now records a miss only when the server answered
   that the samples are not there (or disagree); a failure is tried again next time.
4. **A folder with fewer than two photos** cannot learn a mapping (one file is never trusted alone to store one): its
   biggest photo or video is looked up (one 2 MiB read), and a hit places the trash by that file's mapping without
   storing it; not found is case 4; a failure is case 5.
5. **Resume rule.** A unit whose `moving` rows are left by a kill is completed when any of its files is already in the
   trash, and rolled back (rows forgotten, files untouched) when none is, so a kill never leaves half a shot. A
   `restoring` row returns to `trashed` while the file is still in the trash; a `purging` one too (Empty runs again).
6. **The day folder is chosen per unit** (shot or plain item): the first `<day>`, `<day>-2`… where none of the unit's
   targets is taken, so all files of a shot share one day folder.
7. **Auto-empty** deletes a day folder whose date is at least the setting's days before today (`2026-10-16` goes on
   `2026-11-15` with 30 days). Empty and restore remove the folders they leave empty up to the day folder;
   `.hoard-trash` itself (and its `.nomedia`) stays.
8. **No foreground service yet.** A1 runs a trash in the app's process scope with progress and Cancel (between units);
   the rows make a kill safe. The foreground notification comes with the screens (A2).
9. **The phone below Android 11 already uses the folder trash** (`.hoard-trash` with `.nomedia`, the same code as a
   volume); on Android 11+ the phone is refused (`PHONE_TRASH_UNAVAILABLE`) until A3 plugs the MediaStore trash in as
   another `TrashBackend`.

**Review of PR #58** (fixes before merge):

10. **Case 4 needs a server answer, or nothing indexed moving.** A folder with no photos of its own (e.g. `Imagens/2024`
    holding only month folders) learns from the photos under the selected folders (a bounded walk: 20,000 entries,
    depth 16); with the server down that is case 5 (refused), never the share root. `NotOnServer` comes only from a
    server answer (no paired server, a server without `items-by-path`, a learning miss) or when the selection holds no
    photo or video at all (then the root cannot make the server see a deletion); a walk that could not finish refuses.
    A unit outside the mapping learned that way is refused (`UNKNOWN_PLACE`), not guessed.
11. **A selection that holds a NAS folder or a trash is refused** (`CONTAINS_TRASH`): a folder containing a mapping's
    `smbPrefix`, a known trash root (`trash_roots`), its own trash root, or any `.hoard-trash` below it (found by the
    same walk; an unfinished walk counts as holding one). Trashing `Imagens` from a disk share's root would otherwise
    move the whole NAS folder, its trash included, into the share-root trash.
12. **Auto-empty does not trust the phone's clock or the day folders' names alone.** The run is skipped while now is
    before the newest trash time this device wrote (`trashNewestTrashedAt`, which outlives Empty). Each root's "now"
    is the earliest of the phone's clock, the mtime of a probe file auto-empty writes in that `.hoard-trash`
    (`.hoard-clock`: the share's own clock) and a trusted network time when one is available; a day folder goes only
    when its name **and** its mtime are older than the setting by that "now" (no mtime: only this device's folders,
    every row trashed before the limit); a trash that cannot be written purges nothing. Known limit: on a local drive
    the probe's mtime is the phone's own clock, so a clock that jumps ahead there is caught only when a trusted
    network time is available.
13. **A trashed folder stays one row** through a refresh: the listing stops at a folder the index knows and reports it
    as a folder, so restore moves it back whole, empty subfolders included.
14. **Restore and Empty work on whole units in the service**: the ids given are completed with every row of the same
    shot (or plain item) in the same day folder of the same root.
15. **Deletes are fenced**: Empty deletes a row's path only when it parses as `<trash root>/.hoard-trash/<day>/…` of
    that row; auto-empty deletes only `<root>/.hoard-trash/<yyyy-MM-dd(-n)>`.
16. **Smaller**: `PathFs.move` uses `link` + `remove` where the filesystem has hard links (fails `EEXIST` atomically,
    never replaces), else the existence check then `renameTo`; a share that drops mid-batch stops the batch (the rest
    refused `UNREACHABLE` at once); auto-empty waits for start-up recovery, which it runs itself when the worker comes
    first.

## Amendment (A2)

Phase A2 (the screens) follows the decision with these deviations:

1. **The entry points are buttons**: Files and the Gallery have no overflow menu, so the Trash is a button in their top
   bars (Files: next to Transfers; Gallery: next to Settings), plus Settings › Trash › "Open the trash".
2. **The Trash screen acts on groups**: Restore and Delete permanently work on a folder of a day, or on a whole day;
   Empty trash on everything (after listing every reachable root). No per-shot selection and no thumbnails yet (the
   rows are folders, the counts and sizes are per group). Restore and Empty still work on whole shots (A1 item 14).
3. **Changing the auto-empty setting runs auto-empty at once** (a one-off `TrashCleanupWorker`), so a shorter limit
   applies now, not at the next daily run.
4. **The foreground notification** (`TrashRunService`, `dataSync`) shows for runs of more than 20 files; it follows
   the run, which lives in the process scope, with Cancel (after the shot being moved).
5. **The permanent-delete offer** is made for the refusals that permanent delete solves: a read-only trash root,
   `NOT_SAME_DEVICE`, a provider that cannot move, already in a trash, and the phone until A3. Not for an unreachable
   server or location (try again) or a folder holding a trash (never deleted that way).
6. **Media management** is a placeholder in Settings › Trash until A3.
7. **Errors of `SmbFs`** are mapped per call inside the trash (`MappedHoardFs`): found on the emulator, where smbj's
   own "not found" made every Files delete refuse (docs/testing/trash.md).
8. **The sidecar rule of a delete in Files** (the coordinator's decision, 2026-11-16): a delete in Files moves only the
   selected files, never the shot's other files, and takes the shot's sidecars (`X.xmp`, `X.JPG.xmp`, `.X.xmp`) only
   when it removes the **last** media member of that stem in that folder. Deleting `DSC00001.JPG` with no
   `DSC00001.ARW` / HEIF / video left takes `DSC00001.xmp` and `DSC00001.JPG.xmp` too; with the RAW still there, every
   sidecar stays with it. A sidecar the user selected explicitly goes as a plain file. Culling's "Move rejects to
   trash" still moves the whole shot (a reject is the shot). `TrashPlanner.units(…, wholeShots = false)`.
9. **Permanent delete acts on exactly what the user chose** (review of PR #59): the offer deletes the units of the
   plan, so in Files the selected files plus sidecars under item 8 (never the shot's other files: on the phone before
   A3 every Files delete is refused and goes straight to the offer), and in culling the reject shots. Its confirmation
   shows the file count, size and place (culling: also the shot count, Hoard 1's wording) and why the trash refused;
   **Keep** is the default button, "Delete permanently" the other.
10. **A shorter auto-empty period asks first**: off → on, or fewer days, computes what auto-empty would delete now (a
    dry run on every reachable root, counting files and bytes, and whether other devices' day folders are included)
    and applies only after a confirmation; Cancel leaves the setting and the trash as they were. A longer period
    applies at once (it deletes nothing more).
11. **A session or share that went away is unreachable, never an empty trash**: `USER_SESSION_DELETED`,
    `NETWORK_SESSION_EXPIRED`, `NETWORK_NAME_DELETED` and `BAD_NETWORK_NAME` (which Files reads as `NOT_FOUND`) are
    `UNREACHABLE` in the trash (`MappedHoardFs`): a refresh marks the root not reachable and forgets no row.
12. **Screens follow the trash after their dialog is gone**: `TrashService.changes` makes an open Files folder read
    itself again after any run, restore or Empty; an open culling grid hides the shots whose every file is in the
    trash. Empty trash counts only the places that answered the last refresh and says how many files it leaves on the
    others.

## Amendment (A3)

Phase A3 (the phone's MediaStore trash, `MediaStoreTrashBackend` over `MediaStoreOps`) follows the decision with these
findings and deviations (emulator API 36, docs/testing/trash.md):

1. **All files access allows a direct `IS_TRASHED` update** (and untrash and delete) of rows Hoard does not own, with
   no consent. While it is granted (the phone's Files tab needs it anyway) Hoard changes rows directly; the consent
   requests (`createTrashRequest`, `createDeleteRequest`, at most 500 URIs each, whole shots per request) serve when
   it is not, and Media management skips their dialog. Rows Hoard owns (its `Pictures/Hoard/` copies) are always
   changed directly.
2. **Consent requests take media rows only** ("All requested items must be Media items"): sidecars and other plain
   `MediaStore.Files` rows are always changed directly, so without All files access a shot's sidecar cannot follow
   (the shot is reported half-moved, never split silently). A file without a row is scanned first.
3. **A phone folder cannot go to Android's trash** (it takes files): refused `FOLDER_NOT_TRASHABLE`, with the
   permanent-delete offer (Keep is the default).
4. **Rows are read back by id from `MediaStore.Files`** after each step (a typed collection has no `MEDIA_TYPE`), and
   settled by what they say: a declined consent (`DECLINED`) leaves no row. Refresh and start-up recovery forget rows
   Android purged (its 30 days) or the user restored elsewhere, and update `expires_at` (`DATE_EXPIRES`), which the
   Trash screen shows ("Android deletes them on …"). The 30 days are Android's: Hoard's auto-empty does not touch the
   phone's trash.
5. **Media management** is offered once, in the first phone trash dialog (`trashMediaManagementAsked`), and in
   Settings › Trash (its state, and Allow / Change opening Android's screen). `MANAGE_MEDIA` is declared in the
   manifest.
6. **Permanent deletes always ask** (the coordinator's decision on PR #60): trash and restore stay direct under All
   files access (item 1), but Delete permanently and Empty of phone rows Hoard does not own **always** go through
   Android's `createDeleteRequest` consent (batches of at most 500, whole shots), even when a direct delete would be
   allowed; Hoard's own rows (`Pictures/Hoard/`) are deleted directly. A shot's sidecars (plain rows, which a consent
   cannot hold) are deleted directly only after the consent for that shot's media succeeded, never when it was
   declined; a shot with no media row of someone else needs no consent.
7. **Auto-empty leaves the phone's trash to Android**: a background run cannot show a consent, so
   `TrashCleanupWorker` skips phone rows Hoard does not own (Android deletes them itself at their `DATE_EXPIRES`, which
   the Trash screen shows) and only purges Hoard's own phone rows older than the setting, directly (with the same
   clock guard as the folder trash).
8. **Below Android 11** the phone keeps the folder trash (`.hoard-trash` with `.nomedia`); not run on an API 29 image
   (none on the build host).

## Alternatives considered

- **Tombstones keep their edits (B)**: see "Server": blind to the trash, previews lost, the empty-mount guard keeps
  trashed rejects live.
- **A `.hoard-trash` in each photo folder**: always writable where a delete is, always inside the NAS folder, never
  across filesystems; but dozens of trash folders per share, and the Trash screen would need a full-share walk after
  a reinstall. The mapping rule keeps one root per share (two in case 3).
- **The server moves the files** (it knows every path): its mount stays read-only (ADR 0013, 0016); Hoard has the
  write access and the owner's intent.
- **A manifest per day folder**: see "No manifest".
- **Restore with a new name on a conflict**: Reveal and ADR 0022 would see another file; refusing keeps identity by
  path.
- **Phone trash in `.hoard-trash` on API ≥ 30 too**: no consent dialog, but invisible to the system's trash and to
  other apps, and `PathFs` writes would bypass MediaStore's row bookkeeping.
