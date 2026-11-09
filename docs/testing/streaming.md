# Streaming and the local web server: end-to-end run

- **Date**: 2026-11-09
- **Build**: debug APK of this branch
- **Device**: Android emulator `hoard-stream`, API 36.1 (Android 16) arm64, Google Play image
- **Players**: VLC for Android 3.7.1 (official VideoLAN APK, arm64, sha256 checked), Google Photos,
  Chrome (preinstalled)
- **Server**: `docs/testing/samba/Dockerfile` (256 MB / 1 CPU) on `127.0.0.1:14460`, reached from the
  emulator as `10.0.2.2:14460`, with a 544,396,529-byte H.264 MP4 (10 min, `testsrc` with a seconds
  counter, keyframe every 2 s, moov at the front), a 7 MB MP3 and a small site (`index.html`,
  `css/site.css`, `js/app.js`, `img/banner.png`, `docs/page2.html` using `../css/site.css`).

Bytes "sent by the server" are the Samba container's `eth0` TX counter (`/proc/net/dev`) before and
after, so they include SMB headers: an upper bound of what Hoard read.

## Results

| Check                                                                          | Result                                                                                                          |
| ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| Tap a 519 MB video → chooser appears immediately (no download)                 | Pass ([1](../screenshots/android/streaming/1-chooser.png))                                                      |
| VLC plays the `content://` stream                                              | Pass, playing ~1 s after "Just once" ([2](../screenshots/android/streaming/2-vlc-playing.png))                  |
| Seek forward in VLC                                                            | Pass: 7:12, frame counter 433 ([3](../screenshots/android/streaming/3-vlc-seeked.png))                          |
| **Bytes read vs file size**                                                    | **29.4 MB sent by the server for a 544.4 MB file** (start, ~12 s of playback, seek to 7:12, ~6 s of playback)   |
| Longer session (play, seek to 7:33, seek back to 1:43)                         | Pass; Hoard's counters on close: `fetched=56,624,369 served=55,481,585` of 544,396,529 (read-ahead overhead 2%) |
| Google Photos plays and seeks the same stream                                  | Pass (9:37)                                                                                                     |
| Audio (MP3) in VLC                                                             | Pass                                                                                                            |
| `transport: "http"` URL in VLC, with a seek                                    | Pass (6:03)                                                                                                     |
| Samba restarted mid-playback                                                   | Pass: one failed read (`DiskShare has already been closed`), reconnect, VLC continued without an error          |
| Foreground service while VLC is in front                                       | Pass: `StreamService` `isForeground=true types=dataSync`, Hoard at oom adj 200 (perceptible), never frozen      |
| Notification permission asked on the first stream; "Streaming" notification    | Pass ([7](../screenshots/android/streaming/7-notification.png))                                                 |
| Notification "Stop"                                                            | Pass: token ended, service stopped                                                                              |
| `stopStreams`                                                                  | Pass: all tokens ended, server closed its port, service stopped                                                 |
| Tap `index.html` → in-app viewer, relative CSS / JS / image                    | Pass ([4](../screenshots/android/streaming/4-web-viewer.png))                                                   |
| Link to `docs/page2.html` (stylesheet via `../css/`)                           | Pass ([5](../screenshots/android/streaming/5-web-viewer-subfolder.png))                                         |
| Toolbar "Open in browser" → Chrome renders the same page (Hoard in background) | Pass ([6](../screenshots/android/streaming/6-chrome.png))                                                       |
| Server listens on loopback only                                                | Pass: `/proc/net/tcp6` shows `::ffff:127.0.0.1:<port>` only                                                     |

### Server checks (curl through `adb forward`)

| Request                                                 | Answer                                                                  |
| ------------------------------------------------------- | ----------------------------------------------------------------------- |
| `/<token>/index.html`, `css/site.css`, `img/banner.png` | 200, `text/html; charset=utf-8`, `text/css; charset=utf-8`, `image/png` |
| `/<token>/docs` (folder, no slash)                      | 301 → `/<token>/docs/`                                                  |
| `/<token>/docs/` (no `index.html`)                      | 200, listing                                                            |
| `Range: bytes=0-9`                                      | 206, `Content-Range: bytes 0-9/289`, 10 bytes                           |
| `Range: bytes=99999-`                                   | 416                                                                     |
| `/<token>/../index.html`, `%2e%2e/…`, `..%2f…`          | 404                                                                     |
| Wrong token, no token                                   | 404                                                                     |
| `Host: evil.example:<port>`                             | 403                                                                     |
| `POST`                                                  | 405                                                                     |

## Known limitations

- VLC 3.7 queries only `_data` from the provider and titles the stream `fd://NN` / "Unknown".
- Native strings (notification, viewer toolbar) follow the system language, like the transfer
  notifications.
- Not tested on a physical phone or on Android < 8 (HTTP fallback).
