import type { EditorView } from "@codemirror/view"
import { MAX_COUNTED_MATCHES, runSearch, searchCommands, searchStatus } from "./codemirror"

/** One find engine behind `FindBar`: CodeMirror (source/editor) or the rendered DOM. */
export interface Finder {
  /** Set the query; returns the match count and the current (1-based) match. */
  search(query: string, caseSensitive: boolean): FindStatus
  next(): FindStatus
  previous(): FindStatus
  clear(): void
  /** Editor only. */
  replace?(query: string, caseSensitive: boolean, replacement: string): FindStatus
  replaceAll?(query: string, caseSensitive: boolean, replacement: string): FindStatus
}

export interface FindStatus {
  count: number
  current: number
}

const NONE: FindStatus = { count: 0, current: 0 }

/** Find (and replace) in a CodeMirror view, through its headless search panel. */
export function codeMirrorFinder(getView: () => EditorView | null): Finder {
  const status = () => {
    const view = getView()
    return view ? searchStatus(view.state) : NONE
  }
  return {
    search: (query, caseSensitive) => {
      const view = getView()
      return view ? runSearch(view, query, caseSensitive) : NONE
    },
    next: () => {
      const view = getView()
      if (view) searchCommands.findNext(view)
      return status()
    },
    previous: () => {
      const view = getView()
      if (view) searchCommands.findPrevious(view)
      return status()
    },
    clear: () => {
      const view = getView()
      if (view) runSearch(view, "", false)
    },
    replace: (query, caseSensitive, replacement) => {
      const view = getView()
      if (!view) return NONE
      runSearch(view, query, caseSensitive, replacement)
      searchCommands.replaceNext(view)
      return status()
    },
    replaceAll: (query, caseSensitive, replacement) => {
      const view = getView()
      if (!view) return NONE
      runSearch(view, query, caseSensitive, replacement)
      searchCommands.replaceAll(view)
      return status()
    },
  }
}

const ALL = "hoard-find"
const CURRENT = "hoard-find-current"

/**
 * Find in rendered content (Markdown preview, CSV table) with the CSS Custom Highlight API: no
 * DOM changes, so React's tree is never touched. Plain substring search over text nodes.
 */
export function domFinder(getRoot: () => HTMLElement | null): Finder {
  let ranges: Range[] = []
  let index = -1

  const paint = () => {
    const registry = (globalThis as { CSS?: { highlights?: Map<string, unknown> } }).CSS?.highlights
    const HighlightCtor = (globalThis as { Highlight?: new (...r: Range[]) => unknown }).Highlight
    if (!registry || !HighlightCtor) return
    registry.set(ALL, new HighlightCtor(...ranges))
    if (ranges[index]) registry.set(CURRENT, new HighlightCtor(ranges[index]))
    else registry.delete(CURRENT)
  }

  const reveal = (): FindStatus => {
    paint()
    const range = ranges[index]
    range?.startContainer.parentElement?.scrollIntoView({ block: "center" })
    return { count: ranges.length, current: range ? index + 1 : 0 }
  }

  return {
    search: (query, caseSensitive) => {
      ranges = []
      index = -1
      const root = getRoot()
      if (root && query) {
        const needle = caseSensitive ? query : query.toLowerCase()
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          const text = caseSensitive ? node.nodeValue! : node.nodeValue!.toLowerCase()
          for (
            let at = text.indexOf(needle);
            at >= 0;
            at = text.indexOf(needle, at + needle.length)
          ) {
            const range = document.createRange()
            range.setStart(node, at)
            range.setEnd(node, at + needle.length)
            ranges.push(range)
            if (ranges.length >= MAX_COUNTED_MATCHES) break
          }
          if (ranges.length >= MAX_COUNTED_MATCHES) break
        }
        index = ranges.length ? 0 : -1
      }
      return reveal()
    },
    next: () => {
      if (ranges.length) index = (index + 1) % ranges.length
      return reveal()
    },
    previous: () => {
      if (ranges.length) index = (index - 1 + ranges.length) % ranges.length
      return reveal()
    },
    clear: () => {
      ranges = []
      index = -1
      const registry = (globalThis as { CSS?: { highlights?: Map<string, unknown> } }).CSS
        ?.highlights
      registry?.delete(ALL)
      registry?.delete(CURRENT)
    },
  }
}
