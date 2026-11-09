import { DEMO_MACHINES, WORST_CASE_MACHINES } from "@/plugins/smb/mockData"
import type { Db } from "./db"
import { addFavorite, getSetting, saveMachine, setSetting } from "./queries"

type SeedMachine = {
  id: string
  name: string
  icon: string | null
  color: string | null
  username: string
  domain: string | null
  addresses: readonly string[]
  favorites: readonly { share: string; path: string; label: string }[]
}

async function insertMachines(db: Db, machines: readonly SeedMachine[]) {
  const [{ next }] = await db.query<{ next: number }>(
    "SELECT COALESCE(MAX(sort_order) + 1, 0) AS next FROM machines"
  )
  for (const [index, machine] of machines.entries()) {
    await db.run(
      "INSERT OR IGNORE INTO machines (id, name, icon, color, username, domain, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
      [
        machine.id,
        machine.name,
        machine.icon,
        machine.color,
        machine.username,
        machine.domain,
        next + index,
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
}

/**
 * Browser only: fills an empty database once with the mock's demo machines (fixed ids, so the
 * mock's pretend-Keystore has their credentials) and a few favorites. Server GUIDs stay null, like
 * a machine that was never connected, so the first connect exercises GUID pinning.
 */
export async function seedDemoData(db: Db): Promise<void> {
  if (await getSetting(db, "demoSeeded", false)) return
  await db.transaction(async () => {
    await insertMachines(db, DEMO_MACHINES)
    await setSetting(db, "demoSeeded", true)
  })
}

/** Browser dev tool (worst-case data pass): adds 18 machines with absurd but plausible data. */
export async function seedWorstCaseData(db: Db): Promise<void> {
  await db.transaction(async () => {
    await insertMachines(db, WORST_CASE_MACHINES)
    await setSetting(db, "worstCaseSeeded", true)
  })
}

/** Browser dev tool: removes the worst-case machines again (cascade removes their favorites). */
export async function removeWorstCaseData(db: Db): Promise<void> {
  await db.transaction(async () => {
    for (const machine of WORST_CASE_MACHINES) {
      await db.run("DELETE FROM machines WHERE id = ?", [machine.id])
    }
    await setSetting(db, "worstCaseSeeded", false)
  })
}
