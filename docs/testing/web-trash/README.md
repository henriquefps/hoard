# The trash from the web gallery (hoard-server 0.14.0): tests and screenshots

ADR 0023 "Amendment (0.14.0)", ADR 0025 "Amendment (0.14.0)" (the whole-library estimate, `no_space` as a wait), ADR
0024 "Amendment (0.14.0)" (the backup purge, `507 storage_full`). Synthetic files only.

## Go (`cd server && go test ./...`)

| Test | What it checks |
| --- | --- |
| `TestWebTrashShotRule` | the shot rule: the selected media, then `X.xmp`, `X.ARW.xmp`, `.X.xmp` only when no other media of the stem stays; another stem's sidecars never; case-insensitive names; AppleDouble `._X.xmp` ignored |
| `TestWebTrashMoveRestoreAndDelete` | a dry run (counts, nothing moved); a RAW+JPEG pair with every sidecar convention and a single JPEG moved into today's `.hoard-trash/<day>/<path>`; a path already taken today goes to `<day>-2`, nothing overwritten; the walk queued at once outside the budget; trashed for `include=trash`, a tombstone otherwise; `GET /web/trash` units (key, day, directory, `byServer`, `purgeAfter`); restore refused on a taken path with its name, then every file back (the hidden sidecar too), same id, live; delete permanently → tombstone after the walk |
| `TestWebTrashRefusedWithoutWriteMount` | `nasBlocked` in the dry run; `503 unavailable` reason `no_write_mount`, nothing moved, no `.hoard-trash` made; 501 ids `422`; a device key cannot use the route |
| `TestWebTrashChangedFileNotMoved` | a file rewritten since the walk: `changed`, left where it is |
| `TestWebTrashJournalRecovery` | a kill between link and unlink (both names, one inode) finished; a `moving` row whose file never moved forgotten |
| `TestWebTrashServerTrash` | a freed phone backup photo with a Reveal edit: warnings `onlyCopy` and `edits`; the one still on the phone skipped `on_phone`; trashed with `serverTrash` (`include=trash`), a tombstone otherwise; the blob kept and `DELETE /v1/originals` `409`; restore: live with its edit; trashed again and 31 days later the auto-empty purges it (blob and backup entry gone, tombstone); the rows forgotten |
| `TestWebTrashEmptyAndAutoEmpty` | Empty refused without the right count and size (`409` with the current figures); the auto-empty after `trashDays` takes only what the server moved with its size and mtime (a file replaced in the trash stays); then Empty takes the rest |
| `TestWebTrashHeldBusyAndMixed`, `TestWebTrashHeldEntry` | a video in a queued compression is skipped `busy` in a mixed selection, the other moves; a journal stopped after its trash link holds the entry: skipped, listed held, Restore and Delete refuse it, the auto-empty leaves it 400 days later |
| `TestCompressEstimateSpace` | `totalsOnly`: no items; `space` (`needed` = estSize + 64 MiB, `free`, `fits`, `known`), too little room, an unreadable free space; a replace without room waits `ready`/`no_space` with `space` (`where`, `needed`, `free`) |
| `TestBackupPurgeSettlesCompressions` | a phone with a freed photo, a replaced backup compression, a queued one, a NAS compression elsewhere and a Reveal upload: the page's `compressedKept`; the purge refused without `confirmCompressed`; then: the replaced one `purged`, the queued one `cancelled` (`backup_purged`), no row names an entry, the NAS one untouched; the kept original, the output, the freed photo and the queued video's blobs gone, Reveal's upload kept, the NAS file kept; tombstones in the feed; the phone's next report `404`, a new root can be made |
| `TestUploadStorageFull` | `507 storage_full` (free, needed) at the create with no upload row; before a chunk with nothing written (offset 0, part empty); with room again the chunk is stored |
| `TestRetryFailedAfterToolsChange`, `TestCPUProxyCameraAVI`, `TestImageDecodesCameraClips` (`HOARD_IMAGE`) | failed previews retried once when the tools change; the proxy of an MJPEG + μ-law AVI; seven camera clips decoded in full and proxied by the image's ffmpeg (all fail on `0.13.2-nvidia` with "no decoder found", all pass on the 0.14.0 build) |

## Web (`cd server/web && bun run lint && bun run typecheck && bun run test && bun run e2e`)

`src/lib/trash.test.ts` (groups by day and place, held units counted not acted on, Empty's figures, the batches' dry
runs summed, the space verdict). `e2e/trash.spec.ts` runs a fourth server (the library folder as its own write mount, a
backup root, a phone's backup of three photos freed on the phone): reject three shots, the Rejected filter, Select all,
Move to trash (the plan, then the files in today's `.hoard-trash` with every sidecar, the hidden one too); a freed phone
photo with the only-copy warning; the Trash view by day and place; a restore refused by a taken path, then done; Delete
permanently of the server copy; Empty trash with its count and size; Portuguese. `e2e/compress.spec.ts` gains the
compressed originals in the new Trash view, the whole-library estimate (and its shortfall, from an answer changed in the
test) and the `no_space` waiting state (an item answer changed in the test).

## Screenshots (`HOARD_WEB_SCREENSHOTS=1 bun run e2e`)

Light and dark, desktop (1440 × 900) and phone width (390 × 844):

- `move-*`: Move to trash of the Rejected filter (the dialog's plan); `move-only-copy-desktop-light`: the only-copy
  warning; `move-pt-BR-desktop-light`;
- `trash-*`: the Trash view (a day, the NAS folder's group open, the server backup's group);
  `restore-taken-desktop-light`, `empty-confirm-desktop-light`, `trash-pt-BR-desktop-light`.

The compression screenshots (`../web-compression/`): `library-estimate-*`, `library-estimate-short-*`, `no-space-*`, and
the compressed originals in the new Trash view (`trash-*`).
