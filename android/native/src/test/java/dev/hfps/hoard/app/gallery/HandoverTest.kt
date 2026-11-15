package dev.hfps.hoard.app.gallery

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.CullSidecarEntity
import dev.hfps.hoard.data.legacy.isoOf
import dev.hfps.hoard.server.db.DevicePushedEntity
import dev.hfps.hoard.server.sync.CullValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** ADR 0021 hand-over of phone decisions (what `GalleryRepository.promote` queues), and the offline thumbs counter. */
class HandoverTest {
    private val now = 1_700_000_000_000L
    private fun row(flag: String?, rating: Int, at: Long) = CullDecisionEntity("device", "", "DCIM/Camera", "pxl_1", flag, rating, isoOf(at))
    private fun sidecar(decidedAt: Long?, mtime: Long? = null, synced: Int = 0) = CullSidecarEntity("device", "", "DCIM/Camera", "pxl_1", decidedAt, mtime, synced)
    private fun pushed(at: Long, itemId: String = "item") = DevicePushedEntity("device/DCIM/Camera/pxl_1", itemId, at)

    @Test
    fun `a first hand-over sends the decision with the time it was made`() {
        val step = Handover.step("item", row("pick", 3, now - 60_000), null, null, now)!!
        assertThat(step.promotion).isEqualTo(Handover.Promotion(CullValues(3, "pick"), now - 60_000, clears = false))
        assertThat(step.pushedAt).isEqualTo(now)
    }

    @Test
    fun `a decision imported from an old sidecar keeps the sidecar's time, so a newer server value wins`() {
        // The Files tab imported a sidecar written a year ago: the row is dated by its mtime (ADR 0016 rule 4).
        val old = now - 365L * 86_400_000
        val step = Handover.step("item", row("reject", 0, old), sidecar(null, old, synced = 1), null, now)!!
        assertThat(step.promotion!!.at).isEqualTo(old)
    }

    @Test
    fun `nothing is handed over again until the shot is decided again`() {
        assertThat(Handover.step("item", row("pick", 3, now - 60_000), null, pushed(now - 1_000), now)).isNull()
        val again = Handover.step("item", row(null, 5, now - 500), null, pushed(now), now)
        assertThat(again).isNull()
        val later = Handover.step("item", row(null, 5, now + 5_000), null, pushed(now), now)!!
        assertThat(later.promotion).isEqualTo(Handover.Promotion(CullValues(5, null), now + 5_000, clears = true))
    }

    @Test
    fun `an unmark in the Files tab after the hand-over is pushed as a clear with its time`() {
        // The row is deleted on unmark; the sidecar row's decided_at dates the clear.
        val step = Handover.step("item", null, sidecar(decidedAt = now - 2_000), pushed(now - 10_000), now)!!
        assertThat(step.promotion).isEqualTo(Handover.Promotion(CullValues(), now - 2_000, clears = true))
        // A clear imported from a sidecar is dated by that sidecar's mtime.
        val imported = Handover.step("item", null, sidecar(decidedAt = now - 20_000, mtime = now - 3_000, synced = 1), pushed(now - 10_000), now)!!
        assertThat(imported.promotion!!.at).isEqualTo(now - 3_000)
        // A clear older than the hand-over was already part of it.
        assertThat(Handover.step("item", null, sidecar(decidedAt = now - 20_000), pushed(now - 10_000), now)).isNull()
        // Never handed over: the server never had the phone's decision, so there is nothing to clear.
        assertThat(Handover.step("item", null, sidecar(decidedAt = now - 2_000), null, now)!!.promotion).isNull()
    }

    @Test
    fun `missing thumbnails are counted exactly while four fetch at once`() = runBlocking {
        val ids = (1..2_000).map { "i$it" }
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val missing = withContext(Dispatchers.Default) {
            GalleryRepository.fetchEach(ids, 4) { id ->
                val n = running.incrementAndGet()
                peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                if (id.hashCode() % 3 == 0) delay(1) else yield()
                running.decrementAndGet()
                id.removePrefix("i").toInt() % 2 == 0
            }
        }
        assertThat(missing).isEqualTo(1_000)
        assertThat(peak.get()).isAtMost(4)
    }
}
