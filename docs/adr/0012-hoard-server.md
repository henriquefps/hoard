# ADR 0012: hoard-server, the photo server, lives in Hoard

- **Date**: 2026-11-14
- **Status**: Accepted (server 0.4.0, Hoard 2 phase 1a)

## Context

"Hoard 2" makes Hoard a universal gallery backed by one photo/video server shared by Hoard, Reveal (the owner's RAW
editor) and a web gallery. That server already existed: Reveal's `reveal-sync` (Go + SQLite, `sync-server/` in the
Reveal repository, protocol in Reveal ADR 0017, app side in Reveal ADR 0018, image `ghcr.io/henriquefps/reveal-sync`),
deployed on the owner's home server with a Cloudflare Tunnel and paired with his Reveal installs.

The server's next features (an items model with videos, one pairing for both apps, culling records, gallery
previews) are Hoard's, and a server that serves several apps should not live inside one of its clients.

## Decision

1. **The server moves into this repository as `server/`, keeping its git history**, and is renamed **hoard-server**
   (image `ghcr.io/henriquefps/hoard-server`). Hoard owns it from now on; Reveal is one of its clients.
   Imported from github.com/henriquefps/reveal `main` at **`94fbfe7`** (the last change to `sync-server/` is
   `ef0b8db`, reveal-sync 0.5.3) with `git filter-repo --subdirectory-filter sync-server
   --to-subdirectory-filter server` and one merge commit (`--allow-unrelated-histories`).
2. **The protocol of record is ADR 0013** (Reveal ADR 0017 imported, with a header noting its origin);
   `server/api/openapi.yaml` is its machine-readable form. Reveal's copy points here.
3. **The move is a rename only**: no protocol change, no schema change, no data migration. The deployed reveal-sync
   is replaced by the new image pointed at the same data directory and backup folder, and Reveal keeps working
   unchanged.
4. **Releases**: tags `server-v<version>` build the static binaries and the multi-arch image
   (`:<version>` and `:latest`, `.github/workflows/server-release.yml`); the Hoard app keeps its own `v*` tags.
   Versions continue reveal-sync's numbering (its last tag was `sync-server-v0.3.0`), so the first hoard-server
   release is `server-v0.4.0` and a client comparing versions never sees the server go back. ("0.5.3" in this ADR
   is the Reveal app release whose `sync-server/` was imported, not a server version.)
   CI (`.github/workflows/server.yml`) runs gofmt, `go mod tidy`, `go vet`, golangci-lint and
   `go test -race ./...` (unit, end-to-end and the compatibility test below) on `server/**` changes.

### What was renamed

| Before (reveal-sync) | After (hoard-server) | Old name still accepted? |
| --- | --- | --- |
| Go module `github.com/henriquefps/reveal/sync-server` | `github.com/henriquefps/hoard/server` | n/a |
| Binary `reveal-sync`, `/usr/local/bin/reveal-sync` in the image | `hoard-server`, `/usr/local/bin/hoard-server` | n/a |
| Image `ghcr.io/henriquefps/reveal-sync` | `ghcr.io/henriquefps/hoard-server` | the old image stays published, frozen |
| Log message `reveal-sync listening`, `reveal-sync <version>` | `hoard-server listening`, `hoard-server <version>` | n/a |
| Compose service / container `reveal-sync` | `hoard-server` | n/a (a compose file of yours keeps its names) |
| Database `<data dir>/reveal-sync.db` | `<data dir>/hoard-server.db` **for new data directories** | yes: an existing `reveal-sync.db` is opened in place, never renamed |
| Setup page CSRF header `X-Reveal-Admin: 1` | `X-Hoard-Admin: 1` | yes |
| Admin session cookie `reveal_sync_admin` | `hoard_server_admin` | yes (read; logout clears both) |
| Default server name `Reveal sync` (new installs) | `Hoard server` | an existing server keeps its stored name |
| Setup page title and header, favicon | "Hoard server" with Hoard's icon (hfps identity) | n/a |
| OpenAPI title `reveal-sync device API` | `hoard-server device API` | n/a (title only) |
| Image labels `org.opencontainers.image.source` | `https://github.com/henriquefps/hoard`, AGPL-3.0-or-later | n/a |

**Environment variables**: none renamed. reveal-sync never used `REVEAL_SYNC_*` names: its settings are the
unprefixed `PORT`, `LISTEN`, `DATA_DIR`, `MOUNTS`, `BACKUP_ROOT`, `NAS_INTERVAL` and `LOG_FORMAT` (each with a
flag), and hoard-server reads the same ones, so a reveal-sync compose file or unit works as is. There is therefore no
deprecated alias to warn about. Default paths are unchanged too (`./data`, `/data` and `/mnt` in the image).

### What did not change (frozen for `/v1`)

- **The HTTP API**: every `/v1` path, method, payload, status and error code (`server/testdata/v1-routes.txt` is the
  frozen route list, checked by `TestFrozenV1Routes`), including the `WWW-Authenticate` realm `reveal-sync`.
- **Device keys**: `rvk1_<keyId>_<secret>`. Existing keys stay valid and **new keys keep the `rvk1_` prefix**: a new
  prefix would change the key format clients validate (`^rvk1_…$` in the OpenAPI document) for no gain. A new
  prefix, if ever, is a protocol change with its own ADR.
- **Pairing**: the `revealsync://pair?v=1&url=…&token=…&server=…` QR code and `POST /v1/pair`.
- **The SQLite schema** and its migrations (`internal/store/migrations` byte for byte), the admin password hash
  (argon2id) and sessions.
- **The blob layout on disk**: `<backup root>/reveal-sync/blobs/<sha[0:2]>/<sha[2:4]>/<sha>` and
  `<backup root>/reveal-sync/uploads/`. The folder keeps the `reveal-sync` name for every installation, old or new:
  it is part of the data format, not branding.

`TestOpensRevealSyncDataDir` opens a data directory and backup folder made by the original reveal-sync
(`server/testdata/compat/reveal-sync-0.5.3`, named after that Reveal release, generated by the recorded `mkfixture` program against a build of the
imported commit) and checks admin login with the old password, both device keys, `/v1/server`, the change feed
(equal to what reveal-sync served) and byte-identical originals and previews, and that nothing was renamed.

### Compatibility promise

- **`/v1` is frozen for Reveal.** Existing routes, fields, meanings, limits and error codes never change or
  disappear while any released Reveal uses them.
- **Additions are versioned.** New behaviour arrives as new optional fields (clients ignore unknown fields, ADR 0013),
  new routes under `/v1` that old clients never call, or, for anything incompatible, a new `/v2` served next to
  `/v1`. `GET /v1/server` reports `api` and `version` so a client can tell what is available. Each addition is
  written into ADR 0013 (or an ADR that amends it) in the same PR.
- **Pinned-protocol contract tests for clients.** Each client (Reveal, Hoard, the web gallery) keeps a copy of
  `server/api/openapi.yaml` pinned to a `server-v*` tag and a contract test that its requests and parsers match that
  copy; bumping the pin is a deliberate change in the client. On the server side, `TestOpenAPIListsEveryRoute`,
  `TestFrozenV1Routes` and the reveal-sync compatibility test keep the server honest about the same document.

## Consequences

- Upgrading the owner's deployment is an image swap: same `./data`, same backup folder, same environment; the log
  says it uses the `reveal-sync.db` name. Rolling back to the reveal-sync image works too, as nothing was renamed or
  migrated. Steps: `server/README.md`, "Upgrading from reveal-sync".
- Reveal's orchestrator switches Reveal's side (its README, its ADR 0017 pointing here, its CI no longer building
  `sync-server/`) separately; until then both copies exist and only this one changes.
- The fingerprint vectors (`server/testdata/fingerprint-vectors.txt`) are still shared with Reveal's
  `FileFingerprintVectorsTest`: a change to the `v1:` rule is a protocol change made here first.
- The Hoard app build ignores `server/` (ESLint and Prettier ignore it; Vitest and TypeScript only see `src/`).
