import { useSyncExternalStore } from "react"

/**
 * Viewer conveniences remembered per device (soft wrap, font size): localStorage, every access
 * guarded, defaults when storage is unavailable.
 */
export interface TextPrefs {
  /** Soft wrap for prose (Markdown, plain text, logs, CSV). */
  wrap: boolean
  /** Soft wrap for source code (off by default: wrapping breaks indentation on phones). */
  wrapCode: boolean
  fontSize: number
}

export const FONT_SIZES = { min: 12, max: 24, default: 14 } as const
const KEY = "hoard.textViewer"
const DEFAULTS: TextPrefs = { wrap: true, wrapCode: false, fontSize: FONT_SIZES.default }

function read(): TextPrefs {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? "{}") as Partial<TextPrefs>
    return {
      wrap: typeof saved.wrap === "boolean" ? saved.wrap : DEFAULTS.wrap,
      wrapCode: typeof saved.wrapCode === "boolean" ? saved.wrapCode : DEFAULTS.wrapCode,
      fontSize:
        typeof saved.fontSize === "number" &&
        saved.fontSize >= FONT_SIZES.min &&
        saved.fontSize <= FONT_SIZES.max
          ? saved.fontSize
          : DEFAULTS.fontSize,
    }
  } catch {
    return DEFAULTS
  }
}

let current = read()
const listeners = new Set<() => void>()

export function setTextPrefs(next: Partial<TextPrefs>) {
  current = { ...current, ...next }
  try {
    localStorage.setItem(KEY, JSON.stringify(current))
  } catch {
    // Lasts for this page load only.
  }
  listeners.forEach((listener) => listener())
}

export function useTextPrefs(): TextPrefs {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => current
  )
}
