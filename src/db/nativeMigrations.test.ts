import { describe, expect, it } from "vitest"
import kotlinMigrations from "../../android/core/data/src/main/java/dev/hfps/hoard/data/legacy/LegacyMigrations.kt?raw"
import { renderKotlinMigrations } from "../../scripts/legacy-db/migrationsKotlin"

// The native app (ADR 0019) upgrades an old Capacitor database with a Kotlin copy of these migrations before it
// imports it. A new migration here must reach that copy in the same change.
describe("native copy of the migrations", () => {
  it("is up to date (bun scripts/legacy-db/export-migrations.ts)", () => {
    expect(kotlinMigrations).toBe(renderKotlinMigrations())
  })
})
