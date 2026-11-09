import { App } from "@capacitor/app"
import { useNavigate } from "@tanstack/react-router"
import { useEffect } from "react"
import { HoardSmb } from "@/plugins/smb"
import type { SharedFiles } from "@/plugins/smb/definitions"
import { incomingShare } from "./incoming"

/**
 * Hoard as a share target: when another app shares files to it (on launch, while it runs, or
 * when it comes back to the front), open the Save to… page. Mounted once in the app shell.
 */
export function IncomingShareWatcher() {
  const navigate = useNavigate()
  useEffect(() => {
    const show = (shared: SharedFiles | null) => {
      if (!shared || shared.shareId === incomingShare.get()?.shareId) return
      incomingShare.set(shared)
      void navigate({ to: "/save" })
    }
    const check = () =>
      void HoardSmb.getSharedFiles().then(
        ({ shared }) => show(shared),
        () => {}
      )
    check()
    const listener = HoardSmb.addListener("filesShared", show)
    const resume = App.addListener("resume", check)
    return () => {
      void listener.then((h) => h.remove()).catch(() => {})
      void resume.then((h) => h.remove()).catch(() => {})
    }
  }, [navigate])
  return null
}
