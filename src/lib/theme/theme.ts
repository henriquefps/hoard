import { useSyncExternalStore } from "react"

// Two independent axes saved in localStorage (guarded: storage can be unavailable):
//  - theme: `system` follows prefers-color-scheme; `light`/`dark` put that class on <html>.
//  - palette: `data-palette="<id>"` on <html> (assets/palettes.css). The default has no attribute.
// Call initTheme() once before the first render (works under a CSP that forbids inline scripts).
// Rename the storage keys to your app ('<app>.theme', '<app>.palette').

export const THEMES = ["light", "dark", "system"] as const
export type Theme = (typeof THEMES)[number]
export const DEFAULT_THEME: Theme = "system"
export const THEME_STORAGE_KEY = "hoard.theme"

export const PALETTES = ["blue", "olive", "violet", "rose", "graphite"] as const
export type Palette = (typeof PALETTES)[number]
export const DEFAULT_PALETTE: Palette = "blue"
export const PALETTE_STORAGE_KEY = "hoard.palette"

/** Light-mode primary, approximated in sRGB: swatches in the picker and <meta name="theme-color">. */
export const PALETTE_SWATCHES: Record<Palette, string> = {
  blue: "#2063b0",
  olive: "#5b7a3a",
  violet: "#7a55c0",
  rose: "#c0467a",
  graphite: "#52575e",
}

export const isTheme = (v: unknown): v is Theme => THEMES.includes(v as Theme)
export const isPalette = (v: unknown): v is Palette => PALETTES.includes(v as Palette)

function read<T>(key: string, ok: (v: unknown) => v is T, fallback: T): T {
  try {
    const saved = localStorage.getItem(key)
    return ok(saved) ? saved : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // The choice then lasts for this page load only.
  }
}

function applyTheme(theme: Theme): void {
  const root = document.documentElement
  root.classList.remove("light", "dark")
  if (theme !== "system") root.classList.add(theme)
}

function applyPalette(palette: Palette): void {
  const root = document.documentElement
  if (palette === DEFAULT_PALETTE) root.removeAttribute("data-palette")
  else root.setAttribute("data-palette", palette)
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute("content", PALETTE_SWATCHES[palette])
}

let theme: Theme = DEFAULT_THEME
let palette: Palette = DEFAULT_PALETTE
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())

export const getTheme = () => theme
export const getPalette = () => palette

export function setTheme(next: Theme): void {
  theme = next
  applyTheme(next)
  write(THEME_STORAGE_KEY, next)
  emit()
}

export function setPalette(next: Palette): void {
  palette = next
  applyPalette(next)
  write(PALETTE_STORAGE_KEY, next)
  emit()
}

/** Reads the saved theme and palette and applies them; call once before the first render. */
export function initTheme(): void {
  theme = read(THEME_STORAGE_KEY, isTheme, DEFAULT_THEME)
  applyTheme(theme)
  palette = read(PALETTE_STORAGE_KEY, isPalette, DEFAULT_PALETTE)
  applyPalette(palette)
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export const useTheme = () => useSyncExternalStore(subscribe, getTheme, getTheme)
export const usePalette = () => useSyncExternalStore(subscribe, getPalette, getPalette)
