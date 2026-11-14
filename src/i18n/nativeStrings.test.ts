// @ts-expect-error -- Vitest runs in Node; the app's tsconfig has no Node types (the generator is a Python script).
import { execFileSync } from "node:child_process"
import { describe, expect, it } from "vitest"

// The native app (ADR 0019) says what Hoard 1 says: its Files side reads these keys as Android string resources
// generated from en.json and pt-BR.json. A changed string here must reach that copy in the same change.
describe("native copy of the strings", () => {
  it("is up to date (python3 android/scripts/strings/export_strings.py)", () => {
    const run = (): unknown =>
      execFileSync("python3", ["android/scripts/strings/export_strings.py", "--check"], {
        encoding: "utf8",
        stdio: "pipe",
      })
    expect(run).not.toThrow()
  })
})
