import { App } from "@capacitor/app"
import { Capacitor } from "@capacitor/core"
import {
  createContext,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import { setServerGuid } from "@/db/queries"
import { HoardSmb } from "@/plugins/smb"
import { readSetting, SETTING_DEFAULTS } from "@/features/settings/settings"
import { type EngineMachine, RouteEngine, type RouteState } from "./engine"

const EngineContext = createContext<RouteEngine | null>(null)

/** The probe timeout setting, read by the engine on every check (one provider per app). */
let probeTimeoutMs = SETTING_DEFAULTS.probeTimeoutMs

/**
 * One RouteEngine for the app. Rechecks every machine seen so far when the app returns to the
 * foreground (`resume` / `appStateChange`), when the plugin reports a network change, and on the
 * browser's online/offline events (dev build).
 */
export function RouteProvider({ children }: { children: ReactNode }) {
  const db = useDb()
  const probeTimeout = useLiveQuery((d) => readSetting(d, "probeTimeoutMs"))
  useEffect(() => {
    probeTimeoutMs = probeTimeout ?? SETTING_DEFAULTS.probeTimeoutMs
  }, [probeTimeout])
  const [engine] = useState(
    () =>
      new RouteEngine({
        smb: HoardSmb,
        saveGuid: (machineId, guid) => setServerGuid(db, machineId, guid),
        probeTimeoutMs: () => probeTimeoutMs,
      })
  )

  useEffect(() => {
    const recheck = () => engine.recheckAll()
    const handles = [
      App.addListener("resume", recheck),
      App.addListener("appStateChange", ({ isActive }) => isActive && recheck()),
      HoardSmb.addListener("networkChanged", recheck),
    ]
    if (!Capacitor.isNativePlatform()) {
      window.addEventListener("online", recheck)
      window.addEventListener("offline", recheck)
    }
    return () => {
      handles.forEach((handle) => void handle.then((h) => h.remove()).catch(() => {}))
      window.removeEventListener("online", recheck)
      window.removeEventListener("offline", recheck)
    }
  }, [engine])

  return <EngineContext.Provider value={engine}>{children}</EngineContext.Provider>
}

export function useRouteEngine(): RouteEngine {
  const engine = useContext(EngineContext)
  if (!engine) throw new Error("useRouteEngine must be used inside <RouteProvider>")
  return engine
}

/**
 * The machine's live route state; starts a check the first time a machine is shown. Pass
 * `autoCheck: false` to only observe.
 */
export function useMachineRoute(
  machine: EngineMachine | null | undefined,
  { autoCheck = true } = {}
): RouteState {
  const engine = useRouteEngine()
  const id = machine?.id ?? ""
  const state = useSyncExternalStore(
    engine.subscribe,
    () => engine.getState(id),
    () => engine.getState(id)
  )
  const addressesKey = machine?.addresses.map((a) => `${a.host}:${a.port ?? 445}`).join(",")
  useEffect(() => {
    if (!machine || !autoCheck) return
    void engine.ensure(machine)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-run when the machine's addresses change, not on every new object
  }, [engine, id, addressesKey, autoCheck])
  return state
}
