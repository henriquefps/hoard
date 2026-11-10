import { EyeClosedIcon, MoonIcon, ScanIcon, SunIcon } from "@phosphor-icons/react"
import type { ComponentType } from "react"
import { useTranslation } from "react-i18next"
import type { ShotFlag, SuggestionReason } from "./assist"

export const FLAG_ICONS: Record<ShotFlag, ComponentType<{ weight?: "bold" | "fill" }>> = {
  blurry: ScanIcon,
  eyesClosed: EyeClosedIcon,
  overexposed: SunIcon,
  underexposed: MoonIcon,
}

/** Why a suggestion, in words ("Blurrier than DSC07705 · Eyes closed"). */
export function useReasonText() {
  const { t } = useTranslation()
  return (reasons: readonly SuggestionReason[]) =>
    reasons
      .map((r) => {
        switch (r.kind) {
          case "flag":
            return t(`cull.assist.flag.${r.flag}`)
          case "blurrierThan":
            return t("cull.assist.reason.blurrierThan", { name: r.than })
          case "notBest":
            return t("cull.assist.reason.notBest", { name: r.than })
          default:
            return t(`cull.assist.reason.${r.kind}`)
        }
      })
      .join(" · ")
}
