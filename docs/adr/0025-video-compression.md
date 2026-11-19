# ADR 0025: Video compression on hoard-server

- **Date**: 2026-11-19
- **Status**: Accepted 2026-11-19 (Hoard phase 5; hoard-server 0.13.0, Hoard 2.7.0–2.8.0). The owner's answers are in
  "Owner's decisions (2026-11-19)"; the text below is written to match them.
- **Builds on**: ADR 0012 (compatibility promise), 0013 (protocol, errors, logs), 0014 (items, identity by the main
  file's fingerprint, `replacedBy`), 0015 (previews: the queue, `Generator`, "GPU worker"), 0016 (culling follows
  `replacedBy`), 0017 (physical devices), 0018 (web gallery), 0020 (edits move with `replacedBy`), 0021 (Gallery,
  `gallery.db`), 0022 (SMB ↔ NAS mappings), 0023 (trash: `.hoard-trash`, the trash root, the walk, restore, Empty and
  auto-empty, "the server never writes the NAS"), 0024 (phone backup: the mirror, blobs, ownership, verify, free up,
  `freed`, pinned entries).
- **Amends**: ADR 0013 (additions under `/v1`, "Video compression (hoard-server 0.13.0)"), ADR 0014 (an heir rule for
  compressed files; items gain `compressed`), ADR 0023 (the server may write the NAS through a write mount, for
  compression only; it moves replaced originals into the trash and swaps them back on restore; a trashed item may carry
  `replacedBy`; a server-kept trashed original), ADR 0024 (a removal reason `compressed`; a fifth ownership rule),
  ADR 0021 (`gallery.db` version 6).
- **Input**: the step 2 calibration (2026-11-18/19, 13 clips on the server host's RTX 4080, aggregates only) and the
  inventory of the owner's phone camera-drone folder (aggregates). Numbers quoted below come from them.

## Context

Videos are most of the library's bytes. The phone's drone and camera folder alone holds 730 videos, 51.9 GB: 129
camera originals (29.1 GB, HEVC 10-bit 2.7K and H.264/HEVC Pocket clips at about 0.41 bits per pixel), 550 outputs of
the owner's old NVENC script (18.5 GB, already compressed, no marker), 50 app re-encodes (4.4 GB) and one broken file.
The NAS holds more of the same plus 1080p60 camera clips at about 50 Mb/s. The server host has an NVIDIA GPU whose
NVENC encodes HEVC (and AV1) at several times realtime, and the owner already compresses by hand with a script whose
look he approves.

What the server has that this builds on:

- **Items** keyed by the main file's `v1:` fingerprint (ADR 0014). A file rewritten in place is a new item; the old one
  becomes a tombstone, with `replacedBy` only when another live item has its main file (`items.Syncer.rebuild`,
  `server/internal/items/sync.go`). `replacedBy` already carries edits (`store/edits.go`: `item_edits` move in the
  indexer's transaction) and culling (`store/culling.go`: records follow the tombstone's `replacedBy`), and Hoard
  follows it for queued culling changes (`GalleryDatabase`, "Follow replacedBy").
- **The trash** (ADR 0023): `<trash root>/.hoard-trash/<yyyy-MM-dd>/<path>`, the path being its own manifest; the walk
  indexes those files as trashed copies, so an item whose every file is trashed keeps its id, edits, previews and
  culling; restore is a rename back; Empty and auto-empty (30 days by default) delete; `include=trash`,
  `?trashed=only` and Hoard's Trash screen list trashed items, server ones included.
- **Metadata per fingerprint** (`file_meta`, `media.Video`: duration, container, codec, profile, size, rotation, fps,
  bit depth, HDR, colour space, audio codec), from ffprobe during the walk.
- **The previews queue** (`internal/previews`: the `previews` table is the queue and the record, priorities 0–3,
  `ResetRunningPreviews` on start, a `Generator` interface that ADR 0015 kept open for a GPU worker).
- **The content store** (`blobstore`, `<backup root>/reveal-sync/blobs/`), the phone **mirror** (`mirror_entries`,
  hard links, `.hoard-mirror/`), **ownership computed by query** (ADR 0024 rules 1–4) and per-content locks.
- **The deployed layout** (`server/docker-compose.yml`): the NAS at `/mnt/nas` **read-only**, `/data` and the backup
  root (`/mnt/backup`) as separate volumes, `read_only: true` rootfs with a `/tmp` tmpfs, uid 65532,
  `no-new-privileges`, `cpus: "2"`, `mem_limit: 1536m`. The image is Alpine (musl) with an FFmpeg built from source
  for decoding and `libx264` proxies only.

The host is also the owner's desktop, where he plays games and streams (with NVENC). Compression must yield to that.

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Where | On hoard-server, in a new package `internal/compress`, behind the feature `compress`. Only when the server finds a working `hevc_nvenc` at start; otherwise the feature is absent and nothing is queued. |
| Image | A second image variant, **`hoard-server:<version>-nvidia`** (Debian slim, glibc; the same binary and tools plus an FFmpeg with NVENC/NVDEC/CUDA and libvmaf, and exiftool). The Alpine image stays the default and never compresses (musl cannot load the driver's libraries). The container runs as the library owner's uid:gid. |
| Recipe | HEVC NVENC, **lean** options (`-preset p7 -tune hq`, no multipass/lookahead/b-frame/AQ options), **cq 28**, `-maxrate` = source video bitrate × max(1, r_frame_rate ÷ avg_frame_rate) and `-bufsize` 2× that, `-hwaccel cuda` with a CPU-decode retry, `-fps_mode passthrough -enc_time_base:v demux`, `-noautorotate`, 10-bit stays Main 10 with HDR10 static metadata, audio copied except **PCM → AAC-LC 256k**, `-map -0:d`, marker `dev.hfps.hoard.compressed`, then exiftool and mtime. |
| Quality | **cq 28, re-encoded lower only under VMAF 93** (mode C): encode at 28, measure; under 93, re-encode at 26, then 24, keeping the first that reaches 93. |
| Profiles | `hevc-cq28` (default), `hevc-cq30` ("Smaller"), `av1-cq34` **opt-in per job**, offered once the owner has checked AV1 playback on his devices. |
| Skip | Before encoding: the marker; an ffmpeg-made HEVC/AV1 stream (`Lavc…` encoder tag: the old script); HEVC/AV1 under 0.15 bits per pixel; an estimated saving under 20 %; Dolby Vision; motion photos; items Reveal has records of; Syncthing's own artifacts (`.stversions`, `*.sync-conflict-*`). After encoding: a real saving under 20 % discards the output. |
| Verify | Before anything replaces anything: duration within 0.1 s, the same streams (data streams excepted) and resolution and frame rate, decoded frame count when the header counts differ, an ffprobe + exiftool metadata diff in the item's log, VMAF (full clip up to 30 s, 3 × 2 s spots above) ≥ 92, real saving ≥ 20 %. |
| Per-frame metadata | What the recipe cannot keep (DJI `djmd`/`dbgi`, Sony `rtmd` + NRTM XML, Samsung `smta`) is saved first as an `exiftool -ee3 -j` JSON sidecar in the **server's data directory**, keyed by the output's fingerprint. Nothing is written next to the video. |
| Job model | `compress_jobs` + `compress_items` (schema 12). One GPU queue, priorities, **one** NVENC session, **pause while the GPU is busy**, resumable after a restart (running steps go back to queued; the replace is a journal), cancellable, a log file per item. |
| Triggering | **On demand**: "Compress videos" on a selection or a folder, in Hoard or the web gallery. A per-folder **"Compress automatically"** switch (new videos: after the backup for phone folders, after the walk for NAS folders) is **off everywhere** until the owner turns it on for a folder. An optional night window (off) holds back automatic work only. |
| NAS library | **The server replaces the file itself** through a second **read-write mount** (`/mnt/nas-write`). Every NAS folder under it is eligible; nothing runs until asked. The verified output is renamed over the path; the original goes to **the trash** (ADR 0023's trash root, a same-filesystem hard link or rename into `.hoard-trash/<day>/<path>`). The path never goes missing. The original's item becomes **trashed with `replacedBy`** the output's item, so edits, culling and ratings follow. |
| Phone videos | The server compresses the backed-up original (ADR 0024) into a **new blob**; Hoard downloads it by range, verifies it, and **writes it over the phone's file in place** (same MediaStore row, name, folder, `DATE_TAKEN`, mtime) under a redo journal. The original blob is kept as a **trashed server item** (mirror entry `compressed`, pinned until purged); the backup never uploads the output and never reports a deletion. Android 11+ with All files access. |
| Server-only phone videos | Videos already freed from the phone (or deleted there) whose backup the server holds are compressed **on the server alone**: the output becomes the mirror entry's content, the original a trashed server item. No phone step. |
| Originals | **They go to the trash** and behave like any trashed file: listed in Trash, Empty, auto-empty after the trash's retention (30 days by default). **Restore** puts the original back **and undoes the replace**: the output goes to the trash and `replacedBy` is reversed. No confirmation step, no hold, no `.hoard-originals`. |
| Synced copies | No folder is treated specially for Syncthing (it is no longer used; stale `.stfolder` markers are ignored). An output that reappears in a NAS folder (a sync, a copy) is recognised by the marker and the `compress_items` fingerprint pair: it joins the output's item, and the original's item follows it through `replacedBy`. |
| Marker | Keys `dev.hfps.hoard.compressed` = `v1, <codec>-cq<q>, <yyyy-MM-dd>` in the file, and the server record `compress_items` keyed by both fingerprints (original and output). |
| Protocol | 14 additive `/v1` routes under `/v1/compress/…`, `features: "compress"`, a `compress` object in `GET /v1/server`, items gain optional `compressed` and `compressedTo`. Reveal is not affected. |
| UI | Hoard: "Compress videos" on a Gallery selection and a folder, the estimate, Settings › Compress videos (progress, automatic folders, history), Trash entries "Original of a compressed video". Web gallery: the same, plus a Compression view. Admin page: GPU status, sessions, pause-while-busy, the night window, the retention of server-kept originals. |

## The recipe

### Default profile `hevc-cq28`

Per file, with `MAXR`, `PIX` and `AUD` from the source's ffprobe (`-select_streams V:0`):

```sh
# MAXR = source video bit_rate × max(1, r_frame_rate / avg_frame_rate)            (b/s)
# PIX  = "-pix_fmt p010le -profile:v:0 main10" for a 10-bit source, else "-pix_fmt yuv420p -profile:v:0 main"
# AUD  = "-c:a aac -b:a 256k" when the audio is pcm_*, else nothing (audio copied)
ffmpeg -hide_banner -nostdin -y -hwaccel cuda -noautorotate -i "$SRC" \
  -map 0 -map -0:d -map_metadata 0 -map_chapters 0 -c copy \
  -c:v:0 hevc_nvenc -rc vbr -cq $Q -b:v:0 0 -maxrate:v:0 $MAXR -bufsize:v:0 $((2*MAXR)) \
  -preset p7 -tune hq -tag:v:0 hvc1 $PIX $AUD \
  -fps_mode passthrough -enc_time_base:v demux \
  -metadata "dev.hfps.hoard.compressed=v1, hevc-cq$Q, $(date +%F)" \
  -movflags +use_metadata_tags+faststart "$OUT"
exiftool -q -q -m -overwrite_original -tagsFromFile "$SRC" -all:all -unsafe "$OUT"
touch -r "$SRC" "$OUT"
```

The server runs these as argument vectors (never a shell), with `PATH=/usr/bin:/bin` and `HOME=/nonexistent` like the
CPU generator (`previews/cpu.go`), under `nice` (`previews/nice_unix.go`), in a private work directory. `$OUT` keeps the
source's container (`.mp4`/`.mov`, by the source's extension). Before the encode, when the source has a timed data track
or a vendor box the recipe drops, `exiftool -ee3 -j -G3` writes the per-frame sidecar ("Metadata").

- **`-maxrate` (the cap fix).** Without an explicit cap NVENC silently caps the bitrate at a level-derived maximum, and
  for variable-frame-rate sources it budgets at `r_frame_rate` (120 on Samsung) instead of the real ~30 fps. The
  calibration's 2.7K60 clip came out at the same 35.5 MB and VMAF 83.2 for every cq from 24 to 30, and a 4K phone clip
  at about 21 MB for cq 24–30; the old script has the same cap (its 2.7K60 output scores 83.5). With the cap the cq is
  honoured and the output's video stays at or near the source's bitrate (at most +3 % seen; re-encoding the old outputs
  at cq 24 went from +23/+32 % larger to +0.3/+2.9 %). `-level 6.2 -tier high` also lifts the cap but writes level 6.2
  into the stream, which phones may refuse: not used.
- **`-enc_time_base:v demux` (the VFR fix).** Without it `-fps_mode passthrough` re-rounds Samsung timestamps by up to
  4.2 ms (446 of 603 frames moved): invisible, but frame-accurate checks break (VMAF read 77 instead of 95.9). With it
  the timestamps are bit-identical to the source's.
- **`-hwaccel cuda`**: encoding is decode-bound. 2.7K 10-bit HEVC runs at 0.9–1.2× realtime with CPU decode and
  2.2–2.8× with CUDA decode (same size within 0.3–1.5 %); H.264 1080p/2.7K at 2.2–3.0× either way. When CUDA decode
  fails (an unsupported profile, an odd chroma format) the item is retried once without it, logged.
- **`-noautorotate`** keeps the rotation matrix (the old script rotated pixels and dropped it).
- **10-bit stays 10-bit.** At the same q a Main 10 output is the same size (−5 to +1 %) with +0.25–0.5 VMAF; 8-bit needs
  3–21 % more bytes (median 5.5 %) for the same VMAF. HLG (`arib-std-b67`) and PQ (`smpte2084`) with BT.2020 are kept;
  HDR10 mastering display and content light level survive **bit-exact**, as SEI and as container side data.
  **HDR10+ dynamic metadata (SMPTE 2094-40) cannot survive** a re-encode: the estimate flags the item (`hdr10plus`)
  and the UI says so before the job starts. **Dolby Vision** (`media.Video.HDR = "dolby-vision"`) would lose its RPU
  and become its base layer: skipped (`dolby_vision`).
- **Audio** is copied, except PCM (the ZV-E10's `twos`): copied into MP4 it would become ISO `ipcm` at 1536 kb/s
  (14–16 % of a q28 output) whose Android playback is unverified. It is encoded to **AAC-LC 256k** (about 2.5 % of the
  output; the old script used AAC 128k).
- **`-map -0:d`** drops the timed data tracks MP4 cannot carry (DJI `djmd`/`dbgi`, Sony `rtmd`: the mp4 muxer refuses
  them, `-f mov` writes unreadable samples); `tmcd` is regenerated from the timecode tag. Their content is kept in the
  sidecar ("Metadata").
- **`-b:v 0`** changes nothing (byte-identical outputs with and without); it stays as documentation that the target is
  cq only.

### Why lean, not the draft option set

The owner's draft adds `-multipass fullres -rc-lookahead 32 -spatial-aq 1 -temporal-aq 1 -bf 4 -b_ref_mode middle`.
NVENC accepts every one of them on the RTX 4080; measured:

| Option | Effect at equal VMAF or equal cq |
| --- | --- |
| `-spatial-aq 1` | about **+17 % size and −1 VMAF** at the same cq (2.7K30: 17.55 MB / 94.31 with it, 15.06 MB / 95.40 without; 1080p60: 23.20 / 95.87 vs 22.81 / 96.83). AQ fights banding in flat areas, which VMAF under-rates; the approved old look has no AQ. |
| `-temporal-aq 1` | no measurable effect (±0.05 % size, ±0.01 VMAF) |
| `-multipass fullres -rc-lookahead 32 -bf 4 -b_ref_mode middle` (with `-tune hq`) | **0.5–4.7 % more bytes** than lean for the same VMAF (BD-rate, lean vs specified: −3.3, −0.5, −4.7, −4.7 %), and 4–22 % slower. Lean scores ≥ the specified set at the same cq and equals the old recipe on 8-bit sources. |

So the recipe is **lean** (default taken, see "Defaults taken"). The specified set (without AQ) stays reachable as the
job option `"options": "quality"` for a later banding comparison by eye.

### Profiles

| Profile | Codec, q | Use |
| --- | --- | --- |
| `hevc-cq28` (default, "Same look") | HEVC, cq 28 | Within ±0.3 VMAF and ±5 % size of the old script's output on every camera original, with 10-bit, rotation, audio and metadata kept. |
| `hevc-cq30` ("Smaller") | HEVC, cq 30 | About −1 to −1.5 VMAF for another 15–25 % of size. |
| `av1-cq34` ("AV1", opt-in) | `av1_nvenc`, cq 34 (≈ HEVC cq + 6) | 14–22 % fewer bytes than HEVC at equal VMAF, the same speed (decode-bound). Plays only where the device decodes AV1 in hardware: hidden until the owner turns on "Offer AV1" (admin page) after his playback check, then chosen per job; never the default, never for automatic folders. |

Each job names one profile. Quality mode C may lower the q of one item (by 2 or 4); the marker and the record carry the
q actually used.

### Expected results (calibration, final recipe)

| Class | Saving at cq 28 | VMAF (mean / 1 % low) |
| --- | --- | --- |
| 2.7K30 drone, HEVC 10-bit | 82–91 % | 93.2–96.4 / 89–94 |
| 2.7K60 drone, high motion | 56 % at cq 28 (90.8), so mode C keeps **cq 26: 43 %** | 93.5 / 86.7 |
| 2.7K Pocket (H.264, HEVC 10-bit) | 76–80 % | 97.0–98.4 |
| 4K phone, HEVC | 61 % | 99.8 (4K model) |
| 1080p phone, HEVC | 72 % | 95.9 |
| 1080p60 camera, H.264 (PCM → AAC 256k) | 79–81 % + the audio's share (13.8 → 2.5 % of the output) | 96.8–98.8 |
| app export, H.264 Baseline | 86 % | 96.4 |
| old-script outputs | 5–15 % (skipped) | second-generation loss, −1 to −8 VMAF |

**Throughput** (clean, one job): 2.2–2.8× realtime for 2.7K 10-bit HEVC with CUDA decode, 2.2–3.0× for H.264, about
1.1× for 4K30 HEVC; plus verification (below). The phone folder's 129 originals (about 78 minutes of video) are about
35 minutes of GPU time and save about 23 GB.

## Estimate and skip rules

### Probe

The estimate needs more than `media.Video` holds: the video stream's `bit_rate`, `r_frame_rate` and `avg_frame_rate`,
`pix_fmt`, its `encoder` tag, the format tag `dev.hfps.hoard.compressed`, the audio codec of every audio stream, data
streams, the first frame's side data (HDR10+, Dolby Vision RPU) and `nb_frames`. These go into a new table
`video_probe` (per fingerprint, versioned by `compress.ProbeVersion`), filled **lazily**: when an estimate or a job
needs a file and its row is missing, one ffprobe run (`PROBE_WORKERS`, `PROBE_TIMEOUT`, as the walk) reads it. Not
`media.MetaVersion`: raising that would make the next walk re-read the metadata of every photo too.

### Rules, in order

| # | Rule | Reason code | Why |
| --- | --- | --- | --- |
| 0 | not a `video` item (motion photos' videos, Live Photo pairs), or its main file unreadable (no moov: the one broken phone file), or no video stream | `not_video`, `unreadable` | Motion photo videos are seconds long and bound to their still. |
| 1 | the marker `dev.hfps.hoard.compressed` is present, or the fingerprint is the output of a `compress_items` row | `already_compressed` | Never twice. |
| 2 | the video stream's encoder tag starts with `Lavc` and the codec is HEVC or AV1 | `older_compression` | Every old-script output carries it (`Lavc… hevc_nvenc`; the inventory found all 550 by it), and so does every output of this job if a remux stripped the marker. Camera and phone encoders never write `Lavc`. |
| 3 | HEVC or AV1 at **bpp < 0.15** (bpp = video bitrate ÷ (width × height × avg fps)) | `efficient` | Real cq-28 saving at 0.12–0.13 bpp was 5–15 %. The nearest original measured was a 4K phone clip at 0.164 (real 61 %): the margin is thin, rules 2 and 5 are the net. |
| 4 | estimated saving < 20 % | `small_saving` | Estimate below. |
| — | HDR `dolby-vision` | `dolby_vision` | Its RPU cannot be kept. |
| — | a live Reveal `photo` or `version` record names a member's fingerprint (`originalNamed`) | `reveal_record` | Reveal has no rule for a replaced video; the item stays as it is. |
| — | a Syncthing artifact: a file whose name contains `.sync-conflict-`, or a path under `.stversions` (the walk and Hoard skip dot folders already; the rule makes it explicit for phone paths and selections) | `sync_artifact` | A conflict copy or a kept version belongs to the sync tool; Hoard never rewrites it. |
| — | trashed (ADR 0023), in a `.hoard-*` folder, or already in an unfinished job | `trashed`, `busy` | |
| 5 | after encoding: **real saving < 20 %** (output file vs source file) | `small_saving` (state `skipped`, output discarded) | Catches estimate errors such as high-motion 60 fps (estimated 80 %, real 56 %, still kept) and anything rule 3 misses. |

**Estimate.** Predicted video bytes = bpp_ref(q) × width × height × avg fps × duration ÷ 8, with bpp_ref(28) = 0.067,
bpp_ref(30) = 0.049 (the camera originals' median output) and AV1 at 0.82 × HEVC's; plus the audio as it will be (copied
bytes, or 256 kb/s for PCM), plus 0.3 % container. Mean absolute error on camera originals was 5.3 points (4.1 at cq
30); it is blind to generation (old outputs over-predicted by 31–45 points: rules 1–3 take those out) and over-predicts
high-motion 60 fps by about 20 points (rule 5, and mode C's re-encodes, which the estimate does not foresee). The UI
says "about". Time = duration ÷ the class's speed (2.5× realtime for HEVC 10-bit sources, 2.6× for H.264, 1.1× for 4K
HEVC; corrected by the server's own measured average per class once it has 10 items of a class) + the verification
time.

On the owner's phone folder this skips the 550 old outputs (rule 2) and the broken file, encodes the 129 originals, and
decides the 50 app re-encodes one by one (the calibration's H.264 Baseline export saved 86 %). The folder is a normal
folder: its stale `.stfolder` marker changes nothing.

## Quality and verification

### Quality mode C (decided)

Encode at the profile's q (28); measure VMAF; when it is **under 93**, re-encode at q − 2, then q − 4 at most, and keep
the first output that reaches 93. In the calibration only the 2.7K60 clip falls under (90.8 → cq 26: 93.5 at a 43 %
saving); every other clip keeps the approved look at one encode. The floor is a job option (`quality: {"mode":
"floor", "vmaf": 93}`, the default); `{"mode": "fixed"}` (no re-encode) and `{"mode": "target", "vmaf": 95}` (a
per-clip search, 2–3 encodes) remain available as job options in the web gallery's advanced settings, not offered in
Hoard.

### Checks (every item, after the exiftool step, before anything is replaced)

| Check | Rule | Time |
| --- | --- | --- |
| duration | within 0.1 s (ffprobe format duration) | two ffprobe runs for all probe checks, 0.13–0.36 s |
| streams | the same count and kinds, ignoring the dropped data streams; the cover counted as an attached picture (it fails before the exiftool step on drone clips: ffmpeg drops the cover, exiftool restores it) | (same) |
| picture | the same coded width and height, rotation, frame rate, bit depth, transfer and primaries; HDR10 mastering display and CLL equal | (same) |
| frames | header `nb_frames` equal; when they differ (a phone source had 607 packets but 603 decodable frames) a full decode counts frames on both sides | 6–28 s only then |
| metadata diff | `ffprobe -show_format -show_streams` and `exiftool -j -G1 -a` of both, the diff written to the item's log (tag names and whether values are equal; values stay in the log file, never in the server log) | 1–2 s |
| VMAF | libvmaf `vmaf_v0.6.1` (≤ 2.7K) or `vmaf_4k_v0.6.1` (4K), at the source's resolution and bit depth, unrotated, distorted stream shifted +5 ms; **full clip when ≤ 30 s, else 3 × 2 s spots at 10/50/85 %** (spots were within ±1.0 of the full score) | full: 0.8–2.6 s per second of video on 8 threads; spots: 4.5–23 s |
| saving | output file ≥ 20 % smaller than the source file (rule 5) | — |

A failing check fails the item (`failed`, reason `verify_<check>`), the original is untouched and the output is
deleted. A final VMAF under the floor − 1 (92) after mode C's steps fails it as `verify_quality`. VMAF runs on the CPU
(`VMAF_THREADS`, default 4): libvmaf's CUDA path (`libvmaf_cuda`) is measured in phase S1 and used if it matches the
CPU scores within 0.2.

### Metadata

What ffmpeg keeps or exiftool restores (QuickTime create/modify dates, track dates, rotation, colour, HDR10 static
metadata, HLG tags, GPS in `©xyz` and Keys `location`, Samsung's Author and capture fps, DJI cover art, preview and
thumbnail images, `©too`, `Xtra`, `fsid`) is kept in the file. **Not writable back** into a re-encoded MP4:

- DJI: the `djmd` per-frame track (model, serial, ISO, shutter, colour temperature, frame number), the `dbgi` debug
  track (1.2–2.5 Mb/s of the source), the `btec` beauty-filter tags, `©uid`, the Pocket 3 `dji` udta atom;
- Sony XAVC S: the `rtmd` per-frame track (exposure, aperture, gain, white balance, time), the NRTM XML (device, serial,
  creation date, timecode changes, recording mode, UMID), the `PROF` and `MTDT` uuid boxes;
- Samsung: `smta` (model, `saut`, `sthm`), `smrd`, `cami`, Keys `AndroidVideoTemporalLayersCount`;
- every source: `HandlerVendorID` and the old codec's configuration record.

**The per-frame sidecar (decided).** For a source with any of these, the job runs `exiftool -ee3 -j -G3 -a` on the
original before the encode and keeps the JSON in the **server's data directory**:
`<data dir>/compress/meta/<output fingerprint[0:2]>/<output fingerprint>.json` (gzip; a few hundred KB per file, the
`dbgi` debug track's binary payload excluded). It is keyed by content, so it survives moves, renames and the
original's purge; it is backed up with `/data`; it is never written next to a video (the owner's rule: apps do not
create files in his folders). The web gallery's item panel and `GET /v1/compress/items` say "per-frame data saved"
and the web gallery offers it as a download. The original's trash entry still holds everything until it is purged.

What Hoard's and the web gallery's info panels show today comes from `file_meta` (capture time, make/model for photos,
duration, codec, fps, bit depth, HDR, rotation, audio): after a compression they show the new codec and the same
everything else; make and model of videos were never read, so nothing visible disappears.

## The job model

### Tables (schema 12, `0012_compress.sql`)

```sql
CREATE TABLE compress_jobs (
    id           TEXT PRIMARY KEY,          -- UUID
    target       TEXT NOT NULL,             -- 'nas' (library files) | 'phone' (the caller's phone files)
                                            -- | 'backup' (phone backups no longer on the phone: server only)
    physical_id  TEXT,                      -- phone jobs: the physical device whose files are replaced
    created_by   TEXT NOT NULL,             -- device id, or 'admin' (web gallery session)
    name         TEXT NOT NULL DEFAULT '',
    selection    TEXT NOT NULL,             -- JSON, as sent (folders, filters, items)
    profile      TEXT NOT NULL,             -- 'hevc-cq28' | 'hevc-cq30' | 'av1-cq34'
    options      TEXT NOT NULL,             -- JSON: quality mode and floor, 'quality' option set
    auto         INTEGER NOT NULL DEFAULT 0, -- 1: a folder's "Compress automatically" (a standing job)
    priority     INTEGER NOT NULL,          -- 1 on-demand phone, 2 on-demand library and backup, 3 automatic
    state        TEXT NOT NULL,             -- active | paused | cancelled | done (an automatic job stays active)
    created_at   INTEGER NOT NULL,
    finished_at  INTEGER,
    request_id   TEXT UNIQUE                -- the client's idempotency key
) STRICT;
CREATE UNIQUE INDEX compress_jobs_auto ON compress_jobs (target, selection) WHERE auto = 1 AND state != 'cancelled';

CREATE TABLE compress_items (
    id              INTEGER PRIMARY KEY,
    job_id          TEXT NOT NULL REFERENCES compress_jobs (id),
    item_id         TEXT NOT NULL,          -- the item when queued (the original's)
    fingerprint     TEXT NOT NULL,          -- the original's v1: fingerprint
    sha256          TEXT,                   -- the original's SHA-256 (made while reading it for the encode)
    size            INTEGER NOT NULL,       -- the original's
    source          TEXT NOT NULL,          -- 'nas' | 'blob'
    folder_id       TEXT,                   -- nas: the NAS folder and path being replaced
    rel_path        TEXT,
    mirror_entry_id INTEGER,                -- phone and backup: the mirror entry
    state           TEXT NOT NULL,          -- below
    reason          TEXT NOT NULL DEFAULT '',   -- skip or failure reason
    priority        INTEGER NOT NULL,
    order_key       INTEGER NOT NULL DEFAULT 0, -- newest shot first within a priority
    not_before      INTEGER NOT NULL DEFAULT 0,
    attempts        INTEGER NOT NULL DEFAULT 0,
    est_size        INTEGER, est_ms INTEGER, duration_ms INTEGER,
    q               INTEGER,                -- the q actually used
    out_fingerprint TEXT, out_sha256 TEXT, out_size INTEGER,
    vmaf_mean REAL, vmaf_low REAL, vmaf_mode TEXT,   -- 'full' | 'spot'
    encode_ms INTEGER, verify_ms INTEGER, decoder TEXT,  -- 'cuda' | 'cpu'
    meta_sidecar    INTEGER NOT NULL DEFAULT 0, -- 1: a per-frame JSON was saved
    replace_step    TEXT NOT NULL DEFAULT '',   -- the replace or restore journal (below)
    trash_path      TEXT,                   -- nas: the trashed file, relative to the folder (original, or the
                                            -- output after a restore)
    replaced_at INTEGER, trashed_at INTEGER, purge_after INTEGER, purged_at INTEGER, restored_at INTEGER,
    updated_at      INTEGER NOT NULL
) STRICT;
CREATE INDEX compress_items_queue ON compress_items (priority, order_key DESC) WHERE state = 'queued';
CREATE INDEX compress_items_job ON compress_items (job_id, state);
CREATE INDEX compress_items_fp ON compress_items (fingerprint);
CREATE INDEX compress_items_out ON compress_items (out_fingerprint) WHERE out_fingerprint IS NOT NULL;
CREATE INDEX compress_items_purge ON compress_items (purge_after) WHERE state IN ('replaced', 'restored');
CREATE UNIQUE INDEX compress_items_active ON compress_items (fingerprint)
    WHERE state NOT IN ('skipped', 'failed', 'cancelled', 'purged');

CREATE TABLE video_probe (fingerprint TEXT PRIMARY KEY, version INTEGER NOT NULL, data TEXT NOT NULL) STRICT;
```

Additive only (no existing table changes meaning; `server/testdata/compat/` keeps opening; no index on an existing
table, so `TestMigrationsNeedNoTempDir` holds). New values in existing TEXT columns, without a table change: ADR 0024's
`mirror_entries.removed_reason` gains `compressed` (the partial unique index `mirror_entries_path` does not list it,
so a compressed entry never owns its path), and `item_sources.source` gains `kept` (a server-kept trashed original,
"Phone videos"). Settings stay rows of `settings`.

### States

```
queued → estimating → skipped
                    → encoding → verifying → failed | skipped (rule 5)
                                           → ready → replacing → replaced ⇄ restored   (restore / restore again)
                                                                     ↓          ↓
                                                                   purged     purged
any state before replacing → cancelled
```

| State | Meaning |
| --- | --- |
| `queued` | waiting for the GPU queue |
| `estimating` | probe and rules (CPU, short) |
| `skipped` | a rule (or the post-encode guard) kept the original; `reason` says which |
| `encoding`, `verifying` | sidecar, GPU encode (mode C's re-encodes included), exiftool, mtime and the checks; the output lives in the work directory |
| `failed` | `reason` (`encoder`, `verify_*`, `source_changed`, `no_space`, `unreadable`, `trash_failed`); the original untouched; retried up to 3 times for transient reasons (`encoder`, `no_space`) with back-off |
| `ready` | a verified output exists. NAS and backup: its replace runs next. Phone: an output blob waiting for the phone |
| `replacing` | the replace (or a restore) journal is running; for phone items, the phone reported it is writing |
| **`replaced`** | the output is the file in place; **the original is in the trash** (NAS: `.hoard-trash`; phone and backup: a trashed server item), restorable until purged |
| `restored` | the owner restored the original: it is back in place and **the output is in the trash**; restoring again swaps them back (`replaced`) |
| `purged` | the trashed file (original, or output after a restore) was emptied from the trash, by the owner or auto-empty; final |
| `cancelled` | the job or item was cancelled before `replacing` |

### Queue, priorities, sessions

- **One GPU queue** (`compress.Queue`), separate from the previews queue (whose lanes stay CPU; a later NVENC proxy
  generator, ADR 0015 "GPU worker", would lease from this queue's session budget). Items are leased like previews
  (`LeasePreview`): the most urgent `queued` row, by `priority` (0: an item a client is waiting on, e.g. "Compress
  now" on one video; 1: on-demand phone jobs; 2: on-demand library and backup jobs; 3: automatic folders), then
  `order_key`, then id.
- **One session** (default taken): `COMPRESS_SESSIONS` = 1 (at most 3). Consumer NVIDIA drivers allow 8 NVENC
  sessions, but encoding is decode-bound and the GPU is shared with the owner's games and streaming; two sessions give
  little on one NVDEC. Estimating, the sidecar and exiftool steps and VMAF run on the CPU in their own small pool (2),
  overlapping the next encode.
- **Pause while the GPU is busy** (default taken, on): before leasing, the server reads NVML
  (`nvidia-smi --query-gpu=utilization.gpu,encoder.stats.sessionCount --format=csv`; the `utility` driver capability)
  and starts nothing while another process holds an NVENC session or the GPU was over 50 % busy in the last minute
  (checked every 30 s). A running encode finishes (minutes). This applies to every job.
- **Night window** (optional, **off**): when set (e.g. 01:00–08:00), automatic items (priority 3) start only inside it.
  On-demand jobs run now.
- **Space**: before an encode, the work directory's filesystem needs the estimated output × 1.5 + 1 GiB free, and for a
  NAS replace the NAS filesystem needs the output's size (the original stays, in the trash, until it is purged).
  Otherwise the item waits (`not_before` + 10 min, `reason: no_space` shown) and the job's status says so.
- **Work directory**: `<backup root>/.hoard-compress/work/<item id>/` (the backup root's filesystem is the large one,
  and the phone outputs then reach the blob store by rename). A dot name cannot collide with a device folder (ADR 0024
  forbids dot names there). Without a backup root, `POST /v1/compress/jobs` answers `503 storage_not_configured`.

### Triggering

- **On demand** is the default and the only thing that runs unless the owner turns more on: "Compress videos" on a
  selection, or "Compress videos in this folder" (a NAS folder or a sub-folder in the web gallery's Folders and
  Hoard's Files when the folder is mapped (ADR 0022); a backed-up phone folder in Hoard's Settings › Backup and its
  Gallery folder). Each makes one job (`auto = 0`) over what is there now.
- **"Compress automatically"** is a per-folder switch, **off everywhere** by default. Turning it on makes a standing job
  (`auto = 1`, one per folder) with the default profile (never AV1) and mode C; it covers **new** videos only:
  - **NAS folder** (and its sub-folders): after each walk, video items that became live in it since the switch was
    turned on (the walk's new item ids) are appended to the job and estimated; skips apply as always.
  - **Phone folder** (a backup root, or a folder under it): after the backup stores a new video of it (a report answer
    `stored` or `nas` for a video entry created after the switch was turned on), the item is appended; the phone does
    its replace when its `CompressWorker` next runs.
  - Turning the switch off cancels the job's queued items; replaced ones stay replaced (their originals stay in the
    trash until purged).
- The server never starts work over videos that existed before a switch was turned on, unless the owner asks for
  that folder on demand (the switch's sheet offers "Also compress the N videos already there").

### Restart, cancellation, logs

- **Restart**: at start, `estimating`/`encoding`/`verifying` rows go back to `queued` (`attempts` unchanged; a run killed
  by a restart is not a failure) and their work directories are removed. A `replacing` row is settled from its journal
  (NAS, backup) or left to the phone (phone). `ready` outputs stay (the work file or blob is checked by size and SHA-256
  before use; missing or different → back to `queued`).
- **Cancel** a job: every `queued`/`estimating`/`ready` item becomes `cancelled`, running ffmpeg/VMAF processes are
  killed through their context, their work files removed. Items already `replaced` are not undone (Restore is the
  undo). **Pause** stops leasing the job's items; running ones finish. Single items can be cancelled the same way.
- **Logs**: one file per item, `<data dir>/compress/logs/<job id>/<item id>.log`: the command lines (paths replaced by
  `SRC`/`OUT`), ffmpeg's stderr (last 64 KiB), the check table, the metadata diff, VMAF per spot, timings. Kept 90 days
  after the item settles. The server's own log (`slog`) carries ids, states, reasons and timings, never paths, names or
  metadata values (ADR 0013 "Logs").

## GPU, image and compose

- **Image**: `ghcr.io/henriquefps/hoard-server:<version>-nvidia`, built by `server-release.yml` next to the default. Base
  `debian:<stable>-slim` (glibc: the NVIDIA container toolkit injects the host driver's `libcuda.so.1` and
  `libnvidia-encode.so.1`, which are glibc libraries; Alpine's musl cannot load them). The same Go binary and preview
  tools (libvips, libheif, LibRaw, FFmpeg), with FFmpeg configured `--enable-ffnvcodec --enable-nvenc --enable-nvdec
  --enable-cuvid --enable-libvmaf` (nv-codec-headers pinned by SHA-256 like every tarball in the Dockerfile; NVENC is
  loaded at run time, nothing NVIDIA is shipped in the image), plus `exiftool` (Perl, pinned tarball) and the two VMAF
  models.
- **User** (default taken): the `-nvidia` container runs as **the library owner's uid:gid** (`user:` in compose), so it
  can rename files in his directories and every file it writes (outputs, trash day folders) keeps his ownership; mode
  bits are copied from the original. The default image keeps uid 65532.
- **Compose** (the example gains a commented block, `server/README.md` a "GPU compression" section):

  ```yaml
  services:
    hoard-server:
      image: ghcr.io/henriquefps/hoard-server:latest-nvidia
      user: "${LIBRARY_UID:-1000}:${LIBRARY_GID:-1000}"   # the owner of the NAS files
      # GPU through the NVIDIA container toolkit (CDI); "video" is required for NVENC/NVDEC.
      devices:
        - nvidia.com/gpu=all
      environment:
        - NVIDIA_DRIVER_CAPABILITIES=compute,video,utility
        - COMPRESS_SESSIONS=1
      volumes:
        - ${NAS_DIR:-/srv/photos}:/mnt/nas:ro                  # indexed read-only, as before
        - ${NAS_DIR:-/srv/photos}:/mnt/nas-write               # compression's replaces and trash moves only
      cpus: "4"            # VMAF and exiftool run on the CPU
      mem_limit: 2g
      tmpfs:
        - /tmp
  ```

  `runtime: nvidia` or `deploy.resources.reservations.devices` (`driver: nvidia`, `capabilities: [gpu, video]`) are
  equivalent for older toolkits. `read_only`, `no-new-privileges` and the tmpfs stay; ffmpeg and exiftool write only to
  the work directory (exiftool's temporary file is next to its target, in the work directory). `/data` and the backup
  folder must be owned by the same uid:gid (`chown` once when switching images).
- **Detection** at start and every 10 minutes while unavailable: `ffmpeg -encoders` lists `hevc_nvenc`, then a 0.2 s
  `testsrc2` encode with `hevc_nvenc` (and `-hwaccel cuda` decode of it) succeeds, libvmaf and exiftool answer. The
  result is on the admin page (driver version, GPU name, encoders, `av1_nvenc`, VMAF, CUDA decode) and in `GET
  /v1/server`.
- **Without a GPU** (the default image, no toolkit, a driver error): `features` has no `"compress"`, the routes that
  start work answer `503 unavailable` (`details.reason`: `no_gpu`, `no_tools`), the queue does not lease. Jobs and
  replaced items that exist (a GPU that went away) stay readable, and restore and purge keep working: they need no
  GPU. `GET /v1/server` then still has the `compress` object with `"available": false`.

## Where the output goes

### NAS library: the server replaces, through a write mount

ADR 0023 kept the NAS read-only for the server: every move is Hoard's over SMB. For compression that does not work:

| Option | Verdict |
| --- | --- |
| Hoard replaces over SMB (as the trash) | Every output goes server → phone → share: for a library job of 100 GB of video that is ~20 GB pulled and pushed through a phone over Wi-Fi, with the phone awake for hours; the server cannot finish a job alone. SMB has no server-side copy from outside the share. Rejected. |
| A small helper on the host (outside the container) does the rename | A second program to install and secure; the server's state machine would depend on it. Rejected. |
| **The server gets write access** | The bytes never leave the host; a job runs unattended; the replace is a local link + rename, atomic. Cost: the server can write the library. Mitigated below. **Chosen.** |

**The write mount.** The read-only `/mnt/nas` stays the only path the walk, previews and originals read. A second
mount of the same host directory, `/mnt/nas-write` (configurable `NAS_WRITE_ROOT`), is used **only** by
`compress.Replacer`. **Every NAS folder under it is eligible**; there is no per-folder allowlist, and nothing is written
until the owner starts a job (or turns "Compress automatically" on for a folder). The server maps a folder's read path
to its write path by the same relative path and proves it before a folder's first replace of a job: the directory under
the write path is the same as the one under `/mnt/nas` (same `st_dev`/`st_ino`) and the trash root (below) is writable
(a probe file created and removed in its `.hoard-trash`). A folder that fails has its items fail `not_writable` with
the reason (not mounted, permission denied); without the write mount, `target: "nas"` is answered `503 unavailable`,
reason `no_write_mount`, and `GET /v1/server` says `"nasWrite": false`.

**Containment.** All writes go through an `os.Root` opened on the folder's write path (no `..`, no symlink out), from
`rel_path` in the database (never a request path), and only these operations exist: create a hidden temporary file
next to the target; create trash day folders; hard-link (or rename) a file into the trash; rename the temporary file
over the target; rename a trashed file back over its path (restore); unlink a trashed file (purge). Nothing else in the
server opens the write mount (a test greps for it).

**The trash root** is ADR 0023's: on the server it is always `<serverPrefix>/.hoard-trash` inside the NAS folder. The
server does not know Hoard's SMB mappings, so it takes **the nearest existing `.hoard-trash` among the file's ancestor
directories inside the NAS folder** (one Hoard made for a share exporting a sub-folder, ADR 0023 case 2), **else the
NAS folder's root**. The trashed path is `<trash root>/.hoard-trash/<yyyy-MM-dd>/<path from the trash root>`
(`<yyyy-MM-dd>-2`, `-3` when taken, the server's local date), exactly what Hoard writes: the path is its manifest, and
the walk, Hoard's Trash screen, restore and auto-empty treat it as any trashed file. `.hoard-trash` is the only new name
the server creates in the owner's folders, and the one he already accepted for the trash.

**The replace journal** (`compress_items.replace_step`, each step written before it runs; a restart settles a step by
`stat`, like ADR 0023's `moving` rows):

1. `check`: the file at `rel_path` has the original's size and mtime, and its `v1:` fingerprint (2 MiB read) equals
   the row's. Otherwise `failed: source_changed` (the file was edited or replaced meanwhile).
2. `copy`: the verified output is copied into `<dir>/.<name>.hoard-compress-<item id>` (a dot name: the walk and Hoard
   never list it), `fsync`ed, read back and checked by SHA-256, its mode set to the original's, its mtime to the
   original's (`os.Chtimes`).
3. `trash`: the original is **hard-linked** to its trashed path. The path never goes missing and no byte is copied.
   Without hard links (`EPERM`), it is **renamed** there instead, and step 4 follows at once; the walk's second look
   (ADR 0023 walk step 3) covers the instant the path is empty. `EXDEV` (the trash root on another filesystem than the
   file, a bind mount inside the folder) fails the item (`trash_failed`): the temporary file is removed and the
   original is untouched. Never a copy, never a delete.
4. `swap`: `rename(<temporary>, <rel_path>)`: atomic; readers (Samba, the walk) see the old or the new file. After a
   hard link the original keeps exactly one name, its trashed one.
5. `done`: `replaced`, `trash_path`, `trashed_at`, `purge_after` set; a walk of the folder is requested
   (`Indexer.RequestWalk`).

**What the walk and the items do.** The walk sees the path with another size (mtime is the original's), re-reads its
fingerprint and metadata: the file is the output, a new item. It also sees the original under `.hoard-trash`: a
trashed copy of the same path (ADR 0023 walk step 1), so the original's item has only trashed locations and is
**trashed**, not a tombstone: it keeps its id, previews and the right to come back. **Heir rule** (ADR 0014 and 0023
amendment): when an item's main fingerprint is the `fingerprint` of a `compress_items` row in `ready`, `replacing`,
`replaced` or `purged`, and the row's `out_fingerprint` is a member of a live item, the original's item (trashed, or a
tombstone once purged) gets **`replacedBy`** = that live item; and the reverse for `restored` rows (the output's item,
then trashed, gets `replacedBy` = the original's item, live again). Then:

- `item_edits` move in the indexer's transaction (`store/edits.go`, as for any `replacedBy`), culling records are sent
  again with `replacedBy` (`store/culling.go`), and Hoard and Reveal follow it as they already do for a RAW that
  appears. A trashed item with `replacedBy` is served with `include=trash` as trashed plus
  **`"compressedTo": "<item id>"`** and `replacedBy`; without `include=trash`, as the tombstone shape with `replacedBy`
  (ADR 0023's old-client shape gains that field), so every client follows it.
- The output's item data gains `"compressed": {"from": "<original fingerprint>", "fromItem": "<item id>", "profile":
  "hevc-cq28", "q": 28, "at": <ms>, "original": "trashed" | "purged", "originalSize": <bytes>, "metaSidecar": bool}`,
  built from the record (absent on every other item).
- Sidecars (`X.MP4.xmp`, `X.xmp`) keep their path and pair with the new file by stem, unchanged.
- `GET /v1/originals/{original fingerprint}` and `POST /v1/fingerprints/lookup` keep finding the original while it is
  trashed (ADR 0023: a trashed file is found until it is purged).
- The item id changes (the id is derived from the main fingerprint, and clients derive it too: ADR 0016 culling of
  phone-only shots). Keeping the old id for the new file was rejected: it would break that derivation for every
  client, and `replacedBy` already moves everything attached to an id.

**Restore** (`POST /v1/compress/restore`, also what Hoard's Trash screen and the web gallery call for an item with
`compressedTo`): a journal of its own, the replace mirrored. `check` (the output is still at the path, by fingerprint;
the trashed original still at `trash_path`), `trash` (the output hard-linked to its own trashed path, today's day
folder), `swap` (`rename(trash_path, rel_path)`: the original is back, atomically, mtime and all), `done` (`restored`,
`trash_path` = the output's, `purge_after` restarted). The walk sees the original's fingerprint at its path: the same
item id, live again (ADR 0023 restore); the output's item becomes trashed with `replacedBy` the original's, so edits
and culling move back. Restoring again (from the output's trash entry) swaps once more. A plain ADR 0023 restore by
rename is refused by Hoard for these (the path is taken), so Hoard always uses the route for items with
`compressedTo`.

**Empty and auto-empty.** The trashed original is a trashed file like any other: Hoard's Empty and auto-empty delete it
over SMB (ADR 0023, 30 days by default, "the shorter setting wins"), and the server deletes, through the write mount,
the trashed files **it** made (rows in `replaced` or `restored`) once `purge_after` passes (`compress.trashDays`,
default 30, the admin page) or when `POST /v1/compress/purge` asks. Either way the next walk sees the trash row gone:
the trashed item becomes a tombstone that keeps its `replacedBy` (edits were already moved), and the row becomes
`purged`. This is when the space is saved.

**Trash and compression otherwise.** A trashed item is never queued. A compressed (output) file can be trashed like
any file; its original stays in the trash as its own trashed item. Restoring the original while the output is trashed
by hand: the restore's `check` finds no output at the path, so it is a plain rename back (`restored`, nothing to trash).

### Phone videos: the server side

A phone job's items are files of the caller's own phone that the backup holds (ADR 0024 mirror entries of the
caller's physical device, `on_device = 1`, `store` `blob` or `nas`). The server reads the original by fingerprint, as
previews do (`previews.Config.Open`), and:

- **The original is in the store first.** When the entry's `store` is `nas` (the phone's file is a reference to a NAS
  copy, ADR 0024), the server ingests that NAS copy into the blob store before the item can become `ready` (the
  checked ingest of ADR 0024's "server's own NAS copy"; the entry becomes `blob`). The original then never depends on
  a NAS file that a library job, a copy or a sync may replace later.
- **The output becomes a blob**: the verified work file is ingested by `blobstore` (renamed into
  `reveal-sync/blobs/<sha>` on the same filesystem, mode `0400`), with an `originals` row for its fingerprint, no
  device claim (`blobs.device_id` null), and is **not linked** into the phone's tree yet. State `ready`.
- **Ownership rule 5** (ADR 0024 amendment): bytes are also kept while a `compress_items` row in `ready`, `replacing`,
  `replaced` or `restored` names them as the original or the output, until the row is `purged`. So neither a Reveal
  delete nor a backup purge can remove a trashed original or an output the phone has not fetched.
- **The phone reports the replace** (`POST /v1/compress/items/{id}/replaced` with the file's new fingerprint, SHA-256,
  size and mtime; all must equal the output's, mtime the original's). In one transaction: the mirror entry of the
  original gets `removed_reason = 'compressed'`, `on_device = 0`, **`pinned = 1`**, and its hard link moves to
  `.hoard-mirror/compressed/<path>~<sha8>`; a new current entry takes the path with the output's fingerprint and
  SHA-256, `store = 'blob'`, linked; the item is `replaced`, `trashed_at` and `purge_after` set.
- **Order does not matter.** If the backup's next report arrives first (the path with a new fingerprint), the server
  recognises the rewrite: the new fingerprint is the `out_fingerprint` of a `ready`/`replacing` row of this entry, so it
  applies the same transaction instead of ADR 0024's "rewritten in place" (`replaced`, `.hoard-mirror/previous/`).
  The report answers `stored`: **nothing is uploaded**. A replace is never a removal: the phone reports no `removed`
  for a path that still exists.
- **The original is a trashed server item.** An entry with reason `compressed` is not a member of a mirror source
  (`items.GroupMirror` skips it); instead the server keeps one item source `kept` (`folder_id` = the root id, key = the
  original's fingerprint) whose only member is the original, with a `backup` location (its blob) and a `mirror`
  location (its phone path, `onDevice: false`, `gone: "compressed"`; clients treat an unknown `gone` as `deleted`). An
  item whose every source is `trash` or `kept` is **trashed** (ADR 0023's rule, extended), so the original's item is
  trashed with `replacedBy` / `compressedTo` = the output's item by the heir rule above: listed in Hoard's Trash screen
  (server shots trashed elsewhere, ADR 0023 A4) and the web gallery's trash, with the same retention.
- **Restore** brings the original back to the phone through the phone replace flow (below, "Restore original"): the
  server marks the row `replacing`, Hoard writes the original over the file and reports `/restored`; the server swaps
  the entries (the original's current again, the output's `compressed` and pinned, now the `kept` source) and the heir
  rule reverses. If the phone no longer has the file (freed since), the restore is server-only, as for backup items.
- **Purge** (`POST /v1/compress/purge`, the owner's Empty in Hoard or the web gallery, or `purge_after` passing): the
  `compressed` entry and its link in `.hoard-mirror/compressed/` are removed, the `kept` source dropped (the item becomes
  a tombstone with `replacedBy`), and the blob is removed under the per-content lock if no other rule names it (a Reveal
  claim, another phone's entry, a live record): those stay, and the answer says "kept: also backed up by …".
  `DELETE /v1/originals/{original}` answers `409 conflict`, reason `compressed`, until the purge.
- **Free up** (ADR 0024): the phone file is now the output; its current entry is verified and freed like any other
  (`POST /v1/backup/verify` re-hashes the output blob). The original's `compressed` entry is pinned, so a backup purge
  counts it among "only copies" until it is purged from the trash. `FreeUpPlan` leaves out a shot with an unsettled
  compression on the phone (rows of `compress_phone` not `reported`), and the compression worker abandons an item whose
  file is gone (`gone`): the server then finishes it as a backup item (below).
- If the content is also on the NAS (`store` was `nas`), the phone job still only replaces the phone's file. The NAS
  copy is the library's: a library job decides about it.

### Server-only phone videos (`target: "backup"`)

Videos of a phone backup that are **no longer on the phone** (mirror entries with `on_device = 0`, `freed` or
`deleted`, whose content the store holds) are compressed by the server alone, from any device or the web gallery. After
verification, in one transaction: the original's entry becomes `compressed` (pinned, the `kept` source, trashed), a new
entry with the output's content takes its place with the same path, `on_device = 0` and the same `removed_reason`
(`freed` stays pinned, so the backup's "only copies" count holds), linked in the tree. The output's item replaces the
original's (`replacedBy`), and "Save to this device" (ADR 0024 A2) then saves the compressed file. Restore and purge are
the server-side halves of the phone flow, without a phone step. Hoard's `freed` table is untouched (it keys by phone
path; the item behind it changes by `replacedBy`).

### An output that reappears elsewhere

Syncthing is no longer used, so no folder is detected or treated as synced. The one thing kept is cheap and needs no
table: an output reaching a NAS folder by any route (a sync someone sets up later, a manual copy, "Save to this device"
then a copy) has the output's fingerprint, so it is **the output's item** (copies are one item, ADR 0014), and when it
**replaced the original at that path** (the original's NAS location vanished, the same path now holds the output) the
heir rule applies: the original's item follows through `replacedBy` instead of becoming a plain tombstone, and its
original stays in the store (phone jobs ingest it first) or the trash, so ADR 0024's "a reference lost its only copy"
warning does not fire. The marker confirms the match; a marker without a matching row is only `already_compressed`.
Syncthing's own artifacts (`.stversions`, `*.sync-conflict-*`) are never inputs (rule `sync_artifact`).

## Phone replace flow (Hoard)

Offered on **Android 11+** (as free up) and only with **All files access** (Hoard already asks for it for RAW backup and
"Save to this device"): the in-place write and the restored mtime need it.

### Modules and data

- `:core:server` `compress/`: `CompressPlan` (pure, JVM-tested: which items are ready, what to download, verify, write,
  report; the journal's next step after a crash), `CompressDownloader` (range download with resume, after
  `BackupUploader`: the offset saved after each chunk, `If-Range` on the output's ETag, a body that ends early is a
  dropped connection), `CompressEngine` over a `CompressSource` (the phone side, `PhoneCompress` in `:native`
  `compress/`), `CompressWorker` (WorkManager; `CompressHost` = the application, like `BackupHost`).
- `gallery.db` version 6 (migration 5 → 6, schema exported to `core/server/schemas/`, `GalleryMigrationTest`):
  `compress_jobs` (server job id, target, profile, auto, created at, last status JSON) and `compress_phone` (server item
  id, item id, media id, volume + path, direction `compress` | `restore`, the source's and the target's
  fingerprint/SHA-256/size, the original mtime and `DATE_TAKEN`, `state`, part file, offset, attempts, next attempt,
  error).
- `ServerApi` gains the routes below; `Models.kt` the `compressed` and `compressedTo` item fields and the `compress`
  server object.

### Steps

| Step | What | Failure |
| --- | --- | --- |
| `ready` | `GET /v1/compress/items?mine=true&state=ready` (and `replacing` restores) on every Gallery sync and every `CompressWorker` run; the backup worker enqueues a `CompressWorker` run after a pass that stored videos of an automatic folder | — |
| `downloading` | `GET /v1/compress/items/{id}/output` (a restore: `GET /v1/originals/{fingerprint}`) by range into `getExternalFilesDir(null)/compress/<id>.part` (app-specific, same volume, not scanned, removed on uninstall). Constraints: unmetered network by default (outputs are a fifth of the uploads), battery not low; a foreground `dataSync` service for long runs, as backup | resumed at the saved offset; `404` (cancelled on the server) drops the row |
| `verifying` | SHA-256 of the part = the target's and the size; `MediaMetadataRetriever` duration within 0.1 s of the file's, `MediaExtractor` track count and kinds, width and height equal | the part is deleted and downloaded once more; a second failure reports `POST …/abandon` `verify` |
| `checking` | the phone file still has the expected size, mtime and fingerprint (re-read 2 MiB); its MediaStore row (by id) still names that path | `changed`: abandoned, nothing written |
| `consent` | `MediaStore.createWriteRequest` for a batch of ≤ 500 rows (one system dialog per batch, as the trash; skipped with **Media management**, `MANAGE_MEDIA`): the owner's consent to change camera or WhatsApp files Hoard does not own | refused: those rows stay `verified` ("Waiting for your permission") |
| `writing` | under the grant: `IS_PENDING = 1` on the row (other apps do not read a half-written file; whether the update is allowed on rows Hoard does not own is checked on the emulator, the flow does not depend on it); the part is copied **over the file in place** (`FileOutputStream(path)` truncating, through the platform's FUSE), `fsync`; mtime set back (`File.setLastModified(original mtime)`); `IS_PENDING = 0`; `MediaStore.scanFile` on the path, then again after the mtime (the row of a rewritten file must not keep the old size: ADR 0024 A2's `PathTransferFs` finding) | a crash or kill here is **redone** at the next start from the part file (the journal says `writing`); never rolled back on the phone, since the phone no longer has the previous bytes once the write started (the server does) |
| `written` | read back: SHA-256 of the file = the target's; the row's `SIZE`, `DURATION` and `DATE_TAKEN` (± 1 s) checked; if `DATE_TAKEN` changed, it is written back under the grant (else noted, `date_taken`); `device_media` gets the new fingerprint with `fp_size`/`fp_modified` and SHA-256 (as A2's saved files), `backup_files` its fingerprint, SHA-256, size and state `stored`, so the backup does not hash or upload it | a read-back mismatch rewrites once from the part, then reports `abandon` and asks the server for the previous file (a restore) |
| `reported` | `POST /v1/compress/items/{id}/replaced` (or `/restored`); the part is deleted | retried; the backup report heals it anyway |

**Why in place, not a rename.** Renaming a new file over the old path gives MediaStore a new row: a new `_ID`,
`DATE_ADDED` = now (the video jumps to the top of "recently added" in every gallery app), favourites and the system's
album memberships lost, Hoard's own `device_media` and `backup_files` keys broken. Writing in place keeps the row, its
`_ID`, `DATE_ADDED`, `IS_FAVORITE`, owner package, name and folder; with the mtime set back, `DATE_MODIFIED` reads the
original's after the scan, and `DATE_TAKEN` comes from the restored QuickTime `CreateDate`. The atomicity a rename
gives is replaced by the journal: the verified file is on the phone before the first byte is written, and the server
holds both versions throughout.

**Restore original** is the same flow in the other direction (`direction = restore`): the original's blob downloaded,
checked by SHA-256 (the row's `sha256`), consent, written in place, mtime, scan, `POST /v1/compress/items/{id}/restored`.
Hoard starts it from the Trash screen (the original's entry, "Restore" = undo the compression) or from Settings ›
Compress videos (history).

**What else sees it.** The Gallery shows the same tile (same media id; the item behind it is the output's, joined by
fingerprint) with a "Compressed" badge while the original is in the trash; the viewer plays the phone file. Reveal is
not involved (videos are not handed to Reveal; items Reveal has records of are skipped). Other apps see a modified file:
a cloud backup app (Google Photos) may upload it again as a new version; Hoard says so once in the job sheet.

## Originals: the trash

- **Every replaced original goes to the trash** (owner's decision): NAS originals into `.hoard-trash` by a
  same-filesystem link or rename; phone and backup originals as trashed server items (their blob, pinned). There is no
  confirmation step and no hold: the trash is the safety window.
- **Listed** in Hoard's Trash screen (NAS: as trashed files of their share and folder; server-kept: as server shots
  trashed elsewhere, ADR 0023 A4) with the label "Original of a compressed video" and the saving, and in the web
  gallery's trash and Compression view.
- **Restore** undoes the compression (above): the original back in place, the output into the trash, `replacedBy`
  reversed, edits and culling back on the original's item.
- **Empty / auto-empty** deletes the original for good: Hoard's Empty (selection, day, everything) and its auto-empty
  for NAS files; `POST /v1/compress/purge` and the server's own `compress.trashDays` (30) for what it trashed. That is
  when the space is saved.
- **Accounting** (admin page Storage tab and the Compression view): per job and in total, "saved" = Σ (original −
  output) of `purged` items (realised), "in the trash" (originals not purged yet: count, bytes, oldest, when
  auto-empty takes them), skipped by reason, failed by reason, GPU time. NAS figures read sizes from the records (no
  `du`); phone figures separate "smaller on the phone now" (the phone file shrank at the replace) from "server space"
  (the output is added at once, the original goes at the purge).

## Protocol: `/v1` additions (hoard-server 0.13.0)

All additive; written into ADR 0013 as "Video compression (hoard-server 0.13.0)", `server/api/openapi.yaml` and
`server/testdata/v1-routes.txt` in the server PR. Every route needs a device key (or the web gallery's admin session
for its `/web/` twins); a revoked key is `401` as everywhere. Reveal never calls them.

### `GET /v1/server`

`features` gains `"compress"` when the GPU check passed. The `compress` object (present whenever the server has jobs or
the feature):

```json
"compress": {"available": true, "reason": null, "gpu": "NVIDIA GeForce RTX 4080",
             "codecs": ["hevc", "av1"], "vmaf": true, "cudaDecode": true, "sessions": 1,
             "nasWrite": true, "offerAv1": false, "trashDays": 30,
             "profiles": [{"id": "hevc-cq28", "codec": "hevc", "q": 28, "default": true},
                          {"id": "hevc-cq30", "codec": "hevc", "q": 30},
                          {"id": "av1-cq34", "codec": "av1", "q": 34, "optIn": true}],
             "limits": {"maxItemsPerJob": 5000, "maxEstimate": 500, "maxBatch": 1000}}
```

### Selections

The body of an estimate or a job names what to compress, one of:

```json
{"target": "nas",    "select": {"folders": [{"folderId": "…", "path": "2024/Trips", "recursive": true}],
                                "filter": {"from": 1700000000000, "to": 1730000000000, "minBytes": 50000000}}}
{"target": "nas",    "select": {"itemIds": ["…"]}}
{"target": "phone",  "select": {"roots": [{"rootId": "…", "path": "DCIM/Camera"}], "filter": {…}}}
{"target": "phone",  "select": {"itemIds": ["…"]}}
{"target": "backup", "select": {"roots": [{"rootId": "…", "path": "DCIM"}]}}
```

`target: "nas"`: live video items with a NAS location under the write mount (an item with several NAS copies replaces
the one under the selection; the others are left and named in the answer). `target: "phone"`: video items with a
current mirror location (`onDevice`) of **the caller's physical device** (a tablet cannot start a job for a phone: the
phone must do the replace). `target: "backup"`: video items whose mirror location is no longer on its phone (any
phone). Items outside the target are answered `skip` with `not_in_target`.

### `POST /v1/compress/estimate` `{target, select, profile?, options?, cursor?}`

```json
{"items": [{"itemId": "…", "fingerprint": "v1:…", "name": "…", "durationMs": 74000, "size": 463000000,
            "decision": "encode", "estSize": 82000000, "estSaving": 0.82, "estMs": 31000,
            "warnings": ["hdr10plus", "per_frame_metadata"]},
           {"itemId": "…", "decision": "skip", "reason": "older_compression"}],
 "totals": {"items": 312, "encode": 129, "skip": {"older_compression": 180, "efficient": 2, "unreadable": 1},
            "size": 29100000000, "estSize": 5600000000, "estMs": 2100000},
 "pending": 0, "next": null}
```

At most `maxEstimate` items per page (`next` cursor). An item whose probe is not done yet is `"decision":
"pending"` and counted in `pending` (the client asks again after `Retry-After`); probes run in the background.
`warnings`: `hdr10plus`, `per_frame_metadata` (saved to the server's sidecar), `pcm_audio` (re-encoded to AAC 256k),
`av1_playback`.

### Jobs and items

| Route | Answer |
| --- | --- |
| `POST /v1/compress/jobs` `{target, select, profile, options, auto?, name?, requestId}` | `201` the job (`200` with it when `requestId` exists). The server estimates again (the preview is informative). `options`: `{"quality": {"mode": "floor" (default) \| "fixed" \| "target", "vmaf"?}, "set": "lean" (default) \| "quality"}`. `"auto": true` with a single-folder selection turns on "Compress automatically" for it (`409 conflict` reason `exists` with the job when it is on already; AV1 refused, `422`). `409 conflict` reason `busy` lists items already in an unfinished job (the rest are queued). |
| `GET /v1/compress/jobs?state=&auto=&cursor=` | jobs, newest first, with counts per state, bytes, saving, progress (items done / to do, current item's percent and ETA); `auto=true` lists the folders compressed automatically |
| `GET /v1/compress/jobs/{jobId}` | one job |
| `PATCH /v1/compress/jobs/{jobId}` `{"paused"?, "priority"?, "auto"?: false}` | the job; `"auto": false` turns the folder's switch off (queued items cancelled) |
| `GET /v1/compress/items?jobId=&state=&mine=&cursor=` | items: state, reason, sizes, q, VMAF (mean, 1 % low, mode), timings, `progress` (0–1 while encoding), `metaSidecar`, `purgeAfter`, `logAvailable` (the log itself is on the web gallery and admin page only). `mine=true`: phone items of the caller's physical device. |
| `POST /v1/compress/cancel` `{"jobId"} \| {"itemIds"}` | per item `cancelled`, or its state when too late |
| `POST /v1/compress/restore` `{"itemIds"}` (≤ `maxBatch`) | per item: NAS and backup `replacing` then `restored` (or `replaced` when restoring the output again), async; phone `replacing`, the phone does the rest; not restorable (`purged`, running): `{"error": "state"}` for that item |
| `POST /v1/compress/purge` `{"itemIds"} \| {"jobId"}` (≤ `maxBatch`) | empties the trashed file of each `replaced`/`restored` item now: `purged`, with `alsoKeptBy` (`claim`, `mirror`, `record`) when the bytes stay for another reason |
| `POST /v1/compress/lookup` `{"fingerprints": [...]}` (≤ 1000) | per fingerprint as original or output: `{"role": "original" \| "output", "itemId", "state", "jobId", "profile", "purgeAfter"?}`; the Gallery's badges and the Trash screen's labels |

### Phone

| Route | Answer |
| --- | --- |
| `GET /v1/compress/items/{id}/output` | the output, `Range` supported (`http.ServeContent`), `ETag: "<sha256>"`, `X-Hoard-Sha256`, `X-Hoard-Fingerprint`. Only for phone items of the caller's physical device in `ready`/`replacing` (else `404 not_found`). A restore downloads the original through `GET /v1/originals/{fingerprint}`. |
| `POST /v1/compress/items/{id}/replaced` `{"fingerprint", "sha256", "size", "modifiedAt"}` | `200` the item (`replaced`); `409 conflict` reason `mismatch` when they are not the output's |
| `POST /v1/compress/items/{id}/restored` `{"fingerprint", "sha256", "size", "modifiedAt"}` | `200` (`restored`), the original's |
| `POST /v1/compress/items/{id}/abandon` `{"reason"}` (`verify`, `changed`, `gone`, `refused`) | `200`; the output blob is dropped when nothing else names it (`gone`: the item continues as a backup item) |

### Items, errors, limits

- Items (`/v1/items/changes`, `GET /v1/items/{id}`, lookups) gain the optional `compressed` object on output items and
  `compressedTo` on trashed originals (with `include=trash`); a trashed item's old-client tombstone shape gains
  `replacedBy`. Clients ignore unknown fields (Hoard's models default every field; Reveal's parser ignores them).
- A `mirror` location may carry `gone: "compressed"` (clients treat unknown values as `deleted`).
- `DELETE /v1/originals/{fingerprint}`: a new `409 conflict` reason `compressed` (rule 5) until the purge.
- New codes: none. New `details.reason` values: `busy`, `exists`, `mismatch`, `state` (`409`), `compressed` (`409` on
  `DELETE /v1/originals`), `no_gpu`, `no_tools`, `no_write_mount` (`503 unavailable`). `503 storage_not_configured`
  without a backup root.
- `server/testdata/v1-routes.txt` gains the **14** routes above: `POST /v1/compress/estimate`, `POST
  /v1/compress/jobs`, `GET /v1/compress/jobs`, `GET /v1/compress/jobs/{jobId}`, `PATCH /v1/compress/jobs/{jobId}`,
  `GET /v1/compress/items`, `POST /v1/compress/cancel`, `POST /v1/compress/restore`, `POST /v1/compress/purge`, `POST
  /v1/compress/lookup`, `GET /v1/compress/items/{id}/output`, `POST /v1/compress/items/{id}/replaced`, `POST
  /v1/compress/items/{id}/restored`, `POST /v1/compress/items/{id}/abandon`. The automatic switch has no route of its
  own (a job with `auto`); the earlier draft's `confirm` is gone and `purge` takes its place.

## UI

### Hoard

- **"Compress videos"** (when the server has `compress`): on a Gallery selection with videos, in the viewer's menu on
  a video, and as **"Compress videos in this folder"** on a folder (a mapped NAS folder in Files, a backed-up phone
  folder in Settings › Backup and its Gallery folder, a phone backup in the Gallery's Devices). A sheet: what will be
  compressed (phone videos replace this phone's files; NAS videos replace the library's; backups no longer on a phone
  are compressed on the server), the profile ("Same look", "Smaller", "AV1" once offered), then the **estimate**: "129
  of 312 videos · 29.1 GB → about 5.6 GB · about 35 min", skipped by reason ("180 already compressed by an older
  tool", "2 already efficient", "1 unreadable"), warnings (HDR10+), "Per-frame camera data is saved on the server",
  and "Originals go to the trash: restore them there until it is emptied (30 days)". Start.
- **"Compress automatically"** switch on the same folder rows (off by default), with a sheet: "New videos in this
  folder will be compressed after they are backed up (or found on the server). Originals go to the trash." and "Also
  compress the N videos already there".
- **Settings › Compress videos** (shown with the feature): jobs in progress (progress line, current item, ETA, Pause,
  Cancel), folders compressed automatically (each with its switch), history (saved, in the trash, skipped, failed with
  reasons; Restore on an item).
- **Trash screen** (ADR 0023): originals of compressed videos are listed like other trashed shots, labelled
  "Original of a compressed video · saves 412 MB when emptied"; **Restore** = undo the compression (the route above).
- **Gallery badges**: "Compressed" on output items while the original is in the trash, nothing after the purge;
  "Compressing" on queued phone items.
- The hfps design system: `HfpsScaffold`, one card surface, eyebrow labels, Phosphor icons from
  `android/scripts/icons/` (an archive/arrows-in icon), progress as in Settings › Backup.

String keys (`values/strings.xml` and `values-pt-rBR/strings.xml`; plurals with `one`, `many`, `other` in pt-BR):
`compress_title`, `compress_action`, `compress_action_selection`, `compress_action_folder`, `compress_target_phone`,
`compress_target_nas`, `compress_target_backup`, `compress_profile_label`, `compress_profile_same`,
`compress_profile_same_desc`, `compress_profile_smaller`, `compress_profile_smaller_desc`, `compress_profile_av1`,
`compress_profile_av1_desc`, `compress_estimate_title`, `compress_estimate_summary` (plural),
`compress_estimate_pending`, `compress_skip_already` (plural), `compress_skip_older` (plural),
`compress_skip_efficient` (plural), `compress_skip_small` (plural), `compress_skip_unreadable` (plural),
`compress_skip_dolby_vision` (plural), `compress_skip_reveal` (plural), `compress_skip_sync_artifact` (plural),
`compress_skip_not_writable` (plural), `compress_warn_hdr10plus`, `compress_meta_saved`,
`compress_warn_other_backup_apps`, `compress_originals_to_trash`, `compress_start`, `compress_progress`, `compress_eta`,
`compress_pause`, `compress_resume`, `compress_cancel`, `compress_auto_title`, `compress_auto_switch`,
`compress_auto_desc`, `compress_auto_existing` (plural), `compress_auto_folders_title`, `compress_history_title`,
`compress_saved`, `compress_in_trash` (plural), `compress_restore`, `compress_restore_desc`,
`compress_trash_label`, `compress_trash_saves`, `compress_failed_reason_verify`, `compress_failed_reason_changed`,
`compress_failed_reason_space`, `compress_failed_reason_encoder`, `compress_failed_reason_trash`,
`compress_phone_waiting_permission`, `compress_phone_needs_all_files`, `compress_phone_needs_android11`,
`compress_badge_compressed`, `compress_badge_compressing`, `compress_unavailable_gpu`,
`compress_unavailable_write_mount`.

### Web gallery and admin page

- **Web gallery** (`server/web/`): "Compress videos" on a selection and "Compress videos in this folder" / "Compress
  automatically" in Folders (NAS folders and the Devices section's phone backups, `target: "backup"`), with the same
  estimate; a **Compression** view in the shell (jobs with progress, automatic folders, history with saved bytes and
  what is in the trash, each item's VMAF, its log and its per-frame data download, Restore, Empty now). Phone jobs are
  listed read-only. Strings in `server/web/src/i18n/locales/{en,pt-BR}.json` under `compress.*`: `compress.title`,
  `compress.action`, `compress.actionFolder`, `compress.estimate.*` (`summary`, `pending`, `skipped`, `warnings`),
  `compress.profile.*`, `compress.quality.*` (`floor`, `fixed`, `target`), `compress.auto.*` (`switch`, `desc`,
  `existing`, `list`), `compress.jobs.*` (`progress`, `eta`, `pause`, `resume`, `cancel`), `compress.trash.*`
  (`label`, `restore`, `emptyNow`, `autoEmpty`), `compress.history.*`, `compress.item.*` (`vmaf`, `log`, `meta`,
  `reason.*`), `compress.unavailable.*` (`noGpu`, `noTools`, `noWriteMount`).
- **Admin page** (`internal/server/web`): a Compression section: GPU status (detected, driver, encoders, VMAF, CUDA
  decode, last check), the write mount's status, sessions, "Pause while the GPU is busy" (on), the night window (off),
  "Offer AV1" (off until the owner's playback check), the retention of what compression trashed (`trashDays`, 30); the
  Storage tab gains originals in the trash and saved bytes.

## Security

- **The write mount is the only new power**, used by one component through `os.Root`, from database paths, with six
  operations; no live file is ever deleted: a replaced original is moved into the trash, and only a purge of a trashed
  file (the owner's Empty, or the retention he set) deletes anything, as with ADR 0023's trash.
- **Devices**: every paired device may start jobs, restore and empty (one owner, ADR 0013), as every device may empty
  the trash today; the trash's retention is the safety window. A phone job can only target the caller's own phone
  files; outputs are served only to that phone.
- **Inputs to tools**: file paths are passed as arguments after `-i`/`--` from the database, never from requests; the
  profile and options are enums; the metadata written is a constant string with the q and a date. exiftool runs with
  `-q -q -m` on files in the private work directory only.
- **Logs**: as ADR 0013; the item logs and per-frame sidecars (paths, metadata) live in the data directory and are
  readable only through the admin session.

## Build plan

Each phase is one PR with its tests, shippable alone.

| Phase | PR | Contents | Tests |
| --- | --- | --- | --- |
| S1 | hoard-server 0.13.0 | Schema 12; `internal/compress`: `Prober` (`video_probe`), `Estimator` and skip rules, `Queue` (lease, priorities, one session, pause-while-busy, the optional window, restart), an `Encoder` interface (`NvencEncoder`: per-frame sidecar, ffmpeg with mode C's re-encodes, exiftool, mtime; CUDA-decode retry), `Verifier` (ffprobe checks, metadata diff, VMAF full/spot), `Replacer` (write mount, the trash root, the replace and restore journals, purge, `trashDays`); the heir rule and `compressedTo` in `items.Syncer.rebuild`, `replacedBy` on trashed items and their old-client shape, `compressed` on items; `/v1/compress/*` for `target: "nas"` (jobs, `auto`, estimate, restore, purge, lookup); NAS "Compress automatically" after the walk; GPU detection, `features`; the `-nvidia` image and compose/README; admin page section; ADR 0013 section, `openapi.yaml`, `v1-routes.txt`. | Go with a **fake encoder** (copies a synthetic file and rewrites bytes; a slow one for cancel and restart): every state transition, restart in each state, cancel, priorities, one session, pause-while-busy (a fake NVML), the window holding automatic items only, space checks; skip rules on synthetic probe JSON (marker, `Lavc`, bpp, DV, Reveal record, `.sync-conflict-` and `.stversions` paths); estimate maths; mode C (a fake VMAF under 93 at q 28 → 26 → 24); the replace journal killed after each step and settled; hard link vs rename (`EPERM`), `EXDEV` failing cleanly (an injected linker); the trash root (an existing `.hoard-trash` in an ancestor, else the folder root; `-2` day folders); a changed source refused; the walk: original trashed with `replacedBy`, edits and culling moved, restore reversing both and restoring again, purge by `trashDays` and by route leaving a tombstone with `replacedBy`; an output copied into another NAS folder over its original following the heir rule; `GET /v1/originals` of a trashed original; automatic NAS folder (new walk items only); `os.Root` containment; Verifier with real ffmpeg on lavfi clips made with libx264/libx265 (CPU, seconds): duration, stream, rotation, HDR side data, frame-count mismatch, VMAF spot vs full; `TestRevealProtocolContract`, `TestOpensRevealSyncDataDir`, `TestUpgradesHoardServer040DataDir`, `TestMigrationsNeedNoTempDir` unchanged. **GPU integration test** (opt-in `HOARD_GPU_TEST=1`, run on the GPU host in the `-nvidia` container as a non-root uid): the recipe on synthetic 8-bit, 10-bit HLG/PQ, PCM-audio and VFR clips, the marker, the cap, HDR bit-exact, the per-frame sidecar from a synthetic data track, CUDA-decode fallback. |
| S2 | hoard-server 0.13.x | Phone and backup jobs: `target: "phone"` and `"backup"`, the NAS-referenced original ingested first, output blobs, ownership rule 5, the `compressed` mirror reason, the `kept` source and its trashed item, the transaction (route or report first), output/replaced/restored/abandon routes, restore and purge for blobs, `409 compressed` on `DELETE /v1/originals`; phone "Compress automatically" after the backup. | Go: both orders of report and `/replaced`; nothing uploaded; no removal; the trashed original listed with `include=trash`/`trashed=only`, restored, restored again, purged by route and by `trashDays`; ownership across a Reveal delete, a backup purge and a purge with another claim; free-up verify of the output; a server-only (`freed`) video compressed with its entry staying `freed` and pinned; an automatic phone folder (new stored videos only). |
| S3 | hoard-server 0.13.x | Web gallery: Compress on selection/folder, "Compress automatically", the Compression view, trash entries, per-frame download, logs; strings `en` + `pt-BR`. | `bun run lint/typecheck/test/e2e` with a synthetic library and the fake encoder (a server flag only tests can set). |
| A1 | Hoard 2.7.0 | Library and backup jobs from Hoard: `compress` in models, `compressed`/`compressedTo`, selection and folder → estimate → job, "Compress automatically", Settings › Compress videos, the Trash screen's compressed originals (Restore through the route, Empty), badges, lookup. | JVM: the sheet's model and the Trash screen's actions against a fake server, Roborazzi goldens; emulator against a local hoard-server container with the fake encoder and a temp NAS directory mounted read-only and read-write (`docs/testing/compression.md`): compress a folder, see the original in Trash, restore it, empty it. |
| A2 | Hoard 2.8.0 | Phone replace: `gallery.db` 6, `CompressPlan`, `CompressDownloader`, `CompressEngine`, `CompressWorker`, `PhoneCompress` (consent, in-place write, mtime, scan, `DATE_TAKEN`), restore to the phone, `FreeUpPlan` exclusion, the worker run after a backup pass of an automatic folder. | JVM: `CompressPlan` (every journal state after a kill, both directions), the downloader (resume, `If-Range`, short body, 404), engine against a fake server; migration 5 → 6. Emulator (debug id, local container with a real or fake encoder): synthetic camera videos in `DCIM/Camera`, backed up, compressed, replaced in place: same `_ID`, name, `DATE_TAKEN`, mtime, `IS_FAVORITE`; the process killed during `writing` and redone; the backup's next run uploads nothing and reports no removal; consent refused; restore from the Trash screen; free up after a compression; a folder with a stale `.stfolder` compressed normally and a `.sync-conflict-` file skipped. Never on the owner's phone. |

S1 ships alone (the default image keeps working without a GPU). S2 needs S1; A1 needs S1 (backup targets need S2); A2
needs S2. The owner's library can be compressed after S1 from the web gallery (S3) or Hoard (A1); the phone folder
after A2; server-only phone videos after S2.

## Consequences

- The server can now write the NAS, through a mount the owner adds, for one purpose: replacing a video with its
  verified compression and moving the original into the trash (and back). ADR 0023's "the server never writes the NAS"
  holds for everything else.
- A job saves no disk space until the trash is emptied (by hand or after 30 days); on the phone, the phone's space is
  saved at once.
- A compressed file is a new item; its old id is trashed (then a tombstone) with `replacedBy`. Clients that ignore
  `replacedBy` lose their link to the old id (none of Hoard's or Reveal's flows do).
- The `-nvidia` image is larger (glibc base, libvmaf, exiftool), runs as the library owner's uid, and must be rebuilt
  when FFmpeg's NVENC API needs a newer driver; the default image is unchanged.
- Per-frame drone and camera telemetry and some vendor boxes leave the file; their content stays in the server's data
  directory as JSON.
- PCM camera audio becomes AAC 256k: transparent for listening, not bit-exact.

## Owner's decisions (2026-11-19)

Henrique accepted this ADR with these answers; the sections above are written to match them.

1. **Quality: mode C** — cq 28, re-encoded lower only when VMAF falls under 93.
2. **PCM audio → AAC-LC 256k.**
3. **Per-frame metadata**: saved as an `exiftool -ee3 -j` JSON sidecar **in the server's data directory**, keyed by the
   output's fingerprint; nothing next to the video.
4. **AV1**: opt-in per job, only after his playback check.
5. **Syncthing is no longer used**: the `.stfolder` markers are stale and no folder is treated as synced. Only
   Syncthing's own artifacts (`.stversions`, `*.sync-conflict-*`) are never inputs, and an output that reappears in a
   NAS folder is recognised by the marker and the fingerprint pair.
6. **NAS folders**: every folder under the write mount is eligible, **on demand only** (a folder or a selection); no
   allowlist beyond the mount itself.
7. **Originals go to the trash** (ADR 0023): no `.hoard-originals`, no confirmation, no hold. Restore puts the original
   back and undoes the replace (the output to the trash, `replacedBy` reversed). Phone originals are trashed server
   items with the same retention and restore, brought back to the phone through the replace flow.
8. **Triggering**: on demand first; a per-folder **"Compress automatically"** (after backup for phone folders, after the
   walk for NAS folders), off everywhere until he turns it on; the GPU night window optional and off.
9. **Server-only (freed) phone videos** are compressed on the server too, with no phone step (phase S2).

### Defaults taken

Questions he did not answer, decided with the ADR's recommendation (each can be changed later without a protocol
change):

- **Lean option set** (`-preset p7 -tune hq`, no AQ, multipass, lookahead or b-frame options); the specified set stays
  a job option for a banding comparison.
- **Dolby Vision sources are skipped** (`dolby_vision`).
- **The `-nvidia` container runs as the library owner's uid:gid**, so replaced files and trash folders keep his
  ownership.
- **GPU sharing: one NVENC session, "Pause while the GPU is busy" on.**

## Open questions

None left. The AV1 profile stays hidden until the owner reports his playback check (admin page "Offer AV1").

## Amendment (S1)

Phase S1 (hoard-server 0.13.0) as built; each point is a detail the text above left open or a small deviation.

- **Routes.** S1 routes the ten routes of `target: "nas"` (`estimate`, `jobs` ×4, `items`, `cancel`, `restore`,
  `purge`, `lookup`); `server/testdata/v1-routes.txt` gains those ten now and the four phone routes (`items/{id}/output`,
  `…/replaced`, `…/restored`, `…/abandon`) with S2. Until then `target: "phone"` and `"backup"` answer `422
  invalid_request`, `details.reason: "target_not_supported"`, and `GET /v1/compress/items?mine=true` is empty.
- **Ids.** A compression item has a string `id` (the `compress_items` row), distinct from `itemId` (the gallery item it
  was queued for). `cancel`, `restore` and `purge` take either in `itemIds` (a gallery item id means that item's
  newest compression, as original or output), so Hoard's Trash screen can restore an item with `compressedTo` by its own
  id. `lookup` answers `id` too.
- **Job answers.** Busy videos (in an unfinished job) do not fail the job: `201` lists them in `busy`; `409 conflict`
  reason `busy` only when every video is busy, and then no job is made. Videos the rules skip are recorded as `skipped`
  rows of the job (they count in its `skipped`); videos outside the target are only answered.
- **The write mount's mapping.** `NAS_READ_ROOT` (default `/mnt/nas`) names the read-only mount that `NAS_WRITE_ROOT`
  mirrors: a folder's write path is its read path relative to `NAS_READ_ROOT`, under `NAS_WRITE_ROOT`, proven by
  `st_dev`/`st_ino`. A folder outside `NAS_READ_ROOT` is `not_writable`.
- **The heir rule's other side.** A trashed output after a restore carries `compressedTo` too (the original's live
  item), so "restore again" is offered the same way; `compressed` is on outputs in `replaced` and `purged` only. A live
  output rebuilt by the indexer rebuilds its original's item in the same transaction (and the reverse), so the link is
  made whichever side a walk sees first; a state change of a record (restore, purge) rebuilds both items at once.
- **CUDA decode.** For a source NVDEC cannot decode (H.264 4:4:4, some 4:2:2), ffmpeg itself falls back to CPU decode
  and exits 0 (it logs "Failed setup for format cuda"): the item records `decoder: "cpu"` and the log says so; the
  explicit retry without `-hwaccel cuda` stays for a run that fails.
- **Per-frame sidecar.** Made when the probe shows a timed data track other than `tmcd` (DJI `djmd`/`dbgi`, Sony `rtmd`,
  GoPro `gpmd`, …). Vendor boxes without a track (Samsung `smta`) are not seen by ffprobe: no sidecar for them; their
  content stays in the trashed original until the purge. Path: `<data dir>/compress/meta/<hex[0:2]>/<hex>.json.gz`, the
  hex being the output fingerprint without `v1:`.
- **Pause while busy.** Another process's NVENC session is judged on the latest reading (sessions beyond ours);
  utilization over 50 % counts for a minute, and only readings taken while none of our encodes ran.
- **Queue workers.** Sessions + 2 workers lease items; the GPU slot (`COMPRESS_SESSIONS`) is taken for the encode only
  (the state becomes `encoding` then) and a CPU slot (2) for the checks and VMAF, so verification overlaps the next
  encode.
- **Verification details.** HDR10 mastering display and content light level are compared from the stream's side data,
  else from its first frame's (HEVC SEI, as x265 and phones write them). VMAF spots seek both inputs (`-ss`/`-t`), the
  distorted stream shifted +5 ms in the filter graph. libvmaf's CUDA path was not measured in S1: VMAF runs on the CPU
  (`VMAF_THREADS`).
- **A purge by Hoard.** After each walk the server stats the trash path of its `replaced` and `restored` rows (through
  the read mount); a file gone makes the row `purged`.
- **Space.** A NAS replace needs the output's size + 64 MiB free on the NAS filesystem; otherwise the item stays `ready`
  with `reason: no_space` and is tried again after 10 minutes.
- **The trash day folder** gets `-2`, `-3`, … when the file's path already exists in that day folder.
- **Admin page.** Compression is its own tab (GPU status with "Check again", the write mount, sessions, the settings,
  the accounting, recent jobs and items); an item's log is served at `GET /admin/api/compress/items/{id}/log` (admin
  session only).
- **Image.** `Dockerfile.nvidia`: Debian trixie-slim; FFmpeg 9.0.2 with nv-codec-headers 13.0.19.0 (driver 570 or
  newer), libvmaf 3.2.1 with its built-in models, exiftool 13.64, all pinned by SHA-256; amd64 only. Its tools stage
  comes first so the legacy builder (no BuildKit) builds it before the Go stage. `TestMigrationsNeedNoTempDir` drops
  schema 12's tables too when it puts the database back to schema 8 (as 0.12.0 did for its own), and expects 12.
- **Per-frame sidecar's move.** The work directory (backup volume) and the data directory are separate filesystems in
  the compose layout: the sidecar is renamed when it can be, else copied (fsynced) and removed.
- **GPU integration test** (`HOARD_GPU_TEST=1 go test -run TestGPU -v .` in `server/`, 2026-11-19, the build host's RTX
  4080, driver 620, the `-nvidia` image run as a non-root uid with `--device nvidia.com/gpu=all`, read-only root,
  temporary data, backup and NAS directories, one NVENC session next to the desktop's): six synthetic 12 s 1080p clips
  (H.264 + PCM in MOV, HEVC 10-bit HLG, HEVC 10-bit PQ with mastering display and CLL, H.264 VFR at r 60 / avg ~30,
  H.264 with a `gpmd` data track, H.264 4:4:4) all `replaced` at cq 28 (VMAF 96.0–96.7, 1 % low 93.9–94.6, 305 → 56 MB),
  marker present, Main 10 and HLG/PQ kept, HDR10 metadata bit-exact, VFR packet timestamps identical, PCM → AAC, data
  tracks dropped, the cap following r ÷ avg, the sidecar in `<data>/compress/meta`, the 4:4:4 clip decoded on the CPU;
  then the walk (original trashed with `compressedTo`/`replacedBy`, output with `compressed`), a restore, a purge and
  the walk again. Encode 41.5 s for 72 s of video (1.7× realtime with these synthetic clips), verification 145 s on 4
  CPUs (full-clip VMAF: the long pole); the image is 290 MB uncompressed.
- **Review of the journals (PR #71).** From the trash step on, a replace or a restore never leaves `replacing` without
  the files being looked at: an I/O error or a missing write mount keeps the row in its step with its trash path
  (`reason: not_writable`), tried again every minute by the housekeeping and at the next start. The file at the path is
  checked again right before the trash link and right before the rename over it (the trash entry must be the path's own
  inode, or the path empty after a rename): a newer version written meanwhile fails the item `source_changed` and is
  never trashed under the original's record nor overwritten; our own move is undone when that is safe (our link
  removed, or our rename put back). A purge claims the row (`replacing`, step `purge:<state>`) like a restore, so the
  two exclude each other, and refuses (`output_missing`) when the live side is gone from its path with no other live
  NAS copy. After a walk, a trashed file is taken as emptied only when the item's path still exists (an unmounted share
  is an empty directory).
- **Metadata and decode gates.** The exiftool restore, and the per-frame sidecar when the source has a data track, are
  gates (`verify_metadata`); so is the loss of the capture time, the place, the make or model or the rotation in the
  metadata diff. Every output is decoded in full (`ffmpeg -xerror … -f null`, `verify_decode`); each audio and video
  stream keeps its own duration (0.1 s, audio 0.15 s); an unknown duration fails. The `-nvidia` image gains the
  `pcm_s16le` encoder for that decode. GPU test re-run after these changes: six clips replaced (two with capture time
  and place, kept), 72 s of video in 85 s wall.
- **Smaller fixes.** Trash folders get the mode of the folder that holds them (the server's umask is private); the
  replaced file keeps the original's group when the server's user may set it; directories are fsynced after each link,
  rename and unlink; the purge guard checks path segments; both mount paths are resolved through symlinks; a restore
  request never blocks on a full queue; selections never pick a copy inside a `.hoard-*` folder.
- **Re-review (PR #71, second pass).**
  - *A purge's live-copy check* counts only live, indexed NAS files outside any trash or `.hoard-*` folder: an output
    trashed by hand is no live copy, so the original stays.
  - *Waiting journals are visible and their trash entries held.* `compress_items` gains `stalled_at` (added to the
    unreleased `0012_compress.sql`): when a journal first waits. `GET /v1/server`'s `compress` object, the setup page's
    Compression tab and its Status errors show `attention: {stalled, stalledSince, untrackedTrash}`. Every trash entry
    an unfinished journal holds (a `replacing` row's `trash_path`, and the file a restore moved, named in its step) is
    served as `"held": true` on its item's trash location. **Contract for clients:** Hoard's Empty and auto-empty
    must leave held entries (Hoard 2.7.0, phase A1, implements it); the server's own purge and `trashDays` only ever act
    on `replaced`/`restored` rows, never on a held entry. **Deployment rule:** add the write mount only once every
    device runs a Hoard that honours `held` (2.7.0+): Hoard 2.6.x auto-empties `.hoard-trash` over SMB without it.
  - *The feature needs the write mount.* `features` lists `compress`, and `compress.available` is true, only with a
    working GPU **and** a usable write mount: present, the same directory as `/mnt/nas`, writable for the server's user,
    and with stable inode numbers. A folder's first replace links (or renames) a probe file in its `.hoard-trash` and
    checks that every name reports the same inode; when it does not (CIFS without `serverino`, FUSE), the item fails
    `not_writable` and the feature is off (`reason: unstable_inode`) until "Check again". `compress.reason` gains
    `no_write_mount`, `write_mount_mismatch`, `write_mount_read_only`, `unstable_inode`.
  - *Nothing moved, nothing waits.* Without the write mount, a purge, a restore at its check step and a replace at
    its check or copy step go back to where they were (`replaced`/`restored` with `reason: not_writable`, or `ready`
    tried again a minute later); only a journal that already moved a file waits.
  - *The full-decode gate* has a timeout that scales with the duration (as VMAF's) and applies `-xerror` to the video;
    an audio decode error fails the item only when the source's audio decodes cleanly (a copied track keeps a camera's
    damaged last AAC frame).
  - *Smaller.* An original a failed replace leaves in the trash (another file took its path) keeps its `trash_path` on
    the failed row, is logged loudly and counted as `untrackedTrash`; a purge whose journal the housekeeping runs at that
    moment answers `busy`; the background probe never caches "unreadable" for a file that vanished during a rename; the
    store's clock is safe for concurrent use (tests).

## Amendment (S2)

Phase S2 (phone and backup targets, folded into hoard-server 0.13.0 since S1 had not shipped); each point is a detail
the text above left open or a small deviation.

- **Routes.** The four phone routes are routed as `/v1/compress/items/{compressItemId}/output`, `…/replaced`,
  `…/restored` and `…/abandon` (the path parameter is named so because the OpenAPI contract test maps a route's `{id}`
  to the uploads' `{uploadId}`); `server/testdata/v1-routes.txt` now has the 14 routes. `target: "phone"` and
  `"backup"` are served; `422 target_not_supported` is gone.
- **Selections.** A root's `path` is a path on the phone's volume inside the root (`""` = the whole root), checked like
  a report's paths. A phone target takes the caller's **current** files (`on_device = 1`, no removal); a backup target
  the files no longer on their phone (`freed` or `deleted`), any phone. One entry per item (the first by volume and
  path). Priorities: 1 phone, 2 backup (and nas), 3 automatic.
- **When the original is ingested.** A NAS-referenced original is copied into the store (ADR 0024's checked NAS copy,
  the same code as "Keep a copy of NAS files") after the skip rules and before the encode, so skipped videos are never
  copied; the encode then reads the blob. The output's mtime is the phone's mtime of the original.
- **The output blob** is moved into the store by a hard link (else a rename) from the work directory (both on the
  backup root's filesystem), under the content's lock; its `blobs` and `originals` rows (no device, no claim) are
  written in one transaction with the item's `ready`. Until the phone has it, it is no gallery item: blobs a blob
  compression holds (the output while `ready`, both sides while `replacing`, the trashed side while `replaced` or
  `restored`) make no `backup` item source.
- **Phone states.** A phone item stays `ready` until the phone reports (no route reports "writing"); `replacing` is a
  phone restore (`direction: "restore"`, journal step `restore:phone`) or a restore again by the phone (`direction:
  "compress"`, `reswap:phone`). `…/replaced` and `…/restored` accept `modifiedAt` within 2 s of the original's phone
  mtime (FAT keeps even seconds); the same report again answers `200` with the item as it is. A phone item whose file
  left the phone before the phone replaced it (freed or deleted meanwhile) is swapped by the server, as a backup item.
- **The swap** (`store.CompressSwap`, one transaction): the entry holding the path becomes `compressed` (`on_device = 0`,
  `pinned = 1`, its link moves to `.hoard-mirror/compressed/<tree path>~<sha8>`); the other side takes the path: a new
  entry the first time (its `verified_sha256` taken from the blob's last re-hash, if any), else the kept entry again.
  With a phone report the new owner is on the phone; without one (server-only) it keeps the old owner's `on_device`,
  `removed_reason` and `pinned` (a freed video stays freed and pinned). A report whose file at the path is the other
  side of a `ready` (or `replacing`) blob item completes the swap the same way (`stored`, nothing uploaded, no
  `replaced` previous version); a removal is never inferred from it.
- **Restore** of a phone item is the phone's when the file at the path is on the phone (`replacing`); otherwise (a
  backup item, or the phone no longer has the file) the server swaps at once and the answer is `restored` (or
  `replaced` for a restore again), not `replacing`.
- **Abandon.** `gone` finishes the item on the server (a `ready` item is swapped, a phone restore done there);
  `verify` fails it with reason `verify_phone`, `changed` with `source_changed`; `refused` cancels it (reason
  `refused`); the output blob is dropped when nothing else keeps it. A phone restore given up on (other than `gone`)
  goes back to `replaced` (a restore again to `restored`) with the reason. A cancelled phone item drops its output too.
- **The kept source** is one item source per kept entry (`source = 'kept'`, `folder_id` = the root, key = its
  fingerprint), served as its `mirror` location (`onDevice: false`, `gone: "compressed"`) and its `backup` location.
  It joins a live NAS or phone item that has the file (a phone video that was a NAS reference: the NAS copy keeps the
  item live, and the phone's output is an item of its own), else it is an item of its own, trashed (every source a
  `trash` or `kept`), with `compressedTo`/`replacedBy` by the heir rule.
- **Ownership rule 5** counts blob compressions only (`source = 'blob'`): a NAS replace's original is a NAS file in the
  trash, which ADR 0023 governs. `DELETE /v1/originals` answering `409 compressed` releases the caller's claim, as
  `mirror` does, so the bytes go at the purge. A purge's `alsoKeptBy` may also say `compress` (another compression
  holds the bytes). The admin's backup purge removes a phone's kept entries with the rest (the trashed item leaves the
  trash), but the blobs of a compression stay until its own purge (by route or `trashDays`).
- **Automatic phone folders.** `"auto": true` with one root: target phone (the caller's root) appends, after each items
  pass of the backup (the indexer's `AfterBackup`), the videos whose entries were created since the switch and are
  stored (`blob` or `nas`) and on the phone; a replace's output is never appended (its fingerprint is a record's).
  Target backup (the web gallery's switch on a phone backup) appends the videos that left their phone (freed or
  deleted) since the switch: a video still on the phone is never compressed on the server alone.
- **Items in the answers.** Compression items gain `target` (from the job) and `source`; phone and backup items add
  `rootId`, `volume`, `path` (on the phone), `physicalDeviceId`, `modifiedAt`, `sha256` (the original's), `outSha256`
  and, while a phone writes, `direction`. Estimate rows of phone and backup targets carry `rootId`, `volume` and the
  phone path instead of `folderId`.
- **Tests** (Go, fake encoder): `TestCompressPhoneFlow` (estimate and job of the caller's phone, the output served by
  range to that phone only, mismatches, `…/replaced` first, the next report uploading and removing nothing, free-up
  verify of the output, the trashed original with `include=trash` and `trashed=only`, culling moved, restore and
  restore again by the phone, purge by route), `TestCompressPhoneReportFirst` (the report first, for a replace and a
  restore), `TestCompressPhoneOwnership` (a Reveal delete, a purge with another claim, a backup purge then the purge by
  `trashDays`), `TestCompressBackupFreed` (server-only, the entry stays freed and pinned, restore and restore again on
  the server, purge by `trashDays`), `TestCompressAutomaticPhoneFolder` (new stored videos only, not outputs; the
  backup target's freed videos), `TestCompressPhoneNasReferenceAndAbandon` (the NAS copy ingested first; abandon
  verify, gone, refused; cancel). The encoder path did not change, so the GPU test was not re-run.
