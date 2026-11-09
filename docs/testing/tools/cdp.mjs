// node cdp.mjs '<js expression>'   — evaluates in the Hoard WebView (awaits promises), prints JSON
import { execSync } from "node:child_process"
// Always an explicit device: a plugged-in phone must never receive test commands.
const adb = `${process.env.HOME}/Library/Android/sdk/platform-tools/adb -s ${process.env.ANDROID_SERIAL ?? "emulator-5554"}`
const pid = execSync(`${adb} shell pidof dev.hfps.hoard`).toString().trim().split(/\s+/)[0]
execSync(`${adb} forward tcp:9333 localabstract:webview_devtools_remote_${pid}`)
const pages = await (await fetch("http://127.0.0.1:9333/json")).json()
const page = pages.find((p) => p.type === "page") ?? pages[0]
const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((r) => (ws.onopen = r))
const expr = process.argv[2]
ws.send(
  JSON.stringify({
    id: 1,
    method: "Runtime.evaluate",
    params: { expression: expr, awaitPromise: true, returnByValue: true },
  })
)
ws.onmessage = (m) => {
  const msg = JSON.parse(m.data)
  if (msg.id !== 1) return
  const r = msg.result
  if (r.exceptionDetails)
    console.log(
      "EXC",
      JSON.stringify(r.exceptionDetails.exception?.description ?? r.exceptionDetails)
    )
  else
    console.log(
      typeof r.result.value === "string" ? r.result.value : JSON.stringify(r.result.value, null, 1)
    )
  ws.close()
  process.exit(0)
}
