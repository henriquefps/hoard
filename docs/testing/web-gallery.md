# Web gallery: end-to-end tests, screenshots and measurements

The web gallery of hoard-server ([ADR 0018](../adr/0018-web-gallery.md)), tested in a browser against the real
binary. Everything runs on temporary data under `server/web/e2e/.library/` (git-ignored), on ports 3990-3992 bound to
`127.0.0.1` (3992: a second server with `TRUST_TAILSCALE_SERVE=1` and an empty library); nothing touches a deployed
server, its data, a NAS or Tailscale.

## How to run

```sh
cd server/web
bun install
bun run lint && bun run typecheck && bun run test     # unit tests: culling keys, filters, layout, tree, zoom, locales
bun run e2e                                           # Playwright: builds the web app and the binary, writes the
                                                      # synthetic library, starts the server, runs e2e/gallery.spec.ts
HOARD_WEB_SCREENSHOTS=1 bun run e2e                   # also writes the screenshots below
HOARD_WEB_BENCH=1 bun run e2e -- e2e/perf.spec.ts     # + a 20,000-item library and the measurements
HOARD_WEB_ZOOM_BENCH=1 bun run e2e -- e2e/zoom.bench.spec.ts   # full resolution: swap time and memory, 24/45/60 MP,
                                                      # single image vs tiles (HOARD_WEB_ZOOM_VISITS=20 for a longer
                                                      # sitting, HOARD_WEB_ZOOM_BASELINE=1 for the same walk unzoomed)
cd .. && go vet ./... && go test -race ./... && golangci-lint run ./...   # server side, incl. internal/server/web_test.go
```

Needs Go (from `server/go.mod`), bun, ffmpeg and libvips on `PATH`, and Playwright's Chromium (`@playwright/test`
1.64.1, Chromium 1248; `bunx playwright install chromium` once).

**The library** (`TestWriteWebFixture` in `server/internal/server/web_fixture_test.go`, opt-in): 37 items from 42 files,
all synthetic and free to publish. Real, decodable JPEGs drawn in Go (a gradient, a sun, a horizon and a fine
checkerboard at the centre for checking 100 %) with synthetic EXIF (four cameras, lenses, exposure, GPS, offsets),
2400 × 1600 and one 6000 × 4000; four RAW+JPG pairs (a synthetic TIFF-based RAW next to the JPEG); portrait shots;
three H.264 videos from `ffmpeg -f lavfi testsrc2` (one rotated); a motion photo (JPEG + Google XMP + MP4 appended);
an XMP sidecar written as another tool would (5 stars, picked) that the server imports; a lone 6000 × 4000 RAW
(`2023/2023-11-20 Raw/DSC03000.ARW`, 0.8.1) carrying a full-size drawn JPEG the way Canon and Nikon embed theirs, so
the full-resolution route renders it. Folders `2024/2024-05-03 Trip`, `2024/2024-05-04`, `Phone/2023-12`, `2025/Snow`,
`2023/2023-11-20 Raw`. raw.pixls.us files were not needed: the
gallery shows RAWs through the server's previews, which ADR 0015's tests already cover with real camera files
(`HOARD_SAMPLES_DIR`).

**The benchmark library** (`TestWriteWebBench`): 20,000 real 240 × 160 JPEGs with EXIF over six years (about 12 a day
on 1,667 days), five cameras, one folder per month. Indexed in 11 s. Previews made on demand only
(`PREVIEW_BACKLOG=none`), as on a fresh install that has not caught up yet.

## What the end-to-end tests check (`e2e/gallery.spec.ts`, 12 tests)

1. Sign-in: wrong password refused with a message; right password opens the gallery; newest first; the jump bar; the
   sidecar from the NAS shows as picked with 5 stars.
2. Grid in light and dark at 1440 × 900 and 1024 × 768; the jump bar scrolls to 2023; the document never scrolls (hfps
   shell: the card scrolls).
3. Filters: kind, camera facets with counts, picked, text search (every word, file and folder names), date range by
   the camera's calendar day; filters live in the URL and survive a reload.
4. Viewer: thumbnail → preview (a 2400 px photo: the preview is full size, nothing more is asked), exposure in the info panel, Z to 100 % (scale 1 at
   device pixel ratio 1) and Esc back to fit, 3 stars, P picks and advances, X rejects (and advances), rating a
   rejected shot picks it, U clears the flag and keeps the stars, 0 clears the stars, Ctrl+Z undoes, I toggles the
   panel, Esc closes and the grid shows the item with its badges; the server's culling record says
   `pick / 3 / web:…` (ADR 0016, written by the session's writer).
5. A video plays from the proxy (`readyState ≥ 2`); a motion photo plays its video.
6. Keyboard: arrow focus in the grid, Space and Shift+Space select, X rejects the selection, Ctrl+Z, Esc clears.
7. Downloads: a pair as `DSC01000.zip` with both files, one file from the info panel as `DSC01000.JPG` (its link ends
   in the name, `download="DSC01000.JPG"`, `Content-Disposition` with `filename*`), a selection of three as
   `Snow-3 photos.zip`.
8. Live: a rating pushed by a paired device (`POST /v1/culling`) and a Reveal edit (`POST /v1/changes`, a photo and a
   version with a history step) appear in the open grid without a reload; the Edited filter finds it.
9. Folders: the tree with counts; a directory opens the gallery filtered to it.
10. Sign-out ends the session (`/web/library` answers 401).
11. Zoom (0.8.1): at fit nothing asks for full resolution; Z to 100 % on the 6000 × 4000 pair asks once, shows the
    progress line while the answer is held back, then swaps in the 6000 × 4000 original in the same box at the same
    scale ("Original"); the lone RAW renders on the server and shows "Original (rendered from the RAW)" at 6000 × 4000.
12. HTTPS (0.8.1): against the `TRUST_TAILSCALE_SERVE=1` server, requests as `tailscale serve` sends them (loopback,
    `X-Forwarded-For` a tailnet address, `-Proto https`, `-Host` the ts.net name) open the gallery and sign in with a
    `Secure` cookie and the ts.net `Origin`; Funnel, a Cloudflare header or a public address get 404; the main server
    refuses the same request. A Chromium that resolves `gallery.test` to 127.0.0.1 opens the gallery over plain HTTP
    by that name and shows the HTTPS hint (dismissible, remembered); on 127.0.0.1 there is none.

Last run (2026-11-14, cachyos-fps, 0.8.1): **12 passed** in 56 s, plus the server tests (`TestWebGateAndSession`,
`TestWebLibraryCullingAndFolders`, `TestWebFullResolution`, `TestWebFullCacheBound`, `TestWebDownloadNames`,
`TestWebBehindTailscaleServe`, `TestWebCullingReachesOtherClients`, …) and the unit tests.

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
| 100 % before the swap: the 2560 px preview magnified (0.8.1) | ![](web-gallery/zoom100-preview.png) |
| 100 % after the swap: the 6000 × 4000 original, the 1-pixel checkerboard sharp (0.8.1) | ![](web-gallery/zoom100-original.png) |
| 100 % of a lone RAW, rendered by the server (0.8.1) | ![](web-gallery/zoom100-raw.png) |
| Over plain HTTP from another computer: the HTTPS hint (0.8.1) | ![](web-gallery/https-hint.png) |
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

## Measurements: full resolution at 100 % (0.8.1, `e2e/zoom.bench.spec.ts`, raw numbers in `web-gallery/zoom-measurements.json`)

Synthetic 3:2 JPEGs q90 (ffmpeg `testsrc2` → libvips) of 24, 45 and 60 MP answered in place of `/web/items/{id}/full`
(Playwright `route.fulfill`), the viewer at 1440 × 900 with the info panel closed, Chromium 1248 headless. Memory: RSS
of Chromium's renderer and GPU processes (`SystemInfo.getProcessInfo`, `/proc/<pid>/status`), a fresh context per size.

| | 24 MP (2.1 MB) | 45 MP (2.9 MB) | 60 MP (4.7 MB) |
| --- | --- | --- | --- |
| Z → full resolution on screen | 150 ms | 121 ms | 178 ms |
| Pan corner to corner (30 steps) | 762 ms | 498 ms | 494 ms |
| RSS at fit → full → panned → neighbour photo | 274 → 375 → 394 → 409 MB | 275 → 463 → 481 → 565 MB | 273 → 523 → 543 → 558 MB |
| **Tiles** (512 px, `vips dzsave`, only those in view): first view → panned | 194 → 254 MB | 192 → 294 MB | 195 → 297 MB |
| Tile requests for the pan | 50 | 76 | 85 |

A sitting at 60 MP (zoom, ← and back): 184 → 532 → 784 → 806 → 822 → 668 → 835 → 876 → 891 MB over 8 visits; 20 visits
end near 1 GB, about 10 MB a visit above the same walk without zooming (188 → 373 MB over 20). Over a network the
download dominates (4.7 MB at 60 MP). Decision in ADR 0018 "Full resolution": one image; tiles would save about
150 MB at 60 MP for a pyramid per photo on the server and dozens of requests per pan.

Server side (`TestCPUFullRealSamples`, `HOARD_SAMPLES_DIR` with copies of the CC0 raw.pixls.us files, two threads,
nice 10, cachyos-fps shared with other work; two runs):

| File | RAW | Embedded | Full resolution from | Size | Time |
| --- | --- | --- | --- | --- | --- |
| Sony A7 III `.ARW` | 6048 × 4024 | 1616 px | LibRaw → 6024 × 4024 | 9.1 MB | 2.9-4.0 s |
| Fujifilm X-T3 `.RAF` | 6240 × 4160 | 4416 px | LibRaw → 6246 × 4170 | 3.4 MB | 6.1-7.6 s |
| Olympus E-M1 II `.ORF` | 5240 × 3912 | 3200 px | LibRaw → 5240 × 3912 | 7.6 MB | 4.3-6.1 s |
| Panasonic G9 high-res `.RW2` | 10368 × 7776 | 1920 px | LibRaw → 10402 × 7794 | 15.2 MB | 10.9-16.5 s |
| Nikon Z 6 `.NEF`, Canon R6 `.CR3`, iPhone `.DNG` | 24 / 8 / 12 MP | full size | the embedded JPEG as it is | 1.0-5.4 MB | 4-20 ms |
