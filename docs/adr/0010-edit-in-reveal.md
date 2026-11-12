# ADR 0010: Edit in Reveal

- **Date**: 2026-11-11
- **Status**: Accepted (1.8.0); amended 2026-11-12 (1.10.0): persistable hand-offs, stable SMB links served after a
  restart, sidecar write-back, explicit Reveal package. Sections marked **(1.10.0)** replace what 1.8.0 said.

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
- **Intent** (`RevealHandoff.plan`, JVM-tested) **(1.10.0)**: `ACTION = dev.hfps.reveal.action.DEVELOP`, type
  `image/*`, sent to one package explicitly (below), `ClipData` with one item per URI in that order (each shot's
  sidecar right after its files, when sent), `EXTRA_TITLE` (trimmed, at most 120 characters, omitted when blank),
  `EXTRA_INITIAL_URI` only when the initial path is one of the photos sent. Flags: `FLAG_GRANT_READ_URI_PERMISSION |
FLAG_GRANT_PERSISTABLE_URI_PERMISSION` on every item, for every location kind; the intent **never** carries
  `FLAG_GRANT_WRITE_URI_PERMISSION` (Android applies an intent's flags to every ClipData item, so a write flag there
  would make the photos writable). Sidecar items get read + write + persistable through
  `grantUriPermission(<package>, uri, …)` before `startActivity` (revoked again if the start fails). The ClipData
  carries the grants, so Reveal gets exactly those URIs: no folder, no tree, no prefix grant. `resolveActivity` is
  checked before `startActivity`.
- **Detection and package (1.10.0)**: a `<queries>` entry for the action with `image/*` (package visibility) and
  `queryIntentActivities(Intent(ACTION).setType("image/*"), 0)`; of the packages that answer, `RevealPackages.choose`
  (JVM-tested) takes `dev.hfps.reveal` when installed, else `dev.hfps.reveal.debug`, and nothing else (a third-party
  app answering the action is never chosen: the grants are persistable and write on sidecars). The intent is
  `setPackage(<chosen>)`, so a phone with both builds gets no chooser (1.8.0 showed two identical "Reveal" entries and
  the owner landed in the debug build). The app asks at start and on every `resume` / `appStateChange`
  (`RevealProvider`), so installing or uninstalling Reveal shows or hides the action the next time Hoard comes to the
  front.

### URIs per location kind, and how long they work (1.10.0)

| Location                                 | URI                                                                    | Lifetime in Reveal                                                                                                                                                                       |
| ---------------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Device / volume with all files access    | FileProvider (`<app>.fileprovider`, `file_paths.xml`), in place        | **Persistable**: Reveal's `takePersistableUriPermission` succeeds (verified on API 36: `persisted=0x1`, sidecars `0x3`). Survives restarts of both apps and of the phone.                |
| Volume through SAF (no all files access) | The SAF document (tree-based, under Hoard's persisted grant), in place | Persistable (as since 1.8.0). Owned by Android's storage provider: Hoard's "Revoke all" cannot revoke it.                                                                                |
| SMB share                                | `content://<app>.stream/h/<id>/<tag>%2F<name>`, a stable link (below)  | **Persistable**, served by Hoard whenever Reveal opens it (Hoard's process is started for the provider if needed), while the machine is reachable; forgotten after 180 days without use. |

1.8.0 said FileProvider grants could not be persisted: that is not so. The framework (`UriGrantsManagerService`)
persists any grant made with `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` on a provider with `grantUriPermissions`; it does
not require a `DocumentsProvider`. Both Hoard providers stay `exported="false"`.

### Stable SMB links (1.10.0)

The 1.8.0 SMB hand-off used the stream tokens of ADR 0004 (random per hand-off, 60 min idle, gone with Hoard's
process), so a photo Reveal reopened later failed with `SecurityException: Permission Denial` (the grant ended with
the token) or `FileNotFound`. Now:

- **Store** (`smb/handoff/HandoffStore.kt`, JVM-tested): one JSON file in `noBackupFilesDir`
  (`reveal-handoffs.json`, never backed up), written atomically. A link is a random 256-bit id (never a path) mapped to
  (machine id, share, normalised path, name, MIME, size, mtime, sidecar or not, folder tag, URI, created, last used).
  Same machine + share + path (case-insensitively, as SMB) → **same id**, so the grant Reveal persisted keeps matching
  when the file is handed over again. The tag (random, per machine + share + folder) goes in the last segment as
  `<tag>/<name>` (encoded `%2F`): Reveal reads a last segment with a `/` as "folder + name" (as for SAF documents), so
  a RAW and its JPEG from one folder still pair; the URI never shows a path.
- **Serving** (`HandoffServer`, inside `StreamProvider`): only `/h/<id>/…` of a known id is answered; no listing, no
  path in, ids compared in constant time. `query` answers name, size, mtime and type from the store (no network).
  `openFile` runs on a binder thread (never the main thread): it connects, stats the file and returns the seekable
  proxy descriptor of ADR 0004 (`StorageManager.openProxyFileDescriptor`). Unknown link, machine gone, no login,
  unreachable share, missing file, refused login → `FileNotFoundException` with what went wrong (Reveal treats the photo
  as unreadable and shows its own preview). A write mode on a photo → `SecurityException`.
- **Short-lived token URIs** (`content://<app>.stream/<token>/<name>`) stay as they were for share-out and video
  streaming; only "Edit in Reveal" uses links.

### Cold start (1.10.0)

When Reveal opens a link and Hoard is not running, Android starts Hoard's process for the provider (no activity, no
WebView). Everything the provider needs is native:

- **Machine mirror**: the JS DB owns machines; `syncMachines` (contract) replaces a native copy of each machine's id,
  pinned server GUID and addresses in priority order whenever they change (`RouteProvider`, from the live machines
  query; machines left out are forgotten, and their links stop being servable). Every hand-off also notes its route
  (`noteRoute`: an unknown machine is added, a known one learns the GUID and the address in use). No secrets: the
  username and password stay in the Keystore blob of `setCredentials` (`CredentialStore`), read natively, never logged.
- **Native route** (`NativeRoute`, JVM-tested): the address that last worked first, then the priority order; each
  connect checks the pinned GUID right after NEGOTIATE (`SmbConnector`, the password only after a match). Another
  server's GUID or a dead address → the next address (as the JS engine); a refused login, denied share or missing
  share stops at once. A machine without a pinned GUID is never connected to natively. The JS engine stays the app's
  route engine; this one only serves links.
- **Connections and the service**: one SMB connection per machine + share for every link being read (reference
  counted). Every open takes a **lease** in the stream registry (`h-<id>`, not a token: unreachable over HTTP) that
  ends 2 min after the last read (`HandoffServer.LEASE_IDLE_MS`); while a lease lives, the `dataSync` streaming
  service keeps the process un-frozen (Android lets the provider start it from the background: verified on API 36),
  and the "Streaming" notification shows. With no lease left the service stops: no permanent notification.

### Write-back (1.10.0)

Replaces 1.8.0's "No sidecar write-back". With `openInReveal`'s `sidecars` (set when Settings › Culling › "Save
ratings next to photos (XMP)" is on, ADR 0011; `hidden` from "Write XMP as hidden file"), every shot of a folder Hoard
can write also sends its sidecar, per Reveal ADR 0011 "What Reveal writes back":

- the shot's existing sidecar by Hoard's naming rules (`SidecarNames`: `<stem>.xmp`, `<file>.xmp`, hidden forms) when
  it may be written (`TransferFs.canWrite`: SMB opens it for writing and closes it, paths check the file, SAF the
  document's `FLAG_SUPPORTS_WRITE`) and is well-formed XMP (a malformed one is never sent, so never rewritten);
- else a new `<stem>.xmp` (`.<stem>.xmp` hidden) written through Hoard's XMP module with the shot's current decision
  (the `CULLING` value of any of its files; none = rating 0, no flag). A folder that refuses that write is read-only:
  none of its shots get a sidecar (`RevealHandoff.writableSidecars`, JVM-tested with `MemoryFs`).

Read-only folders send none; their decisions still travel as `CULLING` values. The sidecar items go as links (SMB),
FileProvider URIs or SAF documents with read + write + persistable grants; photos stay read-only for every location.
For SMB the provider accepts `w`, `wt`, `rwt`, `rw`, `wa` on sidecar links only (`HandoffAccess`): the bytes stay in
memory (at most 4 MiB, `SidecarBuffer`), and when the descriptor is synced or closed they are written as
`.<name>.hoardpart` and renamed over the sidecar (atomic on the share), only if they are well-formed XMP. Hoard's
sidecar import (ADR 0011: a sidecar newer than Hoard's last write or decision wins) then takes Reveal's rating the next
time the folder opens for culling or the app comes back to the front.

### Bounds and revoking (1.10.0)

- Android does not let an app see grants other apps persisted (`ContentResolver.persistedUriPermissions` lists only the
  caller's own), so Hoard cannot know which links Reveal still keeps. SMB links unused (not read, not handed over) for
  **180 days** are forgotten and their grants revoked (`HandoffStore.prune`, at plugin load and before each SMB
  hand-off). FileProvider records are only capped (20 000, oldest forgotten, grants untouched): Hoard does not see
  their reads, and they cost nothing to keep. Reveal itself releases the grants of hand-offs it forgets (its
  retention: 400 photos); Android caps persisted grants per app (512 on recent versions), which Reveal's retention
  stays under for typical RAW + JPEG + sidecar batches only if it releases as it forgets.
- **Settings › About › "Reveal links"**: the count, and **Revoke all** (`revokeRevealLinks`): one
  `revokeUriPermission(content://<app>.stream/h, read|write)` (a prefix revocation that removes persisted grants too),
  one per FileProvider URI recorded, then the store is emptied (the machine mirror stays). SAF documents are Android's
  storage provider's to revoke. Reveal then shows its preview for those photos; handing them over again makes new links.

### Confirmation (1.10.0)

The SMB note before the first hand-off now says what is true: "Photos on a network share are read through Hoard:
Reveal can reopen them later, even after the phone restarts and with Hoard closed, while this machine is reachable on
the network." (`reveal.confirm.stream`, en + pt-BR; "Don't explain this again" as before, `revealStreamNoteSeen`).

### No sidecar write-back (1.8.0, superseded by "Write-back" in 1.10.0)

> **1.9.0 ([ADR 0011](0011-culling-sidecars.md)):** Hoard writes culling decisions into XMP sidecars next to the photos
> in writable folders, and every hand-off carries the decisions as Reveal's `CULLING` extra (version 1, keyed by item
> URI), writable folder or not. **1.10.0:** the hand-off also sends writable sidecar items (above).

1.8.0 sent no `.xmp` item and no write grant: Reveal kept its ratings in its own database ("Saved in Reveal only").

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
- **Keeping SMB stream tokens forever** (1.8.0's view): a foreground notification and an SMB connection for hours,
  ended with Hoard's process. 1.10.0 keeps the _links_ forever instead and connects only while Reveal reads.
- **Adding the SMB folder inside Reveal** (Reveal would need its own SMB client, credentials and route detection): the
  owner chose persistable hand-offs from Hoard instead.
- **Write flag on the intent** (Reveal ADR 0011's example): it applies to every ClipData item, so FileProvider and SAF
  photos would become writable; per-item `grantUriPermission` on the sidecars only.
- **SQLite / Room for the link store**: a JSON file is enough for thousands of rows, is JVM-testable without
  Robolectric and needs no schema migrations; written atomically.
- **A writable empty `<stem>.xmp` created next to every source** (1.8.0: violated ADR 0009). Since ADR 0011 Hoard writes
  sidecars in writable folders when the culling setting allows it; 1.10.0 creates one per shot only then, with the
  shot's decision, never in read-only folders.

## Consequences

- `HoardSmbPlugin`: `revealAvailable`, `openInReveal` (1.10.0: `sidecars`, result `sidecars`), `syncMachines`,
  `revealLinks`, `revokeRevealLinks`; manifest `<queries>`.
- 1.10.0 native: `smb/handoff/` (`HandoffStore`, `HandoffServer`, `HandoffLogic`: `NativeRoute`, `HandoffUris`,
  `HandoffAccess`, `SidecarBuffer`), `StreamProvider` serves `/h/…`, `StreamRegistry.lease`, `SmbSource`'s `fatal`
  set, `TransferFs.canWrite`, `RevealPackages`. The 1.8.0 `HANDOFF_IDLE_MS` (60 min tokens) is gone.
- `src/features/reveal/` (pure `handoff.ts` + tests, `RevealProvider`, `RevealLinks`), the culling page and viewer,
  the browser, Settings; `RouteProvider` mirrors machines (`nativeMachines` in `route/engine.ts`). Setting
  `revealStreamNoteSeen` (ADR 0003; a key, no migration).
- Decisions made in Reveal come back through the sidecars (ADR 0011's import) when the folder can be written; from
  read-only folders they stay in Reveal.
- End-to-end run: [docs/testing/persistable-handoff.md](../testing/persistable-handoff.md).
