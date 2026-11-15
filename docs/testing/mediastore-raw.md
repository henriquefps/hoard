# MediaStore and RAW files (risk check for Hoard 2.2.0's local copies)

Hoard 2.2.0 copies photos that neither the server nor the phone holds into `Pictures/Hoard/<batch>/` through
MediaStore and hands Reveal the copies' `content://media/...` URIs (ADR 0010, amendment "2.2.0: local copies"). Before
building that, we checked that MediaStore accepts each RAW format, keeps its MIME type, and lets another app read it.

## How

`docs/testing/mediastore-raw-probe/` holds two minimal apps (not shipped, built with the app's AGP):

- **writer** (`dev.hfps.probe.writer`, no permissions): inserts one file per format into
  `MediaStore.Images.Media` (`VOLUME_EXTERNAL_PRIMARY`) the way Hoard's copy does: `DISPLAY_NAME`, `MIME_TYPE`,
  `RELATIVE_PATH = Pictures/Hoard/mediastore-probe/`, `IS_PENDING = 1`, bytes written through `openOutputStream`, then
  `IS_PENDING = 0`. It reads back the stored MIME type, name and size. With `--ez cr3variants true` it tries the CR3
  fallbacks below, and sends each inserted URI to the reader with `FLAG_GRANT_READ_URI_PERMISSION` only (the
  hand-off's grant).
- **reader** (`dev.hfps.probe.reader`, `READ_MEDIA_IMAGES` granted): queries `MediaStore.Images` by `RELATIVE_PATH`
  and opens each row by its content URI, reading every byte and comparing the count with `SIZE`. Given a URI with a
  grant, it opens that URI.

The RAW files are synthetic (each format's magic bytes, then a pattern to about 257 KiB); the JPEG is a real one from
`Bitmap.compress`. No real photos.

```sh
cd docs/testing/mediastore-raw-probe && ./gradlew --max-workers=2 assembleDebug   # needs local.properties (sdk.dir)
adb -s emulator-5580 install -r -g writer/build/outputs/apk/debug/writer-debug.apk
adb -s emulator-5580 install -r -g reader/build/outputs/apk/debug/reader-debug.apk
adb -s emulator-5580 shell am start -n dev.hfps.probe.writer/.WriterActivity
adb -s emulator-5580 shell am start -n dev.hfps.probe.reader/.ReaderActivity
adb -s emulator-5580 logcat -d -s ProbeWriter ProbeReader
```

## Results (2026-11-15)

Emulator `sdk_gphone64_x86_64`, Android 16 (`BE2A.250530.026.D1`), Google Play image, its own MediaProvider module.

| Format | MIME requested | Insert into `Images` | MIME stored | Name stored | Visible to the reader's query | Openable by the reader (bytes = size) |
| --- | --- | --- | --- | --- | --- | --- |
| ARW | `image/x-sony-arw` | accepted | `image/x-sony-arw` | `DSC0001-arw.ARW` | yes | yes (263 238 B) |
| NEF | `image/x-nikon-nef` | accepted | `image/x-nikon-nef` | `DSC0001-nef.NEF` | yes | yes (263 311 B) |
| **CR3** | `image/x-canon-cr3` | **refused**: `IllegalArgumentException: Unsupported MIME type image/x-canon-cr3` | — | — | — | — |
| RAF | `image/x-fuji-raf` | accepted | `image/x-fuji-raf` | `DSC0001-raf.RAF` | yes | yes (264 031 B) |
| DNG | `image/x-adobe-dng` | accepted | `image/x-adobe-dng` | `DSC0001-dng.DNG` | yes | yes (263 981 B) |
| ORF | `image/x-olympus-orf` | accepted | `image/x-olympus-orf` | `DSC0001-orf.ORF` | yes | yes (263 675 B) |
| RW2 | `image/x-panasonic-rw2` | accepted | `image/x-panasonic-rw2` | `DSC0001-rw2.RW2` | yes | yes (263 693 B) |
| JPEG | `image/jpeg` | accepted | `image/jpeg` | `DSC0001-jpg.JPG` | yes | yes (2 561 B) |

The reader's query returns the rows as `content://media/external/images/media/<id>` with `OWNER_PACKAGE_NAME` hidden
(null) from another app; the writer gets `content://media/external_primary/images/media/<id>`. Both open.

### CR3 fallbacks

Android's MIME map has no entry for `.cr3`, so MediaProvider refuses every way of putting a CR3 into `Images` under
its own name:

| Collection | MIME | Result |
| --- | --- | --- |
| `Images` | `image/x-canon-cr3`, `image/cr3`, `image/*` | refused: `Unsupported MIME type` |
| `Images` | none, `application/octet-stream` | refused: `expected MIME type under image/*` |
| `Images` | `image/x-canon-cr2` | accepted, but **renamed** `IMG_0002.CR3.cr2`, MIME `image/x-canon-cr2`; visible and openable (by query and by grant) |
| `Files` | any | refused: `Primary directory Pictures not allowed … allowed directories are [Download, Documents]` |
| `Downloads` (`Download/Hoard/…`) | `application/octet-stream` | accepted, name kept (`IMG.CR3`), MIME `application/octet-stream`; **openable by another app with a read grant**; not visible to its `Images`/`Files` queries |
| `Downloads` | `image/x-canon-cr3` | accepted, stored as `application/octet-stream`; on a name clash the suffix lands after the extension (`IMG.CR3 (1)`), so send `application/octet-stream` instead |

Not verified: older Android versions (MediaProvider is a Mainline module, so recent devices behave alike) and real RAW
files (MediaProvider decides on the MIME type and the name, not the content, at insert time).
