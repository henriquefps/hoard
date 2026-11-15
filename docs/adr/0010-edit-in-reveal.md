# ADR 0010: Edit in Reveal

- **Date**: 2026-11-11
- **Status**: Accepted (1.8.0); amended 2026-11-12 (1.10.0): persistable hand-offs, stable SMB links served after a
  restart, sidecar write-back, explicit Reveal package. Sections marked **(1.10.0)** replace what 1.8.0 said.
  Amended 2026-11-15 (2.0.1): "Bursts of opens" (limits on the provider, the `busy:` failure contract).
  Amended 2026-11-15 (2.0.2): "Idle reclaim" (idle proxy descriptors give their read slot back; the contract unchanged).
  Amended 2026-11-15 (2.1.0): **server items** go by reference, `CULLING` version 2 (section "Server items (2.1.0)",
  Reveal ADR 0011 amendment W3/H).
  Amended 2026-11-15 (2.2.0): SMB files of Files and folder culling that the server holds go as server items too
  (section "Server items (2.1.0)", last bullets; ADR 0022).
  Amended 2026-11-15 (2.2.0, owner's decision): **local copies, no streaming for new hand-offs** (section "Local
  copies, no streaming for new hand-offs (2.2.0)"). It replaces, for every new hand-off, what "URIs per location kind",
  "Stable SMB links", "Write-back" and the persistable grant of "Contract" said; the provider keeps serving the links
  already handed out ("Cold start", "Bursts of opens", "Idle reclaim", "Bounds and revoking" stay for those).

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
  counted). Every proxied open takes a **lease** in the stream registry (`h-<id>`, not a token: unreachable over HTTP) that
  ends 2 min after the last read (`HandoffServer.LEASE_IDLE_MS`); while a lease lives, the `dataSync` streaming
  service keeps the process un-frozen (Android lets the provider start it from the background: verified on API 36),
  and the "Streaming" notification shows. With no lease left the service stops: no permanent notification.

### Bursts of opens (2.0.1)

Reveal opened every link it held at start-up (thumbnails, backup, library refresh). On the owner's Samsung S24
(Android 16) a burst of `HoardHandoff: open …` lines was followed by the storage service hanging and the
`system_server` watchdog restarting the phone. Reproduced on an emulator (`docs/testing/handoff-burst.md`: 200
concurrent opens from a test app with its own uid). With 2.0.0, every SMB link open was a proxy descriptor
(`StorageManager.openProxyFileDescriptor`: an appfuse file that system_server opens through vold, which asks Hoard
for the file's size while it holds the open). Every Hoard binder thread sat in an SMB call, system_server parked
its binder threads in `openFile` for Hoard (188 watchdog pauses in one burst), and Hoard's own `startForeground`
could not get through for 20 s. Android then killed Hoard (`ForegroundServiceDidNotStartInTimeException`), and Reveal
died with it because it held the provider. Holding descriptors open meant a thread for each one (98 threads, 112
descriptors). The provider is now bounded whatever a client does:

- **One limit, `MAX_READS` = 6** (`HandoffServer.reads`, a `HandoffLimiter`, JVM-tested): at most 6 hand-off reads
  run at once. A read is an open doing network work (stat, a small copy, opening a descriptor), or a proxy descriptor
  from its open until the client closes it (2.0.2: or until it has been idle for 12 s, "Idle reclaim"). Other opens
  wait their turn, first come first served, at most `BUSY_WAIT_MS` = 1.5 s, then fail. No binder thread waits longer
  than that; the waiters are bounded by Hoard's binder threads (16) plus, since 2.0.2, its descriptor loopers (12),
  so there is no separate queue limit and no refusal before the wait.
- **Busy contract (frozen; [integration](../integration/README.md) contract 11)**: opening a Hoard hand-off URI
  throws `java.io.FileNotFoundException` whose message starts exactly with `busy:` in one case only: the 6 reads are
  taken and the 1.5 s wait freed no slot (`busy: 6 hand-off reads in progress, retry`, `HandoffBusy`). No other
  failure uses the prefix: a message that would start with it (a file named `busy:…`) is reworded. Clients retry with
  bounded exponential backoff (250 ms doubling up to 4 s, giving up after about 30 s), keep their own concurrent
  opens at or below 6, and never mark a busy file unreadable. Every other `FileNotFoundException` means what it did:
  the link is unknown or revoked, or the file is unreadable, so Reveal shows its preview. The limit (4–8) and the wait
  (< 2 s) are checked by `HandoffLimitsTest`.
- **Small files, no appfuse** (`HandoffCache`): files of at most 8 MiB (sidecars, JPEGs, previews) are copied once
  into `cacheDir/handoff/`, keyed by link id, size and mtime from the stat that every open does anyway. They are
  served as plain file descriptors (`ParcelFileDescriptor.open`): no vold, no thread of Hoard's per read. A copy
  slower than 6 s is abandoned for a proxy descriptor. The folder is capped at 256 MiB (least recently used first) and
  emptied by "Revoke all". Read-only opens only: sidecar writes are unchanged.
- **Proxy descriptors** (`HandoffProxy`): a proxy descriptor holds a read slot while it is read; since 2.0.2 an idle
  one gives it back (below), so at most 6 are being read at once. `openProxyFileDescriptor` is called by one thread
  at a time (vold serialises it anyway), so system_server never has more than one of its threads in an appfuse open
  for Hoard (1–5 ms each when serialised). Each live descriptor has its own looper from a pool started once and
  reused: a slow read on one descriptor never delays another descriptor's size or read. Reads run on a shared
  executor, and a callback waits at most 10 s (`TimedReader`) before answering `EIO`. `RemoteReader`'s read-ahead
  serves sequential reads from memory.
  **Client obligation (good practice):** a client should close a hand-off descriptor as soon as its read is done and
  never hold more than 6 at once. In 2.0.1 this was the only protection: a client that kept 6 RAW descriptors open
  (or leaked one per photo) made every later open fail with `busy:` until it closed them or Hoard's process ended.
  Since 2.0.2 Hoard reclaims idle descriptors itself, so a leaking client costs others at most `IDLE_RELEASE_MS`.
- **Connections**: one SMB connection per machine + share for every open, lease and write, kept for 60 s after the
  last use (`Sources.LINGER_MS`), so cached opens, which take no lease, do not reconnect each time. An unreachable
  machine, a refused login or a GUID mismatch makes the next opens of that share fail at once for 10 s
  (`ConnectBackoff`): no connect timeout per open.
- `query()` / `getType()` read only the in-memory store. Last-use and size updates stay in memory and are written once
  per burst (`HandoffStore.flush`), not as one JSON rewrite per open.
- **Logging**: one line per burst, once 2 s pass with no open
  (`open x237 in 13.6 s, 16 concurrent, 37 refused (140 cached, 60 proxied, 0 failed), 0 descriptors open, appfuse open
  avg 1 ms max 6 ms`). Per-open timings only at debug level (`setprop log.tag.HoardHandoff DEBUG`).
- FileProvider links (phone files) were already plain file descriptors, opened by `androidx.core`'s `FileProvider`
  with no appfuse: measured cheap and left as they were.

### Idle reclaim (2.0.2)

2.0.1 left Hoard at the mercy of its clients: a proxy descriptor (every file over 8 MiB, so every RAW) held one of the
6 read slots until the client closed it, so a client that leaked 6 descriptors locked every other open into `busy:`.
Hoard now reclaims them itself (`HandoffHold`, `HandoffDescriptors` in `HandoffReclaim.kt`, JVM-tested by
`HandoffReclaimTest`). The busy contract is unchanged: same limit, same wait, same message, same single case.

- **Park after `IDLE_RELEASE_MS` = 12 s** without a callback: the descriptor gives its slot back and stays open (its
  SMB reader too). 12 s is longer than a read may take (`READ_TIMEOUT_MS` = 10 s) and than the pauses of a client
  reading one photo (a thumbnail then the full decode), and well inside a client's busy retry budget (about 30 s): a
  client retrying `busy:` while another leaks every slot gets one within about 12 s plus one backoff step. A callback
  in progress is never idle, however long it takes.
- **Re-acquire on the next read**: a parked descriptor's next read (on a sidecar, its next read, write or sync) takes a
  slot through the same `HandoffLimiter`, in the same first-come queue as opens, waiting at most `BUSY_WAIT_MS` =
  1.5 s. When none frees, that read answers `EIO` instead of blocking (the kernel's request must not wait on other
  clients), and the next read tries again. A size query is answered from memory and takes no slot.
- **Retire after `RETIRE_MS` = 10 min** without a callback: Hoard runs what `onRelease` would (the SMB reader closed,
  so the share's open handle goes; the lease and the connection follow; a sidecar's pending bytes are written first),
  and every later callback answers `EIO` at once. The client's descriptor stays valid as a file descriptor until it
  closes it; reading it again means opening the link again. 10 min outlasts the lease (2 min) and an editing pause.
- **Loopers stay bounded** (`MAX_LIVE` = 12, twice `MAX_READS`): each live descriptor (active or parked) owns one
  looper of a pool created once. When all 12 are owned, the next open retires the least recently used parked
  descriptor and reuses its looper (a retired descriptor answers at once, so sharing its looper delays nobody). Only
  if all 12 are active or inside a callback does a looper get a second owner. Hoard therefore runs at most 12 looper
  threads whatever a client leaks.
- Locks: a parked descriptor waiting for a slot holds no lock of its own, parking and retiring never happen inside a
  callback, and the limiter is never called with a descriptor's lock held, so slots and descriptors cannot deadlock.
- The burst summary reports `N descriptors open (P parked, R reacquired, X retired)`; retiring logs one line.
- Measured with a leaking client (20 RAW opens, never closed) followed by a normal client:
  `docs/testing/handoff-burst.md`, "Idle reclaim".

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

### Server items (2.1.0)

Reveal ADR 0011 amendment W3/H (frozen 2026-11-15; `docs/integration/README.md` contracts 9 and 10). Sender:
`RevealHandoff.launchMixed` (`:core:smb`), decided per shot by `GalleryReveal` (`:native`, ADR 0021).

- **Which shots.** A shot goes as a **server item** when Hoard's paired server holds it (a Gallery item on the server,
  or a phone shot whose fingerprint the server has) and the installed Reveal is ≥ 0.9.0 (`versionCode >= 90000`,
  `RevealHandoff.acceptsServerReferences`). Otherwise a shot on the phone goes as a URI item (as before), and a shot
  only on the server cannot go: the viewer says "update Reveal". Hoard never streams a server photo to Reveal (no
  provider, no grant, nothing in `HandoffStore`), and never falls back to URIs for a photo the server holds when
  Reveal takes references, whether or not that Reveal is paired (Reveal offers to pair or claim). SMB files of Files
  and folder culling go as server items since 2.2.0 (below).
- **SMB files (2.2.0, ADR 0022).** On an SMB share Hoard maps to one of the server's NAS folders (learned from two
  files' fingerprints, `items-by-path`), each shot whose main file (the RAW of a RAW + JPEG shot) the server's index
  matches by path, size and mtime goes as a server item; any other shot is looked up by its main file's fingerprint
  (at most 20 shots per hand-off) and goes as a server item when found, else as URIs. Never URIs for a photo Hoard
  knows the server holds; a photo it cannot cheaply place goes as URIs. `FileActions.editInReveal` builds one mixed
  hand-off (`launchMixed`): server items for those shots, URIs (ClipData, grants, sidecars, `CULLING`) for the rest;
  the items' pending culling is pushed first. Reveal older than 0.9.0: every file as a URI, as before.
- **The intent.** The same `dev.hfps.reveal.action.DEVELOP` to one package (release, else debug). Server items add
  `SERVER_REF_VERSION` = 1, `SERVER_ID` (the paired server's id), `SERVER_ITEMS` (an `ArrayList<Bundle>`, order kept,
  one per shot: `itemId`; `fingerprint` = the main file's `v1:`; `fingerprints` = every member's when more than one;
  `name` ≤ 255) and `INITIAL_ITEM` (wins over `INITIAL_URI`). No ClipData, URI, grant or culling key for server items.
  At most 500 server items per intent; more go as several intents with the same `TITLE` (`startActivities`). One
  intent may also carry URI items (ClipData, grants and `CULLING` for those only). A full batch of 500 pairs is
  ≈ 320 KB of extras, a third of the Binder buffer.
  When there is more than one intent, every one carries `BATCH_INDEX` (Int, 0 for the first, the one with
  `INITIAL_ITEM` / `INITIAL_URI`, then 1..n-1 in order) and `BATCH_COUNT` (Int, n); a single intent carries neither.
- **Culling first.** Before sending, the shots' pending culling changes are pushed (best effort, 3 s); Reveal pulls
  the items' culling records on receipt.
- **`CULLING` version 2** (URI items): per field `ratingModifiedAt` / `flagModifiedAt` / `labelModifiedAt` (ms, the
  time of Hoard's decision row) and `ratingDevice` / `flagDevice` / `labelDevice` (Hoard's server `deviceId`, only when
  paired). Sent as version 2 to Reveal ≥ 0.9.0; an older Reveal gets version 1 with the times and without the devices.
- **Tests.** `RevealHandoffTest` (batches, fields, sizes, versions) and `ServerReferenceIntentTest` (Robolectric: a
  test receiver `dev.hfps.reveal` at versionCode 90000 reads the intents back: no ClipData or grants, extras, 1,001
  items in three intents, mixed URI + server items, CULLING v2; an older Reveal takes none). An end-to-end run with a
  real Reveal 0.9.0 waits for that build.

### Local copies, no streaming for new hand-offs (2.2.0)

Owner's decision of 2026-11-15 (`docs/integration/README.md`, "Intent hand-off"). Streaming a RAW from a share through
Hoard's provider tied Reveal to Hoard and the network for as long as it kept the photo (cold starts, bursts, `busy:`,
idle reclaim); sidecar items made a second, file-based sync channel next to the server's. A new hand-off now gives
Reveal each photo one of three ways, per shot:

1. **Server reference**, when the paired server holds it (Gallery items, mapped SMB folders, a looked-up photo):
   exactly as "Server items (2.1.0)" says, unchanged.
2. **Its own MediaStore URI**, when it is on the phone and MediaStore indexes it (`RevealCopies.onPhone`: the phone's
   storage, by `RELATIVE_PATH` + `DISPLAY_NAME` in `MediaStore.Images`): `content://media/external_primary/images/media/<id>`
   as is, no copy.
3. **A copy in MediaStore**, for anything else (an SMB file the server does not hold, a USB drive, an SD card, a SAF
   folder, a phone file MediaStore does not index): the shot's files (RAW + JPEG etc., never a sidecar: `order()` keeps
   photos only) are copied **through the transfer engine** (ADR 0007, destination `Endpoint.MediaStore`, one job per
   hand-off, visible in Transfers; no second copy path) into `Pictures/Hoard/<batch title, else yyyy-MM-dd>/`.
   - **Collection** (`MediaStoreTarget.forFormat`, JVM-tested): `MediaStore.Images` with the MIME type Android's MIME
     map gives the extension, which is what MediaProvider accepts (`docs/testing/mediastore-raw.md`: ARW, NEF, RAF,
     DNG, ORF, RW2, JPEG kept their MIME type, visible to another app's query and readable). A format Images refuses
     (no `image/*` type in the map: **CR3** on Android 16) goes to `MediaStore.Downloads` under
     `Download/Hoard/<batch>/` as `application/octet-stream` with its real name (coordinator's decision, 2026-11-15).
     Another app opens such a file only through the grant (it is not in its MediaStore queries), so **Reveal copies
     those into its own storage on receipt**: it cannot re-open them later.
   - **Writing** (`MediaStoreTransferFs`): the engine's partial file is a row inserted with `IS_PENDING = 1` under its
     final name (invisible to other apps); the engine's rename publishes it (`IS_PENDING = 0`). Rows not published when
     the job ends (cancelled, failed, the worker stopped) are deleted when the endpoint closes: **Cancel removes the
     partial copies**. A name taken in the batch folder gets the engine's `name (1).ext`.
   - **Reuse** (`reveal_copies`, Room version 2, ADR 0003): each copy is a row (keyed by its URI) with its source
     (location key + path) and the source's size and mtime when copied. A later hand-off of the same source reuses a
     copy whose size and mtime match while MediaStore still has it, with that size, in Hoard's copy folders
     (`RevealRoutes.plan`, JVM-tested). A source that changed is copied again; its older copy stays a row.
   - **Recorded as soon as the job ends, whatever its outcome**: the completed items of a job that fails or is
     cancelled midway are saved before the error is reported, so a retry reuses them instead of making
     `name (1).ext` duplicates (`RevealRoutes.records`, JVM-tested with a job failing at item 250 of 300).
   - **Progress**: the screen shows "Copying for Reveal" with files and bytes while the job runs, and Cancel (which
     cancels the job). The hand-off starts when every photo is there; a failed copy fails the hand-off with the
     job's error (Retry in Transfers, or edit again: what was copied is reused).
   - **Hoard in the background when the copy ends**: Android 10+ blocks starting Reveal then, so the prepared intents
     go into a notification, "Ready to open in Reveal", whose tap sends them (`RevealReady`, a `PendingIntent` of the
     same intents and grants). With notifications off, the screen offers "Open in Reveal" when Hoard returns.
   - **Space**: Settings › "Photos copied for Reveal" shows how many and how big, and **Delete copies** after a
     confirmation that says Reveal falls back to its own previews for those photos. Only rows `reveal_copies`
     recorded are counted or deleted, and only while MediaStore still has them, with the recorded size, under
     `Pictures/Hoard/` or `Download/Hoard/` (exact case). A file Hoard owns there but did not record (one the user
     copied with the file manager), a copy the user moved out, or an id MediaStore reused for another file is never
     touched; records that no longer stand for a copy are forgotten (`RevealRoutes.deletion`, JVM-tested). Nothing is
     deleted automatically.
   - **Not in the Gallery**: the device index skips `Pictures/Hoard/` and `Download/Hoard/` (`DeviceFolders.EXCLUDED`),
     so copies never show up as phone photos.

**The intent** is the usual `DEVELOP` to one package: ClipData of the `content://media/…` URIs (in order),
`TITLE`, `INITIAL_URI`, `CULLING` (version 2 to Reveal ≥ 0.9.0, keyed by those URI strings), server items mixed in as
before. **URI items go at most 200 per intent** (`MAX_URI_ITEMS`), each intent with the ClipData, grant and `CULLING`
of its own URIs, so 1,000 photos with `CULLING` v2 stay far under the ~1 MB Binder buffer; the chunk holding the
initial photo goes first. Intent i carries URI chunk i and server batch i (500 per intent), and every intent of a
multi-intent hand-off carries `BATCH_INDEX` / `BATCH_COUNT` (`RevealHandoff.buildIntents`, built off the main thread by
`prepare`, started by `start`). The intent's flags carry **`FLAG_GRANT_READ_URI_PERMISSION` only**:
no persistable grant, no write grant (`grantUriPermission` is no longer called), **no sidecar items**.

**Old links.** `HandoffStore`, `HandoffServer` and `StreamProvider` stay, with `busy:`, idle reclaim and pruning, so the
`content://<app>.stream/h/…` links handed out before 2.2.0 keep working; no new hand-off makes one, and no FileProvider
URI is recorded any more. Settings › "Reveal links" still revokes them.

**Confirmation.** The SMB note ("read through Hoard") is gone; batches over 200 still ask.

Android 10 or later (MediaStore's `RELATIVE_PATH` / `IS_PENDING`; Reveal needs Android 10 too). Tests:
`MediaStoreTargetTest`, `RevealRoutesTest` (routes, reuse by path + size + mtime, mixed with server items, cancel and
failure), `TransferRunnerTest` (a stopped job never publishes its part and closes the destination; no XMP), 
`ServerReferenceIntentTest` (MediaStore URIs + server items in one intent, read grant only, CULLING v1 for an older
Reveal, BATCH extras), `MigrationTest`; emulator E2E in `docs/testing/local-copy-handoff.md`.

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
