import { DesktopIcon, MoonIcon, SunIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  isPalette,
  isTheme,
  PALETTE_SWATCHES,
  PALETTES,
  type Palette,
  setPalette,
  setTheme,
  THEMES,
  usePalette,
  useTheme,
} from "@/lib/theme/theme"

export const THEME_ICONS = { light: SunIcon, dark: MoonIcon, system: DesktopIcon } as const

/** Round color preview; decorative, the label next to it names the palette. */
export function PaletteSwatch({ palette }: { palette: Palette }) {
  return (
    <span
      aria-hidden="true"
      className="inline-block size-4 shrink-0 rounded-full border border-border"
      style={{ backgroundColor: PALETTE_SWATCHES[palette] }}
    />
  )
}

/** Top bar menu: Light/Dark/System, then the palette. The trigger shows the theme choice. */
export function ThemeMenu() {
  const { t } = useTranslation()
  const theme = useTheme()
  const palette = usePalette()
  const Current = THEME_ICONS[theme]
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={t("theme.label")}>
          <Current />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>{t("theme.label")}</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={theme} onValueChange={(v) => isTheme(v) && setTheme(v)}>
          {THEMES.map((option) => {
            const Icon = THEME_ICONS[option]
            return (
              <DropdownMenuRadioItem key={option} value={option}>
                <Icon />
                {t(`theme.${option}`)}
              </DropdownMenuRadioItem>
            )
          })}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuLabel>{t("theme.palette")}</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={palette}
          onValueChange={(v) => isPalette(v) && setPalette(v)}
        >
          {PALETTES.map((option) => (
            <DropdownMenuRadioItem key={option} value={option}>
              <PaletteSwatch palette={option} />
              {t(`theme.palettes.${option}`)}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
