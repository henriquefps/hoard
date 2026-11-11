# ADR 0011: Culling ratings as XMP sidecars

- **Date**: 2026-11-11
- **Status**: Accepted (1.9.0)

## Context

Until 1.8.0 culling decisions (stars, pick, reject) lived only in Hoard's SQLite database (ADR 0008) and XMP was
written only at the destination of "Copy picks" (ADR 0009 §9, off by default). The owner rates a shoot in Hoard,
opens it in Reveal, his RAW editor (ADR 0010), and finds no ratings there; a rating made in Reveal, Lightroom,
darktable or digiKam never reached Hoard either. Reveal already reads and writes the same fields in sidecars
(Reveal ADR 0006 "XMP") and accepts decisions with a hand-off (Reveal ADR 0011 "Culling", the `CULLING` extra).

## Decision

1. **Writable folders** (phone storage, SD card / USB drive / camera, SMB shares that take writes): every culling
   decision is written into the shot's XMP sidecar next to the photos, and the sidecars are read when the folder
   opens for culling, so a rating made elsewhere shows in Hoard.
2. **Read-only folders** (read-only shares and volumes, folders the account cannot write): decisions stay in Hoard's
   database as before; the culling page says so in one muted line ("Ratings are saved only in Hoard for this
   folder").
3. **"Edit in Reveal"** sends the decisions as values (`CULLING` extra) for every folder, writable or not.
4. **"Copy picks to…"** writes the sidecar at the destination with the same module and format, merging into a
   sidecar already there.

### One native module: `dev.hfps.hoard.xmp`

Pure Kotlin, JVM-tested (`XmpTest`, `SidecarNamesTest`), used by the culling writer and reader
(`smb/cull/CullSidecars.kt`) and by the transfer engine (`TransferRunner.writeSidecar`). The reader, merger and
serializer are ported from Reveal's `core/data/.../xmp/Xmp.kt` and `XmlSerializer.kt` (same owner and licence,
AGPL-3.0-or-later; the files say so), so both apps read and write the same bytes. All file access goes through the
transfer engine's endpoints (`TransferFs`: `SmbShareFs` over the culling session's share, `PathTransferFs`,
`DocumentTransferFs`), the same code that copies photos (ADR 0007). `src/features/cull/xmp.ts` keeps only what
JavaScript needs: decisions ↔ wire fields, Copy picks' sidecar list, Reveal's values and the precedence rule.

### Format

Shared with Reveal (its `XmpFields`), Lightroom, darktable and digiKam:

| Field             | XMP                                                                                |
| ----------------- | ---------------------------------------------------------------------------------- |
| Stars 0..5        | `xmp:Rating`                                                                       |
| Reject            | `xmp:Rating="-1"` (a reject's stars stay in Hoard's database only)                 |
| Pick / reject / – | `digiKam:PickLabel` 3 / 1 / 0 (`http://www.digikam.org/ns/1.0/`, prefix `digiKam`) |
| Colour label      | `xmp:Label`: Hoard has none, so it never writes one and leaves an existing one     |
| New file          | `xmp:CreatorTool="Hoard <version>"` (only when Hoard creates the file)             |
| Every write       | `xmp:MetadataDate` (ISO 8601, UTC)                                                 |

Unmarking a shot writes `Rating=0` and `PickLabel=0`; the file is never deleted. Reading follows Reveal's rules: a
negative rating or `PickLabel=1` is a reject, `PickLabel=3` a pick, any other pick label none, and a sidecar
written by an old "Copy picks" (`CreatorTool` starting with `Hoard`, no pick label) is a **pick**. Ratings are
truncated and clamped to 0..5. A sidecar with none of `xmp:Rating`, `digiKam:PickLabel` or a Hoard creator holds
no culling value (develop settings only) and is never imported.

**Merge.** An existing sidecar is parsed into a DOM (namespace-aware, no DTDs, no external entities) and only
`xmp:Rating`, `digiKam:PickLabel` and `xmp:MetadataDate` change, in the description that already holds the rating
or label (else the first). A property found as a child element (`<xmp:Rating>3</xmp:Rating>`, digiKam's form) is
removed and written as an attribute. Everything else stays: Lightroom's `crs:` develop settings, darktable's
history, Reveal's `reveal:` recipe, keywords, comments, processing instructions (`<?xpacket?>`), element order; a
declared prefix for a namespace is reused (`xap:`). Serialization is Reveal's deterministic serializer (attributes
on one line, namespace declarations first), so a write repeated with the same values is byte-identical.

**Malformed sidecars are never overwritten**: a file that does not parse, or is well-formed but not XMP (no
`x:xmpmeta` / `rdf:RDF` root, no `rdf:Description`), or is over 4 MiB is left byte for byte as it was; the
decision stays in the database and the UI counts it (see UI).

**Atomic writes.** The new bytes go to `.<name>.hoardpart` in the same folder and are renamed over the sidecar
(replace), as the transfer engine publishes files. On SAF a replace is delete + rename (the provider has no atomic
replace).

### Naming

One sidecar per **shot** (ADR 0006 stems; a RAW + JPEG pair shares it, as Lightroom expects; a JPEG-only shot gets
one next to its JPEG):

- **Reading**, best first: `<stem>.xmp`, `<file name>.xmp` of each of the shot's files (darktable's
  `DSC1.ARW.xmp`; main file first), then the same names hidden (`.<stem>.xmp`, `.<file name>.xmp`). Names compare
  case-insensitively; macOS `._*` files are ignored. The normal `<stem>.xmp` wins when several exist.
- **Writing**: the sidecar the reader picked, else `<stem>.xmp`, or `.<stem>.xmp` with the hidden option. A second
  sidecar is never created for a shot that has one.
- **Copy picks**: the same rules at the destination, against the copy's name (a conflict rename carries over:
  `DSC1 (1).ARW` → `DSC1 (1).xmp`), one listing of each destination folder per job.

### Reading when a folder opens, and the precedence rule

`cullReadSidecars` gets, per shot, the mtime its sidecar must be **later than** to win (`after`), lists the folder
once (the listing made by `cullOpen`; again with `refresh` when the app comes back to the front) and reads only the
sidecars that may win. Opening a folder never writes.

Migration v7 adds `cull_sidecars` (per shot: `decided_at` ms, Hoard's last decision including an unmark;
`sidecar_mtime` ms, the sidecar's mtime when Hoard last wrote or imported it; `synced`, that sidecar held Hoard's
latest decision). The rule (`importAfter` in `xmp.ts`):

- Hoard never decided on the shot: any sidecar with culling values wins.
- `synced` (Hoard's last decision is in the sidecar): the sidecar wins only when its mtime is later than
  `sidecar_mtime`, i.e. something else changed it since. Both times come from the file system's clock, so a phone
  and an SMB server whose clocks differ do not matter.
- Otherwise (a decision not written: a read-only folder, a failed write, a decision made before 1.9.0, whose time is
  `cull_decisions.updated_at`): the sidecar wins when its mtime is later than that decision (phone clock against the
  file's) and than any `sidecar_mtime` Hoard saw.

When the sidecar wins, its values become the shot's decision in the database (`cull_decisions`) and in the page,
like any user decision: filters, counts, progress, undo of later changes and the suggestions (which never override
decisions, ADR 0009) all see them. An import is not an undo step. A reject keeps Hoard's stars (the file cannot
hold them). Shots decided since the folder opened, and shots whose own write is still on its way, are left alone.
When the values are the same, only `sidecar_mtime` / `synced` are recorded.

### Writing: debounce, serialization, flush

Each decision (and undo) calls `cullWriteSidecars` at once with the shot's new values. Native
(`CullSidecarWriter`) keeps the latest values per shot, waits 400 ms after the last decision (at most 2 s after
the first pending one) and writes the batch on one thread, so writes to one sidecar never overlap and a burst of
taps on one shot is one write. The call resolves once its items are written, with one result per item (`written`
with the new mtime, `malformed`, `failed`, `readOnly`). Nothing is lost when the user leaves: the plugin's
`handleOnPause` (the app goes to the background) and `cullClose` (the page closes) write whatever is pending at
once. The JS side records `sidecar_mtime` / `synced` only when the written values are still the shot's decision.

### Writability

Decided per folder, and never for a folder Hoard did not open for culling:

- the folder listing's `access` (ADR 0005): local paths by `canWrite` and the volume's mount state, SAF folders by
  the document's flags, SMB by the share's maximal access, MxAc and the write probe made with the listing
  (FILE_ADD_FILE asked on the folder's CREATE: nothing is written). `read` means read-only: nothing is attempted and
  the note shows.
- the first write refused with `ACCESS_DENIED` marks the folder read-only **for the session** (`readOnly` in the
  result): the remaining items are not attempted, later decisions are not sent, the note shows. Other failures
  (I/O, a dropped session) are failures of that write only.

### Settings (Settings › Culling, and the culling page's gear)

- **Save ratings next to photos (XMP)**: on by default. It absorbs 1.6–1.8's "Write ratings as XMP sidecars" of the
  Copy picks dialog (`cullXmpSidecars`, off by default), which migration v7 drops: that switch described
  destination copies only and its stored "off" was mostly the default, so the owner's approved default (on) applies
  to everyone. With it on, culling writes next to the photos and "Copy picks" writes at the destination (the dialog
  says so in a muted line instead of its old switch); off, neither writes and nothing is shown, but sidecars are
  still read.
- **Write XMP as hidden file (.name.xmp)**: off by default; for new sidecars only. Reveal and digiKam read hidden
  sidecars, Lightroom does not (the description says so).

### UI

- Read-only folder with the setting on: a muted line with a lock under the folder's progress ("Ratings are saved
  only in Hoard for this folder"), no modal.
- Sidecars that could not be written (malformed, I/O): one non-blocking toast per folder session with the count
  ("The ratings of 3 photos were not saved next to them: kept in Hoard only"); the decisions stay in the database.
- No new dialogs on the happy path.

### "Edit in Reveal": the `CULLING` extra

As Reveal ADR 0011 "Culling": `dev.hfps.reveal.extra.CULLING_VERSION` = 1 and `dev.hfps.reveal.extra.CULLING`, a
`Bundle` keyed by each ClipData item's `uri.toString()` → `Bundle { "rating": Int 0..5, "flag": "pick" | "reject" |
"none" }` (no `label`: Hoard has none). Every file sent whose shot has a decision gets an entry (the RAW and the
JPEG), in every folder, writable or not; a reject sends its stars. The contract's `openInReveal` takes `culling`
(path → values) from JS: the culling page sends its in-memory decisions, the browser the folder's saved ones;
`RevealHandoff.cullingValues` (pure, JVM-tested) keys them by the URIs it built. On writable folders the values match
the sidecar; Reveal's precedence rule (a sidecar sent with the hand-off and newer than the photo's previous hand-off
wins) handles the rest.

The hand-off stays read-only (ADR 0010): no `.xmp` item and no write grant are sent, so a change made in Reveal stays
in Reveal's database. A possible follow-up: send the existing sidecar of a writable folder as an item with
`FLAG_GRANT_WRITE_URI_PERMISSION` (Reveal ADR 0011 "What Reveal writes back"), which Hoard would read back on resume
like any other editor's change.

## Alternatives considered

- **JS writer (the 1.6 `xmpSidecar` string)**: cannot merge into an existing sidecar without a DOM, and "Copy picks"
  replaced a destination sidecar wholesale (dropping Lightroom's develop settings). Replaced by the native module;
  `TransferSidecar` now carries fields (`rating`, `flag`) instead of a document (a contract change: definitions,
  mock, Kotlin; jobs saved by 1.8 with XMP text are read back into fields).
- **Writing on open to bring sidecars up to date**: a folder opened in a hurry would rewrite hundreds of files;
  opening never writes.
- **Comparing every sidecar's mtime with the phone's decision time**: breaks when an SMB server's clock differs from
  the phone's; `synced` + `sidecar_mtime` compare file-system times with each other.
- **Debouncing in JS**: a WebView paused in the background may not run its timers; native owns the queue and flushes
  on pause.
- **A write probe file on SMB**: writes into a folder just to learn whether it can be written; the listing's
  FILE_ADD_FILE probe answers without writing, and the first refused write is the authoritative answer.

## Consequences

- Contract: `cullReadSidecars`, `cullWriteSidecars`, `openInReveal({ culling })`, `TransferSidecar { sourcePath,
rating, flag }` and `StartTransferOptions.hiddenSidecars`; the web mock (`mockXmp.ts`) and `web.test.ts`. No new
  error code.
- Native: `dev.hfps.hoard.xmp` (`Xmp.kt`, `XmlSerializer.kt`, `SidecarNames.kt`, `Sidecars.kt`),
  `smb/cull/CullSidecars.kt`, `SmbShareFs` / `SessionShareFs` in `TransferEndpoints.kt`, `TransferRunner`,
  `RevealHandoff`.
- Database: migration v7 (`cull_sidecars`, the old setting dropped); `deleteMachine` and "Delete rejects" clear the
  rows.
- ADR 0008 ("no sidecars"), ADR 0009 §9 ("destination only", off by default) and ADR 0010 ("no sidecar write-back",
  "decisions stay in Hoard") are amended to point here.
- Testing: `docs/testing/xmp-sidecars.md`.
