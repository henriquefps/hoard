import { useNavigate } from "@tanstack/react-router"
import { useEffect, useRef } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { locationIdOfVolume } from "./ids"
import { useVolumes } from "./volumesContext"

/**
 * "USB drive connected: Open" when a drive or card finishes mounting (ADR 0005). One notice per
 * plug-in (Android reports checking → mounted, sometimes twice).
 */
export function VolumeNotices() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { onChange } = useVolumes()
  const announced = useRef(new Set<string>())
  useEffect(
    () =>
      onChange((change, volumes) => {
        if (!change.uuid) return
        if (change.state === "removed" || change.state === "unmounted") {
          announced.current.delete(change.uuid)
          return
        }
        if (change.state !== "mounted" && change.state !== "mountedReadOnly") return
        if (announced.current.has(change.uuid)) return
        announced.current.add(change.uuid)
        const volume = volumes.find((v) => v.uuid === change.uuid)
        if (!volume) return
        const key =
          volume.kind === "usb"
            ? "locations.usbConnected"
            : volume.kind === "sd"
              ? "locations.sdConnected"
              : "locations.driveConnected"
        toast(t(key), {
          id: `volume-${change.uuid}`,
          description: volume.label,
          duration: 8000,
          action: {
            label: t("locations.open"),
            onClick: () =>
              void navigate({
                to: "/browse/$locationId",
                params: { locationId: locationIdOfVolume(volume) },
              }),
          },
        })
      }),
    [onChange, navigate, t]
  )
  return null
}
