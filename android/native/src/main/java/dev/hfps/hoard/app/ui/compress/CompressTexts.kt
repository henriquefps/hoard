package dev.hfps.hoard.app.ui.compress

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.server.api.Compress

/** The server's reason a target is not usable (`GET /v1/server` `compress.targets`), in the owner's words. */
@Composable
fun unavailableText(reason: String): String = when (reason) {
    "no_gpu", "no_tools" -> stringResource(R.string.compress_unavailable_gpu)
    "no_write_mount", "write_mount_mismatch", "write_mount_read_only", "unstable_inode" -> stringResource(R.string.compress_unavailable_write_mount, reason)
    "no_backup_root" -> stringResource(R.string.compress_unavailable_backup_root)
    else -> stringResource(R.string.compress_unavailable_other, reason)
}

/** One skip reason of an estimate ("180 already compressed by an older tool"). */
@Composable
fun skipText(reason: String, n: Int): String {
    val count = formatNumber(n, locale())
    val res = when (reason) {
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
        else -> R.plurals.compress_skip_other
    }
    return pluralStringResource(res, n, count)
}

/** A failure reason of a job's items (`encoder`, `verify_*`, `source_changed`, `no_space`, `trash_failed`, …). */
@Composable
fun failedText(reason: String): String = when {
    reason.startsWith("verify") -> stringResource(R.string.compress_failed_reason_verify)
    reason == "source_changed" -> stringResource(R.string.compress_failed_reason_changed)
    reason == "no_space" -> stringResource(R.string.compress_failed_reason_space)
    reason == "encoder" -> stringResource(R.string.compress_failed_reason_encoder)
    reason == "trash_failed" -> stringResource(R.string.compress_failed_reason_trash)
    reason == "not_writable" -> stringResource(R.string.compress_failed_reason_writable)
    else -> stringResource(R.string.compress_failed_reason_other)
}

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
