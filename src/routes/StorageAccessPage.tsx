import {
  CheckCircleIcon,
  FolderOpenIcon,
  GearSixIcon,
  HardDrivesIcon,
  ShieldCheckIcon,
  UsbIcon,
  type Icon,
} from "@phosphor-icons/react"
import { useNavigate } from "@tanstack/react-router"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { Page } from "@/components/page/Page"
import { Button } from "@/components/ui/button"
import { useVolumes } from "@/features/locations/volumesContext"
import { useSetting } from "@/features/settings/settings"

/**
 * First run (and from the home's "Allow access" card): why Hoard asks for all files access, then
 * Android's own Settings page for it (ADR 0005). "Not now" keeps SMB working and leaves a card on
 * the home screen; the screen is shown on its own only once.
 */
export function StorageAccessPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { access, requestAccess } = useVolumes()
  const [, setSeen] = useSetting("storageIntroSeen")
  const [busy, setBusy] = useState(false)
  const runtime = access?.mechanism === "runtime"
  const granted = access?.granted === true

  const done = async () => {
    await setSeen(true)
    void navigate({ to: "/", replace: true })
  }

  const points: { icon: Icon; title: string; body: string }[] = [
    {
      icon: FolderOpenIcon,
      title: t("storageAccess.point1Title"),
      body: t("storageAccess.point1Body"),
    },
    { icon: UsbIcon, title: t("storageAccess.point2Title"), body: t("storageAccess.point2Body") },
    {
      icon: ShieldCheckIcon,
      title: t("storageAccess.point3Title"),
      body: t("storageAccess.point3Body"),
    },
  ]

  return (
    <Page title={t("storageAccess.pageTitle")}>
      <div className="mx-auto flex w-full max-w-xl flex-col items-center gap-8 py-4 text-center md:py-10">
        <span className="inline-flex size-16 items-center justify-center rounded-2xl bg-primary/10 text-primary [&_svg]:size-8">
          {granted ? <CheckCircleIcon weight="duotone" /> : <HardDrivesIcon weight="duotone" />}
        </span>
        <div className="flex flex-col gap-2">
          <h2 className="text-2xl font-semibold text-balance">
            {granted ? t("storageAccess.grantedTitle") : t("storageAccess.title")}
          </h2>
          <p className="text-[0.9375rem] text-pretty text-muted-foreground">
            {granted ? t("storageAccess.grantedBody") : t("storageAccess.body")}
          </p>
        </div>
        {!granted && (
          <>
            <ul className="flex w-full flex-col gap-3 text-left">
              {points.map((point) => (
                <li
                  key={point.title}
                  className="flex items-start gap-3 rounded-xl border px-4 py-3"
                >
                  <span className="mt-0.5 inline-flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-primary [&_svg]:size-5">
                    <point.icon weight="duotone" />
                  </span>
                  <span className="flex flex-col gap-0.5">
                    <span className="text-[0.9375rem] font-medium">{point.title}</span>
                    <span className="text-[0.8125rem] text-muted-foreground">{point.body}</span>
                  </span>
                </li>
              ))}
            </ul>
            <ol className="flex w-full list-decimal flex-col gap-1 rounded-xl bg-muted/50 py-3 pr-4 pl-9 text-left text-[0.8125rem] text-muted-foreground">
              {runtime ? (
                <li>{t("storageAccess.stepRuntime")}</li>
              ) : (
                <>
                  <li>{t("storageAccess.step1")}</li>
                  <li>{t("storageAccess.step2")}</li>
                  <li>{t("storageAccess.step3")}</li>
                </>
              )}
            </ol>
          </>
        )}
        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row-reverse">
          {granted ? (
            <Button className="h-11 sm:px-6" onClick={() => void done()}>
              {t("storageAccess.continue")}
            </Button>
          ) : (
            <>
              <Button
                className="h-11 sm:px-6"
                disabled={busy || access === undefined}
                onClick={async () => {
                  setBusy(true)
                  try {
                    const state = await requestAccess()
                    if (state.granted) await setSeen(true)
                  } finally {
                    setBusy(false)
                  }
                }}
              >
                <GearSixIcon data-icon="inline-start" />
                {runtime ? t("storageAccess.allowRuntime") : t("storageAccess.openSettings")}
              </Button>
              <Button variant="ghost" className="h-11 sm:px-6" onClick={() => void done()}>
                {t("storageAccess.notNow")}
              </Button>
            </>
          )}
        </div>
        {!granted && (
          <p className="text-[0.8125rem] text-pretty text-muted-foreground">
            {t("storageAccess.smbNote")}
          </p>
        )}
      </div>
    </Page>
  )
}
