# Server photos in the trash through the server (Hoard 2.10.0, ADR 0023 Amendment (2.10.0))

## JVM and Robolectric

| Suite | What it checks |
| --- | --- |
| `:core:server` `ServerTrashTest` (fake hoard-server 0.15.0, `FakeTrash`) | dry run in 500-item batches with counts, size, skips (held, busy, on_phone, trashed), only-copy and edits warnings; the write mount's 503 (dry run `nasBlocked`, move fails each item with the reason); partial failures, Retry the failed ones and the merged result; 429 waits Retry-After; an unreachable server; Cancel between batches; the listing paged, grouped by day and place, held listed and not counted; restore (path_taken with its name) and delete by unit, held never sent; Empty by group with a group left out; 409 with the current figures, then Empty again; a shot in two groups (the server's own total resent once); auto-empty takes only Hoard's old SMB moves (`by` absent) older than the limit, never held, compressed or server-moved units; the SMB trash index by folder and `.hoard-trash` tail; backup-only units (restore yes, delete never sent, `webOnly` figures, Empty leaves them, the 409's `webOnly`); `403 not_hoard` |
| `:core:server` `ServerTrashRealJsonTest` | hoard-server 0.15.0's real answers (`origin/main` fa7ce45, recorded from its own harness into `core/server/src/test/resources/trash-0150/`): the backup-only unit (`webOnly: true`) listed restore-only and never sent to delete, the server's `web_only` refusal decoded, the listing's `webOnly` figure kept apart from the groups, the 409's figures (groups + `webOnly`) round-tripping into the next Empty |
| `:native` `ServerReasonTextTest` | `web_only` and `not_hoard` in words, en and pt-BR |
| `:native` `ServerRoutingTest` | culling's rejects in a mapped folder go to the server by item (sidecar with the shot); Files: a text file and an empty folder stay SMB, a folder of photos is refused; the JPEG of a RAW + JPEG is refused, the pair goes; a photo the server has not indexed is refused and its folder walked; the server unreachable refuses (never SMB); a folder not on the server stays SMB; server paths from the mapping's prefixes |
| `:native` `ServerTrashSplitTest` | a shot the server lists is shown once (its rows hidden, by item); one not listed yet waits; plain files and other places stay Hoard's; the server unreachable: every server-folder photo waits; a recorded root without a mapping; no feature: all Hoard's |
| `:native` `GalleryTrashServerTest` | with `trash-actions` every server item goes to the server, the phone's files to the phone's trash, nothing left out |

Goldens (`:native:recordRoborazziDebug`, copies in [`server-trash/`](server-trash/)): `trash_server_{phone_light,phone_dark,phone_pt,tablet_light,tablet_dark}` (the server's trash next to the phone's), `trash_server_expanded_phone_light` (a place opened: each photo, a held one, a backup-only one), `trash_server_unreachable_phone_light`, `trash_server_confirm_{phone_light,phone_dark,phone_pt}` and `trash_server_confirm_blocked_phone_light` (Move to trash's server part), `trash_server_result_{phone_light,phone_pt}` (per item, with reasons), `trash_empty_groups_{phone_light,phone_dark,phone_pt}` and `trash_empty_groups_changed_phone_light` (Empty by group, after a 409), `trash_settings_server_{phone_light,phone_pt}`.

## End to end (2026-11-27)

- **Server**: hoard-server built from `origin/server-0150` at 28449aa (`go build`, version `0.15.0-dev`). The binary
  was mounted into the `hoard-server:0.14.0-nvidia` image, which only lends its preview tools, as container
  `hoard2100-server`: `--user 1000:1000 --cpus 2 --memory 1536m`, `127.0.0.1:3291`, `NAS_INTERVAL=1m`.
- **NAS**: a temporary directory, mounted twice: `/mnt/nas` read-only, and `/mnt/nas-write` as the write mount. NAS
  folder `Photos`. Admin set-up, the folder and the pairing link went through the admin API; the link was typed into
  Hoard.
- **Photos**: synthetic JPEGs (ImageMagick plasma, nothing private), in three folders: `2026/Rejects` R_0001–6,
  `2026/Keep` K_0001–3, `2026/Files` F_0001–2 plus `readme.txt`.
- **Files trashed "before 2.10.0"**: three files placed in `Photos/.hoard-trash/2026-11-20/2026/Old/` and
  `…/2026-10-01/…` (mtime Oct 1), as Hoard's SMB moves leave them. The R_* items were marked rejects through the web
  gallery's culling API.
- **Samba**: container `hoard2100-samba` (`docs/testing/compression/a1fake/Dockerfile.samba`) serving the same
  directory as share `photos` on `127.0.0.1:14491`. It was added in Files only for the de-dup and Files steps, with
  `10.0.2.2:14491` typed by hand; the discovered LAN machine was not tapped.
- **Emulator and app**: my own AVD `hoard-2100` (API 36, `-port 5590 -memory 3072 -no-window`), the debug build, All
  files access granted.

| Step | Result | Screenshot |
| --- | --- | --- |
| Gallery › Filters › Rejected › long press › Select all › Move to trash, **no SMB machine configured** | dry run: "6 photos (921 KB) go to the photo server's trash", the server's auto-empty | [`e02`](server-trash/e2e/e02-confirm.png) |
| R_0005 touched on disk, then Move to trash | "5 moved to the trash · 1 not moved: the file changed…", **Retry the failed ones** | [`e03`](server-trash/e2e/e03-result.png) |
| After the server's walk, Retry the failed ones | "6 moved to the trash"; `Rejects/` empty, all six in `Photos/.hoard-trash/2026-11-27/2026/Rejects/` (moved by the server, `by` = the device) | [`e04`](server-trash/e2e/e04-retried.png) |
| Trash | "On the photo server": Nov 27 › Photos › 2026/Rejects (6), Nov 20 and Oct 1 › 2026/Old (the files trashed before) | [`e06`](server-trash/e2e/e06-trash.png) |
| Files › E2ESamba › photos › Photos › 2026 › Files (mapping learned), select F_0001.JPG + readme.txt › Delete | one confirmation: the text file to the share's trash, "1 photo (1 file, 152 KB) goes to the photo server's trash" (wording fixed after the run); the server's log: `trash: moved 1`; readme.txt moved over SMB | [`e08`](server-trash/e2e/e08-files-confirm.png) |
| Trash after Files' refresh had listed `Photos/.hoard-trash` | `hoard.db` holds 10 SMB rows (`run_id` `scan`) for the server's files: each shown **once**, as the server's; readme.txt under "On this phone and your drives" | [`e09`](server-trash/e2e/e09-trash-dedup.png) |
| Tap 2026/Old (Nov 20) › OLD_0001.JPG › Restore | `POST /v1/trash/restore`: back at `Photos/2026/Old/OLD_0001.JPG` (an SMB-trashed file from before, restored through the server) | [`e10`](server-trash/e2e/e10-expanded.png) |
| 2026/Rejects › R_0001.JPG › Delete permanently | "Delete 1 photo permanently? … from the photo server"; `POST /v1/trash/delete`, the file gone | [`e12`](server-trash/e2e/e12-delete-confirm.png) |
| Settings › Trash | "Server photos are emptied by the photo server after 30 days" | [`e13`](server-trash/e2e/e13-settings-trash.png) |
| Empty automatically › After 7 days | "2 files (305 KB) older than 7 days…": OLD_0002 (Nov 20) and OLD_0003 (Oct 1), `by` absent. Delete and apply: `auto-empty (server): 2 deleted`; the rejects the device moved stay (the server's auto-empty) | [`e14`](server-trash/e2e/e14-shorten.png) |
| K_0003 moved from the web gallery; Trash › Empty trash | three lines with count, size and a checkbox: web 1, Hoard 6, this phone/drives 1 file | [`e16`](server-trash/e2e/e16-empty-groups.png) |
| Web unchecked; meanwhile a file dropped into `.hoard-trash` and walked; Empty trash | `409 conflict`: the dialog again, "The trash changed…", Hoard 7 photos | [`e17`](server-trash/e2e/e17-empty-409.png) |
| Web unchecked again, Empty trash | `trash: emptied groups=hoard units=7`, readme.txt deleted over SMB; K_0003 (web) stays | [`e18`](server-trash/e2e/e18-after-empty.png) |

**Found and fixed during the run**: a Trash place could only be restored or deleted whole, so a single photo could not
be; a place now opens on tap, with a menu per photo. The confirmation said "1 files"; it now uses its own plural. After
a 409 every group came back checked; the owner's unchecked groups now stay unchecked.

**After the run**: the containers `hoard2100-server` and `hoard2100-samba` and the image `hoard2100-samba` were
removed, and the emulator stopped. The AVD `hoard-2100` and the temporary directories live only in my scratch space and
user AVD directory.

**Not run here**: a held entry and a backup-only unit on the emulator (no GPU, no phone backup in this run): they are
covered by `ServerTrashTest` and the goldens. `server-0150b` (`web_only`, `not_hoard`) was not pushed yet when this ran:
the fake server answers its contract.
