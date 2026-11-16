# ADR 0024: Phone backup and free up space

- **Date**: 2026-11-15
- **Status**: Accepted 2026-11-15 (Hoard 2 phase 4; hoard-server 0.12.0, Hoard 2.4.0–2.6.0, Reveal 0.10.0)
- **Builds on**: ADR 0012 (compatibility promise), 0013 (protocol: blobs, uploads, lookups, deleting an original),
  0014 (items, sources, pairing rule), 0015 (previews), 0017 (physical devices, liveness), 0018 (web gallery),
  0021 (Gallery: `DeviceIndexer`, `gallery.db`, caches, "Seams"), 0022 (SMB folders); Reveal ADR 0018 ("Originals
  backup", FIX/P, FIX/W "Hand-off backup", W3/C, W5) and Reveal ADR 0027.
- **Amends, when accepted**: ADR 0013 (additions under `/v1`, written there as "Phone backup (hoard-server 0.12.0)" in
  the server PR), ADR 0014 (a third item source, `mirror`), ADR 0017 (a physical device with a backup is kept;
  `physicalDevice.backup` in `GET /v1/device`), ADR 0021 (`gallery.db` version 4).
- **After**: ADR 0023 (trash; Hoard 2.3.0 and hoard-server 0.11.0, which take server schema 9, `gallery.db` 3 and
  `hoard.db` 4). Free up space is not the trash (see "Free up space"); how the two meet: "With ADR 0023's trash".

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
| Reveal | **Server-side unification plus delegation**: the shared store makes a second upload of the same bytes impossible (`exists`), and Reveal stops uploading from folders Hoard mirrors (each covered candidate after a 24 h grace, then uploaded if still missing). Reveal's existing blobs stay as they are and are linked into the mirror when Hoard's report names them. |
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
  So that the two never mix, a primary-volume path whose first segment is `SD card <UUID>` (any case, a UUID of the
  form a volume has) is refused (S1 amendment).
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

### The index (schema 10, `0010_mirror.sql`)

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
    FROM originals o JOIN blobs b ON b.sha256 = o.sha256;          -- everything uploaded before 0.12.0 is claimed

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
   "onDevice": false, "gone": "freed"}
  ```

  next to the `backup` (`sha256`) or `nas` location that holds the bytes. `onDevice: false` means the phone no longer
  has it, and `gone` says why: `"freed"` (Hoard's free up deleted it after verification) or `"deleted"` (it left the
  phone otherwise: deleted, trashed, or rewritten in place, whose previous content keeps the path's location as
  `"deleted"`). `gone` is absent while the file is on the phone; clients treat an unknown value as `"deleted"`. A path
  the phone moved away from has no location (the content's location is its new path). Clients ignore unknown sources (Hoard's models default every field; Reveal's
  `ItemLocationRecord` keeps `source` as a string and only looks for `nas`).
- Report writes are applied with the items they touch in transactions of 500 sources, as the walk does; a phone's first
  report of 20k files makes about as many item revisions as a first NAS walk.
- `GET /v1/items?folder=<rootId>&path=<dir>` accepts a root id where it took a NAS folder id (a root id was a `400`
  before), `path` relative to the root, sub-directories included. It returns every live item with a mirror location
  under that directory, **including locations with `onDevice: false`** (deleted and freed files stay members, above),
  so it is also **the lookup by mirror location**: an app that knows a phone file only by its path (Reveal, before it
  fingerprinted the file) finds its item by the location whose `volume` and `path` equal the file's, **confirmed by
  `size` (the file's) and `modifiedAt` (the location's)**; no match, or a size or time that differs, is no match. A
  whole folder is one paged query (as `GET /v1/items?folder=&path=` for NAS folders, ADR 0022); no new route.
  `POST /v1/items/lookup` with path keys was the alternative: it would add a second lookup shape for what a folder
  query already answers in pages, and adoption works per folder anyway. The web gallery's Folders show a **Devices** section (device › volume › root
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

## Protocol: `/v1` additions (hoard-server 0.12.0)

All additive. `GET /v1/server` `features` gains `"backup"`, and a `backup` object with the limits:
`{"maxRoots": 64, "maxReportEntries": 1000, "maxVerify": 500, "maxPath": 1024}`. Without the feature (an older
server) the app hides backup and free up. Every route needs a device key; a revoked key is `401` as everywhere.

**Paths** (roots, entries, removals): relative, `/`-separated, NFC, at most 1024 bytes, segments at most 255 bytes;
refused (a root's: `422 invalid_request`; an entry's or removal's: refused on its own, Amendment S1) when absolute, empty (except a root's `""`), with `.` or `..`
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
            "types": ["image", "video", "raw", "sidecar"],
            "paused": false, "stopped": false, "active": true, "lastReportAt": 1731686400000,
            "stats": {"files": 12034, "bytes": 52428800000, "stored": 11050, "nas": 950, "waiting": 34,
                      "removedFromDevice": 812, "freed": 0, "verified": 0}}]}
```

`active` = not paused, not stopped, and a report from a live device of that physical device in the last 7 days.
`mine` = the root belongs to the caller's physical device. Each root also has **`types`**: the kinds of files the
phone backs up from it, a subset of `image` (stills MediaStore lists: JPEG, HEIF, PNG, the RAWs Android recognises),
`video`, `raw` (every RAW extension of ADR 0014, CR3 included, found by listing the folder) and `sidecar` (`.xmp`).
A file whose kind is not in `types` is **not covered** by the root.

### `POST /v1/backup/roots` `{"volume", "path", "types"?}`

Starts mirroring a folder of the caller's phone. `201` with the root; `200` with it when it exists (a stopped or
paused root is resumed). A path inside an existing root of this phone: `409 conflict`, `details: {"reason": "covered",
"rootId"}`; a path containing existing roots absorbs them (their entries move to the new root, `200`/`201` with
`details.absorbed`). More than `maxRoots`: `422 invalid_request`. No backup root configured: `503
storage_not_configured`. The first root of a physical device creates its `mirror_devices` row and folder.

### `PATCH /v1/backup/roots/{rootId}` `{"paused"?: bool, "types"?: [...]}` and `DELETE /v1/backup/roots/{rootId}`

Pause and resume, and change `types` (`{"types": [...]}`; `200` with the root). `DELETE` **stops** the root (`200 {"status": "stopped"}`): no new entries; the
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
- A bad entry is refused on its own (Amendment S1): `state: "refused"` with a reason, the rest of the batch is applied.
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
- Every result also carries **`namedByRecord`** (boolean, computed at the time of the call): a live Reveal `photo`
  record names the entry's fingerprint (its `fingerprint` or any `files[].fingerprint`: ownership rule 3,
  `originalNamed`), or a live `version` record names it as `data.photoFingerprint` (Reveal W5). Hoard's free up uses it
  (below).
- **NAS-only content and free up**: by default the server makes its own copy before answering `yes` for an entry whose
  only copy is on the NAS: it copies the NAS file into the store (server-side, local disk to disk, no phone upload),
  checks its SHA-256 and links it (`store` becomes `blob`), so a phone file is never freed onto a share the server
  cannot keep. Server setting "Keep a copy of NAS files a phone no longer has" (on; see "Open questions"); the same
  copy is made when a phone deletes such a file ("With ADR 0023's trash").

### `GET /v1/device` gains `physicalDevice.backup`

For Reveal's delegation (and Hoard's settings), the device view adds the caller's physical device's roots:

```json
"physicalDevice": {"id": "a0e4…", "name": "Pixel 8", "devices": […],
  "backup": {"roots": [{"id": "…", "volume": "primary", "path": "DCIM/Camera", "app": "hoard",
                        "active": true, "since": 1731600000000,
                        "types": ["image", "video", "raw", "sidecar"]}]}}
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
   today; every original stored before 0.12.0 is backfilled as claimed);
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
- **Covered files**: a file of a covered folder whose kind is in the root's `types`. Reveal maps covered folders only
  for `ExternalStorageProvider` trees (`com.android.externalstorage.documents`); any other tree (a USB drive, a cloud
  provider, hand-offs) is never covered.
- **Covered does not mean never uploaded.** Reveal does not upload covered candidates at once (its per-folder backup
  switch shows "Backed up by Hoard", read-only). **Every** Reveal candidate of a covered folder (a folder photo with the
  backup on, an edited photo, and every file of the shot: the RAW of an edited pair, a sidecar) gets a **24-hour
  grace** from when it became a candidate; after it, Reveal looks it up again (`POST /v1/fingerprints/lookup`) and
  uploads what is still missing, with `wait: true`. Files whose kind is not in the root's `types` are not covered and
  go at once, as today. The store makes a late upload harmless: Hoard's next report finds `stored`, and `wait` keeps
  two uploads from running at once.
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

### For Reveal's orchestrator: what Reveal changes (Reveal 0.10.0, needs hoard-server 0.12.0, feature `backup`)

1. Read `physicalDevice.backup.roots` (with `types`) from `GET /v1/device` when `features` has `"backup"` (refreshed
   with the periodic sync); map each `ExternalStorageProvider` source's tree document id to `volume` + `path`; mark
   covered sources.
2. `backupCandidates`: a candidate whose file is covered (its kind in the root's `types`) waits 24 hours from when it
   became a candidate, then is looked up again and uploaded if still missing (`wait: true`); uncovered files go as
   today.
3. Settings › Sync: a covered folder shows "Backed up by Hoard" instead of its switch; "Originals over Wi-Fi only" is
   described as applying to what Reveal still uploads.
4. `POST /v1/uploads` with `"wait": true`; on `status: "in_progress"` leave the upload queued and retry after
   `retryAfter` (no failure count). `claim` stays absent (Reveal's uploads keep claiming).
5. `DELETE /v1/originals`: treat `409` reason `mirror` as done (drop the queued delete at once; the photo is backed up
   by the phone's mirror, so `on_server` stays true), and tell the owner the file stays in the phone's backup.
6. A **folder photo whose file vanished from the phone while the server holds it** is adopted by the Server source in
   place, like W3/C's `adoptHandoffs`: same uuid, versions, edits, history and culling; files take the server's
   document ids. It is found by fingerprint when Reveal has one, else by its mirror location (`GET /v1/items?folder=
   <rootId>&path=<dir>`, matching `volume` + `path`, confirmed by size and `modifiedAt`). It adopts photos whose
   location says `gone: "freed"` always, and `gone: "deleted"` only when the photo carries a Reveal edit (the owner
   deleted it on purpose otherwise). Never forgotten because Hoard freed its file. Then declare
   `PATCH /v1/device {"features": ["freed-adopt"]}`. Until a live Reveal on the phone has declared it, Hoard leaves
   out of free up every shot whose fingerprint a live Reveal `photo` or `version` record names (`namedByRecord` in
   `POST /v1/backup/verify`), and frees nothing while that Reveal has not been seen for 30 minutes ("Free up space").
7. Tolerate `source: "mirror"` locations on items (already true: `ItemLocationRecord` keeps the string; the
   `nas`/`backup` choice in `SyncServerItems` is unaffected because a mirror location always sits next to one of them).
8. Tests: a fake server with `backup` (covered/uncovered sources and `types`, the 24 h grace for every candidate,
   `in_progress`, `409 mirror`), and adoption: a freed photo with and without a fingerprint (by mirror location, size
   and time), a deleted one with an edit (adopted) and without (not).

Nothing in Reveal 0.9.0 breaks against hoard-server 0.12.0: every addition is optional, `TestRevealProtocolContract`
keeps replaying Reveal's `SyncApi` unchanged, and `server/testdata/v1-routes.txt` only grows.

## The app (Hoard)

### Modules and data

- `:core:server` `backup/`: `BackupPlan` (pure, JVM-tested: which files of which roots, which need a hash, upload,
  report or removal, next state), `BackupUploader` (after Reveal's `BlobUploader`: create → `exists` / `resume` /
  `in_progress`; chunks of the server's `chunkSize`, the offset saved after each; `HEAD` after a broken chunk; a
  process killed mid-way resumes from the stored upload id; `checksum_mismatch` marks the file `changed` and it is
  hashed again on the next run), `BackupWorker`, `FreeUpPlan` (pure). `ServerApi` gains the routes above.
- `gallery.db` version 4 (migration 3 → 4, schema exported to `core/server/schemas/`, migration test):
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
  `DeviceFolders.EXCLUDED` (Hoard's copies for Reveal) are never backed up. Photos and videos only, but **not only
  what MediaStore lists**: MediaStore's image collection refuses some RAWs (CR3 among them) and never lists sidecars,
  and files in `Download/` or under `Android/media` may be missing from it. So a root is read two ways: the MediaStore
  image and video collections, as today, and, **with All files access**, a listing of the root's directories (in
  `BackupWorker`, paced, never at start-up; through `PathFs`) that adds every file with an ADR 0014 extension
  MediaStore did not list: RAWs (`raw`) and `.xmp` sidecars (`sidecar`), which then group with their shot by stem as
  on the server. A root's `types` says what the phone actually covers: `image`, `video`, plus `raw` and `sidecar` only
  while Hoard has All files access (Hoard updates them with `PATCH` when that access is granted or revoked). Without it,
  Settings › Backup says "RAW files and sidecars are not backed up: allow All files access". Files the system trash holds (`IS_TRASHED`) are absent for MediaStore's
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

### Other devices see backups (Hoard 2.5.0)

The tablet's Gallery already holds every item (ADR 0021). It gains: a **Devices** entry in the Gallery's folder list
(phone › volume › folders, from `GET /v1/backup/roots` and the items' `mirror` locations), the filter "Where: Pixel 8
backup", the badge "Deleted from the phone". Cells and the viewer use the server's previews; originals stream by range
(`HttpRangeSource`) and stay only within the kept-originals budget (`OriginalCache`); nothing is copied unless the
owner taps **Save to this device** (a folder chosen once, default `Pictures/From <device name>/`; never under
`Pictures/Hoard/`), which goes through `TransferService` as above. A saved file in a backed-up folder is linked into
that device's own mirror on its next report, without an upload.

## Free up space (Hoard 2.6.0)

- **Candidates**: phone shots in backed-up roots, oldest first (`taken`), every file of the shot (a pair, a motion
  photo's still) with `store` `blob` or `nas` and its phone file unchanged since hashed. Left out: shots taken in the
  last 30 days (setting), favourites (`IS_FAVORITE`), shots with pending culling changes (pushed first), and the
  Reveal gate below.
- **The Reveal gate** (data loss otherwise: Reveal 0.9.x and older delete a folder photo, with its versions, when its
  file leaves the folder). While a **live Reveal on this phone** (ADR 0017 "Liveness": listed in
  `physicalDevice.devices`, seen in the last 90 days) has not declared `freed-adopt`, free up leaves out **every shot
  any of whose files has `namedByRecord: true`** in the verification just before the deletion, not only shots with
  ADR 0020 edits (an edit's preview goes up only on Wi-Fi after a 60 s settle, so `edits` misses fresh ones; the photo
  and version records go up within seconds on any network). And it frees nothing at all while that Reveal was last
  seen more than 30 minutes ago (an edit made offline has not reached the server yet): the preview says "Open Reveal
  once so its edits reach the server". With no Reveal on the phone, or one that declared `freed-adopt`, the gate is
  off. The preview names the shots the gate kept ("Kept for Reveal: 42").
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

## With ADR 0023's trash

**NAS references that become the only copy.** A mirror entry with `store = 'nas'` whose phone file is gone
(`on_device = 0`, deleted or freed) holds the backup's only copy on a share the server does not control. If the owner
then trashes that NAS file (`.hoard-trash`, ADR 0023) and empties the trash, or deletes it on the NAS directly, the
backup would be lost without a word. Rule: **the server copies the NAS file into the store as soon as the entry becomes
the only copy**, that is when a report sets `on_device = 0` on a `nas` entry (reason `deleted` or `freed`). The copy
is server-side, disk to disk, and it is checked by SHA-256. The entry is then linked and `store` becomes `blob`, the same
step verify already takes for free up. The bounded queue does the work. Under the setting "Keep a copy of NAS files a
phone no longer has" (on).

- **Why not when the NAS file leaves the live set** (the proposed alternative): a file deleted on the NAS directly,
  or by another tool, is already gone when the walk notices, so there is nothing left to copy. Only a trash move would
  still be caught. Copying when the phone's copy goes covers every case and costs the same bytes.
- **The walk stays a backstop**: when a NAS file referenced by a `nas` entry with `on_device = 0` becomes a trash row
  (ADR 0023 walk step 1) and the copy has not been made yet, the server copies it from `.hoard-trash` before anything
  else. A purge or a direct deletion that still finds no copy marks the entry `store = 'none'`. It also raises a warning
  on the admin page ("N phone backups lost their only copy on the NAS"). It is never silent.
- **While the phone still has the file** (`on_device = 1`), a NAS file that is trashed, purged or removed turns the
  entry to `store = 'none'`. The next report answers `missing`, and the phone uploads it. A restore from the trash before
  that brings it back to `nas`.
- A trashed NAS file is still found by fingerprint until it is purged (ADR 0023), so a report in between keeps
  `nas`. The rule above copies it anyway if the phone's file is gone.

**Phone files in Android's trash.** A file moved to the system trash (`IS_TRASHED = 1`, by Hoard's trash on API 30+,
Google Photos or Files) leaves MediaStore's default queries, so `DeviceIndexer` no longer sees it. It is **reported
`removed`, reason `deleted`**, under the usual completeness rule. The mirror keeps it (shown as "deleted from the
phone"), so nothing is lost when Android empties the trash after 30 days. Nothing more is reported then. Below API 30,
ADR 0023 moves the file into `<volume>/.hoard-trash/`. That is a dot folder, which is never backed up, so the file is
also gone from its root and is reported `deleted`. An **untrash** (or a restore out of `.hoard-trash`) brings the same
content back to the same path. That is a restore: the report sends the entry again, which becomes current again with
`on_device = 1`, no upload. Trashed phone files are never free-up candidates (they are not indexed).

## Build plan

Each phase is one PR with its tests, shippable alone.

| Phase | PR | Contents | Tests |
| --- | --- | --- | --- |
| S1 | hoard-server 0.12.0 | Schema 10; roots, report, verify, `wait`/`claim` on uploads, `409 mirror`, `physicalDevice.backup`; hard links with fallback and `0400`; manifests; `mirror` item source and `SyncBackup` joining mirror items; `GET /v1/items?folder=<rootId>`; admin page: per phone backup, purge, storage accounting, "Keep a copy of NAS files a phone no longer has"; ADR 0013 section, `openapi.yaml`, `v1-routes.txt`. | Go: path validation and traversal on every input; a root of another phone `404`; link, `EXDEV` fallback (an injected linker), no copy; move, rename, rewrite, delete, restore; waiting entries linked on another device's upload; `wait` with a live and a stalled upload; verify with a corrupted blob and a changed NAS file, `namedByRecord` from photo records and version `photoFingerprint`; `GET /v1/items?folder=<rootId>` returning `onDevice: false` locations with `gone` (`freed`, `deleted`); `types` on roots; ownership (Reveal delete vs mirror, purge vs claim/record); revoke and claim/merge; `TestRevealProtocolContract` and `TestOpensRevealSyncDataDir` unchanged; `TestUpgradesHoardServer040DataDir`; the end-to-end test extended with a phone report, an interrupted upload and a verify. |
| S2 | hoard-server 0.12.x | Web gallery: Devices in Folders, "Where" filter, "Deleted from the phone" badge, strings in `en` and `pt-BR`. | `bun run lint/typecheck/test/e2e` with a synthetic phone mirror. |
| A1 | Hoard 2.4.0 | `gallery.db` 4; `BackupPlan`, `BackupUploader`, `BackupWorker`; Settings › Backup with status per folder and file; Gallery badges. | JVM: `BackupPlan`, `FreeUpPlan` later, the uploader against a fake server (resume, `in_progress`, checksum, 401), migration 3 → 4, Roborazzi goldens of the screen. Emulator against a local hoard-server container (`docs/testing/backup.md`): synthetic photos in `DCIM/Camera`, a NAS duplicate referenced not uploaded, the process killed mid-upload and resumed, a rename on the phone moved on the server, unmetered/charging rules. |
| A2 | Hoard 2.5.0 | Devices view, filter and badge; `ServerFs` and "Save to this device" through `TransferService`. | JVM: `ServerFs` in `TransferRunnerTest` style; emulator: two debug ids (`-Phoard.native.debugSuffix`) as phone and tablet. |
| A3 | Hoard 2.6.0 | Free up space: candidates, preview, verify, `createDeleteRequest`, report `freed`. | JVM: `FreeUpPlan` (exclusions, whole shots, staleness; the Reveal gate: a shot named by a live Reveal photo or version record is kept while the phone's live Reveal has not declared `freed-adopt`, also with no ADR 0020 edits; a stale Reveal frees nothing; the gate is off with no Reveal or with `freed-adopt`); emulator: free synthetic files, refuse a partial consent, a corrupted server copy not freed. |
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

## Owner's decisions (2026-11-15)

Henrique accepted this ADR with these answers to its open questions; where they differ from a line above, they win.

1. **Files deleted on the phone are kept in the backup forever** (for now; a retention setting may come later).
2. **"Keep a copy of NAS files a phone no longer has" is on by default.**
3. **Free up keeps the last 30 days and favourites on the phone by default** (both settings).
4. **No folders are on by default after pairing**: the owner picks.
5. **Device folders sit directly under the backup root.**
6. **Reveal's grace window is 24 hours.**
7. **Every paired device of the server sees every backup** (today's trust model). Only the owner uses the server
   today; revisit (per-device read scopes in a later ADR) if anyone else's device is ever paired.
8. **Videos back up on unmetered networks and while charging by default** (Settings › Backup: "Videos only while
   charging", on); photos keep "Only on Wi-Fi and other unmetered networks" (on) and "Only while charging" (off).

## Amendment (S1, hoard-server 0.12.0)

What the server PR settled where the text above was silent or loose. Where this differs from a line above, this wins.

- **Schema 10 additions** to the tables above, all in `0010_mirror.sql` (one transaction, no index on an existing
  table, so it needs no temporary directory; `TestMigrationsNeedNoTempDir` runs it with a broken `TMPDIR`):
  `mirror_roots.types` (JSON, default `["image","video"]`; the column the `types` of the routes needed),
  `mirror_roots.physical_id ... ON UPDATE CASCADE` (a phone's backup moves with a claim), `mirror_entries.tree_path`
  (where the entry's link is, so a moved or rewritten file's link is found again) and `lost_at` (the admin warning),
  an index `(root_id, path)`, `upload_unclaimed` (uploads made with `"claim": false`; `uploads` stays untouched) and
  `blob_verifications` (one re-hash per content, kept 30 days).
- **Entry and verify paths are relative to the volume** (as in the examples: root `DCIM/Camera`, entry
  `DCIM/Camera/PXL_….jpg`) and must lie inside the root (`422` otherwise). Entries must be photos, videos or sidecars by
  ADR 0014 extension, and `png`, `webp` and `gif` (below); removals may name any path.
- **Phone backups accept PNG, WebP and GIF** (every Android screenshot is a PNG; the root type `image` means every
  still MediaStore lists). `media.ClassifyMirror` is `Classify` plus those three as rendered stills; only the mirror
  paths use it (report, verify, `GroupMirror`). The NAS walk, its trash and its sidecar pairing keep `media.Classify`,
  so a PNG or GIF on the NAS stays unindexed. A PNG pairs by stem like any other rendering (after JPEG and HEIF in a
  shot's order) and is the main file of a shot of its own otherwise. Previews: libvips reads them (a GIF: its first
  frame) and the image job is the same four files; the web gallery shows the file itself (a browser shows PNG, WebP and
  GIF as they are). The fingerprint (`v1:`) hashes sampled bytes and does not depend on the format. No EXIF is read
  from them: the sort time is the file's mtime.
- **A bad entry never fails a batch.** `POST /v1/backup/report` and `/verify` refuse one entry or removal on its own:
  `state: "refused"` with `reason` (`unsupported_type`, `invalid_path`, `outside_root`, `reserved_prefix`,
  `invalid_fingerprint`, `invalid_size`, `invalid_modified_at`, `invalid_sha256`; a removal also `invalid_reason`) and
  a `detail`; the other entries are applied and answered (verify: `verified: "no"` with the same `state`, `reason`).
  `422` stays for a malformed request as a whole (bad JSON, more than the limit; an unknown root is `404`). Where lines
  above say `422` for a path, this wins.
- **Device features** (schema 11, `devices.features`): `PATCH /v1/device` stores every known declared feature
  (`culling` as before in `culling_since`, which pushing culling also sets; `freed-adopt` in the new column; unknown
  names are dropped), never removed. `GET /v1/device` shows them in `features` and, additively, per device in
  `physicalDevice.devices[].features`: the free-up gate reads "a live Reveal on this physical device declared
  `freed-adopt`" there.
- **Paused roots** still take reports (pausing is the phone's upload rule); stopped roots answer `409 stopped`. Only
  roots that are not stopped cover a path (`409 covered`) and count towards `maxRoots`.
- **`active`** counts the root's creation as a report (a root is active before its first report). Only a live device
  can report (a revoked key is `401`), so a revoked phone's roots turn inactive 7 days after its last report.
- **Writes go through `os.Root` opened on the backup root**, not on the device folder: a hard link needs the blob and
  the new name in one root. Device folder names are validated as one path segment (never `reveal-sync`, never a dot
  name) and phone paths as above, so no name leaves the phone's folder; a symbolic link out of the backup root is
  refused by `os.Root`. A file in the way that is not a hard link of a blob (one name only) is never replaced.
- **Moves**: a `moved` removal whose new path has not been reported keeps its content out of the tree and settles as
  `deleted` after 24 hours (`replaced` when another file took the path meanwhile). The inferred move applies to
  `deleted` entries only: a `freed` entry stays pinned even when its content comes back at another path (that path is a
  current entry of its own). A `deleted` report never turns a `freed` entry back.
- **Rewritten files**: each previous version is an item of its own (its own group, keyed by the entry), linked at
  `.hoard-mirror/previous/<SD card prefix><path>~<sha8>` and served as a `mirror` location of its old path with
  `onDevice: false`, `gone: "deleted"`.
- **Mirror items** join the live NAS item that holds their main file (as an upload does); a `mirror` location carries
  its own `backup` (`sha256`) location for stored bytes, or the `nas` location of a live NAS copy for a reference, so
  it always sits next to the bytes even when the NAS shot is grouped otherwise.
- **NAS references and the trash**: a reference stays `nas` while the file is findable by fingerprint, in a trash too
  ("a report in between keeps `nas`"); it turns `none` when the file is purged or deleted (and `lost_at` is set when the
  phone no longer has it). After every walk the server re-checks every reference; with the setting on, a reference
  whose phone file is gone is copied into the store from wherever the file is (a trash first if that is all there is).
  Content uploaded meanwhile turns references into `blob`.
- **The server's own NAS copy** is made through the blob store's ingest: hashed while written, then read back and
  checked by SHA-256 and fingerprint before it is moved into place; that read is the re-hash `verify` needs. It is
  nobody's claim (`blobs.device_id` is null): the mirror entry keeps it. A copy whose bytes differ from the phone's
  `sha256` is discarded and verify answers `differs`.
- **`DELETE /v1/originals`** checks the live-record rule (`referenced`) before the mirror rule (`mirror`); the claim is
  released only on `mirror`.
- **Purge** is per phone: `POST /admin/api/backup/{physicalDeviceId}/purge` with `{"confirmPinned": n}`, where `n` must
  equal the phone's current number of freed entries (`409` otherwise), so a purge never removes files freed after the
  page looked. Contents another phone's entry, a claim or a live photo record name stay; the phone's folder is removed.
  The page also renames a phone's folder (`PUT /admin/api/backup/{id}/folder`) and sets "Keep a copy of NAS files a
  phone no longer has" (`PUT /admin/api/backup/settings`).
- **Claims and moves**: when the moving device's old physical device keeps no live device, its backup moves. If the
  target has no backup, the folder row moves (the folder keeps its name). If it has one, roots of the same volume and
  path merge, the files are re-linked into the target's folder and the old folder is pruned; on a path both hold, the
  newer `modified_at` wins (a tie: the moving phone's entry loses), and the loser becomes `replaced`, or `moved` when it
  is the same content.
- **Revoking** keeps today's sweep: a revoked device's unfinished uploads are removed at the next sweep, not after
  7 days.
- `GET /v1/device` always carries `physicalDevice.backup` in 0.12.0 (the feature is always on; `POST
  /v1/backup/roots` answers `503 storage_not_configured` while no backup folder is chosen).
- `POST /v1/uploads` with `wait`: an upload created or given a chunk in the last 10 minutes is live (its row's
  `updated_at`).

From the S1 review (same PR):

- **A blob is never replaced.** Completing an upload of bytes the store already has (two devices at once; Reveal 0.9
  sends no `wait`) drops the new part file instead of renaming it over the blob, so the mirror's hard links keep the
  blob's inode (a link attempt that fails when the name exists, a rename only on filesystems without hard links).
- **A NAS copy that finds the blob already there** does not mark it verified: its bytes on disk were not read back, so
  a re-hash is queued and verify answers `pending` until it is done.
- **Pinned is a flag** (`mirror_entries.pinned`), set by a `freed` report and cleared only when the phone has that
  file again (a restore). A rewrite of the path or a lost merge no longer hides it: the purge's "only copies" count
  (and its `confirmPinned`) and the admin page count `pinned`.
- **Main storage and SD cards never share a folder**: on the primary volume, a path whose first segment is
  `SD card <UUID>` (any case) is refused (`422`, roots and reports), since the tree keeps other volumes there.
- **The purge removes only what the server made** in the phone's folder: the hard links it recorded, its manifests,
  then directories left empty. Files the owner put there stay (the answer's `keptFiles`, and the page says so before and
  after).
- **Blob files are removed under a per-content lock** (a purge, `DELETE /v1/originals`), and only if no row came back
  meanwhile; completing an upload, registering an existing blob (`exists`) and a NAS copy write their rows under the
  same lock, so a row never outlives its file.


**hoard-server 0.12.1: `maxAge` on verify.** Free up must delete only after a recent re-hash, but a stored one answered
for 30 days. `POST /v1/backup/verify` takes an optional `maxAge` (ms; in the body, or on an entry, which wins). A
stored re-hash (blob, or the NAS entry's `verified_sha256` / `nas_files.sha256`) older than `maxAge` is queued again in
the same bounded queue and answered `pending` with `retryAfter`; once it ran, `verifiedAt` is the new time and a
damaged copy answers `differs`. The value is clamped to 1 hour to 30 days (never nonstop hashing); without it the
behaviour is the 30 days above. Additive under `/v1`.

## Amendment (A1, Hoard 2.4.0)

What the app PR settled where the text above was silent or loose. Where this differs from a line above, this wins.

- **Stills include PNG, WebP and GIF** (coordinator's decision, 2026-11-16): a root's `image` type covers every still
  MediaStore lists whose extension the server takes for mirror entries: JPEG, HEIF, PNG, WebP, GIF and the RAWs Android
  lists (a DNG from MediaStore is `image`). hoard-server 0.12.0 accepts PNG, WebP and GIF for mirror entries (the
  mirror-formats change of S1; the NAS walk keeps its classifier). Other formats are never reported and never count.
- **Per-entry refusals.** A report answer `state: "refused"` with a `reason` (`unsupported_type`, `invalid_path`,
  `outside_root`, `reserved_prefix`) is the file's state (`refused`, shown with the reason) and the file is not reported
  again until its size, mtime or fingerprint changes. Against a server that answers a whole batch `422` with
  `details.index` (before that change), the app marks that entry `refused` (`invalid_request`) and sends the batch again
  without it, so one bad file never blocks a folder.
- **Where the code is.** `BackupPlan`, `BackupUploader`, `BackupEngine` (a pass: list, identify, report, removals,
  uploads, report again) and `BackupWorker` live in `:core:server` `backup/`; the worker reaches the app through a
  `BackupHost` (the application, like `GallerySyncHost`), and the engine reads the phone through a `BackupSource`
  (`PhoneBackup` in `:native` `backup/`), so the whole pass runs on the JVM against a fake server in tests.
- **`backup_files`** is keyed by (root, path relative to the volume), with the phone path, the `device_media` row (null
  for a RAW or sidecar found by listing), the kind, size, mtime and capture time, its own `fingerprint`/`fp_size`/
  `fp_modified` (a listed file has no index row), and `reported` (`fingerprint|size|mtime` last reported: a row is
  reported again when it differs, which also re-reports a file after its upload). States: `waiting` (no fingerprint),
  `queued`, `hashing`, `uploading`, `stored` (backed up), `nas` (already on the server), `verified`, `paused` (`wifi`,
  `charging`), `failed` (`unreadable`, `server_error`, `server_full`, `too_large`; backed off from 1 minute to 6 hours),
  `changed` (`checksum_mismatch`: identified and hashed again on the next run), `refused`. A killed run's `hashing` and
  `uploading` rows go back to `queued`; the upload id and offset stay. `freed` is keyed by the phone path.
- **Identity in the worker.** A file the index has not fingerprinted yet (new photos, a listed RAW or sidecar) is
  fingerprinted by the run itself, newest first, in batches of 50 with yields and the stop check, paced at the hashing
  throttle unless charging, and the result is written back to `device_media` (fingerprint, motion flag, and the SHA-256
  of a file of at most 2 MiB), so the Gallery does not read it again.
- **Rules as constraints.** The periodic and the "soon" runs carry the photo rules (unmetered or any network, charging
  when asked, battery not low). Videos follow the photo rules plus "Videos only while charging"; when a run leaves
  videos waiting for the charger it enqueues one run constrained on charging. "Back up now" is expedited with only a
  network constraint (WorkManager refuses charging and battery constraints on expedited work) and the run applies the
  rules file by file, so it still waits where they ask. The rules are checked again before every file (the network can
  change mid-run).
- **The debounced run** is a unique one-off 2 minutes after the index sees new, changed or removed media while a root
  exists: a pending one starts its wait again; while one runs, one more is appended after it.
- **Listing for RAWs and sidecars** walks the root's directories with `java.io.File` (All files access), skipping dot
  names and `DeviceFolders.EXCLUDED`, inside the run only, yielding every 20 directories; `PathFs` is in `:core:smb`,
  which `:core:server` does not depend on, and the walk needs no more than a directory listing.
- **Complete listing** = the index's MediaStore scan at the start of the run succeeded, the volume is mounted
  (`Environment.getExternalStorageState`) and the root folder exists, and the walk (when it ran) finished. A removal is
  `moved` when its fingerprint is at a path the phone still has, else `deleted`; after the server answers, the row
  leaves `backup_files` (the server keeps the entry).
- **A root the server no longer knows** (`404`) or stopped (`409 stopped`) is made again with `POST` (a stopped root
  resumes with its id; a new id takes the old rows); `409 covered` drops the local root. Turning a switch off stops the
  root (`DELETE`) and forgets it here; offline, it is forgotten here and the server keeps it until a later change.
- **Status per folder**: "backed up" counts `stored` and `verified`, "already on the server" counts `nas`; the bytes are
  those of backed-up files over the folder's; the last complete pass is when a run ended without being stopped; the
  server's `stats` of the root are kept with it for A2.
- **Gallery badge**: only phone-only shots of backed-up folders that are not backed up yet get one (waiting, uploading,
  waiting for a rule, failed); a backed-up shot shows as on the server (the existing badge) once the feed has its item.
- Not in A1 (A2): "Reveal on this phone is not grouped with Hoard" in Settings › Backup.

## Amendment (A2, Hoard 2.5.0)

What the app PR settled where the text above was silent or loose. Where this differs from a line above, this wins.

- **Where Devices lives.** The Gallery has no folder list yet, so **Devices** is its first entry point: a button in the
  Gallery's top bar (shown once the server has any backup) opening a sheet of phones › volumes › folders. Every root
  of `GET /v1/backup/roots` is listed (stopped ones and roots with nothing on the server yet included), plus every
  directory holding a backed-up shot; counts include sub-folders. Picking a row sets the **Where** filter
  (`Where(physicalDeviceId, volume?, dir?)`, a folder matches its sub-folders); a bar above the grid names it with a ✕.
  Filters gain "Phone backups": one chip per phone, "<phone> backup". This phone is listed first as "This phone".
- **Data.** `gallery.db` 5 adds `server_item_mirrors` (item, fingerprint, physical device, its name, root, volume,
  path, size, `modifiedAt`, `onDevice`, `gone`), written with the items in the feed's transaction, and
  `server_account.backup_roots` (the last `GET /v1/backup/roots`, JSON, refreshed by every sync when the server has
  `backup`) and `mirrors_indexed`. Items cached before the upgrade are indexed once from their JSON (no new pull).
  `onDevice` absent reads as on the phone; an unknown `gone` as `deleted`. A server item filed nowhere on the NAS gets
  `<phone>/<directory>` as its folder, and the phone's name is searchable.
- **The badge** "Deleted from the phone": a shot with mirror locations none of which is still on its phone (one file
  of a pair still there is enough to show no badge). While the grid shows one phone's backup it is about that phone
  only. `gone: freed` shows the same icon described "Freed from the phone".
- **This phone's files shown once.** Besides the fingerprint join (ADR 0021), a phone file the index has not
  fingerprinted yet merges with the item that has a mirror location of **this** physical device at the same volume and
  path, still on the phone, with the file's size. A saved file (below) is given to the index with the fingerprint it
  has on the server, so it merges from its first scan.
- **Previews and originals** are unchanged from ADR 0021: cells and the viewer use the server's previews; the viewer's
  100 % and RAW decode read `GET /v1/originals/{fingerprint}` through `HttpRangeSource`, kept only within
  `OriginalCache`'s budget.
- **`ServerTransferFs`** (`:core:smb`, `Endpoint.Server(serverId)`, kind `server` in a job's JSON): read-only, paths
  `<fingerprint>/<file name>` (an upload without a name: `<16 hex>.<format>`), `stat` by `HEAD`, reads one
  `Range: bytes=<offset>-` stream (`ServerApi.openOriginal`). It is a network endpoint for the engine: a dropped
  connection, a timeout or a `5xx` is retried and resumed at the byte written; a body that ends before the announced
  size counts as a dropped connection; `404` fails that file alone (`NOT_FOUND`); `401`/`403` stop the job
  (`AUTH_FAILED`). `:core:smb` does not know the server client: the app sets `TransferEndpoints.server` (in
  `HoardApplication`'s constructor, so a transfer worker started in a fresh process has it). Server endpoints are
  sources of copies only; for a job reading them the engine makes the destination folder (parents included) when it
  is missing.
- **Save to this device.** In the viewer (server-only shots) and the selection bar; one sheet: what is saved, the
  folder, Change folder (the destination picker), Use the default, Save. The default is `Pictures/From <phone name>/`
  per shot's phone (the server's name for NAS photos), one transfer per destination folder; a chosen folder is kept in
  the setting `gallerySaveFolder` and used for every shot. `Pictures/Hoard` and `Download/Hoard` (and inside them) are
  refused. The conflict policy is the owner's default (Settings › Files). Saving into the phone's folders needs All
  files access (the sheet asks); a SAF folder from the picker works without it.
- **Linked, not uploaded.** Nothing new on the backup side: a saved file inside a backed-up folder is a new file to
  A1's engine, reported with its fingerprint; the server already has the content (`stored`), links it into this
  phone's tree and nothing is uploaded (verified on the emulator, `docs/testing/backup.md` "A2").
- **Found on the emulator, fixed for every copy**: on Android 11+ MediaStore kept the row it made for the engine's
  hidden part file (size 0, time 0) through the rename that publishes it, so a copied file was indexed with size 0 and
  would have been reported to the backup as such. `PathTransferFs` now asks MediaStore to scan the published file, and
  again after its modified time is set.
- Not in A2 (still open): "Reveal on this phone is not grouped with Hoard" in Settings › Backup (A1 deferred it here);
  it needs `physicalDevice.devices` and is left for A3, where the Reveal gate reads the same list.
