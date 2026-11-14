import { useTranslation } from "react-i18next"
import { isEdited } from "@/lib/edits"
import { formatDateTime } from "@/lib/format"
import type { Edit, Row } from "@/lib/types"
import { wallClock } from "@/lib/types"

/** The accessible name of a grid cell: file (and the edit of a stack cell), date, decision, stars, edited. */
export function useRowLabel() {
  const { t, i18n } = useTranslation()
  return (row: Row, edit?: Edit) =>
    [
      edit ? `${row.n} (${edit.l || t("edits.edited")})` : row.n,
      formatDateTime(wallClock(row), i18n.language),
      row.x === "pick" ? t("cull.picked") : row.x === "reject" ? t("cull.rejected") : null,
      row.r ? t("cull.stars", { count: row.r }) : null,
      isEdited(row) ? t("badges.edited") : null,
    ]
      .filter(Boolean)
      .join(", ")
}

