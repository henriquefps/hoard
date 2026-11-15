# Hoard 2.0.0: the native app replaces Hoard 1 (ADR 0019, "The swap")

- **Date**: 2026-11-15
- **Host**: cachyos-fps (Linux, OpenJDK 21, Android SDK in `~/Android/Sdk`), Gradle `-Xmx3g`, `--max-workers=4`
- **Device**: Android emulator `Pixel_API_36` (API 36, x86_64, 1080×2400), `-memory 3072 -no-window`; every `adb` call
  under `timeout`
- **Server**: the culling Samba of `docs/testing/xmp-sidecars/samba/` (container `hoard-smb-2c`, `127.0.0.1:4461`,
  reached at `10.0.2.2:4461`, user `hoard`), `photos/shoot` with 237 shots
- **Signing**: both builds with this host's debug key (cachy has no skv group `hoard`). On the phone the pair is Hoard
  1.11.0 from `bun run android:release` (installed) and 2.0.0 from `bun run android:release` on the Mac: same keystore,
  as here same debug key.

## Automated

| Check | Result |
| --- | --- |
| `cd android && ./gradlew testDebugUnitTest lint` (every module) | Pass: 434 tests, 8 opt-in skipped (`:core:smb` 154, `:core:image` 45, `:core:xmp` 20, `:core:data` 16, `:core:designsystem` 3, `:native` 196; the bridge and Passwords tests went with them); lint: no new issues (`:core:smb`'s inherited baseline unchanged) |
| `:native:hoardReleaseApk` (R8, resource shrinking) | Pass, unsigned (no skv here): `hoard-2.0.0-unsigned.apk`, 31 MB. `aapt2 dump badging`: `dev.hfps.hoard`, versionCode 200, versionName 2.0.0, label "Hoard"; the manifest has the launcher alias `dev.hfps.hoard.MainActivity` → `dev.hfps.hoard.app.MainActivity`; ML Kit's unused contour model is no longer packaged |
| `:native:verifyRoborazziDebug` | 11 goldens differ, all by one word: `FilesTabTest`'s browse / transfers and `Phase2dTest.transfersSheetPhone` show "Yesterday" where the goldens (recorded 2026-11-14) show "Today". Their sample files are dated relative to the wall clock, so these goldens go stale every day; unrelated to the swap, left for a follow-up (a fixed clock in the samples). No other golden changed; the 7 bridge / Passwords goldens were removed with their screens |
| `UpgradeE2eTest` on the emulator after the upgrade (`-e hoardUpgradeE2e true`): every row of the 11 tables identical to Hoard 1's file, the Keystore password decrypts, the imported machine signs in to the Samba with its pinned GUID | Pass (1 test) |
| `ImporterOnDeviceTest` (temporary files, the device's SQLite) | Pass (3 tests) |
| `server/`: no file changed; `go vet ./... && go test ./...` | Pass: `go vet` clean, every package `ok` (the `server.yml` workflow does not run: no `server/**` path changed) |

## The upgrade in place (emulator)

```sh
# Hoard 1.11.0 debug, from the last commit with Hoard 1 (29cabe0), in a scratch copy:
git archive 29cabe0 | tar -x -C <copy> && cd <copy> && bun install && bun run build && bunx cap sync android
cd android && ./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
# … data made in Hoard 1 (below) …
# Hoard 2.0.0 debug (this branch): same id dev.hfps.hoard, same debug key, versionCode 200
cd android && ./gradlew :native:assembleDebug :native:assembleDebugAndroidTest
adb -s emulator-5554 install -r native/build/outputs/apk/debug/native-debug.apk
adb -s emulator-5554 install -r native/build/outputs/apk/androidTest/debug/native-debug-androidTest.apk
# launch once (the import runs), then
adb -s emulator-5554 shell am instrument -w -r -e hoardUpgradeE2e true -e class dev.hfps.hoard.app.UpgradeE2eTest \
  dev.hfps.hoard.test/androidx.test.runner.AndroidJUnitRunner
```

Never `connectedAndroidTest` here: it uninstalls the app, and its data, when it finishes.

**Hoard 1.11.0's data at the upgrade** (made through its UI; appearance set in its own storage, which its
`AppearanceMirror` copied into `settings`): 1 machine (1 address, password in the Keystore, pinned server GUID), 2
favourites (`photos`, `photos/shoot`), 1 cull session, 5 decisions (3 picked, 2 rejected, stars), 6 analyses, 5
sidecar records, 1 transfer, settings `showHiddenFiles: true`, `themeMode: "dark"`, `palette: "violet"`,
`language: "pt-BR"`, `textViewer: {wrap: false, wrapCode: true, fontSize: 16}`, one Reveal link record.

| Step | Result |
| --- | --- |
| Hoard 1.11.0 browses `photos/shoot` with its saved password | Pass ([01](hoard-2.0.0/01-hoard1-browse.png)) |
| `adb install -r` of 2.0.0 over it | Pass: `versionName=2.0.0`, `versionCode=200`, same data directory |
| The launcher entry | Pass: `dev.hfps.hoard/.MainActivity` (Hoard 1's component) resolves before and after: the alias keeps pinned icons working |
| First launch: the import | Pass: "Your data is here", 1 machine, 2 favorites, 5 culling decisions, 1 transfer, already dark and violet ([02](hoard-2.0.0/02-import-done.png)) |
| Continue: the language | Pass: the app relaunches in Portuguese (`locale list changing from [en-US] to [pt-BR]`) |
| Files tab | Pass: both favourites with their route, the machine online "Local · 10.0.2.2 · 3 ms" ([03](hoard-2.0.0/03-files.png)) |
| Browse the share | Pass: `photos/shoot` lists with thumbnails, **no password asked** ([04](hoard-2.0.0/04-browse-no-password.png)) |
| Culling | Pass: "5 de 237 decididas · 3 escolhidas · 2 descartadas", Hoard 1's decisions and stars on the tiles ([05](hoard-2.0.0/05-cull-decisions.png)) |
| Settings | Pass: Escuro, Violeta, Português (Brasil), Mostrar arquivos ocultos on ([06](hoard-2.0.0/06-settings.png)) |
| Hoard 1's `hoardSQLite.db` after Hoard 2 ran | Pass: byte-identical to the copy taken before the upgrade (only ever read) |
| Reveal links | Pass: `no_backup/reveal-handoffs.json` still there with the machine mirror |
| `UpgradeE2eTest` | Pass, run on a fresh import: the first run failed only because the culling step above had updated the session row (opened time, 237 shots); `hoard.db` was deleted, the app relaunched (the import ran again from the untouched Hoard 1 file) and the test passed before any other use |

## Found while preparing the swap

- **Pinned launcher icons would have broken.** Hoard 1's launcher component was `dev.hfps.hoard/.MainActivity`
  (class `dev.hfps.hoard.MainActivity`); Hoard 2's activity is `dev.hfps.hoard.app.MainActivity`. A home-screen icon
  or shortcut pinned from Hoard 1 names the old component. The LAUNCHER intent filter now sits on an
  `<activity-alias android:name="dev.hfps.hoard.MainActivity">` targeting the new activity; checked above with
  `cmd package resolve-activity` before and after the upgrade.
- ML Kit's contour model (`contours.tfl`, unused: contour mode is off) was excluded from Hoard 1's APK but packaged
  in `:native`'s; the same `ignoreAssetsPattern` now applies.

## Not verified

- **Release signing and the real phone**: no skv group `hoard` on cachy. The upgrade needs the release 2.0.0 signed
  with the keystore of the installed 1.11.0; `apksigner verify --print-certs` on both on the Mac before installing.
- **Hoard Next on the same phone**: not touched by 2.0.0 (another id); the owner uninstalls it.
- The text viewer's preferences (`textViewer`) were imported as a row; they were not checked in the viewer itself.
