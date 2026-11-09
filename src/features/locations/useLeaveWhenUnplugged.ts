import { useNavigate } from "@tanstack/react-router"
import { useEffect, useRef } from "react"
import { useTranslation } from "react-i18next"
import { toast } from "sonner"
import { parseLocationId } from "./ids"
import type { BrowseLocation } from "./useBrowseLocation"
import { useVolumes } from "./volumesContext"

/**
 * A drive pulled out (or ejected) while one of its folders is open: say so and go back to the
 * home screen instead of leaving a page whose every call now fails.
 */
export function useLeaveWhenUnplugged(location: BrowseLocation | null | undefined) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { onChange } = useVolumes()
  const parsed = location ? parseLocationId(location.id) : undefined
  const uuid = parsed?.kind === "volume" ? parsed.uuid : undefined
  const name = useRef(location?.name)
  useEffect(() => {
    name.current = location?.name
  })
  useEffect(() => {
    if (!uuid) return
    return onChange((change) => {
      if (change.uuid !== uuid) return
      if (!["removed", "unmounted", "ejecting", "unmountable"].includes(change.state)) return
      toast.warning(t("locations.unplugged", { name: name.current ?? t("locations.drive") }))
      void navigate({ to: "/", replace: true })
    })
  }, [uuid, onChange, navigate, t])
}
