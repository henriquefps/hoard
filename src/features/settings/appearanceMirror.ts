import { useEffect } from "react"
import { useTranslation } from "react-i18next"
import type { Db } from "@/db/db"
import { useDb } from "@/db/DbProvider"
import { mirrorSettings } from "@/db/queries"
import type { TextPrefs } from "@/features/text/prefs"
import { useTextPrefs } from "@/features/text/prefs"
import { usePalette, useTheme } from "@/lib/theme/theme"

/**
 * The device's appearance as `settings` rows (ADR 0019 "Lost at the swap unless carried over"): theme, palette,
 * language and the text viewer's preferences live in the WebView's localStorage, which Hoard 2 cannot read. Hoard 1
 * mirrors them into the database the importer copies, under the keys Hoard 2 reads. The mirror only writes; Hoard 1
 * keeps reading localStorage, so nothing it shows changes.
 */
export const MIRROR_KEYS = {
  theme: "themeMode",
  palette: "palette",
  language: "language",
  textViewer: "textViewer",
} as const

export function mirrorRows(
  theme: string,
  palette: string,
  language: string,
  text: TextPrefs
): [string, unknown][] {
  return [
    [MIRROR_KEYS.theme, theme],
    [MIRROR_KEYS.palette, palette],
    [MIRROR_KEYS.language, language],
    [MIRROR_KEYS.textViewer, { wrap: text.wrap, wrapCode: text.wrapCode, fontSize: text.fontSize }],
  ]
}

export async function mirrorAppearance(db: Db, rows: [string, unknown][]): Promise<void> {
  await mirrorSettings(db, rows)
}

/** Keeps the mirror current while the app runs (mounted once, under the database provider). */
export function AppearanceMirror() {
  const db = useDb()
  const theme = useTheme()
  const palette = usePalette()
  const { i18n } = useTranslation()
  const text = useTextPrefs()
  const language = i18n.language
  useEffect(() => {
    void mirrorAppearance(db, mirrorRows(theme, palette, language, text)).catch(() => {})
  }, [db, theme, palette, language, text])
  return null
}
