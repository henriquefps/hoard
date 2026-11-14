# ADR 0019: Hoard 2, a native app built next to the Capacitor app

- **Date**: 2026-11-14
- **Status**: Accepted (phase 2a: foundations)

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
  WebView's `localStorage`. The plan is that the last Capacitor release before the swap mirrors them into
  `settings` (keys `themeMode`, `palette`, `language`), so the importer brings them like any other setting. Until
  then the native app starts with the system theme and the blue palette.
- **Fixtures** come from the real JS code: `scripts/legacy-db/fixtures.ts` runs `src/db/migrations.ts` and the
  `src/db/queries.ts` writers over sql.js. It writes a v7 database with rows in every table, a v1 database, the same
  v1 after the JS `migrate()` (the native upgrade must produce an identical database), and a "v99" database.

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
  with `./gradlew :native:assembleDebug`.
- Every native change lands once and both apps get it. Until the swap, a change to `:core:smb` is checked against
  the Capacitor app (still the plugin contract rule) and against `:native`.
- A Capacitor schema change before the swap (a migration v8) needs, in the same PR, the regenerated
  `LegacyMigrations.kt`, the Room entity and a Room migration, and regenerated fixtures.
- The AndroidX versions stay one step behind Reveal's until the swap.
