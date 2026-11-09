import {
  FileArchiveIcon,
  FileAudioIcon,
  FileCodeIcon,
  FileDocIcon,
  FileIcon as PhFileIcon,
  FileImageIcon,
  FilePdfIcon,
  FileTextIcon,
  FileVideoIcon,
  FileXlsIcon,
  FolderSimpleIcon,
  type Icon,
} from "@phosphor-icons/react"
import type { FileKind } from "@/lib/files"
import { cn } from "@/lib/utils"

const ICONS: Record<FileKind, Icon> = {
  folder: FolderSimpleIcon,
  image: FileImageIcon,
  video: FileVideoIcon,
  audio: FileAudioIcon,
  document: FileDocIcon,
  spreadsheet: FileXlsIcon,
  pdf: FilePdfIcon,
  archive: FileArchiveIcon,
  code: FileCodeIcon,
  text: FileTextIcon,
  other: PhFileIcon,
}

/** A 40px tile with the kind's icon; folders use the brand tint, files a neutral one. */
export function FileIcon({ kind, className }: { kind: FileKind; className?: string }) {
  const Component = ICONS[kind]
  return (
    <span
      aria-hidden
      className={cn(
        "inline-flex size-10 shrink-0 items-center justify-center rounded-lg [&_svg]:size-5",
        kind === "folder" ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground",
        className
      )}
    >
      <Component weight={kind === "folder" ? "fill" : "duotone"} />
    </span>
  )
}
