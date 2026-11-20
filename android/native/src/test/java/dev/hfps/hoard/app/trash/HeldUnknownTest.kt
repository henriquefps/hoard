package dev.hfps.hoard.app.trash

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** ADR 0025 Amendment (2.7.1): restore and the auto-empty preview sync the held state like Empty does. */
class HeldUnknownTest {
    @Test
    fun aRefreshSyncsFirstAndIsUnknownOnlyWhenTheSyncFails() = runBlocking {
        var syncs = 0
        assertThat(heldUnknown(true) { syncs++; true }).isFalse()
        assertThat(syncs).isEqualTo(1)
        assertThat(heldUnknown(true) { syncs++; false }).isTrue()
        assertThat(heldUnknown(true) { syncs++; error("offline") }).isTrue()
        assertThat(syncs).isEqualTo(3)
    }

    @Test
    fun noRefreshNeverSyncs() = runBlocking {
        var syncs = 0
        assertThat(heldUnknown(false) { syncs++; false }).isFalse()
        assertThat(syncs).isEqualTo(0)
    }

    @Test
    fun anUnknownStateHoldsEveryShareRowSoARestoreWaits() = runBlocking {
        val unknown = TrashCompressionState(CompressedTrash.held(emptyList(), emptyList(), unknown = heldUnknown(true) { false }), emptyList())
        val row = TrashSamples.row(TrashSamples.nas, "Imagens", "2026-11-14", "Imagens/a.mp4", 10)
        val split = TrashSplit.of(listOf(row), unknown)
        assertThat(split.held).containsExactly(row)
        assertThat(split.plain).isEmpty()
    }
}
