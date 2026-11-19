# Video compression, phase A1: Hoard 2.7.0 (ADR 0025)

Library (`nas`) and backup jobs from Hoard, Settings › Compress videos, the Gallery's badges, and the Trash screen's
compressed originals, with the **held** rule: Empty, Delete permanently and auto-empty leave every trash entry an
unfinished compression journal holds (ADR 0025 Amendment (S1)). Run on cachyos-fps on 2026-11-19, branch
`henriquefps/compress-a1` on `main` 4e3866e (hoard-server 0.13.0 S1, S2 and the S2 follow-ups).

## JVM and Robolectric

| Suite | What it checks |
| --- | --- |
| `:core:server` `CompressModelTest` | the gate (no `compress` feature: everything hidden; a target not ok: its reason, phone and backup judged apart; the phone target never offered in 2.7.0; Settings also shown while the server reports compression without the feature); AV1 offered only with "Offer AV1" and never for automatic folders; the estimate summary (the selection's totals, skips by count, warnings of encoded videos only); the sheet's Start rule; Settings' split into running, automatic and history; items' `compressed`, `compressedTo` and `held`; every route against the fake server (`Retry-After` of a pending estimate, `201` job, `409 exists`, jobs with `auto=true`, `PATCH` with only `auto`, purge's `alsoKeptBy` and per-id `error`, lookup, items) and the bodies sent |
| `:native` `HeldTrashTest` | held server locations mapped to the share's `.hoard-trash` paths (and, without a learned mapping, by `.hoard-trash/<day>/<path>`); **Empty**, **Delete permanently** of the held row alone and **auto-empty** (a day folder emptied around the held file, counted without it; a dry run too; the next run once released) on the share; an unknown held state leaves every share alone (local drives emptied); the split (held / compressed through the routes / plain, by mapped path or trash tail) and the groups' counts; **only the server deletes a compressed original** (`emptyTrash` against a refusing fake server: `no_write_mount` for the whole request, `not_writable`, `output_missing`, `busy`, `state`: nothing of it deleted, its rows kept; a purged one is never touched by Hoard; auto-empty leaves it); auto-empty and Empty share one predicate; **server side** against a fake server: purge never sends a held original, a refusal is reported, offline fails every id |
| `:native` `CompressTargetsTest` | a Gallery video's target (`nas` with a live NAS file, `backup` only when every phone copy is off the phone, else none); a Devices place as backup roots; merged estimates; an automatic job matched to its folder |
| `:native` `CompressScreenshotTest` | the goldens below and the Trash counts without held entries |

Goldens (`:native:recordRoborazziDebug`, copies here): `compress_sheet_{phone,tablet}_{light,dark}` (estimate with skips,
HDR10+ and per-frame notes), `compress_sheet_estimating_phone_light` (AV1 offered), `compress_sheet_unavailable_phone_light`
(`no_write_mount`), `compress_sheet_auto_phone_dark` (the switch's sheet), `compress_settings_{phone,tablet}_{light,dark}`,
`compress_settings_unavailable_phone_light`, `compress_trash_{phone,tablet}_{light,dark}` (compressed originals on a share,
a held one, the server section), `compress_trash_empty_confirm_phone_light` (the held note).

## End to end: emulator against my own hoard-server and Samba containers

No GPU is used on this host for the test. The server is hoard-server built from `main` 4e3866e with a CPU stand-in for
NVENC: [`compression/a1fake/fake.go.txt`](compression/a1fake/fake.go.txt), built as `server/cmd/a1fake/` (a copy of
`server/main.go` whose `server.Config` adds `CompressEncoder`, `CompressVerifier`, `CompressDetector`, `CompressNVML`
and `CompressLinker` from that file; never committed under `server/`). Its encoder is libx264 at 1 Mb/s with the
marker, its verifier passes, the GPU is "detected" and never busy; a source whose name contains `HOLD` gets its trash
link and then `EIO`, so its replace journal waits with the original in the trash (a held entry).

```sh
mkdir -p server/cmd/a1fake && cp server/main.go server/umask_*.go server/cmd/a1fake/ \
  && cp docs/testing/compression/a1fake/fake.go.txt server/cmd/a1fake/fake.go     # then add the five Config fields
(cd server && CGO_ENABLED=0 go build -o "$E2E/hoard-server-fake" ./cmd/a1fake)
docker run -d --name hoard-a1-server --user 1000:1000 --cpus 2 --memory 1536m -p 127.0.0.1:3181:3071 \
  -v "$E2E/hoard-server-fake":/e2e/hoard-server:ro -v "$E2E/nas":/mnt/nas:ro -v "$E2E/nas":/mnt/nas-write \
  -v "$E2E/data":/data -v "$E2E/backup":/mnt/backup -e MOUNTS=/mnt/nas,/mnt/backup -e BACKUP_ROOT=/mnt/backup \
  -e NAS_INTERVAL=1m --entrypoint /e2e/hoard-server ghcr.io/henriquefps/hoard-server:0.12.1 serve
docker build -t hoard-a1-samba -f docs/testing/compression/a1fake/Dockerfile.samba docs/testing/compression/a1fake
docker run -d --name hoard-a1-samba --memory 256m --cpus 1 -p 127.0.0.1:14481:445 -v "$E2E/nas":/srv/photos hoard-a1-samba
```

- The 0.12.1 image only lends its preview tools (ffprobe, ffmpeg with libx264); the binary is main's. One temporary
  directory is the NAS (`/mnt/nas` read-only, `/mnt/nas-write` the write mount) and the Samba share `photos`
  (`photos/Videos` = NAS folder `Videos`). Admin set-up, the NAS folder and the pairing link through the admin API.
- Synthetic files (ffmpeg `testsrc2` + noise, 4 s 720p H.264 at 20 Mb/s): `Videos/Drone/DJI_0001.MP4`, `DJI_0002.MP4`,
  `DJI_HOLD_0003.MP4`, `OLD_0004.MP4` (HEVC by libx265: the `Lavc` rule), two JPEGs for the mapping.
- Emulator `hoard-burst` (API 36, `-port 5580 -memory 3072 -no-window`), the debug build, data cleared first. The
  machine typed by hand (`10.0.2.2:14481`); the form also listed a machine discovered on the LAN, which was not tapped,
  and the saved address was checked before saving.
- Afterwards: both containers and the Samba image removed, the emulator stopped, the temporary directories left in the
  session's scratch space only.

| Step | Result | Screenshot |
| --- | --- | --- |
| Pair; Settings › Photo server | "Compress videos" row (feature `compress`, `targets.nas.ok`) | — |
| Files › E2ESamba › photos › Videos › Drone › ⋮ › Compress videos in this folder | "Library videos: the server replaces the files in the library", Same look / Smaller, "3 of 4 videos · 31.6 MB → about 2.8 MB · about 1 min", "1 already compressed by an older tool", the trash note | [`e01`](compression/e2e/e01-sheet-estimate.png) |
| Start | "3 videos queued"; server: two `replaced` (originals in `Videos/.hoard-trash/2026-11-19/Drone/`, outputs at their paths), `DJI_HOLD_0003` `replacing` (`not_writable`), `attention.stalled` 1 | — |
| Gallery | "Compressed" on the two outputs, "Compressing" on the held one | [`e02`](compression/e2e/e02-gallery-badges.png) |
| Trash | Nov 19 › Videos/Drone "2 files · 20.9 MB"; DJI_0001 and DJI_0002 "Original of a compressed video · saves … when emptied"; DJI_HOLD_0003 "Held by a compression in progress", not counted | [`e03`](compression/e2e/e03-trash.png) |
| DJI_0001 › Restore original | "1 file restored": `POST /v1/compress/restore` by the item's id; the original back at its path (11 MB), the output into `.hoard-trash/2026-11-19-2/` | — |
| A new `DJI_HOLD_0005.MP4`, compressed from the **Gallery selection** (long press, Compress videos: "1 of 1 video"); while its journal waited, the server restarted **without** its write mount | the journal keeps waiting (`replacing`, `not_writable`): a lasting held entry | [`e04`](compression/e2e/e04-gallery-selection.png), [`e05`](compression/e2e/e05-gallery-sheet.png) |
| Trash › Empty trash | "3 files (22.1 MB) will be deleted for good" + "1 entry held by a compression in progress stays in the trash"; first run: the server refused the purges (`no_write_mount`), nothing deleted, held kept. A fallback added then (deleting them over SMB) was removed in the review of #76: only the server deletes a compressed original. Rerun below ("Review of #76") | [`e06`](compression/e2e/e06-trash-held.png), [`e07`](compression/e2e/e07-empty-confirm.png), [`e08`](compression/e2e/e08-after-empty.png) |
| The held file (and its journal's `trash_path`, edited in the stopped server's database) moved to a day folder `2026-10-01` (mtime Oct 1) next to a plain `OLD_PLAIN.JPG`; Settings › Trash › After 7 days | "1 file (113 KB) older than 7 days is deleted for good now"; Delete and apply: `OLD_PLAIN.JPG` gone, **`DJI_HOLD_0005.MP4` kept** in the 49-day-old folder | [`e09`](compression/e2e/e09-trash-held-old.png), [`e10`](compression/e2e/e10-shorten-confirm.png) |
| Server restarted with the write mount | the waiting journal completes (`replaced`), `attention.stalled` 0 | — |
| ⋮ › Compress automatically, "Also compress the 1 video already there" | the sheet: "1 of 5 videos", 3 already compressed, 1 by an older tool; a standing job `Videos/Drone` (`auto`) and a one-off job; DJI_0001 `replaced` | [`e11`](compression/e2e/e11-auto-sheet.png) |
| Trash › Empty trash (write mount present) | "2 compressed originals purged" through `POST /v1/compress/purge`; `.hoard-trash` holds only `.hoard-clock`; the items `purged` | [`e12`](compression/e2e/e12-trash-two.png) |
| Settings › Compress videos | "47.4 MB saved · 0 B in the trash", GPU, AV1 not offered, the automatic folder, three finished jobs | [`e13`](compression/e2e/e13-settings-compress.png) |
| Its switch off | `PATCH … {"auto": false}`: the job `cancelled`; "No folder yet" | — |

**Found and fixed during the run**: the folder menu resolved the share's mapping once, before it was learned (now when
the menu opens); the Trash screen showed a held entry as plain until the next sync (its refresh now syncs first, as
Empty and auto-empty already did); a purge the server refused for want of its write mount left the originals with no
message (now the reason is shown and they stay: see "Review of #76"); the harness's encoder read the
source from a pipe (an MP4's index is at its end).

**Not run here**: a backup (`target: "backup"`) job on the emulator (no phone backup in this run; the selection's
target choice and the Devices action are covered by `CompressTargetsTest`, the routes by `CompressModelTest`); the real
NVENC encoder (the server's GPU test, S1).

## Review of #76 (2026-11-19): only the server deletes a compressed original

The first build deleted compressed originals over SMB when the server refused a purge for want of its write mount;
that bypassed the server's own checks (a trashed or missing output, a failed safety check answered `not_writable`).
Now: Empty and Delete permanently send compressed originals only to `POST /v1/compress/purge` and leave them on any
refusal, with the reason; auto-empty and Empty's plain rows delete with a protected set (held entries and every
compressed original, by mapped path, trash tail and item); a failed sync with the paired server holds every share row
(auto-empty skips shares); auto-empty uses Empty's predicate; an unmapped share's original is matched by its trash
tail, so restore goes through the server too. Tests: `HeldTrashTest` `aRefusedPurgeDeletesNothingOfTheOriginal`
(`no_write_mount` for the whole request, `not_writable`, `output_missing`, `busy`, `state`), `aPurgedOriginalIsTheServersDeleteNotHoards`,
`autoEmptyLeavesCompressedOriginalsToTheServer`, `autoEmptyUsesTheSamePredicateAsEmpty`,
`anUnmappedShareMatchesCompressedOriginalsByTheirTrashPath`, `anUnknownHeldStateLeavesEveryShareAlone`.

Emulator rerun (hoard-server built from `main` 1774d15 with the same harness, fresh data; the Samba container's new
identity was re-pinned in the machine form after checking its address `10.0.2.2:14481`): a new `DJI_0006.MP4`
compressed with the write mount (`replaced`, original in `.hoard-trash/2026-11-19/Drone/`), then the server restarted
**without** the write mount (`targets.nas`: `no_write_mount`). Trash › Empty trash: "1 original stays in the trash: the
server cannot write the library now" ([`r01`](compression/e2e/r01-empty-refused.png)); log `1 refused [no_write_mount]`;
the original still on the share, the item still `replaced`. Containers, the Samba image and the emulator removed/stopped.
