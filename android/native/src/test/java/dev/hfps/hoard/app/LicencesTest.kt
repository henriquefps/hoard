package dev.hfps.hoard.app

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.ui.settings.LICENCES
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * Settings › Licences and `docs/licences.md` list the same components (CLAUDE.md "Licences"), and every component's
 * name is a resource of `licences.xml` (not translated, never written in code).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LicencesTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun ptBr() = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("pt-BR")) })

    @Test
    fun everyComponentIsInTheDocsAndNamedTheSameInEveryLanguage() {
        val docs = listOf(File("../../docs/licences.md"), File("../docs/licences.md"), File("docs/licences.md")).first { it.isFile }.readText()
        val pt = ptBr()
        for (licence in LICENCES) {
            val name = app.getString(licence.name)
            assertThat(pt.getString(licence.name)).isEqualTo(name)
            assertThat(docs).contains("| $name |")
        }
        assertThat(LICENCES.map { app.getString(it.name) }).contains("Google code scanner (ML Kit, through Google Play services)")
        assertThat(docs).contains("play-services-code-scanner")
    }
}
