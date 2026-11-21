package dev.hfps.hoard.app.ui.common

import android.content.Context
import android.icu.text.DateFormat as IcuDateFormat
import android.icu.text.DisplayContext
import android.icu.text.NumberFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.hfps.hoard.app.files.formatBytes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
 * "Oct 24" this year or "Oct 24, 2024" before. Always in [locale] (the app's language, not the system's).
 */
fun formatRelativeDate(context: Context, iso: String?, now: Long = System.currentTimeMillis(), locale: Locale = context.appLocale()): String {
    val millis = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return "—"
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    if (days in 0..6) {
        val f = relativeFormatter(locale, RelativeDateTimeFormatter.Style.SHORT, DisplayContext.CAPITALIZATION_NONE)
        return when (days) {
            0L -> f.format(RelativeDateTimeFormatter.Direction.THIS, RelativeDateTimeFormatter.AbsoluteUnit.DAY).replaceFirstChar { it.titlecase(locale) }
            1L -> f.format(RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.AbsoluteUnit.DAY).replaceFirstChar { it.titlecase(locale) }
            else -> f.format(days.toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        }
    }
    val skeleton = if (day.year == today.year) "MMMd" else "yMMMd"
    return IcuDateFormat.getInstanceForSkeleton(skeleton, locale).format(Date(millis))
}

/**
 * "2 min ago" / "há 2 min" for an instant, in [locale]: for the middle of a sentence ("Synced …"), so no capital
 * letter. Under a minute is "now"/"agora"; up to 59 minutes, then hours, then days.
 */
fun formatRelativeTime(millis: Long, now: Long, locale: Locale): String {
    val f = relativeFormatter(locale, RelativeDateTimeFormatter.Style.SHORT, DisplayContext.CAPITALIZATION_NONE)
    val diff = now - millis
    val direction = if (diff >= 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
    val minutes = abs(diff) / 60_000L
    return when {
        minutes < 1 -> f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        minutes < 60 -> f.format(minutes.toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        minutes < 24 * 60 -> f.format((minutes / 60).toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        else -> f.format((minutes / (24 * 60)).toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    }
}

/** An ISO date or instant ("2026-11-20…") as a medium date in [locale] ("20 Nov 2026" / "20 de nov. de 2026"). */
fun formatIsoDay(iso: String, locale: Locale): String {
    val day = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
}

/** Full date and time (details). */
fun formatFullDate(context: Context, iso: String?, locale: Locale = context.appLocale()): String {
    val millis = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return ""
    return IcuDateFormat.getInstanceForSkeleton("yMMMdjm", locale).format(Date(millis))
}

/** The app's language for the resources of [this] (the per-app language, not the system's). */
fun Context.appLocale(): Locale = resources.configuration.locales[0]

private fun relativeFormatter(locale: Locale, style: RelativeDateTimeFormatter.Style, context: DisplayContext) =
    RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale), NumberFormat.getInstance(locale), style, context)

@Composable
fun bytes(value: Long): String = formatBytes(value, locale())

@Composable
fun locale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

fun formatNumber(value: Number, locale: Locale): String = java.text.NumberFormat.getNumberInstance(locale).format(value)

