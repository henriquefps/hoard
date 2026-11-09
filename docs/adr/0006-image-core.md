# ADR 0006: Native image core for culling (RAW previews, region decode, LibRaw, sharpness)

- **Date**: 2026-11-09
- **Status**: Accepted (engine; the culling UI and its plugin methods are [ADR 0008](0008-culling.md))

## Context

The next phase adds a **culling mode**: browse a shoot full screen, zoom to 100% to check focus,
pick or reject. The owner shoots a **Sony ZV-E10** (24 MP, RAW + JPEG). Files live on SMB shares
today and on phone storage, SAF trees and USB drives soon (the "locations" rework happens in
parallel). Culling must feel instant: the next photo on screen within a frame or two, 100% zoom
without loading 96 MB bitmaps, and RAW-only shots must still be checkable at 100%.

This ADR covers the engine, `dev.hfps.hoard.image` (Kotlin) + `src/main/cpp` (LibRaw + JNI). It
adds no plugin method, no contract change and no UI.

## What a ZV-E10 ARW contains

Measured on 31 real ARW + JPEG pairs (two shoots, firmware 2.00; files never committed, see
"Testing"). Every ARW is 24.7–25.2 MB, little-endian TIFF:

| Where | What |
| --- | --- |
| IFD0 @ 8 (19 entries) | Make/Model, Orientation (1 or 8 here), DateTime, **PreviewImage** via `JPEGInterchangeFormat`: a **1616×1080** baseline JPEG at offset 114 850 / 118 946 / 123 042, **274–708 KB**, no EXIF of its own |
| IFD1 @ ~43 042 | 160×120 thumbnail JPEG at 43 312, 6–12 KB |
| SubIFD (0x14A) @ 115 102 | The raw: 6048×4024, compression 32767 (Sony), 12-bit, one strip at 540 672 of 24 337 152 bytes = 8 bits/pixel (Sony's lossy "compressed RAW") |
| EXIF IFD @ 4 540 | Exposure, f-number, ISO, focal length (+35 mm), `LensModel` ("30mm F1.4 DC DN \| Contemporary 016"), `DateTimeOriginal` + `SubSecTimeOriginal` + `OffsetTimeOriginal` (-03:00), PixelX/Y 6000×4000, a 37 KB maker note |

There is **no full-size JPEG inside the ARW**: the largest embedded image is 1616×1080, about a
quarter of the sensor's width. Enough for a quick look, not for checking focus. The paired camera
JPEGs (4.6–8.0 MB, 6000×4000) carry the same EXIF plus an XMP packet; in these folders they have
`xmp:Rating="4"` and the ARWs have no rating tag.

Everything the core needs from an ARW header fits in the **first 128 KiB** (the SubIFD ends at
115 734). Extracting the preview therefore costs **two reads**: 128 KiB, then the preview itself.
That is **436–743 KB of a 25 MB file (1.8–3.0%)**, two SMB round trips.

## Decision

### 1. Byte sources

Everything reads through `RandomAccessSource { size; read(position, buffer, offset, length) }`,
with adapters for local files (`FileSource`), smbj files (`SmbFileSource`, one READ per call),
memory (`ByteArraySource`) and windows (`SliceSource`). `BlockCachedSource` caches aligned 64 KiB
blocks, so a parser hopping around a header makes a few large reads instead of hundreds of small
ones (one SMB round trip each). `CountingSource` counts bytes and calls (tests, harness). SAF
(`ParcelFileDescriptor` + `FileChannel`) and USB adapters are one small class each when the
locations work lands.

### 2. Classification and pairing (`format/`)

- `ImageFormats.classify(name)` (listing, no reads) and `classify(name, firstBytes)`: JPEG, HEIF/AVIF,
  PNG, WebP, and RAW (ARW/SRF/SR2, CR2, CR3, NEF/NRW, RAF, DNG, ORF, RW2, PEF, SRW). The magic wins
  when it disagrees in kind (a JPEG named `.png`); a TIFF named `.tif` is not called RAW.
- `ShotPairing.pair(listing)` groups by case-insensitive stem (`DSC07668.ARW` + `dsc07668.jpg`),
  handles RAW-only and JPEG-only shots, prefers JPEG over HEIF as the rendering and keeps the rest
  in `Shot.others` (so deleting a shot deletes all of it). Shots sort naturally (`IMG_9` <
  `IMG_10`); the UI re-sorts by capture time once EXIF is known.

### 3. Header parsing (`tiff/`, `format/ImageInspector`)

One defensive TIFF reader (`TiffReader`: bounds-checked IFDs, loop guards, entry caps; magic 42,
Olympus `RO`/`RS`, Panasonic `0x55`) and a JPEG marker walker (`JpegMarkers`: SOF size, APP1
EXIF, APP1 XMP, stops at SOS). `ImageInspector.inspect(source, name)` returns type, EXIF
essentials and every embedded JPEG (largest first):

| Format | Previews looked at |
| --- | --- |
| ARW, NEF, PEF, SRW, DNG, CR2 | IFD chain + SubIFDs (2 levels): `JPEGInterchangeFormat`, single-strip JPEG IFDs (CR2 IFD0, DNG preview SubIFDs) |
| RW2 | `JpgFromRaw` (tag 0x2E) |
| ORF | Olympus maker note → CameraSettings → PreviewImage |
| CR3 | ISO BMFF: `THMB`, the `PRVW` uuid box, and the track whose sample is a JPEG (track 1 = full size) |
| RAF | The JPEG at the header's offset |
| JPEG | EXIF IFD1 thumbnail |

Only displayable JPEGs count (SOF0/1/2): lossless-JPEG RAW data (CR2 IFD3, DNG main image, SOF3)
is rejected by its frame marker. EXIF essentials (`PhotoMetadata`): make, model, lens, capture
time + sub-seconds + offset (`captureSortMillis` for sorting and burst grouping, `captureEpochMillis`
when the offset is known; no `java.time`, minSdk 24), exposure (`exposureLabel` "1/250"),
f-number, ISO (ISO, ISOSpeed or RecommendedExposureIndex), focal length (+35 mm), orientation,
rating (TIFF `Rating` or XMP `xmp:Rating`), pixel size. CR3 reads `CMT1`/`CMT2`; RAF reads the
embedded JPEG's EXIF.

### 4. Decoding (`decode/`)

- **Screen**: `BitmapDecoding.decode(file|bytes, targetLongSide, orientation)` uses BitmapFactory
  with a power-of-two `inSampleSize` (libjpeg-turbo's scaled IDCT), keeping the longest side at
  least 90% of the target: a 6000×4000 JPEG for a 3120 px S24 Ultra screen decodes at 3000×2000
  (24 MB) instead of 6000×4000 (96 MB). EXIF orientation is applied by us (BitmapFactory ignores it;
  embedded previews have no EXIF and take the RAW's). Optional HARDWARE bitmaps (no CPU copy;
  not usable for sharpness).
- **100% zoom**: `ZoomImage` wraps `BitmapRegionDecoder` on a **local** JPEG and decodes upright
  rectangles at any sample size; `Orientation.toStored` maps upright rectangles to stored pixels for
  all 8 EXIF orientations (tested pixel by pixel against the definition). A 1024² tile costs
  16–54 ms and ~20 MB; nothing allocates the whole image.
- **Plans** (`ImagePlans`, pure): screen = embedded preview when it covers ≥ 75% of the target,
  else preview first then the camera JPEG, else (RAW-only) preview then LibRaw half size. Zoom =
  camera JPEG; else a full-size embedded JPEG (CR3, many NEF/PEF) when it is ≥ 90% of the sensor;
  else LibRaw full size; else the preview.

### 5. LibRaw for RAW-only shots (`raw/`, `src/main/cpp`)

The ZV-E10's 1616 px preview is too small to judge focus, so RAW-only shots need a real decode.

- **LibRaw 0.22.3** (latest release, pinned), **vendored** in `src/main/cpp/third_party/LibRaw`
  (only `src/`, `libraw/`, `internal/` and the license files; tag and sha256 in `VENDORED.md`).
  Vendored rather than a submodule: Orca worktrees, CI and `bun run android:*` work without
  `git submodule update`, and the 2.1 MB of source changes only when we bump the version.
- **License**: LibRaw is dual LGPL-2.1 / CDDL-1.0; Hoard uses it under **LGPL-2.1**, compatible
  with Hoard's AGPL-3.0-or-later. Its `COPYRIGHT` and license texts ship in the source tree; the
  corresponding source of the library is this repository.
- **Build**: our own `CMakeLists.txt` (LibRaw's Makefile object list) builds a static `raw` library
  linked into `libhoardimage.so` with `-O3`, `c++_static`, `--gc-sections`, `--exclude-libs,ALL`
  and 16 KB page alignment. No RawSpeed, DNG SDK, libjpeg, zlib, LCMS or OpenMP (lossy/JPEG-XL DNG
  and a few exotic formats are not decoded; Sony, Canon, Nikon, Fuji, Panasonic, Olympus, Pentax
  and lossless DNG are). ABIs: **arm64-v8a** (phones) and **x86_64** (emulator on x86 hosts),
  set in CMake's `abiFilters` only, so the APK keeps every other ABI of SQLCipher; on armeabi-v7a/x86
  `LibRawDecoder.isAvailable` is false and RAW-only shots fall back to their previews. The Gradle
  part is isolated in `android/app/image-native.gradle` (one `apply from:` line in `build.gradle`).
- **Rendering**: white balance as shot, camera matrix, sRGB primaries and sRGB transfer curve,
  LibRaw's auto-brightness (dcraw's white point); orientation is not applied by LibRaw (the caller
  rotates). JNI subclasses `LibRaw` to reuse its output curve and converts the 16-bit image **in
  place** to RGBA 8888 (4 bytes/pixel never overtake 8), so the conversion needs no extra buffer.
- **Full size** goes **straight to a JPEG file** (`AndroidBitmap_compress` from the native buffer,
  Android 11+, looked up with `dlsym` because minSdk is 24; older versions go through a Bitmap),
  so a full-size Java Bitmap (97 MB) never exists. The render is cached on disk and zoomed with
  `ZoomImage` like a camera JPEG. **Half size** (2×2 binning, no demosaic) goes to a Bitmap.
- One decode at a time (a semaphore; peak ~300 MB native). Progress per LibRaw stage through a JNI
  callback, which also cancels (`CancelToken`); errors throw `LibRawException(code)`. R8 keeps the
  JNI names (`proguard-rules.pro`).

### 6. Caches and scheduling

- **Keys**: `FileIdentity(sourceId, path, size, modifiedMillis)` + variant (+ parameter) →
  SHA-256, 40 hex chars. `sourceId` names the location (SMB server GUID + share, `local`, a SAF tree
  URI…). A changed file gets a new key; stale entries are never served, only aged out.
- **Disk** (`DiskCache`, under `cache/image/`, LRU by mtime, atomic temp + rename):
  `originals/` (1 GB: camera JPEGs for region decode, RAWs for LibRaw), `previews/` (128 MB:
  extracted embedded JPEGs), `renders/` (512 MB: LibRaw renders, ~2.6–3.7 MB per full-size JPEG
  at quality 92). In-memory inspections are memoized (4 000 entries).
- **Memory** (`SizedLruCache`, by bytes): decoded screen bitmaps, a quarter of the large-heap class
  clamped to 48–256 MB (S24 Ultra: 128 MB ≈ five 3000×2000 images). `onTrimMemory` halves it, a
  quarter of it, or clears it.
- **Scheduler** (`PriorityScheduler`): worker threads (2) with a priority queue (0 = on screen,
  then ahead before behind), de-duplication by key (a second request joins the running decode and
  can raise its priority), cancellation of queued work at once and of running work through its
  token. `prefetch(shots, current, target, ahead = 3, behind = 1, zoomAhead = 0)` cancels whatever
  left the window; `zoomAhead` stages/renders the 100% source of the next shots in the background.

### 7. Sharpness (`sharpness/`, prep for phase 3)

Variance of the 4-neighbour Laplacian on Rec. 601 luma downscaled to ≤ 1024 px (box filter), for
the whole frame and per tile on a 4×4 grid (`best`, `bestIndex`, `topQuarter`). The best tile is
what matters for a portrait with a sharp face on a soft background. Scores depend on content and
analysis size: compare shots of one burst or scene, not across shoots. 7–9 ms per 3000×2000 image
on the emulator.

## Measurements

Emulator `hoard-image` (Android 16 / API 36, arm64 image virtualized on the Apple Silicon Mac
mini, 4 vCPU, 3 GB), 4 real pairs, warm run, screen target 3120 px (S24 Ultra). Native heap peaks
sampled every 5 ms. The emulator runs arm64 natively on the host CPU, so a Snapdragon 8 Gen 3
should be in the same range; to confirm on the phone.

| Step | Time | Peak native heap |
| --- | --- | --- |
| Inspect ARW (header, local file) | 1–7 ms; 128 KiB, 1 read | — |
| Read preview | 0–2 ms; 274–612 KB | — |
| Decode preview 1616×1080 (+ rotation) | 5–23 ms | — |
| Camera JPEG → 3000×2000 (sample 2) | 73–82 ms | +23 MB (+46 MB with rotation) |
| Region decoder open (camera JPEG) | 5–13 ms | — |
| 1024² tile at 100% | 16–54 ms | +17–25 MB |
| Whole image at 1/4 through the region decoder | 44–82 ms | — |
| LibRaw half size → 3012×2012 Bitmap | 153–181 ms (cold 367 ms) | +117 MB |
| LibRaw full size, PPG → JPEG 6024×4024 | 417–491 ms (cold 718 ms) | +303 MB |
| LibRaw full size, AHD → JPEG 6024×4024 | 726–997 ms (cold 1 255 ms) | +303 MB |
| Facade: preview step / final camera JPEG / memory hit | 7–17 ms / 69–100 ms / 0 ms | — |
| Facade: zoom RAW-only first time / again (disk cache) | 730 ms / 3 ms | — |

AHD (LibRaw's default interpolation, fewer demosaic artifacts than PPG) costs ~1.5× PPG end to
end; full-size renders use AHD (`ImageCoreConfig.fullDemosaic`, switchable to PPG). Most of a LibRaw run is unpacking Sony's compressed RAW
(120–260 ms) and the demosaic; the in-place conversion plus JPEG encode is 125–200 ms.

**Sharpness** on the two shoots (camera JPEG, global / best tile): indoor shoot at 1/50–1/320 s,
f/1.4–1.8: 15–188 / 40–740; outdoor shoot at 1/500–1/1000 s: 272–825 / 567–3 882. Frames of one
burst score close together (DSC07704 / 07705, 0.16 s apart: 37.5 / 36.3 global, 149.4 / 149.2
best), so the score ranks within a burst rather than judging an image in isolation. Scores on the
1616 px preview are of the same order and rank the shots much like the JPEG's.

**APK size**: `libhoardimage.so` is 1.31 MB (arm64-v8a) + 1.39 MB (x86_64), stored uncompressed
and page-aligned, so the universal release APK grows by **2.70 MB** (to 17.5 MB). Compressed
(Play delivers one ABI per device): ~0.47 MB per ABI. The Kotlin side adds nothing to release
builds until the UI uses it (R8 strips unused code).

## How the culling UI will call it

1. List a folder (any location), `ShotPairing.pair` → shots; build `ShotRequest(raw, rendered)`
   with `ImageFile(FileIdentity, name) { open a RandomAccessSource }`.
2. Showing shot *i*: `cachedScreen` (UI thread, memory only); else `screen(shot, target,
   step = screenPlan(...).first())` for an instant image and `screen(shot, target)` for the final
   one; then `prefetch(shots, i, target, zoomAhead = 1)`.
3. Zoom: `zoom(shot)` → `ZoomImage` (camera JPEG, full-size preview or LibRaw render); decode
   upright tiles with `decode(rect, sample)` for the visible area; close it when the shot leaves.
4. Overlay, sorting and burst grouping: `inspect(file).metadata` (capture time + sub-seconds,
   exposure, lens, rating).
5. Sharpness (phase 3): `ImageCore.sharpness(bitmap)` on the screen decode, compared within bursts.

Bytes still never cross the bridge (ADR 0001): the culling surface will be either a native
full-screen viewer (pager + tiled zoom, like `WebViewerActivity`) or file URLs to cached images.
That choice, and the plugin methods, belong to the culling UI work.

## Testing

- JVM: synthetic ARW/NEF/DNG/CR2/RW2/ORF/RAF/CR3/JPEG structures (`Synthetic.kt`, built in the
  test), corrupt/truncated/looping files, pairing, classification, EXIF time math against
  `java.time`, cache keys, LRU and disk cache, scheduler priorities/dedupe/cancellation, the
  8 orientations, sampling, plans and sharpness math.
- Opt-in on real files (never committed; the repo is public and these are private photos):
  `HOARD_ARW_DIR=/path/to/shoot ./gradlew testDebugUnitTest --tests 'dev.hfps.hoard.image.*'`
  prints previews, bytes read, EXIF and sharpness per shot.
- Device harness `ImageCoreHarnessTest` (instrumented; skipped when its folder is empty): copy a few
  pairs into the debug app's files, then run it on an emulator (never the owner's phone):

  ```sh
  export ANDROID_SERIAL=emulator-5558
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb push <copies> /data/local/tmp/hoard-harness/
  adb shell run-as dev.hfps.hoard sh -c "'mkdir -p files/harness && cp /data/local/tmp/hoard-harness/* files/harness/'"
  adb shell am instrument -w -e class dev.hfps.hoard.image.ImageCoreHarnessTest \
    -e dir /data/user/0/dev.hfps.hoard/files/harness -e target 3120 \
    dev.hfps.hoard.test/androidx.test.runner.AndroidJUnitRunner
  adb logcat -d -s HoardHarness:I HoardImage:V
  ```

  Delete the copies afterwards (`run-as dev.hfps.hoard rm -rf files/harness`, and
  `/data/local/tmp/hoard-harness`).

## Consequences

- RAW+JPEG shots: the preview shows in tens of ms after ~0.5 MB of reads; the camera JPEG replaces
  it after one full read (5–8 MB) and ~80 ms of decoding; 100% zoom is a tile away.
- RAW-only shots are checkable at 100% after ~1 s of LibRaw (then instant from disk), at the cost
  of the whole 25 MB RAW being copied locally and ~300 MB of transient native memory.
- +2.7 MB universal APK; a C++ dependency to keep current (LibRaw releases ~yearly; camera support
  arrives with them).
- ORF and CR3 parsing is tested on synthetic structures only (no sample files); ARW is tested on
  real files.
