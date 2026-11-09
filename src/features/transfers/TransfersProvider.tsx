import { App } from "@capacitor/app"
import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react"
import { useDb, useLiveQuery } from "@/db/DbProvider"
import { getMachine, listTransfers } from "@/db/queries"
import type { MachineWithAddresses, TransferRecord } from "@/db/types"
import { HoardSmb } from "@/plugins/smb"
import { TERMINAL_TRANSFER_STATES, type ConflictPolicy } from "@/plugins/smb/definitions"
import { nativeRoute } from "@/features/route/engine"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { type DownloadFile, type LiveTransfer, TransferService } from "./service"

const ServiceContext = createContext<TransferService | null>(null)

/** Starts the transfer service (progress listener + reconcile) and reconciles again on resume. */
export function TransfersProvider({ children }: { children: ReactNode }) {
  const db = useDb()
  const [service] = useState(() => new TransferService(HoardSmb, db))
  useEffect(() => {
    void service.start().catch(() => {})
    const resume = App.addListener("resume", () => void service.reconcile().catch(() => {}))
    return () => {
      void resume.then((h) => h.remove()).catch(() => {})
    }
  }, [service])
  return <ServiceContext.Provider value={service}>{children}</ServiceContext.Provider>
}

export function useTransferService(): TransferService {
  const service = useContext(ServiceContext)
  if (!service) throw new Error("useTransferService must be used inside <TransfersProvider>")
  return service
}

/** A `transfers` row with the live view (progress, speed, host) merged in. */
export interface TransferView extends TransferRecord {
  live?: LiveTransfer
  /** Bytes done / total / state from the live view when fresher than the row. */
  bytesDone: number
  total: number
  active: boolean
}

export function useTransfers(): TransferView[] | undefined {
  const service = useTransferService()
  const rows = useLiveQuery(listTransfers)
  const version = useSyncExternalStore(service.subscribe, service.getVersion, service.getVersion)
  return useMemo(() => {
    void version
    return rows?.map((row) => {
      const live = service.getLive(row.id)
      // The row wins once it is terminal (the database is the history); the live view otherwise.
      const useLive = live && !TERMINAL_TRANSFER_STATES.includes(row.state)
      const state = useLive ? live.state : row.state
      return {
        ...row,
        state,
        live,
        bytesDone: useLive ? live.bytesDone : row.bytes_done,
        total: (useLive ? live.totalBytes : row.size) || row.size,
        error_code: useLive && live.error ? live.error.code : row.error_code,
        active: !TERMINAL_TRANSFER_STATES.includes(state),
      }
    })
  }, [rows, version, service])
}

/** Download, retry and cancel, with the route engine supplying addresses and the pinned GUID. */
export function useTransferActions() {
  const service = useTransferService()
  const engine = useRouteEngine()
  const db = useDb()

  return useMemo(() => {
    const routeFor = (machine: MachineWithAddresses) => nativeRoute(engine, machine)
    return {
      async download(
        machine: MachineWithAddresses,
        files: DownloadFile[],
        destTreeUri: string,
        conflict: ConflictPolicy
      ) {
        const route = await routeFor(machine)
        return service.download({ machineId: machine.id, files, destTreeUri, conflict, ...route })
      },
      async retry(row: TransferRecord) {
        const machine = await getMachine(db, row.machine_id)
        if (!machine) throw new Error("Machine not found")
        await service.retry(row, await routeFor(machine))
      },
      cancel: (id: string) => service.cancel(id),
    }
  }, [service, engine, db])
}
