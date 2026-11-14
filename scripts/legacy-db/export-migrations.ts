/** Writes the Kotlin copy of `src/db/migrations.ts` for `:core:data` (ADR 0019). Run: `bun scripts/legacy-db/export-migrations.ts`. */
import { writeFileSync } from "node:fs"
import { KOTLIN_MIGRATIONS_PATH, renderKotlinMigrations } from "./migrationsKotlin"

writeFileSync(KOTLIN_MIGRATIONS_PATH, renderKotlinMigrations())
console.log(`wrote ${KOTLIN_MIGRATIONS_PATH}`)
