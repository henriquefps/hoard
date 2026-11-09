import { ArchiveIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"

export function AppLogo() {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-2">
      <ArchiveIcon weight="duotone" className="size-5 text-primary" aria-hidden />
      <span className="text-[0.9375rem] font-semibold">{t("app.name")}</span>
    </div>
  )
}
