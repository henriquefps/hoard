import { CheckIcon, StackIcon, StarIcon, XIcon } from "@phosphor-icons/react"
import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { cn } from "@/lib/utils"
import type { CullMetadata, CullShot } from "@/plugins/smb/definitions"
import { loadThumbnail, peekThumbnail, thumbnails, shotKey } from "./images"
import { exposureLine, focalLine, formatCapture } from "./exif"
import type { BurstPosition, CullDecision } from "./model"
import type { CullFolder } from "./useCullFolder"

/** A shot's thumbnail (the RAW's preview or a small JPEG decode), loaded while mounted. */
export function ShotThumb({
  folder,
  shot,
  className,
}: {
  folder: CullFolder
  shot: CullShot
  className?: string
}) {
  const key = shotKey(folder, shot)
  const [loaded, setLoaded] = useState<{ key: string; src?: string }>()
  const src = loaded?.key === key ? loaded.src : peekThumbnail(folder, shot)
  useEffect(() => {
    if (peekThumbnail(folder, shot) || thumbnails.hasFailed(`${key}|thumb`)) return
    const controller = new AbortController()
    loadThumbnail(folder, shot, controller.signal).then(
      (value) => !controller.signal.aborted && setLoaded({ key, src: value }),
      () => {}
    )
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return src ? (
    <img
      src={src}
      alt=""
      draggable={false}
      decoding="async"
      className={cn("object-cover", className)}
    />
  ) : (
    <span className={cn("block bg-muted", className)} />
  )
}

/** RAW+JPG / RAW / JPG. */
export function KindBadge({ shot, className }: { shot: CullShot; className?: string }) {
  const { t } = useTranslation()
  return (
    <span
      className={cn(
        "inline-flex h-5 items-center rounded-md bg-viewer/75 px-1.5 font-mono text-xs leading-none font-medium text-viewer-foreground",
        className
      )}
    >
      {t(`cull.kind.${shot.kind}`)}
    </span>
  )
}

/** The pick (check, primary) or reject (cross, destructive) mark of a shot. */
export function FlagMark({ decision, className }: { decision: CullDecision; className?: string }) {
  const { t } = useTranslation()
  if (!decision.flag) return null
  const pick = decision.flag === "pick"
  return (
    <span
      role="img"
      aria-label={t(pick ? "cull.flag.picked" : "cull.flag.rejected")}
      className={cn(
        "inline-flex size-6 items-center justify-center rounded-full [&_svg]:size-3.5",
        pick ? "bg-primary text-primary-foreground" : "bg-destructive text-viewer-foreground",
        className
      )}
    >
      {pick ? <CheckIcon weight="bold" /> : <XIcon weight="bold" />}
    </span>
  )
}

/** Read-only stars (grid cells, filmstrip). */
export function StarsMark({ rating, className }: { rating: number; className?: string }) {
  const { t } = useTranslation()
  if (rating <= 0) return null
  return (
    <span
      role="img"
      aria-label={t("cull.stars", { count: rating })}
      className={cn(
        "inline-flex h-5 items-center gap-0.5 rounded-md bg-viewer/75 px-1 text-xs leading-none text-viewer-foreground [&_svg]:size-3",
        className
      )}
    >
      <StarIcon weight="fill" />
      <span className="tabular-nums">{rating}</span>
    </span>
  )
}

/** "2/5" on a frame of a burst. */
export function BurstMark({
  position,
  className,
}: {
  position: BurstPosition
  className?: string
}) {
  const { t } = useTranslation()
  return (
    <span
      title={t("cull.burst", { index: position.index + 1, size: position.size })}
      className={cn(
        "inline-flex h-5 items-center gap-1 rounded-md bg-viewer/75 px-1.5 text-xs leading-none text-viewer-foreground tabular-nums [&_svg]:size-3",
        className
      )}
    >
      <StackIcon weight="fill" />
      {position.index + 1}/{position.size}
    </span>
  )
}

/** The EXIF overlay of the viewer: time, exposure, focal length, lens, camera, size, burst. */
export function ExifPanel({
  shot,
  metadata,
  burst,
  className,
}: {
  shot: CullShot
  metadata?: CullMetadata
  burst?: BurstPosition
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const capture = formatCapture(metadata?.captureTime, i18n.language)
  const lines = [
    capture ? (
      <span key="time">
        <span className="font-medium tabular-nums">{capture.time}</span>
        <span className="text-viewer-foreground/70"> · {capture.date}</span>
      </span>
    ) : null,
    exposureLine(metadata, i18n.language),
    focalLine(metadata, i18n.language, t),
    metadata?.lens,
    [
      metadata?.make && !metadata.model?.toUpperCase().startsWith(metadata.make.toUpperCase())
        ? metadata.make
        : undefined,
      metadata?.model,
    ]
      .filter(Boolean)
      .join(" ") || undefined,
    metadata?.width && metadata.height
      ? `${metadata.width} × ${metadata.height} · ${t(`cull.kind.${shot.kind}`)}`
      : t(`cull.kind.${shot.kind}`),
    burst ? t("cull.burst", { index: burst.index + 1, size: burst.size }) : undefined,
  ].filter(Boolean)
  return (
    <div
      className={cn(
        "flex max-w-[min(22rem,calc(100vw-2rem))] flex-col gap-0.5 rounded-xl bg-viewer/75 px-3 py-2 text-[0.8125rem] leading-5 text-viewer-foreground backdrop-blur-sm",
        className
      )}
    >
      <span className="truncate text-sm font-semibold">{shot.stem}</span>
      {lines.map((line, i) => (
        <span key={i} className="truncate">
          {line}
        </span>
      ))}
      {!metadata ? <span className="text-viewer-foreground/70">{t("cull.exif.none")}</span> : null}
    </div>
  )
}
