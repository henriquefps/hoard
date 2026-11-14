import { useCallback, useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { TooltipProvider } from "@/components/ui/tooltip"
import { Toaster } from "@/components/ui/sonner"
import { Spinner } from "@/components/ui/spinner"
import { api, setUnauthorizedHandler, type Session } from "@/lib/api"
import { useTheme } from "@/lib/theme"
import { SignInPage } from "@/features/auth/SignInPage"
import { Shell } from "@/features/Shell"

export function App() {
  const { t } = useTranslation()
  const theme = useTheme()
  const [session, setSession] = useState<Session | null>(null)
  const [failed, setFailed] = useState(false)
  const load = useCallback(() => {
    api<Session>("/web/session")
      .then((s) => {
        setSession(s)
        setFailed(false)
      })
      .catch(() => setFailed(true))
  }, [])
  useEffect(() => {
    load()
    setUnauthorizedHandler(() => setSession((s) => (s ? { ...s, signedIn: false } : s)))
  }, [load])

  let body
  if (!session) {
    body = (
      <div className="grid h-dvh place-items-center text-muted-foreground" role="status">
        {failed ? (
          <Button variant="outline" onClick={load}>
            {t("common.retry")}
          </Button>
        ) : (
          <Spinner className="size-6" aria-label={t("common.loading")} />
        )}
      </div>
    )
  } else if (!session.signedIn) {
    body = <SignInPage setup={session.setup} onSignedIn={load} />
  } else {
    body = <Shell session={session} onSignedOut={load} />
  }
  return (
    <TooltipProvider delayDuration={300}>
      {body}
      <Toaster theme={theme} position="bottom-center" />
    </TooltipProvider>
  )
}
