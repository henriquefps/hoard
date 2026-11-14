import { useSyncExternalStore } from "react"

/**
 * A small history router (the app has three views): `/` the gallery, `/folders`, and `/items/<id>` the viewer over the
 * gallery. Filters live in the query string. The server answers index.html for these paths.
 */
export interface Location {
  path: string
  search: URLSearchParams
}

const listeners = new Set<() => void>()
let current = read()

function read(): Location {
  return { path: window.location.pathname, search: new URLSearchParams(window.location.search) }
}

function emit() {
  current = read()
  listeners.forEach((l) => l())
}

window.addEventListener("popstate", emit)

export function navigate(path: string, search?: URLSearchParams, replace = false) {
  const qs = search?.toString()
  const url = path + (qs ? `?${qs}` : "")
  if (url === window.location.pathname + window.location.search) return
  if (replace) window.history.replaceState(null, "", url)
  else window.history.pushState(null, "", url)
  emit()
}

export const useLocation = () =>
  useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => current
  )

/** The item open in the viewer, from /items/<id>. */
export const viewerItem = (path: string) => /^\/items\/([0-9a-f-]{36})$/.exec(path)?.[1] ?? null
