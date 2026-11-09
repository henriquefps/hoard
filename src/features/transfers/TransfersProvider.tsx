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
import type { TransferRecord } from "@/db/types"
import { HoardSmb } from "@/plugins/smb"
import {
  type ConflictPolicy,
  TERMINAL_TRANSFER_STATES,
  type TransferSourceLocation,
} from "@/plugins/smb/definitions"
import { parseLocationId } from "@/features/locations/ids"
import { nativeRoute } from "@/features/route/engine"
import { useRouteEngine } from "@/features/route/RouteProvider"
import {
  INCOMING_LOCATION_ID,
  type LiveTransfer,
  type TransferPlace,
  type TransferRequest,
  TransferService,
  TREE_LOCATION_ID,
} from "./service"

const ServiceContext = createContext<TransferService | null>(null)

/** Starts the transfer service (progress listener + reconcile) and reconciles again on resume. */
export function TransfersProvider({ children }: { children: ReactNode }) {
  const db = useDb()
  const engine = useRouteEngine()
  const [service] = useState(() => {
    // A place in app terms → what native needs; SMB gets the route that is current right now.
    const resolve = async (place: TransferPlace): Promise<TransferSourceLocation> => {
      if (place.locationId === TREE_LOCATION_ID) {
        if (!place.treeUri) throw new Error("No folder")
        return { kind: "tree", uri: place.treeUri }
      }
      if (place.locationId === INCOMING_LOCATION_ID) {
        if (!place.shareId) throw new Error("No shared files")
        return { kind: "incoming", shareId: place.shareId }
      }
      const parsed = parseLocationId(place.locationId)
      if (parsed.kind === "device") return { kind: "device" }
      if (parsed.kind === "volume") return { kind: "volume", uuid: parsed.uuid }
      const machine = await getMachine(db, parsed.machineId)
      if (!machine) throw Object.assign(new Error("Machine not found"), { code: "NOT_FOUND" })
      return {
        kind: "smb",
        machineId: machine.id,
        share: place.share ?? "",
        ...(await nativeRoute(engine, machine)),
      }
    }
    return new TransferService(HoardSmb, db, resolve)
  })
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

/** A `transfers` row with the live view (progress, speed, host, current file) merged in. */
export interface TransferView extends TransferRecord {
  live?: LiveTransfer
  /** Bytes done / total / items from the live view when fresher than the row. */
  bytesDone: number
  total: number
  itemsTotal: number
  itemsDone: number
  itemsFailed: number
  itemsSkipped: number
  conflicts: number
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
        itemsTotal: (useLive ? live.itemsTotal : row.items_total) || row.items_total,
        itemsDone: useLive ? live.itemsDone : row.items_done,
        itemsFailed: useLive ? live.itemsFailed : row.items_failed,
        itemsSkipped: useLive ? live.itemsSkipped : row.items_skipped,
        conflicts: (useLive && live.conflicts) || 0,
        error_code: useLive && live.error ? live.error.code : row.error_code,
        active: !TERMINAL_TRANSFER_STATES.includes(state),
      }
    })
  }, [rows, version, service])
}

/**
 * Start, retry, cancel and answer conflicts. `start` is the one API for moving bytes between
 * locations (copy, move, upload, download, "Save to…", culling's "copy picks"): see ADR 0007.
 */
export function useTransferActions() {
  const service = useTransferService()
  return useMemo(
    () => ({
      start: (request: TransferRequest) => service.transfer(request),
      retry: (row: TransferRecord) => service.retry(row),
      cancel: (id: string) => service.cancel(id),
      resolveConflicts: (
        id: string,
        answer: { all?: ConflictPolicy; items?: { index: number; policy: ConflictPolicy }[] }
      ) => service.resolveConflicts(id, answer),
      items: service.items.bind(service),
    }),
    [service]
  )
}

/**
 * A number that changes whenever a transfer into (or a move out of) this folder makes progress
 * on its items or ends, so the browser can refresh the listing without polling.
 */
export function useFolderTransferSignal(
  locationId: string,
  share: string | undefined,
  path: string
) {
  const transfers = useTransfers()
  return useMemo(() => {
    let signal = 0
    for (const x of transfers ?? []) {
      const into =
        x.dest_location_id === locationId &&
        (x.dest_share || "").toLowerCase() === (share ?? "").toLowerCase() &&
        x.dest_path === path
      const outOf =
        x.op === "move" &&
        x.source_location_id === locationId &&
        (x.source_share || "").toLowerCase() === (share ?? "").toLowerCase()
      if (into || outOf) signal += x.itemsDone + x.itemsSkipped + (x.active ? 0 : 1000)
    }
    return signal
  }, [transfers, locationId, share, path])
}
