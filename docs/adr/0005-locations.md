# ADR 0005: Locations — the phone's storage, USB drives and SD cards

- **Date**: 2026-11-09
- **Status**: Accepted (1.3.0)

## Context

Hoard was an SMB client. The owner wants it to become a file manager and then a photo-culling app:
a Sony camera card in a USB-C reader → cull on the phone → copy the keepers to the phone, a USB
drive or an SMB share. Every later step (copy/move between places, share to and from other apps,
culling) needs one thing first: **every place files live is a _location_ with the same
operations**. This ADR is that base (phase 1). Phases 2a (copy/move/upload between any two
locations, share-to/from) and 2b (culling mode) build on it.

## Decision

### Model

Three location kinds, one shape in the contract (`LocationRef` in `src/plugins/smb/definitions.ts`):

| Kind     | Ref                          | Root                                             | Id in routes / favorites                |
| -------- | ---------------------------- | ------------------------------------------------ | --------------------------------------- |
| `smb`    | `{ kind, sessionId, share }` | the share                                        | the machine id (+ `share` search param) |
| `device` | `{ kind: "device" }`         | the primary shared storage `/storage/emulated/0` | `device`                                |
| `volume` | `{ kind: "volume", uuid }`   | a removable volume (`/storage/<uuid>`)           | `vol-<uuid>`                            |

Paths are always `/`-separated, without a leading slash, relative to the location's root (`""` is
the root), exactly as SMB paths already were. Volumes are keyed by their filesystem UUID (the FAT /
exFAT serial Android puts in `/storage/<uuid>`), which is stable across plug-ins, so a favorite on
a USB drive works again when the drive comes back. Machine ids are random UUIDs, never `device` or
`vol-…`, so `/browse/$locationId` keeps every 1.2 link and favorite working unchanged
(`src/features/locations/ids.ts`).

### Contract shape: a `location` in every file call

We considered parallel methods (`listLocal`, `statLocal`, …) and a `location` discriminated union.
**We chose the union**: `PathOptions` is now `{ location: LocationRef; path }` and every file
operation takes it — `list`, `stat`, `createFolder`, `rename`, `delete`, `readTextFile`,
`writeTextFile`, `thumbnail`, `openFile`. Parallel methods would double the contract and every
caller, and phase 2a needs exactly this shape for "copy from location A to location B". The
SMB-only methods (`probe`, `discover`, credentials, `connect`, `listShares`, transfers) are
unchanged. `streamFile` / `openWebPage` take a `StreamLocation`: SMB keeps its own-connection
source (machine, addresses, GUID, share) as a `location`, local locations are just their ref.

New methods: `getStorageAccess`, `requestStorageAccess`, `listVolumes`, `grantVolumeAccess`,
`openStorageSettings`; new event `volumesChanged`; new codes `STORAGE_PERMISSION` (no access to
local files) and `UNAVAILABLE` (volume unplugged, ejected, still checking, unreadable format).
Entries get an optional `restricted` flag (below).

The plugin keeps its name (`HoardSmb`): renaming it would touch every import and the Capacitor
registration for no behaviour change.

### Native: one `HoardFs` interface, three implementations

`android/app/src/main/java/dev/hfps/hoard/smb/location/`:

- `HoardFs` — list, stat, createFolder, rename, delete, readText, writeText.
- `SmbFs` — the existing smbj code (`SmbBrowser`, `TextFiles`) behind the interface, unchanged.
- `PathFs` — plain file paths with all files access. Listing reads attributes with one `lstat` per
  entry (NIO, Android 8+): a 20,000-entry folder lists in ~1.8 s on the emulator. Text saves use
  the same rules as SMB: conflict check on the modified time, temp file + `rename(2)` over the
  target. Case-only renames go through a temporary name (FAT/exFAT and sdcardfs compare names
  case-insensitively). Android 10 and older get a media scan after each change; Android 11+'s FUSE
  daemon keeps MediaStore in sync itself.
- `SafFs` — the SAF fallback (below). Document ids of the external storage provider are
  `<uuid>:<path>`, so a path maps to its document directly instead of walking the tree.

`VolumeRegistry` lists volumes (`StorageManager.getStorageVolumes()`, `StorageVolume.getDirectory()`
on Android 11+, the hidden `getPath()` before), with label, state, total/free bytes (`StatFs`),
filesystem (from `/proc/mounts` at `/mnt/media_rw/<uuid>`: the `/storage/<uuid>` view is FUSE) and
how Hoard reads it (`path`, `saf`, `none`). Whether a removable volume is a USB drive or an SD card
is not public API: Android's own description ("SanDisk USB drive", "SD card") decides, and a USB
mass-storage device attached right now settles the rest (`VolumeMapping.kind`, unit tested).
`VolumeMonitor` reports changes through `StorageManager.StorageVolumeCallback` (Android 11+) or the
`ACTION_MEDIA_*` broadcasts (older), coalesced for 400 ms. The UI derives attach / detach from the
difference between volume lists (`diffVolumes`) rather than from the event's `change` field: on the
emulator a removal arrived without a usable `change`, and a diff is right whichever callback fired.

**Bytes still never cross the bridge.** Local thumbnails reuse `Thumbnailer` and its cache
(`cache/thumbs`, same LRU), decoding the file in place (EXIF thumbnail, `ImageDecoder` with a
target size, `MediaMetadataRetriever` on the path or the SAF URI); nothing is copied first. Local
`openFile` and `streamFile` hand the file itself to the other app: its FileProvider URI (paths
`device/`, `storage/`, `media/` in `file_paths.xml`) or its SAF document URI, with a one-off read
grant — no cache copy, no stream token, no foreground service (the other app reads a real file
descriptor, so a frozen Hoard cannot stall it). `openWebPage` serves a local folder through the
same local web server and viewer as SMB pages (`LocalStreamSource` implements the new
`StreamSource` interface that `SmbSource` now implements too).

### Permissions: all files access

A file manager needs the whole shared storage and every volume, read and write; media-only access
(`READ_MEDIA_*`) sees only photos, videos and audio, and not on USB drives' arbitrary folders.
Hoard asks for **`MANAGE_EXTERNAL_STORAGE`** ("All files access") on Android 11+, and the
`READ/WRITE_EXTERNAL_STORAGE` runtime permissions (with `requestLegacyExternalStorage`) on Android 10
and older.

- First launch shows an explanation screen (`/storage-access`): what it gives, that nothing leaves
  the phone, the three steps, then `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (Hoard's own
  switch; the generic list as a fallback). "Not now" is remembered (`storageIntroSeen`); the home
  screen keeps an "Allow access" card and Settings → Phone storage has the switch's state.
- Denied: SMB keeps working; device and volume pages show "Allow all files access" (and, for a
  volume, the SAF picker); nothing crashes or spins.
- **Play Store:** Google Play only allows `MANAGE_EXTERNAL_STORAGE` for apps whose core purpose is
  file management (file managers, backup, antivirus…), after a permissions declaration review, and
  may reject or pull apps that use it otherwise. Hoard is sideloaded (signed APK, see README), so
  the policy does not apply today. If Hoard ever ships on Play, it qualifies as a file manager but
  the declaration and review are required; the SAF fallback is what a Play build without the
  permission would rely on.

### SAF fallback

Without all files access a volume can still be opened through the Storage Access Framework:
`grantVolumeAccess` opens the system folder picker on the volume
(`StorageVolume.createOpenDocumentTreeIntent`) and keeps a persisted grant. **Android 11+ refuses a
volume's root in that picker** ("Can't use this folder", verified on the emulator), so a grant is
a folder (`DCIM`); several grants per volume are allowed. `SafFs` resolves a path against the
longest grant covering it, shows the levels above grants as folders leading to them, and rejects
`STORAGE_PERMISSION` outside every grant. SAF is slower (each listing is a cross-process query),
and text saves write in place (SAF has no atomic replace). Grants on a removable volume are kept
while it is unplugged (`SafDestinations.list` no longer releases them). The primary storage has no
SAF fallback: Android 11+ refuses its root and its `Android/` folders in the picker too.

### Android/data and Android/obb

Since Android 11 these belong to each app; even all files access lists them empty. Hoard lists
them with `restricted: true` (lock icon, "Not accessible"), and opening one rejects `ACCESS_DENIED`
with `data.restricted`, which the UI explains instead of showing an empty folder or an error.

### Ejecting

Apps cannot unmount or eject a volume on modern Android (`StorageManager.unmount` needs
`MOUNT_UNMOUNT_FILESYSTEMS`, a system permission). The drive's menu offers "Eject in Android
settings" (`openStorageSettings`: `ACTION_INTERNAL_STORAGE_SETTINGS`, which lists volumes with
their Eject action). Pulling a drive out while browsing it shows "<drive> was disconnected" and goes
back to the home screen.

### UI

- Home: **This phone** (internal storage with a used/free bar, then each USB drive and SD card,
  appearing and disappearing live), then favorites, then machines. The sidebar has the same order.
  When a drive finishes mounting, a toast says "USB drive connected" / "SD card connected" with an
  **Open** action.
- `/browse/$locationId?share=&path=` and `/view/$locationId?…` for every location: breadcrumbs
  (Home › Internal storage › DCIM › Camera for local, Machines › machine › share › … for SMB),
  back, sort, filter, search, thumbnails, multi-select, read-only badge (a read-only mounted
  volume), text viewer and editor, streaming, web viewer and favorites. Downloads stay SMB → phone
  in this phase; copying between any locations is phase 2a.
- `BrowseLocation` (`src/features/locations/useBrowseLocation.ts`) is what pages work with: a
  `run(fn)` that hands each plugin call its `LocationRef` (SMB through the route engine's session),
  the stream source, the display path for "Copy path", and cache keys.

### Storage

Favorites became location-aware in schema v3 (ADR 0003): `location_id` (machine id, `device` or
`vol-<uuid>`), `machine_id` nullable (set and equal to `location_id` for SMB, so deleting a machine
still cascades), `share` `''` for local ones, unique `(location_id, share, path)`. The migration
copies every v2 favorite as an SMB favorite. `transfers` is unchanged: transfers stay SMB → SAF in
this phase.

## What phases 2a and 2b build on

- **2a (copy/move/upload, share-to/from):** a transfer becomes "location A + path → location B +
  path". The native side already has a `HoardFs` per location; it needs streaming read/write on it
  (open input/output, which `PathFs`/`SafFs` already do for text, and SMB's pipelined reader and
  writer) and the WorkManager job generalised from `SMB → SAF tree` to `HoardFs → HoardFs`, with
  the `transfers` table gaining source/destination location ids (a migration). Share-to Hoard
  (`ACTION_SEND`) lands in any location; share-from uses the in-place URIs above.
- **2b (culling):** the native image core (ADR 0006) reads local files by path or SAF URI, the same
  `LocalFile` this phase produces; culling a card in a USB reader is browsing a `volume` location.

## Consequences

- One contract change touched every file call; the mock, its tests and the UI moved with it.
- The mock simulates the phone (`src/plugins/smb/mockLocal.ts`): internal storage, a Sony-style
  USB drive with a 20,000-file folder, a read-only SD card, Android/data, and Settings → Developer
  switches to plug drives in and out and to toggle all files access.
- Device testing is in [docs/testing/locations.md](../testing/locations.md).
