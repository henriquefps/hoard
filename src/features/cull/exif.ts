import type { TFunction } from "i18next"
import type { CullMetadata } from "@/plugins/smb/definitions"

/** `15:04:05.733` and `Aug 7, 2026` from the camera clock (no zone: shown as the camera wrote it). */
export function formatCapture(captureTime: string | undefined, locale: string) {
  if (!captureTime) return undefined
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?/.exec(captureTime)
  if (!match) return undefined
  const [, y, mo, d, h, mi, s, ms] = match
  const date = new Date(Date.UTC(Number(y), Number(mo) - 1, Number(d)))
  return {
    time: `${h}:${mi}:${s}${ms ? `.${ms.padEnd(3, "0")}` : ""}`,
    date: new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(date),
  }
}

/** `1/250 s · f/1.8 · ISO 400` (whatever is known). */
export function exposureLine(m: CullMetadata | undefined, locale: string) {
  if (!m) return undefined
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 })
  const parts = [
    m.exposure ? `${m.exposure} s` : undefined,
    m.fNumber ? `f/${number.format(m.fNumber)}` : undefined,
    m.iso ? `ISO ${m.iso}` : undefined,
  ].filter(Boolean)
  return parts.length ? parts.join(" · ") : undefined
}

/** `30 mm (45 mm)`. */
export function focalLine(m: CullMetadata | undefined, locale: string, t: TFunction) {
  if (!m?.focalLength) return undefined
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 })
  const focal = `${number.format(m.focalLength)} mm`
  return m.focalLength35 && Math.round(m.focalLength35) !== Math.round(m.focalLength)
    ? `${focal} ${t("cull.exif.equivalent", { value: `${number.format(m.focalLength35)} mm` })}`
    : focal
}
