# SMB folders on the server (Hoard 2.2.0, ADR 0022): tests and end-to-end run

## JVM and Robolectric (`cd android && ./gradlew testDebugUnitTest`)

| Suite | What it checks |
| --- | --- |
| `:core:server` `PathMappingTest` | prefixes derived from the common tail (share = folder, share holds the folder, share inside it, case-insensitive names); two samples must agree (another folder, copies in two folders); never one sample; a mapping covers its sub-tree, the most specific wins; invalidation by another server or SMB GUID; an on-demand lookup outside the mapping is a mismatch; matching by path + size + mtime (2 s), changed files, rename candidates (unique size + mtime, never an indexed file still present, ambiguous ones not matched; a candidate is only looked up, never mapped), a new burst frame next to a deleted one of the same size and second is not mapped to it, `replacedBy` chains, files outside the mapping, when the query is complete |
| `:core:server` `SmbServerPathsTest` (fake hoard-server 0.10.0) | learning reads exactly two files and makes two lookups, a second folder of the share reads none; the 5-minute cache and pull to refresh; a cached folder matched again against the current listing (an overwritten file is no longer its old item, without asking the server); Hoard's own file operations drop the folder and its sub-folders; pairing another server never reuses the old server's cached items; an older server (no `items-by-path`) reads nothing; a folder not on the server reads one file and is not tried again for a day; a disagreeing second file is not trusted; another SMB GUID relearns, unpairing forgets; a gone NAS folder id (by-path 404) is forgotten and learned again at once; a lookup outside the mapping forgets it (the item still goes as a server item); a share root over a big library stops after 4 query pages and asks the rest by path |
| `:core:server` `DualWriteTest` | re-dating takes only pending changes with the sidecar's values; push then NAS import: no revision, nothing pulled or pushed again; NAS import first (offline): the push is `unchanged`, the pull brings the same values; a newer decision from Reveal wins over an older sidecar and nothing loops |
| `:core:server` `GalleryMigrationTest` | `gallery.db` 1 → 2 (`MigrationTestHelper` against `core/server/schemas/`), the queue kept, the new tables usable |
| `:native` `SmbRevealTest` | mapped shots by reference (the RAW's item), the rest as URIs; a shot whose RAW is not matched (JPEG matched) is looked up by the RAW, else sent whole as URIs, never as the JPEG's item; a rename candidate is looked up; an older Reveal or no pairing: URIs as before; unmatched shots looked up, then by what the lookup said; at most 20 lookups; 1,200 mapped shots + 1 local photo → batches 500 / 500 / 200 and one URI; shots grouped by folder + stem |
| `:native` `MappedCullingTest` | only mapped shots go to the server, with their times (an unmark too); a newer server value shows on the folder, an older one does not; our own pending changes are not shown back; 3,000 mapped shots read their server sides in 6 + 6 `IN (...)` queries |

`./gradlew testDebugUnitTest lint verifyRoborazziDebug`: green (2026-11-15).

## End-to-end run (2026-11-15, cachyos-fps)

- **hoard-server 0.10.0** built from `origin/main` `server/` (`7ff51df`, `docker build`, image `hoard-server:smbmap-e2e`),
  temporary data directory, `127.0.0.1:3995` (`http://10.0.2.2:3995` from the emulator), `--cpus 2 --memory 1536m`,
  `NAS_INTERVAL=2m`; NAS folder `/mnt/nas/Imagens` = a synthetic tree (16 generated JPEGs, CC0: `2024/Trip` × 10,
  `2024/Beach` × 6) mounted read-only.
- **Samba** container (alpine + samba, user `hoard`), share `photos` = the directory **holding** `Imagens/` (so share
  path `Imagens/2024/Trip` is NAS path `2024/Trip`: the prefixes differ on both sides), `127.0.0.1:4462`.
- Emulator Pixel API 36 (`-read-only -memory 2048 -no-window`, port 5580), Hoard 2.2.0 debug, data cleared; a **test
  receiver** as `dev.hfps.reveal.debug` (an activity for `dev.hfps.reveal.action.DEVELOP` that logs every extra and
  ClipData URI), versionCode 90000, later 80000. Reveal's `main` is 0.8.0 and has no server-reference receiver yet,
  so no real Reveal ≥ 0.9.0 build exists to test with.

| Step | Result |
| --- | --- |
| Pair (pasted link), add the machine `10.0.2.2:4462` | 16 items synced |
| Open `photos/Imagens/2024/Trip` in Files | `HoardServerPaths: … 10 of 10 files on the server (folder fae5d5b6…, 2 files read)`; the server saw 2 `POST /v1/items/lookup` and 1 `GET /v1/items`; every row has the cloud mark (`01-files-on-server.png`) |
| Open `…/2024/Beach` | `6 of 6 files on the server (… 0 files read)`: the stored mapping, no lookup |
| Culling grid of Trip | cloud mark on every shot (`02-cull-grid-on-server.png`) |
| DSC01001 4★ + pick, DSC01002 reject | `queued … for the server` ×3; one `POST /v1/culling`; sidecars written (Save ratings on). Server `culling_fields`: rating 4 / pick at `modified_at` 1794731553824 and reject at 1794731557869, **equal to the sidecars' mtimes** (1794731553.824…, 1794731557.869…): the re-dating worked |
| Server NAS walk (reindex) | `"hashed":2 … "culling":0`: both sidecars read, **no culling write**; revs 17 / 18 / 19 unchanged, only the fields' `device_id` moved to `nas` in place (ADR 0016 step 2) |
| Hoard sync after the walk | `pulled=4 pushed=0` (the two sidecars as new item members); the sidecars' mtimes unchanged; still one `POST /v1/culling` in total: **no ping-pong** |
| Files: select DSC01001, DSC01003 and `NEW_0001.JPG` (added on the share, not walked yet) → Edit in Reveal | `looked up 1 shots, 0 on the server`; one intent: `SERVER_REF_VERSION` 1, `SERVER_ID`, `SERVER_ITEMS` × 2 (`itemId`, `fingerprint`, `name`), ClipData = `NEW_0001.JPG` + its sidecar only, flags READ + PERSISTABLE |
| Culling viewer, DSC01002 → Edit in Reveal | `SERVER_ITEMS` × 1, `INITIAL_ITEM` = its item, **no ClipData, flags 0**; after the fix below, no "read through Hoard" confirmation |
| Same with the receiver at versionCode 80000 | URIs as before: `INITIAL_URI`, ClipData (photo + sidecar), `CULLING_VERSION` 1, no server extras |
| Hoard restarted, Trip opened again | `11 of 11 … 0 files read` (the stored mapping; the 2-minute walk had indexed `NEW_0001.JPG` by then) |
| Settings › Photo server › Mapped folders | "E2E-Samba › photos/Imagens — On the server: Imagens" (`03-settings-mapped-folders.png`); Forget → "None yet" (`04-settings-forgotten.png`) |

Request counts on the server for the whole run: `server-routes.txt`.

**Found and fixed during the run:** the SMB confirmation ("photos are read through Hoard…") was shown even when every
photo went as a server item; it is now skipped when the folder's cached resolution maps every photo and Reveal takes
references.

**Observed, not changed (since 1.10.0):** a URI hand-off with Save ratings on creates a writable sidecar for each shot
sent, holding the shot's decision even when it has none (`xmp:Rating="0"`, `digiKam:PickLabel="0"`). The server's next
walk imports those as explicit `rating 0` / `flag none` writes by `nas` (here for `NEW_0001.JPG`, two revisions). The
values are the defaults, so nothing visible changes, but each such sidecar costs revisions. Worth an ADR 0011 / 0016
follow-up (skip creating empty sidecars, or let the import skip default values on a first read).

**Not verified end to end:** a real Reveal ≥ 0.9.0 reading the items (none exists yet); a decision made on another
device appearing in an open folder culling session (covered by `MappedCullingTest` only); the hand-over of decisions
made before a share was mapped (`MappedCullStore.handOver`, which reuses the Gallery's JVM-tested `Handover.step`; not
exercised on the emulator); renames and the gone-folder relearn (JVM only).
