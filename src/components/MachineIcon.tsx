import {
  DesktopIcon,
  DesktopTowerIcon,
  HardDrivesIcon,
  LaptopIcon,
  type Icon,
  CloudIcon,
  HouseIcon,
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
} satisfies Record<string, Icon>

export type MachineIconId = keyof typeof MACHINE_ICONS

/**
 * `machines.color` is stored but not rendered yet: the base theme's chart tokens are a neutral ramp,
 * so per-machine colors need their own semantic tokens (checked for contrast in every palette and
 * both modes) before they are shown. Until then every machine uses the brand tint.
 */

export function MachineIcon({
  icon,
  className,
}: {
  icon: string | null
  /** Reserved, see above. */
  color?: string | null
  className?: string
}) {
  const Component = MACHINE_ICONS[icon as MachineIconId] ?? HardDrivesIcon
  return (
    <span
      aria-hidden
      className={cn(
        "inline-flex size-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary [&_svg]:size-5",
        className
      )}
    >
      <Component weight="duotone" />
    </span>
  )
}
