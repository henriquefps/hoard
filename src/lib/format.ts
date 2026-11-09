/**
 * Locale-aware formatting for sizes, speeds, durations and dates. Pure (the locale and "now" are
 * parameters) so it is unit tested.
 */

const UNITS = ["B", "KB", "MB", "GB", "TB", "PB"] as const

/** 1024-based sizes with the user's number format: `0 B`, `512 B`, `1,2 MB`, `8,5 GB`. */
export function formatBytes(bytes: number, locale: string): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "—"
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024
    unit++
  }
  const digits = unit === 0 || value >= 100 ? 0 : 1
  const number = new Intl.NumberFormat(locale, {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  }).format(value)
  return `${number} ${UNITS[unit]}`
}

export const formatSpeed = (bytesPerSecond: number, locale: string) =>
  `${formatBytes(bytesPerSecond, locale)}/s`

/** Compact duration, the same in English and Portuguese: `45 s`, `3 min`, `1 h 4 min`. */
export function formatDuration(totalSeconds: number): string {
  if (!Number.isFinite(totalSeconds) || totalSeconds < 0) return "—"
  const seconds = Math.ceil(totalSeconds)
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours >= 48) return `${Math.round(hours / 24)} d`
  return rest ? `${hours} h ${rest} min` : `${hours} h`
}

/** Seconds left at the current speed, or undefined when it cannot be estimated. */
export function etaSeconds(done: number, total: number, bytesPerSecond?: number) {
  if (!bytesPerSecond || bytesPerSecond <= 0 || total <= 0) return undefined
  return Math.max(0, (total - done) / bytesPerSecond)
}

const startOfDay = (date: Date) =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()

/**
 * Relative date without time (hfps card-list rule): today, yesterday, 3 days ago (up to 6), then
 * `Oct 24` this year or `Oct 24, 2024` before.
 */
export function formatRelativeDate(iso: string | null, locale: string, now = new Date()): string {
  if (!iso) return "—"
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return "—"
  const days = Math.round((startOfDay(now) - startOfDay(date)) / 86_400_000)
  if (days >= 0 && days < 7) {
    return new Intl.RelativeTimeFormat(locale, { numeric: "auto" }).format(-days, "day")
  }
  return new Intl.DateTimeFormat(locale, {
    month: "short",
    day: "numeric",
    year: date.getFullYear() === now.getFullYear() ? undefined : "numeric",
  }).format(date)
}

/** Full date and time for `title` tooltips. */
export function formatFullDate(iso: string | null, locale: string): string {
  if (!iso) return ""
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ""
  return new Intl.DateTimeFormat(locale, { dateStyle: "long", timeStyle: "short" }).format(date)
}

export const formatNumber = (value: number, locale: string) =>
  new Intl.NumberFormat(locale).format(value)
