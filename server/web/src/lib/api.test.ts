import { describe, expect, it } from "vitest"
import { fileUrl, insecureDownloads } from "./api"

describe("downloads", () => {
  it("ends a file's URL with its name", () => {
    expect(fileUrl("i", "v1_ab", true, "Férias 01.JPG")).toBe("/web/items/i/files/v1_ab/F%C3%A9rias%2001.JPG?download=1")
    expect(fileUrl("i", "v1_ab")).toBe("/web/items/i/files/v1_ab")
  })
  it("knows when Chrome blocks downloads (plain HTTP, not this computer)", () => {
    expect(insecureDownloads({ protocol: "http:", hostname: "cachyos-fps" })).toBe(true)
    expect(insecureDownloads({ protocol: "http:", hostname: "100.87.163.92" })).toBe(true)
    expect(insecureDownloads({ protocol: "https:", hostname: "cachyos-fps.tail1234.ts.net" })).toBe(false)
    expect(insecureDownloads({ protocol: "http:", hostname: "localhost" })).toBe(false)
    expect(insecureDownloads({ protocol: "http:", hostname: "127.0.0.1" })).toBe(false)
    expect(insecureDownloads({ protocol: "http:", hostname: "[::1]" })).toBe(false)
  })
})
