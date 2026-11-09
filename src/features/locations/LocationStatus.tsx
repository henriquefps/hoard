import { useTranslation } from "react-i18next"
import { cn } from "@/lib/utils"
import { volumeSummary } from "./summary"
import type { BrowseLocation } from "./useBrowseLocation"

/** The meta line of a local location: free space, filesystem, or "Not connected". */
export function LocationStatus({
  location,
  className,
}: {
  location: BrowseLocation | undefined
  className?: string
}) {
  const { t, i18n } = useTranslation()
  if (!location || (!location.volume && !location.volumesKnown)) return null
  const text = location.volume
    ? volumeSummary(location.volume, t, i18n.language)
    : t("locations.notConnected")
  return (
    <span className={cn("truncate text-[0.8125rem] text-muted-foreground tabular-nums", className)}>
      {text}
    </span>
  )
}
