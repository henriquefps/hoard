import { PlusIcon, SortAscendingIcon, TrashIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { AddressKindBadge } from "@/components/AddressKindBadge"
import { SortableList } from "@/components/SortableList"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { detectAddressKind, sortByKind } from "@/lib/addressKind"
import { cn } from "@/lib/utils"
import type { AddressTestResult } from "./address"
import { TestResultLine } from "./TestConnection"

export interface DraftAddress {
  /** Local key for React and drag (not stored). */
  key: string
  host: string
  /** Text of the port field; empty = 445. */
  port: string
  label: string | null
}

export const newDraftAddress = (host = "", port = ""): DraftAddress => ({
  key: crypto.randomUUID(),
  host,
  port,
  label: null,
})

/**
 * The ordered address list: host and optional port per row, the kind detected live from the host,
 * drag handle and Move up/down buttons, remove. New rows go after their kind's group (local before
 * Tailscale before other) until the user reorders by hand.
 */
export function AddressEditor({
  addresses,
  onChange,
  errors,
  results,
  manualOrder,
  onManualOrder,
}: {
  addresses: DraftAddress[]
  onChange: (addresses: DraftAddress[]) => void
  errors: Record<string, string | undefined>
  results?: AddressTestResult[]
  manualOrder: boolean
  onManualOrder: (manual: boolean) => void
}) {
  const { t } = useTranslation()
  const update = (key: string, patch: Partial<DraftAddress>) =>
    onChange(addresses.map((a) => (a.key === key ? { ...a, ...patch } : a)))
  const sorted = sortByKind(addresses)
  const isSorted = sorted.every((a, i) => a.key === addresses[i].key)

  return (
    <div className="@container flex flex-col gap-3">
      <SortableList
        items={addresses}
        getKey={(a) => a.key}
        getLabel={(a) => a.host || t("machines.form.emptyAddress")}
        onReorder={(next) => {
          onManualOrder(true)
          onChange(next)
        }}
        className="divide-y overflow-hidden rounded-xl border"
        renderItem={(address, { index, handle, moveButtons }) => {
          const kind = detectAddressKind(address.host)
          const error = errors[address.key]
          const result = results?.find(
            (r) => r.host === address.host.trim() && r.port === (Number(address.port) || 445)
          )
          return (
            <div className="flex flex-col gap-2 px-2 py-2 @lg:px-3">
              <div className="flex flex-wrap items-center gap-x-1 gap-y-2 @lg:flex-nowrap @lg:gap-2">
                {handle}
                <Input
                  value={address.host}
                  onChange={(e) => update(address.key, { host: e.target.value })}
                  onBlur={() => {
                    if (!manualOrder) onChange(sortByKind(addresses))
                  }}
                  placeholder={t("machines.form.hostPlaceholder")}
                  aria-label={t("machines.form.hostLabel", { index: index + 1 })}
                  aria-invalid={Boolean(error)}
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  inputMode="url"
                  className="min-w-0 flex-1 basis-40 font-mono"
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  onClick={() => onChange(addresses.filter((a) => a.key !== address.key))}
                  aria-label={t("machines.form.removeAddress", {
                    host: address.host || t("machines.form.emptyAddress"),
                  })}
                  className="text-muted-foreground @lg:order-last"
                >
                  <TrashIcon />
                </Button>
                <div className="flex w-full items-center gap-2 pl-10 @lg:w-auto @lg:pl-0">
                  <Input
                    value={address.port}
                    onChange={(e) =>
                      update(address.key, { port: e.target.value.replace(/[^\d]/g, "") })
                    }
                    placeholder="445"
                    inputMode="numeric"
                    aria-label={t("machines.form.portLabel", { index: index + 1 })}
                    className="w-20 font-mono"
                    maxLength={5}
                  />
                  <span className="w-20 shrink-0" aria-live="polite">
                    {address.host.trim() ? <AddressKindBadge kind={kind} /> : null}
                  </span>
                  <span className="ml-auto @lg:ml-0">{moveButtons}</span>
                </div>
              </div>
              {error && (
                <p role="alert" className="pl-10 text-[0.8125rem] text-destructive">
                  {error}
                </p>
              )}
              {result && <TestResultLine result={result} className="pl-10" />}
            </div>
          )
        }}
      />
      <div className={cn("flex flex-wrap gap-2")}>
        <Button
          type="button"
          variant="outline"
          onClick={() => onChange([...addresses, newDraftAddress()])}
        >
          <PlusIcon data-icon="inline-start" />
          {t("machines.form.addAddress")}
        </Button>
        {!isSorted && (
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              onManualOrder(false)
              onChange(sorted)
            }}
          >
            <SortAscendingIcon data-icon="inline-start" />
            {t("machines.form.sortByKind")}
          </Button>
        )}
      </div>
    </div>
  )
}
