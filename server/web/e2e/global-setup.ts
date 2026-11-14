import path from "node:path"
import { buildBinary, LIB, prepare, save, startServer, writeLibrary } from "./server"

export default async function globalSetup() {
  const port = Number(process.env.HOARD_E2E_PORT ?? 3990)
  const base = `http://127.0.0.1:${port}`
  const bin = buildBinary()
  const dir = path.join(LIB, "fixture")
  writeLibrary(dir, "TestWriteWebFixture", { HOARD_WEB_FIXTURE: dir })
  const { child, code } = await startServer(bin, dir, port)
  const ready = await prepare(base, code, dir, { previews: ["thumb", "screen", "proxy"], waitItems: 36 })
  save("state", { base, pid: child.pid!, ...ready })

  // The 20,000-item library for the measurements (opt-in: HOARD_WEB_BENCH=1).
  if (process.env.HOARD_WEB_BENCH) {
    const n = process.env.HOARD_WEB_BENCH_N ?? "20000"
    const bdir = path.join(LIB, `bench-${n}`)
    writeLibrary(bdir, "TestWriteWebBench", { HOARD_WEB_BENCH: bdir, HOARD_WEB_BENCH_N: n })
    const bport = port + 1
    // Thumbnails are made on demand (as a fresh install catches up), only for what the grid shows.
    const b = await startServer(bin, bdir, bport, { PREVIEW_BACKLOG: "none" })
    const bready = await prepare(`http://127.0.0.1:${bport}`, b.code, bdir, { previews: [], waitItems: Number(n) })
    save("bench", { base: `http://127.0.0.1:${bport}`, pid: b.child.pid!, ...bready })
  }
}
