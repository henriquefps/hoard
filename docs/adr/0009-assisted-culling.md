# ADR 0009: Assisted culling (sharpness, closed eyes, exposure, bursts, suggestions)

- **Date**: 2026-11-10
- **Status**: Accepted (1.6.0). Builds on the image core ([ADR 0006](0006-image-core.md)) and the
  culling mode ([ADR 0008](0008-culling.md)); sidecars go through the transfer engine
  ([ADR 0007](0007-transfers-any-location.md)).

## Context

1.5.0 culls a shoot by hand. This phase makes Hoard **help choose, never choose**: measure each
shot (focus, faces and eyes, exposure, a similarity signature), group bursts, and suggest the best
frame of each burst plus rejects for frames with a problem. Nothing is picked, rejected or deleted
until the user accepts. Everything runs on the phone, offline, on any location (SMB, USB, the phone).

The owner may build a native photo editor later (likely Apple platforms with Core Image / Vision).
So this ADR writes every measurement **platform-neutrally**: inputs, image sizes, formulas and
constants, enough to reproduce the same numbers elsewhere. Kotlin: `dev.hfps.hoard.image.analysis`
(`ShotAnalysis.kt`, `FaceDetection.kt`); TypeScript: `src/features/cull/assist.ts` (flags, bursts,
ranking, suggestions) and `xmp.ts`.

## 1. The image that is measured

**Source**: a shot with a RAW whose largest embedded JPEG is at least **1024 px** on its long side
is measured on that **embedded preview** (ZV-E10: 1616 × 1080, 0.3–0.7 MB read in two small
reads, ADR 0006); otherwise (JPEG-only shots, RAWs with tiny previews) on the **camera JPEG**.

Why the preview: it is the cheap image on every location. Over SMB on the emulator's slow link a
RAW-only or pair shot costs **~330 ms**, a JPEG-only shot (whole 7 MB file read) **~700 ms**. On
31 real pairs the preview and the JPEG agree: same dHash (0–1 bit apart), same mean luma
(±0.001), and the pairwise order of shots by best-tile Laplacian agrees in 444 of 465 pairs
(by subject blur 440 of 465).

**Scores compare within a folder** because every shot of one kind is measured the same way at the
same size. Mixing kinds in one folder (pairs and JPEG-only shots) is less comparable: on the same
shot the JPEG's Laplacian variance is **1.8–3.8× (median 2.3×)** the preview's (a sharper
downscale) and its Crété-Roffet blur is **0.01–0.11 lower (median 0.04)**. The flags use the blur
(the smaller gap) and the ranking compares frames of one burst, which share a kind in practice.

**Preparation** (exactly reproducible):

1. Decode the JPEG and apply its EXIF orientation (an embedded preview takes the RAW's
   orientation). Any decoder that can downscale by powers of two may do so while keeping the long
   side ≥ 1707 px (Android: `inSampleSize`).
2. Resample to the **analysis size**: long side **L = 1536** (never upscaled; a smaller source is
   measured at its own size), other side `round(side × L / long side)`. Resampling is an **exact
   area average**: target pixel `i` covers source interval `[i·s, (i+1)·s)` with `s = source /
target`, each source pixel weighted by the length of its overlap divided by `s`; separable
   (horizontal, then vertical), in floating point, rounded to 8 bits at the end (`+0.5`,
   clamped). ZV-E10 preview 1616 × 1080 → 1536 × 1027 (1027 × 1536 upright portrait).
3. **Luma** `Y = floor((299·R + 587·G + 114·B) / 1000)` on the 8-bit sRGB values (Rec. 601,
   integer), 0..255.

The same pipeline on the JVM (`javax.imageio` decode) and on Android (libjpeg-turbo) gave
**identical dHashes** for all 31 previews.

## 2. Sharpness

- **Laplacian variance** (ADR 0006): `lap(x, y) = Y(x−1,y) + Y(x+1,y) + Y(x,y−1) + Y(x,y+1) −
4·Y(x,y)` for every pixel not on the image border; variance = `E[lap²] − E[lap]²` (population).
  Computed for the whole frame (`global`) and per tile of a **4 × 4** grid, pixel `(x, y)` in tile
  `(floor(4x / W), floor(4y / H))`; `bestTile` = the tile with the highest variance.
- **Subject region**: the largest prominent face's box (section 3) when there is one, else the best
  tile. `subject` = Laplacian variance inside it.
- **Blur** (the scale-free number the flag uses): **Crété-Roffet** no-reference blur (Crété-Roffet
  et al., 2007) on the subject region `[x0, x1) × [y0, y1)`:
  - `B_v` = `Y` averaged with a vertical **1 × 9** box (`y−4 … y+4`), `B_h` with a horizontal
    9 × 1 box; pixels outside the image are clamped to the nearest edge (also outside the region,
    since the filter reads the image).
  - For each pixel of the region and its predecessor along the direction (the first row/column
    compares with the pixel just before the region, clamped): `D_F = |Y(p) − Y(p−1)|`,
    `D_B = |B(p) − B(p−1)|`, `V = max(0, D_F − D_B)`.
  - `b_dir = (ΣD_F − ΣV) / ΣD_F` (1 when `ΣD_F = 0`), `blur = max(b_v, b_h)`, 0 (sharp) .. 1.
- Shown to the user as **Sharpness = round(100 × (1 − blur))**.
- **Calibration** (31 ZV-E10 pairs, two shoots: indoor f/1.4 at 1/50–1/320 s, outdoor
  1/500–1/1000 s; synthetic degradations applied to the 1536 px analysis image):

  | Subject blur                                     | min   | median | max   |
  | ------------------------------------------------ | ----- | ------ | ----- |
  | Real frames, subject = face (device, ML Kit)     | 0.358 | 0.445  | 0.557 |
  | Real frames, subject = best tile (no faces, JVM) | 0.226 | 0.380  | 0.657 |
  | + Gaussian σ = 1.0 px (faces)                    | 0.523 | 0.568  | 0.656 |
  | + Gaussian σ = 1.5 px (faces)                    | 0.587 | 0.647  | 0.725 |
  | + Gaussian σ = 2.0 px (faces)                    | 0.623 | 0.719  | 0.777 |
  | + motion 6 px (faces)                            | 0.582 | 0.665  | 0.736 |
  | + motion 12 px (faces)                           | 0.669 | 0.758  | 0.820 |

  (σ = 1.5 px at 1536 px is ~6 px at 6000 px: clearly soft at 100%.)

- **"Blurry" thresholds** (`subjectBlur ≥ T`): **low 0.68, medium 0.60 (default), high 0.53**.
  At medium on the device pipeline: **0 of 31** real frames flagged; 30/31 with σ = 1.5, 31/31
  with σ = 2, 28/31 with a 6 px and 31/31 with a 12 px motion blur. High flags 5 real frames
  (smooth baby skin and small faces: DSC07717, 07761, 07764, 07775, 07779) — that is what "high"
  means. On best tiles (no faces) medium flags 2 of 31 real frames (DSC07701 0.657, DSC07761
  0.604: the two frames with the lowest Laplacian variance of the indoor shoot).

## 3. Faces and eyes

- **ML Kit face detection**, the **bundled** model (`com.google.mlkit:face-detection:16.1.7`, not
  the Play-services variant: works on devices without Google Play and offline), on the analysis
  image. Options: `PERFORMANCE_MODE_ACCURATE`, `CLASSIFICATION_MODE_ALL` (eye-open and smiling
  probabilities), no landmarks, no contours, `minFaceSize 0.04` (of the image width).
- **Prominent** face: box width ≥ **5%** of the image's long side **and** ≥ **40%** of the largest
  face's width. Only prominent faces count for eyes and as the sharpness subject; boxes are
  reported normalised to 0..1 (also used by **Zoom to face** in the viewer).
- **"Eyes closed"**: some prominent face has **both** eye-open probabilities below **T**: low 0.15,
  **medium 0.30 (default)**, high 0.50 (half-closed eyes too). An eye ML Kit cannot judge
  (profile, occlusion: probability absent) never counts as closed.
- **Calibration**: 31 real frames, 70 faces (67 prominent). At medium, **0** frames flagged. ML Kit
  often reports one eye low on babies and three-quarter views (e.g. L 0.02 / R 1.00), which is
  why both eyes must be closed. These shoots have no frame with clearly closed eyes, so the
  true-positive rate is **not verified** on real photos (see "Not verified").
- Another platform can use any detector that returns face boxes and per-eye open probabilities.
  Apple Vision returns face boxes and landmarks but, to our knowledge, no eye-open probability; an
  eye aspect ratio from the eye landmarks is the usual substitute and needs its own calibration
  against these thresholds.
- **Licence and privacy**: ML Kit is proprietary Google software under the
  [ML Kit Terms](https://developers.google.com/ml-kit/terms) (not open source; Hoard's own code
  stays AGPL-3.0-or-later; F-Droid would not accept this dependency). Images are processed on the
  device and never sent; per Google's
  [Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure), ML Kit
  sends **usage and performance metrics** (device information, event types, error codes) to
  Google over HTTPS. Hoard does not disable that; it is listed in "Consequences".
- **R8**: ML Kit ships its consumer rules; no rule was added. Verified: the minified release build
  (signed with the debug key, on the emulator) detects the same faces as the debug build.
- **APK size**: ML Kit's native library is **8.5 MB (arm64-v8a)** and **9.7 MB (x86_64)**, stored
  uncompressed; its models 3.1 MB (2.4 MB in the APK); dex +0.65 MB. Shipping all four ABIs made
  the universal release APK **55.2 MB**; Hoard keeps ML Kit's library only for the ABIs
  `libhoardimage.so` is built for (arm64-v8a, x86_64) and drops the unused contour model
  (1.1 MB): **17.7 MB → 39.2 MB (+21.5 MB)** universal. Per arm64 device (Play split APKs) about
  **+12 MB installed, ~+6 MB download** (the library gzips to 3.4 MB). On armeabi-v7a / x86 devices
  face detection reports itself unavailable and analysis runs without faces.

## 4. Exposure

- **Histogram**: 64 bins of luma, bin = `floor(Y / 4)`, as fractions of the pixels.
- `mean` = mean luma / 255; `highlights` = share of pixels with `Y ≥ 250`; `shadows` = share with
  `Y ≤ 5`.
- **Over-exposed**: `highlights ≥ 0.10` or `mean ≥ 0.70`. **Under-exposed** (only when not over):
  `shadows ≥ 0.08` or `mean ≤ 0.20`.
- **Calibration** (31 frames; ±EV applied in linear light: decode sRGB, scale by 2^EV, encode,
  clip): real frames mean 0.30–0.56, highlights 0–8.4%, shadows 0–0.55% → **0 flagged**. +1 EV:
  15/31 over, +2 EV: 26/31; −2 EV: 19/31 under; −1 EV: 0/31 (one stop is within taste).

## 5. Similarity signature

- **dHash**: luma area-averaged (exact, as above, kept in floating point) to **9 × 8**; bit
  `r·8 + c` (most significant first) is 1 when cell `(c, r)` is brighter than `(c+1, r)`.
  16 hex digits.
- **Colour signature**: the RGB image area-averaged to **4 × 4**, rounded; 48 bytes (R, G, B per
  cell, row-major), 96 hex digits. Distance = mean absolute byte difference / 255.
- Measured between consecutive frames: same burst (gaps 0.16–1.9 s) **hamming 1–19, colour
  0–0.09**; different scenes **hamming 22–40, colour 0.059–0.235**.

## 6. Bursts

Shots in capture order (EXIF time with sub-seconds; ADR 0008). Shot _n_ joins shot _n−1_'s burst
when both have a capture time, the time does not go backwards, and either

1. `gap ≤ G` (the **burst gap**, default **1 s**; setting 0.5 / 1 / 2 / 3 s) and the frames are
   **not a scene change** — `hamming ≥ 21 and colour ≥ 0.04`, or `colour ≥ 0.15` — (frames not
   analysed yet never split a run), or
2. `gap ≤ 4·G` and the frames are **near-identical**: `hamming ≤ 12 and colour ≤ 0.06`.

Runs of 2+ shots are bursts. On the real shoots (G = 1 s): Celine {07668, 07673} (1.8 s apart,
joined by rule 2), {07699, 07701, 07702}, {07704, 07705}, {07741, 07746}; Dia dos pais {07786,
07791} (3.1 s, rule 2), {07828, 07834}, {07849, 07855, 07864, 07869}. Time alone found 3 and 2
of these. No scene was wrongly joined.

## 7. Ranking and suggestions

- **Rank inside a burst**: `score = subject / max(subject in burst) − Σ penalties`, penalties
  eyes closed 1.0, blurry 0.6, over / under-exposed 0.4; ties keep capture order. (Laplacian
  variance compares well inside one scene; blur is used for the flag.)
- **Suggestions** (on "Suggest", never automatic):
  - every burst whose frames are all analysed: **keep** the best ("Best frame of the burst · eyes
    open"), **reject** the others with why: their flags, "Blurrier than DSC…" when
    `subject < 0.8 × best's`, else "DSC… is a better frame". When every frame is flagged none is
    kept ("every frame of this burst has a problem").
  - single shots: **reject** when flagged; unflagged singles get no suggestion.
  - shots the user already picked or rejected get none; accepting writes ordinary decisions (one
    undo step per accept, with an Undo in the toast).
- On the real shoots: Celine keeps 07668, 07699, 07704, 07746 and suggests rejecting 07673, 07701,
  07702 (blurrier than 07699), 07705, 07741; Dia dos pais keeps 07791, 07834, 07849.
- **Compare** opens on the burst's top two frames; its crown marks the better-ranked one.

## 8. Pipeline and caching

- **Contract** (`definitions.ts`): `cullAnalyze({ cullId, location, shotIds ≤ 20, urgent? })` →
  `{ results: [{ id, analysis? | error? }], faces }` (`CullAnalysis` has every number above).
  Native runs the shots **one after another** on the image core's scheduler at priority 60
  (behind the viewer's prefetch and grid thumbnails; `urgent` = 30 for the shot on screen), so
  analysis uses at most one worker and never delays the image on screen. `cullClose` cancels a
  folder's running analyses (`CANCELLED`). Results are memoized natively (4,000 entries) by file
  identity + `VERSION`. Mock: `web.ts` / `mockShoot.ts` (`photoAnalysis`, deterministic), tested in
  `web.test.ts`.
- **App** (`useCullAssist`): cached rows first (`cull_analysis`, migration v6, ADR 0003), valid
  while the analysed file's path, size, modified time and the analysis version match; the rest in
  batches of 3, nearest to the shot on screen first (ahead before behind), then sweeping the
  folder; each batch saved in one transaction through the folder's write queue. A failed batch is
  retried twice with back-off, then its shots count as "could not be analysed" until the folder is
  reloaded.
- **Bump `ShotAnalysis.VERSION`** (and `ANALYSIS_VERSION` in `assist.ts`) whenever a measurement
  changes: every cached row is then measured again.

## 9. XMP sidecars

"Copy picks to…" has **Write ratings as XMP sidecars** (off by default, remembered). For each
copied shot one `<stem>.xmp` is written **at the destination only**, next to the copy, by the
transfer engine itself (`startTransfer({ sidecars: [{ sourcePath, content }] })`: after the file is
copied and verified, `TransferRunner` writes `<destination stem>.xmp` through the same destination
endpoint, via a partial name and a rename; a conflict rename carries over, `DSC1 (1).ARW` →
`DSC1 (1).xmp`; a skipped file gets none; an existing sidecar of that name is replaced; a sidecar
that cannot be written is logged and does not fail the photo). It is attached to the shot's RAW
when the RAW is copied (one sidecar per RAW + JPEG pair, as Lightroom expects), else to the copied
JPEG. Content: `xmp:Rating` (0–5 stars; rejects are never copied), `xmp:CreatorTool`,
`xmp:MetadataDate`; no develop settings, no `xmpDM`. `<basename>.xmp` next to a RAW is the
convention Lightroom Classic reads; darktable and digiKam can import such sidecars. Lightroom
ignores a `.xmp` next to a JPEG-only shot (it reads XMP inside JPEGs). None of the editors was
tried (see "Not verified").

## UI

- Grid: bursts **stacked** (one cell, its best frame, a count; tap the count to expand, tap the
  first frame's badge to stack again), crown on the best frame, marks for blurry / eyes closed /
  over / under; sort by time or sharpest first (stacks only in time order); analysis filter (any
  problem, each flag, no problems); "Analysing 120 of 300" next to the decision progress, never
  blocking.
- **Suggest** shows suggestions as dashed outlines and "Keep? / Reject?" chips (never like a
  decision), a bar with "Accept all" and "Hide", per shot and per burst accept in the viewer.
- Viewer: the analysis panel under the camera data (sharpness and where it was measured, faces and
  eyes with **Zoom to face**, a 64-bar histogram with clipped ends, flags, the suggestion and why).
- Settings (Settings › Culling and the gear on the culling page): blur and eyes sensitivity, burst
  gap.

## Measurements

Emulator `hoard-assist` (Android 16 / API 36.1, arm64 on the Apple Silicon Mac mini, 3 GB).

| What                                                                                   | Result                                                                               |
| -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Analysis per shot, preview, local file (decode + resample + metrics + ML Kit accurate) | 163–252 ms, median 200 ms (first of a run 357–392 ms: model load); 31 shots in 6.6 s |
| Whole folder in the app (17 shots, local, from "Cull photos" to "All shots analysed")  | 4.5 s                                                                                |
| Reopening the folder after an app restart                                              | "All shots analysed" after 57 ms, 0 native analyses (cache)                          |
| SMB (emulator's slow link), pairs (preview reads)                                      | 14 shots in 5.2 s, ~370 ms per shot                                                  |
| SMB, JPEG-only shots (whole JPEG read) / RAW-only shots                                | ~700 ms / ~330 ms per shot                                                           |
| Memoized native result                                                                 | 4 ms for 3 shots                                                                     |
| Browser mock, 320 RAW-only frames in one burst, no faces                               | analysed in 17 s; one stack; "keep 1, reject 319"                                    |

A 300-shot card therefore takes about a minute of background work on first open, with the shot on
screen always analysed next.

## Testing

- TS: `assist.test.ts` (flags and sensitivities, distances, burst grouping: time, scene split,
  colour split, near-identical join and its limit, missing/backwards times, 320 frames in one
  burst; ranking, suggestions, decisions win, partial bursts, all-flagged bursts, compare pair,
  sorting, grid stacks), `xmp.test.ts` (well-formed XML parsed with xmldom, namespaces, rating
  clamp, escaping of `& < > " '`, tabs, newlines and invalid characters, one sidecar per pick on
  the RAW), `db.test.ts` (migration v6, cache rows), `web.test.ts` (mock `cullAnalyze`, sidecars).
- Kotlin (JVM): `ShotAnalysisTest` (sizes, exact area resampling, blur/Laplacian on synthetic
  blur, faces and prominence, exposure, hashes), `TransferRunnerTest` (sidecars at the destination
  only, after conflict renames, not for skipped files, JSON round trip).
- Opt-in on real files (copies; never committed): `AnalysisCalibrationTest` (JVM,
  `HOARD_ARW_DIR`): preview vs JPEG, synthetic blur and ±EV; `AnalysisHarnessTest` (device, `-e
dir … -e synthetic true`): ML Kit faces, timings, synthetic blur on face regions.

## Not verified

- Closed-eye **detection on real closed eyes**: the calibration shoots have none; only the absence
  of false positives (0 of 31) is measured.
- On the owner's phone (S24 Ultra) and over Wi-Fi to the NAS; SMB numbers come from the emulator's
  slow link.
- Lightroom / darktable actually importing the sidecars (the XML is checked for well-formedness and
  the standard namespace and attribute only).
- Memory during analysis was not profiled (one analysis at a time: a decoded preview of ~7 MB, a
  JPEG-only shot ~24 MB while it is reduced).
- Calibration covers one camera (ZV-E10) and 31 shots; other cameras' previews may need their own
  thresholds.

## Consequences

- Suggestions are one tap away, decisions stay the user's; no automatic reject or delete exists.
- +21.5 MB universal APK (+~12 MB per arm64 device), a proprietary dependency, and ML Kit's usage
  metrics sent to Google (images are not). Removing ML Kit later only removes faces and eyes: the
  rest of the analysis is Hoard's own code.
- Up to 4,000 analyses in memory and ~1–2 KB per shot in SQLite (`cull_analysis`; never pruned
  automatically, deleted with a machine).
