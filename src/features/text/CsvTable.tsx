import { useMemo, type Ref } from "react"
import { useTranslation } from "react-i18next"
import { formatNumber } from "@/lib/format"
import { parseDelimited, RENDER_LIMITS } from "@/lib/text/textFiles"
import { cn } from "@/lib/utils"

/** CSV/TSV as a table (first row as header), capped at `RENDER_LIMITS.csvRows` rows. */
export function CsvTable({
  ref,
  text,
  delimiter,
  fontSize,
  hidden,
}: {
  ref?: Ref<HTMLDivElement>
  text: string
  delimiter: string
  fontSize: number
  hidden?: boolean
}) {
  const { t, i18n } = useTranslation()
  const rows = useMemo(
    () => parseDelimited(text, delimiter, RENDER_LIMITS.csvRows + 1),
    [text, delimiter]
  )
  const [header, ...body] = rows
  const shown = body.slice(0, RENDER_LIMITS.csvRows - 1)
  const columns = Math.max(0, ...rows.map((r) => r.length))
  if (!header) {
    return (
      <p className={cn("p-6 text-[0.9375rem] text-muted-foreground", hidden && "hidden")}>
        {t("text.emptyFile")}
      </p>
    )
  }
  return (
    <div
      ref={ref}
      className={cn("min-h-0 flex-1 overflow-auto overscroll-contain", hidden && "hidden")}
      data-find-root
      style={{ fontSize }}
    >
      <table className="w-max min-w-full border-collapse text-left tabular-nums">
        <thead className="sticky top-0 z-10 bg-card">
          <tr>
            <th className="border-r border-b px-2 py-2 text-right font-mono text-xs font-normal text-muted-foreground">
              #
            </th>
            {Array.from({ length: columns }, (_, i) => (
              <th
                key={i}
                scope="col"
                className="max-w-80 border-r border-b px-3 py-2 font-semibold [overflow-wrap:anywhere] whitespace-pre-wrap"
              >
                {header[i] ?? ""}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {shown.map((row, r) => (
            <tr key={r} className="hover:bg-muted/50">
              <td className="border-r border-b px-2 py-1.5 text-right font-mono text-xs text-muted-foreground">
                {r + 2}
              </td>
              {Array.from({ length: columns }, (_, c) => (
                <td
                  key={c}
                  className="max-w-80 border-r border-b px-3 py-1.5 align-top [overflow-wrap:anywhere] whitespace-pre-wrap"
                >
                  {row[c] ?? ""}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {body.length > shown.length && (
        <p className="p-4 text-[0.8125rem] text-muted-foreground">
          {t("text.csvTruncated", { count: formatNumber(shown.length, i18n.language) })}
        </p>
      )}
    </div>
  )
}
