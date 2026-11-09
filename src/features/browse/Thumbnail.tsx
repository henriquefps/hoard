import { PlayIcon } from "@phosphor-icons/react"
import { useEffect, useRef, useState } from "react"
import { FileIcon } from "@/components/FileIcon"
import type { MachineWithAddresses } from "@/db/types"
import { useRouteEngine } from "@/features/route/RouteProvider"
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
  machine,
  share,
  entry,
  kind,
  className,
}: {
  machine: MachineWithAddresses
  share: string
  entry: SmbEntry
  kind: FileKind
  className?: string
}) {
  const engine = useRouteEngine()
  const wanted = hasThumbnail(entry)
  const key = `${machine.id}|${share}|${entry.path}|${entry.modifiedAt ?? ""}|${SIZE}`
  const [loaded, setLoaded] = useState<{ key: string; src: string | undefined }>()
  const src = loaded?.key === key ? loaded.src : wanted ? queue.peek(key) : undefined
  const ref = useRef<HTMLSpanElement>(null)
  const machineRef = useRef(machine)
  useEffect(() => {
    machineRef.current = machine
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
              engine
                .withSession(machineRef.current, (sessionId) =>
                  HoardSmb.thumbnail({ sessionId, share, path: entry.path, maxSize: SIZE })
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
  }, [engine, key, share, entry.path, wanted])

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
