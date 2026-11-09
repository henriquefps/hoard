import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands"
import { Compartment, EditorState, type Text } from "@codemirror/state"
import {
  drawSelection,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  type ViewUpdate,
} from "@codemirror/view"
import { useEffect, useImperativeHandle, useRef, type Ref } from "react"
import { cn } from "@/lib/utils"
import {
  editingExtensions,
  editorTheme,
  headlessSearch,
  highlighting,
  languageForFile,
} from "./codemirror"

export interface CodeEditorHandle {
  view: EditorView | null
  /** The text as the editor holds it (lines joined with `\n`). */
  text: () => string
  /** Replace everything (discard changes, reload, pretty-print), keeping undo history. */
  setText: (text: string) => void
}

/**
 * CodeMirror 6, read-only (the viewer: line numbers, highlighting, wrap, find; only the visible
 * lines are in the DOM, so 100,000 lines scroll fine) or editable (history, indentation, bracket
 * matching). One instance stays mounted across view and edit mode so undo survives switching.
 */
export function CodeEditor({
  ref,
  initialText,
  fileName,
  editable,
  wrap,
  fontSize,
  label,
  hidden,
  onDocChange,
  onSelectionChange,
}: {
  ref?: Ref<CodeEditorHandle>
  /** Read once on mount: remount (key) to load another text. */
  initialText: string
  fileName: string
  editable: boolean
  wrap: boolean
  fontSize: number
  label: string
  hidden?: boolean
  onDocChange?: (doc: Text) => void
  onSelectionChange?: (update: ViewUpdate) => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const compartments = useRef({
    language: new Compartment(),
    wrap: new Compartment(),
    editable: new Compartment(),
    theme: new Compartment(),
  })
  const callbacks = useRef({ onDocChange, onSelectionChange })
  useEffect(() => {
    callbacks.current = { onDocChange, onSelectionChange }
  })

  // Create once; later prop changes reconfigure compartments below.
  useEffect(() => {
    const c = compartments.current
    const state = EditorState.create({
      doc: initialText,
      extensions: [
        lineNumbers(),
        highlightSpecialChars(),
        drawSelection(),
        history(),
        headlessSearch,
        highlighting,
        keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
        c.language.of([]),
        c.wrap.of(wrap ? EditorView.lineWrapping : []),
        c.editable.of(editableExtensions(editable)),
        c.theme.of(editorTheme(fontSize)),
        EditorView.contentAttributes.of({ "aria-label": label }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) callbacks.current.onDocChange?.(update.state.doc)
          if (update.selectionSet || update.docChanged || update.transactions.length) {
            callbacks.current.onSelectionChange?.(update)
          }
        }),
      ],
    })
    view.current = new EditorView({ state, parent: host.current! })
    return () => {
      view.current?.destroy()
      view.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- created once per mount (see `initialText`)
  }, [])

  useEffect(() => {
    let cancelled = false
    void languageForFile(fileName).then((support) => {
      if (cancelled || !view.current) return
      view.current.dispatch({
        effects: compartments.current.language.reconfigure(support ?? []),
      })
    })
    return () => {
      cancelled = true
    }
  }, [fileName])

  useEffect(() => {
    view.current?.dispatch({
      effects: compartments.current.wrap.reconfigure(wrap ? EditorView.lineWrapping : []),
    })
  }, [wrap])

  useEffect(() => {
    view.current?.dispatch({
      effects: compartments.current.editable.reconfigure(editableExtensions(editable)),
    })
  }, [editable])

  useEffect(() => {
    view.current?.dispatch({
      effects: compartments.current.theme.reconfigure(editorTheme(fontSize)),
    })
  }, [fontSize])

  // Shown again after being hidden (Markdown preview on a phone): CodeMirror re-measures.
  useEffect(() => {
    if (!hidden) view.current?.requestMeasure()
  }, [hidden])

  useImperativeHandle(
    ref,
    () => ({
      get view() {
        return view.current
      },
      text: () => view.current?.state.doc.toString() ?? initialText,
      setText: (text: string) => {
        const current = view.current
        if (!current) return
        current.dispatch({ changes: { from: 0, to: current.state.doc.length, insert: text } })
      },
    }),
    [initialText]
  )

  return (
    <div
      ref={host}
      className={cn("h-full min-h-0 min-w-0 flex-1 overflow-hidden", hidden && "hidden")}
      data-editable={editable || undefined}
    />
  )
}

function editableExtensions(editable: boolean) {
  return editable
    ? [
        editingExtensions,
        highlightActiveLine(),
        highlightActiveLineGutter(),
        EditorView.editable.of(true),
        EditorState.readOnly.of(false),
      ]
    : [EditorView.editable.of(false), EditorState.readOnly.of(true)]
}
