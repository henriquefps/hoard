/**
 * Synthetic photo shoots for the web mock's culling methods (ADR 0008). Every image is a vector
 * drawing (sky, sun, hills and a focus target with fine lines), so it stays crisp at any zoom and a
 * "soft" frame (its `blur`) is visibly soft at 100%, like a missed focus. Nothing here is a real
 * photo: the repo is public and the owner's shoots are private.
 */
import type { CullAnalysis, CullFace, CullMetadata } from "./definitions"
import type { MockFile } from "./mockData"

/** Camera data of one shot (shared by the RAW and the JPEG of a pair). */
export interface MockPhoto {
  /** Camera clock: `2026-08-09T10:12:03.120`. */
  capture: string
  offset?: string
  make: string
  model: string
  lens: string
  exposure: string
  fNumber: number
  iso: number
  focal: number
  focal35?: number
  /** Upright size of the full image. */
  width: number
  height: number
  orientation: number
  /** 0 = tack sharp … 6 = clearly out of focus (Gaussian blur on the subject, in 1/1000 of the width). */
  blur: number
  /** Scene colors and layout. */
  hue: number
  scene: number
  /** Frames of one burst share it (their similarity signature matches, ADR 0009). */
  sceneId?: number
  /** No faces in this frame (the mock analysis finds none). */
  noFaces?: boolean
}

const MiB = 1024 * 1024

/** Deterministic pseudo-random in [0, 1) from a seed (mulberry32). */
function random(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function hashOf(text: string): number {
  let hash = 2166136261
  for (const char of text) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619)
  return hash >>> 0
}

const pad = (n: number, width = 2) => String(n).padStart(width, "0")

/** `2026-08-09T10:12:03.120` from epoch ms read as the camera's clock (UTC fields). */
function clock(ms: number): string {
  const d = new Date(ms)
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` +
    `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}.${pad(d.getUTCMilliseconds(), 3)}`
  )
}

export type MockShotKind = "pair" | "raw" | "jpeg"

export interface ShootOptions {
  /** File name prefix and first number: `DSC` + 7650 → `DSC07650`. */
  prefix: string
  start: number
  count: number
  /** First capture, camera clock (UTC fields). */
  startMs: number
  seed: number
  /** Which files each shot has; default every shot is a RAW + JPEG pair. */
  kindOf?: (index: number) => MockShotKind
  lens?: string
  /** Days ago for the files' modified time. */
  daysAgo?: number
  /** Worst case: the whole folder is one continuous burst (0.1-0.3 s apart, one scene). */
  oneBurst?: boolean
  /** No faces in any frame (landscapes). */
  noFaces?: boolean
}

const LENSES = ["30mm F1.4 DC DN | Contemporary 016", "E PZ 16-50mm F3.5-5.6 OSS", "E 11mm F1.8"]

/**
 * A shoot: RAW (`.ARW`, ~25 MB) and camera JPEG (`.JPG`, 5-8 MB) files with shared {@link MockPhoto}
 * data. Captures are seconds apart with **bursts** (runs of 3-6 frames 0.1-0.3 s apart), a few
 * portrait frames and some soft ones, so sorting, burst hints and focus checks have something to show.
 */
export function buildShoot(options: ShootOptions): MockFile[] {
  const rnd = random(options.seed)
  const files: MockFile[] = []
  let time = options.startMs
  let burstLeft = 0
  let previous: MockPhoto | undefined
  const modifiedAt = new Date(Date.UTC(2026, 10, 8, 12) - (options.daysAgo ?? 3) * 86_400_000)
  for (let i = 0; i < options.count; i++) {
    const inBurst = burstLeft > 0 || (Boolean(options.oneBurst) && i > 0)
    if (options.oneBurst && i > 0) {
      time += 100 + Math.floor(rnd() * 200)
    } else if (burstLeft > 0) {
      time += 100 + Math.floor(rnd() * 200)
      burstLeft--
    } else {
      time += 4_000 + Math.floor(rnd() * 50_000)
      if (rnd() < 0.22) burstLeft = 2 + Math.floor(rnd() * 4)
    }
    const portrait = rnd() < 0.18
    const lens = options.lens ?? LENSES[Math.floor(rnd() * LENSES.length)]
    const focal = lens.startsWith("30mm")
      ? 30
      : lens.startsWith("E 11mm")
        ? 11
        : 16 + Math.round(rnd() * 34)
    const photo: MockPhoto = {
      capture: clock(time),
      offset: "-03:00",
      make: "SONY",
      model: "ZV-E10",
      lens,
      exposure: ["1/50", "1/125", "1/250", "1/500", "1/1000", "1/2000"][Math.floor(rnd() * 6)],
      fNumber: [1.4, 1.8, 2.8, 4, 5.6, 8][Math.floor(rnd() * 6)],
      iso: [100, 200, 400, 800, 1600, 3200][Math.floor(rnd() * 6)],
      focal,
      focal35: Math.round(focal * 1.5),
      width: portrait ? 4000 : 6000,
      height: portrait ? 6000 : 4000,
      orientation: portrait ? 8 : 1,
      blur: rnd() < 0.25 ? 2 + Math.floor(rnd() * 5) : rnd() < 0.5 ? 1 : 0,
      hue: Math.floor(rnd() * 360),
      scene: Math.floor(rnd() * 4),
      sceneId: options.seed + i,
      ...(options.noFaces ? { noFaces: true } : {}),
    }
    // A burst frames the same scene: keep the layout, drift the colours a little.
    if (inBurst && previous) {
      photo.scene = previous.scene
      photo.hue = (previous.hue + 3) % 360
      photo.sceneId = previous.sceneId
      photo.width = previous.width
      photo.height = previous.height
      photo.orientation = previous.orientation
    }
    previous = photo
    const stem = `${options.prefix}${pad(options.start + i, 5)}`
    const kind = options.kindOf?.(i) ?? "pair"
    const at = new Date(modifiedAt.getTime() + i * 1000).toISOString()
    if (kind !== "jpeg") {
      files.push({
        name: `${stem}.ARW`,
        size: Math.round(24.7 * MiB + rnd() * 0.5 * MiB),
        modifiedAt: at,
        photo,
      })
    }
    if (kind !== "raw") {
      files.push({
        name: `${stem}.JPG`,
        size: Math.round(4.6 * MiB + rnd() * 3.4 * MiB),
        modifiedAt: at,
        photo,
      })
    }
  }
  return files
}

/** Camera data for a mock image file that was not built by {@link buildShoot} (phone photos…). */
export function derivedPhoto(name: string, modifiedAt: string | undefined): MockPhoto {
  const seed = hashOf(name.replace(/\.[^.]+$/, "").toLowerCase())
  const rnd = random(seed)
  const base = modifiedAt ? Date.parse(modifiedAt) : Date.UTC(2026, 10, 1)
  return {
    capture: clock(base - 3 * 3_600_000 + Math.floor(rnd() * 1000)),
    make: "Google",
    model: "Pixel 9",
    lens: "Pixel 9 back camera 6.9mm f/1.68",
    exposure: "1/120",
    fNumber: 1.7,
    iso: 50 + Math.floor(rnd() * 8) * 50,
    focal: 6.9,
    focal35: 25,
    width: 4080,
    height: 3072,
    orientation: 1,
    blur: rnd() < 0.2 ? 3 : 0,
    hue: seed % 360,
    scene: seed % 4,
  }
}

/** What `cullMetadata` reports for a photo. */
export function photoMetadata(photo: MockPhoto): CullMetadata {
  const [date, time] = photo.capture.split("T")
  const [y, mo, d] = date.split("-").map(Number)
  const [h, mi, s] = time.split(":")
  const millis =
    Date.UTC(y, mo - 1, d, Number(h), Number(mi), Math.floor(Number(s))) +
    Math.round((Number(s) % 1) * 1000)
  return {
    captureTime: photo.capture,
    captureMillis: millis,
    ...(photo.offset ? { offset: photo.offset } : {}),
    make: photo.make,
    model: photo.model,
    lens: photo.lens,
    exposure: photo.exposure,
    fNumber: photo.fNumber,
    iso: photo.iso,
    focalLength: photo.focal,
    ...(photo.focal35 ? { focalLength35: photo.focal35 } : {}),
    width: photo.width,
    height: photo.height,
    orientation: photo.orientation,
  }
}

/** Focus score like the native Laplacian variance: high for sharp frames, low for soft ones. */
export function photoSharpness(photo: MockPhoto, stem: string) {
  const jitter = (hashOf(stem) % 1000) / 1000
  const best =
    Math.round((900 / (1 + photo.blur * photo.blur * 0.6)) * (0.85 + jitter * 0.3) * 10) / 10
  return { global: Math.round(best * 0.31 * 10) / 10, best, bestTile: 5 + (photo.scene % 2) }
}

export interface ViewBox {
  x: number
  y: number
  width: number
  height: number
}

/**
 * The photo as an SVG data URL: the whole frame or one region of it (`view`, in full-image pixels),
 * drawn at `outWidth × outHeight`.
 */
export function photoSvg(
  photo: MockPhoto,
  label: string,
  outWidth: number,
  outHeight: number,
  view?: ViewBox
): string {
  const W = photo.width
  const H = photo.height
  const v = view ?? { x: 0, y: 0, width: W, height: H }
  const hue = photo.hue
  const cx = W * (0.38 + (photo.scene % 3) * 0.12)
  const cy = H * 0.52
  const r = Math.min(W, H) * 0.16
  const wedges = Array.from({ length: 36 }, (_, i) => {
    const a0 = (i / 36) * Math.PI * 2
    const a1 = ((i + 0.5) / 36) * Math.PI * 2
    return `M${cx} ${cy}L${(cx + r * Math.cos(a0)).toFixed(1)} ${(cy + r * Math.sin(a0)).toFixed(1)}L${(cx + r * Math.cos(a1)).toFixed(1)} ${(cy + r * Math.sin(a1)).toFixed(1)}Z`
  }).join("")
  const lines = Array.from({ length: 48 }, (_, i) => {
    const x = cx + r * 1.25 + i * 9
    return `M${x} ${cy - r}V${cy + r}`
  }).join("")
  const hills =
    photo.scene % 2 === 0
      ? `M0 ${H * 0.72} C${W * 0.2} ${H * 0.55} ${W * 0.45} ${H * 0.8} ${W * 0.7} ${H * 0.62} S${W} ${H * 0.7} ${W} ${H * 0.66} V${H} H0Z`
      : `M0 ${H * 0.8} L${W * 0.3} ${H * 0.5} L${W * 0.55} ${H * 0.74} L${W * 0.8} ${H * 0.56} L${W} ${H * 0.78} V${H} H0Z`
  const blur = (photo.blur * W) / 1000
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${Math.round(outWidth)}" height="${Math.round(outHeight)}" viewBox="${v.x} ${v.y} ${v.width} ${v.height}" preserveAspectRatio="none">` +
    `<defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="hsl(${hue} 55% 70%)"/><stop offset="1" stop-color="hsl(${(hue + 40) % 360} 45% 88%)"/></linearGradient>` +
    (blur > 0
      ? `<filter id="f" x="-10%" y="-10%" width="120%" height="120%"><feGaussianBlur stdDeviation="${blur}"/></filter>`
      : "") +
    `</defs><rect width="${W}" height="${H}" fill="url(#s)"/>` +
    `<circle cx="${W * 0.8}" cy="${H * 0.2}" r="${Math.min(W, H) * 0.07}" fill="hsl(${(hue + 20) % 360} 90% 92%)"/>` +
    `<path d="${hills}" fill="hsl(${(hue + 140) % 360} 30% 32%)"/>` +
    `<g${blur > 0 ? ' filter="url(#f)"' : ""}>` +
    `<circle cx="${cx}" cy="${cy}" r="${r * 1.08}" fill="hsl(${hue} 20% 96%)"/>` +
    `<path d="${wedges}" fill="hsl(${(hue + 200) % 360} 40% 18%)"/>` +
    `<path d="${lines}" stroke="hsl(${(hue + 200) % 360} 40% 18%)" stroke-width="3"/>` +
    `<text x="${cx}" y="${cy + r * 1.6}" text-anchor="middle" font-family="Inter, sans-serif" font-weight="600" font-size="${r * 0.42}" fill="hsl(${(hue + 200) % 360} 40% 14%)">${label}</text>` +
    `</g></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

/** A Sony card's culling folder: mostly pairs, some RAW-only and JPEG-only shots, bursts. */
export function zvE10Shoot(): MockFile[] {
  return buildShoot({
    prefix: "DSC",
    start: 7650,
    count: 36,
    startMs: Date.UTC(2026, 7, 9, 10, 2, 11, 240),
    seed: 20260809,
    kindOf: (i) => (i % 11 === 5 ? "raw" : i % 13 === 9 ? "jpeg" : "pair"),
    daysAgo: 91,
  })
}

/** A NAS folder of 30 RAW + JPEG pairs (the SMB measurement case). */
export function parkShoot(): MockFile[] {
  return buildShoot({
    prefix: "DSC",
    start: 7801,
    count: 30,
    startMs: Date.UTC(2026, 7, 7, 16, 40, 5, 0),
    seed: 20260807,
    lens: LENSES[0],
    daysAgo: 93,
  })
}

/** Worst case for assisted culling: 320 RAW-only frames, all one burst, no faces. */
export function oneBurstShoot(): MockFile[] {
  return buildShoot({
    prefix: "DSC",
    start: 9001,
    count: 320,
    startMs: Date.UTC(2026, 9, 20, 15, 0, 0, 0),
    seed: 20261020,
    kindOf: () => "raw",
    oneBurst: true,
    noFaces: true,
    daysAgo: 20,
  })
}

/** 200 JPEG-only shots (the memory test: scrolling through a long folder). */
export function longShoot(): MockFile[] {
  return buildShoot({
    prefix: "DSC",
    start: 1,
    count: 200,
    startMs: Date.UTC(2026, 9, 12, 9, 0, 0, 0),
    seed: 2026101,
    kindOf: () => "jpeg",
    daysAgo: 27,
  })
}

// --- Assisted culling (ADR 0009) -------------------------------------------------------------

/** 64-bit hex from a seed (two FNV rounds). */
function hex64(seed: string): string {
  const a = hashOf(seed).toString(16).padStart(8, "0")
  const b = hashOf(`${seed}:2`).toString(16).padStart(8, "0")
  return a + b
}

/** Flip `count` bits of a 16-digit hex hash, chosen by `seed`. */
function flipBits(hash: string, count: number, seed: number): string {
  let value = BigInt(`0x${hash}`)
  const rnd = random(seed)
  for (let i = 0; i < count; i++) value ^= 1n << BigInt(Math.floor(rnd() * 64))
  return value.toString(16).padStart(16, "0")
}

function hslRgb(h: number, s: number, l: number): [number, number, number] {
  const k = (n: number) => (n + h / 30) % 12
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)))
  return [Math.round(f(0) * 255), Math.round(f(8) * 255), Math.round(f(4) * 255)]
}

/**
 * What native `cullAnalyze` would measure on a mock photo: sharpness from its `blur`, faces on
 * some scenes (now and then with closed eyes), exposure with a few over/under frames, and a
 * similarity signature shared by the frames of a burst. Deterministic per shot.
 */
export function photoAnalysis(photo: MockPhoto, stem: string): CullAnalysis {
  const h = hashOf(stem)
  const rnd = random(h)
  const portrait = photo.height > photo.width
  const width = portrait ? 1024 : 1536
  const height = portrait ? 1536 : 1024
  const focus = photoSharpness(photo, stem)
  const tiles = Array.from({ length: 16 }, (_, i) =>
    i === focus.bestTile ? focus.best : Math.round(focus.best * (0.15 + rnd() * 0.6) * 10) / 10
  )
  const subjectBlur = Math.round((0.3 + photo.blur * 0.07 + (rnd() - 0.5) * 0.06) * 1000) / 1000
  // Faces: scenes 0 and 2 are "people" shots (1-3 faces); eyes closed now and then.
  const faces: CullFace[] = []
  if (photo.scene % 2 === 0 && !photo.noFaces) {
    const count = 1 + (h % 3)
    for (let i = 0; i < count; i++) {
      const size = i === 0 ? 0.16 : 0.1 - i * 0.02
      const closed = (h >>> (4 + i)) % 9 === 0
      faces.push({
        x: Math.round((0.2 + i * 0.25) * 1000) / 1000,
        y: Math.round((0.25 + (i % 2) * 0.1) * 1000) / 1000,
        width: size,
        height: Math.round(size * (width / height) * 1000) / 1000,
        leftEyeOpen: closed ? 0.04 + rnd() * 0.1 : 0.85 + rnd() * 0.14,
        rightEyeOpen: closed ? 0.03 + rnd() * 0.1 : 0.82 + rnd() * 0.17,
        smiling: Math.round(rnd() * 1000) / 1000,
        prominent: i === 0 || size >= 0.16 * 0.4,
        sharpness: Math.round(focus.best * 0.8 * 10) / 10,
        blur: subjectBlur,
      })
    }
  }
  // Exposure: a bell around the mean; ~8% over, ~6% under.
  const roll = (h >>> 9) % 100
  const mean = roll < 8 ? 0.74 : roll < 14 ? 0.17 : 0.38 + rnd() * 0.18
  const spread = 0.16
  const weights = Array.from({ length: 64 }, (_, i) =>
    Math.exp(-(((i + 0.5) / 64 - mean) ** 2) / (2 * spread * spread))
  )
  const highlights = roll < 8 ? 0.14 + rnd() * 0.1 : rnd() * 0.01
  const shadows = roll >= 8 && roll < 14 ? 0.12 + rnd() * 0.1 : rnd() * 0.004
  weights[63] += highlights * 64
  weights[0] += shadows * 64
  const total = weights.reduce((a, b) => a + b, 0)
  const histogram = weights.map((w) => Math.round((w / total) * 1e5) / 1e5)
  // Similarity: the scene's hash with a few bits flipped per frame; colours from the hue.
  const hash = flipBits(hex64(`scene:${photo.sceneId ?? h}`), 1 + (h % 4), h)
  const colors = Array.from({ length: 16 }, (_, i) => {
    const [r, g, b] = hslRgb((photo.hue + (i < 8 ? 0 : 140)) % 360, 0.45, i < 8 ? 0.72 : 0.32)
    return [r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")
  }).join("")
  return {
    version: 1,
    source: "rawPreview",
    width,
    height,
    millis: 40 + Math.round(rnd() * 60),
    sharpness: {
      global: focus.global,
      tiles,
      bestTile: focus.bestTile,
      subject: faces[0]?.sharpness ?? focus.best,
      subjectKind: faces.length ? "face" : "tile",
      subjectBlur,
    },
    facesDetected: true,
    faces,
    exposure: {
      histogram,
      mean: Math.round(mean * 1e4) / 1e4,
      highlights: Math.round(highlights * 1e5) / 1e5,
      shadows: Math.round(shadows * 1e5) / 1e5,
    },
    hash,
    colors,
  }
}
