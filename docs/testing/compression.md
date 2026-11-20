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

## Hoard 2.7.1 (ADR 0025 Amendment (2.7.1))

- JVM/Robolectric: `CompressReasonTextsTest` (every listed reason is a sentence in en and pt-BR, never the raw code; an
  unknown one is generic plus its code; the sub-folders plural), `CompressModelTest` (`subfolders` read and absent = 0;
  the drift guard against the server's `Reason*` constants), `CompressTargetsTest` (merged sub-folders), `HeldUnknownTest`
  (restore and the preview sync first; a failed sync holds every share row). Goldens re-recorded: `compress_sheet_*`
  (sub-folders line; `write_mount_read_only` sentence) and the new `compress_settings_unknown_reason_phone_light`.
- Emulator (own AVD `hoard-271`, port 5582; hoard-server built from `main` 1863dbf with the a1fake harness in a
  container on port 3271, no write mount; paired by typing the link): Settings › Compress videos says "The photo server
  has no write access to the library, so it cannot replace videos." for `no_write_mount`
  ([`e271-settings-no-write-mount.png`](compression/e2e/e271-settings-no-write-mount.png)). The sub-folders line needs
  hoard-server 0.13.2 and is covered by the unit tests and goldens only. The container, the AVD and the temporary
  directories were removed.

## A2: Hoard 2.8.0, the phone's own replace (ADR 0025 Amendment (A2))

Run on cachyos-fps on 2026-11-20, branch `compress-a2` on `main` d91e160 (hoard-server 0.13.2, Hoard 2.7.1, web S3).

### JVM and Robolectric

| Suite | What it checks |
| --- | --- |
| `:core:server` `CompressPlanTest` | a `ready` item is a `compress` row, a `replacing` one a `restore` row (a restore again writes the output); items without a phone file or hashes are no rows; **every journal state resumes where it was killed, in both directions** (download at its offset, verify and check again, write redone, report, abandon; back-offs wait); reconcile (new items, a new cycle over a settled row, the other direction replaced unless its write started, an abandoned row not made again; rows dropped only before the write and only from a complete list); the check (size, mtime within 2 s, fingerprint, the MediaStore path; gone; a target already in place); a bad download fetched once more then abandoned (never after the write started); a bad read-back written once more, abandoned, then repaired from the server; consent batches of 500; the overview |
| `:core:server` `CompressDownloaderTest` | a dropped connection (a body shorter than its length) resumes at the bytes that arrived, with `If-Range` on the ETag; a stopped run resumes from the saved offset (bytes after it dropped); a changed file (another ETag) is sent whole and the part starts over; a server that ignores the range starts over; a complete part is done; `404` is not found; a connection that keeps dropping gives up with what arrived kept |
| `:core:server` `CompressEngineTest` | against a fake server (`FakeCompress` over `FakeBackup`): a backed-up video replaced in place (same path, mtime), `…/replaced` with the output's fingerprint, SHA-256, size and the original's mtime, the index and backup rows updated, and **the backup's next run uploads nothing, reports nothing for it and removes nothing**; a report lost is healed by the backup's own report without an upload; **a process killed during the write is redone from the part** (no new download); consent waits without a screen, a refusal keeps the original, an allowance writes at once; Keep originals abandons `refused`; a changed file `changed`, a missing one `gone`; a `404` on the output and an item no longer listed drop the row unwritten; a lost answer is reported without a download; a restore writes the original back from `GET /v1/originals` and a restore again the output; a broken write is written again, abandoned `verify` and repaired; the server still compressing asks for a later run |
| `:core:server` `GalleryMigrationTest` | 5 → 6 keeps the pairing and backup rows; the new tables work |
| `:core:server` `FreeUpPlanTest`, `CompressModelTest` | a video with an unsettled replace stays on the phone (`Compressing`); the phone target only with `targets.phone.ok`, unusable on Android 10 or without All files access |
| `:native` `PhoneCompressScreenshotTest` | the goldens below |

Goldens (`:native:recordRoborazziDebug`, copies here): `compress_phone_sheet_{phone_light,phone_dark,tablet_light}`
(this phone's videos, the other-apps note, skips), `compress_phone_sheet_no_all_files_phone_light`,
`compress_phone_settings_{phone,tablet}_{light,dark}` ("This phone": the consent step with replaces and a restore, a
download in progress, recent rows, the phone job), `compress_phone_settings_no_all_files_phone_light`.

### End to end: emulator against my own hoard-server container

- **Server**: hoard-server built from `main` d91e160 with `-tags hoarde2e` (`HOARD_E2E_FAKE_COMPRESS=1`: the fake
  libx264 encoder of `server/e2e_compress_fake.go`), run as `hoard-a2-server` with the 0.12.1 image's tools,
  `--cpus 2 --memory 1536m`, `-p 127.0.0.1:3282:3071`, temporary `/data` and `/mnt/backup` (`BACKUP_ROOT`), no write
  mount (`targets.nas`: `no_write_mount`, `targets.phone.ok`). No GPU used. Admin set-up and the pairing link through
  the admin API; the link typed into Hoard's pairing field (`10.0.2.2:3282`).
- **The fake encoder drops the capture time** (no `-map_metadata 0`, and `use_metadata_tags`): its outputs have no
  `creation_time`, so MediaStore's `DATE_TAKEN` was NULL after the first replaces. MediaProvider ignores every app's
  update of `datetaken` (Amendment (A2)), so for the date check the harness was rebuilt locally with the real recipe's
  `-map_metadata 0` and without `use_metadata_tags` ([`a2fake/keep-dates.patch.txt`](compression/a2fake/keep-dates.patch.txt),
  never committed under `server/`).
- **Emulator**: AVD `hoard-a2` (API 36 google_apis_playstore x86_64, `ANDROID_AVD_HOME` in my scratch space, port
  5594, `-memory 3072 -no-window`), the debug build, All files access by `appops`, media and notification permissions
  granted. Synthetic clips (ffmpeg `testsrc2` + noise, 720p H.264 at 20 Mb/s + AAC, 4–8 s, `creation_time` set):
  `DCIM/Camera/PXL_*.mp4` and a DJI-like `DCIM/DJI Album/` with `DJI_0412.MP4`, a
  `DJI_0413.sync-conflict-20241101-123456-ABCDEFG.MP4` and a stale `.stfolder`; mtimes set in 2024; one video made a
  favourite (`is_favorite=1`). MediaStore owner `com.android.shell`, so every replace needed consent.

| Step | Result | Screenshot |
| --- | --- | --- |
| Settings › Phone backup: Camera and DJI Album on, Back up now | 5 of 5 backed up | — |
| DJI Album › Compress videos in this folder | "Videos on this phone are replaced … same name, folder and date", the other-apps note, "1 of 2 videos · 15.4 MB → about 1.4 MB", **"1 sync tool copy"** skipped; the stale `.stfolder` changes nothing | [`a2-p01`](compression/e2e/a2-p01-dji-sheet.png) |
| Start; Settings › Compress videos | the worker downloaded and verified the output, then waits: "1 video is ready to replace (saves 13 MB here)", Allow / Keep originals | [`a2-p02`](compression/e2e/a2-p02-consent-step.png) |
| Allow → system dialog "Allow Hoard debug to modify this video?" → **Deny** | "consent … false"; the row still waits; the file untouched | [`a2-p03`](compression/e2e/a2-p03-system-dialog.png) |
| Allow → Allow | DJI_0412 replaced: **same `_ID` 21, name, `date_modified` (mtime), `is_pending=0`**, 15.4 → 2.4 MB; server: the original's entry `compressed` + pinned, a new current entry with the output; item `replaced`; "13 MB smaller on this phone" | [`a2-p04`](compression/e2e/a2-p04-replaced.png) |
| Finding | `IS_PENDING = 1` on the row moved the file to a `.pending-` name (undone by the first build's guard): the write no longer sets it (Amendment (A2)) | — |
| Back up now | `backup run: Done, 0 uploaded`; no report or upload request reached the server for the replaced file | — |
| Camera › Compress videos in this folder → Start (3 videos); Allow → one dialog "modify 3 videos?" → Allow, the debug build pausing half-way through the first write (`files/compress-pause-write`), **the process killed** (`kill -9`) | the journal: row `writing`, `write_started=1`; the phone file a 2 MiB half | [`a2-p06`](compression/e2e/a2-p06-consent-three.png) |
| New build installed, app started | **the write redone from the part** (no download): the file's SHA-256 is the server's output SHA-256, the mtime the original's, reported; the other two asked again (a grant does not outlive the process), Allow: all three replaced; **`IS_FAVORITE` kept** (row 17 still `1`), `_ID`s, names and `date_modified` unchanged | — |
| DJI Album › Compress automatically (on); a new `DJI_0420.MP4`, Back up now | the server appended it to the standing job after the backup (`ready`); a worker run scheduled 10 minutes after the backup | — |
| Gallery (after a sync) | "Compressed" on the replaced videos, "Compressing" on the waiting one | [`a2-p09`](compression/e2e/a2-p09-gallery-badges.png) |
| A new Camera clip selected in the Gallery › Compress videos | "1 of 1 video", the phone target | [`a2-p10`](compression/e2e/a2-p10-selection-sheet.png) |
| Harness keeping the dates; `PXL_20241106_192030.mp4` compressed and replaced | **same `_ID` 30, name, `datetaken` 1730920830000, `date_modified`** | [`a2-p14`](compression/e2e/a2-p14-settings-phone-section.png) |
| Trash › Originals of compressed videos › PXL_20241102 › Restore original | the server: `replacing` (`restore:phone`); the worker downloaded the original (`GET /v1/originals`) and waits; the notification "1 compressed video ready to replace · Open Hoard to allow it" opens Settings › Compress videos; Allow: **the original's bytes back** (SHA-256 of the source clip), same `_ID` 18, `datetaken` and mtime; the server `restored`, entries swapped back | [`a2-p16`](compression/e2e/a2-p16-notification.png), [`a2-p17`](compression/e2e/a2-p17-after-restore.png) |
| Settings › Phone backup › Free up space, only DJI_0412 and PXL_20241103 (both compressed) kept selected | "Staying on the phone: 1 favourite"; the server re-hashed the outputs, "2 shots verified"; Android's delete dialog, Allow: "Freed 2 files · 4.7 MB"; server: the outputs' entries `freed`, the originals still `compressed` + pinned | [`a2-p18`](compression/e2e/a2-p18-freeup-preview.png), [`a2-p20`](compression/e2e/a2-p20-freed.png) |
| Back up now | `0 uploaded`; no mirror entry `deleted` (removal reasons: `compressed` 7, `freed` 2) | — |

**Found and fixed during the run**: `IS_PENDING` moved the file (dropped); a NULL `DATE_TAKEN` after the write was not
seen as changed (now noted); nothing ran the worker after a phone job started or when Settings › Compress videos
opened (both now do); a restore's consent line said "saves 0 B" (restores have their own sentence); with the phone
target working, the library's `no_write_mount` read as if nothing could be compressed (now "Library videos: …").

**Not run here**: the real NVENC image (the server side is S1/S2/N4's, tested there); an SD card volume; Android 10
(the phone target is shown unusable there, a golden and `CompressModelTest`); the 24 h stalled path of a served output.

Afterwards: the container removed by name, the AVD stopped and deleted from my scratch space, the temporary
directories left in the session's scratch space only.

## 2.8.1: one Gallery cell after a phone replace, "ready to replace" (ADR 0025 Amendment (2.8.1))

Run on cachyos-fps on 2026-11-20, branch `hoard-281` on `main` fcc8895.

### JVM and Robolectric

| Suite | What it checks |
| --- | --- |
| `:core:server` `CompressEngineTest` | a replace or restore report calls `reported` once per run (not while consent waits, not on the run after the sync: no loop), also from the consent path; the file checked again after the dialog (changed meanwhile: abandoned, not written); a partly written file keeps its part and is redone (no new download, no abandon); a started write finishes while unpaired (`written`, nothing reported); `written` saved before the part is deleted (a kill there reports next); the index takes the file's time when MediaStore still describes the old file; the parts folder created before its free space is read; the backup reads the writing paths once per pass |
| `:core:server` `CompressPlanTest` | `PartlyWritten` (also with MediaStore unreadable), never for the source, `changed` for another file |
| `:native` `DevicesTest` | the reported state (the output's fingerprint and size on the phone, the cached item with the original's fingerprint and its mirror's old size at the same place, a `reported` or `written` journal row): **one cell**, on the phone and the server, before and after the sync; the same after a restore; only rows that wrote the file alias |
| `:native` `PhoneCompressSyncTest` | a report schedules `GallerySyncWorker.soon`; a consent run that backs off schedules `CompressWorker.later`; the consent notification is on `phone-compress-consent` at the default importance |
| `:native` `GalleryScreenTest`, `PhoneCompressScreenshotTest` | the banner (text, Continue, gone at 0) on the Gallery and Settings; the writing warning; goldens `gallery_replace_ready_phone_{light,dark}`, `compress_phone_settings_*` (the banner on top), `compress_phone_settings_writing_phone_light` |

### End to end: emulator against my own hoard-server container

Server: `main` built with `-tags hoarde2e` (`HOARD_E2E_FAKE_COMPRESS=1`), the binary mounted into the 0.12.1 image as
`hoard-281-server`, `--cpus 2 --memory 1536m`, `127.0.0.1:3281`, temporary `/data` and `/mnt/backup`; admin set-up and
the pairing link through the admin API, typed into Hoard (`10.0.2.2:3281`). Emulator: AVD `hoard-281` (API 36, in my
scratch space, port 5596, `-memory 3072 -no-window`), the debug build, All files access by `appops`. Three synthetic
720p clips in `DCIM/Camera` (MediaStore owner `com.android.shell`: every replace needs consent).

| Step | Result | Screenshot |
| --- | --- | --- |
| Backup of DCIM/Camera; Compress videos in this folder (2 clips) | the worker downloads both, then waits for consent; the notification is on `phone-compress-consent`, importance 3 (shown) | — |
| Gallery after a sync | banner "2 compressed videos are ready to replace on this phone" · Continue; two cells, both on the phone and the server | [`b281-p01`](compression/e2e/b281-p01-gallery-banner.png) |
| Continue | Settings › Compress videos opens and Android's "Allow Hoard debug to modify 2 videos?" | [`b281-p02`](compression/e2e/b281-p02-consent-dialog.png) |
| Allow | both written (17 MB → 1 MB), reported; "2 reported, the Gallery syncs"; the sync 5 s later; the run after it replaced nothing (no loop); two cells, "Compressed", banner gone | — |
| A third clip backed up and compressed; Gallery synced; dialog open, **the server paused**, Allow | the file written (17 022 165 → 1 032 056 bytes), the report unanswered: journal `written`, `device_media` the output's fingerprint, the cached item the original's (the reported state). **One cell**, on the phone and the server | [`b281-p04`](compression/e2e/b281-p04-gallery-before-sync.png) |
| Server unpaused | the row stayed `written` until Settings › Compress videos ran the worker (found: the consent run did not schedule its back-off; fixed, `PhoneCompressSyncTest`); then reported, the sync brought the swapped item (the output's fingerprint): **one cell** | [`b281-p05`](compression/e2e/b281-p05-gallery-after-report-sync.png) |

**Not run here**: a restore on the emulator (JVM: `DevicesTest`, `CompressEngineTest`); the partly-written and unpaired
paths (JVM only). Afterwards the container was removed by name and the AVD stopped and deleted.
