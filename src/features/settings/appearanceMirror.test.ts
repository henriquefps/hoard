import initSqlJs from "sql.js"
import { describe, expect, it } from "vitest"
import { migrate } from "@/db/migrations"
import { sqljsDb } from "@/db/sqljsDb"
import { MIRROR_KEYS, mirrorAppearance, mirrorRows } from "./appearanceMirror"

describe("appearance mirror (ADR 0019)", () => {
  it("writes theme, palette, language and the text viewer's preferences as settings rows Hoard 2 imports", async () => {
    const SQL = await initSqlJs()
    const db = sqljsDb(new SQL.Database())
    await migrate(db)
    const rows = mirrorRows("dark", "olive", "pt-BR", { wrap: false, wrapCode: true, fontSize: 18 })
    await mirrorAppearance(db, rows)
    await mirrorAppearance(
      db,
      mirrorRows("light", "olive", "pt-BR", { wrap: false, wrapCode: true, fontSize: 18 })
    )
    const stored = new Map(
      (await db.query<{ key: string; value: string }>("SELECT key, value FROM settings")).map(
        (r) => [r.key, JSON.parse(r.value) as unknown]
      )
    )
    expect(stored.get(MIRROR_KEYS.theme)).toBe("light")
    expect(stored.get(MIRROR_KEYS.palette)).toBe("olive")
    expect(stored.get(MIRROR_KEYS.language)).toBe("pt-BR")
    expect(stored.get(MIRROR_KEYS.textViewer)).toEqual({
      wrap: false,
      wrapCode: true,
      fontSize: 18,
    })
  })
})
