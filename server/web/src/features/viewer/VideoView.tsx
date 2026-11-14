import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { Spinner } from "@/components/ui/spinner"
import { proxyUrl } from "@/lib/api"

type State = "checking" | "pending" | "ready" | "unavailable"

/**
 * A video (or a motion photo's video) from its H.264 proxy (ADR 0015; HTTP ranges). The server may still be making it
 * (202 with Retry-After): ask again until it is there.
 */
export function VideoView({ itemId, poster, loop }: { itemId: string; poster: string; loop?: boolean }) {
  const { t } = useTranslation()
  const [state, setState] = useState<State>("checking")
  useEffect(() => {
    let live = true
    let timer: number | undefined
    const check = async () => {
      try {
        const res = await fetch(proxyUrl(itemId), { method: "HEAD", credentials: "same-origin" })
        if (!live) return
        if (res.status === 200) setState("ready")
        else if (res.status === 202) {
          setState("pending")
          const after = Number(res.headers.get("Retry-After")) || 5
          timer = window.setTimeout(check, Math.min(after, 15) * 1000)
        } else setState("unavailable")
      } catch {
        if (live) timer = window.setTimeout(check, 5000)
      }
    }
    void check()
    return () => {
      live = false
      window.clearTimeout(timer)
    }
  }, [itemId])

  if (state === "ready") {
    return (
      <video
        key={itemId}
        className="size-full bg-viewer object-contain"
        src={proxyUrl(itemId)}
        poster={poster}
        controls
        autoPlay
        loop={loop}
        muted={loop}
        playsInline
        data-testid="video"
      />
    )
  }
  return (
    <div className="relative size-full">
      <img src={poster} alt="" className="absolute inset-0 size-full object-contain opacity-60" />
      <div className="absolute inset-0 grid place-items-center">
        <p role="status" className="flex items-center gap-2 rounded-md bg-viewer/80 px-3 py-2 text-[0.9375rem]">
          {state === "unavailable" ? (
            t("viewer.videoUnavailable")
          ) : (
            <>
              <Spinner className="size-4" />
              {state === "pending" ? t("viewer.videoPreparing") : t("common.loading")}
            </>
          )}
        </p>
      </div>
    </div>
  )
}
