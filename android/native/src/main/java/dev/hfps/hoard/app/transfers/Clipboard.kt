package dev.hfps.hoard.app.transfers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The file manager clipboard (ADR 0007, src/features/transfers/clipboard.ts): "Copy" or "Move" puts the selection
 * here, the user goes anywhere and "Paste here" starts the transfer. In memory only (like a desktop clipboard it does
 * not survive the process); one entry at a time.
 */
data class ClipboardContent(
    /** `copy` or `move`. */
    val op: String,
    /** The folder the entries were picked in. */
    val source: FolderPlace,
    /** Shown in the bar: the folder's name, or the location's. */
    val sourceLabel: String,
    val entries: List<PickedEntry>,
)

/** The folder on screen, where "Paste here" puts things; [readOnlyReason] says why pasting is off there. */
data class PasteTarget(val folder: FolderPlace, val label: String, val readOnlyReason: String? = null)

class Clipboard {
    private val _content = MutableStateFlow<ClipboardContent?>(null)
    val content: StateFlow<ClipboardContent?> = _content.asStateFlow()

    private val _target = MutableStateFlow<PasteTarget?>(null)
    val target: StateFlow<PasteTarget?> = _target.asStateFlow()

    fun set(content: ClipboardContent) {
        _content.value = content
    }

    fun clear() {
        _content.value = null
    }

    fun setTarget(target: PasteTarget) {
        _target.value = target
    }

    /** Leaves the target only if it is still [folder] (a newer screen may have set its own). */
    fun clearTarget(folder: FolderPlace) {
        if (_target.value?.folder == folder) _target.value = null
    }
}
