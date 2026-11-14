package dev.hfps.hoard.app.transfers

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.MachineEntity
import dev.hfps.hoard.data.TransferEntity
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.StartTransferRequest
import dev.hfps.hoard.smb.transfer.TransferAddress
import dev.hfps.hoard.smb.transfer.TransferJob
import dev.hfps.hoard.smb.transfer.TransferProgress
import dev.hfps.hoard.smb.transfer.TransferStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The transfers bookkeeping (ports of src/features/transfers/service.test.ts) over a real Room database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TransferServiceTest {
    private lateinit var db: HoardDatabase

    @Before fun open() {
        db = HoardDatabase.open(ApplicationProvider.getApplicationContext(), name = null)
    }

    @After fun close() = db.close()

    class FakeNative : NativeTransfers {
        val started = ArrayList<StartTransferRequest>()
        val acknowledged = ArrayList<String>()
        val cancelled = ArrayList<String>()
        var answers = ArrayList<Pair<String?, Map<Int, String>>>()
        var jobs = ArrayList<TransferJob>()
        var failStart: Throwable? = null
        val listeners = ArrayList<(TransferProgress) -> Unit>()

        override fun start(request: StartTransferRequest) {
            failStart?.let { throw it }
            started += request
        }
        override fun cancel(transferId: String) {
            cancelled += transferId
        }
        override fun resolveConflicts(transferId: String, all: String?, perItem: Map<Int, String>) {
            answers += all to perItem
        }
        override fun reported(): List<TransferJob> = jobs
        override fun acknowledge(transferIds: Collection<String>) {
            acknowledged += transferIds
        }
        override fun addListener(listener: (TransferProgress) -> Unit) {
            listeners += listener
        }
        override fun removeListener(listener: (TransferProgress) -> Unit) {
            listeners -= listener
        }
        override fun job(transferId: String): TransferJob? = jobs.firstOrNull { it.transferId == transferId }
    }

    private val smb = Endpoint.Smb("m1", listOf(TransferAddress("10.0.2.2", 4460)), "guid", "media")

    private fun TestScope.service(native: FakeNative) = TransferService(
        scope = backgroundScope,
        native = native,
        dao = db.transfers(),
        resolve = { place ->
            when (place.locationId) {
                "device" -> Endpoint.Device
                "m1" -> smb.copy(share = place.share.orEmpty())
                TREE_LOCATION_ID -> Endpoint.Tree(place.treeUri!!)
                INCOMING_LOCATION_ID -> Endpoint.Incoming(place.shareId!!)
                else -> throw HoardSmbException(ErrorCode.NOT_FOUND, "Machine not found")
            }
        },
        now = { testScheduler.currentTime + 1_700_000_000_000 },
    )

    private suspend fun machine() = db.machines().insert(MachineEntity("m1", "NAS", null, null, "u", null, "guid", 0, "t"))

    private val request = TransferRequest(
        op = "copy",
        source = TransferPlace("m1", "media"),
        paths = listOf("Photos/a.jpg", "Photos/b.jpg"),
        name = "a.jpg",
        size = 20,
        itemsTotal = 2,
        destination = FolderPlace(TransferPlace("device"), "Download"),
        conflict = "ask",
    )

    @Test fun recordsTheRowThenStartsTheNativeJobWithTheSameId() = runTest {
        machine()
        val native = FakeNative()
        val id = service(native).transfer(request)
        val row = db.transfers().get(id)!!
        assertThat(row.sourceMachineId).isEqualTo("m1")
        assertThat(row.destMachineId).isNull()
        assertThat(TransferService.sourcePaths(row)).containsExactly("Photos/a.jpg", "Photos/b.jpg").inOrder()
        assertThat(row.state).isEqualTo(TransferStates.QUEUED)
        val started = native.started.single()
        assertThat(started.transferId).isEqualTo(id)
        assertThat(started.sources.map { it.endpoint }.distinct()).containsExactly(smb)
        assertThat(started.destination).isEqualTo(Endpoint.Device)
        assertThat(started.destPath).isEqualTo("Download")
        assertThat(started.conflict).isEqualTo("ask")
        assertThat(started.resume).isFalse()
    }

    @Test fun aJobThatCannotStartFailsItsRowWithTheCode() = runTest {
        machine()
        val native = FakeNative().apply { failStart = HoardSmbException(ErrorCode.UNREACHABLE, "down") }
        val error = runCatching { service(native).transfer(request) }.exceptionOrNull()
        assertThat(error).isInstanceOf(HoardSmbException::class.java)
        val row = db.transfers().all().single()
        assertThat(row.state).isEqualTo(TransferStates.FAILED)
        assertThat(row.errorCode).isEqualTo("UNREACHABLE")
        assertThat(row.finishedAt).isNotNull()
    }

    @Test fun progressIsLiveAtFullRateAndTheLastOfABurstReachesTheDatabase() = runTest {
        machine()
        val native = FakeNative()
        val service = service(native)
        val id = service.transfer(request)
        service.onProgress(id, LiveTransfer(TransferStates.RUNNING, bytesDone = 1, totalBytes = 20, bytesPerSecond = 5))
        service.onProgress(id, LiveTransfer(TransferStates.RUNNING, bytesDone = 5, totalBytes = 20))
        assertThat(service.live.value[id]!!.bytesDone).isEqualTo(5)
        // The speed carries over while running when an event has none.
        assertThat(service.live.value[id]!!.bytesPerSecond).isEqualTo(5)
        // (Not asserted in between: runTest skips virtual time while Room answers on its own thread.)
        advanceTimeBy(1_100)
        runCurrent()
        assertThat(db.transfers().get(id)!!.bytesDone).isEqualTo(5)
        service.onProgress(id, LiveTransfer(TransferStates.COMPLETED, bytesDone = 20, totalBytes = 20, itemsTotal = 2, itemsDone = 2))
        val row = db.transfers().get(id)!!
        assertThat(row.state).isEqualTo(TransferStates.COMPLETED)
        assertThat(row.itemsDone).isEqualTo(2)
        assertThat(native.acknowledged).containsExactly(id)
        val view = service.transfers.first().single()
        assertThat(view.active).isFalse()
        assertThat(view.bytesDone).isEqualTo(20)
    }

    @Test fun reconcileFailsRowsNativeLostAndLeavesThisRunsRows() = runTest {
        val lost = TransferEntity(
            "old", "copy", "device", null, "", "[\"a\"]", "device", null, "", "x", null, "a", "rename", 10, 4, 1, 0, 0, 0,
            TransferStates.RUNNING, null, null, "2020-01-01T00:00:00Z", null,
        )
        db.transfers().insert(lost)
        db.transfers().insert(lost.copy(id = "fresh", createdAt = "2099-01-01T00:00:00Z"))
        val service = service(FakeNative())
        service.reconcile()
        val old = db.transfers().get("old")!!
        assertThat(old.state).isEqualTo(TransferStates.FAILED)
        assertThat(old.error).isEqualTo(INTERRUPTED)
        assertThat(old.bytesDone).isEqualTo(4)
        assertThat(db.transfers().get("fresh")!!.state).isEqualTo(TransferStates.RUNNING)
    }

    @Test fun retryRunsTheSameIdAgainWithResume() = runTest {
        machine()
        val native = FakeNative()
        val service = service(native)
        val id = service.transfer(request)
        service.onProgress(id, LiveTransfer(TransferStates.FAILED, bytesDone = 7, totalBytes = 20, errorCode = "TIMEOUT"))
        service.retry(db.transfers().get(id)!!)
        val again = native.started.last()
        assertThat(again.transferId).isEqualTo(id)
        assertThat(again.resume).isTrue()
        assertThat(again.sources.map { it.path }).containsExactly("Photos/a.jpg", "Photos/b.jpg").inOrder()
        assertThat(db.transfers().get(id)!!.state).isEqualTo(TransferStates.QUEUED)
        assertThat(db.transfers().get(id)!!.bytesDone).isEqualTo(7)
    }

    @Test fun incomingAndTreePlacesRoundTripThroughTheRow() = runTest {
        val native = FakeNative()
        val id = service(native).transfer(
            request.copy(
                source = TransferPlace(INCOMING_LOCATION_ID, shareId = "share-1"),
                paths = listOf("0"),
                destination = FolderPlace(TransferPlace(TREE_LOCATION_ID, treeUri = "content://tree/x"), ""),
            ),
        )
        val row = db.transfers().get(id)!!
        assertThat(TransferService.placeOf(row, source = true).shareId).isEqualTo("share-1")
        assertThat(TransferService.placeOf(row, source = false).treeUri).isEqualTo("content://tree/x")
        assertThat(native.started.single().sources.single().endpoint).isEqualTo(Endpoint.Incoming("share-1"))
    }

    @Test fun cancelAndConflictAnswersGoToNative() = runTest {
        val native = FakeNative()
        val service = service(native)
        service.cancel("t1")
        service.resolveConflicts("t1", "rename")
        service.resolveConflicts("t1", null, mapOf(3 to "skip"))
        assertThat(native.cancelled).containsExactly("t1")
        assertThat(native.answers).containsExactly("rename" to emptyMap<Int, String>(), null to mapOf(3 to "skip")).inOrder()
    }
}
