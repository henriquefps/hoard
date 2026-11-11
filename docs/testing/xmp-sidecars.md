# Culling sidecars (1.9.0): end-to-end run

- **Date**: 2026-11-11
- **Build**: debug APK 1.9.0 (versionCode 15); Reveal debug APK built from a temporary clone of reveal `main`
  (`c090478`), not modified
- **Device**: Android emulator `hoard-xmp` (API 36.1 arm64, 1080×2400), port 5584, `-no-window`
- **Server**: `docs/testing/xmp-sidecars/samba/Dockerfile` (Samba on Alpine, 256 MB / 1 CPU, `-p 4455:445`):
  `photos` (writable) and `photos-ro` (`read only = yes`); removed after the run
- **Photos**: one CC0 Sony ILCE-6000 RAW from raw.pixls.us (`DSC01542.ARW`, copied three times with a few trailing
  bytes so each copy is a different file) with its embedded preview as the JPEG, and three synthetic gradient JPEGs.
  Fixtures: `DSC01543.ARW.xmp` (Lightroom-style, `xmp:Rating` as an **element**, `xmp:Label="Red"`, `crs:` settings
  and a tone curve) and `IMG_0002.xmp` (malformed: `<x:xmpmeta …><rdf:RDF broken`).
- **Driving**: the debug WebView over CDP (`docs/testing/tools/cdp.mjs`): routes by `history.pushState`, the
  viewer's keyboard shortcuts (`1`–`5`, `p`, `x`, arrows), DB rows read through the SQLite plugin; screenshots of the
  WebView with `Page.captureScreenshot` (the headless emulator's own screencap showed stale tiles).

## Results

| Case                                                                                                                   | Result                                                                                                                                                                                                                                                                                                            |
| ---------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Local folder (`/sdcard/Pictures/xmptest`), open: Lightroom sidecar `DSC01543.ARW.xmp` (element-form rating 2) imported | Pass: `cull_decisions` `dsc01543` rating 2; `cull_sidecars` synced with its mtime; log `sidecars: 2 of 6 read in 4 ms`                                                                                                                                                                                            |
| Rate 4 + pick DSC01542                                                                                                 | Pass: new `DSC01542.xmp`, `CreatorTool="Hoard 1.9.0"`, `Rating="4"`, `PickLabel="3"`                                                                                                                                                                                                                              |
| Reject DSC01543                                                                                                        | Pass: merged into `DSC01543.ARW.xmp` (no second sidecar): `Rating="-1"`, `PickLabel="1"`, `Label="Red"`, `crs:Exposure2012`, tone curve kept; rating element replaced by the attribute                                                                                                                            |
| Edit `DSC01542.xmp` with `sed` (Rating 1, PickLabel 0) + `touch`, reopen the folder                                    | Pass: DSC01542 became unmarked ★1                                                                                                                                                                                                                                                                                 |
| Same while Hoard is in the background (Rating 5, PickLabel 3), bring Hoard back                                        | **Failed → fixed**: read on resume, but the import's DB save hit "BeginTransaction: Already in transaction" (two writes at once from different parts of the app). `capacitorDb` now runs top-level transactions and standalone writes one after another. After the fix: pick ★5, page "2 of 6 decided · 1 picked" |
| Rate the shot whose sidecar is malformed (IMG_0002)                                                                    | Pass: md5 unchanged (`81f7fe37…`), decision kept in the DB, toast "The rating of 1 photo was not saved next to it: kept in Hoard only" ([malformed-toast.png](xmp-sidecars/malformed-toast.png))                                                                                                                  |
| Hidden option on, rate DSC01544                                                                                        | Pass: `.DSC01544.xmp` created                                                                                                                                                                                                                                                                                     |
| Rate IMG_0003 and press Home 150 ms later                                                                              | Pass: `.IMG_0003.xmp` written 130 ms after the decision (before the 400 ms debounce): flushed on pause                                                                                                                                                                                                            |
| SMB writable share `photos/shoot`: open                                                                                | Pass: the Lightroom sidecar imported (★2)                                                                                                                                                                                                                                                                         |
| SMB: rate 4 + pick, reject                                                                                             | Pass: `DSC01542.xmp` created, `DSC01543.ARW.xmp` merged (label and `crs:` kept), no `.hoardpart` left                                                                                                                                                                                                             |
| SMB read-only share `photos-ro/shoot`                                                                                  | Pass: the listing's write probe says `read`; note shown ([read-only-note.png](xmp-sidecars/read-only-note.png)); decisions saved in the DB; no `cullWriteSidecars` call and no file changed on the server                                                                                                         |
| "Copy picks" (`startTransfer` with `sidecars`) into a folder holding a Lightroom `DSC01542.xmp`                        | Pass: merged (`Label="Red"`, `crs:` kept, `Rating="5"`, `PickLabel="3"`); `IMG_0001.xmp` created for the JPEG-only shot                                                                                                                                                                                           |
| Edit in Reveal from the read-only SMB folder ("Edit the 6 shown")                                                      | Pass: intent `DEVELOP` with 9 ClipData items and extras; Reveal shows DSC01542 ★5 picked on the RAW and the JPEG, DSC01543 ★2 ([reveal-received.png](xmp-sidecars/reveal-received.png))                                                                                                                           |
| Settings › Culling                                                                                                     | [settings-culling.png](xmp-sidecars/settings-culling.png)                                                                                                                                                                                                                                                         |

The culling page's note first truncated on a phone (the header column is narrow next to the actions); it now wraps.

## Automated

- JVM (`./gradlew testDebugUnitTest`): `XmpTest` (create, round trip, reject, unmark, old Hoard picks, creator tool
  only on new files, Lightroom with `crs:` and elements, darktable with `xpacket`, digiKam with element-form
  `PickLabel`, prefix reuse, escaping, Reveal namespace kept, malformed / non-XMP / XXE refused, develop-only
  sidecars, clamping, BOM; Reveal's Lightroom and darktable fixtures), `SidecarNamesTest`, `CullSidecarsTest`
  (reader thresholds, writer merge / create / malformed byte-identical / one sidecar per shot, read-only after the
  first `ACCESS_DENIED`, debounce + flush + close), `TransferRunnerTest` (destination merge, malformed left, hidden,
  1.8 jobs with XMP text), `RevealHandoffTest` (`CULLING` values).
- JS (`bun run test`): `xmp.test.ts` (wire, Copy picks list, Reveal values, precedence), `web.test.ts` (mock
  `cullReadSidecars` / `cullWriteSidecars`, read-only share, Reveal values, Copy picks merge and validation),
  `db.test.ts` (migration v7, `cull_sidecars`, setting dropped).

## Commands

```
docker build -t hoard-smb-xmp docs/testing/xmp-sidecars/samba
docker run -d --name hoard-smb-xmp --memory 256m --cpus 1 -p 4455:445 hoard-smb-xmp
# copy photos into /srv/photos/shoot and /srv/photos-ro/shoot (docker cp), chown -R hoard
# machine: 10.0.2.2:4455, user hoard / hoardpass
ANDROID_SERIAL=emulator-5584 node docs/testing/tools/cdp.mjs '…'
docker rm -f hoard-smb-xmp
```
