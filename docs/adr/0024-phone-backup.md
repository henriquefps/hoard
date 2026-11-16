# ADR 0024: Phone backup and free up space

- **Date**: 2026-11-15
- **Status**: Proposed (Hoard 2 phase 4; hoard-server 0.11.0, Hoard 2.3.0–2.5.0, Reveal 0.10.0)
- **Builds on**: ADR 0012 (compatibility promise), 0013 (protocol: blobs, uploads, lookups, deleting an original),
  0014 (items, sources, pairing rule), 0015 (previews), 0017 (physical devices, liveness), 0018 (web gallery),
  0021 (Gallery: `DeviceIndexer`, `gallery.db`, caches, "Seams"), 0022 (SMB folders); Reveal ADR 0018 ("Originals
  backup", FIX/P, FIX/W "Hand-off backup", W3/C, W5) and Reveal ADR 0027.
- **Amends, when accepted**: ADR 0013 (additions under `/v1`, written there as "Phone backup (hoard-server 0.11.0)" in
  the server PR), ADR 0014 (a third item source, `mirror`), ADR 0017 (a physical device with a backup is kept;
  `physicalDevice.backup` in `GET /v1/device`), ADR 0021 (`gallery.db` version 3).
- **Next to**: ADR 0023 (Hoard's trash, written in parallel). Free up space is not the trash (see "Free up space").

## Context

The phone (camera, screenshots, WhatsApp) is the one place in the owner's library with no backup of its own. The
server already has the pieces: a content-addressed blob store with chunked, resumable uploads (ADR 0013 "Blobs"), the
fingerprint lookup that finds what the NAS already holds, items with locations (ADR 0014), previews for every item
(ADR 0015) and one identity per phone (ADR 0017). Hoard has the phone's index (`DeviceIndexer`, `device_media` with
lazy `v1:` fingerprints) and a Gallery that shows server items without downloading them (ADR 0021).

Reveal already backs up originals (Reveal ADR 0018): device-only photos of folders with its per-folder backup on, every
edited photo of any folder, hand-offs per "Photos opened from other apps", under "Originals over Wi-Fi only", as blobs
in `<backup root>/reveal-sync/blobs/`. One phone must not upload the same file twice through two apps, nor run two
backups that disagree.

Owner's decisions this ADR designs within: he picks folders; photos and videos only; the server mirrors each chosen
folder unchanged under `<backup root>/<physical device>/<path on the phone>`; content the server already has is never
uploaded (a hard link on the same filesystem, else an index reference); other devices browse backups from previews,
stream originals on open, keep a bounded cache and copy back only on "Save to this device"; WorkManager with optional
unmetered-network and charging rules; resumable chunked uploads after Reveal's `BlobUploader`; a status per folder and
per file; "free up space" deletes from the phone only what is verified on the server by checksum, oldest first, with a
preview.

Deployed layout constraints (`server/docker-compose.yml`, `server/README.md`): `/data` (database, previews) and the
backup root (`/mnt/backup`) are separate volumes; the NAS is mounted **read-only** at `/mnt/nas` on another
filesystem. So the blob store and a mirror tree, both under the backup root, share a filesystem and can hard-link;
NAS content can only be referenced.

Input from Reveal's orchestrator (agreed by the coordinator) is designed in: one content-addressed store matched by
`sha256` and the `v1:` fingerprint; the mirror as a path index; in-flight upload dedupe; Reveal delegating covered
folders after a grace window, learning them from `GET /v1/device`; ownership so neither app's delete removes the other's
backup; storage counted once.

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Store | **One content store**: the existing blob store (`reveal-sync/blobs/<sha>`) holds every uploaded byte once, whichever app sent it. The **mirror** is a path index, `(physical device, volume, path) → content`, plus a browsable tree of hard links into that store. No second store, no copy. |
| Mirror tree | `<backup root>/<Device name> (<first 8 hex of physicalDeviceId>)/<path on the phone>`; SD cards under `SD card <UUID>/`. Name derived once, stored, never renamed by itself. |
| Content the server has | Uploaded bytes (any app, any device): a hard link in the tree, no upload. A NAS file: an index reference (no file in the tree; listed in the tree's manifest). Matched by fingerprint, confirmed by SHA-256. |
| Deletes, moves, renames on the phone | Kept: the mirror is a backup, not a sync. A deleted file stays (marked "deleted from the phone"); a move or rename moves the entry (no duplicate, no upload); a file rewritten in place keeps its previous content as a previous version. |
| Items | A third item source, `mirror`, grouped by the ADR 0014 pairing rule; items gain `mirror` locations. The tablet's Gallery and the web gallery show the phone's folders from them. |
| Identity | `v1:` fingerprint to match (already computed lazily by `DeviceIndexer`); full SHA-256 only for files that must be uploaded and for free-up verification; files ≤ 2 MiB get it in the fingerprint's own read. |
| Reveal | **Server-side unification plus delegation**: the shared store makes a second upload of the same bytes impossible (`exists`), and Reveal stops uploading from folders Hoard mirrors (after a 24 h grace for edited photos). Reveal's existing blobs stay as they are and are linked into the mirror when Hoard's report names them. |
| Ownership | Computed, not counted: bytes are kept while a mirror entry, a device claim (an app's own upload), a live photo record or a preview names them. `DELETE /v1/originals` is refused (`409`, reason `mirror`) while a mirror entry names the file. |
| Free up space | Permanent deletion through `MediaStore.createDeleteRequest` (one system consent per batch), Android 11+; only shots whose every file the server has re-hashed and matched; not the system trash, not `IS_TRASHED`, not Hoard's trash. |
| Security | A device writes only its own physical device's mirror (other roots answer `404`); no device key can delete backed-up content (only the admin page can); revoking a key stops its uploads and leaves its mirror. |

## The mirror

### Layout on disk

```
<backup root>/
  reveal-sync/blobs/ab/cd/abcd…          the content store (ADR 0013, unchanged), every uploaded byte once
  reveal-sync/uploads/<id>.part          unfinished uploads (unchanged)
  Pixel 8 (a0e4c1d2)/                    one folder per physical device
    DCIM/Camera/PXL_20261101_101500.jpg  hard link to its blob
    Pictures/Screenshots/…
    Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-20261101-WA0001.jpg
    SD card 1234-ABCD/DCIM/…             another volume of the same phone
    .hoard-mirror/manifest-<rootId>.tsv  every entry of a root: path, size, sha256, fingerprint, where, on the phone
    .hoard-mirror/previous/<path>~<sha8>  content a path held before it was rewritten (hard links)
```

- **Device folder name**: the physical device's name (ADR 0017: the name of its oldest live device, e.g. "Pixel 8"),
  NFC, with `/ \ : * ? " < > |` and control characters replaced by `_`, leading and trailing dots and spaces trimmed,
  cut to 48 bytes of UTF-8 at a character boundary (`Device` when empty), then ` (` + the first 8 hex digits of
  `physicalDeviceId` + `)`. It is readable, unique (the id suffix; it can never be `reveal-sync`), stable (computed when
  the physical device's first root is made and stored in `mirror_devices.dir_name`; renaming the phone later changes
  nothing on disk). The admin page can rename it (one `rename` inside the backup root).
- **Path on the phone**: the path relative to its storage volume, exactly as the phone has it (`DCIM/Camera/…`). The
  primary volume has no prefix; another volume (an SD card, `vol-<uuid>` in ADR 0005) goes under `SD card <UUID>/`.
  On the wire a volume is `"primary"` or the volume's UUID as Android shows it (`1234-ABCD`, compared without case),
  so it matches both MediaStore (`VOLUME_NAME`) and SAF document ids (`primary:DCIM/Camera`, `1234-ABCD:DCIM`), which
  Reveal uses.
- **Hard links** are made with `os.Root.Link` from the store's file to the mirror path (both inside the backup root,
  so on one filesystem in the documented layout). When linking fails (`EXDEV`, `EPERM`, a filesystem without hard
  links, a device folder the owner mounted elsewhere) the entry stays in the index as `blob` without a link and is
  listed in the manifest; the server never falls back to copying. Blobs become mode `0400` (the store never rewrites a
  blob; a hard link shares the mode, so the tree cannot be used to alter a backup by accident). Existing blobs are
  `chmod`ed lazily when first linked.
- **NAS content** has no file in the tree: the NAS is read-only and on another filesystem, and a symbolic link to
  `/mnt/nas/…` means nothing outside the container (and the walk skips links). The manifest lists it as
  `nas:<folder name>/<path>`. The tree plus the manifests are a complete description of a phone's backup without the
  database, for disaster recovery.
- Mirror files and `.hoard-mirror/` are written only by the server, through `os.Root` opened on the device folder,
  from names in the database; never from a request path directly (see "Security").

### The index (schema 9, `0009_mirror.sql`)

```sql
CREATE TABLE mirror_devices (           -- outlives its physical device row while entries exist
    physical_id TEXT PRIMARY KEY,
    dir_name    TEXT NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL) STRICT;

CREATE TABLE mirror_roots (             -- a folder the owner chose on a phone
    id             TEXT PRIMARY KEY,    -- UUID
    physical_id    TEXT NOT NULL REFERENCES mirror_devices (physical_id),
    volume         TEXT NOT NULL,       -- 'primary' or the volume UUID, upper-case
    path           TEXT NOT NULL,       -- relative, '/'-separated, no leading or trailing '/'; '' = the whole volume
    created_by     TEXT NOT NULL,       -- device id
    created_at     INTEGER NOT NULL,
    paused_at      INTEGER,
    stopped_at     INTEGER,             -- no new entries; the mirror stays
    last_report_at INTEGER,
    UNIQUE (physical_id, volume, path)) STRICT;

CREATE TABLE mirror_entries (           -- one file of a phone, current or kept
    id              INTEGER PRIMARY KEY,
    physical_id     TEXT NOT NULL,
    volume          TEXT NOT NULL,
    path            TEXT NOT NULL,
    root_id         TEXT NOT NULL REFERENCES mirror_roots (id),
    fingerprint     TEXT NOT NULL,
    sha256          TEXT,               -- of the phone's bytes, when the phone sent it or the store proved it
    size            INTEGER NOT NULL,
    modified_at     INTEGER NOT NULL,   -- the phone's mtime (ms)
    store           TEXT NOT NULL,      -- 'blob' | 'nas' | 'none' (waiting for an upload)
    linked          INTEGER NOT NULL DEFAULT 0,
    on_device       INTEGER NOT NULL DEFAULT 1,
    removed_at      INTEGER,
    removed_reason  TEXT,               -- 'deleted' | 'moved' | 'freed' | 'replaced'
    verified_sha256 TEXT,               -- what the server's own re-hash of its copy found
    verified_at     INTEGER,
    reported_by     TEXT NOT NULL,      -- device id
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL) STRICT;
CREATE UNIQUE INDEX mirror_entries_path ON mirror_entries (physical_id, volume, path) WHERE removed_reason IS NULL
    OR removed_reason IN ('deleted', 'freed');   -- one entry owns each path in the tree
CREATE INDEX mirror_entries_fp ON mirror_entries (fingerprint);
CREATE INDEX mirror_entries_sha ON mirror_entries (sha256) WHERE sha256 IS NOT NULL;
CREATE INDEX mirror_entries_waiting ON mirror_entries (fingerprint) WHERE store = 'none';

CREATE TABLE original_claims (          -- who asked the server to keep an original (ownership, below)
    fingerprint TEXT NOT NULL,
    device_id   TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    PRIMARY KEY (fingerprint, device_id)) STRICT, WITHOUT ROWID;
INSERT INTO original_claims SELECT o.fingerprint, coalesce(b.device_id, ''), b.created_at
    FROM originals o JOIN blobs b ON b.sha256 = o.sha256;          -- everything uploaded before 0.11.0 is claimed

ALTER TABLE nas_files ADD COLUMN sha256 TEXT;          -- full hash, made only for verification (below)
ALTER TABLE nas_files ADD COLUMN sha_mtime INTEGER;    -- the mtime it was made for
```

Additive only: no existing table changes meaning; `originals`, `blobs`, `uploads` are untouched and
`server/testdata/compat/` keeps opening. ADR 0017's rule "a physical device with no device left is deleted" gains
"unless it has a `mirror_devices` row" (its backup stays addressable after its last app is revoked or moved).

### Entry rules

- **New file** with a fingerprint the server can serve: `store = blob` (an `originals` row has it; linked into the tree)
  or `store = nas` (an indexed, enabled NAS file has it). Otherwise `store = none` and the phone uploads it; when any
  upload of that content completes (from this phone, another phone, or Reveal), every waiting entry with that
  fingerprint (and, when the entry has one, that `sha256`) becomes `blob` and is linked. Content in both places prefers
  `blob` (bytes the server controls).
- **Deleted on the phone** (reported `removed`, reason `deleted`): `on_device = 0`; the file stays in the tree and the
  index. Nothing is ever dropped because the phone dropped it.
- **Moved or renamed**: reported as a removal (reason `moved`) plus a new path, in any order and within 24 hours; the
  server also infers it when a removed entry's content is present at another current path of the same phone. The old
  path leaves the tree (its link is removed; the content is at the new path, linked without an upload).
- **Rewritten in place** (same path, new fingerprint): the old entry becomes `replaced`; its link moves to
  `.hoard-mirror/previous/<path>~<sha8>`; the new content takes the path. The old content stays an item of its own
  (ADR 0014 "Item id": a new fingerprint is a new item).
- **Freed** (reported reason `freed`, see "Free up space"): like deleted, and the entry is **pinned**: the admin page's
  purge warns that these are the only copies, and the content's ownership can never drop to nothing through a device.
- **Restored** to the phone later (the same content at the same or another path): a current entry again, no upload.

### Mirror items (ADR 0014 amendment)

- A third value of `item_sources.source`, `mirror`, next to `nas` and `backup`: one source per (root, directory,
  lower-case stem), with `folder_id` = the root id, built by the same grouping as the NAS walk (`items.Syncer`, the
  pairing rule of ADR 0014: a phone's DNG + JPEG pair or a Live Photo-style still + video are one shot). Only entries
  with `store` `blob` or `nas` are members; a waiting entry is not an item yet. Deleted-on-phone entries stay members
  (the shot is still backed up). `SyncBackup` makes an uploaded original join a live NAS **or mirror** item that has
  its fingerprint (today: NAS only), so a phone upload never becomes a second, ungrouped item.
- A file of a mirror item gains a location:

  ```json
  {"source": "mirror", "physicalDeviceId": "a0e4…", "deviceName": "Pixel 8", "rootId": "…",
   "volume": "primary", "path": "DCIM/Camera/PXL_20261101_101500.jpg", "modifiedAt": 1730455000000,
   "onDevice": true}
  ```

  next to the `backup` (`sha256`) or `nas` location that holds the bytes. `onDevice: false` means the phone no longer
  has it (deleted or freed). Clients ignore unknown sources (Hoard's models default every field; Reveal's
  `ItemLocationRecord` keeps `source` as a string and only looks for `nas`).
- Report writes are applied with the items they touch in transactions of 500 sources, as the walk does; a phone's first
  report of 20k files makes about as many item revisions as a first NAS walk.
- `GET /v1/items?folder=<rootId>&path=<dir>` accepts a root id where it took a NAS folder id (a root id was a `400`
  before), `path` relative to the root. The web gallery's Folders show a **Devices** section (device › volume › root
  path), its filters gain "Where: phone backups" and a "Deleted from the phone" badge (ADR 0018 amendment in the S2 PR).

## Identity: fingerprint vs SHA-256

| | `v1:` fingerprint | SHA-256 |
| --- | --- | --- |
| What | size + first MiB + last MiB (ADR 0014) | every byte |
| Used for | matching: is it on the server, which item, the report's key | upload (the blob's name, `POST /v1/uploads` needs it first), verification for free up |
| When the app computes it | already, lazily, for every indexed phone file (`DeviceIndexer.startFingerprints`: never at start-up, 15 s after, newest first, one file at a time) | (a) files ≤ 2 MiB: in the fingerprint's own read, at no extra I/O; (b) larger files: only when the server answers `missing` (just before the upload) or when free up needs it (just before verification) |
| Cost on ~20k phone files | at most 2 MiB each, ≈ 35–40 GiB of reads once, already paid by the Gallery since 2.1.0 | only for what is uploaded or freed: with typical ~4 MB photos and some videos, ≈ 80–150 GB for a whole phone, read once at the throttle below |

Rules that keep start-up and the phone quiet:

- Hashing runs only inside `BackupWorker` (WorkManager), never in the app's start path or a screen.
- Batches of 50 files with a yield and the worker's stop check between files; at most one file read at a time.
- Throttle: 32 MiB/s while not charging, unthrottled (one core, one file) while charging. SHA-256 through the platform
  `MessageDigest` (BoringSSL with the ARMv8 SHA-2 instructions) runs at roughly 1–2 GB/s, so storage reads and the
  throttle, not the CPU, set the pace. Numbers are estimates until phase A1 measures them on the emulator and the
  owner's phone.
- A hash is valid for the size and mtime it was made for (`sha_size`, `sha_modified`, like `fp_size`, `fp_modified`);
  a changed file is hashed again only when it is needed again.
- Uploads read the file twice (hash, then send) because `POST /v1/uploads` takes the hash first (frozen). For photos
  the second read is mostly page cache; for large videos it is a real second read, accepted.

## Protocol: `/v1` additions (hoard-server 0.11.0)

All additive. `GET /v1/server` `features` gains `"backup"`, and a `backup` object with the limits:
`{"maxRoots": 64, "maxReportEntries": 1000, "maxVerify": 500, "maxPath": 1024}`. Without the feature (an older
server) the app hides backup and free up. Every route needs a device key; a revoked key is `401` as everywhere.

**Paths** (roots, entries, removals): relative, `/`-separated, NFC, at most 1024 bytes, segments at most 255 bytes;
refused (`422 invalid_request`, `details.index`) when absolute, empty (except a root's `""`), with `.` or `..`
segments, a segment starting with `.`, a backslash, a NUL or control character. The server normalises `a//b` to `a/b`
first. Volumes: `primary` or `^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$|^[0-9a-f-]{36}$` (stored upper-case).

**Ownership of a root**: a root belongs to a physical device. Every route that names a root answers `404 not_found`
when the root is unknown **or belongs to another physical device** (no `403`: a device learns nothing about roots it
does not own through writes). Reads of other devices' roots go through `GET /v1/backup/roots` only.

### `GET /v1/backup/roots`

Every root of every physical device (the tablet browses the phone's), stopped ones included:

```json
{"roots": [{"id": "…", "physicalDeviceId": "a0e4…", "deviceName": "Pixel 8", "mine": true,
            "folder": "Pixel 8 (a0e4c1d2)", "volume": "primary", "path": "DCIM/Camera",
            "createdBy": "5c1d…", "app": "hoard", "createdAt": 1731600000000,
            "paused": false, "stopped": false, "active": true, "lastReportAt": 1731686400000,
            "stats": {"files": 12034, "bytes": 52428800000, "stored": 11050, "nas": 950, "waiting": 34,
                      "removedFromDevice": 812, "freed": 0, "verified": 0}}]}
```

`active` = not paused, not stopped, and a report from a live device of that physical device in the last 7 days.
`mine` = the root belongs to the caller's physical device.

### `POST /v1/backup/roots` `{"volume", "path"}`

Starts mirroring a folder of the caller's phone. `201` with the root; `200` with it when it exists (a stopped or
paused root is resumed). A path inside an existing root of this phone: `409 conflict`, `details: {"reason": "covered",
"rootId"}`; a path containing existing roots absorbs them (their entries move to the new root, `200`/`201` with
`details.absorbed`). More than `maxRoots`: `422 invalid_request`. No backup root configured: `503
storage_not_configured`. The first root of a physical device creates its `mirror_devices` row and folder.

### `PATCH /v1/backup/roots/{rootId}` `{"paused": bool}` and `DELETE /v1/backup/roots/{rootId}`

Pause and resume (`200` with the root). `DELETE` **stops** the root (`200 {"status": "stopped"}`): no new entries; the
mirror, its files and its items stay. Deleting backed-up content is the admin page's alone ("Security").

### `POST /v1/backup/report`

What the phone has, in batches. Idempotent; the order of batches does not matter.

```json
{"rootId": "…",
 "entries": [{"path": "DCIM/Camera/PXL_20261101_101500.jpg", "fingerprint": "v1:…", "size": 4123456,
              "modifiedAt": 1730455000000, "sha256": "…"}],
 "removed": [{"path": "DCIM/Camera/old.jpg", "reason": "deleted"}]}
```

- `entries` at most `maxReportEntries`, `removed` at most as many. `sha256` optional (send it when known). `reason`:
  `deleted`, `moved`, `freed` (only after a free-up deletion the owner confirmed).
- The phone reports a removal only when it knows: the file left MediaStore while its volume was mounted and the listing
  of that root was complete and not empty (the rule of contract 12 for sidecars). The server never infers a deletion
  from silence.
- Answer `200`:

```json
{"results": [{"index": 0, "path": "DCIM/Camera/PXL_20261101_101500.jpg", "state": "stored",
              "itemId": "…", "verified": "no"}],
 "removed": [{"index": 0, "path": "DCIM/Camera/old.jpg", "state": "kept"}]}
```

  `state`: `stored` (the bytes are in the store and linked), `nas` (an indexed NAS file has them), `missing` (upload
  it), `in_progress` (another device's upload of this content is running; with `retryAfter`), `differs` (the server
  has this fingerprint with another SHA-256 than the one sent: upload it; both contents are kept). `verified`: `yes`
  (the server's own re-hash matched this file's `sha256`), `pending`, `no` (never asked, or the phone sent no hash).
  Removal `state`: `kept`, `moved` (to a current path of this phone), `unknown` (no such path; ignored).
- A malformed entry is `422 invalid_request` with `details.index` and nothing is applied (like `POST /v1/changes`).
  A stopped root: `409 conflict`, reason `stopped`. Calling it updates `lastReportAt`.

### Uploads: `POST /v1/uploads` gains `wait`

An original from the backup is uploaded exactly as Reveal does (`POST /v1/uploads` `{"sha256", "size", "kind":
"original", "fingerprint"}`, `PATCH` chunks, `HEAD` to resume). Two optional additions:

- `"wait": true`: when **another device** has an unfinished upload of the same `sha256` that received a chunk in the
  last 10 minutes, the answer is `200 {"status": "in_progress", "retryAfter": 300}` with `Retry-After`, instead of a
  second parallel upload. Without `wait` (every released client) nothing changes: a client that does not know the
  status never sees it. A stalled upload (no chunk for 10 minutes) does not block anyone.
- `"claim": false`: the upload is for the mirror only and adds no device claim (below). Hoard's backup sends it;
  Reveal's uploads (absent = `true`) claim as today.

The completion of any original upload links the waiting mirror entries of that content (above).

### `POST /v1/backup/verify` `{"entries": [{"rootId", "path", "sha256", "size"}]}`

For free up: is the server's copy of each file byte-identical to the phone's, proven now? At most `maxVerify`.

```json
{"results": [{"index": 0, "verified": "yes", "verifiedAt": 1731700000000},
             {"index": 1, "verified": "pending", "retryAfter": 30},
             {"index": 2, "verified": "no", "reason": "differs"}]}
```

- The server compares the phone's `sha256` with its own copy's **re-hash** (not the database's belief): a blob is read
  and hashed; a NAS file is read and hashed only if its size and mtime still match the index (else `no`,
  `changed_on_nas`, and the walk is triggered). The result is kept (`verified_sha256`, `verified_at`, and
  `nas_files.sha256` for the NAS file) and re-hashed at most once per 30 days per content. Hashing runs in a bounded
  background queue (one file at a time, low priority, as the previews); `pending` until done.
- `reason`: `differs`, `missing` (the server has no copy: blob gone, entry `none`), `changed_on_nas`, `unavailable`
  (share offline), `not_found` (no such entry).
- **NAS-only content and free up**: by default the server makes its own copy before answering `yes` for an entry whose
  only copy is on the NAS: it copies the NAS file into the store (server-side, local disk to disk, no phone upload),
  checks its SHA-256 and links it (`store` becomes `blob`), so a phone file is never freed onto a share the server
  cannot keep. Server setting "Keep a copy of NAS files freed from a phone" (on; see "Open questions").

### `GET /v1/device` gains `physicalDevice.backup`

For Reveal's delegation (and Hoard's settings), the device view adds the caller's physical device's roots:

```json
"physicalDevice": {"id": "a0e4…", "name": "Pixel 8", "devices": […],
  "backup": {"roots": [{"id": "…", "volume": "primary", "path": "DCIM/Camera", "app": "hoard",
                        "active": true, "since": 1731600000000}]}}
```

Absent when the server has no `backup` feature; `roots: []` when the phone has none. `since` = the root's creation.

### `DELETE /v1/originals/{fingerprint}`: a new refusal

`409 conflict`, `details.reason = "mirror"`: a mirror entry (current, deleted or freed) names this content. Nothing is
deleted; the calling device's claim on it is released (so the bytes go when the mirror no longer needs them). Released
Reveal treats a `409` as "referenced" and stops after 20 attempts, still believing the server has the file, which is
true. The other answers are unchanged.

### Errors and limits

New codes: none. New `details.reason` values: `covered`, `stopped` (`409 conflict`), `mirror` (`409` on
`DELETE /v1/originals`). New status: `in_progress` (upload create with `wait`). Limits in `GET /v1/server`
`backup`. `server/testdata/v1-routes.txt` gains `GET /v1/backup/roots`, `POST /v1/backup/roots`,
`PATCH /v1/backup/roots/{rootId}`, `DELETE /v1/backup/roots/{rootId}`, `POST /v1/backup/report`,
`POST /v1/backup/verify`. Each is written into ADR 0013 and `server/api/openapi.yaml` in the server PR.

## Ownership and storage accounting

Bytes are kept while **anything** names them, computed by query at the moment of a delete (no counters to drift):

1. a mirror entry (any state) with that `sha256` or fingerprint;
2. a device claim (`original_claims`): a device that uploaded or registered the original with `claim` true (Reveal
   today; every original stored before 0.11.0 is backfilled as claimed);
3. a live photo record naming the fingerprint (the existing `originalNamed` check);
4. a live photo record naming a preview (`previewReferenced`, for previews).

So a Reveal delete never removes Hoard's backup (rule 1 refuses it), and an admin purge of a phone's mirror never
removes an original Reveal claimed or a record names (rules 2–3). `DELETE /v1/originals` with no mirror entry removes
every device claim and the `originals` row as today (any paired device may delete, as since 0.5.3).

Accounting counts each content once: the Storage tab sums `blobs.size` (one row per `sha256`, as now); hard links take
no space (and `du` counts a hard-linked file once). Per phone, the admin page shows "uploaded from this phone" (blob
contents only its entries name), "shared" (blob contents also named by another phone, a claim or a record) and
"on the NAS" (references), plus pinned (freed) entries.

## Security

- **A device writes only its own physical device's mirror**: roots are looked up with the caller's `physical_id`;
  others are `404`. Entry paths are validated (above), joined to the root's volume and path from the database, and
  written through `os.Root` opened on the device folder (which refuses `..` and links leaving it), so no request can
  name a file outside its phone's tree.
- **No device can delete a backup.** Devices can stop and pause roots and report removals; nothing a key can send
  removes a mirror entry or its bytes. A stolen phone's key can at most add files to its own tree, mark files as gone
  from the phone (kept anyway) or abandon uploads. Deleting backed-up content is the admin page's (Devices › a phone ›
  Backup › Delete), with the count of pinned entries shown first.
- **Reading** follows the existing trust model: every paired device reads every item (one owner, ADR 0013). A partner's
  phone paired to the same server sees the owner's phone backups; separate servers, or per-device read scopes in a
  later ADR, are the answer if that changes (open question).
- **Revoking** a device: its next request is `401` (unchanged); its unfinished uploads expire after 7 days; its
  roots, entries and files stay, readable by the other devices; `active` turns false after 7 days without a report from
  a live device of that phone, so Reveal on the same phone takes its folders back ("Reveal"). The physical device and
  its mirror folder outlive its last device (`mirror_devices`). A re-paired Hoard on the same phone claims the old
  physical device (ADR 0017 question) and continues the same roots: its report matches entries by path and content, so
  nothing is uploaded again.
- **Claims and moves** (ADR 0017): when a device moves to another physical device and it was the last live device of
  the old one, the old physical device's roots move with it (`mirror_roots.physical_id` updated; the folder on disk
  keeps its name); a root with the same volume and path on both sides is merged (current entries by path, the newer
  `modified_at` wins the path, the other becomes `replaced`). When other live devices remain there, the roots stay. A
  claim into another phone's physical device is possible today for any paired device (ADR 0017); it is the owner's
  server, and the admin page undoes it. Logs never carry phone paths or file names (ADR 0013 "Logs").

## Reveal: one backup per phone

### What was weighed

| Option | Verdict |
| --- | --- |
| Hoard owns the files; Reveal's upload becomes a reference when the content is backed up | Half of the answer: the store already turns a second upload into `exists`; but Reveal would still read and hash the file, and two apps would show two backup settings for the same folder that can disagree. |
| Reveal delegates its originals backup to Hoard when Hoard is installed and paired | Needs IPC or a shared setting between apps signed with different keys (ADR 0017 rejected both); breaks when Hoard is paused, unpaired or uninstalled. |
| Server-side unification: blobs and mirror files one content-addressed store, the mirror a view | Makes duplicates impossible at the byte level, needs no migration, keeps both apps working alone. |
| **Chosen: unification + delegation through the server** | The store is one (option 3). Reveal learns from the server which of its phone's folders Hoard mirrors (`GET /v1/device` `physicalDevice.backup`) and stops uploading from them; the server, not an IPC channel, is the shared truth, and it ages out (`active`) when Hoard stops. |

### Rules

- **Covered**: a Reveal source folder is covered when its volume and path are inside an `active` root of its physical
  device (`volume` equal without case; `path` equal or a prefix at a `/`). Reveal maps its SAF tree document id
  (`primary:DCIM/Camera` → `primary`, `DCIM/Camera`).
- **Covered folders**: Reveal does not upload their originals (its per-folder backup switch shows "Backed up by Hoard",
  read-only). **Edited photos** of a covered folder: Reveal waits; if the original is still missing on the server 24
  hours after the edit (Hoard paused, waiting for Wi-Fi), Reveal may upload it as today. The store makes that harmless:
  Hoard's next report finds `stored`, and `wait` keeps two uploads from running at once.
- **Not covered** (other folders, hand-offs per "Photos opened from other apps", a phone where Hoard has no root):
  Reveal keeps its own rules unchanged ("Originals over Wi-Fi only", per-folder backup, edited originals).
- **Reveal's blobs already on the server stay where they are.** They are already in the one store; when Hoard first
  reports a folder holding those files, the server finds them (`stored`), links them into the mirror and nothing is
  uploaded or copied. They are not turned into mirror entries without a phone path (the server cannot know one), and
  they keep their claims, so nothing Reveal backed up can disappear through the mirror.

### When only one app is installed, or they are not grouped

| Situation | Behaviour |
| --- | --- |
| Only Reveal | Unchanged: its own originals backup into the store. No mirror, no tree. |
| Only Hoard | The phone backup as in this ADR. |
| Both, grouped as one phone (ADR 0017) | Hoard mirrors its roots; Reveal delegates covered folders; one upload per content. |
| Both, not grouped (the owner answered "Another device", or Reveal is older than 0.10.0) | No delegation: both may upload, but never twice the same bytes (`exists`) and, for 0.10.0+, never at the same time (`wait`); the cost is a second hash on the phone. Hoard's backup settings say "Reveal on this phone is not grouped with Hoard" with a link to "This phone". |
| Hoard paused, stopped, unpaired or uninstalled | Its roots stop being `active` within 7 days; Reveal resumes its own backup of those folders. |

### For Reveal's orchestrator: what Reveal changes (Reveal 0.10.0, needs hoard-server 0.11.0, feature `backup`)

1. Read `physicalDevice.backup.roots` from `GET /v1/device` when `features` has `"backup"` (refreshed with the
   periodic sync); map each source's tree document id to `volume` + `path`; mark covered sources.
2. `backupCandidates`: skip photos of covered sources, except edited photos whose original is still missing 24 hours
   after the edit (`photos.has_edit`, the version's `edited_at`).
3. Settings › Sync: a covered folder shows "Backed up by Hoard" instead of its switch; "Originals over Wi-Fi only" is
   described as applying to what Reveal still uploads.
4. `POST /v1/uploads` with `"wait": true`; on `status: "in_progress"` leave the upload queued and retry after
   `retryAfter` (no failure count). `claim` stays absent (Reveal's uploads keep claiming).
5. `DELETE /v1/originals`: treat `409` reason `mirror` as done (drop the queued delete at once; the photo is backed up
   by the phone's mirror, so `on_server` stays true).
6. A **folder photo whose file vanished from the phone while the server holds it** (Hoard freed it) is adopted by the
   Server source in place, like W3/C's `adoptHandoffs`: same uuid, versions, edits, history and culling; files take the
   server's document ids. Never forgotten because its file left the folder. Then declare
   `PATCH /v1/device {"features": ["freed-adopt"]}`. Until a live Reveal on the phone has declared it, Hoard leaves
   out of free up every shot whose item has Reveal edits (ADR 0020 `edits`) and warns in the preview.
7. Tolerate `source: "mirror"` locations on items (already true: `ItemLocationRecord` keeps the string; the
   `nas`/`backup` choice in `SyncServerItems` is unaffected because a mirror location always sits next to one of them).
8. Tests: a fake server with `backup` (covered/uncovered sources, the 24 h rule, `in_progress`, `409 mirror`), and the
   adoption of a freed folder photo with edits.

Nothing in Reveal 0.9.0 breaks against hoard-server 0.11.0: every addition is optional, `TestRevealProtocolContract`
keeps replaying Reveal's `SyncApi` unchanged, and `server/testdata/v1-routes.txt` only grows.

## The app (Hoard)

### Modules and data

- `:core:server` `backup/`: `BackupPlan` (pure, JVM-tested: which files of which roots, which need a hash, upload,
  report or removal, next state), `BackupUploader` (after Reveal's `BlobUploader`: create → `exists` / `resume` /
  `in_progress`; chunks of the server's `chunkSize`, the offset saved after each; `HEAD` after a broken chunk; a
  process killed mid-way resumes from the stored upload id; `checksum_mismatch` marks the file `changed` and it is
  hashed again on the next run), `BackupWorker`, `FreeUpPlan` (pure). `ServerApi` gains the routes above.
- `gallery.db` version 3 (migration 2 → 3, schema exported to `core/server/schemas/`, migration test):
  `backup_roots` (server root id, volume, path, Hoard location and folder, added at, paused), `backup_files` (media id,
  path, root, `sha256` with `sha_size`/`sha_modified`, state, upload id, offset, server state, verified at, attempts,
  next attempt, error), `freed` (path, item id, fingerprint, bytes, freed at); `device_media` gains `sha256`,
  `sha_size`, `sha_modified` (filled for files ≤ 2 MiB by the fingerprint read).
- **Backup is not a transfer** (ADR 0007): the server is not a location, the destination path is the server's, the
  upload is content-addressed and protocol-specific, like the Gallery's downloads. "Save to this device" **is** a
  transfer (a server item to a phone folder) and goes through `TransferService` with a read-only server endpoint
  (`ServerFs`, a `TransferFs` over `GET /v1/originals/{fingerprint}` by range), so its progress, retries and
  conflicts are the engine's.
- `DeviceIndexer` indexes the backup roots too (a root implies its folder is in `device_folders`); hidden names and
  `DeviceFolders.EXCLUDED` (Hoard's copies for Reveal) are never backed up. Photos and videos only: the MediaStore
  image and video collections, as today. Files the system trash holds (`IS_TRASHED`) are absent for MediaStore's
  default queries and count as removed.

### Settings › Backup

- Folders: the phone's folders with photos or videos (grouped like the Gallery's: Camera, Screenshots, WhatsApp images
  and videos, others), each with a switch, and "Add a folder". A switch on calls `POST /v1/backup/roots`.
- Rules: "Only on Wi-Fi and other unmetered networks" (default on), "Only while charging" (default off). Always:
  battery not low.
- Status per folder: "11,050 of 12,034 backed up · 950 already on the server · 34 waiting for Wi-Fi", bytes, the last
  complete pass, a progress line while uploading; tapping opens the folder's files filtered by state. Status per file
  (`backup_files.state`, also a Gallery badge): waiting (no fingerprint yet), queued, hashing, uploading (percent),
  backed up, already on the server, verified, paused (by a rule, with which), failed (the reason: unreadable, changed
  while uploading, server full, server error). "Back up now" runs at once within the rules.
- Every string in `values/` and `values-pt-rBR/`; the hfps design system (`HfpsScaffold`, one card surface, eyebrow
  labels).

### `BackupWorker`

- Unique periodic work every 6 hours, plus a one-off "soon" (2 minutes after `DeviceIndexer` sees new media, debounced)
  and an expedited one for "Back up now"; constraints from the rules (`NetworkType.UNMETERED` or `CONNECTED`,
  `setRequiresCharging`, `setRequiresBatteryNotLow(true)`).
- A run: report new and changed files (fingerprint known) in batches of 1000; hash and upload the `missing` ones
  newest first; report removals (complete listings only); re-report what changed state. Long uploads run as a
  foreground `dataSync` service through `setForeground` (permissions already in the manifest); Android 15's daily
  `dataSync` budget ends a run early, and the next one resumes from the stored offsets.
- Never at start-up; the first run after enabling waits for its constraints.

### Other devices see backups (Hoard 2.4.0)

The tablet's Gallery already holds every item (ADR 0021). It gains: a **Devices** entry in the Gallery's folder list
(phone › volume › folders, from `GET /v1/backup/roots` and the items' `mirror` locations), the filter "Where: Pixel 8
backup", the badge "Deleted from the phone". Cells and the viewer use the server's previews; originals stream by range
(`HttpRangeSource`) and stay only within the kept-originals budget (`OriginalCache`); nothing is copied unless the
owner taps **Save to this device** (a folder chosen once, default `Pictures/From <device name>/`; never under
`Pictures/Hoard/`), which goes through `TransferService` as above. A saved file in a backed-up folder is linked into
that device's own mirror on its next report, without an upload.

## Free up space (Hoard 2.5.0)

- **Candidates**: phone shots in backed-up roots, oldest first (`taken`), every file of the shot (a pair, a motion
  photo's still) with `store` `blob` or `nas` and its phone file unchanged since hashed. Left out: shots taken in the
  last 30 days (setting), favourites (`IS_FAVORITE`), shots with pending culling changes (pushed first), and, until
  the phone's Reveal declares `freed-adopt`, shots whose item has Reveal edits.
- **Preview**: a screen listing what goes, by month, with counts and the bytes freed ("2,340 photos and 112 videos
  from before 15 Oct 2026 · 18.4 GB"), each shot's thumbnail, and per-shot exclusion. Nothing is deleted without it.
- **Verification just before**: the phone re-stats every file (hashing again what changed), then
  `POST /v1/backup/verify` for all of them; only `yes` answers within the last 10 minutes go. `pending` waits (the
  screen shows "Checking on the server…"); `no` stays on the phone with the reason.
- **Deletion**: `MediaStore.createDeleteRequest` with the verified files' content URIs, in requests of at most 1000;
  the system's dialog is the owner's consent ("Allow Hoard to delete these 1,000 items?"). Afterwards the phone checks
  which URIs are really gone and reports exactly those as `removed`, reason `freed`; a refused or partial request
  frees what the system deleted, no more.
- **Why permanent deletion and not the trash**: the point is space, and the system trash (`createTrashRequest`,
  `IS_TRASHED`) keeps the bytes on the phone for 30 days; the safety net is the server's verified, pinned copy, and the
  shots stay in the Gallery as server items (streamed, saved back with "Save to this device"). Hoard's trash (ADR 0023)
  is for things the owner deletes and may want back on the phone; mixing in freed files would make "empty the trash"
  delete nothing and "restore" mean two things. So free up does not reuse `IS_TRASHED`; the two features stay apart.
- **Why `createDeleteRequest` even with All files access**: it is one consent per batch from the system, the same on
  every phone and the same whether Hoard owns the files or not (camera and WhatsApp files are not Hoard's). Android 11
  (API 30) and later; on older Android free up is not offered (backup works there).
- After freeing, the Gallery shows those shots as server items (`DeviceIndexer` drops the rows; the item's mirror
  location says `onDevice: false`); `freed` keeps the local history for Settings ("18.4 GB freed").

## Build plan

Each phase is one PR with its tests, shippable alone.

| Phase | PR | Contents | Tests |
| --- | --- | --- | --- |
| S1 | hoard-server 0.11.0 | Schema 9; roots, report, verify, `wait`/`claim` on uploads, `409 mirror`, `physicalDevice.backup`; hard links with fallback and `0400`; manifests; `mirror` item source and `SyncBackup` joining mirror items; `GET /v1/items?folder=<rootId>`; admin page: per phone backup, purge, storage accounting, "Keep a copy of NAS files freed from a phone"; ADR 0013 section, `openapi.yaml`, `v1-routes.txt`. | Go: path validation and traversal on every input; a root of another phone `404`; link, `EXDEV` fallback (an injected linker), no copy; move, rename, rewrite, delete, restore; waiting entries linked on another device's upload; `wait` with a live and a stalled upload; verify with a corrupted blob and a changed NAS file; ownership (Reveal delete vs mirror, purge vs claim/record); revoke and claim/merge; `TestRevealProtocolContract` and `TestOpensRevealSyncDataDir` unchanged; `TestUpgradesHoardServer040DataDir`; the end-to-end test extended with a phone report, an interrupted upload and a verify. |
| S2 | hoard-server 0.11.x | Web gallery: Devices in Folders, "Where" filter, "Deleted from the phone" badge, strings in `en` and `pt-BR`. | `bun run lint/typecheck/test/e2e` with a synthetic phone mirror. |
| A1 | Hoard 2.3.0 | `gallery.db` 3; `BackupPlan`, `BackupUploader`, `BackupWorker`; Settings › Backup with status per folder and file; Gallery badges. | JVM: `BackupPlan`, `FreeUpPlan` later, the uploader against a fake server (resume, `in_progress`, checksum, 401), migration 2 → 3, Roborazzi goldens of the screen. Emulator against a local hoard-server container (`docs/testing/backup.md`): synthetic photos in `DCIM/Camera`, a NAS duplicate referenced not uploaded, the process killed mid-upload and resumed, a rename on the phone moved on the server, unmetered/charging rules. |
| A2 | Hoard 2.4.0 | Devices view, filter and badge; `ServerFs` and "Save to this device" through `TransferService`. | JVM: `ServerFs` in `TransferRunnerTest` style; emulator: two debug ids (`-Phoard.native.debugSuffix`) as phone and tablet. |
| A3 | Hoard 2.5.0 | Free up space: candidates, preview, verify, `createDeleteRequest`, report `freed`. | JVM: `FreeUpPlan` (exclusions, whole shots, staleness); emulator: free synthetic files, refuse a partial consent, a corrupted server copy not freed. |
| R1 | Reveal 0.10.0 | "For Reveal's orchestrator" above. | Reveal's own. |

S1 ships alone (nothing calls it until A1; Reveal 0.9.0 keeps working). A1 needs S1. R1 needs S1 and is independent
of A1–A3 except item 6, which must ship before A3 frees Reveal-edited shots (A3 gates on `freed-adopt`).

## Consequences

- The backup folder gains one top-level folder per phone next to `reveal-sync/`; it is browsable without the server,
  and its manifests describe NAS references and kept versions.
- Deleted phone files are kept forever by default; the backup grows with the phone's history (the admin page shows it
  per phone and can purge).
- Free up depends on the server's re-hash, so a damaged disk is found before a phone copy is deleted.
- Blobs become read-only files (`0400`); restoring a backup folder by hand must keep hard links (`cp -a`, `rsync -H`)
  or it will take the mirror's space twice.
- The `originals` table still keeps one `sha256` per fingerprint; a `v1:` collision (a file edited in place keeping its
  size, head and tail) is stored as a second blob and kept by its mirror entry's own `sha256`, not by the lookup.

## Open questions for the owner

1. Deleted-from-the-phone files: kept forever (proposed), or dropped after a period (one year?) unless freed?
2. Free up of photos the server holds only on the NAS: keep a server copy in the backup folder first (proposed: on),
   or trust the NAS as the backup?
3. Free up's defaults: keep the last 30 days on the phone, and keep favourites?
4. Which folders are on by default after pairing: none (proposed: the owner picks), or Camera and Screenshots?
5. Device folders directly under the backup root (proposed, as decided), or under one `phones/` folder?
6. Reveal's grace window for edited photos in covered folders: 24 hours?
7. A partner's phone on the same server sees every backup (today's model): acceptable, or should backups be readable
   only by the same physical device and the admin?
8. Videos: same network rule as photos, or "videos only while charging"?
