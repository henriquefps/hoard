package dev.hfps.hoard.smb.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationRulesTest {

    private data class Doc(val name: String, val size: Long)

    private val folder = listOf(Doc("movie.mkv", 4096), Doc("notes.md", 10))

    private fun plan(wanted: String, conflict: String, resume: Long = 0) =
        DestinationRules.plan(wanted, conflict, resume, folder, { it.name }, { it.size })

    @Test
    fun `UI retry appends to the partial file when its size matches`() {
        // TransferService.retry(): same transferId, destName = partial file, overwrite, resumeFromBytes.
        assertEquals(DestinationRules.Decision.Resume(folder[0], 4096L), plan("movie.mkv", "overwrite", resume = 4096))
    }

    @Test
    fun `UI retry truncates and restarts when the partial size differs`() {
        assertEquals(DestinationRules.Decision.Resume(folder[0], 0L), plan("movie.mkv", "overwrite", resume = 5000))
        assertEquals(DestinationRules.Decision.Resume(folder[0], 0L), plan("movie.mkv", "overwrite", resume = 1000))
    }

    @Test
    fun `resume never renames or skips, whatever the policy`() {
        assertEquals(DestinationRules.Decision.Resume(folder[0], 4096L), plan("movie.mkv", "rename", resume = 4096))
        assertEquals(DestinationRules.Decision.Resume(folder[0], 4096L), plan("movie.mkv", "skip", resume = 4096))
    }

    @Test
    fun `resume of a file that is gone starts a new file`() {
        assertEquals(DestinationRules.Decision.Create("other.mkv"), plan("other.mkv", "overwrite", resume = 4096))
    }

    @Test
    fun `conflict policies for a fresh download`() {
        assertEquals(DestinationRules.Decision.Create("movie (1).mkv"), plan("movie.mkv", "rename"))
        assertEquals(DestinationRules.Decision.Skip(folder[0]), plan("movie.mkv", "skip"))
        assertEquals(DestinationRules.Decision.Overwrite(folder[0]), plan("movie.mkv", "overwrite"))
        assertEquals(DestinationRules.Decision.Create("new.mkv"), plan("new.mkv", "skip"))
    }

    @Test
    fun `a terminal transferId is accepted as a fresh job, a live one is not`() {
        val job = TransferJob(
            transferId = "t1", machineId = "m", addresses = listOf(TransferAddress("h", 445)), expectedServerGuid = "g",
            share = "s", remotePath = "a", destTreeUri = "tree", destName = "a", destUri = null, startedAt = "now",
        )
        assertTrue(DestinationRules.acceptsNew(null))
        for (state in TransferStates.TERMINAL) assertTrue(state, DestinationRules.acceptsNew(job.copy(state = state)))
        for (state in listOf(TransferStates.QUEUED, TransferStates.RUNNING, TransferStates.RETRYING)) {
            assertFalse(state, DestinationRules.acceptsNew(job.copy(state = state)))
        }
    }
}
