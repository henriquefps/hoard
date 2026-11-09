import {
  CheckCircleIcon,
  MinusCircleIcon,
  WarningCircleIcon,
  XCircleIcon,
} from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { cn } from "@/lib/utils"
import type { AddressTestResult } from "./address"

/** One address's test outcome, e.g. "Reachable · 8 ms · signed in to CACHYOS-FPS". */
export function TestResultLine({
  result,
  className,
}: {
  result: AddressTestResult
  className?: string
}) {
  const { t } = useTranslation()
  const { probe, connect } = result
  let tone: "ok" | "warn" | "bad" | "off" = "off"
  let text: string
  if (!probe.reachable) {
    text = t(`route.problem.${probe.reason ?? "UNREACHABLE"}`)
  } else if (connect?.ok) {
    tone = "ok"
    text = [
      t("route.reachable", { ms: probe.latencyMs ?? 0 }),
      connect.serverName
        ? t("machines.test.signedInTo", { server: connect.serverName })
        : t("machines.test.signedIn"),
      connect.guid === "match" ? t("machines.test.sameServer") : null,
    ]
      .filter(Boolean)
      .join(" · ")
  } else if (connect?.code === "GUID_MISMATCH") {
    tone = "bad"
    text = t("machines.test.otherServer")
  } else {
    tone = connect?.code === "AUTH_FAILED" ? "warn" : "bad"
    text = [
      t("route.reachable", { ms: probe.latencyMs ?? 0 }),
      t(`route.problem.${connect?.code ?? "UNKNOWN"}`, t("route.problem.UNKNOWN")),
    ].join(" · ")
  }
  const Icon =
    tone === "ok"
      ? CheckCircleIcon
      : tone === "warn"
        ? WarningCircleIcon
        : tone === "bad"
          ? XCircleIcon
          : MinusCircleIcon
  return (
    <p
      className={cn(
        "flex items-start gap-1.5 text-[0.8125rem]",
        tone === "ok" && "text-primary",
        tone === "warn" && "text-warning",
        tone === "bad" && "text-destructive",
        tone === "off" && "text-muted-foreground",
        className
      )}
    >
      <Icon weight="fill" aria-hidden className="mt-0.5 size-4 shrink-0" />
      <span className="min-w-0 [overflow-wrap:anywhere]">{text}</span>
    </p>
  )
}
