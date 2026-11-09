import { PlayIcon } from "@phosphor-icons/react"
import { useEffect, useRef, useState } from "react"
import { FileIcon } from "@/components/FileIcon"
import type { BrowseLocation } from "@/features/locations/useBrowseLocation"
import { type FileKind, hasThumbnail } from "@/lib/files"
import { TaskQueue } from "@/lib/taskQueue"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { SmbEntry } from "@/plugins/smb/definitions"

/** Thumbnails for the whole app: 4 at a time, newest request first, results cached. */
const queue = new TaskQueue<string>(4, 800)
const SIZE = 96

/**
 * A 40px file tile that turns into the image's thumbnail once the row is on screen (Intersection
 * Observer, then the shared queue). Falls back to the kind icon while loading or when it fails.
 */
export function Thumbnail({
  location,
  entry,
  kind,
  className,
}: {
  location: BrowseLocation
  entry: SmbEntry
  kind: FileKind
  className?: string
}) {
  const wanted = hasThumbnail(entry)
  const key = `${location.cacheKey}|${entry.path}|${entry.modifiedAt ?? ""}|${entry.size}|${SIZE}`
  const [loaded, setLoaded] = useState<{ key: string; src: string | undefined }>()
  const src = loaded?.key === key ? loaded.src : wanted ? queue.peek(key) : undefined
  const ref = useRef<HTMLSpanElement>(null)
  const locationRef = useRef(location)
  useEffect(() => {
    locationRef.current = location
  })

  useEffect(() => {
    if (!wanted || queue.hasFailed(key) || queue.peek(key)) return
    const element = ref.current
    if (!element) return
    const controller = new AbortController()
    let started = false
    const observer = new IntersectionObserver(
      ([entryState]) => {
        if (!entryState.isIntersecting || started) return
        started = true
        observer.disconnect()
        queue
          .get(
            key,
            () =>
              locationRef.current
                .run((loc) =>
                  HoardSmb.thumbnail({ location: loc, path: entry.path, maxSize: SIZE })
                )
                .then((result) => result.src),
            controller.signal
          )
          .then(
            (value) => !controller.signal.aborted && setLoaded({ key, src: value }),
            () => {}
          )
      },
      { rootMargin: "200px 0px" }
    )
    observer.observe(element)
    return () => {
      observer.disconnect()
      controller.abort()
    }
  }, [key, entry.path, wanted])

  return (
    <span ref={ref} className={cn("relative inline-flex size-10 shrink-0", className)}>
      {src ? (
        <img
          src={src}
          alt=""
          draggable={false}
          className="size-10 rounded-lg bg-muted object-cover"
          onError={() => setLoaded({ key, src: undefined })}
        />
      ) : (
        <FileIcon kind={kind} />
      )}
      {src && kind === "video" ? (
        <span
          aria-hidden
          className="absolute right-0.5 bottom-0.5 inline-flex size-4 items-center justify-center rounded-full bg-foreground/70 text-background [&_svg]:size-2.5"
        >
          <PlayIcon weight="fill" />
        </span>
      ) : null}
    </span>
  )
}
