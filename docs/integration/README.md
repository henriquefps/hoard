# Hoard ↔ Reveal integration

The one place that lists every contract between **Hoard** (this repository: the Android app and hoard-server) and
**Reveal** (github.com/henriquefps/reveal, the owner's Android RAW editor). It gives each contract's owner, version
and source of record. The ADRs hold the details and this page points to them. Reveal's docs link here.

Maintained by the Hoard ↔ Reveal coordinator. A change to a contract below is made by its owner (an ADR or ADR
amendment in the owner's repository), announced to the other side through the coordinator before merging, and
recorded here in the same cycle.

## Roles

| Piece | Role | Owner |
| --- | --- | --- |
| **hoard-server** (`server/`) | Source of truth for the catalogue (items, originals, previews), culling (per field) and edited previews. Both apps are clients. | Hoard |
| **Hoard** (`dev.hfps.hoard`) | Finds, browses, moves and culls photos everywhere: phone, SMB, USB/SD, the server's Gallery. | Hoard |
| **Reveal** (`dev.hfps.reveal`) | Edits. Gives its results back through the server (records, edited previews) and XMP sidecars. | Reveal |
| **Intent hand-off** | Opens a photo in Reveal. Content URIs **only for photos the server does not hold**. A photo the server holds goes as a server reference. | Reveal (intent), Hoard (sender) |
| **XMP sidecars** | Portable copy of culling (and Reveal's edit) for other tools (Lightroom). **Not** a sync channel between the two apps. | Shared (see below) |
| **Deployed server** (cachyos-fps) | Upgrades and restarts, each one with the owner's OK. | Hoard orchestrator |

## Contracts

| # | Contract | Owner | Source of record | Current version |
| --- | --- | --- | --- | --- |
| 1 | Server protocol `/v1` (frozen for Reveal; additions only) | Hoard | ADR 0013, `server/api/openapi.yaml`, `server/testdata/v1-routes.txt`; compatibility rules ADR 0012 | hoard-server 0.9.0 |
| 2 | Pairing: `revealsync://pair?v=1&…` QR, `POST /v1/pair`, `rvk1_` device keys | Hoard | ADR 0012, 0013 | unchanged since reveal-sync |
| 3 | Items, folders, previews | Hoard | ADR 0014, 0015 | 0.9.0 |
| 4 | Culling records: per-field last writer wins on `(modifiedAt, deviceId)`, `baseRev`, 10 min future clip, `features: ["culling"]` ends the photo-record bridge | Hoard | ADR 0016; Reveal ADR 0027 (client) | 0.9.0 / Reveal 0.8.0 |
| 5 | Device identity: one physical device groups the apps of a phone; app ids `hoard`, `hoard-debug`, `reveal`, `reveal-debug`, `reveal-bench`; `/v1/device`, `/v1/device/claim` | Hoard | ADR 0017; Reveal ADR 0027 | 0.9.0 / Reveal 0.8.0 |
| 6 | Edited previews: `PUT`/`DELETE /v1/items/{itemId}/edits/{versionId}`, `?edit=primary`; digest `r<pipeline>.<render>:<sha256>` is opaque to Hoard | Hoard (server), Reveal (digest) | ADR 0020; Reveal ADR 0027 | 0.9.0 / Reveal 0.8.0 |
| 7 | v1 fingerprint: SHA-256 of size (8 B big-endian) + first 1 MiB + last 1 MiB | Hoard (generator and vectors) | `server/testdata/fingerprint-vectors.txt`; Reveal ADR 0006 | v1 |
| 8 | Intent hand-off, URI items: `dev.hfps.reveal.action.DEVELOP`, ClipData, `TITLE`, `INITIAL_URI`, sent to one package (release, else debug), READ + PERSISTABLE grants, sidecars READ + WRITE + PERSISTABLE | Reveal (intent), Hoard (sender) | Reveal ADR 0011; Hoard ADR 0010 | `CULLING_VERSION` 1 |
| 9 | `CULLING` extra (URI items only): per item `rating`, `flag`, `label`; **version 2** adds `*ModifiedAt` (ms) and `*Device` (server deviceId, only when the sender is paired), applied with contract 4's rule | Reveal | Reveal ADR 0011 "Culling" + amendment W3/H | 1 shipped, 2 agreed |
| 10 | **Server references** (amendment W3/H): `SERVER_REF_VERSION`, `SERVER_ID`, `SERVER_ITEMS` (`itemId` required, `fingerprint` / `fingerprints` / `name` optional, **no culling**), `INITIAL_ITEM`; at most 500 items per intent | Reveal (intent), Hoard (sender) | Reveal ADR 0011 amendment W3/H; Hoard ADR 0010 amendment | agreed 2026-11-15, ships in Reveal 0.9.0 |
| 11 | Hoard's hand-off provider: SMB links `content://dev.hfps.hoard.stream/h/<id>/…` stay stable and are served after a cold start; the store is pruned after about 180 days. `busy:` contract (below). | Hoard | Hoard ADR 0010 | Hoard 2.0.0; `busy:` in 2.0.1 |
| 12 | XMP culling: `xmp:Rating` 0..5 / −1 reject, `digiKam:PickLabel` 3/1/0, `xmp:Label`. A malformed sidecar is never overwritten. Each app writes only the fields the user changed. The server imports NAS sidecars read only (`nas`) and never writes them. | Shared: Hoard for the culling mapping, Reveal for its `reveal:` / `crs:` blocks | Hoard ADR 0011, 0016; Reveal ADR 0006, 0026, 0027 | — |

### Sender rule (contracts 8–10)

Hoard sends a shot as a **server item** when its paired server holds the item and can serve the original. That
covers Gallery items and SMB files Hoard maps to server items. The mapping comes from the SMB share prefix ↔ the
server's NAS folder, learned by fingerprinting one file. A single photo can also be looked up by its v1 fingerprint on
demand. Hoard sends server items whenever the installed Reveal is ≥ 0.9.0 (`versionCode >= 90000`).

`GET /v1/device` tells Hoard whether this phone's Reveal is paired with the same server. Hoard uses that only for how
it presents things. When the answer is no or unknown, Hoard **still** sends server items, and Reveal offers to pair
or claim. Hoard never falls back to URIs for a photo the server holds.

URIs are used only for photos the server does not hold, or when the installed Reveal is older than 0.9.0. Reveal
never reads a server item through Hoard: no provider and no grants.

Before sending, Hoard pushes its pending culling for those items (best effort). Reveal pulls the items' culling
records on receipt.

### `busy:` (contract 11, Hoard 2.0.1)

Opening a Hoard hand-off URI throws `java.io.FileNotFoundException` whose message starts exactly with `busy:` in one
case only: the provider is already serving its maximum number of concurrent hand-off reads (a constant, 4–8), and a
short bounded wait (< 2 s) did not free a slot. No other failure uses this prefix.

Clients retry with bounded exponential backoff (250 ms doubling up to 4 s, giving up after about 30 s). They keep
their own concurrent opens at or below the limit, and never mark a busy file as unreadable. The final constant is in
Hoard 2.0.1's PR.

## Shared fixtures

The Hoard repository owns them. Reveal keeps pinned copies and a test that checks them, the same way it pins the
server:

- v1 fingerprint vectors: `server/testdata/fingerprint-vectors.txt` (Reveal:
  `core/image/src/test/resources/fingerprint-vectors.txt`).
- Server protocol: `server/api/openapi.yaml` at a `server-v*` tag (Reveal: `HOARD_SERVER_VERSION` in
  `scripts/sync/local-server.sh`).
- XMP culling sidecars and `CULLING` extra vectors: to be gathered under `docs/integration/fixtures/` (planned).

## Compatibility

| Reveal | Hoard | hoard-server | Status |
| --- | --- | --- | --- |
| 0.8.0 | 2.0.0 | 0.9.0 | **Installed** on the owner's phone and tablet (2026-11-15). Culling sync, edited previews and the web gallery work. Open issue: a burst of hand-off opens when Reveal starts can hang storage. |
| 0.9.0 (W3/C, W3/D, W3/H) | 2.0.1 (`busy:`) + Gallery | 0.10.0 (`devices[].revoked`, `lastSeenAt`; `POST /v1/items/by-path`) | **Planned pair.** Reveal 0.9.0 and Hoard 2.0.1 ship together. Reveal does not need 0.10.0. |

## Testing a release pair

Before each pair of releases, run the end-to-end script twice: first on the emulator on cachyos-fps (both debug
apps and a test server on a temporary directory), then on the owner's phone with the owner. The script is planned;
it will cover:

- a hand-off with URIs, culling and sidecars;
- a hand-off with server references;
- culling round trips Hoard ↔ server ↔ Reveal ↔ web;
- edited previews;
- the `busy:` retry;
- persistence after a reboot.
