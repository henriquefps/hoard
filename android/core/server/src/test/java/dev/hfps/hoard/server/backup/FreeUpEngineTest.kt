package dev.hfps.hoard.server.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.MemoryKeyVault
import dev.hfps.hoard.server.PairingCode
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

/**
 * Free up space against a fake hoard-server 0.12.0 (ADR 0024 "Free up space", phase A3): the files are backed up by
 * [BackupEngine] first, then previewed, checked (re-stat, hash, `POST /v1/backup/verify` with `pending`, `no`,
 * `refused`), gated by Reveal, freed (partially too) and reported `freed`; a lost report is made by the next backup run.
 */
@RunWith(AndroidJUnit4::class)
class FreeUpEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var fake: FakeBackup
    private lateinit var db: GalleryDatabase
    private val vault = MemoryKeyVault()
    private var clock = 400 * FreeUpPlan.DAY_MS
    private lateinit var sync: SyncEngine
    private lateinit var phone: File
    private lateinit var source: BackupEngineTest.DirSource
    private val sleeps = ArrayList<Long>()

    private val freeSource = object : FreeUpSource {
        var favourites = emptySet<Long>()
        var pending = emptySet<String>()
        var pushed = 0
        override suspend fun favourites(mediaIds: Collection<Long>) = favourites
        override suspend fun pushCulling() {
            pushed++
        }
        override suspend fun pendingCulling(keys: Set<String>) = pending intersect keys
        override fun conditions() = Conditions(unmetered = true, charging = false)
    }

    @Before
    fun setUp() {
        server = FakeServer()
        fake = FakeBackup(server)
        db = GalleryDatabase.open(ApplicationProvider.getApplicationContext(), null)
        sync = SyncEngine(db, vault, DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.6.0"), now = { clock })
        runBlocking { sync.pair(PairingCode(server.url, "good", "fake-server")) }
        phone = tmp.newFolder("primary")
        source = BackupEngineTest.DirSource(phone)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun backup() = BackupEngine(db, { sync.api() }, source, now = { clock }, throttle = null)

    private fun engine(maxWaitMs: Long = 600_000) = FreeUpEngine(db, { sync.api() }, freeSource, now = { clock }, throttle = null, maxWaitMs = maxWaitMs, sleep = { ms ->
        sleeps += ms
        clock += ms
    })

    /** A synthetic photo, 100 days old (its time is its capture time in [BackupEngineTest.DirSource]). */
    private fun photo(path: String, size: Int, seed: Int = path.hashCode(), daysAgo: Int = 100): File {
        val f = File(phone, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() })
        f.setLastModified(clock - daysAgo * FreeUpPlan.DAY_MS)
        return f
    }

    private fun backedUp(vararg files: File): String = runBlocking {
        val rootId = (backup().addRoot("device", "DCIM/Camera") as AddRootResult.Added).root.id
        assertThat(backup().run().outcome).isEqualTo(BackupOutcome.Done)
        assertThat(db.backup().files(rootId).map { it.state }.toSet()).containsAnyOf(BackupState.STORED, BackupState.NAS)
        // DirSource has no MediaStore: stills and videos get a media id as the index would give them (sidecars and
        // RAWs stay files found by listing).
        val rows = db.backup().allFiles()
        db.backup().putFiles(rows.mapIndexed { i, r -> if (r.kind == BackupKind.IMAGE || r.kind == BackupKind.VIDEO) r.copy(mediaId = 100L + i) else r })
        rootId
    }

    /** What the system's dialog does: deletes the media files of [shots] whose name is not in [deny]. */
    private fun consent(shots: List<FreeShot>, deny: Set<String> = emptySet()): Set<String> {
        val gone = HashSet<String>()
        for (s in shots) for (f in s.media) {
            if (f.path.substringAfterLast('/') in deny) continue
            File(f.absolute).delete()
            gone += FreeUpPlan.fileKey(f)
        }
        return gone
    }

    @Test
    fun freesVerifiedShotsAndReportsThemFreed() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/PXL_1.jpg", 300_000)
        val b = photo("DCIM/Camera/PXL_2.jpg", 2_000)
        // Taken last week: kept by the 30-day rule.
        photo("DCIM/Camera/PXL_3.jpg", 2_000, daysAgo = 7)
        val rootId = backedUp(a, b)
        val preview = engine().preview(FreeUpRules())
        assertThat(preview.block).isNull()
        assertThat(freeSource.pushed).isEqualTo(1)
        assertThat(preview.gate).isEqualTo(RevealGate.Off)
        assertThat(preview.candidates.shots.map { it.stem }).containsExactly("PXL_1", "PXL_2").inOrder()
        assertThat(preview.candidates.excluded).containsExactly(FreeUpExclusion.Recent, 1)
        val check = engine().check(preview.candidates.shots)
        assertThat(check.go).hasSize(2)
        assertThat(check.kept).isEmpty()
        assertThat(engine().fresh(check.go, check.answers)).isTrue()
        // The system deletes both; the report says freed and the rows leave the backup.
        val gone = consent(check.go)
        val done = engine().freed(FreeUpPlan.settle(check.go, gone)) { it.absoluteFile().delete() }
        assertThat(done.files).isEqualTo(2)
        assertThat(done.bytes).isEqualTo(302_000)
        assertThat(done.reported).isTrue()
        assertThat(fake.removed).containsExactly("DCIM/Camera/PXL_1.jpg" to "freed", "DCIM/Camera/PXL_2.jpg" to "freed")
        assertThat(db.backup().files(rootId).map { it.path }).containsExactly("DCIM/Camera/PXL_3.jpg")
        assertThat(db.backup().freed().map { it.path to it.bytes }).containsExactly(a.absolutePath to 300_000L, b.absolutePath to 2_000L)
        // Ten minutes later the answers are no longer fresh.
        clock += 11 * 60_000
        assertThat(engine().fresh(check.go, check.answers)).isFalse()
        // The next backup run reports nothing more for them.
        val before = fake.removed.size
        backup().run()
        assertThat(fake.removed.size).isEqualTo(before)
    }

    private fun dev.hfps.hoard.server.db.BackupFileEntity.absoluteFile() = File(absolute)

    @Test
    fun pendingWaitsThenGoesAndNoOrRefusedStays() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/A.jpg", 3_000)
        val b = photo("DCIM/Camera/B.jpg", 3_000)
        val c = photo("DCIM/Camera/C.jpg", 3_000)
        backedUp(a, b, c)
        fake.pendingRounds["DCIM/Camera/A.jpg"] = 2
        fake.damaged += "DCIM/Camera/B.jpg"
        fake.refusedVerify += "DCIM/Camera/C.jpg"
        val shots = engine().preview(FreeUpRules()).candidates.shots
        val check = engine().check(shots)
        assertThat(check.go.map { it.stem }).containsExactly("A")
        assertThat(check.kept.map { it.first.stem to it.second }).containsExactly("B" to "differs", "C" to "refused:invalid_path")
        // A was asked three times (pending twice, at the server's retryAfter), B and C once.
        assertThat(fake.verifies.flatten().count { it.endsWith("A.jpg") }).isEqualTo(3)
        assertThat(fake.verifies.flatten().count { it.endsWith("B.jpg") }).isEqualTo(1)
        assertThat(sleeps).isNotEmpty()
    }

    @Test
    fun pendingForTooLongStays() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/A.jpg", 3_000)
        backedUp(a)
        fake.pendingRounds["DCIM/Camera/A.jpg"] = 1000
        val check = engine(maxWaitMs = 5_000).check(engine().preview(FreeUpRules()).candidates.shots)
        assertThat(check.go).isEmpty()
        assertThat(check.kept.single().second).isEqualTo("pending")
    }

    @Test
    fun theRevealGateKeepsNamedShotsAndAStaleRevealKeepsEverything() = runBlocking<Unit> {
        val edited = photo("DCIM/Camera/E.jpg", 3_000)
        val plain = photo("DCIM/Camera/P.jpg", 3_000)
        backedUp(edited, plain)
        fake.named += Fingerprint.of(edited)
        // A live Reveal on this phone without freed-adopt: the named shot stays, the other goes.
        fake.devices += """{"id":"rv","name":"Pixel 8","app":"reveal","platform":"android","lastSeenAt":${clock - 60_000},"features":["culling"]}"""
        val preview = engine().preview(FreeUpRules())
        assertThat(preview.gate).isInstanceOf(RevealGate.On::class.java)
        val check = engine().check(preview.candidates.shots)
        assertThat(check.go.map { it.stem }).containsExactly("P")
        assertThat(check.kept.map { it.first.stem to it.second }).containsExactly("E" to FreeUpPlan.REASON_REVEAL)
        assertThat(check.keptForReveal).isEqualTo(1)
        // Reveal last seen 40 minutes ago: nothing goes, nothing is even asked.
        fake.devices.clear()
        fake.devices += """{"id":"rv","app":"reveal","platform":"android","lastSeenAt":${clock - 40 * 60_000},"features":[]}"""
        val asked = fake.verifies.size
        val stale = engine().check(preview.candidates.shots)
        assertThat(stale.gate).isInstanceOf(RevealGate.Stale::class.java)
        assertThat(stale.go).isEmpty()
        assertThat(stale.keptForReveal).isEqualTo(2)
        assertThat(fake.verifies.size).isEqualTo(asked)
        // Reveal declared freed-adopt: both go.
        fake.devices.clear()
        fake.devices += """{"id":"rv","app":"reveal","platform":"android","lastSeenAt":${clock - 40 * 60_000},"features":["culling","freed-adopt"]}"""
        assertThat(engine().check(preview.candidates.shots).go.map { it.stem }).containsExactly("E", "P")
    }

    @Test
    fun aFileChangedSinceItsBackupStaysAndALargeNasFileIsHashedFirst() = runBlocking<Unit> {
        val changed = photo("DCIM/Camera/C.jpg", 3_000)
        val big = photo("DCIM/Camera/N.jpg", 3_000_000)
        fake.nas += Fingerprint.of(big)
        val rootId = backedUp(changed, big)
        // A NAS reference of more than 2 MiB was never hashed (no upload).
        assertThat(db.backup().file(rootId, "DCIM/Camera/N.jpg")!!.sha256).isNull()
        val shots = engine().preview(FreeUpRules()).candidates.shots
        // Rewritten in place after the backup, same size.
        changed.writeBytes(ByteArray(3_000) { 7 })
        changed.setLastModified(clock - 1000)
        val check = engine().check(shots)
        assertThat(check.kept.map { it.first.stem to it.second }).containsExactly("C" to FreeUpPlan.REASON_CHANGED)
        assertThat(check.go.map { it.stem }).containsExactly("N")
        assertThat(db.backup().file(rootId, "DCIM/Camera/N.jpg")!!.sha256).isNotNull()
    }

    @Test
    fun aPartialConsentFreesOnlyWhatTheSystemDeletedAndSidecarsWaitForTheirShot() = runBlocking<Unit> {
        source.allFiles = true
        source.rules = BackupRules(videosCharging = false)
        val a = photo("DCIM/Camera/A.jpg", 3_000)
        val aMov = photo("DCIM/Camera/A.mp4", 4_000)
        val aXmp = photo("DCIM/Camera/A.xmp", 200)
        val b = photo("DCIM/Camera/B.jpg", 3_000)
        val bXmp = photo("DCIM/Camera/B.xmp", 200)
        val c = photo("DCIM/Camera/C.jpg", 3_000)
        backedUp(a, aMov, aXmp, b, bXmp, c)
        val check = engine().check(engine().preview(FreeUpRules()).candidates.shots)
        assertThat(check.go.map { it.stem }).containsExactly("A", "B", "C")
        // The owner's consent deleted A's still, all of B, nothing of C.
        val gone = consent(check.go, deny = setOf("A.mp4", "C.jpg"))
        val settlement = FreeUpPlan.settle(check.go, gone)
        val done = engine().freed(settlement) { File(it.absolute).delete() }
        assertThat(done.files).isEqualTo(3)
        assertThat(fake.removed.map { it.first }).containsExactly("DCIM/Camera/A.jpg", "DCIM/Camera/B.jpg", "DCIM/Camera/B.xmp")
        assertThat(aXmp.exists()).isTrue()
        assertThat(aMov.exists()).isTrue()
        assertThat(bXmp.exists()).isFalse()
        assertThat(c.exists()).isTrue()
        assertThat(db.backup().freed().map { File(it.path).name }).containsExactly("A.jpg", "B.jpg", "B.xmp")
    }

    @Test
    fun aLostReportIsMadeAsFreedByTheNextBackupRun() = runBlocking<Unit> {
        val a = photo("DCIM/Camera/A.jpg", 3_000)
        val b = photo("DCIM/Camera/B.jpg", 3_000)
        val rootId = backedUp(a, b)
        val check = engine().check(engine().preview(FreeUpRules()).candidates.shots.filter { it.stem == "A" })
        val gone = consent(check.go)
        // The server is gone when the report is sent.
        fake.roots[rootId]!!.stopped = true
        val done = engine().freed(FreeUpPlan.settle(check.go, gone)) { false }
        assertThat(done.reported).isFalse()
        assertThat(db.backup().file(rootId, "DCIM/Camera/A.jpg")).isNotNull()
        // The next run: the root is resumed, and A is reported freed (not deleted).
        fake.roots[rootId]!!.stopped = false
        backup().run()
        assertThat(fake.removed).containsExactly("DCIM/Camera/A.jpg" to "freed")
        assertThat(db.backup().file(rootId, "DCIM/Camera/A.jpg")).isNull()
    }

    @Test
    fun withoutTheBackupFeatureFreeUpIsNotOffered() = runBlocking<Unit> {
        server.features = server.features - "backup"
        sync.sync()
        assertThat(engine().preview(FreeUpRules()).block).isEqualTo(FreeUpBlock.Unsupported)
    }
}
