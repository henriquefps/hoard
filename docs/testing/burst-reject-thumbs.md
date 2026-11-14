# 1.10.1: burst ✕ and culling thumbnails (end-to-end run)

- **Date**: 2026-11-13/14
- **Device**: Android emulator `hoard-burst` (API 36, x86_64, Google Play image, Pixel 7 profile 1080×2400),
  port 5590, `-no-window`, 4 GB
- **Server**: `docs/testing/xmp-sidecars/samba/Dockerfile` (`photos` writable), 256 MB / 1 CPU, `-p 4456:445`, the
  folder bind-mounted as `photos/shoot`
- **Photos**: 840 RAW+JPEG pairs (`DSC00001`–`DSC00840`), built from one CC0 Sony ILCE-6000 RAW from raw.pixls.us
  (`DSC01542.ARW`, a landscape, no people): reflink copies with the EXIF date strings patched so the shots are 10 s
  apart, except every 20th group of three 1 s apart (42 bursts of 3); the JPEGs are the RAW's preview upscaled to
  6000×4000 (4 MB, like the camera's). Sidecars on (`<stem>.xmp`).
- **Driving**: the debug WebView over CDP (routes by `history.pushState`, the viewer's keys `p` / `x` / Esc,
  `hoardCull.summary()`), native caches with `adb shell run-as dev.hfps.hoard du/find cache/image/…`.

## Thumbnails: root cause

Reproduced on 1.10.0 with the owner's steps: open the folder (every grid thumbnail loaded), cull it in the viewer
(830 key presses, 755 of 840 decided, the other frames of the 42 bursts left undecided; 758 sidecars written on
the share), leave the folder and open it again, scroll.

| 1.10.0                                                   | Measured                                                            |
| -------------------------------------------------------- | ------------------------------------------------------------------- |
| Native `cache/image/screens/` after loading the grid     | 31 MB: ~800 thumbnails (~38 KB each)                                |
| … after culling                                          | 404 MB (cap 384 MB, trimmed every 20 writes): **15** files < 100 KB left, 303 screen images |
| Reopened grid, first screen                              | 27 tiles, **27 broken** ([grid-reopened-1.10.0.png](burst-reject-thumbs/grid-reopened-1.10.0.png)) |
| Reopened grid, scrolled top to bottom                    | **656 broken** `<img>` (complete, `naturalWidth` 0); badges render  |

Thumbnails were written into the same LRU disk cache as the viewer's screen images (`ImageCore.thumbnailFile` →
`screens/`, 384 MB). Culling writes ~0.8–1.5 MB of screen images per shot (fast + final, prefetch), so after a few
hundred shots the trim deletes the oldest files — the thumbnails, written when the grid first loaded and never
touched again, because the JS keeps their file URLs (`thumbnails` `TaskQueue`, 3,000 entries, keyed by folder +
shot + file sizes/mtimes) and never asks native again. On reopening, `ShotThumb` takes the URL from that cache
(`peekThumbnail`), the file is gone, the `<img>` fails, and nothing retried. The sidecars play no part: they are
not photos for `ShotPairing` (`ImageFormats.isPhoto`), so they never enter shots, `others`, the thumbnail source or
any cache key; "all decided" only correlates because deciding everything means viewing everything. The JS cache
lives as long as the WebView, so it shows after leaving and reopening the folder in the same app process (an app
restart clears it and native regenerates the thumbnails).

## Fix

- Native: thumbnails have their own disk cache `cache/image/thumbs/` (64 MB). Measured after loading the grid on
  1.10.1: 13 MB for ~800 thumbnails (~16 KB each), so ~4,000 fit; screen images no longer evict them.
- JS: a tile whose `<img>` fails drops the cached URL and asks native once more (`reloadThumbnail`, URL with a
  cache-busting query); a second failure caches the failure and leaves the muted tile (`data-thumb="failed"`), never
  the broken-image icon. Counted in `hoardCull.summary()`: `thumb:error`, `thumb:reload`, `thumb:failed`.
- `TaskQueue` now leaves the in-flight list when a load settles, before callers resume (a `forget` right after a
  load used to get the old promise back).
- JVM: `CullLogicTest` — 840 pairs with every sidecar naming (`<stem>.xmp`, `<file>.xmp`, `.<stem>.xmp`,
  upper case), listed before, after and between the photos, give exactly the same shots.

## Results on 1.10.1

| Case                                                       | Result |
| ---------------------------------------------------------- | ------ |
| Grid loaded on 1.10.1 (fresh image caches), all thumbnails | 0 broken; `thumbs/` 13 MB for ~800 thumbnails (64 MB cap: ~4,000) |
| ✕ on a burst step (DSC00001–03, "View burst (3)", "1 of 3 decided") | Pass: toast "Rejected 3 photos of the burst" with Undo ([burst-reject-toast.png](burst-reject-thumbs/burst-reject-toast.png)); 211 → 214 rejected; auto-advance to the next step; the three sidecars on the share `xmp:Rating="-1" digiKam:PickLabel="1"` |
| One Undo (Ctrl+Z) after it                                  | Pass: 214 → 211 rejected, 547 → 548 picked (the picked cover restored), back on the burst step |
| Same culling run as on 1.10.0, leave and reopen, scroll     | **Not verified**: the host's low-memory guard stopped the emulator during the 1.10.1 culling run (another VM was using 16 GB); the run was not repeated |
| Thumbnail files deleted under a live grid (`onError` → reload once) | **Not verified** on the device (Vitest covers `reloadThumbnail` / `thumbnailFailed` / `TaskQueue.forget`) |
| Every thumbnail request failed (second failure → muted tile, no broken icon) | **Not verified** on the device (same unit coverage) |
| Screenshot of the reopened 1.10.1 grid                      | **Not taken** (follows from the row above) |

What still argues for the fix without the 1.10.1 reopen run: the eviction was measured directly (thumbnails gone from
`screens/` while screen images filled it to the cap), and 1.10.1 no longer writes thumbnails there; the 64 MB
`thumbs/` cache holds the whole 840-shot folder five times over.

## Automated

- Vitest: `burst.test.ts` (`rejectBurst`: every frame incl. picks, stars kept, one undo step restores each frame,
  toggle back when all rejected, `set` never unmarks, after Keep this one), `images.test.ts` (reload once with a
  fresh URL, second failure cached, no further native calls), `lib.test.ts` (`TaskQueue.forget` / `fail`).
- JVM: `CullLogicTest` (sidecars of every naming never change shots).
