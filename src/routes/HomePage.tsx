import {
  ArrowsDownUpIcon,
  CheckIcon,
  DotsThreeVerticalIcon,
  FolderSimpleStarIcon,
  HardDrivesIcon,
  PencilSimpleIcon,
  PlusIcon,
  ShieldCheckIcon,
  TrashIcon,
  type Icon,
} from "@phosphor-icons/react"
import { Link, useNavigate } from "@tanstack/react-router"
import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { MachineList } from "@/components/MachineList"
import { NameDialog } from "@/components/NameDialog"
import { Page } from "@/components/page/Page"
import { moveItem, SortableList } from "@/components/SortableList"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import {
  listFavorites,
  listMachines,
  removeFavorite,
  renameFavorite,
  reorderFavorites,
} from "@/db/queries"
import type { FavoriteRecord, MachineWithAddresses } from "@/db/types"
import { parseLocationId } from "@/features/locations/ids"
import { PhoneStorage } from "@/features/locations/PhoneStorage"
import { findVolume, useVolumes } from "@/features/locations/volumesContext"
import { Section } from "@/features/machines/actions"
import { useSetting } from "@/features/settings/settings"
import { useMachineRoute } from "@/features/route/RouteProvider"
import { RouteStatus } from "@/features/route/RouteStatus"

/**
 * Home (ADR 0005): this phone (internal storage, USB drives, SD cards), then favorites, then SMB
 * machines. The first launch shows the all files access screen once.
 */
export function HomePage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const machines = useLiveQuery(listMachines)
  const favorites = useLiveQuery(listFavorites)
  const { access } = useVolumes()
  const [introSeen] = useSetting("storageIntroSeen")

  useEffect(() => {
    if (access && !access.granted && introSeen === false) {
      void navigate({ to: "/storage-access", replace: true })
    }
  }, [access, introSeen, navigate])

  return (
    <Page title={t("nav.home")}>
      <PhoneStorage />
      {(machines?.length !== 0 || (favorites?.length ?? 0) > 0) && (
        <Favorites favorites={favorites} machines={machines} />
      )}
      <Section
        title={t("home.machines")}
        action={
          <Button variant="outline" asChild>
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
          <Onboarding />
        ) : (
          <div className="-mx-4 md:-mx-6">
            <MachineList machines={machines} showHeader={false} />
          </div>
        )}
      </Section>
    </Page>
  )
}

/** No machine yet: what SMB machines do in three steps, and one button. */
function Onboarding() {
  const { t } = useTranslation()
  const steps: { icon: Icon; title: string; body: string }[] = [
    { icon: HardDrivesIcon, title: t("onboarding.step1Title"), body: t("onboarding.step1Body") },
    { icon: ShieldCheckIcon, title: t("onboarding.step2Title"), body: t("onboarding.step2Body") },
    { icon: ArrowsDownUpIcon, title: t("onboarding.step3Title"), body: t("onboarding.step3Body") },
  ]
  return (
    <div className="mx-auto flex w-full max-w-xl flex-col items-center gap-8 rounded-xl border px-4 py-6 text-center md:py-10">
      <span className="inline-flex size-16 items-center justify-center rounded-2xl bg-primary/10 text-primary [&_svg]:size-8">
        <HardDrivesIcon weight="duotone" />
      </span>
      <div className="flex flex-col gap-2">
        <h2 className="text-2xl font-semibold text-balance">{t("onboarding.title")}</h2>
        <p className="text-[0.9375rem] text-pretty text-muted-foreground">{t("onboarding.body")}</p>
      </div>
      <ol className="flex w-full flex-col gap-3 text-left">
        {steps.map((step, index) => (
          <li key={index} className="flex items-start gap-3 rounded-xl border px-4 py-3">
            <span className="mt-0.5 inline-flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-primary [&_svg]:size-5">
              <step.icon weight="duotone" />
            </span>
            <span className="flex flex-col gap-0.5">
              <span className="text-[0.9375rem] font-medium">{step.title}</span>
              <span className="text-[0.8125rem] text-muted-foreground">{step.body}</span>
            </span>
          </li>
        ))}
      </ol>
      <Button asChild className="h-11 w-full sm:w-auto sm:px-6">
        <Link to="/machines/new">
          <PlusIcon data-icon="inline-start" />
          {t("onboarding.cta")}
        </Link>
      </Button>
    </div>
  )
}

function Favorites({
  favorites,
  machines,
}: {
  favorites: FavoriteRecord[] | undefined
  machines: MachineWithAddresses[] | undefined
}) {
  const { t } = useTranslation()
  const db = useDb()
  const [reordering, setReordering] = useState(false)
  const [renaming, setRenaming] = useState<FavoriteRecord | null>(null)
  const machine = (id: string) => machines?.find((m) => m.id === id)
  const reorder = (next: FavoriteRecord[]) =>
    void reorderFavorites(
      db,
      next.map((f) => f.id)
    )

  return (
    <Section
      title={t("home.favorites")}
      action={
        favorites && favorites.length > 1 ? (
          <Button
            variant="ghost"
            onClick={() => setReordering((r) => !r)}
            aria-pressed={reordering}
          >
            {reordering ? (
              <CheckIcon data-icon="inline-start" />
            ) : (
              <ArrowsDownUpIcon data-icon="inline-start" />
            )}
            {reordering ? t("common.done") : t("favorites.reorder")}
          </Button>
        ) : undefined
      }
    >
      {favorites === undefined ? (
        <Skeleton className="h-16 w-full" />
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
      ) : reordering ? (
        <SortableList
          items={favorites}
          getKey={(f) => f.id}
          getLabel={(f) => f.label}
          onReorder={reorder}
          className="divide-y overflow-hidden rounded-xl border"
          renderItem={(favorite, { handle, moveButtons }) => (
            <div className="flex min-h-14 items-center gap-2 px-2 py-1.5">
              {handle}
              <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium">
                {favorite.label}
              </span>
              {moveButtons}
            </div>
          )}
        />
      ) : (
        <div className="@container">
          <ul className="grid grid-cols-1 gap-2 @2xl:grid-cols-2 @5xl:grid-cols-3">
            {favorites.map((favorite, index) => (
              <FavoriteCard
                key={favorite.id}
                favorite={favorite}
                machine={favorite.machine_id ? machine(favorite.machine_id) : undefined}
                onRename={() => setRenaming(favorite)}
                onMove={(delta) => reorder(moveItem(favorites, index, index + delta))}
                first={index === 0}
                last={index === favorites.length - 1}
              />
            ))}
          </ul>
        </div>
      )}
      <NameDialog
        open={renaming !== null}
        onOpenChange={(open) => !open && setRenaming(null)}
        title={t("favorites.rename")}
        label={t("favorites.label")}
        initialValue={renaming?.label ?? ""}
        submitLabel={t("common.save")}
        validate={(value) => (value.trim() ? null : t("favorites.labelRequired"))}
        onSubmit={async (label) => {
          if (renaming) await renameFavorite(db, renaming.id, label)
        }}
      />
    </Section>
  )
}

function FavoriteCard({
  favorite,
  machine,
  onRename,
  onMove,
  first,
  last,
}: {
  favorite: FavoriteRecord
  machine: MachineWithAddresses | undefined
  onRename: () => void
  onMove: (delta: number) => void
  first: boolean
  last: boolean
}) {
  const { t } = useTranslation()
  const db = useDb()
  const route = useMachineRoute(machine)
  const { volumes } = useVolumes()
  const parsed = parseLocationId(favorite.location_id)
  const isLocal = parsed.kind !== "smb"
  const volume = isLocal
    ? findVolume(volumes, parsed.kind === "volume" ? parsed.uuid : null)
    : undefined
  const place = isLocal
    ? parsed.kind === "device"
      ? t("locations.internal")
      : (volume?.label ?? t("locations.drive"))
    : machine?.name
  const location = [place, favorite.share, favorite.path].filter(Boolean).join(" / ")
  return (
    <li className="relative flex min-h-16 items-center gap-3 rounded-xl border py-2 pr-1 pl-3 transition-colors hover:bg-muted/50 has-[a:focus-visible]:ring-3 has-[a:focus-visible]:ring-ring/50">
      <span
        aria-hidden
        data-machine-color={machine?.color ?? ""}
        className={
          isLocal
            ? "inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5"
            : "inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-(--machine)/12 text-(--machine) [&_svg]:size-5"
        }
      >
        <FolderSimpleStarIcon weight="duotone" />
      </span>
      <Link
        to="/browse/$locationId"
        params={{ locationId: favorite.location_id }}
        search={{ share: favorite.share || undefined, path: favorite.path || undefined }}
        className="flex min-w-0 flex-1 flex-col gap-0.5 outline-none after:absolute after:inset-0"
      >
        <span className="truncate text-[0.9375rem] font-medium" title={favorite.label}>
          {favorite.label}
        </span>
        <span className="truncate text-[0.8125rem] text-muted-foreground" title={location}>
          {location}
        </span>
        {isLocal ? (
          parsed.kind === "volume" &&
          !volume && (
            <span className="truncate text-[0.8125rem] text-muted-foreground">
              {t("locations.notConnected")}
            </span>
          )
        ) : (
          <RouteStatus state={route} hideHost className="max-w-full" />
        )}
      </Link>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="relative z-10"
            aria-label={t("favorites.actionsFor", { name: favorite.label })}
          >
            <DotsThreeVerticalIcon weight="bold" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-44">
          <DropdownMenuItem onSelect={onRename}>
            <PencilSimpleIcon />
            {t("favorites.rename")}
          </DropdownMenuItem>
          <DropdownMenuItem disabled={first} onSelect={() => onMove(-1)}>
            {t("common.moveUpShort")}
          </DropdownMenuItem>
          <DropdownMenuItem disabled={last} onSelect={() => onMove(1)}>
            {t("common.moveDownShort")}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            onSelect={async () => {
              await removeFavorite(db, favorite.id)
              toast(t("favorites.removed", { name: favorite.label }))
            }}
          >
            <TrashIcon />
            {t("favorites.remove")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
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
