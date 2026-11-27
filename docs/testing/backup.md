# Phone backup (Hoard 2.4.0–2.6.0, ADR 0024 phases A1–A3): tests and the emulator runs

## JVM and Robolectric (`cd android && ./gradlew testDebugUnitTest`)

| Suite | What it checks |
| --- | --- |
| `:core:server` `BackupPlanTest` | kinds by extension and where a file was found (PNG/WebP/GIF stills, DNG from MediaStore, CR3 and `.xmp` by listing); the rules (Wi-Fi before charging, videos while charging); merge of a listing (new, unchanged, changed: upload abandoned, identity from the index); removals only after a complete, non-empty listing, `moved` when the content is elsewhere; every report answer (`stored`, `nas`, `verified: yes`, `missing`, `in_progress` with `retryAfter`, `refused`); uploads newest first and due; backoff; `changed`; a folder's counts |
| `:core:server` `BackupEngineTest` (fake hoard-server 0.12.0, `FakeBackup`) | a pass end to end: `wait: true` and `claim: false` on every upload, small files' SHA-256 from the fingerprint read, nothing reported twice; a NAS duplicate referenced, never uploaded; **a run stopped after two chunks resumes from the stored upload id** (`HEAD`, then the remaining chunks only); `in_progress` waited for `retryAfter`; `checksum_mismatch` → `changed` → hashed again and sent; a revoked key (`401`); `409 covered` and a parent absorbing a child root; a stopped root resumed, `503 storage_not_configured`; a per-entry `refused` and an older server's `422` (the bad entry only); deletions and a rename after a complete listing only (no upload for the rename); the rules; All files access adding RAWs and sidecars and patching the root's `types`; turning a folder off |
| `:core:server` `GalleryMigrationTest` | `gallery.db` 3 → 4: index and queue kept, the new hash columns empty, the backup tables usable |
| `:native` `BackupFoldersTest` | the folder list (roots with their sub-folders, the rest off, grouped Camera, Screenshots, WhatsApp, others, SD cards last), labels, "Add a folder" input (relative, absolute, SD card, hidden refused), the Gallery's badge priority |
| `:native` `BackupScreenshotTest` | goldens (copies in [`backup/goldens/`](backup/goldens/)): `backup_{phone_light,phone_dark,tablet_light}`, `backup_running_phone_light`, `backup_off_phone_light`, `backup_old_server_phone_light`, `backup_files_phone_{light,dark}`; `gallery_settings_paired_phone_dark` gained the Phone backup row |

## Emulator run (2026-11-16)

Emulator `hoard-backup-a1` (API 36 Google Play image, `-memory 3072 -no-window`, port 5580, made for the run and
deleted after), the debug build. My own hoard-server built from the `mirror-formats` branch (3110059: 0.12.0 with PNG,
WebP and GIF for mirrors and per-entry refusals) with [`backup/server.sh`](backup/server.sh): temporary data and backup
folders, the synthetic NAS folder mounted **read-only** at `/mnt/nas`, `127.0.0.1:3994` → `http://10.0.2.2:3994`
typed by hand (pairing link pasted). Synthetic files only: [`backup/make-files.py`](backup/make-files.py) (noise JPEGs,
a PNG, an MP4, a fake CR3 and an `.xmp`; the NAS holds a byte copy of one phone photo). Driven with `adb shell input` and
`uiautomator dump`; the server checked with its admin API (`GET /admin/api/backup`), its log, and the tree and
manifests inside the container ([`backup/e2e/server-tree.txt`](backup/e2e/server-tree.txt)). Container, image, proxy
and emulator removed afterwards.

| Step | Result | Screenshot |
| --- | --- | --- |
| Settings › Photo server › Phone backup, nothing on | Camera and Screenshots listed off with counts | `e2e/e01-backup-off.png` |
| Camera and Screenshots on | the periodic work ran at once (constraints met) as a foreground `dataSync` worker: 4 uploaded, the NAS copy referenced (`nas:nas/Phone/…` in the manifest, no upload); mirror files are hard links (`nlink 2`, mode `0400`); the PNG went with the debounced run 2 minutes later | `e2e/e02-backup-on.png` |
| A 31 MB photo through a 1.5 MB/s proxy ([`tools/proxy.mjs`](tools/proxy.mjs), loopback only), process killed (`run-as … kill -9`) after 2 chunks | the row kept its upload id at 16 MiB; WorkManager ran the work again in a new process: `HEAD`, one `PATCH`, complete, no new `POST /v1/uploads`; the server's file has the phone's SHA-256 | `e2e/e03-uploading.png` |
| Rename `PXL_20261102…` → `Renamed_20261102.jpg` | report `moved`; the tree's link moved to the new name; no upload | — |
| Wi-Fi off (mobile data, metered), new photo, Back up now | "1 waiting for Wi-Fi"; the Gallery cell shows the waiting badge | `e2e/e05-waiting-wifi.png`, `e2e/e06-gallery-badge.png` |
| Wi-Fi on, unplugged (`dumpsys battery unplug`), new video | the photo uploaded, the video "waiting for charging"; plugging in (`dumpsys battery reset`) ran the charging-constrained work by itself, which uploaded the video | `e2e/e07-waiting-charging.png` |
| All files access (`appops … MANAGE_EXTERNAL_STORAGE allow`), back to Hoard | the screen asked for a run; both roots `PATCH`ed to `image, video, raw, sidecar`; `IMG_0001.CR3` (MediaStore does not list it) and `IMG_0001.xmp` uploaded | `e2e/e08-raw-on.png` |
| A photo deleted on the phone | reported `deleted`: the file stays in the tree, `on_device 0` in the manifest | — |

Found and fixed during the run: the charging check used `BatteryManager.isCharging`, which still said "not charging" a
moment after the plug (and is false when full), so the run the charger started paused the video again; it now reads
the battery's sticky broadcast (plugged in = charging, as WorkManager's constraint), and the charging run's own
follow-up is appended instead of dropped by `KEEP`.

Note: the periodic work runs immediately when a first folder is turned on and its constraints hold (WorkManager starts
a new periodic work at once); app start-up only keeps it (`UPDATE`) and never runs a pass itself.

## A2: other devices see backups (Hoard 2.5.0)

### JVM and Robolectric

| Suite | What it checks |
| --- | --- |
| `:core:smb` `ServerTransferFsTest` (the engine with `ServerTransferFs` over a fake `GET /v1/originals`, `TransferRunnerTest` style) | a whole shot (DNG, JPEG, XMP) under each file's own name with the phone's time, one range per file; the destination folder made with its parents; a dropped connection retried and resumed at the byte written (`Range` from 20, nothing sent twice); a body that ends early is a dropped connection, never a short file; a `404` before or during the copy fails that file alone, without retries; a server that goes away stops the job after its retries, leaves the part file, and Retry resumes at its size; a revoked key stops at once; rename and skip on conflicts; read-only; the endpoint survives the job's JSON |
| `:core:server` `FeedTest`, `SyncEngineTest`, `GalleryMigrationTest` | `mirror` locations become `server_item_mirrors` rows (on the phone, freed, an unknown `gone` read as deleted, one without a root skipped), a phone-only item filed as `<phone>/<dir>` and searchable; trashed items keep none; a sync stores `GET /v1/backup/roots` and drops rows an item no longer names; items cached before 4 → 5 are indexed once from their JSON; `openOriginal` from an offset and its `404`; `gallery.db` 4 → 5 keeps the pairing and cursor |
| `:native` `DevicesTest` | spots per item; the badge (one file of a pair on the phone is enough; freed vs deleted; seen from another phone); Where by phone, volume and folder (sub-folders, never a name prefix); the tree (this phone first, roots without shots, counts with sub-folders, SD cards after internal storage, from items alone without roots); this phone's unfingerprinted file merged by place and size only; a fingerprinted copy merged by fingerprint |
| `:native` `SaveToDeviceTest` | whole shots, main file first; `Pictures/From <phone>` per phone, the server's name for NAS photos, one chosen folder for all; the setting round trip; `Pictures/Hoard` and `Download/Hoard` refused; safe folder and file names; `server` is not a machine |
| `:native` `GalleryDevicesScreenTest` | goldens (copies in [`backup-a2/goldens/`](backup-a2/goldens/)): `gallery_devices_{phone_light,phone_dark,empty_phone_light}`, `gallery_backup_grid_{phone_light,phone_dark,tablet_light}`, `gallery_save_device_phone_{light,dark}`; the badge and the Where bar's ✕, a folder pick, Save disabled without All files access |

### Emulator run (2026-11-16)

Emulator `hoard-backup-a2` (API 36 Google Play image, `-memory 3072 -no-window`, port 5582, made for the run and
deleted after). **Two Android users** give the phone and the tablet storage of their own: user 0 runs the debug build
with `-Phoard.native.debugSuffix=.a2phone` ("phone"), user 10 (`pm create-user tablet`, `am switch-user 10`) the one
with `.a2tab` ("tablet"); each paired with its own link and became its own physical device (both carry the emulator's
model name; the tablet lists itself as "This phone"). My own hoard-server image built from this branch's `server/`
(0.12.0, legacy builder with `--build-arg BUILDPLATFORM=linux/amd64 TARGETOS=linux TARGETARCH=amd64`), run with
[`backup-a2/server.sh`](backup-a2/server.sh) on `127.0.0.1:3995` → `http://10.0.2.2:3995` (typed by hand in the pairing
link). Synthetic files from [`backup-a2/make-files.py`](backup-a2/make-files.py). Container, image, emulator and AVD
removed afterwards.

| Step | Result | Screenshot |
| --- | --- | --- |
| Phone: Camera on | 6 of 6 backed up (JPEG, DNG, XMP, three JPEGs; All files access on, so `raw` and `sidecar`) | `e2e/e01-phone-backed-up.png` |
| Tablet: Gallery after pairing | the phone's 4 shots as server items (the pair as RAW+JPG), from previews only | `e2e/e02-tablet-gallery.png` |
| Tablet: Devices › phone › Internal storage › DCIM/Camera | the tree, then the grid with "<phone> backup › Internal storage › DCIM/Camera" and ✕ | `e2e/e03-tablet-devices.png`, `e2e/e04-tablet-where-folder.png` |
| Tablet: opens the 7 MB photo | previews first; the original read with two `206` range responses (4 MiB + 2.9 MiB) and kept in the originals cache (unmetered) | `e2e/e05-tablet-viewer.png` |
| Tablet: Save to this device on the pair | sheet: 1 shot, `Pictures/From sdk_gphone64_x86_64`; a transfer "Photo server → Internal storage / Pictures/From …", Done, 3 of 3 files (3 `HEAD` + 3 `GET`); the cell turns "on the phone and the server" (one cell) | `e2e/e06…e09` |
| Tablet: backs up that folder | 4 of 4 backed up (the pair and a second saved photo) with one report and **no `POST /v1/uploads`** from the tablet (all 6 uploads of the run are the phone's); the tablet's tree files are hard links of the phone's blobs (link count 3, [`backup-a2/e2e/server-tree.txt`](backup-a2/e2e/server-tree.txt)) | `e2e/e10-tablet-backup-linked.png` |
| Phone: deletes `PXL_20261111_110000.jpg`, Back up now | reported `deleted` (`removedFromDevice: 1`); tablet after Sync now: the cell has "Deleted from the phone"; Devices: "4 shots · 1 deleted from the phone" | `e2e/e11-tablet-badge.png`, `e2e/e12-tablet-devices-both.png` |

Found and fixed during the run: on Android 11+ the saved files were indexed with size 0 and time 0 (MediaStore kept
the hidden part file's row through the rename that publishes it), so the tablet's backup would have reported size 0;
`PathTransferFs` now has MediaStore scan the published file (ADR 0024 "Amendment (A2)"). The first save, made before
the fix, was rescanned by hand; the second save, after it, was indexed at once with its size and the phone's time.
Notes: the tablet's pull to refresh did not start a sync in one try (`adb input swipe`), Settings › Sync now did; the
fake DNG has no thumbnail on the tablet (Android cannot decode it), as expected for that synthetic file.

## A3: free up space (Hoard 2.6.0)

### JVM and Robolectric

| Suite | What it checks |
| --- | --- |
| `:core:server` `FreeUpPlanTest` | whole shots oldest first (a pair with its sidecar; a pair whose RAW is still uploading stays whole); NAS references count as backed up; exclusions: the last 30 days, favourites, pending culling, a shot of listed files only, failed, refused, reported before it changed; both settings off; shots per folder and volume, paused roots left out; **the Reveal gate**: no Reveal (off), a live Reveal without `freed-adopt` (on; the debug build too), seen 31 minutes ago (stale: nothing goes), with `freed-adopt` (off), never seen or not for 90 days (off), two Reveals one without it (on); verify answers `yes` / `pending` (asked again at `retryAfter`) / `no` (the whole shot stays with the reason) / `refused` / unknown; a `yes` older than 10 minutes asked again; a shot named by a record through one file of a pair kept while the gate is on, not with it off; requests of at most 1000 media files with whole shots; a partial consent (one file of a pair: reported alone, its sidecar stays) and a refused one; a lost `freed` report found again |
| `:core:server` `FreeUpEngineTest` (fake hoard-server 0.12.0: `FakeBackup` with verify and `GET /v1/device`) | backed up by `BackupEngine` first, then: the preview (culling pushed, the 30-day rule), the check, the deletion reported `freed` and kept in `freed`, the answers stale after 10 minutes; `pending` twice then `yes`, `differs` and `refused` kept; pending for too long kept; the gate on (the named shot kept, the other freed), stale (nothing verified), `freed-adopt` (both go); a file rewritten in place after its backup kept (`changed`), a 3 MB NAS reference hashed by the check, then verified; a partial consent (a still deleted, its video and sidecar kept; a whole shot's sidecar deleted after its media); a report that fails is made as `freed` by the next backup run; no `backup` feature, no free up |
| `:native` `FreeUpMonthsTest` | months oldest first in the phone's zone (a shot at 23:30 UTC on 31 August is September in Tokyo), counts, bytes, shots left out; "Reveal not grouped" only with Reveal installed and no live Reveal in the group |
| `:native` `FreeUpScreenshotTest` | goldens (copies in [`backup-a3/goldens/`](backup-a3/goldens/)): `freeup_preview_{phone_light,phone_dark,tablet_light}` (one shot left out), `freeup_reveal_on_phone_light`, `freeup_reveal_stale_phone_light` (button off), `freeup_checking_phone_light`, `freeup_result_phone_light` (freed, declined, kept for Reveal, reasons), `freeup_result_none_phone_dark`, `backup_freeup_phone_{light,dark}` (Settings › Backup with Free up space, the freed total and the "Reveal not grouped" hint) |

### Emulator run (2026-11-16)

Emulator `hoard-a3` (API 36 Google Play image, `-memory 3072 -no-window`, port 5600, made for the run and deleted
after), the debug build. My own hoard-server image built from main's `server/` (0.12.0, legacy builder) and run with
[`backup-a3/server.sh`](backup-a3/server.sh) on `127.0.0.1:3996` → `http://10.0.2.2:3996`, typed by hand in the pairing
link. Synthetic files from [`backup-a3/make-files.py`](backup-a3/make-files.py) (noise JPEGs with an EXIF capture time
in August and September 2026, a fake DNG and an `.xmp` for a pair, one taken today), pushed to `DCIM/Camera`, times set
with `touch -d`, All files access granted. Reveal was simulated with `curl`: a device paired with `"app": "reveal"`,
claimed into the phone's physical device (`POST /v1/device/claim`), and a `photo` record (`POST /v1/changes`) naming
`PXL_20260816_090000.jpg`'s fingerprint. Driven with `adb shell input` and `uiautomator dump`. Container, image, work
folders, emulator and AVD removed afterwards.

| Step | Result | Screenshot |
| --- | --- | --- |
| Camera on | 9 of 9 backed up (the pair's DNG and `.xmp` included) | — |
| The server copy of `PXL_20260815_090000.jpg` damaged in my container (8 bytes overwritten in its blob, in place: the mirror's hard link has the same inode) | — | — |
| Free up space, Reveal live without `freed-adopt` | preview: "6 photos from before Oct 17, 2026 · 4.1 MB", August (4) and September (2), the Reveal note, "Staying on the phone: 1 from the last 30 days" | `e2e/e01-preview-reveal-gate.png` |
| Free up, the system dialog **denied** | "Checking on the server…", then Android's "Allow Hoard debug to delete 5 photos?"; denied: "Nothing was deleted. 4 shots stay", "Kept for Reveal: 1", the damaged one stays ("The server's copy is not the same"); every file still on the phone | `e2e/e02-checking.png`, `e2e/e03-consent.png`, `e2e/e04-denied.png` |
| Free up again with `freeup-request-size` = 1 (one dialog per shot): allow, allow, allow, **deny** | "Freed 5 files · 3.3 MB" (the lone photo, the pair's JPEG and DNG, then its `.xmp`, and the first September photo), "1 shot stays: Android did not delete it"; the damaged and the Reveal-named photos stay. The manifest: exactly those 5 entries `on_device 0`, `freed`; the others `1` | `e2e/e05-consent-denied-last.png`, `e2e/e06-result-partial.png` |
| The simulated Reveal declares `freed-adopt` (`PATCH /v1/device`) | the Reveal note is gone; the named photo is a candidate | `e2e/e07-preview-freed-adopt.png` |
| One shot left out by tapping it, Free up, allow | "Freed 1 file · 312 KB" (the Reveal-named photo); the photo left out and the damaged one stay | `e2e/e08-result-freed-adopt.png` |
| Settings and the Gallery after a sync | "3.6 MB freed · 6 files"; the freed photos are server items with the "Freed from the phone" badge, the record's rating (3 stars) on the Reveal-named one | `e2e/e09-settings-freed.png`, `e2e/e10-gallery-after.png` |

The server's tree after the run: [`backup-a3/e2e/server-tree.txt`](backup-a3/e2e/server-tree.txt) (freed files keep
their hard links in the phone's folder). Found and fixed during the run: the debug override of the request size was
first read from the app's external files folder, which `adb shell` can write but the app could not read; it is now
read from the app's own files folder (`run-as`). The preview's cover of a RAW + JPEG pair is the JPEG.

### After the PR review (2026-11-16)

New JVM tests: `FreeUpPlanTest` (`aYesCountsOnlyOnAServerRehashAtMost24HoursOld`, `theGateFailsClosedWhenRevealIsInstalledButNotGrouped`, `aRequestTakesOnlyFilesStillAsCheckedAndMediaIdsStillNamingThem`: an id naming another file, a file rewritten, a MediaStore row gone, a sidecar rewritten never deleted), `FreeUpEngineTest` (`theRevealGateUsesTheServersClock` with the phone's clock two hours ahead and one behind, `revealInstalledButNotGroupedFreesNothing`, `theCheckHashesTheCurrentBytesNotTheCachedHash` with the old time put back, `verifyAsksForARehashAtMost24HoursOld` against a server that honours `maxAge`, one that ignores it and one without `Date`, `aRequestLeftWaitingForConsentBySomeoneKillingTheProcessIsSettledAsFreed`, `aSidecarRewrittenAfterTheCheckIsNeverDeleted`); `FakeBackup` sends `Date` and honours `maxAge`. Golden `freeup_reveal_ungrouped_phone_dark`.

Emulator rerun: a new `hoard-a3` AVD (port 5600) and my own hoard-server built from `server-0121-verify-maxage`
(317a25c, 0.12.1 with `maxAge`), the same synthetic files, the same damaged blob and simulated Reveal; request size 1.

| Step | Result | Screenshot |
| --- | --- | --- |
| Preview | the same as before: 6 photos, the Reveal note, 1 kept from the last 30 days | `e2e/r01-preview.png` |
| Free up: allow, allow, deny, then **`kill -9` of Hoard's process while the 4th dialog is up**, then Allow | the first two requests freed the lone photo and the pair (JPEG, DNG, then its unchanged `.xmp`); the third (denied) stays; `free-up-pending.json` held the 4th request before its dialog; the dialog outlived the process and Android deleted the photo | `e2e/r02-dialog-before-kill.png` |
| Hoard started again | "settled a request left waiting for consent: 1 files freed": the manifest has `PXL_20260902_080000.jpg` `freed` (never `deleted`); the pending file is gone | `e2e/r03-after-restart.png` |
| The server's re-hash of `PXL_20260901_080000.jpg` aged by 2 days in its database (container stopped, `sqlite3`), then Free up | verify answered `pending` (with `maxAge` the server re-hashed), 30 s later `yes`; that photo freed (its `verified_at` then 52 s old); the damaged copy stays ("not the same"), "Kept for Reveal: 1" | `e2e/r04-rehash-24h.png` |

Not on the emulator (JVM only): an id naming another file after a media index rebuild, a sidecar rewritten during the
dialog, and the "not grouped" gate (no Reveal build was installed). During the rerun another session launched its own
app (`com.hfps.dbinspector.sample`) on this AVD; it was left alone, and the AVD was deleted after the run.

## Emulator run (0.14.0): Hoard 2.9.0 after the admin purges its phone's backup

Run on cachyos-fps on 2026-11-26 (ADR 0024 "Amendment (0.14.0)"). Server: this branch's hoard-server built with
`-tags hoarde2e` (`HOARD_E2E_FAKE_COMPRESS=1`, the fake libx264 encoder), in a container `w0140-g-server` of my own
`-nvidia` image build (its ffmpeg, vips and exiftool; no GPU used), `--cpus 2 --memory 1536m`,
`-p 127.0.0.1:3297:3071`, temporary `/data` and `/mnt/backup`. App: Hoard 2.9.0 debug from `main` (545bc04), on my own
AVD `w0140-g` (API 36, port 5598, `-memory 3072 -no-window`), All files access and media permissions granted, paired
with the link typed by hand. Synthetic files: four noise JPEGs and two 6 s 720p H.264 clips in `DCIM/Camera`. The
container, the image, the emulator and the AVD were removed afterwards. Screenshots: [`backup-purge-0140/`](backup-purge-0140/).

| Step | Result |
| --- | --- |
| Settings › Phone backup: Camera on | 6 of 6 backed up |
| Camera › Compress videos in this folder › Start; Settings › Compress videos › Allow (Android's dialog: Allow) | both clips `replaced` (16.5 → 1.8 MB on the phone) |
| Free up space ("Keep the last 30 days" off), two JPEGs and one compressed clip | "Freed 3 files · 4.1 MB"; server: 8 entries, 5 pinned, `compressedKept` 2 |
| Gallery before the purge | freed cells "On the server · Freed from the phone", "Compressed" badges ([`g01`](backup-purge-0140/g01-gallery-before.png)) |
| Admin purge with `confirmPinned: 5` only | `409 confirm_pinned`, `compressedKept: 2` |
| Admin purge with `confirmPinned: 5, confirmCompressed: 2` | `compressPurged: 2`, `removedFiles: 8`; no blob left; no phone in `GET /admin/api/backup`; both compression rows `purged` (`backup_purged`), no entry id left |
| Hoard: Sync now, Gallery | **clean**: the freed cells and the Compressed badges are gone (the items are tombstones in the feed); the three files still on the phone show "On this phone" (not backed up); the Devices button is gone (no other device's backup) ([`g02`](backup-purge-0140/g02-gallery-after.png)) |
| Settings › Phone backup | **stale**: "1 folder · 3 of 3 backed up", "3 of 3 backed up · 4.1 MB" ([`g03`](backup-purge-0140/g03-backup-after-purge.png)) |
| Back up now (and the periodic runs) | **stale**: `backup run: Done, 0 uploaded`; the server still has no phone, no root and no blob: nothing is backed up again |
| Settings › Phone backup › Free up space row | **stale**: "4.1 MB freed · 3 files" and "Freed photos stay in the Gallery from the server" (they are gone) ([`g04`](backup-purge-0140/g04-backup-freed-after.png)) |

**Why the backup never starts over.** A pass reports only rows that are due (`BackupPlan.toReport`); rows already
`stored` are not, so no request names the purged root, and `ensureTypes` only `PATCH`es when the types change. The 404 →
`POST` path (`recreate`) exists but is never reached. `GET /v1/backup/roots` is read at the end of a pass for the stats
only. Nothing server-side can trigger it (the server answers what it is asked; it cannot tell an app its backup was
purged other than by the roots list).

**App fixes (a small Hoard PR; nothing changed in the app here):**

1. At the start of each pass, read `GET /v1/backup/roots` (it is already read at the end): a local root whose id the
   server does not list (and that is not paused) is **gone**: `recreate` it (`POST /v1/backup/roots`, which already
   handles `covered`), and reset that root's rows: `stored`/`nas`/verified rows back to *identified, not reported*
   (keep fingerprints and SHA-256), upload ids dropped, so the same pass reports them (`missing`) and uploads them again.
2. Rows `freed` of a root the server forgot: the phone has no file and the server no copy. Drop them (and their
   `freed` history), so Settings › Phone backup stops counting "4.1 MB freed · 3 files" and the "Freed photos stay in
   the Gallery from the server" note is not shown for them. The Gallery already drops them at the sync.
3. Compression rows (`compress_phone`) of the purged items: settled rows need nothing; an unsettled one gets `404` from
   `…/replaced`/`…/output` and is abandoned by the existing rules (not seen in this run: both were reported).
4. Optional: when `GET /v1/backup/roots` is empty and the account had roots, say it once on Settings › Phone backup
   ("The photo server's backup of this phone was deleted; Hoard is backing it up again").

## Hoard 2.9.1: after an admin purge, and a full backup volume (ADR 0024/0025 "Amendment (2.9.1)")

### JVM and Robolectric

| Suite | What it checks |
| --- | --- |
| `:core:server` `BackupPurgeTest` (fake hoard-server 0.14.0: purge, `507 storage_full`) | one root of two purged: made again with a new id, its files reported and uploaded again in the same pass, its `freed` history dropped (the other root's kept), said once; a whole purge; the roots list read each pass; a stopped root not reset; a full volume at `POST /v1/uploads`: one request, rows `queued` with no attempt and no error, the wait's figures and back-off (1 h with `Retry-After: 3600`), no upload before it is due, Back up now tries again, resumed and cleared once there is room; a disk that fills during a `PATCH`: one refused chunk, never retried, the upload id kept, resumed with `HEAD`; the pure back-off and reset |
| `:core:server` `CompressEngineTest` ("after a purge …") | a write whose `…/replaced` answers `404` is settled (`reported`, no report) and the restarted backup uploads the output; a row waiting for consent is dropped without a write; a download answered `404` is abandoned |
| `:core:server` `CompressModelTest`, `GalleryMigrationTest` | `SpaceWait` (0.14.0 `space`, `failed.no_space`, never under failures); `gallery.db` 6 → 7 |
| `:native` `BackupWaitTextsTest`, `CompressReasonTextsTest` | the new sentences in English and Portuguese |
| `:native` `BackupScreenshotTest`, `CompressScreenshotTest` | goldens (copies in [`backup-291/goldens/`](backup-291/goldens/)): `backup_restarted_phone_{light,dark}`, `backup_server_full_phone_{light,dark}`, `compress_settings_space_wait_phone_{light,dark}` |

### Emulator run (2026-11-26)

On cachyos-fps. Server: `origin/server-0140` (5d34104, hoard-server 0.14.0) built locally (`go build`, web gallery not
embedded) over the tools of `hoard-server:0.13.2-nvidia`, as my own image `hoard291-server:test`; container
`hoard291-srv`, `--cpus 2 --memory 1536m`, `127.0.0.1:3391`, temporary `/data`, the backup root `/mnt/backup` on an
**800 MB tmpfs**. App: this branch's debug build on my own AVD `hoard291` (API 36, port 5594, `-memory 3072
-no-window`), media permissions and All files access granted, paired with the link typed by hand
(`http://10.0.2.2:3391`). Synthetic files: six noise JPEGs (1.7 MB each) in `DCIM/Camera`, dated October 2026. The
container, the image, the emulator and the AVD were removed afterwards. Screenshots: [`backup-291/e2e/`](backup-291/e2e/).

| Step | Result |
| --- | --- |
| Settings › Phone backup: Camera on ("Only on Wi-Fi" off: the emulator's network is metered) | 5 of 5 backed up |
| Free up space, two photos, Android's dialog: Allow | "Freed 2 files · 3.3 MB"; Settings: "3.3 MB freed · 2 files" and the "Freed photos stay…" note ([`p02`](backup-291/e2e/p02-before-purge-freed.png)) |
| Admin purge without confirmation, then `{"confirmPinned": 2, "confirmCompressed": 0}` | `409 confirm_pinned` (`pinned: 2`); then `removedFiles: 5`, no phone in `GET /admin/api/backup`, the tmpfs empty |
| Hoard: Back up now | `1 folder(s) gone from the server (purged): backing them up again`, `Done, 3 uploaded, … 1 folder(s) started over`; the server: a new root, 3 files stored; `gallery.db`: `freed` empty, `backup_restarted_at` set |
| Settings › Phone backup | "The photo server's backup of this phone was deleted; Hoard is backing it up again" with OK; no freed totals, no "Freed photos stay…" note ([`p05`](backup-291/e2e/p05-restarted-notice.png), [`p06`](backup-291/e2e/p06-restarted-freeup.png)); OK clears it |
| A 600 MB filler in the backup tmpfs (196 MB free, under the 512 MiB reserve), a new photo, Back up now | one `POST /v1/uploads` → `507 storage_full`; `ServerFull, 0 uploaded, waiting for room until` +1 h; the row `queued`, attempts 0, no error; the folder: "3 of 4 backed up · Waiting: the photo server's drive is full (195 MB free)" ([`p07`](backup-291/e2e/p07-server-full.png)) |
| Back up now again, still full | one more `507` (two in all), the wait renewed; no other request |
| Filler removed, Back up now | `Done, 1 uploaded`; "4 of 4 backed up"; `backup_wait` cleared ([`p08`](backup-291/e2e/p08-resumed.png)) |
| A second purge with the screen open, Back up now | 4 uploaded again; the notice appears at the top of the open screen ([`p09`](backup-291/e2e/p09-second-purge-notice.png)) |

Found and fixed during the run: the notice was first a list item of its own above the summary; with the screen
already open (scrolled to its top) the list kept the summary in place and the new item appeared above the visible
area, so the owner would not have seen it. It is now part of the first item.
