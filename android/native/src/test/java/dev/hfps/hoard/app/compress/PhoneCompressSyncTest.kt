package dev.hfps.hoard.app.compress

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.data.HoardDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * ADR 0025 Amendment (2.8.1): a replace or restore reported schedules a Gallery sync (so the rewritten file shows as
 * one cell), and the "ready to replace" notification is on a channel that shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneCompressSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aReportSchedulesAGallerySync() {
        val direct = Executors.newSingleThreadExecutor()
        WorkManager.initialize(context, Configuration.Builder().setExecutor(direct).setTaskExecutor(direct).build())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gallery = GalleryRepository(context, HoardDatabase.open(context, name = null).cull(), scope, "2.8.1", true)
        val phone = PhoneCompress(context, gallery, scope)
        assertThat(WorkManager.getInstance(context).getWorkInfosForUniqueWork(GALLERY_SYNC_SOON).get()).isEmpty()
        phone.reported(1)
        assertThat(WorkManager.getInstance(context).getWorkInfosForUniqueWork(GALLERY_SYNC_SOON).get()).isNotEmpty()
    }

    @Test
    fun theReadyToReplaceNotificationShows() {
        CompressNotifications.consent(context, 3)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CompressNotifications.CHANNEL_CONSENT)
        assertThat(channel.importance).isAtLeast(NotificationManager.IMPORTANCE_DEFAULT)
        assertThat(channel.name.toString()).isEqualTo("Videos ready to replace")
        val posted = shadowOf(manager).allNotifications.single()
        assertThat(posted.channelId).isEqualTo(CompressNotifications.CHANNEL_CONSENT)
        assertThat(posted.extras.getString("android.title")).isEqualTo("3 compressed videos ready to replace")
    }

    private companion object {
        /** `GallerySyncWorker.soon`'s unique work. */
        const val GALLERY_SYNC_SOON = "gallery-sync-now"
    }
}
