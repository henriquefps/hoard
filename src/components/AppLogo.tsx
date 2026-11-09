import { useTranslation } from "react-i18next"

/** The Hoard mark (scripts/glyph.svg): a stash of folders with a network signal. */
export function HoardMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 128 128" className={className} aria-hidden fill="none">
      <g
        transform="matrix(1.28 0 0 1.28 0 0)"
        stroke="currentColor"
        strokeWidth="5.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M38 12H62" opacity="0.45" />
        <path d="M26 26H74" opacity="0.7" />
        <path d="M14 84V46Q14 40 20 40H36L43 48H80Q86 48 86 54V84Q86 90 80 90H20Q14 90 14 84Z" />
        <path d="M41 69A12 12 0 0 1 59 69" />
        <path d="M33 63A24 24 0 0 1 67 63" />
        <circle cx="50" cy="77" r="4" fill="currentColor" stroke="none" />
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
