# Web gallery: end-to-end tests, screenshots and measurements

The web gallery of hoard-server ([ADR 0018](../adr/0018-web-gallery.md)), tested in a browser against the real
binary. Everything runs on temporary data under `server/web/e2e/.library/` (git-ignored), on ports 3990-3991 bound to
`127.0.0.1`; nothing touches a deployed server, its data or a NAS.

## How to run

```sh
cd server/web
bun install
bun run lint && bun run typecheck && bun run test     # unit tests: culling keys, filters, layout, tree, zoom, locales
bun run e2e                                           # Playwright: builds the web app and the binary, writes the
                                                      # synthetic library, starts the server, runs e2e/gallery.spec.ts
HOARD_WEB_SCREENSHOTS=1 bun run e2e                   # also writes the screenshots below
HOARD_WEB_BENCH=1 bun run e2e -- e2e/perf.spec.ts     # + a 20,000-item library and the measurements
cd .. && go vet ./... && go test -race ./... && golangci-lint run ./...   # server side, incl. internal/server/web_test.go
```

Needs Go (from `server/go.mod`), bun, ffmpeg and libvips on `PATH`, and Playwright's Chromium (`@playwright/test`
1.64.1, Chromium 1248; `bunx playwright install chromium` once).

**The library** (`TestWriteWebFixture` in `server/internal/server/web_fixture_test.go`, opt-in): 36 items from 41 files,
all synthetic and free to publish. Real, decodable JPEGs drawn in Go (a gradient, a sun, a horizon and a fine
checkerboard at the centre for checking 100 %) with synthetic EXIF (four cameras, lenses, exposure, GPS, offsets),
2400 × 1600 and one 6000 × 4000; four RAW+JPG pairs (a synthetic TIFF-based RAW next to the JPEG); portrait shots;
three H.264 videos from `ffmpeg -f lavfi testsrc2` (one rotated); a motion photo (JPEG + Google XMP + MP4 appended);
an XMP sidecar written as another tool would (5 stars, picked) that the server imports. Folders
`2024/2024-05-03 Trip`, `2024/2024-05-04`, `Phone/2023-12`, `2025/Snow`. raw.pixls.us files were not needed: the
gallery shows RAWs through the server's previews, which ADR 0015's tests already cover with real camera files
(`HOARD_SAMPLES_DIR`).

**The benchmark library** (`TestWriteWebBench`): 20,000 real 240 × 160 JPEGs with EXIF over six years (about 12 a day
on 1,667 days), five cameras, one folder per month. Indexed in 11 s. Previews made on demand only
(`PREVIEW_BACKLOG=none`), as on a fresh install that has not caught up yet.

## What the end-to-end tests check (`e2e/gallery.spec.ts`, 10 tests)

1. Sign-in: wrong password refused with a message; right password opens the gallery; newest first; the jump bar; the
   sidecar from the NAS shows as picked with 5 stars.
2. Grid in light and dark at 1440 × 900 and 1024 × 768; the jump bar scrolls to 2023; the document never scrolls (hfps
   shell: the card scrolls).
3. Filters: kind, camera facets with counts, picked, text search (every word, file and folder names), date range by
   the camera's calendar day; filters live in the URL and survive a reload.
4. Viewer: thumbnail → preview → original (a pair shows its JPEG), exposure in the info panel, Z to 100 % (scale 1 at
   device pixel ratio 1) and Esc back to fit, 3 stars, P picks and advances, X rejects (and advances), rating a
   rejected shot picks it, U clears the flag and keeps the stars, 0 clears the stars, Ctrl+Z undoes, I toggles the
   panel, Esc closes and the grid shows the item with its badges; the server's culling record says
   `pick / 3 / web:…` (ADR 0016, written by the session's writer).
5. A video plays from the proxy (`readyState ≥ 2`); a motion photo plays its video.
6. Keyboard: arrow focus in the grid, Space and Shift+Space select, X rejects the selection, Ctrl+Z, Esc clears.
7. Downloads: a pair as a zip with both files, one file from the info panel with its name, a selection of three as a
   zip.
8. Live: a rating pushed by a paired device (`POST /v1/culling`) and a Reveal edit (`POST /v1/changes`, a photo and a
   version with a history step) appear in the open grid without a reload; the Edited filter finds it.
9. Folders: the tree with counts; a directory opens the gallery filtered to it.
10. Sign-out ends the session (`/web/library` answers 401).

Last run (2026-11-14, cachyos-fps): **10 passed** in 34 s, plus the server tests (`TestWebGateAndSession`,
`TestWebLibraryCullingAndFolders`, …) and 27 unit tests (7 files).

## Screenshots (1440 × 900 unless named, Chromium, synthetic library)

| | |
| --- | --- |
| Sign-in | ![](web-gallery/sign-in.png) |
| Grid, light | ![](web-gallery/grid-1440-light.png) |
| Grid, dark, after jumping to 2023 | ![](web-gallery/grid-1440-dark-jump.png) |
| Grid, 1024 × 768, light | ![](web-gallery/grid-1024-light.png) |
| Grid, 1024 × 768, dark | ![](web-gallery/grid-1024-dark.png) |
| Camera filter with counts | ![](web-gallery/filters-camera.png) |
| Date range | ![](web-gallery/filters-dates.png) |
| Selection, two rejected | ![](web-gallery/grid-selection.png) |
| Viewer with the info panel (original loaded) | ![](web-gallery/viewer-1440-info.png) |
| Viewer at 100 % | ![](web-gallery/viewer-1440-zoom100.png) |
| Viewer, 1024 × 768, after 4 stars from the web gallery | ![](web-gallery/viewer-1024.png) |
| Video from the proxy | ![](web-gallery/viewer-video.png) |
| Folders, light | ![](web-gallery/folders-1440-light.png) |
| Folders, 1024 × 768, dark | ![](web-gallery/folders-1024-dark.png) |

## Measurements: 20,000 items (`e2e/perf.spec.ts`, raw numbers in `web-gallery/measurements.json`)

cachyos-fps (16 threads, shared with the owner's other work, so absolute times are an upper bound), Chromium 156
headless via Playwright, 1440 × 900, device pixel ratio 1, server and browser on the same host (loopback).

| What | Cold (first request after the server started) | Warm (reload) |
| --- | --- | --- |
| First contentful paint | 104 ms | 48 ms |
| `GET /web/library` (whole index) | 254 ms (server builds the index: 232 ms) | 80 ms |
| Index size | 686 KB gzipped (4.0 MB JSON) | same |
| First thumbnail received | 499 ms (made on demand by vips) | 226 ms |
| First grid cell's image decoded | 2.8 s (a `202` while vips made it, retried after 2 s) | 328 ms |

| Scrolling (6 s at 3,000 px/s, rAF frame times) | |
| --- | --- |
| Frames per second | 59.8 |
| Median / p95 / p99 frame | 16.7 / 16.8 / 16.8 ms |
| Frames over 50 ms | 0 |
| Cells in the DOM while scrolling | 48 (of 20,000) |

| Other | |
| --- | --- |
| Jump bar to the oldest year (click to painted) | 37 ms |
| Search over 20,000 rows (type to count shown) | 69 ms |
| Browser JS heap after load / after scrolling | 27.8 MB / 32.1 MB |
| DOM nodes after load / after scrolling (CDP count, detached nodes not yet collected included) | 3,818 / 12,004 |
| Server RSS before / after the first index request (index plus the encoding buffers Go keeps) | 36 MB / 78 MB |
| Live poll with nothing changed | 3 ms on the server, a few hundred bytes |

Reading: the grid's cost does not grow with the library (48 cells in the DOM for 20,000 items); the index download
dominates first load and is about 35 bytes per item gzipped; a cold server spends a quarter of a second building it
once. On a fresh install the first screen waits for on-demand thumbnails (2 s retry); with the default backlog the
server has them ready.
