<img src="docs/img/icon.svg" alt="" width="96" height="96">

# Hoard

An SMB file client for Android. Save your **machines** once, each with every address it answers on (local network IP, Tailscale IP or MagicDNS name), and Hoard picks the one that is reachable right now. Browse shares and folders, pin favorite folders, and copy files to the phone's storage.

Free and open source (AGPL-3.0-or-later). The whole UI is built and runs in the browser against an in-memory SMB mock; the native Kotlin plugin is being written against the same contract.

| Phone, light                                                   | Phone, dark                                                           | Desktop                                                              |
| -------------------------------------------------------------- | --------------------------------------------------------------------- | -------------------------------------------------------------------- |
| ![Home](docs/screenshots/home-phone-light.png)                 | ![Browser with thumbnails](docs/screenshots/browse-phone-dark.png)    | ![Machines](docs/screenshots/machines-desktop-light.png)             |
| ![Machine form](docs/screenshots/machine-form-phone-light.png) | ![Transfers drawer](docs/screenshots/transfers-drawer-phone-dark.png) | ![Worst-case folder](docs/screenshots/browse-worst-desktop-dark.png) |

More in [`docs/screenshots/`](docs/screenshots/) (every screen at 412×915 and 1280×800, light and dark).

## Features

- **Machines** with an ordered list of addresses (drag handle or Move up/down buttons, optional port). The address kind (local, Tailscale, other) is detected live from the host and local addresses sort first. New machines are prefilled from SMB servers found on the network (mDNS). **Test connection** tries every address and reports reachability, latency, sign-in and whether it is the same server. Passwords go only to the Android Keystore (`setCredentials`); on edit an empty password keeps the saved one.
- **Automatic route detection** ([ADR 0002](docs/adr/0002-machines-addresses-route-detection.md), `src/features/route/engine.ts`): every address is probed in parallel, the first reachable one in priority order wins as soon as that is certain, then Hoard connects with the pinned **server GUID**. An address answered by another server (`GUID_MISMATCH`) is skipped like an unreachable one; the GUID is pinned on the first connect and can be cleared ("Re-pin"). Sessions are reused and reopened when they expire; routes are rechecked when the app resumes and when the network changes. **The route in use is always visible** ("Local · 192.168.1.155 · 8 ms") on the home screen, the machine list, the sidebar and the browser header.
- **Browser**: shares, folders and files with a breadcrumb trail, Android back goes up a folder (or leaves selection mode first). Sort (name, size, date; folders first), filter by kind, search inside the folder, hidden files on demand. Card rows on phones, a table on desktop, virtualized for folders with thousands of entries. Image **thumbnails** load lazily for visible rows (4 at a time, cached). Tap to open with any app; row menu or long press for download, rename, delete (recursive for folders, confirmed), copy path, add to favorites; multi-select for batch download and delete. Every error code has its own state and next step (wrong password → edit credentials, different server → explanation, unreachable → retry with the routes tried).
- **Favorites**: pin any folder or share; shown on the home screen with the machine's route and in the sidebar; rename, remove and reorder.
- **Transfers** from SMB to a phone folder chosen with the Storage Access Framework (the last one is remembered), with a conflict policy (keep both, replace, skip). Bytes stream natively, never through the WebView; the queue shows progress, speed, time left and the host in use (so a failover is visible), with cancel, retry (resuming where it stopped) and clear finished. On phones the queue opens in a bottom drawer from the top bar's progress ring; the full list is at `/transfers`. The app reconciles with the native service on start and resume.
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
```

Android builds need the Android SDK (`~/Library/Android/sdk`) and JDK 21; the scripts use Android Studio's bundled JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

In the browser, data lives in a sql.js database saved to `localStorage`; run `localStorage.removeItem("hoard.devdb")` in the console to reseed the demo machines.

## Project layout

```
src/
  plugins/smb/        HoardSmb plugin: definitions.ts (the contract), index.ts (registerPlugin),
                      web.ts + mockData.ts (in-memory mock used by the browser and the tests)
  db/                 Db interface, Capacitor and sql.js adapters, migrations, queries, demo seed
  lib/                addressKind, pickRoute, files (kinds, sort, filter), format, taskQueue, theme
  features/route/     reachability engine (route + session per machine), provider and status UI
  features/browse/    folder loading, file rows, toolbar, thumbnails
  features/machines/  address editor, host parsing, test connection, delete
  features/transfers/ transfer service (progress, reconcile), download dialog, queue page and drawer
  features/settings/  typed settings with defaults
  components/         AppShell, AppSidebar, Page + breadcrumbs, ResponsiveDialog, SortableList, ui/ (shadcn)
  routes/             one component per route (router.tsx wires them)
  i18n/               i18next setup, locales/en.json, locales/pt-BR.json
android/              Capacitor Android project; app/src/main/java/dev/hfps/hoard/smb/ is the native plugin
docs/adr/             architecture decisions
```

## License

[AGPL-3.0-or-later](LICENSE).
