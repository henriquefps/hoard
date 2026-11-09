import { useTranslation } from "react-i18next"
import { Page } from "@/components/page/Page"
import { TransfersList } from "@/features/transfers/TransfersViews"

/** `/transfers`: the whole queue and history. */
export function TransfersPage() {
  const { t } = useTranslation()
  return (
    <Page title={t("transfers.title")} description={t("transfers.description")}>
      <div className="-mx-4 -mb-4 md:-mx-6 md:-mb-6">
        <TransfersList />
      </div>
    </Page>
  )
}
