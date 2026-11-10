# Changelog

## 1.7.2

- The app's header shows the new icon: the in-app mark (header and sidebar) still drew the old folder glyph. The splash drawable is now written by `build-icons.mjs --android` (hfps-repo-assets skill), replacing `scripts/splash-drawable.mjs`.

## 1.7.1

- New app icon: a files glyph (a sheet with a second sheet behind it) on the hfps blue, with Android adaptive and themed (monochrome) layers, the favicon set and the social preview. The editable source is `scripts/glyph.designer.json`; everything is rebuilt from `scripts/glyph.svg` with `build-icons.mjs` (hfps-repo-assets skill).
