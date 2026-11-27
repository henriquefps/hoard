import { spawn, execFileSync, type ChildProcess } from "node:child_process"
import { mkdirSync, openSync, rmSync, writeFileSync, readFileSync, existsSync } from "node:fs"
import path from "node:path"

export const WEB = path.resolve(import.meta.dirname, "..")
export const SERVER = path.resolve(WEB, "..")
export const LIB = path.join(WEB, "e2e", ".library")
export const PASSWORD = "e2e admin password"

export interface E2EState {
  base: string
  pid: number
  deviceKey: string
  folderId: string
  items: { id: string; kind: string; mainFingerprint: string; name: string }[]
}

export const statePath = (name: string) => path.join(LIB, `${name}.json`)
export const readState = (name = "state"): E2EState => JSON.parse(readFileSync(statePath(name), "utf8"))

/**
 * Builds the web app and the server binary (embedding it) once per run. The tag hoarde2e compiles in the fake video
 * encoder (e2e_compress_fake.go), switched on only by HOARD_E2E_FAKE_COMPRESS=1: no release build has it.
 */
export function buildBinary(): string {
  execFileSync("bun", ["run", "build"], { cwd: WEB, stdio: "inherit" })
  mkdirSync(LIB, { recursive: true })
  const bin = path.join(LIB, "hoard-server")
  execFileSync("go", ["build", "-tags", "hoarde2e", "-o", bin, "."], { cwd: SERVER, stdio: "inherit" })
  return bin
}

/** Writes a library with one of the Go generators (TestWriteWebFixture or TestWriteWebBench) into dir. */
export function writeLibrary(
  dir: string,
  test: "TestWriteWebFixture" | "TestWriteWebBench" | "TestWriteWebCompressFixture" | "TestWriteWebTrashFixture",
  env: Record<string, string>
) {
  if (existsSync(path.join(dir, "mnt"))) return
  mkdirSync(dir, { recursive: true })
  execFileSync("go", ["test", "-run", `^${test}$`, "-count=1", "./internal/server/"], {
    cwd: SERVER,
    stdio: "inherit",
    env: { ...process.env, ...env },
  })
}

/** Starts the server on dir (data in dir/data, mounts dir/mnt) and returns it with its setup code. */
export async function startServer(bin: string, dir: string, port: number, extraEnv: Record<string, string> = {}) {
  rmSync(path.join(dir, "data"), { recursive: true, force: true })
  mkdirSync(path.join(dir, "data"), { recursive: true })
  mkdirSync(path.join(dir, "mnt", "backup"), { recursive: true })
  const logPath = path.join(dir, "server.log")
  const out = openSync(logPath, "w")
  const child: ChildProcess = spawn(
    bin,
    ["serve", "--port", String(port), "--listen", "127.0.0.1", "--data-dir", path.join(dir, "data"), "--mounts", path.join(dir, "mnt")],
    {
      env: { ...process.env, PREVIEW_WORKERS: "2", PREVIEW_THREADS: "2", PROXY_WORKERS: "1", ...extraEnv },
      stdio: ["ignore", out, out],
      detached: true,
    }
  )
  child.unref()
  let code = ""
  for (let i = 0; i < 300 && !code; i++) {
    await new Promise((r) => setTimeout(r, 100))
    code = /"setupCode":"([^"]+)"/.exec(readFileSync(logPath, "utf8"))?.[1] ?? ""
    if (child.exitCode !== null) break
  }
  if (!code) throw new Error("no setup code in the log:\n" + readFileSync(logPath, "utf8"))
  return { child, code }
}

async function call<T>(base: string, method: string, p: string, body?: unknown, headers: Record<string, string> = {}): Promise<{ status: number; json: T; cookie?: string }> {
  const res = await fetch(base + p, {
    method,
    headers: { "Content-Type": "application/json", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  return { status: res.status, json: (text ? JSON.parse(text) : null) as T, cookie: res.headers.get("set-cookie") ?? undefined }
}

/**
 * First-run setup through the admin API (setup code → password), adds the NAS folder, waits for the walk, pairs a
 * "device" for pushes, and waits until the previews of every item are made.
 */
export async function prepare(base: string, code: string, dir: string, opts: { previews: ("thumb" | "screen" | "proxy")[]; waitItems: number }) {
  const admin = { "X-Hoard-Admin": "1" }
  const setup = await call(base, "POST", "/admin/api/setup", { code, password: PASSWORD }, admin)
  if (setup.status !== 200) throw new Error("setup failed " + JSON.stringify(setup.json))
  const cookie = setup.cookie!.split(";")[0]
  const h = { ...admin, Cookie: cookie }
  const nas = await call<{ id: string }>(base, "POST", "/admin/api/nas", { path: path.join(dir, "mnt", "nas") }, h)
  if (nas.status !== 201) throw new Error("nas failed " + JSON.stringify(nas.json))
  const pairing = await call<{ token: string }>(base, "POST", "/admin/api/pairings", { url: base }, h)
  const pair = await call<{ apiKey: string }>(base, "POST", "/v1/pair", { token: pairing.json.token, deviceName: "E2E phone", platform: "android", appVersion: "e2e" })
  const key = pair.json.apiKey
  const auth = { Authorization: `Bearer ${key}` }
  // Wait for the walk: every item listed.
  let items: { id: string; kind: string; mainFingerprint: string; files: { name: string; fingerprint: string }[] }[] = []
  for (let i = 0; i < 600; i++) {
    items = []
    let cursor = ""
    for (;;) {
      const page = await call<{ items: typeof items; cursor: string | null; hasMore: boolean }>(base, "GET", `/v1/items?limit=500${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`, undefined, auth)
      items.push(...page.json.items)
      if (!page.json.hasMore || !page.json.cursor) break
      cursor = page.json.cursor
    }
    if (items.length >= opts.waitItems) break
    await new Promise((r) => setTimeout(r, 500))
  }
  if (items.length < opts.waitItems) throw new Error(`only ${items.length} items indexed`)
  // Previews: warm in chunks and wait until ready.
  if (opts.previews.length) {
    for (let round = 0; round < 600; round++) {
      let pending = 0
      for (let i = 0; i < items.length; i += 500) {
        const ids = items.slice(i, i + 500).map((x) => x.id)
        const w = await call<{ items: Record<string, { status: string }>[] }>(base, "POST", "/v1/items/previews", { items: ids, previews: opts.previews }, auth)
        for (const it of w.json.items) for (const p of opts.previews) if (it[p]?.status === "pending") pending++
      }
      if (pending === 0) break
      await new Promise((r) => setTimeout(r, 1000))
    }
  }
  return {
    deviceKey: key,
    folderId: nas.json.id,
    items: items.map((x) => ({ id: x.id, kind: x.kind, mainFingerprint: x.mainFingerprint, name: x.files.find((f) => f.fingerprint === x.mainFingerprint)?.name ?? "" })),
  }
}

/** First-run setup only (setup code → admin password). */
export async function setupOnly(base: string, code: string) {
  const setup = await call(base, "POST", "/admin/api/setup", { code, password: PASSWORD }, { "X-Hoard-Admin": "1" })
  if (setup.status !== 200) throw new Error("setup failed " + JSON.stringify(setup.json))
}

export function save(name: string, state: E2EState) {
  writeFileSync(statePath(name), JSON.stringify(state, null, 2))
}

/**
 * A phone's backup (ADR 0024) for the trash e2e: pairs "E2E Pixel" (Hoard), adds the root DCIM, uploads each file as
 * the mirror's own upload, reports them, then reports them freed on the phone (server-only items, the only copies).
 */
export async function seedPhoneBackup(base: string, dir: string) {
  const manifest = JSON.parse(readFileSync(path.join(dir, "manifest.json"), "utf8")) as { name: string; sha256: string; fingerprint: string }[]
  const files = manifest.map((m) => ({ ...m, data: readFileSync(path.join(dir, m.name)) }))
  const admin = { "X-Hoard-Admin": "1" }
  const login = await call(base, "POST", "/admin/api/login", { password: PASSWORD }, admin)
  const cookie = login.cookie?.split(";")[0] ?? ""
  const pairing = await call<{ token: string }>(base, "POST", "/admin/api/pairings", { url: base }, { ...admin, Cookie: cookie })
  const pair = await call<{ apiKey: string }>(base, "POST", "/v1/pair", {
    token: pairing.json.token, deviceName: "E2E Pixel", platform: "android", appVersion: "2.9.0", app: "hoard", manufacturer: "Google", model: "Pixel 8",
  })
  if (pair.status !== 201) throw new Error("pair failed " + JSON.stringify(pair.json))
  const auth = { Authorization: `Bearer ${pair.json.apiKey}` }
  const root = await call<{ id: string }>(base, "POST", "/v1/backup/roots", { volume: "primary", path: "DCIM" }, auth)
  if (root.status !== 201) throw new Error("root failed " + JSON.stringify(root.json))
  for (const f of files) {
    const up = await call<{ uploadId?: string; status?: string }>(base, "POST", "/v1/uploads", {
      sha256: f.sha256, size: f.data.length, kind: "original", fingerprint: f.fingerprint, claim: false,
    }, auth)
    if (up.json.uploadId) {
      const res = await fetch(`${base}/v1/uploads/${up.json.uploadId}`, {
        method: "PATCH",
        headers: { ...auth, "Upload-Offset": "0", "Content-Type": "application/offset+octet-stream" },
        body: new Uint8Array(f.data),
      })
      if (res.status !== 204) throw new Error("upload failed " + res.status)
    }
  }
  const entries = files.map((f) => ({ path: `DCIM/Camera/${f.name}`, fingerprint: f.fingerprint, sha256: f.sha256, size: f.data.length, modifiedAt: 1762020000000 }))
  const rep = await call(base, "POST", "/v1/backup/report", { rootId: root.json.id, entries, removed: [] }, auth)
  if (rep.status !== 200) throw new Error("report failed " + JSON.stringify(rep.json))
  const freed = await call(base, "POST", "/v1/backup/report", {
    rootId: root.json.id, entries: [], removed: files.map((f) => ({ path: `DCIM/Camera/${f.name}`, reason: "freed" })),
  }, auth)
  if (freed.status !== 200) throw new Error("freed failed " + JSON.stringify(freed.json))
}
