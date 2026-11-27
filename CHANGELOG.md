# Changelog

## 2.9.1 (unreleased)

The phone backup after the photo server's admin deletes it, and a photo server whose disk is full ([ADR 0024](docs/adr/0024-phone-backup.md) and [ADR 0025](docs/adr/0025-video-compression.md), "Amendment (2.9.1)"). Made for hoard-server 0.14.0; works with older servers too.

- **The backup starts over after a purge.** When the photo server's admin deletes this phone's backup, Hoard notices at its next backup pass, sets each folder up again on the server and backs up every photo and video again, in the same pass. Settings › Phone backup says it once: "The photo server's backup of this phone was deleted; Hoard is backing it up again". Before, Hoard went on showing everything as backed up and sent nothing.
- **No stale "freed" totals.** Files Free up space had deleted from the phone were in the backup that went: Settings › Phone backup no longer counts them as freed, and the note that freed photos stay in the Gallery is not shown right after a purge.
- **A full photo server is a wait, not a failure.** When the server's backup disk has no room (hoard-server 0.14.0 says so), the backup stops, marks nothing as failed and tries again later (after an hour, then longer each time, at most every 6 hours). The folder says "Waiting: the photo server's drive is full (2 GB free)"; **Back up now** tries at once, and the backup goes on by itself once there is room.
- **Compression waiting for room.** A video the server cannot put back on the NAS for lack of space now reads "Waiting for free space on the NAS: empty the trash" in Settings › Compress videos (with what is free, and the Trash one tap away), as on the web gallery, instead of being listed among the failures.

## 2.9.0 (unreleased)

Settings reorganized, after the owner's wireframes ([`docs/design/settings-2026-11/`](docs/design/settings-2026-11/)).

- **Settings is now a list of eight screens**: Photo server, Backup and phone folders, Cache and storage, Appearance and language, Browsing and transfers, Trash, Culling, and About. Each opens its own screen, and back returns to the list. Every setting is still there and works as before; [`MAPPING.md`](docs/design/settings-2026-11/MAPPING.md) says where each one went.
- **Drop-down lists** replace the long lists of options: theme, palette, language, default sort and its direction, "If the file already exists", the route check timeout, the preview cache limit, the kept originals budget, and "Empty the trash automatically".
- **Sync now** is a button of its own at the top of Settings › Photo server; the Gallery's "pair again" banner opens that screen directly.
- Long texts in Settings are no longer cut after two lines: the plain HTTP warning, every sync error, the kept originals' size and the Reveal copies' explanation now show in full.

## 2.8.1 (unreleased)

Fixes after the first phone compressions ([ADR 0025](docs/adr/0025-video-compression.md), "Amendment (2.8.1)").

- **A compressed video shows once in the Gallery.** Right after Hoard replaced a video on the phone, the Gallery showed it twice: once as on the phone and once as on the server. It now shows once, as on both, right away, and Hoard syncs with the photo server after each replace or restore.
- **You see when videos are ready to replace.** The notification that compressed videos wait for your permission now shows like other notifications (it was silent), and the Gallery and Settings › Compress videos say "3 compressed videos are ready to replace on this phone" with **Continue**, which opens Android's permission dialog.
- **Safer replaces.** Hoard checks each video again after you allow the replace, so a video you changed while the dialog was open is left as it is. A replace interrupted half-way is finished from the copy Hoard already downloaded, also when the phone is no longer paired with the photo server. While a video is being replaced, Settings › Compress videos warns not to uninstall Hoard or clear its storage.
- Smaller fixes: the phone's free space is read correctly before the first download, and the Gallery and backup take the replaced video's details from the finished file.

## 2.8.0 (unreleased)

Compress the videos on this phone: the photo server makes the compressed copy, and Hoard puts it in place of your video, with the same name, folder and date ([ADR 0025](docs/adr/0025-video-compression.md), phase A2). Android 11 and later with All files access; needs hoard-server 0.13.0 or later with its GPU image and a backup folder.

- **Compress videos on this phone.** Select backed-up videos in the Gallery, or open a folder in Settings › Photo server › Phone backup and choose **Compress videos in this folder**. The server compresses its backup copy; Hoard downloads the result (only on Wi-Fi by default, picking up where it left off if the connection drops), checks it, and writes it over the video on the phone. The video keeps its place in every gallery app: same name, folder, date taken, modified time and favourite.
- **Android asks you first.** Android only lets Hoard change videos another app made (the camera, a drone app) after you allow it: Settings › Compress videos says "3 videos are ready to replace" with **Allow** (one Android dialog for up to 500 videos) and **Keep originals**. A notification tells you when videos are waiting.
- **Nothing is lost on the way.** If the phone stops half-way through writing a video, Hoard finishes it the next time from the copy it already downloaded. A video you changed on the phone since its backup is left as it is. Other apps see a changed file: a cloud backup app may upload it again.
- **The originals go to the trash, as for the library.** Each original waits in the Trash ("Original of a compressed video"); **Restore original** brings it back onto the phone the same way, after you allow it.
- **The backup knows.** A replaced video is neither uploaded again nor reported as deleted, and Free up space can free it like any other backed-up video (a video still being replaced stays on the phone until it is done).
- **Compress automatically** on a backed-up phone folder: new videos are compressed after their backup, and replaced on the phone when they are ready.
- Settings › Compress videos shows **This phone**: what is downloading or being replaced, what was replaced, and how much smaller the phone is.

## 2.7.1 (2026-11-20)

Compression follow-ups ([ADR 0025](docs/adr/0025-video-compression.md), "Amendment (2.7.1)"). Works with hoard-server 0.13.x.

- **Plain words instead of codes.** When the photo server cannot compress, or a video is skipped or fails, Settings › Compress videos and the sheet now say why in a sentence ("The photo server's write access to the library is read-only", "3 not in this place", "the compressed copy did not match the original") instead of codes such as `no_write_mount`. A reason Hoard does not know yet shows a general sentence with the server's code in small type.
- **Restore and the auto-empty preview check the server first.** Like Empty, they now ask the photo server which originals a compression is still holding before they act, so a restore or a preview never goes by an out-of-date list.
- **Compressing a folder says what is in it:** "129 of 312 videos, including 4 in sub-folders" (with hoard-server 0.13.2 or later).
- The Gallery's badges (on a photo or video, over its thumbnail) use the app's colours instead of fixed ones; they look the same.

## 2.7.0 (2026-11-19)

Compress your videos on the photo server: the same look in a fraction of the space, with the originals in the trash until you empty it ([ADR 0025](docs/adr/0025-video-compression.md)). Needs hoard-server 0.13.0 with its GPU image; with an older server, or one without a usable GPU, nothing changes.

- **Compress videos.** Select videos in the Gallery, or open a library folder in Files and choose **Compress videos in this folder** (⋮). A sheet says what happens (the server replaces the files in your library; phone backups no longer on a phone are compressed on the server), lets you pick **Same look** or **Smaller**, and shows the server's estimate: "129 of 312 videos · 29.1 GB → about 5.6 GB · about 35 min", with what is skipped and why ("180 already compressed by an older tool", "2 already efficient"). **AV1** appears only once it is turned on on the server.
- **Originals go to the trash.** Each compressed video's original waits in the Trash, marked "Original of a compressed video · saves 412 MB when emptied". **Restore original** puts it back and moves the compressed one to the trash; **Delete permanently** and **Empty trash** free the space. A video the server is still working on is shown as "Held by a compression in progress": emptying the trash, by hand or automatically, never deletes it, and it is not counted in what emptying frees.
- **Compress automatically** (⋮ on a library folder, off until you turn it on): new videos in that folder are compressed after the server finds them; you can also compress the ones already there.
- **Settings › Photo server › Compress videos**: space saved and still in the trash, compressions in progress with their progress (Pause, Resume, Cancel), the folders compressed automatically (each with its switch), and the history.
- **Gallery badges**: "Compressed" while the original is in the trash, "Compressing" while the server works on a video.
- When the server cannot compress (no GPU, the library not writable for it), the sheet says why instead of starting.
- Compressing videos that are only on this phone comes in 2.8.0.

## 2.6.0 (2026-11-18)

Free up space on the phone: what the server holds and has just checked leaves the phone, oldest first, after you see it and Android asks you ([ADR 0024](docs/adr/0024-phone-backup.md), "Free up space"). Android 11 and later; needs hoard-server 0.12.0.

- **Settings › Photo server › Phone backup › Free up space.** A preview of what would leave the phone, by month, with counts and the space each month frees ("6 photos from before Oct 17, 2026 · 4.1 MB"), each photo's thumbnail. Tap a photo, or a month, to keep it on the phone. Nothing is deleted without this screen.
- **Only what the server has proven it has.** Just before deleting, Hoard reads every file again and makes its checksum from the bytes the phone has now, then asks the server to compare with its own copy, read again within the last day (hoard-server 0.12.1; with 0.12.0, files the server last read more than a day ago stay on the phone): only photos whose every file (a RAW + JPEG pair, a video, its sidecar) matched in the last 10 minutes go. Right before Android's dialog Hoard checks once more that each file is unchanged and that Android's entry still names that very file; a sidecar changed since (by culling or Reveal) is never deleted. While the server is still reading, the screen says "Checking on the server…"; a photo whose server copy differs, is missing or changed on the phone stays, with the reason.
- **Android asks you.** The deletion goes through Android's own dialog ("Allow Hoard to delete these 1,000 photos?"), one per 1,000 files, also with All files access. Hoard then checks what Android really deleted and tells the server exactly that: if you deny a dialog, those photos stay. A shot's `.xmp` sidecar (and a RAW Android does not list) goes only after its photos did.
- **What stays on the phone**: photos from the last 30 days and favourites (two settings, both on), photos whose culling has not reached the server yet (Hoard sends it first), and photos not backed up yet. The preview says how many of each.
- **If Hoard is closed while Android's dialog is up**, what Android deleted is recorded as freed the next time Hoard starts.
- **Reveal's edits are safe.** If Reveal is installed but not grouped with Hoard on the server, nothing is freed until you group them (Reveal › Settings › Sync › This device). While Reveal on this phone has not been updated to keep photos Hoard frees, every photo Reveal knows stays ("Kept for Reveal: 42"), and nothing is freed while Reveal has not been opened for 30 minutes ("Open Reveal once so its edits reach the server").
- **After freeing**, the photos stay in the Gallery from the server, marked "Freed from the phone"; **Save to this device** brings them back. Settings shows how much was freed ("18.4 GB freed · 4,210 files"). The server keeps freed files pinned: no app can delete them, only the server's admin page.
- On Android 10 and older, free up is not offered (the backup works as before).
- Settings › Phone backup says when Reveal on this phone is not grouped with Hoard (both apps may then back up the same folders), with a way to **This phone**.

## 2.5.0 (2026-11-16)

Phone backup to your photo server, and every device sees it ([ADR 0024](docs/adr/0024-phone-backup.md)). Needs hoard-server 0.12.0; with an older server Settings says so and nothing is sent.

### Phone backup

- **Settings › Photo server › Phone backup.** Your phone's folders with photos or videos (Camera, Screenshots, WhatsApp images and videos, the others), each with a switch; no folder is on until you turn it on. **Add a folder** backs up any other folder of the phone or of an SD card. The server keeps each folder exactly as the phone has it, in a folder of its own for this phone next to the backup's other files.
- **Nothing is sent twice.** A photo the server already has (uploaded by Reveal or another phone, or a copy on your NAS) is linked, not uploaded: the folder says "950 already on the server". Only files the server is missing are uploaded, newest first, in resumable chunks: a backup cut off by the network, the phone or Android continues where it stopped.
- **Rules**: only on Wi-Fi and other unmetered networks (on), only while charging (off), videos only while charging (on), and never while the battery is low. A folder's status says how many files wait for Wi-Fi or for the charger.
- **Status per folder and per file**: "11,050 of 12,034 backed up · 950 already on the server · 34 waiting for Wi-Fi", the bytes, the last complete pass and, while it runs, the file being sent. Tap a folder for its files, filtered by state (waiting, queued, preparing, uploading, backed up, already on the server, waiting for a rule, failed with the reason). **Back up now** starts at once, within the rules.
- **The Gallery** marks phone photos of backed-up folders that are not on the server yet (waiting, uploading, waiting for Wi-Fi or charging, failed); once backed up they show as on the server.
- **Deleted, moved, renamed.** A file you delete on the phone (or move to Android's trash) stays in the backup, marked as deleted from the phone; a renamed or moved file is moved on the server, without uploading it again. Hoard reports a deletion only after reading the whole folder with its storage mounted, so an SD card taken out never deletes anything.
- **RAW files and XMP sidecars** (CR3 and the RAWs Android does not list) are backed up too when Hoard has All files access; without it Settings › Backup says so and offers to allow it.
- PNG screenshots, WebP and GIF are backed up like any photo. A file the server does not take is shown with the reason and never holds up the rest of its folder.
- The backup runs in the background (every 6 hours, 2 minutes after new photos arrive, and on **Back up now**), never while Hoard starts; long uploads show a notification. Checksums are made only for files that must be uploaded (small files get theirs while the Gallery reads them anyway), at most 32 MB/s on battery.

### Other devices see backups

- **Gallery › Devices** (the phone button in the Gallery's top bar): every phone with a backup on your server, this one first, each with its storage (internal storage, SD cards) and backed-up folders, how many photos each holds and how many were deleted from the phone. Tap a phone, a storage or a folder to show that part of its backup in the grid; the bar above the grid says where you are and **✕** shows everything again.
- **Filters › Phone backups**: "Pixel 8 backup" (one chip per phone) shows only what that phone backed up.
- **"Deleted from the phone"**: a backed-up photo the phone no longer has keeps its place in the Gallery with a badge (also in the viewer); the server keeps it.
- Another phone's photos show from the server's previews: nothing is downloaded to browse them. Opening one at 100 % reads the original from the server by ranges, and keeps it only within the originals budget of Settings › Photo server.
- **Save to this device** (in the viewer, and in the selection bar for several photos): copies whole shots from the server (RAW, JPEG, video and the `.xmp` sidecar when the server has one) into `Pictures/From <phone name>/`, or a folder you choose once (never Hoard's copies for Reveal in `Pictures/Hoard`). It is a normal transfer: progress, Cancel, retries after a dropped connection (resuming where it stopped) and the name-conflict choice are in Transfers. A saved photo shows once in the Gallery, as on the phone and on the server; if its folder is backed up, the next backup links it on the server without uploading it again.
- This phone's own backed-up files show once even before Hoard has read them (matched by where they are on the phone).
- Search finds photos by the phone they were backed up from.

### Fixed

- On Android 11 and later, files copied to the phone's storage (any copy, not only Save to this device) were seen by Android with size 0 and no date until the next media scan, which the Gallery and the backup read.

## 2.3.0 (2026-11-16)

A trash for everything Hoard deletes ([ADR 0023](docs/adr/0023-trash.md)). Needs hoard-server 0.11.0 for server photos to keep their edits while in the trash.

- **Move rejects to trash.** Culling's "Delete rejects" is now **Move rejects to trash**: every file of each rejected shot, with its `.xmp`, goes to the trash after a confirmation that says how many files, how much and where. A trashed shot keeps its pick or reject, so it comes back with it.
- **Clear rejects from the Gallery.** Long press a photo (or a day's header) to select; **Select all** takes what the filters show (for example **Rejected**, a folder or a date range); **Move to trash** moves every copy Hoard can reach: the phone's photo and the server photo's file on your share. Server photos whose folder Hoard has not seen in Files yet, and photos that exist only as uploads, are left out and counted. Trashed photos leave the Gallery at once.
- **Deleting in Files moves to the trash too**: the files you chose, and a photo's `.xmp` only when nothing else of that photo stays in the folder (delete the JPEG of a RAW + JPEG pair and the `.xmp` stays with the RAW).
- **Where the trash is.** On a share or a USB drive or SD card: a hidden `.hoard-trash` folder on that share or drive, by date and with the original folders; moving is a rename, so it is instant and copies nothing. On a share that is also a photo server folder, the trash goes inside that folder so the server sees it; when Hoard does not know yet whether a folder is on the server and cannot reach it, it refuses instead of guessing. On the phone (Android 11 and later): Android's own trash, the one the system's Files and Photos apps show; Android deletes those after its 30 days, and the Trash screen shows when.
- **Trash** (the trash button in Files and the Gallery, and Settings › Trash): your trashed files by day and folder, with sizes, including server photos another device trashed.
  - **Restore** puts files back exactly where they were and tells you, instead of overwriting, when another file took the place.
  - **Delete permanently** and **Empty trash** ask first and show how much space they free; for phone photos made by other apps, Android asks too. A share or drive that is not connected shows greyed and is not counted.
  - **Settings › Trash**: empty the trash automatically after 7, 30 (the default) or 90 days, or never. A shorter period first shows how many files and how much it would delete now, and applies only when you confirm. Phone photos made by other apps are left to Android's own 30 days.
  - **Media management** (Android 12+, offered in the first trash dialog and in Settings › Trash) lets Hoard use Android's trash without Android asking each time.
- **Edits survive the trash.** With hoard-server 0.11.0, a trashed photo leaves the Gallery and the web gallery but keeps its Reveal edits and its picks and stars on the server; restoring it brings it back with them, and emptying the trash removes the edits. (Reveal 0.9 keeps edited trashed photos in its own grid until Reveal shows them as in trash.)
- When something cannot go to the trash (a read-only share root, a share that spans two disks, a folder on the phone, storage that cannot move files), Hoard offers to delete exactly the files you chose, with their count, size and place; **Keep** is the default.
- If Hoard is stopped in the middle, it finishes or undoes the interrupted shot the next time it starts, so a shot is never left half in the trash. Moving many files shows a notification with the progress and Cancel.
- With a photo server older than 0.11.0, the dialog warns that the server will forget Reveal's edits of the trashed photos until it is updated.

## 2.2.2 (2026-11-15)

- **The Gallery keeps your place** ([ADR 0021](docs/adr/0021-gallery.md), "Amendment 2.2.2"). Opening a photo and coming back no longer sends you to the top of the library: the grid stays where it was, and if you swiped to other photos in the viewer, the one you closed on is brought into view. The grid also stays on the same photo when a sync adds new photos above it, when a decision changes a filtered list, and after Android closes Hoard in the background.
- **The fast scroller no longer grabs your scrolls.** It only reacts to a touch that starts on its handle, in a narrow strip at the right edge; a swipe anywhere else, even near the edge, scrolls the grid normally. The handle shows while you scroll and fades out when you stop; dragging it still shows the month and the years.
- **Server photos open offline.** In airplane mode or away from the server, the viewer shows the copy the phone already has (the thumbnail the grid shows) with a small "Offline · low resolution" note, instead of "This shot could not be shown."; online it shows that thumbnail at once and swaps in the full preview when it arrives. The filmstrip shows the thumbnails too. A photo with no copy on the phone at all says "Not available offline" rather than an error.
  - Why the filmstrip was empty: the grid draws server photos from the offline thumbnails (kept for the whole library), while the viewer and its filmstrip looked only in the preview cache, which has no copy of the photos the grid showed from those thumbnails. Both now read the same files.
- **Photos you browse stay available offline.** Every server thumbnail the grid or the filmstrip downloads is now also kept in the offline thumbnails (unless you turned them off in Settings › Photo server), so what you scrolled past on mobile data still shows in airplane mode. No extra download; the count in Settings includes them.

## 2.2.1 (2026-11-15)

- **A sidecar made by another app no longer overwrites your stars** ([ADR 0011](docs/adr/0011-culling-sidecars.md), "Amendment 2.2.1"). When Lightroom, darktable or another tool creates an `.xmp` file for a photo you already rated in Hoard, the file starts from that tool's defaults (often 1 star or none), not from your decision. Hoard now remembers which sidecars it has read and what they said. The first time it sees a sidecar, it only fills what you have not set (stars when the shot has none, a pick or reject when it has neither); your own stars, picks and rejects stay, however new the file is. After that, only what changes in the file counts: rating a photo 2 stars in Lightroom still shows up in Hoard, while a file rewritten without changing the rating leaves Hoard's newer edits alone. The same rule as hoard-server 0.10.1 and Reveal.
  - Sidecars written by **Export XMP** are recorded as read, so Hoard never takes them back as someone else's.
  - When a sidecar is deleted, Hoard forgets it (only after listing the folder in full; a drive or share that shows up empty forgets nothing), and a new one later is read as a first read again.

## 2.2.0 (2026-11-15)

- **Your share's photos are your server's photos** ([ADR 0022](docs/adr/0022-smb-folders-on-the-server.md)). When a folder of an SMB share is also one of hoard-server's NAS folders (the same files), Hoard recognises it the first time you open a folder there, by reading two of its photos (2 MB each), and from then on knows which files the server holds by their path, size and date. Needs hoard-server 0.10.0.
  - Files and the culling grid show the **on the server** mark (the cloud of the Gallery) on those photos.
  - **Culling** on that share also goes to the server, for the same photos as in the Gallery: Reveal and the web gallery see your picks and stars, and theirs show up in Hoard.
  - **Edit in Reveal** sends those photos as server photos (Reveal 0.9.0 or later reads them from the server), and the rest as below; one hand-off can have both. Large hand-offs keep each shot (RAW + JPEG and the rest of its files) together in one Reveal intent.
  - Settings › Photo server › **Mapped folders** lists what Hoard recognised, with **Forget**.
  - Folders the server does not have work exactly as before.
- **Edit in Reveal copies instead of streaming** ([ADR 0010](docs/adr/0010-edit-in-reveal.md), "Local copies, no streaming for new hand-offs"). Each photo now reaches Reveal one of three ways: from the server (when the server holds it, as above), as the photo itself when it is already on the phone, or, for anything else (a share the server does not have, a USB drive, an SD card), as a **copy** Hoard makes first in **Pictures/Hoard/<batch title or date>/**. The copy goes through Transfers, with its progress on screen and Cancel (which removes the partial copies); editing the same photo again reuses its copy while the original is unchanged. Reveal gets a plain read permission on those files: no more links that depend on Hoard and the network, no writable files. CR3 files, which Android does not accept as pictures, go to **Download/Hoard/** instead ([test report](docs/testing/mediastore-raw.md)). Links Hoard gave Reveal before keep working.
  - If you leave Hoard while it copies, a notification **Ready to open in Reveal** sends the photos when you tap it (with notifications off, Hoard offers it when you come back).
  - A copy that stopped halfway keeps what it copied: editing again copies only the rest. Big hand-offs go to Reveal in parts of 200 photos.
  - The copies do not show up in the Gallery as phone photos.
  - Settings › **Photos copied for Reveal** shows how many copies there are and how much space they take, with **Delete copies** (Reveal then shows its own previews of those photos). It deletes only the copies Hoard made for Reveal that are still in Pictures/Hoard or Download/Hoard, never other files there. Nothing is deleted automatically.
- **XMP files only when you ask** ([ADR 0011](docs/adr/0011-culling-sidecars.md), "Amendment 2.2.0"). Hoard no longer writes `.xmp` files on its own: not while culling, not with "Copy picks", not for Reveal. The "Save ratings next to photos (XMP)" setting is gone and is off for everyone. The new **Export XMP** (Files: a selection or a folder; culling: "Export XMP of decided shots") writes your stars, picks and rejects into the sidecars next to the photos, merging into existing ones and never touching one that is not valid XMP; for a read-only folder it asks where to put them. Sidecars written by Lightroom and others are still read when a folder opens.
- **Edit in Reveal, big batches**: when a hand-off needs several intents (over 500 server photos), each now says which one it is (`BATCH_INDEX`) and how many there are (`BATCH_COUNT`), so Reveal can tell they belong together ([ADR 0010](docs/adr/0010-edit-in-reveal.md)).

## 2.1.0

- **Gallery tab** ([ADR 0021](docs/adr/0021-gallery.md)): photos on the phone and on hoard-server in one grid, with culling everywhere (decisions are shared with the server and the web gallery), edited previews from Reveal, a pairing flow (QR code) and Settings › Photo server. Photos on the server open in Reveal by reference (`CULLING` version 2 for Reveal 0.9.0 and later; [ADR 0010](docs/adr/0010-edit-in-reveal.md), "Server items"); SMB files still go as links. Needs Reveal 0.9.0 or later for server references ([test report](docs/testing/gallery/README.md)).

## 2.0.2

- **Fix: one app holding photos open could make every other open fail** ([ADR 0010](docs/adr/0010-edit-in-reveal.md), "Idle reclaim"). Since 2.0.1 Hoard reads at most 6 network photos for other apps at once, and a large photo (every RAW) kept its place until the app reading it closed it. An app that forgot to close them took all 6 places, and every later photo got the "busy" answer until Hoard was restarted. Now a photo nobody has read for 12 s gives its place back; if the app reads it again, it gets a place again (waiting up to 1.5 s, as an open does). A photo left untouched for 10 minutes is closed on Hoard's side, so the share does not keep files open for it. The "busy" contract with Reveal is unchanged. In a test where one app opened 20 RAW photos and never closed them, another app then opened all 60 of its photos within 9 s; with 2.0.1 it got none ([test report](docs/testing/handoff-burst.md)).
- Screenshot tests show the same dates on any day: "today" and "yesterday" no longer depend on when the tests run.

## 2.0.1

- **Fix: Reveal opening many photos at once could freeze the phone** ([ADR 0010](docs/adr/0010-edit-in-reveal.md), "Bursts of opens"). When Reveal started, it reopened every photo Hoard had handed it at the same time. Each photo from a network share went through Android's storage service, and hundreds of them at once could stall that service until Android restarted itself. Hoard could also crash, which closed Reveal too. Now Hoard serves at most 6 of those photos at a time; an open that finds them all busy waits up to 1.5 s, then gets a "busy" answer (`FileNotFoundException` starting with `busy:`, the contract with Reveal, which retries). Sidecars and smaller photos (up to 8 MB) are kept in a private copy, so reopening them skips Android's storage service entirely. A machine that cannot be reached makes the remaining photos of the burst fail straight away instead of each one waiting to connect. In a test with 200 opens at once on an emulator, Hoard no longer crashes and the storage service stays responsive ([test report](docs/testing/handoff-burst.md)).
- Photos on the phone were already cheap to reopen and are unchanged.
- The log has one line per burst of opens instead of one per photo.

## 2.0.0

**Hoard 2: the native app replaces Hoard 1** ([ADR 0019](docs/adr/0019-native-app.md)). Hoard is now a Kotlin + Jetpack Compose app, rebuilt screen by screen at parity with 1.11 (Files, culling, assisted culling, sidecars, transfers, viewers, Edit in Reveal, settings), with a built-in image viewer and a Gallery tab on the way. It installs **over** Hoard 1 (same app, same signing key):

- **Your data comes with you.** The first launch imports Hoard 1's database once: machines and their addresses, favorites, culling decisions, analyses and sidecar records, transfer history and settings, including theme, palette, language and the text viewer's preferences. Hoard 1's file is only read; a failed import changes nothing and is tried again on the next launch.
- **Passwords keep working**, without typing them again: they stay in the phone's keystore, which the same app keeps reading.
- **Reveal's links keep working**: photos handed to Reveal from a network share still reopen.
- **Home-screen icons and shortcuts** pinned from Hoard 1 still open Hoard.
- **Hoard Next** (the 2.0.0-beta.1 test build) is no longer needed: Hoard 2.0.0 does nothing about it, so uninstall it yourself. Your data is in Hoard; whatever you did only in Hoard Next stays there.
- Gone with the WebView app: the browser build and its demo data (the web gallery is hoard-server's).
- Requires Android 7.0 or later, as before.

## 1.11.0

- **Import bridge for Hoard Next** ([ADR 0019](docs/adr/0019-native-app.md)): Hoard can hand a read-only snapshot of its data (machines, favorites, transfers, culling decisions and analyses, settings) to **Hoard Next**, the native Hoard 2 test build installed next to it. Only an app signed with Hoard's own key can ask (a signature permission, plus a check of the calling app); nothing in Hoard changes, and no password leaves it: Hoard Next asks for each machine's password once.

## 1.10.1

- **✕ on a burst rejects the whole burst**: in the culling viewer, on a burst shown as one item (with "View burst (N)"), ✕ — the button, `X`, or swiping down when swipe-to-decide is on — now rejects every frame of the burst, in one step that Undo reverts (toast "Rejected N photos of the burst" with Undo). Stars are kept, and each frame's sidecar is written as usual. ✓ still picks only the frame shown, "View burst" → "Keep this one" is unchanged, and inside the burst ✕ still rejects only the frame on screen.
- **Fix: broken thumbnails after culling a large folder**: after culling a few hundred photos, reopening the folder showed the broken-image icon on most grid tiles. Thumbnails shared a disk cache with the viewer's screen images, so culling pushed them out while the grid still pointed at them. They now have their own cache (64 MB, about 2,000 or more thumbnails). A tile whose image fails to load asks for it again once; if it fails again, the tile stays grey instead of showing a broken image. XMP sidecars had nothing to do with it, and they never affect thumbnails or how photos are paired (now tested).

## 1.10.0

- **Edit in Reveal: photos stay reopenable** ([ADR 0010](docs/adr/0010-edit-in-reveal.md)): every hand-off now gives Reveal a _persistable_ read grant, for photos on the phone, on drives and on SMB shares. Photos from a network share go as stable links that Hoard serves itself: Reveal can reopen them later, after the phone restarts and with Hoard closed, while the machine is reachable (before, they stopped working an hour after the last read or when Hoard closed: "Permission Denial" in Reveal). Hoard starts in the background to serve them, connects on its own (the address that last worked first, the pinned server checked before the password is sent), shows the "Streaming" notification while Reveal reads, and stops two minutes after. An unreachable share makes Reveal show its own preview.
- **Ratings come back from Reveal**: with "Save ratings next to photos (XMP)" on, each shot's sidecar goes with the photos as a writable item (the existing one, or a new `<stem>.xmp` holding Hoard's decision), in folders Hoard can write. A rating, pick or label set in Reveal is written into it (on SMB atomically, through Hoard), and Hoard imports it when the folder opens for culling. Photos are never writable; read-only folders send no sidecar.
- **No more double "Reveal" chooser**: with both Reveal and its debug build installed, Hoard opens the release one directly.
- **Settings › About › Reveal links**: how many files Reveal can reopen, and **Revoke all**. Links unused for 180 days are forgotten.
- The note before the first hand-off from a network share says what is now true.

## 1.9.2

- **Culling ignores hidden files**: macOS AppleDouble copies (`._DSC07845.ARW`, ~4 KB metadata files a Mac leaves on non-HFS disks and shares), `.DS_Store` and other dot-files no longer become shots. A folder of 14 RAW+JPEG pairs with 28 `._*` files opened 28 shots (14 grey tiles, "14 photos could not be analysed"); it now opens 14. The same rule applies to "Cull photos" counts, "Edit in Reveal" and the web mock. Hidden XMP sidecars (`.name.xmp`) are still read. The file browser is unchanged.

## 1.9.1

- **Compact, collapsing page header** (landscape phones and short screens): scrolling a list down moves the page header (breadcrumbs, title, status line) and its toolbar out with the content; a compact bar stays on top with ←, the folder name on one line and the page's actions as icons (search expands into a field, one ⋯ menu holds filter, sort, new folder, refresh and the page's own items). Scrolling up brings the full header back at once (quick-return); it follows the finger, nothing animates by itself. Same behaviour on every page with this header: machine, phone and USB folders, the culling grid (its filter rows and analysis progress collapse too), machines, transfers, settings.
- **Breadcrumbs on one line**: the tail that fits (`… › crucial › Images`), the earlier levels behind "…"; the current folder is no longer repeated above the title. The ← back button shows at every width.
- **One meta line**: connection, address, latency and the Read-only badge share one line under the title.
- **Short screens** (viewport ≤ 500 px tall, e.g. a phone in landscape): the 56 dp top bar goes away and the sidebar becomes an icon rail (logo on top, transfers and theme at the bottom, tooltips, same items and active state) instead of the full pinned sidebar; labelled header buttons ("Cull photos", "Start culling", "Add machine") become icon buttons; the culling filters fit on one line. In a deep folder at 900×415 the file list went from 3 % of the screen to 51 % at the top and 84 % after scrolling (390×844 portrait: 58 % → 66 % and 86 %).
- The browse list's item count moved to the end of the list; while selecting, the selection bar stays above the scrolling header.

## 1.9.0

- **Culling ratings as XMP sidecars** ([ADR 0011](docs/adr/0011-culling-sidecars.md)): in folders Hoard can write (phone storage, SD card, USB drive or camera, SMB shares that take writes) every pick, reject and star rating is written into the shot's XMP sidecar next to the photos (`<stem>.xmp`, shared by a RAW + JPEG pair): `xmp:Rating` (−1 for a reject) and `digiKam:PickLabel`, merged into an existing sidecar (Lightroom's develop settings, darktable's history, Reveal's recipe, keywords stay), never over a malformed one, atomically, coalesced and written at once when the app leaves the screen. Sidecars named `<file>.xmp` (darktable) or hidden (`.name.xmp`) are found and updated in place.
- Ratings made in Reveal, Lightroom, darktable or digiKam show in Hoard: a folder's sidecars are read when it opens for culling and again when the app comes back to the front; a sidecar changed after Hoard last wrote or decided wins. Opening a folder never writes.
- Read-only folders (read-only shares and volumes) keep decisions in Hoard only, with a discreet note on the culling page; a sidecar that cannot be written shows one toast with the count.
- **Edit in Reveal** sends the decisions with the photos (Reveal's `CULLING` extra), from every folder, so ratings arrive in Reveal even from read-only shares.
- **Copy picks** writes the same sidecar at the destination (stars and pick), merging into one already there.
- Settings › Culling: **Save ratings next to photos (XMP)** (on by default; replaces the Copy picks switch) and **Write XMP as hidden file**.
- Database v7 (`cull_sidecars`). Fix: two database writes from different parts of the app at once could fail with "Already in transaction".

## 1.8.0

- **Edit in Reveal** ([ADR 0010](docs/adr/0010-edit-in-reveal.md)): when Reveal, the RAW editor, is installed, photos go to it straight from Hoard with its hand-off contract (`dev.hfps.reveal.action.DEVELOP`): the culling viewer's current shot, all the picks of a folder or the shots the grid shows, and a browser selection of photos. RAW + JPEG pairs always travel together; only the photos sent are granted, read-only, and no sidecar is ever written. Phone and drive photos are handed over in place; photos on an SMB share stream through Hoard (readable while Hoard runs and for an hour after the last read; the app says so first). Big batches (over 200 files) ask first. Nothing shows when Reveal is not installed; installing or uninstalling it is noticed when Hoard comes back to the front.

## 1.7.2

- The app's header shows the new icon: the in-app mark (header and sidebar) still drew the old folder glyph. The splash drawable is now written by `build-icons.mjs --android` (hfps-repo-assets skill), replacing `scripts/splash-drawable.mjs`.

## 1.7.1

- New app icon: a files glyph (a sheet with a second sheet behind it) on the hfps blue, with Android adaptive and themed (monochrome) layers, the favicon set and the social preview. The editable source is `scripts/glyph.designer.json`; everything is rebuilt from `scripts/glyph.svg` with `build-icons.mjs` (hfps-repo-assets skill).
