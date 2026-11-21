package dev.hfps.hoard.app.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Relative and absolute dates follow the app's language, not the system's (Hoard 2.9.0). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FormatsTest {
    private val en = Locale.forLanguageTag("en")
    private val pt = Locale.forLanguageTag("pt-BR")
    private val now = 1_800_000_000_000L

    @Test fun relativeTimeInEnglish() {
        assertThat(formatRelativeTime(now - 2 * 60_000, now, en)).isEqualTo("2 min. ago")
        assertThat(formatRelativeTime(now - 5_000, now, en)).isEqualTo("now")
        assertThat(formatRelativeTime(now - 3 * 3_600_000, now, en)).isEqualTo("3 hr. ago")
        assertThat(formatRelativeTime(now - 2 * 86_400_000L, now, en)).isEqualTo("2 days ago")
    }

    @Test fun relativeTimeInPortuguese() {
        assertThat(formatRelativeTime(now - 2 * 60_000, now, pt)).isEqualTo("há 2 min.")
        assertThat(formatRelativeTime(now - 5_000, now, pt)).isEqualTo("agora")
        assertThat(formatRelativeTime(now - 3 * 3_600_000, now, pt)).isEqualTo("há 3 h")
        assertThat(formatRelativeTime(now - 2 * 86_400_000L, now, pt)).isEqualTo("há 2 dias")
    }

    @Test fun isoDayFollowsTheLocale() {
        assertThat(formatIsoDay("2026-11-20T10:00:00Z", en)).isEqualTo("Nov 20, 2026")
        assertThat(formatIsoDay("2026-11-20T10:00:00Z", pt)).isEqualTo("20 de nov. de 2026")
        assertThat(formatIsoDay("garbage", pt)).isEqualTo("garbage")
    }

    @Test fun relativeDateIgnoresTheSystemLocale() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val iso = "2026-11-12T10:00:00Z"
        val at = java.time.Instant.parse("2026-11-14T15:00:00Z").toEpochMilli()
        assertThat(formatRelativeDate(context, iso, at, en)).isEqualTo("2 days ago")
        assertThat(formatRelativeDate(context, iso, at, pt)).isEqualTo("há 2 dias")
        assertThat(formatRelativeDate(context, "2026-11-14T10:00:00Z", at, en)).isEqualTo("Today")
        assertThat(formatRelativeDate(context, "2026-11-14T10:00:00Z", at, pt)).isEqualTo("Hoje")
        assertThat(formatRelativeDate(context, "2026-11-13T10:00:00Z", at, pt)).isEqualTo("Ontem")
        assertThat(formatRelativeDate(context, "2026-10-02T10:00:00Z", at, pt)).isEqualTo("2 de out.")
    }
}
