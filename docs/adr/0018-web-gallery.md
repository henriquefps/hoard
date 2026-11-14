# ADR 0018: The web gallery served by hoard-server

- **Date**: 2026-11-14
- **Status**: Accepted (hoard-server 0.8.0, Hoard 2 phase 7, moved up)
- **Amends**: ADR 0013 (additive routes outside `/v1`, summarised there in "Web gallery (hoard-server 0.8.0)"). Reads
  ADR 0014 (items), 0015 (previews), 0016 (culling), 0017 (devices), and Hoard ADR 0008/0009 for the culling keys.

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
(Consequence: `tailscale serve` in front, which adds `X-Forwarded-For`, hides the gallery; open it at the Tailscale
address and port directly.)

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
| `GET /web/items/{id}/files/{fingerprint}` | One member file of the item with ranges, `Content-Type` by extension, `Content-Disposition: inline` (`?download=1`: `attachment`) with the file's name. `404` when the fingerprint is not a member; `503` when the NAS file is offline or changed. |
| `POST /web/culling` | As `POST /v1/culling` (ADR 0016), written by the session's writer id. |
| `POST /web/downloads` `{"items": [ids]}` (1-5000) | `{"url": "/web/downloads/<token>", "name", "items", "files", "bytes"}`: a download link valid **10 minutes**, for this session only. `422 invalid_request` (`details.index`) for an unknown or deleted item. |
| `GET /web/downloads/{token}` | `application/zip`, streamed as it is read (no temporary file, no `Content-Length`): every file of every item (a pair's RAW and JPEG, its sidecar; a motion photo's video when it is a separate file), stored without compression (photos and videos are compressed already), names made unique (`DSC0001 (2).JPG`). A file the server cannot open is skipped and listed in `missing.txt` at the end. |

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
WebP) replaces it, then the **original** when the browser can show it (a pair's JPEG, a JPEG/PNG/WebP/AVIF photo up
to 80 MB; a RAW or HEIC stays at the preview). The header says which one is on screen ("Thumbnail", "Preview",
"Original"). **Z**, a double click or the wheel zoom to 100 % (one image pixel per device pixel) at the pointer; drag
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
