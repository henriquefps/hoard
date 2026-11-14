import { defineConfig, devices } from "@playwright/test"

// End-to-end tests of the web gallery against a real hoard-server binary (ADR 0018 "Tests"). global-setup.ts builds
// the binary with the web build embedded, writes a synthetic library (Go: TestWriteWebFixture), starts the server on
// a temporary data directory and port, and signs in. Run: `bun run e2e` (needs Go, ffmpeg and vips on PATH).
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  workers: 1,
  fullyParallel: false,
  reporter: [["list"]],
  globalSetup: "./e2e/global-setup.ts",
  globalTeardown: "./e2e/global-teardown.ts",
  use: {
    baseURL: `http://127.0.0.1:${process.env.HOARD_E2E_PORT ?? "3990"}`,
    viewport: { width: 1440, height: 900 },
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } }],
})
