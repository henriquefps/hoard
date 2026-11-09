import { DotsThreeVerticalIcon, EjectIcon, GearSixIcon, LockKeyIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Progress } from "@/components/ui/progress"
import { Skeleton } from "@/components/ui/skeleton"
import { Section } from "@/features/machines/actions"
import { HoardSmb } from "@/plugins/smb"
import type { StorageVolumeInfo } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { locationIdOfVolume, usedFraction } from "./ids"
import { volumeSummary } from "./summary"
import { VolumeIcon } from "./VolumeIcon"
import { useVolumes } from "./volumesContext"

/**
 * Home: "This phone": the internal storage with its used/free bar, then every USB drive and SD
 * card attached right now (they appear and disappear live), and a card asking for all files access
 * while it is off. Tapping a card browses it (ADR 0005).
 */
export function PhoneStorage() {
  const { t } = useTranslation()
  const { volumes, access } = useVolumes()
  const removable = volumes?.filter((v) => !v.primary) ?? []
  return (
    <Section title={t("locations.thisPhone")}>
      {access && !access.granted && <AccessCard />}
      {volumes === undefined ? (
        <Skeleton className="h-20 w-full" />
      ) : (
        <div className="@container">
          <ul className="grid grid-cols-1 gap-2 @2xl:grid-cols-2 @5xl:grid-cols-3">
            {volumes.map((volume) => (
              <VolumeCard key={volume.uuid ?? "primary"} volume={volume} />
            ))}
          </ul>
          {removable.length === 0 && (
            <p className="mt-2 text-[0.8125rem] text-muted-foreground">
              {t("locations.connectHint")}
            </p>
          )}
        </div>
      )}
    </Section>
  )
}

function AccessCard() {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col gap-3 rounded-xl border px-4 py-3 sm:flex-row sm:items-center">
      <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5">
        <LockKeyIcon weight="duotone" />
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-[0.9375rem] font-medium">{t("locations.accessCardTitle")}</span>
        <span className="text-[0.8125rem] text-muted-foreground">
          {t("locations.accessCardBody")}
        </span>
      </span>
      <Button asChild className="shrink-0">
        <Link to="/storage-access">{t("locations.accessCardAction")}</Link>
      </Button>
    </div>
  )
}

function VolumeCard({ volume }: { volume: StorageVolumeInfo }) {
  const { t, i18n } = useTranslation()
  const used = usedFraction(volume)
  const label = volume.primary ? t("locations.internal") : volume.label
  const summary = volumeSummary(volume, t, i18n.language)
  const settings = () =>
    void HoardSmb.openStorageSettings(volume.uuid ? { uuid: volume.uuid } : undefined).catch(
      (error: unknown) =>
        toast.error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")))
    )
  return (
    <li className="relative flex min-h-16 items-center gap-3 rounded-xl border py-2 pr-1 pl-3 transition-colors hover:bg-muted/50 has-[a:focus-visible]:ring-3 has-[a:focus-visible]:ring-ring/50">
      <span
        aria-hidden
        className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5"
      >
        <VolumeIcon kind={volume.kind} weight="duotone" />
      </span>
      <Link
        to="/browse/$locationId"
        params={{ locationId: locationIdOfVolume(volume) }}
        className="flex min-w-0 flex-1 flex-col gap-1 outline-none after:absolute after:inset-0"
      >
        <span className="truncate text-[0.9375rem] font-medium" title={label}>
          {label}
        </span>
        <span
          className="truncate text-[0.8125rem] text-muted-foreground tabular-nums"
          title={summary}
        >
          {summary}
        </span>
        {used !== undefined && (
          <Progress
            value={used * 100}
            tone={used > 0.95 ? "destructive" : used > 0.85 ? "warning" : "default"}
            aria-label={t("locations.usedPercent", { percent: Math.round(used * 100) })}
            className="mr-2 max-w-64"
          />
        )}
      </Link>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="relative z-10"
            aria-label={t("locations.actionsFor", { name: label })}
          >
            <DotsThreeVerticalIcon weight="bold" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-52">
          {volume.removable ? (
            <DropdownMenuItem onSelect={settings}>
              <EjectIcon />
              {t("locations.ejectInSettings")}
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem onSelect={settings}>
              <GearSixIcon />
              {t("locations.storageSettings")}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  )
}
