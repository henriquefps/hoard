import { useTranslation } from "react-i18next"
import { formatDateTime } from "@/lib/format"
import type { Row } from "@/lib/types"
import { wallClock } from "@/lib/types"

/** The accessible name of a grid cell: file, date, decision, stars, edited. */
export function useRowLabel() {
  const { t, i18n } = useTranslation()
  return (row: Row) =>
    [
      row.n,
      formatDateTime(wallClock(row), i18n.language),
      row.x === "pick" ? t("cull.picked") : row.x === "reject" ? t("cull.rejected") : null,
      row.r ? t("cull.stars", { count: row.r }) : null,
      row.e ? t("badges.edited") : null,
    ]
      .filter(Boolean)
      .join(", ")
}

