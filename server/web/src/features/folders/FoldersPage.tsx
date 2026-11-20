import {
  ArrowsInIcon,
  CaretDownIcon,
  CaretRightIcon,
  DevicesIcon,
  DeviceMobileIcon,
  DotsThreeVerticalIcon,
  FolderOpenIcon,
  FolderSimpleIcon,
  HardDrivesIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Spinner } from "@/components/ui/spinner"
import { api } from "@/lib/api"
import { filtersToSearch, NO_FILTERS, UPLOADS } from "@/lib/filters"
import { formatNumber } from "@/lib/format"
import { navigate } from "@/lib/router"
import { useLibraryState } from "@/lib/store"
import { buildTree, type DirNode } from "@/lib/tree"
import { type CompressInfo, type Job, sameFolder, type SelFolder, type Target, useCompress } from "@/lib/compress"
import { CompressDialog, type CompressRequest } from "../compress/CompressDialog"

/** What a folder row can ask: compress what is there now, or turn its "Compress automatically" switch. */
interface FolderActions {
  info: CompressInfo | null
  autoJobs: Job[]
  ask: (r: CompressRequest) => void
}

/** The ⋮ menu of a NAS folder, a directory or a phone backup (ADR 0025 "Triggering"). */
function CompressMenu({ actions, target, folder, label }: { actions: FolderActions; target: Target; folder: SelFolder; label: string }) {
  const { t } = useTranslation()
  if (!actions.info) return null
  const autoJob = actions.autoJobs.find((j) => j.target === target && sameFolder(j.select, folder)) ?? null
  const part = { target, select: folder.rootId ? { roots: [folder] } : { folders: [{ ...folder, recursive: true }] } }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label={t("compress.folderMenu", { name: label })} data-testid="folder-menu">
          <DotsThreeVerticalIcon />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onSelect={() => actions.ask({ parts: [part], label, mode: "once", target })} data-testid="compress-folder">
          <ArrowsInIcon />
          {t("compress.actionFolder")}
        </DropdownMenuItem>
        <DropdownMenuCheckboxItem
          checked={!!autoJob}
          onSelect={() => actions.ask({ parts: [part], label, mode: "auto", autoJob, target })}
          data-testid="compress-auto"
        >
          {t("compress.auto.switch")}
        </DropdownMenuCheckboxItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

interface FolderInfo {
  id: string
  name: string
  state: string
  items: number
  dirs: { path: string; items: number }[]
}

const openInGallery = (id: string, dir: string) => navigate("/", filtersToSearch({ ...NO_FILTERS, folder: { id, dir } }))

function TreeNode({
  folder,
  folderName,
  node,
  depth,
  open,
  toggle,
  actions,
}: {
  folder: string
  folderName: string
  node: DirNode
  depth: number
  open: Set<string>
  toggle: (key: string) => void
  actions: FolderActions
}) {
  const { i18n, t } = useTranslation()
  const key = `${folder}:${node.path}`
  const expanded = open.has(key)
  return (
    <li role="treeitem" aria-expanded={node.children.length ? expanded : undefined} aria-level={depth + 1} aria-selected={false}>
      <div className="flex h-11 items-center gap-1 border-b pr-4 hover:bg-muted/50" style={{ paddingLeft: 8 + depth * 20 }}>
        {node.children.length ? (
          <button
            type="button"
            onClick={() => toggle(key)}
            aria-label={expanded ? t("folders.collapse", { name: node.name }) : t("folders.expand", { name: node.name })}
            className="grid size-8 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-muted"
          >
            {expanded ? <CaretDownIcon className="size-4" /> : <CaretRightIcon className="size-4" />}
          </button>
        ) : (
          <span className="size-8 shrink-0" />
        )}
        <button
          type="button"
          onClick={() => openInGallery(folder, node.path)}
          className="flex min-w-0 flex-1 items-center gap-2 rounded-md py-1 text-left text-[0.9375rem] outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {expanded ? <FolderOpenIcon className="size-4 shrink-0 text-muted-foreground" /> : <FolderSimpleIcon className="size-4 shrink-0 text-muted-foreground" />}
          <span className="truncate">{node.name}</span>
        </button>
        <span className="text-sm text-muted-foreground tabular-nums">{formatNumber(node.total, i18n.language)}</span>
        <CompressMenu actions={actions} target="nas" folder={{ folderId: folder, path: node.path }} label={`${folderName}/${node.path}`} />
      </div>
      {expanded && node.children.length > 0 && (
        <ul role="group">
          {node.children.map((c) => (
            <TreeNode key={c.path} folder={folder} folderName={folderName} node={c} depth={depth + 1} open={open} toggle={toggle} actions={actions} />
          ))}
        </ul>
      )}
    </li>
  )
}

/** The NAS folders the server indexes, as a tree with item counts; a directory opens the gallery filtered to it. */
export function FoldersPage() {
  const { t, i18n } = useTranslation()
  const { library } = useLibraryState()
  const [data, setData] = useState<{ folders: FolderInfo[]; uploads: number } | null>(null)
  const [failed, setFailed] = useState(false)
  const [open, setOpen] = useState<Set<string>>(new Set())
  const { info, roots, tick } = useCompress()
  const [autoJobs, setAutoJobs] = useState<Job[]>([])
  const [request, setRequest] = useState<CompressRequest | null>(null)
  const actions: FolderActions = { info, autoJobs, ask: setRequest }

  // The folders compressed automatically (their switch's state), again after each change.
  useEffect(() => {
    if (!info) return
    let stop = false
    api<{ jobs: Job[] }>("/web/compress/jobs?auto=true&limit=100")
      .then((d) => !stop && setAutoJobs(d.jobs.filter((j) => j.state !== "cancelled")))
      .catch(() => {})
    return () => {
      stop = true
    }
  }, [info, tick])

  // Reload when the library changes (a walk added items).
  useEffect(() => {
    api<{ folders: FolderInfo[]; uploads: number }>("/web/folders")
      .then((d) => {
        setData(d)
        setFailed(false)
      })
      .catch(() => setFailed(true))
  }, [library.cursor])

  const trees = useMemo(() => data?.folders.map((f) => ({ f, root: buildTree(f.dirs) })) ?? [], [data])
  const toggle = (key: string) =>
    setOpen((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  if (!data) {
    return (
      <div className="grid flex-1 place-items-center" role="status">
        {failed ? <p className="text-muted-foreground">{t("folders.loadFailed")}</p> : <Spinner className="size-6 text-muted-foreground" />}
      </div>
    )
  }
  if (data.folders.length === 0 && data.uploads === 0 && roots.length === 0) {
    return (
      <Empty className="flex-1">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <HardDrivesIcon />
          </EmptyMedia>
          <EmptyTitle>{t("folders.emptyTitle")}</EmptyTitle>
          <EmptyDescription>{t("folders.emptyHint")}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }
  return (
    <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain" data-testid="folders">
      <div className="border-b px-4 py-4 md:px-6">
        <p className="eyebrow">{t("folders.eyebrow")}</p>
        <h1 className="text-lg font-semibold">{t("nav.folders")}</h1>
      </div>
      {trees.map(({ f, root }) => (
        <section key={f.id} aria-labelledby={`folder-${f.id}`}>
          <div className="flex h-14 items-center gap-3 border-b px-4 md:px-6">
            <HardDrivesIcon className="size-5 shrink-0 text-primary" />
            <button
              type="button"
              id={`folder-${f.id}`}
              onClick={() => openInGallery(f.id, "")}
              className="min-w-0 truncate text-left text-[0.9375rem] font-semibold hover:underline"
            >
              {f.name || t("folders.unknown")}
            </button>
            {f.state !== "ready" && <Badge variant="outline">{t(`folders.state.${f.state}`, { defaultValue: f.state })}</Badge>}
            <span className="ml-auto text-sm text-muted-foreground tabular-nums">
              {t("gallery.itemsCount", { count: root.total, formatted: formatNumber(root.total, i18n.language) })}
            </span>
            <CompressMenu actions={actions} target="nas" folder={{ folderId: f.id, path: "" }} label={f.name || t("folders.unknown")} />
          </div>
          {root.children.length > 0 && (
            <ul role="tree" aria-labelledby={`folder-${f.id}`}>
              {root.children.map((c) => (
                <TreeNode key={c.path} folder={f.id} folderName={f.name} node={c} depth={0} open={open} toggle={toggle} actions={actions} />
              ))}
            </ul>
          )}
        </section>
      ))}
      {data.uploads > 0 && (
        <section>
          <button
            type="button"
            onClick={() => openInGallery(UPLOADS, "")}
            className="flex h-14 w-full items-center gap-3 border-b px-4 text-left hover:bg-muted/50 md:px-6"
          >
            <DevicesIcon className="size-5 shrink-0 text-muted-foreground" />
            <span className="text-[0.9375rem] font-semibold">{t("folders.uploads")}</span>
            <span className="ml-auto text-sm text-muted-foreground tabular-nums">
              {t("gallery.itemsCount", { count: data.uploads, formatted: formatNumber(data.uploads, i18n.language) })}
            </span>
          </button>
        </section>
      )}
      {roots.length > 0 && info && (
        <section aria-labelledby="phone-backups" data-testid="phone-backups">
          <h2 id="phone-backups" className="eyebrow border-b px-4 pt-4 pb-2 md:px-6">
            {t("folders.backups")}
          </h2>
          {roots.map((r) => (
            <div key={r.id} className="flex h-14 items-center gap-3 border-b px-4 md:px-6">
              <DeviceMobileIcon className="size-5 shrink-0 text-muted-foreground" />
              <span className="min-w-0 truncate text-[0.9375rem] font-semibold">{r.device || t("compress.backup.phone")}</span>
              <span className="min-w-0 truncate font-mono text-[0.8125rem] text-muted-foreground">{r.path}</span>
              <span className="ml-auto" />
              <CompressMenu actions={actions} target="backup" folder={{ rootId: r.id, path: "" }} label={`${r.device || t("compress.backup.phone")} · ${r.path}`} />
            </div>
          ))}
        </section>
      )}
      {roots.length === 0 && <p className="px-4 py-3 text-sm text-muted-foreground md:px-6">{t("folders.backupsLater")}</p>}
      <CompressDialog request={request} info={info} onClose={() => setRequest(null)} />
    </div>
  )
}
