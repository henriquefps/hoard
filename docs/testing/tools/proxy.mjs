// TCP proxy: node proxy.mjs <listenPort> <targetPort> [bytesPerSecond]
import net from "node:net"
const [listen, target, rate] = process.argv.slice(2).map(Number)
const server = net.createServer((client) => {
  const upstream = net.connect(target, "127.0.0.1")
  client.pipe(upstream)
  if (!rate) upstream.pipe(client)
  else {
    // throttle server -> client
    let budget = rate / 10
    const tick = setInterval(() => {
      budget = rate / 10
      upstream.resume()
    }, 100)
    upstream.on("data", (chunk) => {
      client.write(chunk)
      budget -= chunk.length
      if (budget <= 0) upstream.pause()
    })
    const stop = () => clearInterval(tick)
    upstream.on("close", stop)
    client.on("close", stop)
  }
  const kill = () => {
    client.destroy()
    upstream.destroy()
  }
  client.on("error", kill)
  upstream.on("error", kill)
  client.on("close", kill)
  upstream.on("close", kill)
})
server.listen(listen, "0.0.0.0", () =>
  console.log(`proxy ${listen} -> ${target} rate=${rate || "∞"}`)
)
