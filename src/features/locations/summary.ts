import type { TFunction } from "i18next"
import { formatBytes } from "@/lib/format"
import type { StorageVolumeInfo } from "@/plugins/smb/definitions"

/** One-line state of a volume: "41.3 GB free of 128 GB", "Checking…", "Read-only · exFAT". */
export function volumeSummary(volume: StorageVolumeInfo, t: TFunction, locale: string): string {
  const parts: string[] = []
  if (volume.state === "checking") parts.push(t("locations.state.checking"))
  else if (volume.state === "ejecting") parts.push(t("locations.state.ejecting"))
  else if (volume.state === "unmounted") parts.push(t("locations.state.unmounted"))
  else if (volume.state === "unmountable") parts.push(t("locations.state.unmountable"))
  else {
    if (volume.state === "mountedReadOnly") parts.push(t("locations.readOnlyDrive"))
    if (volume.freeBytes !== undefined) {
      parts.push(
        volume.totalBytes
          ? t("locations.freeOf", {
              free: formatBytes(volume.freeBytes, locale),
              total: formatBytes(volume.totalBytes, locale),
            })
          : t("locations.free", { free: formatBytes(volume.freeBytes, locale) })
      )
    }
  }
  if (volume.removable && volume.fileSystem) parts.push(volume.fileSystem.toUpperCase())
  return parts.join(" · ")
}
