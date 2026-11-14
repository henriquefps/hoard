# Hoard 2 phase 2a: native foundations (ADR 0019)

- **Date**: 2026-11-14
- **Host**: cachyos-fps (Linux, OpenJDK 21, Android SDK in `~/Android/Sdk`, NDK 28.2)
- **Device**: Android emulator `Pixel_API_36` (API 36, x86_64, Google Play image, 1080×2400), `-memory 3072
-no-window`, shut down afterwards
- **Server**: the disposable Samba of `docs/testing/samba/` (container `hoard-smb-native2a`, 256 MB, published on
  `127.0.0.1:4459`, reached from the emulator at `10.0.2.2:4459`), with six synthetic JPEGs in `media/Photos/Shoot`

## Automated

| Check                                                                                   | Result                                                                                                                                        |
| --------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `./gradlew testDebugUnitTest` (JVM)                                                     | Pass: `:core:smb` 154 (5 opt-in skipped), `:core:image` 45 (3 skipped), `:core:xmp` 20, `:core:data` 16, `:core:designsystem` 3, `:native` 33 |
| Every test file of `:app` before the split exists in exactly one module                 | Pass (29 files)                                                                                                                               |
| `lintDebug` on `:app`, `:native` and every `:core:*`                                    | Pass (`:core:smb` with its baseline of findings inherited from `:app`)                                                                        |
| `:app:assembleDebug`, `:native:assembleDebug`                                           | Pass                                                                                                                                          |
| `:app:assembleRelease` with signing variables (throwaway keystore)                      | Pass, `apksigner verify` OK; no R8 Kotlin-metadata warnings with the R8 9.5.32 pin                                                            |
| `bun run lint`, `typecheck`, `test` (270, including the migrations copy check), `build` | Pass                                                                                                                                          |
| Roborazzi `recordRoborazziDebug` (`:native`, `:core:designsystem`)                      | Recorded; copies in `native-2a/`                                                                                                              |

The real signing keys live only on the Mac (skv group `hoard`), so the signing path was exercised with a throwaway
keystore through the same environment variables (`HOARD_KEYSTORE`, `HOARD_KEYSTORE_PASSWORD`, `HOARD_KEY_ALIAS`).
`app/build.gradle`'s signing block is unchanged.

## The Capacitor app after the split (emulator)

The current `:app` debug APK installed over the 1.10.1 debug app (`adb install -r`), with its existing data:

| Item                                                                                          | Result                                       |
| --------------------------------------------------------------------------------------------- | -------------------------------------------- |
| Home: favourites and the machine with their route ("Local · 2 ms")                            | Pass                                         |
| Browse a share with the Keystore password; native thumbnails                                  | Pass (6 thumbnails)                          |
| Cull: open the folder (image core, `HoardCull: open 6 shots`), render, pick (P)               | Pass; XMP sidecar written                    |
| Copy to › Internal storage › Download (transfer engine, WorkManager, notification permission) | Pass, SHA-256 identical to the server's file |
| Settings: show hidden files (stored in the database)                                          | Pass                                         |

## Upgrade in place: Capacitor → native (emulator only)

```sh
cd android
./gradlew -Phoard.native.sameId=true :native:assembleDebug :native:assembleDebugAndroidTest
adb -s emulator-5554 install -r native/build/outputs/apk/debug/native-debug.apk          # over dev.hfps.hoard 1.x
adb -s emulator-5554 install -r native/build/outputs/apk/androidTest/debug/native-debug-androidTest.apk
# launch once: the import runs, then
adb -s emulator-5554 shell am instrument -w -r -e hoardUpgradeE2e true \
  dev.hfps.hoard.test/androidx.test.runner.AndroidJUnitRunner
```

`-Phoard.native.sameId=true` builds `:native` with the Capacitor app's id (`dev.hfps.hoard`), the same debug signing
and `versionCode` 100. `adb install -r` then upgrades the Capacitor app in place, as the swap will. Never install it on
a phone that has the real app. Do not use `connectedAndroidTest` for this: it uninstalls the app, and its data with
it, when it finishes.

Capacitor data at the upgrade (made through the app's UI): 1 machine (1 address, password in the Keystore, pinned
server GUID), 2 favourites, 1 cull session, 5 decisions, 6 analysis rows, 5 sidecar rows, 1 completed transfer, 1
setting.

| Item                                                                                                                                                    | Result               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------- |
| First launch shows the import summary (1 machine, 2 favourites, 5 decisions, 1 transfer)                                                                | Pass                 |
| `hoard.db` created next to `hoardSQLite.db`; the Capacitor file stays                                                                                   | Pass                 |
| Files tab lists the imported machine, this phone and the favourites                                                                                     | Pass                 |
| `UpgradeE2eTest`: every row of the 11 tables identical; the Keystore password decrypts; the imported machine signs in to the Samba with its pinned GUID | Pass                 |
| `ImporterOnDeviceTest` (3 tests, temporary files, the device's SQLite)                                                                                  | Pass                 |
| `FirstLaunchTest` skips itself on the same-id build (it replaces the databases)                                                                         | Skipped, as designed |

On the `.next` build (`dev.hfps.hoard.next`, a separate data directory) all instrumented tests run with the fixtures:
`FirstLaunchTest` (fixture v7 → import screen → Files tab) and `ImporterOnDeviceTest` pass. `UpgradeE2eTest` skips
itself there.

### Bug found and fixed

SMB favourites showed "Test Samba · Root" for a favourite on a share's root: the share was missing. They now read
"Test Samba · media" and "Test Samba · media · Photos/Shoot", as in Hoard 1.

## Screenshots

On the emulator, with the imported data: `device-import-done.png`, `device-files-light.png`,
`device-files-dark.png`. Shell (Roborazzi): `files-phone-*`, `files-landscape-*`, `files-tablet-*`,
`gallery-*`, `settings-*`, `import-*`, and two palettes.

## Not verified

- **Landscape on the emulator**: `wm user-rotation` / `fixed-to-user-rotation` changed the display rotation, but the
  relaunched activity's screenshots stayed portrait. Landscape and tablet layouts are covered by the Roborazzi
  screenshots and by `ShellBehaviourTest.theRailIsForTabletsAndShortScreens`.
- The real release keystore (on the Mac) and a physical device.
- `:core:image`'s instrumented harness (`ImageCoreHarnessTest`, `AnalysisHarnessTest`): moved unchanged and not run.
  The Capacitor app's cull flow above did load and render through the moved image core.
- Theme, palette and language are in the WebView's `localStorage` and are not imported (ADR 0019, "Lost at the
  swap unless carried over").
