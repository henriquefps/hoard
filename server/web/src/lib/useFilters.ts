import { useMemo } from "react"
import { filtersFromSearch, filtersToSearch, type Filters } from "./filters"
import { navigate, useLocation } from "./router"

/** The filters in the URL, and a setter that keeps the current path. */
export function useFilters(): [Filters, (f: Filters, replace?: boolean) => void] {
  const loc = useLocation()
  const filters = useMemo(() => filtersFromSearch(loc.search), [loc.search])
  const setFilters = (f: Filters, replace = false) => navigate(loc.path, filtersToSearch(f), replace)
  return [filters, setFilters]
}
