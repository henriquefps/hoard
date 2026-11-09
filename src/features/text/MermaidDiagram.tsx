// Adapted from markdown-preview's MermaidRenderer (Henrique Silva, MIT):
// https://github.com/henriquefps/markdown-preview — src/components/MermaidRenderer.tsx.
// Kept: theme colours from the app tokens, sequential renders (mermaid.initialize is global),
// waiting for fonts before measuring, securityLevel "strict". Dropped: the print copy.
// Mermaid is loaded lazily, on the first diagram.
import { useEffect, useId, useState } from "react"
import { useTranslation } from "react-i18next"
import { usePalette } from "@/lib/theme/theme"
import { useResolvedDark } from "./useResolvedDark"

let probe: CanvasRenderingContext2D | null = null
/** Any CSS colour (the tokens are oklch) as #rrggbb, which Mermaid's colour library parses. */
function toHex(cssColor: string, fallback: string): string {
  try {
    probe ??= document.createElement("canvas").getContext("2d", { willReadFrequently: true })
    if (!probe || !cssColor) return fallback
    probe.clearRect(0, 0, 1, 1)
    probe.fillStyle = fallback
    probe.fillStyle = cssColor
    probe.fillRect(0, 0, 1, 1)
    const [r, g, b] = probe.getImageData(0, 0, 1, 1).data
    return `#${[r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")}`
  } catch {
    return fallback
  }
}

const token = (name: string) =>
  getComputedStyle(document.documentElement).getPropertyValue(name).trim()

let counter = 0
let queue: Promise<unknown> = Promise.resolve()

function render(chart: string, dark: boolean, baseId: string): Promise<string> {
  const run = async () => {
    const { default: mermaid } = await import("mermaid")
    const elementId = `mermaid-${baseId}-${counter++}`
    // Mermaid sizes boxes from measured text: measuring before Inter has loaded clips labels.
    await Promise.race([
      document.fonts?.ready,
      new Promise((resolve) => setTimeout(resolve, 1500)),
    ]).catch(() => undefined)
    try {
      const primary = toHex(token("--primary"), dark ? "#7aa7e8" : "#2f5fa8")
      // "strict": no scripts or unsafe HTML; the SVG is injected with dangerouslySetInnerHTML and
      // the Markdown comes from a file on someone's server.
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: dark ? "dark" : "default",
        themeVariables: {
          fontFamily: "Inter Variable, Inter, system-ui, sans-serif",
          primaryBorderColor: primary,
          lineColor: primary,
        },
      })
      const { svg } = await mermaid.render(elementId, chart)
      return svg
    } finally {
      document.getElementById(elementId)?.remove()
      document.getElementById(`d${elementId}`)?.remove()
    }
  }
  const result = queue.then(run, run)
  queue = result.catch(() => undefined)
  return result
}

function errorText(error: unknown): string {
  if (error instanceof Error) return error.message
  if (typeof error === "string") return error
  if (error && typeof error === "object" && "str" in error && typeof error.str === "string") {
    return error.str
  }
  return ""
}

export function MermaidDiagram({ chart }: { chart: string }) {
  const { t } = useTranslation()
  const dark = useResolvedDark()
  const palette = usePalette()
  const baseId = useId().replace(/[^a-zA-Z0-9_-]/g, "")
  const [state, setState] = useState<{ svg: string; error: string | null }>({
    svg: "",
    error: null,
  })

  useEffect(() => {
    let mounted = true
    render(chart.trim(), dark, baseId).then(
      (svg) => mounted && setState({ svg, error: null }),
      (error: unknown) => mounted && setState({ svg: "", error: errorText(error) })
    )
    return () => {
      mounted = false
    }
  }, [chart, dark, palette, baseId])

  if (state.error !== null) {
    return (
      <div className="mermaid-container">
        <div className="mermaid-error" role="alert">
          <strong>{t("text.mermaidError")}</strong>
          {state.error && <pre>{state.error}</pre>}
        </div>
      </div>
    )
  }
  if (!state.svg) {
    return (
      <div className="mermaid-container text-[0.8125rem] text-muted-foreground">
        {t("text.mermaidRendering")}
      </div>
    )
  }
  return (
    <div
      className="mermaid-container"
      role="img"
      aria-label={t("text.mermaidLabel")}
      dangerouslySetInnerHTML={{ __html: state.svg }}
    />
  )
}
