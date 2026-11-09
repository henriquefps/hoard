import {
  ArrowsClockwiseIcon,
  CloudSlashIcon,
  FingerprintIcon,
  FolderSimpleIcon,
  KeyIcon,
  LockKeyIcon,
  type Icon,
  TimerIcon,
  WarningIcon,
} from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useTranslation } from "react-i18next"
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
import type { HoardSmbErrorCode } from "@/plugins/smb/definitions"
import { cn } from "@/lib/utils"

const ICONS: Partial<Record<HoardSmbErrorCode, Icon>> = {
  UNREACHABLE: CloudSlashIcon,
  TIMEOUT: TimerIcon,
  AUTH_FAILED: KeyIcon,
  GUID_MISMATCH: FingerprintIcon,
  ACCESS_DENIED: LockKeyIcon,
  NOT_FOUND: FolderSimpleIcon,
}

/**
 * A full-area error for a browse or connect failure, with the next step per code: AUTH_FAILED →
 * edit the credentials, GUID_MISMATCH → explain and offer re-pin in the machine form,
 * UNREACHABLE/TIMEOUT → retry and list every route that was tried.
 */
export function SmbErrorState({
  code,
  machineId,
  attempts,
  onRetry,
  retrying = false,
  className,
}: {
  code: HoardSmbErrorCode
  machineId: string
  attempts?: AddressAttempt[]
  onRetry?: () => void
  retrying?: boolean
  className?: string
}) {
  const { t } = useTranslation()
  const Icon = ICONS[code] ?? WarningIcon
  const editable = code === "AUTH_FAILED" || code === "GUID_MISMATCH"
  const showRoutes =
    (code === "UNREACHABLE" || code === "TIMEOUT" || code === "GUID_MISMATCH") && attempts?.length
  return (
    <Empty className={cn("border", className)}>
      <EmptyHeader>
        <EmptyMedia variant="icon" className={cn(code !== "NOT_FOUND" && "text-destructive")}>
          <Icon />
        </EmptyMedia>
        <EmptyTitle>{t(`errors.${code}.title`, t("errors.UNKNOWN.title"))}</EmptyTitle>
        <EmptyDescription>{t(`errors.${code}.body`, t("errors.UNKNOWN.body"))}</EmptyDescription>
      </EmptyHeader>
      {showRoutes ? <TriedRoutes attempts={attempts!} /> : null}
      <EmptyContent className="flex-row flex-wrap justify-center gap-2">
        {editable && (
          <Button asChild>
            <Link to="/machines/$machineId/edit" params={{ machineId }}>
              {code === "AUTH_FAILED" ? t("errors.editCredentials") : t("errors.reviewMachine")}
            </Link>
          </Button>
        )}
        {onRetry && (
          <Button variant={editable ? "outline" : "default"} onClick={onRetry} disabled={retrying}>
            <ArrowsClockwiseIcon
              data-icon="inline-start"
              className={cn(retrying && "animate-spin")}
            />
            {t("common.retry")}
          </Button>
        )}
      </EmptyContent>
    </Empty>
  )
}

/** One line per address tried: host, kind and what happened. */
export function TriedRoutes({ attempts }: { attempts: AddressAttempt[] }) {
  const { t } = useTranslation()
  return (
    <ul className="flex w-full max-w-sm flex-col divide-y rounded-lg border text-left">
      {attempts.map((attempt) => {
        const outcome =
          attempt.connect && attempt.connect !== "ok"
            ? t(`route.problem.${attempt.connect}`, t("route.problem.UNKNOWN"))
            : attempt.probe
              ? attempt.probe.reachable
                ? t("route.reachable", { ms: attempt.probe.latencyMs ?? 0 })
                : t(`route.problem.${attempt.probe.reason ?? "UNREACHABLE"}`)
              : t("route.notTried")
        return (
          <li key={`${attempt.host}:${attempt.port}`} className="flex flex-col gap-0.5 px-3 py-2">
            <span className="truncate font-mono text-[0.8125rem]" title={attempt.host}>
              {attempt.host}
              {attempt.port !== 445 ? `:${attempt.port}` : ""}
            </span>
            <span className="text-[0.8125rem] text-muted-foreground">
              {t(`addressKind.${attempt.kind}`)} · {outcome}
            </span>
          </li>
        )
      })}
    </ul>
  )
}
