import { CheckIcon, StackIcon, StarIcon, XIcon } from "@phosphor-icons/react"
import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { cn } from "@/lib/utils"
import type { CullShot } from "@/plugins/smb/definitions"
import {
  cullTimings,
  loadThumbnail,
  peekThumbnail,
  reloadThumbnail,
  shotKey,
  thumbKey,
  thumbnailFailed,
  thumbnails,
} from "./images"
import type { BurstPosition, CullDecision } from "./model"
import type { CullFolder } from "./useCullFolder"

/**
 * A shot's thumbnail (the RAW's preview or a small JPEG decode), loaded while mounted. When the
 * `<img>` cannot show its URL (the cached file is gone), it asks native once more; a second failure
 * leaves the tile in its failed state (muted), never the browser's broken-image icon.
 */
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
  const [loaded, setLoaded] = useState<{ key: string; src?: string; retried?: boolean }>()
  const mine = loaded?.key === key ? loaded : undefined
  const retried = mine?.retried ?? false
  const failed = thumbnails.hasFailed(thumbKey(folder, shot))
  const src = failed ? undefined : (mine?.src ?? peekThumbnail(folder, shot))
  useEffect(() => {
    if (peekThumbnail(folder, shot) || thumbnails.hasFailed(thumbKey(folder, shot))) return
    const controller = new AbortController()
    const load = retried ? reloadThumbnail : loadThumbnail
    load(folder, shot, controller.signal).then(
      (value) => !controller.signal.aborted && setLoaded({ key, src: value, retried }),
      () => !controller.signal.aborted && setLoaded({ key, retried })
    )
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, retried])
  const onError = () => {
    if (retried) {
      thumbnailFailed(folder, shot)
      setLoaded({ key, retried })
      return
    }
    cullTimings.record("thumb:error", 0)
    thumbnails.forget(thumbKey(folder, shot))
    setLoaded({ key, retried: true })
  }
  return src ? (
    <img
      key={src}
      src={src}
      alt=""
      draggable={false}
      decoding="async"
      onError={onError}
      className={cn("object-cover", className)}
    />
  ) : (
    <span data-thumb={failed ? "failed" : "loading"} className={cn("block bg-muted", className)} />
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
