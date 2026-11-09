import {
  CloudIcon,
  DesktopIcon,
  DesktopTowerIcon,
  HardDrivesIcon,
  HouseIcon,
  LaptopIcon,
  type Icon,
  MonitorPlayIcon,
  CpuIcon,
} from "@phosphor-icons/react"
import { cn } from "@/lib/utils"

/** Icons a machine can have (`machines.icon`). The id is stored, never the component. */
export const MACHINE_ICONS = {
  "desktop-tower": DesktopTowerIcon,
  desktop: DesktopIcon,
  laptop: LaptopIcon,
  "hard-drives": HardDrivesIcon,
  cloud: CloudIcon,
  house: HouseIcon,
  "monitor-play": MonitorPlayIcon,
  cpu: CpuIcon,
} satisfies Record<string, Icon>

export type MachineIconId = keyof typeof MACHINE_ICONS
export const MACHINE_ICON_IDS = Object.keys(MACHINE_ICONS) as MachineIconId[]

/** Colors a machine can have (`machines.color`), styled by `[data-machine-color]` in index.css. */
export const MACHINE_COLORS = ["blue", "violet", "rose", "green", "amber", "teal", "slate"] as const
export type MachineColor = (typeof MACHINE_COLORS)[number]

export function MachineIcon({
  icon,
  color,
  className,
}: {
  icon: string | null
  color?: string | null
  className?: string
}) {
  const Component = MACHINE_ICONS[icon as MachineIconId] ?? HardDrivesIcon
  return (
    <span
      aria-hidden
      data-machine-color={color ?? ""}
      className={cn(
        "inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-(--machine)/12 text-(--machine) [&_svg]:size-5",
        className
      )}
    >
      <Component weight="duotone" />
    </span>
  )
}
