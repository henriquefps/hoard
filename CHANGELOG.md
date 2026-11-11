# Changelog

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
