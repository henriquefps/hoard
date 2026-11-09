import { markdown } from "@codemirror/lang-markdown"
import {
  bracketMatching,
  indentOnInput,
  LanguageDescription,
  syntaxHighlighting,
  type LanguageSupport,
} from "@codemirror/language"
import { languages } from "@codemirror/language-data"
import {
  findNext,
  findPrevious,
  getSearchQuery,
  openSearchPanel,
  replaceAll,
  replaceNext,
  search,
  SearchQuery,
  setSearchQuery,
} from "@codemirror/search"
import { EditorSelection, type EditorState, type Extension } from "@codemirror/state"
import { EditorView } from "@codemirror/view"
import { classHighlighter } from "@lezer/highlight"

/**
 * CodeMirror 6 pieces shared by the viewer and the editor. Highlighting uses lezer's
 * `classHighlighter` (`tok-*` classes) so the editor, the read-only viewer and the highlighted
 * code blocks inside rendered Markdown share one set of colours (`text.css`).
 */

export const highlighting: Extension = syntaxHighlighting(classHighlighter)

const MARKDOWN = markdown({ codeLanguages: languages })

const loaded = new Map<string, Promise<LanguageSupport | null>>()

/** The language for a file name (`notes.md`, `Dockerfile`, `app.kt`), loaded lazily; null for none. */
export function languageForFile(name: string): Promise<LanguageSupport | null> {
  const description = LanguageDescription.matchFilename(languages, name)
  if (!description) return Promise.resolve(null)
  if (description.name === "Markdown") return Promise.resolve(MARKDOWN)
  return loadDescription(description)
}

/** The language for a fence tag (`ts`, `kotlin`, `bash`), for code blocks inside Markdown. */
export function languageForName(name: string): Promise<LanguageSupport | null> {
  const description = LanguageDescription.matchLanguageName(languages, name, true)
  return description ? loadDescription(description) : Promise.resolve(null)
}

function loadDescription(description: LanguageDescription) {
  let promise = loaded.get(description.name)
  if (!promise) {
    promise = description.load().catch(() => null)
    loaded.set(description.name, promise)
  }
  return promise
}

/** The editor's look, from the app tokens (light/dark/palette follow automatically). */
export const editorTheme = (fontSize: number) =>
  EditorView.theme({
    "&": {
      height: "100%",
      fontSize: `${fontSize}px`,
      backgroundColor: "transparent",
      color: "var(--foreground)",
    },
    "&.cm-focused": { outline: "none" },
    ".cm-scroller": {
      fontFamily: "var(--font-mono)",
      lineHeight: "1.6",
      overscrollBehavior: "contain",
    },
    ".cm-content": { padding: "12px 0", caretColor: "var(--primary)" },
    ".cm-line": { padding: "0 16px 0 8px" },
    ".cm-gutters": {
      backgroundColor: "var(--card)",
      color: "var(--muted-foreground)",
      borderRight: "1px solid var(--border)",
    },
    ".cm-lineNumbers .cm-gutterElement": { padding: "0 8px 0 12px", minWidth: "3ch" },
    ".cm-activeLine": { backgroundColor: "color-mix(in oklch, var(--muted) 55%, transparent)" },
    ".cm-activeLineGutter": { backgroundColor: "var(--muted)", color: "var(--foreground)" },
    ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--primary)", borderLeftWidth: "2px" },
    "&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection":
      { backgroundColor: "color-mix(in oklch, var(--primary) 22%, transparent)" },
    ".cm-searchMatch": {
      backgroundColor: "color-mix(in oklch, var(--warning) 28%, transparent)",
      borderRadius: "2px",
    },
    ".cm-searchMatch.cm-searchMatch-selected": {
      backgroundColor: "color-mix(in oklch, var(--warning) 60%, transparent)",
    },
    ".cm-matchingBracket": {
      backgroundColor: "color-mix(in oklch, var(--primary) 18%, transparent)",
      outline: "none",
    },
    // The search panel is our own React bar; CodeMirror's stays mounted but empty, which keeps
    // its match highlighting active.
    ".cm-panels": { display: "none" },
  })

export const editingExtensions: Extension = [indentOnInput(), bracketMatching()]

/** Search with a headless panel: the UI is `FindBar`, CodeMirror only highlights and moves. */
export const headlessSearch: Extension = search({
  top: true,
  createPanel: () => ({ dom: document.createElement("div") }),
})

export const MAX_COUNTED_MATCHES = 9999

export interface SearchStatus {
  count: number
  /** 1-based index of the match under the selection, 0 when none. */
  current: number
}

/** Set the query (opening the headless panel so matches highlight) and select the nearest match. */
export function runSearch(
  view: EditorView,
  text: string,
  caseSensitive: boolean,
  replace = ""
): SearchStatus {
  openSearchPanel(view)
  const query = new SearchQuery({ search: text, caseSensitive, literal: true, replace })
  view.dispatch({ effects: setSearchQuery.of(query) })
  if (!text) return { count: 0, current: 0 }
  // Jump to the first match at or after the cursor, like typing in a browser's find bar.
  const from = view.state.selection.main.from
  const cursor = query.getCursor(view.state, from)
  const next = cursor.next()
  const match = next.done ? query.getCursor(view.state).next() : next
  if (!match.done) {
    view.dispatch({
      selection: EditorSelection.single(match.value.from, match.value.to),
      effects: EditorView.scrollIntoView(match.value.from, { y: "center" }),
    })
  }
  return searchStatus(view.state)
}

export function searchStatus(state: EditorState): SearchStatus {
  const query = getSearchQuery(state)
  if (!query.search || !query.valid) return { count: 0, current: 0 }
  const selection = state.selection.main
  let count = 0
  let current = 0
  const cursor = query.getCursor(state)
  for (let next = cursor.next(); !next.done; next = cursor.next()) {
    count++
    if (next.value.from === selection.from && next.value.to === selection.to) current = count
    if (count >= MAX_COUNTED_MATCHES) break
  }
  return { count, current }
}

export const searchCommands = { findNext, findPrevious, replaceNext, replaceAll }
