package dev.hfps.hoard.app.cull

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Port of src/features/cull/burst.test.ts. */
class BurstTest {
    private fun d(flag: Flag?, rating: Int = 0) = Decision(flag, rating)
    private val ids = listOf("a", "b", "c", "d", "e")

    @Test
    fun `keep this one picks the frame and rejects the rest in one undo step`() {
        val before = emptyMap<String, Decision>()
        val change = keepThisOne(before, ids, "c")!!
        assertThat(change.shotIds).isEqualTo(ids)
        val after = applyChange(before, change)
        assertThat(after["c"]?.flag).isEqualTo(Flag.Pick)
        assertThat(listOf("a", "b", "d", "e").map { after[it]?.flag }).containsExactly(Flag.Reject, Flag.Reject, Flag.Reject, Flag.Reject)
        assertThat(applyChange(after, change, undo = true)).isEqualTo(before)
    }

    @Test
    fun `keep this one never un-picks explicit keepers and keeps the frame's own stars`() {
        val before = mapOf("a" to d(Flag.Pick), "b" to d(null, 3), "c" to d(null, 2), "e" to d(Flag.Reject))
        val after = applyChange(before, keepThisOne(before, ids, "c")!!)
        assertThat(after["a"]).isEqualTo(d(Flag.Pick))
        assertThat(after["b"]).isEqualTo(d(null, 3))
        assertThat(after["c"]).isEqualTo(d(Flag.Pick, 2))
        assertThat(after["d"]).isEqualTo(d(Flag.Reject))
        assertThat(after["e"]).isEqualTo(d(Flag.Reject))
    }

    @Test
    fun `keep this one changes only what differs, nothing when resolved, not for a stranger`() {
        val resolved = mapOf("a" to d(Flag.Reject), "b" to d(Flag.Pick), "c" to d(Flag.Reject))
        assertThat(keepThisOne(resolved, listOf("a", "b", "c"), "b")).isNull()
        assertThat(keepThisOne(mapOf("a" to d(Flag.Reject)), listOf("a", "b", "c"), "b")!!.shotIds).containsExactly("b", "c").inOrder()
        assertThat(applyChange(resolved, keepThisOne(resolved, listOf("a", "b", "c"), "a")!!)["a"]?.flag).isEqualTo(Flag.Pick)
        assertThat(keepThisOne(resolved, listOf("a", "b", "c"), "z")).isNull()
    }

    @Test
    fun `keep this one works for a burst of 2 and of 40`() {
        val two = applyChange(emptyMap(), keepThisOne(emptyMap(), listOf("x", "y"), "y")!!)
        assertThat(listOf(two["x"]?.flag, two["y"]?.flag)).containsExactly(Flag.Reject, Flag.Pick).inOrder()
        val forty = (0 until 40).map { "f$it" }
        val after = applyChange(emptyMap(), keepThisOne(emptyMap(), forty, "f17")!!)
        assertThat(burstStatus(forty, after)).isEqualTo(BurstStatus(size = 40, kept = 1, decided = 40, resolved = true))
    }

    @Test
    fun `x on a burst rejects every frame, picks included, undone in one step`() {
        val before = mapOf("a" to d(Flag.Pick, 2), "b" to d(null, 4), "d" to d(Flag.Reject))
        val change = rejectBurst(before, ids, RejectMode.Toggle)!!
        assertThat(change.shotIds).containsExactly("a", "b", "c", "e").inOrder()
        val after = applyChange(before, change)
        assertThat(ids.map { after[it]?.flag }.toSet()).containsExactly(Flag.Reject)
        assertThat(after["a"]).isEqualTo(d(Flag.Reject, 2))
        assertThat(after["b"]).isEqualTo(d(Flag.Reject, 4))
        assertThat(burstStatus(ids, after).resolved).isTrue()
        assertThat(applyChange(after, change, undo = true)).isEqualTo(before)
    }

    @Test
    fun `x toggles back to unmarked when every frame is rejected, set never unmarks`() {
        val allRejected = ids.associateWith { d(Flag.Reject, if (it == "c") 3 else 0) }
        val off = applyChange(allRejected, rejectBurst(allRejected, ids, RejectMode.Toggle)!!)
        assertThat(ids.map { off[it]?.flag }.toSet()).isEqualTo(setOf<Flag?>(null))
        assertThat(off["c"]).isEqualTo(d(null, 3))
        assertThat(rejectBurst(allRejected, ids, RejectMode.Set)).isNull()
        assertThat(rejectBurst(emptyMap(), emptyList(), RejectMode.Toggle)).isNull()
    }

    @Test
    fun `x rejects the whole burst after keep this one`() {
        val kept = applyChange(emptyMap(), keepThisOne(emptyMap(), ids, "c")!!)
        assertThat(burstCover(ids, kept, null)).isEqualTo("c")
        val after = applyChange(kept, rejectBurst(kept, ids, RejectMode.Toggle)!!)
        assertThat(burstStatus(ids, after).kept).isEqualTo(0)
        assertThat(burstStatus(ids, after).decided).isEqualTo(5)
    }

    @Test
    fun `cover is the user's pick, else the crowned best, else the first not rejected`() {
        assertThat(burstCover(ids, emptyMap(), "c")).isEqualTo("c")
        assertThat(burstCover(ids, mapOf("d" to d(Flag.Pick)), "c")).isEqualTo("d")
        assertThat(burstCover(ids, mapOf("b" to d(Flag.Pick), "d" to d(Flag.Pick)), "c")).isEqualTo("b")
        assertThat(burstCover(ids, mapOf("c" to d(Flag.Reject)), "c")).isEqualTo("a")
        assertThat(burstCover(ids, mapOf("a" to d(Flag.Reject), "c" to d(Flag.Reject)), "c")).isEqualTo("b")
        assertThat(burstCover(ids, emptyMap(), null)).isEqualTo("a")
        assertThat(burstCover(ids, emptyMap(), "stranger")).isEqualTo("a")
        assertThat(burstCover(ids, ids.associateWith { d(Flag.Reject) }, "c")).isEqualTo("a")
    }

    @Test
    fun `status is pending until every frame is decided`() {
        assertThat(burstStatus(ids, mapOf("a" to d(Flag.Pick)))).isEqualTo(BurstStatus(5, 1, 1, false))
        val done = mapOf("a" to d(Flag.Pick), "b" to d(Flag.Reject), "c" to d(Flag.Reject), "d" to d(Flag.Pick), "e" to d(Flag.Reject))
        assertThat(burstStatus(ids, done).kept).isEqualTo(2)
        assertThat(burstStatus(ids, done).resolved).isTrue()
        assertThat(burstStatus(listOf("a"), mapOf("a" to d(null, 4))).resolved).isFalse()
        assertThat(burstStatus(emptyList(), emptyMap()).resolved).isFalse()
    }

    private val order = listOf("s1", "a", "b", "c", "s2", "x", "y")
    private val bursts = mapOf(
        "a" to BurstPosition("a", 0, 3), "b" to BurstPosition("a", 1, 3), "c" to BurstPosition("a", 2, 3),
        "x" to BurstPosition("x", 0, 2), "y" to BurstPosition("x", 1, 2),
    )
    private val members = mapOf("a" to listOf("a", "b", "c"), "x" to listOf("x", "y"))

    @Test
    fun `a burst is one step in the folder`() {
        val steps = viewerSteps(order, bursts, members, true)
        assertThat(steps).containsExactly(
            ViewerStep.Single("s1"), ViewerStep.Burst("a", listOf("a", "b", "c")), ViewerStep.Single("s2"), ViewerStep.Burst("x", listOf("x", "y")),
        ).inOrder()
        assertThat(stepOf(steps, "b")).isEqualTo(1)
        assertThat(stepOf(steps, "s2")).isEqualTo(2)
        assertThat(stepOf(steps, "nope")).isEqualTo(-1)
    }

    @Test
    fun `filters and no collapse show individual frames`() {
        assertThat(viewerSteps(listOf("b", "y"), bursts, members, true)).containsExactly(ViewerStep.Single("b"), ViewerStep.Single("y")).inOrder()
        assertThat(viewerSteps(order, bursts, members, false)).hasSize(7)
    }

    @Test
    fun `a burst step shows its cover, following the decisions`() {
        val step = viewerSteps(order, bursts, members, true)[1]
        val best = mapOf("a" to "b")
        assertThat(stepShot(step, emptyMap(), best)).isEqualTo("b")
        assertThat(stepShot(step, mapOf("c" to d(Flag.Pick)), best)).isEqualTo("c")
        assertThat(stepShot(ViewerStep.Single("s1"), emptyMap(), best)).isEqualTo("s1")
    }

    @Test
    fun `quick compare partner`() {
        assertThat(comparePartner("c", ids, listOf("b", "c", "a"))).isEqualTo("b")
        assertThat(comparePartner("b", ids, listOf("b", "c", "a"))).isEqualTo("c")
        assertThat(comparePartner("b", ids, listOf("b"))).isEqualTo("c")
        assertThat(comparePartner("e", ids, emptyList())).isEqualTo("d")
        assertThat(comparePartner("a", listOf("a"), emptyList())).isNull()
    }
}
