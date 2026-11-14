# ADR 0018: The web gallery served by hoard-server

- **Date**: 2026-11-14
- **Status**: Accepted (hoard-server 0.8.0, Hoard 2 phase 7, moved up); amended in 0.8.1 after the owner's first test
  (full resolution for 100 %, named downloads, HTTPS behind `tailscale serve`, the culling check)
- **Amends**: ADR 0013 (additive routes outside `/v1`, summarised there in "Web gallery (hoard-server 0.8.0)"). Reads
  ADR 0014 (items), 0015 (previews), 0016 (culling), 0017 (devices), and Hoard ADR 0008/0009 for the culling keys.
- **Amended by**: ADR 0020 (hoard-server 0.9.0: Reveal's edited previews: the primary edit in the grid with the
  "Edited" badge and filter, virtual copies as an expandable stack, Edited / Original in the viewer (E), "Download
  edited JPEG"; the library row's `ed`)

## Context

The owner's library lives on the NAS and is indexed by hoard-server as items (ADR 0014), with previews (ADR 0015) and
culling shared by Hoard and Reveal (ADR 0016). Looking through it, choosing and downloading from a computer meant a
file browser over SMB and an image viewer that knows nothing about pairs, ratings or picks. The server can serve a web
gallery itself: browse, search, cull and download from any browser on the LAN or Tailscale. No editing (that is
Reveal's job), no sharing with other people (later).

Constraints: `/v1` is frozen for Reveal (additions only, ADR 0012) and reachable from anywhere (the tunnel); the setup
page (`/admin`) is LAN/Tailscale only. The gallery shows every private photo, so it must never be reachable through
the Cloudflare tunnel unless a later decision allows it. The server is one static binary; the gallery must not add a
second process or a Node runtime.

## Decision

### Where it lives

- Sources in `server/web/` (Vite, React 19, TypeScript, Tailwind 4, shadcn/ui radix-vega; the hfps identity of the
  Hoard app: the same `index.css` tokens, Inter, Phosphor icons, one card on a tinted background, eyebrow labels;
  English and Brazilian Portuguese). Dependencies pinned to exact versions (the ones Hoard's `bun.lock` resolves), with
  `server/web/bun.lock`.
- `bun run build` writes to `server/internal/webui/dist/`, which the Go package `webui` embeds (`//go:embed all:dist`).
  Only `dist/.gitkeep` is in git: `go build`, `go vet` and `go test` work without a web build, and a binary built that
  way answers `/` with a one-line page saying so. The Docker image builds the web app in a `web` stage
  (`oven/bun:1.4.0-alpine`, `bun install --frozen-lockfile`) before the Go stage; the release workflow does the same
  before cross-compiling.
- `server/web/go.mod` (an empty module) keeps `server/web` and its `node_modules` (some npm packages ship `.go`
  files) out of `go vet ./...` and golangci-lint.
- Served at `/` with the app's client-side routes (`/folders`, `/folders/…`, `/items/<id>`) answered with
  `index.html`, assets under `/assets/` (content-hashed, `Cache-Control: immutable`), and the API under **`/web/`**.
  `/v1` and `/admin` are unchanged; `/` used to redirect to `/admin/` and still does for a request that came through a
  proxy while the setup page is allowed there.
- CSP: `default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'` (React sets inline sizes on the
  virtualized grid), `img-src 'self' blob: data:`, `media-src 'self' blob:`, `connect-src 'self'`, `font-src 'self'`
  (Inter is bundled), `frame-ancestors 'none'`.

### Reach and sign-in

**LAN and Tailscale only**: every gallery path (the app and `/web/`) goes through the setup page's address check
(ADR 0013 "Admin": loopback, RFC 1918, ULA, link-local, CGNAT `100.64.0.0/10` for Tailscale) **and** refuses any request
carrying a proxy header (`Cf-Connecting-Ip`, `X-Forwarded-For`, `X-Real-Ip`, `Forwarded`). Unlike `/admin`, the
setup page's "allow through a tunnel or proxy" setting does **not** open the gallery: that needs a decision of its own
(Cloudflare Access in front, share links). Elsewhere every gallery path answers `404` like an unknown route.
The one exception, opt-in, is `tailscale serve` on the same machine (0.8.1, "HTTPS with tailscale serve" below).

**Sign-in with the admin password** (chosen over pairing the browser as a device):

| | Admin password + session cookie (chosen) | Pair the browser as a device |
| --- | --- | --- |
| First use | type the password you already have | open `/admin` on another tab, make a QR/token, paste it |
| Key storage | an HttpOnly cookie script cannot read | an `rvk1_` key in `localStorage`, readable by any script on the origin (an XSS steals a key that works from anywhere, through the tunnel) |
| Revocation | sign out; changing the admin password ends every gallery session | revoke on the Devices page |
| CSRF | needed (cookie): header + Origin + SameSite=Strict | not needed (bearer) |
| Device list | no entry per browser | one device per browser and profile, cluttering ADR 0017's physical devices |

- `POST /web/session {"password"}` checks the admin password (argon2id, constant cost, rate limited 5 a minute per
  address like the setup page), creates a session: 256 random bits in the cookie `hoard_web` (`HttpOnly`,
  `SameSite=Strict`, `Path=/web/`, `Secure` over HTTPS, 7-day max age), stored as SHA-256 in `web_sessions`, **7 days
  idle** expiry (the setup page keeps 12 hours: the gallery is used for longer sittings and changes nothing but
  culling). A gallery session never opens `/admin` (separate table and cookie) and an admin session never opens the
  gallery.
- Every state-changing call (`POST`, `DELETE`) needs the header `X-Hoard-Web: 1` (a cross-site form cannot set it)
  and, when present, an `Origin` naming the same host; the cookie is `SameSite=Strict` as well.
- Changing the admin password (setup page) deletes every gallery session with the setup page's.
- Each session has a **writer id** `web:<16 hex>` (64 random bits, made at sign-in). Culling written from the gallery
  is written by that id (ADR 0016's `deviceId`), so the info panel can say "web gallery" and a later audit can tell
  sessions apart. It is not a device: it is not in `devices`, `GET /v1/devices` or the Devices page. Rate limit: 60
  requests a second (burst 240) per session; preview GETs use ADR 0015's preview bucket.

### API (`/web/`, outside `/v1`)

| Method and path | Answer |
| --- | --- |
| `GET /web/session` | `{"signedIn", "setup" (no admin password yet), "version", "writer", "previews"}` (no sign-in needed) |
| `POST /web/session` `{"password"}` | `200 {"ok", "writer"}` + cookie; `401 unauthorized`; `409 conflict` (no admin password yet); `429` |
| `DELETE /web/session` | `200`, cookie cleared |
| `GET /web/library?cursor=` | The library index (below): every live item, or the rows changed since `cursor`. gzip when accepted. |
| `GET /web/folders` | `{"folders": [{"id", "name", "state", "items", "dirs": [{"path", "items"}]}], "uploads"}`: every NAS folder (empty ones too) with the items per directory; the browser builds the tree. `uploads` = items only devices uploaded. |
| `GET /web/items/{id}` | `{"item": <as GET /v1/items/{id}>, "culling": <record> \| null, "edited": bool}` |
| `GET /web/items/{id}/exif` | `{"exposure": {"exposureTime", "fNumber", "iso", "bias", "focalLength", "focalLength35"}}` read on demand from the still (a pair's JPEG, else the main file); `{}` fields absent when not recorded or for videos. Not stored: `MetaVersion` and the items do not change. |
| `GET /web/items/{id}/preview\|poster\|proxy` | As `/v1` (ADR 0015), same handlers (`202` pending, `?v=` immutable, ranges for the proxy). |
| `GET /web/items/{id}/full` (0.8.1) | The still at full resolution ("Full resolution" below): the original file (`X-Hoard-Full: original`), or a JPEG made on demand (`converted`, `raw`), `202` + `Retry-After` while it is made; `404 preview_unavailable` for a video. |
| `GET /web/items/{id}/files/{fingerprint}[/{name}]` | One member file of the item with ranges, `Content-Type` by extension, `Content-Disposition: inline` (`?download=1`: `attachment`) with the file's name ("Downloads"). The optional last segment is the file's name, ignored by the server. `404` when the fingerprint is not a member; `503` when the NAS file is offline or changed. |
| `POST /web/culling` | As `POST /v1/culling` (ADR 0016), written by the session's writer id. |
| `POST /web/downloads` `{"items": [ids]}` (1-5000) | `{"url": "/web/downloads/<token>/<name>", "name", "items", "files", "bytes"}`: a download link valid **10 minutes**, for this session only. `422 invalid_request` (`details.index`) for an unknown or deleted item. |
| `GET /web/downloads/{token}[/{name}]` | `application/zip`, streamed as it is read (no temporary file, no `Content-Length`): every file of every item (a pair's RAW and JPEG, its sidecar; a motion photo's video when it is a separate file), stored without compression (photos and videos are compressed already), names made unique (`DSC0001 (2).JPG`). A file the server cannot open is skipped and listed in `missing.txt` at the end. |

Errors are ADR 0013's JSON errors. A browser downloads one file with the `files` route and a pair, a motion photo with
a separate video, or a selection with a zip link (`<a download>` on the link: the browser's own download manager shows
progress and the page stays).

### Library index

The grid needs every item at once: its position (date headers, the jump bar), its filters, its badges. Paging the
items query (ADR 0014) would mean 40 round trips of full items (1-4 KiB each) for 20,000 items, and filters that only
see loaded pages. Instead the server keeps a compact **index** in memory and the browser filters it:

```json
{"cursor": "<boot>:<generation>", "full": true, "total": 20000,
 "folders": {"<folder id>": "photos"},
 "removed": [],
 "rows": [{"id": "…", "t": 1714730400250, "l": 1714719600250, "k": "pair", "w": 6000, "h": 4000, "n": "DSC01000.ARW",
           "fo": "<folder id>", "d": "2024/2024-05-03 Trip", "cam": "SONY ILCE-7M4", "lens": "FE 35mm F1.8", "raw": true,
           "r": 4, "x": "pick", "lb": "Red", "e": true, "v": "<preview key>", "cr": "131.96.0", "size": 33763136, "dur": 4000}]}
```

`t` sortTime (ADR 0014), `l` the camera's wall clock read as UTC when it differs (days and times are shown and
grouped by it), `k` kind, `w`/`h` upright size, `n` the main file's name, `fo`/`d` NAS folder and directory (absent:
uploaded), `cam` make and model as one name ("Canon EOS R5", not "Canon Canon EOS R5"), `raw` the main file is a RAW,
`dur` video ms, `r`/`x`/`lb` culling, `e` **edited in Reveal** (a live `photo` record with this fingerprint has a live
`version` whose `history` has a step), `v` the image preview's key when one is on disk (the grid builds immutable
thumbnail URLs, ADR 0015, and never revalidates), `cr` the culling fields' revisions `rating.flag.label` (the
`baseRev` of the next push), `size` bytes of all files. Absent fields are empty. 20,000 items: 4.0 MB of JSON,
**686 KB gzipped** (measured below).

- **Built** from the items feed (items and culling records share its counter, ADR 0016) on the first request, then
  brought up to date on each request: items and culling records since the last revision applied, the edited set again
  when Reveal's records counter moved, preview keys changed since the last look (`previews.updated_at`, a new index
  `previews_updated`, at most every 2 s). Every change gets the next **generation** (a counter of this process).
- **Cursor** `<boot>:<generation>`: rows with a later generation and the ids removed since (tombstones) come back; a
  cursor from another server start, ahead of the generation or malformed gets the whole index (`full: true`). The
  browser polls every 4 s while the tab is visible (**live**: a pick made in Hoard or Reveal, a sidecar the NAS walk
  imported, a new shot appear without a reload). Server-sent events were considered and left out: one cheap GET every
  4 s per open tab costs less than a held connection per tab plus a fan-out in the server, and needs nothing new.
- **Search and filters run in the browser** over the index: kind, camera (facets with counts), minimum stars,
  pick/reject/not flagged, a folder or directory (sub-directories included) or "uploaded from devices", a date range
  of the camera's calendar days, edited in Reveal, and text: every word must appear in the file name, the folder's
  name or the directory. They live in the query string (`?q=&kind=&camera=&stars=&flag=&folder=&from=&to=&edited=`)
  so a view can be bookmarked and Back works. **Semantic search** comes with the GPU phase as a server route that
  returns item ids; the search box keeps its place and the filters compose with its answer.

### Gallery

- **Grid**: newest first, a header per capture day, square cells (small 140, medium 200, large 300 px targets),
  virtualized with `@tanstack/react-virtual` over the day headers and rows: only what is on screen is in the DOM, so
  20,000 items render like 200. A sticky header shows the day at the top; the **jump bar** on the right lists the
  years, and the months of the year in view, with counts in tooltips.
- **Badges** (the viewer tokens, as Hoard's culling grid): `RAW+JPG` for a pair, `RAW` for a RAW alone, video with its
  duration, motion, edited in Reveal, stars (a star and the number), pick (check, primary) and reject (cross,
  destructive; the cell is dimmed). Bursts are not grouped: the server has no burst grouping yet (Hoard ADR 0009 runs on
  the phone); items are plain cells, and a burst rule moves here when the server learns bursts.
- **Selection**: Ctrl/Cmd-click or the cell's circle toggles, Shift-click selects a range, Ctrl/Cmd+A everything
  shown, Esc clears. The toolbar then offers pick, reject, clear flag, stars and **Download zip**.
- **Keyboard**: the grid is one tab stop (roving focus): arrows move (up and down keep the column across days), Home
  and End, Page up/down, Enter opens, Space selects; P, X, U, 0-5 cull the selection (else the focused item), Ctrl/Cmd+Z
  undoes; `/` focuses the search.

### Viewer

Full screen, dark in both themes (Hoard's viewer tokens). The thumbnail shows at once, the **screen preview** (2560 px
WebP) replaces it, and the **full-resolution still** replaces that once a zoom would magnify the preview ("Full
resolution", 0.8.1; in 0.8.0 the original of a JPEG loaded always, and a RAW or HEIC stayed at the preview, blurry at
100 %). The header says which one is on screen ("Thumbnail", "Preview", "Original", "Original (rendered from the
RAW)", "Original (converted from HEIF)"). **Z**, a double click or the wheel zoom to 100 % (one image pixel per device pixel) at the pointer; drag
pans. **I** opens the info panel: capture time with milliseconds and offset, camera, lens, exposure (shutter, aperture,
ISO, focal length and its 35 mm equivalent, bias), size and megapixels, video codec/fps/HDR/audio, GPS with a map
link, each culling field with who wrote it (a device, the NAS sidecar, the web gallery) and when, edited in Reveal,
and every file with its role, size, folder path and a download button.

**Videos** play from the proxy (H.264, HTTP ranges); while the server is still making it (`202`), the poster shows
with "Preparing the video" and the viewer asks again after `Retry-After`. **Motion photos** show the still with a
"Play motion" button (the proxy, looped and muted).

**Culling**, with Hoard's semantics (ADR 0008, `CullViewer`): **P** and **X** toggle pick and reject, **U** clears the
flag, **1-5** set the stars (the same number again clears them), **0** clears them, rating a rejected shot makes it a
pick; stars survive a reject (ADR 0016). After a pick or reject the viewer goes to the next item (a setting, on by
default). ←/→ move, Esc leaves zoom then the viewer, Ctrl/Cmd+Z undoes (200 steps) and shows the item it changed. The
viewer walks the list as it was when it opened, so an item does not vanish under the cursor when a decision takes it
out of the filter. On a burst there is nothing to do yet (see "Badges").

Writes follow ADR 0016's client rules: the decision shows at once; one change per field that changed, with the field's
revision from the index as `baseRev` and the browser's clock as `modifiedAt`; changes to the same field collapse to
the last; a failed push stays queued and retries (a header indicator counts pending changes); a field still pending
keeps its local value over what a poll brings; a conflict the server wins makes the next poll show the winner.

### Full resolution (0.8.1)

The owner's first test: 100 % was blurry. The viewer zoomed the 2560 px screen preview, and a Sony RAW's preview is
its 1616 px embedded JPEG (ADR 0015), so 100 % of a 24-60 MP shot was the preview magnified 2.3-6 times.

- **When**: the viewer asks for full resolution once the image's width on screen in device pixels is larger than the
  screen preview's own width (5 % slack), i.e. on any zoom past what the preview holds, not at fit. A photo whose
  preview is already its full size (≤ 2560 px) never asks. Over 150 MP it stays at the preview.
- **What** (`GET /web/items/{id}/full`, `previews.FullOriginal` and `JobFull`):
  - a JPEG photo, or a pair's camera JPEG (a rendering a browser shows: JPEG, PNG, WebP, AVIF, GIF): **the file
    itself**, untouched (`X-Hoard-Full: original`; the browser applies its EXIF orientation and ICC profile);
  - a **HEIF** rendering: converted to a full-size JPEG (q90, sRGB, upright) by libvips (`converted`);
  - a **lone RAW** (`raw`): its largest embedded JPEG **as it is** (the camera's rendering, the RAW's orientation put in
    a minimal EXIF in front) when its long edge is at least 90 % of the RAW's (Canon, Nikon, Apple embed a full-size
    one), else a **LibRaw decode** (`dcrawload`, as ADR 0015's fallback) to a full-size JPEG q90; if LibRaw fails, the
    largest embedded JPEG when it is larger than the screen preview, else unavailable (the viewer stays at "Preview").
    "Whichever is larger and sharp": a Sony's 1616 px or an Olympus's 3200 px preview is never called full resolution.
- **Made on demand, cached like previews**: `JobFull` is a third job of the preview queue (same rows, keys, states and
  retries as ADR 0015; key = source + recipe + `PREVIEW_VERSION`), in its own lane (one worker, so a 10 s LibRaw decode
  never holds up thumbnails), at priority 0 when a viewer asks (`202` + `Retry-After: 2`), **never in the backlog**
  (`--preview-backlog` does not take it). Files: `<key>.full.jpg` under the previews directory. The cache is bounded
  (`--full-cache-mb`, default 4 GiB, about 200 photos of 24-60 MP): after each new still, the least recently served
  go (serving one touches its row); they are made again when asked.
- **Progressive, no jump**: the viewer downloads the answer with `fetch` (progress from `Content-Length`: "Loading full
  resolution… 42 %"; "Rendering full resolution on the server…" while it gets `202`), makes a blob URL, and puts the
  image in the same box as the preview (every layer is `absolute inset-0` of a box sized from the item's width and
  height), visible once decoded: the swap changes no position or scale (tested).
- **Memory**: one full-resolution image at a time (the viewer remounts per item; leaving an item aborts the download,
  clears the `<img>` and revokes the blob). Single image chosen over tiles (measured below): it costs about 100 MB
  (24 MP) to 250 MB (60 MP) of browser memory while shown, Chrome's own decoded-image cache levels off at about 0.6-1
  GB over a sitting of 20 visits at 60 MP (discardable: Chrome drops it under memory pressure); tiles save about 150 MB
  at 60 MP but cost 50-85 requests per pan across the photo, a server-side pyramid per photo (and so a second copy of
  every JPEG original instead of serving it untouched) and a tile renderer.

### Downloads (0.8.1)

The owner's first test: downloads were blocked and arrived named after the URL (`v1_2537051c…`, no extension).

- **Names**: every download answer sends `Content-Disposition: attachment; filename="<ASCII fallback>";
  filename*=UTF-8''<percent-encoded name>` (RFC 6266/8187: accents and spaces survive). One file keeps its own name;
  a zip of one item is `<main file's stem>.zip` (a pair: the RAW, the JPEG and the sidecar under their own names);
  several items from one directory `<directory>-<n> photos.zip`, else the capture day or days
  (`2024-05-03–2024-05-10-<n> photos.zip`). The URL ends in the name too (`/files/{fingerprint}/{name}`,
  `/web/downloads/{token}/{name}`, ignored by the server), so a browser that names a download by its URL (a blocked
  download, "Save link as") still gets it right; the links are same-origin `<a download="<name>">`.
- **Blocked downloads**: Chrome blocks downloads from a plain-HTTP page that is not on the same computer
  (`http://cachyos-fps:3071/` from a Mac). No header fixes that; HTTPS does (next section). Over plain HTTP from
  another computer the gallery shows a dismissible line saying so.

### HTTPS with tailscale serve (0.8.1)

`tailscale serve --bg --https=443 http://127.0.0.1:3071` on the server machine gives the gallery
`https://<machine>.<tailnet>.ts.net/` with a certificate Tailscale manages, reachable from the tailnet only. It
proxies from this host, which the 0.8.0 gate refused (a proxy header). With **`TRUST_TAILSCALE_SERVE=1`** (off by
default) the gate, for the gallery and `/admin` alike, also accepts a request when all of these hold:

- the TCP peer is loopback, or, in a container (`/.dockerenv`, `/run/.containerenv`), the default gateway (the Docker
  bridge, which a published port's traffic from the host comes from), found at start and logged;
- `X-Forwarded-For` is **one** address in Tailscale's ranges (100.64.0.0/10, fd7a:115c:a1e0::/48): `tailscale serve`
  replaces whatever the client sent with the connection's tailnet address;
- none of `Tailscale-Funnel-Request` (Funnel brings the internet in: refused), `Cf-Connecting-Ip`, `X-Real-Ip`,
  `Forwarded` (another proxy).

`Tailscale-User-*` headers are not needed (a tagged device has none) and are not used for authorization: the admin
password still signs in. Behind it the cookie is `Secure` (`X-Forwarded-Proto: https`), the `Origin` check of
writes compares with `X-Forwarded-Host` (the `ts.net` name; the request's own Host is `127.0.0.1:3071`), and rate
limits key on the tailnet address. URLs are relative, so nothing else changes. A Cloudflare Tunnel connector on the
same host still gets `404` (it sends `Cf-Connecting-Ip` and a public `X-Forwarded-For`). The setup is in the server's
README; the server never configures Tailscale.

### Culling from the gallery reaches other clients (0.8.1 check)

Verified end to end (`TestWebCullingReachesOtherClients`, the e2e "viewer" test): a pick or rating set in the gallery
is a per-field write by the session's `web:<16 hex>` writer through ADR 0016's rule, appears in
`GET /v1/items/changes?include=culling` with a new revision for every paired client, and leaves Reveal's record feed
(`/v1/changes`) untouched. ADR 0016 bridges **one way only** (Reveal's `photo` records → culling, for releases that do
not push culling; "The bridge never writes `photo` records"), so a released Reveal that does not pull the culling feed
does not show gallery decisions: that is Reveal's pending culling client (ADR 0016 "Client rules", step 5), not a
server change. Hoard shows them once its phase-2 pull lands.

### Folders

The NAS folders as a tree (from `GET /web/folders`), each directory with its items (sub-directories included);
choosing one opens the gallery filtered to it. Items uploaded by devices are one more entry. Device backups get their
place here when the server has them.

### Storage

Migration `0007_web_sessions.sql` (schema 7; forward only): `web_sessions (id_hash, device_id, created_at,
expires_at, last_seen_at)` and the index `previews_updated (job, updated_at)`. Swept with the setup page's sessions.

## Measurements

On cachyos-fps (16 threads, shared with other work), Chromium 156 headless through Playwright, hoard-server built from
this branch serving a synthetic library (`TestWriteWebBench`: 20,000 real 240 × 160 JPEGs with EXIF over six years,
five cameras), previews made on demand only (`PREVIEW_BACKLOG=none`, as a fresh install catching up), 1440 × 900.
Method, every number and how to run them again: `docs/testing/web-gallery.md`.

| | 20,000 items |
| --- | --- |
| First contentful paint (cold / warm) | 104 / 48 ms |
| Library index request (cold: the server builds it / warm) | 254 / 80 ms; 686 KB gzipped |
| Grid shows its first image (warm) | 328 ms |
| Scrolling at 3,000 px/s for 6 s | 59.8 fps, p99 frame 16.8 ms, 0 frames over 50 ms, 48 cells in the DOM |
| Jump to the oldest year / search over every row | 37 / 69 ms |
| Browser JS heap after load / after scrolling | 27.8 / 32.1 MB |
| Server RSS before / after the first index | 36 / 78 MB |

Full resolution (0.8.1): `server/web/e2e/zoom.bench.spec.ts` (`HOARD_WEB_ZOOM_BENCH=1`), synthetic 3:2 JPEGs q90
(ffmpeg `testsrc2` → libvips) answered in place of `/full`, the viewer at 1440 × 900, info panel closed; memory is the
RSS of Chromium's renderer and GPU processes, a fresh context per size. Numbers in
`docs/testing/web-gallery/zoom-measurements.json`.

| | 24 MP | 45 MP | 60 MP |
| --- | --- | --- | --- |
| Z to full resolution shown (local, 2.1 / 2.9 / 4.7 MB) | 150 ms | 121 ms | 178 ms |
| Memory at fit → full → panned corner to corner | 274 → 375 → 394 MB | 275 → 463 → 481 MB | 273 → 523 → 543 MB |
| Tiles instead (512 px, only those in view): first view → panned; requests | 194 → 254 MB; 50 | 192 → 294 MB; 76 | 195 → 297 MB; 85 |

A sitting at 60 MP (zoom, ← and back, 8 and 20 times): 184 → 532 → 784 MB, then 800-1000 MB, rising about 10 MB per
visit more than the same walk without zooming (Chrome's caches; it gives them back under memory pressure). LibRaw
renders on cachyos-fps (two threads, nice 10, CC0 raw.pixls.us files, two runs on a shared machine): Sony A7 III 24 MP
2.9-4.0 s, Fujifilm X-T3 26 MP 6.1-7.6 s, Olympus E-M1 II 20 MP 4.3-6.1 s, Panasonic G9 80 MP high-res 10.9-16.5 s;
Nikon Z 6, Canon R6 and the iPhone DNG use their full-size embedded JPEG as it is (4-20 ms).

## Consequences

- One more thing the binary serves; the binary grows by the web build (0.9 MB of assets: 660 KB of JavaScript, 196 KB
  gzipped; Inter's fonts; 72 KB of CSS).
- The library index costs memory in the server (RSS 36 → 78 MB for 20,000 items, encoding buffers included) and a
  full pass over the items feed on the first request after a start (232 ms for 20,000). A library of hundreds of thousands of items would
  want the index paged by month; the cursor and row format allow that later without a change for the grid.
- Everything private is reachable by anyone on the LAN or tailnet who knows the admin password. That is already true of
  the setup page; the gallery adds no new secret.
- No share links, no editing, no access through the tunnel: each is a decision of its own.

## Alternatives considered

- **Tiles for 100 %** (a DZI-like pyramid per photo): bounded browser memory, but per-photo server work and storage, a
  re-encode of JPEG originals that are served untouched now, and dozens of requests per pan over Tailscale; measured
  in "Measurements" and left out for 24-60 MP (0.8.1).
- **Trusting `Tailscale-User-Login` as sign-in**: would skip the password on the tailnet, but tagged devices have no
  identity and it would make the HTTPS address a different security model from the LAN one; the password stays.

- **Pair the browser as a device**: see the table in "Reach and sign-in".
- **Reuse `/v1` with a cookie**: `/v1` is reachable through the tunnel and is bearer-only by design; accepting cookies
  there would make every `/v1` write a CSRF target and blur the "LAN only" line. The gallery's routes call the same
  handlers under `/web/`.
- **Server-side filtering and paging** (extend `GET /v1/items` with camera, rating, text): keeps the browser light but
  makes every filter change a round trip, the jump bar and counts need separate aggregate queries, and live updates
  would re-run queries. The compact index is one gzip download and makes filtering instant; the server does the joins
  once.
- **Server-sent events** for live updates: see "Library index".
- **A separate web server or a Node SSR process**: a second process to deploy for what static files and a few routes
  do.
