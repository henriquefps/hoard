package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferJobTest {
    @Test
    fun `jobs survive a JSON round trip`() {
        val job = TransferJob(
            transferId = "t1",
            machineId = "m1",
            addresses = listOf(TransferAddress("192.168.1.155", 445), TransferAddress("nas.ts.net", 4450)),
            expectedServerGuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
            share = "media",
            remotePath = "Movies/a.mkv",
            destTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            destName = "a (1).mkv",
            destUri = "content://x/document/1",
            totalBytes = 10,
            bytesDone = 4,
            state = TransferStates.RETRYING,
            errorCode = ErrorCode.TIMEOUT,
            errorMessage = "slow",
            activeHost = "nas.ts.net",
            startedAt = "2026-11-08T21:14:03.120Z",
            remoteModifiedMillis = 123L,
            started = true,
            createdDocument = true,
        )
        assertEquals(job, TransferJob.fromJson(job.toJson()))
        val minimal = job.copy(destUri = null, errorCode = null, errorMessage = null, activeHost = null, remoteModifiedMillis = null)
        assertEquals(minimal, TransferJob.fromJson(minimal.toJson()))
    }
}
