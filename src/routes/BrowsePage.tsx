import { FolderOpenIcon, PencilSimpleIcon } from "@phosphor-icons/react"
import { Link, useParams, useSearch } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { AddressKindBadge } from "@/components/AddressKindBadge"
import { browseTrail } from "@/components/page/breadcrumbs"
import { Page } from "@/components/page/Page"
import { Button } from "@/components/ui/button"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { useLiveQuery } from "@/db/DbProvider"
import { getMachine } from "@/db/queries"

/** Placeholder: `/browse/$machineId?share=&path=`. The trail is already the real path. */
export function BrowsePage() {
  const { t } = useTranslation()
  const { machineId } = useParams({ from: "/browse/$machineId" })
  const { share, path } = useSearch({ from: "/browse/$machineId" })
  const machine = useLiveQuery((db) => getMachine(db, machineId), [machineId])
  const segments = (path ?? "").split("/").filter(Boolean)
  const title = segments.at(-1) ?? share ?? machine?.name ?? ""
  return (
    <Page
      title={machine === null ? t("machines.notFound") : title}
      breadcrumbs={browseTrail(
        t,
        { id: machineId, name: machine?.name ?? (machine === null ? "?" : undefined) },
        share,
        path
      )}
      actions={
        machine ? (
          <Button variant="outline" asChild>
            <Link to="/machines/$machineId/edit" params={{ machineId }}>
              <PencilSimpleIcon data-icon="inline-start" />
              {t("common.edit")}
            </Link>
          </Button>
        ) : undefined
      }
    >
      {machine && (
        <ul className="flex flex-wrap gap-2">
          {machine.addresses.map((address) => (
            <li key={address.id} className="flex items-center gap-2 rounded-md border px-3 py-1.5">
              <span className="font-mono text-[0.8125rem]">
                {address.host}
                {address.port !== 445 ? `:${address.port}` : ""}
              </span>
              <AddressKindBadge kind={address.kind} />
            </li>
          ))}
        </ul>
      )}
      <Empty className="border">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <FolderOpenIcon />
          </EmptyMedia>
          <EmptyTitle>{t("browse.placeholderTitle")}</EmptyTitle>
          <EmptyDescription>{t("browse.placeholderBody")}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    </Page>
  )
}
