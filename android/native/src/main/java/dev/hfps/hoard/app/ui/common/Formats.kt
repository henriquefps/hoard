package dev.hfps.hoard.app.ui.common

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.hfps.hoard.app.files.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "Now" for relative dates on screen: the real clock, a fixed instant in screenshot tests (so goldens that show
 * "today" or "yesterday" do not depend on the day they are verified).
 */
val LocalNow = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/** [formatRelativeDate] against [LocalNow]. */
@Composable
fun relativeDate(context: Context, iso: String?): String = formatRelativeDate(context, iso, LocalNow.current())

/**
 * Relative date without time (the hfps card-list rule, src/lib/format.ts): today, yesterday, N days ago up to 6, then
 * "Oct 24" this year or "Oct 24, 2024" before.
 */
fun formatRelativeDate(context: Context, iso: String?, now: Long = System.currentTimeMillis()): String {
    val millis = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return "—"
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    if (days in 0..6) {
        return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    flags = flags or if (day.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, millis, flags)
}

/** Full date and time (details). */
fun formatFullDate(context: Context, iso: String?): String {
    val millis = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return ""
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
}

@Composable
fun bytes(value: Long): String = formatBytes(value, locale())

@Composable
fun locale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

fun formatNumber(value: Number, locale: Locale): String = java.text.NumberFormat.getNumberInstance(locale).format(value)

