/** Formatting for the gallery. Times are wall-clock values read as UTC (see Row.l), so every format uses UTC. */

export function formatDay(day: string, locale: string): string {
  const t = Date.parse(day + "T00:00:00Z")
  const now = new Date()
  const sameYear = now.getUTCFullYear() === Number(day.slice(0, 4))
  return new Intl.DateTimeFormat(locale, {
    weekday: "short",
    day: "numeric",
    month: "long",
    year: sameYear ? undefined : "numeric",
    timeZone: "UTC",
  }).format(t)
}

export function formatMonth(year: number, month: number, locale: string, style: "short" | "long" = "short"): string {
  return new Intl.DateTimeFormat(locale, { month: style, timeZone: "UTC" }).format(Date.UTC(year, month - 1, 1))
}

export function formatMonthYear(key: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(
    Date.parse(key + "-01T00:00:00Z")
  )
}

export function formatDateTime(ms: number, locale: string, withMs = false): string {
  const base = new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "UTC",
  }).format(ms)
  return withMs && ms % 1000 ? `${base} .${String(ms % 1000).padStart(3, "0")}` : base
}

export function formatDuration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, "0")
  return h ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`
}

export function formatBytes(n: number, locale: string): string {
  const units = ["B", "KB", "MB", "GB", "TB"]
  let v = n
  let u = 0
  while (v >= 1000 && u < units.length - 1) {
    v /= 1000
    u++
  }
  const digits = u === 0 || v >= 100 ? 0 : 1
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: digits }).format(v)} ${units[u]}`
}

/** 1/250 s, 0.5 s, 2 s, 30 s. */
export function formatShutter(seconds: number): string {
  if (seconds <= 0) return ""
  if (seconds < 0.3) return `1/${Math.round(1 / seconds)} s`
  return `${Math.round(seconds * 10) / 10} s`
}

export const formatNumber = (n: number, locale: string) => new Intl.NumberFormat(locale).format(n)
