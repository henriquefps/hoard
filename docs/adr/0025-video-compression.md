# ADR 0025: Video compression on hoard-server

- **Date**: 2026-11-19
- **Status**: Proposed (Hoard phase 5; hoard-server 0.13.0, Hoard 2.7.0–2.8.0)
- **Builds on**: ADR 0012 (compatibility promise), 0013 (protocol, errors, logs), 0014 (items, identity by the main
  file's fingerprint, `replacedBy`), 0015 (previews: the queue, `Generator`, "GPU worker"), 0016 (culling follows
  `replacedBy`), 0017 (physical devices), 0018 (web gallery), 0020 (edits move with `replacedBy`), 0021 (Gallery,
  `gallery.db`), 0022 (SMB ↔ NAS mappings), 0023 (trash: `.hoard-trash`, the walk, "the server never writes the NAS"),
  0024 (phone backup: the mirror, blobs, ownership, verify, free up, `freed`, pinned entries).
- **Amends, when accepted**: ADR 0013 (additions under `/v1`, "Video compression (hoard-server 0.13.0)"), ADR 0014
  (an heir rule for compressed files; items gain `compressed`), ADR 0023 ("the server never writes the NAS" gains one
  exception, opt-in per folder), ADR 0024 (a removal reason `compressed`; a fifth ownership rule), ADR 0021
  (`gallery.db` version 6).
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
| Image | A second image variant, **`hoard-server:<version>-nvidia`** (Debian slim, glibc; the same binary and tools plus an FFmpeg with NVENC/NVDEC/CUDA and libvmaf, and exiftool). The Alpine image stays the default and never compresses (musl cannot load the driver's libraries). |
| Recipe | HEVC NVENC, **lean** options (`-preset p7 -tune hq`, no multipass/lookahead/b-frame/AQ options), **cq 28** default, `-maxrate` = source video bitrate × max(1, r_frame_rate ÷ avg_frame_rate) and `-bufsize` 2× that, `-hwaccel cuda` with a CPU-decode retry, `-fps_mode passthrough -enc_time_base:v demux`, `-noautorotate`, 10-bit stays Main 10 with HDR10 static metadata, audio copied (PCM → AAC 256k, owner's option), `-map -0:d`, marker `dev.hfps.hoard.compressed`, then exiftool and mtime. This deviates from the owner's draft option set; why and the numbers in "The recipe". |
| Skip | Before encoding: the marker; an ffmpeg-made HEVC/AV1 stream (`Lavc…` encoder tag: the old script); HEVC/AV1 under 0.15 bits per pixel; an estimated saving under 20 %. After encoding: a real saving under 20 % discards the output. Dolby Vision, motion photos and items Reveal has records of are skipped too. |
| Quality | Fixed cq 28 is the default (= the approved look). The owner picks between A (fixed), B (per-clip search to VMAF 95) and C (cq 28, lowered only when VMAF falls under a floor): "Options for the owner", recommendation **C with floor 93**. |
| Verify | Before anything replaces anything: duration within 0.1 s, the same streams (data streams excepted) and resolution and frame rate, decoded frame count when the header counts differ, an ffprobe + exiftool metadata diff in the item's log, VMAF (full clip up to 30 s, 3 × 2 s spots above), real saving ≥ 20 %. |
| Job model | `compress_jobs` + `compress_items` (schema 12). One GPU queue, priorities, `COMPRESS_SESSIONS` (default 1) NVENC sessions, resumable after a restart (running steps go back to queued; the replace is a journal), cancellable, a log file per item. |
| NAS library | **The server replaces the file itself** through a second, opt-in **read-write mount** (`/mnt/nas-write`), only in NAS folders the admin enabled. The original is **hard-linked** into a hidden `.hoard-originals/` on the same filesystem (by default **one at the mount root**, mirroring paths, so nothing new appears inside the owner's photo folders; per NAS folder is the alternative: owner's option 5) and the verified output is **renamed** over the path: atomic, no copy of the original, the path never missing. The walk sees a new fingerprint at the same path; the old item becomes a tombstone with `replacedBy` the new one (a new heir rule reads the compression record), so edits, culling and ratings follow. |
| Phone videos | The server compresses the backed-up original (ADR 0024) into a **new blob**; Hoard downloads it by range, verifies it, and **writes it over the phone's file in place** (same MediaStore row, name, folder, `DATE_TAKEN`, mtime) under a redo journal. The mirror keeps the original as an entry with removal reason `compressed`, pinned, until the owner confirms; the backup never uploads the output (it is already in the store) and never reports a deletion. Android 11+ with All files access. |
| Originals | Kept until the owner confirms, per item or per job; no automatic deletion by default. A confirmation from a device (Hoard) deletes after a **7-day hold** the admin page can cancel; the web gallery's confirmation (admin session) can delete at once. "Restore original" undoes a replaced item. |
| Synced folders | Folders that Syncthing manages (a `.stfolder` or `.stignore` in the folder or an ancestor), detected by Hoard on the phone and by the NAS walk and stored per root and folder, are **skipped by default** (`synced_folder`). A per-folder opt-in, which explains that the compressed file will replace the peer's copy, allows them. A compressed file that arrives in a NAS folder by sync is recognised by the marker and the `compress_items` fingerprint pair: its item follows through `replacedBy`, and the original, kept in the blob store, is not treated as deleted. |
| Files in the owner's folders | Nothing new inside his photo folders by default: kept originals under one `.hoard-originals/` at the mount root, the per-frame metadata sidecar (option 3a) in the server's data directory. Both are owner's options (5, 6). |
| Marker | Keys `dev.hfps.hoard.compressed` = `v1, <codec>-cq<q>, <yyyy-MM-dd>` in the file, and the server record `compress_items` keyed by both fingerprints (original and output). |
| Protocol | Additive `/v1` routes under `/v1/compress/…`, `features: "compress"`, a `compress` object in `GET /v1/server`, items gain an optional `compressed` field. Reveal is not affected. |
| UI | Hoard: Gallery selection and Settings › "Compress videos" (estimate preview, progress, ready to confirm, history). Web gallery: the same on a selection or a folder, and a Compression view. Admin page: GPU status, sessions, writable folders, the hold. |

## The recipe

### Default profile `hevc-cq28`

Per file, with `MAXR`, `PIX` and `AUD` from the source's ffprobe (`-select_streams V:0`):

```sh
# MAXR = source video bit_rate × max(1, r_frame_rate / avg_frame_rate)            (b/s)
# PIX  = "-pix_fmt p010le -profile:v:0 main10" for a 10-bit source, else "-pix_fmt yuv420p -profile:v:0 main"
# AUD  = "-c:a aac -b:a 256k" when the audio is pcm_* and the owner chose it, else nothing (audio copied)
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
source's container (`.mp4`/`.mov`, by the source's extension).

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
  **HDR10+ dynamic metadata (SMPTE 2094-40) cannot survive** a re-encode: the estimate flags the item
  (`hdr10plus`) and the UI says so before the job starts. **Dolby Vision** (`media.Video.HDR = "dolby-vision"`) loses
  its RPU and becomes its base layer: skipped by default (`dolby_vision`), see "Open questions".
- **Audio** is copied, except PCM (the ZV-E10's `twos`): copied into MP4 it becomes ISO `ipcm` at 1536 kb/s (14–16 % of
  a q28 output) whose Android playback is unverified. AAC-LC 256k takes it to about 2.5 % of the output. Owner's
  option 2; the recommendation is AAC 256k.
- **`-map -0:d`** drops the timed data tracks MP4 cannot carry (DJI `djmd`/`dbgi`, Sony `rtmd`: the mp4 muxer refuses
  them, `-f mov` writes unreadable samples); `tmcd` is regenerated from the timecode tag. What is lost for good is in
  "Metadata" and owner's option 3.
- **`-b:v 0`** changes nothing (byte-identical outputs with and without); it stays as documentation that the target is
  cq only.

### Why not the draft option set

The owner's draft adds `-multipass fullres -rc-lookahead 32 -spatial-aq 1 -temporal-aq 1 -bf 4 -b_ref_mode middle`.
NVENC accepts every one of them on the RTX 4080; measured:

| Option | Effect at equal VMAF or equal cq |
| --- | --- |
| `-spatial-aq 1` | about **+17 % size and −1 VMAF** at the same cq (2.7K30: 17.55 MB / 94.31 with it, 15.06 MB / 95.40 without; 1080p60: 23.20 / 95.87 vs 22.81 / 96.83). AQ fights banding in flat areas, which VMAF under-rates; the approved old look has no AQ. |
| `-temporal-aq 1` | no measurable effect (±0.05 % size, ±0.01 VMAF) |
| `-multipass fullres -rc-lookahead 32 -bf 4 -b_ref_mode middle` (with `-tune hq`) | **0.5–4.7 % more bytes** than lean for the same VMAF (BD-rate, lean vs specified: −3.3, −0.5, −4.7, −4.7 %), and 4–22 % slower. Lean scores ≥ the specified set at the same cq and equals the old recipe on 8-bit sources. |

So the default is **lean**. The specified set stays reachable as the profile option `"options": "quality"` for a
later playback or banding comparison (open question 5), without AQ.

### Profiles

| Profile | Codec, q | Use |
| --- | --- | --- |
| `hevc-cq28` (default, "Same look") | HEVC, cq 28 | Within ±0.3 VMAF and ±5 % size of the old script's output on every camera original, with 10-bit, rotation, audio and metadata kept. |
| `hevc-cq30` ("Smaller") | HEVC, cq 30 | About −1 to −1.5 VMAF for another 15–25 % of size. |
| `av1-cq34` ("AV1", opt-in) | `av1_nvenc`, cq 34 (≈ HEVC cq + 6) | 14–22 % fewer bytes than HEVC at equal VMAF, the same speed (decode-bound). Plays only where the device decodes AV1 in hardware: offered only after the owner's playback check (owner's option 4); the marker then reads `av1-cq34`. |

Each job names one profile. Quality mode C (below) may lower the q of one item; the marker and the record carry the q
actually used.

### Expected results (calibration, final recipe)

| Class | Saving at cq 28 | VMAF (mean / 1 % low) |
| --- | --- | --- |
| 2.7K30 drone, HEVC 10-bit | 82–91 % | 93.2–96.4 / 89–94 |
| 2.7K60 drone, high motion | 56 % | 90.8 / 83.3 (cq 26: 43 %, 93.5) |
| 2.7K Pocket (H.264, HEVC 10-bit) | 76–80 % | 97.0–98.4 |
| 4K phone, HEVC | 61 % | 99.8 (4K model) |
| 1080p phone, HEVC | 72 % | 95.9 |
| 1080p60 camera, H.264 (PCM copied) | 79–81 % | 96.8–98.8 |
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
| — | HDR `dolby-vision` | `dolby_vision` | Its RPU cannot be kept (default; owner may allow). |
| — | a live Reveal `photo` or `version` record names a member's fingerprint (`originalNamed`) | `reveal_record` | Reveal has no rule for a replaced video; the item stays as it is. |
| — | under a Syncthing-managed directory, unless opted in; a `.sync-conflict-` sibling | `synced_folder`, `sync_conflict` | A replace propagates to every peer ("Synced folders"). |
| — | trashed (ADR 0023), in a `.hoard-*` folder, or already in an unfinished job | `trashed`, `busy` | |
| 5 | after encoding: **real saving < 20 %** (output file vs source file) | `small_saving` (state `skipped`, output discarded) | Catches estimate errors such as high-motion 60 fps (estimated 80 %, real 56 %, still kept) and anything rule 3 misses. |

**Estimate.** Predicted video bytes = bpp_ref(q) × width × height × avg fps × duration ÷ 8, with bpp_ref(28) = 0.067,
bpp_ref(30) = 0.049 (the camera originals' median output) and AV1 at 0.82 × HEVC's; plus the audio as it will be (copied
bytes, or 256 kb/s for PCM), plus 0.3 % container. Mean absolute error on camera originals was 5.3 points (4.1 at cq
30); it is blind to generation (old outputs over-predicted by 31–45 points: rules 1–3 take those out) and over-predicts
high-motion 60 fps by about 20 points (rule 5). The UI says "about". Time = duration ÷ the class's speed (2.5×
realtime for HEVC 10-bit sources, 2.6× for H.264, 1.1× for 4K HEVC; corrected by the server's own measured average per
class once it has 10 items of a class) + the verification time.

On the owner's phone folder this skips the 550 old outputs (rule 2) and the broken file, encodes the 129 originals, and
decides the 50 app re-encodes one by one (the calibration's H.264 Baseline export saved 86 %).

## Quality and verification

### Quality modes

- **A, fixed**: every item at the profile's q (28).
- **B, per-clip search to VMAF 95**: encode at 28, measure, step ±2 until the highest q with VMAF ≥ 95. One q cannot
  meet 95 everywhere: within one class (2.7K30 drone) the clips need q 25, 28 and 31, and the 2.7K60 clip needs q 24 for
  a 27 % saving. Costs 2–3 encodes per item.
- **C, cq 28 with a floor**: encode at 28, measure; only when VMAF < the floor (proposed **93**) re-encode at q − 2,
  then q − 4 at most, and keep the first that reaches it. In the calibration only the 2.7K60 clip falls under (90.8 →
  q 26: 93.5 at a 43 % saving); everything else keeps the approved look at one encode.

Recommended: **C, floor 93** ("Options for the owner", 1). The floor is a job setting (`quality: {"mode": "floor",
"vmaf": 93}`); A is `{"mode": "fixed"}`, B `{"mode": "target", "vmaf": 95}`.

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

A failing check fails the item (`failed`, reason `verify_<check>`), keeps the original untouched and the output is
deleted. Every mode has a floor (A and C: 93 by default; B: its target): a final VMAF under the floor − 1 fails the
item as `verify_quality` (under A, the 2.7K60 clip at 90.8 would keep its original). VMAF runs on the CPU
(`VMAF_THREADS`, default 4): libvmaf's CUDA path (`libvmaf_cuda`) is measured in phase S1 and used if it matches the
CPU scores within 0.2.

### Metadata

What ffmpeg keeps or exiftool restores (QuickTime create/modify dates, track dates, rotation, colour, HDR10 static
metadata, HLG tags, GPS in `©xyz` and Keys `location`, Samsung's Author and capture fps, DJI cover art, preview and
thumbnail images, `©too`, `Xtra`, `fsid`) is kept. **Lost for good** (no tool writes them back into a re-encoded MP4):

- DJI: the `djmd` per-frame track (model, serial, ISO, shutter, colour temperature, frame number), the `dbgi` debug
  track (1.2–2.5 Mb/s of the source), the `btec` beauty-filter tags, `©uid`, the Pocket 3 `dji` udta atom;
- Sony XAVC S: the `rtmd` per-frame track (exposure, aperture, gain, white balance, time), the NRTM XML (device, serial,
  creation date, timecode changes, recording mode, UMID), the `PROF` and `MTDT` uuid boxes;
- Samsung: `smta` (model, `saut`, `sthm`), `smrd`, `cami`, Keys `AndroidVideoTemporalLayersCount`;
- every source: `HandlerVendorID` and the old codec's configuration record.

The old script loses all of this and more. What Hoard's and the web gallery's info panels show today comes from
`file_meta` (capture time, make/model for photos, duration, codec, fps, bit depth, HDR, rotation, audio): after a
compression they show the new codec and the same everything else; make and model of videos were never read, so
nothing visible disappears. Owner's option 3 decides whether the per-frame data is saved as a sidecar, and option 6 where (by default not next to
the output: that would be a new file inside his folders).

## The job model

### Tables (schema 12, `0012_compress.sql`)

```sql
CREATE TABLE compress_jobs (
    id           TEXT PRIMARY KEY,          -- UUID
    target       TEXT NOT NULL,             -- 'nas' (replace library files) | 'phone' (the caller's phone files)
    physical_id  TEXT,                      -- phone jobs: the physical device whose files are replaced
    created_by   TEXT NOT NULL,             -- device id, or 'admin' (web gallery session)
    name         TEXT NOT NULL DEFAULT '',
    selection    TEXT NOT NULL,             -- JSON, as sent (folders, filters, items), for the history
    profile      TEXT NOT NULL,             -- 'hevc-cq28' | 'hevc-cq30' | 'av1-cq34'
    options      TEXT NOT NULL,             -- JSON: quality mode, pcm audio, per-frame sidecar, allow flags
    priority     INTEGER NOT NULL,          -- 1 phone, 2 library (0 is reserved for an item a client waits for)
    state        TEXT NOT NULL,             -- active | paused | cancelled | done
    created_at   INTEGER NOT NULL,
    finished_at  INTEGER,
    request_id   TEXT UNIQUE                -- the client's idempotency key
) STRICT;

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
    mirror_entry_id INTEGER,                -- phone: the mirror entry of the file on the phone
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
    replace_step    TEXT NOT NULL DEFAULT '',   -- the NAS replace journal (below)
    original_path   TEXT,                   -- nas: where the original is kept, relative to the folder
    replaced_at INTEGER, confirmed_at INTEGER, delete_after INTEGER, deleted_at INTEGER, restored_at INTEGER,
    updated_at      INTEGER NOT NULL
) STRICT;
CREATE INDEX compress_items_queue ON compress_items (priority, order_key DESC) WHERE state = 'queued';
CREATE INDEX compress_items_job ON compress_items (job_id, state);
CREATE INDEX compress_items_fp ON compress_items (fingerprint);
CREATE INDEX compress_items_out ON compress_items (out_fingerprint) WHERE out_fingerprint IS NOT NULL;
CREATE UNIQUE INDEX compress_items_active ON compress_items (fingerprint)
    WHERE state NOT IN ('skipped', 'failed', 'cancelled', 'confirmed', 'restored');

CREATE TABLE video_probe (fingerprint TEXT PRIMARY KEY, version INTEGER NOT NULL, data TEXT NOT NULL) STRICT;

CREATE TABLE synced_dirs (              -- Syncthing-managed directories ("Synced folders")
    scope    TEXT NOT NULL,             -- 'mirror' (a phone root) | 'nas' (a NAS folder)
    owner_id TEXT NOT NULL,             -- root id or NAS folder id
    path     TEXT NOT NULL,             -- relative to the volume (mirror) or the folder (nas); '' = all of it
    tool     TEXT NOT NULL,             -- 'syncthing'
    seen_at  INTEGER NOT NULL,
    PRIMARY KEY (scope, owner_id, path)) STRICT, WITHOUT ROWID;

CREATE TABLE compress_propagations (    -- an output that arrived in a NAS folder by sync
    item_id   INTEGER NOT NULL REFERENCES compress_items (id),
    folder_id TEXT NOT NULL,
    rel_path  TEXT NOT NULL,
    seen_at   INTEGER NOT NULL,
    PRIMARY KEY (item_id, folder_id, rel_path)) STRICT, WITHOUT ROWID;
```

Additive only (no existing table changes meaning; `server/testdata/compat/` keeps opening; no index on an existing
table, so `TestMigrationsNeedNoTempDir` holds). ADR 0024's `mirror_entries.removed_reason` gains the value
`compressed` (a TEXT column without a CHECK: no table change; the partial unique index `mirror_entries_path` does not
list it, so a compressed entry never owns its path). Settings stay rows of `settings`.

### States

```
queued → estimating → skipped
                    → encoding → verifying → failed | skipped (rule 5)
                                           → ready → replacing → replaced → confirmed → (deleted)
                                                                          → restoring → restored
any state before replacing → cancelled
```

| State | Meaning |
| --- | --- |
| `queued` | waiting for the GPU queue |
| `estimating` | probe and rules (CPU, short) |
| `skipped` | a rule (or the post-encode guard) kept the original; `reason` says which |
| `encoding`, `verifying` | GPU encode, then exiftool, mtime and the checks; the output lives in the work directory |
| `failed` | `reason` (`encoder`, `verify_*`, `source_changed`, `no_space`, `unreadable`); the original untouched; retried up to 3 times for transient reasons (`encoder`, `no_space`) with back-off |
| `ready` | a verified output exists. NAS: waiting for its replace (immediate unless the folder is busy). Phone: an output blob waiting for the phone |
| `replacing` | NAS: the journal below is running. Phone: the phone reported it is writing |
| **`replaced`** (shown as **"Ready to confirm"**) | the output is the file in place; the original is kept |
| `confirmed` | the owner confirmed; the original is deleted at `delete_after` (then `deleted_at`) |
| `restoring`, `restored` | the owner chose "Restore original": the original is put back, the output deleted |
| `cancelled` | the job or item was cancelled before `replacing` |

### Queue, priorities, sessions

- **One GPU queue** (`compress.Queue`), separate from the previews queue (whose lanes stay CPU; a later NVENC proxy
  generator, ADR 0015 "GPU worker", would lease from this queue's session budget). Items are leased like previews
  (`LeasePreview`): the most urgent `queued` row, by `priority` (0: an item a client is waiting on, e.g. "Compress now"
  on one phone video; 1: phone jobs; 2: library jobs), then `order_key`, then id.
- **Sessions**: `COMPRESS_SESSIONS` (default **1**, at most 3) encodes at a time. Consumer NVIDIA drivers allow 8 NVENC
  sessions, but encoding is decode-bound and the GPU is shared with the owner's games and streaming; two sessions give
  little on one NVDEC. Estimating, the exiftool step and VMAF run on the CPU in their own small pool (2), overlapping
  the next encode.
- **Yield** (setting "Pause while the GPU is busy", on): before leasing, the server reads NVML
  (`nvidia-smi --query-gpu=utilization.gpu,encoder.stats.sessionCount --format=csv`; the `utility` driver capability)
  and starts nothing while another process holds an NVENC session or the GPU was over 50 % busy in the last minute
  (checked every 30 s). A running encode finishes (minutes). Optional **window** (setting, e.g. 01:00–08:00, off by
  default): outside it, nothing new starts.
- **Space**: before an encode, the work directory's filesystem needs the estimated output × 1.5 + 1 GiB free, and for a
  NAS replace the NAS filesystem needs the output's size (the original stays until confirmed). Otherwise the item waits
  (`not_before` + 10 min, `reason: no_space` shown) and the job's status says so.
- **Work directory**: `<backup root>/.hoard-compress/work/<item id>/` (the backup root's filesystem is the large one,
  and the phone outputs then reach the blob store by rename). A dot name cannot collide with a device folder (ADR 0024
  forbids dot names there). Without a backup root, `POST /v1/compress/jobs` answers `503 storage_not_configured`.

### Restart, cancellation, logs

- **Restart**: at start, `estimating`/`encoding`/`verifying` rows go back to `queued` (`attempts` unchanged; a run killed
  by a restart is not a failure) and their work directories are removed. A `replacing` row is settled from its journal
  (NAS) or left to the phone (phone). `ready` outputs stay (the work file is checked by size and SHA-256 before use;
  missing or different → back to `queued`).
- **Cancel** a job: every `queued`/`estimating`/`ready` item becomes `cancelled`, running ffmpeg/VMAF processes are
  killed through their context, their work files removed. Items already `replaced` are not undone (they keep "Ready to
  confirm"; "Restore original" is the undo). **Pause** stops leasing the job's items; running ones finish. Single items
  can be cancelled the same way.
- **Logs**: one file per item, `<data dir>/compress/logs/<job id>/<item id>.log`: the command lines (paths replaced by
  `SRC`/`OUT`), ffmpeg's stderr (last 64 KiB), the check table, the metadata diff, VMAF per spot, timings. Kept 90 days
  after the job is done. The server's own log (`slog`) carries ids, states, reasons and timings, never paths, names or
  metadata values (ADR 0013 "Logs").

## GPU, image and compose

- **Image**: `ghcr.io/henriquefps/hoard-server:<version>-nvidia`, built by `server-release.yml` next to the default. Base
  `debian:<stable>-slim` (glibc: the NVIDIA container toolkit injects the host driver's `libcuda.so.1` and
  `libnvidia-encode.so.1`, which are glibc libraries; Alpine's musl cannot load them). The same Go binary and preview
  tools (libvips, libheif, LibRaw, FFmpeg), with FFmpeg configured `--enable-ffnvcodec --enable-nvenc --enable-nvdec
  --enable-cuvid --enable-libvmaf` (nv-codec-headers pinned by SHA-256 like every tarball in the Dockerfile; NVENC is
  loaded at run time, nothing NVIDIA is shipped in the image), plus `exiftool` (Perl, pinned tarball) and the two VMAF
  models. Non-root uid 65532 as before.
- **Compose** (the example gains a commented block, `server/README.md` a "GPU compression" section):

  ```yaml
  services:
    hoard-server:
      image: ghcr.io/henriquefps/hoard-server:latest-nvidia
      # GPU through the NVIDIA container toolkit (CDI); "video" is required for NVENC/NVDEC.
      devices:
        - nvidia.com/gpu=all
      environment:
        - NVIDIA_DRIVER_CAPABILITIES=compute,video,utility
        - COMPRESS_SESSIONS=1
      volumes:
        - ${NAS_DIR:-/srv/photos}:/mnt/nas:ro                  # indexed read-only, as before
        - ${NAS_DIR:-/srv/photos}:/mnt/nas-write               # optional: only for "replace in this folder"
      cpus: "4"            # VMAF and exiftool run on the CPU
      mem_limit: 2g
      tmpfs:
        - /tmp
  ```

  `runtime: nvidia` or `deploy.resources.reservations.devices` (`driver: nvidia`, `capabilities: [gpu, video]`) are
  equivalent for older toolkits. `read_only`, `no-new-privileges` and the tmpfs stay; ffmpeg and exiftool write only to
  the work directory (exiftool's temporary file is next to its target, in the work directory).
- **Detection** at start and every 10 minutes while unavailable: `ffmpeg -encoders` lists `hevc_nvenc`, then a 0.2 s
  `testsrc2` encode with `hevc_nvenc` (and `-hwaccel cuda` decode of it) succeeds, libvmaf and exiftool answer. The
  result is on the admin page (driver version, GPU name, encoders, `av1_nvenc`, VMAF, CUDA decode) and in `GET
  /v1/server`.
- **Without a GPU** (the default image, no toolkit, a driver error): `features` has no `"compress"`, the routes that
  start work answer `503 unavailable` (`details.reason`: `no_gpu`, `no_tools`), the queue does not lease. Jobs and
  replaced items that exist (a GPU that went away) stay readable, and confirm and restore keep working: they need no GPU.
  `GET /v1/server` then still has the `compress` object with `"available": false`.

## Where the output goes

### NAS library: the server replaces, through a write mount

ADR 0023 kept the NAS read-only for the server: every move is Hoard's over SMB. For compression that does not work:

| Option | Verdict |
| --- | --- |
| Hoard replaces over SMB (as the trash) | Every output goes server → phone → share: for a library job of 100 GB of video that is ~20 GB pulled and pushed through a phone over Wi-Fi, with the phone awake for hours; the server cannot finish a job alone. SMB has no server-side copy from outside the share. Rejected for library jobs. |
| A small helper on the host (outside the container) does the rename | A second program to install and secure; the server's state machine would depend on it. Rejected. |
| **The server gets write access to chosen folders** | The bytes never leave the host; a job runs unattended; the replace is a local link + rename, atomic. Cost: the server can write the library. Mitigated below. **Chosen.** |

**The write mount.** The read-only `/mnt/nas` stays the only path the walk, previews and originals read. A second
mount of the same host directory, `/mnt/nas-write` (configurable `NAS_WRITE_ROOT`), is used **only** by
`compress.Replacer`, and only for NAS folders whose admin setting "Compression may replace files here" is on
(`nas_folders` gains no column: a `settings` row `compress.writableFolders`). The server maps a folder's read path to
its write path by the same relative path, then proves it: it creates and removes a probe file in the kept-originals root that folder would use (below) and
checks that the directory under the write path is the same as the one under `/mnt/nas` (same `st_dev`/`st_ino`). A
folder that fails is not writable (the admin page says why: not mounted, permission denied, another filesystem).

**Ownership on disk.** The server runs as uid 65532. To rename files in the owner's directories it needs write
permission on them; files it writes are owned by its uid. The documented setup runs the `-nvidia` container as the
library owner's uid:gid (`user: "1000:1000"`), so replaced files keep their owner; the alternative is group-writable
directories with the server's gid. Mode bits are copied from the original. Open question 10.

**Containment.** All writes go through an `os.Root` opened on the folder's write path and one on its kept-originals root (no `..`, no symlink
out), from
`rel_path` in the database (never a request path), and only these operations exist: create
directories under the kept-originals root, create a hidden temporary file next to the target, hard-link the original into
`.hoard-originals`, rename the temporary file over the target, unlink a kept original (confirm), rename a kept original
back (restore). Nothing else in the server opens the write mount (a test greps for it).

**The replace journal** (`compress_items.replace_step`, each step written before it runs; a restart settles a step by
`stat`, like ADR 0023's `moving` rows):

1. `check`: the file at `rel_path` has the original's size and mtime, and its `v1:` fingerprint (2 MiB read) equals
   the row's. Otherwise `failed: source_changed` (the file was edited or replaced meanwhile).
2. `copy`: the verified output is copied into `<dir>/.<name>.hoard-compress-<item id>` (a dot name: the walk and Hoard
   never list it), `fsync`ed, read back and checked by SHA-256, its mode set to the original's, its mtime to the
   original's (`os.Chtimes`).
3. `keep`: the original is **hard-linked** into the kept-originals root (below) at `<yyyy-MM-dd>/<path>` (`-2`, `-3`
   when taken). The path never goes missing and no byte is copied. When linking fails (`EXDEV`: the root is on another
   filesystem than this file; `EPERM`: no hard links), the item fails (`keep_failed`), the temporary file is removed
   and the original is untouched. Never a copy, never a delete. A `.hoard-originals` at the NAS folder's root is used
   instead only where the owner allowed option 5 (a) for that folder.
4. `swap`: `rename(<temporary>, <rel_path>)`: atomic; readers (Samba, the walk) see the old or the new file.
5. `done`: `replaced`, `original_path` set; a walk of the folder is requested (`Indexer.RequestWalk`).

`.hoard-originals` is a dot folder: the walk skips it (only `.hoard-trash` is walked, ADR 0023), so a kept original is
not a live file and its item does not stay alive. It is self-describing like the trash (`<day>/<original path>`), so
the kept originals survive a lost database and can be put back by hand. Hoard's Files shows it only with "Show hidden
files" and refuses "Move to trash" inside it, as for `.hoard-trash`.

**Where the kept originals live** (owner's option 5). The owner does not want apps to create files in his NAS folders
(the reason XMP sidecars are export-only, ADR 0011); he accepted `.hoard-trash` for the trash (ADR 0023, at the
mapping's root, which is the NAS folder's root on the server). Kept originals are a new hidden folder of the same kind,
so where they go is his choice:

| | (a) per NAS folder | (b) one at the mount root (recommended) |
| --- | --- | --- |
| Path | `<NAS folder>/.hoard-originals/<yyyy-MM-dd>/<path in the folder>` (next to `.hoard-trash`) | `<root>/.hoard-originals/<yyyy-MM-dd>/<NAS folder path from the root>/<path in the folder>` |
| How the root is found | the NAS folder's own root | from the NAS folder upwards to the write mount's root (`/mnt/nas-write`), the **highest directory on the folder's filesystem** (same `st_dev`), as ADR 0023 picks the trash root that still holds the file. Normally the mount root itself (= the share root when the share exports the same directory). Stored per NAS folder (`settings` row `compress.originalsRoots`) and checked again before each replace. |
| Visible in his folders (SMB, Finder with hidden files shown, Lightroom, backup tools) | yes: one hidden folder in each enabled folder | no: only at the mount or share root, outside the photo folders |
| Other tools | a tool covering the folder with hidden files (a sync, a backup job) copies the kept originals too: their space doubles on the other side | the same only for tools covering the whole mount; a sync or backup of one photo folder never picks them up |
| Restore by hand | the folder's tree, without its name | the full path from the root, folder name included: self-describing without the database |
| Mount boundaries | always the file's filesystem when the folder is one filesystem | a folder on another filesystem than the mount root (a bind mount, a pool member) gets the highest same-filesystem directory, which may be the folder itself or a directory inside it (then (b) degrades to (a) for that folder, and the admin page says so before it is enabled). When that directory is **not writable** (permissions, a read-only export), the folder is not offered for compression ("kept originals cannot be stored on this folder's disk"). Never a copy across filesystems. |

Recommended: **(b)**: his photo folders stay as they are, and the original stays on the same filesystem (instant, no
bytes copied, hard links). (a) remains a per-folder choice he can allow where (b) is impossible or where he prefers the
originals next to the photos.

**What the walk and the items do.** The walk sees the path with another size (mtime is the original's), re-reads its
fingerprint and metadata (ffprobe): the file is the output. The original's item loses its main file and dies in
`items.Syncer.rebuild`; today it would become a tombstone **without** `replacedBy` (its main fingerprint is a member of
no live item), so its edits would be dropped (ADR 0020) and its culling orphaned. **New heir rule** (ADR 0014
amendment): when a dead item's main fingerprint is the `fingerprint` of a `compress_items` row in `replaced` or
`confirmed`, its heir is the live item whose member is that row's `out_fingerprint`; and the reverse for `restored`
rows (a dead output item's heir is the live item of the original). Then:

- the tombstone carries `replacedBy`; `item_edits` move in the same transaction (`store/edits.go`), culling records are
  sent again with `replacedBy` (`store/culling.go`), and Hoard and Reveal follow it as they already do for a RAW that
  appears;
- the new item's data gains `"compressed": {"from": "<original fingerprint>", "profile": "hevc-cq28", "at": <ms>,
  "original": "kept" | "deleted", "originalSize": <bytes>}`, built from the record (absent on every other item);
- sidecars (`X.MP4.xmp`, `X.xmp`) keep their path and pair with the new file by stem, unchanged;
- `GET /v1/originals/{original fingerprint}` keeps answering while the original is kept: `Open` falls back to the
  record's `original_path` under `/mnt/nas` (read-only) when no live file has the fingerprint. `POST
  /v1/fingerprints/lookup` finds it the same way.
- The item id changes (the id is derived from the main fingerprint, and clients derive it too: ADR 0016 culling of
  phone-only shots). Keeping the old id for the new file was rejected: it would break that derivation for every
  client, and `replacedBy` already moves everything attached to an id.

**Trash and compression.** A trashed item is never queued. A replaced (compressed) file can be trashed like any file:
the output goes to `.hoard-trash`; the kept original stays in `.hoard-originals` until confirmed or restored. Restoring
the original of an item whose output is in the trash or purged puts the original back at its path (the trash keeps the
output; the owner empties it). Originals are deleted only by a confirmation.

### Phone videos: the server side

A phone job's items are files of the caller's own phone that the backup holds (ADR 0024 mirror entries of the
caller's physical device, `on_device = 1`, `store` `blob` or `nas`). The server reads the original from the store (or
the NAS copy) by fingerprint, as previews do (`previews.Config.Open`), and:

- **The original is in the store first.** When the entry's `store` is `nas` (the phone's file is a reference to a NAS
  copy, ADR 0024), the server ingests that NAS copy into the blob store before the item can become `ready` (the checked
  ingest of ADR 0024's "server's own NAS copy"; the entry becomes `blob`). The original then never depends on a NAS
  file that a sync or a library job may replace later.
- **The output becomes a blob**: the verified work file is ingested by `blobstore` (renamed into
  `reveal-sync/blobs/<sha>` on the same filesystem, mode `0400`), with an `originals` row for its fingerprint, no
  device claim (`blobs.device_id` null), and is **not linked** into the phone's tree yet. State `ready`.
- **Ownership rule 5** (ADR 0024 amendment): bytes are also kept while a `compress_items` row in `ready`, `replacing`,
  `replaced` or `confirmed` (until `deleted_at`) names them as original or output. So neither a Reveal delete nor a
  purge can remove an original waiting for confirmation, nor an output the phone has not fetched.
- **The phone reports the replace** (`POST /v1/compress/items/{id}/replaced` with the file's new fingerprint, SHA-256,
  size and mtime; all must equal the output's, mtime the original's). In one transaction: the mirror entry of the
  original gets `removed_reason = 'compressed'`, `on_device = 0`, **`pinned = 1`**, and its hard link moves to
  `.hoard-mirror/compressed/<path>~<sha8>`; a new current entry takes the path with the output's fingerprint and
  SHA-256, `store = 'blob'`, linked; the item is `replaced`.
- **Order does not matter.** If the backup's next report arrives first (the path with a new fingerprint), the server
  recognises the rewrite: the new fingerprint is the `out_fingerprint` of a `ready`/`replacing` row of this entry, so it
  applies the same transaction instead of ADR 0024's "rewritten in place" (`replaced`, `.hoard-mirror/previous/`).
  The report answers `stored`: **nothing is uploaded**. A replace is never a removal: the phone reports no `removed`
  for a path that still exists.
- **Items**: an entry with reason `compressed` is not a member of any mirror source (`items.GroupMirror` skips it),
  unlike a `replaced` previous version; the original's item dies and the heir rule above gives it `replacedBy` = the
  output's item. The original stays downloadable by fingerprint (`GET /v1/originals`).
- **Confirm**: the `compressed` entry and its link in `.hoard-mirror/compressed/` are removed, the row is `confirmed`;
  at `delete_after` the original blob is removed under the per-content lock if no other rule names it (a Reveal claim,
  another phone's entry, a live record, a NAS copy referenced by another entry): those stay, and the result says
  "kept: also backed up by …". `DELETE /v1/originals/{original}` answers `409 conflict`, reason `compressed`, until
  then.
- **Free up** (ADR 0024): the phone file is now the output; its current entry is verified and freed like any other
  (`POST /v1/backup/verify` re-hashes the output blob). The original's `compressed` entry is pinned, so a purge counts
  it among "only copies" until confirmed. `FreeUpPlan` leaves out a shot with an unsettled compression on the phone
  (rows of `compress_phone` not `reported`), and the compression worker abandons an item whose file is gone (`gone`).
- If the content is also on the NAS (`store = 'nas'`), the phone job still only replaces the phone's file. The NAS
  copy is the library's: a library job decides about it.

### Synced folders (Syncthing)

The owner's phone camera-drone folder holds a `.stfolder`: Syncthing syncs it to another device (possibly the NAS, or a
folder the server indexes). Replacing a file in a synced folder replaces it on every peer: the compressed file
overwrites the peer's original, or the peer keeps it in `.stversions` (when its versioning is on), or a concurrent
change there makes a `.sync-conflict-…` file. If the peer is an indexed NAS folder, the walk also sees a new
fingerprint at the same path, racing the phone flow's mirror and blob steps.

**Detection.**

- **Phone (Hoard)**: a directory is synced when it or an ancestor up to the volume root holds `.stfolder` (a
  directory) or `.stignore` (a file). Hoard checks names only (All files access, `java.io.File`) in `BackupWorker`'s
  listing pass and before an estimate, and stores the result per backup root in `gallery.db` 6 (`backup_roots.synced`:
  the synced directory prefixes relative to the volume, with the tool). It sends them with the root:
  `PATCH /v1/backup/roots/{rootId}` gains `"synced": [{"path": "<volume-relative dir>", "tool": "syncthing"}]`
  (additive; `[]` when none; without All files access nothing is sent and the root reads `"synced": null`, unknown).
- **NAS (server walk)**: the walk lists every directory anyway; it notes a `.stfolder` or `.stignore` entry by name
  (dot names are still not walked into), and at the start of a folder's walk checks the folder's ancestors up to
  `/mnt/nas`. Stored in a new schema 12 table, `synced_dirs (scope 'mirror' | 'nas', owner_id (root id or NAS folder
  id), path, tool, seen_at)`, replaced by each walk or root update; shown on the admin page and as `synced` on NAS
  folders and in `GET /v1/backup/roots`. Only Syncthing's markers for now, in a constant (`compress.SyncMarkers`) that
  can grow without a protocol change.

**Default: skip.** A video under a synced directory is skipped with reason `synced_folder` on both targets. Each synced
directory can be opted in (`compress.syncedOptIn`, per root path or NAS folder path, from Hoard's Settings › Compress
videos or the web gallery) after a sheet that says: "This folder is synced by Syncthing. The compressed file will
replace the copy on every synced device; their original is kept only if that device keeps versions. Restoring the
original syncs back too." Even when opted in, a file with a `.sync-conflict-` sibling of the same stem is skipped
(`sync_conflict`), and conflict copies are never compressed. Unknown (`synced: null`) counts as not synced and the
estimate warns that the folder could not be checked (`sync_unknown`).

**A compressed file arriving by sync** (an opted-in phone folder synced into a NAS folder, or a NAS job in a folder
synced to the phone) is recognised by content, never by who wrote it:

- **In a NAS folder**: the walk reads the new file's fingerprint and probe. When the fingerprint is the
  `out_fingerprint` of a `compress_items` row in `ready`, `replacing`, `replaced` or `confirmed` (the marker confirms
  it; a marker without a matching row only means `already_compressed`), the file joins **the output's item** (its id
  derives from the same fingerprint: the same item, not a new one), and the heir rule gives the original's item
  `replacedBy`, also while the row is still `ready`/`replacing` (the sync can beat the phone's report). The extra
  location is recorded (`compress_propagations (compress item, folder_id, rel_path, seen_at)`) and shown as "also
  replaced in <folder> by sync". The original is not lost or treated as deleted: it is in the blob store (phone jobs
  ingest it first, above) or in `.hoard-originals` (NAS jobs), and a mirror entry that referenced the NAS copy already
  points at the blob, so ADR 0024's "a reference lost its only copy" warning does not fire. Whatever the peer kept in
  `.stversions` is outside Hoard: not walked, not counted, not deleted by a confirmation (the admin page says so).
- **On the phone** (a NAS job in a folder synced to the phone): Syncthing rewrites the phone's file (a new MediaStore
  row, its own temporary file and rename). Hoard's next backup run reports a rewrite at the path with the output's
  fingerprint; the server applies "Order does not matter": the old entry becomes `compressed` and pinned, nothing is
  uploaded, no removal.

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
  `compress_jobs` (server job id, target, profile, created at, last status JSON) and `compress_phone` (server item id,
  item id, media id, volume + path, the original's fingerprint/SHA-256/size/mtime/`DATE_TAKEN`, the output's
  SHA-256/size/fingerprint, `state`, part file, offset, attempts, next attempt, error).
- `ServerApi` gains the routes below; `Models.kt` the `compressed` item field and the `compress` server object.

### Steps

| Step | What | Failure |
| --- | --- | --- |
| `ready` | `GET /v1/compress/items?mine=true&state=ready` on every Gallery sync and every `CompressWorker` run | — |
| `downloading` | `GET /v1/compress/items/{id}/output` by range into `getExternalFilesDir(null)/compress/<id>.part` (app-specific, same volume, not scanned, removed on uninstall). Constraints: unmetered network by default (outputs are a fifth of the uploads), battery not low; a foreground `dataSync` service for long runs, as backup | resumed at the saved offset; `404` (cancelled or restored on the server) drops the row |
| `verifying` | SHA-256 of the part = `outSha256` and size = `outSize`; `MediaMetadataRetriever` duration within 0.1 s of the original's, `MediaExtractor` track count and kinds, width and height equal | the part is deleted and downloaded once more; a second failure reports `POST …/abandon` `verify` |
| `checking` | the phone file still has the original's size, mtime and fingerprint (re-read 2 MiB); its MediaStore row (by id) still names that path | `changed`: abandoned, nothing written |
| `consent` | `MediaStore.createWriteRequest` for a batch of ≤ 500 rows (one system dialog per batch, as the trash; skipped with **Media management**, `MANAGE_MEDIA`): the owner's consent to change camera or WhatsApp files Hoard does not own | refused: those rows stay `verified` ("Waiting for your permission") |
| `writing` | under the grant: `IS_PENDING = 1` on the row (other apps do not read a half-written file; whether the update is allowed on rows Hoard does not own is checked on the emulator, the flow does not depend on it); the part is copied **over the file in place** (`FileOutputStream(path)` truncating, through the platform's FUSE), `fsync`; mtime set back (`File.setLastModified(original mtime)`); `IS_PENDING = 0`; `MediaStore.scanFile` on the path, then again after the mtime (the row of a rewritten file must not keep the old size: ADR 0024 A2's `PathTransferFs` finding) | a crash or kill here is **redone** at the next start from the part file (the journal says `writing`); never rolled back on the phone, since the phone no longer has the original once the write started (the server does) |
| `written` | read back: SHA-256 of the file = `outSha256`; the row's `SIZE`, `DURATION` and `DATE_TAKEN` (± 1 s) checked; if `DATE_TAKEN` changed, it is written back under the grant (else noted, `date_taken`); `device_media` gets the output's fingerprint with `fp_size`/`fp_modified` and SHA-256 (as A2's saved files), `backup_files` its fingerprint, SHA-256, size and state `stored`, so the backup does not hash or upload it | a read-back mismatch rewrites once from the part, then restores the original (below) |
| `reported` | `POST /v1/compress/items/{id}/replaced`; the part is deleted | retried; the backup report heals it anyway |

**Why in place, not a rename.** Renaming a new file over the old path gives MediaStore a new row: a new `_ID`,
`DATE_ADDED` = now (the video jumps to the top of "recently added" in every gallery app), favourites and the system's
album memberships lost, Hoard's own `device_media` and `backup_files` keys broken. Writing in place keeps the row, its
`_ID`, `DATE_ADDED`, `IS_FAVORITE`, owner package, name and folder; with the mtime set back, `DATE_MODIFIED` reads the
original's after the scan, and `DATE_TAKEN` comes from the restored QuickTime `CreateDate`. The atomicity a rename
gives is replaced by the journal: the verified output is on the phone before the first byte is written, and the server
holds both versions throughout.

**Restore original** (owner's undo) is the same flow in reverse: `GET /v1/originals/{fingerprint}` by range into a part,
check by SHA-256 (the row's `sha256`), consent, write in place, mtime, scan, `POST /v1/compress/items/{id}/restored`.
The server then makes the original's entry current again, the output's entry `compressed` (pinned until the output
blob is dropped), and the item goes back by the reverse heir rule.

**What else sees it.** The Gallery shows the same tile (same media id; the item behind it is the output's, joined by
fingerprint) with a "Compressed" badge until confirmed; the viewer plays the phone file. Reveal is not involved (videos
are not handed to Reveal; items Reveal has records of are skipped). Other apps see a modified file: a cloud backup app
(Google Photos) may upload it again as a new version; Hoard says so once in the job sheet.

## Originals policy

- **Kept until confirmed.** Nothing deletes an original automatically by default. A setting "Delete kept originals
  after N days" (off) exists for later.
- **Confirm** per item, per job, or "everything ready to confirm" (with counts and bytes shown first). From Hoard (a
  device key) a confirmation sets `delete_after` = now + **7 days** (setting `compress.confirmHold`): the admin page
  lists pending deletions and can cancel them, consistent with ADR 0024's "no device key can delete a backup at once".
  From the web gallery (admin session, LAN/Tailscale only) "Delete originals now" skips the hold.
- **Restore original** undoes `replaced`: NAS (server) renames the kept original back over the path (atomic; the output
  is unlinked and its work copy gone), phone (Hoard, above). Allowed until the original is deleted.
- **Accounting** (admin page Storage tab and the Compression view): per job and in total, "saved" = Σ (original −
  output) of `confirmed` items whose original is gone (realised), "will be saved when you confirm" for `replaced`,
  "kept originals" (count, bytes, oldest), skipped by reason, failed by reason, GPU time. The NAS figures read sizes
  from the records (no `du`); phone figures separate "freed on the phone now" (the phone file shrank at the replace) from
  "server space" (the output is added at once, the original goes at confirmation).

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
             "profiles": [{"id": "hevc-cq28", "codec": "hevc", "q": 28, "default": true},
                          {"id": "hevc-cq30", "codec": "hevc", "q": 30},
                          {"id": "av1-cq34", "codec": "av1", "q": 34, "optIn": true}],
             "writableFolders": ["<nas folder id>"], "confirmHoldMs": 604800000,
             "limits": {"maxItemsPerJob": 5000, "maxEstimate": 500, "maxConfirm": 1000}}
```

### Selections

The body of an estimate or a job names what to compress, one of:

```json
{"target": "nas",   "select": {"folders": [{"folderId": "…", "path": "2024/Trips", "recursive": true}],
                               "filter": {"from": 1700000000000, "to": 1730000000000, "minBytes": 50000000}}}
{"target": "nas",   "select": {"itemIds": ["…"]}}
{"target": "phone", "select": {"roots": [{"rootId": "…", "path": "DCIM/Camera"}], "filter": {…}}}
{"target": "phone", "select": {"itemIds": ["…"]}}
```

`target: "nas"`: live video items with a NAS location in a writable folder (an item with several NAS copies replaces
the one under the selection; the others are left and named in the answer). `target: "phone"`: video items with a
current mirror location (`onDevice`) of **the caller's physical device** (a tablet cannot start a job for a phone: the
phone must do the replace). Items outside the target are answered `skip` with `not_in_target`.

### `POST /v1/compress/estimate` `{target, select, profile?, options?, cursor?}`

```json
{"items": [{"itemId": "…", "fingerprint": "v1:…", "name": "…", "durationMs": 74000, "size": 463000000,
            "decision": "encode", "estSize": 82000000, "estSaving": 0.82, "estMs": 31000,
            "warnings": ["hdr10plus", "per_frame_metadata", "pcm_audio"]},
           {"itemId": "…", "decision": "skip", "reason": "older_compression"}],
 "totals": {"items": 312, "encode": 129, "skip": {"older_compression": 180, "efficient": 2, "unreadable": 1},
            "size": 29100000000, "estSize": 5600000000, "estMs": 2100000},
 "pending": 0, "next": null}
```

At most `maxEstimate` items per page (`next` cursor). An item whose probe is not done yet is `"decision":
"pending"` and counted in `pending` (the client asks again after `Retry-After`); probes run in the background.
`warnings`: `hdr10plus`, `dolby_vision` (with the skip), `synced_folder` (with the skip, or the opt-in's reminder),
`sync_unknown`, `per_frame_metadata` (a `djmd`/`dbgi`/`rtmd` track or
NRTM/`smta` box will be lost), `pcm_audio`, `av1_playback`.

### Jobs

| Route | Answer |
| --- | --- |
| `POST /v1/compress/jobs` `{target, select, profile, options, name?, requestId}` | `201` the job (`200` with it when `requestId` exists). The server estimates again (the preview is informative). Options: `{"quality": {"mode": "fixed" \| "floor" \| "target", "vmaf"?}, "pcm": "aac256" \| "copy", "perFrameSidecar": bool, "allow": ["dolby_vision"]?}`. `409 conflict` reason `busy` lists items already in an unfinished job (the rest are queued). |
| `GET /v1/compress/jobs?state=&cursor=` | jobs, newest first, with counts per state, bytes, saving, progress (items done / to do, current item's percent and ETA) |
| `GET /v1/compress/jobs/{jobId}` | one job |
| `GET /v1/compress/items?jobId=&state=&mine=&cursor=` | items: state, reason, sizes, q, VMAF (mean, 1 % low, mode), timings, `progress` (0–1 while encoding), `logAvailable` (the log itself is on the web gallery/admin only). `mine=true`: phone items of the caller's physical device. |
| `PATCH /v1/compress/jobs/{jobId}` `{"paused"?, "priority"?}` | the job |
| `POST /v1/compress/cancel` `{"jobId"} \| {"itemIds"}` | per item `cancelled` or its state when too late |
| `POST /v1/compress/confirm` `{"jobId"} \| {"itemIds"}` (≤ `maxConfirm`) | per item `{"state": "confirmed", "deleteAfter": ms, "alsoKeptBy"?: ["claim", "mirror", "record"]}`; not `replaced`: `{"error": "state"}` for that item |
| `POST /v1/compress/restore` `{"itemIds"}` | NAS: `restoring` then `restored` (async); phone: `restoring`, the phone does the rest |
| `POST /v1/compress/lookup` `{"fingerprints": [...]}` (≤ 1000) | per fingerprint as original or output: `{"role": "original" \| "output", "itemId", "state", "jobId", "profile", "deleteAfter"?}`; the Gallery's badges |

### Phone

| Route | Answer |
| --- | --- |
| `GET /v1/compress/items/{id}/output` | the output, `Range` supported (`http.ServeContent`), `ETag: "<sha256>"`, `X-Hoard-Sha256`, `X-Hoard-Fingerprint`. Only for phone items of the caller's physical device in `ready`/`replacing` (else `404 not_found`). |
| `POST /v1/compress/items/{id}/replaced` `{"fingerprint", "sha256", "size", "modifiedAt"}` | `200` the item (`replaced`); `409 conflict` reason `mismatch` when they are not the output's |
| `POST /v1/compress/items/{id}/restored` `{"fingerprint", "sha256", "size", "modifiedAt"}` | `200` (`restored`), the original's |
| `POST /v1/compress/items/{id}/abandon` `{"reason"}` (`verify`, `changed`, `gone`, `refused`) | `200`; the output blob is dropped when nothing else names it |

### Items, errors, limits

- Items (`/v1/items/changes`, `GET /v1/items/{id}`, lookups) gain the optional `compressed` object above on output items.
  Clients ignore unknown fields (Hoard's models default every field; Reveal's parser ignores them). Tombstones of
  compressed originals carry `replacedBy` as for any replacement.
- `DELETE /v1/originals/{fingerprint}`: a new `409 conflict` reason `compressed` (rule 5).
- New codes: none. New `details.reason` values: `busy`, `mismatch`, `state` (`409`), `compressed` (`409` on `DELETE
  /v1/originals`), `no_gpu`, `no_tools` (`503 unavailable`). `503 storage_not_configured` without a backup root.
- `server/testdata/v1-routes.txt` gains the fourteen routes above.

## UI

### Hoard

- **Gallery selection** (and the viewer's menu on a video): "Compress videos" when the server has `compress` and the
  selection has videos. A sheet: what will be compressed (phone videos replace this phone's files; NAS videos replace
  the library's, only in writable folders), the profile ("Same look", "Smaller", "AV1" when offered), then the
  **estimate**: "129 of 312 videos · 29.1 GB → about 5.6 GB · about 35 min", skipped by reason ("180 already compressed
  by an older tool", "2 already efficient", "1 unreadable"), warnings (HDR10+, per-frame data lost, PCM audio), and
  "Originals stay on the server until you confirm". Start.
- **Settings › Compress videos** (shown with the feature): jobs in progress (progress line, current item, ETA, Pause,
  Cancel), **Ready to confirm** (count, bytes, per job; Confirm, Restore original, "Deleted in 7 days unless you cancel
  it on the server"), history (saved, skipped, failed with reasons), and "Compress this phone's videos…" (folder
  picker over the backed-up roots).
- **Gallery badges**: "Compressed" on output items while the original is kept ("Original kept"), and nothing after
  confirmation; "Compressing" on queued phone items.
- The hfps design system: `HfpsScaffold`, one card surface, eyebrow labels, Phosphor icons from
  `android/scripts/icons/` (an archive/arrows-in icon), progress as in Settings › Backup.

String keys (`values/strings.xml` and `values-pt-rBR/strings.xml`; plurals with `one`, `many`, `other` in pt-BR):
`compress_title`, `compress_action`, `compress_action_selection`, `compress_target_phone`, `compress_target_nas`,
`compress_profile_label`, `compress_profile_same`, `compress_profile_same_desc`, `compress_profile_smaller`,
`compress_profile_smaller_desc`, `compress_profile_av1`, `compress_profile_av1_desc`, `compress_estimate_title`,
`compress_estimate_summary` (plural), `compress_estimate_pending`, `compress_skip_already` (plural),
`compress_skip_older` (plural), `compress_skip_efficient` (plural), `compress_skip_small` (plural),
`compress_skip_unreadable` (plural), `compress_skip_dolby_vision` (plural), `compress_skip_reveal` (plural),
`compress_skip_not_writable` (plural), `compress_warn_hdr10plus`, `compress_warn_per_frame`, `compress_warn_pcm`,
`compress_warn_other_backup_apps`, `compress_originals_kept`, `compress_start`, `compress_progress`,
`compress_eta`, `compress_pause`, `compress_resume`, `compress_cancel`, `compress_ready_title`,
`compress_ready_summary` (plural), `compress_confirm`, `compress_confirm_hold`, `compress_restore`,
`compress_restore_summary` (plural), `compress_history_title`, `compress_saved`, `compress_failed_reason_verify`,
`compress_failed_reason_changed`, `compress_failed_reason_space`, `compress_failed_reason_encoder`,
`compress_phone_waiting_permission`, `compress_skip_synced` (plural), `compress_synced_optin_title`,
`compress_synced_optin_body`, `compress_synced_optin_action`, `compress_warn_sync_unknown`, `compress_propagated`, `compress_phone_needs_all_files`, `compress_phone_needs_android11`,
`compress_badge_compressed`, `compress_badge_original_kept`, `compress_badge_compressing`, `compress_unavailable_gpu`.

### Web gallery and admin page

- **Web gallery** (`server/web/`): on a selection or a folder (Folders), "Compress videos" with the same estimate; a
  **Compression** view in the shell (jobs with progress, Ready to confirm with "Confirm" and "Delete originals now",
  Restore, history with saved bytes, each item's VMAF and its log). Library jobs only (`target: "nas"`); phone jobs
  are listed read-only. Strings in `server/web/src/i18n/locales/{en,pt-BR}.json` under `compress.*`: `compress.title`,
  `compress.action`, `compress.estimate.*` (`summary`, `pending`, `skipped`, `warnings`), `compress.profile.*`,
  `compress.quality.*` (`fixed`, `floor`, `target`), `compress.jobs.*` (`progress`, `eta`, `pause`, `resume`,
  `cancel`), `compress.ready.*` (`title`, `confirm`, `deleteNow`, `restore`, `hold`), `compress.history.*`,
  `compress.item.*` (`vmaf`, `log`, `reason.*`), `compress.unavailable.*` (`noGpu`, `noTools`), `compress.synced.*` (`skipped`, `optIn`, `optInBody`, `unknown`,
  `propagated`, `stversions`), `compress.originalsRoot.*` (`mountRoot`, `perFolder`, `notWritable`).
- **Admin page** (`internal/server/web`): a Compression section: GPU status (detected, driver, encoders, VMAF, CUDA
  decode, last check), sessions, "Pause while the GPU is busy", window, the writable folders (each with its check
  result), the confirm hold, pending deletions (cancel), the PCM and sidecar defaults; the Storage tab gains kept
  originals and saved bytes.

## Security

- **The write mount is the only new power**, opt-in per folder, used by one component through `os.Root`, from database
  paths, with six operations, none of which deletes a live file: a kept original is unlinked only by a confirmation,
  and a target is only ever replaced by a verified output while its original is linked aside.
- **Devices**: every paired device may start jobs and confirm (one owner, ADR 0013), but confirmations from devices
  wait 7 days and are cancellable on the admin page; a stolen key can at most queue work and schedule deletions the
  owner sees. A phone job can only target the caller's own phone files; outputs are served only to that phone.
- **Inputs to tools**: file paths are passed as arguments after `-i`/`--` from the database, never from requests; the
  profile and options are enums; the metadata written is a constant string with the q and a date. exiftool runs with
  `-q -q -m` on files in the private work directory only.
- **Logs**: as ADR 0013; the item logs (paths, metadata) live in the data directory and are readable only through the
  admin session.

## Build plan

Each phase is one PR with its tests, shippable alone.

| Phase | PR | Contents | Tests |
| --- | --- | --- | --- |
| S1 | hoard-server 0.13.0 | Schema 12; `internal/compress`: `Prober` (`video_probe`), `Estimator` and skip rules, `Queue` (lease, priorities, sessions, yield, window, restart), an `Encoder` interface (`NvencEncoder`: ffmpeg + exiftool + mtime; CUDA-decode retry), `Verifier` (ffprobe checks, metadata diff, VMAF full/spot), `Replacer` (write mount, journal, `.hoard-originals`, restore, confirm), the heir rule in `items.Syncer.rebuild`, `compressed` on items, `Open` falling back to kept originals; `/v1/compress/*` for `target: "nas"`, estimate, lookup; GPU detection, `features`; the `-nvidia` image and compose/README; admin page section; ADR 0013 section, `openapi.yaml`, `v1-routes.txt`. | Go with a **fake encoder** (copies a synthetic file and rewrites bytes; a slow one for cancel and restart): every state transition, restart in each state, cancel, priorities, sessions, yield (a fake NVML), space checks; skip rules on synthetic probe JSON (marker, `Lavc`, bpp, DV, Reveal record); estimate maths; the replace journal killed after each step and settled; `EXDEV`/`EPERM` fallbacks (an injected linker); a changed source refused; the walk + heir rule (edits and culling follow `replacedBy`; restore reverses it); `.hoard-originals` not walked; the kept-originals root (mount root found by `st_dev`, a folder on another filesystem, a root not writable, option (a) per folder); Syncthing markers in a folder and in an ancestor (`synced_dirs`), the `synced_folder` skip and opt-in, a `.sync-conflict-` sibling; an output appearing by sync in another NAS folder joins the output's item with `replacedBy` while the row is `ready`; `GET /v1/originals` of a kept original; `os.Root` containment; Verifier with real ffmpeg on lavfi clips made with libx264/libx265 (CPU, seconds): duration, stream, rotation, HDR side data, frame-count mismatch, VMAF spot vs full; `TestRevealProtocolContract`, `TestOpensRevealSyncDataDir`, `TestUpgradesHoardServer040DataDir`, `TestMigrationsNeedNoTempDir` unchanged. **GPU integration test** (opt-in `HOARD_GPU_TEST=1`, run on the GPU host in the `-nvidia` container): the recipe on synthetic 8-bit, 10-bit HLG/PQ and VFR clips, the marker, the cap, HDR bit-exact, CUDA-decode fallback. |
| S2 | hoard-server 0.13.x | Phone jobs: `target: "phone"`, output blobs, ownership rule 5, the `compressed` mirror reason and its transaction (route or report first), `GroupMirror` skipping it, output/replaced/restored/abandon routes, `409 compressed` on `DELETE /v1/originals`. | Go: the NAS-referenced original ingested before `ready`; `synced` on root `PATCH`; both orders of report and `/replaced`, and a sync-propagated output in an indexed NAS folder before either; nothing uploaded; no removal; ownership across Reveal delete, purge, confirm with another claim; free-up verify of the output; restore reversing items. |
| S3 | hoard-server 0.13.x | Web gallery: Compress on selection/folder, the Compression view, logs; strings `en` + `pt-BR`. | `bun run lint/typecheck/test/e2e` with a synthetic library and the fake encoder (a server flag only tests can set). |
| A1 | Hoard 2.7.0 | Library jobs from Hoard: `compress` in models, Gallery selection → estimate → job, Settings › Compress videos (progress, ready to confirm, history), badges, lookup. | JVM: the sheet's model against a fake server, Roborazzi goldens; emulator against a local hoard-server container with the fake encoder (`docs/testing/compression.md`). |
| A2 | Hoard 2.8.0 | Phone replace: `gallery.db` 6, `CompressPlan`, `CompressDownloader`, `CompressEngine`, `CompressWorker`, `PhoneCompress` (consent, in-place write, mtime, scan, `DATE_TAKEN`), restore, `FreeUpPlan` exclusion. | JVM: `CompressPlan` (every journal state after a kill), the downloader (resume, `If-Range`, short body, 404), engine against a fake server; migration 5 → 6. Emulator (debug id, local container with a real or fake encoder): synthetic camera videos in `DCIM/Camera`, backed up, compressed, replaced in place: same `_ID`, name, `DATE_TAKEN`, mtime, `IS_FAVORITE`; the process killed during `writing` and redone; the backup's next run uploads nothing and reports no removal; consent refused; restore; free up after a compression; a root under a `.stfolder` skipped until opted in. Never on the owner's phone. |

S1 ships alone (the default image keeps working without a GPU). S2 needs S1; A1 needs S1; A2 needs S2. The owner's
library can be compressed after S1 from the web gallery (S3) or Hoard (A1); the phone folder after A2.

## Consequences

- The server can now write the NAS, in folders the owner enables, for one purpose. ADR 0023's "the server never writes
  the NAS" holds everywhere else.
- Kept originals take the space of the library they came from until confirmed: a job saves nothing on disk before the
  owner confirms (on the phone, the phone's space is saved at once).
- A compressed file is a new item; its old id is a tombstone with `replacedBy`. Clients that ignore `replacedBy` lose
  their link to the old id (none of Hoard's or Reveal's flows do).
- The `-nvidia` image is larger (glibc base, libvmaf, exiftool) and must be rebuilt when FFmpeg's NVENC API needs a
  newer driver; the default image is unchanged.
- Per-frame drone and camera telemetry and some vendor boxes are lost in every compressed file unless the sidecar
  option is on.

## Options for the owner

Each with a recommendation; the ADR assumes the recommended one until answered.

1. **Quality.** A: fixed cq 28 (today's look, one encode). B: per-clip search to VMAF 95 (2–3 encodes, savings as low
   as 27 % on high-motion 60 fps). C: cq 28, re-encoded lower only when VMAF < a floor (e.g. 93). **Recommended: C,
   floor 93**: the approved look everywhere, and the one class the old script ruined (2.7K60, VMAF 83.5 then) is
   brought to 93.5 for one extra encode.
2. **PCM audio** (the ZV-E10's 1536 kb/s): copy as is (lossless, `ipcm` in MP4, 14–16 % of the output, Android
   playback unverified) vs AAC-LC 256k (about 2.5 %, plays everywhere). **Recommended: AAC 256k.**
3. **Lost per-frame tracks** (DJI `djmd`/`dbgi`, Sony `rtmd` + NRTM XML, Samsung `smta`): (a) an `exiftool -ee3 -j`
   JSON sidecar (a few hundred KB per file; where it is kept: option 6), (b) keep the
   original (no compression for such files), (c) accept the loss as the old script did. **Recommended: (a)**, cheap
   and searchable later; (c) if you never used the telemetry.
4. **AV1** (`av1_nvenc`, 14–22 % smaller than HEVC at equal VMAF): opt-in per job only after you check playback on
   your phone, tablet and TV. **Recommended: offer it as opt-in, HEVC stays the default.**

5. **Where kept originals live on the NAS** (a new hidden folder either way): (a) `.hoard-originals` in each NAS
   folder, next to `.hoard-trash`; (b) one `.hoard-originals` at the mount root mirroring `<folder>/<path>`, on the same
   filesystem (hard links still work); a folder on another filesystem uses its highest same-filesystem directory, and
   is not offered when that is not writable. **Recommended: (b)**, with (a) allowed per folder where you prefer it.
   Trade-offs in "Where the kept originals live".
6. **Where the per-frame sidecar of option 3 (a) lives**: (a) next to the output in your folder (`<name>.hoard-meta.json`;
   visible to every tool, a new file in your folders), (b) next to the kept original inside `.hoard-originals` (hidden,
   but it goes with the original at confirmation unless moved, and phone outputs have no such folder), (c) the server's
   data directory, `<data dir>/compress/meta/<output fingerprint>.json`, keyed by content (survives confirmation,
   moves and renames; backed up with `/data`; downloadable from the web gallery's item panel). **Recommended: (c).**
7. **Synced folders** (Syncthing): skip by default with a per-folder opt-in that explains the propagation
   (recommended), or warn and compress? Where does the phone's drone folder sync to (a NAS folder the server indexes,
   another device), and should compression ever touch synced folders?

## Open questions

8. The lean option set replaces your draft's multipass/lookahead/b-frames/AQ (measured 0.5–4.7 % less efficient and
   AQ +17 % size). Keep lean, or compare banding by eye on a few flat-sky clips first?
9. Dolby Vision sources: skip (default), or allow with the base layer only?
10. The `-nvidia` container runs as your library's uid:gid so replaced files keep their owner: acceptable, or
    group-writable folders instead?
11. Which NAS folders may compression replace files in (none by default)?
12. Confirm hold for confirmations from Hoard: 7 days? And should "Delete kept originals after N days" ever be on?
13. GPU sharing: one session, "pause while the GPU is busy" on; a night window too?
14. Phone videos already freed from the phone (server-only): compress them on the server (blob replaced, no phone
    step) in a later phase, or never?
15. A standing rule "compress new phone videos once backed up" (needs a confirmation per batch anyway), or jobs only?
