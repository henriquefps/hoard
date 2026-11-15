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
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.InlineSpan
import dev.hfps.hoard.app.files.MdBlock
import dev.hfps.hoard.app.files.Token
import dev.hfps.hoard.app.files.TokenKind
import dev.hfps.hoard.app.files.fenceExtension
import dev.hfps.hoard.app.files.grammarOf
import dev.hfps.hoard.app.files.grammarTokens
import dev.hfps.hoard.app.files.highlightLine
import dev.hfps.hoard.app.files.inlineSpans
import dev.hfps.hoard.app.files.tokensPerLine
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    val colors = syntaxColors()
    // The grammar pass (Highlights) runs off the main thread on the whole text; lines show the light tokenizer until
    // it is there, and for formats without a grammar.
    val grammar by produceState<List<List<Token>>?>(null, text, ext, highlight) {
        value = if (highlight) withContext(Dispatchers.Default) { grammarTokens(text, ext)?.let { tokensPerLine(text, it) } } else null
    }
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
                        if (highlight) {
                            val tokens = grammar?.getOrNull(index) ?: highlightLine(line, ext)
                            tokens.forEach { t -> addToken(t, colors, line.length) }
                        }
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

/** The code colours of the hfps theme per token kind (the code view and Markdown fences). */
@Composable
fun syntaxColors(): Map<TokenKind, Color> = mapOf(
    TokenKind.Keyword to HfpsTheme.colors.primary,
    TokenKind.String to Color(0xFF3FA06B),
    TokenKind.Comment to HfpsTheme.colors.mutedForeground,
    TokenKind.Number to Color(0xFFC07A2C),
    TokenKind.Annotation to Color(0xFF9B6BD3),
)

private fun AnnotatedString.Builder.addToken(t: Token, colors: Map<TokenKind, Color>, length: Int) {
    val color = colors[t.kind] ?: return
    val start = t.start.coerceIn(0, length)
    val end = t.end.coerceIn(start, length)
    if (end > start) addStyle(SpanStyle(color = color, fontStyle = if (t.kind == TokenKind.Comment) FontStyle.Italic else null), start, end)
}

/** Inline Markdown as an annotated string: bold, italic, code, strike, links (tagged `link`); images as their alt. */
@Composable
fun inline(text: String): AnnotatedString = inline(remember(text) { inlineSpans(text) })

@Composable
fun inline(spans: List<InlineSpan>): AnnotatedString {
    val link = HfpsTheme.colors.primary
    val codeBg = HfpsTheme.colors.muted
    return buildAnnotatedString {
        for (span in spans) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic || span.image != null) FontStyle.Italic else null,
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

/** What a Markdown preview needs from its page: links and images of the file's location ([MarkdownImages]). */
class MarkdownHost(val onLink: (String) -> Unit = {}, val loadImage: suspend (String) -> ImageBitmap? = { null })

private val LocalMarkdownHost = staticCompositionLocalOf { MarkdownHost() }

/**
 * The Markdown preview (Hoard 1's MarkdownView): blocks, inline markup, links, images (from the share, the web or
 * `data:`), code blocks highlighted with a "Copy code" button; ```mermaid blocks drawn.
 */
@Composable
fun MarkdownView(blocks: List<MdBlock>, fontSize: Int, dark: Boolean, host: MarkdownHost, state: LazyListState, modifier: Modifier = Modifier) {
    val body = HfpsTheme.typography.body.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.55).sp, color = HfpsTheme.colors.cardForeground)
    CompositionLocalProvider(LocalMarkdownHost provides host) {
        LazyColumn(modifier.fillMaxSize().testTag("markdown"), state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(blocks) { _, block -> MdBlockView(block, body, fontSize, dark, host.onLink) }
        }
    }
}

@Composable
private fun MdBlockView(block: MdBlock, body: TextStyle, fontSize: Int, dark: Boolean, onLink: (String) -> Unit) {
    when (block) {
        is MdBlock.Heading -> Text(
            inline(block.text),
            style = body.copy(fontSize = (fontSize * (when (block.level) { 1 -> 1.7; 2 -> 1.4; 3 -> 1.2; else -> 1.05 })).sp, fontWeight = FontWeight.SemiBold, lineHeight = (fontSize * 2.0).sp),
        )
        is MdBlock.Paragraph -> RichText(block.text, body, onLink)
        is MdBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, (depth, text) ->
                Row(Modifier.padding(start = (depth * 16).dp)) {
                    Text(if (block.ordered) "${block.start + i}." else "•", style = body, modifier = Modifier.width(24.dp))
                    RichText(text.removePrefix("[ ] ").let { t -> if (text.startsWith("[x] ", true)) "☑ " + text.substring(4) else if (text.startsWith("[ ] ")) "☐ $t" else t }, body, onLink)
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
            CodeBlock(block.code, block.language, fontSize)
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

/**
 * A fenced code block (MarkdownView.tsx `CodeBlock`): highlighted by its language, scrolling sideways, with a "Copy
 * code" button that turns into a check for two seconds.
 */
@Composable
private fun CodeBlock(code: String, language: String, fontSize: Int) {
    val context = LocalContext.current
    val colors = syntaxColors()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    val ext = remember(language) { if (grammarOf(language) != null) language else fenceExtension(language) }
    val tokens by produceState<List<List<Token>>?>(null, code, ext) {
        value = withContext(Dispatchers.Default) { grammarTokens(code, ext)?.let { tokensPerLine(code, it) } }
    }
    val annotated = remember(code, tokens, colors) {
        buildAnnotatedString {
            val lines = code.split('\n')
            lines.forEachIndexed { i, line ->
                val start = length
                append(line)
                val lineTokens = tokens?.getOrNull(i) ?: if (language.isNotEmpty()) highlightLine(line, ext) else emptyList()
                lineTokens.forEach { t -> addToken(t.copy(start = t.start + start, end = t.end + start), colors, length) }
                if (i < lines.lastIndex) append('\n')
            }
        }
    }
    val copyLabel = stringResource(if (copied) R.string.t_text_copied else R.string.t_text_copy_code)
    val failed = stringResource(R.string.t_text_copy_failed)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(HfpsTheme.colors.muted).testTag("md-code")) {
        Text(
            annotated,
            style = HfpsTheme.typography.mono.copy(fontSize = (fontSize - 1).sp, color = HfpsTheme.colors.cardForeground),
            softWrap = false,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 52.dp),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(HfpsTheme.colors.card)) {
            HfpsIconButton(
                if (copied) PhosphorRegular.Check else PhosphorRegular.Copy,
                copyLabel,
                {
                    val manager = context.getSystemService(ClipboardManager::class.java)
                    if (manager == null) {
                        android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        manager.setPrimaryClip(ClipData.newPlainText(copyLabel, code))
                        copied = true
                    }
                },
                tint = if (copied) HfpsTheme.colors.primary else HfpsTheme.colors.foreground,
                modifier = Modifier.testTag("md-copy-code"),
            )
        }
    }
}

/**
 * A paragraph or list item: text with inline markup; images in it drawn ([MdImage]). A paragraph of images only
 * (badges) flows them in a row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RichText(text: String, style: TextStyle, onLink: (String) -> Unit) {
    val spans = remember(text) { inlineSpans(text) }
    if (spans.none { it.image != null }) {
        LinkText(inline(spans), style, onLink)
        return
    }
    val runs = remember(spans) {
        val out = ArrayList<List<InlineSpan>>()
        var current = ArrayList<InlineSpan>()
        for (span in spans) {
            if (span.image != null) {
                if (current.isNotEmpty()) out += current
                out += listOf(span)
                current = ArrayList()
            } else {
                current += span
            }
        }
        if (current.isNotEmpty()) out += current
        out
    }
    val imagesOnly = runs.all { run -> run.single().image != null || run.all { it.text.isBlank() } }
    if (imagesOnly) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            runs.forEach { run -> run.singleOrNull()?.takeIf { it.image != null }?.let { MdImage(it, onLink) } }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            runs.forEach { run ->
                val image = run.singleOrNull()?.takeIf { it.image != null }
                if (image != null) MdImage(image, onLink) else if (run.any { it.text.isNotBlank() }) LinkText(inline(run), style, onLink)
            }
        }
    }
}

private sealed interface MdImageState {
    data object Loading : MdImageState
    data class Image(val bitmap: ImageBitmap) : MdImageState
    data object Failed : MdImageState
}

/** One Markdown image at its natural size (never wider than the column); a linked one follows its link. */
@Composable
private fun MdImage(span: InlineSpan, onLink: (String) -> Unit) {
    val host = LocalMarkdownHost.current
    val src = span.image ?: return
    val state by produceState<MdImageState>(MdImageState.Loading, src) {
        value = runCatching { host.loadImage(src) }.getOrNull()?.let { MdImageState.Image(it) } ?: MdImageState.Failed
    }
    val click = span.link?.let { link -> Modifier.clickable { onLink(link) } } ?: Modifier
    when (val s = state) {
        MdImageState.Loading -> Box(Modifier.size(width = 96.dp, height = 20.dp).clip(RoundedCornerShape(4.dp)).background(HfpsTheme.colors.muted).testTag("md-image-loading"))
        is MdImageState.Image -> androidx.compose.foundation.Image(
            s.bitmap,
            span.text,
            Modifier.widthIn(max = s.bitmap.width.dp).aspectRatio(s.bitmap.width.toFloat() / s.bitmap.height.coerceAtLeast(1)).then(click).testTag("md-image"),
            contentScale = ContentScale.Fit,
        )
        MdImageState.Failed -> Row(
            Modifier.then(click).semantics { contentDescription = span.text }.testTag("md-image-broken"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(PhosphorRegular.FileImage, null, Modifier.size(18.dp), tint = HfpsTheme.colors.mutedForeground)
            if (span.text.isNotEmpty()) Text(span.text, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
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
