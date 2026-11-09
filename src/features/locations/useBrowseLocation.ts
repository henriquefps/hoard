import { useEffect, useMemo, useRef } from "react"
import { useTranslation } from "react-i18next"
import { useLiveQuery } from "@/db/DbProvider"
import { getMachine } from "@/db/queries"
import type { MachineWithAddresses } from "@/db/types"
import { nativeRoute } from "@/features/route/engine"
import { useRouteEngine } from "@/features/route/RouteProvider"
import { uncPath } from "@/lib/files"
import type { LocationRef, StorageVolumeInfo, StreamLocation } from "@/plugins/smb/definitions"
import { localDisplayPath, parseLocationId } from "./ids"
import { findVolume, useVolumes } from "./volumesContext"

/**
 * One place the browser, the text viewer and the thumbnails work in, whatever kind it is (ADR 0005).
 * Every plugin call goes through `run`, which hands the call its {@link LocationRef}: SMB through
 * the route engine's cached session (reconnects once on an expired one), local ones directly.
 */
export interface BrowseLocation {
  /** Route param and favorites key: a machine id, `device` or `vol-<uuid>`. */
  id: string
  kind: LocationRef["kind"]
  /** SMB only: the share being browsed (undefined on the machine's share list). */
  share?: string
  machine?: MachineWithAddresses
  /** Local only: the volume as Android reports it now (undefined while loading or unplugged). */
  volume?: StorageVolumeInfo
  /** Local only: the volume list has loaded, so a missing `volume` means "not connected". */
  volumesKnown?: boolean
  /** Machine name, "Internal storage", the drive's label; undefined while loading. */
  name: string | undefined
  /** Key for caches of listings and thumbnails. */
  cacheKey: string
  run: <T>(fn: (location: LocationRef) => Promise<T>) => Promise<T>
  /** Where `streamFile` / `openWebPage` read from (SMB: own connection over the route). */
  streamLocation: () => Promise<StreamLocation>
  /** "Copy path": `\\host\share\path` for SMB, `/storage/…/path` for local storage. */
  displayPath: (path: string) => string
}

/**
 * The location for a route's `$locationId` (+ `share` for SMB). `undefined` while the machine
 * loads, `null` when no machine has that id. Local locations are always returned (a drive that was
 * unplugged still has a page: its calls reject `UNAVAILABLE`).
 */
export function useBrowseLocation(
  locationId: string,
  share?: string
): BrowseLocation | null | undefined {
  const { t } = useTranslation()
  const engine = useRouteEngine()
  const parsed = useMemo(() => parseLocationId(locationId), [locationId])
  const machineId = parsed.kind === "smb" ? parsed.machineId : ""
  const machine = useLiveQuery(
    (db) => (machineId ? getMachine(db, machineId) : Promise.resolve(null)),
    [machineId]
  )
  const { volumes } = useVolumes()
  const volume =
    parsed.kind === "smb"
      ? undefined
      : findVolume(volumes, parsed.kind === "device" ? null : parsed.uuid)
  const machineRef = useRef(machine)
  useEffect(() => {
    machineRef.current = machine
  })

  return useMemo((): BrowseLocation | null | undefined => {
    if (parsed.kind === "smb") {
      if (machine === undefined) return undefined
      if (machine === null) return null
      const current = () => machineRef.current ?? machine
      return {
        id: locationId,
        kind: "smb",
        share,
        machine,
        name: machine.name,
        cacheKey: JSON.stringify([locationId, share ?? null]),
        run: (fn) =>
          engine.withSession(current(), (sessionId) =>
            fn({ kind: "smb", sessionId, share: share ?? "" })
          ),
        streamLocation: async () => ({
          kind: "smb",
          machineId: machine.id,
          share: share ?? "",
          ...(await nativeRoute(engine, current())),
        }),
        displayPath: (path) =>
          uncPath(
            engine.activeRoute(machine.id)?.host ?? machine.addresses[0]?.host ?? "",
            share ?? "",
            path
          ),
      }
    }
    const ref: LocationRef =
      parsed.kind === "device" ? { kind: "device" } : { kind: "volume", uuid: parsed.uuid }
    return {
      id: locationId,
      kind: parsed.kind,
      volume,
      volumesKnown: volumes !== undefined,
      name:
        parsed.kind === "device"
          ? t("locations.internal")
          : (volume?.label ?? (volumes ? t("locations.disconnectedDrive") : undefined)),
      cacheKey: JSON.stringify([locationId]),
      run: (fn) => fn(ref),
      streamLocation: async () => ref as StreamLocation,
      displayPath: (path) =>
        localDisplayPath(
          volume?.path ?? (parsed.kind === "device" ? "/storage/emulated/0" : undefined),
          path
        ),
    }
  }, [parsed, machine, share, locationId, engine, volume, volumes, t])
}
