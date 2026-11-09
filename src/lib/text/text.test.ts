import { describe, expect, it } from "vitest"
import {
  canEncode,
  cutAtCharBoundary,
  decodeText,
  decodeTextPrefix,
  encodeText,
  UnencodableError,
} from "./codec"
import { resolveLink } from "./links"
import {
  applyLineEnding,
  countLines,
  detectLineEnding,
  isTextFile,
  longestLine,
  nextFreeName,
  opensInViewer,
  parseDelimited,
  prettyJson,
  textFormat,
} from "./textFiles"

const bytes = (...values: number[]) => new Uint8Array(values)
const utf8 = (text: string) => new TextEncoder().encode(text)

describe("text codec", () => {
  it("detects UTF-8, with and without a byte order mark", () => {
    expect(decodeText(utf8("Olá, mundo ✓"))).toEqual({ text: "Olá, mundo ✓", encoding: "utf-8" })
    expect(decodeText(bytes(0xef, 0xbb, 0xbf, 0x68, 0x69))).toEqual({
      text: "hi",
      encoding: "utf-8-bom",
    })
    expect(decodeText(new Uint8Array(0))).toEqual({ text: "", encoding: "utf-8" })
  })

  it("detects UTF-16 from the byte order mark, both byte orders", () => {
    expect(decodeText(bytes(0xff, 0xfe, 0x68, 0x00, 0xe5, 0x65))).toEqual({
      text: "h日",
      encoding: "utf-16le",
    })
    expect(decodeText(bytes(0xfe, 0xff, 0x00, 0x68, 0x65, 0xe5))).toEqual({
      text: "h日",
      encoding: "utf-16be",
    })
  })

  it("falls back to windows-1252 for invalid UTF-8, losslessly", () => {
    const latin = bytes(
      0x52,
      0x65,
      0x6c,
      0x61,
      0x74,
      0xf3,
      0x72,
      0x69,
      0x6f,
      0x20,
      0x80,
      0x97,
      0x81
    )
    const decoded = decodeText(latin)!
    expect(decoded.encoding).toBe("windows-1252")
    expect(decoded.text).toBe("Relatório €—\u0081")
    // Every byte, including the five windows-1252 leaves undefined, comes back unchanged.
    const all = Uint8Array.from({ length: 255 }, (_, i) => i + 1)
    expect(encodeText(decodeText(all)!.text, "windows-1252")).toEqual(all)
  })

  it("treats NUL bytes without a BOM as binary", () => {
    expect(decodeText(bytes(0x7f, 0x45, 0x4c, 0x46, 0x00))).toBeNull()
  })

  it("round-trips every encoding", () => {
    const text = "Line 1\r\nação ✓ 日本 😀\n"
    for (const encoding of ["utf-8", "utf-8-bom", "utf-16le", "utf-16be"] as const) {
      expect(decodeText(encodeText(text, encoding))).toEqual({ text, encoding })
    }
    expect(decodeText(encodeText("ação €", "windows-1252"))).toEqual({
      text: "ação €",
      encoding: "windows-1252",
    })
  })

  it("refuses characters windows-1252 cannot hold", () => {
    expect(canEncode("ação €", "windows-1252")).toBe(true)
    expect(canEncode("日本", "windows-1252")).toBe(false)
    expect(canEncode("日本", "utf-8")).toBe(true)
    expect(() => encodeText("✓", "windows-1252")).toThrow(UnencodableError)
  })

  it("cuts a prefix at a character boundary", () => {
    const text = utf8("aé日😀")
    // 1 + 2 + 3 + 4 bytes: a cut inside 日 or 😀 steps back to the previous character.
    expect(decodeTextPrefix(text, 5)).toEqual({ text: "aé", encoding: "utf-8" })
    expect(decodeTextPrefix(text, 7)).toEqual({ text: "aé日", encoding: "utf-8" })
    expect(decodeTextPrefix(text, 10)).toEqual({ text: "aé日😀", encoding: "utf-8" })
    const utf16 = encodeText("a😀b", "utf-16le") // BOM + a + surrogate pair + b = 10 bytes
    expect(cutAtCharBoundary(utf16, 7, "utf-16le").length).toBe(4)
    expect(decodeTextPrefix(utf16, 8)).toEqual({ text: "a😀", encoding: "utf-16le" })
  })
})

describe("text files", () => {
  it("recognises text files by extension and by name", () => {
    for (const name of ["a.md", "x.LOG", "c.json", "Dockerfile", "README", ".bashrc", "k.kt"]) {
      expect(isTextFile(name), name).toBe(true)
    }
    for (const name of ["a.jpg", "b.mkv", "c.pdf", "no-extension-at-all", "d.zip"]) {
      expect(isTextFile(name), name).toBe(false)
    }
    expect(opensInViewer({ name: "a.txt", size: 10, isDirectory: false })).toBe(true)
    expect(opensInViewer({ name: "index.html", size: 10, isDirectory: false })).toBe(false)
    expect(opensInViewer({ name: "a.txt", size: 6 * 1024 * 1024, isDirectory: false })).toBe(false)
    expect(textFormat("notes.MD")).toBe("markdown")
    expect(textFormat("x.csv")).toBe("csv")
    expect(textFormat("x.yml")).toBe("plain")
  })

  it("detects and re-applies line endings", () => {
    expect(detectLineEnding("a\r\nb\r\n")).toBe("crlf")
    expect(detectLineEnding("a\nb\n")).toBe("lf")
    expect(detectLineEnding("a\rb\r")).toBe("cr")
    expect(detectLineEnding("")).toBe("lf")
    expect(detectLineEnding("one line")).toBe("lf")
    expect(applyLineEnding("a\nb\n", "crlf")).toBe("a\r\nb\r\n")
    expect(applyLineEnding("a\r\nb\n", "lf")).toBe("a\nb\n")
    expect(applyLineEnding("a\nb", "cr")).toBe("a\rb")
  })

  it("counts lines and the longest line", () => {
    expect(countLines("")).toBe(1)
    expect(countLines("a\nb\r\nc\rd")).toBe(4)
    expect(longestLine("ab\nabcd\r\nx")).toBe(4)
  })

  it("parses CSV with quotes, doubled quotes and newlines inside fields", () => {
    expect(parseDelimited('a,b,c\n1,"x, y","say ""hi"""\n2,"multi\nline",\n')).toEqual([
      ["a", "b", "c"],
      ["1", "x, y", 'say "hi"'],
      ["2", "multi\nline", ""],
    ])
    expect(parseDelimited("a\tb\r\n1\t2", "\t")).toEqual([
      ["a", "b"],
      ["1", "2"],
    ])
    expect(parseDelimited("1\n2\n3\n", ",", 2)).toEqual([["1"], ["2"]])
  })

  it("pretty-prints JSON or says it cannot", () => {
    expect(prettyJson('{"a":[1,2]}')).toBe('{\n  "a": [\n    1,\n    2\n  ]\n}')
    expect(prettyJson("{nope")).toBeNull()
  })

  it("finds a free name for a copy", () => {
    const taken = new Set(["notes (1).md"])
    expect(nextFreeName("notes.md", (n) => taken.has(n))).toBe("notes (2).md")
    expect(nextFreeName("notes (2).md", () => false)).toBe("notes (1).md")
    expect(nextFreeName("Makefile", () => false)).toBe("Makefile (1)")
  })
})

describe("markdown links", () => {
  const file = "henrique/Documents/notes.md"
  it("resolves relative paths against the file's folder", () => {
    expect(resolveLink(file, "todo.md")).toEqual({
      kind: "share",
      path: "henrique/Documents/todo.md",
      isFolder: false,
    })
    expect(resolveLink(file, "./img/network.png")).toMatchObject({
      path: "henrique/Documents/img/network.png",
    })
    expect(resolveLink(file, "../Projects/hoard/README.md#install")).toEqual({
      kind: "share",
      path: "henrique/Projects/hoard/README.md",
      hash: "install",
      isFolder: false,
    })
    expect(resolveLink(file, "/henrique/Downloads/")).toEqual({
      kind: "share",
      path: "henrique/Downloads",
      isFolder: true,
    })
    expect(resolveLink(file, "Contrato%20de%20loca%C3%A7%C3%A3o.pdf?x=1")).toMatchObject({
      path: "henrique/Documents/Contrato de locação.pdf",
    })
  })

  it("keeps anchors, external links and data images apart, and blocks the rest", () => {
    expect(resolveLink(file, "#tasks")).toEqual({ kind: "anchor", id: "tasks" })
    expect(resolveLink(file, "https://example.com/a")).toEqual({
      kind: "external",
      url: "https://example.com/a",
    })
    expect(resolveLink(file, "mailto:a@b.c").kind).toBe("external")
    expect(resolveLink(file, "data:image/png;base64,AAAA").kind).toBe("data")
    expect(resolveLink(file, "javascript:alert(1)").kind).toBe("blocked")
    expect(resolveLink(file, "file:///etc/passwd").kind).toBe("blocked")
    expect(resolveLink(file, "//evil.example/x").kind).toBe("blocked")
    expect(resolveLink(file, "../../../outside.md").kind).toBe("blocked")
    expect(resolveLink(file, "").kind).toBe("blocked")
  })
})

describe("wrap defaults", () => {
  it("treats prose and code differently", async () => {
    const { isProse } = await import("./textFiles")
    expect(isProse("notes.md")).toBe(true)
    expect(isProse("server.log")).toBe(true)
    expect(isProse("README")).toBe(true)
    expect(isProse("data.csv")).toBe(true)
    expect(isProse("MainActivity.kt")).toBe(false)
    expect(isProse("package.json")).toBe(false)
  })
})
