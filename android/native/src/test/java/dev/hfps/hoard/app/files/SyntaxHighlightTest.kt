package dev.hfps.hoard.app.files

import com.google.common.truth.Truth.assertThat
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Test

/** Phase 2d: grammar highlighting (Highlights) and its split per line for the code view. */
class SyntaxHighlightTest {
    private fun kinds(text: String, tokens: List<Token>) = tokens.map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun extensionsAndFencesPickAGrammar() {
        assertThat(grammarOf("kt")).isEqualTo(SyntaxLanguage.KOTLIN)
        assertThat(grammarOf("Kotlin")).isEqualTo(SyntaxLanguage.KOTLIN)
        assertThat(grammarOf("tsx")).isEqualTo(SyntaxLanguage.TYPESCRIPT)
        assertThat(grammarOf("bash")).isEqualTo(SyntaxLanguage.SHELL)
        assertThat(grammarOf("c++")).isEqualTo(SyntaxLanguage.CPP)
        // Formats without a grammar keep the light tokenizer.
        assertThat(grammarOf("json")).isNull()
        assertThat(grammarOf("yml")).isNull()
        assertThat(grammarTokens("{\"a\": 1}", "json")).isNull()
        assertThat(fenceExtension("yaml")).isEqualTo("yml")
        assertThat(fenceExtension("")).isEqualTo("txt")
    }

    @Test
    fun kotlinKeywordsStringsCommentsAndAnnotations() {
        val code = "@Composable\nfun greet(name: String) = \"hi \$name\" // say hi\nval n = 42"
        val tokens = kinds(code, grammarTokens(code, "kt")!!)
        assertThat(tokens).contains("fun" to TokenKind.Keyword)
        assertThat(tokens).contains("val" to TokenKind.Keyword)
        assertThat(tokens).contains("42" to TokenKind.Number)
        assertThat(tokens.any { it.second == TokenKind.String && it.first.contains("hi") }).isTrue()
        assertThat(tokens.any { it.second == TokenKind.Comment && it.first.contains("say hi") }).isTrue()
        assertThat(tokens.any { it.second == TokenKind.Annotation && it.first.contains("Composable") }).isTrue()
    }

    @Test
    fun aBlockCommentIsColouredOnEveryLineItSpans() {
        val code = "/* one\ntwo\nthree */\nreturn x"
        val tokens = grammarTokens(code, "js")!!
        val lines = tokensPerLine(code, tokens)
        assertThat(lines).hasSize(4)
        val split = code.split('\n')
        for (i in 0..2) {
            val comment = lines[i].filter { it.kind == TokenKind.Comment }
            assertThat(comment).isNotEmpty()
            assertThat(comment.all { it.start >= 0 && it.end <= split[i].length }).isTrue()
        }
        assertThat(lines[0].first { it.kind == TokenKind.Comment }.let { split[0].substring(it.start, it.end) }).isEqualTo("/* one")
        assertThat(lines[3].map { split[3].substring(it.start, it.end) to it.kind }).contains("return" to TokenKind.Keyword)
    }

    @Test
    fun tokensPerLineOffsetsAreRelativeToTheLine() {
        val text = "ab\ncd ef\n"
        val lines = tokensPerLine(text, listOf(Token(3, 5, TokenKind.Keyword), Token(1, 7, TokenKind.String)))
        assertThat(lines).hasSize(3)
        assertThat(lines[1]).contains(Token(0, 2, TokenKind.Keyword))
        assertThat(lines[0]).containsExactly(Token(1, 2, TokenKind.String))
        assertThat(lines[1]).contains(Token(0, 4, TokenKind.String))
        assertThat(lines[2]).isEmpty()
    }

    @Test
    fun hugeFilesSkipTheGrammar() {
        assertThat(grammarTokens("x".repeat(GRAMMAR_MAX_CHARS + 1), "kt")).isNull()
    }
}
