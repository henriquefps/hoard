package dev.hfps.hoard.server.backup

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.BackupVerifyResult
import dev.hfps.hoard.server.api.CandidateDevice
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.BackupRootEntity
import org.junit.Test

/** Free up's pure decisions (ADR 0024 "Free up space", phase A3: `FreeUpPlan`). */
class FreeUpPlanTest {
    private val now = 400 * FreeUpPlan.DAY_MS
    private val old = now - 100 * FreeUpPlan.DAY_MS
    private val root = BackupRootEntity("r", "primary", "DCIM/Camera", "device", "DCIM/Camera", "image,video,raw,sidecar", 0)
    private val sd = BackupRootEntity("s", "1234-ABCD", "DCIM", "vol-1234-ABCD", "DCIM", "image,video", 0)
    private var nextMedia = 1L

    private fun fp(path: String) = "v1:" + path.hashCode().toUInt().toString(16).padStart(64, '0')

    private fun row(
        path: String,
        taken: Long = old,
        state: String = BackupState.STORED,
        server: String? = "stored",
        media: Boolean = true,
        kind: String = if (path.endsWith(".mp4")) BackupKind.VIDEO else if (path.endsWith(".xmp")) BackupKind.SIDECAR else BackupKind.IMAGE,
        rootId: String = "r",
        sha: String? = "a".repeat(64),
        size: Long = 1000,
    ): BackupFileEntity {
        val f = fp(path)
        val base = BackupFileEntity(
            rootId, path, "/storage/emulated/0/$path", if (media) nextMedia++ else null, kind, size, 10_000, taken,
            f, size, 10_000, sha, size.takeIf { sha != null }, 10_000L.takeIf { sha != null }, state = state, serverState = server,
        )
        return base.copy(reported = BackupPlan.reportKey(base))
    }

    private fun candidates(files: List<BackupFileEntity>, rules: FreeUpRules = FreeUpRules(), favourites: Set<Long> = emptySet(), culling: Set<String> = emptySet()) =
        FreeUpPlan.candidates(listOf(root, sd), files, rules, favourites, culling, now)

    @Test
    fun wholeShotsOldestFirstWithEveryFileBackedUp() {
        val files = listOf(
            row("DCIM/Camera/PXL_2.jpg", taken = old + 1000),
            row("DCIM/Camera/PXL_1.jpg", taken = old),
            row("DCIM/Camera/PXL_1.dng", taken = old, kind = BackupKind.RAW, media = false),
            row("DCIM/Camera/PXL_1.xmp", taken = old, media = false),
            // A pair whose RAW is still uploading: the whole shot stays.
            row("DCIM/Camera/PXL_3.jpg", taken = old - 5),
            row("DCIM/Camera/PXL_3.dng", taken = old - 5, kind = BackupKind.RAW, state = BackupState.UPLOADING, server = "missing", media = false),
            // On the NAS (referenced) counts as backed up.
            row("DCIM/Camera/PXL_4.jpg", taken = old + 2000, state = BackupState.NAS, server = "nas"),
        )
        val c = candidates(files)
        assertThat(c.shots.map { it.key }).containsExactly("device/DCIM/Camera/pxl_1", "device/DCIM/Camera/pxl_2", "device/DCIM/Camera/pxl_4").inOrder()
        assertThat(c.shots.first().files.map { it.path }).containsExactly("DCIM/Camera/PXL_1.dng", "DCIM/Camera/PXL_1.jpg", "DCIM/Camera/PXL_1.xmp")
        assertThat(c.shots.first().others.map { it.path }).containsExactly("DCIM/Camera/PXL_1.dng", "DCIM/Camera/PXL_1.xmp")
        assertThat(c.excluded).containsExactly(FreeUpExclusion.NotBackedUp, 1)
        assertThat(c.bytes).isEqualTo(5000)
    }

    @Test
    fun recentFavouritesCullingAndListedOnlyShotsStay() {
        val recent = row("DCIM/Camera/new.jpg", taken = now - 3 * FreeUpPlan.DAY_MS)
        val fav = row("DCIM/Camera/fav.jpg")
        val culled = row("DCIM/Camera/culled.jpg")
        val listedOnly = row("DCIM/Camera/IMG_1.CR3", kind = BackupKind.RAW, media = false)
        val failed = row("DCIM/Camera/failed.jpg", state = BackupState.FAILED, server = "missing")
        val refused = row("DCIM/Camera/odd.jpg", state = BackupState.REFUSED, server = "refused")
        // Reported before it changed: the row's report key no longer matches.
        val stale = row("DCIM/Camera/stale.jpg").copy(reported = "v1:old|1|1")
        val ok = row("DCIM/Camera/ok.jpg")
        val files = listOf(recent, fav, culled, listedOnly, failed, refused, stale, ok)
        val c = candidates(files, favourites = setOf(fav.mediaId!!), culling = setOf("device/DCIM/Camera/culled"))
        assertThat(c.shots.map { it.key }).containsExactly("device/DCIM/Camera/ok")
        assertThat(c.excluded).containsExactly(
            FreeUpExclusion.Recent, 1,
            FreeUpExclusion.Favourite, 1,
            FreeUpExclusion.Culling, 1,
            FreeUpExclusion.NoMedia, 1,
            FreeUpExclusion.NotBackedUp, 3,
        )
        // Both settings off: recent shots and favourites go too.
        val all = candidates(files, FreeUpRules(keepRecent = false, keepFavourites = false), setOf(fav.mediaId!!), setOf("device/DCIM/Camera/culled"))
        assertThat(all.shots.map { it.key }).containsExactly("device/DCIM/Camera/new", "device/DCIM/Camera/fav", "device/DCIM/Camera/ok")
    }

    @Test
    fun shotsGroupPerFolderAndVolumeAndPausedRootsAreLeftOut() {
        val files = listOf(
            row("DCIM/Camera/a.jpg"),
            row("DCIM/Camera/sub/a.jpg"),
            row("DCIM/a.jpg", rootId = "s"),
            row("DCIM/Camera/v.mp4"),
        )
        val c = candidates(files)
        assertThat(c.shots.map { it.key }).containsExactly("device/DCIM/Camera/a", "device/DCIM/Camera/sub/a", "vol-1234-ABCD/DCIM/a", "device/DCIM/Camera/v")
        assertThat(FreeUpPlan.counts(c.shots)).isEqualTo(3 to 1)
        val paused = FreeUpPlan.candidates(listOf(root, sd.copy(paused = true)), files, FreeUpRules(), emptySet(), emptySet(), now)
        assertThat(paused.shots.map { it.rootId }.toSet()).containsExactly("r")
    }

    private fun device(app: String, seen: Long?, vararg features: String) = CandidateDevice(id = app, app = app, lastSeenAt = seen, features = features.toList())

    @Test
    fun theRevealGate() {
        val hoard = device("hoard", now)
        // No Reveal on the phone: off.
        assertThat(FreeUpPlan.revealGate(listOf(hoard), now)).isEqualTo(RevealGate.Off)
        // A live Reveal without freed-adopt: on.
        assertThat(FreeUpPlan.revealGate(listOf(hoard, device("reveal", now - 60_000, "culling")), now)).isEqualTo(RevealGate.On(now - 60_000))
        // The debug build counts as Reveal too.
        assertThat(FreeUpPlan.revealGate(listOf(device("reveal-debug", now - 60_000)), now)).isInstanceOf(RevealGate.On::class.java)
        // Seen more than 30 minutes ago: nothing goes.
        val stale = FreeUpPlan.revealGate(listOf(device("reveal", now - 31 * 60_000)), now)
        assertThat(stale).isEqualTo(RevealGate.Stale(now - 31 * 60_000))
        assertThat(stale.blocksAll).isTrue()
        // With freed-adopt: off (also when it was not seen for a while).
        assertThat(FreeUpPlan.revealGate(listOf(device("reveal", now - 60_000, "culling", "freed-adopt")), now)).isEqualTo(RevealGate.Off)
        assertThat(FreeUpPlan.revealGate(listOf(device("reveal", now - 5 * FreeUpPlan.DAY_MS, "freed-adopt")), now)).isEqualTo(RevealGate.Off)
        // Not live (never seen, or not for 90 days): off.
        assertThat(FreeUpPlan.revealGate(listOf(device("reveal", null)), now)).isEqualTo(RevealGate.Off)
        assertThat(FreeUpPlan.revealGate(listOf(device("reveal", now - 91 * FreeUpPlan.DAY_MS)), now)).isEqualTo(RevealGate.Off)
        // Two Reveals, one of them without freed-adopt: on.
        assertThat(FreeUpPlan.revealGate(listOf(device("reveal", now - 1000, "freed-adopt"), device("reveal-debug", now - 2000)), now)).isEqualTo(RevealGate.On(now - 2000))
    }

    private fun shot(vararg files: BackupFileEntity) = FreeUpPlan.shots(listOf(root), files.toList()).single()

    private fun yes(namedByRecord: Boolean = false, at: Long = now) = VerifyAnswer("yes", at, namedByRecord = namedByRecord)

    @Test
    fun verifyAnswersYesPendingNoAndRefused() {
        val jpg = row("DCIM/Camera/P.jpg")
        val dng = row("DCIM/Camera/P.dng", kind = BackupKind.RAW, media = false)
        val s = shot(jpg, dng)
        val k1 = FreeUpPlan.fileKey(jpg)
        val k2 = FreeUpPlan.fileKey(dng)
        // Every file yes: it goes.
        assertThat(FreeUpPlan.check(s, mapOf(k1 to yes(), k2 to yes()), RevealGate.Off, now)).isEqualTo(ShotCheck.Go)
        // One file not asked yet, or pending: it waits.
        assertThat(FreeUpPlan.check(s, mapOf(k1 to yes()), RevealGate.Off, now)).isEqualTo(ShotCheck.Wait)
        val pending = FreeUpPlan.answer(BackupVerifyResult(1, "pending", retryAfter = 30), now)
        assertThat(pending.retryAt).isEqualTo(now + 30_000)
        assertThat(FreeUpPlan.check(s, mapOf(k1 to yes(), k2 to pending), RevealGate.Off, now)).isEqualTo(ShotCheck.Wait)
        // A no keeps the whole shot with the server's reason, even with another file pending.
        val differs = FreeUpPlan.answer(BackupVerifyResult(0, "no", reason = "differs"), now)
        assertThat(FreeUpPlan.check(s, mapOf(k1 to differs, k2 to pending), RevealGate.Off, now)).isEqualTo(ShotCheck.Keep("differs"))
        // A refused entry is a no with its reason.
        val refused = FreeUpPlan.answer(BackupVerifyResult(0, "no", reason = "invalid_path", state = "refused"), now)
        assertThat(refused.refused).isTrue()
        assertThat(FreeUpPlan.check(s, mapOf(k1 to yes(), k2 to refused), RevealGate.Off, now)).isEqualTo(ShotCheck.Keep("refused:invalid_path"))
        // An unknown answer is a no.
        assertThat(FreeUpPlan.answer(BackupVerifyResult(0, "maybe"), now).verified).isEqualTo("no")
        // A yes older than 10 minutes is asked again.
        val older = mapOf(k1 to yes(at = now - 11 * 60_000), k2 to yes())
        assertThat(FreeUpPlan.check(s, older, RevealGate.Off, now)).isEqualTo(ShotCheck.Wait)
        assertThat(FreeUpPlan.toAsk(listOf(s), older, now)).containsExactly(jpg)
        // Pending is asked again only once due.
        assertThat(FreeUpPlan.toAsk(listOf(s), mapOf(k1 to yes(), k2 to pending), now)).isEmpty()
        assertThat(FreeUpPlan.toAsk(listOf(s), mapOf(k1 to yes(), k2 to pending), now + 30_000)).containsExactly(dng)
    }

    @Test
    fun revealGateAppliesToNamedShotsAfterVerify() {
        val named = shot(row("DCIM/Camera/E.jpg"), row("DCIM/Camera/E.dng", kind = BackupKind.RAW, media = false))
        val plain = shot(row("DCIM/Camera/F.jpg"))
        // Named by a record through one file of the pair (no ADR 0020 edit needed).
        val answers = mapOf(
            FreeUpPlan.fileKey(named.files[0]) to yes(namedByRecord = true),
            FreeUpPlan.fileKey(named.files[1]) to yes(),
            FreeUpPlan.fileKey(plain.files[0]) to yes(),
        )
        val on = RevealGate.On(now)
        assertThat(FreeUpPlan.check(named, answers, on, now)).isEqualTo(ShotCheck.Keep(FreeUpPlan.REASON_REVEAL))
        assertThat(FreeUpPlan.check(plain, answers, on, now)).isEqualTo(ShotCheck.Go)
        // No Reveal, or one with freed-adopt: named shots go.
        assertThat(FreeUpPlan.check(named, answers, RevealGate.Off, now)).isEqualTo(ShotCheck.Go)
        // A stale Reveal: nothing goes.
        assertThat(FreeUpPlan.check(plain, answers, RevealGate.Stale(now - 3_600_000), now)).isEqualTo(ShotCheck.Keep(FreeUpPlan.REASON_REVEAL_STALE))
    }

    @Test
    fun requestsHoldAtMostAThousandMediaFilesAndWholeShots() {
        val shots = (1..1500).map { i -> shot(row("DCIM/Camera/S$i.jpg"), row("DCIM/Camera/S$i.mp4"), row("DCIM/Camera/S$i.xmp", media = false)) }
        val requests = FreeUpPlan.requests(shots)
        assertThat(requests.map { r -> r.sumOf { it.media.size } }).containsExactly(1000, 1000, 1000).inOrder()
        assertThat(requests.flatten()).containsExactlyElementsIn(shots).inOrder()
    }

    @Test
    fun aPartialConsentFreesOnlyWhatTheSystemDeleted() {
        val pair = shot(row("DCIM/Camera/A.jpg"), row("DCIM/Camera/A.mp4"), row("DCIM/Camera/A.xmp", media = false))
        val single = shot(row("DCIM/Camera/B.jpg"), row("DCIM/Camera/B.xmp", media = false))
        val untouched = shot(row("DCIM/Camera/C.jpg"))
        // The system deleted B whole and only the still of A; nothing of C.
        val gone = setOf(FreeUpPlan.fileKey(pair.files.first { it.path.endsWith(".jpg") }), FreeUpPlan.fileKey(single.media.single()))
        val s = FreeUpPlan.settle(listOf(pair, single, untouched), gone)
        assertThat(s.whole).containsExactly(single)
        assertThat(s.partial).containsExactly(pair)
        assertThat(s.kept).containsExactly(untouched)
        assertThat(s.goneMedia.map { it.path }).containsExactly("DCIM/Camera/A.jpg", "DCIM/Camera/B.jpg")
        // A's sidecar stays with its video; B's goes after its media.
        assertThat(s.othersToDelete.map { it.path }).containsExactly("DCIM/Camera/B.xmp")
        // A refused request deletes nothing.
        val none = FreeUpPlan.settle(listOf(pair, single), emptySet())
        assertThat(none.goneMedia).isEmpty()
        assertThat(none.othersToDelete).isEmpty()
    }

    @Test
    fun freedRemovalsReportWhatFreeUpDeletedWhenItsReportWasLost() {
        val a = row("DCIM/Camera/A.jpg")
        val b = row("DCIM/Camera/B.jpg")
        val freed = mapOf(
            a.absolute to dev.hfps.hoard.server.db.FreedEntity(a.absolute, null, a.fingerprint!!, 1000, 1),
            // Freed once, but the phone has other content there now.
            b.absolute to dev.hfps.hoard.server.db.FreedEntity(b.absolute, null, "v1:other", 1000, 1),
        )
        val out = BackupPlan.freedRemovals(listOf(a, b), freed) { false }
        assertThat(out.map { it.path to it.reason }).containsExactly("DCIM/Camera/A.jpg" to "freed")
        // Still on the phone (a restore): nothing to report.
        assertThat(BackupPlan.freedRemovals(listOf(a), freed) { true }).isEmpty()
    }
}
