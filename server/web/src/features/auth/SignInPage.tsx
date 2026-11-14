import { type FormEvent, useState } from "react"
import { useTranslation } from "react-i18next"
import { HoardMark } from "@/components/AppLogo"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { api, ApiError } from "@/lib/api"

/** Sign in with the server's admin password (ADR 0018 "Sign-in"). */
export function SignInPage({ setup, onSignedIn }: { setup: boolean; onSignedIn: () => void }) {
  const { t } = useTranslation()
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api("/web/session", { method: "POST", json: { password } })
      onSignedIn()
    } catch (err) {
      const code = err instanceof ApiError ? err.code : ""
      setError(
        code === "unauthorized"
          ? t("signIn.wrong")
          : code === "rate_limited"
            ? t("signIn.rateLimited")
            : t("signIn.failed")
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="grid min-h-dvh place-items-center bg-background p-4">
      <div className="w-full max-w-sm rounded-xl border bg-card p-6 shadow-xs">
        <div className="mb-6 flex items-center gap-2">
          <HoardMark className="size-7 text-primary" />
          <h1 className="text-lg font-semibold">{t("app.name")}</h1>
        </div>
        {setup ? (
          <p className="text-[0.9375rem] text-muted-foreground">
            {t("signIn.setupFirst")}{" "}
            <a className="font-medium text-primary underline-offset-4 hover:underline" href="/admin/">
              {t("signIn.setupLink")}
            </a>
          </p>
        ) : (
          <form onSubmit={submit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="password">{t("signIn.password")}</Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                autoFocus
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                aria-invalid={error ? true : undefined}
                aria-describedby={error ? "signin-error" : "signin-hint"}
              />
              {error ? (
                <p id="signin-error" role="alert" className="text-sm text-destructive">
                  {error}
                </p>
              ) : (
                <p id="signin-hint" className="text-sm text-muted-foreground">
                  {t("signIn.hint")}
                </p>
              )}
            </div>
            <Button type="submit" disabled={busy || !password}>
              {t("signIn.submit")}
            </Button>
          </form>
        )}
      </div>
    </main>
  )
}
