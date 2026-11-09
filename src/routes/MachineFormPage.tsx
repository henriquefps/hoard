import {
  BroadcastIcon,
  CheckIcon,
  EyeIcon,
  EyeSlashIcon,
  FingerprintIcon,
  PlugsConnectedIcon,
  PlusIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { useNavigate } from "@tanstack/react-router"
import { useEffect, useRef, useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import {
  MACHINE_COLORS,
  MACHINE_ICON_IDS,
  MACHINE_ICONS,
  MachineIcon,
} from "@/components/MachineIcon"
import { Page } from "@/components/page/Page"
import { editMachineTrail, newMachineTrail } from "@/components/page/breadcrumbs"
import { SmbErrorState } from "@/components/SmbErrorState"
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
import { Button } from "@/components/ui/button"
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import { getMachine, listMachines, saveMachine, setServerGuid } from "@/db/queries"
import type { MachineWithAddresses } from "@/db/types"
import {
  type AddressTestResult,
  isValidPort,
  parseHostInput,
  testConnection,
} from "@/features/machines/address"
import {
  AddressEditor,
  type DraftAddress,
  newDraftAddress,
} from "@/features/machines/AddressEditor"
import { DeleteMachineDialog, Section } from "@/features/machines/actions"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { readSetting } from "@/features/settings/settings"
import { sortByKind } from "@/lib/addressKind"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { DiscoveredService } from "@/plugins/smb/definitions"

interface Draft {
  name: string
  icon: string
  color: string | null
  username: string
  password: string
  domain: string
  addresses: DraftAddress[]
}

const emptyDraft = (): Draft => ({
  name: "",
  icon: "desktop-tower",
  color: "blue",
  username: "",
  password: "",
  domain: "",
  addresses: [newDraftAddress()],
})

const fromMachine = (machine: MachineWithAddresses): Draft => ({
  name: machine.name,
  icon: machine.icon ?? "hard-drives",
  color: machine.color,
  username: machine.username,
  password: "",
  domain: machine.domain ?? "",
  addresses: machine.addresses.map((a) => ({
    key: a.id,
    host: a.host,
    port: a.port === 445 ? "" : String(a.port),
    label: a.label,
  })),
})

type Errors = Partial<Record<"name" | "username" | "password" | "addresses", string>> & {
  byAddress: Record<string, string | undefined>
}

/** `/machines/new` and `/machines/$machineId/edit`. */
export function MachineFormPage({ machineId }: { machineId?: string }) {
  const { t } = useTranslation()
  const db = useDb()
  const navigate = useNavigate()
  const engine = useRouteEngine()
  const isNew = !machineId
  // A new machine gets its id up front, so "Test connection" can store the password under it.
  const [id] = useState(() => machineId ?? crypto.randomUUID())
  const [loaded, setLoaded] = useState<MachineWithAddresses | null | undefined>(
    isNew ? null : undefined
  )
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [manualOrder, setManualOrder] = useState(!isNew)
  const [hasPassword, setHasPassword] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const [errors, setErrors] = useState<Errors>({ byAddress: {} })
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [results, setResults] = useState<AddressTestResult[]>()
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [repinOpen, setRepinOpen] = useState(false)
  const credentialsStored = useRef(false)
  const saved = useRef(false)
  const machines = useLiveQuery(listMachines)
  const live = machines?.find((m) => m.id === id)

  useEffect(() => {
    if (isNew) return
    let cancelled = false
    void getMachine(db, id).then((machine) => {
      if (cancelled) return
      setLoaded(machine)
      if (machine) setDraft(fromMachine(machine))
    })
    void HoardSmb.hasCredentials({ machineId: id }).then(
      ({ value }) => !cancelled && setHasPassword(value)
    )
    return () => {
      cancelled = true
    }
  }, [db, id, isNew])

  // A new machine whose password was stored by "Test connection" but never saved: forget it.
  useEffect(
    () => () => {
      if (isNew && credentialsStored.current && !saved.current) {
        void HoardSmb.deleteCredentials({ machineId: id }).catch(() => {})
      }
    },
    [id, isNew]
  )

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((d) => ({ ...d, [key]: value }))

  function validate(): { ok: boolean; addresses: { host: string; port?: number }[] } {
    const next: Errors = { byAddress: {} }
    const addresses: { host: string; port?: number }[] = []
    const seen = new Set<string>()
    for (const address of draft.addresses) {
      if (!address.host.trim() && !address.port) continue
      const parsed = parseHostInput(address.host)
      const port = address.port ? Number(address.port) : parsed?.port
      if (!parsed) next.byAddress[address.key] = t("machines.form.errors.host")
      else if (port !== undefined && !isValidPort(port))
        next.byAddress[address.key] = t("machines.form.errors.port")
      else {
        const key = `${parsed.host.toLowerCase()}:${port ?? 445}`
        if (seen.has(key)) next.byAddress[address.key] = t("machines.form.errors.duplicate")
        seen.add(key)
        addresses.push({ host: parsed.host, port })
      }
    }
    if (!draft.name.trim()) next.name = t("machines.form.errors.name")
    if (!draft.username.trim()) next.username = t("machines.form.errors.username")
    if (addresses.length === 0 && Object.keys(next.byAddress).length === 0)
      next.addresses = t("machines.form.errors.addresses")
    const userChanged =
      loaded &&
      (loaded.username !== draft.username.trim() || (loaded.domain ?? "") !== draft.domain.trim())
    if (!draft.password && (isNew || !hasPassword))
      next.password = t("machines.form.errors.password")
    else if (!draft.password && userChanged)
      next.password = t("machines.form.errors.passwordForUser")
    setErrors(next)
    const ok =
      !next.name &&
      !next.username &&
      !next.password &&
      !next.addresses &&
      Object.values(next.byAddress).every((e) => !e)
    return { ok, addresses }
  }

  async function storeCredentials() {
    if (!draft.password) return
    await HoardSmb.setCredentials({
      machineId: id,
      username: draft.username.trim(),
      password: draft.password,
      domain: draft.domain.trim() || undefined,
    })
    credentialsStored.current = true
    setHasPassword(true)
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    const { ok, addresses } = validate()
    if (!ok) {
      requestAnimationFrame(() =>
        document.querySelector<HTMLElement>("[aria-invalid=true]")?.focus()
      )
      return
    }
    setSaving(true)
    try {
      await saveMachine(
        db,
        {
          name: draft.name.trim(),
          icon: draft.icon,
          color: draft.color,
          username: draft.username.trim(),
          domain: draft.domain.trim() || null,
          addresses,
        },
        id,
        { create: isNew }
      )
      await storeCredentials()
      saved.current = true
      engine.forget(id)
      toast.success(isNew ? t("machines.form.created") : t("machines.form.saved"))
      void navigate({ to: "/browse/$locationId", params: { locationId: id } })
    } catch {
      toast.error(t("errors.UNKNOWN.title"))
    } finally {
      setSaving(false)
    }
  }

  async function onTest() {
    const { addresses } = validate()
    if (addresses.length === 0) return
    setTesting(true)
    setResults(undefined)
    try {
      if (draft.password && draft.username.trim()) await storeCredentials()
      const timeoutMs = await readSetting(db, "probeTimeoutMs")
      setResults(
        await testConnection(HoardSmb, {
          machineId: id,
          addresses,
          pinnedGuid: live?.server_guid ?? null,
          timeoutMs,
        })
      )
    } finally {
      setTesting(false)
    }
  }

  function applySuggestion(service: DiscoveredService) {
    setDraft((d) => {
      const exists = d.addresses.some((a) => a.host.trim().toLowerCase() === service.host)
      const filled = d.addresses.filter((a) => a.host.trim() || a.port)
      const next = exists
        ? d.addresses
        : [
            ...filled,
            newDraftAddress(service.host, service.port === 445 ? "" : String(service.port)),
          ]
      return {
        ...d,
        name: d.name.trim() ? d.name : service.name,
        addresses: manualOrder ? next : sortByKind(next),
      }
    })
    setResults(undefined)
  }

  if (loaded === undefined) {
    return (
      <Page title={t("machines.edit")} breadcrumbs={editMachineTrail(t, { id, name: undefined })}>
        <div className="flex max-w-2xl flex-col gap-4">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      </Page>
    )
  }
  if (!isNew && loaded === null) {
    return (
      <Page title={t("machines.notFound")} breadcrumbs={editMachineTrail(t, { id, name: "?" })}>
        <SmbErrorState code="NOT_FOUND" machineId={id} />
      </Page>
    )
  }

  const pinned = live?.server_guid ?? loaded?.server_guid ?? null
  return (
    <Page
      title={isNew ? t("machines.new") : t("machines.edit")}
      breadcrumbs={isNew ? newMachineTrail(t) : editMachineTrail(t, { id, name: loaded?.name })}
    >
      <form
        id="machine-form"
        noValidate
        onSubmit={onSubmit}
        className="flex max-w-2xl flex-col gap-8"
      >
        {isNew && <Suggestions existing={machines} onPick={applySuggestion} />}

        <Section title={t("machines.form.machine")}>
          <FieldGroup>
            <Field data-invalid={Boolean(errors.name)}>
              <FieldLabel htmlFor="machine-name">{t("machines.form.name")}</FieldLabel>
              <div className="flex items-center gap-3">
                <MachineIcon icon={draft.icon} color={draft.color} />
                <Input
                  id="machine-name"
                  value={draft.name}
                  onChange={(e) => set("name", e.target.value)}
                  placeholder={t("machines.form.namePlaceholder")}
                  aria-invalid={Boolean(errors.name)}
                  autoComplete="off"
                  maxLength={120}
                />
              </div>
              {errors.name && <FieldError>{errors.name}</FieldError>}
            </Field>
            <Field>
              <FieldLabel id="machine-icon-label">{t("machines.form.icon")}</FieldLabel>
              <ToggleGroup
                type="single"
                variant="outline"
                value={draft.icon}
                onValueChange={(value) => value && set("icon", value)}
                aria-labelledby="machine-icon-label"
                className="flex-wrap justify-start"
                spacing={1}
              >
                {MACHINE_ICON_IDS.map((icon) => {
                  const Icon = MACHINE_ICONS[icon]
                  return (
                    <ToggleGroupItem
                      key={icon}
                      value={icon}
                      aria-label={t(`machines.icons.${icon}`)}
                      title={t(`machines.icons.${icon}`)}
                      className="size-10 sm:size-11"
                    >
                      <Icon weight="duotone" className="size-5" />
                    </ToggleGroupItem>
                  )
                })}
              </ToggleGroup>
            </Field>
            <Field>
              <FieldLabel id="machine-color-label">{t("machines.form.color")}</FieldLabel>
              <ToggleGroup
                type="single"
                value={draft.color ?? ""}
                onValueChange={(value) => value && set("color", value)}
                aria-labelledby="machine-color-label"
                className="flex-wrap justify-start"
                spacing={1}
              >
                {MACHINE_COLORS.map((color) => (
                  <ToggleGroupItem
                    key={color}
                    value={color}
                    aria-label={t(`machines.colors.${color}`)}
                    title={t(`machines.colors.${color}`)}
                    className="size-11 rounded-full p-0 data-[state=on]:bg-transparent"
                  >
                    <span
                      data-machine-color={color}
                      className="flex size-7 items-center justify-center rounded-full bg-(--machine) text-white ring-offset-2 ring-offset-card in-data-[state=on]:ring-2 in-data-[state=on]:ring-(--machine)"
                    >
                      {draft.color === color && <CheckIcon weight="bold" className="size-4" />}
                    </span>
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </Field>
          </FieldGroup>
        </Section>

        <Section
          title={t("machines.form.addresses")}
          description={t("machines.form.addressesHint")}
        >
          <AddressEditor
            addresses={draft.addresses}
            onChange={(addresses) => {
              set("addresses", addresses)
              setResults(undefined)
            }}
            errors={errors.byAddress}
            results={results}
            manualOrder={manualOrder}
            onManualOrder={setManualOrder}
          />
          {errors.addresses && (
            <p role="alert" className="text-[0.8125rem] text-destructive">
              {errors.addresses}
            </p>
          )}
        </Section>

        <Section title={t("machines.form.signIn")} description={t("machines.form.signInHint")}>
          <FieldGroup>
            <Field data-invalid={Boolean(errors.username)}>
              <FieldLabel htmlFor="machine-user">{t("machines.form.username")}</FieldLabel>
              <Input
                id="machine-user"
                value={draft.username}
                onChange={(e) => set("username", e.target.value)}
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                aria-invalid={Boolean(errors.username)}
              />
              {errors.username && <FieldError>{errors.username}</FieldError>}
            </Field>
            <Field data-invalid={Boolean(errors.password)}>
              <FieldLabel htmlFor="machine-password">{t("machines.form.password")}</FieldLabel>
              <InputGroup>
                <InputGroupInput
                  id="machine-password"
                  type={showPassword ? "text" : "password"}
                  value={draft.password}
                  onChange={(e) => set("password", e.target.value)}
                  autoComplete="new-password"
                  placeholder={hasPassword ? t("machines.form.passwordKeep") : undefined}
                  aria-invalid={Boolean(errors.password)}
                  aria-describedby="machine-password-hint"
                />
                <InputGroupAddon align="inline-end">
                  <InputGroupButton
                    size="icon-sm"
                    onClick={() => setShowPassword((v) => !v)}
                    aria-label={
                      showPassword
                        ? t("machines.form.hidePassword")
                        : t("machines.form.showPassword")
                    }
                    aria-pressed={showPassword}
                  >
                    {showPassword ? <EyeSlashIcon /> : <EyeIcon />}
                  </InputGroupButton>
                </InputGroupAddon>
              </InputGroup>
              <FieldDescription id="machine-password-hint">
                {hasPassword ? (
                  <span className="inline-flex items-center gap-1 text-primary">
                    <CheckIcon weight="bold" aria-hidden />
                    {t("machines.form.passwordSaved")}
                  </span>
                ) : (
                  t("machines.form.passwordHint")
                )}
              </FieldDescription>
              {errors.password && <FieldError>{errors.password}</FieldError>}
            </Field>
            <Field>
              <FieldLabel htmlFor="machine-domain">{t("machines.form.domain")}</FieldLabel>
              <Input
                id="machine-domain"
                value={draft.domain}
                onChange={(e) => set("domain", e.target.value)}
                placeholder={t("machines.form.domainPlaceholder")}
                autoCapitalize="characters"
                spellCheck={false}
              />
            </Field>
          </FieldGroup>
        </Section>

        <Section
          title={t("machines.test.title")}
          description={t("machines.test.hint")}
          action={undefined}
        >
          <div className="flex flex-wrap items-center gap-3">
            <Button type="button" variant="outline" onClick={onTest} disabled={testing}>
              {testing ? (
                <Spinner data-icon="inline-start" />
              ) : (
                <PlugsConnectedIcon data-icon="inline-start" />
              )}
              {testing ? t("machines.test.running") : t("machines.test.run")}
            </Button>
            {results && (
              <p className="text-[0.9375rem]" aria-live="polite">
                {results.some((r) => r.connect?.ok)
                  ? t("machines.test.summaryOk", {
                      count: results.filter((r) => r.connect?.ok).length,
                      total: results.length,
                    })
                  : t("machines.test.summaryNone")}
              </p>
            )}
          </div>
        </Section>

        {!isNew && (
          <Section title={t("machines.identity.title")} description={t("machines.identity.body")}>
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border px-4 py-3">
              <div className="flex min-w-0 items-center gap-3">
                <FingerprintIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
                <div className="flex min-w-0 flex-col">
                  <span className="text-[0.9375rem] font-medium">
                    {pinned ? t("machines.identity.pinned") : t("machines.identity.notPinned")}
                  </span>
                  <span
                    className="truncate font-mono text-[0.8125rem] text-muted-foreground"
                    title={pinned ?? undefined}
                  >
                    {pinned ?? t("machines.identity.pinsOnConnect")}
                  </span>
                </div>
              </div>
              {pinned && (
                <Button type="button" variant="outline" onClick={() => setRepinOpen(true)}>
                  {t("machines.identity.repin")}
                </Button>
              )}
            </div>
          </Section>
        )}

        {!isNew && loaded && (
          <Section title={t("machines.delete.section")}>
            <div>
              <Button type="button" variant="destructive" onClick={() => setDeleteOpen(true)}>
                <TrashIcon data-icon="inline-start" />
                {t("machines.delete.action")}
              </Button>
            </div>
          </Section>
        )}
      </form>
      <div className="sticky bottom-0 z-10 -mx-4 -mt-2 -mb-4 flex gap-2 border-t bg-card px-4 py-3 md:-mx-6 md:-mb-6 md:justify-end md:px-6 max-md:[&_button]:h-11 max-md:[&_button]:flex-1">
        <Button type="submit" form="machine-form" disabled={saving} className="order-last">
          {saving && <Spinner data-icon="inline-start" />}
          {isNew ? t("machines.form.create") : t("common.save")}
        </Button>
        <Button
          type="button"
          variant="outline"
          onClick={() =>
            isNew
              ? navigate({ to: "/machines" })
              : navigate({ to: "/browse/$locationId", params: { locationId: id } })
          }
        >
          {t("common.cancel")}
        </Button>
      </div>

      {loaded && (
        <DeleteMachineDialog
          machine={{ id, name: loaded.name }}
          open={deleteOpen}
          onOpenChange={setDeleteOpen}
          onDeleted={() => void navigate({ to: "/machines" })}
        />
      )}
      <AlertDialog open={repinOpen} onOpenChange={setRepinOpen}>
        <AlertDialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto">
          <AlertDialogHeader>
            <AlertDialogTitle>{t("machines.identity.repinTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("machines.identity.repinBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={async () => {
                await setServerGuid(db, id, null)
                engine.forget(id)
                toast.success(t("machines.identity.repinDone"))
              }}
            >
              {t("machines.identity.repin")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Page>
  )
}

/** mDNS (`_smb._tcp`) servers on this network, offered as one-tap prefill on a new machine. */
function Suggestions({
  existing,
  onPick,
}: {
  existing: MachineWithAddresses[] | undefined
  onPick: (service: DiscoveredService) => void
}) {
  const { t } = useTranslation()
  const [services, setServices] = useState<DiscoveredService[]>()
  const [failed, setFailed] = useState(false)
  const [picked, setPicked] = useState<string>()
  useEffect(() => {
    let cancelled = false
    HoardSmb.discover({ timeoutMs: 3000 }).then(
      ({ services }) => !cancelled && setServices(services),
      () => !cancelled && setFailed(true)
    )
    return () => {
      cancelled = true
    }
  }, [])
  if (failed || (services && services.length === 0)) return null
  const known = new Set(
    existing?.flatMap((m) => m.addresses.map((a) => a.host.toLowerCase())) ?? []
  )
  return (
    <Section title={t("machines.discover.title")} description={t("machines.discover.hint")}>
      {!services ? (
        <p
          className="flex items-center gap-2 text-[0.9375rem] text-muted-foreground"
          aria-live="polite"
        >
          <Spinner aria-hidden />
          {t("machines.discover.searching")}
        </p>
      ) : (
        <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {services.map((service) => {
            const already = known.has(service.host.toLowerCase())
            const chosen = picked === `${service.host}:${service.port}`
            return (
              <li key={`${service.name}-${service.host}-${service.port}`}>
                <button
                  type="button"
                  onClick={() => {
                    setPicked(`${service.host}:${service.port}`)
                    onPick(service)
                  }}
                  className={cn(
                    "flex min-h-14 w-full items-center gap-3 rounded-xl border px-3 py-2 text-left transition-colors outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50",
                    chosen && "border-primary/40 bg-primary/5"
                  )}
                >
                  <BroadcastIcon
                    aria-hidden
                    weight="duotone"
                    className="size-5 shrink-0 text-primary"
                  />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-[0.9375rem] font-medium">{service.name}</span>
                    <span className="truncate font-mono text-[0.8125rem] text-muted-foreground">
                      {service.host}
                      {service.port !== 445 ? `:${service.port}` : ""}
                      {already && (
                        <span className="font-sans"> · {t("machines.discover.already")}</span>
                      )}
                    </span>
                  </span>
                  {chosen ? (
                    <CheckIcon aria-hidden className="size-5 shrink-0 text-primary" />
                  ) : (
                    <PlusIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </Section>
  )
}
