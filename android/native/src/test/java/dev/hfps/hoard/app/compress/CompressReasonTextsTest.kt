package dev.hfps.hoard.app.compress

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.compress.CompressReasonTexts
import dev.hfps.hoard.server.compress.CompressReasons
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR 0025 Amendment (2.7.1): every reason the server can show is a human sentence in English and Portuguese, never the
 * raw code; a code Hoard does not know gets a generic sentence and the code to show in small type.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompressReasonTextsTest {
    private fun res(tag: String): Resources {
        RuntimeEnvironment.setQualifiers(if (tag == "pt") "+pt-rBR" else "+en")
        return ApplicationProvider.getApplicationContext<android.content.Context>().resources
    }

    /** A code as the server writes it: with an underscore (a plain word such as `encoder` may be in the sentence). */
    private fun raw(code: String): String = if ('_' in code) code else "\u0000$code"

    private fun both(check: (Resources) -> Unit) {
        for (tag in listOf("en", "pt")) check(res(tag))
    }

    @Test
    fun everyTargetReasonHasItsOwnSentenceWithoutTheCode() = both { r ->
        for (code in CompressReasons.TARGET) {
            val t = CompressReasonTexts.target(r, code)
            assertThat(t.code).isNull()
            assertThat(t.text).doesNotContain(code)
            assertThat(t.text).isNotEqualTo(r.getString(R.string.compress_unavailable_other))
        }
        // Each write-mount reason says something different.
        val texts = listOf("no_write_mount", "write_mount_mismatch", "write_mount_read_only", "unstable_inode").map { CompressReasonTexts.target(r, it).text }
        assertThat(texts.toSet()).hasSize(4)
    }

    @Test
    fun everySkipReasonHasItsOwnPlural() = both { r ->
        for (code in CompressReasons.SKIP) {
            for (n in listOf(1, 5)) {
                val t = CompressReasonTexts.skip(r, code, n, n.toString())
                assertThat(t.code).isNull()
                assertThat(t.text).contains(n.toString())
                assertThat(t.text).doesNotContain(raw(code))
                assertThat(t.text).isNotEqualTo(r.getQuantityString(R.plurals.compress_skip_other, n, n.toString()))
            }
        }
    }

    @Test
    fun everyFailureReasonHasItsOwnSentence() = both { r ->
        for (code in CompressReasons.FAILED) {
            val t = CompressReasonTexts.failed(r, code)
            assertThat(t.code).isNull()
            assertThat(t.text).doesNotContain(raw(code))
            assertThat(t.text).isNotEqualTo(r.getString(R.string.compress_failed_reason_other))
        }
    }

    @Test
    fun noSpaceReadsAsTheWaitNotAFailure() {
        // ADR 0025 Amendment (2.9.1): the web's words of hoard-server 0.14.0.
        assertThat(CompressReasonTexts.failed(res("en"), "no_space").text).isEqualTo("Waiting for free space on the NAS: empty the trash")
        assertThat(CompressReasonTexts.failed(res("pt"), "no_space").text).isEqualTo("Aguardando espaço livre no NAS: esvazie a lixeira")
    }

    @Test
    fun startErrorsAreSentences() = both { r ->
        for (code in CompressReasons.ROUTE.filter { it == "exists" || it == "busy" } + listOf("offline", "not_paired") + CompressReasons.TARGET) {
            val t = CompressReasonTexts.start(r, code)
            assertThat(t.code).isNull()
            assertThat(t.text).doesNotContain(code)
        }
    }

    @Test
    fun anUnknownCodeFallsBackToAGenericSentenceAndKeepsTheCode() = both { r ->
        val code = "quantum_flux"
        assertThat(CompressReasonTexts.target(r, code)).isEqualTo(dev.hfps.hoard.app.ui.compress.ReasonText(r.getString(R.string.compress_unavailable_other), code))
        assertThat(CompressReasonTexts.skip(r, code, 2, "2").code).isEqualTo(code)
        assertThat(CompressReasonTexts.failed(r, code).code).isEqualTo(code)
        assertThat(CompressReasonTexts.start(r, code).code).isEqualTo(code)
        assertThat(CompressReasonTexts.target(r, code).text).doesNotContain(code)
        assertThat(CompressReasonTexts.inline(r, CompressReasonTexts.failed(r, code))).endsWith("($code)")
    }

    @Test
    fun theSubfoldersLineIsPluralInBothLanguages() {
        val en = res("en")
        assertThat(en.getQuantityString(R.plurals.compress_estimate_with_subfolders, 1, "5 videos", "1")).isEqualTo("5 videos, including 1 in a sub-folder")
        assertThat(en.getQuantityString(R.plurals.compress_estimate_with_subfolders, 3, "5 videos", "3")).isEqualTo("5 videos, including 3 in sub-folders")
        val pt = res("pt")
        assertThat(pt.getQuantityString(R.plurals.compress_estimate_with_subfolders, 1, "5 vídeos", "1")).isEqualTo("5 vídeos, incluindo 1 em uma subpasta")
        assertThat(pt.getQuantityString(R.plurals.compress_estimate_with_subfolders, 3, "5 vídeos", "3")).isEqualTo("5 vídeos, incluindo 3 em subpastas")
    }
}
