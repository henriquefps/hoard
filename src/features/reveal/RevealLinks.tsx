import { App } from "@capacitor/app"
import { useCallback, useEffect, useState } from "react"
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
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { formatNumber } from "@/lib/format"
import { HoardSmb } from "@/plugins/smb"

/**
 * Settings → "Reveal links" (ADR 0010 "Persistable hand-offs"): how many files Hoard handed to Reveal stay
 * reopenable, and "Revoke all". Shown while Reveal is installed or links remain (an uninstalled Reveal leaves none
 * readable, but the records stay until revoked or pruned).
 */
export function RevealLinks({ available }: { available: boolean }) {
  const { t, i18n } = useTranslation()
  const [count, setCount] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    void HoardSmb.revealLinks().then(
      (r) => setCount(r.count),
      () => setCount(null)
    )
  }, [])

  useEffect(() => {
    load()
    const handle = App.addListener("resume", load)
    return () => void handle.then((h) => h.remove()).catch(() => {})
  }, [load])

  if (count === null || (!available && count === 0)) return null
  const n = formatNumber(count, i18n.language)

  const revoke = async () => {
    setBusy(true)
    try {
      const { revoked } = await HoardSmb.revokeRevealLinks()
      toast.success(
        t("reveal.links.revoked", { count: revoked, n: formatNumber(revoked, i18n.language) })
      )
    } catch {
      toast.error(t("reveal.links.failed"))
    } finally {
      setBusy(false)
      load()
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-[0.9375rem] font-medium">{t("reveal.links.title")}</p>
      <p className="text-[0.9375rem] text-muted-foreground">
        {count === 0 ? t("reveal.links.none") : t("reveal.links.count", { count, n })}
      </p>
      {count > 0 ? (
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button variant="outline" className="self-start" disabled={busy}>
              {t("reveal.links.revoke")}
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto">
            <AlertDialogHeader>
              <AlertDialogTitle>{t("reveal.links.confirmTitle")}</AlertDialogTitle>
              <AlertDialogDescription>{t("reveal.links.confirmBody")}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
              <AlertDialogAction variant="destructive" onClick={() => void revoke()}>
                {t("reveal.links.revoke")}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      ) : null}
    </div>
  )
}
