import { CrownSimpleIcon, SparkleIcon, UserFocusIcon } from "@phosphor-icons/react"
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
import type { CullAnalysis, CullFace } from "@/plugins/smb/definitions"
import {
  BURST_GAPS_MS,
  DEFAULT_ASSIST,
  type Sensitivity,
  sharpnessScore,
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
            className={clipped && v > 0.005 ? "fill-warning" : "fill-viewer-foreground/70"}
          />
        )
      })}
    </svg>
  )
}

const percent = (value: number, locale: string) =>
  `${formatNumber(Math.round(value * 1000) / 10, locale)}%`

function eyesLine(face: CullFace | undefined, t: (key: string) => string) {
  if (!face || face.leftEyeOpen === undefined || face.rightEyeOpen === undefined)
    return t("cull.assist.eyesUnknown")
  const open = Math.max(face.leftEyeOpen, face.rightEyeOpen)
  return open < 0.3 ? t("cull.assist.eyesClosed") : t("cull.assist.eyesOpen")
}

/**
 * The viewer's analysis overlay (ADR 0009): sharpness (0-100, on the face or the sharpest area),
 * faces and eyes with "zoom to face", exposure with a mini histogram, flags, and the suggestion
 * with its reason and an Accept button.
 */
export function AnalysisPanel({
  analysis,
  flags,
  best,
  suggestion,
  facesAvailable,
  onJumpToFace,
  onAccept,
  onAcceptBurst,
  className,
}: {
  analysis?: CullAnalysis
  flags?: readonly ShotFlag[]
  best?: boolean
  suggestion?: Suggestion
  facesAvailable: boolean
  onJumpToFace?: (face: CullFace) => void
  onAccept?: () => void
  /** Accept the suggestions of this shot's whole burst (one undo step). */
  onAcceptBurst?: () => void
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const reasons = useReasonText()
  const locale = i18n.language
  const box =
    "flex w-[min(22rem,calc(100vw-1.5rem))] flex-col gap-1.5 rounded-xl bg-viewer/75 px-3 py-2 text-[0.8125rem] leading-5 text-viewer-foreground backdrop-blur-sm"
  if (!analysis) {
    return (
      <div className={cn(box, className)}>
        <span className="text-viewer-foreground/70">{t("cull.assist.notAnalysed")}</span>
      </div>
    )
  }
  const prominent = analysis.faces.filter((f) => f.prominent)
  const main = prominent[0]
  const e = analysis.exposure
  return (
    <div className={cn(box, className)}>
      {suggestion ? (
        <div className="flex items-start gap-2 border-b border-viewer-foreground/15 pb-1.5">
          <SparkleIcon
            weight="fill"
            className={cn(
              "mt-0.5 size-4 shrink-0",
              suggestion.action === "keep" ? "text-primary" : "text-destructive"
            )}
          />
          <span className="min-w-0 flex-1">
            <span className="font-medium">
              {t(
                suggestion.action === "keep"
                  ? "cull.assist.suggestKeep"
                  : "cull.assist.suggestReject"
              )}
            </span>
            <span className="block text-viewer-foreground/75">{reasons(suggestion.reasons)}</span>
            {onAcceptBurst ? (
              <Button
                size="sm"
                variant="link"
                className="pointer-events-auto h-auto px-0 text-viewer-foreground underline"
                onClick={onAcceptBurst}
              >
                {t("cull.assist.acceptBurst")}
              </Button>
            ) : null}
          </span>
          {onAccept ? (
            <Button
              size="sm"
              variant={suggestion.action === "keep" ? "default" : "destructive"}
              className="pointer-events-auto shrink-0"
              onClick={onAccept}
            >
              {t("cull.assist.accept")}
            </Button>
          ) : null}
        </div>
      ) : null}
      <div className="flex items-center gap-2">
        <span className="font-medium tabular-nums">
          {t("cull.assist.sharpness", { value: sharpnessScore(analysis) })}
        </span>
        <span className="text-viewer-foreground/70">
          {t(
            analysis.sharpness.subjectKind === "face"
              ? "cull.assist.subjectFace"
              : "cull.assist.subjectTile"
          )}
        </span>
        {best ? (
          <span className="ml-auto inline-flex items-center gap-1 text-primary [&_svg]:size-3.5">
            <CrownSimpleIcon weight="fill" />
            {t("cull.assist.best")}
          </span>
        ) : null}
      </div>
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate">
          {!analysis.facesDetected || !facesAvailable
            ? t("cull.assist.facesUnavailable")
            : prominent.length === 0
              ? t("cull.assist.noFaces")
              : `${t("cull.assist.faces", { count: prominent.length })} · ${eyesLine(main, t)}`}
        </span>
        {main && onJumpToFace ? (
          <Button
            size="sm"
            variant="ghost"
            className="pointer-events-auto -mr-2 shrink-0"
            onClick={() => onJumpToFace(main)}
          >
            <UserFocusIcon data-icon="inline-start" />
            {t("cull.assist.jumpToFace")}
          </Button>
        ) : null}
      </div>
      <div className="flex flex-col gap-0.5">
        <Histogram bins={e.histogram} />
        <span className="flex justify-between text-xs text-viewer-foreground/75 tabular-nums">
          <span>{t("cull.assist.shadows", { value: percent(e.shadows, locale) })}</span>
          <span>{t("cull.assist.highlights", { value: percent(e.highlights, locale) })}</span>
        </span>
      </div>
      {flags?.length ? (
        <div className="flex flex-wrap gap-1">
          {flags.map((flag) => {
            const Icon = FLAG_ICONS[flag]
            return (
              <span
                key={flag}
                className="inline-flex h-6 items-center gap-1 rounded-full bg-warning px-2 text-xs font-medium text-viewer [&_svg]:size-3.5"
              >
                <Icon weight="bold" />
                {t(`cull.assist.flag.${flag}`)}
              </span>
            )
          })}
        </div>
      ) : null}
      <span className="text-xs text-viewer-foreground/60">
        {t("cull.assist.measuredOn", {
          source: t(`cull.source.screen.${analysis.source}`),
          size: `${analysis.width} × ${analysis.height}`,
        })}
      </span>
    </div>
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
