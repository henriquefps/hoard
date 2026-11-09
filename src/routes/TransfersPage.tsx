import { ArrowsDownUpIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { Page } from "@/components/page/Page"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"

export function TransfersPage() {
  const { t } = useTranslation()
  return (
    <Page title={t("transfers.title")} description={t("transfers.description")}>
      <Empty className="border">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <ArrowsDownUpIcon />
          </EmptyMedia>
          <EmptyTitle>{t("transfers.emptyTitle")}</EmptyTitle>
          <EmptyDescription>{t("transfers.emptyBody")}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    </Page>
  )
}
