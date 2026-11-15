# Hoard 2 phase 2d: the parity gaps closed, Hoard Next side by side (ADR 0019)

- **Date**: 2026-11-14
- **Host**: cachyos-fps (Linux, OpenJDK 21, Android SDK in `~/Android/Sdk`), Gradle `-Xmx3g`, `--max-workers=4`
- **Device**: Android emulator `Pixel_API_36` (API 36, x86_64, 1080×2400), `-memory 3072 -no-window`; every `adb` call
  under `timeout`
- **Server**: the culling Samba of `docs/testing/xmp-sidecars/samba/` (container `hoard-smb-2c`, `127.0.0.1:4461`,
  reached at `10.0.2.2:4461`, user `hoard`), the 2c shoot in `photos/shoot` (237 shots) plus `notes/trip.md`
- **Signing**: cachy has no skv group `hoard`, so no build here is release-signed. The emulator ran **Hoard 1.11.0
  debug** (`:app:assembleDebug`) next to **Hoard Next 2.0.0-beta.1** built as `nextRelease` (R8, resource shrinking)
  with `-Phoard.next.debugSigned=true`: both carry the debug key, which is what the signature permission compares. The
  real pair for the phone is `bun run android:release` + `bun run android:next` on the Mac (skv).

## Automated

| Check | Result |
| --- | --- |
| `cd android && ./gradlew testDebugUnitTest lint` (every module) | Pass: 401 tests, lint clean |
| New in 2d: `Phase2dTest` (Robolectric + Roborazzi), `SyntaxHighlightTest` (JVM), `:app`'s `ImportBridgeContractTest` | Pass: 29 tests |
| `ImportBridge` against a fake Hoard 1 (Robolectric provider + the v7 fixture made by Hoard 1's migrations) | Pass: no Hoard 1 → no offer; 1.10.1 → "update"; 1.11 → offer, import, machines with a user name listed for passwords, copy deleted, not offered again; a newer bridge refused |
| `:native:recordRoborazziDebug` | 10 new goldens; `cull_compare_landscape` changed (the crown follows Hoard 1's rule: no crown on a tie) |
| `:app:assembleRelease`, `:native:assembleRelease`, `:native:hoardNextApk` | Pass, unsigned (no skv here): `app-release-unsigned.apk`, `native-release-unsigned.apk`, `hoard-next-2.0.0-beta.1-unsigned.apk` |
| `bun run lint`, `typecheck`, `test`, `build` | Pass (272 tests) |

## End to end on the emulator

| Step | Result |
| --- | --- |
| Hoard 1.11.0 installed with data (the 2a database: 1 machine, 2 favourites, 5 culling decisions, 1 transfer; pointed at the culling Samba), password saved in Hoard 1 | Pass: Hoard 1 browses `photos/shoot` ([01](native-2d/01-hoard1-browse.png)) |
| Hoard Next installed next to it (both in the launcher) | Pass |
| First launch: the offer | Pass: "Hoard 1.11.0 is on this phone", the counts read from the bridge's `settings.json` ([02](native-2d/02-next-offer.png)); `HoardImportBridge: served settings.json (353 bytes) to dev.hfps.hoard.next` |
| Import from Hoard | Pass: `served database (139264 bytes)`, "Your data is here" with the counts, "1 machine needs its password" ([03](native-2d/03-next-imported.png)) |
| Passwords | Pass: one row, "Signs in as hoard" ([04](native-2d/04-next-passwords.png)); `hoardpass` → "Saved. Connected to Culling Samba." ([05](native-2d/05-next-password-connected.png)) |
| Files tab | Pass: favourites and the machine imported, the machine online (Local · 10.0.2.2 · 1 ms), no password notice left ([06](native-2d/06-next-files.png)) |
| Browse | Pass: `photos/shoot` lists ([07](native-2d/07-next-browse.png)) |
| Cull | Pass: "5 of 237 decided · 3 picked · 2 rejected": Hoard 1's decisions, merged with the folder's sidecars ([08](native-2d/08-next-cull.png)) |
| Markdown `notes/trip.md` | Pass: `../DSC00001.JPG` drawn from the share, `nope.png` shows its alt with the broken-image mark, the Kotlin block highlighted with its two-line block comment, "Copy code" ([09](native-2d/09-next-markdown.png)) |
| Split editor (landscape, 914 dp) | Pass: Editor / Split / Preview, Split chosen by default, the preview follows typing; Discard left the file unchanged on the share ([10](native-2d/10-next-split-landscape.png)) |
| Bridge cleanup | Pass: Hoard 1's `cache/import-bridge/` empty after serving |
| Bridge refuses others | Pass: `adb shell content read --uri content://dev.hfps.hoard.importbridge/settings.json` (shell uid) → `SecurityException: Permission Denial … requires dev.hfps.hoard.permission.IMPORT_BRIDGE` |

## The "Partly" rows of the 2c audit, closed

| 2c row | 2d |
| --- | --- |
| Markdown: images show their alt text; no "Copy code" | Images from the share (thumbnails), the web and `data:`, by Hoard 1's rules; "Copy code" on every block, highlighted by its language. SVG images (badges) still show their alt text: Android cannot decode SVG |
| Syntax highlighting: a lighter tokenizer | Highlights (Apache-2.0) grammars for 17 languages, block comments and strings across lines; JSON / YAML / TOML / INI / SQL / XML keep the light tokenizer (Hoard 1's CodeMirror had modes for some of these) |
| No split layout while editing | Editor / Split / Preview at ≥ 840 dp; Editor / Preview below |
| Transfers: a screen instead of a drawer | Bottom sheet on phones (< 600 dp) from the indicator, the clipboard bar and "View" toasts, with "Open all transfers"; the screen on tablets ([golden](native-2d/transfers_sheet_phone_light.png)) |
| Compare: no ADR 0006 focus score for shots not analysed yet | `CullBackend.focusScore` (1600 px screen image, best tile), "Focus N" and the crown on the higher score until both are analysed |

## Goldens (Roborazzi)

[Import offer](native-2d/import_offer_phone_light.png) ([dark](native-2d/import_offer_phone_dark.png)),
[update Hoard first](native-2d/import_offer_update_phone_light.png),
[imported through the bridge](native-2d/import_done_bridge_phone_light.png),
[Passwords](native-2d/passwords_phone_light.png) ([tablet dark](native-2d/passwords_tablet_dark.png)),
[Files notice](native-2d/files_passwords_notice_phone_light.png),
[Markdown images and code](native-2d/markdown_images_code_phone_light.png) ([dark](native-2d/markdown_images_code_phone_dark.png)),
[transfers sheet](native-2d/transfers_sheet_phone_light.png), [compare](native-2d/cull_compare_landscape.png),
and the [Hoard Next icon sheet](native-2d/hoard-next-icon-sheet.png).

## Not verified

- **Release signing and the real phone**: no skv group `hoard` on cachy. The signature permission was proven with the
  debug key on both apps; on the phone both must come from `bun run android:release` (1.11.0) and `bun run android:next`.
  Check the launcher icon there (violet tile with a dot) next to Hoard's, and the themed (monochrome) icon.
- The transfers sheet and compare's focus score were checked in Robolectric, not driven on the emulator (no running
  transfer or unanalysed pair was set up).
- Web images (`http(s)`) in Markdown: covered by the code path and the unit test of the source rules, not fetched on the
  emulator.
