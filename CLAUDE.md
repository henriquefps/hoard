# Hoard: notes for agents

Android file manager and photo culling app (Capacitor 8 + React): the phone's storage, USB drives, SD
cards and SMB shares, all as _locations_ (ADR 0005), with copy/move/share between any of them (ADR 0007). Read `README.md` and `docs/adr/` first.

## Commands

- `bun run dev` (browser, web mock), `bun run lint`, `bun run typecheck`, `bun run test`, `bun run build`.
- `bun run android:debug` builds the APK (`android/app/build/outputs/apk/debug/app-debug.apk`);
  it sets `JAVA_HOME` to Android Studio's JBR (no system JDK on this Mac). A fresh worktree needs
  `android/local.properties` (git-ignored) with `sdk.dir=/Users/<you>/Library/Android/sdk`.
- `bun run android:release` builds the signed release APK
  (`android/app/build/outputs/apk/release/app-release.apk`) under `skv run hoard`: the keystore and
  its passwords live only in the skv group `hoard` (never copy the .jks into the repo or print the
  passwords). Without those variables Gradle builds `app-release-unsigned.apk` and warns.
- With a phone plugged in, target the emulator explicitly (`adb -s emulator-5554`, or
  `ANDROID_SERIAL`); never install test builds on the owner's phone.
- End-to-end testing against a real Samba: see `docs/testing/release-1.0.0.md` (container, routes
  through a killable throttled TCP proxy, driving the WebView over CDP in debug builds).
- Image core (`dev.hfps.hoard.image`, LibRaw vendored in `android/core/image/src/main/cpp`): see
  `docs/adr/0006-image-core.md` for the device harness (`ImageCoreHarnessTest`) and the opt-in
  `HOARD_ARW_DIR` JVM tests. Real photos are private: never commit them, use copies.
- Culling (ADR 0008, `src/features/cull/`): the WebView shows JPEG files the image core writes
  (`cull*` plugin methods); pure logic in `model.ts` (tested). Measure on the emulator with
  `hoardCull.summary()` over CDP and `adb logcat -s HoardCull HoardImage`. The copy step goes only
  through `copyPicks.ts` (the transfer engine's adapter), never a culling-specific copy.
- Assisted culling (ADR 0009): native measurements in `dev.hfps.hoard.image.analysis` (pure Kotlin,
  JVM-tested; formulas written platform-neutrally in the ADR), flags / bursts / suggestions in
  `src/features/cull/assist.ts`. Changing a measurement bumps `ShotAnalysis.VERSION` and
  `ANALYSIS_VERSION` together (cached rows in `cull_analysis` are then recomputed). Suggestions
  never decide: only the user's accept writes decisions. Calibrate on copies with
  `AnalysisCalibrationTest` (JVM, `HOARD_ARW_DIR`) and `AnalysisHarnessTest` (emulator, ML Kit);
  report aggregates only.
- Culling sidecars (ADR 0011): one native module `dev.hfps.hoard.xmp` (ported from Reveal's `Xmp.kt`; JVM-tested)
  reads, merges and writes XMP for the culling writer/reader (`smb/cull/CullSidecars.kt`, `cullReadSidecars` /
  `cullWriteSidecars`) and for "Copy picks" (`TransferRunner.writeSidecar`, `startTransfer`'s `sidecars` as
  fields). Never overwrite a malformed sidecar; never write into a folder not opened for culling; opening a folder
  never writes. Precedence rule and `cull_sidecars` in `src/features/cull/xmp.ts`. E2E: `docs/testing/xmp-sidecars.md`.
- Edit in Reveal (ADR 0010): `smb/share/RevealHandoff.kt` builds Reveal's `DEVELOP` intent (Reveal ADR
  0011), sent to one package (`RevealPackages`: release, else debug; never a chooser); `src/features/reveal/` decides
  what is sent. Read + **persistable** grant on exactly the URIs sent, never a write flag on the intent; write
  (`grantUriPermission`) only on sidecar items (`openInReveal`'s `sidecars`, when `cullSaveXmp` is on); decisions go
  as the `CULLING` extra (ADR 0011). SMB photos go as stable links `content://<app>.stream/h/<id>/<tag>%2F<name>`
  (`smb/handoff/`: `HandoffStore` JSON in `noBackupFilesDir`, `HandoffServer` inside `StreamProvider`, served after a
  cold start through the native machine mirror `syncMachines` + `NativeRoute`); the short-lived token URIs stay for
  share-out and streaming. Settings › "Reveal links" (`revealLinks` / `revokeRevealLinks`). E2E:
  `docs/testing/persistable-handoff.md`. Test with Reveal's debug APK on the emulator only
  (`./gradlew :app:assembleDebug` in a copy of the reveal repo; its `release` variant did not compile in a clean clone
  on 2026-11-12).
- **hoard-server** (`server/`, ADR 0012; protocol of record ADR 0013, `server/api/openapi.yaml`; items ADR 0014; culling ADR 0016; physical devices ADR 0017; Reveal's edited previews ADR 0020): Go +
  SQLite, formerly Reveal's `reveal-sync`. `cd server && go vet ./... && go test ./...` (Go from `server/go.mod`;
  includes an end-to-end test, `TestOpensRevealSyncDataDir`, `TestUpgradesHoardServer040DataDir` and
  `TestRevealProtocolContract`; video tests need ffmpeg/ffprobe on PATH), `docker build -t hoard-server .`, `gofmt`/golangci-lint as in
  `.github/workflows/server.yml`. Releases: tag `server-v<version>` (`server-release.yml` → GHCR); the app keeps `v*`.
  The bun scripts (ESLint, Prettier, Vitest, tsc) ignore `server/`. Run test servers on temp dirs and other ports;
  never touch the owner's deployed server (`~/Apps/reveal-sync` on cachyos-fps, containers `reveal-sync` and
  `reveal-sync-tunnel`) or its data.
- **Web gallery** (`server/web/`, ADR 0018): Vite + React + shadcn in the hfps identity, built into
  `server/internal/webui/dist` and embedded in the binary; API under `/web/` (LAN/Tailscale only, admin-password session
  cookie). `cd server/web && bun run lint && bun run typecheck && bun run test && bun run e2e` (Playwright against the
  binary with a synthetic library; `HOARD_WEB_SCREENSHOTS=1`, `HOARD_WEB_BENCH=1`): `docs/testing/web-gallery.md`.
  Strings in `server/web/src/i18n/locales/{en,pt-BR}.json`.
- **Hoard 2 (native, ADR 0019)**: one Gradle project. Native code lives in `android/core/{smb,image,xmp}` (used by the
  Capacitor `:app` and by the Compose app `:native`, id `dev.hfps.hoard.next` until the swap), the database and the
  one-time import of `hoardSQLite.db` in `:core:data` (Room, schema = Capacitor v7), the hfps theme in
  `:core:designsystem`. `cd android && ./gradlew testDebugUnitTest` runs every JVM test; `:native:recordRoborazziDebug`
  re-records screenshots. A Capacitor migration also regenerates `LegacyMigrations.kt`
  (`bun scripts/legacy-db/export-migrations.ts`) and the fixtures (`bun scripts/legacy-db/fixtures.ts`). Upgrade test
  with `-Phoard.native.sameId=true` on the emulator only: `docs/testing/native-2a.md`.
- Package manager is **bun**. Never commit secrets; credentials live in `skv`.

## Rules

- **UI follows the `hfps-kit` skill (`react/GUIDE.md`)** (type scale, single card surface, density, list → card list
  below `@xl`, breadcrumbs via `Page` + `components/page/breadcrumbs.ts`, `ResponsiveDialog` for
  form dialogs (Drawer on phones), motion and gesture rules, no raw Tailwind colors). After any
  `shadcn add`: fix `from "cn"` imports, keep `sonner.tsx` without next-themes, re-apply the
  `tabs.tsx` focus fix, the `progress.tsx` `tone` variant and the `sidebar.tsx` short-screen rail
  (`useIsShort`, `short:block` / `short:flex`).
- **Page header** (1.9.1, `components/page/`): `Page` collapses its header on scroll (quick-return,
  `collapse.ts`) behind a compact bar. A `fill` page puts its scrolling list in `PageScroll`
  (header, `toolbar`, `compactTools`, `compactMenu`), and a virtual list in it uses
  `useScrollMargin`. Header buttons wrap their text in `ActionLabel` (icon only when compact and on
  `short` screens: viewport ≤ 500px tall, where the top bar becomes the sidebar rail).
- **No hard-coded UI strings.** Every string goes through i18next with keys in both
  `src/i18n/locales/en.json` and `pt-BR.json`.
- **Plugin contract rule.** `src/plugins/smb/definitions.ts` is the contract. Any change touches, in
  the same PR: `definitions.ts`, the web mock (`web.ts` + `mockData.ts`, with tests in
  `web.test.ts`) and the Kotlin plugin (facade `android/app/.../smb/HoardSmbPlugin.kt`, the rest in `android/core/smb/`, including
  `ErrorCode.kt`). Every rejection carries a `HoardSmbErrorCode` in `error.code`.
- **Locations** (ADR 0005): file calls take a `location` (`LocationRef`); pages work through
  `BrowseLocation.run` (`src/features/locations/useBrowseLocation.ts`), never with a raw
  `sessionId`. Route ids: machine id, `device`, `vol-<uuid>`. Native file operations go through
  `HoardFs` (`smb/location/`: `SmbFs`, `PathFs`, `SafFs`).
- Emulator volumes: `adb shell sm set-virtual-disk true` + `sm partition disk:7,432 public` (pull
  it out with `false`); see `docs/testing/locations.md`.
- **Bytes never cross the bridge**: transfers, open and thumbnails work with native files and URLs.
- **Transfers** (ADR 0007): every byte that moves between locations goes through one engine —
  `useTransferActions().start(request)` in JS, `startTransfer` in the contract, `TransferRunner`
  over `TransferFs` endpoints natively (`smb/transfer/`). Don't add a second copy path. Pure logic
  is JVM-tested with the in-memory `MemoryFs` (`TransferRunnerTest`). Share in/out: `smb/share/`.
- **Passwords never go to SQLite or JS storage**: only `HoardSmb.setCredentials` (Android Keystore).
- **Schema changes** append a migration to `src/db/migrations.ts` (never edit a released one) and
  update `src/db/types.ts`, `docs/adr/0003-storage.md` and `src/db/db.test.ts`.
- All data access goes through the `Db` interface (`useDb`, `useLiveQuery`); the app's `Db` notifies
  live queries after every committed write (`src/db/changes.ts`).
- **Server compatibility** (ADR 0012): `/v1` is frozen for Reveal — additions only (new optional fields, new routes;
  incompatible changes go to `/v2` next to it), each written into ADR 0013 and `api/openapi.yaml` in the same PR;
  `server/testdata/v1-routes.txt` never shrinks. Keep `rvk1_` keys, the `revealsync://pair` URI, the SQLite schema
  (append migrations, never edit one), and the on-disk names `reveal-sync.db` (when present) and
  `<backup root>/reveal-sync/`. `server/testdata/compat/` must keep opening as is.
- Code, identifiers, comments and ADRs in English.
