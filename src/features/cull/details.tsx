import {
  CrownSimpleIcon,
  EyeIcon,
  CrosshairSimpleIcon,
  SparkleIcon,
  StackIcon,
  UserFocusIcon,
} from "@phosphor-icons/react"
import type { ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { formatNumber } from "@/lib/format"
import { cn } from "@/lib/utils"
import type {
  CullAnalysis,
  CullFace,
  CullImageResult,
  CullMetadata,
  CullShot,
  CullZoomResult,
} from "@/plugins/smb/definitions"
import {
  type EyeState,
  eyesSummary,
  exposureOf,
  type Sensitivity,
  sharpnessScore,
  type ShotFlag,
  type Suggestion,
} from "./assist"
import { FLAG_ICONS, useReasonText } from "./assistLabels"
import { Histogram } from "./assistParts"
import { exposureLine, focalLine, formatCapture } from "./exif"
import type { BurstPosition } from "./model"

const percent = (value: number, locale: string) =>
  `${formatNumber(Math.round(value * 1000) / 10, locale)}%`

/** One small round-ish chip of the on-photo cluster. */
function Chip({
  label,
  tone = "neutral",
  children,
}: {
  label: string
  tone?: "neutral" | "warning" | "primary"
  children: ReactNode
}) {
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className={cn(
        "inline-flex h-7 min-w-7 items-center justify-center gap-1 rounded-full px-2 text-xs leading-none font-medium tabular-nums [&_svg]:size-3.5",
        tone === "warning" && "bg-warning text-viewer",
        tone === "primary" && "bg-primary text-primary-foreground",
        tone === "neutral" && "bg-viewer/75 text-viewer-foreground backdrop-blur-sm"
      )}
    >
      {children}
    </span>
  )
}

/**
 * The compact cluster on the photo (1.7.0): what matters at a glance, as icons — the crown on the
 * suggested best of a burst, each flag, the sharpness number, the eyes, RAW+JPG and the burst
 * position. One tap target that opens the details.
 */
export function InfoChips({
  shot,
  analysis,
  flags,
  best,
  burst,
  sensitivity,
  onOpen,
  className,
}: {
  shot: CullShot
  analysis?: CullAnalysis
  flags?: readonly ShotFlag[]
  best: boolean
  burst?: BurstPosition
  sensitivity: Sensitivity
  onOpen: () => void
  className?: string
}) {
  const { t } = useTranslation()
  const eyes = analysis ? eyesSummary(analysis, sensitivity) : undefined
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={t("cull.details.open")}
      className={cn(
        "pointer-events-auto flex max-w-full flex-wrap items-center gap-1 rounded-full text-left focus-visible:outline-2 focus-visible:outline-ring",
        className
      )}
    >
      {best ? (
        <Chip label={t("cull.assist.best")} tone="primary">
          <CrownSimpleIcon weight="fill" />
        </Chip>
      ) : null}
      {flags?.map((flag) => {
        const Icon = FLAG_ICONS[flag]
        return (
          <Chip key={flag} label={t(`cull.assist.flag.${flag}`)} tone="warning">
            <Icon weight="bold" />
          </Chip>
        )
      })}
      {analysis ? (
        <Chip label={t("cull.assist.sharpness", { value: sharpnessScore(analysis) })}>
          <CrosshairSimpleIcon weight="bold" />
          {sharpnessScore(analysis)}
        </Chip>
      ) : null}
      {eyes && eyes.verdict === "open" ? (
        <Chip label={t("cull.chips.eyesOpen", { count: eyes.prominent.length })}>
          <EyeIcon weight="bold" />
          {eyes.prominent.length > 1 ? eyes.prominent.length : null}
        </Chip>
      ) : null}
      <Chip label={t(`cull.kind.${shot.kind}`)}>
        <span className="font-mono">{t(`cull.kind.${shot.kind}`)}</span>
      </Chip>
      {burst ? (
        <Chip label={t("cull.burst", { index: burst.index + 1, size: burst.size })}>
          <StackIcon weight="fill" />
          {burst.index + 1}/{burst.size}
        </Chip>
      ) : null}
    </button>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2 border-b px-4 py-3 last:border-b-0">
      <h3 className="eyebrow text-muted-foreground">{title}</h3>
      {children}
    </section>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-h-6 items-baseline justify-between gap-3 text-[0.9375rem]">
      <span className="shrink-0 text-sm text-muted-foreground">{label}</span>
      <span className="min-w-0 text-right [overflow-wrap:anywhere]">{children}</span>
    </div>
  )
}

const EYE_KEYS: Record<EyeState, string> = {
  open: "cull.assist.eyesOpen",
  closed: "cull.assist.eyesClosed",
  unknown: "cull.assist.eyesUnknown",
}

/** At most this many faces are listed one by one (a group photo lists the rest as a count). */
const FACE_ROWS = 6

/**
 * Everything about the shot on screen (the details sheet on phones, the side panel on wider
 * screens): the suggestion with Accept, the analysis (sharpness, every prominent face with its
 * eyes and Zoom to face, exposure with the histogram and what triggered a flag, flags, what was
 * measured), then the camera data. Eyes come from {@link eyesSummary}, the same reading the flag
 * uses, so the two can never disagree.
 */
export function ShotDetails({
  shot,
  metadata,
  analysis,
  flags,
  best,
  burst,
  suggestion,
  sensitivity,
  facesAvailable,
  image,
  zoom,
  onJumpToFace,
  onAccept,
  onAcceptBurst,
}: {
  shot: CullShot
  metadata?: CullMetadata
  analysis?: CullAnalysis
  flags?: readonly ShotFlag[]
  best: boolean
  burst?: BurstPosition
  suggestion?: Suggestion
  sensitivity: Sensitivity
  facesAvailable: boolean
  image?: CullImageResult
  zoom?: CullZoomResult
  onJumpToFace: (face: CullFace) => void
  onAccept?: () => void
  onAcceptBurst?: () => void
}) {
  const { t, i18n } = useTranslation()
  const locale = i18n.language
  const reasons = useReasonText()
  const capture = formatCapture(metadata?.captureTime, locale)
  const camera =
    [
      metadata?.make && !metadata.model?.toUpperCase().startsWith(metadata.make.toUpperCase())
        ? metadata.make
        : undefined,
      metadata?.model,
    ]
      .filter(Boolean)
      .join(" ") || undefined

  return (
    <div className="flex flex-col text-foreground">
      {suggestion ? (
        <Section title={t("cull.details.suggestion")}>
          <div className="flex items-start gap-3">
            <SparkleIcon
              weight="fill"
              className={cn(
                "mt-0.5 size-5 shrink-0",
                suggestion.action === "keep" ? "text-primary" : "text-destructive"
              )}
            />
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <span className="text-[0.9375rem] font-medium">
                {t(
                  suggestion.action === "keep"
                    ? "cull.assist.suggestKeep"
                    : "cull.assist.suggestReject"
                )}
              </span>
              <span className="text-[0.8125rem] text-muted-foreground">
                {reasons(suggestion.reasons)}
              </span>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {onAccept ? (
              <Button
                variant={suggestion.action === "keep" ? "default" : "destructive"}
                onClick={onAccept}
              >
                {t("cull.assist.accept")}
              </Button>
            ) : null}
            {onAcceptBurst ? (
              <Button variant="outline" onClick={onAcceptBurst}>
                {t("cull.assist.acceptBurst")}
              </Button>
            ) : null}
          </div>
        </Section>
      ) : null}

      <Section title={t("cull.details.analysis")}>
        {!analysis ? (
          <span className="text-[0.9375rem] text-muted-foreground">
            {t("cull.assist.notAnalysed")}
          </span>
        ) : (
          <AnalysisRows
            analysis={analysis}
            flags={flags}
            best={best}
            sensitivity={sensitivity}
            facesAvailable={facesAvailable}
            onJumpToFace={onJumpToFace}
          />
        )}
      </Section>

      <Section title={t("cull.details.camera")}>
        <span className="text-[0.9375rem] font-medium [overflow-wrap:anywhere]">{shot.stem}</span>
        {capture ? (
          <Row label={t("cull.details.taken")}>
            <span className="tabular-nums">{capture.time}</span>
            <span className="text-muted-foreground"> · {capture.date}</span>
          </Row>
        ) : null}
        {exposureLine(metadata, locale) ? (
          <Row label={t("cull.details.exposure")}>{exposureLine(metadata, locale)}</Row>
        ) : null}
        {focalLine(metadata, locale, t) ? (
          <Row label={t("cull.details.focal")}>{focalLine(metadata, locale, t)}</Row>
        ) : null}
        {metadata?.lens ? <Row label={t("cull.details.lens")}>{metadata.lens}</Row> : null}
        {camera ? <Row label={t("cull.details.camera")}>{camera}</Row> : null}
        <Row label={t("cull.details.file")}>
          {metadata?.width && metadata.height
            ? `${metadata.width} × ${metadata.height} · ${t(`cull.kind.${shot.kind}`)}`
            : t(`cull.kind.${shot.kind}`)}
        </Row>
        {burst ? (
          <Row label={t("cull.details.burst")}>
            {t("cull.details.burstValue", { index: burst.index + 1, size: burst.size })}
          </Row>
        ) : null}
        {image ? (
          <Row label={t("cull.details.shown")}>
            {zoom ? t(`cull.source.zoom.${zoom.source}`) : t(`cull.source.screen.${image.source}`)}
          </Row>
        ) : null}
        {!metadata ? (
          <span className="text-[0.8125rem] text-muted-foreground">{t("cull.exif.none")}</span>
        ) : null}
      </Section>
    </div>
  )
}

function AnalysisRows({
  analysis,
  flags,
  best,
  sensitivity,
  facesAvailable,
  onJumpToFace,
}: {
  analysis: CullAnalysis
  flags?: readonly ShotFlag[]
  best: boolean
  sensitivity: Sensitivity
  facesAvailable: boolean
  onJumpToFace: (face: CullFace) => void
}) {
  const { t, i18n } = useTranslation()
  const locale = i18n.language
  const eyes = eyesSummary(analysis, sensitivity)
  const e = analysis.exposure
  const exposure = exposureOf(e)
  const exposureText =
    exposure.verdict === "ok"
      ? t("cull.details.exposureOk")
      : t(`cull.details.${exposure.verdict}.${exposure.reason}`, {
          value: percent(
            exposure.reason === "blownArea"
              ? e.blownArea
              : exposure.reason === "face"
                ? e.faceHighlights
                : exposure.reason === "shadows"
                  ? e.shadows
                  : e.mean,
            locale
          ),
        })
  return (
    <>
      {flags?.length || best ? (
        <div className="flex flex-wrap gap-1.5">
          {best ? (
            <span className="inline-flex h-7 items-center gap-1 rounded-full bg-primary px-2.5 text-xs font-medium text-primary-foreground [&_svg]:size-3.5">
              <CrownSimpleIcon weight="fill" />
              {t("cull.assist.best")}
            </span>
          ) : null}
          {flags?.map((flag) => {
            const Icon = FLAG_ICONS[flag]
            return (
              <span
                key={flag}
                className="inline-flex h-7 items-center gap-1 rounded-full bg-warning px-2.5 text-xs font-medium text-viewer [&_svg]:size-3.5"
              >
                <Icon weight="bold" />
                {t(`cull.assist.flag.${flag}`)}
              </span>
            )
          })}
        </div>
      ) : null}
      <Row label={t("cull.details.sharpness")}>
        <span className="font-medium tabular-nums">{sharpnessScore(analysis)}</span>
        <span className="text-muted-foreground">
          {" "}
          ·{" "}
          {t(
            analysis.sharpness.subjectKind === "face"
              ? "cull.assist.subjectFace"
              : "cull.assist.subjectTile"
          )}
        </span>
      </Row>
      {!analysis.facesDetected || !facesAvailable ? (
        <Row label={t("cull.details.faces")}>{t("cull.assist.facesUnavailable")}</Row>
      ) : eyes.prominent.length === 0 ? (
        <Row label={t("cull.details.faces")}>{t("cull.assist.noFaces")}</Row>
      ) : (
        <div className="flex flex-col gap-1">
          <Row label={t("cull.details.faces")}>
            {t("cull.assist.faces", { count: eyes.prominent.length })} · {t(EYE_KEYS[eyes.verdict])}
          </Row>
          <ul className="flex flex-col">
            {eyes.prominent.slice(0, FACE_ROWS).map((face, i) => (
              <li key={i} className="flex min-h-11 items-center gap-2 text-[0.9375rem]">
                <span className="min-w-0 flex-1">
                  {t("cull.details.face", { index: i + 1 })}
                  <span
                    className={cn(
                      "text-muted-foreground",
                      eyes.states[i] === "closed" && "font-medium text-warning"
                    )}
                  >
                    {" "}
                    · {t(EYE_KEYS[eyes.states[i]])}
                  </span>
                </span>
                <Button variant="ghost" onClick={() => onJumpToFace(face)}>
                  <UserFocusIcon data-icon="inline-start" />
                  {t("cull.assist.jumpToFace")}
                </Button>
              </li>
            ))}
          </ul>
          {eyes.prominent.length > FACE_ROWS ? (
            <span className="text-[0.8125rem] text-muted-foreground">
              {t("cull.details.moreFaces", { count: eyes.prominent.length - FACE_ROWS })}
            </span>
          ) : null}
        </div>
      )}
      <Row label={t("cull.details.exposureLabel")}>{exposureText}</Row>
      <div className="flex flex-col gap-1">
        <Histogram bins={e.histogram} className="text-foreground" />
        <span className="flex flex-wrap justify-between gap-x-3 text-xs text-muted-foreground tabular-nums">
          <span>{t("cull.assist.shadows", { value: percent(e.shadows, locale) })}</span>
          <span>
            {t("cull.details.highlights", {
              value: percent(e.highlights, locale),
              area: percent(e.blownArea, locale),
            })}
          </span>
        </span>
      </div>
      <span className="text-xs text-muted-foreground">
        {t("cull.assist.measuredOn", {
          source: t(`cull.source.screen.${analysis.source}`),
          size: `${analysis.width} × ${analysis.height}`,
        })}
      </span>
    </>
  )
}
