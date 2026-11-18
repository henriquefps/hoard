# Free up space: release acceptance (Hoard 2.6.0 + Reveal 0.10.0, hoard-server 0.12.1), 2026-11-18

The release acceptance of ADR 0024's "Free up space" (A3), run before the owner tests it himself. It used the signed
**release** APKs, not debug builds:

- Hoard 2.6.0 (`dev.hfps.hoard`, versionCode 260), built from 4293917, sha256
  `eaa7acc37cd2539e278474589109deb5ad9319b9960fabf440680ddd903b7857` (checked);
- Reveal 0.10.0 (`dev.hfps.reveal`, versionCode 100000), from its GitHub release, sha256
  `9d2bf8a163898f8cb4dc78375026080fc5c0e359d916dd0763e86ecd76bd0ad3`.

No debug switch was needed: with the release request size (1000), one system dialog covers the whole free up.

**Setup.** A new AVD `hoard-freeup-acc` (API 36 Google Play image, `-memory 3072 -no-window`, port 5610), created for
the run and deleted afterwards. My own hoard-server image `hoard-freeup-acc-srv:0.12.1`, built from `server/` at
1b7a30a (`server-v0.12.1`; legacy builder, `VERSION=0.12.1`). It ran with
[`../backup-a3/server.sh`](../backup-a3/server.sh), with the container renamed and port 3998, so
`http://10.0.2.2:3998` was typed by hand in each app's pairing link. It used temporary data and backup folders and the
synthetic NAS folder mounted read-only. The synthetic files came from [`../backup-a3/make-files.py`](../backup-a3/make-files.py).
They were pushed to `DCIM/Camera`, their times set with `touch -d` and scanned into MediaStore. All files access was
granted with `appops`.

The apps were driven with `adb shell input` and `uiautomator dump`. The server was checked with its admin API, its
request log, and a copy of its SQLite database (`docker cp`). The raw outputs are in [`evidence/`](evidence/).

| # | Step | Result | Screenshots |
| --- | --- | --- | --- |
| 1 | Pair Hoard, then turn Camera on in Phone backup | **Pass.** "1 folder · 9 of 9 backed up" (the pair's DNG and `.xmp` included). Admin API: `files 9, stored 9`. The manifest has 9 entries `blob`, `on_device 1` ([`evidence/manifest-after-backup.tsv`](evidence/manifest-after-backup.tsv)). Phone backup already showed "Reveal on this phone is not grouped with Hoard". | `s01a-paired-settings.png`, `s01b-backed-up.png` |
| 2 | Reveal installed, not paired or grouped: Free up space | **Pass (fails closed).** The preview lists 6 photos · 4.1 MB with "Reveal is installed on this phone but not grouped with Hoard on the server: nothing is freed until you group them (Reveal › Settings › Sync › This device)." The **Free up** button is disabled (`enabled=false`), and a tap does nothing. No `POST /v1/backup/verify` was sent ([`evidence/routes-until-step2.txt`](evidence/routes-until-step2.txt)), and all 9 files stayed on the phone. | `s02-preview-reveal-ungrouped.png` |
| 3 | Pair real Reveal 0.10.0 with the same server; answer "Another device" to its question; group it in Reveal › Settings › Sync › This device › **Same phone**; add `DCIM/Camera` in Reveal; rate `PXL_20260816_090000.jpg` 3 stars; Sync now | **Pass.** This device lists Hoard under "Apps on this phone". The server DB has the Reveal device (`app reveal`, 0.10.0) in Hoard's physical device with **`features = freed-adopt`** ([`evidence/devices.txt`](evidence/devices.txt)); Hoard declares none. Reveal pushed a `photo` record for every photo of the folder: `PXL_20260816_090000.jpg` with rating 3, and the pair with rating 4 from its `.xmp` ([`evidence/reveal-photo-records.txt`](evidence/reveal-photo-records.txt)). Hoard: Settings shows "Grouped with reveal", the "not grouped" hint is gone, and the preview has **no "Kept for Reveal" note**; the Reveal-named photos are candidates. | `s03a`…`s03f` |
| 4 | Server copy of `PXL_20260815_090000.jpg` damaged in place (8 bytes at offset 100000 in its blob, in my container; the mirror's hard link has the same inode, 535754, with link count 2 and the same size) | **Pass.** At every free up the server re-hashed it as `967174d8…`, not `62caf436…` ([`evidence/blob-verifications-after-deny.txt`](evidence/blob-verifications-after-deny.txt)). The consent dialog left it out ("6 photos": the other five shots, with the pair as JPEG + DNG). The result says "The server's copy is not the same: PXL_20260815_090000". The file stayed on the phone, and the manifest still has it `on_device 1`. | `s06c-denied.png`, `s06e-result-freed.png` |
| 5 | Stored re-hash of `PXL_20260901_080000.jpg` (`9aae6dc0…`) aged by 2 days: container stopped, `UPDATE blob_verifications SET verified_at = verified_at - 2 days` with `sqlite3`, ownership restored, container started; then Free up | **Pass.** `verified_at` went from 2026-11-18 03:26:29 to 2026-11-16 03:26:29. At the tap, `POST /v1/backup/verify` sent all files at 03:27:53 (615 bytes), then only one entry 30 s later at 03:28:23 (91 bytes): the `pending` one, asked again at its `retryAfter`, and then `yes`. The server's row now says 2026-11-18 03:27:53, so the re-hash was redone ([`evidence/blob-verifications-after-allow.txt`](evidence/blob-verifications-after-allow.txt)). The photo was then freed. | `s05a-checking-pending.png` |
| 6a | Free up, system dialog **denied** | **Pass.** "Checking on the server…", then "Allow Hoard to delete 6 photos?". After Deny: "Nothing was deleted. 5 shots stay: Android did not delete them", plus the damaged photo with its reason. All 9 files were still in `DCIM/Camera`. | `s06a-checking.png`, `s06b-consent-6.png`, `s06c-denied.png` |
| 6b | Free up again, **allowed** | **Pass.** "Freed 7 files · 3.8 MB". The 7 files are the 5 shots: the pair's JPEG and DNG, then its unchanged `.xmp`. The 7 manifest entries are `on_device 0`, `freed`; the damaged photo and today's photo stay `1` ([`evidence/manifest-after-freeup.tsv`](evidence/manifest-after-freeup.tsv)). Admin stats: `removedFromDevice 7, freed 7, verified 7, pinned 7`. MediaStore lists only `PXL_20260815_090000.jpg` and `PXL_20261116_090000.jpg`. The server tree keeps every freed file's hard link ([`evidence/server-tree.txt`](evidence/server-tree.txt)). Settings: "3.8 MB freed · 7 files" on the Phone backup row and in Free up space. Gallery after a sync: the freed shots are server items with "On the server" and "Freed from the phone", and the 3 and 4 stars are shown. | `s06d`…`s06i` |
| 6c | Reveal opened afterwards | **Pass.** The first sync while Reveal's process was still running found nothing vanished (Reveal walks a folder at library start). After a cold start, logcat shows `RevealSync: vanished folder photos: FreedReport(adopted=5, deleted=0, kept=0)` and no crash (the crash buffer is empty). All 7 photos are still in Reveal's library; the 5 freed ones say "Original only on the server". `PXL_20260816_090000.jpg` opens full size from the server with **3 stars**, and the server's record still has rating 3 with no tombstone ([`evidence/reveal-photo-records-after-adopt.txt`](evidence/reveal-photo-records-after-adopt.txt)). With the Camera folder filter on, only the 2 photos left on the phone are listed, as expected: the adopted photos moved to the Server source. | `s06j…s06l` |
| 7 | Gallery: select the freed `PXL_20260902_080000.jpg`, then **Save to this device** | **Pass.** The sheet offered "1 shot … Pictures/From sdk_gphone64_x86_64", and Save completed. The file is back at `Pictures/From sdk_gphone64_x86_64/PXL_20260902_080000.jpg`: 230651 bytes, sha256 `a968d992…`, the same as the backup, with the phone's time (2026-09-02 08:00). MediaStore indexed it, and the server served it (`GET /v1/originals` `200`/`206`). | `s07a-save-sheet.png`, `s07b-gallery-after-save.png` |
| 8 | Against hoard-server 0.12.0 | **Skipped** (optional): it needs another image and a full rerun. The JVM `FreeUpEngineTest` covers a server that ignores `maxAge`. | — |

## Observations (no bug found)

- **The saved copy's cell has two badges.** After step 7, the cell has both "Backed up: on this phone and on the
  server" and "Freed from the phone". This follows `Devices.offPhone`: the off-phone badge reads only the backup's
  mirror places (`DCIM/Camera` was freed), and `Pictures/From …` is not a backed-up folder. It is correct, but the
  combination can read oddly. A copy only stops counting as freed if that folder is backed up.
- **Reveal adopts only after a cold start.** Reveal adopts freed photos at its next library walk, which happens at
  library start. While its process stays alive, the freed photos keep their old cells until it restarts. This is
  Reveal's documented rule (Reveal ADR 0006 "A source is indexed … on every library start"; ADR 0018 R1).
- **App name shown as an id.** Hoard's Settings › This phone says "Grouped with reveal": it shows Reveal's `app` id,
  lower case.
- **Real Reveal records name every photo.** Real Reveal 0.10.0 pushes a `photo` record for every photo of a folder it
  indexes, not only rated ones. Every shot is therefore "named by a record" once Reveal knows the folder, which makes
  `freed-adopt` essential: without it, nothing in a Reveal folder could be freed.

## Cleanup

Removed after the run, by name: the container `hoard-freeup-acc-srv`, the image `hoard-freeup-acc-srv:0.12.1`, the
AVD `hoard-freeup-acc`, and `/tmp/hoard-freeup-acc` (APKs, work folders, DB copies). No other container, AVD or
device was touched.
