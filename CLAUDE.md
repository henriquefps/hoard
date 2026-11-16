# Hoard: notes for agents

Android file manager and photo culling app, native since 2.0.0 (Kotlin + Jetpack Compose, ADR 0019): the phone's
storage, USB drives, SD cards and SMB shares, all as _locations_ (ADR 0005), with copy/move/share between any of them
(ADR 0007), and a Gallery tab (photos on the phone and on hoard-server). Hoard 1.x (Capacitor + React) was removed in
2.0.0; ADRs 0001–0018 describe it where they mention `src/`, the plugin contract or the WebView. Every contract with
Reveal (owners, versions, the hand-off policy, the compatibility table) is listed in `docs/integration/README.md`;
change one only through the Hoard ↔ Reveal coordinator. Read `README.md` and
`docs/adr/` first.

## Commands

- From `android/` (a fresh worktree needs `android/local.properties`, git-ignored, with `sdk.dir=…`):
  `./gradlew :native:assembleDebug` (debug: `dev.hfps.hoard`, debug key, "Hoard debug"), `testDebugUnitTest` (JVM +
  Robolectric for every module), `lint`, `:native:recordRoborazziDebug` / `verifyRoborazziDebug` (goldens in
  `native/src/test/screenshots/`, copies in `docs/testing/<report>/`), `:native:assembleRelease` (R8; signed only
  under `skv run hoard`). The bun scripts wrap them with the Mac's SDK and Android Studio's JBR (overridable with
  `ANDROID_HOME` / `JAVA_HOME`): `bun run android:debug`, `bun run android:test`.
- `bun run android:release` builds the signed release APK (`android/native/build/outputs/hoard/hoard-<version>.apk`,
  task `:native:hoardReleaseApk`) under `skv run hoard`: the keystore (Hoard 1's, the upgrade in place needs it) and its
  passwords live only in the skv group `hoard` (never copy the .jks into the repo or print the passwords). Without
  those variables Gradle builds `hoard-<version>-unsigned.apk` and warns. cachy has no skv group `hoard`: release
  builds there are unsigned; the owner's Mac signs.
- Gradle on cachy: `--max-workers=4`, the `-Xmx3g` of `gradle.properties`; one emulator, `-memory 3072 -no-window`;
  wrap every `adb` call in `timeout`.
- With a phone plugged in, target the emulator explicitly (`adb -s emulator-5554`, or `ANDROID_SERIAL`); never install
  test builds on the owner's phone. The debug build has the release's id with another key: on a phone with Hoard it
  would not install, and uninstalling Hoard deletes its data.
- Modules: `:core:smb` (SMB over smbj, Keystore credentials, locations and `HoardFs`, the transfer engine, streaming +
  `StreamProvider`, share in/out, Reveal hand-off, culling's native side), `:core:image` (LibRaw, decoders, caches,
  analysis), `:core:xmp`, `:core:data` (Room + the Hoard 1 importer), `:core:designsystem` (hfps theme, Inter,
  Phosphor), `:native` (`route/`, `transfers/`, `files/`, `cull/`, `smb/`, `data/`, `ui/`).
- Upgrade from Hoard 1 (ADR 0019): the first launch runs `CapacitorImporter` (`:core:data`) on Hoard 1's
  `databases/hoardSQLite.db` (schema v7, read only, copied first). The Kotlin copy of Hoard 1's migrations
  (`LegacyMigrations.kt`) and the fixtures in `core/data/fixtures/` are frozen: Hoard 1's sources are gone, so never
  regenerate them. E2E: `docs/testing/hoard-2.0.0.md` (a Hoard 1.11.0 debug build from commit `29cabe0`, the last with Hoard 1, then this
  debug build over it, `UpgradeE2eTest` opt-in with `-e hoardUpgradeE2e true`; `FirstLaunchTest` replaces the
  install's databases, opt-in with `-e hoardFirstLaunch true`). Never `connectedAndroidTest` on an install with data:
  it uninstalls the app.
- End-to-end testing against a real Samba: `docs/testing/samba/` and `docs/testing/xmp-sidecars/samba/` (containers),
  `docs/testing/tools/proxy.mjs` (a killable throttled TCP proxy). In the machine form type the container's address
  (`10.0.2.2:<port>`) by hand; never accept or tap a discovered machine (discovery on the build host sees the LAN, the
  owner's machines included), and check the saved address before connecting. Drive the app with `adb shell input` and
  `uiautomator dump`, screenshots with `adb exec-out screencap -p`.
- Test containers and images on a shared build host: remove only your own, by name or id (`docker rm`, `docker image rm`).
  Never run a host-wide `docker image prune`, `docker system prune` or `docker builder prune`: other projects' images,
  build caches and services live on the same host.
- Image core (`dev.hfps.hoard.image`, LibRaw vendored in `android/core/image/src/main/cpp`): see
  `docs/adr/0006-image-core.md` for the device harness (`ImageCoreHarnessTest`) and the opt-in
  `HOARD_ARW_DIR` JVM tests. Real photos are private: never commit them, use copies.
- Culling (ADR 0008, `native/.../cull/`, `ui/cull/`): pure logic in `CullLogic.kt` (tested), `CullViewModel` over
  `CullBackend` (`NativeCullBackend`: the image core and `CullService` of `:core:smb`). Measure with
  `adb logcat -s HoardCull HoardImage`. "Copy picks" goes only through the transfer engine (`TransferRequest` with
  `sidecars`), never a culling-specific copy.
- Assisted culling (ADR 0009): native measurements in `dev.hfps.hoard.image.analysis` (pure Kotlin, JVM-tested;
  formulas written platform-neutrally in the ADR), flags / bursts / suggestions in `cull/Assist.kt` and `Burst.kt`.
  Changing a measurement bumps `ShotAnalysis.VERSION` and `ANALYSIS_VERSION` (`Assist.kt`) together (cached rows in
  `cull_analysis` are then recomputed). Suggestions never decide: only the user's accept writes decisions. Calibrate
  on copies with `AnalysisCalibrationTest` (JVM, `HOARD_ARW_DIR`) and `AnalysisHarnessTest` (emulator, ML Kit);
  report aggregates only.
- Culling sidecars (ADR 0011): one native module `dev.hfps.hoard.xmp` (ported from Reveal's `Xmp.kt`; JVM-tested)
  reads, merges and writes XMP for the culling writer/reader (`smb/cull/CullSidecars.kt`, `CullSidecarWriter`) and for
  "Copy picks" (`TransferRunner.writeSidecar`). Never overwrite a malformed sidecar; never write into a folder not
  opened for culling; opening a folder never writes. Precedence rule and `cull_sidecars` in `cull/CullXmp.kt`. E2E:
  `docs/testing/xmp-sidecars.md`.
- Edit in Reveal (ADR 0010): `smb/share/RevealHandoff.kt` builds Reveal's `DEVELOP` intent (Reveal ADR 0011), sent to
  one package (`RevealPackages`: release, else debug; never a chooser); `ui/reveal/Reveal.kt` decides what is sent.
  Read + **persistable** grant on exactly the URIs sent, never a write flag on the intent; write
  (`grantUriPermission`) only on sidecar items (when `cullSaveXmp` is on); decisions go as the `CULLING` extra
  (ADR 0011). SMB photos go as stable links `content://<app>.stream/h/<id>/<tag>%2F<name>` (`smb/handoff/`:
  `HandoffStore` JSON in `noBackupFilesDir`, `HandoffServer` inside `StreamProvider`, served after a cold start through
  the native machine mirror); the authority follows the application id, so links Hoard 1 gave keep working. Keep the
  `StreamProvider` authority and `HandoffStore`'s file. Settings › "Reveal links". E2E:
  `docs/testing/persistable-handoff.md`. Test with Reveal's debug APK on the emulator only
  (`./gradlew :app:assembleDebug` in a copy of the reveal repo; its `release` variant did not compile in a clean clone
  on 2026-11-12).
- **Gallery** (ADR 0021): the server client is `:core:server` (pairing, Keystore key, `gallery.db` with the items feed,
  culling cache and queue, previews, kept originals, offline thumbnails; `CullingRules` and `Feed` are pure and
  JVM-tested, `SyncEngineTest` runs against a fake server); `:native` `gallery/` (device index, model, culling
  store/backend, Reveal sender) and `ui/gallery/`, `ui/server/`. Culling in the Gallery reuses `CullViewModel` through
  `CullStore` / `CullBackend` (`FolderCullStore` is folder culling, unchanged). Server items go to Reveal by reference
  only (ADR 0010 "Server items"). E2E against your own hoard-server container: `docs/testing/gallery/README.md`
  (`-Phoard.native.debugSuffix=.gallery` for another debug id, `-Phoard.native.benchRelease=true` for a debug-signed
  R8 build to measure frames).
- **Phone backup** (ADR 0024, A1): `:core:server` `backup/` (`BackupPlan` pure, `BackupUploader`, `BackupEngine` over a
  `BackupSource`, `BackupWorker` with `BackupHost` = the application), `gallery.db` 4 (`backup_roots`, `backup_files`,
  `freed`); `:native` `backup/PhoneBackup.kt` and `ui/backup/`. Hashing and uploads only inside the worker. E2E:
  `docs/testing/backup.md`. A2 (other devices see backups): `gallery.db` 5 (`server_item_mirrors`, the items' `mirror`
  locations), `gallery/Devices.kt` (tree, "Where" filter, badge, own-files merge by place), "Save to this device" =
  `ServerSaves` + `SaveToDevice` through `TransferService` with the read-only `ServerTransferFs` (`Endpoint.Server`,
  `:core:smb`, opened by the app through `TransferEndpoints.server`). E2E: two debug ids in two Android users of one AVD.
- **hoard-server** (`server/`, ADR 0012; protocol of record ADR 0013, `server/api/openapi.yaml`; items ADR 0014; culling ADR 0016; physical devices ADR 0017; Reveal's edited previews ADR 0020): Go +
  SQLite, formerly Reveal's `reveal-sync`. `cd server && go vet ./... && go test ./...` (Go from `server/go.mod`;
  includes an end-to-end test, `TestOpensRevealSyncDataDir`, `TestUpgradesHoardServer040DataDir` and
  `TestRevealProtocolContract`; video tests need ffmpeg/ffprobe on PATH), `docker build -t hoard-server .`, `gofmt`/golangci-lint as in
  `.github/workflows/server.yml`. Releases: tag `server-v<version>` (`server-release.yml` → GHCR); the app keeps `v*`.
  Run test servers on temp dirs and other ports;
  never touch the owner's deployed server (`~/Apps/hoard-server` on cachyos-fps, container `hoard-server`, reached
  only through Tailscale) or its data.
- **Web gallery** (`server/web/`, ADR 0018): Vite + React + shadcn in the hfps identity, built into
  `server/internal/webui/dist` and embedded in the binary; API under `/web/` (LAN/Tailscale only, admin-password session
  cookie). `cd server/web && bun run lint && bun run typecheck && bun run test && bun run e2e` (Playwright against the
  binary with a synthetic library; `HOARD_WEB_SCREENSHOTS=1`, `HOARD_WEB_BENCH=1`): `docs/testing/web-gallery.md`.
  Strings in `server/web/src/i18n/locales/{en,pt-BR}.json`.
- A side-by-side beta (another id next to Hoard, like 2.0.0-beta.1's "Hoard Next"): see ADR 0019, "After the swap".
- Package manager is **bun** (the scripts above, `server/web`). Never commit secrets; credentials live in `skv`.

## Rules

- **UI follows the `hfps-kit` skill's Android guide** through `:core:designsystem` (`HfpsTheme` tokens, the five
  palettes, Inter, Phosphor `ImageVector`s from `android/scripts/icons/`, one card surface, eyebrow labels,
  `HfpsScaffold`'s collapsing top bar). No raw colours or ad-hoc Material components where a design-system one exists.
  Layout: bottom bar on phones in portrait, a rail when the window is ≥ 600 dp wide or ≤ 500 dp tall.
- **No hard-coded UI strings.** Every string is a resource in both `native/src/main/res/values/` and
  `values-pt-rBR/` (`strings.xml`; Hoard 1's words, `t_` names, in `strings_hoard1.xml`, now edited by hand; names of
  components and licences in `licences.xml`, not translated). pt-BR plurals have `one`, `many` and `other`.
- **Locations** (ADR 0005): screens work through `FileService.run(BrowseLocation…)` (`files/Locations.kt`), which
  takes the route engine's session; never a raw smbj session. Route ids: machine id, `device`, `vol-<uuid>`. Native
  file operations go through `HoardFs` (`smb/location/`: `SmbFs`, `PathFs`, `SafFs`). Every failure carries a
  `HoardSmbErrorCode` (`ErrorCode.kt`) that the UI maps to its state.
- Emulator volumes: `adb shell sm set-virtual-disk true` + `sm partition disk:7,432 public` (pull
  it out with `false`); see `docs/testing/locations.md`.
- **Transfers** (ADR 0007): every byte that moves between locations goes through one engine: `TransferService` in
  `:native` (the `transfers` row first, then `TransferManager.start`), `TransferRunner` over `TransferFs` endpoints
  (`smb/transfer/`). Don't add a second copy path. Pure logic is JVM-tested with the in-memory `MemoryFs`
  (`TransferRunnerTest`). Share in/out: `smb/share/`.
- **Passwords never go to the database, settings or logs**: only `CredentialStore` (Android Keystore, alias
  `dev.hfps.hoard.credentials.v1`, unchanged since Hoard 1 so saved passwords survive the upgrade).
- **Schema changes** are Room migrations in `:core:data` (bump the version, export the schema to
  `core/data/schemas/`, a migration test; never edit a released one) and update `docs/adr/0003-storage.md`. Settings
  stay JSON rows of `settings` (`SettingsStore`, `AppSettings` with Hoard 1's validators).
- **Don't rename what an upgrade depends on**: the application id, the signing key, the `dev.hfps.hoard.*` package and
  class names WorkManager and JNI bind (`LibRawNative`), the Keystore alias, provider authorities, `HandoffStore`'s
  file, and the launcher alias `dev.hfps.hoard.MainActivity` (pinned icons from Hoard 1).
- **Licences**: a new library in the APK goes into Settings › Licences (`LicencesScreen.kt`, `licences.xml`) and
  `docs/licences.md` in the same PR.
- **Server compatibility** (ADR 0012): `/v1` is frozen for Reveal — additions only (new optional fields, new routes;
  incompatible changes go to `/v2` next to it), each written into ADR 0013 and `api/openapi.yaml` in the same PR;
  `server/testdata/v1-routes.txt` never shrinks. Keep `rvk1_` keys, the `revealsync://pair` URI, the SQLite schema
  (append migrations, never edit one), and the on-disk names `reveal-sync.db` (when present) and
  `<backup root>/reveal-sync/`. `server/testdata/compat/` must keep opening as is.
- Code, identifiers, comments and ADRs in English.
