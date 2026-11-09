# ADR 0001: Capacitor app with a native SMB plugin

- **Date**: 2026-11-08
- **Status**: Accepted

## Context

Hoard is an SMB file client for Android (iOS maybe later). The UI should be built the same way as
Henrique's other apps (React, shadcn/ui in the hfps identity, Capacitor), but SMB cannot be spoken
from a WebView:

- A WebView can only make HTTP(S) and WebSocket requests. SMB2/3 runs over raw TCP on port 445;
  there is no browser API for raw sockets.
- Even with a socket bridge, an SMB stack in JavaScript would push every file byte across the
  Capacitor bridge as base64 (about 33% larger, copied several times, on the UI thread), which rules
  out multi-gigabyte video files.
- Transfers must keep running with the screen off and after the app is swiped away, which only an
  Android foreground service can do.

## Decision

- The app is a **Capacitor 8** project. All UI is React in the WebView.
- All SMB work lives in one **native Capacitor plugin, `HoardSmb`**, written in Kotlin
  (`android/app/src/main/java/dev/hfps/hoard/smb/`), registered in `MainActivity`.
- The Kotlin side uses **[smbj](https://github.com/hierynomus/smbj)** (Apache-2.0, SMB 2.0.2 to
  3.1.1, signing and encryption, maintained, pure Java so it runs on Android). Alternatives
  considered: jcifs-ng (SMB1 heritage, slower, harder API), libsmb2 over JNI (native build per ABI
  for no real gain).
- **File bytes never cross the JS bridge.** Downloads stream SMB → Storage Access Framework inside
  the native transfer service; "open" downloads to the app cache and fires an `ACTION_VIEW` intent;
  thumbnails are cache files given to `<img>` through `Capacitor.convertFileSrc`. JS only sees
  metadata, progress events and URLs.
- **One deliberate exception: small text files.** `readTextFile` / `writeTextFile` carry a text
  file's contents as a JSON string, for the built-in text viewer and editor (2026-11-09, see
  "Text files" below). The rule exists for multi-gigabyte media; a config file, a log or a Markdown
  note is kilobytes, and an editor needs the text in the WebView anyway (CodeMirror edits a string;
  round-tripping through a cache file and `fetch` would add a native file, a URL and a cleanup for
  no gain, and saving would still need the text sent back). The exception is bounded: 5 MiB by
  default (`TEXT_FILE_MAX_BYTES`, `TOO_LARGE` above it unless the caller asks for the first part
  read-only), never more than 16 MiB natively, text only (`NOT_TEXT` for binary content). Images
  inside Markdown still go through `thumbnail` (cache-file URLs), never base64.
- The contract is one TypeScript file, `src/plugins/smb/definitions.ts`, with a typed error code
  on every rejection. A complete **in-memory mock** (`src/plugins/smb/web.ts`) implements the same
  interface so the UI is developed and tested in a desktop browser.
- Rule: a change to the contract changes `definitions.ts`, `web.ts` and the Kotlin plugin in the
  same pull request.

## Implementation (native plugin, 2026-11-08)

All code lives in `android/app/src/main/java/dev/hfps/hoard/smb/`. Every plugin method runs on a
coroutine (`Dispatchers.IO`), never on Capacitor's plugin thread, and every failure goes through
`SmbErrors.map` so it rejects with a contract code. Calls on an open session are never
thread-interrupted (smbj closes the share or connection when interrupted mid-request, which would
kill the session for every other call): cancellation is cooperative between chunks, and smbj's
20 s request timeout bounds each request.

| Area                        | How                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SMB                         | smbj 0.15 (SMB 2.0.2 to 3.1.1, signing on). One `SMBClient` per session, so sessions never share a TCP connection and `disconnect` closes exactly one. Requests time out after 20 s (a silent server is treated as gone). Folders are listed with a plain CREATE + QUERY_DIRECTORY (`SmbBrowser.listDirectory`), never smbj's `DiskShare.list`/`openDirectory`: their 0.15 directory-lease cache is keyed by the path inside the share only, so on servers that grant directory leases (macOS, Windows) every share showed the first share's folder (`docs/testing/shares-macos.md`). |
| `connect`                   | `Net.resolve` (DNS with a real deadline), TCP connect with timeout, NEGOTIATE, **GUID compare**, then SESSION_SETUP with the Keystore credentials (`SmbConnector`). The whole sequence is bounded by `timeoutMs` and interruptible. A Samba "map to guest" fallback is treated as `AUTH_FAILED`.                                                                                                                                                                                                                                                                                      |
| Sessions                    | `SmbSessions` registry (UUID ids). A call on a dead connection, or one that kills it, drops the session and rejects `NOT_FOUND`, so the UI reconnects. Dropped sessions close in the background, with TREE_DISCONNECT/LOGOFF only on a live link (closing inline on a silent link cost 90 s per failed call).                                                                                                                                                                                                                                                                         |
| `listShares`                | Own minimal MS-SRVS client (`SrvSvc.kt`): DCE/RPC bind + `NetrShareEnum` level 1 over `\PIPE\srvsvc`. We wrote this instead of using rapid7 `dcerpc` because that library pulls in Guava and commons and was built against smbj 0.12. Only disk shares are returned; `$` shares are `isHidden`.                                                                                                                                                                                                                                                                                       |
| Browsing                    | `SmbBrowser`: `list` (no `.`/`..`), `stat`, `createFolder` (FILE_CREATE → `ALREADY_EXISTS`), `rename` (FileRenameInformation in the same folder), `delete` (recursive depth-first, clears read-only).                                                                                                                                                                                                                                                                                                                                                                                 |
| `probe`                     | Plain `Socket.connect` with the timeout; DNS counts against it. Never rejects for unreachable.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `discover`                  | NsdManager `_smb._tcp` + Wi-Fi multicast lock; Android 14+ resolves with `ServiceInfoCallback` (in parallel), older versions with a serial `resolveService` queue; IPv4 preferred, deduplicated by name.                                                                                                                                                                                                                                                                                                                                                                              |
| `networkChanged`            | Default-network callback, debounced 750 ms; transport is wifi/ethernet/cellular before vpn (VPN networks carry their underlying transport on Android 12+), `vpn` = TRANSPORT_VPN.                                                                                                                                                                                                                                                                                                                                                                                                     |
| Credentials                 | `CredentialStore`: AES-256-GCM key in the Android Keystore (non-exportable); ciphertext in private SharedPreferences, machine id as associated data. Backups and device transfers exclude all app data (`allowBackup=false` + `data_extraction_rules.xml`).                                                                                                                                                                                                                                                                                                                           |
| Destinations                | SAF `ACTION_OPEN_DOCUMENT_TREE` through Capacitor's activity result, persisted read+write grant; external-storage trees are named by their relative path (`Movies/Hoard`). Stale grants are released when listed.                                                                                                                                                                                                                                                                                                                                                                     |
| Transfers                   | `TransferManager` + `TransferWorker` (WorkManager, expedited, promoted to a `dataSync` foreground service with a progress notification and a Cancel action). At most 2 run at once; the rest stay `queued`. Jobs persist to `files/hoard_transfers.json`, so `getTransfers` survives a process death, and unfinished jobs are re-enqueued when the plugin loads.                                                                                                                                                                                                                      |
| Streaming                   | `PipelinedFileReader` keeps 4 SMB READs (1 MiB each) in flight; smbj's own stream waits a full round trip per chunk. Bytes go straight into the SAF file descriptor.                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Failover                    | `FailoverPlan`: fatal errors stop the job; `GUID_MISMATCH` skips the address; anything else retries the same address 3 times with exponential backoff, then the next address, and walks the list twice. A READ unanswered for 15 s (route died without a FIN: Wi-Fi gone, VPN down) counts as a lost connection. Every reconnect re-checks the GUID before authenticating. The destination stays open and the transfer resumes at the exact byte written; a changed remote file (size or modified time) restarts at 0.                                                                |
| Resume                      | `resumeFromBytes` appends (`"wa"`) only if the partial file has exactly that size; a worker restarted after a process death continues after whatever the file holds.                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Progress                    | `transferProgress` on every state change and while running at most every 250 ms when the job moved 1% or 1 MiB (or anything after 1 s), with a 3 s rolling `bytesPerSecond`.                                                                                                                                                                                                                                                                                                                                                                                                          |
| `openFile`                  | Download to `cache/open/<key>/<name>` (key = server GUID + share + path + size + modified time, reused while unchanged, LRU-capped at 2 GiB), FileProvider URI, `ACTION_VIEW` (the system resolver picks the app), `NO_APP` when nothing handles the type. A newer call cancels the previous one (`CANCELLED`).                                                                                                                                                                                                                                                                       |
| `streamFile`, `openWebPage` | Streams through a proxy-FD content provider or the loopback web server, with their own connection and a `dataSync` foreground service: see [ADR 0004](0004-streaming-and-local-web-server.md).                                                                                                                                                                                                                                                                                                                                                                                        |
| `thumbnail`                 | JPEG under `cache/thumbs/` (150 MB, LRU). Small JPEG thumbnails use the EXIF thumbnail from the first 128 KiB; other images are downloaded (≤ 64 MiB) and decoded with subsampling (ImageDecoder on Android 9+, BitmapFactory + EXIF rotation before). Videos are never downloaded: `MediaMetadataRetriever` reads them through a random-access SMB `MediaDataSource`. `src` is `bridge.localUrl + "/_capacitor_file_" + path` (what `convertFileSrc` produces). At most 3 run at once.                                                                                               |

**Read-only shares and folders** (`SmbAccess.kt`, 2026-11-09, 1.2.0): `listShares`, `list` and
`readTextFile` report the account's `access` (`read` / `readWrite`) from the server's own answer,
never from a guess. Share level: the TREE_CONNECT response's MaximalAccess (smbj keeps it on
`TreeConnect.getMaximalAccess`); `listShares` connects every listed share for it, 4 at a time,
at most 64 shares and 4 s in total, and a share that fails or is slow is listed without `access`.
Folder and file level: the SMB2_CREATE_QUERY_MAXIMAL_ACCESS_REQUEST ("MxAc") create context on the
CREATE Hoard already sends to list the folder or read the file. smbj 0.15 can serialize create
contexts and parse them from responses, but only a package-private `Share.createFile` sends them,
so `com/hierynomus/smbj/share/HoardOpens.kt` (Hoard's single file in smbj's package) calls it and
wraps the handle in smbj's own `Directory` / `File`; signing, encryption and timeouts are smbj's,
and no lease context is ever sent (the directory-lease cache stays out of the way). The decision
counts FILE_WRITE_DATA/ADD_FILE, FILE_APPEND_DATA/ADD_SUBDIRECTORY, FILE_DELETE_CHILD,
GENERIC_WRITE and GENERIC_ALL as write rights and ignores DELETE, WRITE_DAC, WRITE_OWNER and the
attribute/EA writes, because Samba keeps those in the maximal access of a `read only = yes` share;
a share-level `read` wins over a folder's answer. Measured per server type in
[docs/testing/read-only.md](../testing/read-only.md). The UI treats unknown access as writable
and the server's ACCESS_DENIED stays the final word.

**macOS read-only shares** (2026-11-09, 1.2.1): macOS File Sharing answers full access at tree
level and POSIX-only rights in MxAc even on a share marked read-only, so 1.2.0 called folders
inside `Macintosh HD` (read-only: 1) writable. Two more answers from the server, no writes:

- Share level: `listShares` asks NetrShareEnum for level 502 (falling back to level 1 when the
  server refuses or faults) and evaluates each share's security descriptor (`ShareSecurity.kt`)
  only when every ACE names Everyone or the null SID, so the answer holds for any account. macOS
  grants Everyone `0x00120089` on a read-only share; Samba sends no descriptor to non-admins and
  Windows usually refuses level 502 to them (both: unknown, the tree answer stays).
- Folder and file level: the CREATE that lists the folder (or reads the text file) also asks for
  FILE_ADD_FILE (FILE_WRITE_DATA on a file). Granted: writable, at no extra cost. Refused with
  ACCESS_DENIED / MEDIA_WRITE_PROTECTED: read-only, and the CREATE is sent again without it (one
  extra round trip, only on read-only items; a folder found read-only is remembered per session
  for 60 s so listing it again costs nothing). The handle is only read from and closed; `stat`
  of the folders and files is identical before and after on macOS and on Samba (on a real
  filesystem; inside a container whose `/srv` is the image's overlayfs, granted write opens
  bumped ctime once, an overlay effect).

**Text files** (`text/TextCodec.kt`, `text/TextFiles.kt`, 2026-11-09):

- **Encoding detection** (same rules as `src/lib/text/codec.ts`, both unit tested): a byte order
  mark wins (UTF-8, UTF-16 LE/BE); a NUL byte without one means binary (`NOT_TEXT`); valid UTF-8 is
  `utf-8`; anything else is `windows-1252`, decoded losslessly (the five bytes Windows leaves
  undefined map to the C1 controls) so a Latin-1 file saves back byte for byte. Saving uses the
  encoding the file was read with; the UI falls back to UTF-8 (and says so) when windows-1252
  cannot hold a newly typed character. Line endings are not touched natively: the UI detects
  LF/CRLF/CR and re-applies it on save (CodeMirror works with `\n`).
- **`truncate`** reads `maxBytes + 1` bytes so the cut can step back to a character boundary.
- **Atomic save**: check the remote last write time against `expectedModifiedAt` (`CONFLICT`
  with `data.modifiedAt`, null when the file is gone), write a dot-named `.<name>.hoard-<id>.tmp` (no HIDDEN attribute: attributes travel with the rename) in
  the same folder (FILE_CREATE, no sharing), check again, then rename it over the target
  (`FileRenameInformation` with ReplaceIfExists). A dropped connection leaves at worst the temp
  file, never half a document. If the share refuses creating files, or the target is held open
  without delete sharing (rename → ACCESS_DENIED), it falls back to overwriting in place. The
  returned entry is read after every handle is closed, because servers update the last write
  time on close and that value is the next `expectedModifiedAt`. Known limits: the conflict check
  and the rename are not one atomic step (a save landing in between is overwritten); servers that
  report no modified time cannot detect conflicts; the replaced file takes the temp file's
  attributes and ACL (a file's custom ACL is not copied).
- `mode: "create"` writes a new file with FILE_CREATE (`ALREADY_EXISTS` when taken): new text
  file from the browser, and "save as a copy" in the conflict dialog.

**Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE` (network callback, WorkManager constraint),
`CHANGE_WIFI_MULTICAST_STATE` (mDNS while discovering), `FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_DATA_SYNC` (transfers), `POST_NOTIFICATIONS` (asked once, at the first
download; transfers run without it). `NEARBY_WIFI_DEVICES` is not needed: it guards Wi-Fi scanning,
Wi-Fi Direct and Aware, not NSD. Android 17 is expected to gate local-network access behind a new
permission for apps that target it; revisit when the target SDK moves past 36.

**Free space.** Before a new download the worker compares the file size with the free space of
the destination: from the provider's roots when readable, otherwise `fstatvfs` on the document it
just created (external storage only answers the roots query to holders of `MANAGE_DOCUMENTS`).

**System bars.** Capacitor's SystemBars paints the strips behind the status and navigation bars
with the window background and picks the icon color from the _system_ night mode. Hoard has its own
light/dark choice, so a second local plugin, `SystemUi` (`dev/hfps/hoard/SystemUiPlugin.kt`,
`src/plugins/systemUi.ts`, driven by `src/lib/theme/systemBars.ts`), sets the bar background and
icon contrast from the app's resolved theme. It is not part of the HoardSmb contract.

**Release builds** use R8 (`minifyEnabled true`); the rules in `proguard-rules.pro` keep smbj, its
event bus and the Capacitor plugin methods. Verified on an emulator for 1.0.0: connect (GUID +
Keystore), srvsvc shares, browse, image and video thumbnails, NSD discovery, open (FileProvider)
and a WorkManager download into a SAF folder. Signing: `bun run android:release` (see README).

**Testing.** `./gradlew testDebugUnitTest` runs the pure logic tests (error mapping, paths, conflict
names, failover, throttling, resume rules, srvsvc encoding, text encodings). Two opt-in tests need a network and are
skipped by default: `SmbSmokeTest` (`HOARD_SMOKE_HOSTS=host:port,...` reads each server GUID with
an unauthenticated NEGOTIATE) and `SmbIntegrationTest` (`HOARD_SMOKE_SMB=host:port:user:pass`
against the disposable Samba server in `docs/testing/samba/`).

## Consequences

- iOS would need its own native implementation (Swift; SMB libraries are scarcer there). Not
  planned now.
- The UI can be built and reviewed without a phone or an SMB server (`bun run dev`), and the native
  work can proceed in parallel against a fixed contract.
- Capacitor plugin calls are asynchronous JSON; listing a folder with thousands of entries is one
  call returning plain objects, which is fine at that scale.
