# Video compression in the web gallery, phase S3 (ADR 0025)

The web gallery's compression (ADR 0025 Amendment (S3)): **Compress videos** on a selection and on a folder (the
estimate, then a job), **Compress automatically** per folder, the **Compression** view (jobs, progress, history,
saved space, each item's state with reason, sizes, q, VMAF, timings, its log and the metadata download), the **Trash**
view (compressed originals with Restore and Delete permanently through the server's routes, held entries shown and
never emptied), and the grid's badges. Run on cachyos-fps on 2026-11-20, branch `web-compress-s3` on `main` 1863dbf
(hoard-server 0.13.1). No GPU, no NAS, no phone: synthetic clips on temporary directories and the e2e build's fake
encoder.

## How to run

```sh
cd server/web
bun install --frozen-lockfile
bun run lint && bun run typecheck && bun run test          # unit tests, src/lib/compress.test.ts among them
bun run e2e                                                # every spec; e2e/compress.spec.ts runs on its own server
HOARD_WEB_SCREENSHOTS=1 bun run e2e -- e2e/compress.spec.ts   # also writes the screenshots below
cd .. && go vet ./... && go vet -tags hoarde2e . && go test ./... && golangci-lint run ./...
```

**The server.** `e2e/global-setup.ts` builds the binary with `go build -tags hoarde2e` (the tag compiles in
`server/e2e_compress_fake.go`; no release, image or workflow uses it) and starts a third server on port 3993 with
`HOARD_E2E_FAKE_COMPRESS=1`, `NAS_READ_ROOT` = `NAS_WRITE_ROOT` = its temporary `mnt` (the library's folder is its own
write mount: the same directory, so the `st_dev`/`st_ino` proof holds) and `BACKUP_ROOT` = `mnt/backup`. The fake
encoder is libx264 at 1 Mb/s writing the marker; its verifier passes (VMAF 95.4, 1 % low 92.1, full clip); the GPU is
"Fake GPU (web e2e)" and never busy; a source named `HOLD` gets its trash link and then `EIO` on every try, so its
replace journal waits with the original in the trash (a held entry) for the whole run.

**The library** (`TestWriteWebCompressFixture`, opt-in with `HOARD_WEB_COMPRESS_FIXTURE`): six synthetic 3 s 720p
clips (ffmpeg `testsrc2` with noise, H.264 at 16 Mb/s, AAC) and two drawn JPEGs: `Videos/Drone/DJI_0001.MP4`,
`DJI_0002.MP4`, `DJI_HOLD_0003.MP4`, `OLD_0004.MP4` (HEVC by libx265, encoder tag `Lavc…`: "already compressed by an
older tool"), `Videos/Trips/C0010.MP4`, `C0011.MP4`, `Videos/Drone/DSC09000.JPG`, `DSC09001.JPG`. It is written once
into `e2e/.library/compress-pristine/` and copied afresh for every run (a run replaces files and fills the trash).

## What the e2e checks (`e2e/compress.spec.ts`, 8 tests, about 2 minutes)

| Step | Result |
| --- | --- |
| The server offers compression | `GET /web/compress`: `available`, `targets.nas.ok`; the **Compression** link in the header; "GPU: Fake GPU (web e2e)"; "Nothing is being compressed." |
| Folders › Videos › Drone › ⋮ › Compress videos in this folder | "Library videos: the server replaces the files in the library.", Same look chosen, AV1 not offered, "3 of 4 videos · 21.1 MB → about 2.2 MB · under a minute", "1 already compressed by an older tool", the trash note (30 days). Start: "3 videos queued"; the items end `replaced` ×2, `replacing` (HOLD), `skipped` (OLD) |
| The Compression view | the job "nas/Videos/Drone", its skip line, "1 compression waits"; its items: Compressed, "VMAF 95.4 (1 % low 92.1, full clip)", "q 28", OLD "already compressed by an older tool"; **Log** opens the item's log; **Download metadata** answers `200`, `DJI_0001.metadata.json.gz`, gzip |
| Gallery badges | "Compressing" on the held video at once; "Compressed" on the two outputs after the walk (a requested walk waits up to a minute, ADR 0023) |
| Trash | DJI_0001 and DJI_0002 "Original of a compressed video · saves … when emptied" with Restore original and Delete permanently; DJI_HOLD_0003 "Held by a compression in progress" with no button, not counted ("Empty 2 originals"). Restore DJI_0001: the record `restored`. Delete DJI_0002 permanently: the confirmation "1 original (… MB) is deleted for good by the server.", then "1 original emptied", gone from the list at once; the held entry still there. After the walk DJI_0001 is listed as "Compressed video (the original was restored)" with **Compress again** |
| Gallery selection (C0011.MP4 and a photo) › Compress videos | "Compress C0011.MP4", "1 of 1 video" (the photo left out); "1 video queued" |
| Folders › Videos › Trips › ⋮ › Compress automatically | the automatic sheet, "Also compress the 1 video already there" (C0011 is in the selection's job: "1 already in another job"); Turn on: one standing job, `createdBy: "admin"`; the Compression view lists "Videos/Trips"; Turn off: "No folder yet." |
| Portuguese | "Comprimir vídeos", "Lixeira", "Retido por uma compressão em andamento" |

Go (`server/internal/server/web_compress_test.go`, `TestWebCompress`, the S1 fake encoder): the routes need the
gallery's session (`401`), the CSRF header (`403`) and the LAN (`404`); `GET /web/compress`; estimate and job through the
twins (`createdBy: "admin"`); the log and the metadata download (headers, gzip, `404`, `401`); `GET /web/trash` (two
compressed originals, the held live item); the library index's `cz`; restore and purge (a purged row left out before the
walk; after the walk the restored output is the trashed side); Compress automatically on and off by `PATCH`.

## Screenshots (`docs/testing/web-compression/`)

Light and dark, 1440 × 900 and 390 × 844, from the e2e run with `HOARD_WEB_SCREENSHOTS=1`:

| View | Files |
| --- | --- |
| The estimate (Folders › Videos › Drone) | `estimate-{desktop,phone}-{light,dark}.png` |
| The Compression view, the job opened | `compression-{desktop,phone}-{light,dark}.png`, `compression-pt-BR-desktop-light.png`, `log-desktop-light.png` |
| Grid badges | `gallery-badges-{desktop,phone}-{light,dark}.png` |
| Trash (compressed originals, the held entry) | `trash-{desktop,phone}-{light,dark}.png`, `trash-pt-BR-desktop-light.png`, `trash-purge-confirm-desktop-light.png` |
| Selection | `selection-desktop-light.png` |
| Compress automatically | `auto-{desktop,phone}-{light,dark}.png` |

**Found and fixed during the run**: the header did not fit a phone with four sections (the app name is hidden under
640 px and the section labels under 1024 px); "1 of 2 video" (the noun now agrees with the total); "about under a
minute left"; the trash list showed a purged original, and a restored one under its old label, until the next walk (the
record decides now). Not run here: a backup (`target: "backup"`) job (no phone backup in this library; the selection's
split and the Folders section are covered by `compress.test.ts` and the Go test lists the roots); the real NVENC encoder
(the server's GPU test, S1).

**Also fixed**: `TestCompressPurgeOutputTrashedByHand` moved the output to `.hoard-trash/2026-11-20/A.MP4`, which on
2026-11-20 is the replace's own trash path of the original: the move overwrote the original and the test failed that
day only. It uses a past day now.
