import { NotePencilIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { editMachineTrail, newMachineTrail } from "@/components/page/breadcrumbs"
import { Page } from "@/components/page/Page"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { useLiveQuery } from "@/db/DbProvider"
import { getMachine } from "@/db/queries"

/** Placeholder for the machine form (create when `machineId` is absent, edit otherwise). */
export function MachineFormPage({ machineId }: { machineId?: string }) {
  const { t } = useTranslation()
  const machine = useLiveQuery(
    (db) => (machineId ? getMachine(db, machineId) : Promise.resolve(null)),
    [machineId]
  )
  const trail = machineId
    ? editMachineTrail(t, {
        id: machineId,
        name: machine?.name ?? (machine === null ? "?" : undefined),
      })
    : newMachineTrail(t)
  return (
    <Page title={machineId ? t("machines.edit") : t("machines.new")} breadcrumbs={trail}>
      <Empty className="border">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <NotePencilIcon />
          </EmptyMedia>
          <EmptyTitle>
            {machineId && machine === null
              ? t("machines.notFound")
              : t("machines.formPlaceholderTitle")}
          </EmptyTitle>
          <EmptyDescription>{t("machines.formPlaceholderBody")}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    </Page>
  )
}
