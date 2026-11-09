# Text viewer and editor: end-to-end run

- **Date**: 2026-11-09
- **Build**: debug APK, rebased on `main` after PR #6 (streaming / web viewer)
- **Device**: Android emulator `hoard-text` (API 36.1 arm64, 2 GB, port 5560)
- **Server**: the disposable Samba in `docs/testing/samba/` (256 MB / 1 CPU, `127.0.0.1:14470`,
  reached as `10.0.2.2:14470`), which now also holds `media/Docs/` (`notes.md`, `crlf.txt`,
  `latin1.txt`, a 6 MB one-line `big.log`)

The WebView was driven over CDP (`tools/cdp.mjs` on another port and serial), the keyboard with
`adb shell input text`, Android back with `KEYCODE_BACK`. Files were checked on the server with
`docker exec … od -c`.

## Bug found and fixed

| Bug                                                                                                                                                                                                                                                                                | Fix                                                                                                            |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Every file saved through the atomic replace became **hidden**: the temp file was created with `FILE_ATTRIBUTE_HIDDEN`, and attributes travel with the rename (Samba stores the DOS attribute), so the saved file vanished from the listing. Caught on the server, not by the mock. | The temp file is only dot-named (no HIDDEN attribute); `SmbIntegrationTest` asserts the saved file is visible. |

## Checklist

| Item                                                                                                                                                                           | Result                                                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------- |
| Tap `notes.md` in the browser → viewer, rendered Markdown (task list, table, link)                                                                                             | Pass                                                  |
| Edit, type, Save → file on the server has the new text; toast                                                                                                                  | Pass (written via temp file + rename; no `.tmp` left) |
| Edit on the server meanwhile, save in the app → conflict dialog                                                                                                                | Pass                                                  |
| Conflict → Save mine as a copy → `notes (1).md` created next to it, opened in edit mode; the server's version untouched                                                        | Pass                                                  |
| Conflict → Overwrite with mine (on `latin1.txt`) → server change replaced                                                                                                      | Pass                                                  |
| windows-1252 file: detected, shown correctly (ó ç ã €), saved back as windows-1252 bytes                                                                                       | Pass (`od -c`: 363 347 343 200)                       |
| CRLF file edited and saved → still CRLF                                                                                                                                        | Pass (`\r\n` on every line)                           |
| Android back with unsaved changes: first press closes the keyboard, next asks Save / Discard / Keep editing; Save saves and leaves edit mode; back again goes up to the folder | Pass                                                  |
| 6 MB file → "Too large" state → Load the first 5 MB → read-only notice, one 5 MB line (wrap off automatically) scrolls                                                         | Pass                                                  |
| Re-pin after the Samba container was recreated (new server GUID)                                                                                                               | Pass (existing flow)                                  |

Known limits (ADR 0001): the replaced file gets the share's default mode/attributes (on this
Samba `-rwxr--r--` after a save, from the archive bit), not the original file's ACL; the conflict
check and the rename are two steps.
