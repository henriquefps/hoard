/**
 * Fake servers, networks and file trees for the web mock (`web.ts`). The browser dev build
 * (`bun run dev`) seeds the database with {@link DEMO_MACHINES} so the UI has something to show;
 * the same ids and addresses resolve to the fake servers below.
 *
 * Scenarios covered (keep them when editing, the UI task develops against them):
 *  - cachyos-fps: local 192.168.1.155 answers fast, Tailscale and MagicDNS slower. Media library with
 *    nested folders, many images, videos, an 8 GiB file, long and non-Latin names, an empty folder,
 *    a folder with 1,284 files, a file whose transfer drops mid-way and fails over ("flaky").
 *  - nas-sala: local works, its Tailscale address times out. Has a share the account cannot open.
 *  - office-pc: local address unreachable (other network), reached through a public name; it rejects
 *    the demo password (AUTH_FAILED) until the user saves the password "office".
 *  - 192.168.0.10 is somebody else's server: answers on 445 with a different GUID (GUID_MISMATCH).
 *  - homes/henrique/Documents and Projects/hoard: text files for the built-in viewer and editor
 *    (Markdown with every extension, code, CSV, CRLF, windows-1252, UTF-16, a binary ".txt", a file
 *    someone else edits right after you open it ("conflict"), 100,000 lines, one huge line, a 12 MB
 *    log). NAS-SALA's Public share is read-only.
 *  - lab (worst-case pass, seeded from Settings → Developer in the browser only): a folder with 5,000
 *    entries, unicode and very long names, a 1.4 TiB image, zero-byte files, missing dates, an empty
 *    share, a transfer that fails mid-way with no address left ("broken"), and 20 machines.
 */

import {
  CONFIG_YAML,
  DATA_CSV,
  DEPLOY_SH,
  GENERATED_SIZES,
  type GeneratedTextKey,
  HOARD_README,
  LATIN1_BYTES,
  MAIN_KT,
  NOTES_MD,
  PACKAGE_JSON,
  TODO_MD,
  UTF16_BYTES,
  WINDOWS_TXT,
  BINARY_BYTES,
} from "./mockText"

export interface MockFile {
  name: string
  size?: number
  modifiedAt?: string
  hidden?: boolean
  children?: MockFile[]
  /** Folder content the account cannot read (`ACCESS_DENIED`). */
  denied?: boolean
  /** File content for `readTextFile` (UTF-8). */
  text?: string
  /** Raw file content (other encodings, binary); wins over `text`. */
  bytes?: Uint8Array
  /** Content generated on first read (worst-case files). */
  generated?: GeneratedTextKey
}

export interface MockShare {
  name: string
  comment?: string
  hidden?: boolean
  denied?: boolean
  /** The account may read but not write (`writeTextFile` rejects `ACCESS_DENIED`). */
  readOnly?: boolean
  root: MockFile[]
}

export interface MockServer {
  guid: string
  serverName: string
  dialect: "SMB_3_1_1" | "SMB_3_0_2" | "SMB_2_1"
  /** Passwords this server accepts for any username (the mock ignores usernames). */
  passwords: string[]
  shares: MockShare[]
}

/** How a host behaves from the browser's point of view. */
export interface MockRoute {
  serverKey: keyof typeof MOCK_SERVERS | null
  /** Round-trip of a TCP connect. */
  latencyMs: number
  /** "timeout": never answers (probe waits the full timeout); "refused": fails fast. */
  failure?: "timeout" | "refused"
}

const KiB = 1024
const MiB = 1024 * KiB
const GiB = 1024 * MiB

const day = (offsetDays: number, hour = 12) => {
  const d = new Date(Date.UTC(2026, 10, 8, hour, 14, 3))
  d.setUTCDate(d.getUTCDate() - offsetDays)
  return d.toISOString()
}

const dir = (name: string, children: MockFile[], extra: Partial<MockFile> = {}): MockFile => ({
  name,
  children,
  modifiedAt: day(children.length % 30),
  ...extra,
})

const file = (
  name: string,
  size: number,
  daysAgo = 3,
  extra: Partial<MockFile> = {}
): MockFile => ({
  name,
  size: Math.round(size),
  modifiedAt: day(daysAgo, 9 + (name.length % 12)),
  ...extra,
})

const photos = (prefix: string, count: number, startDay: number): MockFile[] =>
  Array.from({ length: count }, (_, i) =>
    file(
      `${prefix}_${String(i + 1).padStart(4, "0")}.jpg`,
      2 * MiB + ((i * 7919) % (4 * MiB)),
      startDay + i
    )
  )

const TiB = 1024 * GiB

const utf8Length = (text: string) => new TextEncoder().encode(text).length

/** A file with text content (size from the content). */
const textFile = (name: string, text: string, daysAgo = 2, extra: Partial<MockFile> = {}) =>
  file(name, utf8Length(text), daysAgo, { text, ...extra })

const bytesFile = (name: string, bytes: Uint8Array, daysAgo = 2) =>
  file(name, bytes.length, daysAgo, { bytes })

const generatedFile = (name: string, generated: GeneratedTextKey, daysAgo = 1) =>
  file(name, GENERATED_SIZES[generated], daysAgo, { generated })

/** Plausible worst-case names (break-ui pass): long, unicode, RTL, emoji, one letter, dots. */
const WORST_NAMES = [
  "Relatório financeiro consolidado do terceiro trimestre — versão revisada pela diretoria (FINAL) (2).xlsx",
  "مستندات العقد النهائي.pdf",
  "提案書_最終版_修正済み_2026年11月.docx",
  "🎉 Festa de aniversário da Jo 🎂.mp4",
  "x",
  "Ølstue på Nørrebro – billeder.jpg",
  "IMG_20261108_235959_HDR_PORTRAIT_NIGHT_MODE_EDITED_COPY_COPY.jpg",
  "a.b.c.d.e.tar.gz",
  "no-extension-at-all",
  "Ψηφιακό αρχείο.txt",
]

/** 5,000 entries: 300 folders, then files of every kind (deterministic). */
const fiveThousand: MockFile[] = Array.from({ length: 5000 }, (_, i) => {
  if (i < 300) return dir(`Pasta ${String(i + 1).padStart(3, "0")}`, [])
  const kinds = ["jpg", "jpg", "jpg", "png", "mp4", "pdf", "docx", "flac", "zip", "txt", "heic"]
  const ext = kinds[i % kinds.length]
  const name =
    i % 97 === 0
      ? WORST_NAMES[((i / 97) % WORST_NAMES.length) | 0]
      : `${ext === "jpg" || ext === "heic" ? "IMG" : "file"}_${String(i).padStart(5, "0")}.${ext}`
  return file(name, i % 211 === 0 ? 0 : 40 * KiB + ((i * 7919) % (60 * MiB)), i % 900, {
    modifiedAt: i % 53 === 0 ? undefined : day(i % 900),
  })
})

const manyFiles = Array.from({ length: 1284 }, (_, i) =>
  file(
    `scan-${String(i + 1).padStart(5, "0")}.pdf`,
    120 * KiB + ((i * 104729) % (900 * KiB)),
    i % 400
  )
)

export const MOCK_SERVERS = {
  cachy: {
    guid: "6f1c2b9e-3a5d-4c7e-9b21-0d8e4f7a1c35",
    serverName: "CACHYOS-FPS",
    dialect: "SMB_3_1_1",
    passwords: ["demo"],
    shares: [
      {
        name: "media",
        comment: "Movies, series and music",
        root: [
          dir("Movies", [
            file("Blade Runner 2049 (2017) [2160p HDR].mkv", 8 * GiB + 512 * MiB, 120),
            file("Spirited Away (2001).mkv", 4 * GiB + 300 * MiB, 200),
            file("千と千尋の神隠し - 特典映像.mp4", 700 * MiB, 210),
            file("flaky-network-test.mp4", 380 * MiB, 2),
            file("Spirited Away (2001).srt", 96 * KiB, 200),
          ]),
          dir("Series", [
            dir("Severance", [
              dir("Season 1", [
                file("Severance S01E01 Good News About Hell.mkv", 1.6 * GiB, 300),
                file("Severance S01E02 Half Loop.mkv", 1.5 * GiB, 299),
                file("Severance S01E03 In Perpetuity.mkv", 1.4 * GiB, 298),
              ]),
              dir("Season 2", []),
            ]),
          ]),
          dir("Music", [
            dir("Caetano Veloso - Transa (1972)", [
              file("01 - You Don't Know Me.flac", 38 * MiB, 500),
              file("02 - Nine Out of Ten.flac", 42 * MiB, 500),
              file("cover.jpg", 1.2 * MiB, 500),
            ]),
          ]),
          dir("Empty folder", []),
          file(".DS_Store", 6 * KiB, 1, { hidden: true }),
        ],
      },
      {
        name: "photos",
        comment: "Phone backups",
        root: [
          dir("2026", [
            dir("2026-10 Lisbon trip", photos("IMG", 48, 30)),
            dir("2026-11", [
              ...photos("PXL_20261101", 12, 1),
              file("VID_20261104_181022.mp4", 412 * MiB, 4),
            ]),
          ]),
          dir("2025", [dir("Christmas", photos("DSC", 24, 320))]),
          dir("Scans", manyFiles),
          file(
            "a-very-long-file-name-that-keeps-going-to-test-truncation-and-wrapping-in-every-list-and-card-layout-final-v2-REALLY-final.png",
            3.4 * MiB,
            12
          ),
          file("screenshot.webp", 320 * KiB, 0),
        ],
      },
      {
        name: "homes",
        comment: "Home directories",
        root: [
          dir("henrique", [
            dir("Documents", [
              file("Contrato de locação 2026.pdf", 840 * KiB, 60),
              file("Budget 2026.xlsx", 92 * KiB, 5),
              textFile("notes.md", NOTES_MD, 0),
              textFile("todo.md", TODO_MD, 1),
              textFile(
                "shared-with-conflict.md",
                "# Shared notes\n\nSomeone else edits this file right after you open it.\n",
                3
              ),
              dir("img", [file("network.png", 180 * KiB, 30)]),
              dir("Site", [
                file("index.html", 6 * KiB, 2),
                dir("css", [file("site.css", 3 * KiB, 2)]),
                dir("js", [file("app.js", 2 * KiB, 2)]),
                dir("img", [file("logo.png", 24 * KiB, 2)]),
              ]),
              file("Résumé — Henrique.docx", 64 * KiB, 90),
            ]),
            dir("Projects", [
              dir("hoard", [
                textFile("README.md", HOARD_README, 4),
                textFile("package.json", PACKAGE_JSON, 4),
                textFile("MainActivity.kt", MAIN_KT, 6),
                textFile("deploy.sh", DEPLOY_SH, 9),
                textFile("docker-compose.yml", CONFIG_YAML, 12),
                textFile("backups.csv", DATA_CSV, 2),
                textFile("windows-crlf.txt", WINDOWS_TXT, 20),
                bytesFile("latin1-relatorio.txt", LATIN1_BYTES, 40),
                bytesFile("utf16-notes.txt", UTF16_BYTES, 41),
                bytesFile("firmware.txt", BINARY_BYTES, 42),
                textFile("empty.txt", "", 1),
                generatedFile("100k-lines.log", "hundredThousandLines"),
                generatedFile("minified.json", "oneHugeLine"),
                generatedFile("server-12mb.log", "bigLog"),
              ]),
            ]),
            dir("Downloads", [
              file("android-studio-2026.1-linux.tar.gz", 1.3 * GiB, 14),
              file("archive.zip", 220 * MiB, 40),
            ]),
            file(".bashrc", 3 * KiB, 400, { hidden: true }),
          ]),
        ],
      },
      { name: "IPC$", hidden: true, root: [] },
    ],
  },
  nas: {
    guid: "a7d40e11-92c3-4b5f-8e6a-3f1b2c9d0e74",
    serverName: "NAS-SALA",
    dialect: "SMB_3_0_2",
    passwords: ["demo"],
    shares: [
      {
        name: "Backup",
        root: [
          dir("Time Machine", [], { denied: true }),
          file("router-config-2026-09.bin", 64 * KiB, 50),
          file("backup-2026-11-01.tar.zst", 18 * GiB, 7),
        ],
      },
      {
        name: "Public",
        comment: "Shared with everyone",
        readOnly: true,
        root: [
          dir("Wallpapers", photos("wall", 9, 100)),
          textFile("README.txt", "Public share of the NAS. Read-only for this account.\n", 300),
        ],
      },
      { name: "admin", comment: "Administrators only", denied: true, root: [] },
    ],
  },
  office: {
    guid: "0c9e8d7f-1a2b-4c3d-8e9f-a0b1c2d3e4f5",
    serverName: "OFFICE-PC",
    dialect: "SMB_2_1",
    passwords: ["office"],
    shares: [
      {
        name: "Users",
        root: [dir("Public", [file("desktop.ini", 174, 600, { hidden: true })])],
      },
    ],
  },
  lab: {
    guid: "3b0f7c2a-55e1-4d0c-9a9e-6c1d2e3f4a5b",
    serverName: "ARQUIVO-ESCRITORIO-2ANDAR",
    dialect: "SMB_3_1_1",
    passwords: ["demo"],
    shares: [
      {
        name: "Arquivo morto — 2019–2026 (não apagar)",
        comment:
          "Documentos antigos do escritório; peça acesso à Aleksandra Wiśniewska-Kowalczyk antes de mexer",
        root: [
          dir("5000 itens", fiveThousand),
          dir("Pasta vazia", []),
          ...WORST_NAMES.map((name, i) => file(name, (i + 1) * 3.3 * MiB, i)),
          file("disk-image-full-backup-2026.img", 1.4 * TiB, 1),
          file("broken-transfer-demo.iso", 900 * MiB, 1),
          file("empty.txt", 0, 0),
          file("undated.bin", 12 * KiB, 0, { modifiedAt: undefined }),
        ],
      },
      {
        name: "共有フォルダ",
        comment: "日本チームの共有",
        root: [dir("写真", photos("写真", 6, 3))],
      },
      { name: "x", root: [] },
    ],
  },
  neighbor: {
    guid: "ffffffff-0000-4000-8000-000000000001",
    serverName: "SOMEONE-ELSES-NAS",
    dialect: "SMB_3_1_1",
    passwords: [],
    shares: [],
  },
} satisfies Record<string, MockServer>

export type MockServerKey = keyof typeof MOCK_SERVERS

/** Host (lowercase) → behavior. Anything not listed is refused after 30 ms. */
export const MOCK_ROUTES: Record<string, MockRoute> = {
  "192.168.1.155": { serverKey: "cachy", latencyMs: 8 },
  "100.101.12.34": { serverKey: "cachy", latencyMs: 70 },
  "cachyos-fps": { serverKey: "cachy", latencyMs: 75 },
  "cachyos-fps.tail1234.ts.net": { serverKey: "cachy", latencyMs: 78 },
  "192.168.1.20": { serverKey: "nas", latencyMs: 12 },
  "100.88.7.5": { serverKey: "nas", latencyMs: 0, failure: "timeout" },
  "10.0.0.40": { serverKey: null, latencyMs: 0, failure: "timeout" },
  "office.example.com": { serverKey: "office", latencyMs: 160 },
  "192.168.0.10": { serverKey: "neighbor", latencyMs: 10 },
  "192.168.1.77": { serverKey: "lab", latencyMs: 25 },
  "100.70.1.77": { serverKey: "lab", latencyMs: 95 },
}

/** Default "refused" behavior for unknown hosts. */
export const UNKNOWN_ROUTE: MockRoute = { serverKey: null, latencyMs: 30, failure: "refused" }

/** Bytes per second a simulated transfer runs at, per server-side route latency bucket. */
export const mockSpeedFor = (latencyMs: number) =>
  latencyMs < 20 ? 48 * MiB : latencyMs < 100 ? 9 * MiB : 2.5 * MiB

/** Machines the web build seeds into an empty database (ids are stable so credentials match). */
export const DEMO_MACHINES = [
  {
    id: "demo-cachyos-fps",
    name: "cachyos-fps",
    icon: "desktop-tower",
    color: "blue",
    username: "henrique",
    domain: null,
    serverKey: "cachy" as MockServerKey,
    addresses: ["192.168.1.155", "100.101.12.34", "cachyos-fps"],
    favorites: [
      { share: "media", path: "Movies", label: "Movies" },
      { share: "photos", path: "2026/2026-11", label: "Photos this month" },
    ],
  },
  {
    id: "demo-nas-sala",
    name: "NAS da sala",
    icon: "hard-drives",
    color: "violet",
    username: "admin",
    domain: null,
    serverKey: "nas" as MockServerKey,
    addresses: ["192.168.1.20", "100.88.7.5"],
    favorites: [{ share: "Backup", path: "", label: "Backup" }],
  },
  {
    id: "demo-office-pc",
    name: "Office PC",
    icon: "desktop",
    color: "rose",
    username: "henrique",
    domain: "WORKGROUP",
    serverKey: "office" as MockServerKey,
    addresses: ["10.0.0.40", "office.example.com"],
    favorites: [],
  },
] as const

/**
 * Worst-case machines (dev only, Settings → Developer): one reachable server with absurd data plus
 * enough unreachable ones to make 20 machines. Their credentials are "demo" like the others.
 */
export const WORST_CASE_MACHINES = [
  {
    id: "worst-lab",
    name: "Servidor de arquivos do escritório da Aleksandra Wiśniewska-Kowalczyk (2º andar)",
    icon: "hard-drives",
    color: "amber",
    username: "aleksandra.wisniewska-kowalczyk@northwind-industries-holdings.example.com",
    domain: "NORTHWIND",
    addresses: ["192.168.1.77", "100.70.1.77", "arquivo-escritorio-segundo-andar.tail1234.ts.net"],
    favorites: [
      { share: "Arquivo morto — 2019–2026 (não apagar)", path: "5000 itens", label: "5000 itens" },
    ],
  },
  ...[
    "X",
    "Jo",
    "Raspberry Pi 5 (garagem)",
    "サーバー",
    "خادم الملفات",
    "media-server-01.home.arpa",
    "Mac mini da sala de reuniões do terceiro andar, perto da copa",
    "NAS",
    "backup-offsite-hetzner-storagebox-bx11",
    "Ølstue NAS",
    "PC do Jo 🎮",
    "Synology DS920+",
    "TrueNAS Scale",
    "windows-11-vm",
    "old-laptop",
    "Câmera de segurança (NVR)",
    "printer-scan-folder",
  ].map((name, i) => ({
    id: `worst-${i}`,
    name,
    icon: ["desktop", "laptop", "cloud", "house", "desktop-tower", "hard-drives"][i % 6],
    color: ["blue", "violet", "rose", "green", "amber", "teal", null][i % 7],
    username: i % 2 ? "admin" : "j",
    domain: null,
    addresses:
      i === 2
        ? ["192.168.50.2", "192.168.50.3", "10.8.0.2", "100.64.0.9", "nas.local", "203.0.113.40"]
        : [`192.168.9.${i + 10}`, ...(i % 3 ? [`100.99.0.${i}`] : [])],
    favorites: [] as { share: string; path: string; label: string }[],
  })),
]

/** Credentials the mock pretends are already in the Keystore for the demo machines. */
export const DEMO_PASSWORDS: Record<string, string> = {
  "demo-cachyos-fps": "demo",
  "demo-nas-sala": "demo",
  // Wrong on purpose: connecting to Office PC fails with AUTH_FAILED until "office" is saved.
  "demo-office-pc": "demo",
  "worst-lab": "demo",
}
