package dev.hfps.hoard.app

import android.app.Application
import dev.hfps.hoard.app.backup.BackupNotifications
import dev.hfps.hoard.server.backup.BackupHost
import dev.hfps.hoard.server.backup.BackupProgress
import dev.hfps.hoard.server.backup.BackupRules
import dev.hfps.hoard.server.backup.BackupRunResult
import dev.hfps.hoard.server.compress.CompressHost
import dev.hfps.hoard.server.compress.CompressProgress
import dev.hfps.hoard.server.compress.CompressRunResult
import dev.hfps.hoard.server.sync.GallerySyncHost

class HoardApplication : Application(), GallerySyncHost, BackupHost, CompressHost {
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
    override suspend fun runBackup(stopped: () -> Boolean, foreground: suspend (BackupProgress) -> Unit): BackupRunResult {
        // A free-up request left waiting for consent is settled first, so its files are reported `freed`, not `deleted`.
        container.freeUp.settlePending()
        return container.backup.run(stopped, foreground).also { result ->
            // New videos of a folder compressed automatically: the replace follows once the server has them (ADR 0025).
            runCatching { container.phoneCompress.afterBackup(result) }
        }
    }

    override suspend fun backupRules(): BackupRules = container.backup.rules()

    override fun backupForeground(progress: BackupProgress): androidx.work.ForegroundInfo = BackupNotifications.foreground(this, progress)

    /** The phone replace of compressed videos (ADR 0025 A2): only from its worker (and the screen's consent step). */
    override suspend fun runCompress(stopped: () -> Boolean, foreground: suspend (CompressProgress) -> Unit): CompressRunResult =
        container.phoneCompress.run(stopped, foreground)

    override suspend fun compressUnmetered(): Boolean = container.backup.rules().photosUnmetered

    override fun compressForeground(progress: CompressProgress): androidx.work.ForegroundInfo =
        dev.hfps.hoard.app.compress.CompressNotifications.foreground(this, progress)
}
