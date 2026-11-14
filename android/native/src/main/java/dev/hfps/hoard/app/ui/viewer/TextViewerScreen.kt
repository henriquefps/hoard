package dev.hfps.hoard.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

private sealed interface TextState {
    data object Loading : TextState
    data class Ready(val read: TextRead) : TextState
    data class Failed(val code: ErrorCode) : TextState
}

/**
 * The built-in text viewer and editor (Hoard 1's TextViewerPage, the essentials): the file in a monospaced view with
 * line numbers, find in file, then Edit → Save, which writes only if nobody changed the file since it was opened
 * (`expectedModifiedAt`; a conflict offers overwrite or reload). Read-only files and shares stay view-only.
 */
@Composable
fun TextViewerScreen(locationId: String, share: String?, path: String, startEditing: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as HoardApplication).container
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val machines by container.database.machines().observeAll().collectAsState(initial = null)
    val volumes by container.volumes.observe().collectAsState(initial = null)
    val phone = stringResource(R.string.t_locations_internal)
    val drive = stringResource(R.string.t_locations_drive)
    val location = remember(machines, volumes) {
        if (machines == null || volumes == null) null else FileService.resolve(locationId, share, machines!!, volumes!!, phone, drive)
    }
    val name = path.substringAfterLast('/')
    var state by remember { mutableStateOf<TextState>(TextState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(location?.id, reload) {
        val loc = location ?: return@LaunchedEffect
        state = TextState.Loading
        state = try {
            val read = container.files.run(loc) { fs, _ -> fs.readText(path, TEXT_VIEWER_MAX_BYTES.toInt(), false) }
            if (startEditing && reload == 0 && read.access != SmbAccess.READ) {
                editing = true
                draft = read.text
            }
            TextState.Ready(read)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            TextState.Failed(errorCodeOf(e))
        }
    }
    val ready = (state as? TextState.Ready)?.read
    val dirty = editing && ready != null && draft != ready.text

    fun save(force: Boolean = false) {
        val loc = location ?: return
        val read = ready ?: return
        saving = true
        scope.launch {
            try {
                val entry = container.files.run(loc) { fs, _ ->
                    fs.writeText(path, draft, read.encoding, "overwrite", if (force) null else read.modifiedAt)
                }
                state = TextState.Ready(read.copy(text = draft, modifiedAt = entry.modifiedAt, size = entry.size))
                editing = false
                toaster.show(context.getString(R.string.t_text_saved, name))
            } catch (e: Throwable) {
                when (val code = errorCodeOf(e)) {
                    ErrorCode.CONFLICT -> conflict = true
                    else -> toaster.show(context.getString(R.string.t_text_save_failed, name) + "\n" + context.getString(errorTitle(code)))
                }
            } finally {
                saving = false
            }
        }
    }

    BackHandler(enabled = dirty) { leaving = true }

    HfpsScaffold(topBar = {
        HfpsTopBar(name, onBack = { if (dirty) leaving = true else onBack() }, titleIsFileName = true) {
            val writable = ready != null && ready.access != SmbAccess.READ && !ready.truncated
            if (ready != null && !editing) {
                HfpsIconButton(PhosphorRegular.MagnifyingGlass, stringResource(R.string.t_text_find), { finding = !finding }, active = finding)
                HfpsIconButton(PhosphorRegular.ArrowSquareOut, stringResource(R.string.t_text_open_with), {
                    val activity = context.findActivity() ?: return@HfpsIconButton
                    val loc = location ?: return@HfpsIconButton
                    scope.launch {
                        runCatching { FileActions(container).open(activity, loc, SmbEntry(name, path, false, ready.size, ready.modifiedAt, false), chooser = true) }
                    }
                })
                if (writable) {
                    HfpsIconButton(PhosphorRegular.PencilSimple, stringResource(R.string.t_text_edit), {
                        draft = ready.text
                        editing = true
                    })
                }
            }
            if (editing) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp, color = HfpsTheme.colors.primary)
                } else {
                    HfpsIconButton(PhosphorRegular.Check, stringResource(R.string.t_text_save), { save() }, enabled = dirty)
                    HfpsIconButton(PhosphorRegular.X, stringResource(R.string.t_text_discard), { editing = false })
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
                    ErrorCode.TOO_LARGE -> null
                    else -> stringResource(errorBody(s.code))
                },
                tint = HfpsTheme.colors.destructive,
            ) {
                SecondaryButton(stringResource(R.string.t_common_retry), { reload++ })
            }
            is TextState.Ready -> Column(Modifier.fillMaxSize()) {
                val read = s.read
                val meta = listOfNotNull(
                    formatBytes(read.size),
                    read.encoding.uppercase(),
                    formatFullDate(context, read.modifiedAt).ifEmpty { null },
                    if (read.access == SmbAccess.READ) stringResource(R.string.t_text_read_only) else null,
                    if (dirty) stringResource(R.string.t_text_unsaved) else null,
                ).joinToString(" · ")
                Text(meta, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (finding && !editing) {
                    val matches = if (query.isBlank()) 0 else read.text.lineSequence().count { it.contains(query, ignoreCase = true) }
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(query, { query = it }, Modifier.weight(1f), label = stringResource(R.string.t_text_find))
                        if (query.isNotBlank()) {
                            Text(
                                if (matches == 0) stringResource(R.string.t_text_no_matches) else pluralStringResource(R.plurals.t_text_lines, matches, matches.toString()),
                                style = HfpsTheme.typography.ui,
                                color = HfpsTheme.colors.mutedForeground,
                            )
                        }
                    }
                }
                HorizontalDivider(color = HfpsTheme.colors.hairline, modifier = Modifier.padding(top = 8.dp))
                if (editing) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = HfpsTheme.typography.mono.copy(color = HfpsTheme.colors.cardForeground),
                        cursorBrush = SolidColor(HfpsTheme.colors.primary),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).focusRequester(focus).testTag("text-editor"),
                    )
                } else if (read.text.isEmpty()) {
                    Text(stringResource(R.string.t_text_empty_file), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(16.dp))
                } else {
                    TextLines(read.text, query.takeIf { finding })
                }
            }
        }
    }

    if (conflict) {
        ConfirmDialog(
            title = stringResource(R.string.t_text_conflict_title, name),
            body = stringResource(R.string.t_text_conflict_body, ""),
            confirmLabel = stringResource(R.string.t_text_overwrite),
            destructive = true,
            onDismiss = { conflict = false },
            onConfirm = { save(force = true) },
        )
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

@Composable
private fun TextLines(text: String, query: String?) {
    val lines = remember(text) { text.split('\n') }
    val digits = lines.size.toString().length
    val state = rememberLazyListState()
    val highlight = HfpsTheme.colors.warning.copy(alpha = 0.35f)
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).testTag("text-lines"), state = state) {
            itemsIndexed(lines) { index, line ->
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        (index + 1).toString().padStart(digits),
                        style = HfpsTheme.typography.mono,
                        color = HfpsTheme.colors.mutedForeground.copy(alpha = 0.6f),
                        modifier = Modifier.width((digits * 9 + 12).dp),
                    )
                    val q = query?.takeIf { it.isNotBlank() }
                    if (q == null || !line.contains(q, ignoreCase = true)) {
                        Text(line.trimEnd('\r'), style = HfpsTheme.typography.mono, color = HfpsTheme.colors.cardForeground, softWrap = false)
                    } else {
                        Text(
                            buildAnnotatedString {
                                var start = 0
                                val lower = line.lowercase()
                                val needle = q.lowercase()
                                while (true) {
                                    val at = lower.indexOf(needle, start)
                                    if (at < 0) {
                                        append(line.substring(start))
                                        break
                                    }
                                    append(line.substring(start, at))
                                    withStyle(SpanStyle(background = highlight)) { append(line.substring(at, at + needle.length)) }
                                    start = at + needle.length
                                }
                            },
                            style = HfpsTheme.typography.mono,
                            color = HfpsTheme.colors.cardForeground,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
