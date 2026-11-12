# Changelog

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
