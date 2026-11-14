# ADR 0015: hoard-server gallery previews (thumbnails, screen previews, video posters and proxies)

- **Date**: 2026-11-14
- **Status**: Accepted (hoard-server 0.6.0, Hoard 2 phase 1e)
- **Amends**: ADR 0013 (protocol additions under `/v1`, written there in "Previews (hoard-server 0.6.0)")
- **Amended by**: ADR 0020 (hoard-server 0.9.0: an image job `edit:<versionId>` per Reveal edited preview, in the
  image lane, and `edit=` on `/preview` and the warm request)

## Context

The Hoard Gallery (Android) and the web gallery browse **items** (ADR 0014). A grid needs a small image of every item
at once, full-screen viewing needs a large one, and a phone away from home cannot stream a 4K HEVC 10-bit original
or download a 60 MB RAW to look at a shot. The server already has every file (NAS folders, uploads); it should make
the previews once and serve them to every client.

Reveal's **smart previews** (`/v1/blobs`, uploaded by Reveal, ADR 0013 "Blobs") are a different thing — an editing
proxy Reveal makes on the device — and stay exactly as they are.

## Decision

### What is made

| Preview | For | Made from | Format |
| --- | --- | --- | --- |
| **thumb** | grids | the item's best source (below) | JPEG quality 80 and WebP quality 70, long edge ≤ 512 px |
| **screen** | full-screen viewing | the same | progressive JPEG quality 82 and WebP quality 85, long edge ≤ 2560 px |
| **poster** | a video's thumb and screen | a frame of the video | the same four files |
| **proxy** | playing and scrubbing a video on a phone | the video (a motion photo's video) | MP4, H.264 High, short edge ≤ 720 px, ≤ 30 fps, keyframe every 2 s, CRF 23 capped at 2.5 Mb/s, AAC stereo 128 kb/s, `faststart` |

Every image is **upright** (EXIF orientation, HEIF `irot`/`imir`, a video's display matrix applied), **sRGB** (an
embedded ICC profile — Display P3, Adobe RGB, anything — converted with lcms2, perceptual intent; a file without one is
taken as sRGB) and **never upscaled**. Metadata is stripped (no GPS in previews). Videos with HDR (PQ, HLG, Dolby Vision's
base layer) are tone-mapped to SDR BT.709 (zimg, Hable) for posters and proxies.

### Formats

JPEG is the default and always there (`format=jpeg`): it decodes everywhere (every Android version, every browser,
`BitmapFactory`) at the lowest CPU cost. **WebP is made too** (`format=webp`), because it measured smaller at the same
look: on 30 bench photos, mean bytes at equal SSIM against a lossless reference (libvips, `effort=4`):

| Size | JPEG | WebP | AVIF (aom) |
| --- | --- | --- | --- |
| thumb (512) | q80: 39.2 KB, SSIM 0.934 | q70: 26.6 KB, 0.934 (**−32 %**) | ≈ q52: ≈ 22 KB, 0.934 (−43 %) |
| screen (2560) | q82: 674 KB, SSIM 0.966 | q85: 542 KB, 0.965 (**−20 %**) | ≈ q68: ≈ 400 KB, 0.966 (−40 %) |

Both WebP files come from the same uncompressed intermediate as the JPEGs (one decode of the source, no generation
loss) for about 20 % more work. **AVIF is not made**: smaller still, but its encoder costs several times the
WebP's per image (the 2560 px AVIF rows of the comparison took longer than all the other rows together), would add an
AV1 encoder (aom or SVT-AV1) to the image, and decodes natively only on Android 12+. A GPU worker or a later recipe
can add it as another file of the image job (a new `format` value) without any other change.

### Sources

Per item, one **image job** (thumb + screen together: one decode) and, for videos and motion photos, one **proxy job**:

| Item kind | Image job reads | Proxy job reads |
| --- | --- | --- |
| `photo` | its rendering (JPEG before HEIF/AVIF) | — |
| `pair` | the **camera's JPEG/HEIF** (the look the photographer saw) | — |
| `photo` of a lone RAW | the RAW's **largest embedded JPEG**, if its long edge is at least 1536 px (or the RAW's own size); else a **LibRaw decode**; if that fails, the largest embedded JPEG anyway | — |
| `motion` | the still (as above) | the video next to it, or the MP4 inside the still (`subfile` of its offset and length) |
| `video` | a **poster frame**: the first frame that is not dark among 10 %, 25 %, 50 % of the duration and the first frame (mean luma ≥ 24/255; else the brightest) | the video |

Embedded previews are found by content, not by maker tables: the TIFF IFD chain and SubIFDs (`JPEGInterchangeFormat`,
single-strip JPEG images), Panasonic `JpgFromRaw`, the Olympus MakerNote (ORF), CR3's first track (the full-size
JPEG) and `PRVW`, RAF's header JPEG — baseline or progressive JPEGs only (a DNG's lossless-JPEG raw data is not a
preview). An embedded JPEG has no orientation of its own: the RAW's orientation is written into a minimal EXIF in front
of it. On the CC0 samples (Canon R6 CR3, Sony A7 III ARW, Nikon Z 6 NEF, Fujifilm X-T3 RAF, Panasonic G9 RW2,
Olympus E-M1 II ORF, iPhone 12 Pro DNG) every RAW has one: full size for Canon, Nikon and Apple, 4416 px Fujifilm,
3200 px Olympus, 1920 px Panasonic, 1616 px Sony. **The camera's rendering is preferred to a LibRaw decode** even at
1616 px: it has the camera's tone curve, picture style and lens corrections, while LibRaw's neutral decode looks flat,
and LibRaw 0.21 decodes Apple ProRAW DNGs wrongly. The threshold (1536 px) keeps a RAW with only a 160 px thumbnail
from becoming a blurry screen preview.

### Keys, versions and caching

A job's **content key** is SHA-256 (16 bytes, hex) of `PREVIEW_VERSION` (`previews.Version`, 1), the job's recipe
(sizes, qualities, encoder settings), the source file's fingerprint and role, the embedded video's offset and length,
and a RAW's orientation. The same bytes read the same way give the same key on every server; a recipe change, a new
`PREVIEW_VERSION` or a new best source (the camera JPEG of a lone RAW appears) give a new key.

- Changing the recipe **regenerates lazily**: the previews of the old key keep being served (`X-Hoard-Preview:
  stale`) while the new ones are queued (backlog priority, or "waiting" when a client asks), then the old files go.
- Files live under `--previews-dir` (default `<data dir>/previews`): `<key[0:2]>/<key>.thumb.jpg`, `.thumb.webp`,
  `.screen.jpg`, `.screen.webp`, `.proxy.mp4`, written in `tmp/` and renamed in place. **Originals are never written**: sources are opened read-only
  (a NAS file only if it is still as indexed, else the job waits), and handed to the tools as an open descriptor.
- HTTP: `ETag: "<key>-<file>"` (e.g. `"…-thumb.webp"`), `X-Hoard-Preview-Version: <key>`. A URL with `?v=<key>` (the current key) is
  `Cache-Control: private, max-age=31536000, immutable`; without it, `private, no-cache` (the client revalidates and
  gets 304). The warm endpoint returns `v` for every ready preview, so a gallery builds immutable URLs and never asks
  twice; an item whose preview changes gets a new `v`.

### Full resolution (hoard-server 0.8.1)

The web gallery's 100 % zoom (ADR 0018 "Full resolution") adds a third job, **`full`**: a full-size JPEG (`full.jpg`,
q90, sRGB, upright) of an item whose still a browser cannot show as it is — a HEIF rendering, or a lone RAW (its
embedded JPEG as it is when that is at least 90 % of the RAW's size, else a LibRaw decode). JPEG items have none:
their file is shown. It uses the same queue, keys and states, in its own lane (one worker), is made only when asked
(never in the backlog) and its files are bounded by `--full-cache-mb` (least recently served dropped first). No other
recipe changed: no preview is made again on upgrade.

### Queue

One SQLite row per item and job (`previews`, migration 0004) is both the queue entry and the record of the files:
`want_key` (what the item needs now), `key` (what is on disk), `state` (`pending`, `running`, `done`, `failed`),
`priority`, `order_key`, `attempts`, `not_before`, `error`, the outputs (sizes, bytes), the generator and the time it
took.

- **Priorities**: 0 a client is waiting (`GET` answered 202), 1 a client asked to warm it (`POST /v1/items/previews`),
  2 an item created in the last 7 days, 3 the backlog. Within 0 and 1 the newest request runs first (what the user
  looks at now; within one warm request, the list's order); within 2 and 3 the newest shot (`sortTime`) first, like
  the gallery.
- **Backlog**: a scanner follows the items feed (it polls the `items` counter every 15 s, so the indexer needs no
  hook) and queues every live item's jobs whose key changed; tombstones lose their rows and files.
  `--preview-backlog` chooses what is made ahead of requests (default `image,proxy`; `image` makes proxies only when
  asked; `none` makes everything on request).
- **Bounded**: `--preview-workers` image jobs (default 2) and `--proxy-workers` proxy jobs (default 1) at a time, in
  separate lanes so a 10-minute proxy never holds up thumbnails; each vips/ffmpeg run uses `--preview-threads`
  threads (default 2) at nice 10, with a time limit (2 min for an image; 5 min + 4 × the duration for a proxy).
- **Resumable**: a stop leaves rows `running`; the next start puts them back to `pending`. The temporary folder is
  emptied on start; files no row names are swept every 6 hours.
- **Failures**: a source that cannot be opened (NAS offline, file changed since indexed) is retried after 1 min, 5 min,
  30 min, then every 6 h, never marked failed (the next walk updates the item anyway). A tool error is retried twice,
  then `failed`; a file no decoder reads is `failed` at once with `unsupported`. A failure is for one key: a new
  recipe or source tries again. Tools missing: jobs wait (retried hourly) and the status page says so.

### Generators (and the GPU worker)

Generation sits behind `previews.Generator`:

```go
type Generator interface {
    Name() string
    Accepts(j Job) bool      // a GPU worker may take only videos
    Available() bool
    Generate(ctx context.Context, j Job, src *os.File, dir string) (Result, error)
}
```

A `Job` carries everything a generator needs and nothing about the store: the item id and kind, the job, the source
(fingerprint, role, format, embedded offset/length, orientation, the video's duration, frame rate, rotation and HDR)
and the key. The generator reads `src`, writes the job's files into `dir` and reports their sizes; the manager owns
the queue, the files and the database.

The **CPU generator** (today's only one) runs **libvips** (`vips thumbnail`: shrink-on-load, `--export-profile srgb`,
EXIF orientation; `dcrawload` for LibRaw) and **ffmpeg** (frame grabs to PPM, `libx264`, `zscale` + `tonemap`) as
separate processes with the source as descriptor 3 (`/proc/self/fd/3`, the `subfile` protocol for an embedded video),
a minimal environment, `VIPS_BLOCK_UNTRUSTED=1` for files from outside (only libvips' fuzzed loaders: JPEG, PNG,
WebP, HEIF/AVIF), ffmpeg's `-protocol_whitelist file,subfile` and the container `-format_whitelist` of ffprobe (ADR
0014).

**GPU worker (later).** The owner's RTX 4080 will run a separate CUDA worker (NVDEC/NVENC for proxies and posters,
possibly nvJPEG for stills). It takes the same jobs through the same contract, in one of two shapes, both open:

1. **In process**: a `Generator` whose `Generate` calls ffmpeg with `-hwaccel cuda` / `h264_nvenc` on a host with the
   NVIDIA runtime, chosen per job by `Accepts` (videos to the GPU, stills to libvips) — a `MultiGenerator` in front of
   both.
2. **Remote**: a worker on the GPU machine that leases jobs over an internal API (lease = the `LeasePreview`
   transaction: the most urgent pending row, marked `running`; finish = `FinishPreview` with the uploaded files;
   retry/fail likewise), reads sources through `GET /v1/originals/{fingerprint}` and uploads results. The queue's
   rows, keys and states need no change; a lease timeout puts a lost worker's job back to `pending`.

The key does not name the generator, so a GPU-made proxy and a CPU-made one are interchangeable (the `generator`
column records which made it). If GPU output must be told apart (a different encoder looks different), its recipe
string gets its own suffix and so its own key.

### Storage and the status page

The Storage tab of the setup page reports, per job, the previews ready (and how many are being refreshed), their size
on disk, the queue (pending, running), the failures (with their most common reasons), the previews directory and its
free space. Previews are rebuildable: deleting the folder (server stopped) and the `previews` rows makes them again.
They are not part of the backup folder.

### Docker image

The runtime image moves from distroless/static to **Alpine 3.22** (non-root, uid 65532 as before) with the tools in
`/opt/hoard`, built in the `Dockerfile` from pinned, checksummed sources: **libvips 8.18.7** (JPEG, PNG, WebP,
HEIF/AVIF, LibRaw, lcms2, libexif, highway; no other loaders), **libheif 1.23.7** (decoders only: libde265, dav1d) and
**FFmpeg 9.0.2** (the same demuxers/decoders as the ffprobe of ADR 0014, plus libdav1d, libx264, AAC, PPM, zscale,
tonemap; shared libraries; GPL because of x264), with Alpine's LibRaw 0.21.4, libjpeg-turbo, lcms2, x264, zimg, dav1d.
The previous image shipped a static ffprobe only; this one ships ffprobe from the same build.

## Measurements

On cachyos-fps (16 cores; files on local NVMe), the image built from this `Dockerfile` in a container limited to
**4 CPUs and 3 GiB**, `PREVIEW_WORKERS=2`, `PROXY_WORKERS=1`, `PREVIEW_THREADS=2`, everything in the backlog.

**Bench library** (synthetic or CC0; nothing personal): 1,050 items — 400 JPEGs (crops and colour variants of the CC0
raw.pixls.us cameras' own renderings, 1616–6048 px, q≈95), 300 RAW+JPEG pairs and 200 lone RAWs (copies of the seven
CC0 RAWs of "Sources", each with a unique trailer), 100 HEICs in Display P3 (4032 px, libheif/x265), and 50 ten-second
videos with AAC audio from ffmpeg test sources plus noise: 30 × 1080p30 H.264, 8 × 1080p60 HEVC, 5 × portrait
(display-rotated) 1080p H.264, 4 × 4K30 H.264, 2 × 720p with two black seconds first, 1 × **4K60 HEVC Main 10 HLG**.

Everything made, from an empty data directory, in **685 s** (indexing took 3.7 s of it): 1,050 image jobs and 50
proxies, no failure. Per job (wall time of the job, its two threads at nice 10, while the other lanes ran):

| Job | Source | Count | Mean | Max | thumb JPEG / WebP | screen JPEG / WebP |
| --- | --- | --- | --- | --- | --- | --- |
| image | camera JPEG (photos) | 400 | 1.64 s | 2.8 s | 40 / 27 kB | 536 / 462 kB |
| image | camera JPEG of a pair | 300 | 0.92 s | 1.8 s | 40 / 27 kB | 536 / 463 kB |
| image | lone RAW, embedded preview | 200 | 0.96 s | 1.8 s | 40 / 27 kB | 560 / 502 kB |
| image | HEIC, Display P3 | 100 | 1.08 s | 1.5 s | 40 / 27 kB | 659 / 547 kB |
| image | video poster (≤ 4 frame grabs) | 50 | 1.14 s | 3.3 s | 10 / 4 kB | 117 / 98 kB |
| proxy | 1080p/4K H.264, 1080p60 HEVC (10 s) | 49 | 5.7 s | 9.2 s (4K30) | 2.1 MB (≈ 1.7 Mb/s) | |
| proxy | **4K60 HEVC Main 10 HLG** (10 s) | 1 | 44.6 s | | 1.6 MB (720p30 SDR, 1.16 Mb/s video) | |

(The bench JPEGs are larger than a camera's, q≈95 at up to 6048 px: their decode dominates. A LibRaw decode, when a RAW
has no usable preview, took 0.9 s (CR3) to 3.8 s (RAF, 100 MP RW2) on the samples.)

- **CPU**: 3.1 of the 4 cores on average, the quota at peak. Throughput ≈ 1.5 image jobs/s; a 10 s 4K HEVC 10-bit
  clip is about 4.5 × slower than real time on two threads — the case for the GPU worker.
- **Memory (RSS peaks, 1 s samples)**: ffmpeg 739 MiB (the 4K HEVC 10-bit proxy; 1080p ones stay under 200 MiB),
  vips 71 MiB, ffprobe 18 MiB, hoard-server 146 MiB; all processes together at most 855 MiB. The cgroup's memory
  (page cache of the files read included) filled its 3 GiB; that is reclaimable cache, not a need.
- **Storage per 1,000 items**: **1.0 GiB** for the image previews (thumb 40 + 27 kB, screen ≈ 550 + 475 kB; 0.55 GiB
  of it is the JPEGs), plus **≈ 2.1 MB per 10 s of video** for proxies (≈ 0.75 GB per hour of footage). The bench's
  1,050 items: 1.06 GiB of image previews, 104 MiB of proxies.
- **Docker image**: 86 MB (35 MB for 0.5.0): `/opt/hoard` (libvips, libheif, FFmpeg's shared libraries and programs)
  17 MB, Alpine's runtime libraries 25 MB, the server 12 MB.

## Consequences

- Reveal is untouched: no `/v1` route, field, limit or meaning changed (`limits` stays reveal-sync's object; the
  preview limits are in a new `previews` object of `/v1/server`). Smart previews and blobs are unchanged.
- Hoard and the web gallery call `POST /v1/items/previews` for the screen they are about to show and load
  `…/preview?size=thumb&v=<v>` (immutable); a phone plays `…/proxy` with ranges.
- Licences: the image now contains FFmpeg built with x264 (GPL-2.0-or-later) and libvips/libheif (LGPL), run as
  separate programs next to the AGPL server; their sources are the pinned upstream tarballs (`Dockerfile`).
- Known limits: a camera JPEG in Adobe RGB **without** an embedded profile (EXIF `ColorSpace` = uncalibrated, the
  `_DSC` convention) is taken as sRGB; HEIF `irot`/`imir` are applied by libheif, but HEIF gain maps (Apple's HDR
  photos) are ignored (the SDR base image is shown); Dolby Vision profile 5 (no backward-compatible base layer) is
  tone-mapped from its base layer and looks wrong; LibRaw 0.21 cannot decode Apple ProRAW correctly (the embedded
  preview is used).
