package dev.hfps.hoard.server.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.MemoryKeyVault
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.db.FreedEntity
import dev.hfps.hoard.server.db.GalleryDatabase
import dev.hfps.hoard.server.sync.DeviceInfo
import dev.hfps.hoard.server.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * ADR 0024 Amendment (2.9.1), against a fake hoard-server 0.14.0: after the admin purges this phone's backup the next
 * pass makes its roots again and uploads every file again (freed history dropped, said once); a full backup volume
 * (`507 storage_full`) is a pass-wide wait, never a failed file nor a hot loop, and Back up now tries again.
 */
@RunWith(AndroidJUnit4::class)
class BackupPurgeTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var fake: FakeBackup
    private lateinit var db: GalleryDatabase
    private var clock = 1_000_000L
    private lateinit var sync: SyncEngine
    private lateinit var phone: File
    private lateinit var source: BackupEngineTest.DirSource

    @Before
    fun setUp() {
        server = FakeServer()
        fake = FakeBackup(server)
        db = GalleryDatabase.open(ApplicationProvider.getApplicationContext(), null)
        sync = SyncEngine(db, MemoryKeyVault(), DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.9.1"), now = { clock })
        runBlocking { sync.pair(PairingCode(server.url, "good", "fake-server")) }
        phone = tmp.newFolder("primary")
        source = BackupEngineTest.DirSource(phone)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun engine() = BackupEngine(db, { sync.api() }, source, now = { clock }, throttle = null)

    private fun photo(path: String, size: Int, seed: Int = path.hashCode()): File =
        File(phone, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() })
        }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun add(folder: String): String = runBlocking { (engine().addRoot("device", folder) as AddRootResult.Added).root.id }

    private fun account() = runBlocking { db.account().get()!! }

    /** Free up deleted [f] (its `freed` row first, then the file), as `FreeUpEngine` does; the next pass reports it. */
    private fun freeUp(rootId: String, f: File) = runBlocking {
        val row = db.backup().filesAt(f.absolutePath).single { it.rootId == rootId }
        db.backup().putFreed(listOf(FreedEntity(f.absolutePath, row.itemId, row.fingerprint!!, row.size, clock)))
        f.delete()
    }

    @Test
    fun afterAPurgeTheNextPassMakesTheRootAgainAndUploadsEverythingAgain() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/PXL_1.jpg", 300_000)
        val b = photo("DCIM/Camera/PXL_2.jpg", 2_000)
        val c = photo("DCIM/Camera/PXL_3.jpg", 5_000)
        val shot = photo("Pictures/Screenshots/S_1.png", 4_000)
        val camera = add("DCIM/Camera")
        val shots = add("Pictures/Screenshots")
        assertThat(engine().run().uploaded).isEqualTo(4)
        freeUp(camera, c)
        freeUp(shots, shot)
        engine().run()
        assertThat(fake.removed).containsAtLeast("DCIM/Camera/PXL_3.jpg" to "freed", "Pictures/Screenshots/S_1.png" to "freed")
        assertThat(db.backup().freed()).hasSize(2)
        assertThat(account().backupRestartedAt).isNull()

        // The admin purges the camera folder's backup (the server forgets its root and its bytes).
        fake.roots.remove(camera)
        fake.store.clear()
        fake.blobs.clear()
        val uploadsBefore = fake.creates.size
        clock += 60_000
        val result = engine().run()

        assertThat(result.outcome).isEqualTo(BackupOutcome.Done)
        assertThat(result.restarted).isEqualTo(1)
        assertThat(result.uploaded).isEqualTo(2)
        assertThat(fake.creates.size - uploadsBefore).isEqualTo(2)
        assertThat(fake.blobs.keys).containsExactly(sha(a), sha(b))
        // A new root for the folder; its rows backed up again.
        val roots = db.backup().roots()
        assertThat(roots.map { it.path }).containsExactly("DCIM/Camera", "Pictures/Screenshots")
        val newCamera = roots.single { it.path == "DCIM/Camera" }.id
        assertThat(newCamera).isNotEqualTo(camera)
        assertThat(fake.roots.keys).contains(newCamera)
        assertThat(db.backup().files(newCamera).map { it.state }.toSet()).containsExactly(BackupState.STORED)
        assertThat(db.backup().files(camera)).isEmpty()
        // The camera's freed history went (the server has no copy any more); the screenshots' stays.
        assertThat(db.backup().freed().map { it.path }).containsExactly(shot.absolutePath)
        // Said once on Settings › Phone backup.
        assertThat(account().backupRestartedAt).isEqualTo(clock)

        // The next pass finds nothing gone: nothing starts over, nothing is uploaded.
        db.account().setBackupRestartedAt(null)
        val again = engine().run()
        assertThat(again.restarted).isEqualTo(0)
        assertThat(again.uploaded).isEqualTo(0)
        assertThat(account().backupRestartedAt).isNull()
    }

    @Test
    fun aWholePurgeStartsEveryFolderOverAndDropsEveryFreedTotal() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 3_000)
        val gone = photo("DCIM/Camera/PXL_2.jpg", 4_000)
        val camera = add("DCIM/Camera")
        engine().run()
        freeUp(camera, gone)
        engine().run()
        assertThat(db.backup().freed()).hasSize(1)
        fake.purge()
        val result = engine().run()
        assertThat(result.restarted).isEqualTo(1)
        assertThat(result.uploaded).isEqualTo(1)
        assertThat(db.backup().freed()).isEmpty()
        // The freed file is not reported again (it is neither on the phone nor in the new root).
        assertThat(fake.reports.last().removed).isEmpty()
    }

    @Test
    fun theRootsListIsReadAtTheStartOfEachPassAndAnUnreadableListChangesNothing() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 3_000)
        val camera = add("DCIM/Camera")
        engine().run()
        val reads = fake.rootReads
        engine().run()
        assertThat(fake.rootReads - reads).isAtLeast(1)
        // A stopped root is listed (stopped ones included): not gone, nothing reset.
        fake.roots[camera]!!.stopped = true
        val result = engine().run()
        assertThat(result.restarted).isEqualTo(0)
        assertThat(db.backup().files(camera).single().state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun aRootTheServerStillHasUnderTheSameIdIsNotStartedOver() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 3_000)
        val camera = add("DCIM/Camera")
        engine().run()
        // Pure: a local root missing from the list, made again with the same id (the list was read mid-change).
        assertThat(BackupRestart.gone(db.backup().roots(), emptySet()).map { it.id }).containsExactly(camera)
        assertThat(BackupRestart.gone(db.backup().roots(), setOf(camera))).isEmpty()
        assertThat(BackupRestart.gone(db.backup().roots().map { it.copy(paused = true) }, emptySet())).isEmpty()
    }

    // --- 507 storage_full --------------------------------------------------------------------------------------------

    @Test
    fun aFullBackupVolumeIsAWaitNotAFailure() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 3_000)
        photo("DCIM/Camera/PXL_2.jpg", 4_000)
        photo("DCIM/Camera/PXL_3.jpg", 5_000)
        val camera = add("DCIM/Camera")
        fake.full = 1_234_567
        val first = engine().run()
        assertThat(first.outcome).isEqualTo(BackupOutcome.ServerFull)
        assertThat(first.uploaded).isEqualTo(0)
        // Stopped at the first refusal: one request, no file failed, no attempt counted.
        assertThat(fake.fulls).isEqualTo(1)
        val rows = db.backup().files(camera)
        assertThat(rows.map { it.state }.toSet()).containsExactly(BackupState.QUEUED)
        assertThat(rows.map { it.attempts }.toSet()).containsExactly(0)
        assertThat(rows.all { it.error == null }).isTrue()
        val wait = BackupWait.decode(account().backupWait)!!
        assertThat(wait.free).isEqualTo(1_234_567)
        assertThat(wait.needed).isNotNull()
        assertThat(wait.repeats).isEqualTo(1)
        // At least the server's Retry-After (3600 s), more than the first 30 minutes.
        assertThat(wait.until - clock).isEqualTo(3_600_000L)
        assertThat(first.retryAt).isEqualTo(wait.until)

        // The periodic runs before the wait is due: no upload tried (no hot loop).
        clock += 10 * 60_000
        val waiting = engine().run()
        assertThat(waiting.outcome).isEqualTo(BackupOutcome.ServerFull)
        assertThat(fake.fulls).isEqualTo(1)

        // Back up now tries at once; still full: the wait grows.
        val manual = engine().run(manual = true)
        assertThat(manual.outcome).isEqualTo(BackupOutcome.ServerFull)
        assertThat(fake.fulls).isEqualTo(2)
        val longer = BackupWait.decode(account().backupWait)!!
        assertThat(longer.repeats).isEqualTo(2)
        assertThat(longer.since).isEqualTo(wait.since)
        assertThat(longer.until - clock).isEqualTo(3_600_000L)

        // Space freed on the server and the wait due: everything goes, the wait is cleared.
        fake.full = null
        clock = longer.until
        val resumed = engine().run()
        assertThat(resumed.outcome).isEqualTo(BackupOutcome.Done)
        assertThat(resumed.uploaded).isEqualTo(3)
        assertThat(account().backupWait).isNull()
        assertThat(db.backup().files(camera).map { it.state }.toSet()).containsExactly(BackupState.STORED)
    }

    @Test
    fun aDiskThatFillsDuringAnUploadKeepsItsOffsetAndResumes() = runBlocking<Unit> {
        server.chunkSize = 64L * 1024
        val big = photo("DCIM/Camera/PXL_BIG.jpg", 400_000)
        val camera = add("DCIM/Camera")
        fake.full = 100
        fake.fullOnPatchOnly = true
        val result = engine().run()
        assertThat(result.outcome).isEqualTo(BackupOutcome.ServerFull)
        // One refused PATCH, not retried by the uploader.
        assertThat(fake.patches).isEqualTo(1)
        val row = db.backup().files(camera).single()
        assertThat(row.state).isEqualTo(BackupState.QUEUED)
        assertThat(row.uploadId).isNotNull()
        assertThat(row.attempts).isEqualTo(0)
        fake.full = null
        val resumed = engine().run(manual = true)
        assertThat(resumed.uploaded).isEqualTo(1)
        assertThat(fake.heads).contains(row.uploadId)
        assertThat(fake.blobs[sha(big)]).isEqualTo(big.readBytes())
        assertThat(account().backupWait).isNull()
    }

    @Test
    fun theWaitGrowsWithRepeatsUpToSixHours() {
        assertThat(BackupWait.backoffMs(1, null)).isEqualTo(30 * 60_000L)
        assertThat(BackupWait.backoffMs(2, null)).isEqualTo(60 * 60_000L)
        assertThat(BackupWait.backoffMs(3, null)).isEqualTo(120 * 60_000L)
        assertThat(BackupWait.backoffMs(9, null)).isEqualTo(6 * 3_600_000L)
        assertThat(BackupWait.backoffMs(1, 3600)).isEqualTo(3_600_000L)
        assertThat(BackupWait.backoffMs(1, 999_999)).isEqualTo(6 * 3_600_000L)
        val w = BackupWait(1, 2, 3, 4, 5)
        assertThat(BackupWait.decode(w.encode())).isEqualTo(w)
        assertThat(BackupWait.decode("not json")).isNull()
    }

    @Test
    fun aResetRowKeepsItsIdentityAndDropsTheServersAnswers() {
        val row = dev.hfps.hoard.server.db.BackupFileEntity(
            "r", "DCIM/Camera/a.jpg", "/p/DCIM/Camera/a.jpg", 1, "image", 10, 20, 30,
            fingerprint = dev.hfps.hoard.server.Fingerprint.of(ByteArray(10)), fpSize = 10, fpModified = 20, sha256 = "s", shaSize = 10, shaModified = 20,
            state = BackupState.VERIFIED, reported = "x", serverState = "stored", itemId = "i", verifiedAt = 5, uploadId = "u", uploadOffset = 3, attempts = 2,
        )
        val reset = BackupRestart.reset(row, 99)
        assertThat(reset.state).isEqualTo(BackupState.QUEUED)
        assertThat(reset.fingerprint).isEqualTo(row.fingerprint)
        assertThat(reset.sha256).isEqualTo("s")
        assertThat(listOf(reset.reported, reset.serverState, reset.itemId, reset.verifiedAt, reset.uploadId)).containsExactly(null, null, null, null, null)
        assertThat(reset.uploadOffset).isEqualTo(0)
        assertThat(BackupPlan.toReport(listOf(reset))).hasSize(1)
        assertThat(BackupRestart.reset(row.copy(fingerprint = null), 99).state).isEqualTo(BackupState.WAITING)
        assertThat(BackupRestart.folderFromRows("DCIM/Camera", listOf(row))).isEqualTo("/p/DCIM/Camera")
        val freed = listOf(FreedEntity("/p/DCIM/Camera/a.jpg", null, "f", 1, 1), FreedEntity("/p/DCIM/CameraX/b.jpg", null, "f", 1, 1))
        assertThat(BackupRestart.freedUnder(freed, "/p/DCIM/Camera").map { it.path }).containsExactly("/p/DCIM/Camera/a.jpg")
    }
}
