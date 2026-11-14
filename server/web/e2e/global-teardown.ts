import { existsSync, rmSync } from "node:fs"
import { readState, statePath } from "./server"

export default async function globalTeardown() {
  for (const name of ["state", "bench"]) {
    if (!existsSync(statePath(name))) continue
    try {
      process.kill(readState(name).pid, "SIGTERM")
    } catch {
      // already gone
    }
    rmSync(statePath(name)) // never kill a stale pid on a later run
  }
}
