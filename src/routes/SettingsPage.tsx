import { Capacitor } from "@capacitor/core"
import {
  ArrowSquareOutIcon,
  FolderOpenIcon,
  FolderPlusIcon,
  GithubLogoIcon,
  ScalesIcon,
} from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { PaletteSwatch, THEME_ICONS } from "@/components/ThemeMenu"
import { Page } from "@/components/page/Page"
import { Button } from "@/components/ui/button"
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Switch } from "@/components/ui/switch"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useDb } from "@/db/DbProvider"
import { AssistSettingsFields } from "@/features/cull/assistParts"
import { useVolumes } from "@/features/locations/volumesContext"
import { Section } from "@/features/machines/actions"
import { useRouteEngine } from "@/features/route/RouteProvider"
import {
  CONFLICT_POLICIES,
  PROBE_TIMEOUTS,
  SETTING_DEFAULTS,
  useSetting,
} from "@/features/settings/settings"
import { isLanguage, LANGUAGES, setLanguage } from "@/i18n"
import { SORT_KEYS, type SortKey } from "@/lib/files"
import {
  isPalette,
  isTheme,
  PALETTES,
  setPalette,
  setTheme,
  THEMES,
  usePalette,
  useTheme,
} from "@/lib/theme/theme"
import { HoardSmb } from "@/plugins/smb"
import type { DestinationFolder, TransferConflict } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"

const REPOSITORY = "https://github.com/henriquefps/hoard"

export function SettingsPage() {
  const { t, i18n } = useTranslation()
  const theme = useTheme()
  const palette = usePalette()
  const [showHidden, setShowHidden] = useSetting("showHiddenFiles")
  const [sort, setSort] = useSetting("defaultSort")
  const [conflict, setConflict] = useSetting("defaultConflict")
  const [probeTimeout, setProbeTimeout] = useSetting("probeTimeoutMs")
  const currentSort = sort ?? SETTING_DEFAULTS.defaultSort

  return (
    <Page title={t("settings.title")}>
      <div className="flex max-w-2xl flex-col gap-10">
        <Section title={t("settings.appearance")}>
          <FieldGroup>
            <Field>
              <FieldLabel id="theme-label">{t("settings.theme")}</FieldLabel>
              <ToggleGroup
                type="single"
                variant="outline"
                aria-labelledby="theme-label"
                value={theme}
                onValueChange={(value) => isTheme(value) && setTheme(value)}
                className="w-full *:flex-1 sm:w-auto"
              >
                {THEMES.map((option) => {
                  const Icon = THEME_ICONS[option]
                  return (
                    <ToggleGroupItem key={option} value={option}>
                      <Icon />
                      {t(`theme.${option}`)}
                    </ToggleGroupItem>
                  )
                })}
              </ToggleGroup>
            </Field>
            <Field>
              <FieldLabel id="palette-label">{t("settings.palette")}</FieldLabel>
              <ToggleGroup
                type="single"
                variant="outline"
                aria-labelledby="palette-label"
                value={palette}
                onValueChange={(value) => isPalette(value) && setPalette(value)}
                className="flex-wrap justify-start"
                spacing={2}
              >
                {PALETTES.map((option) => (
                  <ToggleGroupItem key={option} value={option} className="h-11 px-3">
                    <PaletteSwatch palette={option} />
                    {t(`theme.palettes.${option}`)}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </Field>
            <Field>
              <FieldLabel htmlFor="language">{t("settings.language")}</FieldLabel>
              <Select
                value={i18n.language}
                onValueChange={(value) => isLanguage(value) && setLanguage(value)}
              >
                <SelectTrigger id="language" className="w-full sm:w-64">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {LANGUAGES.map((language) => (
                    <SelectItem key={language} value={language}>
                      {t(`language.${language}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
          </FieldGroup>
        </Section>

        <Section title={t("settings.browsing")}>
          <FieldGroup>
            <Field orientation="horizontal">
              <FieldContent>
                <FieldLabel htmlFor="show-hidden">{t("settings.showHidden")}</FieldLabel>
                <FieldDescription>{t("settings.showHiddenHint")}</FieldDescription>
              </FieldContent>
              <Switch
                id="show-hidden"
                checked={showHidden ?? false}
                onCheckedChange={(checked) => void setShowHidden(checked)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="default-sort">{t("settings.defaultSort")}</FieldLabel>
              <div className="flex flex-wrap gap-2">
                <Select
                  value={currentSort.key}
                  onValueChange={(key) => void setSort({ ...currentSort, key: key as SortKey })}
                >
                  <SelectTrigger id="default-sort" className="w-full sm:w-48">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {SORT_KEYS.map((key) => (
                      <SelectItem key={key} value={key}>
                        {t(`browse.sortKeys.${key}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Select
                  value={currentSort.direction}
                  onValueChange={(direction) =>
                    void setSort({ ...currentSort, direction: direction as "asc" | "desc" })
                  }
                >
                  <SelectTrigger
                    className="w-full sm:w-56"
                    aria-label={t("settings.sortDirection")}
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(["asc", "desc"] as const).map((direction) => (
                      <SelectItem key={direction} value={direction}>
                        {t(`browse.directions.${currentSort.key}.${direction}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <FieldDescription>{t("settings.foldersFirst")}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="probe-timeout">{t("settings.probeTimeout")}</FieldLabel>
              <Select
                value={String(probeTimeout ?? SETTING_DEFAULTS.probeTimeoutMs)}
                onValueChange={(value) => void setProbeTimeout(Number(value))}
              >
                <SelectTrigger id="probe-timeout" className="w-full sm:w-64">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PROBE_TIMEOUTS.map((ms) => (
                    <SelectItem key={ms} value={String(ms)}>
                      {t("settings.seconds", {
                        value: new Intl.NumberFormat(i18n.language).format(ms / 1000),
                      })}
                      {ms === SETTING_DEFAULTS.probeTimeoutMs ? ` (${t("settings.default")})` : ""}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FieldDescription>{t("settings.probeTimeoutHint")}</FieldDescription>
            </Field>
          </FieldGroup>
        </Section>

        <StorageSettings />

        <Section title={t("settings.culling")} description={t("settings.cullingHint")}>
          <AssistSettingsFields />
        </Section>

        <Section title={t("settings.downloads")}>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="default-conflict">{t("settings.defaultConflict")}</FieldLabel>
              <Select
                value={conflict ?? SETTING_DEFAULTS.defaultConflict}
                onValueChange={(value) => void setConflict(value as TransferConflict)}
              >
                <SelectTrigger id="default-conflict" className="w-full sm:w-64">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CONFLICT_POLICIES.map((option) => (
                    <SelectItem key={option} value={option}>
                      {t(`conflict.${option}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FieldDescription>
                {t(`conflict.${conflict ?? SETTING_DEFAULTS.defaultConflict}Hint`)}
              </FieldDescription>
            </Field>
            <Destinations />
          </FieldGroup>
        </Section>

        <Section title={t("settings.about")}>
          <div className="flex flex-col gap-3">
            <p className="text-[0.9375rem]">
              {t("app.name")}{" "}
              <span className="font-mono text-[0.8125rem] text-muted-foreground">
                {__APP_VERSION__}
              </span>
            </p>
            <p className="text-[0.9375rem] text-muted-foreground">{t("settings.aboutBody")}</p>
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" asChild>
                <a href={REPOSITORY} target="_blank" rel="noreferrer">
                  <GithubLogoIcon data-icon="inline-start" />
                  {t("settings.sourceCode")}
                  <ArrowSquareOutIcon data-icon="inline-end" />
                </a>
              </Button>
              <Button variant="outline" asChild>
                <a href={`${REPOSITORY}/blob/main/LICENSE`} target="_blank" rel="noreferrer">
                  <ScalesIcon data-icon="inline-start" />
                  {t("settings.license")}
                  <ArrowSquareOutIcon data-icon="inline-end" />
                </a>
              </Button>
            </div>
          </div>
        </Section>

        {!Capacitor.isNativePlatform() && <DeveloperTools />}
      </div>
    </Page>
  )
}

/** Phone folders Hoard may write to (persisted SAF permissions): list, add, release. */
function Destinations() {
  const { t } = useTranslation()
  const [folders, setFolders] = useState<DestinationFolder[]>()
  const [recent, setRecent] = useSetting("recentDestinations")
  const reload = () =>
    HoardSmb.listDestinationFolders().then(
      ({ folders }) => setFolders(folders),
      () => setFolders([])
    )
  useEffect(() => {
    void reload()
  }, [])
  return (
    <Field>
      <FieldLabel>{t("settings.destinations")}</FieldLabel>
      <FieldDescription>{t("settings.destinationsHint")}</FieldDescription>
      {folders === undefined ? (
        <Skeleton className="h-14 w-full" />
      ) : folders.length > 0 ? (
        <ul className="divide-y rounded-xl border">
          {folders.map((folder) => (
            <li key={folder.uri} className="flex min-h-14 items-center gap-3 py-2 pr-2 pl-4">
              <FolderOpenIcon
                aria-hidden
                weight="duotone"
                className="size-5 shrink-0 text-primary"
              />
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[0.9375rem] font-medium">{folder.name}</span>
                {recent?.[0]?.treeUri === folder.uri && (
                  <span className="text-[0.8125rem] text-muted-foreground">
                    {t("settings.lastUsed")}
                  </span>
                )}
              </span>
              <Button
                variant="ghost"
                onClick={async () => {
                  await HoardSmb.releaseDestinationFolder({ uri: folder.uri })
                  await setRecent((recent ?? []).filter((d) => d.treeUri !== folder.uri))
                  await reload()
                  toast(t("settings.released", { name: folder.name }))
                }}
              >
                {t("settings.release")}
              </Button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-[0.9375rem] text-muted-foreground">{t("download.noFolders")}</p>
      )}
      <div>
        <Button
          variant="outline"
          onClick={async () => {
            try {
              await HoardSmb.pickDestinationFolder()
              await reload()
            } catch (error) {
              if (smbErrorCode(error) !== "CANCELLED") toast.error(t("errors.UNKNOWN.title"))
            }
          }}
        >
          <FolderPlusIcon data-icon="inline-start" />
          {t("download.chooseFolder")}
        </Button>
      </div>
    </Field>
  )
}

/** All files access: its state and the way to change it (ADR 0005). */
function StorageSettings() {
  const { t } = useTranslation()
  const { access } = useVolumes()
  return (
    <Section title={t("settings.storage")}>
      <Field orientation="horizontal">
        <FieldContent>
          <FieldLabel>{t("settings.storageAccess")}</FieldLabel>
          <FieldDescription>
            {access === undefined ? (
              <Skeleton className="h-4 w-48" />
            ) : access.granted ? (
              t("settings.storageGranted")
            ) : (
              t("settings.storageDenied")
            )}
          </FieldDescription>
        </FieldContent>
        {access?.granted ? (
          <Button variant="outline" onClick={() => void HoardSmb.openStorageSettings()}>
            {t("locations.storageSettings")}
          </Button>
        ) : (
          <Button variant="outline" asChild>
            <Link to="/storage-access">{t("settings.storageManage")}</Link>
          </Button>
        )}
      </Field>
    </Section>
  )
}

type MockControls = {
  simulateNetwork: (network: "home" | "away" | "offline") => Promise<void> | void
  simulatedNetwork?: "home" | "away" | "offline"
  simulateVolume: (uuid: string, attached: boolean) => void
  simulateStorageAccess: (granted: boolean) => void
  simulatedVolumes: { uuid: string; label: string; attached: boolean }[]
  simulateIncomingShare: (files: { name: string; size: number; mimeType?: string }[]) => unknown
}

/** Browser build: plug the mock drives in and out, flip all files access. */
function MockStorageTools() {
  const { t } = useTranslation()
  const { access } = useVolumes()
  const mock = HoardSmb as unknown as MockControls
  // Through Capacitor's proxy a getter may arrive as a method wrapper: only an array counts.
  const [drives, setDrives] = useState(() =>
    Array.isArray(mock.simulatedVolumes) ? mock.simulatedVolumes : []
  )
  return (
    <>
      <Field orientation="horizontal">
        <FieldContent>
          <FieldLabel htmlFor="mock-storage-access">{t("settings.mockStorageAccess")}</FieldLabel>
        </FieldContent>
        <Switch
          id="mock-storage-access"
          checked={access?.granted ?? false}
          onCheckedChange={(checked) => mock.simulateStorageAccess(checked)}
        />
      </Field>
      <Field>
        <FieldLabel>{t("settings.volumes")}</FieldLabel>
        <div className="flex flex-col gap-2">
          {drives.map((drive) => (
            <Field key={drive.uuid} orientation="horizontal">
              <FieldLabel htmlFor={`mock-${drive.uuid}`} className="font-normal">
                {drive.label}
              </FieldLabel>
              <Switch
                id={`mock-${drive.uuid}`}
                checked={drive.attached}
                onCheckedChange={(checked) => {
                  mock.simulateVolume(drive.uuid, checked)
                  setDrives(mock.simulatedVolumes)
                }}
              />
            </Field>
          ))}
        </div>
        <FieldDescription>{t("settings.volumesHint")}</FieldDescription>
      </Field>
    </>
  )
}

/** Browser build only: simulated network and the worst-case data set (break-ui pass). */
function DeveloperTools() {
  const { t } = useTranslation()
  const db = useDb()
  const engine = useRouteEngine()
  const [worstCase, setWorstCase] = useState<boolean>()
  const [network, setNetwork] = useState<"home" | "away" | "offline">("home")
  useEffect(() => {
    void db
      .query<{ value: string }>("SELECT value FROM settings WHERE key = 'worstCaseSeeded'")
      .then(([row]) => setWorstCase(row?.value === "true"))
  }, [db])
  return (
    <Section title={t("settings.developer")} description={t("settings.developerHint")}>
      <FieldGroup>
        <Field>
          <FieldLabel id="network-label">{t("settings.network")}</FieldLabel>
          <ToggleGroup
            type="single"
            variant="outline"
            aria-labelledby="network-label"
            value={network}
            onValueChange={(value) => {
              if (!value) return
              const next = value as typeof network
              setNetwork(next)
              void (HoardSmb as unknown as MockControls).simulateNetwork(next)
              engine.recheckAll(0)
            }}
            className="w-full *:flex-1 sm:w-auto"
          >
            {(["home", "away", "offline"] as const).map((option) => (
              <ToggleGroupItem key={option} value={option}>
                {t(`settings.networks.${option}`)}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <FieldDescription>{t("settings.networkHint")}</FieldDescription>
        </Field>
        <MockStorageTools />
        <Field>
          <FieldLabel>{t("settings.mockShare")}</FieldLabel>
          <div>
            <Button
              variant="outline"
              onClick={() =>
                (HoardSmb as unknown as MockControls).simulateIncomingShare([
                  { name: "IMG_20261109_101500.jpg", size: 3_481_220, mimeType: "image/jpeg" },
                  { name: "IMG_20261109_101502.jpg", size: 3_102_904, mimeType: "image/jpeg" },
                  {
                    name: "Família — praia 2026 (versão final, não apagar).jpg",
                    size: 5_912_330,
                    mimeType: "image/jpeg",
                  },
                  { name: "boarding-pass.pdf", size: 182_004, mimeType: "application/pdf" },
                ])
              }
            >
              {t("settings.mockShareAction")}
            </Button>
          </div>
          <FieldDescription>{t("settings.mockShareHint")}</FieldDescription>
        </Field>
        <Field orientation="horizontal">
          <FieldContent>
            <FieldLabel htmlFor="worst-case">{t("settings.worstCase")}</FieldLabel>
            <FieldDescription>{t("settings.worstCaseHint")}</FieldDescription>
          </FieldContent>
          <Switch
            id="worst-case"
            checked={worstCase ?? false}
            disabled={worstCase === undefined}
            onCheckedChange={async (checked) => {
              const seed = await import("@/db/seed")
              if (checked) await seed.seedWorstCaseData(db)
              else await seed.removeWorstCaseData(db)
              setWorstCase(checked)
            }}
          />
        </Field>
      </FieldGroup>
    </Section>
  )
}
