import { classHighlighter, highlightCode } from "@lezer/highlight"
import { useEffect, useState, type ReactNode } from "react"
import { languageForName } from "./codemirror"

/**
 * A code block highlighted with the same lezer parsers and `tok-*` classes as the editor. Plain
 * text first, highlighted once the language has loaded (lazily, per language).
 */
export function HighlightedCode({ code, language }: { code: string; language: string }) {
  const [result, setResult] = useState<{ key: string; nodes: ReactNode[] } | null>(null)
  const key = `${language}\u0000${code}`

  useEffect(() => {
    if (!language || code.length > 200_000) return
    let cancelled = false
    void languageForName(language).then((support) => {
      if (cancelled || !support) return
      const tree = support.language.parser.parse(code)
      const nodes: ReactNode[] = []
      highlightCode(
        code,
        tree,
        classHighlighter,
        (text, classes) => {
          nodes.push(
            classes ? (
              <span key={nodes.length} className={classes}>
                {text}
              </span>
            ) : (
              text
            )
          )
        },
        () => nodes.push("\n")
      )
      setResult({ key, nodes })
    })
    return () => {
      cancelled = true
    }
  }, [code, language, key])

  return (
    <code className={language ? `language-${language}` : undefined}>
      {result?.key === key ? result.nodes : code}
    </code>
  )
}
