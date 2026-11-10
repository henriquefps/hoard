import { CrownSimpleIcon, SparkleIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import {
  ResponsiveDialog,
  ResponsiveDialogBody,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ResponsiveDialog"
import { Button } from "@/components/ui/button"
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useSetting } from "@/features/settings/settings"
import { formatNumber } from "@/lib/format"
import { cn } from "@/lib/utils"
import {
  BURST_GAPS_MS,
  DEFAULT_ASSIST,
  type Sensitivity,
  type ShotFlag,
  type Suggestion,
} from "./assist"
import { FLAG_ICONS, useReasonText } from "./assistLabels"

/** Small round marks for a shot's problems (and the best-in-burst crown), for grid cells. */
export function FlagMarks({
  flags,
  best,
  className,
}: {
  flags?: readonly ShotFlag[]
  best?: boolean
  className?: string
}) {
  const { t } = useTranslation()
  if (!best && !flags?.length) return null
  return (
    <span className={cn("flex flex-col items-end gap-1", className)}>
      {best ? (
        <span
          role="img"
          aria-label={t("cull.assist.best")}
          title={t("cull.assist.best")}
          className="inline-flex size-6 items-center justify-center rounded-full bg-primary text-primary-foreground [&_svg]:size-3.5"
        >
          <CrownSimpleIcon weight="fill" />
        </span>
      ) : null}
      {flags?.map((flag) => {
        const Icon = FLAG_ICONS[flag]
        return (
          <span
            key={flag}
            role="img"
            aria-label={t(`cull.assist.flag.${flag}`)}
            title={t(`cull.assist.flag.${flag}`)}
            className="inline-flex size-6 items-center justify-center rounded-full bg-warning text-viewer [&_svg]:size-3.5"
          >
            <Icon weight="bold" />
          </span>
        )
      })}
    </span>
  )
}

/** The dashed "suggested" ring and label on a grid cell (never looks like a decision). */
export function SuggestionMark({
  suggestion,
  className,
}: {
  suggestion: Suggestion
  className?: string
}) {
  const { t } = useTranslation()
  const reasons = useReasonText()
  const keep = suggestion.action === "keep"
  const label = t(keep ? "cull.assist.suggestKeep" : "cull.assist.suggestReject")
  return (
    <span
      role="img"
      aria-label={`${label}: ${reasons(suggestion.reasons)}`}
      title={reasons(suggestion.reasons)}
      className={cn(
        "inline-flex h-6 items-center gap-1 rounded-full border-2 border-dashed bg-viewer/80 px-1.5 text-xs font-medium text-viewer-foreground [&_svg]:size-3",
        keep ? "border-primary" : "border-destructive",
        className
      )}
    >
      <SparkleIcon weight="fill" />
      {t(keep ? "cull.assist.keepShort" : "cull.assist.rejectShort")}
    </span>
  )
}

/** A 64-bin luma histogram as bars, with the clipped ends highlighted. */
export function Histogram({ bins, className }: { bins: readonly number[]; className?: string }) {
  const { t } = useTranslation()
  const max = Math.max(1e-9, ...bins)
  const n = bins.length
  return (
    <svg
      role="img"
      aria-label={t("cull.assist.histogram")}
      viewBox={`0 0 ${n} 32`}
      preserveAspectRatio="none"
      className={cn("h-10 w-full", className)}
    >
      {bins.map((v, i) => {
        const h = Math.max(v > 0 ? 0.6 : 0, (Math.sqrt(v / max) || 0) * 32)
        const clipped = i === 0 || i === n - 1
        return (
          <rect
            key={i}
            x={i + 0.1}
            y={32 - h}
            width={0.8}
            height={h}
            className={clipped && v > 0.005 ? "fill-warning" : "fill-current opacity-60"}
          />
        )
      })}
    </svg>
  )
}

const SENSITIVITIES: Sensitivity[] = ["low", "medium", "high"]

/** Blur and eyes sensitivity and the burst gap (Settings page and the culling page's dialog). */
export function AssistSettingsFields() {
  const { t, i18n } = useTranslation()
  const [blur, setBlur] = useSetting("cullBlurSensitivity")
  const [eyes, setEyes] = useSetting("cullEyesSensitivity")
  const [gap, setGap] = useSetting("cullBurstGapMs")
  const sensitivity = (id: string, value: Sensitivity, onChange: (value: Sensitivity) => void) => (
    <ToggleGroup
      type="single"
      variant="outline"
      aria-labelledby={id}
      value={value}
      onValueChange={(v) => v && onChange(v as Sensitivity)}
      className="w-full *:flex-1 sm:w-auto"
    >
      {SENSITIVITIES.map((s) => (
        <ToggleGroupItem key={s} value={s} className="px-4">
          {t(`cull.assist.sensitivity.${s}`)}
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
  return (
    <FieldGroup>
      <Field>
        <FieldLabel id="assist-blur">{t("cull.assist.blurSetting")}</FieldLabel>
        {sensitivity("assist-blur", blur ?? DEFAULT_ASSIST.blur, (v) => void setBlur(v))}
        <FieldDescription>{t("cull.assist.blurHint")}</FieldDescription>
      </Field>
      <Field>
        <FieldLabel id="assist-eyes">{t("cull.assist.eyesSetting")}</FieldLabel>
        {sensitivity("assist-eyes", eyes ?? DEFAULT_ASSIST.eyes, (v) => void setEyes(v))}
        <FieldDescription>{t("cull.assist.eyesHint")}</FieldDescription>
      </Field>
      <Field>
        <FieldLabel htmlFor="assist-gap">{t("cull.assist.burstGap")}</FieldLabel>
        <Select
          value={String(gap ?? DEFAULT_ASSIST.burstGapMs)}
          onValueChange={(v) => void setGap(Number(v))}
        >
          <SelectTrigger id="assist-gap" className="w-full sm:w-48">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {BURST_GAPS_MS.map((ms) => (
              <SelectItem key={ms} value={String(ms)}>
                {t("cull.assist.seconds", { value: formatNumber(ms / 1000, i18n.language) })}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <FieldDescription>{t("cull.assist.burstGapHint")}</FieldDescription>
      </Field>
    </FieldGroup>
  )
}

export function AssistSettingsDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  return (
    <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveDialogContent>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{t("cull.assist.settingsTitle")}</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>{t("cull.assist.settingsHint")}</ResponsiveDialogDescription>
        </ResponsiveDialogHeader>
        <ResponsiveDialogBody>
          <AssistSettingsFields />
        </ResponsiveDialogBody>
        <ResponsiveDialogFooter>
          <Button className="max-md:h-11" onClick={() => onOpenChange(false)}>
            {t("common.done")}
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
