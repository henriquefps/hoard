import {
  createRootRoute,
  createRoute,
  createRouter,
  lazyRouteComponent,
} from "@tanstack/react-router"
import { AppShell } from "@/components/AppShell"
import { trackHistory } from "@/lib/historyTrail"
import { NotFoundPage } from "@/routes/NotFoundPage"
import { HomePage } from "@/routes/HomePage"
import { MachinesPage } from "@/routes/MachinesPage"
import { MachineFormPage } from "@/routes/MachineFormPage"
import { BrowsePage } from "@/routes/BrowsePage"
import { TransfersPage } from "@/routes/TransfersPage"
import { SettingsPage } from "@/routes/SettingsPage"

const rootRoute = createRootRoute({ component: AppShell, notFoundComponent: NotFoundPage })

const homeRoute = createRoute({ getParentRoute: () => rootRoute, path: "/", component: HomePage })
const machinesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/machines",
  component: MachinesPage,
})
const newMachineRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/machines/new",
  component: () => <MachineFormPage />,
})
export const editMachineRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/machines/$machineId/edit",
  component: function EditMachine() {
    const { machineId } = editMachineRoute.useParams()
    return <MachineFormPage machineId={machineId} />
  },
})

export interface BrowseSearch {
  /** Share name; absent = the machine's share list. */
  share?: string
  /** Folder inside the share, `/`-separated without a leading slash; absent = share root. */
  path?: string
}

const browseRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/browse/$machineId",
  validateSearch: (search: Record<string, unknown>): BrowseSearch => ({
    share: typeof search.share === "string" && search.share ? search.share : undefined,
    path:
      typeof search.path === "string" && search.path
        ? search.path.replace(/^\/+|\/+$/g, "")
        : undefined,
  }),
  component: BrowsePage,
})
export interface ViewSearch {
  share?: string
  /** The file, `/`-separated inside the share. */
  path?: string
  /** Open in edit mode (a new file). */
  edit?: boolean
}

// The text viewer and editor (CodeMirror, the Markdown pipeline, Mermaid, KaTeX) is its own chunk.
const viewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/view/$machineId",
  validateSearch: (search: Record<string, unknown>): ViewSearch => ({
    share: typeof search.share === "string" && search.share ? search.share : undefined,
    path:
      typeof search.path === "string" && search.path
        ? search.path.replace(/^\/+|\/+$/g, "")
        : undefined,
    edit: search.edit === true || search.edit === "true" ? true : undefined,
  }),
  component: lazyRouteComponent(() => import("@/features/text/TextViewerPage"), "TextViewerPage"),
})
const transfersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/transfers",
  component: TransfersPage,
})
const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/settings",
  component: SettingsPage,
})

const routeTree = rootRoute.addChildren([
  homeRoute,
  machinesRoute,
  newMachineRoute,
  editMachineRoute,
  browseRoute,
  viewRoute,
  transfersRoute,
  settingsRoute,
])

// Capacitor's local server answers every extension-less path with index.html, so browser history
// works in the APK as well as in `bun run dev`.
export const router = createRouter({ routeTree, defaultPreload: "intent" })
trackHistory(router)

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router
  }
}
