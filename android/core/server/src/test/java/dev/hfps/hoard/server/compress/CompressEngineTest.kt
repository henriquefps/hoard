package dev.hfps.hoard.server.compress

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.MemoryKeyVault
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressItem
import dev.hfps.hoard.server.backup.AddRootResult
import dev.hfps.hoard.server.backup.BackupEngine
import dev.hfps.hoard.server.backup.BackupEngineTest
import dev.hfps.hoard.server.backup.BackupState
import dev.hfps.hoard.server.backup.FakeBackup
import dev.hfps.hoard.server.db.CompressPhoneEntity
import dev.hfps.hoard.server.db.DeviceMediaEntity
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
 * ADR 0025 A2: the phone replace against a fake hoard-server. A backed-up video is compressed, downloaded by range,
 * written in place (same path and mtime), reported, and the backup's next run uploads and removes nothing; a process
 * killed during the write is redone; consent waits, is refused, is given; a changed file, a cancelled item, a lost
 * answer, a restore, and a broken write repaired from the server.
 */
@RunWith(AndroidJUnit4::class)
class CompressEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var backupFake: FakeBackup
    private lateinit var fake: FakeCompress
    private lateinit var db: GalleryDatabase
    private lateinit var sync: SyncEngine
    private lateinit var phone: File
    private lateinit var source: Source
    private var clock = 1_000_000L

    /** Thrown by [Source.write] half-way through: the process "dies" there. */
    class Killed : RuntimeException("killed")

    /** The phone's primary volume as a directory; MediaStore as a map; consent and writes scripted. */
    inner class Source : CompressSource {
        /** Media management (`MANAGE_MEDIA`): a camera's row is written without asking. */
        var manageMedia = true
        var allow = true
        /** MediaStore's query throws. */
        var mediaUnreadable = false
        /** Bytes free on the storage of a directory (null: unknown). */
        var free: (File) -> Long? = { null }
        /** Writes that fail with an I/O error before the first byte. */
        var failWrites = 0
        var supported = true
        /** Calls of [write] (each one truncates the phone file). */
        var writes = 0
        val asks = ArrayList<Int>()
        var killNextWrite = false
        /** Writes of the output that flip a byte (a broken write). */
        var corruptWrites = 0
        var waitingTold = 0
        val media = HashMap<String, PhoneMedia>()
        override fun supported() = supported
        override fun absolute(volume: String, path: String) = if (volume == "primary") File(phone, path).absolutePath else null
        override fun partFile(row: CompressPhoneEntity) = File(tmp.root, "parts/${row.id}.part")
        override suspend fun media(absolute: String): MediaLookup = when {
            mediaUnreadable -> MediaLookup.Unreadable
            else -> media[absolute]?.let { MediaLookup.Found(it) } ?: MediaLookup.Missing
        }
        override fun usableSpace(dir: File): Long? = free(dir)
        override suspend fun compareMedia(part: File, phone: File): String? = null
        override fun needsConsent(media: PhoneMedia) = media.owner != OWN && !manageMedia
        override suspend fun requestWrite(media: List<PhoneMedia>): Boolean {
            asks += media.size
            return allow
        }
        override suspend fun write(row: CompressPhoneEntity, media: PhoneMedia, part: File, mtime: Long): PhoneWrite {
            writes++
            if (failWrites > 0) {
                failWrites--
                throw java.io.IOException("EIO")
            }
            val target = File(row.absolute)
            val bytes = part.readBytes()
            if (killNextWrite) {
                killNextWrite = false
                target.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
                throw Killed()
            }
            if (corruptWrites > 0 && row.targetFingerprint == outFp) {
                corruptWrites--
                bytes[10] = (bytes[10] + 1).toByte()
            }
            target.writeBytes(bytes)
            target.setLastModified(mtime)
            this.media[row.absolute] = media.copy(size = bytes.size.toLong())
            return PhoneWrite(pending = true)
        }
        override suspend fun setDateTaken(mediaId: Long, dateTaken: Long) = true
        override fun consentWaiting(count: Int) {
            waitingTold = count
        }
    }

    private val original = ByteArray(3_000_000) { ((it * 31 + 7) and 0xff).toByte() }
    private val output = ByteArray(700_000) { ((it * 17 + 3) and 0xff).toByte() }
    private val origFp = Fingerprint.of(original)
    private val outFp = Fingerprint.of(output)
    private val origSha = sha(original)
    private val outSha = sha(output)
    private val path = "DCIM/Camera/VID_1.mp4"
    private val mtime = 1_700_000_000_000L
    private lateinit var video: File

    private companion object {
        const val OWN = "dev.hfps.hoard.debug"
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = FakeServer()
        backupFake = FakeBackup(server)
        fake = FakeCompress(server, backupFake)
        db = GalleryDatabase.open(ApplicationProvider.getApplicationContext(), null)
        sync = SyncEngine(db, MemoryKeyVault(), DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.8.0"), now = { clock })
        runBlocking { sync.pair(PairingCode(server.url, "good", "fake-server")) }
        phone = tmp.newFolder("primary")
        source = Source()
        video = File(phone, path).apply { parentFile!!.mkdirs(); writeBytes(original); setLastModified(mtime) }
        source.media[video.absolutePath] = PhoneMedia(42L, video.absolutePath, original.size.toLong(), mtime, mtime - 5_000, 10_000, favorite = true, owner = "com.camera")
        runBlocking { db.device().put(listOf(DeviceMediaEntity(42L, video.absolutePath, "device", "DCIM/Camera", "VID_1.mp4", "k", "video/mp4", original.size.toLong(), mtime, mtime - 5_000, 10_000, null, null, null, true, origFp, original.size.toLong(), mtime, false))) }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun backup() = BackupEngine(db, { sync.api() }, BackupEngineTest.DirSource(phone).apply { conditions = dev.hfps.hoard.server.backup.Conditions(unmetered = true, charging = true) }, now = { clock }, throttle = null)

    private fun engine() = CompressEngine(db, { sync.api() }, source, now = { clock }, downloaderOf = { CompressDownloader(it, chunk = 65_536, sleep = {}) })

    /** The video backed up, then compressed on the server: item 7 ready for this phone. */
    private fun backedUpAndCompressed(): String = runBlocking {
        val rootId = (backup().addRoot("device", "DCIM/Camera") as AddRootResult.Added).root.id
        assertThat(backup().run().uploaded).isEqualTo(1)
        fake.items["7"] = CompressItem(
            id = "7", jobId = "job", itemId = "it-a", fingerprint = origFp, target = "phone", source = "blob", state = Compress.STATE_READY,
            size = original.size.toLong(), outFingerprint = outFp, outSize = output.size.toLong(), rootId = rootId, volume = "primary", path = path,
            modifiedAt = mtime, sha256 = origSha, outSha256 = outSha,
        )
        fake.outputs["7"] = output
        rootId
    }

    private fun row(id: String = "7") = runBlocking { db.compress().phone(id) }

    @Test
    fun aBackedUpVideoIsReplacedInPlaceAndTheNextBackupUploadsAndRemovesNothing() = runBlocking<Unit> {
        val rootId = backedUpAndCompressed()
        val result = engine().run()
        assertThat(result.outcome).isEqualTo(CompressOutcome.Done)
        assertThat(result.replaced).isEqualTo(1)
        // The same path holds the output, with the original's mtime.
        assertThat(video.readBytes()).isEqualTo(output)
        assertThat(video.lastModified()).isEqualTo(mtime)
        assertThat(row()!!.state).isEqualTo(PhoneState.REPORTED)
        assertThat(File(tmp.root, "parts/7.part").exists()).isFalse()
        val (id, route, body) = fake.reports.single()
        assertThat(id to route).isEqualTo("7" to "replaced")
        assertThat(body.fingerprint).isEqualTo(outFp)
        assertThat(body.sha256).isEqualTo(outSha)
        assertThat(body.size).isEqualTo(output.size.toLong())
        assertThat(body.modifiedAt).isEqualTo(mtime)
        // Hoard's index and backup know the new file: nothing to hash or upload.
        val media = db.device().get(42L)!!
        assertThat(listOf(media.size, media.fingerprint, media.sha256)).containsExactly(output.size.toLong(), outFp, outSha).inOrder()
        val file = db.backup().file(rootId, path)!!
        assertThat(file.state).isEqualTo(BackupState.STORED)
        assertThat(file.fingerprint).isEqualTo(outFp)
        val creates = backupFake.creates.size
        val reports = backupFake.reports.size
        val next = backup().run()
        assertThat(next.uploaded).isEqualTo(0)
        assertThat(backupFake.creates.size).isEqualTo(creates)
        assertThat(backupFake.removed).isEmpty()
        // The row was reported as it is (the replace told the server): the backup sends nothing for it.
        assertThat(backupFake.reports.drop(reports).flatMap { it.entries }).isEmpty()
        assertThat(db.backup().file(rootId, path)!!.state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun aReportLostBeforeTheBackupIsHealedByTheBackupWithoutAnUpload() = runBlocking<Unit> {
        val rootId = backedUpAndCompressed()
        // The replace's report fails (the server unreachable for that call): the file stays written.
        server.extra.let { prev ->
            server.extra = { ex, p, q, b ->
                if (p.endsWith("/replaced")) {
                    server.send(ex, 503, """{"error":{"code":"unavailable","message":"no"}}""")
                    true
                } else {
                    prev?.invoke(ex, p, q, b) == true
                }
            }
        }
        engine().run()
        assertThat(row()!!.state).isEqualTo(PhoneState.WRITTEN)
        // The server learns from the backup's report (its swap answers `stored` for the output's fingerprint).
        backupFake.store[outFp] = outSha
        val next = backup().run()
        assertThat(next.uploaded).isEqualTo(0)
        assertThat(backupFake.removed).isEmpty()
        assertThat(backupFake.reports.last().entries.single().fingerprint).isEqualTo(outFp)
        assertThat(db.backup().file(rootId, path)!!.state).isEqualTo(BackupState.STORED)
    }

    @Test
    fun aProcessKilledDuringTheWriteIsRedoneFromThePart() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.killNextWrite = true
        val killed = runCatching { engine().run() }.exceptionOrNull()
        assertThat(killed).isInstanceOf(Killed::class.java)
        // The journal says `writing`; the phone file is half the output.
        assertThat(row()!!.state).isEqualTo(PhoneState.WRITING)
        assertThat(row()!!.writeStarted).isTrue()
        assertThat(video.length()).isEqualTo(output.size / 2L)
        val reads = fake.outputReads.size
        // The next process redoes the write from the part (never checks the half file against the original).
        val again = engine().run()
        assertThat(again.replaced).isEqualTo(1)
        assertThat(video.readBytes()).isEqualTo(output)
        assertThat(video.lastModified()).isEqualTo(mtime)
        assertThat(fake.outputReads.size).isEqualTo(reads)
        assertThat(row()!!.state).isEqualTo(PhoneState.REPORTED)
    }

    @Test
    fun consentWaitsWithoutAScreenIsAskedInBatchesAndARefusalKeepsTheOriginal() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.manageMedia = false
        val worker = engine().run()
        assertThat(worker.waitingConsent).isEqualTo(1)
        assertThat(source.waitingTold).isEqualTo(1)
        assertThat(source.asks).isEmpty()
        assertThat(row()!!.state).isEqualTo(PhoneState.VERIFIED)
        assertThat(row()!!.error).isEqualTo(PhoneError.PERMISSION)
        assertThat(video.readBytes()).isEqualTo(original)
        // The screen asks: refused, the row keeps waiting and nothing is written.
        source.allow = false
        assertThat(engine().run(interactive = true).waitingConsent).isEqualTo(1)
        assertThat(source.asks).containsExactly(1)
        assertThat(video.readBytes()).isEqualTo(original)
        // Allowed: written at once and reported.
        source.allow = true
        val allowed = engine().run(interactive = true)
        assertThat(allowed.replaced).isEqualTo(1)
        assertThat(allowed.waitingConsent).isEqualTo(0)
        assertThat(video.readBytes()).isEqualTo(output)
        assertThat(fake.reports.single().second).isEqualTo("replaced")
    }

    @Test
    fun keepOriginalsAbandonsAsRefused() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.manageMedia = false
        engine().run()
        engine().keepOriginals(listOf("7"))
        engine().run()
        assertThat(fake.abandons.single()).isEqualTo("7" to "refused")
        assertThat(row()!!.state).isEqualTo(PhoneState.ABANDONED)
        assertThat(video.readBytes()).isEqualTo(original)
    }

    @Test
    fun aFileChangedOnThePhoneIsAbandonedAndNotWritten() = runBlocking<Unit> {
        backedUpAndCompressed()
        val edited = original.copyOf().also { it[0] = 9 }
        video.writeBytes(edited)
        engine().run()
        assertThat(fake.abandons.single()).isEqualTo("7" to "changed")
        assertThat(video.readBytes()).isEqualTo(edited)
        // A file gone from the phone: `gone`.
        fake.items["8"] = fake.items["7"]!!.copy(id = "8", state = Compress.STATE_READY, path = "DCIM/Camera/VID_2.mp4")
        fake.outputs["8"] = output
        engine().run()
        assertThat(fake.abandons.last()).isEqualTo("8" to "gone")
    }

    @Test
    fun anItemCancelledOnTheServerIsDroppedWithoutAWrite() = runBlocking<Unit> {
        backedUpAndCompressed()
        // Listed, then the output is gone before the download (cancelled meanwhile): 404.
        fake.outputs.remove("7")
        engine().run()
        assertThat(row()!!.state).isEqualTo(PhoneState.ABANDONED)
        assertThat(video.readBytes()).isEqualTo(original)
        // Not listed any more: the row goes at the next pass.
        fake.items.remove("7")
        engine().run()
        assertThat(row()).isNotNull() // settled rows stay a while (history)
        assertThat(fake.reports).isEmpty()
    }

    @Test
    fun aRowNotListedAnyMoreBeforeItsWriteIsDropped() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.manageMedia = false
        engine().run()
        assertThat(row()!!.state).isEqualTo(PhoneState.VERIFIED)
        // Cancelled on the server (or settled by a backup report): no longer listed.
        fake.items.remove("7")
        engine().run()
        assertThat(row()).isNull()
        assertThat(File(tmp.root, "parts/7.part").exists()).isFalse()
    }

    @Test
    fun aWriteWhoseAnswerWasLostIsReportedWithoutADownload() = runBlocking<Unit> {
        backedUpAndCompressed()
        // An earlier run wrote the output, its database row was lost (reinstall of the database, say).
        video.writeBytes(output)
        video.setLastModified(mtime)
        engine().run()
        assertThat(fake.outputReads).isEmpty()
        assertThat(fake.reports.single().second).isEqualTo("replaced")
    }

    @Test
    fun aRestoreWritesTheOriginalBackFromTheStore() = runBlocking<Unit> {
        backedUpAndCompressed()
        engine().run()
        assertThat(video.readBytes()).isEqualTo(output)
        // The owner restores from the Trash screen: the server marks it replacing (direction restore).
        fake.items["7"] = fake.items["7"]!!.copy(state = Compress.STATE_REPLACING, direction = Compress.DIRECTION_RESTORE)
        server.originals[origFp] = original
        val result = engine().run()
        assertThat(result.replaced).isEqualTo(1)
        assertThat(video.readBytes()).isEqualTo(original)
        assertThat(video.lastModified()).isEqualTo(mtime)
        assertThat(fake.reports.last().second).isEqualTo("restored")
        assertThat(fake.reports.last().third.fingerprint).isEqualTo(origFp)
        assertThat(db.device().get(42L)!!.fingerprint).isEqualTo(origFp)
        // Restore again: the output written once more.
        fake.items["7"] = fake.items["7"]!!.copy(state = Compress.STATE_REPLACING, direction = Compress.DIRECTION_COMPRESS)
        engine().run()
        assertThat(video.readBytes()).isEqualTo(output)
        assertThat(fake.reports.last().second).isEqualTo("replaced")
    }

    @Test
    fun aBrokenWriteIsWrittenAgainThenAbandonedAndRepairedFromTheServer() = runBlocking<Unit> {
        backedUpAndCompressed()
        server.originals[origFp] = original
        source.corruptWrites = 2
        engine().run()
        assertThat(fake.abandons.single()).isEqualTo("7" to "verify")
        // The original came back from the server's store; nothing was reported as replaced.
        assertThat(video.readBytes()).isEqualTo(original)
        assertThat(fake.reports).isEmpty()
        assertThat(row()!!.state).isEqualTo(PhoneState.REPORTED)
        assertThat(row()!!.report).isEqualTo(PhoneReport.NONE)
    }

    // --- Review of #81 (Amendment (A2)): no write without Android's consent, no truncation, no hot loop -----------

    @Test
    fun aFileWithoutAMediaStoreRowIsNeverWritten() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.media.clear()
        engine().run()
        assertThat(source.writes).isEqualTo(0)
        assertThat(video.readBytes()).isEqualTo(original)
        assertThat(fake.abandons.single()).isEqualTo("7" to "changed")
    }

    @Test
    fun anUnreadableMediaStoreWaitsWithoutAWrite() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.mediaUnreadable = true
        val result = engine().run()
        assertThat(source.writes).isEqualTo(0)
        assertThat(video.readBytes()).isEqualTo(original)
        assertThat(row()!!.state).isEqualTo(PhoneState.VERIFIED)
        assertThat(row()!!.error).isEqualTo(PhoneError.MEDIA)
        assertThat(result.retryAt).isEqualTo(clock + 60_000)
        assertThat(fake.abandons).isEmpty()
        // MediaStore answers again: written after the back-off.
        source.mediaUnreadable = false
        clock += 60_000
        assertThat(engine().run().replaced).isEqualTo(1)
        assertThat(video.readBytes()).isEqualTo(output)
    }

    @Test
    fun aForeignRowWithoutConsentIsNeverWrittenByTheWorker() = runBlocking<Unit> {
        backedUpAndCompressed()
        // A camera's video, no Media management, no screen to ask: the worker only waits.
        source.manageMedia = false
        repeat(2) { engine().run() }
        assertThat(source.writes).isEqualTo(0)
        assertThat(video.readBytes()).isEqualTo(original)
        assertThat(row()!!.error).isEqualTo(PhoneError.PERMISSION)
        // Hoard's own video needs no dialog.
        source.media[video.absolutePath] = source.media[video.absolutePath]!!.copy(owner = OWN)
        assertThat(engine().run().replaced).isEqualTo(1)
        assertThat(source.asks).isEmpty()
    }

    @Test
    fun aStartedWriteWhoseMediaStoreRowIsGoneWaitsInsteadOfWriting() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.killNextWrite = true
        runCatching { engine().run() }
        assertThat(row()!!.state).isEqualTo(PhoneState.WRITING)
        val writes = source.writes
        source.mediaUnreadable = true
        engine().run()
        source.mediaUnreadable = false
        source.media.clear()
        clock += 60_000
        engine().run()
        assertThat(source.writes).isEqualTo(writes)
        assertThat(row()!!.state).isEqualTo(PhoneState.WRITING)
        assertThat(row()!!.error).isEqualTo(PhoneError.MEDIA)
        assertThat(row()!!.nextAttemptAt).isGreaterThan(clock)
    }

    @Test
    fun aRestoreWithoutRoomLeavesTheOutputIntactAndBacksOff() = runBlocking<Unit> {
        backedUpAndCompressed()
        engine().run()
        assertThat(video.readBytes()).isEqualTo(output)
        fake.items["7"] = fake.items["7"]!!.copy(state = Compress.STATE_REPLACING, direction = Compress.DIRECTION_RESTORE)
        server.originals[origFp] = original
        // The part's storage has room (its own check); the phone's volume lacks (original − output) + margin.
        val grow = original.size.toLong() - output.size
        source.free = { dir -> if (dir.absolutePath.startsWith(phone.absolutePath)) grow + CompressPlan.WRITE_MARGIN - 1 else null }
        assertThat(engine().run().outcome).isEqualTo(CompressOutcome.Done)
        assertThat(source.writes).isEqualTo(1) // the compress only
        assertThat(video.readBytes()).isEqualTo(output)
        assertThat(row()!!.state).isEqualTo(PhoneState.VERIFIED)
        assertThat(row()!!.writeStarted).isFalse()
        assertThat(row()!!.error).isEqualTo(PhoneError.SPACE)
        val at = row()!!.nextAttemptAt!!
        assertThat(at).isGreaterThan(clock)
        // Run again before the back-off: nothing touched.
        engine().run()
        assertThat(source.writes).isEqualTo(1)
        assertThat(video.readBytes()).isEqualTo(output)
        // Room again after the back-off: restored.
        clock = at
        source.free = { grow + CompressPlan.WRITE_MARGIN }
        assertThat(engine().run().replaced).isEqualTo(1)
        assertThat(video.readBytes()).isEqualTo(original)
    }

    @Test
    fun aWriteThatKeepsFailingBacksOffInsteadOfLooping() = runBlocking<Unit> {
        backedUpAndCompressed()
        source.failWrites = 100
        val first = engine().run()
        assertThat(source.writes).isEqualTo(1)
        assertThat(row()!!.state).isEqualTo(PhoneState.WRITING)
        assertThat(row()!!.writeStarted).isTrue()
        assertThat(row()!!.error).isEqualTo(PhoneError.WRITE)
        assertThat(first.retryAt).isEqualTo(clock + 60_000)
        // Runs before the back-off write nothing; after it, one write each, the wait doubling.
        engine().run()
        assertThat(source.writes).isEqualTo(1)
        clock += 60_000
        engine().run()
        assertThat(source.writes).isEqualTo(2)
        assertThat(row()!!.nextAttemptAt).isEqualTo(clock + 120_000)
        // All files access revoked: a started write is not even tried; once it is back, the row finishes.
        source.supported = false
        clock += 120_000
        engine().run()
        assertThat(source.writes).isEqualTo(2)
        source.supported = true
        source.failWrites = 0
        assertThat(engine().run().replaced).isEqualTo(1)
        assertThat(video.readBytes()).isEqualTo(output)
    }

    @Test
    fun theBackupSkipsAFileWhoseReplaceIsHalfWritten() = runBlocking<Unit> {
        val rootId = backedUpAndCompressed()
        source.killNextWrite = true
        runCatching { engine().run() }
        assertThat(video.length()).isEqualTo(output.size / 2L)
        val creates = backupFake.creates.size
        val reports = backupFake.reports.size
        // A backup pass during the redo: the half file is neither reported nor uploaded.
        val pass = backup().run()
        assertThat(pass.uploaded).isEqualTo(0)
        assertThat(backupFake.creates.size).isEqualTo(creates)
        assertThat(backupFake.reports.drop(reports).flatMap { it.entries }.map { it.path }).doesNotContain(path)
        // The redo finishes; the backup still sends nothing new.
        assertThat(engine().run().replaced).isEqualTo(1)
        assertThat(backup().run().uploaded).isEqualTo(0)
        assertThat(backupFake.creates.size).isEqualTo(creates)
        assertThat(db.backup().file(rootId, path)!!.fingerprint).isEqualTo(outFp)
    }

    @Test
    fun theServerStillCompressingAsksForALaterRun() = runBlocking<Unit> {
        backedUpAndCompressed()
        fake.items.clear()
        fake.pending = 2
        assertThat(engine().run().serverPending).isTrue()
    }
}
