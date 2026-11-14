# ADR 0014: hoard-server items (photos, RAW+JPEG pairs, videos, motion photos)

- **Date**: 2026-11-14
- **Status**: Accepted (hoard-server 0.5.0, Hoard 2 phase 1b)
- **Amends**: ADR 0013 (protocol additions under `/v1`, written there in "Items (hoard-server 0.5.0)")

## Context

hoard-server (ADR 0012) knows what Reveal needed: photo records written by devices, uploaded originals, and the photos
(RAW, JPEG, HEIF) of the NAS folders it indexes, each found by its `v1:` fingerprint. Hoard 2 wants the server to be
the one index of every photo **and video** the owner has — the NAS folders plus what devices upload — shared by Hoard,
Reveal and a future web gallery. A gallery does not browse files: it browses **shots**. A RAW+JPEG pair is one picture,
a Live Photo is a still and a clip, a Pixel "MP" JPEG has its video inside, and an XMP sidecar is not a picture at all.

The addition must not change anything Reveal uses (`/v1` is frozen, ADR 0012), must upgrade existing data directories
in place, and must stay cheap on a large library (tens of thousands of files, re-walked every 30 minutes).

## Decision

### Items

An **item** is one shot. Its **kind** is:

| Kind | What | Main file |
| --- | --- | --- |
| `photo` | one still, or several renderings of it (a JPEG and a HEIF of the same stem) | the rendering (JPEG before HEIF) |
| `pair` | a RAW and its camera rendering | the RAW |
| `video` | a video | the video |
| `motion` | a still with a video: a same-stem video next to it (Apple Live Photo: `IMG_1234.HEIC` + `IMG_1234.MOV`), or a video inside it (Google/Samsung motion photo) | the RAW if any, else the rendering |

Each member file has a **role**: `raw`, `rendered`, `video` or `sidecar`. Per item the server keeps: id, kind, the main
file's fingerprint, the member files (fingerprint, role, name, content format, size, and every **location**: a NAS path
in a folder with its mtime, or an uploaded blob; device paths are reported by clients in a later phase), and the
shot's metadata: capture time, camera make and model, lens, GPS, dimensions, EXIF orientation, and for videos (and the
video of a motion photo) duration, codec and profile, frame rate, bit depth, HDR transfer (`pq`, `hlg`,
`dolby-vision`), colour primaries, rotation and the audio codec.

**Item id.** A UUID derived from the main file's fingerprint: the first 16 bytes of SHA-256(`"hoard-item\0"` + main
fingerprint) with the version nibble set to 8 and the RFC 9562 variant. The same shot therefore has the same id on
every walk, after a move, after a folder is re-added and on every server. Consequences, accepted:

- A file **rewritten in place** (a JPEG edited by another program) has a new fingerprint, so it is a new item; the old
  item becomes a tombstone. This is the same identity rule as Reveal's photo records (the fingerprint is the photo).
- When a shot's main file **changes role** — the RAW of a JPEG-only shot appears later — the shot becomes a new item
  (named by the RAW) and the JPEG item becomes a tombstone with `replacedBy` set to the new item, so a client can move
  what it attached to the old id (ratings, albums). The reverse (the RAW disappears) works the same way.
- **Copies** of a shot (the same bytes in two folders, or on the NAS and uploaded) are **one item** with several
  locations per file.

### Pairing rule

Files are grouped by **folder + directory + case-insensitive stem** (`DSC07668.ARW` + `dsc07668.jpg`), exactly as
Hoard's `ShotPairing` (`android/.../image/format/ShotPairing.kt`): RAWs first (by name), then renderings (JPEG before
HEIF, then name), then videos, then sidecars. The main file is the first readable RAW, else rendering, else video.
Supported names (by extension, the same lists as the app): RAW `arw srf sr2 cr2 cr3 nef nrw raf dng orf rw2 pef srw`;
renderings `jpg jpeg jpe jfif heic heif hif`; videos `mp4 m4v mov 3gp 3g2 mts m2ts mkv webm avi`; sidecars `xmp`.

- **Hidden files are never items**: any name starting with `.` (macOS `._X` resource forks, `.DS_Store`), and the NAS
  housekeeping folders `@eaDir` (Synology thumbnails), `#recycle`, `@recycle`, `#snapshot`, `$RECYCLE.BIN`.
- **XMP sidecars attach** to the shot of their stem as role `sidecar`, both conventions: `DSC1.xmp` and `DSC1.ARW.xmp`.
  A sidecar alone is not an item.
- A file that cannot be read yet (no fingerprint) is left out until the next walk reads it.

### Motion photos

A still is a motion photo when a video is found **inside** it, checked in this order, each candidate accepted only if
an MP4 `ftyp` box starts at the computed offset:

1. **Google Motion Photo 1.0** (Pixel, Samsung since One UI 1, other Android cameras; JPEG or HEIF): XMP
   `GCamera:MotionPhoto="1"` with a `Container:Directory` whose items follow the primary image in order and end at the
   end of the file; the `MotionPhoto` item's offset is the file size minus the `Item:Length` (+ `Item:Padding`) of it
   and every item after it. `GCamera:MotionPhotoPresentationTimestampUs` is kept (`presentationUs`).
2. **Google MicroVideo** (older Pixel `MVIMG_*.jpg`): `GCamera:MicroVideo="1"`, the video starts
   `GCamera:MicroVideoOffset` bytes before the end.
3. **Samsung SEF trailer** (Galaxy motion photos, with or without the XMP above): the file ends with
   `[u32 directory length]"SEFT"`; the `SEFH` directory's entries (12 bytes, little-endian: u16 —, u16 type, u32
   offset back from `SEFH`, u32 length) point at blocks `u16 —, u16 type, u32 name length, name, payload`; the video is
   the payload of the block named `MotionPhoto_Data`.

The XMP is read from the JPEG APP1 packet or the HEIF `mime` item (`application/rdf+xml`). The item stores
`motion: {source: "embedded", fingerprint (the still's), format: google | microvideo | samsung, offset, length,
presentationUs}`, and the embedded video is probed in place (ffprobe `subfile` protocol) for its metadata. A still
with a same-stem video next to it is `motion: {source: "file", fingerprint (the video's)}`. Apple pairs Live Photos by
a content identifier as well; the stem rule already matches every Live Photo exported or synced by Apple's tools, so
the identifier is not read.

### Fingerprints of videos and sidecars

Every file uses the **existing `v1:` fingerprint** (Reveal ADR 0006 "Identity", ADR 0013): SHA-256 over the size as
8 bytes big-endian, then the first MiB and the last MiB (a file of at most 2 MiB whole). It is not redefined for
videos:

- Both apps already compute it, `POST /v1/fingerprints/lookup` and `GET /v1/originals/{fingerprint}` already take it,
  and the shared vectors (`server/testdata/fingerprint-vectors.txt`) already pin it. A second rule would be a second
  identity for the same file.
- It is cheap on a phone (2 MiB read per file, whatever the size of the video).
- For a camera or phone video, the first MiB holds the container header and the first coded frames, the last MiB the
  `moov` index (or the last frames); with the size, two different recordings colliding would need identical size,
  identical start and identical end. A middle chunk would add a third read for a case that does not happen with
  recordings; it would only catch an edit that keeps size, head and tail, which no editor or transcoder does.

Rejected: size + head/middle/tail chunks (`v2:`); a full-file hash (reads gigabytes per video, on phones too).

### Capture time

Stored as `capture: {local, offset, utc, source}` plus a single `sortTime` (Unix ms) for ordering and filters.

- **Photos (EXIF)**: `local` = DateTimeOriginal + SubSecTimeOriginal (milliseconds), `offset` = OffsetTimeOriginal;
  falling back to DateTimeDigitized (+ SubSecTimeDigitized, OffsetTimeDigitized), then IFD0 DateTime (+ SubSecTime,
  OffsetTime); the general OffsetTime stands in when the specific one is missing (cameras write all three alike).
  With an offset, `utc` = local − offset, `source: "exif"`. Without one, when the GPS block has GPSDateStamp and
  GPSTimeStamp (UTC), the offset is local − GPS time rounded to a quarter hour, accepted only within ±2 minutes of
  that quarter (fix delay) and at most 14 hours: `source: "exif-gps"`. Otherwise `utc` is absent: the time is a wall
  clock in an unknown zone. Zero dates (`0000:00:00`) and years before 1900 are ignored.
- **Videos (QuickTime/MP4 tags through ffprobe)**: `com.apple.quicktime.creationdate` (iPhone; local time with its
  offset, e.g. `2024-05-01T10:00:00-0300`) gives `local`, `offset` and `utc`. Else `creation_time` (the movie header,
  UTC by the QuickTime spec; Android and cameras) gives `utc` only; Samsung's `com.samsung.android.utc_offset`, when
  present, adds `offset` and `local`. `source: "quicktime"`. ffprobe joins a key written twice (`mvhd` and a metadata
  atom) with `;`; the first value is used. Known limit: some old Android phones wrote local time into `creation_time`;
  it is read as UTC.
- **Items** take the main file's capture time, else the first other member's (the video of a motion photo, a RAW's
  rendering), else the earliest mtime of the main file's NAS copies (`source: "mtime"`) or its upload time
  (`"upload"`).
- **`sortTime`** = `utc` when known, else `local` read as if it were UTC (a "floating" time: a shot at 10:00 sorts at
  10:00 whatever the zone), else 0.

### Other metadata

- Stills: Make, Model, LensMake, LensModel, Orientation (1–8, as stored; HEIF `irot`/`imir` are not read), GPS
  (decimal degrees, altitude in metres, `0,0` ignored). **Dimensions** are the largest image a file describes: IFD0,
  SubIFDs (DNG, NEF, ARW), EXIF PixelX/YDimension, the JPEG frame header, the largest HEIF `ispe`; Panasonic RW2 from
  its sensor borders, Fujifilm RAF from the RAW header's cropped size (tag 0x0111, else full size 0x0100). Formats read:
  JPEG, HEIF/HEIC/AVIF, TIFF-based RAWs (ARW, NEF, DNG, CR2, ORF, RW2, PEF, SRW, …), CR3 (`moov/uuid` CMT1/CMT2/CMT4),
  RAF. Reads are bounded (directory entries, value sizes, segment and box counts); malformed files give partial or no
  metadata, never a failure of the walk (fuzzed with every truncation of the synthetic files).
- Checked on real cameras (CC0 samples from raw.pixls.us, opt-in `HOARD_SAMPLES_DIR` test, files never committed):
  Canon EOS R6 (CR3), Sony ILCE-7M3 (ARW), Nikon Z 6 (NEF), Fujifilm X-T3 (RAF), Panasonic DC-G9 (RW2), Olympus
  E-M1 II (ORF), Apple iPhone 12 Pro (DNG, with GPS).
- **Video metadata** comes from `ffprobe` (in the Docker image: a small static build, see "Consequences"). Runs are **bounded** (a pool,
  `--probe-workers`, default 2) and **time-limited** (`--probe-timeout`, default 30 s). The file is passed as an open
  descriptor (`/proc/self/fd/3`), never by name, with `-protocol_whitelist file,subfile` and a `-format_whitelist` of
  container formats (`mov,mp4,m4a,3gp,3g2,mj2,mpegts,matroska,webm,avi`), so a hostile file (a playlist named `.mp4`)
  cannot make ffprobe open other files or the network. Rotation comes from the display matrix (clockwise degrees to
  apply), bit depth from `bits_per_raw_sample` or the pixel format, HDR from the transfer characteristics or a Dolby
  Vision configuration record. Without ffprobe, videos are indexed (fingerprint, location, item) and their metadata is
  read once it is installed; a file ffprobe fails on or times out on is recorded as such (`error`) and not retried
  until the rules change.
- Metadata is stored **per fingerprint** (`file_meta`), with the rules' version (`media.MetaVersion`): copies are read
  once, a move reads nothing again, and raising the version re-reads metadata without re-hashing anything.

### Indexer behaviour

Per folder walk (on start, on request, every `--nas-interval`):

1. Walk (no symlinks, no hidden names), keeping photos, videos and sidecars.
2. Compare size + mtime with `nas_files`: unchanged files are **not hashed again**; new or changed files get a
   fingerprint (2 MiB read) and, when no copy has it yet, their metadata (header reads; ffprobe for videos and motion
   photos). Unchanged files whose fingerprint has no metadata of the current version (a data directory from 0.4.0, new
   rules) get metadata only. Missing files become `removed`.
3. Group the folder's current files into shots and compare each group with the stored **source** (`item_sources`: one
   row per folder + directory + stem, with a signature of its members). Unchanged groups cost nothing. Changed, new and
   vanished groups are written with every item they touch, rebuilt from all its sources, in **one transaction per 500
   groups**, so an item never lags behind its sources after a crash.
4. After all folders: folders no longer indexed lose their sources (their items become tombstones unless another
   location remains), then **uploaded originals** are synced (below).

Incremental cases: **new** file → new or changed item; **changed** (new fingerprint) → see "Item id"; **moved** (same
fingerprint, new path, in the same walk or across folders) → same item id, new location, new revision (the moved file
is hashed at its new path, its metadata is not read again); **deleted** → the location goes, the item becomes a
**tombstone** when no location is left. Tombstones are kept forever.

### Uploads

Uploaded originals are items too: an original whose fingerprint is a member of a live NAS item joins it as a `backup`
location (the phone's upload of a JPEG the NAS also holds as part of a pair); otherwise it is an item of its own, its
role taken from its content (TIFF-based RAW, CR3, RAF → `raw`; JPEG/HEIF → `rendered`; ISO-BMFF/Matroska/AVI →
`video`), its metadata read from the blob. Completing an original upload, registering a fingerprint on an existing blob
and deleting an original start a pass over uploads only (no folder walk). Sidecars, device paths and pairings reported
by devices come in a later phase (Android apps).

### Feed and API (ADR 0013 "Items")

- `GET /v1/items/changes?cursor=&limit=`: the **items feed**, like `/v1/changes` (each changed item once, at its
  latest state, oldest revision first, tombstones forever) with its **own revision counter and cursors**
  (`<server>:i<rev>`).
- `GET /v1/items?kind=&from=&to=&folder=&path=&order=&cursor=&limit=`: a **query** of live items in `sortTime` order
  (newest first by default), keyset-paged, filtered by kinds, a time range and a NAS folder (and a directory in it,
  sub-directories included). Not a change feed.
- `GET /v1/items/{itemId}`: one item, tombstones included (`replacedBy`).
- `POST /v1/items/lookup {"fingerprints": [...]}` (≤ 1000): the live items with any of them as a member file, each
  once, plus `matches` (fingerprint → item, role) and `missing`.
- `GET /v1/server` gains `features: ["items"]`; `GET /v1/health` counts gain `items`.

**Why a separate feed and not items as a record kind in `/v1/changes`:** records are written by devices and resolved
by the conflict rule; items are written by the server only and are rebuilt wholesale. Putting them in Reveal's feed
would make every Reveal device download tens of thousands of server rows it ignores on its next pull (and on every
re-index), would let a stale device push to a kind it must not write, and would tie items' volume to Reveal's
pagination. A filtered change feed was also rejected: an item that leaves a filter (moved to another folder) would
never be delivered to a client syncing that filter. So: one unfiltered feed for clients that keep a local copy (Hoard),
one query for clients that browse (the web gallery).

### What Reveal sees: nothing new

- `GET /v1/nas/files` lists **photos only**, as before: videos and sidecars are indexed in `nas_files` with
  `media = 'video' | 'sidecar'` and skipped by the feed (its cursors and entries are byte-identical for an upgraded
  data directory, `TestUpgradesHoardServer040DataDir`). `counts.nasFiles` counts photos only, as before.
- Additions Reveal may notice and ignores safely: `POST /v1/fingerprints/lookup` and `GET /v1/originals` also find
  indexed **videos** (and sidecars) of the NAS — a fingerprint is found when an indexed NAS file has it, as ADR 0013
  says; Reveal only looks up its photos. `/v1/server` and `/v1/health` have new fields (clients ignore unknown fields).
- `TestRevealProtocolContract` replays Reveal's `SyncApi` (Reveal `bcfb71b`, `core/sync/.../api/SyncApi.kt`) against a
  server that indexes items: the same requests, and every field Reveal's data classes require, with its JSON type.

### Storage

Migration `0003_items.sql` (schema 3; forward only, applied in place on start): `nas_files.media`, counter `items`,
tables `file_meta`, `items` (tombstones keep their row), `item_sources`, `item_files` (lookup index), with indexes for
the feed (rev), the query (sort time, kind) and the lookup. Upgrading from 0.4.0 needs no step; the first walk reads
metadata and makes items. **Rolling back** to 0.4.0 after the upgrade is refused by 0.4.0 ("database schema 3 is newer
than this server"): back up `data/` before upgrading (README).

## Measurements

Synthetic library on cachyos-fps (16 cores, files on tmpfs, `TestIndexLargeTree`, `HOARD_BENCH_FILES=20000`): real
headers (EXIF stills, ffmpeg H.264 clips) extended with holes to camera sizes — RAWs 20–60 MiB, JPEGs 3–14 MiB, HEICs
2–5 MiB, videos 30–400 MiB, Live Photo MOVs 2–4 MiB — in one folder per day; 40 % RAW+JPEG pairs (a tenth with a
sidecar), 32 % single stills, 10 % videos, 8 % Live Photos, 5 % Google motion photos, 5 % stills with macOS `._`
litter. 2 hash workers, 2 ffprobe workers.

20,000 files written (551.5 GiB apparent size, almost all holes), of which 609 are `._` files the walk skips:
19,391 indexed files, 12,735 shots (5,090 pairs, 4,675 photos, 1,289 videos, 1,681 motion photos).

| Walk | Time | Hashed | Metadata read | ffprobe runs | Item revisions | Peak RSS (server) | DB (with WAL) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| First (empty database) | 84.1 s | 19,391 | 19,391 | 2,970 | 12,735 | 48 MiB | 54.0 MiB |
| Unchanged | 0.25 s | 0 | 0 | 0 | 0 | 50 MiB | 54.0 MiB |
| 3,714 files moved (a month), 290 deleted, 100 added | 4.0 s | 3,714 | 100 | 0 | 2,732 | 54 MiB | 56.9 MiB |

Go heap in use stays between 7 and 24 MiB; one ffprobe process peaks at 81 MiB RSS (at most `--probe-workers` run at
once). The first walk is dominated by the 2,970 ffprobe runs (each a process start plus a few KiB read) and the
2 MiB fingerprint reads. A moved shot costs one fingerprint read per file and one item revision (its removed and its
new source are applied in the same transaction); its metadata is not read again. About 4.3 KiB of database per item
(item JSON, sources, per-fingerprint metadata, lookup index).

Reading on a real NAS is bound by the share: each new file costs 2 MiB for the fingerprint plus a few KiB of headers,
and each video one ffprobe run (tens of milliseconds locally; mostly the `moov` read over the network). The second walk
only stats files.

## Consequences

- Hoard (phase 2) syncs `GET /v1/items/changes` into its database and shows the owner's whole library; the web gallery
  pages through `GET /v1/items`. Both look files up by the fingerprints they already compute.
- The Docker image carries a small static ffprobe built in the `Dockerfile` from the FFmpeg 9.0.2 release (tarball
  pinned by SHA-256, signature checked; only the mov/matroska/mpegts/avi demuxers and the parsers and decoders of
  phone and camera codecs; no network, no filters; LGPL configuration): 7 MiB, the image goes from 25 to 35 MiB. A
  full static build (144 MiB) was tried first and gives the same fields on the test clips.
- Device-side locations (which phone has which file) and device-reported pairings come with the Android apps; the
  `locations` list is ready for a `device` source.
- Synthetic fixtures follow the published formats (Google Motion Photo 1.0, Samsung SEF as read by ExifTool); real
  Samsung/Pixel motion photos and iPhone Live Photos are to be checked on copies when the Android apps index devices.
