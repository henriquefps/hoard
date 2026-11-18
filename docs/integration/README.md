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
| **Reveal** (`dev.hfps.reveal`) | Edits. Gives its results back through the server (records, edited previews). | Reveal |
| **Intent hand-off** | Opens a photo in Reveal. A photo the server holds goes as a **server reference**. A photo already on the phone goes as its MediaStore URI. Any other photo (SMB, USB, SD) is first **copied to the phone** (`Pictures/Hoard/…`) and goes as the copy's MediaStore URI. No streaming, no persistable or write grants, no sidecars. | Reveal (intent), Hoard (sender) |
| **XMP sidecars** | **Export on demand only** ("Export XMP" in each app, to where the user chooses), for other tools such as Lightroom. Neither app nor the server writes sidecars automatically. Existing sidecars are still read. **Not** a sync channel between the two apps. | Shared (see below) |
| **Deployed server** (cachyos-fps) | Upgrades and restarts, each one with the owner's OK. | Hoard orchestrator |

**Decided 2026-11-15 (owner):** no automatic XMP writes; hand-off by server reference, MediaStore URI or local copy.
Shipped in Hoard 2.2.x and Reveal 0.9.0 (installed 2026-11-15). Where contracts 8, 11 and 12 below describe URI streaming, persistable grants or automatic sidecars, that is the earlier behaviour, kept only for links handed out before.

## Contracts

| # | Contract | Owner | Source of record | Current version |
| --- | --- | --- | --- | --- |
| 1 | Server protocol `/v1` (frozen for Reveal; additions only). Clients check capabilities through `GET /v1/server` `features` (`culling`, `device-identity`, `edits`; 0.10.0 adds `items-by-path`). Clients tolerate missing optional fields (hoard-server 0.9.0 sends the files of uploaded originals without `name`). Reveal is a live client of Reveal-only parts too: records (photo, version, preset, setting), `/v1/blobs` smart previews (`.rvsp`), `GET`/`DELETE /v1/originals/{fingerprint}` by range, the NAS feed and lookups. | Hoard (server); client side Reveal ADR 0018 (Reveal ADR 0017 is history) | ADR 0013, `server/api/openapi.yaml`, `server/testdata/v1-routes.txt` (only grows); compatibility rules ADR 0012 | hoard-server 0.9.0 |
| 2 | Pairing: `revealsync://pair?v=1&…` QR, `POST /v1/pair`, `rvk1_` device keys | Hoard | ADR 0012, 0013 | unchanged since reveal-sync |
| 3 | Items, folders, previews | Hoard | ADR 0014, 0015 | 0.9.0 |
| 4 | Culling records: per-field last writer wins on `(modifiedAt, deviceId)` (on equal times, the greater `deviceId` compared byte-wise/ordinally, as Go orders strings; never a locale collator), `baseRev`, 10 min future clip, `features: ["culling"]` ends the photo-record bridge | Hoard | ADR 0016; Reveal ADR 0027 (client) | 0.9.0 / Reveal 0.8.0 |
| 5 | Device identity: one physical device groups the apps of a phone; app ids `hoard`, `hoard-debug`, `reveal`, `reveal-debug`, `reveal-bench`; `/v1/device`, `/v1/device/claim` | Hoard | ADR 0017; Reveal ADR 0027 | 0.9.0 / Reveal 0.8.0 |
| 6 | Edited previews: `PUT`/`DELETE /v1/items/{itemId}/edits/{versionId}` (`versionId` = Reveal's version uuid, not a server id), `?edit=primary`; digest `r<pipeline>.<render>:<sha256>` is opaque to Hoard (Reveal bumps `EditPreviews.RENDER_VERSION` when a render changes) | Hoard (server), Reveal (digest) | ADR 0020; Reveal ADR 0027 | 0.9.0 / Reveal 0.8.0 |
| 7 | v1 fingerprint: SHA-256 of size (8 B big-endian) + first 1 MiB + last 1 MiB | Hoard (generator and vectors) | `server/testdata/fingerprint-vectors.txt`; Reveal ADR 0006 | v1 |
| 8 | Intent hand-off, URI items: `dev.hfps.reveal.action.DEVELOP`, ClipData, `TITLE`, `INITIAL_URI`, sent to one package (release, else debug), READ + PERSISTABLE grants, sidecars READ + WRITE + PERSISTABLE. The `CULLING` bundle is keyed by `uri.toString()` exactly as placed in the ClipData. | Reveal (intent), Hoard (sender) | Reveal ADR 0011; Hoard ADR 0010 | Reveal 0.5.x+, Hoard 1.10.0+ (persistable grants) |
| 9 | `CULLING` extra (URI items only): per item `rating`, `flag`, `label`, optional `*ModifiedAt` (ms; accepted since Reveal 0.8.0 under version 1); **version 2** adds `*Device` (server deviceId, only when the sender is paired) and makes contract 4's per-field rule on `(modifiedAt, deviceId)` the way values are applied. Hoard sends version 2 only to Reveal ≥ 0.9.0. An older Reveal gets version 1, with `*ModifiedAt` and without `*Device` | Reveal | Reveal ADR 0011 "Culling" + amendment W3/H | 1 shipped, 2 agreed |
| 10 | **Server references** (amendment W3/H): `SERVER_REF_VERSION`, `SERVER_ID`, `SERVER_ITEMS` (`itemId` required, `fingerprint` / `fingerprints` / `name` optional, **no culling**), `INITIAL_ITEM`; at most 500 items per intent. A batch sent as several intents (`startActivities`) carries `BATCH_INDEX` (0 = first, 1..n−1 = trailing chunks, which Reveal ingests silently) and `BATCH_COUNT` (n) on every intent; a single intent carries neither. | Reveal (intent), Hoard (sender) | Reveal ADR 0011 amendment W3/H; Hoard ADR 0010 amendment | agreed 2026-11-15, ships in Reveal 0.9.0 |
| 11 | Hoard's hand-off URIs are stable and every one carries PERSISTABLE: FileProvider paths and SAF documents keep their URIs; SMB links `content://dev.hfps.hoard.stream/h/<id>/…` are served after a cold start, and only these are pruned, after about 180 days without use. `busy:` contract (below). | Hoard | Hoard ADR 0010 | Hoard 2.0.0; `busy:` in 2.0.1 |
| 12 | XMP culling: `xmp:Rating` 0..5 / −1 reject, `digiKam:PickLabel` 3/1/0, `xmp:Label`. Naming: one `<stem>.xmp` per shot next to the RAW (else next to the JPEG). Readers accept `<stem>.xmp`, `<file name>.xmp` and the hidden `.<stem>.xmp` / `.<file name>.xmp`, and `<stem>.xmp` wins. Hoard writes the hidden form only when its setting is on (off by default). A malformed sidecar is never overwritten. Each app writes only the fields the user changed. First read of a sidecar (Hoard 2.2.1, hoard-server 0.10.1, Reveal 0.9.0): a sidecar never read before only fills fields that hold no value, where a value is non-default (rating ≠ 0, flag ≠ none, label not empty). On later reads, only the fields changed since the last read are candidates, and they are applied when the sidecar's mtime is later than the reader's last decision (ADR 0016 last-writer-wins on the server). Each reader records what it read and what its Export XMP wrote. A sidecar that disappears is forgotten only after a complete, non-empty listing. The server imports NAS sidecars read only (`nas`) and never writes them. | Shared: Hoard for the culling mapping, Reveal for its `reveal:` / `crs:` blocks | Hoard ADR 0011, 0016; Reveal ADR 0006, 0026, 0027 | — |

### Sender rule (contracts 8–10)

Hoard sends a shot as a **server item** when its paired server holds the item and can serve the original. That
covers Gallery items and SMB files Hoard maps to server items. The mapping comes from the SMB share prefix ↔ the
server's NAS folder, learned by fingerprinting one file. A single photo can also be looked up by its v1 fingerprint on
demand. Hoard sends server items whenever the installed Reveal is ≥ 0.9.0 (`versionCode >= 90000`).

**Transition.** Hoard 2.1.0 (Gallery) meets the rule only for Gallery items. Until Hoard 2.2.0 ships the SMB path
mapping (which needs hoard-server 0.10.0), photos opened from Files or culling on an SMB share still go as URIs,
even when the server holds them.

`GET /v1/device` tells Hoard whether this phone's Reveal is paired with the same server. Hoard uses that only for how
it presents things. When the answer is no or unknown, Hoard **still** sends server items, and Reveal offers to pair
or claim. Hoard never falls back to URIs for a photo it **knows** the server holds (a Gallery item, a file in a
mapped folder, or a single photo it looked up). A photo Hoard cannot place cheaply goes as URIs: for example a
multi-selection in an unmapped folder, or more than 20 lookups. Reveal later adopts those by fingerprint (W3/C).

URIs are used only for photos the server does not hold, or when the installed Reveal is older than 0.9.0. Reveal
never reads a server item through Hoard: no provider and no grants.

Before sending, Hoard pushes its pending culling for those items (best effort). Reveal pulls the items' culling
records on receipt.

### `busy:` (contract 11, Hoard 2.0.1)

Opening a Hoard hand-off URI throws `java.io.FileNotFoundException` whose message starts exactly with `busy:` in one
case only: the provider is already serving its maximum number of concurrent hand-off reads (a constant, 4–8), and a
short bounded wait (< 2 s) did not free a slot. No other failure uses this prefix.

Clients retry with bounded exponential backoff (250 ms doubling up to 4 s, giving up after about 30 s). They keep
their own concurrent opens at or below the limit, and never mark a busy file as unreadable. Hoard 2.0.1's
constants: 6 concurrent reads (`HandoffServer.MAX_READS`), a 1.5 s wait (`BUSY_WAIT_MS`); ADR 0010 "Bursts of opens".

**Descriptors.** Files up to 8 MiB are served as plain descriptors from Hoard's cache, and their slot is freed at
once. Bigger files, which means every RAW, are appfuse proxy descriptors. In 2.0.1 each one **holds a slot until the client
closes it**. Since 2.0.2, a proxy descriptor idle for 12 s gives its slot back and takes one again on its next read. That read can fail with `EIO` after 1.5 s if all 6 slots are taken: read again. A descriptor idle for 10 min, or a parked one whose looper is needed, is closed on Hoard's side: its reads fail with `EIO` ("open the link again"). The `busy:` wording, limit and wait are unchanged.

So clients:

- close every hand-off descriptor as soon as the read is done;
- never keep long-lived descriptors in caches, decoders or the editor (copy to a local file or memory instead);
- never hold more than 6 at once, and cap their own concurrent opens at 4;
- treat a proxy read that fails with `EIO` (a slow share, a parked or retired descriptor) as transient: close and
  reopen the URI, never mark the file unreadable.

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
| 0.8.0 | 2.0.0 | 0.9.0 | **Installed** on the owner's phone and tablet (2026-11-15). Hoard 2.0.0 has **no server client yet**: culling sync and edited previews work between Reveal, the server and the web gallery only. Hoard ↔ Reveal is the URI hand-off plus sidecars. Open issue: a burst of hand-off opens when Reveal starts can hang storage. |
| 0.8.99-rc1 (W3/C, untagged, versionCode 89900) | 2.0.2 | 0.9.0 | **Installed** on the owner's phone and tablet (2026-11-15, night). Cold start with the real library: no appfuse mounts, no watchdog block. Server-held hand-offs adopted as Server photos. No server references yet (versionCode < 90000). |
| 0.9.0 (W3/C, W3/D, W3/H; versionCode 90000) | 2.0.2 → 2.2.0 | 0.9.0 → 0.10.1 | **All merged**: Reveal main 663ff97, signed untagged candidate built. Hoard 2.2.0 main b9dcbcf. Release pair test with the owner, after the server-v0.10.1 tag and deploy. |
| ≥ 0.9.0 | 2.1.0 (Gallery: server client, culling sync, server-reference sender for Gallery items, edited thumbnails) | ≥ 0.9.0 | Merged (main 7ff51df, not released). The server client reaches a plain-HTTP LAN/Tailscale server through a loopback relay (ADR 0021). SMB files still go as URIs. |
| 0.9.0 (+ W4: XMP export only, media-URI hand-offs) | 2.2.1 (SMB path mapping, local-copy hand-off, XMP export only, first-read rule; ADR 0022) | 0.10.1 (items by path, empty sidecar imports skipped, first-read rule, empty-walk guard) | All merged; server 0.10.1 **deployed** (Tailscale only, tunnel removed). **Hoard 2.2.2 + Reveal 0.9.0 (candidate 3, W5 edit sync + recovery)** installed and accepted on both devices (2026-11-15): edits, culling and new edits sync both ways; the 4 recovered versions are re-pushed with no conflict copies; devices grouped by phone. **Released**: Hoard v2.2.2, Reveal v0.9.0 (GitHub releases with signed APKs), installed on both devices. |
| 0.9.0 | 2.3.0 (trash, ADR 0023) | 0.11.0 → 0.11.1 (trashed items keep their edits until purge; `include=trash`, NAS feed `trashed:true`; 0.11.1: migrations need no temp directory) | **Released**: Hoard v2.3.0, server-v0.11.0 and server-v0.11.1 (2026-11-16); 0.11.0 then 0.11.1 deployed. Reveal 0.9.0 keeps edited trashed photos in its grid until it shows them as in trash. |
| 0.10.0 (`freed-adopt`) | 2.5.0 (phone backup, Gallery › Devices, Save to this device; ADR 0024; 2.4.0 never shipped) | 0.12.0 (backup roots, mirror, report/verify; one content store with Reveal's blobs) | **Released** (2026-11-18): Hoard v2.5.0, Reveal v0.10.0, server-v0.12.0 (deployed 2026-11-16). Device pair test passed; Reveal's phone suite passed. First real phone backup: 159 files, all linked, no errors. |
| 0.10.0 | 2.6.0 (free up space; ADR 0024 A3) | 0.12.1 (`POST /v1/backup/verify` `maxAge`) | **Released** (2026-11-18): Hoard v2.6.0 (2eac362), server-v0.12.1 (1b7a30a, deployed 2026-11-18). Release acceptance on the emulator with the release APKs passed (`docs/testing/freeup-acceptance/`); the owner tested free up on his phone. Free up needs Reveal grouped with Hoard and declaring `freed-adopt`; with 0.12.0, files the server last re-hashed over a day ago stay on the phone. |

## Testing a release pair

Before each pair of releases, run the end-to-end script twice: first on the emulator on cachyos-fps (both debug
apps and a test server on a temporary directory), then on the owner's phone with the owner. The script is planned. It
will reuse Reveal's instrumented tests as building blocks (`HandoffFlowTest`, `CullingFlowTest`, `EditedPreviewFlowTest`,
the coming `HandoffServerRefTest`) and cover:

- a hand-off with URIs, culling and sidecars;
- a hand-off with server references;
- culling round trips Hoard ↔ server ↔ Reveal ↔ web;
- edited previews;
- the `busy:` retry;
- persistence after a reboot.
