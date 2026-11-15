# ADR 0022: SMB folders on the server

- **Date**: 2026-11-15
- **Status**: Accepted (Hoard 2.2.0; needs hoard-server 0.10.0, feature `items-by-path`)
- **Builds on**: ADR 0005 (locations), 0010 (Edit in Reveal, "Server items"), 0011 (culling sidecars), 0014 (items,
  "Items by path"), 0016 (culling sync), 0021 (Gallery, `:core:server`); `docs/integration/README.md` (the sender rule).
- **Amends**: ADR 0010 ("Server items": SMB files of Files and folder culling), ADR 0021 ("Seams": `items-by-path` is
  now called; `gallery.db` version 2).

## Context

The owner's photo library lives on cachyos-fps (`/mnt/crucial/Imagens`). Hoard reaches it two ways: as an **SMB share**
(Files, folder culling) and through **hoard-server**, which indexes the same directory as a NAS folder (the Gallery).
Hoard 2.1.0 knew the two only in the Gallery: a photo opened from Files or folder culling on the share still went to
Reveal as one of Hoard's content URIs, and its culling stayed in Hoard's folder rows and the XMP sidecar. The
integration rule (contracts 8–10) is that a photo the server holds goes to Reveal as a **server reference**, never as a
URI, and that culling is shared through the server (ADR 0016). Hoard needs to know that `\\cachy\photos\Imagens\2024\
Trip\DSC01003.JPG` is item `0b6f…` of the server, cheaply: the library has ~21,000 items, the share is reached over
Wi-Fi or Tailscale, and opening a folder must not read files in bulk.

## Decision

### Mapping

A **mapping** says that a prefix of an SMB share is a prefix of one of the server's NAS folders:

| SMB side | Server side |
| --- | --- |
| machine id, share (case-insensitive), `smbPrefix` | server id, NAS folder id (and its name), `serverPrefix` |

so that a share path `smbPrefix/rest` is the NAS path `serverPrefix/rest` of that folder. A mapping covers its whole
sub-tree: one learned folder maps every other folder under the same prefix.

**Learning** (`SmbServerPaths`, `MappingDiscovery`, `:core:server`, JVM-tested). When an SMB folder opens in Files or
in culling, after its listing is on screen, and no stored mapping covers it, and the paired server lists
`items-by-path` (hoard-server 0.10.0):

1. Pick two photos of the folder (by `ImageFormats`; not hidden; preferably ≥ 64 KiB; the first and the last by name,
   so two different shots). A folder with fewer than two photos learns nothing.
2. Read the first photo's v1 fingerprint (contract 7: size + first MiB + last MiB, a **2 MiB read**) and look it up
   (`POST /v1/items/lookup`). Not found: stop (one read). Found: every NAS location of that file in the answer gives a
   candidate mapping: the two paths' longest common tail of segments (compared ignoring case, as SMB does; it must
   include the file name) is the shared part, and what is left of each is its prefix. The most specific candidate
   comes first (copies of a file in two folders give two candidates).
3. **Verify** with the second photo the same way: the mapping is kept only when the second file's candidates hold the
   same folder and the same prefixes. One file is never trusted alone.
4. Store it (`gallery.db` `smb_mappings`). A folder where learning found nothing is remembered for a day
   (`smb_mapping_misses`), so reopening a folder that is not on the server does not read two files again each time.

So learning costs at most two 2 MiB reads and two small lookups, once per share prefix; no listing is fingerprinted.

**Invalidation.** A mapping is forgotten (and learned again the next time a folder there opens) when:

- the paired server changes (the rows carry the server id; unpairing or pairing another server clears them);
- the SMB machine answers with another **server GUID** than when it was learned (ADR 0002's pinned GUID: another
  machine behind the same address);
- the NAS **folder id** is gone: the folder was removed, or added again with a new id. `GET /v1/items?folder=` answers
  an unknown id with an empty page, so an empty answer is checked with `POST /v1/items/by-path`, which answers
  `404 not_found` for it; the mapping is forgotten and learned again at once from the same listing;
- a **mismatch**: a file looked up on demand (below) is found, but not in the mapping's folder under its server prefix
  (the share prefix is not that tree any more);
- the owner taps **Forget** (Settings › Photo server › Mapped folders).

### Resolution

For an open folder covered by a mapping (`FolderMatch`, JVM-tested):

- **The folder query**: `GET /v1/items?folder=<id>&path=<serverPrefix + folder>` (sub-directories included), pages of
  500, stopping as soon as every photo and video of the folder has been seen. A folder whose sub-tree is the whole
  library (the share root) stops after 4 pages and asks the rest with **`POST /v1/items/by-path`** (≤ 1000 paths per
  call), which is also the call for scattered selections.
- **Per file**, by path (exact, else ignoring case) then **size and mtime** (mtime within 2 s: SMB gives 100 ns, the
  server keeps ms, a FAT disk under Samba keeps 2 s):
  - same size and mtime → **mapped** to the item (its id followed through `replacedBy` of the cached culling records:
    a JPEG-only item replaced when its RAW appeared);
  - the path is indexed but the size or mtime differ → **changed** (the file changed since the server's walk);
  - not indexed → a **rename** on the share since the walk when exactly one indexed file of the same directory and
    extension, whose own path is gone from the share, has its size and mtime; else **unknown** (new, not walked yet).
- Changed and unknown files are looked up by their own fingerprint **only when it matters**: at a hand-off (below).
  Badges and culling use mapped files only.
- Cached per folder for **5 minutes**; pull to refresh in Files asks again at once.

A shot (folder + lower-case stem, as culling groups files) is mapped when one of its files is; a RAW's item comes
before its JPEG's.

### Files and culling

- **Badges**: mapped files in Files and mapped shots in the culling grid show the Gallery's "on the server" mark (the
  cloud). Unmapped folders and files look exactly as before.
- **Culling: dual write** (`MappedCullStore` over `FolderCullStore`). A decision on a mapped shot is saved as before
  (the folder's `cull_decisions` row, and its XMP sidecar when Save ratings is on, ADR 0011 unchanged) **and** queued
  for the server's culling of the same item (ADR 0016 rule 2: per field, `baseRev` = the cached rev, `modifiedAt` = the
  decision's time), pushed by the Gallery's sync soon after. A decision imported from a sidecar is queued dated by the
  sidecar's mtime (rule 4). Unmapped shots: unchanged.
- **One time for both writes.** When the sidecar write comes back with its mtime and still holds the shot's latest
  decision, the shot's pending server changes that carry exactly those values are **re-dated to the sidecar's mtime**
  (`CullingRules.redated`). The server then sees the same value at the same time in both places.
- **Showing the server's values**: a mapped shot whose server record is newer than its folder row (another device, the
  web gallery) shows the server's rating and flag, dated by the server's `modifiedAt` (so an older sidecar does not win
  over it at the next open, rule 4), and an open culling session follows syncs. A pulled value is never written to a
  sidecar (rule 4).

**Why this converges (no ping-pong).** Take a decision `v` on a mapped shot, written to the sidecar with mtime `t`
and pushed with `modifiedAt` `t` by Hoard's device `H`; the server walks the share later and imports the sidecar as a
write by `"nas"` at `t` (ADR 0016 "The NAS sidecars").

- Push first: the field holds `(v, t, H)`. The import brings the same value: step 2, **no revision** (at most the
  field's `(t, deviceId)` moves to `"nas"` in place). Nothing reaches any client.
- Import first (Hoard offline): the field takes `(v, t, "nas")`, one revision. Hoard's push of the same value is step 2
  again: `unchanged`, no revision. Hoard's pull brings `v`, which it already shows, and writes no sidecar.
- A sidecar is read by the server only when its size or mtime changed, and Hoard writes one only for a decision the
  user made: a pulled value never causes a write, so there is nothing to read back. One decision makes at most one
  sidecar write, one import and one revision per field, and `(modifiedAt, deviceId)` orders every write the same way
  everywhere, so all parties end on the same value.
- Without the re-dating (Save ratings off, or the push left before the sidecar was written) the two times differ by
  the write delay: the import still carries the same value (step 2), so the result is the same; only a decision made
  elsewhere inside that window could be ordered differently. The re-dating closes it, and uses one clock (the SMB
  server's) for both writes.

`DualWriteTest` runs both orders and a newer decision from Reveal against the fake server's per-field rule and NAS
import.

### Edit in Reveal

From Files and folder culling on an SMB share (`FileActions.editInReveal`, plan in `SmbReveal`, JVM-tested):

- With Reveal ≥ 0.9.0 (`versionCode >= 90000`) and a paired server, each **shot** with a mapped file goes as a
  **server item** (ADR 0010 "Server items": `SERVER_REF_VERSION`, `SERVER_ID`, `SERVER_ITEMS` with `itemId`, the main
  file's `fingerprint`, every member's `fingerprints`, `name`; at most 500 per intent, more as several intents with
  the same title; `INITIAL_ITEM` when the shot opened first is one). The item's details come from the folder query,
  the Gallery's mirror, or the lookup.
- A shot with no mapped file but with a changed or unknown one is **looked up** by its main file's fingerprint (2 MiB)
  when the folder is mapped (or the hand-off is one shot), at most 20 shots per hand-off. Found → a server item (even
  when the mapping was wrong: the server holds the photo; the mapping is then forgotten); not found → URIs.
- Everything else goes as URIs, exactly as before (ClipData, grants, sidecars, `CULLING` for those only). One intent
  may carry both.
- The pending culling of the items is pushed first (best effort, 3 s), as in the Gallery.
- **Never URIs for a mapped shot** when Reveal takes references. An older Reveal gets every file as a URI, as before.

### Settings

Settings › Photo server › **Mapped folders**: each mapping as "machine › share/prefix" and "On the server:
folder/prefix", with **Forget**.

### Storage

`gallery.db` version 2 (Room migration 1 → 2, `GalleryMigrationTest`): `smb_mappings` (key machine, share, SMB prefix;
server id, folder id and name, server prefix, SMB server GUID, learned / verified times) and `smb_mapping_misses`
(machine, share, folder, server id, checked at). `hoard.db` is unchanged (it stays Hoard 1's schema table for table,
ADR 0019): mappings belong to the paired server, as everything in `gallery.db`.

## Alternatives considered

- **Fingerprint every file of an opened folder** (or keep a fingerprint per SMB file): exact, but a folder of 600 RAWs
  is 1.2 GB of reads over Wi-Fi each time it changes. Rejected: two reads per share prefix, then paths, sizes and times.
- **Match on the Gallery's local mirror** of the items feed (it has every item's NAS locations) instead of
  `GET /v1/items`: no request, but the mirror is only as fresh as the last sync and is empty until the first full pull;
  the folder query answers what the server knows now. The query is one request per folder, cached for 5 minutes.
- **Mapping by name** (the owner types "share X is NAS folder Y"): no reads at all, but one more setting to get wrong,
  and invisible when a folder is re-added with a new id. Learning is automatic and verifiable.
- **The server writes the sidecars** (one writer): ADR 0016 keeps the NAS read-only; the apps keep writing sidecars
  for other tools.

## Consequences

- Opening an SMB folder on a mapped share costs one `GET /v1/items` (more pages only under the share root) every 5
  minutes; learning costs two 2 MiB reads once per share prefix (or one, when the folder is not on the server, then
  nothing for a day).
- Culling on a mapped share writes each decision twice (folder row + sidecar, and the server's queue); the Gallery,
  the web gallery and Reveal see folder culling on the share.
- Reveal ≥ 0.9.0 gets photos of the share as server items and reads them from the server; Hoard's `HandoffStore`
  links are made only for the URI items.
- `docs/integration/README.md` "Sender rule" holds for Files and culling once 2.2.0 ships (proposed in the PR).

Tests and the end-to-end run: `docs/testing/smb-mapping.md`.
