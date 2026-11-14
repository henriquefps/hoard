import i18n from "i18next"
import { initReactI18next } from "react-i18next"
import en from "./locales/en.json"
import ptBR from "./locales/pt-BR.json"

export const LANGUAGES = ["en", "pt-BR"] as const
export type Language = (typeof LANGUAGES)[number]
const STORAGE_KEY = "hoard-web.language"

export const isLanguage = (value: unknown): value is Language => LANGUAGES.includes(value as Language)

function detectLanguage(): Language {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (isLanguage(saved)) return saved
  } catch {
    // storage unavailable
  }
  return navigator.language?.toLowerCase().startsWith("pt") ? "pt-BR" : "en"
}

export function setLanguage(language: Language): void {
  void i18n.changeLanguage(language)
  document.documentElement.lang = language
  try {
    localStorage.setItem(STORAGE_KEY, language)
  } catch {
    // lasts for this page load only
  }
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, "pt-BR": { translation: ptBR } },
  lng: detectLanguage(),
  fallbackLng: "en",
  interpolation: { escapeValue: false },
})
document.documentElement.lang = i18n.language

export default i18n
