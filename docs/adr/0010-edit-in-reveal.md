# ADR 0010: Edit in Reveal

- **Date**: 2026-11-11
- **Status**: Accepted (1.8.0)

## Context

Reveal (`dev.hfps.reveal`, debug `dev.hfps.reveal.debug`) is the owner's Android RAW editor. Its
hand-off contract is [Reveal ADR 0011](https://github.com/henriquefps/reveal/blob/main/docs/adr/0011-hand-off-contract.md):
an explicit `dev.hfps.reveal.action.DEVELOP` intent with the photos as `ClipData`, the read grant on
those URIs, an optional batch title and an optional photo to open first. Reveal never copies
originals; it reads the content URIs it is given and pairs RAW + JPEG by stem inside one intent.

Hoard is where the owner culls a shoot (ADR 0008, 0009) and browses every location (ADR 0005), so
the natural next step from a pick is "edit it". Constraints that already hold in Hoard: bytes never
cross the bridge; share-out (ADR 0007) already turns any location's file into a content URI another
app can read; Hoard never writes next to the source (ADR 0009); only the exact URIs handed out are
granted (`SharedUris`, PR #16).

## Decision

### Contract

```ts
revealAvailable(): Promise<{ available: boolean }>
openInReveal({ location: StreamLocation, paths, title?, initialPath? }): Promise<{ sent }>
```

One location per call (the culling folder, the browser folder). `NO_APP` when Reveal is not
installed (or was uninstalled between the check and the tap); `NOT_FOUND`, `UNKNOWN` (no photo among
`paths`), and for SMB the usual connection errors. No new error code. The web mock reports Reveal as
installed (`MOCK_REVEAL_INSTALLED`), records every hand-off as native would send it
(`revealCalls`), and `setRevealInstalled(false)` simulates an uninstall.

### Native: `smb/share/RevealHandoff.kt`

- **Order** (`RevealHandoff.order`, JVM-tested, mirrored by `revealOrder` in
  `src/features/reveal/handoff.ts`): photos only (by extension, as `ImageFormats`), duplicates
  dropped, the files of one shot (same folder, same lower-case stem — ADR 0006) together at the
  place of the first one sent, the RAW first, then JPEG, HEIF, PNG/WebP. So a pair arrives adjacent
  and in one intent, which is what Reveal's pairing needs.
- **Intent** (`RevealHandoff.plan`, JVM-tested): `ACTION = dev.hfps.reveal.action.DEVELOP`, type
  `image/*`, `ClipData` with one item per URI in that order, `EXTRA_TITLE` (trimmed, at most 120
  characters, omitted when blank), `EXTRA_INITIAL_URI` only when the initial path is among those
  sent. Flags: `FLAG_GRANT_READ_URI_PERMISSION` always; `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`
  only when the photos are SAF documents (the only kind whose provider lets Reveal persist the
  grant); **never** `FLAG_GRANT_WRITE_URI_PERMISSION`. The ClipData carries the grants, so Reveal
  gets exactly those URIs: no folder, no tree, no prefix grant. `resolveActivity` is checked before
  `startActivity`.
- **Detection**: a `<queries>` entry for the action with `image/*` (package visibility) and
  `queryIntentActivities(Intent(ACTION).setType("image/*"), 0)`. The app asks at start and on every
  `resume` / `appStateChange` (`RevealProvider`), so installing or uninstalling Reveal shows or
  hides the action the next time Hoard comes to the front.

### URIs per location kind, and how long they work

| Location                                 | URI                                                                                              | Lifetime in Reveal                                                                                                                                                                                                                                                                                              |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Device / volume with all files access    | FileProvider (`<app>.fileprovider`, `file_paths.xml`), in place                                  | Android's grant: until Reveal's process ends or the phone restarts. Hoard being killed does not matter (the system restarts Hoard's process to serve the provider). Not persistable (FileProvider does not support it): after that the photo stays in Reveal's library without an image until it is sent again. |
| Volume through SAF (no all files access) | The SAF document (tree-based, under Hoard's persisted grant), in place                           | Same, and Reveal may take a **persistable** grant, which survives restarts.                                                                                                                                                                                                                                     |
| SMB share                                | `content://<app>.stream/<token>/<name>`, the seekable proxy file descriptor provider of ADR 0004 | While Hoard's process lives **and** for 60 min after the last read of that file (`StreamEntry.idleMs`, `HANDOFF_IDLE_MS`; ordinary streams use 15 min). Swiping Hoard away ends idle tokens; a killed Hoard ends all of them.                                                                                   |

**SMB: stream, don't copy.** A RAW editor reads the whole file, so streaming saves no bytes; it
saves the wait. Copying a batch of 40 RAWs (1 GB+) before Reveal opens would hold the user for
minutes, and copying behind Reveal's back into a hidden cache doubles storage and contradicts both
apps' "originals are never copied" stance. ShareOut's "≤ 16 MiB goes to the cache" rule is not
reused either: it would make the JPEG of a pair durable and its RAW not, a confusing half state.
So every SMB photo streams; one SMB connection serves the whole batch (a reference-counted source
that closes with the last token), and the "Streaming" foreground notification keeps Hoard serving
while Reveal is in front (Android 13+ asks for the notification permission first, as `streamFile`
does).

The cost is that the stream cannot outlive Hoard. The UI says so **before** an SMB hand-off (a
confirmation: "Photos on a network share are read through Hoard: Reveal can open them while Hoard is
running, and for an hour after it last read them. To keep editing them later, copy them to the phone
first and open them from there."), with "Don't explain this again" (`revealStreamNoteSeen`). Copying
to the phone is the existing transfer engine (Copy to…, Copy picks), not a second copy path.

### No sidecar write-back

> **1.9.0 ([ADR 0011](0011-culling-sidecars.md)):** Hoard now writes culling decisions into XMP sidecars next to
> the photos in writable folders (not through this hand-off), and every hand-off carries the decisions as
> Reveal's `CULLING` extra (version 1, keyed by item URI), writable folder or not. The hand-off itself stays as
> below: no `.xmp` item, no write grant.

Hoard never sends a `.xmp` item and never grants write. Reveal therefore keeps its ratings and
edits in its own database ("Saved in Reveal only"), and Hoard's decisions stay in Hoard's DB (ADR
0008). An opt-in "send the existing sidecar, writable" (only when one already exists next to the
source and the location is writable) is possible later under Reveal's contract; it is not
implemented, so nothing is ever written next to a source by this feature.

### UI

The action exists only while Reveal is installed; otherwise nothing shows (no prompt to install).
Icon: Phosphor `SlidersHorizontal`. Strings under `reveal.*` (en, pt-BR).

- **Culling viewer**: ⋯ → **Edit in Reveal**: the current shot (all its files), opened in the
  editor at once (`initialPath` = its RAW, else the rendering). Back in Reveal returns to Hoard.
- **Culling page**, Finish menu: **Edit N picks in Reveal** (every pick of the folder) and **Edit
  the N shown in Reveal** (the grid's current filter: the culling grid has no multi-select, so the
  filter is its selection — e.g. "Picked, 4+ stars"). Titled with the folder's name.
- **Browser**: the selection bar's ⋯ and the row menu → **Edit in Reveal** when the selection has
  photos (RAW, JPEG, HEIF, PNG, WebP). Each selected photo is expanded to every photo of the folder
  with the same stem (`selectionPaths`): selecting `DSC1.ARW` also sends `DSC1.JPG`. Folders and
  other files are left out. A single row opens in the editor at once.
- **Batches above 200 files** ask first ("Open 640 files in Reveal?").
- **Settings → About** shows one line about Reveal when it is installed.

## Alternatives considered

- **Share sheet (`ACTION_SEND_MULTIPLE`) to Reveal**: Reveal accepts it, but the user would pick
  Reveal every time and lose `TITLE` / `INITIAL_URI`.
- **Copy SMB photos to the phone first, always**: durable, but slow for batches and a second copy
  of every RAW; offered as advice instead (the transfer engine does it on request).
- **Keeping SMB stream tokens forever**: a foreground notification and an SMB connection for hours;
  and it still ends with Hoard's process.
- **A writable empty `<stem>.xmp` created next to every source**: violates ADR 0009 (Hoard never
  writes next to the source).

## Consequences

- `HoardSmbPlugin`: `revealAvailable`, `openInReveal`; `StreamEntry.idleMs`; manifest `<queries>`.
- `src/features/reveal/` (pure `handoff.ts` + tests, `RevealProvider`), the culling page and viewer,
  the browser, Settings. Setting `revealStreamNoteSeen` (ADR 0003; a key, no migration).
- Reveal re-reads nothing back into Hoard: decisions made in Reveal stay in Reveal (1.9.0: Hoard reads sidecars
  changed by any editor when a folder opens or the app comes back to the front, ADR 0011).
