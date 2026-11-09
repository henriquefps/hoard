/**
 * Sample text files for the web mock's `readTextFile` (the built-in viewer and editor develop
 * against these). Small files carry their text in the tree; the worst-case ones are generated on
 * first read (`generated`), so the dev build does not build megabytes of text at start-up.
 */

export const NOTES_MD = `# Notes

Things to remember about the **home lab**. See also the [to-do list](todo.md), the
[project README](../Projects/hoard/README.md#install) and [the Downloads folder](/henrique/Downloads/).

![Network diagram](img/network.png "The house network")

## Tasks

- [x] Move the media library to the new disk
- [ ] Renew the Tailscale key :key:
- [ ] Back up the router config ==before== the firmware update

## Machines

| Machine     | Role             | Address        |
| ----------- | ---------------- | -------------- |
| cachyos-fps | Media and Docker | 192.168.1.155  |
| NAS da sala | Backups          | 192.168.1.20   |
| Office PC   | Work             | via Tailscale  |

## Snippets

\`\`\`bash
# Mount the media share on the laptop
sudo mount -t cifs //192.168.1.155/media /mnt/media -o user=henrique,vers=3.1.1
\`\`\`

\`\`\`kotlin
fun greet(name: String) = "Olá, $name!"
\`\`\`

Inline \`code\`, H~2~O and E = mc^2^.

## Disk math

The array holds $n \\cdot s$ bytes; with RAID 5:

$$
C = (n - 1) \\times s
$$

## Backup flow

\`\`\`mermaid
graph LR
  Phone -->|Hoard| NAS[NAS da sala]
  NAS -->|nightly| Cloud[(Storage box)]
  cachyos-fps -->|rsync| NAS
\`\`\`

> Keep at least **two** copies, one of them off site. :floppy_disk:

---

Footnote-ish: last updated in November.
`

export const TODO_MD = `# To do

1. Replace the NAS fan
2. Read [the notes](notes.md#tasks) again
3. Check the [contract PDF](Contrato%20de%20loca%C3%A7%C3%A3o%202026.pdf)
`

export const HOARD_README = `# Hoard

An SMB client for Android.

## Install

\`\`\`sh
bun install
bun run dev
\`\`\`

Back to the [notes](../../Documents/notes.md).
`

export const PACKAGE_JSON = `{"name":"hoard","version":"1.0.0","private":true,"scripts":{"dev":"vite","build":"tsc -b && vite build","test":"vitest run"},"dependencies":{"react":"^19.3.1","@capacitor/core":"^8.6.0"},"license":"AGPL-3.0-or-later"}
`

export const MAIN_KT = `package dev.hfps.hoard

import android.os.Bundle
import com.getcapacitor.BridgeActivity

/** The only activity: Capacitor's bridge plus the two local plugins. */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(HoardSmbPlugin::class.java)
        super.onCreate(savedInstanceState)
        val greeting = "Hoard is running" // TODO: remove
        android.util.Log.i("Hoard", greeting)
    }
}
`

export const DEPLOY_SH = `#!/usr/bin/env bash
set -euo pipefail

# Build and copy the release APK to the NAS.
bun run android:release
cp android/app/build/outputs/apk/release/app-release.apk "/mnt/nas/Builds/hoard-$(date +%F).apk"
echo "done"
`

export const CONFIG_YAML = `# Docker compose for the media server
services:
  jellyfin:
    image: jellyfin/jellyfin:10.11
    restart: unless-stopped
    ports:
      - "8096:8096"
    volumes:
      - /srv/media:/media:ro
      - ./config:/config
  samba:
    image: dperson/samba
    environment:
      USERID: 1000
      TZ: America/Sao_Paulo
`

export const DATA_CSV = `date,machine,share,"bytes copied",notes
2026-11-01,cachyos-fps,media,1288490188,"nightly, ok"
2026-11-02,cachyos-fps,media,0,"skipped: ""no changes"""
2026-11-03,NAS da sala,Backup,19327352832,"first full backup
(took 3 h)"
2026-11-04,Office PC,Users,52428800,
2026-11-05,cachyos-fps,photos,734003200,ok
`

/** CRLF line endings: saving must keep them. */
export const WINDOWS_TXT =
  "Arquivo criado no Windows.\r\nCada linha termina com CRLF.\r\n\r\nSalvar deve manter os finais de linha.\r\n"

/** "Relatório de manutenção — € 120,00" in windows-1252 (not valid UTF-8): each code unit is a byte. */
export const LATIN1_BYTES = Uint8Array.from(
  "Relat\xf3rio de manuten\xe7\xe3o \x97 \x80 120,00\nPre\xe7o sem acentua\xe7\xe3o: OK\n",
  (c) => c.charCodeAt(0)
)

/** UTF-16 LE with a byte order mark. */
export const UTF16_BYTES = (() => {
  const text = "Unicode (UTF-16 LE) 日本語のテキスト\r\nSecond line ✓\r\n"
  const out = new Uint8Array(2 + text.length * 2)
  out.set([0xff, 0xfe])
  for (let i = 0; i < text.length; i++) {
    out[2 + i * 2] = text.charCodeAt(i) & 0xff
    out[3 + i * 2] = text.charCodeAt(i) >> 8
  }
  return out
})()

/** Looks like text by name, but holds NUL bytes. */
export const BINARY_BYTES = new Uint8Array([0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, 0x00])

/** Generated on first read: worst cases for the viewer. */
export const GENERATED_TEXT = {
  /** 100,000 short lines (about 4.3 MB): under the limit, must scroll without freezing. */
  hundredThousandLines: () =>
    Array.from(
      { length: 100_000 },
      (_, i) =>
        `2026-11-08T${String(Math.floor(i / 3600) % 24).padStart(2, "0")}:${String(Math.floor(i / 60) % 60).padStart(2, "0")}:${String(i % 60).padStart(2, "0")} INFO line ${i + 1}`
    ).join("\n") + "\n",
  /** One 1.2 MB line (minified JSON). */
  oneHugeLine: () =>
    JSON.stringify({
      items: Array.from({ length: 12_000 }, (_, i) => ({
        id: i,
        name: `item-${i}`,
        tags: ["a", "b"],
        ok: i % 3 === 0,
      })),
    }) + "\n",
  /** 12 MB log: TOO_LARGE unless the first part is loaded read-only. */
  bigLog: () => {
    const line =
      "2026-11-08 21:14:03.120 DEBUG smbj.transport: read 1048576 bytes from 192.168.1.155\n"
    return line.repeat(Math.ceil((12 * 1024 * 1024) / line.length))
  },
} as const

export type GeneratedTextKey = keyof typeof GENERATED_TEXT

/** Approximate sizes for the listing (the real size comes from the read). */
export const GENERATED_SIZES: Record<GeneratedTextKey, number> = {
  hundredThousandLines: 4_388_890,
  oneHugeLine: 600_000,
  bigLog: 12 * 1024 * 1024,
}
