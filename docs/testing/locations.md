# Locations (1.3.0): device test report

Debug build on an emulator only (AVD `hoard-locations`, Android 16 / API 36.1 Google Play image,
`-port 5556 -no-window -memory 2048`); the owner's phone was never addressed (`ANDROID_SERIAL=emulator-5556`).

## Setup

- Removable volume: `adb shell sm set-virtual-disk true`, then `sm partition disk:7,432 public`
  (a 512 MB "Virtual SD card", vfat, uuid like `8285-1702`). `sm set-virtual-disk false` pulls it
  out; `true` + `partition` plugs a new one in. There is no virtual USB mass-storage device on the
  emulator, so the USB-vs-SD guess was checked only through unit tests (`VolumeMapping.kind`).
- Samples: 24 JPEGs and an MP4 in `DCIM/Camera`, 12 JPEGs + an MP4 + a unicode-named folder on the
  volume's `DCIM/100MSDCF`, an HTML folder in `Download/site`, and `DCIM/Worst` with 20,000 files
  (1-byte "JPEGs": thumbnails fail and fall back to the icon).
- Plugin calls through CDP on the debug WebView (`docs/testing/tools/cdp.mjs`, which now picks the
  app's `https://localhost` page, not the web viewer's); client-side navigation with
  `history.pushState` + `popstate` (a full WebView reload trips `@capacitor-community/sqlite`'s
  "connection hoard already exists", a pre-existing behaviour).
- SMB regressions: `docs/testing/samba/Dockerfile` on `127.0.0.1:14470` (256 MB, removed afterwards).

## Results

| Area              | Check                                                                                                                                                                                                                                                                      | Result                                                                                           |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| First run         | Permission screen shown once; "Open settings" lands on Hoard's own "All files access" switch; coming back shows "All set"                                                                                                                                                  | Pass                                                                                             |
| Home              | Internal storage with used/free bar, the volume with size and filesystem; favorites; machines                                                                                                                                                                              | Pass (filesystem showed `FUSE` at first: fixed to read `/mnt/media_rw/<uuid>`, now `VFAT`)       |
| Attach            | Partitioning the virtual disk while on home: the card appears and "SD card connected · Open"                                                                                                                                                                               | Pass                                                                                             |
| Detach mid-browse | Pull the disk while in `DCIM/100MSDCF`: "Virtual SD card was disconnected", back to home, card gone                                                                                                                                                                        | Pass (first try did not leave: the event's `change` was unusable; fixed by diffing volume lists) |
| Listing           | `DCIM/Worst`, 20,000 entries: 1.75 s natively, 1.9 s to rendered list                                                                                                                                                                                                      | Pass                                                                                             |
| Restricted        | `Android/data` and `obb` listed with a lock, "Not accessible"; opening → `ACCESS_DENIED` `{restricted: true}`                                                                                                                                                              | Pass                                                                                             |
| File ops (path)   | create, duplicate → `ALREADY_EXISTS`, case-only rename on vfat, unicode text file create/read/save, stale save → `CONFLICT`, non-empty delete → `ACCESS_DENIED`, recursive delete                                                                                          | Pass                                                                                             |
| Path safety       | `../..`, `vol-` uuid `../x` → `UNKNOWN`                                                                                                                                                                                                                                    | Pass                                                                                             |
| Thumbnails        | Internal JPEG and MP4, volume JPEG and MP4                                                                                                                                                                                                                                 | Pass                                                                                             |
| Open / stream     | JPEG `openFile` → Google Photos; volume MP4 `streamFile` → FileProvider URI, played in Photos (no copy)                                                                                                                                                                    | Pass                                                                                             |
| Web page          | `Download/site/index.html` in Hoard's web viewer through the local server                                                                                                                                                                                                  | Pass (viewer opened; page content not inspected)                                                 |
| SAF fallback      | All files access revoked → `STORAGE_PERMISSION` (`data.saf`); "Choose a folder on this drive" → picker refuses the volume root on Android 11+; picking `DCIM` → access `saf`, root shows `DCIM`, list/thumbnail/create/write/delete inside, `Music` → `STORAGE_PERMISSION` | Pass (design changed from whole-volume to folder grants because of the root refusal)             |
| Favorites         | Star on a volume folder and on an SMB folder; both on home with their place                                                                                                                                                                                                | Pass                                                                                             |
| SMB regression    | `SmbIntegrationTest` against the container; in-app: shares with access, list, stat, text read, create/rename/write with conflict check/delete, expired session → `NOT_FOUND`, `streamFile` (http) through the new `location` shape                                         | Pass                                                                                             |

## Not verified

- A real USB OTG drive and a real SD card (only the emulator's virtual disk); exFAT/NTFS mounts; a
  write-protected card (`mountedReadOnly` is exercised in the mock only).
- Android 10 and older (runtime storage permission path, legacy `getPath()`, broadcasts instead of
  `StorageVolumeCallback`).
- The release (R8) build on a device: it builds, but was not installed and driven.
- Dark theme screenshots on the device; the browser build covers both themes.
