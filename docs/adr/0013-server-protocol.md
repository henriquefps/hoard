# ADR 0013: Server protocol (`hoard-server`, formerly Reveal's `reveal-sync`)

> **Origin.** This is Reveal ADR 0017 ("Sync protocol and the `reveal-sync` server",
> github.com/henriquefps/reveal, `docs/adr/0017-sync-protocol.md` at commit `94fbfe7`), imported with the server
> when it moved into this repository (ADR 0012). From now on **this is the protocol of record**: changes to `/v1`
> are made here, and Reveal's copy points here. The text is Reveal's, unchanged except for paths (`sync-server/` is
> now `server/`), the setup page's CSRF header (`X-Hoard-Admin`, the old name still accepted) and ADR references:
> "Reveal ADR nnnn" means an ADR in the Reveal repository (0003 recipes, 0006 identity and history, 0018 the app
> side), and `docs/plan/…` are Reveal's planning notes. Everything that defines a client's view (paths, payloads,
> `rvk1_` keys, the `revealsync://pair` pairing URI, error codes, limits, the blob layout under
> `<backup root>/reveal-sync/`) is unchanged and frozen for `/v1` (ADR 0012).

Status: accepted (Reveal MS/1 server; Reveal MS/2 app side, Reveal ADR 0018, added `GET /v1/devices`). Date: 2026-11-11.
Imported into Hoard: 2026-11-14 (ADR 0012). Amended by ADR 0014 (items, hoard-server 0.5.0: "Items" below), ADR 0015
(gallery previews, 0.6.0: "Previews" below), ADR 0016 (culling, 0.7.0: "Culling" below) and ADR 0017 (physical
devices, 0.7.0: "Physical devices" below).

## Context

Reveal's `docs/plan/sync.md` decides what syncs (edits and library metadata always, smart previews optionally, originals only
when the server cannot already reach them), how a photo is recognised (the content fingerprint of Reveal ADR 0006
"Identity") and how conflicts end (last-write-wins per version, the loser kept as a virtual copy).
Reveal's `docs/plan/distribution.md` adds that the server is the user's own ("bring your own server"): nothing in the protocol
or the app may assume the owner's host, and a third party must be able to write a client from this document alone.

This ADR is that document: the HTTP API (`/v1`), authentication and pairing, the data model, the change feed with
cursors and tombstones, the conflict rule, blob addressing and resumable uploads, NAS indexing, limits and errors.
The machine-readable form is `server/api/openapi.yaml` (OpenAPI 3.1); where the two differ, this ADR wins and
the YAML is a bug.

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Transport | HTTP/1.1 or 2, JSON (`application/json; charset=utf-8`) except blob bodies; every path under `/v1`. HTTPS is the reverse proxy's job (Cloudflare Tunnel, Tailscale serve, Caddy); the server itself speaks plain HTTP. |
| Auth (devices) | One API key per device, `Authorization: Bearer rvk1_<keyId>_<secret>`; 256-bit secret, stored as SHA-256, compared in constant time, revocable. No anonymous mode. |
| Pairing | The admin page shows a QR with a one-time pairing token (10 minutes); the app exchanges it at `POST /v1/pair` for its own key. |
| Admin | Web setup page; password set on first run with a one-time setup code from the server log; argon2id; reachable only from loopback/LAN/Tailscale ranges and never through a proxy unless the admin turns that on. |
| Records | `photo`, `version`, `preset`, `setting`: an id (UUID), a JSON `data` object owned by the app, a server revision. |
| Feed | One global, strictly increasing revision; `GET /v1/changes?cursor=` returns each changed record once, at its latest state, oldest first. Tombstones are kept forever. |
| Conflict | Optimistic: a push carries the `baseRev` it edited. Different from the current rev = conflict, resolved last-write-wins on the client's `modifiedAt` (then device id). For a `version` the loser is kept as a new version record with `copyOf`; for other kinds the loser is dropped. |
| Blobs | Content-addressed by the full SHA-256 (lower-case hex), immutable; kinds `original` and `preview`. Uploads are chunked and resumable (`POST /v1/uploads`, `PATCH` with `Upload-Offset`, `HEAD`). |
| Originals | Looked up by fingerprint (`POST /v1/fingerprints/lookup`): the server answers from the files it indexes on the NAS and from uploaded originals; the app uploads only what is missing. `GET /v1/originals/{fingerprint}` serves either, with ranges. |
| Errors | `{"error": {"code": "...", "message": "..."}}` with a stable `code`; `429` and `503` carry `Retry-After`. |

## Authentication

### Device keys

A key is `rvk1_` + `keyId` (16 lower-case hex characters, 64 random bits, public: it names the key in the database
and in logs) + `_` + `secret` (43 characters, unpadded base64url of 32 random bytes). The server stores `keyId` and
`SHA-256(secret)`; it looks the key up by `keyId` and compares the hashes with `crypto/subtle.ConstantTimeCompare`
(hashing first makes the time independent of the length of what was sent). A slow password hash is not needed: the
secret has 256 bits of entropy. A key is shown once, in the `POST /v1/pair` response; the app keeps it in the Android
Keystore. Every request except `GET /v1/health` and `POST /v1/pair` needs a valid, unrevoked key; a missing,
malformed, unknown or revoked key is `401 unauthorized` (one code for all four, so a caller learns nothing about
which). Revoking a device (admin page) takes effect on its next request. Each authenticated request updates the
device's `lastSeenAt` (at most once a minute); a pull or push also updates `lastSyncAt`.

### Pairing

1. The admin opens Devices → Pair a device. The server creates a pairing token (32 random bytes, base64url), stores
   only its SHA-256 with an expiry of **10 minutes**, and shows a QR code of
   `revealsync://pair?v=1&url=<server URL, percent-encoded>&token=<token>&server=<serverId>`. The server URL is the
   one the admin types (the setup page remembers it; it defaults to the address the page was opened with).
2. The app scans it and calls `POST /v1/pair` `{"token", "deviceName", "platform", "appVersion"}` at `url`.
3. The server checks the token's hash in constant time, that it is unused and unexpired, marks it used in the same
   transaction that creates the device, and answers `201 {"deviceId", "apiKey", "serverId", "serverName"}`.
   A used, expired or unknown token is `401 invalid_pairing_token`. Pair attempts are rate limited per address.

The `serverId` (a UUID made on first start) lets the app notice that a URL now points at another server.

### Admin

- **First run:** no admin password exists. The server prints a one-time **setup code** (8 digits in two groups,
  new on every start until a password is set) to its log. The setup page asks for that code and a new password
  (12-256 characters). This keeps someone else on the network from claiming a fresh server first.
- The password is hashed with **argon2id** (time 3, memory 64 MiB, parallelism 2, 16-byte salt, 32-byte key; the
  parameters are stored with the hash, PHC string format). Login is rate limited per address (5 a minute) and costs
  the same time for a wrong password.
- A login creates a session: a random 256-bit id in an `HttpOnly`, `SameSite=Strict` cookie (`Secure` when the
  request came over HTTPS), stored as SHA-256, 12 hours idle expiry. Every state-changing admin call must be JSON
  with the header `X-Hoard-Admin: 1` (the reveal-sync name `X-Reveal-Admin: 1` is still accepted) (a cross-site form cannot set it) and, when present, an `Origin` that matches
  the host.
- **Where the admin page answers:** only from loopback, private (RFC 1918, IPv6 ULA), link-local and CGNAT
  (`100.64.0.0/10`, Tailscale) addresses (a Docker host's port mapping arrives from the bridge gateway, a private
  address), and only when the request carries no proxy header (`Cf-Connecting-Ip`,
  `X-Forwarded-For`, `X-Real-Ip`, `Forwarded`). A tunnel connector sits on the LAN, so its requests are recognised by
  those headers. Elsewhere `/admin` answers `404`. The setting "Allow the setup page through a tunnel or proxy"
  (Status page, default off, changeable only from the LAN) lifts this. The device API (`/v1`) is reachable from
  anywhere: it always needs a key.

## Data model

### Records

A record is one row the app syncs:

| Field | Type | Meaning |
| --- | --- | --- |
| `kind` | `photo` \| `version` \| `preset` \| `setting` | |
| `id` | string | `photo`, `version`, `preset`: a version 4 UUID, lower-case with dashes (`photos.uuid` / `versions.uuid` of Reveal ADR 0006). `setting`: a key, `[a-z0-9][a-z0-9._-]{0,63}`. |
| `rev` | integer | the server revision of this state (see "Feed") |
| `deleted` | boolean | a tombstone: `data` is `null` |
| `modifiedAt` | integer | when the device made this change, Unix milliseconds, the device's clock (only used to order conflicting writes) |
| `deviceId` | string | the device that wrote this state |
| `copyOf` | string, optional | `version` only: the id of the version this one is the conflict copy of |
| `data` | object or null | the app's payload, at most **2 MiB** of JSON |

The server treats `data` as opaque except for the fields below, which it validates so a broken client cannot write a
record no other client can place:

- `photo`: `fingerprint` (required, `v1:` + 64 hex) — the photo's main file (the RAW of a pair). The app also keeps
  there the other files of the shot (`files: [{fingerprint, role, name, size, sha256?}]`), the library metadata
  (`rating`, `flag`, `label`, `captureTime`, `width`, `height`, `format`, `hidden`) and the smart preview reference
  (`preview: {sha256, width, height}`); the server reads `preview.sha256` and `files[].sha256` only to count blobs.
- `version`: `photoId` (required, a UUID), `position` (integer), `name` (string or null), `recipe` (the Reveal ADR 0003
  JSON, `v` inside), `history` (the steps of Reveal ADR 0006 "History": `[{recipe, label}]`) and `cursor` (integer).
- `preset`, `setting`: no required field yet (M3 defines presets).

Versions refer to photos by id, not the other way round, so two devices adding a virtual copy to the same photo never
conflict. A version whose photo is unknown is accepted (the photo may arrive in the same or a later push).

### Revisions, cursors and tombstones

The server keeps one counter. Every accepted write (an edit, a delete, a conflict copy) gives its record the next
value as `rev`; a record's `rev` is the revision of its latest state. `GET /v1/changes?cursor=<c>&limit=<n>` returns
the records whose `rev` is greater than the cursor, in increasing `rev` order, and a new cursor. A record written
twice since the cursor appears once, in its latest state. The cursor is an **opaque string** (today the first 8
characters of the `serverId`, a colon and the revision, e.g. `858e1b9d:42`; clients store it as given and never
compute with it); the empty cursor means "from the start".
`hasMore: true` means call again with the returned cursor. A cursor from another server, one ahead of the server's
newest revision (a restored backup) or a malformed one is `400 invalid_cursor`: the client then pulls from the
start, which is safe because applying a record twice is idempotent.

A delete is a write: the record stays as a tombstone (`deleted: true`, `data: null`) with a new `rev`, **forever**,
so a device that was offline for a year still learns about it. A later write to the same id with the tombstone's rev
as `baseRev` revives it.

### Pushing and the conflict rule

`POST /v1/changes` takes up to **500** changes:

```json
{"changes": [{"kind": "version", "id": "…", "baseRev": 41, "modifiedAt": 1762851000000, "deleted": false,
              "data": {"photoId": "…", "recipe": {"v": 1}, "history": [], "cursor": -1}}]}
```

`baseRev` is the `rev` of the state the device edited (`0` for a record it created). Each change is applied in its own
step, in order, and gets one result:

- The record does not exist yet, or `baseRev` equals its current `rev`: **applied**.
- Otherwise it is a **conflict** (another device wrote in between). The winner is the change with the later
  `modifiedAt`; equal times are broken by the greater `deviceId` (string order), so every server decides the same way.
  - `version`: the losing state, if it is not a delete, is kept as a **new version record** (a new UUID, the loser's
    `data` unchanged, `copyOf` = the version's id, `deviceId` = the loser's device). The app shows it as a virtual copy
    of the photo ("Conflict copy" with the device name), never silently dropped; the user may delete it like any
    version. A losing delete is dropped (nothing is lost). A winning delete over an edit keeps the edit as a copy.
  - `photo`, `preset`, `setting`: last-write-wins, the loser is dropped. (Photo fields are ratings and flags, cheap
    to redo; presets get copies in M3 if needed.)
- An identical repeat (same kind, id, `modifiedAt`, device and data as the current state) is **applied** without a new
  revision, so a push retried after a lost response is harmless.

Result: `{"kind", "id", "status": "applied" | "conflict", "rev": <current rev of the record>, "winner": "incoming" |
"server" (conflict only), "copyId": <the copy's id> (when one was made)}`. A change that fails validation makes the
whole request `422 invalid_change` with the index in `details.index`, and nothing is applied, so a client can fix
and retry the batch as a whole. After a push the client pulls the feed (it sees its own writes, the copies and the
winner), which keeps the client logic one path.

History steps are append-only per device, so merging non-overlapping groups (one device crops, the other grades) is
possible later without changing the wire format; v1 does not do it.

## Blobs

### Addressing

A blob is immutable bytes named by the **full SHA-256** of its content, lower-case hex (64 characters). Kinds:
`original` (a photo file, byte for byte) and `preview` (a smart preview, the app's format: about 2560 px, see
`sync.md`). The server stores a blob at `<backup root>/reveal-sync/blobs/<sha[0:2]>/<sha[2:4]>/<sha>`, mode 0600, and
records its size, kind and, for originals, its fingerprint.

`GET /v1/blobs/{sha256}` returns the bytes (`application/octet-stream`, `ETag: "<sha256>"`, `Accept-Ranges: bytes`,
single `Range` requests, `HEAD` for size). `404 not_found` when the server does not hold it.

### Chunked, resumable uploads

1. `POST /v1/uploads` `{"sha256", "size", "kind", "fingerprint" (originals: required; previews: must be absent)}`.
   - The blob exists already: `200 {"status": "exists"}` (an original also registers its fingerprint).
   - Otherwise `201 {"status": "created", "uploadId", "offset": 0, "chunkSize", "expiresAt"}`. If this device already
     has an unfinished upload of the same blob, that one is returned (`200 {"status": "resume", …, "offset"}`), so a
     client that lost its upload id resumes too.
2. `PATCH /v1/uploads/{uploadId}` with `Upload-Offset: <n>` and `Content-Type: application/offset+octet-stream`,
   body = the next bytes (at most `chunkSize`, the last chunk shorter). `n` must equal the server's offset, else
   `409 offset_mismatch` with `details.offset`. On success `204` with the new `Upload-Offset`. If the connection
   breaks in the middle of a chunk, the bytes that arrived are kept (synced to disk); the client asks
   `HEAD /v1/uploads/{uploadId}` (`Upload-Offset`, `Upload-Length`) and continues from there.
3. The chunk that reaches `size` completes the upload: the server hashes the file, and for an original also checks its
   fingerprint against the declared one (the same `v1` rule as the app). A mismatch is `422 checksum_mismatch` and the
   upload restarts at offset 0. A match is moved into place atomically (`fsync` file, `rename`, `fsync` directory) and
   the response carries `Upload-Complete: 1`.
4. `DELETE /v1/uploads/{uploadId}` abandons it. Unfinished uploads expire **7 days** after their last chunk.

Uploads are per device: another device's upload id is `404`. Two devices uploading the same blob each upload; the
second to finish finds it in place and is told `exists`.

### Dropping a smart preview

Added in 0.5.2 (FIX/P; schema migration `0002_preview_refs`). A device may drop a smart preview **it uploaded** that no
live photo record names any more (its photo was un-edited, or its original reached the server, Reveal ADR 0018 "Smart
previews"): `DELETE /v1/blobs/{sha256}` →

- `204`: the row and the file are gone (a later upload of the same bytes is a new blob).
- `404 not_found`: no such blob (dropped before).
- `403 forbidden`: `details.reason` is `original` (originals are never dropped by a device) or `other_device` (another
  device uploaded it).
- `409 conflict`, `details.reason = referenced`: a photo record that is not deleted still names it in
  `data.preview.sha256`. The client pushes the record without it first, then asks again.

The reference check and the delete are one transaction (an index on `json_extract(data, '$.preview.sha256')` of live
photo records), so a record naming the preview keeps it; a preview upload completing at the same moment is serialised
with the drop. A record pushed later that names a dropped preview (a device that wrote the photo without having pulled the
change) gets `404` on download; the device that owns the preview pushes the record again with its current preview
when it sees one without it. Servers before 0.5.2 answer `405`: the app keeps its drops queued until the server is updated.

### A deleted photo releases its preview

Added in 0.5.3 (FIX/V). When a push turns a photo record into a tombstone (applied, or a conflict the tombstone wins)
and the record's previous `data.preview.sha256` is a `preview` blob that no live photo record names any more, the
server deletes that blob (row in the push's transaction, file right after) whichever device uploaded it: nothing can
reach it after that. A tombstone that loses a conflict releases nothing; a retried identical push releases nothing
again.

### Deleting an original

Added in 0.5.3 (FIX/V; the user removed a folder with "Also delete from the server", or chose "Delete from server").
`DELETE /v1/originals/{fingerprint}`, any paired device, after the photo's tombstone was pushed:

- `204`: the `originals` row is gone; the blob row and file go too when no other fingerprint uses the bytes.
- `404 not_found`: no uploaded original with this fingerprint (never uploaded, or deleted before).
- `403 forbidden`, `details.reason = nas`: the fingerprint is an indexed NAS file; the server never deletes those.
- `409 conflict`, `details.reason = referenced`: a photo record that is not deleted names the fingerprint (its
  `fingerprint` or any `files[].fingerprint`; the check finds the quoted fingerprint in the record's JSON, exact for
  `v1:` + 64 hex). Another device still describes the photo: its backup stays.

The check and the delete are one transaction, serialised with blob uploads completing. Servers before 0.5.3 answer
`405`: the app keeps its deletes queued.

### Originals and the fingerprint lookup

`POST /v1/fingerprints/lookup` `{"fingerprints": ["v1:…", …]}` (at most 1000) →
`{"found": [{"fingerprint", "size", "source": "nas" | "backup", "sha256" (backup only)}], "missing": ["v1:…"]}`.
A fingerprint is found when an indexed NAS file has it or an uploaded original was registered with it. The app
uploads an original only when its fingerprint is missing (and the user's rules allow it: Wi-Fi, per-folder switch).
`GET /v1/originals/{fingerprint}` serves the file from either place with the blob's headers (ranges, `HEAD`).

## NAS folders

The server indexes folders the admin picks among the read-only mounts it can see (Docker: the paths mounted under
`/mnt`; see "Mounts"). It walks them in the background (on enabling, on request and every 30 minutes), skips names
starting with `.` and symbolic links, keeps RAW, JPEG and HEIF files by extension (the list of `ImageFormats` in
`:core:image`) — and since 0.5.0 videos and XMP sidecars for items (ADR 0014), which the NAS feed below does not
list —, compares size and mtime with the last walk, and computes the `v1:` fingerprint of new or changed files
(at most 2 MiB read per file, 2 files at a time). The `v1:` rule is defined once (Reveal ADR 0006 "Identity"); the Go and
Kotlin implementations check the same vectors (`server/testdata/fingerprint-vectors.txt`, see "Tests").

Devices can list the indexed files so a device without the folder still sees the shoots:
`GET /v1/nas/files?cursor=&limit=` → `{"files": [{"fingerprint" (null when the file could not be read),
"folderId", "folderName", "path" (relative to the folder, `/`-separated), "size", "modifiedAt" (ms), "removed"}],
"cursor", "hasMore"}`, with its own revision counter and the same cursor rules (its cursors are not interchangeable
with the change feed's); a file that disappears, or whose folder stops being indexed, stays as `removed: true`.
A NAS file that changed since it was indexed is not served (`503 unavailable`, `Retry-After`) and is indexed again. NAS files are served by fingerprint
(`/v1/originals`), never by path. The server never writes to a NAS folder.

### Mounts

The server only ever touches paths inside its **mount roots** (`--mounts`, default `/mnt`): the setup page lists the
directories directly under each root (Docker: one per volume) with free/used space and whether they are writable.
The backup root must be a writable one; NAS folders are any of them. Every path that comes from a request, the
database or the setup page is resolved (symbolic links included) and checked to stay inside a mount root; files are
opened through `os.Root`, which refuses `..` and links that leave the folder. The backup root is fixed once a blob is
stored (moving blobs is a manual step, see the server's README).

## Items (hoard-server 0.5.0)

Added by ADR 0014 (the model, pairing rule, motion photos, capture-time rules, indexer behaviour and why items have a
feed of their own). Additions only: no existing route, field or meaning changed; Reveal does not call these routes.

An **item** is one shot: `kind` `photo` | `pair` (RAW + rendering) | `video` | `motion` (still + video, next to it or
inside it). Its `id` is a UUID derived from its main file's fingerprint (ADR 0014 "Item id"). Served as:

```json
{"id": "…", "rev": 12, "deleted": false, "createdAt": 1762851000000, "updatedAt": 1762851000000,
 "kind": "pair", "mainFingerprint": "v1:…", "sortTime": 1714568400500,
 "capture": {"local": "2024-05-01T10:00:00.500", "offset": "-03:00", "utc": 1714568400500, "source": "exif"},
 "make": "SONY", "model": "ILCE-7M4", "lens": "FE 35mm F1.8", "gps": {"lat": -23.5, "lon": -46.6, "alt": 760},
 "width": 7008, "height": 4672, "orientation": 1,
 "files": [{"fingerprint": "v1:…", "role": "raw", "name": "DSC00001.ARW", "format": "tiff", "size": 25639680,
            "locations": [{"source": "nas", "folderId": "…", "folderName": "photos", "path": "2024/DSC00001.ARW",
                           "modifiedAt": 1714568400000},
                          {"source": "backup", "sha256": "…"}]},
           {"fingerprint": "v1:…", "role": "rendered", "name": "DSC00001.JPG", "format": "jpeg", "size": 8123456,
            "locations": [{"source": "nas", "…": "…"}]}]}
```

`video` (`durationMs`, `container`, `codec`, `profile`, `width`, `height`, `rotation`, `fps`, `bitDepth`, `hdr`,
`colorSpace`, `audio`) is present on videos and motion photos; `motion` is `{"source": "file", "fingerprint": <the
video's>}` or `{"source": "embedded", "fingerprint": <the still's>, "format": "google" | "microvideo" | "samsung",
"offset", "length", "presentationUs"?}` (the MP4 is bytes `[offset, offset + length)` of the still, which
`GET /v1/originals/{fingerprint}` serves with ranges). Optional fields are left out when unknown. A **tombstone** is
`{"id", "rev", "deleted": true, "createdAt", "updatedAt", "replacedBy"?}`; `replacedBy` names the item that took its
main file over (its RAW appeared). Clients ignore unknown fields; new fields may be added.

| Method and path | Answer |
| --- | --- |
| `GET /v1/items/changes?cursor=&limit=` | The items feed: `{"items": [...], "cursor", "hasMore"}`, every item whose revision is greater than the cursor, once, at its latest state, oldest revision first; tombstones forever. Its own revision counter and cursors (`<server>:i<rev>`, opaque; not interchangeable with `/v1/changes` or `/v1/nas/files`); the cursor rules of "Revisions, cursors and tombstones" apply (`400 invalid_cursor` → pull from the start). `limit` default 200, max 500. |
| `GET /v1/items?kind=&from=&to=&folder=&path=&order=&cursor=&limit=` | A query, not a feed: live items ordered by `sortTime` then `id`, newest first (`order=desc`, default) or oldest first (`asc`). `kind`: comma-separated kinds; `from` / `to`: `sortTime` range `[from, to)` in Unix ms; `folder`: a NAS folder id; `path` (with `folder`): a directory in it, sub-directories included. `{"items", "cursor" (null on the last page), "hasMore"}`; the cursor is valid for the same `order` (else `400 invalid_cursor`) and is used with the same filters. Bad parameters: `400 invalid_request`. |
| `GET /v1/items/{itemId}` | One item, tombstones included; `404 not_found` when unknown. |
| `POST /v1/items/lookup` `{"fingerprints": [...]}` (at most 1000) | `{"items": [...], "matches": [{"fingerprint", "itemId", "role"}], "missing": [...]}`: the live items with any of the fingerprints as a member file (each once), which fingerprint matched which item in which role, and the fingerprints no live item has. `422 invalid_request` for a malformed fingerprint or too many. |

Also added: `GET /v1/server` has `features: ["items"]` (absent before 0.5.0: a client checks it before calling the
routes above); `GET /v1/health` `counts` has `items`. `POST /v1/fingerprints/lookup` and `GET /v1/originals` find
indexed NAS **videos** (and XMP sidecars) as well as photos; `GET /v1/nas/files` keeps listing photos only.

## Previews (hoard-server 0.6.0)

Added by ADR 0015 (what is made from which source, keys and versions, the queue, the generators). Additions only;
Reveal does not call these routes, and its smart previews (`/v1/blobs`) are unchanged. Present when `GET /v1/server`
has `features` containing `previews`; its `previews` object gives `version` (PREVIEW_VERSION), `thumbEdge` (512),
`screenEdge` (2560), `proxyShortEdge` (720), `maxWarm` (500), `requestsPerSecond` and `requestBurst` (the preview
GETs' own per-device bucket: 200 per second, burst 600, separate from the 30/s of the rest of the API).

| Method and path | Answer |
| --- | --- |
| `GET /v1/items/{itemId}/preview?size=thumb\|screen&format=jpeg\|webp&v=` | The item's preview: an upright sRGB JPEG (default) or WebP, long edge ≤ 512 (`thumb`, default) or ≤ 2560 (`screen`) px, never upscaled; a video's is its poster frame. |
| `GET /v1/items/{itemId}/poster?size=thumb\|screen&format=jpeg\|webp&v=` | A video's poster frame (the same file as `/preview` of a video); `404 preview_unavailable` (`not_applicable`) for other kinds. |
| `GET /v1/items/{itemId}/proxy?v=` | The proxy of a video or of a motion photo's video: MP4 (`faststart`), H.264, short edge ≤ 720 px, ≤ 30 fps, ≤ 2.5 Mb/s, AAC; HDR tone-mapped to SDR. `Range` and `HEAD` work. |
| `POST /v1/items/previews` `{"items": [...] (≤ 500), "previews": ["thumb", "screen", "proxy"] (default ["thumb"])}` | `{"items": [{"id", "thumb": {"status", "v", "stale"?, "width", "height", "bytes", "webpBytes"} \| {"status": "pending"} \| {"status": "unavailable", "reason"}, …}], "missing": [ids]}`: each item once, in the order asked; unknown or deleted ids in `missing`. Previews not ready are queued (first item first). |

The three GETs answer:

- **200** with the file. `ETag: "<key>-<file>"`, `X-Hoard-Preview-Version: <key>` (the content key: source +
  recipe + PREVIEW_VERSION), `X-Hoard-Preview: ready | stale` (`stale`: an older preview, served while the new one is
  made). With `v` equal to the key: `Cache-Control: private, max-age=31536000, immutable`; otherwise
  `private, no-cache` (revalidate with `If-None-Match` → 304). A client that builds URLs from the warm answer's `v`
  never asks twice for the same preview, and a changed preview has a new `v`.
- **202** `{"status": "pending", "retryAfter"}` with `Retry-After` (2 s for images, 15 s for proxies) and
  `X-Hoard-Preview: pending`: not made yet; it was queued ahead of everything else. Ask again later.
- **404** `not_found` (no such live item) or `preview_unavailable` with `details.reason`: `not_applicable`,
  `unsupported` (no decoder reads the file), `failed` (generation failed three times; a new recipe or source tries
  again), `no_tools` (the server's vips/ffmpeg are missing).
- **400** `invalid_request` (bad `size`), **401**, **429** (the preview bucket) as usual.

## Culling (hoard-server 0.7.0)

Added by ADR 0016 (the record, the per-field conflict rule, NAS sidecars, the bridge from `photo` records and the
client rules), amended by Reveal's review (findings A–E, still 0.7.0). Additions only; `features` gains `"culling"`.

A **culling record** belongs to an item id (ADR 0014) and has up to three fields, `rating` (0..5), `flag` (`pick` |
`reject` | `none`) and `label` (≤ 64 characters, `""` = none), each `{"value", "rev", "modifiedAt", "deviceId"}`
(`deviceId` `"nas"` = imported from a NAS sidecar). A reject keeps its stars (`rating` and `flag` are independent).
Revisions come from the **items counter**.

| Method and path | Answer |
| --- | --- |
| `POST /v1/culling` `{"changes": [{"itemId" \| "fingerprint", "field", "value", "baseRev", "modifiedAt"}]}` (≤ 500) | `{"results": [{"index", "itemId", "field", "status": "applied" \| "unchanged" \| "conflict" \| "invalid", "winner"?, "rev", "clipped"?, "code"?, "message"?}]}`, one per change, in order. Per field: same value → `unchanged` (no revision; a later `modifiedAt` is remembered); `baseRev` equal to the field's `rev` (or a field never written) → `applied`; else last-writer-wins on `modifiedAt`, then device id. A `modifiedAt` more than 10 minutes ahead of the server's clock is clipped to it. A `fingerprint` names the live item that holds the file, else the id derived from it (a shot the server does not index yet); an `itemId` must be known (a tombstone's `replacedBy` is followed). A change that cannot be applied is a result `"status": "invalid"` with a `code` (`invalid_target`, `invalid_field`, `invalid_value`, `label_too_long`, `invalid_base_rev`, `invalid_modified_at`, `unknown_item`) and no `rev`; the other changes apply and the answer is `200`. Only a body that is not a push (malformed JSON `400`, 0 or more than 500 changes `422 invalid_change`) is refused as a whole. Calling it marks the device culling-aware (below). |
| `GET /v1/items/changes?…&include=items,culling` | `include` (comma-separated `items`, `culling`; absent = `items`, the 0.5.0 answer). With `culling`, the answer has a `culling` array of records changed since the cursor, merged with items in revision order (one cursor; `limit` counts both). Each record also carries `fingerprints` (the item's member files, main first; `[]` when the server has no live item with that id) and, for a replaced item, `replacedBy`; a record is sent again when its item's members change or the item is replaced. |
| `GET /v1/culling/{itemId}` | One record; `404 not_found` when no field was ever written. |

Written by the server too: changed NAS sidecars (as `"nas"`, dated by the file's mtime; the server never writes a
sidecar; a label present in a sidecar the last time and absent now imports as `""`) and the culling fields a pushed
`photo` record changes (its device and `modifiedAt`; once at the upgrade for every live photo record), **unless the
device is culling-aware**: it declared `"culling"` in `PATCH /v1/device` `features` or called `POST /v1/culling`. A
record write that turns to `REJECT` and drops the rating to 0 together keeps the stars (released Reveal reads
`xmp:Rating="-1"` as 0 + reject); a label over 64 characters is skipped alone. `/v1/changes` is unchanged.

## Physical devices (hoard-server 0.7.0)

Added by ADR 0017 (why and the app flow). Additions only; `features` gains `"device-identity"`.

A device (one app installation, one key) belongs to one **physical device** (`physicalDeviceId`, a UUID). A new
pairing starts its own, with the device's id; devices paired before 0.7.0 got the same at the upgrade.

| Method and path | Answer |
| --- | --- |
| `POST /v1/pair` | Request also takes optional `app` (the app's name with an optional build variant: `reveal`, `reveal-debug`, `reveal-bench`, `hoard`, `hoard-debug`; `[a-z0-9]+(-[a-z0-9]+)*`, ≤ 32; variants are distinct apps of one phone), `manufacturer`, `model` (≤ 64); answer also has `physicalDeviceId` and `candidates`: `[{"physicalDeviceId", "name", "match": "model" \| "other", "devices": [{"id", "name", "app", "platform", "manufacturer", "model"}]}]`, the other physical devices on the same platform without a device of this app, model matches first. |
| `GET /v1/device` | `{"deviceId", "name", "platform", "app", "manufacturer", "model", "features", "physicalDevice": {"id", "name", "devices": [...]}, "candidates": [...]}` (`features`: the capabilities the device declared or was seen using, so far `"culling"`) |
| `PATCH /v1/device` `{"app"?, "manufacturer"?, "model"?, "features"?}` | The same view after storing them; `features` (≤ 16 names `[a-z0-9-]{1,32}`) are added, never removed, unknown names ignored; `400 invalid_request` when malformed. |
| `POST /v1/device/claim` `{"physicalDeviceId": "<id>" \| null}` | Moves this device there (`null`: to one of its own); the same view. `404 not_found`, `409 conflict` (`details.reason = same_app`: a live device of the same app id, variant included, is there), `400 invalid_request`. |

`GET /v1/devices` entries gain `physicalDeviceId` and `app`.

## Other endpoints

| Method and path | Auth | Answer |
| --- | --- | --- |
| `GET /v1/health` | none | `{"status": "ok", "version", "api": 1, "counts": {"devices", "photos", "versions", "blobs", "nasFiles", "pendingUploads", "items" (0.5.0)}}`: counts only, no names or paths |
| `GET /v1/server` | key | `{"serverId", "serverName", "version", "api": 1, "deviceId", "limits": {…}, "storageConfigured": bool, "features": ["items" (0.5.0), "previews" (0.6.0), "culling" (0.7.0), "device-identity" (0.7.0)], "previews": {…} (0.6.0, when enabled)}` |
| `GET /v1/devices` | key | `{"devices": [{"id", "name", "platform", "physicalDeviceId" (0.7.0), "app" (0.7.0)}]}`: the paired devices that are not revoked (no keys), so a client can name the device of a conflict copy (`deviceId`) |
| `POST /v1/pair` | pairing token | see "Pairing" |

## Limits

| Limit | Value | Over it |
| --- | --- | --- |
| JSON request body | 8 MiB | `413 too_large` |
| One record's `data` | 2 MiB | `422 invalid_change` |
| Changes per push / per pull page | 500 / 500 (default 200) | `422` / clamped |
| Fingerprints per lookup | 1000 | `422 invalid_request` |
| Chunk | 8 MiB (`chunkSize`; a smaller chunk is fine) | `413 too_large` |
| Blob size | 4 GiB | `422 invalid_request` |
| Unfinished uploads per device | 32 | `429 rate_limited` |
| Requests per device | 30 per second, burst 120 | `429` + `Retry-After` |
| Preview GETs per device (`/preview`, `/poster`, `/proxy`; 0.6.0) | 200 per second, burst 600, a bucket of their own | `429` + `Retry-After` |
| Items per preview warm (0.6.0) | 500 | `422 invalid_request` |
| Unauthenticated requests per address (pair, failed device auth) | 10 per minute, burst 10 | `429` + `Retry-After` |
| Admin sign-in and setup per address | 5 per minute, burst 5 | `429` + `Retry-After` |

`GET /v1/server` returns the limits that apply, so clients read them instead of hard-coding them.

## Errors

Every error is JSON:

```json
{"error": {"code": "offset_mismatch", "message": "Upload-Offset is 1048576, the server has 524288.",
           "details": {"offset": 524288}}}
```

`code` is stable and meant for programs; `message` is English for logs and developers, never shown to users as is.

| Status | Codes |
| --- | --- |
| 400 | `invalid_request`, `invalid_cursor`, `invalid_json` |
| 401 | `unauthorized`, `invalid_pairing_token` |
| 403 | `forbidden` (admin: missing `X-Hoard-Admin` or foreign `Origin`; `DELETE /v1/blobs`: an original or another device's preview; `DELETE /v1/originals`: a NAS file) |
| 404 | `not_found`, `preview_unavailable` (0.6.0, `details.reason`) |
| 405 | `method_not_allowed` |
| 409 | `offset_mismatch`, `upload_busy` (another request is writing the same upload), `conflict` (admin: e.g. changing a backup root that holds blobs; `DELETE /v1/blobs`, `DELETE /v1/originals`: still referenced) |
| 413 | `too_large` |
| 415 | `unsupported_media_type` |
| 422 | `invalid_change`, `checksum_mismatch` |
| 429 | `rate_limited` |
| 500 | `internal` |
| 503 | `storage_not_configured`, `unavailable` |

## Server implementation

Go, `server/` (`hoard-server`, formerly `reveal-sync`), a static binary (`CGO_ENABLED=0`) and a non-root image
(distroless until 0.5.0; Alpine with libvips and ffmpeg for the previews since 0.6.0, ADR 0015):

- **SQLite** through the pure-Go `modernc.org/sqlite`, WAL, `busy_timeout`, foreign keys, write transactions
  `BEGIN IMMEDIATE`; the schema is versioned by `PRAGMA user_version` with numbered migrations embedded in the binary.
  Database in the data directory (`/data`), mode 0600 (the process umask is 077).
- **Blob store** under the backup root, with atomic writes and fsync of file and directory.
- **Logs**: structured (`log/slog`, JSON); each request logs the route pattern (never the raw path), status, bytes,
  duration and the device id. Keys, tokens, passwords, file names and paths of photos never appear; the configured
  roots do.
- **Setup page**: plain HTML/CSS/JS embedded in the binary, the hfps look (tokens of the `hfps-app-ui` skill), English
  and Brazilian Portuguese, light and dark.

## Tests

Go unit tests (keys and constant-time compare, pairing, cursors and tombstones, every conflict case, resumable upload
with an interrupted chunk, checksum and fingerprint mismatch, path traversal on every path input, revoked key, limits,
admin access rules), the fingerprint vectors, and an end-to-end test that builds and runs the binary and drives a
scripted client through pair → push edits → a second device pulls → conflict → a chunked blob upload interrupted and
resumed → download with a range. CI: `gofmt`, `go vet`, `golangci-lint` (staticcheck included), `go test -race`.

The fingerprint vectors are a text file of `size seed fingerprint` lines over a deterministic byte generator
(`byte i = ((i * 2654435761 + seed * 97) >> 13) & 0xff`, 64-bit arithmetic); `FileFingerprintVectorsTest` in
Reveal's `:core:image` reads its copy of the same file, so the server and the app agree byte for byte, including the edges (0 bytes,
exactly 2 MiB, 2 MiB + 1).

## Consequences

- The app side (next task) needs: a key in the Keystore, a cursor per server, a WorkManager job that pushes pending
  records with their `baseRev`, pulls the feed, maps `copyOf` to a virtual copy, looks up fingerprints before
  uploading, and uploads in chunks resuming from `HEAD`.
- Device clocks decide conflicts. A device with a clock far in the future always wins; the loser is still kept as a
  copy, so nothing is lost. A server-side clock check is a possible later addition (`modifiedAt` far ahead of the
  server's time could be clamped).
- Originals are never garbage collected (they are a backup); since 0.5.3 the user can delete them explicitly
  ("Deleting an original"). Since 0.5.2 a device drops the smart previews it no
  longer needs ("Dropping a smart preview"); a server-side sweep of blobs no record references can still come later.
- Encryption at rest of originals (`sync.md` "Security", optional) is not in v1; the backup root can live on an
  encrypted volume.
- Smart previews are made by devices (the server has no RAW decoder), so a NAS photo nobody opened yet has no
  preview; a device that has the NAS file reachable through `/v1/originals` can make one.
