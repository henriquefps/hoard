import { readFileSync } from "node:fs"
import { describe, expect, it } from "vitest"
import { KOTLIN_MIGRATIONS_PATH, renderKotlinMigrations } from "../../scripts/legacy-db/migrationsKotlin"

// The native app (ADR 0019) upgrades an old Capacitor database with a Kotlin copy of these migrations before it
// imports it. A new migration here must reach that copy in the same change.
describe("native copy of the migrations", () => {
  it("is up to date (bun scripts/legacy-db/export-migrations.ts)", () => {
    expect(readFileSync(KOTLIN_MIGRATIONS_PATH, "utf8")).toBe(renderKotlinMigrations())
  })
})
