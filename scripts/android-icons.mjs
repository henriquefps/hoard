// Renders legacy launcher PNGs (pre-API 26) from docs/img/icon.svg. Needs `sharp` (NODE_PATH).
import { createRequire } from 'node:module'
import { readFile } from 'node:fs/promises'
const sharp = createRequire(import.meta.url)('sharp')
const svg = await readFile('docs/img/icon.svg')
const sizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 }
for (const [d, s] of Object.entries(sizes)) {
  const dir = `android/app/src/main/res/mipmap-${d}`
  const base = await sharp(svg, { density: 72 * 4 }).resize(s, s).png().toBuffer()
  await sharp(base).toFile(`${dir}/ic_launcher.png`)
  const mask = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}"><circle cx="${s / 2}" cy="${s / 2}" r="${s / 2}"/></svg>`)
  // round: square art filled edge to edge, clipped to a circle
  const full = await sharp(Buffer.from(svg.toString().replace('rx="28"', 'rx="0"')), { density: 72 * 4 }).resize(s, s).png().toBuffer()
  await sharp(full).composite([{ input: mask, blend: 'dest-in' }]).png().toFile(`${dir}/ic_launcher_round.png`)
}
