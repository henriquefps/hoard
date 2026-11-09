import "./text.css"
import type { Text } from "@codemirror/state"
import {
  ArrowSquareOutIcon,
  ArrowsClockwiseIcon,
  ArrowUDownLeftIcon,
  BracketsCurlyIcon,
  CopyIcon,
  DotsThreeVerticalIcon,
  DownloadSimpleIcon,
  FileXIcon,
  FloppyDiskIcon,
  MagnifyingGlassIcon,
  MinusIcon,
  PencilSimpleIcon,
  PlusIcon,
  TextAaIcon,
} from "@phosphor-icons/react"
import {
  useBlocker,
  useLocation,
  useNavigate,
  useParams,
  useRouter,
  useSearch,
} from "@tanstack/react-router"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { Page } from "@/components/page/Page"
import { viewTrail } from "@/components/page/breadcrumbs"
import { SmbErrorState } from "@/components/SmbErrorState"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { Spinner } from "@/components/ui/spinner"
import { Toggle } from "@/components/ui/toggle"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useLiveQuery } from "@/db/DbProvider"
import { getMachine } from "@/db/queries"
import type { MachineWithAddresses } from "@/db/types"
import { invalidateFolder } from "@/features/browse/useFolder"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { DownloadDialog } from "@/features/transfers/DownloadDialog"
import { useBackHandler } from "@/hooks/useAndroidBackButton"
import { useIsMobile } from "@/hooks/use-mobile"
import { hasThumbnail, parentPath, splitPath, uncPath } from "@/lib/files"
import { formatBytes, formatFullDate, formatNumber, formatRelativeDate } from "@/lib/format"
import { goUp } from "@/lib/historyTrail"
import { canEncode } from "@/lib/text/codec"
import type { LinkTarget } from "@/lib/text/links"
import {
  applyLineEnding,
  countLines,
  detectLineEnding,
  isProse,
  isTextFile,
  type LineEnding,
  longestLine,
  nextFreeName,
  prettyJson,
  RENDER_LIMITS,
  textFormat,
} from "@/lib/text/textFiles"
import { cn } from "@/lib/utils"
import { HoardSmb } from "@/plugins/smb"
import type { ReadTextFileResult, TextEncoding } from "@/plugins/smb/definitions"
import { isHoardSmbError, smbErrorCode, TEXT_FILE_MAX_BYTES } from "@/plugins/smb/definitions"
import { CodeEditor, type CodeEditorHandle } from "./CodeEditor"
import { CsvTable } from "./CsvTable"
import { FindBar } from "./FindBar"
import { MarkdownView, type MarkdownHost } from "./MarkdownView"
import { FONT_SIZES, setTextPrefs, useTextPrefs } from "./prefs"
import { ConflictDialog, UnsavedDialog } from "./TextDialogs"
import { useTextFile } from "./useTextFile"

/** Above this many characters on one line, soft wrap starts off (it would lay out the whole line). */
const WRAP_LINE_LIMIT = 50_000

/** `/view/$machineId?share=&path=[&edit=true]`: the built-in text viewer and editor. */
export function TextViewerPage() {
  const { t } = useTranslation()
  const { machineId } = useParams({ from: "/view/$machineId" })
  const { share, path, edit } = useSearch({ from: "/view/$machineId" })
  const machine = useLiveQuery((db) => getMachine(db, machineId), [machineId])
  const name = splitPath(path ?? "").at(-1) ?? ""

  if (!share || !path || machine === null) {
    return (
      <Page
        title={name || t("notFound.title")}
        breadcrumbs={viewTrail(
          t,
          { id: machineId, name: machine?.name ?? "?" },
          share ?? "",
          path ?? ""
        )}
      >
        <SmbErrorState code="NOT_FOUND" machineId={machineId} />
      </Page>
    )
  }
  if (machine === undefined) {
    return (
      <Page
        fill
        title={name}
        breadcrumbs={viewTrail(t, { id: machineId, name: undefined }, share, path)}
      >
        <ViewerSkeleton />
      </Page>
    )
  }
  return (
    <TextFileScreen
      key={`${share}/${path}`}
      machine={machine}
      share={share}
      path={path}
      startEditing={Boolean(edit)}
    />
  )
}

interface LoadedDoc {
  /** Remount key for the editor: a new one after every (re)load. */
  version: number
  data: ReadTextFileResult
  encoding: TextEncoding
  lineEnding: LineEnding
  modifiedAt: string | null
  size: number
  lines: number
}

type RenderedView = "rendered" | "source"
type EditLayout = "editor" | "split" | "preview"
type Dialog = { kind: "conflict"; remoteModifiedAt: string | null } | { kind: "exitEdit" } | null

function TextFileScreen({
  machine,
  share,
  path,
  startEditing,
}: {
  machine: MachineWithAddresses
  share: string
  path: string
  startEditing: boolean
}) {
  const { t, i18n } = useTranslation()
  const locale = i18n.language
  const engine = useRouteEngine()
  const router = useRouter()
  const navigate = useNavigate()
  const location = useLocation()
  const isMobile = useIsMobile()
  const prefs = useTextPrefs()
  const file = useTextFile(machine, share, path)
  const name = splitPath(path).at(-1) ?? path
  const folder = parentPath(path) ?? ""
  const format = textFormat(name)
  const renderable = format === "markdown" || format === "csv" || format === "tsv"

  const [doc, setDoc] = useState<LoadedDoc | null>(null)
  const [seen, setSeen] = useState<ReadTextFileResult | undefined>(undefined)
  const [editing, setEditing] = useState(startEditing)
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [findOpen, setFindOpen] = useState(false)
  const [dialog, setDialog] = useState<Dialog>(null)
  const [download, setDownload] = useState(false)
  const [view, setView] = useState<RenderedView>("rendered")
  const [layoutChoice, setLayout] = useState<EditLayout>("editor")
  const [pretty, setPretty] = useState(false)
  const [wrapOverride, setWrapOverride] = useState<boolean | null>(null)
  /** The text the rendered views show: the loaded text, then the editor's (debounced). */
  const [liveText, setLiveText] = useState("")
  const editor = useRef<CodeEditorHandle>(null)
  const baseline = useRef<Text | null>(null)
  const dirtyRef = useRef(false)
  const renderedRef = useRef<HTMLDivElement>(null)
  const liveTimer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const machineRef = useRef(machine)
  useEffect(() => {
    machineRef.current = machine
    dirtyRef.current = dirty
  })

  // A (re)load replaces the document (adjusting state while rendering, not in an effect).
  if (file.data && file.data !== seen) {
    const data = file.data
    setSeen(data)
    const big = data.text.length > RENDER_LIMITS.bytes
    setDoc((previous) => ({
      version: (previous?.version ?? 0) + 1,
      data,
      encoding: data.encoding,
      lineEnding: detectLineEnding(data.text),
      modifiedAt: data.modifiedAt,
      size: data.size,
      lines: countLines(data.text),
    }))
    setLiveText(data.text)
    setDirty(false)
    if (!doc) {
      if (big) setView("source")
      if (longestLine(data.text) > WRAP_LINE_LIMIT) setWrapOverride(false)
      if (data.truncated) setEditing(false)
    }
  }
  const prose = isProse(name)
  const wrap = wrapOverride ?? (prose ? prefs.wrap : prefs.wrapCode)
  const readOnly = Boolean(doc?.data.truncated)

  // The editor's baseline (what the server has) after every mount of a loaded document.
  useEffect(() => {
    baseline.current = editor.current?.view?.state.doc ?? null
    // A new file opens ready to type.
    if (startEditing && doc?.version === 1) editor.current?.view?.focus()
  }, [doc?.version, startEditing])

  const onDocChange = useCallback(
    (current: Text) => {
      const base = baseline.current
      const changed = !base || current.length !== base.length || !current.eq(base)
      if (changed !== dirtyRef.current) {
        dirtyRef.current = changed
        setDirty(changed)
      }
      if (renderable) {
        clearTimeout(liveTimer.current)
        liveTimer.current = setTimeout(() => setLiveText(current.toString()), 200)
      }
    },
    [renderable]
  )
  useEffect(() => () => clearTimeout(liveTimer.current), [])

  // --- What is on screen ---
  // Phones have no room for side by side.
  const layout: EditLayout = isMobile && layoutChoice === "split" ? "editor" : layoutChoice
  const showEditor = editing
    ? !renderable || layout !== "preview"
    : !renderable || view === "source"
  const showRendered = renderable && (editing ? layout !== "editor" : view === "rendered")
  const split = editing && renderable && layout === "split" && !isMobile
  const prettyText = useMemo(
    () => (format === "json" && pretty && doc ? prettyJson(doc.data.text) : null),
    [format, pretty, doc]
  )
  const showPretty = !editing && prettyText !== null

  const getView = useCallback(() => editor.current?.view ?? null, [])
  const getRoot = useCallback(() => renderedRef.current, [])

  // --- Actions ---
  const run = <T,>(fn: (sessionId: string) => Promise<T>) =>
    engine.withSession(machineRef.current, fn)

  const save = useCallback(
    async (options: { force?: boolean } = {}): Promise<boolean> => {
      const current = editor.current
      if (!current || !doc || readOnly) return false
      const text = applyLineEnding(current.text(), doc.lineEnding)
      let encoding = doc.encoding
      if (!canEncode(text, encoding)) {
        encoding = "utf-8"
        toast(t("text.savedAsUtf8", { encoding: t(`text.encodings.${doc.encoding}`) }))
      }
      setSaving(true)
      try {
        const { entry } = await run((sessionId) =>
          HoardSmb.writeTextFile({
            sessionId,
            share,
            path,
            text,
            encoding,
            expectedModifiedAt: options.force ? null : doc.modifiedAt,
          })
        )
        baseline.current = current.view?.state.doc ?? null
        dirtyRef.current = false
        setDirty(false)
        const savedText = current.text()
        setDoc({
          ...doc,
          encoding,
          modifiedAt: entry.modifiedAt,
          size: entry.size,
          lines: countLines(savedText),
        })
        setLiveText(savedText)
        invalidateFolder(machine.id, share, folder)
        toast.success(t("text.saved", { name }))
        return true
      } catch (error) {
        const code = smbErrorCode(error)
        if (code === "CONFLICT") {
          const remote = isHoardSmbError(error) ? error.data?.modifiedAt : undefined
          setDialog({
            kind: "conflict",
            remoteModifiedAt: typeof remote === "string" ? remote : null,
          })
        } else {
          toast.error(t("text.saveFailed", { name }), {
            description: t(`errors.${code}.title`, t("errors.UNKNOWN.title")),
          })
        }
        return false
      } finally {
        setSaving(false)
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `run` reads a ref
    [doc, readOnly, share, path, folder, name, machine.id, t]
  )

  async function saveAsCopy(): Promise<boolean> {
    const current = editor.current
    if (!current || !doc) return false
    const text = applyLineEnding(current.text(), doc.lineEnding)
    const encoding = canEncode(text, doc.encoding) ? doc.encoding : "utf-8"
    setSaving(true)
    try {
      const { entries } = await run((sessionId) =>
        HoardSmb.list({ sessionId, share, path: folder })
      )
      const taken = new Set(entries.map((e) => e.name.toLowerCase()))
      const copyName = nextFreeName(name, (candidate) => taken.has(candidate.toLowerCase()))
      const copyPath = folder ? `${folder}/${copyName}` : copyName
      await run((sessionId) =>
        HoardSmb.writeTextFile({ sessionId, share, path: copyPath, text, encoding, mode: "create" })
      )
      dirtyRef.current = false
      setDirty(false)
      invalidateFolder(machine.id, share, folder)
      toast.success(t("text.savedAsCopy", { name: copyName }))
      void navigate({
        to: "/view/$machineId",
        params: { machineId: machine.id },
        search: { share, path: copyPath, edit: true },
        replace: true,
        ignoreBlocker: true,
      })
      return true
    } catch (error) {
      toast.error(t("text.saveFailed", { name }), {
        description: t(`errors.${smbErrorCode(error)}.title`, t("errors.UNKNOWN.title")),
      })
      return false
    } finally {
      setSaving(false)
    }
  }

  function discardChanges() {
    // Back to what the server has (the last load or save). Undo history keeps the discarded text.
    const saved = baseline.current?.toString() ?? doc?.data.text ?? ""
    editor.current?.setText(saved)
    baseline.current = editor.current?.view?.state.doc ?? null
    dirtyRef.current = false
    setDirty(false)
    setLiveText(saved)
  }

  function requestExitEdit() {
    if (dirtyRef.current) setDialog({ kind: "exitEdit" })
    else setEditing(false)
  }

  async function openWithApp() {
    try {
      await run((sessionId) => HoardSmb.openFile({ sessionId, share, path, chooser: true }))
    } catch (error) {
      const code = smbErrorCode(error)
      if (code !== "CANCELLED") {
        toast.error(t(`browse.openError.${code}`, t("browse.openError.UNKNOWN")), {
          description: name,
        })
      }
    }
  }

  async function copyPath() {
    const host = engine.activeRoute(machine.id)?.host ?? machine.addresses[0]?.host ?? ""
    const text = uncPath(host, share, path)
    try {
      await navigator.clipboard.writeText(text)
      toast.success(t("browse.pathCopied"), { description: text })
    } catch {
      toast.error(t("browse.copyFailed"))
    }
  }

  const folderLink = {
    to: "/browse/$machineId" as const,
    params: { machineId: machine.id },
    search: { share, path: folder || undefined },
  }

  // Leaving with unsaved changes (breadcrumb, sidebar, browser back) asks first.
  const blocker = useBlocker({
    shouldBlockFn: () => dirtyRef.current,
    enableBeforeUnload: () => dirtyRef.current,
    withResolver: true,
  })

  // Android back: close find, then leave edit mode (asking when unsaved), then up to the folder.
  useBackHandler(() => {
    if (findOpen) {
      setFindOpen(false)
      return true
    }
    if (editing) {
      requestExitEdit()
      return true
    }
    goUp(router, folderLink)
    return true
  })

  // Ctrl/Cmd+S saves, Ctrl/Cmd+F finds (instead of the browser's own).
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.altKey) return
      const key = event.key.toLowerCase()
      if (key === "s") {
        event.preventDefault()
        if (editing && dirtyRef.current && !saving) void save()
      } else if (key === "f" && doc) {
        event.preventDefault()
        setFindOpen(true)
      }
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [editing, saving, save, doc])

  // Markdown host: images from the share (as thumbnails, never base64 over the bridge) and links.
  const host = useMemo<MarkdownHost>(
    () => ({
      filePath: path,
      imageSrc: async (imagePath) => {
        const imageName = splitPath(imagePath).at(-1) ?? ""
        if (!hasThumbnail({ name: imageName, isDirectory: false })) return null
        const result = await engine.withSession(machineRef.current, (sessionId) =>
          HoardSmb.thumbnail({ sessionId, share, path: imagePath, maxSize: 1600 })
        )
        return result.src
      },
      navigate: (target: LinkTarget) => {
        if (target.kind === "anchor") {
          scrollToAnchor(renderedRef.current, target.id)
          return
        }
        if (target.kind !== "share") return
        const targetName = splitPath(target.path).at(-1) ?? ""
        if (target.isFolder || !targetName) {
          void navigate({
            to: "/browse/$machineId",
            params: { machineId: machine.id },
            search: { share, path: target.path || undefined },
          })
        } else if (isTextFile(targetName)) {
          void navigate({
            to: "/view/$machineId",
            params: { machineId: machine.id },
            search: { share, path: target.path },
            hash: target.hash,
          })
        } else {
          void engine
            .withSession(machineRef.current, (sessionId) =>
              HoardSmb.openFile({ sessionId, share, path: target.path })
            )
            .catch((error: unknown) => {
              const code = smbErrorCode(error)
              if (code !== "CANCELLED") {
                toast.error(t(`browse.openError.${code}`, t("browse.openError.UNKNOWN")), {
                  description: targetName,
                })
              }
            })
        }
      },
    }),
    [engine, share, path, machine.id, navigate, t]
  )

  // A link to `other.md#section`: scroll there once rendered.
  const hash = location.hash
  useEffect(() => {
    if (!hash || !showRendered || !doc) return
    const timer = setTimeout(() => scrollToAnchor(renderedRef.current, hash), 150)
    return () => clearTimeout(timer)
  }, [hash, showRendered, doc])

  // --- Header ---
  const meta = doc && (
    <p className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[0.8125rem] text-muted-foreground tabular-nums">
      <span>{formatBytes(doc.size, locale)}</span>
      <span aria-hidden>·</span>
      <span>{t(`text.encodings.${doc.encoding}`)}</span>
      <span aria-hidden>·</span>
      <span>{t(`text.lineEndings.${doc.lineEnding}`)}</span>
      <span aria-hidden>·</span>
      <span>
        {t("text.lines", { count: doc.lines, formatted: formatNumber(doc.lines, locale) })}
      </span>
      {doc.modifiedAt && (
        <>
          <span aria-hidden>·</span>
          <span title={formatFullDate(doc.modifiedAt, locale)}>
            {formatRelativeDate(doc.modifiedAt, locale)}
          </span>
        </>
      )}
      {readOnly && (
        <Badge variant="outline" className="ml-1">
          {t("text.readOnly")}
        </Badge>
      )}
      {dirty && (
        <Badge variant="outline" className="ml-1 border-warning/50 text-warning">
          {t("text.unsaved")}
        </Badge>
      )}
    </p>
  )

  const actions = doc && (
    <>
      {editing ? (
        <>
          <Button variant="ghost" onClick={requestExitEdit}>
            {t("text.done")}
          </Button>
          <Button onClick={() => void save()} disabled={!dirty || saving}>
            {saving ? (
              <Spinner data-icon="inline-start" />
            ) : (
              <FloppyDiskIcon data-icon="inline-start" />
            )}
            {t("text.save")}
          </Button>
        </>
      ) : (
        !readOnly && (
          <Button variant="outline" onClick={() => setEditing(true)}>
            <PencilSimpleIcon data-icon="inline-start" />
            {t("text.edit")}
          </Button>
        )
      )}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" aria-label={t("common.more")}>
            <DotsThreeVerticalIcon weight="bold" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-52">
          {editing && format === "json" && (
            <>
              <DropdownMenuItem
                onSelect={() => {
                  const formatted = prettyJson(editor.current?.text() ?? "")
                  if (formatted === null) toast.error(t("text.jsonInvalid"))
                  else editor.current?.setText(formatted)
                }}
              >
                <BracketsCurlyIcon />
                {t("text.formatJson")}
              </DropdownMenuItem>
              <DropdownMenuSeparator />
            </>
          )}
          <DropdownMenuItem onSelect={() => void openWithApp()}>
            <ArrowSquareOutIcon />
            {t("text.openWith")}
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => setDownload(true)}>
            <DownloadSimpleIcon />
            {t("browse.download")}
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => void copyPath()}>
            <CopyIcon />
            {t("browse.copyPath")}
          </DropdownMenuItem>
          <DropdownMenuItem
            disabled={dirty}
            onSelect={() => void file.load({ truncate: readOnly })}
          >
            <ArrowsClockwiseIcon />
            {t("text.reload")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  )

  // --- Body ---
  let body
  if (file.error && !doc) {
    body = (
      <LoadError
        code={file.error.code}
        size={file.error.size}
        machine={machine}
        attempts={file.error.attempts}
        retrying={file.loading}
        onRetry={() => {
          if (file.error?.attempts) engine.forget(machine.id)
          void file.load()
        }}
        onLoadPart={() => void file.load({ truncate: true })}
        onOpenWith={() => void openWithApp()}
      />
    )
  } else if (!doc) {
    body = <ViewerSkeleton />
  } else {
    const switcher = renderable && (
      <ToggleGroup
        type="single"
        variant="outline"
        spacing={0}
        value={editing ? layout : view}
        onValueChange={(value) => {
          if (!value) return
          if (editing) setLayout(value as EditLayout)
          else setView(value as RenderedView)
        }}
        aria-label={t("text.viewMode")}
      >
        {editing ? (
          <>
            <ToggleGroupItem value="editor">{t("text.layouts.editor")}</ToggleGroupItem>
            {!isMobile && (
              <ToggleGroupItem value="split">{t("text.layouts.split")}</ToggleGroupItem>
            )}
            <ToggleGroupItem value="preview">
              {format === "markdown" ? t("text.layouts.preview") : t("text.layouts.table")}
            </ToggleGroupItem>
          </>
        ) : (
          <>
            <ToggleGroupItem value="rendered">
              {format === "markdown" ? t("text.layouts.preview") : t("text.layouts.table")}
            </ToggleGroupItem>
            <ToggleGroupItem value="source">{t("text.layouts.source")}</ToggleGroupItem>
          </>
        )}
      </ToggleGroup>
    )

    const rendered =
      showRendered &&
      (format === "markdown" ? (
        <MarkdownView ref={renderedRef} text={liveText} host={host} fontSize={prefs.fontSize} />
      ) : (
        <CsvTable
          ref={renderedRef}
          text={liveText}
          delimiter={format === "tsv" ? "\t" : ","}
          fontSize={prefs.fontSize}
        />
      ))

    body = (
      <div className="flex min-h-0 flex-1 flex-col gap-3">
        <div
          className="@container flex min-h-9 items-center gap-2"
          role="toolbar"
          aria-label={t("text.toolbar")}
        >
          {switcher}
          {format === "json" && !editing && (
            <Toggle
              variant="outline"
              pressed={pretty}
              onPressedChange={setPretty}
              aria-label={t("text.pretty")}
            >
              <BracketsCurlyIcon />
              <span className="max-@md:sr-only">{t("text.pretty")}</span>
            </Toggle>
          )}
          <div className="ml-auto flex shrink-0 items-center gap-2">
            <Button
              variant="outline"
              size="icon"
              aria-label={t("text.find")}
              title={t("text.find")}
              aria-pressed={findOpen}
              onClick={() => setFindOpen((open) => !open)}
            >
              <MagnifyingGlassIcon />
            </Button>
            {showEditor && (
              <Toggle
                variant="outline"
                pressed={wrap}
                onPressedChange={(next) => {
                  setWrapOverride(null)
                  setTextPrefs(prose ? { wrap: next } : { wrapCode: next })
                }}
                aria-label={t("text.wrap")}
                title={t("text.wrap")}
              >
                <ArrowUDownLeftIcon />
              </Toggle>
            )}
            <FontSizeMenu />
          </div>
        </div>

        <div className="-mx-4 -mb-3 flex min-h-0 flex-1 flex-col border-t md:-mx-6 md:-mb-4">
          {findOpen && (
            <FindBar
              target={showRendered && !showEditor ? "rendered" : "editor"}
              getView={getView}
              getRoot={getRoot}
              replace={editing && showEditor}
              onClose={() => setFindOpen(false)}
            />
          )}
          {readOnly && (
            <Alert className="rounded-none border-x-0 border-t-0">
              <AlertDescription>
                {t("text.truncatedNotice", {
                  shown: formatBytes(TEXT_FILE_MAX_BYTES, locale),
                  size: formatBytes(doc.size, locale),
                })}
              </AlertDescription>
            </Alert>
          )}
          <div className="flex min-h-0 flex-1">
            <div
              className={cn(
                "flex min-h-0 min-w-0 flex-1",
                !showEditor && "hidden",
                split && "basis-1/2 border-r"
              )}
            >
              <CodeEditor
                key={doc.version}
                ref={editor}
                initialText={doc.data.text}
                fileName={name}
                editable={editing && !readOnly}
                wrap={wrap}
                fontSize={prefs.fontSize}
                label={t("text.editorLabel", { name })}
                hidden={!showEditor || showPretty}
                onDocChange={onDocChange}
              />
              {showPretty && (
                <CodeEditor
                  key={`pretty-${doc.version}`}
                  initialText={prettyText}
                  fileName={name}
                  editable={false}
                  wrap={wrap}
                  fontSize={prefs.fontSize}
                  label={t("text.editorLabel", { name })}
                />
              )}
            </div>
            {showRendered && (
              <div className={cn("flex min-h-0 min-w-0 flex-1", split && "basis-1/2")}>
                {rendered}
              </div>
            )}
          </div>
        </div>
      </div>
    )
  }

  return (
    <Page
      fill
      title={name}
      breadcrumbs={viewTrail(t, { id: machine.id, name: machine.name }, share, path)}
      meta={meta}
      actions={actions}
    >
      {body}

      <ConflictDialog
        open={dialog?.kind === "conflict"}
        name={name}
        remoteModifiedAt={dialog?.kind === "conflict" ? dialog.remoteModifiedAt : null}
        busy={saving}
        onCancel={() => setDialog(null)}
        onReload={async () => {
          setDialog(null)
          await file.load()
        }}
        onOverwrite={async () => {
          if (await save({ force: true })) setDialog(null)
        }}
        onSaveCopy={async () => {
          if (await saveAsCopy()) setDialog(null)
        }}
      />
      <UnsavedDialog
        open={dialog?.kind === "exitEdit"}
        name={name}
        busy={saving}
        onCancel={() => setDialog(null)}
        onDiscard={() => {
          discardChanges()
          setDialog(null)
          setEditing(false)
        }}
        onSave={async () => {
          if (await save()) {
            setDialog(null)
            setEditing(false)
          } else if (dialog?.kind === "exitEdit") setDialog(null)
        }}
      />
      <UnsavedDialog
        open={blocker.status === "blocked"}
        name={name}
        busy={saving}
        leaving
        onCancel={() => blocker.reset?.()}
        onDiscard={() => {
          dirtyRef.current = false
          blocker.proceed?.()
        }}
        onSave={async () => {
          if (await save()) blocker.proceed?.()
          else blocker.reset?.()
        }}
      />
      {download && doc && (
        <DownloadDialog
          open
          onOpenChange={(open) => !open && setDownload(false)}
          machine={machine}
          files={[{ share, path, name, size: doc.size }]}
          skippedFolders={0}
          onStarted={() => setDownload(false)}
        />
      )}
    </Page>
  )
}

function scrollToAnchor(root: HTMLElement | null, rawId: string) {
  const id = rawId.replace(/^#/, "")
  if (!root || !id) return
  const escaped = CSS.escape(id)
  const target =
    root.querySelector(`#${escaped}`) ??
    root.querySelector(`#user-content-${escaped}`) ??
    root.querySelector(`[name="${escaped}"]`)
  target?.scrollIntoView({ block: "start" })
}

function FontSizeMenu() {
  const { t } = useTranslation()
  const { fontSize } = useTextPrefs()
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="icon"
          aria-label={t("text.fontSize")}
          title={t("text.fontSize")}
        >
          <TextAaIcon />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-48">
        <DropdownMenuLabel>{t("text.fontSize")}</DropdownMenuLabel>
        <div className="flex items-center justify-between gap-2 px-2 pb-1.5">
          <Button
            variant="outline"
            size="icon"
            aria-label={t("text.smaller")}
            disabled={fontSize <= FONT_SIZES.min}
            onClick={() => setTextPrefs({ fontSize: fontSize - 1 })}
          >
            <MinusIcon />
          </Button>
          <span className="text-[0.9375rem] font-medium tabular-nums" aria-live="polite">
            {t("text.fontSizeValue", { size: fontSize })}
          </span>
          <Button
            variant="outline"
            size="icon"
            aria-label={t("text.larger")}
            disabled={fontSize >= FONT_SIZES.max}
            onClick={() => setTextPrefs({ fontSize: fontSize + 1 })}
          >
            <PlusIcon />
          </Button>
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          disabled={fontSize === FONT_SIZES.default}
          onSelect={() => setTextPrefs({ fontSize: FONT_SIZES.default })}
        >
          {t("text.resetSize")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function LoadError({
  code,
  size,
  machine,
  attempts,
  retrying,
  onRetry,
  onLoadPart,
  onOpenWith,
}: {
  code: ReturnType<typeof smbErrorCode>
  size?: number
  machine: MachineWithAddresses
  attempts?: Parameters<typeof SmbErrorState>[0]["attempts"]
  retrying: boolean
  onRetry: () => void
  onLoadPart: () => void
  onOpenWith: () => void
}) {
  const { t, i18n } = useTranslation()
  if (code === "TOO_LARGE" || code === "NOT_TEXT") {
    const large = code === "TOO_LARGE"
    return (
      <Empty className="border">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <FileXIcon />
          </EmptyMedia>
          <EmptyTitle>{large ? t("text.tooLargeTitle") : t("text.notTextTitle")}</EmptyTitle>
          <EmptyDescription>
            {large
              ? t("text.tooLargeBody", {
                  size: formatBytes(size ?? 0, i18n.language),
                  limit: formatBytes(TEXT_FILE_MAX_BYTES, i18n.language),
                })
              : t("text.notTextBody")}
          </EmptyDescription>
        </EmptyHeader>
        <EmptyContent className="flex-row flex-wrap justify-center">
          {large && (
            <Button onClick={onLoadPart}>
              {t("text.loadFirstPart", { limit: formatBytes(TEXT_FILE_MAX_BYTES, i18n.language) })}
            </Button>
          )}
          <Button variant="outline" onClick={onOpenWith}>
            <ArrowSquareOutIcon data-icon="inline-start" />
            {t("text.openWith")}
          </Button>
        </EmptyContent>
      </Empty>
    )
  }
  return (
    <SmbErrorState
      code={code}
      machineId={machine.id}
      attempts={attempts}
      retrying={retrying}
      onRetry={onRetry}
    />
  )
}

function ViewerSkeleton() {
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3" aria-hidden>
      <Skeleton className="h-9 w-48" />
      <div className="-mx-4 flex flex-col gap-2.5 border-t px-4 pt-4 md:-mx-6 md:px-6">
        {Array.from({ length: 14 }, (_, i) => (
          <Skeleton key={i} className="h-3.5" style={{ width: `${30 + ((i * 41) % 65)}%` }} />
        ))}
      </div>
    </div>
  )
}
