package dev.hfps.hoard.server.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.MemoryKeyVault
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.db.BackupRootEntity
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
 * The phone backup against a fake hoard-server 0.12.0 (ADR 0024, phase A1): roots, reports, NAS references, chunked
 * uploads with `wait`/`claim`, a kill mid-upload resumed from the stored upload id, `in_progress`, `checksum_mismatch`,
 * `401`, `409 covered` / `stopped`, per-entry refusals and an older server's `422`, removals and moves, the rules.
 */
@RunWith(AndroidJUnit4::class)
class BackupEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var fake: FakeBackup
    private lateinit var db: GalleryDatabase
    private val vault = MemoryKeyVault()
    private var clock = 1_000_000L
    private lateinit var sync: SyncEngine
    private lateinit var phone: File
    private lateinit var source: DirSource

    /** The phone's primary volume as a directory: MediaStore's stills and videos, RAWs and sidecars by listing. */
    class DirSource(private val base: File) : BackupSource {
        var allFiles = false
        var complete = true
        var conditions = Conditions(unmetered = true, charging = false)
        var rules = BackupRules()
        override fun types() = BackupKind.typesFor(allFiles)
        override suspend fun list(root: BackupRootEntity, types: Set<String>): Listing {
            val dir = File(base, root.path)
            val files = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.mapNotNull { f ->
                val ext = BackupExtensions.of(f.name)
                val kind = when {
                    ext in BackupExtensions.VIDEO -> BackupPlan.kindOf(f.name, mediaStore = true, video = true)
                    ext in BackupExtensions.STILL -> BackupPlan.kindOf(f.name, mediaStore = true, video = false)
                    else -> BackupPlan.kindOf(f.name, mediaStore = false, video = false)
                } ?: return@mapNotNull null
                if (kind !in types) return@mapNotNull null
                PhoneFile(f.relativeTo(base).path, f.absolutePath, null, kind, f.length(), f.lastModified(), f.lastModified())
            }.toList()
            return Listing(files, complete)
        }
        override fun conditions() = conditions
        override suspend fun rules() = rules
    }

    @Before
    fun setUp() {
        server = FakeServer()
        fake = FakeBackup(server)
        db = GalleryDatabase.open(ApplicationProvider.getApplicationContext(), null)
        sync = SyncEngine(db, vault, DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.4.0"), now = { clock })
        runBlocking { sync.pair(PairingCode(server.url, "good", "fake-server")) }
        phone = tmp.newFolder("primary")
        source = DirSource(phone)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun engine() = BackupEngine(db, { sync.api() }, source, now = { clock }, throttle = null)

    private fun photo(path: String, size: Int, seed: Int = path.hashCode()): File {
        val f = File(phone, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() })
        return f
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun addCamera(e: BackupEngine = engine()): String = runBlocking {
        (e.addRoot("device", "DCIM/Camera") as AddRootResult.Added).root.id
    }

    private fun rows(rootId: String) = runBlocking { db.backup().files(rootId).associateBy { it.path } }

    @Test
    fun backsUpNewPhotosWithoutClaimingThemAndReportsThemStored() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/PXL_1.jpg", 300_000)
        val b = photo("DCIM/Camera/PXL_2.jpg", 1_000)
        val rootId = addCamera()
        assertThat(fake.roots[rootId]!!.types).containsExactly("image", "video")
        val result = engine().run()
        assertThat(result.outcome).isEqualTo(BackupOutcome.Done)
        assertThat(result.uploaded).isEqualTo(2)
        assertThat(fake.creates.map { it.wait to it.claim }.toSet()).containsExactly(true to false)
        assertThat(fake.blobs[sha(a)]).isEqualTo(a.readBytes())
        assertThat(fake.blobs[sha(b)]).isEqualTo(b.readBytes())
        val r = rows(rootId)
        assertThat(r.values.map { it.state }.toSet()).containsExactly(BackupState.STORED)
        assertThat(r["DCIM/Camera/PXL_1.jpg"]!!.itemId).isNotNull()
        // The small file's SHA-256 came with its fingerprint's read and was reported with it.
        assertThat(fake.reports.first().entries.first { it.path.endsWith("PXL_2.jpg") }.sha256).isEqualTo(sha(b))
        // Nothing changed: the next run reports and uploads nothing.
        val reports = fake.reports.size
        assertThat(engine().run().uploaded).isEqualTo(0)
        assertThat(fake.reports.size).isEqualTo(reports)
        assertThat(db.backup().root(rootId)!!.lastPassAt).isEqualTo(clock)
    }

    @Test
    fun aNasDuplicateIsReferencedNotUploaded() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/PXL_1.jpg", 5_000)
        fake.nas += Fingerprint.of(a)
        val rootId = addCamera()
        assertThat(engine().run().uploaded).isEqualTo(0)
        assertThat(fake.creates).isEmpty()
        assertThat(rows(rootId).values.single().state).isEqualTo(BackupState.NAS)
    }

    @Test
    fun aKilledUploadResumesFromTheStoredUploadId() = runBlocking<Unit> {
        server.chunkSize = 64L shl 10
        val big = photo("DCIM/Camera/VID_1.jpg", 300_000)
        val rootId = addCamera()
        // The process dies after two chunks (the worker's stop check: the offset was saved after each).
        val stopped = engine().run(stopped = { fake.patches >= 2 })
        assertThat(stopped.outcome).isEqualTo(BackupOutcome.Stopped)
        val row = rows(rootId).values.single()
        assertThat(row.uploadId).isNotNull()
        assertThat(row.uploadOffset).isEqualTo(128L shl 10)
        assertThat(row.state).isEqualTo(BackupState.QUEUED)
        // A new process: HEAD, then the remaining chunks only.
        val result = engine().run()
        assertThat(result.uploaded).isEqualTo(1)
        assertThat(fake.heads).containsExactly(row.uploadId)
        assertThat(fake.patches).isEqualTo(5)
        assertThat(fake.blobs[sha(big)]).isEqualTo(big.readBytes())
        assertThat(rows(rootId).values.single().state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun anotherDevicesUploadInProgressIsWaitedFor() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/PXL_1.jpg", 4_000_000)
        fake.busy += sha(a)
        val rootId = addCamera()
        assertThat(engine().run().uploaded).isEqualTo(0)
        var row = rows(rootId).values.single()
        assertThat(row.state).isEqualTo(BackupState.QUEUED)
        assertThat(row.nextAttemptAt).isEqualTo(clock + 300_000)
        assertThat(fake.patches).isEqualTo(0)
        // Not asked again before retryAfter.
        engine().run()
        assertThat(fake.creates).hasSize(1)
        clock += 301_000
        fake.busy.clear()
        assertThat(engine().run().uploaded).isEqualTo(1)
        row = rows(rootId).values.single()
        assertThat(row.state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun aChecksumMismatchMarksTheFileChangedAndItIsHashedAgain() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 3_000_000)
        val rootId = addCamera()
        fake.corruptNext = true
        engine().run()
        val row = rows(rootId).values.single()
        assertThat(row.state).isEqualTo(BackupState.CHANGED)
        assertThat(row.sha256).isNull()
        assertThat(row.uploadId).isNull()
        assertThat(engine().run().uploaded).isEqualTo(1)
        assertThat(rows(rootId).values.single().state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun aRevokedKeyStopsTheRun() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 1_000)
        addCamera()
        vault.set("rvk1_revoked_secret")
        assertThat(engine().run().outcome).isEqualTo(BackupOutcome.Unauthorized)
    }

    @Test
    fun aFolderInsideAnotherRootIsCoveredAndAParentAbsorbsItsChildren() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 1_000)
        val camera = addCamera()
        val covered = engine().addRoot("device", "DCIM/Camera/Sub")
        assertThat(covered).isEqualTo(AddRootResult.Covered(camera))
        engine().run()
        val dcim = (engine().addRoot("device", "/DCIM/") as AddRootResult.Added).root
        assertThat(dcim.path).isEqualTo("DCIM")
        // The child's rows moved to the parent with their state.
        assertThat(db.backup().roots().map { it.id }).containsExactly(dcim.id)
        assertThat(rows(dcim.id).values.single().state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun aStoppedRootIsResumedAndTheStorageMustBeConfigured() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 1_000)
        val rootId = addCamera()
        fake.roots[rootId]!!.stopped = true
        assertThat(engine().run().uploaded).isEqualTo(1)
        assertThat(fake.roots[rootId]!!.stopped).isFalse()
        fake.storageConfigured = false
        assertThat(engine().addRoot("device", "Pictures")).isEqualTo(AddRootResult.NotConfigured)
    }

    @Test
    fun aRefusedEntryNeverBlocksItsBatch() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 1_000)
        photo("DCIM/Camera/shot.png", 1_000)
        fake.refuseExtensions = setOf("png")
        val rootId = addCamera()
        engine().run()
        val r = rows(rootId)
        assertThat(r["DCIM/Camera/PXL_1.jpg"]!!.state).isEqualTo(BackupState.STORED)
        assertThat(r["DCIM/Camera/shot.png"]!!.state).isEqualTo(BackupState.REFUSED)
        assertThat(r["DCIM/Camera/shot.png"]!!.error).isEqualTo("unsupported_type")
        // Not reported again until the file changes.
        val reports = fake.reports.size
        engine().run()
        assertThat(fake.reports.size).isEqualTo(reports)
    }

    @Test
    fun anOlderServers422RefusesOnlyTheBadEntry() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 1_000)
        photo("DCIM/Camera/PXL_2.jpg", 1_000, seed = 7)
        photo("DCIM/Camera/shot.png", 1_000)
        fake.refuseExtensions = setOf("png")
        fake.legacy422 = true
        val rootId = addCamera()
        assertThat(engine().run().uploaded).isEqualTo(2)
        val r = rows(rootId)
        assertThat(r["DCIM/Camera/shot.png"]!!.state).isEqualTo(BackupState.REFUSED)
        assertThat(r.filterKeys { it.endsWith(".jpg") }.values.map { it.state }.toSet()).containsExactly(BackupState.STORED)
    }

    @Test
    fun deletionsAndRenamesAreReportedOnlyAfterACompleteListing() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/PXL_1.jpg", 2_000)
        val b = photo("DCIM/Camera/PXL_2.jpg", 2_000, seed = 9)
        val rootId = addCamera()
        engine().run()
        val uploads = fake.creates.size
        a.delete()
        assertThat(b.renameTo(File(phone, "DCIM/Camera/renamed.jpg"))).isTrue()
        // An incomplete listing (the volume went away) reports nothing as gone.
        source.complete = false
        engine().run()
        assertThat(fake.removed).isEmpty()
        source.complete = true
        engine().run()
        assertThat(fake.removed).containsExactly("DCIM/Camera/PXL_1.jpg" to "deleted", "DCIM/Camera/PXL_2.jpg" to "moved")
        // The renamed file is stored already: no upload.
        assertThat(fake.creates.size).isEqualTo(uploads)
        assertThat(rows(rootId).keys).containsExactly("DCIM/Camera/renamed.jpg")
        assertThat(rows(rootId).values.single().state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun theRulesPauseFilesAndVideosWaitForTheCharger() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 1_000)
        photo("DCIM/Camera/VID_1.mp4", 1_000, seed = 3)
        val rootId = addCamera()
        source.conditions = Conditions(unmetered = false, charging = false)
        engine().run()
        assertThat(rows(rootId).values.map { it.state to it.error }.toSet()).containsExactly(BackupState.PAUSED to BackupState.REASON_WIFI)
        source.conditions = Conditions(unmetered = true, charging = false)
        val result = engine().run()
        assertThat(result.waitingForCharging).isTrue()
        val r = rows(rootId)
        assertThat(r["DCIM/Camera/PXL_1.jpg"]!!.state).isEqualTo(BackupState.STORED)
        assertThat(r["DCIM/Camera/VID_1.mp4"]!!.state to r["DCIM/Camera/VID_1.mp4"]!!.error).isEqualTo(BackupState.PAUSED to BackupState.REASON_CHARGING)
        source.conditions = Conditions(unmetered = true, charging = true)
        assertThat(engine().run().uploaded).isEqualTo(1)
        // "Only on Wi-Fi" off: a metered network is fine for photos.
        photo("DCIM/Camera/PXL_3.jpg", 1_000, seed = 11)
        source.rules = BackupRules(photosUnmetered = false)
        source.conditions = Conditions(unmetered = false, charging = false)
        assertThat(engine().run().uploaded).isEqualTo(1)
    }

    @Test
    fun allFilesAccessAddsRawsAndSidecarsAndPatchesTheTypes() = runBlocking<Unit> {
        photo("DCIM/Camera/IMG_1.CR3", 2_000)
        photo("DCIM/Camera/IMG_1.xmp", 200, seed = 5)
        photo("DCIM/Camera/IMG_1.jpg", 1_000, seed = 6)
        val rootId = addCamera()
        engine().run()
        assertThat(rows(rootId).keys).containsExactly("DCIM/Camera/IMG_1.jpg")
        source.allFiles = true
        engine().run()
        assertThat(fake.roots[rootId]!!.types).containsExactly("image", "video", "raw", "sidecar")
        val r = rows(rootId)
        assertThat(r.keys).containsExactly("DCIM/Camera/IMG_1.jpg", "DCIM/Camera/IMG_1.CR3", "DCIM/Camera/IMG_1.xmp")
        assertThat(r["DCIM/Camera/IMG_1.CR3"]!!.kind).isEqualTo(BackupKind.RAW)
        assertThat(r.values.map { it.state }.toSet()).containsExactly(BackupState.STORED)
    }

    @Test
    fun turningAFolderOffStopsItOnTheServerAndForgetsItHere() = runBlocking<Unit> {
        photo("DCIM/Camera/PXL_1.jpg", 1_000)
        val rootId = addCamera()
        engine().run()
        assertThat(engine().removeRoot(rootId)).isTrue()
        assertThat(fake.roots[rootId]!!.stopped).isTrue()
        assertThat(db.backup().roots()).isEmpty()
        assertThat(db.backup().files(rootId)).isEmpty()
    }
}
