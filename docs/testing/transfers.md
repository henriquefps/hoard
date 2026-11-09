# Transfers between any locations, share in and out (1.4.0)

- **Date**: 2026-11-09
- **Build**: debug APK (CDP-driven WebView and real taps), then the signed R8 release APK
  (`skv run hoard -- ./gradlew assembleRelease`) for a smoke test.
- **Device**: Android emulator `hoard-transfers` (API 36.1, arm64, Google Play image, 2 GB),
  `-port 5556 -no-window`; a virtual public volume (`sm set-virtual-disk true`, 511 MB vfat) as
  the USB drive / SD card.
- **Server**: the disposable Samba in `docs/testing/samba/` (Docker, 256 MB / 1 CPU) on `:4460`,
  with a 200 MB video and six 3 MB photos added, writable share `media`; removed afterwards.
- **Route faults**: `docs/testing/tools/proxy.mjs 4461 4460 <rate> both` (now throttles uploads
  too), killed mid-transfer.

## How it was driven

Engine-level cases call the plugin directly in the app's WebView over CDP
(`docs/testing/tools/cdp.mjs`, `Capacitor.Plugins.HoardSmb.startTransfer(...)`), watching
`transferProgress` and reading `getTransferItems`; the files were then compared with `sha256sum`
on the emulator and in the container. UI cases tap real coordinates (`adb shell input`) found
through CDP, and Android's own Files app for the share sheet.

## Results

| Case                                                                                                                                                                                         | Result                                                                                                                                                                                                                                                                                   |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SMB folder → phone (download, 6 files)                                                                                                                                                       | Pass: scan → running → completed in 1.9 s, SHA-256 identical, modified times kept                                                                                                                                                                                                        |
| Phone → SMB upload, 150 MB, route killed at ~56 MB                                                                                                                                           | Pass: `retrying`, failed over to the second address, **resumed at 56,623,104** (persisted, acknowledged-in-order offset), SHA-256 identical, mtime kept on SMB                                                                                                                           |
| SMB → USB volume, app process killed (`am crash`) at 50 MB                                                                                                                                   | Pass: new process, WorkManager reran the job, completed, SHA-256 identical                                                                                                                                                                                                               |
| Move inside the phone (`DCIM/Camera` → `Archive`)                                                                                                                                            | Pass: one item, one `rename(2)`, no byte read (instant)                                                                                                                                                                                                                                  |
| Move phone → USB volume (cross-filesystem)                                                                                                                                                   | Pass: copied, verified, sources deleted, emptied folder removed                                                                                                                                                                                                                          |
| Free space: copy needing 159 MB onto a volume with 135 MB free                                                                                                                               | Pass: `NO_SPACE` before any byte ("Needs … free at the destination"); Retry after freeing space completed                                                                                                                                                                                |
| SMB → same share (`Shoot/C0001.MP4` → `Uploads`)                                                                                                                                             | Pass: server-side copy (FSCTL_SRV_COPYCHUNK), 200 MB in 0.3 s, SHA-256 identical                                                                                                                                                                                                         |
| `conflict: "ask"`, 6 existing names                                                                                                                                                          | Pass: stopped in `conflict` with 6, `resolveTransferConflicts(all: rename)` → `name (1).JPG`                                                                                                                                                                                             |
| Volume removed mid-copy (`sm set-virtual-disk false`)                                                                                                                                        | **Failed → fixed**: vold interrupts the app (it holds a file on the volume); after the restart the job stayed `running` forever (the endpoint failed to open outside the runner's error handling). Now it fails `UNAVAILABLE` with the finished items kept (JVM test added)              |
| Volume unmounted mid-copy (`sm unmount`), remounted, Retry                                                                                                                                   | Pass: failed `UNAVAILABLE` (6 of 7 done, the video at 24 MB), Retry **resumed at 27,262,976** from the partial file, SHA-256 identical                                                                                                                                                   |
| UI: long-press select, Copy, navigate to the SD card, Paste here                                                                                                                             | Pass (clipboard bar, transfer, history row)                                                                                                                                                                                                                                              |
| UI: Move to… → picker → machine → share → folder, "Ask me" with an existing name                                                                                                             | Pass: conflict dialog, Keep both → `C0001 (1).MP4`; batch progress, speed, current file, host; sources removed from the SD card                                                                                                                                                          |
| Share in: Files app → select 2 photos → Share → Hoard → Save to… SMB share                                                                                                                   | Pass: Hoard in the share sheet, Save to Hoard, files on the server, SHA-256 identical                                                                                                                                                                                                    |
| Share in: hostile URIs (`am start … SEND --eu android.intent.extra.STREAM file:///data/data/dev.hfps.hoard/databases/hoardSQLite.db`, and `content://dev.hfps.hoard.fileprovider/storage/…`) | Pass: both refused (logged with scheme and authority only), "The shared item could not be received", nothing read; a `content://media/…` share right after still works. `am` has no URI-list extra, so a mixed batch (some refused, some kept) is covered by the JVM and mock tests only |
| Share out: SMB photo (cached) + 200 MB video (streamed) → share sheet                                                                                                                        | Pass: chooser "Sharing 2 files"; shared back into Hoard and saved to the phone, the streamed video read through the proxy file descriptor, SHA-256 identical                                                                                                                             |

## Release build (R8, signed)

Share from the Files app → Hoard → Save to… Internal storage: the WorkManager job ran under R8,
the file landed with an identical SHA-256, the transfers page showed it. SMB transfers were not
repeated on the release build.

## Not verified

- SMB transfers on the release (R8) build.
- A real USB-C card reader and a real phone (emulator only, as required: the owner's phone was not
  touched).
- A silently dead route during an **upload** (`kill -STOP` on the proxy): the write path uses the
  same 15 s stall timeout as reads, unit-tested only through the failover plan.
- SAF `tree` destinations and volumes read through SAF grants (no all files access) on a device;
  the code paths compile and share `DocumentTransferFs`, tested only in the browser mock.
- Thousands of files on a device (the browser mock's 648-file and 20,000-entry folders were used
  for the UI; the engine's per-item persistence was not timed with 20,000 items).
- A 10 GB file (largest on device: 200 MB).
- SMB servers other than Samba (macOS, Windows) for uploads, server-side copy and modified times.
