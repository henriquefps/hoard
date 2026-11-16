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
