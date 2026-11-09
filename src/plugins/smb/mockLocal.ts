/**
 * The phone's own storage and removable volumes for the web mock (ADR 0005). Scenarios:
 *  - Internal storage: DCIM/Camera (photos and videos), Download, Documents (text files),
 *    Pictures/Screenshots, an empty Movies folder, hidden dot-files, and Android/data + Android/obb
 *    (restricted on Android 11+: listed, not openable).
 *  - "SanDisk USB drive" (`1A2B-3C4D`, attached by default): a Sony camera card copied to a USB
 *    stick — DCIM/100MSDCF with JPG + ARW pairs and MP4 clips, DCIM/101MSDCF with 20,000 files
 *    (worst case), a folder with a long unicode name, and PRIVATE/M4ROOT.
 *  - "SD card" (`6C3E-11F0`, detached by default): mounted read-only (write-protect switch), one
 *    empty folder.
 * Settings → Developer attaches and detaches them and toggles all files access.
 */
import type { MockFile } from "./mockData"
import { longShoot, zvE10Shoot } from "./mockShoot"
import type { StorageVolumeInfo } from "./definitions"

const MiB = 1024 * 1024
const GiB = 1024 * MiB

const at = (daysAgo: number, minute = 0) => {
  const d = new Date(Date.UTC(2026, 10, 8, 10, 30 + minute, 0))
  d.setUTCDate(d.getUTCDate() - daysAgo)
  return d.toISOString()
}

const f = (name: string, size: number, daysAgo = 1, extra: Partial<MockFile> = {}): MockFile => ({
  name,
  size: Math.round(size),
  modifiedAt: at(daysAgo, name.length % 50),
  ...extra,
})

const d = (name: string, children: MockFile[], extra: Partial<MockFile> = {}): MockFile => ({
  name,
  children,
  modifiedAt: at(children.length % 20),
  ...extra,
})

const camera = (count: number): MockFile[] =>
  Array.from({ length: count }, (_, i) => {
    const n = String(20261108 - Math.floor(i / 12)).slice(0, 8)
    const time = `${String(10 + (i % 12)).padStart(2, "0")}${String((i * 7) % 60).padStart(2, "0")}${String((i * 13) % 60).padStart(2, "0")}`
    return i % 9 === 4
      ? f(`VID_${n}_${time}.mp4`, 40 * MiB + ((i * 7919) % (300 * MiB)), Math.floor(i / 12))
      : f(`IMG_${n}_${time}.jpg`, 3 * MiB + ((i * 7919) % (5 * MiB)), Math.floor(i / 12))
  })

const sony = (start: number, count: number, withRaw: boolean): MockFile[] =>
  Array.from({ length: count }, (_, i) => {
    const n = String(start + i).padStart(5, "0")
    const day = Math.floor(i / 150)
    const files = [f(`DSC${n}.JPG`, 9 * MiB + ((i * 7919) % (6 * MiB)), day)]
    if (withRaw) files.push(f(`DSC${n}.ARW`, 47 * MiB + ((i * 104729) % (4 * MiB)), day))
    if (i % 40 === 7) files.push(f(`C${n}.MP4`, 380 * MiB + ((i * 7919) % (900 * MiB)), day))
    return files
  }).flat()

export function buildDeviceTree(): MockFile[] {
  return [
    d("Alarms", []),
    d("Android", [d("data", []), d("obb", []), d("media", [d("com.whatsapp", [])])]),
    d("DCIM", [d("Camera", camera(240)), d(".thumbnails", [], { hidden: true })]),
    d("Documents", [
      f("Notas da viagem.md", 0, 2, {
        text: "# Viagem\n\n- [x] Comprar cartão SD\n- [ ] Copiar as fotos para o NAS\n",
      }),
      f("receipt-2026-11.pdf", 412_000, 5),
      f("lista.txt", 0, 1, { text: "leite\npão\ncafé\n" }),
    ]),
    d("Download", [
      f("hoard-1.2.1.apk", 9.6 * MiB, 1),
      f("relatório anual — versão final (2).pdf", 2.4 * MiB, 3),
      f("site.zip", 18 * MiB, 9),
      d("site", [
        f("index.html", 0, 9, {
          text: "<!doctype html><title>Site</title><h1>Hello from the phone</h1>",
        }),
        f("style.css", 0, 9, { text: "body{font-family:sans-serif}" }),
      ]),
    ]),
    d("Movies", []),
    d("Music", [f("Ensaio — faixa 01.flac", 31 * MiB, 40), f("podcast.mp3", 54 * MiB, 2)]),
    d("Pictures", [
      d(
        "Screenshots",
        Array.from({ length: 36 }, (_, i) =>
          f(
            `Screenshot_20261${String(100 + i).slice(1)}-0930${String(i).padStart(2, "0")}.png`,
            900_000 + i * 1000,
            i
          )
        )
      ),
    ]),
    f(".nomedia", 0, 300, { hidden: true }),
  ]
}

export function buildUsbTree(): MockFile[] {
  return [
    d("DCIM", [
      d("100MSDCF", sony(1, 320, true)),
      d("101MSDCF", sony(1001, 20_000, false)),
      d("102MSDCF", zvE10Shoot()),
      d("103MSDCF", longShoot()),
    ]),
    d("PRIVATE", [d("M4ROOT", [d("CLIP", []), f("MEDIAPRO.XML", 18_000, 1)])]),
    d(
      "Seleção — viagem a Lisboa e ao Porto, novembro de 2026 (fotos escolhidas para imprimir)",
      []
    ),
    f("LEIA-ME.txt", 0, 30, { text: "Cartão da Sony A7 IV.\n" }),
  ]
}

export function buildSdTree(): MockFile[] {
  return [d("Empty", []), f("WRITE-PROTECTED.txt", 0, 60, { text: "This card is locked.\n" })]
}

/** The volumes the mock knows, with the parts the mock fills in at runtime left out. */
export const MOCK_VOLUMES: Array<
  Omit<StorageVolumeInfo, "access" | "state"> & {
    uuid: string | null
    attachedByDefault: boolean
    readOnly: boolean
    build: () => MockFile[]
  }
> = [
  {
    uuid: null,
    label: "Internal storage",
    primary: true,
    removable: false,
    kind: "internal",
    path: "/storage/emulated/0",
    totalBytes: 128 * GiB,
    freeBytes: 41.3 * GiB,
    fileSystem: "fuse",
    attachedByDefault: true,
    readOnly: false,
    build: buildDeviceTree,
  },
  {
    uuid: "1A2B-3C4D",
    label: "SanDisk USB drive",
    primary: false,
    removable: true,
    kind: "usb",
    path: "/storage/1A2B-3C4D",
    totalBytes: 64 * GiB,
    freeBytes: 22.8 * GiB,
    fileSystem: "exfat",
    attachedByDefault: true,
    readOnly: false,
    build: buildUsbTree,
  },
  {
    uuid: "6C3E-11F0",
    label: "SD card",
    primary: false,
    removable: true,
    kind: "sd",
    path: "/storage/6C3E-11F0",
    totalBytes: 32 * GiB,
    freeBytes: 31.9 * GiB,
    fileSystem: "vfat",
    attachedByDefault: false,
    readOnly: true,
    build: buildSdTree,
  },
]
