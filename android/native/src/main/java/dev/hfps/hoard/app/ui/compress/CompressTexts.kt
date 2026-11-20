package dev.hfps.hoard.app.ui.compress

import android.content.res.Resources
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.server.api.Compress

/** A reason in the owner's words; [code] is the server's, set only when Hoard has no sentence for it (shown in small type). */
data class ReasonText(val text: String, val code: String? = null)

/**
 * Every reason the server can give for compression, as a short human sentence in the app's language (ADR 0025
 * Amendment (2.7.1)). The tables are what [dev.hfps.hoard.server.compress.CompressReasons] lists; a code that is in none
 * (a newer server) gets a generic sentence and its [ReasonText.code].
 */
object CompressReasonTexts {
    fun targetRes(reason: String): Int? = when (reason) {
        "no_gpu" -> R.string.compress_unavailable_gpu
        "no_tools" -> R.string.compress_unavailable_no_tools
        "no_backup_root" -> R.string.compress_unavailable_backup_root
        "no_write_mount" -> R.string.compress_unavailable_no_write_mount
        "write_mount_mismatch" -> R.string.compress_unavailable_write_mount_mismatch
        "write_mount_read_only" -> R.string.compress_unavailable_write_mount_read_only
        "unstable_inode" -> R.string.compress_unavailable_unstable_inode
        // This phone's own reasons for the phone target (ADR 0025 A2), not the server's.
        dev.hfps.hoard.server.compress.CompressGate.REASON_PHONE_ANDROID11 -> R.string.compress_phone_needs_android11
        dev.hfps.hoard.server.compress.CompressGate.REASON_PHONE_ALL_FILES -> R.string.compress_phone_needs_all_files
        else -> null
    }

    fun skipRes(reason: String): Int? = when (reason) {
        "already_compressed" -> R.plurals.compress_skip_already
        "older_compression" -> R.plurals.compress_skip_older
        "efficient" -> R.plurals.compress_skip_efficient
        "small_saving" -> R.plurals.compress_skip_small
        "unreadable" -> R.plurals.compress_skip_unreadable
        "dolby_vision" -> R.plurals.compress_skip_dolby_vision
        "reveal_record" -> R.plurals.compress_skip_reveal
        "sync_artifact" -> R.plurals.compress_skip_sync_artifact
        "not_writable" -> R.plurals.compress_skip_not_writable
        "busy" -> R.plurals.compress_skip_busy
        "not_video" -> R.plurals.compress_skip_not_video
        "trashed" -> R.plurals.compress_skip_trashed
        "not_in_target" -> R.plurals.compress_skip_not_in_target
        else -> null
    }

    fun failedRes(reason: String): Int? = when (reason) {
        "verify_quality" -> R.string.compress_failed_reason_verify
        "verify_metadata" -> R.string.compress_failed_reason_metadata
        "verify_duration", "verify_streams", "verify_picture", "verify_frames", "verify_decode", "verify_phone" -> R.string.compress_failed_reason_mismatch
        "source_changed" -> R.string.compress_failed_reason_changed
        "no_space" -> R.string.compress_failed_reason_space
        "encoder" -> R.string.compress_failed_reason_encoder
        "trash_failed" -> R.string.compress_failed_reason_trash
        "not_writable" -> R.string.compress_failed_reason_writable
        "unreadable" -> R.string.compress_failed_reason_unreadable
        "output_missing" -> R.string.compress_failed_reason_missing
        else -> null
    }

    /** Why a target is not usable (`GET /v1/server` `compress.targets`). */
    fun target(res: Resources, reason: String): ReasonText =
        targetRes(reason)?.let { ReasonText(res.getString(it)) } ?: ReasonText(res.getString(R.string.compress_unavailable_other), reason)

    /** One skip reason of an estimate ("180 already compressed by an older tool"); [count] is [n] formatted. */
    fun skip(res: Resources, reason: String, n: Int, count: String): ReasonText =
        skipRes(reason)?.let { ReasonText(res.getQuantityString(it, n, count)) } ?: ReasonText(res.getQuantityString(R.plurals.compress_skip_other, n, count), reason)

    /** A failure reason of a job's items (`encoder`, `verify_*`, `source_changed`, `no_space`, `trash_failed`, …). */
    fun failed(res: Resources, reason: String): ReasonText =
        failedRes(reason)?.let { ReasonText(res.getString(it)) } ?: ReasonText(res.getString(R.string.compress_failed_reason_other), reason)

    /** Why a start (or its estimate) failed: the server's reason, or `offline`. */
    fun start(res: Resources, reason: String): ReasonText = when {
        reason == "exists" -> ReasonText(res.getString(R.string.compress_exists))
        reason == "busy" -> ReasonText(res.getString(R.string.compress_busy))
        reason == "offline" || reason == "not_paired" -> ReasonText(res.getString(R.string.compress_load_failed))
        targetRes(reason) != null -> target(res, reason)
        else -> ReasonText(res.getString(R.string.compress_start_failed), reason)
    }

    /** The sentence, then the code in parentheses when there is one (for a toast or a list row, which have no small type). */
    fun inline(res: Resources, r: ReasonText): String = r.code?.let { "${r.text} (${it})" } ?: r.text
}

/** A [ReasonText]: its sentence, and the unknown code under it in small type. */
@Composable
fun ReasonLine(reason: ReasonText, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(reason.text, style = style, color = color)
        reason.code?.let { Text(stringResource(R.string.compress_reason_code, it), style = HfpsTheme.typography.ui.copy(fontSize = 10.sp), color = HfpsTheme.colors.mutedForeground) }
    }
}

@Composable
fun unavailableText(reason: String): ReasonText = CompressReasonTexts.target(LocalResources.current, reason)

/** One skip reason of an estimate ("180 already compressed by an older tool"). */
@Composable
fun skipText(reason: String, n: Int): ReasonText = CompressReasonTexts.skip(LocalResources.current, reason, n, formatNumber(n, locale()))

/** "about 35 min", "about 2 h 5 min" (at least a minute). */
@Composable
fun minutesText(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) {
        stringResource(R.string.compress_duration_hours, (minutes / 60).toString(), (minutes % 60).toString())
    } else {
        stringResource(R.string.compress_duration_min, minutes.toString())
    }
}

/** A profile's name and description. */
@Composable
fun profileText(id: String): Pair<String, String> = when (id) {
    Compress.PROFILE_SMALLER -> stringResource(R.string.compress_profile_smaller) to stringResource(R.string.compress_profile_smaller_desc)
    Compress.PROFILE_AV1 -> stringResource(R.string.compress_profile_av1) to stringResource(R.string.compress_profile_av1_desc)
    else -> stringResource(R.string.compress_profile_same) to stringResource(R.string.compress_profile_same_desc)
}
