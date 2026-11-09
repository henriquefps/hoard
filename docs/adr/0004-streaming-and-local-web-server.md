# ADR 0004: Streaming to other apps and a local web server for HTML folders

- **Date**: 2026-11-09
- **Status**: Accepted

## Context

Until 1.0.0, `openFile` downloaded the whole file to the cache before handing it to another app.
A 2 GB video took minutes before VLC could start. The owner's previous app streams: VLC starts at
once and can seek. That app also turns the folder of an opened `.html` file into a small web
server, so the page's relative CSS, JS and images load. Hoard needs both.

## Decision

### Streaming (`streamFile`)

- **Default transport: a seekable `content://` URI** (`StreamProvider`,
  `content://dev.hfps.hoard.stream/<token>/<name>`). `openFile` in the provider returns
  `StorageManager.openProxyFileDescriptor` (Android 8+): the kernel turns the other app's
  `read`/`pread`/`lseek` into `onRead` calls on a thread of ours. Every player and every non-media
  app accepts a content URI with a read grant; nothing listens on a port; the grant is scoped to the
  app the user picked. `getType` and `query` (`DISPLAY_NAME`, `SIZE`) answer from the token.
- **HTTP is available too** (`transport: "http"`, `http://127.0.0.1:<port>/<token>/<name>` with
  `Range`), for players that only take URLs, and used automatically below Android 8. It is not the
  default: an HTTP URL can be opened by any app on the phone that learns it (the token is the only
  protection), needs the server running, and some players treat HTTP as a network stream (smaller
  caches, no "file" features). VLC and Google Photos both play and seek the content URI.
- **Reads**: `RemoteReader` per FD (or per HTTP response): its own SMB file handle and a read-ahead
  pipeline (`PipelinedFileReader`, 1 MiB READs). A seek starts the pipeline at depth 1 (a lone
  small read such as an MP4 `moov` atom or a Matroska cue table costs one round trip, not four);
  after two sequential chunks it deepens to 4 in flight. Forward jumps under 2 MiB read through.
- **Connection**: like transfers, the stream has its **own** SMB connection (`SmbSource`), built
  from `machineId` + `addresses` + `expectedServerGuid`, not the UI's `sessionId`: the UI may
  disconnect, the WebView may be gone while VLC plays. A failed read reconnects (GUID re-checked
  before credentials), moving to the next address when the link is dead, up to 4 attempts per read;
  only then does the player get `EIO`.
- **Tokens**: 256 random bits (hex), constant-time compared, one per opened file. A token ends 15
  minutes after its last use, never while a descriptor or a response is open (VLC paused for an
  hour keeps its stream). Ending a token revokes the URI grant and closes its connection.
  `stopStreams` and the notification's Stop end all of them.
- **What streams**: video and audio always; any other file from 32 MiB. Smaller files keep
  `openFile` (download to the cache): document and image viewers often read the whole file several
  times or expect a real file, and a cached copy reopens instantly. "Open with…" in the row menu
  always shows the system chooser (both paths take `chooser: true`).

### Local web server (`openWebPage`)

- `LocalWebServer`: a ~300-line HTTP/1.1 server written for Hoard (no dependency: NanoHTTPD is
  unmaintained since 2019 and we need very little), bound to **127.0.0.1 only**, random port, a
  bounded thread pool, keep-alive, `GET`/`HEAD`, single-range `Range` (206/416), directory →
  `index.html` or a plain listing.
- Every URL is `/<token>/<path>`; the token maps to the HTML file's folder, and paths resolve only
  below it: `..`, `.`, encoded `/` or `\`, control characters and characters Windows forbids are
  404 (`WebPaths`). The `Host` header must be the loopback address (no DNS-rebinding access from a
  web page), and every response has `Referrer-Policy: no-referrer` so the token does not leak to
  other sites the page links to.
- **Viewer**: a native `WebViewerActivity` with its own WebView. The app's Capacitor WebView is
  `https://localhost`, where an `http://127.0.0.1` iframe would be blocked as mixed content, and a
  page with arbitrary JS should not run inside the app's origin (which has the plugin bridge)
  anyway. Links inside the served folder stay in the viewer; other sites, `mailto:` and downloads go
  to the system. The toolbar's "Open in browser" hands the same URL to Chrome. While open, the
  viewer holds the token. The network security config allows cleartext only to `127.0.0.1` and
  `localhost`.
- A served page ends 30 minutes after its last request once no viewer holds it; the server stops
  with its last page.

### Staying alive while another app is in front

Android freezes cached apps (Android 14+), and a frozen Hoard would hang every read of the proxy FD
or HTTP request. `StreamService` is a **foreground service** (`dataSync`, like transfers: it moves
file data to another app) with a "Streaming from Hoard" notification listing what is served and a
Stop action. It starts with the first token, always from a tap in the app (so the background-start
restrictions do not apply), and stops with the last one. Android 15+ caps `dataSync` at 6 hours a
day; `onTimeout` ends the streams. Swiping Hoard away keeps only streams in use. The notification
permission is asked once per run on the first stream, like on the first download.

## Consequences

- Opening a video costs a connect + one stat; VLC starts in about a second on a LAN and reads only
  what it plays (measured: 29 MB of a 544 MB file for 15 s of playback plus a seek to 7:12).
- Contract: `streamFile`, `openWebPage`, `stopStreams`, `StreamSourceOptions`, `StreamTransport`,
  `chooser` on `openFile`. No new error codes.
- VLC 3.7 asks the provider only for `_data` (a path) and titles the stream "fd://…" / "Unknown"; a
  stream has no path to give. The `title` extra helps players that read it.
- Below Android 8, content streaming is unavailable and the HTTP URL is used instead.
