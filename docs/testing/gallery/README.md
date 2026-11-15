# Gallery (Hoard 2.1.0, ADR 0021): tests, end-to-end run, measurements

## JVM and Robolectric (`cd android && ./gradlew testDebugUnitTest`)

| Suite | What it checks |
| --- | --- |
| `:core:server` `IdentityTest` | `v1:` fingerprint against `server/testdata/fingerprint-vectors.txt` (edges 0, 2 MiB, 2 MiB + 1); file = bytes; the derived item id equals hoard-server 0.9.0's; pairing codes in any order, refused versions, schemes, missing fields |
| `:core:server` `CullingRulesTest` | ADR 0016 client rules: cached per-field values and revs, a decision queues only changed fields with the cached `baseRev`, pending overrides pulled values, every push outcome (applied, conflict won/lost, unchanged, invalid dropped, replaced item), mapping by any member fingerprint, `replacedBy` chains, the derived id |
| `:core:server` `FeedTest` | an uploaded original without file names syncs (found by the E2E run); tombstones with `replacedBy` |
| `:core:server` `SyncEngineTest` (fake server) | pairing (key in the vault, model candidates, claim), refused token / other server, feed paging and incremental pulls with edits, `invalid_cursor` → from the start, offline queue collapse with original times, conflicts both ways and the per-field case 10, invalid labels dropped, an item replaced before the push, clear cache vs unpair, revoked key, ranged originals and pending previews, kept originals within their budget, another address of the same server |
| `:core:server` `SyncEngineTest` (review fixes) | a refused push does not stop the pull, is backed off (attempts) and retried with its own time; a new decision is not held back; a push answered without results is sent once per pass; a feed that never moves its cursor ends the pass; `culling` declared before the first push and retried when that failed; a moved queue keeps the newer change of each field; a plain-HTTP `*.ts.net` pairing moves to `https://` when the server answers there (once a day otherwise) |
| `:core:server` `CacheConcurrencyTest` | the refcounted `KeyedLocks` admits one caller at a time and forgets the key; 16 concurrent preview / original requests download once and whole; parallel downloads of one preview never share a temporary file |
| `:core:server` `LanRelayTest` | LAN, Tailscale and loopback addresses allowed, public ones not; https and loopback go direct, LAN through the relay, public plain HTTP refused; the relay forwards requests and never connects to a public address |
| `:native` `HandoverTest` | a phone decision is handed over with its own time (an imported sidecar's mtime), not again until decided again, an unmark after the hand-over goes as a clear with its time; the offline thumbnails counter is exact with four fetches at once |
| `:native` `CullViewModelTest` (sidecars) | a decision imported from a sidecar is dated by the sidecar's mtime (ADR 0016 rule 4) |
| `:native` `NetworkSecurityConfigTest`, `LicencesTest` | cleartext only to 127.0.0.1 / localhost; Settings › Licences and `docs/licences.md` list the same components, names from `licences.xml` |
| `:core:smb` `RevealHandoffTest` | server items: 500 per intent in order, the Bundle keys (no culling), the size of a full batch, `CULLING` v2 keys and v1 for older Reveal |
| `:native` `GalleryModelTest` | phone shots grouped like the server (pairs, Live Photos, videos), one entry for a shot on both, newest first with the shot's own day, every filter (kind, where, stars, flag, edited, camera, lens, dates, words), day/month sections and scroller marks (10k entries), phone folders and locations, motion photos, the plain-HTTP rule, the Reveal route per shot |
| `:native` `GalleryScreenTest` | badges (backed up once, RAW+JPG, video, motion, edited, pick, reject, stars), opening a cell, the unpaired banner, search; the goldens below |
| `:native` `ServerReferenceIntentTest` | a test receiver (`dev.hfps.reveal`, versionCode 90000) reads Hoard's intents: no ClipData or grants for server items, the extras, 1,001 items in three intents with one title, a mixed intent with CULLING v2, an older Reveal takes none |
| `:native` `CullViewModelTest`, `CullScreenTest` | phase 2c culling, unchanged through `FolderCullStore` |

Goldens (`./gradlew :native:recordRoborazziDebug`, copies here): `gallery_grid_{phone,landscape,tablet}_{light,dark}`,
`gallery_grid_phone_2col_light`, `gallery_grid_phone_months_dark` (2,000 items, month headers),
`gallery_grid_filtered_phone_light`, `gallery_no_match_phone_light`, `gallery_unpaired_phone_light`,
`gallery_syncing_phone_dark`, `gallery_permission_phone_light`, `gallery_claim_phone_light`,
`gallery_settings_{unpaired,paired}_*`. `transfers_*` and `browse_*` goldens from 2.0.0 depend on today's date
("Today" / "Yesterday") and fail `verifyRoborazziDebug` on another day; not changed here.

## End-to-end run (2026-11-15)

Emulator Pixel API 36 (`-memory 3072 -no-window`, software GPU), my own hoard-server 0.9.0 container
(`ghcr.io/henriquefps/hoard-server:0.9.0`, temporary data, port 127.0.0.1:3981 → `http://10.0.2.2:3981` from the
emulator, `--cpus 2 --memory 1536m`) with the web gallery's synthetic library (`TestWriteWebFixture`: RAW+JPG pairs,
videos, a Google motion photo, an XMP sidecar) and 10,000 bench JPEGs (`TestWriteWebBench`). Phone photos pushed to
`DCIM/HoardE2E` (a copy of a NAS pair's JPEG, a copy of a NAS phone photo, three new photos, a video). Debug build
`-Phoard.native.debugSuffix=.gallery` (test tags as resource ids; driven with `adb shell input` + `uiautomator dump`).

| Step | Result | Screenshot |
| --- | --- | --- |
| Pair from the admin page's link; the server proposes the phone of a "Reveal" device with the same model | "Is this the same phone as Reveal on …?"; Same phone → both devices in one physical device on the server | `e2e/01-pair-same-phone.png` |
| Grid | phone shots (phone badge) and server items (cloud badge), dates, durations, RAW+JPG | `e2e/02-grid-phone-and-server.png` |
| The phone's copy of a NAS pair's JPEG | one entry, "Backed up" (both badge); the shot's earlier phone decision handed over (`promoted 2`) | `e2e/03-backed-up-once.png` |
| Open a server-only pair, 3★ + pick | server preview in the viewer, focus score from the analysis of the screen preview; `GET /v1/culling/{id}`: rating 3 and flag pick, per field, Hoard's device | `e2e/04-viewer-server-item.png` |
| 5★ + label from the web gallery (`/web/culling`), pull in Hoard | the cell shows 5★ | `e2e/07-web-gallery-rating-pulled.png` |
| A Reveal edit uploaded (`PUT /v1/items/{id}/edits/{v}`, primary) | the cell shows the edited thumbnail with the Edited badge; the viewer opens on the edit; Edited ↔ Original | `e2e/05-viewer-edited.png`, `e2e/06-viewer-original.png` |
| Play a server video | the 720p proxy plays with the device key | `e2e/08-video-proxy.png` |
| A phone-only shot decided (4★ + pick), then that file uploaded as an original | after the sync, `GET /v1/culling/{id}`: rating 4 + pick with the phone decision's time | — |
| Edit in Reveal, server-only shot, Reveal 0.7.1 installed | "update Reveal to 0.9 or later" (no streaming link); with Reveal ≥ 0.9.0 the intent is covered by `ServerReferenceIntentTest` (no such Reveal build exists yet) | `e2e/10-reveal-gate.png` |
| Settings › Photo server | server, plain-HTTP note, status, this phone, caches, offline thumbnails, kept originals | `e2e/09-settings-photo-server.png` |
| 10,037 items, R8 release build | grid | `e2e/11-grid-10k-release.png` |

Found and fixed during the run: hoard-server sends the files of an uploaded original without `name` (the strict model
stopped the feed: models now default every optional field, `FeedTest`); `CullViewModel` read a field before its
initialisation when a culling store is live (viewer crash); a phone shot's decision was handed over only on the next
sync after it matched (now on every change of the entries); a sync cancelled by a newer one was recorded as a failure.

Earlier in the run, the then-current sender handed server items to Reveal 0.7.1 as streaming links; Reveal read both
files and crashed in its own editor (`EditorViewModel` has no no-argument constructor in that debug build). That
sender was replaced by server references (ADR 0010 "Server items") before this PR.

## Plain HTTP on a device (2026-11-15, review of PR #44)

`PlainHttpPolicyTest` (opt-in instrumented test, `-e hoardPlainHttp '<pairing link>'`) in the app's own process, on the
emulator (Pixel API 36, `-memory 2048 -no-window -read-only`), against a throwaway hoard-server 0.9.0 container
(temporary data, `127.0.0.1:3993` → `http://10.0.2.2:3993`): `http://example.com/` and a direct `http://10.0.2.2:3993`
request are refused by the platform ("Cleartext HTTP traffic … not permitted"); `ServerApi` refuses a public IP
literal before sending anything; pairing and a full sync with the LAN server work through `LanRelay` (the server's log
shows `POST /v1/pair`, `PATCH /v1/device`, `GET /v1/items/changes` from the new device). `OK (1 test)`.

## Measurements (`e2e/gfxinfo-*.txt`)

Software GPU, cachy loaded (load average 13–38): absolute numbers are the emulator's; compare with the baseline.

| What | Result |
| --- | --- |
| First pull, 10,000 items | 7.8 s |
| Cold start, R8 release, 10k items | 167, 174, 266 ms |
| PSS, R8 release, grid open | 59 MB |
| Grid, 30 flings, R8 release (`-Phoard.native.benchRelease=true`) | 1,035 frames: p50 38 ms, p90 53 ms, p95 57 ms, p99 65 ms, 20.4 % janky |
| Same, before caching the header formatters and cell texts | p50 57 ms, p90 81 ms, 57 % janky |
| Same, debug build | p50 81 ms, p90 200 ms |
| Baseline: Settings, 30 flings, same build | p50 28 ms, p90 53 ms, 17.3 % janky |
| Offline thumbnails | 6,249 kept so far, 49 MB on disk (≈ 8 KB each for 240×160 bench images); the rest waits for the server's previews, retried with back-off |
