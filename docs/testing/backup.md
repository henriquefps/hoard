# Phone backup (Hoard 2.4.0, ADR 0024 phase A1): tests and the emulator run

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
