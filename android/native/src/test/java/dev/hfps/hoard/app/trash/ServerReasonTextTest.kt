package dev.hfps.hoard.app.trash

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.ui.trash.serverReasonText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** hoard-server 0.15.0's `web_only` and `not_hoard` in words (en, pt-BR), never the raw code. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ServerReasonTextTest {
    private fun text(reason: String) = serverReasonText(ApplicationProvider.getApplicationContext<android.content.Context>().resources, reason)

    @Test
    fun webOnlyAndNotHoardHaveWords() {
        assertThat(text("web_only")).isEqualTo("only the web gallery can delete it permanently")
        assertThat(text("not_hoard")).isEqualTo("this photo server lets only Hoard do this")
        RuntimeEnvironment.setQualifiers("pt-rBR")
        assertThat(text("web_only")).isEqualTo("só a galeria web pode apagá-la de vez")
        assertThat(text("not_hoard")).isEqualTo("este servidor de fotos só deixa o Hoard fazer isso")
    }
}
