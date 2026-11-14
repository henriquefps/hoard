# hoard-server

`hoard-server` is the self-hosted photo server for [Hoard](../README.md) and
[Reveal](https://github.com/henriquefps/reveal): it keeps edits, ratings and virtual copies in step between your
devices, backs up the photos that live only on a device, and serves the photos already on your NAS to your devices,
even away from home. You run it yourself; nothing assumes anyone else's server. It was Reveal's `reveal-sync` until
it moved here ([ADR 0012](../docs/adr/0012-hoard-server.md)); an existing reveal-sync data directory works as is.

- One static binary (Linux amd64/arm64) or a Docker image (Alpine, non-root, with libvips and ffmpeg for previews).
- Everything in two places you choose: `/data` (a SQLite database) and a backup folder (content-addressed files).
- NAS folders are mounted read-only and indexed; photos the server can already reach are never uploaded.
- One index of every photo and video: each shot is an **item** (a photo, a RAW+JPEG pair, a video, a motion or Live
  Photo) with its files, where they live and its metadata (capture time, camera, lens, GPS, size; codec, duration,
  frame rate, HDR for videos), from the NAS folders and from uploads ([ADR 0014](../docs/adr/0014-server-items.md)).
- Gallery previews of every item, made on the server: thumbnails (512 px), screen previews (2560 px), video posters
  and low-bitrate H.264 proxies for phones, upright and sRGB, from the best source (a pair's camera JPEG, a RAW's
  embedded preview, HEIC/AVIF, HDR video tone-mapped), in a background queue ([ADR 0015](../docs/adr/0015-server-previews.md)).
- Ratings, pick / reject and colour labels in step between Hoard and Reveal, per field (a rating in one app and a
  pick in the other both survive), and read from the XMP sidecars on the NAS ([ADR 0016](../docs/adr/0016-culling-sync.md)).
- Every app has its own revocable key (pair by QR code); Hoard and Reveal on one phone are grouped as one device
  ([ADR 0017](../docs/adr/0017-device-identity.md)). There is no anonymous mode.
- Conflicts never lose an edit: the newer one wins and the other becomes a virtual copy.
- A web gallery for your computers on the LAN or Tailscale: browse every item by date, search and filter, cull with
  Hoard's keys, view at 100 %, play videos, download originals or a zip ([ADR 0018](../docs/adr/0018-web-gallery.md)).

The protocol (for the apps and any other client) is [ADR 0013](../docs/adr/0013-server-protocol.md), with an OpenAPI
description in [`api/openapi.yaml`](api/openapi.yaml). `/v1` is frozen: additions only (ADR 0012).

## Docker (recommended)

```sh
mkdir hoard-server && cd hoard-server
curl -LO https://raw.githubusercontent.com/henriquefps/hoard/main/server/docker-compose.yml
mkdir -p data backup && sudo chown 65532:65532 data backup     # the container runs as uid 65532
NAS_DIR=/path/to/your/photos docker compose up -d
docker compose logs hoard-server | grep setupCode               # first run: a one-time setup code
```

Open `http://<this machine>:3071/admin/` from your network (or Tailscale), enter the setup code and choose an admin
password. Then:

1. **Storage:** pick the backup folder (`/mnt/backup`).
2. **NAS folders:** add `/mnt/nas` (or the sub-folders you want); the server indexes them in the background.
3. **Devices → Pair a device:** check the server address the devices will use, show the QR code and scan it in
   Reveal (Settings → Sync). Each device gets its own key; revoke it here if a phone is lost.

The container only sees what `docker-compose.yml` mounts: `./data` → `/data`, the backup folder → `/mnt/backup` and
the NAS share → `/mnt/nas` (read-only). The setup page offers the folders under `/mnt` and nothing else; mount more
volumes under `/mnt/<name>` to offer more.

Building the image yourself: `docker build -t hoard-server .` from `server/` with BuildKit (the default with buildx);
the legacy builder needs `--build-arg BUILDPLATFORM=linux/amd64 --build-arg TARGETOS=linux --build-arg TARGETARCH=amd64`.

## Web gallery

Open `http://<this machine>:3071/` from your network or Tailscale and sign in with the admin password. The gallery
answers only direct LAN/Tailscale requests (like the setup page, and even when the setup page is allowed through a
proxy): through a Cloudflare Tunnel or a reverse proxy it is `404`. The one proxy it can trust is `tailscale serve`
on the same machine, for HTTPS (below).

### HTTPS with `tailscale serve` (needed for downloads from another computer)

Chrome (and Edge, Brave, Arc) block downloads from a plain-HTTP page that is not on the same computer ("Insecure
download blocked"), so on `http://<server>:3071/` from your laptop browsing works but downloads do not. Give the
gallery Tailscale's HTTPS address instead, with no certificate to manage:

1. In the Tailscale admin console, turn on **MagicDNS** and **HTTPS certificates** (DNS page) once for the tailnet.
2. Start the server with `TRUST_TAILSCALE_SERVE=1` (Docker: add it to `environment:`). Off by default.
3. Make the server reachable on the machine's loopback: run it without `--listen`/`LISTEN`, or with Docker publish the
   port on `127.0.0.1` too (`ports: ["127.0.0.1:3071:3071", "${BIND_IP:-0.0.0.0}:3071:3071"]` when `BIND_IP` limits the
   other one).
4. On the server machine (not inside the container):

   ```sh
   sudo tailscale serve --bg --https=443 http://127.0.0.1:3071
   tailscale serve status      # shows https://<machine>.<tailnet>.ts.net/
   ```

   Another HTTPS port works the same way (`--https=8443`, then `https://<machine>.<tailnet>.ts.net:8443/`). To stop:
   `sudo tailscale serve --https=443 off`. Never use `tailscale funnel` for it: Funnel requests (from the internet)
   are refused.
5. Open `https://<machine>.<tailnet>.ts.net/` and sign in again (the HTTPS address is another site for the browser).

What the server trusts, only with `TRUST_TAILSCALE_SERVE=1`: a request from loopback (in a container, from the Docker
bridge's gateway, found at start and logged) carrying exactly one tailnet address (100.64.0.0/10 or
fd7a:115c:a1e0::/48) in `X-Forwarded-For`, which `tailscale serve` sets, and none of `Tailscale-Funnel-Request`,
`Cf-Connecting-Ip`, `X-Real-Ip`, `Forwarded`. It applies to the gallery and the setup page; the session cookie is
`Secure` over that HTTPS and the sign-in's `Origin` check uses the `ts.net` name (`X-Forwarded-Host`). Over plain HTTP
from another computer the gallery shows a line saying downloads need the HTTPS address. Every download is named
after the original (`Content-Disposition` with `filename*`, and the name at the end of the URL): one file keeps its
name and extension, a single pair or a photo with its sidecar arrives as `<name>.zip`, a selection as
`<directory>-<n> photos.zip` (or the capture days when it spans several directories).

- **Gallery**: every photo and video, newest first, with a header per day and a jump bar of years and months; badges
  for RAW+JPG, RAW, videos (duration), motion photos, stars, pick / reject and photos edited in Reveal. Filters: kind,
  camera, stars, picked / rejected / not flagged, folder, dates, edited; the search matches file and folder names.
- **Viewer**: the screen preview; Z, a double click or the wheel zoom to 100 %, and once the preview would be
  magnified the viewer swaps in full resolution in place: the original (a JPEG, a pair's camera JPEG) or, for a lone
  RAW or a HEIF, a full-size JPEG the server makes on demand ("Rendering full resolution…", then "Original (rendered
  from the RAW)"); drag to pan; I for details (exposure, files with downloads, who picked or rated it); videos from the
  server's proxy.
- **Culling** with Hoard's keys: P pick, X reject, U clear, 1-5 stars (0 clears), ←/→, Ctrl+Z; in the grid they apply
  to the selection (Ctrl/Shift-click, Ctrl+A). Decisions sync with Hoard and Reveal (ADR 0016); theirs appear within
  seconds without a reload.
- **Folders**: the NAS folders as a tree with counts.
- **Downloads**: one file, or a zip the server streams (a pair's RAW, JPEG and sidecar; any selection).

Changing the admin password signs every browser out. There is no setting to turn it on: it is part of the server.

## Configuration

| Flag | Environment | Default | Meaning |
| --- | --- | --- | --- |
| `--port` | `PORT` | `3071` | port |
| `--listen` | `LISTEN` | all interfaces | address to bind (e.g. a Tailscale IP) |
| `--data-dir` | `DATA_DIR` | `./data` (`/data` in the image) | database and settings |
| `--mounts` | `MOUNTS` | `/mnt` | comma-separated mount roots the setup page may offer |
| `--backup-root` | `BACKUP_ROOT` | none | backup folder to use until one is chosen on the setup page |
| `--nas-interval` | `NAS_INTERVAL` | `30m` | how often NAS folders are walked again |
| `--log-format` | `LOG_FORMAT` | `json` | `json` or `text` |
| `--ffprobe` | `FFPROBE` | `ffprobe` in `PATH` (`/usr/local/bin/ffprobe` in the image) | ffprobe binary for video metadata |
| `--probe-workers` | `PROBE_WORKERS` | `2` | ffprobe runs at a time (1-16) |
| `--probe-timeout` | `PROBE_TIMEOUT` | `30s` | time limit of one ffprobe run |
| `--previews-dir` | `PREVIEWS_DIR` | `<data dir>/previews` | where gallery previews are kept (rebuildable, not backed up) |
| `--vips` | `VIPS` | `vips` in `PATH` (`/opt/hoard/bin/vips` in the image) | libvips' `vips`, for photo previews |
| `--ffmpeg` | `FFMPEG` | `ffmpeg` in `PATH` (`/opt/hoard/bin/ffmpeg` in the image) | ffmpeg, for video posters and proxies |
| `--preview-workers` | `PREVIEW_WORKERS` | `2` | photo/poster previews made at a time (1-16) |
| `--proxy-workers` | `PROXY_WORKERS` | `1` | video proxies made at a time (1-8) |
| `--preview-threads` | `PREVIEW_THREADS` | `2` | threads of one vips/ffmpeg run (1-32) |
| `--preview-backlog` | `PREVIEW_BACKLOG` | `image,proxy` | what is made for every item ahead of requests: `image`, `proxy`, or `none` (the rest only when a client asks) |
| `--full-cache-mb` | `FULL_CACHE_MB` | `4096` | disk kept for the gallery's full-resolution stills of RAW and HEIF photos (made on demand, least recently viewed dropped first; at least 64) |
| `--trust-tailscale-serve` | `TRUST_TAILSCALE_SERVE` | off | let the gallery and setup page answer requests `tailscale serve` proxies on this machine (see "HTTPS with `tailscale serve`") |

These are the names reveal-sync used; none was renamed (there were never `REVEAL_SYNC_*` variables). The ffprobe
settings are new in 0.5.0. Without ffprobe (a binary built by hand) videos are still indexed; their metadata is read
once ffprobe is available.

## Gallery previews

From 0.6.0 the server makes previews of every item in the background (newest shots first; a preview a client asks for
jumps the queue): a 512 px thumbnail and a 2560 px screen JPEG of every photo (a video's are its poster frame), and
an H.264 720p proxy of every video and motion photo, as JPEG and WebP (`format=webp`, a fifth to a third smaller). They
live in `<data dir>/previews` (about 1 GiB per 1,000 photos, plus about 2 MB per 10 s of video; the Storage tab shows
the size, the queue and failures) and never touch the originals. Generation is CPU work
at low priority, at most `PREVIEW_WORKERS` + `PROXY_WORKERS` processes of `PREVIEW_THREADS` threads at a time; the
first pass over a large library takes a while (see ADR 0015, "Measurements"). Change `PREVIEW_BACKLOG` to `image` to
make proxies only when a video is played, or `none` to make everything on demand.

Memory: the example compose file gives the container 1.5 GiB; a 4K HEVC 10-bit proxy needs the most (ffmpeg about
740 MiB; everything together stayed under 900 MiB on the bench). Without libvips/ffmpeg (a binary built by hand) the server runs and previews wait until they are
installed.

## Away from home: Cloudflare Tunnel or Tailscale

Devices sync through anything that forwards HTTPS to port 3071. The device API always requires a key, so it is safe
to expose; the setup page is **not** reachable through a tunnel or proxy unless you turn that on in Status → Settings
(only possible from your network). Keep it off unless you need it, and put Cloudflare Access in front if you do.

- **Cloudflare Tunnel**: create a tunnel in the Cloudflare dashboard with a public hostname pointing at
  `http://hoard-server:3071`, then start the `cloudflared` service of `docker-compose.yml` with the token passed at
  run time from your secret store (never a file in a repository):
  `CLOUDFLARE_TUNNEL_TOKEN=… docker compose --profile tunnel up -d`. Pair devices with the `https://` hostname.
- **Tailscale**: bind the port to the Tailscale address (`BIND_IP=100.x.y.z docker compose up -d`) and pair with
  `http://<tailscale name>:3071`, or put `tailscale serve --bg --https=443 http://127.0.0.1:3071` in front for HTTPS
  (and `TRUST_TAILSCALE_SERVE=1` for the gallery and setup page there: "HTTPS with `tailscale serve`").
  The setup page answers Tailscale addresses (100.64.0.0/10, fd7a:115c:a1e0::/48) directly.

## Upgrading from reveal-sync

Same data directory, same backup folder, new image; no migration and no protocol change, so Reveal keeps working
with its existing pairing.

1. Stop the old container: `docker compose down` (in the reveal-sync folder). Optionally back up `data/` first.
2. In its `docker-compose.yml`, change `image: ghcr.io/henriquefps/reveal-sync:…` (or your local build) to
   `image: ghcr.io/henriquefps/hoard-server:latest` (or a pinned version). Keep the volumes and environment as they
   are; renaming the service or container to `hoard-server` is optional (the tunnel's hostname target must match the
   service name if you rename it).
3. `docker compose up -d`. The log says `using the reveal-sync database file name` and `hoard-server listening`.

What stays as it was on disk: the database keeps its name `reveal-sync.db` (a new installation gets
`hoard-server.db`), and blobs stay under `<backup folder>/reveal-sync/` (the blob folder keeps that name for every
installation: it is part of the data format). Device keys (`rvk1_…`), the admin password and the settings carry over;
a browser signed in to the old setup page stays signed in. Rolling back is the same swap in reverse.

Environment variables: unchanged (see "Configuration"). Renamed things you may notice: the container and service
names in the example compose file, the binary (`/usr/local/bin/hoard-server`), the log message, the setup page's
title, the admin header `X-Hoard-Admin` and cookie `hoard_server_admin` (the old names are still accepted).

## Upgrading to 0.6.0 (previews)

0.6.0 adds the previews (ADR 0015) with a migration (schema 4) applied in place on start, and the image grows from
35 to 86 MB (libvips, ffmpeg). After the upgrade the server starts making previews of the whole library in the
background; give the container more memory (`mem_limit: 1536m` in the example compose file) and, on a small machine,
lower `PREVIEW_WORKERS`/`PREVIEW_THREADS` or set `PREVIEW_BACKLOG=image`. Nothing changes for Reveal. Going back to
0.5.0 means restoring a copy of `data/` made before the upgrade (0.5.0 refuses a schema-4 database).

## Upgrading to 0.5.0 (items)

0.5.0 adds items (ADR 0014) with a database migration (schema 3) applied in place on start: no step to take, and
nothing changes for Reveal. The first NAS walk afterwards reads the metadata of every photo (headers only; photos are
not hashed again) and fingerprints the videos and sidecars 0.4.0 skipped, so it takes longer than usual once (about
85 s for 20,000 files on local disks; on a NAS, mostly the 2 MiB read per new file).

**Back up `data/` before upgrading** (stop the container, copy the folder): 0.4.0 refuses to open a schema-3
database, so going back to 0.4.0 means restoring that copy.

## Upgrading to 0.9.0 (Reveal's edited previews)

0.9.0 lets Reveal upload a preview of each edited photo and virtual copy (ADR 0020): the gallery then shows Reveal's
result with an "Edited" badge (E switches to the original in the viewer; virtual copies open as a stack). A database
migration (schema 8) is applied in place on start: no step to take, and nothing changes for released Reveal or Hoard
(new routes and a new optional `edits` field on items). The uploads are kept in `<data dir>/edits` (about 0.4-2.6 MB
each): only Reveal can make them again, so they belong in your backups with the database (below). **Back up `data/`
before upgrading**: 0.8.x refuses a schema-8 database.

## Upgrading to 0.8.1 (web gallery fixes)

No migration and nothing to do for Reveal or Hoard. New: sharp 100 % zoom (full resolution on demand; RAW and HEIF
renders kept under `<previews dir>`, at most `FULL_CACHE_MB`), downloads named after the originals, and HTTPS for the
gallery behind `tailscale serve` with `TRUST_TAILSCALE_SERVE=1` (above). Culling set in the gallery reaches Hoard and
Reveal through the culling feed (ADR 0016); a Reveal release that does not pull culling yet shows it once it does.

## Upgrading to 0.8.0 (web gallery)

0.8.0 adds the web gallery (ADR 0018) with a database migration (schema 7: gallery sessions, an index on previews)
applied in place on start: no step to take, and nothing changes for Reveal or Hoard. `http://<server>:3071/` now opens
the gallery on your network (it used to redirect to `/admin/`, which is unchanged). **Back up `data/` before
upgrading**: 0.7.x refuses a schema-7 database.

## Upgrading to 0.7.0 (culling, one device per phone)

0.7.0 adds culling records (ADR 0016) and physical devices (ADR 0017) with database migrations (schema 6) applied in
place on start: no step to take, and nothing changes for Reveal. On the first start the ratings, picks and labels
Reveal synced in its photo records become culling records (once); on the next walk the server reads the XMP sidecars
of the NAS folders (it never writes them; a label cleared in Lightroom is picked up as cleared). Every paired device
becomes a device of its own on the Devices page; apps on the same phone are grouped when the second app pairs (one tap
in the app) or with **Move to…** there. Debug and bench builds (`reveal-debug`, `hoard-debug`, …) count as apps of
their own, so they can sit next to the release on one phone.

Once a Reveal version that syncs culling per field declares it (`features: ["culling"]`), the server stops reading
culling from that device's photo records; older Reveal versions keep being bridged (a reject from them keeps its stars).

**Back up `data/` before upgrading**: 0.6.x refuses to open a schema-6 database.

## Without Docker

```sh
cd server && go build -o hoard-server .              # Go version from go.mod, no C compiler needed
./hoard-server --data-dir ./data --mounts /srv/photos   # offers the folders under /srv/photos
# video metadata needs ffprobe, previews need vips and ffmpeg (your distribution's ffmpeg and libvips-tools with
# HEIF support; libvips >= 8.17 for RAW decoding with LibRaw), or --ffprobe/--vips/--ffmpeg /path/to/...
```

## Data and backups

- `/data/hoard-server.db` (or `reveal-sync.db` for a data directory made by reveal-sync; SQLite, WAL) holds devices,
  edits, the change feed, the NAS index and the items (about 4 KiB per item: 54 MiB for 12,700 items from 20,000
  files). Back it up with the server stopped, or with
  `sqlite3 hoard-server.db ".backup copy.db"` while it runs.
- `<backup folder>/reveal-sync/blobs/` holds the uploaded originals and previews, named by their SHA-256; files are
  mode 0600. The backup folder is fixed once it holds files: to move it, stop the server, move the `reveal-sync`
  folder to the new place, mount it at the same path inside the container (or update the `backupRoot` setting).
- `/data/edits/` holds Reveal's edited previews (0.9.0, ADR 0020; one file per image, named by its SHA-256, metadata
  removed). Back it up with the database: unlike the previews below, the server cannot make them again (Reveal can
  upload them again, but only from a device that still has the edits).
- `/data/previews/` holds the gallery previews (`<key[0:2]>/<key>.thumb.jpg`, `.thumb.webp`, `.screen.jpg`,
  `.screen.webp`, `.proxy.mp4`); they
  can be deleted with the server stopped (with `DELETE FROM previews` in the database) and are made again.
- Nothing is ever written to a NAS folder (XMP sidecars there are read, never written).
- Logs never contain keys, tokens, passwords, file names or paths of photos.

## Releases

Push a tag `server-v<version>` (e.g. `server-v0.4.0`): `.github/workflows/server-release.yml` tests, attaches static
binaries to a GitHub release and publishes `ghcr.io/henriquefps/hoard-server:<version>` and `:latest` for amd64 and
arm64. The Hoard app keeps its own `v*` tags.

## Development

```sh
(cd web && bun install && bun run build)   # the web gallery into internal/webui/dist (optional for Go tests)
go vet ./... && go test -race ./...   # unit tests, an end-to-end test that builds and runs the binary, the
                                      # reveal-sync and 0.4.0 data directory tests and the Reveal contract test
golangci-lint run ./...
# ffmpeg/ffprobe and vips on PATH run the video and preview tests (skipped without them; FIXTURE_FFMPEG names a full
# ffmpeg when the one under test is the image's minimal build). Opt-in:
HOARD_SAMPLES_DIR=~/raw-samples go test -run RealSamples -v ./internal/media/ ./internal/previews/   # real camera files (never commit them)
HOARD_BENCH_FILES=20000 go test -run TestIndexLargeTree -v -timeout 30m ./internal/nas/   # indexing benchmark
```

Web gallery (`web/`, Vite + React + shadcn, ADR 0018): `bun run dev` (proxies `/web` to a local server,
`HOARD_SERVER`), `bun run lint`, `bun run typecheck`, `bun run test`, `bun run e2e` (Playwright against the binary with a
synthetic library; see `docs/testing/web-gallery.md`).

Layout: `main.go` (flags, database file), `internal/server` (HTTP: `/v1` device API, `/admin` setup page in
`internal/server/web`, the gallery's `/web/` API in `web*.go`), `internal/webui` (the embedded gallery build), `internal/store` (SQLite, migrations in `internal/store/migrations`: never edit a released
one), `internal/blobstore`, `internal/nas` (indexer), `internal/mounts` (path confinement), `internal/fingerprint`
(the `v1:` rule, vectors shared with Reveal in `testdata/fingerprint-vectors.txt`), `internal/auth`,
`internal/ratelimit`, `internal/xmp` (culling values of XMP sidecars, read only), `internal/media` (metadata readers: EXIF/TIFF, JPEG, HEIF, CR3, RAF, motion photos, ffprobe,
RAW embedded previews; `mediatest` builds synthetic files), `internal/items` (pairing rule, item building, sync of
sources and items), `internal/previews` (gallery previews: plan and keys, the queue manager, the CPU generator).
`testdata/compat/` holds a reveal-sync data directory and a hoard-server 0.4.0 one (each with the program that made
it) that every release must open; `testdata/v1-routes.txt` lists the frozen `/v1` routes.

Licence: [AGPL-3.0-or-later](../LICENSE). The Docker image also ships, run as separate programs and built in the
`Dockerfile` from the pinned upstream releases: FFmpeg (`ffmpeg`, `ffprobe`; GPL-2.0-or-later configuration because of
x264; source: ffmpeg.org), libvips (LGPL-2.1-or-later; github.com/libvips/libvips) and libheif (LGPL-3.0;
github.com/strukturag/libheif), with Alpine's packages of LibRaw, x264, zimg, dav1d, libde265 and their dependencies.
