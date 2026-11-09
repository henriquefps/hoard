import { createContext, useContext, type ReactNode } from "react"
import { cn } from "@/lib/utils"
import { useIsMobile } from "@/hooks/use-mobile"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer"

// hfps-app-ui form-dialog rule: a centered Dialog from 768px up, a draggable bottom Drawer (Vaul) below.
// Needs `npx shadcn@latest add dialog drawer` (installs vaul). Call sites never know which one they get.
// The hook path is shadcn's default (`use-mobile`); point it at the app's own hook if it has one.
const MobileCtx = createContext(false)

export function ResponsiveDialog({
  open,
  onOpenChange,
  children,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  children: ReactNode
}) {
  const mobile = useIsMobile()
  return (
    <MobileCtx.Provider value={mobile}>
      {mobile ? (
        <Drawer open={open} onOpenChange={onOpenChange}>
          {children}
        </Drawer>
      ) : (
        <Dialog open={open} onOpenChange={onOpenChange}>
          {children}
        </Dialog>
      )}
    </MobileCtx.Provider>
  )
}

// The drawer's first child is its built-in grab handle (100px, bg-muted, which vanishes on a light popover).
// Restyle it here instead of editing ui/drawer.tsx, so `shadcn add drawer --overwrite` loses nothing.
const handle =
  "[&>div:first-child]:mt-2 [&>div:first-child]:h-1 [&>div:first-child]:w-10 [&>div:first-child]:bg-muted-foreground/30"

export function ResponsiveDialogContent({
  className,
  children,
}: {
  className?: string
  children: ReactNode
}) {
  const mobile = useContext(MobileCtx)
  if (mobile) {
    return (
      <DrawerContent
        className={cn("gap-0 data-[vaul-drawer-direction=bottom]:max-h-[92dvh]", handle, className)}
      >
        {children}
      </DrawerContent>
    )
  }
  return (
    <DialogContent
      className={cn("flex max-h-[min(40rem,calc(100dvh-2rem))] flex-col gap-0 p-0", className)}
    >
      {children}
    </DialogContent>
  )
}

export function ResponsiveDialogHeader({ children }: { children: ReactNode }) {
  const mobile = useContext(MobileCtx)
  const H = mobile ? DrawerHeader : DialogHeader
  return <H className="shrink-0 p-6 pb-4 text-left">{children}</H>
}

export function ResponsiveDialogTitle({ children }: { children: ReactNode }) {
  const mobile = useContext(MobileCtx)
  const T = mobile ? DrawerTitle : DialogTitle
  return <T className="text-lg font-semibold">{children}</T>
}

export function ResponsiveDialogDescription({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  const mobile = useContext(MobileCtx)
  const D = mobile ? DrawerDescription : DialogDescription
  return <D className={className}>{children}</D>
}

// Vaul only starts a drag from the body once it is scrolled to the top, so a long form still scrolls.
export function ResponsiveDialogBody({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  return (
    <div className={cn("min-h-0 flex-1 overflow-y-auto overscroll-contain px-6 pb-4", className)}>
      {children}
    </div>
  )
}

export function ResponsiveDialogFooter({ children }: { children: ReactNode }) {
  const mobile = useContext(MobileCtx)
  if (mobile) {
    return (
      <DrawerFooter className="mt-0 shrink-0 gap-2 border-t p-4 pb-[max(1rem,env(safe-area-inset-bottom))] [&_button]:h-11 [&_button]:w-full">
        {children}
      </DrawerFooter>
    )
  }
  return <DialogFooter className="shrink-0 border-t px-6 py-4">{children}</DialogFooter>
}
