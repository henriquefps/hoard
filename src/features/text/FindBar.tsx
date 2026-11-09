import {
  CaretDownIcon,
  CaretUpIcon,
  MagnifyingGlassIcon,
  SwapIcon,
  TextAaIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
  InputGroupText,
} from "@/components/ui/input-group"
import { Toggle } from "@/components/ui/toggle"
import { formatNumber } from "@/lib/format"
import { MAX_COUNTED_MATCHES } from "./codemirror"
import type { EditorView } from "@codemirror/view"
import { codeMirrorFinder, domFinder, type FindStatus } from "./find"

/**
 * Find (and, while editing, replace) in the file. Layout from markdown-preview's find bar: the
 * query with "n of m", previous / next, match case, close; the replace row below. Enter = next,
 * Shift+Enter = previous, Escape closes.
 */
export function FindBar({
  target,
  getView,
  getRoot,
  replace,
  onClose,
}: {
  /** Where to search: the CodeMirror view, or the rendered document (Markdown, CSV). */
  target: "editor" | "rendered"
  getView: () => EditorView | null
  getRoot: () => HTMLElement | null
  /** Show the replace row (edit mode). */
  replace: boolean
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [query, setQuery] = useState("")
  const [replacement, setReplacement] = useState("")
  const [caseSensitive, setCaseSensitive] = useState(false)
  const [status, setStatus] = useState<FindStatus>({ count: 0, current: 0 })
  const input = useRef<HTMLInputElement>(null)
  const finder = useMemo(
    () => (target === "rendered" ? domFinder(getRoot) : codeMirrorFinder(getView)),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the getters read refs; only the target matters
    [target]
  )

  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])

  // A new engine (view switched): run the current query on it; clear highlights when leaving.
  useEffect(() => {
    // Syncs with the outside (CodeMirror state, CSS highlights) and reads back the match count.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (query) setStatus(finder.search(query, caseSensitive))
    return () => finder.clear()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only when the engine changes
  }, [finder])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !document.querySelector("[role=dialog],[role=alertdialog]")) {
        onClose()
      }
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [onClose])

  const count =
    status.count >= MAX_COUNTED_MATCHES
      ? `${formatNumber(MAX_COUNTED_MATCHES, i18n.language)}+`
      : formatNumber(status.count, i18n.language)

  return (
    <div role="search" className="flex shrink-0 flex-col gap-2 border-b px-4 py-2 md:px-6">
      <div className="flex items-center gap-1">
        <InputGroup className="min-w-0 flex-1">
          <InputGroupAddon>
            <MagnifyingGlassIcon />
          </InputGroupAddon>
          <InputGroupInput
            ref={input}
            aria-label={t("text.find")}
            placeholder={t("text.find")}
            value={query}
            enterKeyHint="search"
            onChange={(event) => {
              setQuery(event.target.value)
              setStatus(finder.search(event.target.value, caseSensitive))
            }}
            onKeyDown={(event) => {
              if (event.key !== "Enter") return
              event.preventDefault()
              setStatus(event.shiftKey ? finder.previous() : finder.next())
            }}
          />
          {query && (
            <InputGroupAddon align="inline-end">
              <InputGroupText
                className="text-[0.8125rem] font-normal tabular-nums"
                aria-live="polite"
              >
                {status.count === 0
                  ? t("text.noMatches")
                  : t("text.matchOf", {
                      current: formatNumber(status.current, i18n.language),
                      count,
                    })}
              </InputGroupText>
            </InputGroupAddon>
          )}
        </InputGroup>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("text.previousMatch")}
          title={t("text.previousMatch")}
          disabled={status.count === 0}
          onClick={() => setStatus(finder.previous())}
        >
          <CaretUpIcon />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("text.nextMatch")}
          title={t("text.nextMatch")}
          disabled={status.count === 0}
          onClick={() => setStatus(finder.next())}
        >
          <CaretDownIcon />
        </Button>
        <Toggle
          className="aria-pressed:bg-primary/10 aria-pressed:text-primary"
          aria-label={t("text.matchCase")}
          title={t("text.matchCase")}
          pressed={caseSensitive}
          onPressedChange={(pressed) => {
            setCaseSensitive(pressed)
            setStatus(finder.search(query, pressed))
          }}
        >
          <TextAaIcon />
        </Toggle>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("text.closeFind")}
          title={t("text.closeFind")}
          onClick={onClose}
        >
          <XIcon />
        </Button>
      </div>
      {replace && finder.replace && (
        <div className="@container flex flex-wrap items-center gap-2 @md:flex-nowrap">
          <InputGroup className="min-w-0 basis-full @md:flex-1 @md:basis-auto">
            <InputGroupAddon>
              <SwapIcon />
            </InputGroupAddon>
            <InputGroupInput
              aria-label={t("text.replaceWith")}
              placeholder={t("text.replaceWith")}
              value={replacement}
              onChange={(event) => setReplacement(event.target.value)}
              onKeyDown={(event) => {
                if (event.key !== "Enter") return
                event.preventDefault()
                setStatus(finder.replace!(query, caseSensitive, replacement))
              }}
            />
          </InputGroup>
          <Button
            variant="outline"
            disabled={status.count === 0}
            onClick={() => setStatus(finder.replace!(query, caseSensitive, replacement))}
          >
            {t("text.replace")}
          </Button>
          <Button
            variant="outline"
            disabled={status.count === 0}
            onClick={() => setStatus(finder.replaceAll!(query, caseSensitive, replacement))}
          >
            {t("text.replaceAll")}
          </Button>
        </div>
      )}
    </div>
  )
}
