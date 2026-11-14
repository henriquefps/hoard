import { describe, expect, it } from "vitest"
import en from "./locales/en.json"
import ptBR from "./locales/pt-BR.json"

const keys = (o: object, prefix = ""): string[] =>
  Object.entries(o).flatMap(([k, v]) => (typeof v === "object" && v ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`]))

describe("locales", () => {
  it("English and Portuguese have the same keys", () => {
    expect(keys(ptBR).sort()).toEqual(keys(en).sort())
  })
})
