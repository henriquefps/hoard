# hoard-server

`hoard-server` is the self-hosted photo server for [Hoard](../README.md) and
[Reveal](https://github.com/henriquefps/reveal): it keeps edits, ratings and virtual copies in step between your
devices, backs up the photos that live only on a device, and serves the photos already on your NAS to your devices,
even away from home. You run it yourself; nothing assumes anyone else's server. It was Reveal's `reveal-sync` until
it moved here ([ADR 0012](../docs/adr/0012-hoard-server.md)); an existing reveal-sync data directory works as is.

- One static binary (Linux amd64/arm64) or a small Docker image (distroless, non-root).
- Everything in two places you choose: `/data` (a SQLite database) and a backup folder (content-addressed files).
- NAS folders are mounted read-only and indexed; photos the server can already reach are never uploaded.
- Every device has its own revocable key (pair by QR code). There is no anonymous mode.
- Conflicts never lose an edit: the newer one wins and the other becomes a virtual copy.

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

These are the names reveal-sync used; none was renamed (there were never `REVEAL_SYNC_*` variables).

## Away from home: Cloudflare Tunnel or Tailscale

Devices sync through anything that forwards HTTPS to port 3071. The device API always requires a key, so it is safe
to expose; the setup page is **not** reachable through a tunnel or proxy unless you turn that on in Status → Settings
(only possible from your network). Keep it off unless you need it, and put Cloudflare Access in front if you do.

- **Cloudflare Tunnel**: create a tunnel in the Cloudflare dashboard with a public hostname pointing at
  `http://hoard-server:3071`, then start the `cloudflared` service of `docker-compose.yml` with the token passed at
  run time from your secret store (never a file in a repository):
  `CLOUDFLARE_TUNNEL_TOKEN=… docker compose --profile tunnel up -d`. Pair devices with the `https://` hostname.
- **Tailscale**: bind the port to the Tailscale address (`BIND_IP=100.x.y.z docker compose up -d`) and pair with
  `http://<tailscale name>:3071`, or put `tailscale serve --https=443 http://localhost:3071` in front for HTTPS.
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

## Without Docker

```sh
cd server && go build -o hoard-server .              # Go version from go.mod, no C compiler needed
./hoard-server --data-dir ./data --mounts /srv/photos   # offers the folders under /srv/photos
```

## Data and backups

- `/data/hoard-server.db` (or `reveal-sync.db` for a data directory made by reveal-sync; SQLite, WAL) holds devices,
  edits, the change feed and the NAS index. Back it up with the server stopped, or with
  `sqlite3 hoard-server.db ".backup copy.db"` while it runs.
- `<backup folder>/reveal-sync/blobs/` holds the uploaded originals and previews, named by their SHA-256; files are
  mode 0600. The backup folder is fixed once it holds files: to move it, stop the server, move the `reveal-sync`
  folder to the new place, mount it at the same path inside the container (or update the `backupRoot` setting).
- Nothing is ever written to a NAS folder.
- Logs never contain keys, tokens, passwords, file names or paths of photos.

## Releases

Push a tag `server-v<version>` (e.g. `server-v0.4.0`): `.github/workflows/server-release.yml` tests, attaches static
binaries to a GitHub release and publishes `ghcr.io/henriquefps/hoard-server:<version>` and `:latest` for amd64 and
arm64. The Hoard app keeps its own `v*` tags.

## Development

```sh
go vet ./... && go test -race ./...   # unit tests, an end-to-end test that builds and runs the binary, and the
                                      # reveal-sync data directory compatibility test
golangci-lint run ./...
```

Layout: `main.go` (flags, database file), `internal/server` (HTTP: `/v1` device API, `/admin` setup page in
`internal/server/web`), `internal/store` (SQLite, migrations in `internal/store/migrations`: never edit a released
one), `internal/blobstore`, `internal/nas` (indexer), `internal/mounts` (path confinement), `internal/fingerprint`
(the `v1:` rule, vectors shared with Reveal in `testdata/fingerprint-vectors.txt`), `internal/auth`,
`internal/ratelimit`. `testdata/compat/` holds a reveal-sync data directory (and the program that made it) that every
release must open as is; `testdata/v1-routes.txt` lists the frozen `/v1` routes.

Licence: [AGPL-3.0-or-later](../LICENSE).
