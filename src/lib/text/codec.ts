import type { TextEncoding } from "@/plugins/smb/definitions"

/**
 * Text encoding detection and encoding, the same rules as the native `TextCodec.kt` (keep them in
 * step): the web mock decodes its sample files with this, and the tests pin the behaviour.
 *
 *  1. A byte order mark wins: EF BB BF → `utf-8-bom`, FF FE → `utf-16le`, FE FF → `utf-16be`.
 *  2. A NUL byte without a BOM → binary (`null`).
 *  3. Valid UTF-8 → `utf-8` (pure ASCII included).
 *  4. Anything else → `windows-1252` (lossless: every byte maps to one character and back).
 */

/** windows-1252 0x80–0x9F; the five holes (0x81, 0x8D, 0x8F, 0x90, 0x9D) stay C1 controls. */
const CP1252_HIGH = [
  0x20ac, 0x81, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021, 0x02c6, 0x2030, 0x0160, 0x2039,
  0x0152, 0x8d, 0x017d, 0x8f, 0x90, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x02dc,
  0x2122, 0x0161, 0x203a, 0x0153, 0x9d, 0x017e, 0x0178,
]
const CP1252_REVERSE = new Map(CP1252_HIGH.map((code, i) => [code, 0x80 + i]))

export interface DecodedText {
  text: string
  encoding: TextEncoding
}

/** Detect the encoding of `bytes` and decode them; null when the bytes look binary. */
export function decodeText(bytes: Uint8Array): DecodedText | null {
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return { text: utf8(bytes.subarray(3), false) ?? "", encoding: "utf-8-bom" }
  }
  if (bytes[0] === 0xff && bytes[1] === 0xfe) {
    return { text: utf16(bytes.subarray(2), true), encoding: "utf-16le" }
  }
  if (bytes[0] === 0xfe && bytes[1] === 0xff) {
    return { text: utf16(bytes.subarray(2), false), encoding: "utf-16be" }
  }
  if (bytes.includes(0)) return null
  const text = utf8(bytes, true)
  if (text !== null) return { text, encoding: "utf-8" }
  return { text: cp1252(bytes), encoding: "windows-1252" }
}

/**
 * Decode at most the first `max` bytes (`readTextFile({ truncate: true })`): the cut is moved
 * back to a character boundary first, so a split UTF-8 sequence does not make valid UTF-8 look
 * like windows-1252. Null when binary.
 */
export function decodeTextPrefix(bytes: Uint8Array, max: number): DecodedText | null {
  if (bytes.length <= max) return decodeText(bytes)
  const family: TextEncoding =
    bytes[0] === 0xff && bytes[1] === 0xfe
      ? "utf-16le"
      : bytes[0] === 0xfe && bytes[1] === 0xff
        ? "utf-16be"
        : "utf-8"
  return decodeText(cutAtCharBoundary(bytes, max, family))
}

/**
 * Cut `bytes` to at most `max` bytes without splitting a character of `encoding` (a UTF-8 sequence
 * or a UTF-16 code unit pair), for `readTextFile({ truncate: true })`.
 */
export function cutAtCharBoundary(bytes: Uint8Array, max: number, encoding: TextEncoding) {
  if (bytes.length <= max) return bytes
  let end = max
  if (encoding === "utf-8" || encoding === "utf-8-bom") {
    // Step back over continuation bytes (10xxxxxx) to the start of the cut character.
    let start = end
    while (start > 0 && (bytes[start] & 0xc0) === 0x80) start--
    end = start
  } else if (encoding === "utf-16le" || encoding === "utf-16be") {
    end -= end % 2
    const high = encoding === "utf-16le" ? bytes[end - 1] : bytes[end - 2]
    // A high surrogate (D800–DBFF) as the last unit would be half a character.
    if (end >= 2 && high >= 0xd8 && high <= 0xdb) end -= 2
  }
  return bytes.subarray(0, end)
}

/** Encode `text` for `writeTextFile`; throws when windows-1252 cannot hold a character. */
export function encodeText(text: string, encoding: TextEncoding): Uint8Array {
  switch (encoding) {
    case "utf-8":
      return new TextEncoder().encode(text)
    case "utf-8-bom": {
      const body = new TextEncoder().encode(text)
      const out = new Uint8Array(body.length + 3)
      out.set([0xef, 0xbb, 0xbf])
      out.set(body, 3)
      return out
    }
    case "utf-16le":
    case "utf-16be": {
      const le = encoding === "utf-16le"
      const out = new Uint8Array(2 + text.length * 2)
      out.set(le ? [0xff, 0xfe] : [0xfe, 0xff])
      for (let i = 0; i < text.length; i++) {
        const unit = text.charCodeAt(i)
        out[2 + i * 2] = le ? unit & 0xff : unit >> 8
        out[3 + i * 2] = le ? unit >> 8 : unit & 0xff
      }
      return out
    }
    case "windows-1252": {
      const out = new Uint8Array(text.length)
      for (let i = 0; i < text.length; i++) {
        const code = text.charCodeAt(i)
        const byte = code < 0x80 || (code >= 0xa0 && code <= 0xff) ? code : CP1252_REVERSE.get(code)
        if (byte === undefined) throw new UnencodableError(text[i])
        out[i] = byte
      }
      return out
    }
  }
}

/** True when `text` can be saved in `encoding` without losing a character. */
export function canEncode(text: string, encoding: TextEncoding): boolean {
  if (encoding !== "windows-1252") return true
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i)
    if (code >= 0x80 && !(code >= 0xa0 && code <= 0xff) && !CP1252_REVERSE.has(code)) return false
  }
  return true
}

export class UnencodableError extends Error {
  readonly character: string
  constructor(character: string) {
    super(`windows-1252 cannot encode "${character}"`)
    this.character = character
  }
}

function utf8(bytes: Uint8Array, fatal: boolean): string | null {
  try {
    return new TextDecoder("utf-8", { fatal, ignoreBOM: true }).decode(bytes)
  } catch {
    return null
  }
}

function utf16(bytes: Uint8Array, littleEndian: boolean): string {
  let text = ""
  const chunk: number[] = []
  for (let i = 0; i + 1 < bytes.length; i += 2) {
    chunk.push(littleEndian ? bytes[i] | (bytes[i + 1] << 8) : (bytes[i] << 8) | bytes[i + 1])
    if (chunk.length === 8192) {
      text += String.fromCharCode(...chunk)
      chunk.length = 0
    }
  }
  return text + String.fromCharCode(...chunk)
}

function cp1252(bytes: Uint8Array): string {
  let text = ""
  for (let i = 0; i < bytes.length; i += 8192) {
    const codes = Array.from(bytes.subarray(i, i + 8192), (b) =>
      b >= 0x80 && b < 0xa0 ? CP1252_HIGH[b - 0x80] : b
    )
    text += String.fromCharCode(...codes)
  }
  return text
}
