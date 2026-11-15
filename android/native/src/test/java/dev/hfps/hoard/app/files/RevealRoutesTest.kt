package dev.hfps.hoard.app.files

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.data.AppSettings
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.RevealCopyEntity
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.server.paths.FileMatch
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.transfer.FileStat
import dev.hfps.hoard.smb.transfer.ItemKinds
import dev.hfps.hoard.smb.transfer.TransferItem
import dev.hfps.hoard.smb.transfer.TransferStates
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Hoard 2.2.0's hand-off routes (ADR 0010 amendment "2.2.0: local copies"), XMP on demand (ADR 0011 amendment) and the
 * upgrade step that turns automatic XMP off.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RevealRoutesTest {
    private fun stat(name: String, size: Long, mtime: Long?) = FileStat(name, false, size, mtime)
    private fun row(path: String, size: Long, mtime: Long?, uri: String) = RevealCopyEntity(uri = uri, sourceKey = "m1/photos", sourcePath = path, size = size, modifiedMillis = mtime, copiedAt = "2026-11-15T00:00:00Z")

    @Test fun aPhoneShotGoesByItsMediaStoreUriWithoutACopy() {
        val plan = RevealRoutes.plan(
            listOf("DCIM/Camera/PXL_1.jpg"),
            onPhone = mapOf("DCIM/Camera/PXL_1.jpg" to "content://media/external_primary/images/media/5"),
            stats = emptyMap(), copies = emptyMap(),
        ) { _, _ -> error("not asked") }
        assertThat(plan.uris).containsExactly("DCIM/Camera/PXL_1.jpg", "content://media/external_primary/images/media/5")
        assertThat(plan.copy).isEmpty()
    }

    @Test fun aCopyIsReusedOnlyWhilePathSizeAndMtimeMatchAndMediaStoreStillHasIt() {
        val paths = listOf("s/a.ARW", "s/b.ARW", "s/c.ARW", "s/d.ARW", "s/e.ARW", "s/f.ARW")
        val stats = mapOf(
            "s/a.ARW" to stat("a.ARW", 100, 5), // same: reused
            "s/b.ARW" to stat("b.ARW", 101, 5), // size changed
            "s/c.ARW" to stat("c.ARW", 100, 6), // mtime changed
            "s/d.ARW" to stat("d.ARW", 100, 5), // copy deleted from MediaStore
            "s/e.ARW" to stat("e.ARW", 100, 5), // never copied
            "s/f.ARW" to null, // unreadable now: copied (and fails with the source's error)
        )
        val copies = mapOf(
            "s/a.ARW" to listOf(row("s/a.ARW", 100, 5, "content://media/1")),
            "s/b.ARW" to listOf(row("s/b.ARW", 100, 5, "content://media/2")),
            "s/c.ARW" to listOf(row("s/c.ARW", 100, 5, "content://media/3")),
            "s/d.ARW" to listOf(row("s/d.ARW", 100, 5, "content://media/4")),
            "s/f.ARW" to listOf(row("s/f.ARW", 100, 5, "content://media/6")),
        )
        val plan = RevealRoutes.plan(paths, emptyMap(), stats, copies) { uri, size -> uri != "content://media/4" && size == 100L }
        assertThat(plan.uris).containsExactly("s/a.ARW", "content://media/1")
        assertThat(plan.copy).containsExactly("s/b.ARW", "s/c.ARW", "s/d.ARW", "s/e.ARW", "s/f.ARW").inOrder()
        // A copy recorded without an mtime is never trusted.
        assertThat(RevealRoutes.reusable(row("x", 1, null, "u"), stat("x", 1, null))).isFalse()
    }

    @Test fun aSourceCopiedAgainAfterItChangedKeepsBothCopiesAndReusesTheMatchingOne() {
        val copies = mapOf("s/a.ARW" to listOf(row("s/a.ARW", 200, 9, "content://media/new"), row("s/a.ARW", 100, 5, "content://media/old")))
        val now = RevealRoutes.plan(listOf("s/a.ARW"), emptyMap(), mapOf("s/a.ARW" to stat("a.ARW", 200, 9)), copies) { _, _ -> true }
        assertThat(now.uris).containsExactly("s/a.ARW", "content://media/new")
        // Back to the old content (an undo on the share): the older copy still stands for it.
        val back = RevealRoutes.plan(listOf("s/a.ARW"), emptyMap(), mapOf("s/a.ARW" to stat("a.ARW", 100, 5)), copies) { _, _ -> true }
        assertThat(back.uris).containsExactly("s/a.ARW", "content://media/old")
    }

    @Test fun aJobFailingMidwayLeavesItsPublishedCopiesRecordedAndARetryReusesThem() {
        // 300 photos; the share drops at item 250: 249 published, 1 failed, 50 never started.
        val paths = (1..300).map { "s/DSC%04d.ARW".format(it) }
        val items = paths.mapIndexed { i, p ->
            val state = when {
                i < 249 -> TransferStates.COMPLETED
                i == 249 -> TransferStates.FAILED
                else -> TransferStates.QUEUED
            }
            TransferItem(i, 0, p, "Trip/${p.substringAfterLast('/')}", ItemKinds.FILE, 1000L + i, 7, state = state).also { if (i == 249) it.errorCode = ErrorCode.UNREACHABLE }
        }
        val asked = paths.associateBy { it }
        val recorded = RevealRoutes.records(items, asked, "m1/photos", "2026-11-15T00:00:00Z") { folder, name -> "content://media/$folder/$name" }
        assertThat(recorded).hasSize(249)
        assertThat(RevealRoutes.failure(TransferStates.FAILED, items, 300)!!.code).isEqualTo(ErrorCode.UNREACHABLE)
        // The retry: every published copy is reused, only the 51 others are copied; nothing is copied twice.
        val stats = paths.mapIndexed { i, p -> p to stat(p.substringAfterLast('/'), 1000L + i, 7) }.toMap()
        val retry = RevealRoutes.plan(paths, emptyMap(), stats, recorded.groupBy { it.sourcePath }) { _, _ -> true }
        assertThat(retry.uris).hasSize(249)
        assertThat(retry.copy).isEqualTo(paths.drop(249))
        assertThat(retry.copy.intersect(retry.uris.keys)).isEmpty()
        // A copy whose row MediaStore cannot find is not recorded (nothing to point to).
        assertThat(RevealRoutes.records(items, asked, "k", "now") { _, _ -> null }).isEmpty()
    }

    @Test fun deleteCopiesTouchesOnlyRecordedCopiesStillInHoardsFolders() {
        val trip = row("s/a.ARW", 100, 5, "content://media/1")
        val superseded = row("s/a.ARW", 90, 4, "content://media/2")
        val moved = row("s/b.ARW", 100, 5, "content://media/3")
        val wrongCase = row("s/c.ARW", 100, 5, "content://media/4")
        val gone = row("s/d.ARW", 100, 5, "content://media/5")
        val reused = row("s/e.ARW", 100, 5, "content://media/6")
        val cr3 = row("s/f.CR3", 50, 5, "content://media/downloads/7")
        val current = mapOf(
            trip.uri to RevealRoutes.Current("Pictures/Hoard/Trip/", 100),
            superseded.uri to RevealRoutes.Current("Pictures/Hoard/Trip/", 90),
            moved.uri to RevealRoutes.Current("Pictures/Holidays/", 100), // the user moved it out
            wrongCase.uri to RevealRoutes.Current("pictures/hoard/Trip/", 100), // not Hoard's folder (exact case)
            reused.uri to RevealRoutes.Current("Pictures/Hoard/Trip/", 4242), // an id MediaStore gave another file
            cr3.uri to RevealRoutes.Current("Download/Hoard/Trip/", 50),
        )
        val plan = RevealRoutes.deletion(listOf(trip, superseded, moved, wrongCase, gone, reused, cr3)) { current[it] }
        assertThat(plan.delete).containsExactly(trip, superseded, cr3)
        assertThat(plan.forget).containsExactly(moved, wrongCase, gone, reused)
        // A file Hoard owns under Pictures/Hoard but never recorded (copied there with the file manager) is not even
        // asked about: the input is reveal_copies only.
        val asked = ArrayList<String>()
        RevealRoutes.deletion(listOf(trip)) { asked += it; current[it] }
        assertThat(asked).containsExactly(trip.uri)
        // Hoard's folder itself counts; a folder that only starts with the same letters does not.
        assertThat(RevealRoutes.isCopy(trip, RevealRoutes.Current("Pictures/Hoard/", 100))).isTrue()
        assertThat(RevealRoutes.isCopy(trip, RevealRoutes.Current("Pictures/Hoarding/", 100))).isFalse()
    }

    @Test fun mixedHandOffServerReferencesThenMediaStoreThenCopies() {
        // The SMB plan first takes the shots the server holds; the rest go by URI, copied when needed.
        val ordered = listOf("s/a.ARW", "s/a.JPG", "s/b.ARW", "s/c.CR3")
        val smb = SmbReveal.plan(ordered, { p -> if (p == "s/a.ARW") FileMatch.Mapped("item-a", "v1:aa", null) else FileMatch.Unknown }, acceptsReferences = true, paired = true, lookupsAllowed = false)
        assertThat(smb.server.map { it.itemId }).containsExactly("item-a")
        assertThat(smb.uris).containsExactly("s/b.ARW", "s/c.CR3").inOrder()
        val plan = RevealRoutes.plan(smb.uris, emptyMap(), mapOf("s/b.ARW" to stat("b.ARW", 1, 1), "s/c.CR3" to stat("c.CR3", 1, 1)), emptyMap()) { _, _ -> true }
        assertThat(plan.copy).containsExactly("s/b.ARW", "s/c.CR3").inOrder()
        // An older Reveal (before 0.9.0) gets every photo by URI: all copied.
        val old = SmbReveal.plan(ordered, { FileMatch.Mapped("x", null, null) }, acceptsReferences = false, paired = true, lookupsAllowed = false)
        assertThat(old.server).isEmpty()
        assertThat(old.uris).isEqualTo(ordered)
    }

    @Test fun theBatchFolderIsTheTitleOrTheDay() {
        assertThat(RevealRoutes.folder("Trip: day 1", LocalDate.of(2026, 11, 15))).isEqualTo("Trip day 1")
        assertThat(RevealRoutes.folder(null, LocalDate.of(2026, 11, 15))).isEqualTo("2026-11-15")
    }

    @Test fun aCancelledOrIncompleteCopyFailsTheHandOff() {
        fun item(i: Int, state: String, code: ErrorCode? = null) = TransferItem(i, 0, "s/$i.ARW", "2026-11-15/$i.ARW", ItemKinds.FILE, 10, 1, state = state).also { it.errorCode = code }
        assertThat(RevealRoutes.failure(TransferStates.CANCELLED, listOf(item(0, TransferStates.COMPLETED)), 1)!!.code).isEqualTo(ErrorCode.CANCELLED)
        val failed = RevealRoutes.failure(TransferStates.FAILED, listOf(item(0, TransferStates.COMPLETED), item(1, TransferStates.FAILED, ErrorCode.UNREACHABLE)), 2)
        assertThat(failed!!.code).isEqualTo(ErrorCode.UNREACHABLE)
        assertThat(RevealRoutes.failure(TransferStates.COMPLETED, listOf(item(0, TransferStates.COMPLETED), item(1, TransferStates.COMPLETED)), 2)).isNull()
        assertThat(RevealRoutes.copied(listOf(item(0, TransferStates.COMPLETED), item(1, TransferStates.SKIPPED))).map { it.sourcePath }).containsExactly("s/0.ARW")
    }

    @Test fun exportTakesDecidedShotsOnlyWithEveryFileOfTheShot() {
        val decisions = listOf(
            CullDecisionEntity("m1", "photos", "s", "a", "pick", 4, "2026-11-15T00:00:00Z"),
            CullDecisionEntity("m1", "photos", "s", "b", null, 0, "2026-11-15T00:00:00Z"),
            CullDecisionEntity("m1", "photos", "s", "c", "reject", 0, "2026-11-15T00:00:00Z"),
        )
        val shots = XmpExporter.shotsToExport(listOf("s/a.JPG", "s/a.ARW", "s/b.ARW", "s/c.CR3", "s/notes.txt", "s/d.ARW"), decisions)
        assertThat(shots.map { it.stem }).containsExactly("a", "c").inOrder()
        assertThat(shots[0].fileNames).containsExactly("a.ARW", "a.JPG").inOrder()
        assertThat(shots[0].fields).isEqualTo(XmpFields(4, XmpFlag.PICK))
        assertThat(shots[1].fields).isEqualTo(XmpFields(0, XmpFlag.REJECT))
        assertThat(XmpExporter.shotsToExport(listOf("s/notes.txt"), decisions)).isEmpty()
    }

    @Test fun theUpgradeTurnsAutomaticXmpOffAndKeepsItOff() = runBlocking {
        val db = HoardDatabase.open(ApplicationProvider.getApplicationContext(), name = null)
        try {
            val store = SettingsStore(db.settings())
            val settings = AppSettings(store)
            // As Hoard 1 or 2.1 left it (or Hoard 1's import brought it in).
            store.putJson(AppSettings.CULL_SAVE_XMP, "true")
            assertThat(settings.forceNoAutomaticXmp()).isTrue()
            assertThat(store.getJson(AppSettings.CULL_SAVE_XMP)).isEqualTo("false")
            assertThat(settings.cull.first().saveXmp).isFalse()
            assertThat(settings.files.first().cullSaveXmp).isFalse()
            assertThat(settings.forceNoAutomaticXmp()).isFalse()
            // A fresh install has nothing stored: off too.
            db.settings().put(dev.hfps.hoard.data.SettingEntity(AppSettings.CULL_SAVE_XMP, "false"))
            assertThat(settings.forceNoAutomaticXmp()).isFalse()
        } finally {
            db.close()
        }
    }
}
