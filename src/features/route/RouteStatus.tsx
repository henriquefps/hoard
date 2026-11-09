import { useTranslation } from "react-i18next"
import { Spinner } from "@/components/ui/spinner"
import { cn } from "@/lib/utils"
import type { RouteState } from "./engine"

/** Status dot color per hfps status rules: ok = primary, attention = warning, failed = destructive. */
function tone(state: RouteState) {
  if (state.status === "online") return "bg-primary"
  if (state.status === "error") return "bg-destructive"
  if (state.status === "offline") return "bg-muted-foreground/60"
  return "bg-muted-foreground/40"
}

/** The short reason a route is not usable, e.g. "Wrong password". */
export function useRouteProblem() {
  const { t } = useTranslation()
  return (state: RouteState) =>
    state.errorCode ? t(`route.problem.${state.errorCode}`, t("route.problem.UNKNOWN")) : ""
}

/**
 * The route in use, always visible: "Local · 192.168.1.155 · 4 ms", "Tailscale · 100.101.12.34 ·
 * 70 ms", "Checking…", "Offline", "Wrong password". One line, 13 px, middle part truncates.
 */
export function RouteStatus({
  state,
  className,
  hideHost = false,
}: {
  state: RouteState
  className?: string
  /** Only the kind and latency (tight spots such as the sidebar). */
  hideHost?: boolean
}) {
  const { t } = useTranslation()
  const problem = useRouteProblem()
  const route = state.route
  const checking = state.status === "checking" || state.status === "idle"
  let label: string
  let kind: string | null = null
  if (route && (state.status === "online" || (checking && route))) {
    kind = t(`addressKind.${route.kind}`)
    label = [
      hideHost ? null : route.host,
      route.latencyMs !== undefined ? t("route.latency", { ms: route.latencyMs }) : null,
    ]
      .filter(Boolean)
      .join(" · ")
  } else if (checking) {
    label = t("route.checking")
  } else {
    label = problem(state)
  }
  const title =
    state.status === "online" && route
      ? t("route.inUse", { kind: t(`addressKind.${route.kind}`), host: route.host })
      : label
  return (
    <span
      className={cn(
        "inline-flex min-w-0 items-center gap-1.5 text-[0.8125rem] text-muted-foreground",
        state.status === "error" && "text-destructive",
        className
      )}
      title={title}
    >
      {checking ? (
        <Spinner className="size-3 shrink-0" aria-hidden />
      ) : (
        <span aria-hidden className={cn("size-2 shrink-0 rounded-full", tone(state))} />
      )}
      {kind && <span className="shrink-0">{kind}</span>}
      {kind && label && <span aria-hidden>·</span>}
      <span className={cn("min-w-0 truncate", kind && "font-mono")}>{label}</span>
      <span className="sr-only">{checking ? t("route.checking") : ""}</span>
    </span>
  )
}
