package dev.hfps.hoard.app

import android.app.Application
import dev.hfps.hoard.server.sync.GallerySyncHost

class HoardApplication : Application(), GallerySyncHost {
    val container: AppContainer by lazy { AppContainer(this) }

    /** The Gallery's background sync (ADR 0021): WorkManager runs it in this process. */
    override suspend fun syncGallery(): Boolean = container.gallery.syncNow()

    override suspend fun prefetchThumbs(stopped: () -> Boolean): Boolean = container.gallery.prefetchThumbs(stopped)
}
