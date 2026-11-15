package dev.hfps.hoard.server.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.MemoryKeyVault
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemFile
import dev.hfps.hoard.server.db.GalleryDatabase
import dev.hfps.hoard.server.db.PendingCullingEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0022 "Culling: dual write": a decision on a mapped SMB shot goes to the server's culling (same item) and to the
 * shot's XMP sidecar, dated alike; the server's NAS import of that sidecar then changes nothing, in either order, and
 * nothing writes back.
 */
@RunWith(AndroidJUnit4::class)
class DualWriteTest {
    private lateinit var server: FakeServer
    private lateinit var db: GalleryDatabase
    private lateinit var engine: SyncEngine
    private var clock = 1_700_000_000_000L

    @Before
    fun setUp() {
        server = FakeServer()
        db = GalleryDatabase.open(ApplicationProvider.getApplicationContext(), null)
        engine = SyncEngine(db, MemoryKeyVault(), DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.2.0"), now = { clock })
        server.putItem(Item(id = "item-1", rev = 0, kind = "photo", mainFingerprint = "v1:a", files = listOf(ItemFile("v1:a", "rendered", "DSC1.JPG", "jpeg", 10, listOf(FileLocation("nas", "f", "Imagens", "DSC1.JPG", 1))))))
        runBlocking {
            engine.pair(PairingCode(server.url, "good", "fake-server"))
            engine.sync()
        }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun redatingTakesOnlyPendingChangesWithTheSidecarsValues() {
        val pending = listOf(
            PendingCullingEntity("i", "rating", "4", 3, 100),
            PendingCullingEntity("i", "flag", "\"pick\"", 3, 100),
            PendingCullingEntity("i", "label", "\"Red\"", 3, 100),
        )
        val out = CullingRules.redated(pending, CullValues(4, "pick"), 250)
        assertThat(out.map { it.field to it.modifiedAt }).containsExactly("rating" to 250L, "flag" to 250L)
        // A newer decision (3 stars) is not the sidecar's: it keeps its own time.
        assertThat(CullingRules.redated(pending, CullValues(3, "pick"), 250).map { it.field }).containsExactly("flag")
    }

    private suspend fun decide(after: CullValues, at: Long) {
        val before = CullingRules.effective(db.culling().get("item-1"), db.culling().pendingFor("item-1"))
        engine.decide("item-1", before, after, at)
    }

    @Test
    fun pushThenNasImportIsANoOp() = runBlocking<Unit> {
        val decidedAt = clock
        decide(CullValues(4, "pick"), decidedAt)
        // The sidecar is written a moment later; its mtime (the SMB server's clock) dates both writes.
        val mtime = decidedAt + 1_500
        engine.redate("item-1", CullValues(4, "pick"), mtime)
        assertThat(db.culling().pendingFor("item-1").map { it.modifiedAt }.toSet()).containsExactly(mtime)
        engine.sync()
        val revAfterPush = server.rev
        assertThat(server.culling["item-1"]!!["rating"]!!.modifiedAt).isEqualTo(mtime)
        // The server's next NAS walk reads the sidecar: same values, same time: no revision.
        assertThat(server.nasImport("item-1", "rating", JsonPrimitive(4), mtime)).isFalse()
        assertThat(server.nasImport("item-1", "flag", JsonPrimitive("pick"), mtime)).isFalse()
        assertThat(server.rev).isEqualTo(revAfterPush)
        // Hoard pulls nothing new and has nothing to push: converged.
        val pushes = server.pushes.size
        engine.sync()
        assertThat(server.pushes.size).isEqualTo(pushes)
        assertThat(db.culling().pendingFor("item-1")).isEmpty()
        assertThat(CullingRules.serverValues(db.culling().get("item-1"))).isEqualTo(CullValues(4, "pick"))
    }

    @Test
    fun nasImportBeforeThePushAlsoConverges() = runBlocking<Unit> {
        // Hoard is offline: the decision waits in the queue while the server walks the share and imports the sidecar.
        val decidedAt = clock
        decide(CullValues(2, "reject"), decidedAt)
        val mtime = decidedAt + 800
        engine.redate("item-1", CullValues(2, "reject"), mtime)
        assertThat(server.nasImport("item-1", "rating", JsonPrimitive(2), mtime)).isTrue()
        assertThat(server.nasImport("item-1", "flag", JsonPrimitive("reject"), mtime)).isTrue()
        val revAfterImport = server.rev
        // Back online: the push finds the same values (unchanged, no revision); the pull brings the same values.
        engine.sync()
        assertThat(server.rev).isEqualTo(revAfterImport)
        assertThat(db.culling().pendingFor("item-1")).isEmpty()
        assertThat(CullingRules.serverValues(db.culling().get("item-1"))).isEqualTo(CullValues(2, "reject"))
        // And a second walk of the unchanged sidecar is never read again (same size and mtime): nothing more happens.
        engine.sync()
        assertThat(server.rev).isEqualTo(revAfterImport)
    }

    @Test
    fun aNewerDecisionElsewhereWinsOverAnOldSidecar() = runBlocking<Unit> {
        decide(CullValues(3, null), clock)
        engine.redate("item-1", CullValues(3, null), clock + 500)
        engine.sync()
        // Reveal rates 5 later; then the server reads Hoard's (older) sidecar for the first time: it loses.
        server.write("item-1", "rating", JsonPrimitive(5), clock + 60_000, "reveal")
        assertThat(server.nasImport("item-1", "rating", JsonPrimitive(3), clock + 500)).isFalse()
        engine.sync()
        assertThat(CullingRules.serverValues(db.culling().get("item-1")).rating).isEqualTo(5)
        // Hoard never writes a pulled value to a sidecar (ADR 0016 rule 4), so nothing loops.
        assertThat(db.culling().pendingFor("item-1")).isEmpty()
    }
}
