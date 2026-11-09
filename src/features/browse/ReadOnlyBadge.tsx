import { LockSimpleIcon } from "@phosphor-icons/react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { DropdownMenuLabel } from "@/components/ui/dropdown-menu"
import { cn } from "@/lib/utils"

/**
 * Quiet "Read-only" marker (outline, muted, lock) for shares, folders and files the account may
 * read but not change. Writable places show nothing: writable is the normal case.
 */
export function ReadOnlyBadge({ title, className }: { title?: string; className?: string }) {
  const { t } = useTranslation()
  return (
    <Badge
      variant="outline"
      className={cn("text-muted-foreground normal-nums", className)}
      title={title}
      data-testid="read-only-badge"
    >
      <LockSimpleIcon data-icon="inline-start" weight="bold" aria-hidden />
      {t("access.readOnly")}
    </Badge>
  )
}

/**
 * Why the write items above it are disabled, inside a menu (tooltips do not show on touch or on
 * disabled items, so the explanation sits where the user is looking).
 */
export function ReadOnlyMenuNote({ reason }: { reason: string }) {
  return (
    <DropdownMenuLabel className="flex max-w-64 items-start gap-2 text-[0.8125rem] font-normal">
      <LockSimpleIcon aria-hidden className="mt-0.5 size-3.5 shrink-0" />
      <span>{reason}</span>
    </DropdownMenuLabel>
  )
}
