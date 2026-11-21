# Settings reorganized (Hoard 2.9.0): where everything went

The owner's wireframes ([README.md](README.md), `screens/`) turn the single, long Settings screen of Hoard 2.8.x into
a list of eight entries, each opening its own screen. This table lists every item of the old screen (and of the
README's "Notes", the states not drawn) and where it is now. Behaviour is unchanged unless the last column says
otherwise. The app's screens, in pt-BR like the wireframes, are in [`app/`](app/) (Roborazzi goldens of
`SettingsScreensTest`, light and dark).

Code: `ui/settings/SettingsScreen.kt` (the list, `SettingsPage`, the page scaffold, Appearance, About),
`ui/settings/SettingsPages.kt` (the other screens), `ui/server/ServerSettings.kt` (server, caches, phone folders),
`ui/settings/FilesSettingsSection.kt` (browsing and transfers, storage access, Reveal copies and links),
`ui/trash/TrashSettings.kt`, `ui/cull/CullDialogs.kt` (`AssistSettingsFields`). Navigation: `SettingsRoute` (the list)
and `SettingsPageRoute(page)` (one screen) in `ui/shell/`.

| Old Settings item | New screen | Notes |
|---|---|---|
| **Photo server** eyebrow and its explanation | Servidor de fotos | |
| Not paired: "Pair with a photo server" → pairing screen | Servidor de fotos | The only row then (README Notes); the screen's other rows show once paired. |
| Server row: name, version, address; **Address** (change address dialog) | Servidor de fotos | |
| Plain `http://` server: warning line, red warning icon | Servidor de fotos | The warning used to be cut after two lines; it now shows in full. |
| Status: last sync / "Not synced yet", items on the server, "{n} decisions waiting to sync", the sync errors (unreachable, revoked, other, refused decisions) | Servidor de fotos › Situação | Shown in full (they were cut after two lines). |
| **Sync now** (disabled while syncing) | Servidor de fotos | A full-width button above the server row, as drawn (was the status row's trailing button). |
| This phone: alone / grouped with Reveal | Servidor de fotos › Este celular | |
| **Compress videos** (only when the server has the feature) → its own screen | Servidor de fotos | **No home in the wireframes**: placed after "Este celular". The Compress videos screen itself (2.8.1's ready-to-replace banner and "writing" warning included) is unchanged. |
| Mapped folders: explanation / "None yet…", each mapping with **Forget** | Servidor de fotos | |
| **Unpair** (confirmation) | Servidor de fotos | Still a confirmation dialog (the wireframe draws a chevron "opens a screen"; current behaviour kept). |
| Phone backup row (summary, freed so far; only while paired) → Backup screen | Backup e pastas do celular | Still only while paired, as before. |
| Phone folders: the usual ones, each added folder (tap to remove), **Add a folder** (dialog) | Backup e pastas do celular | The added folders were listed twice (in the subtitle and as rows); now only as rows. "Add a folder" is a text button under the list, as drawn. |
| Phone storage: All files access (allowed / off) with **Change** | Backup e pastas do celular › Armazenamento do telefone | |
| "Folders Hoard may save to" (only when Android gave some), each with **Release** | Backup e pastas do celular › Armazenamento do telefone | |
| Preview cache: size of limit, **Clear cache** | Cache e armazenamento | Only while paired, as before. |
| Preview cache limit (256 MB, 1 GB, 4 GB) | Cache e armazenamento | A drop-down list. |
| Offline thumbnails: count, size, **Clear cache**; "Keep small thumbnails of the whole library (Wi-Fi only)"; "Only while charging" | Cache e armazenamento | Only while paired, as before. |
| Kept originals: size of budget, **Clear cache**; budget (512 MiB, 2 GiB, 8 GiB) | Cache e armazenamento | The budget is a drop-down list; the size is no longer cut off. Only while paired. |
| Photos copied for Reveal: "Counting…" / "No copies on this phone." / count and size, **Delete copies** (confirmation) | Cache e armazenamento | |
| Reveal links: count / "Reveal has no links…", **Revoke all** (confirmation) | Cache e armazenamento | |
| Theme (System, Light, Dark) | Aparência e idioma | A drop-down list (the wireframe's "lista suspensa" variant). |
| Palette (Blue, Olive, Violet, Rose, Graphite) | Aparência e idioma | A drop-down list. |
| Language (system, English, Português (Brasil)) | Aparência e idioma | A drop-down list. |
| Show hidden files (switch) | Navegação e transferências › Navegação | |
| Default sort (Name, Size, Modified) and its direction (text follows the sort: A to Z / Smallest / Oldest first…); "Folders always come first." | Navegação e transferências › Navegação | Drop-down lists. |
| "If the file already exists" (Ask (default), Keep both, Replace, Skip) and its help, which follows the choice | Navegação e transferências › Transferências | A drop-down list. |
| Route check timeout (0.8, 1.5 (default), 3, 5 s) and its help | Navegação e transferências › Transferências | A drop-down list. |
| Open the trash | Lixeira | |
| Empty the trash automatically (7, 30, 90 days, Never), its help, "Checking…", the shorter-period confirmation | Lixeira | A drop-down list (disabled while checking). |
| Media management: allowed (**Change**) / not allowed (**Allow**) / Android < 12 (no button) | Lixeira | |
| Culling: explanation, blurred photos and closed eyes sensitivity, burst gap | Seleção de fotos | The existing chips stand for the wireframe's segmented controls. |
| "Write XMP as a hidden file (.name.xmp)" switch | Seleção de fotos | |
| Version | Sobre | |
| Hoard 1 data (import status) | Sobre | Kept: the wireframe's note says it "can be removed"; current behaviour kept, for the owner to decide. |
| AGPL text, Source code, License (AGPL-3.0) links | Sobre | |
| Open-source licences → Licences screen | Sobre | |

## Ways in

| From | Lands on |
|---|---|
| Files › gear, Gallery › gear | Settings (the list) |
| Gallery banner "The photo server no longer accepts this phone… · Settings" | Servidor de fotos (Settings under it, so back goes to the list) |
| Servidor de fotos › Pair | Pairing screen |
| Servidor de fotos › Compress videos; the compression notification | Compress videos screen (unchanged) |
| Backup e pastas do celular › Backup do celular | Backup screen (and Free up space from there, unchanged) |
| Lixeira › Abrir a lixeira; the Files and Gallery trash buttons | Trash screen (unchanged) |
| Sobre › Licenças de código aberto | Licences screen |

## Changes beyond the move

- A new design-system component, `HfpsSelect` (`:core:designsystem`): the wireframes' "Select" (label, the chosen
  value, a menu of options; choosing applies at once).
- `HfpsListRow` takes `subtitleMaxLines` (2 by default); the Settings rows whose text matters in full (server, status,
  mapped folders, caches, storage access, Reveal copies and links, backup, compress, hidden files) use no limit.
- Icons `Palette` (the Appearance entry) and `LinkBreak` (Unpair), from Phosphor.

## Emulator pass (2026-11-20)

The debug build on an emulator of its own (AVD `hoard-290-settings`, API 36, `-port 5590 -memory 3072 -no-window`),
paired by hand with a hoard-server of its own built from main (`-tags hoarde2e`, container `hoard-290-settings-server`,
`127.0.0.1:3291` → `http://10.0.2.2:3291`, temporary data; the pairing link typed, nothing discovered tapped).
Afterwards the container, the emulator and the AVD were removed. Screenshots (half size) in [`emulator/`](emulator/).

| Step | Result | Screenshot |
| --- | --- | --- |
| Files › gear | The list of eight entries | — |
| Servidor de fotos, not paired | Only "Pair with a photo server"; pairing screen | — |
| Pair (plain `http://`) | Sync now, the server with its HTTP warning in full, Status, This phone, Mapped folders, Unpair; no Compress videos (no GPU) | [04](emulator/04-server-paired-http-no-compress.png) |
| Server stopped, Sync now | "Last sync failed: the server was not reachable" under Status | [05](emulator/05-server-sync-error.png) |
| Server with the fake encoder (`compress`), Sync now | "Compress videos" row; it opens the Compress videos screen, back returns | [06](emulator/06-server-compress-row.png) |
| Address, Unpair | The address dialog; the unpair confirmation (cancelled) | — |
| Backup e pastas do celular | Phone backup (opens Backup), Phone folders, Add a folder (`/storage/emulated/0/Download` added, then removed by a tap), All files access: Change → Android's page, "Allowed" on return | [09](emulator/09-backup-folders.png) |
| Cache e armazenamento | Previews, cache limit drop-down (4.3 GB applied to the subtitle), Clear cache → "Cache cleared", offline thumbnails, originals and budget, Reveal copies and links | [12](emulator/12-storage.png) |
| Aparência e idioma | Dark and Olive apply at once; Português (Brasil) recreates the app on this screen | [15](emulator/15-appearance-pt.png) |
| Back | Configurações, in pt-BR | [16](emulator/16-list-pt-dark.png) |
| Navegação e transferências | Ordem: Tamanho → direction "Menores primeiro"; "Pular" → its help follows | [17](emulator/17-browsing-changed.png) |
| Lixeira | Open the trash (and back), 7 days applied (empty trash: nothing to confirm), Media management: Allow → Android's page | [18](emulator/18-trash.png) |
| Seleção de fotos, Sobre | The assist fields and the XMP switch; About, Licences and back | — |
| Device revoked on the server; Gallery banner › Configurações | Lands on Servidor de fotos ("…no longer accepts this phone…"); back → Configurações → back → Gallery | [21](emulator/21-banner-to-server.png) |
