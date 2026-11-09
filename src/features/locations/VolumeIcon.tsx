import { DeviceMobileIcon, HardDriveIcon, UsbIcon, type IconProps } from "@phosphor-icons/react"
import type { StorageVolumeInfo } from "@/plugins/smb/definitions"

/** The phone for internal storage, a USB plug for USB drives, a drive for SD cards and others. */
export function VolumeIcon({
  kind,
  ...props
}: { kind: StorageVolumeInfo["kind"] | "device" } & IconProps) {
  if (kind === "internal" || kind === "device") return <DeviceMobileIcon {...props} />
  if (kind === "usb") return <UsbIcon {...props} />
  return <HardDriveIcon {...props} />
}
