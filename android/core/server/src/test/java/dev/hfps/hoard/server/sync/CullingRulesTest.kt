package dev.hfps.hoard.server.sync

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.ItemIds
import dev.hfps.hoard.server.api.CullingField
import dev.hfps.hoard.server.api.CullingRecord
import dev.hfps.hoard.server.api.CullingResult
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.PendingCullingEntity
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** ADR 0016 "Client rules" (1-4, 6) and "Mapping a record". */
class CullingRulesTest {
    private val record = CullingRules.entity(
        CullingRecord(
            itemId = "item-1", rev = 131, fingerprints = listOf("v1:raw", "v1:jpg"),
            rating = CullingField(JsonPrimitive(4), 131, 1000, "reveal"),
            flag = CullingField(JsonPrimitive("reject"), 96, 900, "nas"),
        ),
    )

    @Test
    fun cachesServerValuesPerField() {
        assertThat(CullingRules.serverValues(record)).isEqualTo(CullValues(rating = 4, flag = "reject"))
        assertThat(CullingRules.revOf(record, CullField.Rating)).isEqualTo(131)
        assertThat(CullingRules.revOf(record, CullField.Flag)).isEqualTo(96)
        // A field never written: rev 0 (rule 2's baseRev for a field it never saw).
        assertThat(CullingRules.revOf(record, CullField.Label)).isEqualTo(0)
        assertThat(CullingRules.serverValues(null)).isEqualTo(CullValues())
    }

    @Test
    fun aDecisionQueuesOnlyTheFieldsThatChangedWithTheCachedRev() {
        val before = CullingRules.serverValues(record)
        val rows = CullingRules.queue("item-1", record, before, before.copy(flag = "pick"), at = 5000)
        assertThat(rows).containsExactly(PendingCullingEntity("item-1", "flag", "\"pick\"", 96, 5000))
        // Unflagging sends "none"; stars are an integer.
        assertThat(CullingRules.queue("item-1", record, before, before.copy(flag = null, rating = 2), 6000).map { it.field to it.value })
            .containsExactly("rating" to "2", "flag" to "\"none\"")
        assertThat(CullingRules.queue("item-1", record, before, before, 7000)).isEmpty()
    }

    @Test
    fun pendingChangesOverrideThePulledValue() {
        val pending = listOf(PendingCullingEntity("item-1", "flag", "\"pick\"", 96, 5000), PendingCullingEntity("item-1", "label", "\"Red\"", 0, 5000))
        assertThat(CullingRules.effective(record, pending)).isEqualTo(CullValues(rating = 4, flag = "pick", label = "Red"))
        // A pull that changes the rating while the flag is pending: the rating follows the server, the flag stays local.
        val pulled = record.copy(rating = 1, ratingRev = 140, flag = "none", flagRev = 141)
        assertThat(CullingRules.effective(pulled, pending)).isEqualTo(CullValues(rating = 1, flag = "pick", label = "Red"))
    }

    @Test
    fun answersDecideWhatHappensToTheQueue() {
        val sent = PendingCullingEntity("item-1", "rating", "5", 131, 5000)
        assertThat(CullingRules.outcome(sent, CullingResult(0, "item-1", status = "applied", rev = 150))).isEqualTo(CullingRules.Outcome.Done("item-1", true, 150))
        assertThat(CullingRules.outcome(sent, CullingResult(0, "item-1", status = "conflict", winner = "incoming", rev = 151))).isEqualTo(CullingRules.Outcome.Done("item-1", true, 151))
        // Server won: the queue drops it, the pull that follows brings the winner.
        assertThat(CullingRules.outcome(sent, CullingResult(0, "item-1", status = "conflict", winner = "server", rev = 149))).isEqualTo(CullingRules.Outcome.Done("item-1", false, 149))
        assertThat(CullingRules.outcome(sent, CullingResult(0, "item-1", status = "unchanged", rev = 131))).isEqualTo(CullingRules.Outcome.Done("item-1", false, 131))
        // Invalid: dropped, never retried, the local value stays.
        assertThat(CullingRules.outcome(sent, CullingResult(0, status = "invalid", code = "label_too_long"))).isEqualTo(CullingRules.Outcome.Dropped("label_too_long"))
        // A replaced item: the result names where the change landed.
        assertThat(CullingRules.outcome(sent, CullingResult(0, "item-2", status = "applied", rev = 152))).isEqualTo(CullingRules.Outcome.Done("item-2", true, 152))
    }

    @Test
    fun aWrittenFieldUpdatesTheCacheUntilThePull() {
        val sent = PendingCullingEntity("item-1", "flag", "\"pick\"", 96, 5000)
        val after = CullingRules.written(record, "item-1", sent, 160, "me")
        assertThat(after.flag).isEqualTo("pick")
        assertThat(after.flagRev).isEqualTo(160)
        assertThat(after.flagAt).isEqualTo(5000)
        assertThat(after.rev).isEqualTo(160)
        assertThat(after.rating).isEqualTo(4)
        val fresh = CullingRules.written(null, "new", PendingCullingEntity("new", "rating", "3", 0, 1), 7, "me")
        assertThat(CullingRules.serverValues(fresh)).isEqualTo(CullValues(rating = 3))
    }

    @Test
    fun changesNameItemsAndKeepTheirOriginalTime() {
        val changes = CullingRules.changes(listOf(PendingCullingEntity("item-1", "rating", "5", 131, 1234)))
        assertThat(changes.single().itemId).isEqualTo("item-1")
        assertThat(changes.single().value).isEqualTo(JsonPrimitive(5))
        assertThat(changes.single().modifiedAt).isEqualTo(1234)
        assertThat(changes.single().baseRev).isEqualTo(131)
    }

    @Test
    fun mapsByAnyMemberFollowingReplacedByElseTheDerivedId() {
        val records = mapOf("v1:jpg" to listOf("old"), "v1:raw" to listOf("old"), "v1:other" to listOf("x"))
        // A phone that holds only the JPEG of a pair finds the pair's record.
        assertThat(CullingMapping.itemFor(listOf("v1:jpg"), "v1:jpg", records, emptyMap())).isEqualTo("old")
        // The RAW appeared later: the record was replaced (twice), the shot maps to the end of the chain.
        assertThat(CullingMapping.itemFor(listOf("v1:jpg"), "v1:jpg", records, mapOf("old" to "mid", "mid" to "new"))).isEqualTo("new")
        // A phone-only shot: the id derived from its main fingerprint.
        assertThat(CullingMapping.itemFor(listOf("v1:lonely"), "v1:lonely", records, emptyMap())).isEqualTo(ItemIds.derive("v1:lonely"))
        assertThat(CullingMapping.follow("a", mapOf("a" to "b", "b" to "a"))).isAnyOf("a", "b")
    }

    @Test
    fun recordsWithoutFieldsAreEmpty() {
        val empty = CullingRules.entity(CullingRecord("i", 3))
        assertThat(CullingRules.serverValues(empty).unmarked).isTrue()
        val pendingNone = listOf(PendingCullingEntity("i", "flag", "\"none\"", 0, 1))
        assertThat(CullingRules.effective(CullingEntity("i", 1, null, null, null, null, null, "pick", 3, 1, "x", null, null, null, null), pendingNone).flag).isNull()
    }
}
