import { App } from "@capacitor/app"
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { useDb } from "@/db/DbProvider"
import { listCullDecisions } from "@/db/queries"
import { revealCullingFor } from "@/features/cull/xmp"
import { useSetting } from "@/features/settings/settings"
import { formatNumber } from "@/lib/format"
import { HoardSmb } from "@/plugins/smb"
import { smbErrorCode } from "@/plugins/smb/definitions"
import { needsConfirm, REVEAL_CONFIRM_ABOVE } from "./handoff"
import { RevealContext, type RevealRequest } from "./revealContext"

/**
 * "Edit in Reveal" (ADR 0010) for the whole app: whether Reveal is installed (asked at start and
 * whenever Hoard comes back to the foreground, so installing or uninstalling it shows or hides the
 * action), and the hand-off with its one confirmation (big batches; SMB photos, which Reveal reads
 * through Hoard while it runs).
 */
export function RevealProvider({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()
  const [available, setAvailable] = useState(false)
  const [pending, setPending] = useState<RevealRequest | null>(null)
  const [dontExplain, setDontExplain] = useState(false)
  const [streamNoteSeen, setStreamNoteSeen] = useSetting("revealStreamNoteSeen")
  const db = useDb()

  const check = useCallback(() => {
    void HoardSmb.revealAvailable().then(
      (r) => setAvailable(r.available),
      () => setAvailable(false)
    )
  }, [])

  useEffect(() => {
    check()
    const handles = [
      App.addListener("resume", check),
      App.addListener("appStateChange", ({ isActive }) => isActive && check()),
    ]
    return () => handles.forEach((handle) => void handle.then((h) => h.remove()).catch(() => {}))
  }, [check])

  const send = useCallback(
    async (request: RevealRequest) => {
      try {
        const decisions =
          request.decisions ??
          (request.cullFolder
            ? new Map(
                (await listCullDecisions(db, request.cullFolder)).map((r) => [
                  r.shot_id,
                  { flag: r.flag, rating: r.rating },
                ])
              )
            : undefined)
        const culling = decisions ? revealCullingFor(request.paths, decisions) : {}
        await HoardSmb.openInReveal({
          location: await request.location.streamLocation(),
          paths: request.paths,
          ...(request.title ? { title: request.title } : {}),
          ...(request.initialPath ? { initialPath: request.initialPath } : {}),
          ...(Object.keys(culling).length ? { culling } : {}),
        })
      } catch (error) {
        const code = smbErrorCode(error)
        if (code === "CANCELLED") return
        if (code === "NO_APP") {
          setAvailable(false)
          toast.error(t("reveal.notInstalled"))
        } else {
          toast.error(t("reveal.failed"), {
            description: t(`errors.${code}.title`, t("errors.UNKNOWN.title")),
          })
        }
      }
    },
    [db, t]
  )

  const edit = useCallback(
    (request: RevealRequest) => {
      if (request.paths.length === 0) return
      const smb = request.location.kind === "smb" && !(streamNoteSeen ?? false)
      if (needsConfirm(request.paths.length, smb)) {
        setDontExplain(false)
        setPending(request)
      } else {
        void send(request)
      }
    },
    [send, streamNoteSeen]
  )

  const value = useMemo(() => ({ available, edit }), [available, edit])
  const count = pending?.paths.length ?? 0
  const isSmb = pending?.location.kind === "smb" && !streamNoteSeen

  return (
    <RevealContext.Provider value={value}>
      {children}
      <AlertDialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
        <AlertDialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto">
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t("reveal.confirm.title", { count, n: formatNumber(count, i18n.language) })}
            </AlertDialogTitle>
            {count > REVEAL_CONFIRM_ABOVE ? (
              <AlertDialogDescription>
                {t("reveal.confirm.many", { n: formatNumber(count, i18n.language) })}
              </AlertDialogDescription>
            ) : null}
            {isSmb ? (
              <AlertDialogDescription>{t("reveal.confirm.stream")}</AlertDialogDescription>
            ) : null}
          </AlertDialogHeader>
          {isSmb ? (
            <div className="flex items-center gap-3">
              <Checkbox
                id="reveal-dont-explain"
                checked={dontExplain}
                onCheckedChange={(v) => setDontExplain(v === true)}
              />
              <Label htmlFor="reveal-dont-explain" className="font-normal">
                {t("reveal.confirm.dontExplain")}
              </Label>
            </div>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const request = pending
                setPending(null)
                if (isSmb && dontExplain) void setStreamNoteSeen(true)
                if (request) void send(request)
              }}
            >
              {t("reveal.confirm.action")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </RevealContext.Provider>
  )
}
