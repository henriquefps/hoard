# reveal-sync

`reveal-sync` is the self-hosted sync server for [Reveal](../README.md): it keeps your edits, ratings and virtual
copies in step between your phone and tablet, backs up the photos that live only on a device, and serves the photos
already on your NAS to your devices, even away from home. You run it yourself; nothing assumes anyone else's server.

- One static binary (Linux amd64/arm64) or a small Docker image (distroless, non-root).
- Everything in two places you choose: `/data` (a SQLite database) and a backup folder (content-addressed files).
- NAS folders are mounted read-only and indexed; photos the server can already reach are never uploaded.
- Every device has its own revocable key (pair by QR code). There is no anonymous mode.
- Conflicts never lose an edit: the newer one wins and the other becomes a virtual copy.

The protocol (for the app and any other client) is [ADR 0017](../docs/adr/0017-sync-protocol.md), with an OpenAPI
description in [`api/openapi.yaml`](api/openapi.yaml). Why it is built this way: [`docs/plan/sync.md`](../docs/plan/sync.md).

## Docker (recommended)

```sh
mkdir reveal-sync && cd reveal-sync
curl -LO https://raw.githubusercontent.com/henriquefps/reveal/main/sync-server/docker-compose.yml
mkdir -p data backup && sudo chown 65532:65532 data backup     # the container runs as uid 65532
NAS_DIR=/path/to/your/photos docker compose up -d
docker compose logs reveal-sync | grep setupCode                # first run: a one-time setup code
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

Building the image yourself: `docker build -t reveal-sync .` with BuildKit (the default with buildx); the legacy builder
needs `--build-arg BUILDPLATFORM=linux/amd64 --build-arg TARGETOS=linux --build-arg TARGETARCH=amd64`.

## Away from home

Devices sync through anything that forwards HTTPS to port 3071: a Cloudflare Tunnel (the `cloudflared` service in
`docker-compose.yml`, `docker compose --profile tunnel up -d`, with `CLOUDFLARE_TUNNEL_TOKEN` passed at run time from
your secret store), Tailscale, or a reverse proxy. The device API always requires a key, so it is safe to expose.
The setup page is **not** reachable through a tunnel or proxy unless you turn that on in Status → Settings (only
possible from your network); keep it off unless you need it, and put Cloudflare Access in front if you do.

## Without Docker

```sh
cd sync-server && go build -o reveal-sync .        # Go 1.27+, no C compiler needed
./reveal-sync --data-dir ./data --mounts /srv/reveal   # offers the folders under /srv/reveal
```

| Flag | Environment | Default | Meaning |
| --- | --- | --- | --- |
| `--port` | `PORT` | `3071` | port |
| `--listen` | `LISTEN` | all interfaces | address to bind (e.g. a Tailscale IP) |
| `--data-dir` | `DATA_DIR` | `./data` | database and settings |
| `--mounts` | `MOUNTS` | `/mnt` | comma-separated mount roots the setup page may offer |
| `--backup-root` | `BACKUP_ROOT` | none | backup folder to use until one is chosen on the setup page |
| `--nas-interval` | `NAS_INTERVAL` | `30m` | how often NAS folders are walked again |
| `--log-format` | `LOG_FORMAT` | `json` | `json` or `text` |

## Data and backups

- `/data/reveal-sync.db` (SQLite, WAL) holds devices, edits, the change feed and the NAS index. Back it up with the
  server stopped, or with `sqlite3 reveal-sync.db ".backup copy.db"` while it runs.
- `<backup folder>/reveal-sync/blobs/` holds the uploaded originals and previews, named by their SHA-256; files are
  mode 0600. The backup folder is fixed once it holds files: to move it, stop the server, move the `reveal-sync`
  folder to the new place, mount it at the same path inside the container (or update the `backupRoot` setting).
- Nothing is ever written to a NAS folder.
- Logs never contain keys, tokens, passwords, file names or paths of photos.

## Development

```sh
go test -race ./...          # unit tests + an end-to-end test that builds and runs the binary
go vet ./... && golangci-lint run ./...
```

Layout: `main.go` (flags), `internal/server` (HTTP: `/v1` device API, `/admin` setup page in `internal/server/web`),
`internal/store` (SQLite, migrations in `internal/store/migrations`), `internal/blobstore`, `internal/nas` (indexer),
`internal/mounts` (path confinement), `internal/fingerprint` (the `v1:` rule, vectors shared with the app in
`testdata/fingerprint-vectors.txt`), `internal/auth`, `internal/ratelimit`.

Licence: [AGPL-3.0-or-later](../LICENSE).
