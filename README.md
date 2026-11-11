<img src="docs/img/icon.svg" alt="" width="96" height="96">

# Hoard

A file manager for Android: the phone's own storage, USB drives and SD cards, and the shared folders of your computers over SMB, with copy, move and share between any of them. It also culls photo shoots (RAW, JPEG and RAW+JPEG pairs) straight from the card, with help spotting blurry shots, closed eyes, bad exposure and bursts, and copies the picks anywhere ([ADR 0008](docs/adr/0008-culling.md), [ADR 0009](docs/adr/0009-assisted-culling.md)). Save your **machines** once, each with every address it answers on (local network IP, Tailscale IP or MagicDNS name), and Hoard picks the one that is reachable right now.

Free and open source (AGPL-3.0-or-later). The whole UI is built and runs in the browser against an in-memory SMB mock; on Android the native Kotlin plugin (smbj, Storage Access Framework, WorkManager transfers) implements the same contract ([ADR 0001](docs/adr/0001-capacitor-native-smb-plugin.md)).

**Burst mode** (1.7.0, browser build with synthetic photos): tap a stack to choose within the burst ("Keep this one" picks the frame on screen and rejects the others, with undo), resolved and pending bursts in the grid, compact icons on the photo and the details in a sheet you swipe up, like the phone's gallery.

| Burst mode                                                  | Keep this one                                                     | Grid                                                             | On the photo                                              | Details                                                     |
| ----------------------------------------------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------- | --------------------------------------------------------- | ----------------------------------------------------------- |
| ![Burst mode](docs/screenshots/burst-mode/1-burst-mode.png) | ![Keep this one](docs/screenshots/burst-mode/2-keep-this-one.png) | ![Grid](docs/screenshots/burst-mode/3-grid-resolved-pending.png) | ![Chips](docs/screenshots/burst-mode/4-compact-chips.png) | ![Details](docs/screenshots/burst-mode/5-details-sheet.png) |

**Assisted culling** (1.6.0, browser build with synthetic photos): bursts stacked with their best frame crowned and problems marked, suggestions to accept or ignore, the viewer's analysis panel with a histogram, and the assist settings.

| Bursts and flags                                               | Suggestions                                                         | Analysis panel                                                 | Settings                                                      |
| -------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------- |
| ![Bursts](docs/screenshots/assist/grid-stacks-phone-light.png) | ![Suggestions](docs/screenshots/assist/suggestions-phone-light.png) | ![Analysis](docs/screenshots/assist/viewer-analysis-phone.png) | ![Settings](docs/screenshots/assist/settings-phone-light.png) |

**Culling** (1.5.0, browser build with synthetic photos): the grid with burst hints, the viewer with its camera data, 100% zoom, and compare.

| Grid                                                | Viewer                                            | 100%                                              | Compare                                             | Copy picks                                                |
| --------------------------------------------------- | ------------------------------------------------- | ------------------------------------------------- | --------------------------------------------------- | --------------------------------------------------------- |
| ![Grid](docs/screenshots/cull/grid-phone-light.png) | ![Viewer](docs/screenshots/cull/viewer-phone.png) | ![100%](docs/screenshots/cull/zoom-100-phone.png) | ![Compare](docs/screenshots/cull/compare-phone.png) | ![Copy picks](docs/screenshots/cull/copy-picks-phone.png) |

On Android (1.4.0, emulator, against a Samba server): Copy then "Paste here" on an SD card, the destination picker inside a share, a move to the NAS in progress, Hoard in another app's share sheet, and Save to Hoard.

| Clipboard                                                                | Destination picker                                                         | Transfers                                                                  | Share sheet                                                              | Save to Hoard                                                    |
| ------------------------------------------------------------------------ | -------------------------------------------------------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| ![Clipboard](docs/screenshots/android/transfers/clipboard-bar-light.png) | ![Picker](docs/screenshots/android/transfers/destination-picker-light.png) | ![Transfers](docs/screenshots/android/transfers/transfers-batch-light.png) | ![Share sheet](docs/screenshots/android/transfers/share-sheet-light.png) | ![Save to](docs/screenshots/android/transfers/save-to-light.png) |

On Android (1.3.0, emulator): phone storage, a removable drive, favorites and machines; the first-run permission screen; the drive-connected notice; internal storage with thumbnails.

| Home                                                                            | All files access                                                       | Drive connected                                                                | Internal storage                                                                   |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| ![Home](docs/screenshots/android/locations/home-phone-drive-machines-light.png) | ![Permission](docs/screenshots/android/locations/permission-light.png) | ![Notice](docs/screenshots/android/locations/drive-connected-notice-light.png) | ![Camera](docs/screenshots/android/locations/browse-internal-thumbnails-light.png) |

On Android (1.0.0, real app against a Samba server):

| Home                                             | Browser with thumbnails                                             | Machine, every address tested                                      | Transfers, dark                                                         |
| ------------------------------------------------ | ------------------------------------------------------------------- | ------------------------------------------------------------------ | ----------------------------------------------------------------------- |
| ![Home](docs/screenshots/android/home-light.png) | ![Thumbnails](docs/screenshots/android/browse-thumbnails-light.png) | ![Addresses](docs/screenshots/android/machine-addresses-light.png) | ![Transfers drawer](docs/screenshots/android/transfers-drawer-dark.png) |

More in [`docs/screenshots/android/`](docs/screenshots/android/). The browser build (in-memory mock):

| Phone, light                                                   | Phone, dark                                                           | Desktop                                                                  |
| -------------------------------------------------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| ![Home](docs/screenshots/home-phone-light.png)                 | ![Browser with thumbnails](docs/screenshots/browse-phone-dark.png)    | ![Machines](docs/screenshots/machines-desktop-light.png)                 |
| ![Machine form](docs/screenshots/machine-form-phone-light.png) | ![Transfers drawer](docs/screenshots/transfers-drawer-phone-dark.png) | ![Worst-case folder](docs/screenshots/browse-worst-desktop-dark.png)     |
| ![Markdown](docs/screenshots/text-markdown-phone-light.png)    | ![Code](docs/screenshots/text-code-phone-dark.png)                    | ![Markdown editor, split](docs/screenshots/text-editor-desktop-dark.png) |

More in [`docs/screenshots/`](docs/screenshots/) (every screen at 412×915 and 1280×800, light and dark).

## Features

- **Locations** ([ADR 0005](docs/adr/0005-locations.md)): the phone's internal storage and every USB drive or SD card are browsed like SMB shares, with the same features (sort, filter, search, thumbnails, multi-select, text editor, streaming to other apps, web viewer, favorites). The home screen lists **This phone** first (used/free bar per volume); drives appear and disappear live, with a "USB drive connected · Open" notice. Needs Android's **All files access** (explained on first launch, a Settings switch; Hoard is sideloaded, see the ADR for the Play Store implication); without it SMB keeps working and a drive can still be opened folder by folder through the system picker. `Android/data` and `Android/obb` are shown as not accessible (Android 11+). Ejecting happens in Android's storage settings (apps cannot eject). Copying and moving between any locations: see Transfers.
- **Photo culling** ([ADR 0008](docs/adr/0008-culling.md)): **Cull photos** in any folder with photos, on any location (a camera card in a USB reader, a NAS share, the phone). RAW + JPEG pairs are one shot (RAW-only and JPEG-only work too); a dense grid in capture order (EXIF with sub-seconds) with burst hints, filters (picked, rejected, not decided, stars) and progress. Full-screen viewer: swipe to page, swipe up to pick and down to reject, 1–5 stars, auto-advance, undo, filmstrip, camera data overlay, pinch or double tap to **100%** (region tiles; RAW-only shots rendered by LibRaw), and side-by-side **compare** with synchronized zoom and focus scores. The embedded preview shows first, the better image replaces it; the next shots are prepared natively, so paging lands on a ready image. Decisions are saved in the app (never next to the photos) and survive restarts. Finish: **Copy picks to…** any location (RAW + JPEG, RAW only or JPEG only; the last destination is remembered per source; copied by the transfer engine, with its progress and conflict questions) and **Delete rejects** (permanent, counted, refused cleanly on read-only folders).
- **Assisted culling** ([ADR 0009](docs/adr/0009-assisted-culling.md)): while a folder is open, every shot is analysed on the phone, offline, in the background (the shot on screen first, ~0.2 s each): sharpness of the face or the sharpest area, faces and closed eyes (ML Kit, bundled model), exposure with a histogram, and a similarity signature. Bursts are grouped by capture time and similarity (a scene change splits a run, near-identical frames a bit further apart join it) and **stacked** in the grid with their best frame crowned; marks for blurry, eyes closed, over- and under-exposed; sort by sharpness and filter by problem. **Suggest** proposes keeping the best frame of each burst and rejecting the rest and every flagged shot, with the reason ("Blurrier than DSC07705", "Eyes closed"); nothing changes until you accept all, a burst or a shot. Compare opens on a burst's top two; the viewer zooms to a face. Results are cached in the app, so reopening a card does not measure again. Sensitivity and burst gap in Settings › Culling. **Copy picks** can write **XMP sidecars** with the stars next to the copies (at the destination only) for Lightroom, darktable and other editors.
- **Machines** with an ordered list of addresses (drag handle or Move up/down buttons, optional port). The address kind (local, Tailscale, other) is detected live from the host and local addresses sort first. New machines are prefilled from SMB servers found on the network (mDNS). **Test connection** tries every address and reports reachability, latency, sign-in and whether it is the same server. Passwords go only to the Android Keystore (`setCredentials`); on edit an empty password keeps the saved one.
- **Edit in Reveal** ([ADR 0010](docs/adr/0010-edit-in-reveal.md)): with [Reveal](https://github.com/henriquefps/reveal), the RAW editor, installed, **Edit in Reveal** hands photos over from the culling viewer (the shot on screen), the culling page (all picks, or the shots the grid shows) and a browser selection, RAW + JPEG pairs together, read-only, in place for the phone and drives and streamed from SMB shares. Nothing shows without Reveal.
- **Automatic route detection** ([ADR 0002](docs/adr/0002-machines-addresses-route-detection.md), `src/features/route/engine.ts`): every address is probed in parallel, the first reachable one in priority order wins as soon as that is certain, then Hoard connects with the pinned **server GUID**. An address answered by another server (`GUID_MISMATCH`) is skipped like an unreachable one; the GUID is pinned on the first connect and can be cleared ("Re-pin"). Sessions are reused and reopened when they expire; routes are rechecked when the app resumes and when the network changes. **The route in use is always visible** ("Local · 192.168.1.155 · 8 ms") on the home screen, the machine list, the sidebar and the browser header.
- **Browser**: shares, folders and files with a breadcrumb trail, Android back goes up a folder (or leaves selection mode first). Sort (name, size, date; folders first), filter by kind, search inside the folder, hidden files on demand. Card rows on phones, a table on desktop, virtualized for folders with thousands of entries. Image **thumbnails** load lazily for visible rows (4 at a time, cached). Tap to open: videos, music and large files **stream** to the app you pick (VLC starts at once and can seek, nothing is downloaded first), `.html` pages open in a built-in viewer with their folder served by a local web server so relative CSS/JS/images load (and "Open in browser" hands them to Chrome), small files are cached then opened ([ADR 0004](docs/adr/0004-streaming-and-local-web-server.md)); "Open with…" always shows the chooser; row menu or long press for download, rename, delete (recursive for folders, confirmed), copy path, add to favorites; multi-select for copy, move, share and delete. Every error code has its own state and next step (wrong password → edit credentials, different server → explanation, unreachable → retry with the routes tried).
- **Text viewer and editor**: text files open in Hoard instead of another app (the row menu keeps "Open with another app"). Markdown renders with the pipeline from [markdown-preview](https://github.com/henriquefps/markdown-preview) (GFM tables and task lists, highlighted code, Mermaid, KaTeX, emoji, ==marks==); relative images load from the share as thumbnails and links to other `.md` files open in the viewer. Code and plain text (CodeMirror 6, only the visible lines are drawn, so 100,000-line logs scroll) have line numbers, highlighting, soft wrap, find, text size; JSON pretty-prints, CSV shows as a table. Edit mode: find and replace, Markdown editor / split / preview, save back with Ctrl/Cmd+S or the Save button (atomic on the server: temp file + rename), unsaved-changes guard on back and navigation, a conflict dialog when someone else saved meanwhile (reload theirs, overwrite, save as a copy), and New text file in the browser. Encodings are detected (UTF-8, UTF-16 with BOM, windows-1252) and kept on save, as are CRLF line endings. Files above 5 MB can be opened read-only, first 5 MB only. Small text is the one exception to "bytes never cross the bridge" ([ADR 0001](docs/adr/0001-capacitor-native-smb-plugin.md)).
- **Favorites**: pin any folder or share; shown on the home screen with the machine's route and in the sidebar; rename, remove and reorder.
- **Transfers between any locations** ([ADR 0007](docs/adr/0007-transfers-any-location.md)): copy or move files and whole folders between the phone, USB drives / SD cards, SMB shares (same or different machines) and folders Android grants. Multi-select → **Copy** / **Move** puts them on a clipboard (a bar at the bottom shows what is on it; navigate anywhere and **Paste here**), or **Copy to… / Move to… / Download / Upload** opens a destination picker (recent destinations, this phone, drives, favorites, machines and shares, new folder). One native engine: a scan with totals first, free-space check, a hidden partial file per file that is verified before it gets its name, modified times kept, moves inside one location are a single rename, moves elsewhere delete each source only after its copy is verified, server-side copy inside one SMB share. Conflicts: ask (one question with "do the same for the others"), keep both, replace or skip. Jobs survive the app being killed, a dropped connection (failover across the machine's addresses, resuming at the byte written, uploads included) and a drive pulled out (Retry runs only the failed files). The queue shows from → to, files and bytes, speed, time left, the current file and the host in use, per-file errors, retry, cancel and open destination; on phones it opens in a bottom drawer from the top bar's progress ring.
- **Share in and out**: **Share** on selected files opens Android's share sheet (local files in place; SMB files streamed, or cached first when small). Hoard is also a share target: files shared from any app open **Save to Hoard**, which saves them to any location, an SMB share included.
- **Settings**: theme and color palette, language, hidden files, default sort, default conflict policy, the folders Hoard may write to (release access), route check timeout, about. The browser build adds a simulated network switch (home, away on Tailscale, offline) and a worst-case data set (18 more machines, a 5,000-entry folder, unicode and very long names, a 1.4 TB file, a transfer that fails mid-way).
- English and Portuguese (Brazil).

## Stack

React 19, TypeScript, Vite, Tailwind CSS 4, shadcn/ui (`radix-vega`) in the hfps identity, Phosphor icons, Inter, TanStack Router, i18next, Capacitor 8 (Android), SQLite (`@capacitor-community/sqlite`, sql.js in the browser), Vitest, ESLint, Prettier, bun. Native SMB: Kotlin + [smbj](https://github.com/hierynomus/smbj) ([ADR 0001](docs/adr/0001-capacitor-native-smb-plugin.md)).

## Run and build

```sh
bun install
bun run dev          # browser, against the in-memory SMB mock and demo machines
bun run lint
bun run typecheck
bun run test
bun run build        # type-check + production bundle in dist/

bun run android:sync     # build + cap sync android
bun run android:debug    # sync + ./gradlew assembleDebug → android/app/build/outputs/apk/debug/app-debug.apk
bun run android:install  # debug build + adb install on the connected phone
bun run android:release  # sync + signed, R8-minified release APK → android/app/build/outputs/apk/release/app-release.apk
```

Android builds need the Android SDK (`~/Library/Android/sdk`) and JDK 21; the scripts use Android Studio's bundled JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

Native plugin tests (JVM) and the release build, from `android/` with that `JAVA_HOME`:

```sh
./gradlew testDebugUnitTest   # pure-logic tests; network tests are opt-in, see ADR 0001
./gradlew assembleRelease     # R8-minified release APK, unsigned unless the signing variables are set
```

**Release signing.** The release keystore (RSA 4096, alias `hoard`) is never in the repo: it lives in the
local `skv` vault, group `hoard` (`HOARD_KEYSTORE` as a file,
`HOARD_KEYSTORE_PASSWORD`, `HOARD_KEY_ALIAS`, `HOARD_KEY_PASSWORD`). `bun run android:release` runs Gradle under
`skv run hoard`, and `android/app/build.gradle` signs only when those variables are present; without them
Gradle warns and builds `app-release-unsigned.apk`. Check a build with
`apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk`. The keystore must be kept:
updates to an installed app only install when signed with the same key.

In the browser, data lives in a sql.js database saved to `localStorage`; run `localStorage.removeItem("hoard.devdb")` in the console to reseed the demo machines.

## Project layout

```
src/
  plugins/smb/        HoardSmb plugin: definitions.ts (the contract), index.ts (registerPlugin),
                      web.ts + mockData.ts + mockLocal.ts (in-memory mock used by the browser and the tests)
  features/locations/ location ids, BrowseLocation (one place for SMB / device / volume calls),
                      volumes provider + notices, phone storage cards, location error states
  db/                 Db interface, Capacitor and sql.js adapters, migrations, queries, demo seed
  lib/                addressKind, pickRoute, files (kinds, sort, filter), format, taskQueue, theme,
                      text/ (encodings, line endings, Markdown link resolution, CSV)
  features/text/      text viewer and editor route (lazy chunk): CodeMirror, Markdown, find
  features/route/     reachability engine (route + session per machine), provider and status UI
  features/browse/    folder loading, file rows, toolbar, thumbnails
  features/reveal/    Edit in Reveal (ADR 0010): handoff.ts (what is sent, pure, tested), RevealProvider
  features/cull/      culling (ADR 0008): model (pure, tested), folder hook, grid page, viewer, zoomable
                      image with tiles, compare, copy-picks and delete-rejects dialogs; assisted culling
                      (ADR 0009): assist.ts (flags, bursts, suggestions; pure, tested), useCullAssist,
                      xmp.ts (sidecars)
  features/machines/  address editor, host parsing, test connection, delete
  features/transfers/ transfer service (start, progress, reconcile, retry), clipboard, destination picker,
                      conflict dialog, queue page and drawer, incoming shares (Save to…)
  features/settings/  typed settings with defaults
  components/         AppShell, AppSidebar, Page + breadcrumbs, ResponsiveDialog, SortableList, ui/ (shadcn)
  routes/             one component per route (router.tsx wires them)
  i18n/               i18next setup, locales/en.json, locales/pt-BR.json
android/              Capacitor Android project; app/src/main/java/dev/hfps/hoard/smb/ is the native plugin
                      (sessions, srvsvc, credentials, NSD, SAF; transfer/ = WorkManager jobs; viewer/ = open + thumbnails;
                      stream/ = streaming provider, local web server, web viewer; location/ = HoardFs with
                      SMB, path and SAF implementations, volumes, volume events; transfer/ = the any-location engine
                      (TransferRunner over TransferFs endpoints); share/ = share in and out; cull/ = culling glue);
                      dev/hfps/hoard/image/ is the image core (ADR 0006: previews, decode, LibRaw, caches;
                      analysis/ = assisted-culling measurements and ML Kit faces, ADR 0009)
docs/testing/samba/   disposable Samba server for the opt-in native integration test
docs/adr/             architecture decisions
```

## License

[AGPL-3.0-or-later](LICENSE).
