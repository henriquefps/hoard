# ADR 0019: Hoard 2, a native app built next to the Capacitor app

- **Date**: 2026-11-14
- **Status**: Accepted. Done: phase 2a (foundations: core modules, Room schema = Capacitor v7, the one-time import,
  the Compose shell), phase 2b (the Files tab at parity), phase 2c (culling at parity, the Files gaps, the parity
  audit), phase 2d (the audit's "Partly" rows closed; **Hoard Next 2.0.0-beta.1**, a side-by-side release build with
  the import bridge of Hoard 1.11.0). Left: the Gallery tab (phase 3) and the swap.

## Context

Hoard 1.x is Capacitor 8 + React: the UI is a WebView (`src/`), the database is the JS SQLite of
`@capacitor-community/sqlite` (`src/db/migrations.ts`, schema v7), and most of the heavy work is already native Kotlin
in `android/app/src/main/java/dev/hfps/hoard/` (SMB over smbj, locations and `HoardFs`, the transfer engine, the image
core with LibRaw over JNI, assisted-culling measurements, XMP, Reveal hand-off, `StreamProvider`), reached through one
Capacitor plugin (`HoardSmbPlugin`, ADR 0001).

The owner decided to rebuild Hoard natively, in Kotlin and Jetpack Compose, with two tabs: **Gallery** (photos on the
device and on hoard-server, phase 3 and later) and **Files** (today's file manager, at parity in phase 2). Three
constraints shape the path:

1. **Nothing is lost.** Every screen is ported one by one, and the shipped app keeps working in the meantime.
2. **The owner's install upgrades in place.** Same application id, same signing key, same data directory: machines,
   favourites, culling decisions, the analysis cache, transfer history and the Keystore passwords all carry over.
3. **One copy of the native code.** The Capacitor app and the new app must not fork the SMB, transfer or image code.

## Decision

### One Gradle project, shared core modules, two apps

Today's Kotlin moves out of `:app` into library modules. Both the Capacitor app (`:app`, still the shipped app until
parity) and the new Compose app (`:native`) depend on them:

```
:app (Capacitor, ships today)          :native (Compose, Hoard 2)
   │  HoardSmbPlugin, SystemUiPlugin,     │  shell, Files / Gallery / Settings,
   │  CullAnalysisJson, manifest           │  first-launch import
   │  components (services, provider,      │
   │  share targets)                       ├──────────────┬──────────────────┐
   ▼                                       ▼              ▼                  ▼
:core:smb ◀───────────────────────────── (uses)      :core:data      :core:designsystem
   │  SMB (smbj), credentials (Keystore),               Room schema v7,   hfps theme, Inter,
   │  locations (HoardFs: SmbFs, PathFs, SafFs,        the Capacitor    Phosphor icons,
   │  volumes), transfer engine, streaming +            importer          components
   │  StreamProvider, share in/out, Reveal hand-off,
   │  culling's native side
   ├──────────────┐
   ▼              ▼
:core:image    :core:xmp
LibRaw + JNI   XMP read / merge / write
(src/main/cpp), (pure Kotlin)
decoders, caches,
analysis (ML Kit)
```

- **`:core:smb` is one module, not four.** The proposal had `:core:transfer` and `:core:handoff` apart from
  `:core:smb`. The packages import each other in cycles today: `transfer → share → transfer`, `stream ↔ handoff`,
  `location → stream → handoff → transfer → location`. Splitting them now means redesigning those seams in a PR whose
  promise is "no behaviour change". They stay packages in one module; a later PR can extract them behind interfaces
  when a screen port needs it.
- **Packages and class names do not change** (`dev.hfps.hoard.smb.*`, `dev.hfps.hoard.image.*`, `dev.hfps.hoard.xmp.*`).
  WorkManager rows persist worker class names, the JNI binds `LibRawNative` by name, and the Reveal hand-off store
  (`HandoffStore`, JSON in `noBackupFilesDir`) and the Keystore alias (`dev.hfps.hoard.credentials.v1`) are read by the
  same code after the swap.
- **The Capacitor glue stays in `:app`**: the plugin facade (`HoardSmbPlugin`), `SystemUiPlugin`, the JSON shaping of
  analysis results, and the manifest components (transfer and stream services, `StreamProvider`, share activities).
  The core modules' manifests are empty: `:native` declares the components as the screens that use them arrive.
- **ProGuard rules travel with the code** (`consumer-rules.pro` of `:core:smb` and `:core:image`), so R8 applies them
  to whichever app depends on it.
- **The NDK build** (`image-native.gradle`, LibRaw's CMake) moves to `:core:image`. Both apps set `ndkVersion` (in
  `variables.gradle`) only to strip the `.so` with the NDK that built it. ML Kit's ABI filtering is shared through
  `app/mlkit-packaging.gradle`.
- **Build constraints while Capacitor is in the build.** AGP stays 8.13 and compileSdk 36 (the Capacitor modules need
  them). That caps the AndroidX lines (Compose BOM 2026.06, Navigation 2.9, Lifecycle 2.10; see
  `gradle/libs.versions.toml`). Kotlin is 2.4.21 for every module, so R8 is pinned to 9.5.32 in the root buildscript:
  AGP 8.13's own R8 cannot parse Kotlin 2.4 metadata. The swap PR moves to AGP 9 and drops the pin.
- **Lint baseline.** `:core:smb` has a lint baseline for findings that came with the code from `:app` (java.nio on
  minSdk 24, `revokeUriPermission` before API 26, the WorkManager service type that `:app`'s manifest declares).
  New findings fail lint.

### Application ids and the swap

- `:native` builds `dev.hfps.hoard.next` (debug and release) until parity, so both apps install side by side.
- `versionCode` 100 and up for `:native` (Capacitor 1.x is below 20).
- **The swap PR** drops the `.next` suffix in `:native`'s release build, keeps the release signing (skv group
  `hoard`, the same keystore as 1.x), and removes `:app`. The owner's phone then upgrades in place: same package,
  same signature, higher version.
- **Hoard Next** (phase 2d, below): the `nextRelease` build type is a release build of `dev.hfps.hoard.next`, named
  "Hoard Next", with its own icon, for the owner to try on the real phone next to Hoard 1.
- **Testing the upgrade before the swap**: `-Phoard.native.sameId=true` builds a debug `:native` with id
  `dev.hfps.hoard` (the build refuses it for release tasks). `adb install -r` over the Capacitor debug app is exactly
  the swap's upgrade. On the emulator only (`docs/testing/native-2a.md`).

### Data: Room in `:core:data`, the Capacitor schema reproduced, a one-time import

- **Room** (2.8, KSP, schema exported to `core/data/schemas/`), not SQLDelight. Flows, `@Relation` and Room's
  migration testing fit the app, and Reveal uses Room too.
- **The schema is the Capacitor schema v7, table for table**: same tables, columns, types, NOT NULL, defaults,
  primary keys, indexes and foreign keys (`SchemaParityTest` compares a Room database with one made by the real JS
  migrations). The exception is CHECK constraints, which Room cannot declare: the app's writers enforce those values.
  One table is added: `legacy_imports` (the import's record). Room version 1 is this starting point; later changes
  are Room migrations.
- **Settings** stay rows of the `settings` table with JSON values, the format Hoard 1 writes (ADR 0003), so imported
  keys keep their meaning. No DataStore: one store, one live-query mechanism. The native app adds its own appearance
  keys (`themeMode`, `palette`).

#### The import

- **Where the data is.** `@capacitor-community/sqlite` opens database `hoard` (`src/db/client.ts`,
  `createConnection("hoard", false, "no-encryption", …)`) as `<databases>/hoardSQLite.db`, a plain SQLite file
  (SQLCipher with no key). Same package, same data directory: after the upgrade `:native` finds it at
  `context.getDatabasePath("hoardSQLite.db")`. Its own database is `hoard.db`.
- **When.** On the first launch, before the shell (`ImportScreen`: progress, then a summary, or an error with Retry
  and Continue). An install that has nothing to import records that in a few milliseconds and goes straight to the
  shell.
- **How** (`CapacitorImporter`):
  1. Copy `hoardSQLite.db` and its `-wal` / `-shm` / `-journal` to `noBackupFilesDir/legacy-import/`. The Capacitor
     file is only ever read, so the old app's data stays as it was.
  2. Upgrade the copy to v7 if it is older, with **a Kotlin copy of `src/db/migrations.ts`** (`LegacyMigrations.kt`,
     generated by `scripts/legacy-db/export-migrations.ts`; `src/db/nativeMigrations.test.ts` fails when it is stale).
     A database newer than v7 is refused (`TooNew`) rather than half-imported.
  3. In **one Room transaction**: empty the 11 tables, copy every row column for column with SQLite's own types,
     and write the `legacy_imports` row (source `capacitor`, importer version, source version, counts per table).
     Foreign keys are deferred to the commit.
- **Once, idempotent, versioned.** While the `legacy_imports` row exists the importer does nothing, so later native
  writes are never overwritten. A failed or interrupted run writes nothing, and the next launch retries it.
  `IMPORTER_VERSION` is recorded with each import.
- **Not in the database, and not imported, because the same package keeps them where they are:**
  - **Passwords**: Android Keystore (`CredentialStore`, alias `dev.hfps.hoard.credentials.v1`) plus the encrypted
    files under the package. The same code in `:core:smb` reads them after the upgrade (verified on the emulator).
  - **Reveal links**: `HandoffStore` JSON in `noBackupFilesDir`.
  - **Pending transfers**: WorkManager's database in `no_backup`.
  - **Route state**: runtime only; route detection rebuilds it.
- **Lost at the swap unless carried over:** theme, palette, language and the text viewer's preferences live in the
  WebView's `localStorage`. Since phase 2c Hoard 1 mirrors them into `settings` (`AppearanceMirror`: keys
  `themeMode`, `palette`, `language`, `textViewer`; it only writes, Hoard 1 keeps reading localStorage), so the
  importer brings them like any other setting: the theme and palette are read as they are, the text viewer reads
  `textViewer`, and the language is applied once after the import (`AppLanguage.applyImported`). A Hoard 1 install
  must run a release with the mirror before the swap; without it Hoard 2 starts with the system theme, the blue
  palette and the system language.
- **Fixtures** come from the real JS code: `scripts/legacy-db/fixtures.ts` runs `src/db/migrations.ts` and the
  `src/db/queries.ts` writers over sql.js. It writes a v7 database with rows in every table, a v1 database, the same
  v1 after the JS `migrate()` (the native upgrade must produce an identical database), and a "v99" database.

### Phase 2b: the Files tab

The Files side of Hoard 1 (machines, routes, browsing any location, file operations, transfers, share in / out, the
viewers, Edit in Reveal and their settings) is ported to `:native` over the unchanged `:core:smb`. The Capacitor `:app`
does not change.

- **What moved from TypeScript to Kotlin** (`:native`, each with its tests ported): the route engine
  (`route/RouteEngine.kt`, from `src/features/route/engine.ts`: same rules, coroutines on the main thread, probes and
  connects on IO), "Test connection", address kinds and host input; the transfer bookkeeping (`transfers/TransferService.kt`,
  from `service.ts`: the `transfers` row first, then `TransferManager.start` with the same id; progress live at full rate
  and in Room at most once a second; reconcile and retry with resume); the browser's rules (`files/FileLogic.kt`) and
  the paste / picker rules (`transfers/Places.kt`); the settings with Hoard 1's validators (`data/AppSettings.kt`).
- **What the plugin did, `:native` does directly**: `SmbGateway` (probes, smbj sessions, Keystore credentials),
  `FileService` (`HoardFs` on any location through the route engine's session), `FileActions` (`Opener`, `Streamer`,
  `ShareOut`, `RevealHandoff`). The manifest components of the Files side come to `:native` (FileProvider,
  `StreamProvider`, `StreamService`, `WebViewerActivity`, WorkManager's foreground service, the cancel receiver, the
  share targets, the permissions and `<queries>`); their authorities follow the application id, so `.next` and the
  Capacitor app live side by side.
- **One engine still**: every byte goes through `TransferManager` / `TransferRunner` (ADR 0007), from the clipboard,
  the destination picker and "Save to Hoard" alike.
- **The words are Hoard 1's**: `android/scripts/strings/export_strings.py` turns `src/i18n/locales/{en,pt-BR}.json`
  (all namespaces but culling's) into `strings_hoard1.xml` (`t_` names, positional arguments, plurals with pt-BR's
  `many`). `src/i18n/nativeStrings.test.ts` fails when the copy is stale. At the swap the JSON files go and the XML
  becomes the source.
- **Navigation**: each folder is a `BrowseRoute(locationId, share, path)` entry, so back goes up the way the user came
  and each folder keeps its list position; tapping the Files tab again returns to its home. Viewers, the machine form,
  Transfers, "Save to Hoard" and the storage screen are full-screen destinations.
- **New in Hoard 2**: a built-in image viewer (pager over the folder's photos, zoom), where Hoard 1 handed images to
  another app.
- **`java.time` on minSdk 24**: core library desugaring in `:native`.
- **Not yet at parity** after 2b (tracked in `docs/testing/native-2b.md`) and closed in 2c: the text viewer's previews
  and editor extras, the per-address attempts in the route error state, the compact header search, drag reorder,
  machine reordering, the transfers progress ring, the in-app language setting.

### Phase 2c: culling at parity, the Files gaps, the parity audit

- **Culling** (ADR 0008, 0009, 0011) moves to `:native` (`cull/`, `ui/cull/`) over the unchanged native side:
  `CullService`, `ImageCore`, `CullSidecarWriter` and `CullSidecars` in `:core:smb` / `:core:image`, one
  `CullService` per process in the container. `NativeCullBackend` does what the plugin's `cull*` methods did (open
  through the route engine's session, reopen once when the handle expired, jobs awaited without blocking a thread).
- **What moved from TypeScript to Kotlin**, each with its tests ported: `model.ts` (`CullLogic.kt`), `burst.ts`
  (`Burst.kt`), `assist.ts` (`Assist.kt`), `xmp.ts` (`CullXmp.kt`); `useCullFolder` + `useCullAssist` become
  `CullViewModel` (decisions applied at once, saved in Room then written as sidecars, undo, imports at open and on
  resume, analyses from the cache then natively around the shot on screen); `images.ts` becomes `CullImages`
  (decoded bitmaps in small memory caches over the image core's disk caches; HARDWARE bitmaps for screens and tiles).
- **The analysis cache is shared**: `cull_analysis` rows hold the contract's `CullAnalysis` JSON with
  `CullAnalysisJson`'s rounding (`AnalysisJson`), so Hoard 1's rows are reused after the import and the other way round.
- **Copy picks** goes through the one engine: `TransferRequest` gained `sidecars` / `hiddenSidecars`, passed to
  `StartTransferRequest` for copies only (as `startTransfer` did).
- **The words**: `export_strings.py` now exports every namespace, culling's included.
- **Text viewer**: Markdown is rendered natively (a GFM subset: headings, lists, tasks, tables, quotes, code); Mermaid
  (files and ```mermaid blocks) uses the same mermaid.js as Hoard 1 (12.3.0, MIT, vendored in
  `native/src/main/assets/mermaid/`) in a WebView that loads nothing from the network (`securityLevel: "strict"`, a
  CSP, network loads blocked). Highlighting is a small tokenizer (keywords, strings, comments, numbers), not
  CodeMirror's grammars.
- **Language**: Android 13+ keeps the choice as the per-app locale (`LocaleManager`; `generateLocaleConfig` lists the
  languages in the system's per-app language page); before 13 a preferences file and `attachBaseContext` apply it.
- **The parity audit** (`docs/testing/native-2c.md`) lists every Hoard 1 route and feature with its native
  equivalent; the few "Partly" rows say what differs and why (Markdown images, a split editor, the highlighter's
  depth, the transfers drawer, compare's focus-score fallback). Phase 2d closes them.

### Phase 2d: the parity gaps closed, Hoard Next side by side

**The audit's "Partly" rows** (`docs/testing/native-2c.md`), closed in `:native`:

- **Markdown images** load by Hoard 1's rules (`MarkdownImages`, `imageSource`): a relative path (or `/` = the share's
  root) is a file on the same location, shown through the native thumbnailer at 1600 px (types without a thumbnail
  are not shown), `http(s)` is fetched (12 MB cap, no cookies), `data:image/…` is decoded; another scheme, a path above
  the root or with a backslash is never followed. A failure shows the alt text with a broken-image mark. Linked images
  (`[![badge](…)](…)`) follow their link; a paragraph of images flows them in a row. SVG images (badges) show their alt
  text: Android's decoders have no SVG.
- **"Copy code"** on every fenced block (a check for two seconds), which is also highlighted by its language.
- **Syntax highlighting with grammars**: SnipMe's [Highlights](https://github.com/SnipMeDev/Highlights)
  (`dev.snipme:highlights` 1.2.0, Apache-2.0, AGPL-compatible, maintained, pure Kotlin: no TextMate runtime, no
  Oniguruma) for its 17 languages (Kotlin, Java, JS/TS, Python, shell, Go, Rust, C/C++, C#, Swift, Dart, PHP, Ruby,
  Perl, CoffeeScript): keywords, literals, strings, line and block comments across lines, annotations. It runs on the
  whole text off the main thread (up to 256 K characters); formats it does not know (JSON, YAML, TOML, INI, SQL,
  XML…) and huge files keep the light per-line tokenizer. Listed in Settings › Licences. TextMate grammars were
  considered: the JVM runtimes are Eclipse tm4e (EPL-2.0, not on Maven Central) or Sora's fork (LGPL), and they need
  Oniguruma regexes (joni); far more weight for the same colours.
- **Split editor + preview** while editing a Markdown, Mermaid or CSV file on windows ≥ 840 dp (Material's expanded
  width; a phone in landscape at 914 dp qualifies): Editor / Split / Preview chips, Split by default where it fits,
  the preview following the draft 200 ms after typing stops. Narrower windows get Editor / Preview.
- **Transfers**: the indicator, the clipboard bar and the "View" actions open a bottom sheet on phones (< 600 dp;
  `TransfersSheet`: the summary, the active jobs, the last five finished, "Open all transfers"), the screen on tablets,
  as Hoard 1's drawer did.
- **Compare**: shots the analysis has not reached show the ADR 0006 focus score (variance of the Laplacian, best
  tile, on the 1600 px screen image: `CullBackend.focusScore`, Hoard 1's `cullSharpness`), and the crown goes to the
  higher score; with both analysed, the ADR 0009 ranking decides (no crown on a tie).

**Side-by-side test build.** The owner tries Hoard 2 on the real phone before the swap, next to Hoard 1:

- `:native`'s **`nextRelease`** build type: `initWith(release)` (R8, resource shrinking), `dev.hfps.hoard.next`, label
  "Hoard Next", the icon from the same pipeline (hfps-kit's `build-icons.mjs` on `scripts/glyph-next.svg`: the glyph
  with a dot, on the violet tile, in `native/src/nextRelease/res/`), version **2.0.0-beta.1** (versionCode 101).
  Signed with Hoard 1's keystore (skv group `hoard`). `bun run android:next` builds
  `android/native/build/outputs/hoard-next/hoard-next-2.0.0-beta.1.apk` (task `:native:hoardNextApk`); without the
  skv variables it is `-unsigned.apk` with a warning. On the emulator only, `-Phoard.next.debugSigned=true` signs it
  with the debug key, to sit next to a debug Hoard 1.
- **The import bridge.** An app cannot read another app's private database, so Hoard 1 (**1.11.0**, versionCode 20)
  gains `ImportBridgeProvider`: authority `dev.hfps.hoard.importbridge`, exported only behind the **signature**
  permission `dev.hfps.hoard.permission.IMPORT_BRIDGE` (declared by both apps, so the install order does not matter),
  plus a check that the caller is `dev.hfps.hoard.next` with the same certificate, read-only opens only. It serves a
  consistent snapshot of `hoardSQLite.db` (`VACUUM INTO` on a read-only connection; a file copy under a read
  transaction before Android 11), made per request and unlinked once its descriptor is open, and `settings.json`
  (bridge version, app version, schema version, counts, and the appearance rows Hoard 1 mirrors from the WebView's
  localStorage into `settings`). The shared contract is `ImportBridgeContract` in `:core:smb`.
- **First launch of Hoard Next** (`ImportBridge`, `ImportViewModel`): with no import recorded and Hoard 1's provider
  there, it offers **Import from Hoard** (what comes over, with the counts) or **Start without importing**; Hoard 1
  older than 1.11 gets "update Hoard, then check again"; a Hoard signed with another key "did not share its data". The
  import copies the snapshot into `noBackupFilesDir/legacy-import-bridge/`, runs the same `CapacitorImporter` as the
  in-place upgrade (recorded once, source `capacitor`) and deletes the copy. Nothing is written to Hoard 1.
- **Passwords cannot cross**: Hoard 1's Keystore key is bound to its app. After a bridge import, every machine with a
  user name and no password in Hoard Next goes into the `passwordsAfterImport` setting, and a **Passwords** step
  follows the import: one row per machine ("Signs in as `DOMAIN\user`"), the password stored in Hoard Next's own
  Keystore (never the database) and proven by listing the shares (connected / saved but not reachable now / refused,
  type it again); "Later" leaves it to a Files-tab notice ("2 machines need their password · Enter") that opens the
  same screen. The machine form still works for any change.
- **At the swap** nothing of this stays in the path: the swap build has Hoard 1's id and imports in place (the bridge
  is not offered to `dev.hfps.hoard`). The provider can go with `:app`.

### UI stack

- **Compose + Material 3, re-themed to hfps** in `:core:designsystem`, built from the `hfps-kit` skill's Android
  guide (Reveal's `:core:designsystem` is the reference): OKLCH tokens converted for the five palettes (blue default,
  olive, violet, rose, graphite; `scripts/theme/`), Inter (variable, OFL), Phosphor icons generated as
  `ImageVector`s (`scripts/icons/`), one card on a tinted background, eyebrow labels.
- **Navigation**: Navigation Compose with type-safe `@Serializable` routes (`GalleryRoute`, `FilesRoute`,
  `SettingsRoute`, `LicencesRoute`).
- **Dependency injection: manual.** One `AppContainer` per process (`HoardApplication`), with ViewModel factories.
  The graph is a handful of singletons. Hilt (Reveal's choice) can come in when the graph grows, and moving to it is
  mechanical.
- Coroutines and Flow throughout (Room flows, `VolumeMonitor` as a `callbackFlow`).
- **Layout**: edge-to-edge, predictive back (`enableOnBackInvokedCallback`). A bottom bar on phones in portrait; a
  navigation rail when the window is ≥ 600 dp wide or ≤ 500 dp tall (landscape phones, tablets: the 1.9.1 lesson
  that content gets the vertical space). The top bar collapses while the list scrolls down and comes back on the
  way up (quick return, `HfpsScaffold`).

### Testing

- **JVM**: the existing tests moved with their code (`:core:smb` 154, `:core:image` 45, `:core:xmp` 20) and the
  opt-in environment variables moved with them. `:core:data` (Robolectric: importer, schema parity, settings JSON),
  `:native` (logic, Compose behaviour under Robolectric). Run them all with `./gradlew testDebugUnitTest`.
- **Screenshots**: Roborazzi (Robolectric, no device) for the design system and the shell: phone portrait and
  landscape, tablet, light and dark, palettes, 200 % font, the import states. Recorded under
  `src/test/screenshots/` (`recordRoborazziDebug`, `verifyRoborazziDebug`).
- **Instrumented** (`:native`): the importer on a real device's SQLite, the first launch end to end, and the opt-in
  upgrade E2E on the same-id build (rows identical, Keystore passwords decrypt, the imported machine signs in to its
  Samba with the pinned GUID).

### What goes at the swap

The WebView UI (`src/`, React, Vite), Capacitor and its plugins, `:app`, `capacitor-cordova-android-plugins`, the web
mock (`src/plugins/smb/web.ts`, `mockData.ts`) and the plugin contract rule. The browser development loop has no
successor: the web gallery is hoard-server's (ADR 0018), and Compose previews plus Roborazzi replace the mock.
`src/db/migrations.ts` freezes at v7. Its Kotlin copy stays in `:core:data` for as long as a Hoard 1 database may
still need importing.

## Consequences

- One build, two apps. `bun run android:debug` / `android:release` and their outputs are unchanged. `:native` builds
  with `./gradlew :native:assembleDebug`; the side-by-side test build with `bun run android:next`.
- Every native change lands once and both apps get it. Until the swap, a change to `:core:smb` is checked against
  the Capacitor app (still the plugin contract rule) and against `:native`.
- A Capacitor schema change before the swap (a migration v8) needs, in the same PR, the regenerated
  `LegacyMigrations.kt`, the Room entity and a Room migration, and regenerated fixtures.
- The AndroidX versions stay one step behind Reveal's until the swap.
