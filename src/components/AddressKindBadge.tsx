import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import type { AddressKind } from "@/lib/addressKind"

export function AddressKindBadge({ kind }: { kind: AddressKind }) {
  const { t } = useTranslation()
  return (
    <Badge variant="outline" className="shrink-0">
      {t(`addressKind.${kind}`)}
    </Badge>
  )
}
