import {
  ArrowsClockwiseIcon,
  FolderLockIcon,
  HouseIcon,
  LockKeyIcon,
  PlugsIcon,
} from "@phosphor-icons/react"
import { Link, type LinkOptions } from "@tanstack/react-router"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { SmbErrorState } from "@/components/SmbErrorState"
import { Button } from "@/components/ui/button"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty"
import type { AddressAttempt } from "@/features/route/engine"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import { type HoardSmbErrorCode, smbErrorCode } from "@/plugins/smb/definitions"
import type { BrowseLocation } from "./useBrowseLocation"
import { useVolumes } from "./volumesContext"

/**
 * The error area of a browse or viewer page in any location. SMB codes keep their own state
 * (`SmbErrorState`); local storage adds the next step for its own codes (ADR 0005):
 * STORAGE_PERMISSION → allow all files access (or the system picker for one drive), UNAVAILABLE →
 * the drive was unplugged or ejected, ACCESS_DENIED on Android/data → Android keeps it private.
 */
export function LocationErrorState({
  code,
  location,
  attempts,
  restricted = false,
  upLink,
  onRetry,
  retrying = false,
  className,
}: {
  code: HoardSmbErrorCode
  location: BrowseLocation
  attempts?: AddressAttempt[]
  restricted?: boolean
  /** Where "Go up" leads (restricted folders). */
  upLink?: LinkOptions
  onRetry?: () => void
  retrying?: boolean
  className?: string
}) {
  const { t } = useTranslation()
  const { access, requestAccess } = useVolumes()
  const [busy, setBusy] = useState(false)

  if (location.kind === "smb") {
    return (
      <SmbErrorState
        code={code}
        machineId={location.machine?.id}
        attempts={attempts}
        onRetry={onRetry}
        retrying={retrying}
        className={className}
      />
    )
  }

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await action()
      onRetry?.()
    } catch (error) {
      if (smbErrorCode(error) !== "CANCELLED") {
        toast.error(t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")))
      }
    } finally {
      setBusy(false)
    }
  }

  const uuid = location.kind === "volume" ? location.volume?.uuid : undefined
  const state = (() => {
    if (code === "STORAGE_PERMISSION") {
      return {
        icon: LockKeyIcon,
        title: t("locations.permission.title"),
        body: t(
          location.kind === "device"
            ? "locations.permission.body"
            : "locations.permission.volumeBody"
        ),
        actions: (
          <>
            <Button disabled={busy} onClick={() => void run(requestAccess)}>
              {t(
                access?.mechanism === "runtime"
                  ? "locations.permission.allowRuntime"
                  : "locations.permission.allow"
              )}
            </Button>
            {uuid && (
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => void run(() => HoardSmb.grantVolumeAccess({ uuid }))}
              >
                {t("locations.permission.picker")}
              </Button>
            )}
          </>
        ),
      }
    }
    if (code === "UNAVAILABLE") {
      return {
        icon: PlugsIcon,
        title: t("locations.unavailable.title", { name: location.name ?? "" }),
        body: t("locations.unavailable.body"),
        actions: (
          <>
            <Button asChild>
              <Link to="/">
                <HouseIcon data-icon="inline-start" />
                {t("locations.backHome")}
              </Link>
            </Button>
            {onRetry && (
              <Button variant="outline" onClick={onRetry} disabled={retrying}>
                <ArrowsClockwiseIcon
                  data-icon="inline-start"
                  className={cn(retrying && "animate-spin")}
                />
                {t("common.retry")}
              </Button>
            )}
          </>
        ),
      }
    }
    if (code === "ACCESS_DENIED" && restricted) {
      return {
        icon: FolderLockIcon,
        title: t("locations.restricted.title"),
        body: t("locations.restricted.body"),
        actions: upLink ? (
          <Button asChild variant="outline">
            <Link {...upLink}>{t("locations.goUp")}</Link>
          </Button>
        ) : null,
      }
    }
    return null
  })()

  if (!state) {
    return <SmbErrorState code={code} onRetry={onRetry} retrying={retrying} className={className} />
  }
  const Icon = state.icon
  return (
    <Empty className={cn("border", className)}>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Icon />
        </EmptyMedia>
        <EmptyTitle>{state.title}</EmptyTitle>
        <EmptyDescription>{state.body}</EmptyDescription>
      </EmptyHeader>
      {state.actions && (
        <EmptyContent className="flex-row flex-wrap justify-center gap-2">
          {state.actions}
        </EmptyContent>
      )}
    </Empty>
  )
}
