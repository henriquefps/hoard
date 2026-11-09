import { Link } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { Page } from "@/components/page/Page"
import { Button } from "@/components/ui/button"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty"

export function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <Page title={t("notFound.title")}>
      <Empty>
        <EmptyHeader>
          <EmptyTitle>{t("notFound.title")}</EmptyTitle>
          <EmptyDescription>{t("notFound.body")}</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button asChild>
            <Link to="/">{t("notFound.home")}</Link>
          </Button>
        </EmptyContent>
      </Empty>
    </Page>
  )
}
