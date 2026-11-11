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
- Image core (`dev.hfps.hoard.image`, LibRaw vendored in `android/app/src/main/cpp`): see
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
  0011); `src/features/reveal/` decides what is sent. Read grant on exactly the URIs sent, never write,
  never a sidecar item; decisions go as the `CULLING` extra (ADR 0011); SMB photos stream (`StreamEntry.idleMs`). Test with Reveal's debug APK on the
  emulator only (`./gradlew :app:assembleDebug` in a copy of the reveal repo).
- Package manager is **bun**. Never commit secrets; credentials live in `skv`.

## Rules

- **UI follows the `hfps-app-ui` skill** (type scale, single card surface, density, list → card list
  below `@xl`, breadcrumbs via `Page` + `components/page/breadcrumbs.ts`, `ResponsiveDialog` for
  form dialogs (Drawer on phones), motion and gesture rules, no raw Tailwind colors). After any
  `shadcn add`: fix `from "cn"` imports, keep `sonner.tsx` without next-themes, re-apply the
  `tabs.tsx` focus fix and the `progress.tsx` `tone` variant.
- **No hard-coded UI strings.** Every string goes through i18next with keys in both
  `src/i18n/locales/en.json` and `pt-BR.json`.
- **Plugin contract rule.** `src/plugins/smb/definitions.ts` is the contract. Any change touches, in
  the same PR: `definitions.ts`, the web mock (`web.ts` + `mockData.ts`, with tests in
  `web.test.ts`) and the Kotlin plugin (`android/app/src/main/java/dev/hfps/hoard/smb/`, including
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
- Code, identifiers, comments and ADRs in English.
