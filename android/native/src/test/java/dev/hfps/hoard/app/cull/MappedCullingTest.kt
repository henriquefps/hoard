package dev.hfps.hoard.app.cull

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.legacy.isoOf
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.PendingCullingEntity
import dev.hfps.hoard.server.sync.CullValues
import org.junit.Test

/** ADR 0022 "Culling: dual write" on the folder side: what goes to the server, what the server shows on the folder. */
class MappedCullingTest {
    private val template = CullDecisionEntity("m1", "photos", "Imagens/Trip", "", null, 0, isoOf(0))
    private fun row(shot: String, flag: String?, rating: Int, at: Long) = template.copy(shotId = shot, flag = flag, rating = rating, updatedAt = isoOf(at))
    private fun record(id: String, rating: Int?, flag: String?, at: Long) =
        CullingEntity(id, 5, null, rating, 5, at, "reveal", flag, 5, at, "reveal", null, null, null, null)

    @Test
    fun onlyMappedShotsGoToTheServerWithTheirTimes() {
        val out = MappedCulling.serverDecisions(
            listOf(Triple("dsc1", "pick", 4), Triple("dsc2", null, 0), Triple("local", "reject", 0)),
            mapOf("dsc1" to "item-1", "dsc2" to "item-2"),
            decidedAt = mapOf("dsc1" to 1234L),
            now = 9999,
        )
        assertThat(out).containsExactly(
            MappedCulling.ServerDecision("dsc1", "item-1", CullValues(4, "pick"), 1234),
            // An unmark is a decision too (rating 0, flag none).
            MappedCulling.ServerDecision("dsc2", "item-2", CullValues(0, null), 9999),
        ).inOrder()
    }

    @Test
    fun aNewerServerValueShowsOnTheFolderAnOlderOneDoesNot() {
        val rows = listOf(row("dsc1", "pick", 2, 1_000), row("dsc2", null, 3, 9_000), row("local", "reject", 0, 1))
        val items = mapOf("dsc1" to "item-1", "dsc2" to "item-2", "dsc3" to "item-3")
        val server = mapOf(
            "item-1" to (CullValues(5, "pick") to 5_000L), // Reveal rated it later: shown
            "item-2" to (CullValues(1, null) to 2_000L), // older than the folder's row: the row stays
            "item-3" to (CullValues(0, "reject") to 3_000L), // no row: the server's value
        )
        val merged = MappedCulling.merge(rows, items, server, template).associateBy { it.shotId }
        assertThat(merged.getValue("dsc1").rating).isEqualTo(5)
        assertThat(merged.getValue("dsc1").updatedAt).isEqualTo(isoOf(5_000))
        assertThat(merged.getValue("dsc2").rating).isEqualTo(3)
        assertThat(merged.getValue("dsc3").flag).isEqualTo("reject")
        assertThat(merged.getValue("local").flag).isEqualTo("reject")
    }

    @Test
    fun ourOwnPendingChangesAreNotShownBack() {
        assertThat(MappedCulling.serverSide(record("i", 3, "pick", 100), listOf(PendingCullingEntity("i", "rating", "4", 5, 200)))).isNull()
        assertThat(MappedCulling.serverSide(record("i", 3, "pick", 100), emptyList())).isEqualTo(CullValues(3, "pick") to 100L)
        assertThat(MappedCulling.serverSide(null, emptyList())).isNull()
    }
}
