import { FolderSimplePlusIcon, ShareFatIcon } from "@phosphor-icons/react"
import { useNavigate } from "@tanstack/react-router"
import { useState } from "react"
import { useTranslation } from "react-i18next"
import { FileIcon } from "@/components/FileIcon"
import { Page } from "@/components/page/Page"
import { Button } from "@/components/ui/button"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty"
import { DestinationPicker } from "@/features/transfers/DestinationPicker"
import { incomingShare, useIncomingShare } from "@/features/transfers/incoming"
import { INCOMING_LOCATION_ID } from "@/features/transfers/service"
import { useStartTransfer } from "@/features/transfers/useStartTransfer"
import { fileKind } from "@/lib/files"
import { formatBytes } from "@/lib/format"
import { HoardSmb } from "@/plugins/smb"

/**
 * `/save`: files another app shared to Hoard (its share sheet → Hoard). Pick a folder in any
 * location — this phone, a USB drive, an SMB share — and they are copied there as a normal
 * transfer, read natively through the other app's content URIs (ADR 0007).
 */
export function SavePage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const shared = useIncomingShare()
  const start = useStartTransfer()
  const [picking, setPicking] = useState(false)
  const files = shared?.files ?? []
  const known = files.filter((f) => f.size >= 0)
  const total = known.reduce((s, f) => s + f.size, 0)

  async function dismiss() {
    if (shared) await HoardSmb.clearSharedFiles({ shareId: shared.shareId }).catch(() => {})
    incomingShare.set(null)
    void navigate({ to: "/" })
  }

  return (
    <Page title={t("save.title")} description={t("save.description")}>
      {!shared || files.length === 0 ? (
        <Empty className="border">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <ShareFatIcon />
            </EmptyMedia>
            <EmptyTitle>{t("save.emptyTitle")}</EmptyTitle>
            <EmptyDescription>
              {shared?.text ? t("save.textOnly") : t("save.emptyBody")}
            </EmptyDescription>
          </EmptyHeader>
          {shared && (
            <EmptyContent>
              <Button variant="outline" onClick={() => void dismiss()}>
                {t("common.done")}
              </Button>
            </EmptyContent>
          )}
        </Empty>
      ) : (
        <div className="flex flex-col gap-4">
          <p className="text-[0.9375rem]">
            {t("save.summary", {
              count: files.length,
              size: formatBytes(total, i18n.language),
            })}
          </p>
          <ul className="-mx-4 border-y md:-mx-6">
            {files.map((file) => (
              <li
                key={file.index}
                className="flex min-h-14 items-center gap-3 border-b px-4 py-2 last:border-b-0 md:px-6"
              >
                <FileIcon kind={fileKind({ name: file.name, isDirectory: false })} />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="line-clamp-2 text-[0.9375rem] font-medium [overflow-wrap:anywhere]">
                    {file.name}
                  </span>
                  <span className="truncate text-[0.8125rem] text-muted-foreground">
                    {[
                      file.size >= 0
                        ? formatBytes(file.size, i18n.language)
                        : t("save.unknownSize"),
                      file.mimeType,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </span>
              </li>
            ))}
          </ul>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button className="h-11 sm:h-9" onClick={() => setPicking(true)}>
              <FolderSimplePlusIcon data-icon="inline-start" />
              {t("save.choose")}
            </Button>
            <Button variant="outline" className="h-11 sm:h-9" onClick={() => void dismiss()}>
              {t("save.dismiss")}
            </Button>
          </div>
          <DestinationPicker
            open={picking}
            onOpenChange={setPicking}
            title={t("save.pickerTitle", { count: files.length })}
            description={t("save.summary", {
              count: files.length,
              size: formatBytes(total, i18n.language),
            })}
            confirmLabel={t("save.here")}
            onPick={async (folder, conflict) => {
              await start(
                "copy",
                { locationId: INCOMING_LOCATION_ID, shareId: shared.shareId },
                files.map((f) => ({
                  path: String(f.index),
                  name: f.name,
                  isDirectory: false,
                  size: Math.max(0, f.size),
                })),
                folder,
                conflict
              )
              await HoardSmb.clearSharedFiles({ shareId: shared.shareId }).catch(() => {})
              incomingShare.set(null)
              void navigate({ to: "/transfers" })
            }}
          />
        </div>
      )}
    </Page>
  )
}
