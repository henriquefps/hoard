# ADR 0020: Reveal's edited previews on hoard-server

- **Date**: 2026-11-14
- **Status**: Accepted (hoard-server 0.9.0). Agreed with Reveal's orchestrator as a draft; this ADR is the frozen
  contract. Reveal implements its side from "Client rules (Reveal)" below.
- **Amends**: ADR 0013 (additions under `/v1`, summarised there in "Edited previews (hoard-server 0.9.0)"), ADR 0015
  (one image job per edit), ADR 0018 (the gallery shows edits). Reads ADR 0014 (items), 0016 (culling stays per item)
  and Reveal ADR 0025 (versions and virtual copies).

## Context

Reveal (the owner's RAW editor, a client of this server) edits photos the server indexes. Its edits sync as `version`
records (ADR 0013 "Records": a recipe, a history, a name), which only Reveal can render: the server has no Reveal
pipeline. So the web gallery, and later the native Hoard, show the camera's rendering of a photo the owner has
finished in Reveal, with at most an "edited in Reveal" badge (ADR 0018). Reveal ADR 0025 also gives a photo several
**versions**: the main one (position 0), **virtual copies** (independent edits of the same photo, each a cell of
Reveal's grid) and sync conflict copies.

The owner wants the gallery to show Reveal's result. Reveal can render it; the server owns the contract.

Constraints: `/v1` is frozen for Reveal (additions only, ADR 0012); the image must not leak what the owner did not
mean to share (GPS in EXIF); a phone's upload must be cheap and resumable by retrying; culling stays per item (ADR
0016, Reveal ADR 0025 "Culling"); nothing the server stores may be lost silently.

## Decision

### An edit

An **edit** is Reveal's rendering of one **version** (Reveal's version uuid, `versionId`) of the photo of one **item**
(ADR 0014): an sRGB JPEG or WebP, long edge at most 2560 px (the screen preview's, ADR 0015). The server keeps per
edit:

| Field | Meaning |
| --- | --- |
| `versionId` | Reveal's version id. A version has at most **one edit on the server**, on one item. |
| `label` | The version's name as Reveal shows it ("Copy 1"; `""` for the main version). At most 64 characters. |
| `recipeDigest` | Reveal's digest of what it rendered (recipe and render pipeline): equal digests mean the same image. 1-128 of `A-Z a-z 0-9 . _ : + / = -`. |
| `editedAt` | When the version last changed on the device (Unix ms): orders two uploads of the same version. |
| `primary` | The edit the gallery shows for the item. At most one per item; the last one set wins. |
| `isVirtualCopy` | A virtual copy (Reveal ADR 0025): shown as its own cell in the item's stack. |
| `deviceId` | The device that uploaded the image. |
| `format`, `width`, `height`, `bytes`, `updatedAt` | The stored image (after cleaning) and the last change. |

Videos have none (`422`). Edits are not culling: rating, flag and label stay **per item** (ADR 0016), shared by the
item's edits and copies, as in Reveal.

### Upload: `PUT /v1/items/{itemId}/edits/{versionId}`

Device key, like every `/v1` route. The **body** is the image; the **metadata** is in headers (one request, no
multipart encoder on the phone, and a metadata-only change needs no body):

| Header | Required | Value |
| --- | --- | --- |
| `Content-Type` | with a body | `image/jpeg` or `image/webp` (else `415 unsupported_media_type`) |
| `X-Hoard-Recipe-Digest` | yes | the digest (above) |
| `X-Hoard-Edited-At` | yes | Unix ms; more than 10 minutes ahead of the server's clock is clipped to it (as culling) |
| `X-Hoard-Label` | no | percent-encoded UTF-8 (`Copy%201`, `C%C3%B3pia%201`); absent = `""` |
| `X-Hoard-Virtual-Copy` | no | `true` / `false` (default) |
| `X-Hoard-Primary` | no | `true` / `false` (default) |

**The image**, at most **8 MiB**:

- **sRGB**, **upright** pixels (no EXIF orientation is applied: the server removes it), long edge **16 to 2560 px**
  (`min(2560, the edit's own long edge)`: never upscaled), JPEG (baseline or progressive, 1 or 3 components) or WebP
  (lossy or lossless, still).
- The server **decodes it whole** (a truncated or corrupt file is refused), checks the size and the colour space,
  and **removes every metadata segment** without re-encoding the pixels: JPEG APP1-APP15 except Adobe APP14 (which
  says YCbCr or RGB), APP2 ICC and COM go; WebP keeps only `VP8 `/`VP8L` (and `ALPH` with its `VP8X`). EXIF, XMP and
  GPS never reach the disk.
- **Colour space**: no ICC profile = sRGB. An embedded profile is accepted when it is sRGB (its `rXYZ`/`gXYZ`/`bXYZ`
  primaries are sRGB's within 0.005, or, without matrix tags, its description names sRGB) or grey; Display P3, Adobe
  RGB, CMYK (profile or 4-component frame) are refused. Converting a wide-gamut upload would mean a re-encode and a
  second look the owner did not approve on the phone; Reveal renders sRGB for sharing already.
- Stored content-addressed under `<data dir>/edits/<sha[0:2]>/<sha256>` (two versions with the same image share a
  file).

**What a PUT does**, in one transaction (the item must be live, else `404 not_found`):

1. **No edit of this version yet**: needs the image; stored (`201`, `status: "created"`). The item may hold at most
   32 edits (`422 invalid_request`, reason `too_many_edits`).
2. **`editedAt` older than the stored edit's**: refused, `409 conflict` with `details: {reason: "older", editedAt,
   recipeDigest}` of the stored one (another device's later edit of the same version won; nothing changes).
3. **Same `recipeDigest` as stored**: the stored image stays and **the body is not read** (send none, or use
   `Expect: 100-continue` and the bytes never travel). Label, primary, virtual copy and `editedAt` are updated
   (`200`, `"updated"`), or nothing changed (`200`, `"unchanged"`, **no new revision**).
4. **Another digest**: needs the image (else `422 invalid_request`, reason `image_required`); it replaces the stored
   one (`200`, `"replaced"`). The old file goes when no edit names it.
5. A version stored on **another item** moves to this one (both items get a new revision).
6. `primary: true` clears the item's other primary.

Every change gives the item a **new revision** in the items feed. Answer:
`{"status", "itemId", "rev" (the item's), "edit": {…the fields above…}}`.

Image errors are `422 invalid_image` with `details.reason`: `format` (not the declared type, or not JPEG/WebP),
`decode`, `dimensions`, `colour_space`, `animated`. Header errors are `400 invalid_request` with `details.field`.
`413 too_large` over 8 MiB. `503 unavailable` when the server runs without previews (`features` then lacks `edits`).

### `GET` and `DELETE /v1/items/{itemId}/edits/{versionId}`

- `GET`: the stored (cleaned) image, `ETag: "<sha256>"`, `X-Hoard-Recipe-Digest`, ranges; `?v=<sha256>` makes the
  answer immutable. `404 not_found` when the item has no edit of this version. Uses the preview GETs' rate bucket.
- `DELETE`: removes the version's edit from the item; idempotent: `200 {"status": "deleted" | "absent", "itemId",
  "rev"}` (`absent` also for a tombstoned item, which has no edits). `404` only for an item the server never had.

### In the items feed

Every item served (`GET /v1/items/changes`, `/v1/items`, `/v1/items/{id}`, `/v1/items/lookup`, the gallery's
`/web/items/{id}`) carries `edits` when it has any, the primary first, then the others in the order they were first
uploaded:

```json
{"id": "…", "rev": 812, "kind": "pair", "…": "…",
 "edits": [{"versionId": "…", "label": "", "recipeDigest": "r1:9f2c…", "editedAt": 1763112000000, "primary": true,
            "isVirtualCopy": false, "deviceId": "…", "format": "jpeg", "width": 2560, "height": 1707,
            "bytes": 1432110, "updatedAt": 1763112004210},
           {"versionId": "…", "label": "Copy 1", "…": "…", "primary": false, "isVirtualCopy": true}]}
```

Absent = no edits (clients ignore unknown fields; ADR 0014 already says new fields may come). Tombstones have none.
An edit change is an item revision (the `items` counter), so a client following the feed sees it without anything
new; `GET /v1/changes` (Reveal's record feed) is untouched.

### Previews of an edit

Each edit is an **image job of its own** in ADR 0015's queue, `edit:<versionId>`: the same four files (thumb 512 and
screen 2560, JPEG and WebP), the same recipe, the CPU generator's still path (libvips, `VIPS_BLOCK_UNTRUSTED`, the
upload as descriptor 3), in the **image lane** (so `--preview-workers` bounds them with the rest). Its **content key**
is SHA-256 of `PREVIEW_VERSION`, the image recipe, the item id, the version id, the recipe digest and the upload's
SHA-256: a new render, digest or recipe gives a new key, and the usual rules follow (stale served while the new one is
made, `?v=` immutable, rows and files dropped when the edit goes). A PUT queues it at the warm priority; the backlog
scanner (which follows the items feed) treats edit jobs like image jobs (`--preview-backlog` `image`).

- `GET /v1/items/{itemId}/preview?size=&format=&v=&edit=<versionId|primary>`: the edit's preview, header
  `X-Hoard-Edit: <versionId>`; `404 preview_unavailable` reason `no_edit` when there is no such edit (or no primary).
  `edit` is refused on `/poster` (`400`).
- `POST /v1/items/previews` takes `"edit": "<versionId|primary>"`: thumb and screen states are the edit's (with
  `"edit": "<versionId>"` in the entry), `unavailable`/`no_edit` for an item without it; proxies are unaffected.
- The web routes are the same handlers (`/web/items/{id}/preview?edit=`).

### Lifecycle

- **Item replaced** (its RAW appeared, ADR 0014 "Item id"; the tombstone's `replacedBy`): its edits **move** to the
  new item (in the indexer's transaction), keeping one primary (the latest edited). Reveal's photo is the RAW
  anyway; the edit stays where the owner looks.
- **Item deleted** (a tombstone without `replacedBy`: files removed, a folder no longer indexed): its edits are
  **removed** with it; their files are swept (every 10 minutes, files no edit names and older than an hour). If the
  shot comes back (same item id), Reveal uploads again (client rule 6).
- **Version moved** (a PUT on another item): moved, as above.

### Web gallery (ADR 0018)

- **Library index**: each row gains `ed: [{id, l, p, c, v, w, h}]` (version, label, primary, virtual copy, its
  preview key when on disk, its size), the primary first; absent when none. Built from the items feed (edits bump the
  item) and the edit jobs' preview keys.
- **Grid**: a cell shows the **primary edit's** thumbnail when there is one, with the **Edited** badge; the badge
  (and the **Edited** filter) also keeps ADR 0018's meaning "edited in Reveal" for photos Reveal edited before it
  uploaded previews (`e`). An item with **virtual copies** shows a **stack** badge with their number; clicking it
  expands the stack in place (or **S** on the focused item): each copy is a cell after the item (its edit's thumbnail
  and label), clicking again folds it. Day and month counts still count items. Selecting or culling a copy cell acts on the item (culling is per item).
- **Viewer**: an item with edits opens on its primary edit (or on the copy whose cell was opened). **E** (and a
  header toggle) switches between the **Edited** and the **Original** (the header says "Edited in Reveal", with the
  copy's label for a copy); a menu lists the edits (main and copies).
  Edited mode shows the edit's screen preview and stays at its resolution when zooming (the upload is the edit's
  full size for the gallery); Original mode is the 0.8.1 viewer (full resolution at 100 %).
- **Downloads** still give the **originals** (single files, zips). The viewer's download menu adds **"Download edited
  JPEG"** (or WebP, as uploaded): the stored upload, named `<stem> (<label or "Edited">).jpg`
  (`GET /web/items/{id}/edits/{versionId}[/{name}]?download=1`).

### Storage and the setup page

- Migration `0008_item_edits.sql` (schema 8; forward only): `item_edits (version_id PRIMARY KEY, item_id, device_id,
  recipe_digest, edited_at, label, is_virtual_copy, is_primary, sha256, format, width, height, bytes, created_at,
  updated_at)`, indexed by item and by file.
- Files in `<data dir>/edits` (next to the database, part of it for backups: unlike previews, **only Reveal can make
  them again**). Their previews live with the others under `--previews-dir`.
- The setup page's Storage tab reports the edits (count, items, virtual copies), the uploaded files and their size,
  the previews made from them (size, queued, failed) and the folder's size; the previews report counts edit jobs as
  one line `edit`.
- `GET /v1/server`: `features` gains `edits` (present when previews are on) and an `edits` object with the limits:
  `maxBytes` (8 MiB), `maxEdge` (2560), `minEdge` (16), `formats` (`jpeg`, `webp`), `maxPerItem` (32), `maxLabel`
  (64), `maxDigest` (128).

### Client rules (Reveal)

Everything Reveal needs, in order. Paths are relative to the paired server; every request carries the device key.

1. **Feature check.** `GET /v1/server`: upload only when `features` contains `"edits"`; read the limits from
   `edits` (do not hard-code them). Older servers: skip silently (the routes answer `404`/`405`).
2. **Which versions.** Every version whose committed recipe is **edited** (not the default recipe): the main version
   (position 0) and **virtual copies** (Reveal ADR 0025). **Not** sync conflict copies (`copy_of` set): they are a
   sync artefact, resolved in Reveal. An unedited version has no edit (DELETE it if the server has one).
3. **Which item.** Look the photo's main fingerprint up with `POST /v1/items/lookup {"fingerprints": [...]}` (batch:
   up to 1000) and take the item whose `matches` entry has that fingerprint; the item id may change when a lone JPEG
   later becomes a RAW+JPEG pair (the server moves the edits with it, and the old item's tombstone names the new one in
   `replacedBy`). A photo the server does not index (`missing`: a phone-only photo not backed up) is skipped until a
   later pass finds it.
4. **Render.** The version's committed recipe through Reveal's full export pipeline: **sRGB**, **upright** (crop and
   rotation applied, no EXIF orientation), long edge `min(maxEdge, the edit's long edge)` (never upscaled), **JPEG
   quality 90** (WebP quality 85 is accepted too); metadata need not be written (the server removes it, GPS included).
   Encode at most `maxBytes` (a 2560 px JPEG q90 is 1-3 MB).
5. **Upload.** `PUT /v1/items/{itemId}/edits/{versionId}` with `Content-Type`, `X-Hoard-Recipe-Digest`,
   `X-Hoard-Edited-At`, `X-Hoard-Label` (percent-encoded), `X-Hoard-Virtual-Copy`, `X-Hoard-Primary`:
   - **recipeDigest**: a digest of everything that changes the pixels, e.g. `r<render pipeline version>:` +
     SHA-256 (hex) of the canonical recipe JSON (Reveal ADR 0003, after its migrators). It must change when the
     recipe or Reveal's rendering changes, and stay equal otherwise (an unchanged edit must not upload again).
   - **editedAt**: the version's modification time (its sync record's `modifiedAt`), so two devices agree.
   - **label**: the version's name (`""` for the main version); **isVirtualCopy**: position > 0 and not a conflict
     copy; **primary**: `true` for the version Reveal's grid shows first for the photo (the main version when it is
     edited; else the first edited copy), `false` for the others.
   - When only the label, primary or editedAt changed (same digest), send the same PUT **without a body**: `200
     "updated"`.
   - Debounce: upload when an edit settles (the editor closes, or the version record is pushed), not on every slider
     step; at most one upload per version in flight.
6. **Reconcile.** After each sync pass (or on the items feed, for the photos Reveal has): compare the server's
   `edits` of the item with Reveal's versions: upload the missing ones and those whose `recipeDigest` differs; send a
   metadata-only PUT when label/primary/virtual copy differ; DELETE `edits` whose version is deleted (a tombstone in
   Reveal's records) or no longer edited. An edit uploaded by another Reveal device for the same version is already
   right when its digest matches.
7. **Answers.** `201`/`200`: done (keep `edit.recipeDigest` as uploaded). `409 conflict` reason `older`: another
   device uploaded a later edit of this version; do not retry (re-render only if this device's version is newer after
   the next records pull). `422 invalid_image`: a bug in the render (wrong colour space, size); log it, do not retry
   the same bytes. `422 invalid_request` `image_required`: send again with the image; `too_many_edits`: stop for this
   item; `not_applicable`: the item is a video. `404`: the item is gone; look it up again (rule 3). `413`: re-encode
   smaller. `429`/`503`: retry after `Retry-After`. Network errors: retry the whole PUT (it is idempotent).
8. **Delete a version** in Reveal → `DELETE /v1/items/{itemId}/edits/{versionId}` (idempotent; `absent` is success).
9. **Culling** does not travel with edits: keep using ADR 0016 (`POST /v1/culling`, per item).

### Limits

| Limit | Value | Over it |
| --- | --- | --- |
| Upload body | 8 MiB | `413 too_large` |
| Long edge | 16-2560 px | `422 invalid_image` `dimensions` |
| Edits per item | 32 | `422 invalid_request` `too_many_edits` |
| Label | 64 characters, no control characters | `400 invalid_request` |
| Recipe digest | 1-128 characters `A-Z a-z 0-9 . _ : + / = -` | `400 invalid_request` |
| PUT / DELETE rate | the device's API bucket (30/s, burst 120) | `429` |
| GET of an edit, previews | the preview bucket (200/s, burst 600) | `429` |

## Measurements

On cachyos-fps (16 threads, shared with other work), 2560 × 1707 synthetic renders (ffmpeg `testsrc2`, plain and with
grain added: the grain is the worst case for both encoders), encoded by libvips as Reveal would send them (JPEG q90,
WebP q85). `BenchmarkCheck` (`HOARD_EDIT_BENCH=<file>`, one core) and the CPU generator's five vips runs (two threads,
nice 10, as in production):

| | JPEG q90 | WebP q85 |
| --- | --- | --- |
| Upload size, plain / grain | 0.40 / 2.64 MB | 0.12 / 1.26 MB |
| Check (decode whole, colour space, strip), plain / grain | 84 / 134 ms | 44 / 189 ms |
| Thumb + screen, JPEG and WebP (one job), grain | 1.19 s | 1.22 s |
| Previews made from it, grain (screen JPEG / WebP, thumb JPEG / WebP) | 723 / 1127 / 13 / 6 KB | same |

Storage per edit: the upload (0.4-2.6 MB) plus its four previews (0.5-1.9 MB, ADR 0015's sizes for camera photos).
A thousand edited photos cost about 2-4.5 GB in the data and previews directories.

## Consequences

- The gallery shows the owner's finished photos as Reveal renders them, virtual copies included, without the server
  learning Reveal's pipeline. Native Hoard reads the same `edits` and `?edit=` previews.
- An edit costs the phone one render and one upload per change of its recipe; a metadata change costs a header-only
  request; an unchanged edit costs nothing (the digest).
- Edits are data the server cannot rebuild: they live in the data directory (backed up with the database), not with
  the previews. Deleting an item deletes its edits; Reveal re-uploads if the shot returns.
- `/v1` is unchanged for released Reveal: new routes, a new optional field on items, a new `features` name.
- Wide-gamut edits (Display P3) are refused for now; if Reveal wants them, a later version can accept them and convert
  (a re-encode) or keep them as they are for P3 screens.

## Alternatives considered

- **The server renders the recipe**: it would need Reveal's pipeline (GPU shaders, masks, AI models) in Go or a
  headless Reveal: out of reach, and two renderers would disagree.
- **Multipart (image + JSON fields)**: one encoder more on the phone, and a metadata change would resend the image or
  need a second route. Headers carry six small values; percent-encoding covers the label.
- **Edits as a record kind or inside `version` records** (`/v1/changes`): records are ≤ 2 MiB of JSON synced to every
  Reveal device; images are not records, and Reveal's feed must not carry megabytes per version.
- **Blobs (`/v1/blobs`, the backup root)**: content-addressed uploads exist, but blobs are Reveal's smart previews and
  originals with their own reference rules, chunked uploads (overkill for ≤ 8 MiB) and a backup root that may not be
  configured. Edits need the item link, the digest rule and their own lifecycle.
- **Re-encoding every upload** (normalising format and quality): a generation loss and 0.5-1 s of CPU per upload for
  nothing; stripping metadata in place keeps Reveal's bytes.
- **Per-copy culling**: Reveal ADR 0025 keeps culling per photo (one rating in the sidecar); the gallery does the same.
- **Last arrival wins** for two devices: an offline tablet uploading an hour later would replace a newer edit;
  `editedAt` (the version's modification time) orders them like Reveal's own conflict rule.

## Tests

Go (`go test -race ./...`): `internal/edits` (cleaning without re-encoding, sRGB/grey/P3/Adobe/CMYK profiles, a
4-component frame, sizes, truncation at every byte, WebP simple/extended/animated), `internal/server` (`TestEditUploadFeedAndPreviews`: create,
copy, primary switch, unchanged without a revision, previews by `edit=` and warm, the stored file without EXIF,
replace, older `409`, delete, storage report, limits; `TestEditValidation`: every refusal, clipping, 32 per item, one
file per image; `TestEditLifecycle`: move, replacement, deletion, sweep; `TestWebEdits`: the library index's `ed`,
immutable thumbnails, download names, the LAN gate), `internal/previews` (`TestCPUEditedPreview` with real libvips,
JPEG and WebP; `TestEditKeys`). Reveal's contract test (`TestRevealProtocolContract`) still passes. Web: vitest
(`lib/edits.test.ts`: stacks, filter) and Playwright (`e2e/gallery.spec.ts` "edits": a device key uploads a primary
edit and a virtual copy, the grid shows the badge, the viewer toggles with E, the stack expands); screenshots in
`docs/testing/web-gallery/`.
