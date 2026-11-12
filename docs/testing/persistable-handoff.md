# Persistable "Edit in Reveal" hand-offs (1.10.0): end-to-end run

- **Date**: 2026-11-12
- **Build**: Hoard debug APK 1.10.0 (versionCode 18). Reveal 0.5.1 (`15d1d18`) built in a temporary clone (the
  reveal repo was not modified): its debug APK (`dev.hfps.reveal.debug`), and, because Reveal's `release` variant does
  not compile in a clean clone (Hilt: `SyncController` / `SyncRepository` "cannot be provided", a Reveal issue), a
  debug build with the `.debug` suffix and its debug-only provider authority changed in the clone, so it installs as
  `dev.hfps.reveal` next to the debug one.
- **Device**: own AVD `hoard-persist` (API 36.1 arm64, 1080×2400, Google Play image), `-no-window`, port 5590; shut
  down after the run. The owner's phone was not used.
- **Server**: `docs/testing/xmp-sidecars/samba/Dockerfile` (Samba on Alpine, 256 MB / 1 CPU, `-p 4457:445`): `photos`
  (writable) and `photos-ro` (`read only = yes`); removed after the run.
- **Photos**: one CC0 Sony ILCE-6000 RAW from raw.pixls.us (`DSC01542.ARW`) and its embedded preview as the JPEG,
  copied as three RAW + JPEG pairs per share with a few different trailing bytes (each copy a different file for
  Reveal's fingerprints). Not committed.
- **Driving**: Hoard's debug WebView over CDP (`docs/testing/tools/cdp.mjs`): plugin calls, the SQLite plugin to add the
  machine row, routes by `history.pushState`; Reveal with `adb shell input` and `uiautomator dump`. Grants read with
  `dumpsys activity permissions`.

## Results

| Case                                                                                                                                                           | Result                                                                                                                                                                                                                          |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Hand off 3 pairs from the writable share with `sidecars` ([01](persistable-handoff/01-reveal-writable-batch.png))                                              | Pass: `{ sent: 6, sidecars: 3 }`; `DSC0160{1,2,3}.xmp` created on the share (`DSC01601.xmp` with the pick ★3 sent as `CULLING`, the others ★0); RAW + JPEG paired in Reveal (the `<tag>%2F<name>` segment)                      |
| Reveal persists the grants                                                                                                                                     | Pass: every `content://dev.hfps.hoard.stream/h/…` photo `persisted=0x1` (read), every sidecar `persisted=0x3` (read + write)                                                                                                    |
| Hand off 3 pairs from the read-only share with `sidecars` ([02](persistable-handoff/02-reveal-readonly-batch.png))                                             | Pass: `{ sent: 6, sidecars: 0 }`, nothing written on the share; Reveal shows the ★5 pick from `CULLING`                                                                                                                         |
| `am force-stop` Hoard and Reveal, open Reveal → "Opened from other apps" ([03](persistable-handoff/03-cold-start-from-apps.png))                               | Pass: Hoard's process started by the provider alone (no activity); Reveal's start-up check opened all 12 links (`open … (r) for dev.hfps.reveal.debug`)                                                                         |
| Open a photo in Reveal after the cold start ([04](persistable-handoff/04-cold-start-editor.png))                                                               | Pass: `DSC01602.ARW` + JPEG read through the links (`fetched=1048576` / `2169401`)                                                                                                                                              |
| Streaming service on a cold start                                                                                                                              | Pass: `StreamService` started from the background (Android allowed it: only the "no location/camera/microphone" warning) while Reveal read; gone after the leases ended (2 min): `dumpsys` shows no service                     |
| Rate ★4 in Reveal ([05](persistable-handoff/05-reveal-rated.png))                                                                                              | Pass: Reveal read the sidecar (`r`) then wrote it (`wt`); Hoard committed it atomically (`DSC01602.xmp written by Reveal (426 bytes)`): `xmp:Rating="4"` on the share, no `.hoardpart` left                                     |
| Reopen culling of `photos/shoot` in Hoard ([06](persistable-handoff/06-hoard-imported.png))                                                                    | Pass: `cull_decisions` `dsc01602` rating 4 imported from the sidecar; the grid shows ★4                                                                                                                                         |
| Machine mirror                                                                                                                                                 | Pass: after the machine row was added, `RouteProvider` sent it (`reveal-handoffs.json`: id, GUID, `10.0.2.2:4457`); the empty DB before had removed the hand-off's own entry (by design: machines not in the app are forgotten) |
| Stop the Samba container, force-stop both, reopen in Reveal ([07](persistable-handoff/07-smb-down-grid.png), [08](persistable-handoff/08-smb-down-editor.png)) | Pass: each open answered at once with `FileNotFoundException` (`not readable: UNREACHABLE`); Reveal showed its stored previews (★4 kept), no crash                                                                              |
| Both `dev.hfps.reveal` and `dev.hfps.reveal.debug` installed, hand off ([09](persistable-handoff/09-both-installed-release-opens.png))                         | Pass: no chooser; `dev.hfps.reveal` opened the RAW in its editor (`INITIAL_URI`)                                                                                                                                                |
| Phone storage (all files access), with `sidecars`                                                                                                              | Pass: `LOC0001.xmp` created next to the JPEG (★2 from `CULLING`); FileProvider grants persisted: photo `0x1`, sidecar `0x3` (1.8.0's ADR said FileProvider could not: wrong)                                                    |
| Settings › "Reveal links" ([10](persistable-handoff/10-settings-reveal-links.png)), Revoke all ([11](persistable-handoff/11-revoke-confirm.png))               | Pass: "Reveal can reopen 17 files" (12 SMB photos, 3 SMB sidecars, 2 phone files); after Revoke all: count 0, no grant left on either authority (persisted ones included), store empty, machine mirror kept                     |
| The app's own path: culling page › Finish › "Edit the 3 shown in Reveal" ([13](persistable-handoff/13-smb-confirm.png))                                        | Pass: the new SMB note; route from the engine, `sidecars` from the culling setting (on by default): 6 photos + the 3 existing sidecars reused, 9 links                                                                          |

## Not verified

- A Reveal **release** build (R8) as the receiver: the release variant did not compile (above); `dev.hfps.reveal` was a
  renamed debug build. Hoard's own release build (R8) was not built or run either.
- SAF volumes (no all files access): sidecar creation through `DocumentTransferFs` and `canWrite` from the document's
  flags are covered by code review only; their persistable grant was already verified in 1.8.0.
- A phone restart (only `am force-stop`), Android versions other than 16, and a real phone (Samsung's process
  management may treat the background start of the streaming service differently).
- The 180-day prune and the 20 000-record cap on a device (JVM-tested only).
- A route change between hand-off and reopen (LAN → Tailscale), and a GUID mismatch on the first address, on a device
  (JVM-tested: `NativeRoute`).
- Reveal showing "Opened from other apps" without any folder source: it shows its empty library instead, so the run
  added an empty folder in Reveal first (a Reveal UI gap, not Hoard's).

## Automated

- JVM (`./gradlew testDebugUnitTest`): `HandoffStoreTest` (same id for the same file across instances and case,
  others distinct, shared folder tags, refresh, pruning, touch coalescing, revoke-all, machine mirror and last good
  address, damaged file), `HandoffLogicTest` (route order, GUID mismatch skipped, login refusal stops, URI parsing,
  write modes, sidecar buffer), `RevealHandoffTest` (flags never write, write grants only on sidecars, package choice,
  sidecars after their shot, existing / created / hidden / read-only / malformed / locked sidecars with `MemoryFs`),
  `StreamHttpTest` (lease expiry).
- JS (`bun run test`): `web.test.ts` (mock: sidecars from a writable and a read-only share, stable links, revoke,
  `syncMachines`, no GUID), `engine.test.ts` (`nativeMachines`).

## Commands

```
docker build -t hoard-smb-persist docs/testing/xmp-sidecars/samba
docker run -d --name hoard-smb-persist --memory 256m --cpus 1 -p 4457:445 hoard-smb-persist
# docker cp the photos into /srv/photos/shoot and /srv/photos-ro/shoot, chown -R hoard
emulator -avd hoard-persist -no-window -no-audio -wipe-data -port 5590
ANDROID_SERIAL=emulator-5590 node docs/testing/tools/cdp.mjs 'Capacitor.Plugins.HoardSmb.openInReveal({...})'
adb -s emulator-5590 shell dumpsys activity permissions | grep -A2 dev.hfps.hoard.stream
adb -s emulator-5590 emu kill; docker rm -f hoard-smb-persist
```
