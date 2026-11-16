# Trash, phase A2: screens and end-to-end run (ADR 0023)

> **Rule for emulator runs against a test Samba container** (since 2026-11-16): type the container's address (`10.0.2.2:<port>`) by hand in the machine form and never accept or tap a machine Hoard discovers. Discovery runs on the build host's LAN and finds the owner's machines; a discovered entry fills the form with one of them (it did in the trash run, below). Check the saved address before the first connection.

Hoard 2.3.0 (unreleased), branch `trash-a2-screens` on `main` 9144acc (A1, PR #58). Run on cachyos-fps on 2026-11-16.

## Goldens (Roborazzi)

`TrashScreenshotTest` (`:native:recordRoborazziDebug` / `verifyRoborazziDebug`), copied here from
`android/native/src/test/screenshots/`:

| Golden | What |
| --- | --- |
| `trash_phone_light.png`, `trash_phone_dark.png`, `trash_tablet_dark.png` | The Trash screen: by day, then place and folder, with file counts and sizes; Refresh and Empty trash |
| `trash_unreachable_phone_light.png` | A USB drive that is not plugged in: its rows greyed, "Not reachable", actions disabled |
| `trash_empty_phone_light.png` | Empty trash, auto-empty off |
| `trash_confirm_phone_light.png`, `trash_confirm_phone_dark.png` | The confirmation's body: files, size, where; auto-empty note; the old-server warning; a refusal (case 5) |
| `trash_progress_phone_light.png` | The progress of a run |
| `trash_settings_phone_light.png`, `trash_settings_phone_dark.png` | Settings › Trash: Open the trash, auto-empty 7 / 30 / 90 days / never, Media management (comes with A3) |
| `trash_permanent_files_phone_light.png` | The permanent-delete offer of a Files delete on the phone (before A3): exactly the 2 selected JPEGs, their size and place, never the DNG of the same shot |
| `trash_permanent_shots_phone_dark.png` | The offer in culling (a read-only trash root): shots, files, size and place, and why |
| `trash_shorten_phone_light.png` | Settings › Trash, a shorter period: files and size it would delete now, other devices' included |
| `trash_phone_entries_phone_light.png` | Phase A3: a phone group (Internal storage › DCIM/Camera) with "Android deletes them on …" (`DATE_EXPIRES`) |
| `trash_media_ask_phone_light.png` | Phase A3: the first phone trash dialog, with the one-time Media management offer |
| `trash_permanent_folder_phone_light.png` | Phase A3: a phone folder (Android's trash takes files only): the permanent-delete offer |
| `trash_settings_media_allowed_phone_dark.png` | Phase A3: Settings › Trash with Media management allowed (`trash_settings_phone_*`: Robolectric's Android has none, "Needs Android 12 or later") |
| `trash_empty_confirm_phone_light.png` | Empty trash with a drive not reachable: only the reachable files counted, the rest named |

The dialogs are captured as their body on a card (an `AlertDialog` opens its own window, which a root capture does not
include). `verifyRoborazziDebug` passes for every golden of `:native`; the Files and Gallery goldens are unchanged (the
Trash buttons show only when the screen is given `onOpenTrash`).

## End to end: emulator against a Samba container

- Emulator `hoard-burst` (API 36, `-memory 3072 -no-window`), the debug build (`dev.hfps.hoard`, debug key).
- Samba: [`trash/samba/Dockerfile`](trash/samba/Dockerfile) (Alpine Samba 4.19.9), files made by
  [`trash/samba/make-files.py`](trash/samba/make-files.py) (synthetic coloured JPEGs, never real photos):
  - share `media`: `Shoot/DSC00001…5.JPG`, `Shoot/DSC00001.xmp` (rating −1: a reject), `Docs/report.txt`, and a day
    folder another device left: `.hoard-trash/2026-10-01/Old/OLD00001.JPG`;
  - share `rootro`: its root belongs to root (`755`), only `Photos/` is writable by `hoard`: a read-only trash root.

```sh
python3 docs/testing/trash/samba/make-files.py docs/testing/trash/samba   # writes samba/files/ (not committed)
docker build -t hoard-trash-samba docs/testing/trash/samba
docker run -d --rm --name hoard-trash-samba --memory 256m --cpus 1 -p 127.0.0.1:14470:445 hoard-trash-samba
```

The machine is `10.0.2.2:14470`, user `hoard`, password `hoardpass`, typed by hand (see the rule above). In this run
the machine form had been filled from discovery first (the owner's Mac's name and LAN address); the address was
replaced before saving and the saved machine held only `10.0.2.2:14470`, but that is exactly what the rule prevents. Nothing was paired (no photo server): every
folder is case 4, the trash at the share root. The app was driven with `adb shell input` and `uiautomator dump`; the
share was checked with `docker exec … find`. The container and its image were removed afterwards.

| Step | Result | Screenshot |
| --- | --- | --- |
| Files › Samba › media › Shoot, `DSC00002.JPG` › Delete | "Move 1 item to the trash? 1 file (5.7 KB) goes to the trash in Samba › media…", "The trash empties itself after 30 days" | [`e2e-01`](trash/e2e/e2e-01-files-confirm.png) |
| Move to trash | `.hoard-trash/2026-11-16/Shoot/DSC00002.JPG` on the share; "1 moved to the trash"; the listing no longer shows it | [`e2e-02`](trash/e2e/e2e-02-files-after.png) |
| Culling on Shoot: DSC00001 already rejected (its sidecar), reject DSC00003, Finish › "Move 2 rejects to trash…" | "Move 2 shots to the trash? 3 files (11.7 KB)…" (two JPEGs and `DSC00001.xmp`) | [`e2e-03`](trash/e2e/e2e-03-cull-confirm.png) |
| Move to trash | `DSC00001.JPG`, `DSC00001.xmp`, `DSC00003.JPG` in `.hoard-trash/2026-11-16/Shoot/`; the grid shows the 2 other shots | [`e2e-04`](trash/e2e/e2e-04-cull-after.png) |
| Files › Trash | Nov 16 (Samba › media › Shoot, 4 files · 17.4 KB) and Oct 1 (Old, 1 file: found by listing, another device's) | [`e2e-05`](trash/e2e/e2e-05-trash-screen.png) |
| A new `Shoot/DSC00001.JPG` written on the share, then Shoot › Restore | "2 files restored" (DSC00002, DSC00003 back at their paths); "Could not restore: a file is already at Shoot/DSC00001.JPG": the DSC00001 shot (JPEG and `.xmp`) stays in the trash, the newer file untouched | [`e2e-06`](trash/e2e/e2e-06-restore-conflict.png) |
| The Oct 1 day folder's mtime set to 2026-10-01 on the share (`touch -d`), Settings › Trash › After 7 days | The setting runs auto-empty at once: `.hoard-trash/2026-10-01` deleted, today's kept; `.hoard-trash/.hoard-clock` (the share's clock probe) written | [`e2e-07`](trash/e2e/e2e-07-settings.png) |
| rootro › Photos › `DSC00004.JPG` › Delete › Move to trash | Refused `ROOT_NOT_WRITABLE` (`STATUS_ACCESS_DENIED` creating `.hoard-trash`): nothing moved, no rows; the dialog offers "Delete 1 item permanently?" | [`e2e-08`](trash/e2e/e2e-08-readonly-offer.png) |
| Delete permanently | `Photos/DSC00004.JPG` deleted | — |
| Trash › Empty trash | "Empty the trash? 2 files (5.9 KB) will be deleted for good." | [`e2e-09`](trash/e2e/e2e-09-empty-confirm.png) |
| Empty trash | "2 files deleted for good"; `.hoard-trash` holds only `.hoard-clock`; "The trash is empty" | [`e2e-10`](trash/e2e/e2e-10-emptied.png) |
| 30 files in `media/Bulk`, Select all › Delete › Move to trash | Progress dialog ("3 of 30 files"…); `TrashRunService` started as a foreground service and stopped at the end (ActivityManager log); 30 files in the trash | — |

**Found and fixed on the way**: the first Files delete was refused. `SmbFs` throws smbj's own `SMBApiException`
(`FileService` maps errors only around a whole block), so the trash took "no file at this target path" for a failure.
The runner now maps every call through `MappedHoardFs`; `smbjsOwnNotFoundIsAFreePathNotARefusal` (the in-memory fs
throwing smbj's exception) covers it.

**Not run here**: a photo server (mapped folders, `POST /v1/nas/walk`, the old-server warning on a real server: needs
hoard-server 0.11.0, phase A4's run), the phone's MediaStore trash (A3), the notification's Cancel button (Cancel in
the dialog is the same call; covered by `cancelStopsBetweenUnits`).

## Phase A3: the phone's MediaStore trash (emulator, API 36)

Emulator `hoard-burst` (API 36, `-memory 3072 -no-window`), the debug build (`dev.hfps.hoard`), All files access
granted. Synthetic coloured JPEGs (never real photos) pushed with `adb push` to `/sdcard/DCIM/Camera/` (rows owned by
`com.android.shell`, so not Hoard's), plus `IMG_0001.xmp` and a folder `DCIM/OldStuff/`. The consent path was forced in
the debug build with the flag file `/sdcard/Android/data/dev.hfps.hoard/files/trash-force-consent` (`tryDirect` off);
Media management was switched with `adb shell appops set dev.hfps.hoard MANAGE_MEDIA allow|deny|default`. Everything
pushed, the flag and the appops were removed afterwards and the emulator stopped.

**The ADR's open question, answered**: with **All files access**, a direct `IS_TRASHED` update of rows Hoard does not
own **is allowed** (no consent, no `RecoverableSecurityException`): `IMG_0001.JPG` and `IMG_0003.JPG` went to Android's
trash (`.trashed-<expiry>-<name>` on disk) without any dialog. So with All files access (which the phone's Files tab
needs anyway) Hoard trashes, restores and deletes directly; the consent requests serve when it is not granted.
Second finding: Android's consent requests take **media rows only** ("All requested items must be Media items" for a
sidecar's `MediaStore.Files` URI), so sidecars are always changed directly (All files access allows it).

| Step | Result | Screenshot |
| --- | --- | --- |
| Files › Internal storage › DCIM › Camera, `IMG_0001.JPG` › Delete (the first phone trash) | "Move 1 item to the trash? 2 files (6 KB)…" (the photo and its `.xmp`, its last member) and the one-time "Allow Media management" offer | [`p01`](trash/phone/p01-confirm-ask.png) |
| Move to trash (All files access, nothing forced) | Both files trashed directly, no consent; the offer did not come back in later dialogs | — |
| Consent forced, `IMG_0004.JPG` › Delete › Move to trash | Android: "Allow Hoard debug to move this photo to trash?"; **Deny**: refused `DECLINED`, the file stays, no row; again, **Allow**: trashed | [`p03`](trash/phone/p03-consent.png) |
| `MANAGE_MEDIA` allowed, consent forced, `IMG_0005.JPG` | The request is approved with no dialog (`consent TRASH 1: true`) | — |
| Files › Trash | Internal storage › DCIM/Camera with "Android deletes them on Dec 16, 2026" (30 days, `DATE_EXPIRES`) | [`p05`](trash/phone/p05-trash.png) |
| A new `IMG_0005.JPG` pushed, Media management denied, DCIM/Camera › Restore | `IMG_0005.JPG` refused ("A file is already at DCIM/Camera/IMG_0005.JPG"); Android: "Allow Hoard debug to move 2 photos out of trash?" › Allow: `IMG_0003.JPG`, `IMG_0004.JPG` back, the sidecars restored directly | [`p06`](trash/phone/p06-restore-consent.png) |
| DCIM/Camera › Delete permanently | "Delete 1 file permanently? 1 file (5.9 KB)…"; then Android: "Allow Hoard debug to delete this photo?" › Allow: deleted; the newer `IMG_0005.JPG` untouched | [`p07`](trash/phone/p07-delete-confirm.png), [`p08`](trash/phone/p08-delete-consent.png) |
| DCIM › `OldStuff` (a folder) › Delete | "Delete 1 item permanently? 1 item with folders in Internal storage cannot go to the trash: Android's trash takes files, not folders."; **Keep** is the default; Keep: nothing deleted | [`p09`](trash/phone/p09-folder-offer.png) |
| `IMG_0003.JPG` trashed again, its `.trashed-…` file removed outside Hoard (`adb shell rm`), Trash › Refresh | The row is forgotten (the item is gone) | — |
| Settings › Trash › Media management › Allow | Android's "Allow app to manage media" screen; switched on and back: "Allowed: Android does not ask…", **Change** | [`p10`](trash/phone/p10-settings.png), [`p11`](trash/phone/p11-system-mm.png) |

**Found and fixed on the way**: reading a trashed photo back by its `images` URI with `MEDIA_TYPE` in the projection
threw (the typed collections have no such column), so every photo looked "not trashed" and the run reported a half
shot; rows are now read by id from `MediaStore.Files`. A sidecar in a consent request made Android refuse the whole
request; sidecars now never go in one.

**Not run here**: an **API 29** image (the folder trash below Android 11: `.hoard-trash` with `.nomedia`, the same code
as a USB drive): only an API 36 system image is installed on this host. Not run: a request of more than 500 items
(`MediaStoreTrashTest` covers the batching), a phone that never had All files access.

