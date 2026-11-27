package dev.hfps.hoard.app.backup

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR 0024 Amendment (2.9.1): the purge notice and the server-full wait in English and Portuguese. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupWaitTextsTest {
    private fun res(tag: String): Resources {
        RuntimeEnvironment.setQualifiers(if (tag == "pt") "+pt-rBR" else "+en")
        return ApplicationProvider.getApplicationContext<android.content.Context>().resources
    }

    @Test
    fun bothLanguagesSayIt() {
        val en = res("en")
        assertThat(en.getString(R.string.backup_restarted)).isEqualTo("The photo server's backup of this phone was deleted; Hoard is backing it up again")
        assertThat(en.getString(R.string.backup_status_waiting_full, "2.1 GB")).isEqualTo("Waiting: the photo server's drive is full (2.1 GB free)")
        assertThat(en.getString(R.string.backup_status_waiting_full_unknown)).isEqualTo("Waiting: the photo server's drive is full")
        val pt = res("pt")
        assertThat(pt.getString(R.string.backup_restarted)).isEqualTo("O backup deste celular no servidor de fotos foi apagado; o Hoard está fazendo o backup de novo")
        assertThat(pt.getString(R.string.backup_status_waiting_full, "2,1 GB")).isEqualTo("Aguardando: o disco do servidor de fotos está cheio (2,1 GB livres)")
        assertThat(pt.getQuantityString(R.plurals.compress_wait_count, 2, "2")).isEqualTo("2 aguardando espaço livre")
    }
}
