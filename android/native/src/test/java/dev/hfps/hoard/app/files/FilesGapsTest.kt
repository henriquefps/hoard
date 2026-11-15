package dev.hfps.hoard.app.files

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.data.AppLanguage
import dev.hfps.hoard.app.transfers.TransferView
import dev.hfps.hoard.app.transfers.TransfersIndicator
import dev.hfps.hoard.app.ui.common.DragReorder
import dev.hfps.hoard.app.ui.common.moved
import dev.hfps.hoard.app.ui.viewer.TextPrefs
import dev.hfps.hoard.data.TransferEntity
import dev.hfps.hoard.smb.transfer.TransferStates
import org.junit.Test
import java.time.Instant

/**
 * Phase 2c's Files gaps: the text viewer's rules (port of src/lib/text/text.test.ts), Markdown blocks, find and
 * replace, the transfers ring, drag reorder, the language and the text viewer's remembered preferences.
 */
class FilesGapsTest {
    @Test
    fun `formats, prose and line endings`() {
        assertThat(textFormat("notes.MD")).isEqualTo(TextFormat.Markdown)
        assertThat(textFormat("x.csv")).isEqualTo(TextFormat.Csv)
        assertThat(textFormat("x.tsv")).isEqualTo(TextFormat.Tsv)
        assertThat(textFormat("flow.mmd")).isEqualTo(TextFormat.Mermaid)
        assertThat(textFormat("x.yml")).isEqualTo(TextFormat.Plain)
        assertThat(isProse("notes.md")).isTrue()
        assertThat(isProse("server.log")).isTrue()
        assertThat(isProse("Main.kt")).isFalse()
        assertThat(detectLineEnding("a\r\nb\r\n")).isEqualTo(LineEnding.Crlf)
        assertThat(detectLineEnding("a\nb\n")).isEqualTo(LineEnding.Lf)
        assertThat(detectLineEnding("a\rb\r")).isEqualTo(LineEnding.Cr)
        assertThat(detectLineEnding("")).isEqualTo(LineEnding.Lf)
        assertThat(applyLineEnding("a\nb\n", LineEnding.Crlf)).isEqualTo("a\r\nb\r\n")
        assertThat(applyLineEnding("a\r\nb\n", LineEnding.Lf)).isEqualTo("a\nb\n")
        assertThat(applyLineEnding("a\nb", LineEnding.Cr)).isEqualTo("a\rb")
        assertThat(countLines("")).isEqualTo(1)
        assertThat(countLines("a\nb\r\nc\rd")).isEqualTo(4)
    }

    @Test
    fun `encodings, copies, JSON and CSV`() {
        assertThat(canEncode("ação €", "windows-1252")).isTrue()
        assertThat(canEncode("日本", "windows-1252")).isFalse()
        assertThat(canEncode("日本", "utf-8")).isTrue()
        val taken = setOf("notes (1).md")
        assertThat(nextFreeName("notes.md") { it in taken }).isEqualTo("notes (2).md")
        assertThat(nextFreeName("notes (2).md") { false }).isEqualTo("notes (1).md")
        assertThat(nextFreeName("Makefile") { false }).isEqualTo("Makefile (1)")
        assertThat(prettyJson("{\"a\":[1,2]}")).isEqualTo("{\n  \"a\": [\n    1,\n    2\n  ]\n}")
        assertThat(prettyJson("{nope")).isNull()
        assertThat(parseDelimited("a,b,c\n1,\"x, y\",\"say \"\"hi\"\"\"\n2,\"multi\nline\",\n")).containsExactly(
            listOf("a", "b", "c"),
            listOf("1", "x, y", "say \"hi\""),
            listOf("2", "multi\nline", ""),
        ).inOrder()
        assertThat(parseDelimited("a\tb\r\n1\t2", '\t')).containsExactly(listOf("a", "b"), listOf("1", "2")).inOrder()
        assertThat(parseDelimited("1\n2\n3\n", ',', 2)).containsExactly(listOf("1"), listOf("2")).inOrder()
    }

    @Test
    fun `links inside Markdown resolve like a browser, never above the share`() {
        val file = "henrique/Documents/notes.md"
        assertThat(resolveLink(file, "todo.md")).isEqualTo(LinkTarget.Share("henrique/Documents/todo.md", false))
        assertThat(resolveLink(file, "./img/network.png")).isEqualTo(LinkTarget.Share("henrique/Documents/img/network.png", false))
        assertThat(resolveLink(file, "../Projects/hoard/README.md#install")).isEqualTo(LinkTarget.Share("henrique/Projects/hoard/README.md", false))
        assertThat(resolveLink(file, "/henrique/Downloads/")).isEqualTo(LinkTarget.Share("henrique/Downloads", true))
        assertThat(resolveLink(file, "Contrato%20de%20loca%C3%A7%C3%A3o.pdf?x=1")).isEqualTo(LinkTarget.Share("henrique/Documents/Contrato de locação.pdf", false))
        assertThat(resolveLink(file, "#tasks")).isEqualTo(LinkTarget.Anchor("tasks"))
        assertThat(resolveLink(file, "https://example.com/a")).isEqualTo(LinkTarget.External("https://example.com/a"))
        assertThat(resolveLink(file, "mailto:a@b.c")).isInstanceOf(LinkTarget.External::class.java)
        listOf("javascript:alert(1)", "file:///etc/passwd", "//evil.example/x", "../../../outside.md", "").forEach {
            assertThat(resolveLink(file, it)).isEqualTo(LinkTarget.Blocked)
        }
    }

    @Test
    fun `Markdown blocks and inline spans`() {
        val blocks = parseMarkdown(
            """
            # Title
            Some *em* and **strong** with `code` and [a link](https://x.y).

            - one
              more
            - two
              - nested

            1. first
            2. second

            > quoted

            ```kotlin
            val x = 1
            ```

            | a | b |
            |---|---|
            | 1 | 2 |

            ---
            """.trimIndent(),
        )
        assertThat(blocks[0]).isEqualTo(MdBlock.Heading(1, "Title", "title"))
        assertThat(blocks[1]).isInstanceOf(MdBlock.Paragraph::class.java)
        assertThat((blocks[2] as MdBlock.ListItems).items).containsExactly(0 to "one more", 0 to "two", 1 to "nested").inOrder()
        assertThat((blocks[3] as MdBlock.ListItems).ordered).isTrue()
        assertThat(blocks[4]).isInstanceOf(MdBlock.Quote::class.java)
        assertThat(blocks[5]).isEqualTo(MdBlock.Code("kotlin", "val x = 1"))
        assertThat(blocks[6]).isEqualTo(MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "2"))))
        assertThat(blocks[7]).isEqualTo(MdBlock.Rule)
        val spans = inlineSpans("Some *em* and **strong** with `code` and [a link](https://x.y).")
        assertThat(spans.filter { it.italic }.map { it.text }).containsExactly("em")
        assertThat(spans.filter { it.bold }.map { it.text }).containsExactly("strong")
        assertThat(spans.filter { it.code }.map { it.text }).containsExactly("code")
        assertThat(spans.single { it.link != null }).isEqualTo(InlineSpan("a link", link = "https://x.y"))
        assertThat(headingId("Hello, World!")).isEqualTo("hello-world")
    }

    @Test
    fun `find, replace and highlighting`() {
        assertThat(findMatches("Foo foo FOO", "foo", matchCase = false)).containsExactly(0, 4, 8).inOrder()
        assertThat(findMatches("Foo foo FOO", "foo", matchCase = true)).containsExactly(4)
        assertThat(replaceAll("a-b-c", "-", "+", false)).isEqualTo("a+b+c" to 2)
        assertThat(replaceAt("hello world", 6, 5, "there")).isEqualTo("hello there")
        val tokens = highlightLine("val name = \"x\" // note", "kt")
        assertThat(tokens.map { it.kind }).containsExactly(dev.hfps.hoard.app.files.TokenKind.Keyword, dev.hfps.hoard.app.files.TokenKind.String, dev.hfps.hoard.app.files.TokenKind.Comment).inOrder()
        assertThat(highlightLine("x = 42  # count", "py").map { it.kind }).containsExactly(dev.hfps.hoard.app.files.TokenKind.Number, dev.hfps.hoard.app.files.TokenKind.Comment).inOrder()
    }

    private fun row(id: String, state: String, size: Long, done: Long, finishedAt: String? = null) = TransferEntity(
        id = id, op = "copy", sourceLocationId = "device", sourceMachineId = null, sourceShare = "", sourcePaths = "[]",
        destLocationId = "device", destMachineId = null, destShare = "", destPath = "", destTreeUri = null, name = id,
        conflict = "ask", size = size, bytesDone = done, itemsTotal = 1, itemsDone = 0, itemsFailed = 0, itemsSkipped = 0,
        state = state, errorCode = null, error = null, createdAt = "2026-11-14T00:00:00Z", finishedAt = finishedAt,
    )

    @Test
    fun `the transfers ring shows the running jobs' overall progress and a recent failure`() {
        val now = Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()
        val views = listOf(
            TransferView.of(row("a", TransferStates.RUNNING, 100, 50), null),
            TransferView.of(row("b", TransferStates.RUNNING, 300, 50), null),
            TransferView.of(row("c", TransferStates.COMPLETED, 10, 10, "2026-11-14T11:00:00Z"), null),
        )
        assertThat(TransfersIndicator.of(views, now)).isEqualTo(TransfersIndicator(2, 25.0, false))
        val failed = listOf(TransferView.of(row("d", TransferStates.FAILED, 10, 1, "2026-11-14T11:45:00Z"), null))
        assertThat(TransfersIndicator.of(failed, now).failedRecently).isTrue()
        val old = listOf(TransferView.of(row("e", TransferStates.FAILED, 10, 1, "2026-11-14T10:00:00Z"), null))
        assertThat(TransfersIndicator.of(old, now)).isEqualTo(TransfersIndicator())
    }

    @Test
    fun `drag reorder moves one row and the others make room`() {
        assertThat(moved(listOf("a", "b", "c", "d"), 0, 2)).containsExactly("b", "c", "a", "d").inOrder()
        assertThat(moved(listOf("a", "b", "c", "d"), 3, 0)).containsExactly("d", "a", "b", "c").inOrder()
        var result: Pair<Int, Int>? = null
        val drag = DragReorder { from, to -> result = from to to }
        drag.count = 4
        drag.measured(100)
        drag.start(0)
        drag.drag(210f)
        assertThat(drag.target).isEqualTo(2)
        assertThat(drag.shift(0)).isEqualTo(210f)
        assertThat(drag.shift(1)).isEqualTo(-100f)
        assertThat(drag.shift(3)).isEqualTo(0f)
        drag.end()
        assertThat(result).isEqualTo(0 to 2)
    }

    @Test
    fun `language tags and the text viewer's preferences read as Hoard 1 wrote them`() {
        assertThat(AppLanguage.normalise("pt-BR")).isEqualTo("pt-BR")
        assertThat(AppLanguage.normalise("pt")).isEqualTo("pt-BR")
        assertThat(AppLanguage.normalise("en-US")).isEqualTo("en")
        assertThat(AppLanguage.normalise("")).isNull()
        assertThat(TextPrefs.parse("""{"wrap":false,"wrapCode":true,"fontSize":18}""")).isEqualTo(TextPrefs(false, true, 18))
        assertThat(TextPrefs.parse("""{"fontSize":99}""")).isEqualTo(TextPrefs())
        assertThat(TextPrefs.parse(null)).isEqualTo(TextPrefs())
        assertThat(TextPrefs.parse(TextPrefs(true, false, 16).json())).isEqualTo(TextPrefs(true, false, 16))
    }
}
