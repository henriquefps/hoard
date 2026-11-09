// TCP proxy: node proxy.mjs <listenPort> <targetPort> [bytesPerSecond] [both]
// Throttles server → client (downloads); with `both`, client → server too (uploads, ADR 0007).
// Kill it (or `kill -STOP` it) to drop a route mid-transfer.
import net from "node:net"
const [listen, target, rate] = process.argv.slice(2, 5).map(Number)
const both = process.argv[5] === "both"

/** Pipe `from` → `to`, at most `rate` bytes per second when set. */
function pipe(from, to) {
  if (!rate) {
    from.pipe(to)
    return () => {}
  }
  let budget = rate / 10
  const tick = setInterval(() => {
    budget = rate / 10
    from.resume()
  }, 100)
  from.on("data", (chunk) => {
    to.write(chunk)
    budget -= chunk.length
    if (budget <= 0) from.pause()
  })
  return () => clearInterval(tick)
}

const server = net.createServer((client) => {
  const upstream = net.connect(target, "127.0.0.1")
  const stops = [both ? pipe(client, upstream) : (client.pipe(upstream), () => {}), pipe(upstream, client)]
  const kill = () => {
    stops.forEach((stop) => stop())
    client.destroy()
    upstream.destroy()
  }
  client.on("error", kill)
  upstream.on("error", kill)
  client.on("close", kill)
  upstream.on("close", kill)
})
server.listen(listen, "0.0.0.0", () =>
  console.log(`proxy ${listen} -> ${target} rate=${rate || "∞"}${both ? " both ways" : ""}`)
)
