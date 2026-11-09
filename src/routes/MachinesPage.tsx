import { PlusIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { MachineList } from "@/components/MachineList"
import { Page } from "@/components/page/Page"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { useLiveQuery } from "@/db/DbProvider"
import { listMachines } from "@/db/queries"
import { MachinesEmpty } from "./HomePage"

export function MachinesPage() {
  const { t } = useTranslation()
  const machines = useLiveQuery(listMachines)
  return (
    <Page
      title={t("machines.title")}
      description={t("machines.description")}
      actions={
        <Button asChild>
          <Link to="/machines/new">
            <PlusIcon data-icon="inline-start" />
            {t("machines.add")}
          </Link>
        </Button>
      }
    >
      {machines === undefined ? (
        <Skeleton className="h-16 w-full" />
      ) : machines.length === 0 ? (
        <MachinesEmpty />
      ) : (
        <div className="-mx-4 -my-4 md:-mx-6 md:-my-6">
          <MachineList machines={machines} />
        </div>
      )}
    </Page>
  )
}
