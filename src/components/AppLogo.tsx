import { useTranslation } from "react-i18next"

/**
 * The Hoard mark (scripts/glyph.svg): a sheet with a second sheet behind it. The viewBox is the
 * glyph's ink box (strokes included), so it fills its box like any other icon.
 */
export function HoardMark({ className }: { className?: string }) {
  return (
    <svg viewBox="15.5 5.5 92.7 115" className={className} aria-hidden fill="none">
      <path
        d="M56.056 5.732L89.135 12.763C90.239 13.227 91.27 13.929 92.098 14.794L107.427 38.399C108.037 39.674 108.232 40.528 108.071 41.933L96.881 94.575C96.253 96.744 95.407 98.552 94.34 100.004L94.34 63.243L98.9 41.792L85.525 21.197L54.404 14.582C51.147 14.362 50.997 14.459 49.874 17.524L48.995 21.66L39.794 21.66L41.119 15.425C43.34 7.756 48.287 4.605 56.056 5.732Z"
        fillRule="evenodd"
        fill="currentColor"
        opacity="0.45"
      />
      <g stroke="currentColor" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round">
        <path d="M28 30L64 30L86 52L86 108C86 113.333 83.333 116 78 116L28 116C22.667 116 20 113.333 20 108L20 38C20 32.667 22.667 30 28 30Z" />
        <path d="M62 30L62 54L86 54" />
        <path d="M36 76L70 76" />
        <path d="M36 94L60 94" />
      </g>
    </svg>
  )
}

export function AppLogo() {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-2">
      <HoardMark className="size-6 text-primary" />
      <span className="text-[0.9375rem] font-semibold">{t("app.name")}</span>
    </div>
  )
}
