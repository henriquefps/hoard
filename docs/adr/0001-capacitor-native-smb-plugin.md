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
- The contract is one TypeScript file, `src/plugins/smb/definitions.ts`, with a typed error code
  on every rejection. A complete **in-memory mock** (`src/plugins/smb/web.ts`) implements the same
  interface so the UI is developed and tested in a desktop browser.
- Rule: a change to the contract changes `definitions.ts`, `web.ts` and the Kotlin plugin in the
  same pull request.

## Consequences

- iOS would need its own native implementation (Swift; SMB libraries are scarcer there). Not
  planned now.
- The UI can be built and reviewed without a phone or an SMB server (`bun run dev`), and the native
  work can proceed in parallel against a fixed contract.
- Capacitor plugin calls are asynchronous JSON; listing a folder with thousands of entries is one
  call returning plain objects, which is fine at that scale.
