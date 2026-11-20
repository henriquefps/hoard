package dev.hfps.hoard.server.compress

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressItem
import dev.hfps.hoard.server.db.CompressPhoneEntity
import org.junit.Test

/**
 * ADR 0025 A2: the phone replace's journal, pure. Every state a process can be killed in resumes at the right step in
 * both directions (compress and restore); server items become rows; the check, a failed download and a failed
 * read-back; the repair after a broken write.
 */
class CompressPlanTest {
    private val fpA = "v1:" + "a".repeat(64)
    private val fpB = "v1:" + "b".repeat(64)
    private val shaA = "1".repeat(64)
    private val shaB = "2".repeat(64)
    private val now = 1_000_000L

    private fun item(state: String = Compress.STATE_READY, direction: String? = null, id: String = "7") = CompressItem(
        id = id,
        jobId = "job",
        itemId = "it-a",
        fingerprint = fpA,
        target = "phone",
        source = "blob",
        state = state,
        size = 1000,
        outFingerprint = fpB,
        outSize = 300,
        rootId = "root",
        volume = "primary",
        path = "DCIM/Camera/VID_1.mp4",
        modifiedAt = 5_000,
        sha256 = shaA,
        outSha256 = shaB,
        direction = direction,
    )

    private val absolute: (String, String) -> String? = { v, p -> if (v == "primary") "/storage/emulated/0/$p" else null }

    private fun row(direction: String = Compress.DIRECTION_COMPRESS, state: String = PhoneState.DOWNLOADING) =
        CompressPlan.rowOf(item(if (direction == Compress.DIRECTION_COMPRESS) Compress.STATE_READY else Compress.STATE_REPLACING, direction), now, absolute)!!.copy(state = state)

    @Test
    fun aReadyItemIsACompressRowFromOriginalToOutput() {
        val r = CompressPlan.rowOf(item(), now, absolute)!!
        assertThat(r.direction).isEqualTo(Compress.DIRECTION_COMPRESS)
        assertThat(r.absolute).isEqualTo("/storage/emulated/0/DCIM/Camera/VID_1.mp4")
        assertThat(listOf(r.sourceFingerprint, r.sourceSha256, r.sourceSize)).containsExactly(fpA, shaA, 1000L).inOrder()
        assertThat(listOf(r.targetFingerprint, r.targetSha256, r.targetSize)).containsExactly(fpB, shaB, 300L).inOrder()
        assertThat(r.report).isEqualTo(PhoneReport.REPLACED)
        assertThat(r.originalMtime).isEqualTo(5_000)
        assertThat(CompressPlan.fromOutputRoute(r)).isTrue()
    }

    @Test
    fun aReplacingItemIsARestoreRowFromOutputToOriginal() {
        val r = CompressPlan.rowOf(item(Compress.STATE_REPLACING), now, absolute)!!
        assertThat(r.direction).isEqualTo(Compress.DIRECTION_RESTORE)
        assertThat(r.sourceFingerprint).isEqualTo(fpB)
        assertThat(r.targetFingerprint).isEqualTo(fpA)
        assertThat(r.targetSha256).isEqualTo(shaA)
        assertThat(r.report).isEqualTo(PhoneReport.RESTORED)
        // The original comes from the store, not the output route.
        assertThat(CompressPlan.fromOutputRoute(r)).isFalse()
        // A restore again (replacing, direction compress) writes the output once more.
        val again = CompressPlan.rowOf(item(Compress.STATE_REPLACING, Compress.DIRECTION_COMPRESS), now, absolute)!!
        assertThat(again.targetFingerprint).isEqualTo(fpB)
        assertThat(again.report).isEqualTo(PhoneReport.REPLACED)
    }

    @Test
    fun itemsWithoutAPhoneFileOrHashesAreNotRows() {
        assertThat(CompressPlan.rowOf(item().copy(volume = "1234-ABCD"), now, absolute)).isNull()
        assertThat(CompressPlan.rowOf(item().copy(path = null), now, absolute)).isNull()
        assertThat(CompressPlan.rowOf(item().copy(outSha256 = null), now, absolute)).isNull()
        assertThat(CompressPlan.rowOf(item(Compress.STATE_REPLACING).copy(sha256 = null), now, absolute)).isNull()
        assertThat(CompressPlan.rowOf(item(Compress.STATE_REPLACED), now, absolute)).isNull()
    }

    @Test
    fun everyJournalStateResumesWhereItWasKilledInBothDirections() {
        for (direction in listOf(Compress.DIRECTION_COMPRESS, Compress.DIRECTION_RESTORE)) {
            val expected = mapOf(
                PhoneState.DOWNLOADING to PhoneStep.Download, // resumed at the saved offset
                PhoneState.VERIFYING to PhoneStep.Verify, // the whole part checked again
                PhoneState.VERIFIED to PhoneStep.Check, // nothing written: checked again
                PhoneState.WRITING to PhoneStep.Write, // redone from the part, never rolled back
                PhoneState.WRITTEN to PhoneStep.Report,
                PhoneState.REPORTED to PhoneStep.Done,
                PhoneState.ABANDON to PhoneStep.Abandon,
                PhoneState.ABANDONED to PhoneStep.Done,
            )
            for ((state, step) in expected) {
                assertThat(CompressPlan.resume(row(direction, state), now)).isEqualTo(step)
            }
        }
        // A back-off holds a download, a report and an abandon until it is due.
        assertThat(CompressPlan.resume(row().copy(nextAttemptAt = now + 10), now)).isEqualTo(PhoneStep.Wait(now + 10))
        assertThat(CompressPlan.resume(row(state = PhoneState.WRITTEN).copy(nextAttemptAt = now + 10), now)).isEqualTo(PhoneStep.Wait(now + 10))
        assertThat(CompressPlan.resume(row(state = PhoneState.ABANDON).copy(nextAttemptAt = now - 1), now)).isEqualTo(PhoneStep.Abandon)
        // A local repair settles at its report step (no route is called for it).
        assertThat(CompressPlan.resume(row(state = PhoneState.WRITTEN).copy(report = PhoneReport.NONE), now)).isEqualTo(PhoneStep.Report)
    }

    @Test
    fun reconcileAddsNewItemsKeepsUnsettledRowsAndStartsANewCycleOverSettledOnes() {
        val rows = listOf(row(state = PhoneState.WRITING).copy(writeStarted = true))
        // The same item and direction, mid-write: kept as it is.
        assertThat(CompressPlan.reconcile(listOf(item()), rows, true, now, absolute).put).isEmpty()
        // A new item: a row.
        val fresh = CompressPlan.reconcile(listOf(item(id = "8")), emptyList(), true, now, absolute)
        assertThat(fresh.put.map { it.id }).containsExactly("8")
        // A settled compress row and the server asks for a restore: a new cycle.
        val settled = listOf(row(state = PhoneState.REPORTED))
        val restore = CompressPlan.reconcile(listOf(item(Compress.STATE_REPLACING)), settled, true, now, absolute)
        assertThat(restore.put.single().direction).isEqualTo(Compress.DIRECTION_RESTORE)
        // An unsettled row of the other direction that wrote nothing is replaced; one that started writing is finished first.
        val before = listOf(row(state = PhoneState.VERIFIED))
        val swap = CompressPlan.reconcile(listOf(item(Compress.STATE_REPLACING)), before, true, now, absolute)
        assertThat(swap.drop.map { it.id }).containsExactly("7")
        assertThat(swap.put.single().direction).isEqualTo(Compress.DIRECTION_RESTORE)
        val writing = listOf(row(state = PhoneState.WRITING).copy(writeStarted = true))
        assertThat(CompressPlan.reconcile(listOf(item(Compress.STATE_REPLACING)), writing, true, now, absolute).put).isEmpty()
        // An abandoned row the server still lists is not made again (the server settles it).
        assertThat(CompressPlan.reconcile(listOf(item()), listOf(row(state = PhoneState.ABANDONED)), true, now, absolute).put).isEmpty()
    }

    @Test
    fun rowsTheServerNoLongerListsAreDroppedOnlyBeforeTheWriteAndOnlyFromACompleteList() {
        val rows = listOf(
            row(state = PhoneState.DOWNLOADING).copy(id = "1"),
            row(state = PhoneState.VERIFIED).copy(id = "2"),
            row(state = PhoneState.WRITING).copy(id = "3", writeStarted = true),
            row(state = PhoneState.WRITTEN).copy(id = "4", writeStarted = true),
            row(state = PhoneState.REPORTED).copy(id = "5", updatedAt = now),
            row(state = PhoneState.REPORTED).copy(id = "6", updatedAt = now - CompressPlan.KEEP_SETTLED_MS - 1),
            row(state = PhoneState.DOWNLOADING).copy(id = "9", writeStarted = true),
        )
        assertThat(CompressPlan.reconcile(emptyList(), rows, complete = true, now, absolute).drop.map { it.id }).containsExactly("1", "2", "6")
        assertThat(CompressPlan.reconcile(emptyList(), rows, complete = false, now, absolute).drop).isEmpty()
    }

    @Test
    fun theCheckWantsTheSourceUnchangedAndKnowsAWriteDoneBefore() {
        val r = row(state = PhoneState.VERIFIED)
        val path = r.absolute
        fun now(size: Long = 1000, fp: String? = fpA, mtime: Long = 5_000, media: String? = path, exists: Boolean = true) = PhoneFileNow(exists, size, mtime, fp, media)
        assertThat(CompressPlan.check(r, now())).isEqualTo(PhoneCheck.Ok)
        // FAT keeps even seconds: within 2 s is the same time.
        assertThat(CompressPlan.check(r, now(mtime = 6_999))).isEqualTo(PhoneCheck.Ok)
        assertThat(CompressPlan.check(r, now(mtime = 7_500))).isEqualTo(PhoneCheck.Abandon(PhoneError.CHANGED))
        assertThat(CompressPlan.check(r, now(size = 999))).isEqualTo(PhoneCheck.Abandon(PhoneError.CHANGED))
        assertThat(CompressPlan.check(r, now(fp = fpB))).isEqualTo(PhoneCheck.Abandon(PhoneError.CHANGED))
        assertThat(CompressPlan.check(r, now(media = "/storage/emulated/0/DCIM/other.mp4"))).isEqualTo(PhoneCheck.Abandon(PhoneError.CHANGED))
        assertThat(CompressPlan.check(r, now(exists = false))).isEqualTo(PhoneCheck.Abandon(PhoneError.GONE))
        // The target is there already (an answer lost after a write): read back and reported, not written again.
        assertThat(CompressPlan.check(r, now(size = 300, fp = fpB))).isEqualTo(PhoneCheck.AlreadyTarget)
        // A restore checks the output at the path.
        val back = row(Compress.DIRECTION_RESTORE, PhoneState.VERIFIED)
        assertThat(CompressPlan.check(back, now(size = 300, fp = fpB))).isEqualTo(PhoneCheck.Ok)
        assertThat(CompressPlan.check(back, now())).isEqualTo(PhoneCheck.AlreadyTarget)
    }

    @Test
    fun aBadDownloadIsFetchedOnceMoreThenAbandoned() {
        val first = CompressPlan.verifyFailed(row(state = PhoneState.VERIFYING).copy(offset = 300), now)
        assertThat(first.state).isEqualTo(PhoneState.DOWNLOADING)
        assertThat(first.offset).isEqualTo(0)
        val second = CompressPlan.verifyFailed(first.copy(state = PhoneState.VERIFYING), now)
        assertThat(second.state).isEqualTo(PhoneState.ABANDON)
        assertThat(second.abandonReason).isEqualTo(PhoneError.VERIFY)
        // Once the write started the phone file is neither version: never abandoned, downloaded again later.
        val started = CompressPlan.verifyFailed(first.copy(state = PhoneState.VERIFYING, writeStarted = true), now)
        assertThat(started.state).isEqualTo(PhoneState.DOWNLOADING)
        assertThat(started.nextAttemptAt).isGreaterThan(now)
    }

    @Test
    fun aBadReadBackIsWrittenOnceMoreThenAbandonedAndRepaired() {
        val w = row(state = PhoneState.WRITING).copy(writeStarted = true)
        val once = CompressPlan.readBackFailed(w, now)
        assertThat(once.state).isEqualTo(PhoneState.WRITING)
        assertThat(once.rewrites).isEqualTo(1)
        val twice = CompressPlan.readBackFailed(once, now)
        assertThat(twice.state).isEqualTo(PhoneState.ABANDON)
        // After the abandon is told: the original is downloaded from the server and written back, nothing reported.
        val repair = CompressPlan.afterAbandon(twice, now)
        assertThat(repair.state).isEqualTo(PhoneState.DOWNLOADING)
        assertThat(repair.report).isEqualTo(PhoneReport.NONE)
        assertThat(repair.targetFingerprint).isEqualTo(fpA)
        assertThat(repair.targetSha256).isEqualTo(shaA)
        assertThat(CompressPlan.fromOutputRoute(repair)).isFalse()
        // An abandon before any write simply settles.
        assertThat(CompressPlan.afterAbandon(CompressPlan.abandon(row(state = PhoneState.VERIFIED), PhoneError.CHANGED, now), now).state).isEqualTo(PhoneState.ABANDONED)
    }

    @Test
    fun consentGoesInBatchesOfFiveHundred() {
        val rows = (1..1201).map { row().copy(id = "$it") }
        assertThat(CompressPlan.consentBatches(rows).map { it.size }).containsExactly(500, 500, 201).inOrder()
    }

    @Test
    fun theOverviewSplitsWaitingActiveAndRecent() {
        val waiting = row(state = PhoneState.VERIFIED).copy(id = "1", error = PhoneError.PERMISSION)
        val active = row(state = PhoneState.DOWNLOADING).copy(id = "2")
        val done = row(state = PhoneState.REPORTED).copy(id = "3", updatedAt = 2)
        val gaveUp = row(state = PhoneState.ABANDONED).copy(id = "4", updatedAt = 3)
        val o = PhoneOverview.of(listOf<CompressPhoneEntity>(waiting, active, done, gaveUp))
        assertThat(o.waiting.map { it.id }).containsExactly("1")
        assertThat(o.active.map { it.id }).containsExactly("2")
        assertThat(o.recent.map { it.id }).containsExactly("4", "3").inOrder()
        assertThat(o.saved).isEqualTo(700)
    }
}
