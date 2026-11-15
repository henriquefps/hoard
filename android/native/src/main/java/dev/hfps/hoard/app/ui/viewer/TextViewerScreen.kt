package dev.hfps.hoard.app.ui.viewer

import dev.hfps.hoard.app.ui.common.HoardDialog
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.TEXT_VIEWER_MAX_BYTES
import dev.hfps.hoard.app.files.formatBytes
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.ui.browse.SearchField
import dev.hfps.hoard.app.ui.browse.findActivity
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.errorBody
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.common.formatFullDate
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.text.TextRead
import kotlinx.coroutines.launch

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.hfps.hoard.app.files.LineEnding
import dev.hfps.hoard.app.files.LinkTarget
import dev.hfps.hoard.app.files.RenderLimits
import dev.hfps.hoard.app.files.TextFormat
import dev.hfps.hoard.app.files.applyLineEnding
import dev.hfps.hoard.app.files.canEncode
import dev.hfps.hoard.app.files.detectLineEnding
import dev.hfps.hoard.app.files.extensionOf
import dev.hfps.hoard.app.files.findMatches
import dev.hfps.hoard.app.files.highlights
import dev.hfps.hoard.app.files.isProse
import dev.hfps.hoard.app.files.nextFreeName
import dev.hfps.hoard.app.files.normalizeNewlines
import dev.hfps.hoard.app.files.parseDelimited
import dev.hfps.hoard.app.files.parseMarkdown
import dev.hfps.hoard.app.files.prettyJson
import dev.hfps.hoard.app.files.replaceAll
import dev.hfps.hoard.app.files.replaceAt
import dev.hfps.hoard.app.files.resolveLink
import dev.hfps.hoard.app.files.textFormat
import androidx.compose.material3.DropdownMenu
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.designsystem.components.HfpsChip
import org.json.JSONObject
private sealed interface TextState {
    data object Loading : TextState
    data class Ready(val read: TextRead, val lineEnding: LineEnding) : TextState
    data class Failed(val code: ErrorCode, val size: Long? = null) : TextState
}

/** How the file is shown: rendered (Markdown, Mermaid), as a table (CSV / TSV) or as its source. */
private enum class TextMode { Preview, Table, Source }

/** While editing a renderable file (TextViewerPage's EditLayout): the editor, editor + preview side by side, the preview. */
enum class EditLayout { Editor, Split, Preview }

/** Side by side needs a wide window (Hoard 1: not on phones); 840dp is Material's expanded width. */
fun splitFits(widthDp: Int): Boolean = widthDp >= 840

/** The layout shown: Split falls back to the editor where it does not fit. */
fun editLayoutShown(choice: EditLayout?, wide: Boolean): EditLayout = when (choice) {
    null -> if (wide) EditLayout.Split else EditLayout.Editor
    EditLayout.Split -> if (wide) EditLayout.Split else EditLayout.Editor
    else -> choice
}

/** A save that did not go through: changed elsewhere, or gone. */
private enum class SaveProblem { Conflict, Deleted }

/** The viewer's conveniences (prefs.ts), the `textViewer` setting Hoard 1's last release mirrors (ADR 0019). */
data class TextPrefs(val wrap: Boolean = true, val wrapCode: Boolean = false, val fontSize: Int = 14) {
    fun json(): String = JSONObject().put("wrap", wrap).put("wrapCode", wrapCode).put("fontSize", fontSize).toString()

    companion object {
        const val KEY = "textViewer"
        const val MIN = 12
        const val MAX = 24

        fun parse(json: String?): TextPrefs {
            val o = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: return TextPrefs()
            val d = TextPrefs()
            return TextPrefs(
                wrap = (o.opt("wrap") as? Boolean) ?: d.wrap,
                wrapCode = (o.opt("wrapCode") as? Boolean) ?: d.wrapCode,
                fontSize = (o.opt("fontSize") as? Number)?.toInt()?.takeIf { it in MIN..MAX } ?: d.fontSize,
            )
        }
    }
}

fun encodingText(encoding: String): Int = when (encoding) {
    "utf-8-bom" -> R.string.t_text_encodings_utf_8_bom
    "utf-16le" -> R.string.t_text_encodings_utf_16le
    "utf-16be" -> R.string.t_text_encodings_utf_16be
    "windows-1252" -> R.string.t_text_encodings_windows_1252
    else -> R.string.t_text_encodings_utf_8
}

fun lineEndingText(ending: LineEnding): Int = when (ending) {
    LineEnding.Lf -> R.string.t_text_line_endings_lf
    LineEnding.Crlf -> R.string.t_text_line_endings_crlf
    LineEnding.Cr -> R.string.t_text_line_endings_cr
}

/**
 * The built-in text viewer and editor (Hoard 1's TextViewerPage): Markdown, Mermaid, CSV / TSV and JSON shown
 * rendered or as source; source with line numbers and highlighting; find with "n of m", match case, and replace while
 * editing; wrap and text size (remembered); edit → save, keeping the file's encoding and line endings, writing only if
 * nobody changed it since it was opened (a conflict offers reload theirs, overwrite or save as a copy; a deleted file
 * save again or as a copy). Files above the limit open their first part, read-only.
 */
@Composable
fun TextViewerScreen(locationId: String, share: String?, path: String, startEditing: Boolean, onBack: () -> Unit, onOpenFile: (String) -> Unit = {}) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val container = (context.applicationContext as HoardApplication).container
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val machines by container.database.machines().observeAll().collectAsState(initial = null)
    val volumes by container.volumes.observe().collectAsState(initial = null)
    val prefsJson by container.settingsStore.observeJson(TextPrefs.KEY).collectAsState(initial = null)
    val prefs = remember(prefsJson) { TextPrefs.parse(prefsJson) }
    fun savePrefs(next: TextPrefs) = scope.launch { container.settingsStore.putJson(TextPrefs.KEY, next.json()) }
    val phone = stringResource(R.string.t_locations_internal)
    val drive = stringResource(R.string.t_locations_drive)
    val location = remember(machines, volumes) {
        if (machines == null || volumes == null) null else FileService.resolve(locationId, share, machines!!, volumes!!, phone, drive)
    }
    val name = path.substringAfterLast('/')
    val folder = path.substringBeforeLast('/', "")
    val format = remember(name) { textFormat(name) }
    val prose = remember(name) { isProse(name) }
    val dark = HfpsTheme.colors.isDark
    var state by remember { mutableStateOf<TextState>(TextState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var firstPart by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<SaveProblem?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    var replacement by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<TextMode?>(null) }
    var pretty by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var layoutChoice by remember { mutableStateOf<EditLayout?>(null) }
    val list = rememberLazyListState()
    val previewList = rememberLazyListState()
    val wide = splitFits(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)
    // The preview beside the editor follows the draft a moment after typing stops (Hoard 1: 200 ms).
    var liveText by remember { mutableStateOf("") }
    LaunchedEffect(draft.text, editing) {
        if (!editing) return@LaunchedEffect
        if (liveText.isNotEmpty()) kotlinx.coroutines.delay(200)
        liveText = draft.text
    }
    val markdownHost = remember(location?.id, path) {
        val loc = location
        MarkdownHost(
            onLink = {},
            loadImage = { src ->
                if (loc == null) null else MarkdownImages(path, loc.cacheKey) { p -> container.files.thumbnail(loc, p, MARKDOWN_IMAGE_SIZE).file }.load(src)
            },
        )
    }

    LaunchedEffect(location?.id, reload, firstPart) {
        val loc = location ?: return@LaunchedEffect
        state = TextState.Loading
        state = try {
            val read = container.files.run(loc) { fs, _ -> fs.readText(path, TEXT_VIEWER_MAX_BYTES.toInt(), firstPart) }
            if (startEditing && reload == 0 && read.access != SmbAccess.READ && !read.truncated) {
                editing = true
                draft = TextFieldValue(normalizeNewlines(read.text))
            }
            TextState.Ready(read, detectLineEnding(read.text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val code = errorCodeOf(e)
            // Too large: say how large (from the folder's listing), and offer its first part.
            val size = if (code == ErrorCode.TOO_LARGE) runCatching { container.files.run(loc) { fs, _ -> fs.list(folder).entries.firstOrNull { it.path == path }?.size } }.getOrNull() else null
            TextState.Failed(code, size)
        }
    }
    val ready = state as? TextState.Ready
    val read = ready?.read
    val sourceText = remember(read?.text) { read?.text?.let(::normalizeNewlines).orEmpty() }
    val dirty = editing && read != null && draft.text != sourceText
    val small = (read?.size ?: 0) <= RenderLimits.BYTES
    val rendered = when (format) {
        TextFormat.Markdown, TextFormat.Mermaid -> TextMode.Preview
        TextFormat.Csv, TextFormat.Tsv -> TextMode.Table
        else -> null
    }
    val shown = if (editing) TextMode.Source else mode ?: if (rendered != null && small && read?.truncated != true) rendered else TextMode.Source
    val wrap = if (prose) prefs.wrap else prefs.wrapCode
    val viewText = if (pretty && format == TextFormat.Json) prettyJson(sourceText) ?: sourceText else sourceText
    val haystack = if (editing) draft.text else viewText
    val matches = remember(haystack, query, matchCase, finding) { if (finding) findMatches(haystack, query, matchCase) else emptyList() }
    val currentMatch = matches.getOrNull(current.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
    val currentLine = currentMatch?.let { at -> haystack.substring(0, at).count { it == '\n' } }

    fun goToMatch(index: Int) {
        if (matches.isEmpty()) return
        current = Math.floorMod(index, matches.size)
        val at = matches[current]
        if (editing) {
            draft = draft.copy(selection = TextRange(at, at + query.length))
        } else {
            val line = haystack.substring(0, at).count { it == '\n' }
            scope.launch { list.animateScrollToItem(line.coerceAtLeast(0)) }
        }
    }

    fun write(target: String, mode: String, expected: String?, onDone: suspend (dev.hfps.hoard.smb.SmbEntry, String) -> Unit) {
        val loc = location ?: return
        val r = ready ?: return
        val text = applyLineEnding(draft.text, r.lineEnding)
        var encoding = r.read.encoding
        if (!canEncode(text, encoding)) {
            toaster.show(res.getString(R.string.t_text_saved_as_utf8, res.getString(encodingText(encoding))), long = true)
            encoding = "utf-8"
        }
        saving = true
        scope.launch {
            try {
                val entry = container.files.run(loc) { fs, _ -> fs.writeText(target, text, encoding, mode, expected) }
                onDone(entry, encoding)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (val code = errorCodeOf(e)) {
                    ErrorCode.CONFLICT -> problem = SaveProblem.Conflict
                    ErrorCode.NOT_FOUND -> if (mode == "overwrite") problem = SaveProblem.Deleted else toaster.show(res.getString(R.string.t_text_save_failed, name) + "\n" + res.getString(errorTitle(code)))
                    else -> toaster.show(res.getString(R.string.t_text_save_failed, name) + "\n" + res.getString(errorTitle(code)))
                }
            } finally {
                saving = false
            }
        }
    }

    fun save(force: Boolean = false, recreate: Boolean = false) {
        val r = ready ?: return
        write(path, if (recreate) "create" else "overwrite", if (force || recreate) null else r.read.modifiedAt) { entry, encoding ->
            state = TextState.Ready(r.read.copy(text = applyLineEnding(draft.text, r.lineEnding), encoding = encoding, modifiedAt = entry.modifiedAt, size = entry.size), r.lineEnding)
            editing = false
            toaster.show(res.getString(R.string.t_text_saved, name))
        }
    }

    fun saveAsCopy() {
        val loc = location ?: return
        scope.launch {
            val taken = runCatching { container.files.run(loc) { fs, _ -> fs.list(folder).entries.map { it.name.lowercase() }.toSet() } }.getOrDefault(emptySet())
            val copyName = nextFreeName(name) { it.lowercase() in taken }
            val copyPath = if (folder.isEmpty()) copyName else "$folder/$copyName"
            write(copyPath, "create", null) { _, _ ->
                editing = false
                toaster.show(res.getString(R.string.t_text_saved_as_copy, copyName))
                onOpenFile(copyPath)
            }
        }
    }

    BackHandler(enabled = dirty) { leaving = true }

    /** The rendered view of [text]: Markdown (links, images of this location), Mermaid, or a CSV / TSV table. */
    val onLink: (String) -> Unit = { href ->
        when (val target = resolveLink(path, href)) {
            is LinkTarget.External -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target.url))) }
            is LinkTarget.Share -> if (!target.isFolder) onOpenFile(target.path)
            is LinkTarget.Anchor -> {
                val blocks = parseMarkdown(if (editing) liveText else sourceText)
                val at = blocks.indexOfFirst { it is dev.hfps.hoard.app.files.MdBlock.Heading && it.id == target.id }
                if (at >= 0) scope.launch { (if (editing) previewList else list).animateScrollToItem(at) }
            }
            else -> Unit
        }
    }
    val currentOnLink = androidx.compose.runtime.rememberUpdatedState(onLink)
    val host = remember(markdownHost) { MarkdownHost({ currentOnLink.value(it) }, markdownHost.loadImage) }
    val Rendered: @Composable (String, androidx.compose.foundation.lazy.LazyListState) -> Unit = { text, state ->
        when (format) {
            TextFormat.Mermaid -> MermaidView(text, dark, Modifier.fillMaxSize())
            TextFormat.Csv, TextFormat.Tsv -> {
                val rows = remember(text) { parseDelimited(text, if (format == TextFormat.Tsv) '\t' else ',', RenderLimits.CSV_ROWS + 1) }
                CsvTable(rows.take(RenderLimits.CSV_ROWS), rows.size > RenderLimits.CSV_ROWS, prefs.fontSize)
            }
            else -> MarkdownView(remember(text) { parseMarkdown(text) }, prefs.fontSize, dark, host, state)
        }
    }

    HfpsScaffold(topBar = {
        HfpsTopBar(name, onBack = { if (dirty) leaving = true else onBack() }, titleIsFileName = true) {
            val writable = read != null && read.access != SmbAccess.READ && !read.truncated
            if (read != null) {
                HfpsIconButton(PhosphorRegular.MagnifyingGlass, stringResource(R.string.t_text_find), { finding = !finding }, active = finding, modifier = Modifier.testTag("text-find"))
                Box {
                    HfpsIconButton(PhosphorRegular.TextAa, stringResource(R.string.t_text_font_size), { sizeMenu = true })
                    DropdownMenu(sizeMenu, { sizeMenu = false }, containerColor = HfpsTheme.colors.card) {
                        MenuItem(stringResource(R.string.t_text_wrap), if (wrap) PhosphorRegular.CheckSquare else PhosphorRegular.Square, {
                            savePrefs(if (prose) prefs.copy(wrap = !prefs.wrap) else prefs.copy(wrapCode = !prefs.wrapCode))
                        })
                        MenuItem(stringResource(R.string.t_text_smaller), PhosphorRegular.Minus, { savePrefs(prefs.copy(fontSize = (prefs.fontSize - 1).coerceAtLeast(TextPrefs.MIN))) }, enabled = prefs.fontSize > TextPrefs.MIN)
                        MenuItem(stringResource(R.string.t_text_font_size_value, prefs.fontSize.toString()), null, {}, enabled = false)
                        MenuItem(stringResource(R.string.t_text_larger), PhosphorRegular.Plus, { savePrefs(prefs.copy(fontSize = (prefs.fontSize + 1).coerceAtMost(TextPrefs.MAX))) }, enabled = prefs.fontSize < TextPrefs.MAX)
                        MenuItem(stringResource(R.string.t_text_reset_size), PhosphorRegular.ArrowCounterClockwise, { savePrefs(prefs.copy(fontSize = 14)) })
                    }
                }
                if (!editing) {
                    HfpsIconButton(PhosphorRegular.ArrowSquareOut, stringResource(R.string.t_text_open_with), {
                        val activity = context.findActivity() ?: return@HfpsIconButton
                        val loc = location ?: return@HfpsIconButton
                        scope.launch {
                            runCatching { FileActions(container).open(activity, loc, SmbEntry(name, path, false, read.size, read.modifiedAt, false), chooser = true) }
                        }
                    })
                    if (writable) {
                        HfpsIconButton(PhosphorRegular.PencilSimple, stringResource(R.string.t_text_edit), {
                            draft = TextFieldValue(sourceText)
                            editing = true
                        }, modifier = Modifier.testTag("text-edit"))
                    }
                }
            }
            if (editing) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp, color = HfpsTheme.colors.primary)
                } else {
                    HfpsIconButton(PhosphorRegular.Check, stringResource(R.string.t_text_save), { save() }, enabled = dirty, modifier = Modifier.testTag("text-save"))
                    HfpsIconButton(PhosphorRegular.X, stringResource(R.string.t_text_discard), { if (dirty) leaving = true else editing = false })
                }
            }
        }
    }) {
        when (val s = state) {
            TextState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = HfpsTheme.colors.primary)
            }
            is TextState.Failed -> StateMessage(
                PhosphorRegular.WarningCircle,
                when (s.code) {
                    ErrorCode.TOO_LARGE -> stringResource(R.string.t_text_too_large_title)
                    ErrorCode.NOT_TEXT -> stringResource(R.string.t_text_not_text_title)
                    else -> stringResource(errorTitle(s.code))
                },
                body = when (s.code) {
                    ErrorCode.NOT_TEXT -> stringResource(R.string.t_text_not_text_body)
                    ErrorCode.TOO_LARGE -> stringResource(R.string.t_text_too_large_body, s.size?.let { formatBytes(it) } ?: "?", formatBytes(TEXT_VIEWER_MAX_BYTES))
                    else -> stringResource(errorBody(s.code))
                },
                tint = HfpsTheme.colors.destructive,
            ) {
                if (s.code == ErrorCode.TOO_LARGE) {
                    SecondaryButton(stringResource(R.string.t_text_load_first_part, formatBytes(TEXT_VIEWER_MAX_BYTES)), { firstPart = true }, Modifier.testTag("text-first-part"))
                } else {
                    SecondaryButton(stringResource(R.string.t_common_retry), { reload++ })
                }
            }
            is TextState.Ready -> Column(Modifier.fillMaxSize()) {
                val r = s.read
                val meta = listOfNotNull(
                    formatBytes(r.size),
                    stringResource(encodingText(r.encoding)),
                    stringResource(lineEndingText(s.lineEnding)),
                    formatFullDate(context, r.modifiedAt).ifEmpty { null },
                    if (r.access == SmbAccess.READ || r.truncated) stringResource(R.string.t_text_read_only) else null,
                    if (dirty) stringResource(R.string.t_text_unsaved) else null,
                ).joinToString(" · ")
                Text(meta, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("text-meta"))
                if (r.truncated) {
                    Text(
                        stringResource(R.string.t_text_truncated_notice, formatBytes(r.text.length.toLong()), formatBytes(r.size)),
                        style = HfpsTheme.typography.ui,
                        color = HfpsTheme.colors.warning,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
                    )
                }
                if (editing && rendered != null) {
                    val layout = editLayoutShown(layoutChoice, wide)
                    Row(Modifier.padding(horizontal = 12.dp).testTag("edit-layouts"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HfpsChip(stringResource(R.string.t_text_layouts_editor), layout == EditLayout.Editor, { layoutChoice = EditLayout.Editor })
                        if (wide) HfpsChip(stringResource(R.string.t_text_layouts_split), layout == EditLayout.Split, { layoutChoice = EditLayout.Split })
                        HfpsChip(stringResource(if (rendered == TextMode.Table) R.string.t_text_layouts_table else R.string.t_text_layouts_preview), layout == EditLayout.Preview, { layoutChoice = EditLayout.Preview })
                    }
                }
                if (!editing && (rendered != null || format == TextFormat.Json)) {
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rendered != null) {
                            HfpsChip(stringResource(if (rendered == TextMode.Table) R.string.t_text_layouts_table else R.string.t_text_layouts_preview), shown == rendered, { mode = rendered })
                            HfpsChip(stringResource(R.string.t_text_layouts_source), shown == TextMode.Source, { mode = TextMode.Source })
                        }
                        if (format == TextFormat.Json) {
                            HfpsChip(stringResource(R.string.t_text_pretty), pretty, {
                                if (!pretty && prettyJson(sourceText) == null) toaster.show(res.getString(R.string.t_text_json_invalid)) else pretty = !pretty
                            })
                        }
                    }
                }
                if (finding) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("find-bar"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SearchField(query, { query = it; current = 0 }, Modifier.weight(1f), label = stringResource(R.string.t_text_find))
                            if (query.isNotEmpty()) {
                                Text(
                                    if (matches.isEmpty()) stringResource(R.string.t_text_no_matches) else stringResource(R.string.t_text_match_of, (current + 1).toString(), matches.size.toString()),
                                    style = HfpsTheme.typography.ui,
                                    color = HfpsTheme.colors.mutedForeground,
                                    modifier = Modifier.testTag("find-count"),
                                )
                            }
                            HfpsIconButton(PhosphorRegular.CaretUp, stringResource(R.string.t_text_previous_match), { goToMatch(current - 1) }, enabled = matches.isNotEmpty())
                            HfpsIconButton(PhosphorRegular.CaretDown, stringResource(R.string.t_text_next_match), { goToMatch(current + 1) }, enabled = matches.isNotEmpty())
                            HfpsIconButton(PhosphorRegular.TextAa, stringResource(R.string.t_text_match_case), { matchCase = !matchCase; current = 0 }, active = matchCase)
                        }
                        if (editing) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SearchField(replacement, { replacement = it }, Modifier.weight(1f), label = stringResource(R.string.t_text_replace_with))
                                SecondaryButton(stringResource(R.string.t_text_replace), {
                                    val at = currentMatch ?: return@SecondaryButton
                                    draft = TextFieldValue(replaceAt(draft.text, at, query.length, replacement), TextRange(at + replacement.length))
                                }, enabled = currentMatch != null)
                                SecondaryButton(stringResource(R.string.t_text_replace_all), {
                                    draft = TextFieldValue(replaceAll(draft.text, query, replacement, matchCase).first)
                                }, enabled = matches.isNotEmpty(), modifier = Modifier.testTag("replace-all"))
                            }
                        }
                    }
                }
                HorizontalDivider(color = HfpsTheme.colors.hairline, modifier = Modifier.padding(top = 6.dp))
                when {
                    editing -> {
                        val layout = if (rendered != null) editLayoutShown(layoutChoice, wide) else EditLayout.Editor
                        val editor: @Composable (Modifier) -> Unit = { m ->
                            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                textStyle = HfpsTheme.typography.mono.copy(color = HfpsTheme.colors.cardForeground, fontSize = prefs.fontSize.sp),
                                cursorBrush = SolidColor(HfpsTheme.colors.primary),
                                modifier = m.verticalScroll(rememberScrollState()).let { if (wrap) it else it.horizontalScroll(rememberScrollState()) }
                                    .padding(16.dp).focusRequester(focus).testTag("text-editor"),
                            )
                        }
                        when (layout) {
                            EditLayout.Editor -> editor(Modifier.fillMaxSize())
                            EditLayout.Preview -> Rendered(liveText, previewList)
                            EditLayout.Split -> Row(Modifier.fillMaxSize().testTag("text-split")) {
                                editor(Modifier.weight(1f).fillMaxHeight())
                                androidx.compose.material3.VerticalDivider(color = HfpsTheme.colors.hairline)
                                Box(Modifier.weight(1f).fillMaxHeight()) { Rendered(liveText, previewList) }
                            }
                        }
                    }
                    r.text.isEmpty() -> Text(stringResource(R.string.t_text_empty_file), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(16.dp))
                    shown == TextMode.Preview || shown == TextMode.Table -> Rendered(sourceText, list)
                    else -> CodeLines(viewText, extensionOf(name), highlights(name), wrap, prefs.fontSize, query.takeIf { finding }, matchCase, currentLine, list)
                }
            }
        }
    }

    when (problem) {
        SaveProblem.Conflict -> HoardDialog(
            title = stringResource(R.string.t_text_conflict_title, name),
            onDismiss = { problem = null },
            confirm = { TextButton({ problem = null; save(force = true) }) { Text(stringResource(R.string.t_text_overwrite), color = HfpsTheme.colors.destructive) } },
            dismiss = {
                Row {
                    TextButton({ problem = null; saveAsCopy() }) { Text(stringResource(R.string.t_text_save_copy), color = HfpsTheme.colors.primary) }
                    TextButton({ problem = null; editing = false; reload++ }) { Text(stringResource(R.string.t_text_reload_theirs), color = HfpsTheme.colors.foreground) }
                }
            },
        ) { Text(stringResource(R.string.t_text_conflict_body, formatFullDate(context, read?.modifiedAt)), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground) }
        SaveProblem.Deleted -> HoardDialog(
            title = stringResource(R.string.t_text_deleted_title, name),
            onDismiss = { problem = null },
            confirm = { TextButton({ problem = null; save(recreate = true) }) { Text(stringResource(R.string.t_text_save_again), color = HfpsTheme.colors.primary) } },
            dismiss = { TextButton({ problem = null; saveAsCopy() }) { Text(stringResource(R.string.t_text_save_copy), color = HfpsTheme.colors.foreground) } },
        ) { Text(stringResource(R.string.t_text_deleted_body), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground) }
        null -> Unit
    }
    if (leaving) {
        ConfirmDialog(
            title = stringResource(R.string.t_text_unsaved_title, name),
            body = stringResource(R.string.t_text_unsaved_leave_body),
            confirmLabel = stringResource(R.string.t_text_discard),
            destructive = true,
            onDismiss = { leaving = false },
            onConfirm = {
                editing = false
                onBack()
            },
        )
    }
}
