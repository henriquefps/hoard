# ADR 0008: Photo culling mode (RAW, JPEG and RAW+JPEG pairs on any location)

- **Date**: 2026-11-09
- **Status**: Accepted (1.5.0). Copying picks runs on the transfer engine of
  [ADR 0007](0007-transfers-any-location.md).

## Context

The owner shoots a Sony ZV-E10 in RAW + JPEG. The workflow: the camera's SD card in a USB-C reader
(or a folder on the NAS / Mac over SMB, or the phone itself) → **cull on the phone** → copy the
keepers anywhere (phone, USB drive / SD card, SMB share; never forced to the NAS). It must work for
RAW-only, JPEG-only and RAW+JPEG shots; a pair is one shot and a decision applies to both files.
No compatibility with the old macOS PhotoCull app (no IPTC, no sidecars).

ADR 0005 made every place files live a _location_; ADR 0006 built the native image core (preview
extraction with partial reads, screen and region decode, LibRaw, caches, priority prefetch,
sharpness) and left two choices to this phase: the culling surface and the plugin methods.

## Decision 1: the surface is the WebView, the pixels are native files

Two options were on the table:

1. **A native full-screen viewer** (an Activity with a pager and a tiled zoom view drawing the core's
   Bitmaps directly, like `WebViewerActivity`).
2. **The React UI in the WebView**, showing **file URLs to JPEGs the core writes** (screen-sized
   images, grid thumbnails and 512 px region tiles at 100%).

**We chose 2.** Reasons:

- **One UI, one design system.** Stars, pick/reject, filters, the filmstrip, the EXIF overlay,
  compare, undo, dialogs and the copy flow are lists, menus and dialogs: in the WebView they follow
  `hfps-app-ui`, i18next and the existing components. A native viewer would duplicate all of that
  in Kotlin Views (and its strings outside i18next), plus a channel to keep decisions in sync with
  the app's SQLite (which only JS writes) while the WebView sits paused behind another Activity.
- **It is fast enough, measured** (below): the image on screen is a JPEG the core already wrote;
  swiping lands on an image that was prefetched natively and pre-decoded in the WebView
  (`img.decode()`), so the next shot is on screen in 0–30 ms; 100% is a few 512 px tiles decoded by
  `BitmapRegionDecoder` (5–30 ms each, cached on disk) drawn over the screen image.
- **It runs in the browser.** The web mock implements the same contract with vector images, so the
  whole flow is developed, tested and screenshotted without a phone or private photos.
- **Bytes still never cross the bridge** (ADR 0001): JS gets URLs (`/_capacitor_file_/…`) and sizes.

The price: each screen image is re-encoded once as a JPEG (2000×3000 from a 6000×4000 camera JPEG:
~150 ms on the emulator, then disk-cached), the WebView decodes it again, and zoom tiles are JPEGs
(q90) instead of raw bitmaps. Gestures are written straight to `transform` (no React render per
move), and the layout is re-done at the new scale only when a gesture ends, so the image stays
crisp without per-frame layout.

## Decision 2: the plugin contract (`cull*` methods)

All on `HoardSmb` (`src/plugins/smb/definitions.ts`), implemented in `web.ts` (+ `mockShoot.ts`,
tested in `web.test.ts`) and natively in `HoardSmbPlugin.kt` → `smb/cull/CullService.kt` →
`image/ImageCore.kt`. No new error code was needed: `NOT_FOUND` for an unknown handle, shot or a
dropped SMB session (the UI reconnects or reopens), `CANCELLED` for superseded work, and the
location's own codes (`ACCESS_DENIED`, `STORAGE_PERMISSION`, `UNAVAILABLE`…).

| Method                                                                                 | What it does                                                                                                                                                                                                                                                                                                                |
| -------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cullOpen({ location, path })`                                                         | Lists the folder and pairs files by case-insensitive stem (`ShotPairing`): `{ cullId, shots, libRaw, access }`. A shot's id is its lowercase stem.                                                                                                                                                                          |
| `cullMetadata({ cullId, location, shotIds })`                                          | EXIF essentials per shot (capture time with sub-seconds and offset, exposure, aperture, ISO, focal length + 35 mm, lens, camera, upright size, orientation, rating), from the RAW's header when there is one (two small reads), 4 at a time natively.                                                                       |
| `cullImage({ …, shotId, longSide, quality, background? })`                             | A screen image file: `fast` = the plan's first step (the RAW's embedded preview, served **as it is** when upright: no decode at all), `final` = the best (camera JPEG downsampled, or LibRaw half size for a RAW-only shot). `background` marks a neighbour of the shot on screen: queued like prefetch and joined with it. |
| `cullThumbnail`                                                                        | Grid thumbnail: the RAW's preview; a local JPEG decoded with a large sample; a remote JPEG-only shot's EXIF thumbnail.                                                                                                                                                                                                      |
| `cullPrefetch({ …, shotIds, current, longSide, ahead, behind, fastAhead, zoomAhead })` | Keeps the window prepared (see below) and cancels what left it.                                                                                                                                                                                                                                                             |
| `cullZoom`                                                                             | Prepares the 100% image (stage the JPEG, or render a RAW-only shot with LibRaw) and returns its size and source.                                                                                                                                                                                                            |
| `cullTile({ …, x, y, width, height, sample })`                                         | One upright region at sample 1/2/4/8/16 as a cached JPEG.                                                                                                                                                                                                                                                                   |
| `cullSharpness({ …, shotIds })`                                                        | Variance-of-Laplacian scores (whole frame and best of 4×4 tiles, ADR 0006) on the preview, for compare.                                                                                                                                                                                                                     |
| `cullClose`                                                                            | Forgets the handle; when no folder is open, cancels prefetch and closes the region decoders.                                                                                                                                                                                                                                |

Every call after `cullOpen` passes `location` again: an SMB session can be replaced between calls
(reconnect over another route), and native always reads through the current one.

## Native additions (all in `dev.hfps.hoard.image` unless noted)

- **Sources**: `ImageFile.local` (a plain path: phone storage or a volume with all files access);
  decoders and LibRaw read it **in place** instead of staging a copy. `DescriptorSource` reads SAF
  documents through a `ParcelFileDescriptor` + `FileChannel` (positional reads). `SmbFileSource`
  stages whole files with `PipelinedFileReader` (4 READs in flight) through the new
  `BulkCopySource`. `LocalFs` gained `pathFile` and `openDescriptor` (`smb/location/`).
- **Files for the WebView**: `ImageCore.screenFile`, `thumbnailFile`, `tileFile`, `zoomInfo`, disk
  caches `screens/` (384 MB) and `tiles/` (128 MB), an LRU of 4 open region decoders, and
  `prefetchFiles`.
- **Prefetch plan** (`CullPlans.prefetch`, pure, JVM-tested): fast images first for the shots up to
  `fastAhead` (6) ahead and 1 behind (a RAW preview is ~0.5 MB), then final images for 2 ahead and 1
  behind (a whole 5–8 MB camera JPEG over SMB), then the 100% source of the next shot. Priorities
  never repeat; the window moves with every page and cancels the rest.
- **Scheduler urgent lane**: `PriorityScheduler(urgentThreads = 1)` keeps one worker for priority
  0 (the shot on screen), so a prefetch busy staging a JPEG over a slow link never makes the current
  shot wait. Found while measuring SMB paging (below).
- **Glue**: `smb/cull/CullService.kt` turns a location + folder into shots and `ShotRequest`s
  (cache identity `smb:<machine>:<share>`, `device` or `volume:<uuid>`), up to 4 open folders.

## The UI (`src/features/cull/`)

- **Entry**: the browser shows **Cull photos** in a folder's header when it holds at least two
  photo files (RAW, JPEG, HEIF…; `src/lib/photos.ts`, the same table as the native side).
  `/cull/$locationId?share=&path=`, its own lazy chunk; breadcrumbs `… › folder › Cull`.
- **Grid**: one cell per shot (pairs collapsed), badges `RAW+JPG` / `RAW` / `JPG`, pick (check,
  primary) or reject (cross, destructive; the cell is dimmed), stars, and a **burst hint** ("2/5")
  on runs of frames less than a second apart in capture order (`burstHints`; full burst grouping
  is phase 3). Capture-time order (EXIF with sub-seconds, names for ties) once EXIF is in, name
  order until then. Virtualized rows (the 20,000-file folder of the mock scrolls). Filters: all /
  picked / rejected / not decided, and "N stars or more"; progress "n of N decided" with a bar.
- **Viewer** (full screen, dark in both themes): swipe left/right to page, **swipe up = pick, swipe
  down = reject** at fit, buttons for reject / 1–5 stars / pick, **auto-advance** (a setting, on by
  default), **undo** (200 steps, jumps back to the shot it changed), a filmstrip, the **EXIF
  overlay** (time with milliseconds, shutter, aperture, ISO, focal length with 35 mm equivalent,
  lens, camera, size, burst position, and which image is shown: "Embedded preview", "Camera JPEG",
  "100% from the RAW (LibRaw)"). **Pinch, double tap or wheel to 100%** (one image pixel per device
  pixel), pan, tiles center-first; the 100% source is prepared as soon as a shot is on screen.
  Keyboard: ←/→, P, X, U, 0–5, I, C, Esc, Ctrl/Cmd+Z.
- **Compare**: two shots side by side (stacked on a portrait phone) with **synchronized zoom and
  pan** (a shared view: zoom in device px per image px and the image point at the center), pick /
  reject per pane, **Keep** (pick this one, reject the other: one undo step), focus scores with a
  crown on the sharper frame, the filmstrip and arrows change the second shot.
- **Decisions** live in SQLite (migration v5, ADR 0003): keyed by location id + share + folder +
  shot id, so they survive restarts, re-entering the folder and a USB drive being unplugged and
  plugged back (volume UUIDs are stable). `cull_sessions` keeps started / last visited / last shot
  ("Continue" opens at the next undecided shot after it). Nothing is written next to the photos.

## Finish

- **Copy picks to…**: a dialog with the destination (the transfer engine's `DestinationPicker`:
  recent destinations, phone storage, USB drives and SD cards, favorites, machines and their
  shares, folders Android grants, "new folder", and what to do when a file exists), which files of
  a pick (RAW and JPEG / RAW only / JPEG only; a pick without the chosen kind is copied with what it
  has, and the dialog says how many), and the folder (a folder named like the source, a new one, or
  straight in; a folder Android grants takes the files straight in). The **last destination,
  options and conflict choice are remembered per source location** (`cull_destinations`), and the
  picker opens there. Planning is pure (`planCopy`, `copyTargetPath`). The copy is **one batch job
  of the transfer engine** through `useCopyPicks()` (`copyPicks.ts`, over `useStartTransfer`): its
  progress, resume, "View" toast, recent destinations and the conflict question ("Keep both /
  Replace / Skip, do the same for the others") are the engine's; culling has no copy code. The
  engine creates the folders it copies, not the folder it copies into, so the adapter creates the
  target subfolder first (`createFolder`, `ALREADY_EXISTS` is fine).
- **Delete rejects**: a confirmation with the shot and file counts, the size and where ("on SanDisk
  USB drive", "on cachyos-fps", "on this phone"), that a pair deletes both files, and that it is
  **permanent** (SMB shares and USB drives have no trash). Files are deleted one by one; the first
  refusal stops it and says so ("The location refused to delete (read-only)"), shots whose files
  are all gone leave the list and the database. On a folder the location reports read-only the
  action is disabled.

## Measurements

Emulator `hoard-culling` (Android 16 / API 36.1, arm64 on the Apple Silicon Mac mini, 3 GB,
1080×2400 at 420 dpi: screen long side 2400 device px, so a ZV-E10 pair is "preview first, then
the camera JPEG"). Real ZV-E10 files (copies, never committed): 30 RAW+JPEG pairs (two shoots),
4 RAW-only, 4 JPEG-only, 200 JPEG-only (copies of the 30 JPEGs under new names). Local = phone
storage by path. SMB = the disposable Samba container (`docs/testing/samba/`) reached by the
emulator at `10.0.2.2`; **that path is slow, about 2–3 MB/s** (emulator NAT plus Docker Desktop's
port forwarding), so SMB rows that stage whole files are link-bound and a phone on Wi-Fi to a NAS
(30–80 MB/s) will be much faster. Caches cleared and the app restarted before each cold run;
timings from the UI (`hoardCull.summary()` over CDP) and native logs (`HoardCull`, `HoardImage`).

| What                                                                          | Local                                                                                     | SMB (slow emulator link)                                                                                         |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Open the 30-shot folder natively (list + pair)                                | —                                                                                         | 41 ms                                                                                                            |
| Enter the folder → first grid thumbnail                                       | 203 ms                                                                                    | 321 ms                                                                                                           |
| Tap a shot → first image in the viewer                                        | 27 ms                                                                                     | 15 ms                                                                                                            |
| First pair's final image (camera JPEG → 2000×3000 file)                       | 146–155 ms native                                                                         | 2.3 s (6 MB staged)                                                                                              |
| Paging at 1.2 s per shot, 15 pages: page → final image on screen              | median 2 ms, max 4 ms                                                                     | median 2 ms, max 5 ms (first page of a cold run: 410 ms)                                                         |
| Paging at 0.6 s per shot, SMB                                                 | —                                                                                         | 10 of 15 pages had an image in time (median 82 ms, max 559 ms after landing); finals never caught up: link-bound |
| Paging at 0.7 s per shot through 200 JPEG shots                               | page → final median 1 ms, max 30 ms                                                       | —                                                                                                                |
| 100% on a pair, double tap after the shot settled                             | first tile 85 ms                                                                          | —                                                                                                                |
| 100% on a pair, double tap right away                                         | zoom ready 175 ms + first tile 168 ms                                                     | zoom ready 1.7 s + first tile 194 ms                                                                             |
| 100% on a RAW-only shot, right away (cold LibRaw, after the half-size render) | 2.3 s + tile 282 ms                                                                       | 13 s (25 MB staged) + tile 76 ms                                                                                 |
| 100% on a RAW-only shot after ~4 s on it                                      | tile 27 ms                                                                                | —                                                                                                                |
| Memory paging through 200 shots (sampled every 10 s)                          | app PSS 134–179 MB, native heap 22–49 MB, WebView renderer PSS 97–168 MB; no upward trend | —                                                                                                                |

What the measurements changed: the first SMB runs at 0.6 s per shot showed even the current shot's
preview waiting 1.3 s behind prefetch staging; the urgent lane, `background` neighbour requests
(which also stopped neighbours from competing as urgent work) and the wider fast-image reach
brought paging at 1.2 s per shot to "the final image is already there" on every page. On the
emulator's 2–3 MB/s link nothing can stage 6 MB per 0.6 s; on a real network that pace is ~10×
under the link.

## Testing

- TS (Vitest): `src/features/cull/model.test.ts` (pairing edge cases, capture order, burst hints,
  decisions + undo, filters, progress, copy planning per kind with fallbacks, target folder,
  delete planning, prefetch slice, sample sizes, tile grid); `src/db/db.test.ts` (migration v5,
  sessions, decisions, unmarking deletes rows, destinations, machine deletion);
  `src/plugins/smb/web.test.ts` (the mock's cull methods, SMB session drop and reconnect).
- Kotlin (JVM): `CullLogicTest` (native pairing, capture time, upright size, prefetch plan,
  preview-as-is, tile clipping) and `SchedulerUrgentLaneTest`.
- Emulator, end to end over CDP (`docs/testing/tools/cdp.mjs`, touch events for double taps):
  entering local and SMB folders, paging with prefetch, 100% on pairs and RAW-only shots, compare,
  decisions surviving an app restart, delete rejects on a local folder (both files of each pair
  deleted) and on an SMB folder whose files the server cannot delete (refused, files intact).
- Copy picks end to end on the emulator (5 picks from a phone folder: 3 pairs, 1 RAW-only, 1
  JPEG-only): RAW and JPEG into `Pictures/<folder>` on the phone (8 files), RAW only into the
  virtual SD card (4 ARW + the JPEG-only pick as fallback), JPEG only into a new folder on a Samba
  share (4 JPG + the RAW-only pick as fallback), and the same copy again with "Ask me": the engine's
  conflict question came up, "Skip, do the same for the other 7" left the 8 files untouched. The
  last destination reopened in the picker each time.
- Found and fixed while testing: overlapping decision saves could collide (the SQLite adapters
  treat concurrent transactions as nested); culling writes are now serialized.

## Not verified

- On the owner's phone (S24 Ultra, 3120 px screen) and over Wi-Fi to the NAS or the Mac: all SMB
  numbers above come from the slow emulator link.
- USB drives and SD cards on real hardware; SAF-only volumes (`DescriptorSource` has no test and
  was not exercised: the emulator runs were on phone storage by path).
- Jank was judged by eye on the emulator and by the timings above; no frame-time trace on a phone.

## Consequences

- Culling works on every location the app can browse, with the same UI in the browser build.
- Disk cache budget grows by up to 512 MB (`screens/` + `tiles/`), trimmed LRU like the others.
- Decisions are per folder: moving a shoot to another folder starts its culling over (no sidecars
  by design).
