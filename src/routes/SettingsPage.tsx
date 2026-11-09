import { Capacitor } from "@capacitor/core"
import { useTranslation } from "react-i18next"
import { PaletteSwatch, THEME_ICONS } from "@/components/ThemeMenu"
import { Page } from "@/components/page/Page"
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { isLanguage, LANGUAGES, setLanguage } from "@/i18n"
import {
  isPalette,
  isTheme,
  PALETTES,
  setPalette,
  setTheme,
  THEMES,
  usePalette,
  useTheme,
} from "@/lib/theme/theme"

export function SettingsPage() {
  const { t, i18n } = useTranslation()
  const theme = useTheme()
  const palette = usePalette()
  return (
    <Page title={t("settings.title")}>
      <FieldSet className="max-w-xl">
        <FieldLegend>{t("settings.appearance")}</FieldLegend>
        <FieldGroup>
          <Field>
            <FieldLabel id="theme-label">{t("settings.theme")}</FieldLabel>
            <ToggleGroup
              type="single"
              variant="outline"
              aria-labelledby="theme-label"
              value={theme}
              onValueChange={(value) => isTheme(value) && setTheme(value)}
              className="w-full *:flex-1 sm:w-auto"
            >
              {THEMES.map((option) => {
                const Icon = THEME_ICONS[option]
                return (
                  <ToggleGroupItem key={option} value={option}>
                    <Icon />
                    {t(`theme.${option}`)}
                  </ToggleGroupItem>
                )
              })}
            </ToggleGroup>
          </Field>
          <Field>
            <FieldLabel htmlFor="palette">{t("settings.palette")}</FieldLabel>
            <Select
              value={palette}
              onValueChange={(value) => isPalette(value) && setPalette(value)}
            >
              <SelectTrigger id="palette" className="w-full sm:w-64">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PALETTES.map((option) => (
                  <SelectItem key={option} value={option}>
                    <PaletteSwatch palette={option} />
                    {t(`theme.palettes.${option}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel htmlFor="language">{t("settings.language")}</FieldLabel>
            <Select
              value={i18n.language}
              onValueChange={(value) => isLanguage(value) && setLanguage(value)}
            >
              <SelectTrigger id="language" className="w-full sm:w-64">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {LANGUAGES.map((language) => (
                  <SelectItem key={language} value={language}>
                    {t(`language.${language}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        </FieldGroup>
      </FieldSet>
      <section className="flex max-w-xl flex-col gap-2">
        <h2 className="eyebrow text-muted-foreground">{t("settings.about")}</h2>
        <p className="text-[0.9375rem]">{t("settings.aboutBody")}</p>
        {!Capacitor.isNativePlatform() && (
          <p className="text-[0.9375rem] text-muted-foreground">{t("settings.demoData")}</p>
        )}
      </section>
    </Page>
  )
}
