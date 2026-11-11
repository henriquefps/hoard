# Changelog

## 1.8.0

- **Edit in Reveal** ([ADR 0010](docs/adr/0010-edit-in-reveal.md)): when Reveal, the RAW editor, is installed, photos go to it straight from Hoard with its hand-off contract (`dev.hfps.reveal.action.DEVELOP`): the culling viewer's current shot, all the picks of a folder or the shots the grid shows, and a browser selection of photos. RAW + JPEG pairs always travel together; only the photos sent are granted, read-only, and no sidecar is ever written. Phone and drive photos are handed over in place; photos on an SMB share stream through Hoard (readable while Hoard runs and for an hour after the last read; the app says so first). Big batches (over 200 files) ask first. Nothing shows when Reveal is not installed; installing or uninstalling it is noticed when Hoard comes back to the front.

## 1.7.2

- The app's header shows the new icon: the in-app mark (header and sidebar) still drew the old folder glyph. The splash drawable is now written by `build-icons.mjs --android` (hfps-repo-assets skill), replacing `scripts/splash-drawable.mjs`.

## 1.7.1

- New app icon: a files glyph (a sheet with a second sheet behind it) on the hfps blue, with Android adaptive and themed (monochrome) layers, the favicon set and the social preview. The editable source is `scripts/glyph.designer.json`; everything is rebuilt from `scripts/glyph.svg` with `build-icons.mjs` (hfps-repo-assets skill).
