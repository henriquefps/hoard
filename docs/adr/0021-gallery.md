# ADR 0021: The Gallery tab (phone + hoard-server items, culling everywhere)

- **Date**: 2026-11-15
- **Status**: Accepted (Hoard 2.1.0, phase 3 of ADR 0019)
- **Builds on**: ADR 0013 (protocol, pairing, keys), 0014 (items), 0015 (previews), 0016 (culling sync, "Client
  rules"), 0017 (device identity, "Client calls"), 0020 (edited previews), 0008/0009/0011 (culling, analysis,
  sidecars), 0010 (Edit in Reveal; amended in the same PR for server references).
- **Amended**: 2026-11-15 (Hoard 2.2.0, ADR 0022): `gallery.db` version 2 (SMB ↔ NAS mappings), `items-by-path` is
  called, and the sync engine also carries folder culling of mapped SMB shots (sections "Modules", "Seams", "Consequences").
- **Amended**: 2026-11-15 (Hoard 2.2.2): the grid's place, the fast scroller's touch area, the viewer offline
  ("Amendment 2.2.2").

## Context

Hoard 2.0.0 (ADR 0019) has a Gallery tab that says "later". The owner's library lives on hoard-server (NAS folders
and uploads, ~21,000 items) and on the phone (camera, screenshots, WhatsApp). The tab must show both as one timeline,
let the owner cull anything in it with the phase 2c viewer, and keep culling in step with Reveal and the web gallery
through the server (ADR 0016). Backup, freeing space, video compression and server-side analysis are later phases.

## Decision

### Modules

- **`:core:server`** (new, package `dev.hfps.hoard.server`): the hoard-server client.
  - `api/`: `ServerApi` over `HttpURLConnection` (a dozen JSON calls, a file download and ranged reads do not need a
    client library), kotlinx.serialization models read with `ignoreUnknownKeys` and **defaults for every field the
    server may leave out** (the E2E found uploaded originals whose files have no `name`; one strict field would stop
    the whole feed).
  - `Identity.kt`: the `v1:` fingerprint (checked against `server/testdata/fingerprint-vectors.txt`), the derived item
    id (`uuid8(SHA-256("hoard-item\0" + fingerprint))`, checked against the server's answer), the pairing code parser
    (`revealsync://pair?…`, parameters in any order) and `ServerAddress` (plain HTTP only to loopback, RFC 1918, ULA,
    link-local, CGNAT/Tailscale and local names; anything else must be `https://`; see "Plain HTTP").
  - `ServerKeys.kt`: the `rvk1_` key in the Android Keystore (AES-256-GCM, alias `dev.hfps.hoard.server.v1`, the
    ciphertext in private SharedPreferences excluded from backups), never in Room or a log.
  - `db/`: **`gallery.db`**, a Room database apart from `hoard.db` (which stays Hoard 1's schema v7 table for table,
    ADR 0019): `server_account` (one row), `server_items` (+ `server_item_files`, every member fingerprint),
    `culling` (+ `culling_fingerprints`), `culling_pending` (the queue), `device_media`, `device_folders`,
    `device_pushed`; since 2.2.0 `smb_mappings` and `smb_mapping_misses` (ADR 0022). Items and records are a cache
    ("Clear cache" pulls again); the queue, the device index and the mappings are not.
  - `sync/`: `CullingRules` (pure, rules 1–4 and 6 of ADR 0016), `Feed` (a page as writes), `SyncEngine` (pair, claim,
    declare `culling`, push → pull → push, see "Sync passes"), `GallerySyncWorker` (hourly with a network, and "soon"
    after a decision), `OfflineThumbsWorker`.
  - `net/`: `LanRelay` and `LanAddresses` (see "Plain HTTP").
  - `preview/`: `PreviewCache` (thumb WebP / screen JPEG, `edit=primary` for Reveal's edit, 202 → retry after
    `Retry-After`, LRU within a budget), `HttpRangeSource` (an original read by range in 256 KiB blocks, for the image
    core), `OriginalCache`, `OfflineThumbs`, `BoundedDir`. A download is serialised per file by `KeyedLocks` (one
    mutex per key, dropped only when its last waiter leaves) and written to a unique temporary file
    (`<name>.<random>.part`) renamed over the target when complete, so two callers (or two processes) never share a
    half-written file.
- **`:native`**: `gallery/` (the model, the device index, the repository, the culling backend and store, thumbnails,
  the Reveal sender), `ui/gallery/` (grid, filters, viewer), `ui/server/` (Settings › Photo server, pairing, the
  "same phone" question).

### Pairing and device identity

The pairing code comes from the server's admin page: Google's code scanner reads the QR code (Play services, no camera
permission, Hoard never sees the image) or the link is pasted. `POST /v1/pair` sends `app` (`hoard`, or
`hoard-debug` for debug builds, ADR 0017), `manufacturer` and `model`. A code whose `server` differs from the answer's
`serverId` is refused. When the answer has model matches, one question follows ("Is this the same phone as Reveal on
Pixel 8?": Same phone → `POST /v1/device/claim`; Another device → nothing). Settings shows "This phone" from
`GET /v1/device`. Hoard declares `features: ["culling"]` with `PATCH /v1/device` once (ADR 0016 rule 6). A paired
server can move to another address of the same server (Settings › Address: accepted when `GET /v1/server` there
answers the same `serverId` for this key); `https://` is preferred and Settings says when the connection is plain
HTTP. A declaration that fails is retried on every sync, and **no culling is pushed until it succeeds** (a push from a
device that has not declared would let the server bridge its records again, ADR 0016 rule 5).

### Plain HTTP

hoard-server speaks plain HTTP (ADR 0013: TLS is the reverse proxy's job, e.g. `tailscale serve`), but the app's
network security config stays as before 2.1.0: **cleartext only to `127.0.0.1` and `localhost`**, HTTPS everywhere
else. Android's policy cannot name address ranges (a `domain-config` lists names, not `192.168.0.0/16` or
`100.64.0.0/10`), so opening cleartext for the server would have meant opening it app-wide. Instead:

- `ServerAddress` refuses, at pairing and on an address change, a plain `http://` URL whose host is not a LAN,
  Tailscale or loopback IP literal (`LanAddresses`: 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64.0.0/10,
  ::1, fe80::/10, fc00::/7 with Tailscale's fd7a:115c:a1e0::/48) or a local name (no dot, `.local`, `.lan`, `.home`,
  `.internal`, `.home.arpa`, MagicDNS `*.ts.net`).
- `ServerApi` sends `https://` and loopback URLs as they are. A plain-HTTP server elsewhere is reached through
  **`LanRelay`**, a byte relay listening on `127.0.0.1:<random port>` (allowed by the policy) that connects to that one
  server only after resolving its name **at every connection** and checking that every address is a `LanAddresses`
  address; anything else is closed before a byte is sent (a name that resolves to a public address, a public IP
  literal). The relay also serves the video player's proxy URL. `/v1` does not depend on the `Host` header, which then
  names the relay.
- **Considered and not done: a sentinel host (`hoard-lan.invalid`) with a custom `Dns`.** A `domain-config` for one
  sentinel name would let the client resolve it to the paired host only, applying `LanAddresses` to every address of
  each lookup. It needs an HTTP stack that takes a `Dns`: the server client is `HttpURLConnection` (Android's
  built-in one cannot be given a resolver, and the project has no OkHttp), and the video proxy URL goes to the
  player's own network stack, which would not resolve the sentinel. Moving both to OkHttp is a client rewrite and a
  new library for the APK, so the relay stays. `/v1` does not depend on `Host` (checked in `server/`: only the `/web`
  origin checks use it), so the idea stays open for a later release.
- Every other cleartext request of the app (a public `http://` link, a library) is still refused by the platform.
  Checked on the emulator (`docs/testing/gallery/README.md`): a public `http://` URL is refused, the paired LAN server
  (`http://10.0.2.2:<port>`) works through the relay.
- **`https://` is preferred**: a server paired by its plain-HTTP MagicDNS name (`http://<machine>.<tailnet>.ts.net:…`)
  is asked, at most once a day, whether `https://<machine>.<tailnet>.ts.net` (`tailscale serve`) answers
  `GET /v1/server` with the same server id for this key; if so, the pairing moves there. Settings › Address does the
  same for any address the owner types.

### The items feed

`GET /v1/items/changes?include=items,culling` with one cursor (ADR 0016 rule 3), pages of `limit` (≤ the server's
`maxPullLimit`), until `hasMore` is false; `invalid_cursor` restarts from the start once. A page that says `hasMore`
without moving the cursor (or 50 empty pages in a row) ends the pass, logged; the next sync goes on from the last
cursor. A page is one Room
transaction: live items replace their rows and member files; tombstones go; a tombstone or record with `replacedBy`
moves its queued changes to the replacement and drops the old record. Moving merges per field: when the replacement
already has a change of that field queued, the one with the later `modifiedAt` stays (the server's own rule); a moved
change gets `baseRev` 0 (it never saw the replacement's field) and its attempts start again.

### Sync passes

A sync is **push, pull, push**, and **the pull never depends on the push**. Each push pass sends every due change of
the queue once, in batches of 500 (ADR 0016 "Wire format"). A batch the server refuses (an error status, or no
connection) is marked (`attempts` + 1) and held back — 30 s, doubling, at most 6 hours — and the pass stops; the pull
goes on and the sync succeeds (Settings shows the push error). A change the answer leaves out (no result for its
index) is marked the same way, so no batch is sent twice in a pass and a broken answer cannot loop. A new decision on
a held-back field replaces the change (attempts 0) and goes out at once. Only a revoked key (401) ends the sync, as
the pull would be refused too. Hoard keeps **every** live item: the grid,
search and filters run on the local copy, so the Gallery works offline and `/v1` needs no text search (there is
none; search is name/folder over the server's own data, mirrored).

### The phone's photos

`DeviceIndexer` reads MediaStore (images and videos) for the default folders (DCIM, Pictures, Movies, WhatsApp media,
both layouts) and the folders the owner adds, with All files access or `READ_MEDIA_IMAGES/VIDEO`. Rows map to Hoard's
locations (`device`, `vol-<uuid>`, ADR 0005). Files are grouped into shots as the server groups them (folder +
directory + case-insensitive stem; RAW, then renderings JPEG before HEIF, then videos; ADR 0014). The `v1:`
fingerprint of each file is computed **lazily**: never at start-up (the first reads wait 15 s), newest first, one file
at a time, 2 MiB per file; a motion photo is recognised in the same read (Google `MotionPhoto`/`MicroVideo` XMP,
`.MP.` names, Samsung `SEFT` trailer). A shot any of whose files is a member of a server item is **one** entry,
"backed up" (ADR 0014 lookup, done locally over `server_item_files`).

### The grid

One list, newest first (the server's `sortTime`, else MediaStore's `DATE_TAKEN`), sticky day headers (month headers
at the densest zoom), a fast scroller with a month bubble and year ticks, "Go to a month", pinch to change the number
of columns (2–9; tablets and landscape get proportionally more), pull to sync. Badges: where (phone / server / both),
RAW+JPG, video duration, motion, stars, pick / reject, Edited in Reveal. Filters: kind, where, stars, pick / reject,
edited, camera, lens, dates; search by every word of names and folders. Cells use the phone's own thumbnails for phone
files and the server's thumb (or the 256 px offline thumb) for the rest; the primary edit's thumb when Reveal edited
the shot (ADR 0020).

### Culling on any item

The phase 2c viewer (`CullViewer`) runs over the grid's items through two seams added to `CullViewModel`:

- `CullStore` (decisions, sessions, sidecars, analyses): `FolderCullStore` is the folder culling of 2c, unchanged;
  `GalleryCullStore` routes decisions. **Anything on the server** is decided through the server's culling: the cached
  record plus a queued change per field (rule 2: the last decision wins, `baseRev` = the cached rev, `modifiedAt` =
  when the owner decided, kept offline), pushed soon after. A pulled record never overrides a field that has a pending
  change (rule 3). **A phone-only shot** is decided in its folder's `cull_decisions` row, the same row culling that
  folder from the Files tab uses, and is **handed to the server when the shot reaches it** (matched by fingerprint):
  queued with `baseRev` 0, so the server keeps whichever decision is newer (step 5). A shot on both writes both. Later
  Files-tab decisions on a shot already handed over are handed over again (`Handover`), **each with the time it was
  made**: the row's `updated_at`, which for a decision imported from a sidecar is the sidecar's mtime (ADR 0016 rule
  4: an old sidecar must not beat a newer server value), and for an **unmark** (the Files tab deletes the row) the
  folder's `cull_sidecars` row, which outlives it (`decided_at`, written by every decision of the culling screen, or
  `sidecar_mtime` for a clear imported from a sidecar). A clear made after the last hand-over goes to the server as
  `rating 0` / `flag none` with that time; one made before it was already part of it.
- `CullBackend`: `GalleryCullBackend` gives phone files to the image core as a folder does (sidecars written next to
  them when Save ratings is on, ADR 0011 unchanged; no sidecar import from the Gallery), and server items as their
  previews (screen, the edit when shown) and their originals by range for 100 % and RAW previews. Analysis (ADR 0009)
  runs on the phone's file, or on the server's 2560 px screen preview, for the six shots around the one on screen.

The viewer adds **Edited / Original** for Reveal's primary edit, **playback** (the phone's file, else the server's
H.264 proxy streamed with the key, ADR 0015) and **Edit in Reveal** (below). Metadata is cheap at open (from the
entries; thousands of shots open at once) and read from EXIF for the shot on screen.

### Edit in Reveal

ADR 0010 "Server items": a shot the paired server holds goes to Reveal ≥ 0.9.0 **by reference** (server id, item id,
fingerprints, name; no URI, no grant, no culling keys), after its pending culling is pushed (best effort, 3 s). A shot
only on the phone goes as today (URIs, grants, `CULLING`). With an older Reveal, phone files go as URIs and a
server-only shot cannot go (the owner is told to update Reveal); Hoard never streams server photos to Reveal.

### Storage on the phone (the 21k-item library)

- **Previews**: thumb and screen previews in the cache directory, LRU within a budget (256 MB / 1 GB / 4 GB).
- **Offline thumbnails**: the server's thumb scaled on the phone to a 256 px short edge, WebP q60 (≈ 8–15 KB), one per
  item in `noBackupFilesDir`, filled by `OfflineThumbsWorker` on **unmetered** networks only (optionally only while
  charging), retried with back-off while the server is still making thumbs. No server change was needed: a 256 px
  server size or a bulk thumbnail route would be an additive `/v1` change, left for when measurements ask for it.
- **Kept originals**: the originals of server shots the owner opened (on unmetered networks while viewing; always at
  100 %), within a budget (512 MB / 2 GB / 8 GB), least recently opened dropped first; a kept original is read from the
  phone (zoom, RAW decode, analysis). A shot opened again shows its screen preview from the cache at once.
- **Start-up does no bulk file I/O**: one MediaStore query, Room reads; fingerprints later and paced; cache sizes are
  walked only when Settings asks or a file is added.

### Seams for later phases (no UI)

Backup (`POST /v1/uploads` of what `device_media` + `server_item_files` say is missing), free up space (phone shots
whose every file is on the server), video compression and server-side analysis plug into `DeviceIndexer`,
`GalleryRepository` and `CullBackend.analyze`. `POST /v1/items/by-path` (hoard-server 0.10.0, feature
`items-by-path`) is called since Hoard 2.2.0 by the SMB path mapping (ADR 0022, `:core:server` `paths/`), with
`GET /v1/items?folder=&path=`; its culling dual write queues through this ADR's `SyncEngine` (plus `redate`, which dates a
pending change by the sidecar written with it).

## Amendment 2.2.2: the grid's place, the fast scroller, the viewer offline

Owner feedback on 2.2.1 with ~19,000 server items.

- **The grid keeps its place.** The cause of the reset was the shell, not the grid: `TabbedLayout` put the screens in
  a different place of the composition when the navigation was hidden, so the Gallery's viewer (which hides the tab
  bar) recomposed the whole `NavHost` from scratch, and with it the grid's `rememberLazyGridState()` and the "bring the
  last viewed shot into view" state. The screens now keep one place whatever the bar shows. The grid's
  `LazyGridState` lives in `GalleryViewModel` (it survives the viewer, the bar and the tabs), with an **anchor by key**
  (`GridAnchor`: the first visible shot and its offset) in the ViewModel's `SavedStateHandle`. On every new layout
  (a sync adding shots above, a decision changing a filtered list, a new process) the grid goes back to the anchor's
  row, found by key (the lazy grid's own key lookup only searches near the old index, so hundreds of new shots above
  moved it). Back from the viewer, the shot it closed on (the one opened, or the one swiped to) is scrolled into view,
  mid-screen, only when it is not already fully visible.
- **The fast scroller** takes a touch only when it starts on its shown thumb (44 dp tall, ± 8 dp) inside a 24 dp strip
  at the right edge (`ScrollerGeometry.onThumb`). The handling sits on the grid's own box at the Initial pass, so any
  other touch, the strip included, reaches the grid untouched (2.2.1's 120 dp box took every vertical drag as a jump).
  Grabbing keeps the finger's place on the thumb; the thumb shows while the grid moves and fades 1.5 s after.
- **The viewer offline.** For a server shot (`ServerShotImages`), the fast step is what the phone holds, without the
  network: the screen preview (final), else a thumbnail as a **low-resolution stand-in** (`ScreenFile.lowRes`): the
  server thumb in the preview cache (the shown edit's, then the original's), else the 256 px offline thumbnail. The
  final step is the screen preview from the server. While it loads a spinner shows over the stand-in; if it fails, a
  subtle "Offline · low resolution" note stays. A shot with no copy at all and no network gets an offline placeholder
  (`ErrorCode.UNREACHABLE`), never "This shot could not be shown" (kept for a server that answers without a preview).
  The filmstrip's thumbnails follow the same chain, and grid cells too large for the offline thumbnail fall back to
  it when the server cannot be reached.
- **Why the filmstrip was empty**: the grid draws server cells up to 384 px from the offline thumbnails
  (`noBackupFilesDir/gallery-thumbs/<id>.webp`, no network), and only larger cells or edits from the preview cache
  (`cacheDir/gallery-previews`, `<id>-thumb-o-o.webp`). The viewer and its filmstrip read only the preview cache. A
  phone in portrait at 3–5 columns has 270–360 px cells, so for every shot the grid showed from an offline thumbnail
  the preview cache had no thumb: offline, the filmstrip and the viewer found nothing. Same item, two keys in two
  stores; not an eviction, not a size cap. The fix reads both. Limits that remain, see "Measurements 2.2.2".

## Consequences

- Hoard has a second database. `hoard.db` keeps Hoard 1's schema; `gallery.db` starts at Room version 1 with its own
  schema history in `core/server/schemas/` (version 2 in 2.2.0: the SMB mappings of ADR 0022, migration 1 → 2).
- Hoard keeps a copy of the whole items feed (a row per item with its JSON, its member files and culling records;
  not yet measured on the owner's 21k library), plus offline thumbnails when on (≈ 8–15 KB per item).
- Cleartext stays limited to loopback in the network security config; a plain-HTTP server is reached through the
  loopback relay, which only connects to LAN, Tailscale and loopback addresses ("Plain HTTP").
- The culling viewer is shared by folders and the Gallery; folder culling is unchanged (`FolderCullStore`).

## Measurements

Emulator (Pixel API 36, x86_64, **software GPU** `swiftshader_indirect`, 3 GB) on cachyos-fps under load, my own
hoard-server 0.9.0 container with 10,037 items (the web gallery's synthetic library + 10,000 bench JPEGs):

| What | Result |
| --- | --- |
| First pull of 10,000 items (feed pages of 500) | 7.8 s |
| Cold start (R8 release build, 10k items) | 167–266 ms (`am start -W`) |
| Memory (R8 release, grid open) | 59 MB PSS |
| Grid scroll, 30 flings (R8 release) | p50 38 ms, p90 53 ms, p99 65 ms, 20 % janky |
| Baseline on the same emulator: Settings scroll | p50 28 ms, p90 53 ms, 17 % janky |
| Offline thumbnails | ≈ 8 KB each for the bench images (49 MB on disk for 6,249) |

The software GPU dominates frame times (a plain column scrolls at p50 28 ms there); the grid stays within the
baseline's p90. Real-device numbers are for the owner's phone.

Details, screenshots and the E2E run: `docs/testing/gallery/README.md`.
