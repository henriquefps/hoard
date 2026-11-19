package dev.hfps.hoard.app.ui.common

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.FileKind
import dev.hfps.hoard.app.route.RouteState
import dev.hfps.hoard.app.route.RouteStatus
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// --- Toasts ---------------------------------------------------------------------------------------------------------

/** Hoard 1's sonner toasts as snackbars: one host in the shell, reached from any screen. */
class Toaster(val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = action,
                withDismissAction = action == null,
                duration = if (long || action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("No Toaster") }

@Composable
fun rememberToaster(host: SnackbarHostState): Toaster {
    val scope = rememberCoroutineScope()
    return remember(host, scope) { Toaster(host, scope) }
}

// --- Route line -----------------------------------------------------------------------------------------------------

/**
 * The route in use, always visible: "Local · 192.168.1.155 · 4 ms", "Checking…", "Wrong user or password". One line;
 * the dot is primary (online), destructive (error) or muted (offline).
 */
@Composable
fun RouteLine(state: RouteState, modifier: Modifier = Modifier, hideHost: Boolean = false) {
    val route = state.route
    val checking = state.status == RouteStatus.Checking || state.status == RouteStatus.Idle
    val kind = if (route != null && (state.status == RouteStatus.Online || checking)) stringResource(addressKindText(route.kind)) else null
    val label = when {
        kind != null -> listOfNotNull(
            route!!.host.takeUnless { hideHost },
            route.latencyMs?.let { stringResource(R.string.t_route_latency, it.toString()) },
        ).joinToString(" · ")
        checking -> stringResource(R.string.t_route_checking)
        else -> state.errorCode?.let { stringResource(problemText(it)) }.orEmpty()
    }
    val color = if (state.status == RouteStatus.Error) HfpsTheme.colors.destructive else HfpsTheme.colors.mutedForeground
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = HfpsTheme.colors.mutedForeground)
        } else {
            val dot = when (state.status) {
                RouteStatus.Online -> HfpsTheme.colors.primary
                RouteStatus.Error -> HfpsTheme.colors.destructive
                else -> HfpsTheme.colors.mutedForeground.copy(alpha = 0.6f)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        }
        val text = listOfNotNull(kind, label.ifEmpty { null }).joinToString(" · ")
        Text(text, style = HfpsTheme.typography.ui, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --- States ---------------------------------------------------------------------------------------------------------

/** An empty or error state inside the card: icon, title, body and actions, centred. */
@Composable
fun StateMessage(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tint: Color = HfpsTheme.colors.primary,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(28.dp), tint = tint) }
        Text(title, style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground, textAlign = TextAlign.Center)
        if (!body.isNullOrEmpty()) {
            Text(
                body,
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

// --- Dialogs --------------------------------------------------------------------------------------------------------

/**
 * Name a folder, a file, a favourite or a share: the field starts with [initialValue] (the base name selected for
 * files), [validate] answers an error text or null, and [onSubmit] may fail with a message shown under the field.
 */
@Composable
fun NameDialog(
    title: String,
    label: String,
    initialValue: String,
    submitLabel: String,
    onDismiss: () -> Unit,
    validate: (String) -> String?,
    onSubmit: suspend (String) -> String?,
    description: String? = null,
    selectBaseName: Boolean = false,
) {
    val end = if (selectBaseName) initialValue.lastIndexOf('.').takeIf { it > 0 } ?: initialValue.length else initialValue.length
    var value by remember { mutableStateOf(TextFieldValue(initialValue, TextRange(0, end))) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        val problem = validate(value.text)
        if (problem != null) {
            error = problem
        } else if (!busy) {
            busy = true
            scope.launch {
                error = onSubmit(value.text.trim())
                busy = false
                if (error == null) onDismiss()
            }
        }
    }
    HoardDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        confirm = { PrimaryButton(submitLabel, { submit() }, enabled = !busy && value.text.isNotBlank()) },
        dismiss = { SecondaryButton(stringResource(R.string.t_common_cancel), onDismiss, enabled = !busy) },
    ) {
        if (description != null) {
            Text(description, style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                error = null
            },
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = HfpsTheme.colors.ring,
    unfocusedBorderColor = HfpsTheme.colors.input,
    focusedLabelColor = HfpsTheme.colors.primary,
    cursorColor = HfpsTheme.colors.primary,
    focusedTextColor = HfpsTheme.colors.cardForeground,
    unfocusedTextColor = HfpsTheme.colors.cardForeground,
    unfocusedLabelColor = HfpsTheme.colors.mutedForeground,
    errorBorderColor = HfpsTheme.colors.destructive,
    errorLabelColor = HfpsTheme.colors.destructive,
    errorSupportingTextColor = HfpsTheme.colors.destructive,
)

/** A confirmation (delete, Reveal batches): [destructive] colours the action. */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Unit,
    body: String? = null,
    destructive: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    HoardDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        confirm = {
            TextButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onConfirm()
                        } finally {
                            busy = false
                            onDismiss()
                        }
                    }
                },
                enabled = !busy,
            ) {
                Text(
                    confirmLabel,
                    color = if (destructive) HfpsTheme.colors.destructive else HfpsTheme.colors.primary,
                    style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        },
        dismiss = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
    ) {
        if (body != null) Text(body, style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        extra()
    }
}

/** The hfps dialog frame: the card colour and shape, a title and a body. */
@Composable
fun HoardDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = confirm,
        dismissButton = dismiss,
        title = { Text(title, style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground) },
        text = { Column { content() } },
        containerColor = HfpsTheme.colors.card,
        shape = HfpsTheme.shapes.card,
    )
}

/** One menu item with an icon (Hoard 1's dropdown rows). */
@Composable
fun MenuItem(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val color = if (destructive) HfpsTheme.colors.destructive else HfpsTheme.colors.foreground
    DropdownMenuItem(
        text = { Text(text, style = HfpsTheme.typography.body) },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(20.dp)) } },
        trailingIcon = trailing,
        colors = MenuDefaults.itemColors(
            textColor = color,
            leadingIconColor = color,
            disabledTextColor = HfpsTheme.colors.mutedForeground.copy(alpha = 0.6f),
            disabledLeadingIconColor = HfpsTheme.colors.mutedForeground.copy(alpha = 0.6f),
        ),
    )
}

// --- Files ----------------------------------------------------------------------------------------------------------

fun fileIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.Folder -> PhosphorRegular.FolderSimple
    FileKind.Image -> PhosphorRegular.FileImage
    FileKind.Video -> PhosphorRegular.FileVideo
    FileKind.Audio -> PhosphorRegular.FileAudio
    FileKind.Document -> PhosphorRegular.FileDoc
    FileKind.Spreadsheet -> PhosphorRegular.FileXls
    FileKind.Pdf -> PhosphorRegular.FilePdf
    FileKind.Archive -> PhosphorRegular.FileZip
    FileKind.Code -> PhosphorRegular.FileCode
    FileKind.Text -> PhosphorRegular.FileText
    FileKind.Other -> PhosphorRegular.File
}

/** Decoded thumbnails, in memory (the JPEG files themselves are :core:smb's disk cache). */
object Thumbnails {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun decode(key: String, file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.also { cache.put(key, it) }
    }
}

/**
 * A 40dp tile: the file's thumbnail when [load] gives one (images and video frames), the kind's icon otherwise and
 * while it loads.
 */
@Composable
fun FileTile(kind: FileKind, thumbnailKey: String?, load: (suspend () -> File?)?, size: Dp = 40.dp, tint: Color = HfpsTheme.colors.primary) {
    val bitmap by produceState(thumbnailKey?.let(Thumbnails::cached), thumbnailKey) {
        if (value == null && thumbnailKey != null && load != null) {
            value = runCatching { load()?.let { Thumbnails.decode(thumbnailKey, it) } }.getOrNull()
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(
            if (kind == FileKind.Folder) tint.copy(alpha = 0.1f) else HfpsTheme.colors.muted,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(
                fileIcon(kind),
                null,
                Modifier.size(size / 2),
                tint = if (kind == FileKind.Folder) tint else HfpsTheme.colors.mutedForeground,
            )
        }
    }
}
