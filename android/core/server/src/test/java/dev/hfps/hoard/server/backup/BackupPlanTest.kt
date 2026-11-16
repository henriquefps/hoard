package dev.hfps.hoard.server.backup

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.BackupRemoval
import dev.hfps.hoard.server.api.BackupReportResult
import dev.hfps.hoard.server.db.BackupFileEntity
import org.junit.Test

/** The phone backup's pure decisions (ADR 0024 "Modules and data": `BackupPlan`). */
class BackupPlanTest {
    private val fpA = "v1:" + "a".repeat(64)
    private val fpB = "v1:" + "b".repeat(64)

    private fun file(path: String, size: Long = 100, modified: Long = 10, fp: String? = null, sha: String? = null, kind: String = BackupKind.IMAGE, taken: Long = modified) =
        PhoneFile(path, "/storage/emulated/0/$path", 1, kind, size, modified, taken, fp, sha)

    private fun row(path: String, fp: String? = fpA, state: String = BackupState.QUEUED, size: Long = 100, modified: Long = 10, taken: Long = 10) =
        BackupFileEntity("r", path, "/storage/emulated/0/$path", 1, BackupKind.IMAGE, size, modified, taken, fp, size.takeIf { fp != null }, modified.takeIf { fp != null }, state = state)

    @Test
    fun kindsFollowTheServersExtensionsAndWhereTheFileWasFound() {
        assertThat(BackupPlan.kindOf("PXL_1.jpg", mediaStore = true, video = false)).isEqualTo(BackupKind.IMAGE)
        assertThat(BackupPlan.kindOf("Screenshot_1.png", mediaStore = true, video = false)).isEqualTo(BackupKind.IMAGE)
        assertThat(BackupPlan.kindOf("IMG_1.dng", mediaStore = true, video = false)).isEqualTo(BackupKind.IMAGE)
        assertThat(BackupPlan.kindOf("anim.webp", mediaStore = true, video = false)).isEqualTo(BackupKind.IMAGE)
        assertThat(BackupPlan.kindOf("VID_1.mp4", mediaStore = true, video = true)).isEqualTo(BackupKind.VIDEO)
        assertThat(BackupPlan.kindOf("IMG_1.CR3", mediaStore = false, video = false)).isEqualTo(BackupKind.RAW)
        assertThat(BackupPlan.kindOf("IMG_1.xmp", mediaStore = false, video = false)).isEqualTo(BackupKind.SIDECAR)
        // Formats outside photos and videos are never reported.
        assertThat(BackupPlan.kindOf("odd.avif2", mediaStore = true, video = false)).isNull()
        assertThat(BackupPlan.kindOf("notes.txt", mediaStore = false, video = false)).isNull()
        assertThat(BackupKind.typesFor(false)).containsExactly("image", "video").inOrder()
        assertThat(BackupKind.typesFor(true)).containsExactly("image", "video", "raw", "sidecar").inOrder()
    }

    @Test
    fun rulesPausePhotosForWifiAndChargingAndVideosForTheCharger() {
        val rules = BackupRules()
        assertThat(BackupPlan.pause(BackupKind.IMAGE, rules, Conditions(unmetered = false, charging = true))).isEqualTo(BackupState.REASON_WIFI)
        assertThat(BackupPlan.pause(BackupKind.IMAGE, rules, Conditions(unmetered = true, charging = false))).isNull()
        assertThat(BackupPlan.pause(BackupKind.VIDEO, rules, Conditions(unmetered = true, charging = false))).isEqualTo(BackupState.REASON_CHARGING)
        assertThat(BackupPlan.pause(BackupKind.VIDEO, rules.copy(videosCharging = false), Conditions(unmetered = true, charging = false))).isNull()
        assertThat(BackupPlan.pause(BackupKind.RAW, rules.copy(photosCharging = true), Conditions(unmetered = true, charging = false))).isEqualTo(BackupState.REASON_CHARGING)
        assertThat(BackupPlan.pause(BackupKind.IMAGE, rules.copy(photosUnmetered = false), Conditions(unmetered = false, charging = false))).isNull()
    }

    @Test
    fun mergeAddsNewFilesKeepsKnownOnesAndStartsChangedOnesOver() {
        val stored = listOf(
            row("DCIM/Camera/same.jpg", state = BackupState.STORED).copy(reported = "$fpA|100|10", itemId = "i1"),
            row("DCIM/Camera/edited.jpg", state = BackupState.UPLOADING).copy(uploadId = "u1", uploadOffset = 50, sha256 = "s", shaSize = 100, shaModified = 10),
            row("DCIM/Camera/waiting.jpg", fp = null, state = BackupState.WAITING),
        )
        val listing = Listing(
            listOf(
                file("DCIM/Camera/same.jpg", fp = fpA),
                file("DCIM/Camera/edited.jpg", size = 120, modified = 20),
                file("DCIM/Camera/waiting.jpg", fp = fpB, sha = "sha-b"),
                file("DCIM/Camera/new.jpg"),
            ),
            complete = true,
        )
        val merge = BackupPlan.merge("r", listing, stored, now = 99)
        val byPath = merge.rows.associateBy { it.path }
        assertThat(byPath.keys).containsExactly("DCIM/Camera/edited.jpg", "DCIM/Camera/waiting.jpg", "DCIM/Camera/new.jpg")
        val edited = byPath["DCIM/Camera/edited.jpg"]!!
        assertThat(edited.state).isEqualTo(BackupState.WAITING)
        assertThat(edited.uploadId).isNull()
        assertThat(edited.sha256).isNull()
        assertThat(merge.abandoned).containsExactly("u1")
        // The index learned its identity: queued for the report, with the small file's hash.
        val waiting = byPath["DCIM/Camera/waiting.jpg"]!!
        assertThat(waiting.state).isEqualTo(BackupState.QUEUED)
        assertThat(waiting.fingerprint).isEqualTo(fpB)
        assertThat(waiting.sha256).isEqualTo("sha-b")
        assertThat(byPath["DCIM/Camera/new.jpg"]!!.state).isEqualTo(BackupState.WAITING)
    }

    @Test
    fun removalsNeedACompleteNonEmptyListingAndAMoveKeepsItsContent() {
        val rows = listOf(row("DCIM/a.jpg", fp = fpA), row("DCIM/b.jpg", fp = fpB), row("DCIM/c.jpg", fp = fpB))
        val listing = Listing(listOf(file("DCIM/c.jpg", fp = fpB)), complete = true)
        assertThat(BackupPlan.removals(listing, rows)).containsExactly(BackupRemoval("DCIM/a.jpg", "deleted"), BackupRemoval("DCIM/b.jpg", "moved"))
        assertThat(BackupPlan.removals(listing.copy(complete = false), rows)).isEmpty()
        assertThat(BackupPlan.removals(Listing(emptyList(), complete = true), rows)).isEmpty()
    }

    @Test
    fun reportAnswersSetTheNextState() {
        val r = row("DCIM/a.jpg")
        assertThat(BackupPlan.reported(r, BackupReportResult(0, state = "stored", itemId = "i"), 5).state).isEqualTo(BackupState.STORED)
        assertThat(BackupPlan.reported(r, BackupReportResult(0, state = "stored", verified = "yes"), 5).let { it.state to it.verifiedAt }).isEqualTo(BackupState.VERIFIED to 5L)
        assertThat(BackupPlan.reported(r, BackupReportResult(0, state = "nas"), 5).state).isEqualTo(BackupState.NAS)
        val missing = BackupPlan.reported(r, BackupReportResult(0, state = "missing"), 5)
        assertThat(missing.state).isEqualTo(BackupState.QUEUED)
        assertThat(missing.reported).isEqualTo(BackupPlan.reportKey(r))
        assertThat(BackupPlan.reported(r, BackupReportResult(0, state = "in_progress", retryAfter = 60), 5).nextAttemptAt).isEqualTo(60_005L)
        val refused = BackupPlan.reported(r, BackupReportResult(0, state = "refused", reason = "outside_root"), 5)
        assertThat(refused.state to refused.error).isEqualTo(BackupState.REFUSED to "outside_root")
        // Reported as they are: not again until they change.
        assertThat(BackupPlan.toReport(listOf(refused, missing))).isEmpty()
        assertThat(BackupPlan.toReport(listOf(refused.copy(size = 101, fpSize = 101)))).hasSize(1)
    }

    @Test
    fun uploadsAreNewestFirstDueAndOnlyWhatTheServerMisses() {
        val now = 1000L
        val rows = listOf(
            row("old.jpg", taken = 1).let { it.copy(serverState = "missing", reported = BackupPlan.reportKey(it)) },
            row("new.jpg", taken = 9).let { it.copy(serverState = "differs", reported = BackupPlan.reportKey(it)) },
            row("later.jpg", taken = 5).let { it.copy(serverState = "in_progress", reported = BackupPlan.reportKey(it), nextAttemptAt = 2000) },
            row("stored.jpg", state = BackupState.STORED).let { it.copy(serverState = "stored", reported = BackupPlan.reportKey(it)) },
            row("unreported.jpg").copy(serverState = "missing"),
            row("refused.jpg", state = BackupState.REFUSED).let { it.copy(serverState = "missing", reported = BackupPlan.reportKey(it)) },
        )
        assertThat(BackupPlan.uploads(rows, now).map { it.path }).containsExactly("new.jpg", "old.jpg").inOrder()
        assertThat(BackupPlan.uploads(rows, 2000).map { it.path }).containsExactly("new.jpg", "later.jpg", "old.jpg").inOrder()
    }

    @Test
    fun failuresBackOffAndChangedFilesAreIdentifiedAgain() {
        val r = row("a.jpg").copy(sha256 = "s", shaSize = 100, shaModified = 10, uploadId = "u", uploadOffset = 10)
        val failed = BackupPlan.failed(r, BackupState.REASON_SERVER_ERROR, 0)
        assertThat(failed.nextAttemptAt).isEqualTo(60_000L)
        assertThat(BackupPlan.failed(failed, BackupState.REASON_SERVER_ERROR, 0).nextAttemptAt).isEqualTo(120_000L)
        assertThat(BackupPlan.backoffMs(30)).isEqualTo(6 * 3_600_000L)
        val changed = BackupPlan.changed(r, 0)
        assertThat(BackupPlan.identified(changed)).isFalse()
        assertThat(BackupPlan.hashed(changed)).isFalse()
        assertThat(changed.uploadId).isNull()
        val uploaded = BackupPlan.uploaded(r, 0)
        assertThat(BackupPlan.toReport(listOf(uploaded))).hasSize(1)
    }

    @Test
    fun aFoldersStatusCountsBackedUpOnTheServerAndWaiting() {
        val s = BackupPlan.status(
            listOf(
                BackupPlan.Count(BackupState.STORED, null, 11_000, 44_000),
                BackupPlan.Count(BackupState.VERIFIED, null, 50, 200),
                BackupPlan.Count(BackupState.NAS, null, 950, 3_800),
                BackupPlan.Count(BackupState.PAUSED, BackupState.REASON_WIFI, 34, 136),
                BackupPlan.Count(BackupState.PAUSED, BackupState.REASON_CHARGING, 2, 900),
                BackupPlan.Count(BackupState.QUEUED, null, 3, 12),
                BackupPlan.Count(BackupState.FAILED, BackupState.REASON_UNREADABLE, 1, 4),
                BackupPlan.Count(BackupState.REFUSED, "unsupported_type", 1, 4),
            ),
        )
        assertThat(s.files).isEqualTo(12_041)
        assertThat(s.backedUp).isEqualTo(11_050)
        assertThat(s.onServer).isEqualTo(950)
        assertThat(s.done).isEqualTo(12_000)
        assertThat(s.waitingWifi).isEqualTo(34)
        assertThat(s.waitingCharging).isEqualTo(2)
        assertThat(s.queued).isEqualTo(3)
        assertThat(s.failed).isEqualTo(1)
        assertThat(s.refused).isEqualTo(1)
    }

    @Test
    fun foldersMapToTheServersVolumesAndPaths() {
        assertThat(BackupPaths.volumeOf("device")).isEqualTo("primary")
        assertThat(BackupPaths.volumeOf("vol-1234-abcd")).isEqualTo("1234-ABCD")
        assertThat(BackupPaths.volumeOf("machine-1")).isNull()
        assertThat(BackupPaths.clean("/DCIM//Camera/")).isEqualTo("DCIM/Camera")
        assertThat(BackupPaths.within("DCIM", "DCIM/Camera/a.jpg")).isTrue()
        assertThat(BackupPaths.within("DCIM", "DCIMX/a.jpg")).isFalse()
        assertThat(BackupPaths.within("", "anything")).isTrue()
    }
}
