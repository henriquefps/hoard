import { cpSync, rmSync } from "node:fs"
import path from "node:path"
import { buildBinary, LIB, prepare, save, setupOnly, startServer, writeLibrary } from "./server"

export default async function globalSetup() {
  const port = Number(process.env.HOARD_E2E_PORT ?? 3990)
  const base = `http://127.0.0.1:${port}`
  const bin = buildBinary()
  const dir = path.join(LIB, "fixture")
  writeLibrary(dir, "TestWriteWebFixture", { HOARD_WEB_FIXTURE: dir })
  const { child, code } = await startServer(bin, dir, port)
  const ready = await prepare(base, code, dir, { previews: ["thumb", "screen", "proxy"], waitItems: 37 })
  save("state", { base, pid: child.pid!, ...ready })

  // A second server with TRUST_TAILSCALE_SERVE=1 and an empty library, for the HTTPS-proxy gate (ADR 0018).
  const tdir = path.join(LIB, "tailscale")
  const tport = port + 2
  const t = await startServer(bin, tdir, tport, { TRUST_TAILSCALE_SERVE: "1" })
  await setupOnly(`http://127.0.0.1:${tport}`, t.code)
  save("tailscale", { base: `http://127.0.0.1:${tport}`, pid: t.child.pid!, deviceKey: "", folderId: "", items: [] })

  // A third server for video compression (ADR 0025 phase S3): the e2e build's fake encoder, the library folder as its
  // own write mount (the same directory), a backup root. Synthetic clips only.
  // A run replaces files and fills the trash: every run starts from a pristine copy of the library.
  const cdir = path.join(LIB, "compress")
  const pristine = path.join(LIB, "compress-pristine")
  writeLibrary(pristine, "TestWriteWebCompressFixture", { HOARD_WEB_COMPRESS_FIXTURE: pristine })
  const mnt = path.join(cdir, "mnt")
  rmSync(mnt, { recursive: true, force: true })
  cpSync(path.join(pristine, "mnt"), mnt, { recursive: true, preserveTimestamps: true })
  const cport = port + 3
  const c = await startServer(bin, cdir, cport, {
    HOARD_E2E_FAKE_COMPRESS: "1",
    NAS_READ_ROOT: mnt,
    NAS_WRITE_ROOT: mnt,
    BACKUP_ROOT: path.join(mnt, "backup"),
  })
  const cready = await prepare(`http://127.0.0.1:${cport}`, c.code, cdir, { previews: ["thumb"], waitItems: 8 })
  save("compress", { base: `http://127.0.0.1:${cport}`, pid: c.child.pid!, ...cready })

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
