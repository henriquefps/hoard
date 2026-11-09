import { DEMO_MACHINES } from "@/plugins/smb/mockData"
import type { Db } from "./db"
import { addFavorite, getSetting, saveMachine, setSetting } from "./queries"

/**
 * Browser only: fills an empty database once with the mock's demo machines (fixed ids, so the
 * mock's pretend-Keystore has their credentials) and a few favorites. Server GUIDs stay null, like
 * a machine that was never connected, so the first connect exercises GUID pinning.
 */
export async function seedDemoData(db: Db): Promise<void> {
  if (await getSetting(db, "demoSeeded", false)) return
  await db.transaction(async () => {
    for (const [index, machine] of DEMO_MACHINES.entries()) {
      await db.run(
        "INSERT OR IGNORE INTO machines (id, name, icon, color, username, domain, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
          machine.id,
          machine.name,
          machine.icon,
          machine.color,
          machine.username,
          machine.domain,
          index,
        ]
      )
      await saveMachine(
        db,
        { ...machine, addresses: machine.addresses.map((host) => ({ host })) },
        machine.id
      )
      for (const favorite of machine.favorites) {
        await addFavorite(db, { machineId: machine.id, ...favorite })
      }
    }
    await setSetting(db, "demoSeeded", true)
  })
}
