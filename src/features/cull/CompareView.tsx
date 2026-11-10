import {
  ArrowLeftIcon,
  CaretLeftIcon,
  CaretRightIcon,
  CrownSimpleIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { useBackHandler } from "@/hooks/useAndroidBackButton"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { CullFace, CullSharpnessScore, CullShot } from "@/plugins/smb/definitions"
import { rankBurst, sharpnessScore } from "./assist"
import { FLAG_ICONS } from "./assistLabels"
import { DecisionBar, Filmstrip } from "./CullViewer"
import { decisionOf, withFlag } from "./model"
import { formatCapture } from "./exif"
import { FlagMark, KindBadge } from "./parts"
import type { CullAssist } from "./useCullAssist"
import type { CullFolder } from "./useCullFolder"
import { ViewLink } from "./viewLink"
import { ZoomableImage } from "./ZoomableImage"

/**
 * Two shots side by side (stacked on a portrait phone) with synchronized zoom and pan: pinch or
 * double tap either one and both follow, so the same eye or the same edge is checked at 100% in
 * both. Each pane can be picked or rejected; "Keep" picks one and rejects the other (choosing
 * within a burst). The filmstrip and the arrows change the second shot. Focus scores (variance of
 * the Laplacian, ADR 0006) mark the sharper frame.
 */
export function CompareView({
  folder,
  assist,
  order,
  a,
  b,
  longSide,
  focusFace,
  onKeep,
  onB,
  onClose,
  onExit,
}: {
  folder: CullFolder
  assist: CullAssist
  order: CullShot[]
  a: CullShot
  b: CullShot
  longSide: number
  /** Open zoomed on this face of `a` (both panes follow: synchronized zoom). */
  focusFace?: CullFace
  /** "Keep" on a pane; by default picks it and rejects the other (burst mode: Keep this one). */
  onKeep?: (winner: CullShot, loser: CullShot) => void
  onB: (shotId: string) => void
  onClose: () => void
  onExit: () => void
}) {
  const { t, i18n } = useTranslation()
  const link = useMemo(() => new ViewLink(), [])
  const [scores, setScores] = useState<Map<string, CullSharpnessScore>>(new Map())
  const aIndex = order.findIndex((s) => s.id === a.id)
  // Start on the main face once pane a has laid out (the link carries the view to pane b).
  const [focus, setFocus] = useState<(CullFace & { seq: number; shotId: string }) | undefined>()
  useEffect(() => {
    if (!focusFace) return
    const timer = window.setTimeout(() => setFocus({ ...focusFace, seq: 1, shotId: a.id }), 350)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const bIndex = order.findIndex((s) => s.id === b.id)

  useBackHandler(() => {
    onClose()
    return true
  })

  // Shots without an analysis yet fall back to the ADR 0006 focus score.
  useEffect(() => {
    const missing = [a.id, b.id].filter((id) => !scores.has(id) && !assist.analyses.has(id))
    if (missing.length === 0) return
    let cancelled = false
    void folder
      .call((ref) => HoardSmb.cullSharpness({ ...ref, shotIds: missing }))
      .then(
        (result) => {
          if (cancelled) return
          setScores((current) => {
            const next = new Map(current)
            for (const score of result.scores) next.set(score.id, score)
            return next
          })
        },
        () => {}
      )
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a.id, b.id, folder.key])

  // With both analysed: the better-ranked frame (sharpness on the face or best area, eyes,
  // exposure: ADR 0009); else the higher focus score.
  const ranked =
    assist.analyses.has(a.id) && assist.analyses.has(b.id)
      ? rankBurst([a.id, b.id], assist.analyses, assist.settings)
      : null
  const best = (id: string) => scores.get(id)?.best
  const sharper = ranked
    ? ranked[0].score !== ranked[1].score
      ? ranked[0].shotId
      : undefined
    : best(a.id) !== undefined && best(b.id) !== undefined && best(a.id) !== best(b.id)
      ? best(a.id)! > best(b.id)!
        ? a.id
        : b.id
      : undefined
  const scoreLabel = (id: string) => {
    const analysis = assist.analyses.get(id)
    if (analysis) return t("cull.compare.sharpness", { value: sharpnessScore(analysis) })
    const focus = scores.get(id)?.best
    return focus !== undefined ? t("cull.compare.focus", { value: Math.round(focus) }) : undefined
  }

  /** Pick one, reject the other: one undo step. */
  const keep = (winner: CullShot, loser: CullShot) =>
    onKeep ? onKeep(winner, loser) : keepPair(winner, loser)
  const keepPair = (winner: CullShot, loser: CullShot) =>
    folder.decide([winner.id, loser.id], (d, id) =>
      withFlag(d, id === winner.id ? "pick" : "reject")
    )

  const moveB = (step: number) => {
    let next = bIndex + step
    if (next === aIndex) next += step
    const shot = order[next]
    if (shot) onB(shot.id)
  }

  return (
    <div className="flex size-full flex-col">
      <div className="flex h-14 items-center gap-1 bg-viewer/70 px-2 pt-[env(safe-area-inset-top)] backdrop-blur-sm">
        <Button variant="ghost" size="icon" aria-label={t("cull.compare.close")} onClick={onClose}>
          <ArrowLeftIcon />
        </Button>
        <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-medium">
          {t("cull.compare.title")}
        </span>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("cull.compare.previous")}
          disabled={bIndex <= 0 || (bIndex === 1 && aIndex === 0)}
          onClick={() => moveB(-1)}
        >
          <CaretLeftIcon />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("cull.compare.next")}
          disabled={
            bIndex >= order.length - 1 ||
            (bIndex === order.length - 2 && aIndex === order.length - 1)
          }
          onClick={() => moveB(1)}
        >
          <CaretRightIcon />
        </Button>
        <Button variant="ghost" size="icon" aria-label={t("common.close")} onClick={onExit}>
          <XIcon />
        </Button>
      </div>
      <div className="grid min-h-0 flex-1 grid-rows-2 gap-px bg-viewer-foreground/10 landscape:grid-cols-2 landscape:grid-rows-1">
        {[
          { shot: a, other: b, pane: "a" },
          { shot: b, other: a, pane: "b" },
        ].map(({ shot, other, pane }) => {
          const decision = decisionOf(folder.decisions, shot.id)
          const label = scoreLabel(shot.id)
          const flags = assist.flags.get(shot.id)
          const capture = formatCapture(folder.metadata.get(shot.id)?.captureTime, i18n.language)
          return (
            <section
              key={pane}
              className="relative flex min-h-0 flex-col bg-viewer"
              aria-label={shot.stem}
            >
              <div className="relative min-h-0 flex-1">
                <ZoomableImage
                  key={shot.id}
                  folder={folder}
                  shot={shot}
                  metadata={folder.metadata.get(shot.id)}
                  longSide={longSide}
                  active
                  link={link}
                  paneId={pane}
                  focusRequest={pane === "a" && focus?.shotId === shot.id ? focus : undefined}
                />
                <div className="pointer-events-none absolute top-2 left-2 flex max-w-[calc(100%-1rem)] flex-wrap items-center gap-1">
                  <span className="rounded-md bg-viewer/75 px-1.5 py-0.5 text-[0.8125rem] font-medium">
                    {shot.stem}
                  </span>
                  {capture ? (
                    <span className="rounded-md bg-viewer/75 px-1.5 py-0.5 font-mono text-xs tabular-nums">
                      {capture.time}
                    </span>
                  ) : null}
                  <KindBadge shot={shot} />
                  <FlagMark decision={decision} />
                  {label ? (
                    <span
                      className={cn(
                        "inline-flex h-5 items-center gap-1 rounded-md px-1.5 text-xs tabular-nums",
                        sharper === shot.id ? "bg-primary text-primary-foreground" : "bg-viewer/75"
                      )}
                      title={t(
                        assist.analyses.has(shot.id)
                          ? "cull.compare.sharpnessHelp"
                          : "cull.compare.focusHelp"
                      )}
                    >
                      {sharper === shot.id ? (
                        <CrownSimpleIcon weight="fill" className="size-3" />
                      ) : null}
                      {label}
                    </span>
                  ) : null}
                  {flags?.map((flag) => {
                    const Icon = FLAG_ICONS[flag]
                    return (
                      <span
                        key={flag}
                        className="inline-flex h-5 items-center gap-1 rounded-md bg-warning px-1.5 text-xs text-viewer [&_svg]:size-3"
                      >
                        <Icon weight="bold" />
                        {t(`cull.assist.flag.${flag}`)}
                      </span>
                    )
                  })}
                </div>
              </div>
              <div className="flex items-center gap-1 bg-viewer/70 pr-2">
                <div className="min-w-0 flex-1">
                  <DecisionBar
                    compact
                    decision={decision}
                    onReject={() =>
                      folder.decide([shot.id], (d) =>
                        withFlag(d, d.flag === "reject" ? null : "reject")
                      )
                    }
                    onPick={() =>
                      folder.decide([shot.id], (d) =>
                        withFlag(d, d.flag === "pick" ? null : "pick")
                      )
                    }
                  />
                </div>
                <Button variant="secondary" size="sm" onClick={() => keep(shot, other)}>
                  {t("cull.compare.keep")}
                </Button>
              </div>
            </section>
          )
        })}
      </div>
      <div className="bg-viewer/70 pb-[env(safe-area-inset-bottom)]">
        <Filmstrip
          folder={folder}
          order={order}
          index={aIndex}
          highlight={b.id}
          onSelect={(i) => {
            const shot = order[i]
            if (shot && shot.id !== a.id) onB(shot.id)
          }}
        />
      </div>
    </div>
  )
}
