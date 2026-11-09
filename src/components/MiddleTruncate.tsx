import { cn } from "@/lib/utils"

/**
 * Truncates in the middle so the end (extension, episode number, "final-v2") stays visible:
 * `a-very-long-file-na…REALLY-final.png`. The full text is in `title`.
 */
export function MiddleTruncate({
  text,
  tail = 14,
  className,
}: {
  text: string
  /** Characters kept at the end. */
  tail?: number
  className?: string
}) {
  const chars = Array.from(text)
  if (chars.length <= tail + 6) {
    return (
      <span className={cn("block truncate", className)} title={text}>
        {text}
      </span>
    )
  }
  const head = chars.slice(0, -tail).join("")
  const end = chars.slice(-tail).join("")
  return (
    <span className={cn("flex min-w-0", className)} title={text}>
      <span className="min-w-0 shrink-[999] truncate">{head}</span>
      <span className="shrink-0 whitespace-pre">{end}</span>
    </span>
  )
}
