import { FolderSimpleStarIcon, HardDrivesIcon, PlusIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { MachineList } from "@/components/MachineList"
import { Page } from "@/components/page/Page"
import { Button } from "@/components/ui/button"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { useLiveQuery } from "@/db/DbProvider"
import { listFavorites, listMachines } from "@/db/queries"

export function HomePage() {
  const { t } = useTranslation()
  const machines = useLiveQuery(listMachines)
  const favorites = useLiveQuery(listFavorites)
  const machineName = (id: string) => machines?.find((m) => m.id === id)?.name

  return (
    <Page title={t("nav.home")}>
      <section className="@container flex flex-col gap-3">
        <h2 className="eyebrow text-muted-foreground">{t("home.favorites")}</h2>
        {favorites === undefined ? (
          <Skeleton className="h-14 w-full" />
        ) : favorites.length === 0 ? (
          <Empty className="border">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <FolderSimpleStarIcon />
              </EmptyMedia>
              <EmptyTitle>{t("home.favoritesEmptyTitle")}</EmptyTitle>
              <EmptyDescription>{t("home.favoritesEmptyBody")}</EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : (
          <ul className="grid grid-cols-1 gap-2 @md:grid-cols-2 @3xl:grid-cols-3">
            {favorites.map((favorite) => (
              <li key={favorite.id}>
                <Link
                  to="/browse/$machineId"
                  params={{ machineId: favorite.machine_id }}
                  search={{ share: favorite.share, path: favorite.path || undefined }}
                  className="flex min-h-14 items-center gap-3 rounded-xl border px-4 py-2 transition-colors outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  <FolderSimpleStarIcon
                    weight="duotone"
                    aria-hidden
                    className="size-5 shrink-0 text-primary"
                  />
                  <div className="flex min-w-0 flex-col">
                    <span className="truncate text-[0.9375rem] font-medium">{favorite.label}</span>
                    <span className="truncate font-mono text-[0.8125rem] text-muted-foreground">
                      {[machineName(favorite.machine_id), favorite.share, favorite.path]
                        .filter(Boolean)
                        .join(" / ")}
                    </span>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="@container flex flex-col gap-3">
        <div className="flex items-center justify-between gap-2">
          <h2 className="eyebrow text-muted-foreground">{t("home.machines")}</h2>
          <Button variant="outline" asChild>
            <Link to="/machines/new">
              <PlusIcon data-icon="inline-start" />
              {t("machines.add")}
            </Link>
          </Button>
        </div>
        {machines === undefined ? (
          <Skeleton className="h-14 w-full" />
        ) : machines.length === 0 ? (
          <MachinesEmpty />
        ) : (
          <MachineList machines={machines} />
        )}
      </section>
    </Page>
  )
}

export function MachinesEmpty() {
  const { t } = useTranslation()
  return (
    <Empty className="border">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <HardDrivesIcon />
        </EmptyMedia>
        <EmptyTitle>{t("machines.emptyTitle")}</EmptyTitle>
        <EmptyDescription>{t("machines.emptyBody")}</EmptyDescription>
      </EmptyHeader>
    </Empty>
  )
}
