package dev.hfps.hoard.app.files

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/*
 * The text viewer's pure rules, ported from src/lib/text/textFiles.ts, codec.ts, links.ts and the find bar
 * (TextLogicTest): formats, line endings, what an encoding can hold, the copy's name, JSON, CSV / TSV, Markdown blocks,
 * links inside Markdown, find and replace, and the tokens of the syntax highlighting.
 */

enum class TextFormat { Markdown, Json, Csv, Tsv, Mermaid, Plain }

val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

fun textFormat(name: String): TextFormat = when (val ext = extensionOf(name)) {
    in MARKDOWN_EXTENSIONS -> TextFormat.Markdown
    "json", "geojson" -> TextFormat.Json
    "csv" -> TextFormat.Csv
    "tsv" -> TextFormat.Tsv
    "mmd", "mermaid" -> TextFormat.Mermaid
    else -> if (ext.isEmpty()) TextFormat.Plain else TextFormat.Plain
}

/** Prose files soft-wrap by default; source code does not (prefs.ts). */
fun isProse(name: String): Boolean {
    val format = textFormat(name)
    if (format != TextFormat.Plain && format != TextFormat.Json) return true
    return extensionOf(name) in setOf("txt", "text", "log", "out", "nfo", "srt", "vtt", "rst", "adoc", "org", "")
}

/** Above these the rendered views are not built by default: the source view opens instead (RENDER_LIMITS). */
object RenderLimits {
    const val BYTES = 1024 * 1024
    const val CSV_ROWS = 5000
}

// --- Line endings ---------------------------------------------------------------------------------------------------

enum class LineEnding(val id: String, val separator: String) { Lf("lf", "\n"), Crlf("crlf", "\r\n"), Cr("cr", "\r") }

/** The file's line ending: the most common one (LF for a single-line or empty file). */
fun detectLineEnding(text: String): LineEnding {
    var crlf = 0
    var lf = 0
    var cr = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\r') {
            if (i + 1 < text.length && text[i + 1] == '\n') {
                crlf++
                i++
            } else {
                cr++
            }
        } else if (c == '\n') {
            lf++
        }
        i++
    }
    return when {
        crlf > lf && crlf >= cr -> LineEnding.Crlf
        cr > lf && cr > crlf -> LineEnding.Cr
        else -> LineEnding.Lf
    }
}

/** Editor text (always `\n`) with the file's own line ending. */
fun applyLineEnding(text: String, ending: LineEnding): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    return if (ending == LineEnding.Lf) normalized else normalized.replace("\n", ending.separator)
}

fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

fun countLines(text: String): Int {
    var lines = 1
    for (i in text.indices) {
        val c = text[i]
        if (c == '\n') lines++ else if (c == '\r' && (i + 1 >= text.length || text[i + 1] != '\n')) lines++
    }
    return lines
}

// --- Encodings ------------------------------------------------------------------------------------------------------

/** Windows-1252's characters in 0x80..0x9F (the rest of 0xA0..0xFF is Latin-1). */
private val CP1252_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ".toSet()

/** True when [text] can be saved in [encoding] without losing a character (codec.ts `canEncode`). */
fun canEncode(text: String, encoding: String): Boolean {
    if (encoding != "windows-1252") return true
    return text.all { c -> c.code < 0x80 || c.code in 0xA0..0xFF || c in CP1252_HIGH }
}

/** `notes.md` → `notes (1).md`, `notes (1).md` → `notes (2).md` (save as copy). */
fun nextFreeName(name: String, taken: (String) -> Boolean): String {
    val dot = name.lastIndexOf('.')
    val hasExt = dot > 0
    val base = (if (hasExt) name.substring(0, dot) else name).replace(Regex(" \\(\\d+\\)$"), "")
    val ext = if (hasExt) name.substring(dot) else ""
    for (n in 1 until 1000) {
        val candidate = "$base ($n)$ext"
        if (!taken(candidate)) return candidate
    }
    return "$base (${System.currentTimeMillis()})$ext"
}

// --- JSON and CSV ---------------------------------------------------------------------------------------------------

/**
 * Pretty-printed JSON as `JSON.stringify(value, null, 2)` writes it (keys in the file's order), or null when it does
 * not parse. The validated text is re-indented token by token, so it never depends on a JSON library's map order.
 */
fun prettyJson(text: String): String? {
    val trimmed = text.trim()
    val valid = runCatching { JSONTokener(trimmed).nextValue().let { it is JSONObject || it is JSONArray } }.getOrDefault(false)
    if (!valid) return null
    val out = StringBuilder()
    var indent = 0
    var inString = false
    var i = 0
    fun newline() {
        out.append('\n')
        repeat(indent) { out.append("  ") }
    }
    while (i < trimmed.length) {
        val c = trimmed[i]
        if (inString) {
            out.append(c)
            if (c == '\\' && i + 1 < trimmed.length) {
                out.append(trimmed[i + 1])
                i++
            } else if (c == '"') {
                inString = false
            }
            i++
            continue
        }
        when {
            c == '"' -> {
                inString = true
                out.append(c)
            }
            c == '{' || c == '[' -> {
                var j = i + 1
                while (j < trimmed.length && trimmed[j].isWhitespace()) j++
                if (j < trimmed.length && (trimmed[j] == '}' || trimmed[j] == ']')) {
                    out.append(c).append(trimmed[j])
                    i = j
                } else {
                    out.append(c)
                    indent++
                    newline()
                }
            }
            c == '}' || c == ']' -> {
                indent--
                newline()
                out.append(c)
            }
            c == ',' -> {
                out.append(c)
                newline()
            }
            c == ':' -> out.append(": ")
            c.isWhitespace() -> Unit
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** RFC 4180 CSV (quoted fields, doubled quotes, newlines inside quotes); stops after [maxRows]. */
fun parseDelimited(text: String, delimiter: Char = ',', maxRows: Int = Int.MAX_VALUE): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else {
                field.append(c)
            }
            i++
            continue
        }
        when {
            c == '"' && field.isEmpty() -> quoted = true
            c == delimiter -> {
                row += field.toString()
                field.clear()
            }
            c == '\n' || c == '\r' -> {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row += field.toString()
                rows += row
                row = ArrayList()
                field.clear()
                if (rows.size >= maxRows) return rows
            }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        rows += row
    }
    return rows
}

// --- Find and replace -----------------------------------------------------------------------------------------------

/** Every match of [query] in [text] as start offsets (non-overlapping). */
fun findMatches(text: String, query: String, matchCase: Boolean): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = !matchCase)
        if (at < 0) break
        out += at
        from = at + query.length
    }
    return out
}

/** [text] with the match at [at] replaced. */
fun replaceAt(text: String, at: Int, length: Int, replacement: String): String = text.substring(0, at) + replacement + text.substring(at + length)

/** [text] with every match replaced, and how many were. */
fun replaceAll(text: String, query: String, replacement: String, matchCase: Boolean): Pair<String, Int> {
    val matches = findMatches(text, query, matchCase)
    if (matches.isEmpty()) return text to 0
    val out = StringBuilder()
    var last = 0
    for (at in matches) {
        out.append(text, last, at).append(replacement)
        last = at + query.length
    }
    out.append(text, last, text.length)
    return out.toString() to matches.size
}

// --- Markdown -------------------------------------------------------------------------------------------------------

/** One block of a Markdown document, as the native preview draws it (GFM's common subset). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String, val id: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItems(val ordered: Boolean, val items: List<Pair<Int, String>>, val start: Int = 1) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** GitHub-style heading id (`## Hello, World!` → `hello-world`), for `#anchor` links. */
fun headingId(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(Regex("\\s+"), "-")

private val FENCE = Regex("^\\s{0,3}(```+|~~~+)\\s*([^`\\s]*).*$")
private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

private fun tableCells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** Markdown → blocks. Inline markup stays in the text ([inlineSpans] reads it). */
fun parseMarkdown(source: String): List<MdBlock> {
    val lines = normalizeNewlines(source).split('\n')
    val blocks = ArrayList<MdBlock>()
    var i = 0
    val paragraph = ArrayList<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.joinToString(" ") { it.trim() })
            paragraph.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.matchEntire(line)
        when {
            fence != null -> {
                flush()
                val marker = fence.groupValues[1]
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                blocks += MdBlock.Code(fence.groupValues[2].lowercase(), code.joinToString("\n"))
            }
            line.isBlank() -> flush()
            HEADING.matchEntire(line) != null -> {
                flush()
                val m = HEADING.matchEntire(line)!!
                val text = m.groupValues[2]
                blocks += MdBlock.Heading(m.groupValues[1].length, text, headingId(stripInline(text)))
            }
            RULE.matches(line) -> {
                flush()
                blocks += MdBlock.Rule
            }
            line.trimStart().startsWith(">") -> {
                flush()
                val quoted = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) quoted += lines[i++].trimStart().removePrefix(">").removePrefix(" ")
                blocks += MdBlock.Quote(parseMarkdown(quoted.joinToString("\n")))
                continue
            }
            BULLET.matches(line) || ORDERED.matches(line) -> {
                flush()
                val ordered = ORDERED.matches(line) && !BULLET.matches(line)
                val items = ArrayList<Pair<Int, String>>()
                val start = ORDERED.matchEntire(line)?.groupValues?.get(2)?.toIntOrNull() ?: 1
                while (i < lines.size) {
                    val l = lines[i]
                    val b = BULLET.matchEntire(l)
                    val o = ORDERED.matchEntire(l)
                    when {
                        b != null -> items += (b.groupValues[1].length / 2) to b.groupValues[2]
                        o != null -> items += (o.groupValues[1].length / 2) to o.groupValues[3]
                        l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty() -> items[items.lastIndex] = items.last().first to (items.last().second + " " + l.trim())
                        else -> break
                    }
                    i++
                }
                blocks += MdBlock.ListItems(ordered, items, start)
                continue
            }
            line.contains('|') && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                flush()
                val header = tableCells(line)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += tableCells(lines[i++])
                blocks += MdBlock.Table(header, rows)
                continue
            }
            else -> paragraph += line
        }
        i++
    }
    flush()
    return blocks
}

/** One run of inline text with its style. */
data class InlineSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null, val strike: Boolean = false)

private val INLINE = Regex("""(`[^`]+`)|(\*\*[^*]+\*\*|__[^_]+__)|(\*[^*\s][^*]*\*|_[^_\s][^_]*_)|(~~[^~]+~~)|(!?\[[^\]]*]\([^)\s]*(?:\s+"[^"]*")?\))|(<https?://[^>]+>)""")

/** Inline Markdown (code, bold, italic, strike, links, autolinks) as spans; images become their alt text. */
fun inlineSpans(text: String): List<InlineSpan> {
    val out = ArrayList<InlineSpan>()
    var last = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > last) out += InlineSpan(text.substring(last, m.range.first))
        val v = m.value
        out += when {
            m.groups[1] != null -> InlineSpan(v.trim('`'), code = true)
            m.groups[2] != null -> InlineSpan(v.substring(2, v.length - 2), bold = true)
            m.groups[3] != null -> InlineSpan(v.substring(1, v.length - 1), italic = true)
            m.groups[4] != null -> InlineSpan(v.substring(2, v.length - 2), strike = true)
            m.groups[6] != null -> InlineSpan(v.trim('<', '>'), link = v.trim('<', '>'))
            else -> {
                val label = v.substringAfter('[').substringBeforeLast("](")
                val href = v.substringAfterLast("](").removeSuffix(")").substringBefore(' ')
                if (v.startsWith("!")) InlineSpan(label, italic = true) else InlineSpan(label, link = href)
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) out += InlineSpan(text.substring(last))
    return out
}

fun stripInline(text: String): String = inlineSpans(text).joinToString("") { it.text }

/** Where a link inside a Markdown file points (links.ts `resolveLink`). */
sealed interface LinkTarget {
    data class External(val url: String) : LinkTarget
    data class Anchor(val id: String) : LinkTarget
    data class Share(val path: String, val isFolder: Boolean) : LinkTarget
    data object Blocked : LinkTarget
}

fun resolveLink(filePath: String, href: String): LinkTarget {
    val raw = href.trim()
    if (raw.isEmpty()) return LinkTarget.Blocked
    if (Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return LinkTarget.External(raw)
    if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(raw) || raw.startsWith("//")) return LinkTarget.Blocked
    if (raw.startsWith("#")) return LinkTarget.Anchor(java.net.URLDecoder.decode(raw.substring(1), "UTF-8"))
    val pathPart = raw.substringBefore('#').substringBefore('?')
    val isFolder = pathPart.endsWith("/")
    val segments = if (pathPart.startsWith("/")) ArrayList() else filePath.split('/').filter { it.isNotEmpty() }.dropLast(1).toMutableList()
    for (segment in pathPart.split('/')) {
        val name = runCatching { java.net.URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
        when (name) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return LinkTarget.Blocked else segments.removeAt(segments.lastIndex)
            else -> segments += name
        }
    }
    return LinkTarget.Share(segments.joinToString("/"), isFolder)
}

// --- Syntax highlighting --------------------------------------------------------------------------------------------

enum class TokenKind { Keyword, String, Comment, Number, Plain }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do", "return", "import", "package",
    "function", "const", "let", "new", "this", "true", "false", "null", "undefined", "nil", "def", "self", "None", "True", "False",
    "public", "private", "protected", "static", "final", "void", "int", "long", "double", "float", "boolean", "string", "struct",
    "enum", "export", "from", "as", "async", "await", "try", "catch", "finally", "throw", "throws", "extends", "implements",
    "in", "is", "not", "and", "or", "with", "yield", "lambda", "elif", "pass", "break", "continue", "switch", "case", "default",
    "type", "go", "func", "map", "select", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "echo", "then", "fi",
)

/** Comment style of a file's language: line comment markers and whether `/* */` and `#` apply. */
private fun commentMarkers(ext: String): List<String> = when (ext) {
    "py", "sh", "bash", "zsh", "rb", "yaml", "yml", "toml", "conf", "ini", "properties", "r", "pl", "dockerfile", "make", "cmake" -> listOf("#")
    "sql", "lua", "hs" -> listOf("--")
    "bat", "cmd" -> listOf("REM ", "::")
    "json", "csv", "tsv", "txt", "log", "md" -> emptyList()
    else -> listOf("//")
}

/** Tokens of one source line (keywords, strings, comments, numbers) for the code view's colours. */
fun highlightLine(line: String, ext: String): List<Token> {
    val tokens = ArrayList<Token>()
    val markers = commentMarkers(ext)
    var i = 0
    while (i < line.length) {
        val c = line[i]
        val marker = markers.firstOrNull { line.startsWith(it, i) }
        when {
            marker != null || (ext !in setOf("py", "sh", "yaml", "yml") && line.startsWith("/*", i)) || line.startsWith("<!--", i) -> {
                tokens += Token(i, line.length, TokenKind.Comment)
                return tokens
            }
            c == '"' || c == '\'' || c == '`' -> {
                var j = i + 1
                while (j < line.length && line[j] != c) j += if (line[j] == '\\') 2 else 1
                val end = minOf(line.length, j + 1)
                tokens += Token(i, end, TokenKind.String)
                i = end
                continue
            }
            c.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit()) -> {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                tokens += Token(i, j, TokenKind.Number)
                i = j
                continue
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                if (line.substring(i, j) in KEYWORDS) tokens += Token(i, j, TokenKind.Keyword)
                i = j
                continue
            }
        }
        i++
    }
    return tokens
}

/** Code views highlight these; prose and data files stay plain. */
fun highlights(name: String): Boolean = extensionOf(name) !in setOf("txt", "text", "log", "out", "nfo", "srt", "vtt", "csv", "tsv", "md", "markdown", "")
