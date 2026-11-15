package dev.hfps.hoard.app.files

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage

/*
 * Syntax highlighting with per-language grammars (phase 2d, ADR 0019): SnipMe's Highlights (dev.snipme:highlights,
 * Apache-2.0, maintained, pure Kotlin) knows the keywords, literals, strings, comments (line and block, across lines)
 * and annotations of 17 languages. Formats it does not know (JSON, YAML, TOML, INI, SQL, XML, CSS…) keep the light
 * tokenizer of [highlightLine]. Highlights works on the whole text, so a block comment or a multi-line string is
 * coloured on every line it spans; the result is split per line for the code view (SyntaxHighlightTest).
 */

/** Above this many characters the grammar pass is skipped (the per-line tokenizer stays cheap at any size). */
const val GRAMMAR_MAX_CHARS = 256 * 1024

/** The grammar for a file extension or a Markdown fence's language, or null for the light tokenizer. */
fun grammarOf(extOrLanguage: String): SyntaxLanguage? = when (extOrLanguage.lowercase()) {
    "kt", "kts", "kotlin" -> SyntaxLanguage.KOTLIN
    "java" -> SyntaxLanguage.JAVA
    "js", "mjs", "cjs", "jsx", "javascript" -> SyntaxLanguage.JAVASCRIPT
    "ts", "tsx", "mts", "cts", "typescript" -> SyntaxLanguage.TYPESCRIPT
    "py", "pyw", "python" -> SyntaxLanguage.PYTHON
    "sh", "bash", "zsh", "ksh", "shell", "console" -> SyntaxLanguage.SHELL
    "go", "golang" -> SyntaxLanguage.GO
    "rs", "rust" -> SyntaxLanguage.RUST
    "c", "h" -> SyntaxLanguage.C
    "cpp", "cc", "cxx", "hpp", "hh", "hxx", "c++" -> SyntaxLanguage.CPP
    "cs", "csharp", "c#" -> SyntaxLanguage.CSHARP
    "swift" -> SyntaxLanguage.SWIFT
    "dart" -> SyntaxLanguage.DART
    "php" -> SyntaxLanguage.PHP
    "rb", "ruby", "gemspec", "rake" -> SyntaxLanguage.RUBY
    "pl", "pm", "perl" -> SyntaxLanguage.PERL
    "coffee", "coffeescript" -> SyntaxLanguage.COFFEESCRIPT
    else -> null
}

/** A Markdown fence's language as the extension the light tokenizer understands (`yaml` → `yml`…). */
fun fenceExtension(language: String): String = when (val l = language.lowercase()) {
    "yaml" -> "yml"
    "javascript" -> "js"
    "typescript" -> "ts"
    "python" -> "py"
    "shell", "bash", "zsh", "console" -> "sh"
    "markdown" -> "md"
    "" -> "txt"
    else -> l
}

/**
 * Tokens of the whole [text] (offsets into it). With a grammar for [ext] (and below [GRAMMAR_MAX_CHARS]): Highlights'
 * structure, strings and comments last so they win over a keyword inside them. Otherwise null: the caller tokenizes
 * each line with [highlightLine].
 */
fun grammarTokens(text: String, ext: String): List<Token>? {
    val language = grammarOf(ext) ?: return null
    if (text.length > GRAMMAR_MAX_CHARS) return null
    val structure = runCatching { Highlights.Builder().code(text).language(language).build().getCodeStructure() }.getOrNull() ?: return null
    val out = ArrayList<Token>()
    fun add(locations: Set<PhraseLocation>, kind: TokenKind) {
        for (l in locations) {
            val start = l.start.coerceIn(0, text.length)
            val end = l.end.coerceIn(start, text.length)
            if (end > start) out += Token(start, end, kind)
        }
    }
    add(structure.keywords, TokenKind.Keyword)
    add(structure.literals, TokenKind.Number)
    add(structure.annotations, TokenKind.Annotation)
    add(structure.strings, TokenKind.String)
    add(structure.comments, TokenKind.Comment)
    add(structure.multilineComments, TokenKind.Comment)
    return out
}

/**
 * [tokens] (offsets into [text]) cut per line, with offsets relative to each line; a token across a newline (a block
 * comment) is split. Lines are split on `\n` as the code view does.
 */
fun tokensPerLine(text: String, tokens: List<Token>): List<List<Token>> {
    val starts = ArrayList<Int>()
    starts += 0
    for (i in text.indices) if (text[i] == '\n') starts += i + 1
    val lines = List(starts.size) { ArrayList<Token>() }
    for (t in tokens) {
        var line = lineOf(starts, t.start)
        var from = t.start
        while (from < t.end && line < starts.size) {
            val lineStart = starts[line]
            val lineEnd = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
            val to = minOf(t.end, lineEnd)
            if (to > from) lines[line] += Token(from - lineStart, to - lineStart, t.kind)
            line++
            from = if (line < starts.size) starts[line] else t.end
        }
    }
    return lines
}

private fun lineOf(starts: List<Int>, offset: Int): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo
}
