package dev.hfps.hoard.app

import android.app.Application
import dev.hfps.hoard.app.backup.BackupNotifications
import dev.hfps.hoard.server.backup.BackupHost
import dev.hfps.hoard.server.backup.BackupProgress
import dev.hfps.hoard.server.backup.BackupRules
import dev.hfps.hoard.server.backup.BackupRunResult
import dev.hfps.hoard.server.sync.GallerySyncHost

class HoardApplication : Application(), GallerySyncHost, BackupHost {
    val container: AppContainer by lazy { AppContainer(this) }

    init {
        // The transfer engine reads the photo server's originals ("Save to this device", ADR 0024 A2) through the app's
        // client, also when WorkManager starts the process for a transfer.
        dev.hfps.hoard.smb.transfer.TransferEndpoints.server = { endpoint -> container.serverSaves.open(endpoint) }
    }

    /** The Gallery's background sync (ADR 0021): WorkManager runs it in this process. */
    override suspend fun syncGallery(): Boolean = container.gallery.syncNow()

    override suspend fun prefetchThumbs(stopped: () -> Boolean): Boolean = container.gallery.prefetchThumbs(stopped)

    /** The phone backup's runs (ADR 0024): only from its worker. */
    override suspend fun runBackup(stopped: () -> Boolean, foreground: suspend (BackupProgress) -> Unit): BackupRunResult =
        container.backup.run(stopped, foreground)

    override suspend fun backupRules(): BackupRules = container.backup.rules()

    override fun backupForeground(progress: BackupProgress): androidx.work.ForegroundInfo = BackupNotifications.foreground(this, progress)
}
