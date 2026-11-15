# Changelog

## 2.2.0

- **Your share's photos are your server's photos** ([ADR 0022](docs/adr/0022-smb-folders-on-the-server.md)). When a folder of an SMB share is also one of hoard-server's NAS folders (the same files), Hoard recognises it the first time you open a folder there, by reading two of its photos (2 MB each), and from then on knows which files the server holds by their path, size and date. Needs hoard-server 0.10.0.
  - Files and the culling grid show the **on the server** mark (the cloud of the Gallery) on those photos.
  - **Culling** on that share also goes to the server, for the same photos as in the Gallery: Reveal and the web gallery see your picks and stars, and theirs show up in Hoard.
  - **Edit in Reveal** sends those photos as server photos (Reveal 0.9.0 or later reads them from the server), and the rest as below; one hand-off can have both.
  - Settings › Photo server › **Mapped folders** lists what Hoard recognised, with **Forget**.
  - Folders the server does not have work exactly as before.
- **Edit in Reveal copies instead of streaming** ([ADR 0010](docs/adr/0010-edit-in-reveal.md), "Local copies, no streaming for new hand-offs"). Each photo now reaches Reveal one of three ways: from the server (when the server holds it, as above), as the photo itself when it is already on the phone, or, for anything else (a share the server does not have, a USB drive, an SD card), as a **copy** Hoard makes first in **Pictures/Hoard/<batch title or date>/**. The copy goes through Transfers, with its progress on screen and Cancel (which removes the partial copies); editing the same photo again reuses its copy while the original is unchanged. Reveal gets a plain read permission on those files: no more links that depend on Hoard and the network, no writable files. CR3 files, which Android does not accept as pictures, go to **Download/Hoard/** instead ([test report](docs/testing/mediastore-raw.md)). Links Hoard gave Reveal before keep working.
  - Settings › **Photos copied for Reveal** shows how many copies there are and how much space they take, with **Delete copies** (Reveal then shows its own previews of those photos). Nothing is deleted automatically.
- **XMP files only when you ask** ([ADR 0011](docs/adr/0011-culling-sidecars.md), "Amendment 2.2.0"). Hoard no longer writes `.xmp` files on its own: not while culling, not with "Copy picks", not for Reveal. The "Save ratings next to photos (XMP)" setting is gone and is off for everyone. The new **Export XMP** (Files: a selection or a folder; culling: "Export XMP of decided shots") writes your stars, picks and rejects into the sidecars next to the photos, merging into existing ones and never touching one that is not valid XMP; for a read-only folder it asks where to put them. Sidecars written by Lightroom and others are still read when a folder opens.
- **Edit in Reveal, big batches**: when a hand-off needs several intents (over 500 server photos), each now says which one it is (`BATCH_INDEX`) and how many there are (`BATCH_COUNT`), so Reveal can tell they belong together ([ADR 0010](docs/adr/0010-edit-in-reveal.md)).

## 2.1.0

- **Gallery tab** ([ADR 0021](docs/adr/0021-gallery.md)): photos on the phone and on hoard-server in one grid, with culling everywhere (decisions are shared with the server and the web gallery), edited previews from Reveal, a pairing flow (QR code) and Settings › Photo server. Photos on the server open in Reveal by reference (`CULLING` version 2 for Reveal 0.9.0 and later; [ADR 0010](docs/adr/0010-edit-in-reveal.md), "Server items"); SMB files still go as links. Needs Reveal 0.9.0 or later for server references ([test report](docs/testing/gallery/README.md)).

## 2.0.2

- **Fix: one app holding photos open could make every other open fail** ([ADR 0010](docs/adr/0010-edit-in-reveal.md), "Idle reclaim"). Since 2.0.1 Hoard reads at most 6 network photos for other apps at once, and a large photo (every RAW) kept its place until the app reading it closed it. An app that forgot to close them took all 6 places, and every later photo got the "busy" answer until Hoard was restarted. Now a photo nobody has read for 12 s gives its place back; if the app reads it again, it gets a place again (waiting up to 1.5 s, as an open does). A photo left untouched for 10 minutes is closed on Hoard's side, so the share does not keep files open for it. The "busy" contract with Reveal is unchanged. In a test where one app opened 20 RAW photos and never closed them, another app then opened all 60 of its photos within 9 s; with 2.0.1 it got none ([test report](docs/testing/handoff-burst.md)).
- Screenshot tests show the same dates on any day: "today" and "yesterday" no longer depend on when the tests run.

## 2.0.1

- **Fix: Reveal opening many photos at once could freeze the phone** ([ADR 0010](docs/adr/0010-edit-in-reveal.md), "Bursts of opens"). When Reveal started, it reopened every photo Hoard had handed it at the same time. Each photo from a network share went through Android's storage service, and hundreds of them at once could stall that service until Android restarted itself. Hoard could also crash, which closed Reveal too. Now Hoard serves at most 6 of those photos at a time; an open that finds them all busy waits up to 1.5 s, then gets a "busy" answer (`FileNotFoundException` starting with `busy:`, the contract with Reveal, which retries). Sidecars and smaller photos (up to 8 MB) are kept in a private copy, so reopening them skips Android's storage service entirely. A machine that cannot be reached makes the remaining photos of the burst fail straight away instead of each one waiting to connect. In a test with 200 opens at once on an emulator, Hoard no longer crashes and the storage service stays responsive ([test report](docs/testing/handoff-burst.md)).
- Photos on the phone were already cheap to reopen and are unchanged.
- The log has one line per burst of opens instead of one per photo.

## 2.0.0

**Hoard 2: the native app replaces Hoard 1** ([ADR 0019](docs/adr/0019-native-app.md)). Hoard is now a Kotlin + Jetpack Compose app, rebuilt screen by screen at parity with 1.11 (Files, culling, assisted culling, sidecars, transfers, viewers, Edit in Reveal, settings), with a built-in image viewer and a Gallery tab on the way. It installs **over** Hoard 1 (same app, same signing key):

- **Your data comes with you.** The first launch imports Hoard 1's database once: machines and their addresses, favorites, culling decisions, analyses and sidecar records, transfer history and settings, including theme, palette, language and the text viewer's preferences. Hoard 1's file is only read; a failed import changes nothing and is tried again on the next launch.
- **Passwords keep working**, without typing them again: they stay in the phone's keystore, which the same app keeps reading.
- **Reveal's links keep working**: photos handed to Reveal from a network share still reopen.
- **Home-screen icons and shortcuts** pinned from Hoard 1 still open Hoard.
- **Hoard Next** (the 2.0.0-beta.1 test build) is no longer needed: Hoard 2.0.0 does nothing about it, so uninstall it yourself. Your data is in Hoard; whatever you did only in Hoard Next stays there.
- Gone with the WebView app: the browser build and its demo data (the web gallery is hoard-server's).
- Requires Android 7.0 or later, as before.

## 1.11.0

- **Import bridge for Hoard Next** ([ADR 0019](docs/adr/0019-native-app.md)): Hoard can hand a read-only snapshot of its data (machines, favorites, transfers, culling decisions and analyses, settings) to **Hoard Next**, the native Hoard 2 test build installed next to it. Only an app signed with Hoard's own key can ask (a signature permission, plus a check of the calling app); nothing in Hoard changes, and no password leaves it: Hoard Next asks for each machine's password once.

## 1.10.1

- **✕ on a burst rejects the whole burst**: in the culling viewer, on a burst shown as one item (with "View burst (N)"), ✕ — the button, `X`, or swiping down when swipe-to-decide is on — now rejects every frame of the burst, in one step that Undo reverts (toast "Rejected N photos of the burst" with Undo). Stars are kept, and each frame's sidecar is written as usual. ✓ still picks only the frame shown, "View burst" → "Keep this one" is unchanged, and inside the burst ✕ still rejects only the frame on screen.
- **Fix: broken thumbnails after culling a large folder**: after culling a few hundred photos, reopening the folder showed the broken-image icon on most grid tiles. Thumbnails shared a disk cache with the viewer's screen images, so culling pushed them out while the grid still pointed at them. They now have their own cache (64 MB, about 2,000 or more thumbnails). A tile whose image fails to load asks for it again once; if it fails again, the tile stays grey instead of showing a broken image. XMP sidecars had nothing to do with it, and they never affect thumbnails or how photos are paired (now tested).

## 1.10.0

- **Edit in Reveal: photos stay reopenable** ([ADR 0010](docs/adr/0010-edit-in-reveal.md)): every hand-off now gives Reveal a _persistable_ read grant, for photos on the phone, on drives and on SMB shares. Photos from a network share go as stable links that Hoard serves itself: Reveal can reopen them later, after the phone restarts and with Hoard closed, while the machine is reachable (before, they stopped working an hour after the last read or when Hoard closed: "Permission Denial" in Reveal). Hoard starts in the background to serve them, connects on its own (the address that last worked first, the pinned server checked before the password is sent), shows the "Streaming" notification while Reveal reads, and stops two minutes after. An unreachable share makes Reveal show its own preview.
- **Ratings come back from Reveal**: with "Save ratings next to photos (XMP)" on, each shot's sidecar goes with the photos as a writable item (the existing one, or a new `<stem>.xmp` holding Hoard's decision), in folders Hoard can write. A rating, pick or label set in Reveal is written into it (on SMB atomically, through Hoard), and Hoard imports it when the folder opens for culling. Photos are never writable; read-only folders send no sidecar.
- **No more double "Reveal" chooser**: with both Reveal and its debug build installed, Hoard opens the release one directly.
- **Settings › About › Reveal links**: how many files Reveal can reopen, and **Revoke all**. Links unused for 180 days are forgotten.
- The note before the first hand-off from a network share says what is now true.

## 1.9.2

- **Culling ignores hidden files**: macOS AppleDouble copies (`._DSC07845.ARW`, ~4 KB metadata files a Mac leaves on non-HFS disks and shares), `.DS_Store` and other dot-files no longer become shots. A folder of 14 RAW+JPEG pairs with 28 `._*` files opened 28 shots (14 grey tiles, "14 photos could not be analysed"); it now opens 14. The same rule applies to "Cull photos" counts, "Edit in Reveal" and the web mock. Hidden XMP sidecars (`.name.xmp`) are still read. The file browser is unchanged.

## 1.9.1

- **Compact, collapsing page header** (landscape phones and short screens): scrolling a list down moves the page header (breadcrumbs, title, status line) and its toolbar out with the content; a compact bar stays on top with ←, the folder name on one line and the page's actions as icons (search expands into a field, one ⋯ menu holds filter, sort, new folder, refresh and the page's own items). Scrolling up brings the full header back at once (quick-return); it follows the finger, nothing animates by itself. Same behaviour on every page with this header: machine, phone and USB folders, the culling grid (its filter rows and analysis progress collapse too), machines, transfers, settings.
- **Breadcrumbs on one line**: the tail that fits (`… › crucial › Images`), the earlier levels behind "…"; the current folder is no longer repeated above the title. The ← back button shows at every width.
- **One meta line**: connection, address, latency and the Read-only badge share one line under the title.
- **Short screens** (viewport ≤ 500 px tall, e.g. a phone in landscape): the 56 dp top bar goes away and the sidebar becomes an icon rail (logo on top, transfers and theme at the bottom, tooltips, same items and active state) instead of the full pinned sidebar; labelled header buttons ("Cull photos", "Start culling", "Add machine") become icon buttons; the culling filters fit on one line. In a deep folder at 900×415 the file list went from 3 % of the screen to 51 % at the top and 84 % after scrolling (390×844 portrait: 58 % → 66 % and 86 %).
- The browse list's item count moved to the end of the list; while selecting, the selection bar stays above the scrolling header.

## 1.9.0

- **Culling ratings as XMP sidecars** ([ADR 0011](docs/adr/0011-culling-sidecars.md)): in folders Hoard can write (phone storage, SD card, USB drive or camera, SMB shares that take writes) every pick, reject and star rating is written into the shot's XMP sidecar next to the photos (`<stem>.xmp`, shared by a RAW + JPEG pair): `xmp:Rating` (−1 for a reject) and `digiKam:PickLabel`, merged into an existing sidecar (Lightroom's develop settings, darktable's history, Reveal's recipe, keywords stay), never over a malformed one, atomically, coalesced and written at once when the app leaves the screen. Sidecars named `<file>.xmp` (darktable) or hidden (`.name.xmp`) are found and updated in place.
- Ratings made in Reveal, Lightroom, darktable or digiKam show in Hoard: a folder's sidecars are read when it opens for culling and again when the app comes back to the front; a sidecar changed after Hoard last wrote or decided wins. Opening a folder never writes.
- Read-only folders (read-only shares and volumes) keep decisions in Hoard only, with a discreet note on the culling page; a sidecar that cannot be written shows one toast with the count.
- **Edit in Reveal** sends the decisions with the photos (Reveal's `CULLING` extra), from every folder, so ratings arrive in Reveal even from read-only shares.
- **Copy picks** writes the same sidecar at the destination (stars and pick), merging into one already there.
- Settings › Culling: **Save ratings next to photos (XMP)** (on by default; replaces the Copy picks switch) and **Write XMP as hidden file**.
- Database v7 (`cull_sidecars`). Fix: two database writes from different parts of the app at once could fail with "Already in transaction".

## 1.8.0

- **Edit in Reveal** ([ADR 0010](docs/adr/0010-edit-in-reveal.md)): when Reveal, the RAW editor, is installed, photos go to it straight from Hoard with its hand-off contract (`dev.hfps.reveal.action.DEVELOP`): the culling viewer's current shot, all the picks of a folder or the shots the grid shows, and a browser selection of photos. RAW + JPEG pairs always travel together; only the photos sent are granted, read-only, and no sidecar is ever written. Phone and drive photos are handed over in place; photos on an SMB share stream through Hoard (readable while Hoard runs and for an hour after the last read; the app says so first). Big batches (over 200 files) ask first. Nothing shows when Reveal is not installed; installing or uninstalling it is noticed when Hoard comes back to the front.

## 1.7.2

- The app's header shows the new icon: the in-app mark (header and sidebar) still drew the old folder glyph. The splash drawable is now written by `build-icons.mjs --android` (hfps-repo-assets skill), replacing `scripts/splash-drawable.mjs`.

## 1.7.1

- New app icon: a files glyph (a sheet with a second sheet behind it) on the hfps blue, with Android adaptive and themed (monochrome) layers, the favicon set and the social preview. The editable source is `scripts/glyph.designer.json`; everything is rebuilt from `scripts/glyph.svg` with `build-icons.mjs` (hfps-repo-assets skill).
