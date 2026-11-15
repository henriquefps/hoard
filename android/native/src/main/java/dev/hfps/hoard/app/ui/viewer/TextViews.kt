package dev.hfps.hoard.app.ui.viewer

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.MdBlock
import dev.hfps.hoard.app.files.TokenKind
import dev.hfps.hoard.app.files.highlightLine
import dev.hfps.hoard.app.files.inlineSpans
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** The code / plain view: line numbers, highlighting, find highlights (current one stronger), wrap and size. */
@Composable
fun CodeLines(
    text: String,
    ext: String,
    highlight: Boolean,
    wrap: Boolean,
    fontSize: Int,
    query: String?,
    matchCase: Boolean,
    currentLine: Int?,
    state: LazyListState,
) {
    val lines = remember(text) { text.split('\n') }
    val digits = lines.size.toString().length
    val found = HfpsTheme.colors.warning.copy(alpha = 0.3f)
    val current = HfpsTheme.colors.warning.copy(alpha = 0.7f)
    val colors = mapOf(
        TokenKind.Keyword to HfpsTheme.colors.primary,
        TokenKind.String to Color(0xFF3FA06B),
        TokenKind.Comment to HfpsTheme.colors.mutedForeground,
        TokenKind.Number to Color(0xFFC07A2C),
    )
    val style = HfpsTheme.typography.mono.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp, color = HfpsTheme.colors.cardForeground)
    SelectionContainer {
        val scroll = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
        LazyColumn(Modifier.fillMaxSize().then(scroll).testTag("text-lines"), state = state) {
            itemsIndexed(lines) { index, raw ->
                val line = raw.trimEnd('\r')
                Row(Modifier.padding(horizontal = 8.dp).let { if (wrap) it.fillMaxWidth() else it }) {
                    Text(
                        (index + 1).toString().padStart(digits),
                        style = style.copy(color = HfpsTheme.colors.mutedForeground.copy(alpha = 0.6f)),
                        modifier = Modifier.width((digits * fontSize * 0.62 + 12).dp),
                    )
                    val annotated = buildAnnotatedString {
                        append(line)
                        if (highlight) highlightLine(line, ext).forEach { addStyle(SpanStyle(color = colors.getValue(it.kind), fontStyle = if (it.kind == TokenKind.Comment) FontStyle.Italic else null), it.start, it.end) }
                        val q = query?.takeIf { it.isNotEmpty() }
                        if (q != null) {
                            var from = 0
                            while (true) {
                                val at = line.indexOf(q, from, ignoreCase = !matchCase)
                                if (at < 0) break
                                addStyle(SpanStyle(background = if (index == currentLine) current else found), at, at + q.length)
                                from = at + q.length
                            }
                        }
                    }
                    Text(annotated, style = style, softWrap = wrap, modifier = if (wrap) Modifier.weight(1f) else Modifier)
                }
            }
        }
    }
}

/** A CSV / TSV file as a table (the first rows; Source has the rest). */
@Composable
fun CsvTable(rows: List<List<String>>, truncated: Boolean, fontSize: Int) {
    val columns = rows.maxOfOrNull { it.size } ?: 0
    val style = HfpsTheme.typography.body.copy(fontSize = fontSize.sp)
    Column(Modifier.fillMaxSize().testTag("csv-table")) {
        if (truncated) {
            Text(
                stringResource(R.string.t_text_csv_truncated, rows.size.toString()),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        val scroll = rememberScrollState()
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            itemsIndexed(rows) { index, row ->
                Row(Modifier.background(if (index == 0) HfpsTheme.colors.muted else Color.Transparent)) {
                    for (c in 0 until columns) {
                        Text(
                            row.getOrNull(c).orEmpty(),
                            style = if (index == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                            color = HfpsTheme.colors.cardForeground,
                            maxLines = 3,
                            modifier = Modifier.widthIn(min = 64.dp, max = 280.dp).border(0.5.dp, HfpsTheme.colors.hairline).padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Inline Markdown as an annotated string: bold, italic, code, strike, links (tagged `link`). */
@Composable
fun inline(text: String): AnnotatedString {
    val link = HfpsTheme.colors.primary
    val codeBg = HfpsTheme.colors.muted
    return buildAnnotatedString {
        for (span in inlineSpans(text)) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBg else Color.Unspecified,
                color = if (span.link != null) link else Color.Unspecified,
                textDecoration = when {
                    span.link != null -> TextDecoration.Underline
                    span.strike -> TextDecoration.LineThrough
                    else -> null
                },
            )
            if (span.link != null) pushStringAnnotation("link", span.link)
            withStyle(style) { append(span.text) }
            if (span.link != null) pop()
        }
    }
}

/** The Markdown preview (Hoard 1's MarkdownView): blocks, inline markup, links; ```mermaid blocks drawn. */
@Composable
fun MarkdownView(blocks: List<MdBlock>, fontSize: Int, dark: Boolean, onLink: (String) -> Unit, state: LazyListState) {
    val body = HfpsTheme.typography.body.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.55).sp, color = HfpsTheme.colors.cardForeground)
    LazyColumn(Modifier.fillMaxSize().testTag("markdown"), state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(blocks) { _, block -> MdBlockView(block, body, fontSize, dark, onLink) }
    }
}

@Composable
private fun MdBlockView(block: MdBlock, body: TextStyle, fontSize: Int, dark: Boolean, onLink: (String) -> Unit) {
    when (block) {
        is MdBlock.Heading -> Text(
            inline(block.text),
            style = body.copy(fontSize = (fontSize * (when (block.level) { 1 -> 1.7; 2 -> 1.4; 3 -> 1.2; else -> 1.05 })).sp, fontWeight = FontWeight.SemiBold, lineHeight = (fontSize * 2.0).sp),
        )
        is MdBlock.Paragraph -> LinkText(inline(block.text), body, onLink)
        is MdBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, (depth, text) ->
                Row(Modifier.padding(start = (depth * 16).dp)) {
                    Text(if (block.ordered) "${block.start + i}." else "•", style = body, modifier = Modifier.width(24.dp))
                    LinkText(inline(text.removePrefix("[ ] ").let { t -> if (text.startsWith("[x] ", true)) "☑ " + text.substring(4) else if (text.startsWith("[ ] ")) "☐ $t" else t }), body, onLink)
                }
            }
        }
        is MdBlock.Quote -> Row {
            Box(Modifier.width(3.dp).height(24.dp).background(HfpsTheme.colors.border))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEach { MdBlockView(it, body.copy(color = HfpsTheme.colors.mutedForeground), fontSize, dark, onLink) }
            }
        }
        is MdBlock.Code -> if (block.language == "mermaid") {
            MermaidView(block.code, dark, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 520.dp))
        } else {
            Text(
                block.code,
                style = HfpsTheme.typography.mono.copy(fontSize = (fontSize - 1).sp, color = HfpsTheme.colors.cardForeground),
                softWrap = false,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(HfpsTheme.colors.muted).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState()).border(0.5.dp, HfpsTheme.colors.hairline)) {
            (listOf(block.header) + block.rows).forEachIndexed { r, row ->
                Row {
                    block.header.indices.forEach { c ->
                        Text(
                            inline(row.getOrNull(c).orEmpty()),
                            style = if (r == 0) body.copy(fontWeight = FontWeight.SemiBold) else body,
                            modifier = Modifier.widthIn(min = 72.dp, max = 260.dp).border(0.5.dp, HfpsTheme.colors.hairline).padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        MdBlock.Rule -> HorizontalDivider(color = HfpsTheme.colors.border)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun LinkText(text: AnnotatedString, style: TextStyle, onLink: (String) -> Unit) {
    if (text.getStringAnnotations("link", 0, text.length).isEmpty()) {
        Text(text, style = style)
    } else {
        ClickableText(text, style = style) { offset -> text.getStringAnnotations("link", offset, offset).firstOrNull()?.let { onLink(it.item) } }
    }
}

/**
 * A Mermaid diagram drawn by the vendored mermaid.js (assets/mermaid, the version Hoard 1 bundles) in a WebView that
 * loads nothing from the network: `securityLevel: "strict"`, no file access, the app's colours.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidView(source: String, dark: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.t_text_mermaid_label)
    val background = HfpsTheme.colors.card.toArgb()
    val html = remember(source, dark) {
        val escaped = source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' file:; style-src 'unsafe-inline'; img-src data:">
<style>body{margin:0;background:transparent}svg{max-width:100%;height:auto}</style>
<script src="file:///android_asset/mermaid/mermaid.min.js"></script></head>
<body><pre class="mermaid">$escaped</pre>
<script>mermaid.initialize({startOnLoad:true,securityLevel:"strict",theme:"${if (dark) "dark" else "default"}"});</script></body></html>"""
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                contentDescription = label
                setBackgroundColor(background)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true
                }
            }
        },
        update = { it.loadDataWithBaseURL("file:///android_asset/mermaid/", html, "text/html", "utf-8", null) },
        modifier = modifier.testTag("mermaid"),
    )
}
