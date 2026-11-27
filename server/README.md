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
- Phone backup (0.12.0): the folders you pick on a phone are mirrored under the backup folder, one folder per phone,
  as hard links into the same content store Reveal uploads to (nothing stored twice, nothing the NAS already has
  uploaded); files deleted on the phone are kept ([ADR 0024](../docs/adr/0024-phone-backup.md)).

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
| `--nas-read-root` | `NAS_READ_ROOT` | `/mnt/nas` | the read-only NAS mount the write mount mirrors (GPU compression) |
| `--nas-write-root` | `NAS_WRITE_ROOT` | `/mnt/nas-write` | a read-write mount of the same NAS directory, used only to replace compressed videos and move their originals into the trash (see "GPU compression") |
| `--compress-sessions` | `COMPRESS_SESSIONS` | `1` | GPU encodes at a time (1-3) |
| `--vmaf-threads` | `VMAF_THREADS` | `4` | threads of one VMAF run (the verification, on the CPU) |
| `--exiftool` | `EXIFTOOL` | `exiftool` in `PATH` | exiftool, for compression's metadata (in the `-nvidia` image) |
| `--nvidia-smi` | `NVIDIA_SMI` | `nvidia-smi` in `PATH` | reads the GPU's load ("Pause while the GPU is busy"; injected by the NVIDIA container toolkit) |
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

## Away from home: Tailscale (recommended) or a tunnel

Devices sync through anything that forwards HTTPS to port 3071. The device API always requires a key, so it is safe
to expose; the setup page is **not** reachable through a tunnel or proxy unless you turn that on in Status → Settings
(only possible from your network). Keep it off unless you need it, and put Cloudflare Access in front if you do.

- **Tailscale** (recommended; the owner's own deployment uses only this, with `tailscale serve` for HTTPS and the
  LAN port at home): bind the port to the Tailscale address (`BIND_IP=100.x.y.z docker compose up -d`) and pair with
  `http://<tailscale name>:3071`, or put `tailscale serve --bg --https=443 http://127.0.0.1:3071` in front for HTTPS
  (and `TRUST_TAILSCALE_SERVE=1` for the gallery and setup page there: "HTTPS with `tailscale serve`").
  The setup page answers Tailscale addresses (100.64.0.0/10, fd7a:115c:a1e0::/48) directly.
- **Cloudflare Tunnel** (optional, public): create a tunnel in the Cloudflare dashboard with a public hostname pointing at
  `http://hoard-server:3071`, then start the `cloudflared` service of `docker-compose.yml` with the token passed at
  run time from your secret store (never a file in a repository):
  `CLOUDFLARE_TUNNEL_TOKEN=… docker compose --profile tunnel up -d`. Pair devices with the `https://` hostname.

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

## GPU compression (0.13.0, the `-nvidia` image)

hoard-server can compress the library's videos on an NVIDIA GPU (ADR 0025): HEVC with NVENC at the look of the owner's
old script (cq 28, re-encoded at cq 26 or 24 only when VMAF falls under 93), 10-bit kept, HDR10 static metadata kept,
PCM audio to AAC 256k, the file's metadata restored with exiftool (a camera only Samsung's `smta` or Sony's XML carried
is written back as the file's make/model), and a metadata sidecar of every replaced video (the original's full tag
set, what could not be restored, per-frame camera data of DJI, Sony and GoPro clips) saved as JSON in
`<data dir>/compress/meta/`. Every output is verified (duration, streams, picture, frames, metadata diff, VMAF ≥ 92,
at least 20 % smaller) before it replaces anything. The replaced original goes to the **trash** (ADR 0023: the
nearest `.hoard-trash` of the folder, by a hard link, so the path never goes missing): restore it from Hoard's Trash
or with `POST /v1/compress/restore` until the trash is emptied (Hoard's Empty, its auto-empty, or the server's own
retention of what it trashed, 30 days, on the setup page). Nothing runs until you start a job or turn "Compress
automatically" on for a folder.

Phone videos (ADR 0025, phase S2) are compressed from their backup (ADR 0024): the output is a new blob, and Hoard
writes it over the phone's file in place and reports it; the phone backup then holds the output at that path, and the
original stays a trashed server item (pinned, linked under `.hoard-mirror/compressed/`) until it is emptied or the
same retention passes. Videos no longer on the phone (freed) are compressed on the server alone: the backup's entry
stays freed and pinned with the output's content. Neither needs the write mount.

What it needs:

- the **`ghcr.io/henriquefps/hoard-server:<version>-nvidia`** image (Debian, amd64 only: the driver's libraries are
  glibc ones; the default Alpine image never compresses and keeps working without a GPU);
- the NVIDIA container toolkit with CDI (`nvidia-ctk cdi generate`), the GPU given as `nvidia.com/gpu=all` and
  `NVIDIA_DRIVER_CAPABILITIES=compute,video,utility` (`video` is NVENC/NVDEC, `utility` is `nvidia-smi`);
- a **second, read-write mount of the NAS directory** at `/mnt/nas-write` (the read-only `/mnt/nas` stays the only
  path the walk and previews read): the server writes through it only to replace a verified video and move its
  original into the trash (and back on restore), all inside an `os.Root` of the folder;
- the container running as **the owner of the library files** (`user: "<uid>:<gid>"`), so replaced files and trash
  folders keep his ownership; `/data` and the backup folder must belong to the same uid:gid (`sudo chown -R` them once
  when switching images);
- a backup folder (the work directory is `<backup folder>/.hoard-compress/work`; outputs are written there first).

```yaml
services:
  hoard-server:
    image: ghcr.io/henriquefps/hoard-server:latest-nvidia
    user: "${LIBRARY_UID:-1000}:${LIBRARY_GID:-1000}"   # the owner of the NAS files
    devices:
      - nvidia.com/gpu=all                              # CDI
    environment:
      - NVIDIA_DRIVER_CAPABILITIES=compute,video,utility
      - COMPRESS_SESSIONS=1
    volumes:
      - ./data:/data
      - ${BACKUP_DIR:-./backup}:/mnt/backup
      - ${NAS_DIR:-/srv/photos}:/mnt/nas:ro            # indexed read-only, as before
      - ${NAS_DIR:-/srv/photos}:/mnt/nas-write         # compression's replaces and trash moves only
    cpus: "4"            # VMAF and exiftool run on the CPU
    mem_limit: 6g        # 4K sources; see "Memory" below
```

**Memory.** It scales with the frame size of the videos being compressed. Measured in the `-nvidia` image on synthetic
4K HEVC 10-bit clips with CUDA decode (`TestGPUMemory`), peak memory per process: the encode (CUDA decode + NVENC)
2.0–2.1 GiB, VMAF 2.9–3.0 GiB, the full-decode check 0.45 GiB, exiftool and ffprobe under 0.1 GiB, the server 0.1 GiB. A
verification above 2.7K takes both CPU slots, so the worst case is one 4K encode next to one 4K verification, about
5 GiB: hence `6g`. Libraries with 1080p or 2.7K videos only need far less (two verifications at a time, each a fraction
of the 4K figures). Below what the videos need the kernel kills ffmpeg (a 4K encode then falls back to CPU decode, a
failed verification fails the item); the retry only partly hides it.

`runtime: nvidia` or `deploy.resources.reservations.devices` (`driver: nvidia`, `capabilities: [gpu, video]`) do the
same with older toolkits. At start (and every 10 minutes while it fails) the server lists ffmpeg's encoders, makes a
0.2 s `hevc_nvenc` test encode and decodes it with CUDA, and checks libvmaf and exiftool; the result is on the setup
page (Compression) and in `GET /v1/server` (`features` gains `compress`). The GPU is shared: one encode at a time by
default; "Pause while the GPU is busy" (setup page, off by default since 0.13.2) starts nothing new while another
program holds an NVENC session or the GPU was over 50 % busy in the last minute; an optional night window holds back
automatic work only.

## Upgrading to 0.15.0 (the trash for paired devices)

0.15.0 adds schema 14 (`0014_trash_by.sql`: one column, `trash_moves.moved_by`) and `/v1` additions only; replace the
image and restart. ADR 0023 "Amendment (0.15.0)", ADR 0013 "Trash for paired devices".

- **Hoard trashes through the server**: `GET /v1/trash`, `POST /v1/trash/move`, `…/restore`, `…/delete` and `…/empty`
  (device key; `features` gains `trash-actions`) are the web gallery's trash for paired devices: the same rules, the
  same journal, the write mount (`NAS_WRITE_ROOT`) for the NAS. A Hoard that sees `trash-actions` no longer moves,
  restores or empties the server's photos over SMB. The Trash lists who moved each shot; Empty's groups are "Moved
  from the web gallery" and "Trashed by Hoard on your devices" (through the server now, or over SMB before).
- **Large selections under a NAS walk**: journal writes wait for a long walk instead of failing (`not_writable` in
  0.14.0), the batches of one selection share one walk a few seconds after the last, and a shot that fails half-way is
  put back whole. The log line of each batch says how many failed for each reason; the gallery lists them with
  **Retry the failed ones**.
- **The web Trash page no longer polls** (it fetched the whole trash several times a second while open): it loads on
  open, after its own actions and on **Refresh**.

## Upgrading to 0.14.0 (the trash from the web gallery)

0.14.0 adds schema 13 (`0013_web_trash.sql`, new tables only: the migration needs no temporary files) and `/v1`
additions only; replace the image and restart. ADR 0023 "Amendment (0.14.0)", ADR 0024 and ADR 0025 "Amendment (0.14.0)".

- **Move to trash from the web gallery** (Select all of a filter, e.g. Rejected): NAS shots go through the write mount
  (`NAS_WRITE_ROOT`, the one compression uses) into the folder's `.hoard-trash/<day>/…`, the layout Hoard reads, so
  Hoard's Trash lists and restores them; phone backups and uploads go to a server trash (kept until it is emptied;
  devices still cannot delete backups). Without a usable write mount, NAS shots are refused with the reason and nothing
  moves. The Trash view lists everything by day and place, with Restore, Delete permanently and Empty trash; what the
  server trashed is emptied after the compression's `trashDays` (30 by default).
- **Compression**: the Compression view estimates the whole library and says whether the NAS has room for the outputs
  while the originals wait in the trash; a replace waiting for room shows as **Waiting**, not failed.
- **The `-nvidia` image decodes more camera files** (AVI with μ-law/A-law/ADPCM audio, MS-MPEG4, H.263, …): previews
  that failed are tried again once at the first start of 0.14.0 (and whenever the tools change).
- **A full backup disk** refuses uploads with `507 storage_full` before writing anything (512 MiB stay free).
- **Purging a phone's backup** (setup page) also ends that phone's compressions and releases the compressed originals
  its backup kept; the confirmation shows how many.

## Upgrading to 0.13.2 (compression follow-ups)

0.13.2 is a set of small compression fixes (ADR 0025 "Amendment (0.13.2)"); no schema change, `/v1` additions only:
replace the image and restart.

- "Pause while the GPU is busy" is now **off by default**. A server where the admin saved the switch keeps that choice;
  turn it on in the setup page (Compression) to hold new work while another program uses NVENC.
- When the free space of the NAS or of the work directory cannot be read (`statfs` fails), the server now waits as if
  space were short (`reason: no_space`, tried again after 10 minutes) instead of going on; the log says so once.
- `POST /v1/compress/estimate` answers `subfolders` (how many folders below the selected ones hold its videos).
- Phone reports (`…/replaced`, `…/restored`) after a rename on the phone check the mtime of the file at its new path.

## Upgrading to 0.13.1 (the trash link held until the swap)

0.13.1 fixes a window in the NAS replace (ADR 0025 "Amendment (S1)"): the original's fresh link in `.hoard-trash` is
marked `held` on its item from the moment it is made until the replace (or a restore) settles, and the swap checks the
link is still there right before it renames the output over the path. If an Empty deleted it meanwhile, the original
stays at its path and the item fails `trash_failed` (a restore goes back with `reason: trash_failed`). No schema or
protocol change: replace the image and restart.

## Upgrading to 0.13.0 (video compression)

0.13.0 adds video compression (ADR 0025, above). With the default image nothing changes: the feature is absent, the
compression routes that start work answer `503 unavailable` (`no_gpu`), and no file is written. A database migration
(schema 12: `compress_jobs`, `compress_items`, `video_probe`, new tables only) runs on start and needs no temporary
directory. Items gain optional fields (`compressed` on an output, `compressedTo` and `replacedBy` on a trashed
original; a trashed item's tombstone shape for older clients carries `replacedBy`): released Hoard and Reveal ignore
them. **Back up `data/` before upgrading**: 0.12.x refuses a schema-12 database. To compress, switch to the `-nvidia`
image as described above (it runs as the library owner's uid: chown `data/` and the backup folder to it first).

Before the release, runs on copies of real camera clips changed these things (ADR 0025 "Amendment (N4)"):

- **Memory.** Give the `-nvidia` container `mem_limit: 6g` (the earlier example's `2g` let the kernel kill ffmpeg on
  2.7K and 4K clips); see "Memory" above for the measured figures. A verification above 2.7K now takes both CPU slots,
  and VMAF and the decode check use `VMAF_THREADS` decoder threads, so memory no longer grows with the host's cores.
- **Edit lists.** A clip whose MP4 edit list hides leading frames (Samsung VFR) no longer fails `verify_duration`: when
  only the video stream's header duration differs, the decoded frames of both files decide.
- **Camera and metadata sidecar.** A camera only Samsung's `smta` or Sony's NRTM XML carried (exiftool cannot write
  those back) is written to the output's UserData make/model, so Hoard's, Reveal's and the web gallery's camera
  filters keep seeing it; an output without the source's make or model (the same values, any case) fails
  `verify_metadata`. Every replaced video
  gets a metadata sidecar in `/data/compress/meta/` (the original's full exiftool tag set, the tags not restored, and
  the per-frame data when there is any); the item's log lists the tags that were not restored.
- **One Keys table.** Videos with QuickTime Keys tags (iPhones' `com.apple.quicktime.*`, Android's) no longer fail
  `verify_metadata`: exiftool restores the original's Keys as the file's only Keys table, and the marker is now the Keys
  entry `com.apple.quicktime.dev.hfps.hoard.compressed` (the server reads both names); every check confirms that the
  output carries it.
- **Per-frame data over 512 MiB.** A clip whose per-frame metadata (uncompressed) is over 512 MiB fails
  `verify_metadata` and keeps its original, instead of a truncated sidecar.

Developer note: schema 12 changed in place before the release (`compress_items.stalled_at` and `kept_entry_id` were
added to the unreleased `0012_compress.sql`). A throwaway data directory created from `main` since #71 has the old
shape under the same version: recreate it. Production 0.12.x (schema 11) is unaffected.

**Add the write mount (`/mnt/nas-write`) only once every device runs Hoard 2.7.0 or later.** A compression whose
replace has to wait (the NAS went away between two steps) keeps its original in `.hoard-trash`, marked `held` on the
item; Hoard 2.7.0 and later leave held files when they empty the trash, older Hoard (2.6.x empties `.hoard-trash` over
SMB on its own) does not know the mark and could delete that original. Without the write mount the feature `compress`
stays off and nothing is written; the setup page's Compression tab says why it is off. The feature also stays off when
the write mount is not the same directory as `/mnt/nas`, is read-only for the server's user, or does not keep inode
numbers stable (a CIFS mount without `serverino`, some FUSE file systems).

## Upgrading to 0.12.1 (verify can demand a fresh re-hash)

0.12.1 adds an optional `maxAge` (milliseconds) to `POST /v1/backup/verify`, for Hoard's "Free up space": a stored
re-hash older than `maxAge` is done again in the background (the answer is `pending` until then) instead of being
trusted for 30 days. `maxAge` is clamped to 1 hour to 30 days, so a client cannot make the server hash nonstop;
without it nothing changes. No migration; the API is `/v1`-compatible (ADR 0013, ADR 0024 Amendment S1).

## Upgrading to 0.12.0 (phone backup)

0.12.0 adds the phone backup (ADR 0024) for Hoard 2.4.0 and later: nothing happens until you turn on a folder in
Hoard's Settings › Backup. Each phone gets a folder next to `reveal-sync/` in the backup folder,
`<backup folder>/<phone name> (<8 hex digits>)/<path on the phone>` (an SD card under `SD card <UUID>/`), whose
files are **hard links** into `reveal-sync/blobs/`: a photo Reveal already uploaded, or one the NAS already holds, is
never uploaded again, and the folder takes no extra space. A NAS file is not copied there (it is listed in the
folder's `.hoard-mirror/manifest-<root>.tsv`); when the phone no longer has such a file, the server keeps its own copy
(Setup page › Phone backups › "Keep a copy of NAS files a phone no longer has", on). Files deleted on the phone stay;
deleting a phone's backup is done only on the setup page, which shows first how many files exist nowhere else (freed
on the phone); it removes only what the server put in the phone's folder, so files you added there yourself stay. On
the phone's main storage, a folder named like an SD card's (`SD card 1234-ABCD`) cannot be backed up.

- Keep the blob store and the phone folders on **one filesystem** (they are both in the backup folder, as the example
  compose file mounts it). If a hard link is impossible (another filesystem, `EXDEV`, or `EPERM`), the server keeps
  the file in its index only and never copies it; the manifest lists it as `blob-unlinked`.
- Stored blobs become read-only (`0400`) as they are linked. **Copy the backup folder with hard links preserved**
  (`cp -a`, `rsync -aH`), or it takes the mirror's space twice.
- A database migration (schema 10) is applied in place on start: no step to take, and it needs no temporary directory
  (it builds indexes on new, empty tables only). Every original uploaded before 0.12.0 is recorded as claimed by its
  uploader, so nothing Reveal backed up can be deleted through a phone backup. **Back up `data/` before upgrading**:
  0.11.x refuses a schema-10 database.
- Released Reveal (0.9.0) keeps working unchanged; Reveal 0.10.0 stops uploading from folders Hoard backs up.

## Upgrading to 0.11.1 (a read-only container starts)

0.11.0 could crash-loop on upgrade in a container with `read_only: true` and no writable `/tmp` (the example
`docker-compose.yml`), logging `migrate: 0009_trash.sql: disk I/O error (6410)`: the migration rebuilds the `items`
indexes and SQLite wanted a temporary file to sort them. 0.11.1 keeps SQLite's temporary data in memory
(`PRAGMA temp_store = MEMORY`, a few tens of MB for a library of this size), so no temporary directory is needed, and the
example compose file now also mounts a `tmpfs: [/tmp]` on the service. No data change, nothing to do but pull the new
image (a failed 0.11.0 start left the database at schema 8: the migration is one transaction). The start-up hint for
an I/O error no longer blames the data directory's owner: it does that only for permission errors.

## Upgrading to 0.11.0 (trash)

0.11.0 understands Hoard's trash (ADR 0023): Hoard 2.3.0 moves rejected photos into a hidden `.hoard-trash` folder on
the share instead of deleting them. The walk now reads those folders (other hidden folders stay skipped) and keeps a
trashed photo as **trashed**, not deleted: its Reveal edits, previews and culling stay, and it comes back as the same
item when Hoard restores it. Only emptying the trash deletes it (and its edits, as before). Trashed photos leave the web
gallery and Hoard's Gallery; Reveal sees them as removed NAS files (with `trashed: true`) until it shows a trash.
Moving into or out of the trash costs no hashing. Hoard asks for a walk after it moves files (`POST /v1/nas/walk`; at most one such walk per folder per minute, whoever asks).

A database migration (schema 9) is applied in place on start: no step to take, and nothing changes for released Reveal
or Hoard (clients that do not ask for the trash get the same answers as before). **Back up `data/` before upgrading**:
0.10.x refuses a schema-9 database. One behaviour change for every folder: a sub-folder the server cannot read (a
permission change) no longer makes its files count as deleted; they are kept until the folder can be read again.

## Upgrading to 0.10.1 (sidecar import skips first-write defaults, first read keeps decisions)

0.10.1 changes one thing in how the server imports the culling values of NAS sidecars (ADR 0016): a sidecar value that is the
default (rating `0`, flag `none`, no label) for a field the item's culling record has never held is no longer written. Hoard
and Lightroom write such sidecars for undecided shots (`xmp:Rating="0"`, `digiKam:PickLabel="0"`), which used to cost two
revisions per shot with no visible change. A default that arrives after a real value is still imported as a clear, and
`POST /v1/culling` from devices is unchanged.

The same release adds the **first-read rule** (ADR 0016, step 2b): a sidecar the server has never read (no record of it, or
its record was dropped because the file disappeared) only fills the fields that hold no value (rating 0, flag none and no
label count as no value), whatever its mtime, so a sidecar another tool creates for a shot you already rated or flagged
does not overwrite that decision. Later changes of the same sidecar apply as before (newest wins). The walk forgets the
sidecars whose file is gone (only after a walk that read the whole folder), so a recreated sidecar is a first read again.
A walk that sees no photo or video in a folder while the index holds some for it is now treated as an unmounted folder:
the folder shows `unavailable`, and nothing is removed (items, files, sidecar records) until files are there again. The
trade-off: a folder you really empty keeps its items until it holds a file again.

No API change, no migration, no step to take; revisions already written stay.

## Upgrading to 0.10.0 (items by path, device liveness)

0.10.0 adds two things for Hoard's hand-off of photos to Reveal, both additive on `/v1` and with no database migration:
`POST /v1/items/by-path` (the live item of each file path in a NAS folder; `features` gains `items-by-path`, ADR 0014)
and `lastSeenAt` on the devices of `GET /v1/device` (the list stays live devices only) (ADR 0017). No step to take.

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
  mode 0600 (0400 once a phone backup links them, 0.12.0).
- `<backup folder>/<phone name> (<8 hex digits>)/` (0.12.0, ADR 0024) mirrors a phone's backed-up folders: hard links
  into `reveal-sync/blobs/`, plus `.hoard-mirror/manifest-<root>.tsv` (every file of a folder: path, size, SHA-256,
  fingerprint, where its bytes are, whether the phone still has it), `.hoard-mirror/previous/` (earlier content of
  files rewritten on the phone) and `.hoard-mirror/compressed/` (0.13.0: originals of compressed phone videos, until
  they are emptied from the trash). The tree plus its manifests describe a phone's backup without the database. Copy it
  with hard links preserved (`cp -a`, `rsync -aH`). The setup page can rename a phone's folder. The backup folder is fixed once it holds files: to move it, stop the server, move the `reveal-sync`
  folder to the new place, mount it at the same path inside the container (or update the `backupRoot` setting).
- `/data/edits/` holds Reveal's edited previews (0.9.0, ADR 0020; one file per image, named by its SHA-256, metadata
  removed). Back it up with the database: unlike the previews below, the server cannot make them again (Reveal can
  upload them again, but only from a device that still has the edits).
- `/data/previews/` holds the gallery previews (`<key[0:2]>/<key>.thumb.jpg`, `.thumb.webp`, `.screen.jpg`,
  `.screen.webp`, `.proxy.mp4`); they
  can be deleted with the server stopped (with `DELETE FROM previews` in the database) and are made again.
- Nothing is ever written to a NAS folder (XMP sidecars there are read, never written), except through the optional
  write mount of GPU compression (0.13.0): a verified output over its original, the original into `.hoard-trash`, and
  back on restore.
- `/data/compress/` holds compression's item logs (`logs/<job>/<item>.log`, kept 90 days) and the metadata sidecar of
  every replaced video (`meta/<xx>/<fingerprint>.json.gz`: the original's tags, what was not restored, per-frame camera
  data): back it up with the rest of `data/`.
- Logs never contain keys, tokens, passwords, file names or paths of photos.

## Releases

Push a tag `server-v<version>` (e.g. `server-v0.4.0`): `.github/workflows/server-release.yml` tests, attaches static
binaries to a GitHub release and publishes `ghcr.io/henriquefps/hoard-server:<version>` and `:latest` for amd64 and
arm64, and `ghcr.io/henriquefps/hoard-server:<version>-nvidia` and `:latest-nvidia` (`Dockerfile.nvidia`, amd64). The
Hoard app keeps its own `v*` tags.

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
# GPU compression on an NVIDIA host (builds the -nvidia image, runs it as your uid on temporary directories):
HOARD_GPU_TEST=1 go test -run TestGPU -v -timeout 90m .
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
The `-nvidia` image (`Dockerfile.nvidia`) adds, built from pinned releases: FFmpeg with NVENC/NVDEC (nv-codec-headers,
MIT; NVIDIA's libraries are not shipped, the driver provides them), libvmaf (BSD-2-Clause-Patent;
github.com/Netflix/vmaf) and exiftool (Perl, GPL-1.0-or-later or Artistic; exiftool.org), on Debian's packages.
