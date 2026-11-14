# ADR 0016: Culling synced through hoard-server

- **Date**: 2026-11-14
- **Status**: Accepted (hoard-server 0.7.0, Hoard 2 phase 1c)
- **Amends**: ADR 0013 (protocol additions under `/v1`, written there in "Culling (hoard-server 0.7.0)"), ADR 0014
  (the items feed can carry culling records). Read with Hoard ADR 0008/0009 (culling), ADR 0011 (culling sidecars) and
  Reveal ADR 0006 (library, XMP fields), 0011 (hand-off, `CULLING` extra), 0018 (sync in the app), 0025 (culling is
  per photo, not per virtual copy) and 0026 (XMP portability).

This ADR is meant to be implemented from on its own: Reveal's side (its sync engine) and Hoard's side (phase 2) follow
"Client rules" and "Wire format" below.

## Context

The owner culls in two apps: Hoard (stars, pick / reject, on the phone, over SMB) and Reveal (stars, pick / reject,
colour labels, while editing). Each keeps the decisions in its own database and in the shot's XMP sidecar next to the
photos (Hoard ADR 0011, Reveal ADR 0006). Reveal also syncs them between its devices inside its `photo` records
(`photo.data.rating`, `flag`, `label`, ADR 0013), last-writer-wins **per record**: a rating on one device and a pick
on another at the same time lose one of the two.

hoard-server now indexes every shot as an **item** (ADR 0014), sees the NAS sidecars, and is paired with both apps. It
can hold the one copy of the culling both apps agree on. Three writers have to compose without fighting: Hoard,
Reveal, and the sidecars on the NAS (written by both apps and by Lightroom, darktable, digiKam).

## Decision

### The culling record

One culling record per **item** (ADR 0014; the shot, so a RAW + JPEG pair has one, and Reveal's virtual copies share
their photo's, Reveal ADR 0025). It has three independent **fields**:

| Field | Values | Notes |
| --- | --- | --- |
| `rating` | integer `0..5` | `0` = no stars |
| `flag` | `"pick"` \| `"reject"` \| `"none"` | |
| `label` | string, at most 64 characters (no control characters) | colour label as `xmp:Label` writes it (`"Red"`, …); `""` = no label |

Each field that was ever written carries its own **`value`, `rev`, `modifiedAt` and `deviceId`**: the value, the
server revision of its last write, when the writer made that change (Unix ms, the writer's clock) and who wrote it
(a device id, or `"nas"` for a sidecar the server imported, see below). A field never written is absent. There is no
history: the record is auditable (who changed each field last, and when) but keeps no older values.

**A reject keeps its stars.** `rating` and `flag` are stored apart: rejecting a 4-star shot leaves `rating = 4`, and
un-rejecting it brings the stars back. `xmp:Rating="-1"` is only the file form of a reject (XMP has one property for
both); it is never a value of `rating`.

### Conflict rule, per field

Every write is about **one field**, and fields never affect each other: a rating made in Hoard and a pick made in
Reveal at the same moment both survive.

A write has an incoming value `v`, a `modifiedAt` `t` and a writer `d`; the field currently holds `cur` (absent if
never written). In order:

1. **Clock guard.** `t` more than **10 minutes** ahead of the server's clock is **clipped** to the server's clock
   (the result says `clipped: true`). `t ≤ 0` or above 2^53 is invalid (`422`).
2. **Same value** (`v` equals `cur.value`): nothing changes in the feed (no revision). If `(t, d)` is later than
   `(cur.modifiedAt, cur.deviceId)` the field's `modifiedAt` and `deviceId` are moved forward in place (the latest
   intent is remembered, so an older write cannot win against it later). Result `unchanged`.
3. **Field never written**: written. Result `applied`.
4. **`baseRev` equals the field's `rev`** (the writer saw the current value: ADR 0013's optimistic rule): written,
   whatever the clocks say. Result `applied`.
5. **Otherwise a conflict**, decided by **last-writer-wins on `modifiedAt`, then `deviceId`** (string order; the
   greater wins on a tie, as ADR 0013): `(t, d) > (cur.modifiedAt, cur.deviceId)` → written, result `conflict` with
   `winner: "incoming"`; else nothing changes, result `conflict` with `winner: "server"`. The loser is dropped: no
   conflict copy (a rating is cheap to redo, and ADR 0013 already drops losing photo fields).

Writers that never saw a revision (the NAS sidecar import and the bridge from Reveal's records, below) have no
`baseRev`: they always go through step 5.

"Written" means: the field takes `v`, `t` (after clipping), `d`, and a new revision from the items counter; the
record's `rev` is its newest field's.

Table of the cases (each is a test in `internal/store/culling_test.go`):

| # | Current field | Incoming | Result |
| --- | --- | --- | --- |
| 1 | absent | any value | applied |
| 2 | `3` rev 10, t=100, A | `3`, t=200, B | unchanged; field now t=200, B, still rev 10 |
| 3 | `3` rev 10, t=200, B | `3`, t=100, A | unchanged; nothing moves |
| 4 | `3` rev 10, t=200, A | `5`, baseRev 10, t=100, B | applied (B saw rev 10; its clock is behind) |
| 5 | `3` rev 10, t=100, A | `5`, baseRev 7, t=200, B | conflict, incoming wins |
| 6 | `3` rev 10, t=200, A | `5`, baseRev 7, t=100, B | conflict, server wins |
| 7 | `3` rev 10, t=100, A | `5`, baseRev 7, t=100, B | conflict, B > A: incoming wins |
| 8 | `3` rev 10, t=100, B | `5`, baseRev 7, t=100, A | conflict, server wins |
| 9 | any | t = now + 1 day | clipped to now, then the rules above |
| 10 | rating `3` and flag `none`, both rev 10, t=100, A | Hoard (B) picks at t=120 with an old `baseRev`; Reveal (A) rates `4` at t=110 with baseRev 10 | both survive: rating 4 (A), flag pick (B) |
| 11 | `pick` rev 10, t=100, A | `reject` from `"nas"`, t=300 | conflict, incoming (the sidecar) wins |
| 12 | `pick` rev 10, t=300, A | `reject` from `"nas"`, t=200 | conflict, server wins |
| 13 | the same push sent twice | | second time: every field `unchanged`, no revision |

### Wire format (additions to `/v1`)

`GET /v1/server` lists `"culling"` in `features` (a client checks it before using anything below).

**Push**: `POST /v1/culling` with up to **500** changes:

```json
{"changes": [
  {"itemId": "0b6f…", "field": "rating", "value": 4, "baseRev": 118, "modifiedAt": 1762851000000},
  {"fingerprint": "v1:9c2e…", "field": "flag", "value": "pick", "baseRev": 0, "modifiedAt": 1762851000500},
  {"itemId": "0b6f…", "field": "label", "value": "", "baseRev": 120, "modifiedAt": 1762851001000}
]}
```

- `field` is `rating`, `flag` or `label`; `value` is an integer 0..5, `"pick" | "reject" | "none"`, or a string of
  at most 64 characters. `baseRev` is the field's `rev` the device last saw (`0` if it never saw the field);
  `modifiedAt` is when the user made the change on the device (Unix ms, the device's clock), **kept as made** when the
  change is pushed later (offline).
- The shot is named by **`itemId`** or by **`fingerprint`** (exactly one):
  - `itemId`: an item the server knows (live or tombstone). A tombstone with `replacedBy` is followed (the RAW of a
    JPEG-only shot appeared, ADR 0014 "Item id"): the change lands on the replacement, and the result says which.
    An unknown `itemId` is `422 invalid_change` (`details.index`).
  - `fingerprint` (`v1:` + 64 hex): the live item that has this file as a member (the main file preferred); if no
    item has it yet (a photo that is only on a phone), the record is kept under the item id **derived** from it
    (ADR 0014: `uuid8(SHA-256("hoard-item\0" + fingerprint))`), which is the id the item will have once the server
    sees that file as a main file. Send the shot's **main** fingerprint (the RAW of a pair, else the rendering:
    `photo.data.fingerprint` in Reveal).
- Applied in order, in one transaction. A change that fails validation makes the whole request
  `422 invalid_change` with `details.index`; nothing is applied.

Answer `200`:

```json
{"results": [
  {"itemId": "0b6f…", "field": "rating", "status": "applied", "rev": 131},
  {"itemId": "77d0…", "field": "flag", "status": "conflict", "winner": "server", "rev": 125},
  {"itemId": "0b6f…", "field": "label", "status": "unchanged", "rev": 120, "clipped": true}
]}
```

`status` is `applied`, `unchanged` or `conflict` (with `winner`); `rev` is the field's revision after the change;
`clipped` only when the clock guard moved `modifiedAt`.

**Pull**: culling records travel in the **items feed**, as a sibling record kind:
`GET /v1/items/changes?cursor=&limit=&include=items,culling`.

- `include` is a comma-separated list of `items` and `culling`; absent = `items` (exactly the 0.5.0 answer).
- Items and culling records share the **items revision counter**, so one cursor covers both and the page holds, in
  revision order, each changed item and each changed culling record once, at its latest state. The answer gains a
  `culling` array next to `items` (present when asked for):

```json
{"items": [ … ],
 "culling": [{"itemId": "0b6f…", "rev": 131,
              "rating": {"value": 4, "rev": 131, "modifiedAt": 1762851000000, "deviceId": "5c1d…"},
              "flag": {"value": "reject", "rev": 96, "modifiedAt": 1762850000000, "deviceId": "nas"}}],
 "cursor": "858e1b9d:i131", "hasMore": false}
```

  A field never written is left out. `limit` counts items plus culling records.
- A client that only needs culling (Reveal) asks `include=culling` and never downloads items.
- `GET /v1/culling/{itemId}` returns one record (`404 not_found` when no field was ever written), for checks and
  tests.

**Why a sibling kind in the items feed, not a field of the item, and not a feed of its own.** Inside the item, every
star would give the whole item (1–4 KiB of files and metadata) a new revision, and a rating could not exist for a shot
the server has not indexed yet (a photo only a phone has). A filtered or separate counter would give Hoard two
cursors to keep in step for one library; sharing the counter keeps one cursor and one pull, and a filter by record
**kind** is safe where ADR 0014 rejected a filter by folder: a record never leaves its kind, so a client asking for
`culling` sees every culling change.

### The NAS sidecars: imported, never written by the server

The server indexes the XMP sidecars of NAS folders already (ADR 0014, role `sidecar`). In this phase it **reads**
them and **never writes** one: the apps keep writing sidecars as today (Hoard ADR 0011, Reveal ADR 0006/0026), on the
device that has the folder, when the user decides.

**Reading** follows Hoard ADR 0011 and Reveal ADR 0006 "XMP" byte for byte:

- Which file: `<stem>.xmp`, else `<file name>.xmp` of the main file, then of the other files. (Hidden sidecars are
  not walked by the NAS indexer; see "Consequences".)
- Values: `xmp:Rating` (attribute or element; truncated, clamped to 0..5; **negative = reject**, stars unknown),
  `digiKam:PickLabel` (`1` reject, `3` pick, other `none`), `xmp:Label`. A sidecar written by an old Hoard "Copy picks"
  (`xmp:CreatorTool` starting with `Hoard`, no pick label) is a pick. A sidecar with none of `xmp:Rating`,
  `digiKam:PickLabel`, `xmp:Label` or a Hoard creator holds no culling value and is ignored. Over 4 MiB, not XML, a
  DTD, not XMP: ignored (never an error of the walk).
- From a sidecar, `rating` comes from a non-negative `xmp:Rating`; a negative one sets `flag = reject` only (the stars
  stay as they are: "a reject keeps its stars"); `flag` comes from `digiKam:PickLabel` when present, else from a
  negative rating, else from the Hoard creator rule; `label` from `xmp:Label` when present.

**When a sidecar is imported** (`culling_sidecars`, one row per sidecar file: its size, mtime and the values read the
last time):

1. A walk reads a sidecar only when it is **new or its size or mtime changed** since the server last read it (file
   clock against the same file clock: no cross-device comparison decides whether to read).
2. Only the fields whose value **differs from what the server read in that sidecar last time** are candidate changes
   (the first read of a sidecar: every field it holds). So Lightroom changing a label does not re-assert a rating that
   another device changed on the server since.
3. Each candidate is a write by `"nas"` with **`modifiedAt` = the sidecar's mtime** (ms; clipped like any write), and
   goes through the conflict rule (step 5: no `baseRev`). A sidecar newer than the server's field wins; one older
   loses. The values read are stored either way.

Aligned with Reveal ADR 0026 where they overlap, explicitly:

| Reveal ADR 0026 (edits) / ADR 0006 (culling) | This ADR |
| --- | --- |
| "The newer edit wins and nothing is lost." | Per field, the newer write wins. Culling has no conflict copies (Reveal ADR 0025: culling is per photo; ADR 0013 drops losing photo fields): the loser is a value, cheap to redo. |
| Sidecar **newer** → import ("applied, not offered"). | Sidecar changed since last read and newer than the field → imported as a write by `"nas"`. |
| Sidecar **behind** → the photo keeps its edit, the sidecar is rewritten. | The server keeps its value and **does not rewrite** (it never writes NAS files). The sidecar is brought up to date by the next decision made on a device that has the folder (see "Client rules"). |
| **Diverged** → the newer becomes the master, the other a conflict copy. | Fields do not diverge as a whole: each field is newer on one side or the other, and that side wins. |
| Reveal's own writes are not read back (mtime stored after writing). | The server never writes, so it never reads itself back; an app's write is read once and is a no-op when the server already holds the value. |
| `reveal:Edited` dates the edit inside the file. | Culling has no in-file date; the file's mtime dates it, as Hoard ADR 0011 and Reveal ADR 0006 already do. |

**Why no write-back fight can loop.** A loop needs something that writes a sidecar *because* it read a value. Nothing
does:

- The server never writes sidecars.
- The apps write a sidecar only for a **decision the user made on that device** (Hoard ADR 0011 "Writing", Reveal ADR
  0006), never for a value pulled from the server ("Client rules" 4; Reveal ADR 0018 already says "synced ratings are
  not written to XMP sidecars").
- An import creates a revision only when the value **differs** and **wins**; an import of an app's own write finds
  the same value (step 2) and creates nothing, so the apps receive nothing to react to.

So one user decision causes at most one sidecar write, at most one import of it, and at most one revision per field;
the chain ends there. The order on writes, `(modifiedAt, deviceId)`, is total and identical on every server, so all
parties converge on the same value whatever order they hear of the writes. Even an app that *did* write pulled values
to its sidecars (a possible later option) would not loop: the import finds the server's own value and is a no-op.

### Reveal's records (bridge for released Reveal versions)

Released Reveal versions sync culling inside `photo` records only. So that their ratings reach Hoard without waiting
for a Reveal update, the server **bridges** them, without changing anything in `/v1/changes`:

- When a pushed `photo` record is written (applied, or a conflict it wins) and is not a tombstone, its `data.rating`
  (0..5), `data.flag` (`PICK` / `REJECT` / `NONE`) and `data.label` (null = `""`) are compared with the record's
  previous state; each field that changed (for a new record: each field that is not the default 0 / `NONE` / null)
  becomes a culling write by the record's `deviceId` with the record's `modifiedAt`, through step 5, on the item named
  by `data.fingerprint` (as a pushed `fingerprint`). Skipping defaults on a new record keeps a device that pairs late
  from clearing ratings with a record it just created.
- Once, at the first start of 0.7.0, every live `photo` record is bridged the same way (setting
  `culling.bridgedRecords`), so the owner's existing Reveal ratings appear in the culling feed.
- The bridge never writes `photo` records: the record feed stays Reveal's (ADR 0013 "Records"). A record's
  `modifiedAt` dates the whole record, so the bridge is less precise than a per-field push; that is why culling-aware
  clients push per field.

### Client rules (Hoard and Reveal, when the server lists `culling`)

1. **The server is the reference; the local database is a cache.** Each app keeps, per shot and per field, the
   server's `value`, `rev` and `modifiedAt` (Hoard: next to `cull_decisions`; Reveal: next to `photos.rating/flag/
   label`), and a queue of **pending** local changes per field.
2. **A local decision** (a tap, an undo, an accepted suggestion of ADR 0009, a burst reject: per shot, per field
   that changed) updates the local value at once, records `modifiedAt` = the device's clock at the decision, and
   queues `{field, value, baseRev = the cached server rev, modifiedAt}`. Offline, the queue waits; it is pushed later
   **with the original `modifiedAt`**. Several decisions on the same field before a push collapse to the last one
   (with its time).
3. **Pull** `GET /v1/items/changes?include=culling` (Hoard: `include=items,culling`, one cursor). For each field in a
   record: if no change of that field is pending locally, take the server's value, rev and time; if one is pending,
   keep the local value and push it (step 5 decides; the next pull brings the winner). Same shape as Reveal ADR 0018's
   `sync_inbox`.
4. **Sidecars.** A value taken from the server is **not written** to a sidecar. A local decision is written to the
   sidecar as today (Hoard ADR 0011, Reveal ADR 0006). An app that **imports** a sidecar (Hoard on folder open, Reveal
   on index) treats it as a local decision whose `modifiedAt` is the **sidecar's mtime**, not the time of the import,
   and pushes it: the conflict rule then compares the sidecar with the server like the server's own import does. A
   value pulled from the server counts, in Hoard's precedence rule (ADR 0011), as a decision made at its
   `modifiedAt`, so an old sidecar does not override a newer server value when a folder opens.
5. **Reveal**: with `culling` listed, Reveal pushes culling per field to `POST /v1/culling` (by `fingerprint` =
   `photo.data.fingerprint`, or the item id it learned from a pull), reads rating / flag / label **from the culling
   records** and ignores those three fields of pulled `photo` records; it keeps writing them into its `photo` records
   (other devices may run an older Reveal). Virtual copies share their photo's record (Reveal ADR 0025). Without
   `culling` (an older server), nothing changes.
6. **Hoard** (phase 2): decisions are per shot in a folder (ADR 0008); a shot maps to an item by its main file's
   fingerprint (push with `fingerprint`, or the item id from `POST /v1/items/lookup`). The `CULLING` hand-off extra
   (ADR 0011) is unchanged: it now carries the server's values (the local cache) plus pending ones.
7. **Unpaired**, each app works as before on its own database and sidecars.

### Items that change identity

- A JPEG-only shot whose RAW appears becomes a new item (ADR 0014); the old item's tombstone names it in
  `replacedBy`. The old item's culling fields are **carried over** to the new item in the same transaction, each as a
  write with its original `modifiedAt` and `deviceId` through step 5 (so a value the new item already had and that is
  newer stays). The same happens when the RAW goes away again and the shot falls back to its rendering: the item that
  now holds any of the dead item's files receives its fields.
- A file rewritten in place has a new fingerprint (a new item, ADR 0014): its culling starts empty, unless a sidecar
  or a client brings it.
- Culling for a shot the server does not index (a phone-only photo) lives under the id derived from its main
  fingerprint; when that file reaches the server (NAS or upload) as the main file of an item, the item has that id and
  the record already applies.

### Storage

Migration `0005_culling_devices.sql` (schema 5; forward only, with ADR 0017's tables):

- `culling (item_id PRIMARY KEY, rev)`, unique index on `rev`: the feed's view of a record.
- `culling_fields (item_id, field, value, rev, modified_at, device_id, updated_at, PRIMARY KEY (item_id, field))`:
  `value` is JSON text (`4`, `"pick"`, `"Red"`).
- `culling_sidecars (folder_id, rel_path, size, mtime, item_id, rating, flag, label)`: what each NAS sidecar held
  when last read (NULL = the field was absent from the file).
- No new counter: culling uses `items`.

## Consequences

- Reveal's existing endpoints, records, keys and payloads are unchanged; `TestRevealProtocolContract` and the
  compatibility tests run as before. The items feed without `include` answers exactly as in 0.5.0.
- Reveal's orchestrator implements "Client rules" 1–5 in Reveal's sync engine; Hoard phase 2 implements 1–4 and 6.
- **Clocks** still decide conflicts between devices, as in ADR 0013, now with a guard: a clock far in the future can
  no longer make its writes win for days (10 minutes of slack, then clipped to the server's time). A NAS whose clock
  is wrong dates its sidecars wrongly: a sidecar changed on a NAS whose clock lags loses to newer device writes, which
  is the safe direction.
- **Sidecars can lag the server**: a rating made on a phone that does not have the folder (or pulled from Reveal)
  reaches the NAS sidecar only with the next decision on a device that has the folder. The server never writes NAS
  files (ADR 0013 "NAS folders"); a server-side writer is a later decision of its own.
- **Hidden sidecars** (`.<stem>.xmp`, Hoard's "Write XMP as hidden file" option) are not walked by the NAS indexer
  (it skips hidden names, ADR 0014), so the server does not import them; the app that wrote one pushes the decision
  anyway, so nothing is lost, only the import of other tools' hidden sidecars.
- A Reveal device on a release without culling support keeps syncing culling through its records; the bridge carries
  it to the culling records with the record's time (coarser than per field).

## Alternatives considered

- **Last-writer-wins per record** (as `photo` records): a rating in one app and a pick in the other at the same time
  lose one. Rejected: the owner uses both apps on the same shoot.
- **The server writing sidecars on the NAS**: would make the NAS share writable to the server, and a third writer of
  the same files (with the apps and Lightroom) races their atomic renames. Not in this phase.
- **Importing a sidecar whenever its mtime is newer than the field** (without remembering what it held): an unchanged
  old sidecar would be compared again after every change made elsewhere, and a label change in Lightroom would
  re-assert the file's old rating. Remembering the values read makes each sidecar change count once, per field.
- **Server-assigned times** (ignore `modifiedAt`): offline edits pushed a day later would beat newer edits made
  online meanwhile. The device's time is kept, with the future clipped.
- **Culling inside the item JSON**: see "Why a sibling kind".
