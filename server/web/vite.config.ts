import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vitest/config"
import pkg from "./package.json" with { type: "json" }

// The build goes into the Go package that embeds it (server/internal/webui, ADR 0018). `bun run dev` proxies the
// API to a local hoard-server (HOARD_SERVER, default http://127.0.0.1:3071).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  build: {
    outDir: "../internal/webui/dist",
    emptyOutDir: false,
    chunkSizeWarningLimit: 800,
  },
  server: {
    proxy: { "/web": process.env.HOARD_SERVER ?? "http://127.0.0.1:3071" },
  },
  resolve: { alias: { "@": path.resolve(import.meta.dirname, "./src") } },
  test: { environment: "node", include: ["src/**/*.test.ts"] },
})
