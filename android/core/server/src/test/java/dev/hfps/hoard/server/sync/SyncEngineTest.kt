package dev.hfps.hoard.server.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.MemoryKeyVault
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemEdit
import dev.hfps.hoard.server.api.ItemFile
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.db.GalleryDatabase
import dev.hfps.hoard.server.preview.HttpRangeSource
import dev.hfps.hoard.server.preview.PreviewCache
import dev.hfps.hoard.server.preview.PreviewSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The client against a fake hoard-server: pairing, feed paging, the culling queue and its conflicts, mapping. */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var server: FakeServer
    private lateinit var db: GalleryDatabase
    private val vault = MemoryKeyVault()
    private var clock = 10_000L
    private lateinit var engine: SyncEngine

    private fun item(n: Int, raw: Boolean = false, time: Long = 1_700_000_000_000L + n * 1000L): Item {
        val jpg = ItemFile("v1:" + "%064x".format(n), "rendered", "DSC%05d.JPG".format(n), "jpeg", 1000, listOf(FileLocation("nas", "f1", "photos", "2024/Trip/DSC%05d.JPG".format(n), 1)))
        val files = if (raw) listOf(ItemFile("v1:" + "%064x".format(n + 100000), "raw", "DSC%05d.ARW".format(n), "tiff", 9000, jpg.locations), jpg) else listOf(jpg)
        return Item(id = "item-$n", rev = 0, kind = if (raw) "pair" else "photo", mainFingerprint = files.first().fingerprint, sortTime = time, files = files, make = "SONY", model = "ILCE-7M4")
    }

    @Before
    fun setUp() {
        server = FakeServer()
        db = GalleryDatabase.open(ApplicationProvider.getApplicationContext(), null)
        engine = SyncEngine(db, vault, DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.0.0"), now = { clock }, pageSize = 7)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun pair() = runBlocking { engine.pair(PairingCode(server.url, "good", "fake-server")) }

    @Test
    fun pairsKeepsTheKeyInTheVaultAndOffersTheModelMatch() = runBlocking<Unit> {
        val paired = pair()
        assertThat(vault.get()).isEqualTo(server.key)
        assertThat(paired.candidates.single().physicalDeviceId).isEqualTo("phys-reveal")
        val account = db.account().get()!!
        assertThat(account.deviceId).isEqualTo("dev-hoard")
        assertThat(account.app).isEqualTo("hoard-debug")
        assertThat(account.has("culling")).isTrue()
        assertThat(engine.pendingClaim()).hasSize(1)
        // "Same phone": claimed, the question is not asked again.
        val view = engine.claim("phys-reveal")
        assertThat(view!!.physicalDevice!!.id).isEqualTo("phys-reveal")
        assertThat(db.account().get()!!.physicalDeviceId).isEqualTo("phys-reveal")
        assertThat(engine.pendingClaim()).isEmpty()
    }

    @Test
    fun refusedTokenAndAnotherServerAreErrors() {
        val refused = runCatching { runBlocking { engine.pair(PairingCode(server.url, "bad", null)) } }.exceptionOrNull()
        assertThat((refused as PairingException).reason).isEqualTo(PairingException.Reason.TokenRefused)
        val other = runCatching { runBlocking { engine.pair(PairingCode(server.url, "good", "someone-else")) } }.exceptionOrNull()
        assertThat((other as PairingException).reason).isEqualTo(PairingException.Reason.OtherServer)
        assertThat(runBlocking { db.account().get() }).isNull()
    }

    @Test
    fun pullsTheWholeFeedPageByPageThenIncrementally() = runBlocking<Unit> {
        for (n in 1..20) server.putItem(item(n, raw = n % 5 == 0))
        server.write("item-3", "rating", JsonPrimitive(4), 500, "reveal")
        pair()
        assertThat(engine.sync()).isTrue()
        assertThat(server.pageCalls).isEqualTo(3) // 21 rows, 7 a page
        assertThat(db.items().count()).isEqualTo(20)
        assertThat(db.account().get()!!.caughtUp).isTrue()
        assertThat(server.declared).containsExactly("culling") // ADR 0016 rule 6, once
        val pair = db.items().get("item-5")!!
        assertThat(pair.kind).isEqualTo("pair")
        assertThat(pair.name).isEqualTo("DSC00005.ARW")
        assertThat(pair.folder).isEqualTo("photos/2024/Trip")
        assertThat(pair.hasRaw).isTrue()
        assertThat(db.culling().get("item-3")!!.rating).isEqualTo(4)
        // Both member files are known: a phone with only the JPEG of the pair finds it.
        assertThat(db.items().filesFor(listOf("v1:" + "%064x".format(5))).single().itemId).isEqualTo("item-5")

        // Incremental: one new item, one deleted, an edit (ADR 0020).
        server.putItem(item(21))
        server.tombstone("item-1", null)
        server.putItem(item(2).copy(edits = listOf(ItemEdit("v-1", primary = true, updatedAt = 99))))
        server.pageCalls = 0
        engine.sync()
        assertThat(server.pageCalls).isEqualTo(1)
        assertThat(db.items().count()).isEqualTo(20)
        assertThat(db.items().get("item-1")).isNull()
        assertThat(db.items().get("item-2")!!.editPrimary).isEqualTo("v-1")
        assertThat(server.declared).hasSize(1)
    }

    @Test
    fun aRefusedCursorPullsFromTheStart() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(item(n))
        pair()
        engine.sync()
        server.putItem(item(4))
        server.rejectNextCursor = true
        assertThat(engine.sync()).isTrue()
        assertThat(db.items().count()).isEqualTo(4)
    }

    @Test
    fun decisionsQueueOfflineAndPushWithTheirOwnTime() = runBlocking<Unit> {
        server.putItem(item(1))
        pair()
        engine.sync()
        // Two decisions on the same field before a push collapse to the last one, with its time.
        engine.decide("item-1", CullValues(), CullValues(flag = "pick"), at = 1000)
        engine.decide("item-1", CullValues(flag = "pick"), CullValues(flag = "reject", rating = 3), at = 2000)
        assertThat(db.culling().pending(10).map { it.field to it.modifiedAt }).containsExactly("flag" to 2000L, "rating" to 2000L)
        clock = 50_000
        engine.sync()
        val push = server.pushes.single().changes
        assertThat(push.map { it.field to it.modifiedAt }).containsExactly("flag" to 2000L, "rating" to 2000L)
        assertThat(push.all { it.baseRev == 0L }).isTrue()
        assertThat(db.culling().pending(10)).isEmpty()
        assertThat(CullingRules.serverValues(db.culling().get("item-1"))).isEqualTo(CullValues(rating = 3, flag = "reject"))
    }

    @Test
    fun conflictsAreDecidedByTheServerAndThePullBringsTheWinner() = runBlocking<Unit> {
        server.putItem(item(1))
        server.write("item-1", "rating", JsonPrimitive(2), 1000, "reveal")
        pair()
        engine.sync()
        // Offline: Hoard rates 5 at t=1500 having seen rev of rating 2. Meanwhile Reveal rates 4 at t=3000 and picks
        // nothing; Hoard also picks at t=1600 (a field Reveal never touched: both survive, case 10).
        engine.decide("item-1", CullValues(rating = 2), CullValues(rating = 5), at = 1500)
        engine.decide("item-1", CullValues(rating = 5), CullValues(rating = 5, flag = "pick"), at = 1600)
        server.write("item-1", "rating", JsonPrimitive(4), 3000, "reveal")
        // While pending, a pull does not override the local value.
        assertThat(CullingRules.effective(db.culling().get("item-1"), db.culling().pendingFor("item-1"))).isEqualTo(CullValues(rating = 5, flag = "pick"))
        engine.sync()
        // Reveal's later rating wins; Hoard's pick survives.
        assertThat(CullingRules.effective(db.culling().get("item-1"), db.culling().pendingFor("item-1"))).isEqualTo(CullValues(rating = 4, flag = "pick"))
        assertThat(db.culling().pending(10)).isEmpty()
        // The reverse: Hoard's newer decision wins over an older one written meanwhile.
        engine.decide("item-1", CullValues(rating = 4, flag = "pick"), CullValues(rating = 1, flag = "pick"), at = 9000)
        server.write("item-1", "rating", JsonPrimitive(3), 8000, "reveal")
        engine.sync()
        assertThat(db.culling().get("item-1")!!.rating).isEqualTo(1)
    }

    @Test
    fun invalidChangesAreDroppedNotRetried() = runBlocking<Unit> {
        server.putItem(item(1))
        pair()
        engine.sync()
        engine.decide("item-1", CullValues(), CullValues(label = "x".repeat(80)), at = 1000)
        engine.sync()
        assertThat(db.culling().pending(10)).isEmpty()
        engine.sync()
        assertThat(server.pushes).hasSize(1)
    }

    @Test
    fun aRefusedPushDoesNotStopThePullAndIsRetriedAfterItsBackoff() = runBlocking<Unit> {
        server.putItem(item(1))
        pair()
        engine.sync()
        engine.decide("item-1", CullValues(), CullValues(rating = 4), at = 1000)
        server.cullingFailure = 503
        server.putItem(item(2))
        // The push is refused; the pull still brings item-2 and the sync succeeds.
        assertThat(engine.sync()).isTrue()
        assertThat(db.items().count()).isEqualTo(2)
        assertThat(engine.status.value.pushError).isEqualTo("unavailable")
        val waiting = db.culling().pendingFor("item-1").single()
        assertThat(waiting.attempts).isEqualTo(1)
        // Backed off: the second push of that sync and the next sync within 30 s do not send it again.
        assertThat(server.cullingCalls).isEqualTo(1)
        clock += 10_000
        engine.sync()
        assertThat(server.cullingCalls).isEqualTo(1)
        // After the backoff, and with the server answering again, it goes out with its own time.
        server.cullingFailure = null
        clock += SyncEngine.backoffMs(1)
        engine.sync()
        assertThat(db.culling().pendingFor("item-1")).isEmpty()
        assertThat(server.pushes.last().changes.single().modifiedAt).isEqualTo(1000L)
        // A new decision on a backed-off field is not held back by the old refusal.
        server.cullingFailure = 500
        engine.decide("item-1", CullValues(rating = 4), CullValues(rating = 2), at = clock)
        engine.sync()
        server.cullingFailure = null
        engine.decide("item-1", CullValues(rating = 2), CullValues(rating = 1), at = clock + 1)
        engine.sync()
        assertThat(db.culling().pendingFor("item-1")).isEmpty()
    }

    @Test
    fun aPushAnsweredWithoutResultsIsSentOncePerPass() = runBlocking<Unit> {
        server.putItem(item(1))
        pair()
        engine.sync()
        engine.decide("item-1", CullValues(), CullValues(rating = 3, flag = "pick"), at = 5000)
        server.cullingEmptyResults = true
        assertThat(engine.sync()).isTrue()
        // One POST; nothing answered, so everything is backed off, not re-sent.
        assertThat(server.cullingCalls).isEqualTo(1)
        assertThat(db.culling().pendingFor("item-1").map { it.attempts }).containsExactly(1, 1)
    }

    @Test
    fun aFeedThatNeverMovesItsCursorEndsThePass() = runBlocking<Unit> {
        pair()
        server.stuckCursor = true
        assertThat(engine.sync()).isTrue()
        assertThat(server.pageCalls).isAtMost(2)
        assertThat(db.account().get()!!.caughtUp).isFalse()
    }

    @Test
    fun cullingIsDeclaredBeforeTheFirstPushAndRetriedWhenThatFailed() = runBlocking<Unit> {
        server.putItem(item(1))
        server.deviceFailures = 1
        pair()
        engine.decide("item-1", CullValues(), CullValues(flag = "pick"), at = 1000)
        // The declaration fails: nothing is pushed (a push would re-enable the server's bridge), the pull goes on.
        assertThat(engine.sync()).isTrue()
        assertThat(server.declared).isEmpty()
        assertThat(server.cullingCalls).isEqualTo(0)
        assertThat(db.items().count()).isEqualTo(1)
        assertThat(db.account().get()!!.cullingDeclared).isFalse()
        // Next sync: declared first, then pushed.
        assertThat(engine.sync()).isTrue()
        assertThat(server.declared).containsExactly("culling")
        assertThat(server.cullingCalls).isEqualTo(1)
        assertThat(db.culling().pendingFor("item-1")).isEmpty()
    }

    @Test
    fun movingAQueueKeepsTheNewerChangeOfEachField() = runBlocking<Unit> {
        // The replaced item's rating is older than the replacement's; its flag is newer; its label has no rival.
        db.culling().putPending(
            listOf(
                dev.hfps.hoard.server.db.PendingCullingEntity("old", "rating", "2", baseRev = 7, modifiedAt = 1000, attempts = 3),
                dev.hfps.hoard.server.db.PendingCullingEntity("old", "flag", "\"reject\"", baseRev = 7, modifiedAt = 5000),
                dev.hfps.hoard.server.db.PendingCullingEntity("old", "label", "\"Red\"", baseRev = 7, modifiedAt = 1500),
                dev.hfps.hoard.server.db.PendingCullingEntity("new", "rating", "5", baseRev = 2, modifiedAt = 3000),
                dev.hfps.hoard.server.db.PendingCullingEntity("new", "flag", "\"pick\"", baseRev = 2, modifiedAt = 4000),
            ),
        )
        db.culling().movePending("old", "new")
        assertThat(db.culling().pendingFor("old")).isEmpty()
        val moved = db.culling().pendingFor("new").associateBy { it.field }
        assertThat(moved["rating"]!!.value).isEqualTo("5")
        assertThat(moved["rating"]!!.modifiedAt).isEqualTo(3000L)
        assertThat(moved["flag"]!!.value).isEqualTo("\"reject\"")
        assertThat(moved["flag"]!!.modifiedAt).isEqualTo(5000L)
        assertThat(moved["flag"]!!.baseRev).isEqualTo(2L)
        assertThat(moved["label"]!!.value).isEqualTo("\"Red\"")
        assertThat(moved["label"]!!.baseRev).isEqualTo(0L)
        assertThat(moved["label"]!!.attempts).isEqualTo(0)
    }

    @Test
    fun aReplacedItemMovesItsRecordAndQueue() = runBlocking<Unit> {
        server.putItem(item(1))
        server.write("item-1", "rating", JsonPrimitive(3), 1000, "reveal")
        pair()
        engine.sync()
        engine.decide("item-1", CullValues(rating = 3), CullValues(rating = 3, flag = "pick"), at = 2000)
        // The RAW appeared: a new item takes the shot over before Hoard pushed.
        server.putItem(item(1, raw = true).copy(id = "item-1b"))
        server.tombstone("item-1", "item-1b")
        // Pull first (as if the push had failed): the queue moves to the new id.
        engine.sync()
        assertThat(db.culling().get("item-1")).isNull()
        assertThat(db.culling().get("item-1b")!!.rating).isEqualTo(3)
        assertThat(db.culling().get("item-1b")!!.flag).isEqualTo("pick")
        assertThat(server.pushes.last().changes.single().itemId).isAnyOf("item-1", "item-1b")
        val records = db.culling().recordsWith(listOf("v1:" + "%064x".format(1)))
        assertThat(records).containsExactly("item-1b")
    }

    @Test
    fun clearingTheCacheKeepsTheQueueAndUnpairingForgetsEverything() = runBlocking<Unit> {
        server.putItem(item(1))
        pair()
        engine.sync()
        engine.decide("item-1", CullValues(), CullValues(rating = 2), at = 1)
        engine.clearCache()
        assertThat(db.items().count()).isEqualTo(0)
        assertThat(db.culling().pending(10)).hasSize(1)
        assertThat(db.account().get()!!.cursor).isNull()
        engine.unpair()
        assertThat(vault.get()).isNull()
        assertThat(db.account().get()).isNull()
        assertThat(db.culling().pending(10)).isEmpty()
        assertThat(engine.sync()).isFalse()
    }

    @Test
    fun aRevokedKeyIsReportedAsUnauthorized() = runBlocking<Unit> {
        pair()
        vault.set("rvk1_revoked_key")
        assertThat(engine.sync()).isFalse()
        assertThat(db.account().get()!!.lastError).isEqualTo(SyncEngine.ERROR_UNAUTHORIZED)
        assertThat(engine.status.first().error).isEqualTo(SyncEngine.ERROR_UNAUTHORIZED)
    }

    @Test
    fun originalsAreReadByRangeAndPreviewsWaitWhilePending() = runBlocking<Unit> {
        pair()
        val bytes = ByteArray(1_000_000) { (it % 251).toByte() }
        server.originals["v1:abc"] = bytes
        val api = engine.api()!!
        assertThat(api.originalSize("v1:abc")).isEqualTo(bytes.size.toLong())
        val source = HttpRangeSource(api, "v1:abc", bytes.size.toLong(), blockSize = 64 * 1024)
        val buf = ByteArray(10)
        assertThat(source.read(999_995, buf, 0, 10)).isEqualTo(5)
        assertThat(buf.copyOf(5).toList()).isEqualTo(bytes.copyOfRange(999_995, 1_000_000).toList())
        source.read(10, buf, 0, 10)
        source.read(20, buf, 0, 10)
        assertThat(source.requests).isEqualTo(2) // one block for the tail, one for the head
        assertThat(source.read(2_000_000, buf, 0, 1)).isEqualTo(-1)

        val dir = File(System.getProperty("java.io.tmpdir"), "previews-" + System.nanoTime())
        val cache = PreviewCache(dir, maxBytes = 1_000_000) { engine.api() }
        val file = cache.get("item-9", PreviewSize.Thumb, edit = "primary", version = "1")
        assertThat(file!!.readText()).contains("edit=primary")
        assertThat(cache.cached("item-9", PreviewSize.Thumb, "primary", "1")).isNotNull()
        cache.maxBytes = 1
        cache.trim()
        assertThat(cache.cached("item-9", PreviewSize.Thumb, "primary", "1")).isNull()
        dir.deleteRecursively()
    }

    @Test
    fun openedOriginalsAreKeptWithinTheirBudget() = runBlocking<Unit> {
        pair()
        val names = listOf("a", "b", "c", "d", "e")
        names.forEachIndexed { i, n -> server.originals["v1:$n"] = ByteArray(240_000) { (it % (i + 3)).toByte() } }
        val dir = File(System.getProperty("java.io.tmpdir"), "originals-" + System.nanoTime())
        val cache = dev.hfps.hoard.server.preview.OriginalCache(dir, maxBytes = 1_000_000) { engine.api() }
        val kept = cache.keep("v1:a", 240_000)!!
        assertThat(kept.readBytes().toList()).isEqualTo(server.originals["v1:a"]!!.toList())
        // More than a quarter of the budget: never kept.
        server.originals["v1:big"] = ByteArray(300_000)
        assertThat(cache.keep("v1:big", 300_000)).isNull()
        for (n in names.drop(1)) {
            Thread.sleep(20)
            assertThat(cache.keep("v1:$n", 240_000)).isNotNull()
        }
        // Five kept = 1.2 MB > 1 MB: the least recently opened go first, down to 90 %.
        assertThat(cache.cached("v1:a", 240_000)).isNull()
        assertThat(cache.cached("v1:b", 240_000)).isNull()
        assertThat(cache.cached("v1:e", 240_000)).isNotNull()
        assertThat(cache.bytes()).isAtMost(900_000)
        dir.deleteRecursively()
    }

    @Test
    fun anotherAddressOfTheSameServerReplacesThePairedOne() = runBlocking<Unit> {
        pair()
        // A public plain-HTTP address is refused; an unreachable one too; the same server elsewhere is taken.
        assertThat(engine.changeAddress("http://8.8.8.8:3071")).isFalse()
        assertThat(engine.changeAddress("http://127.0.0.1:1")).isFalse()
        val other = FakeServer()
        try {
            assertThat(engine.changeAddress(other.url.replace("127.0.0.1", "localhost") + "/")).isTrue()
            assertThat(db.account().get()!!.url).isEqualTo(other.url.replace("127.0.0.1", "localhost"))
            assertThat(engine.sync()).isTrue()
        } finally {
            other.close()
        }
    }

    @Test
    fun aPlainHttpTailnetNameMovesToHttpsWhenTheServerAnswersThere() = runBlocking<Unit> {
        // `tailscale serve` in front of the server: https://<name> answers with the same server id.
        val asked = ArrayList<String>()
        val tailnet = SyncEngine(db, vault, DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.0.0"), apiFor = { url, key ->
            asked += url
            dev.hfps.hoard.server.api.ServerApi(server.url, key)
        }, now = { clock })
        tailnet.pair(PairingCode("http://box.tail1234.ts.net:3070", "good", "fake-server"))
        assertThat(tailnet.sync()).isTrue()
        assertThat(db.account().get()!!.url).isEqualTo("https://box.tail1234.ts.net")
        assertThat(asked).contains("https://box.tail1234.ts.net")
    }

    @Test
    fun aPlainHttpTailnetNameStaysWhenHttpsDoesNotAnswerAndIsAskedAgainADayLater() = runBlocking<Unit> {
        val asked = ArrayList<String>()
        val tailnet = SyncEngine(db, vault, DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.0.0"), apiFor = { url, key ->
            asked += url
            dev.hfps.hoard.server.api.ServerApi(if (url.startsWith("https://")) "http://127.0.0.1:1" else server.url, key)
        }, now = { clock })
        tailnet.pair(PairingCode("http://box.tail1234.ts.net:3070", "good", "fake-server"))
        assertThat(tailnet.sync()).isTrue()
        assertThat(tailnet.sync()).isTrue()
        assertThat(db.account().get()!!.url).isEqualTo("http://box.tail1234.ts.net:3070")
        assertThat(asked.count { it.startsWith("https://") }).isEqualTo(1)
        clock += SyncEngine.HTTPS_RETRY_MS
        tailnet.sync()
        assertThat(asked.count { it.startsWith("https://") }).isEqualTo(2)
    }

    // --- ADR 0023, phase A4: trashed items and the walk request ---

    @Test
    fun trashedRestoredAndPurgedItemsFromTheFeed() = runBlocking<Unit> {
        server.features = server.features + "trash"
        pair()
        server.putItem(item(1))
        server.putItem(item(2))
        engine.sync()
        assertThat(server.lastInclude).isEqualTo("items,culling,trash")
        assertThat(db.items().count()).isEqualTo(2)
        // Trashed on another device: kept as a row with its time, out of the grid.
        server.putItem(item(1).copy(trashed = dev.hfps.hoard.server.api.ItemTrash(since = 5_000)))
        engine.sync()
        assertThat(db.items().count()).isEqualTo(1)
        assertThat(db.items().observeRows().first().map { it.id }).containsExactly("item-2")
        assertThat(db.items().trashed().single().let { it.id to it.trashedSince }).isEqualTo("item-1" to 5_000L)
        // Restored: live again, same id.
        server.putItem(item(1))
        engine.sync()
        assertThat(db.items().count()).isEqualTo(2)
        assertThat(db.items().trashed()).isEmpty()
        // Trashed then purged (Empty): a tombstone, gone.
        server.putItem(item(1).copy(trashed = dev.hfps.hoard.server.api.ItemTrash(since = 6_000)))
        engine.sync()
        server.tombstone("item-1", null)
        engine.sync()
        assertThat(db.items().get("item-1")).isNull()
        assertThat(db.items().trashed()).isEmpty()
    }

    @Test
    fun anOlderServerIsAskedWithoutTrashAndSendsTombstones() = runBlocking<Unit> {
        pair()
        server.putItem(item(1).copy(trashed = dev.hfps.hoard.server.api.ItemTrash(since = 5_000)))
        engine.sync()
        assertThat(server.lastInclude).isEqualTo("items,culling")
        assertThat(db.items().get("item-1")).isNull()
    }

    @Test
    fun theWalkRequestHonoursDeferredAndRetryAfter() = runBlocking<Unit> {
        server.features = server.features + "trash"
        server.nasFolders += listOf("f1", "f2")
        pair()
        val api = engine.api()!!
        val slept = ArrayList<Long>()
        // 202 with a deferred folder: queued all the same, nothing to retry.
        server.walkDeferred["f2"] = 99_000L
        val first = NasWalks.request(api, listOf("f1", "f2")) { slept += it }
        assertThat(first.queued).containsExactly("f1", "f2")
        assertThat(first.deferred).containsExactly("f2", 99_000L)
        assertThat(slept).isEmpty()
        // 429 twice, then accepted: waits Retry-After each time.
        server.walkRateLimited = 2
        server.walkRetryAfter = 3
        val second = NasWalks.request(api, listOf("f1")) { slept += it }
        assertThat(second.queued).containsExactly("f1")
        assertThat(slept).containsExactly(3_000L, 3_000L)
        // Past the retries: reported, not thrown.
        server.walkRateLimited = 10
        val third = NasWalks.request(api, listOf("f1")) { slept += it }
        assertThat(third.rateLimited).containsExactly("f1")
        // An unknown folder is dropped, the others queued.
        server.walkRateLimited = 0
        val fourth = NasWalks.request(api, listOf("f1", "gone")) {}
        assertThat(fourth.queued).containsExactly("f1")
        assertThat(fourth.unknown).containsExactly("gone")
    }

    @Test
    fun anOriginalStreamsFromAnOffsetAndAMissingOneIsA404() = runBlocking<Unit> {
        pair()
        val bytes = ByteArray(300_000) { (it % 241).toByte() }
        server.originals["v1:big"] = bytes
        val api = engine.api()!!
        api.openOriginal("v1:big", 0).use { assertThat(it.readBytes().toList()).isEqualTo(bytes.toList()) }
        api.openOriginal("v1:big", 123_456).use { assertThat(it.readBytes().toList()).isEqualTo(bytes.copyOfRange(123_456, bytes.size).toList()) }
        val missing = runCatching { api.openOriginal("v1:gone", 0) }.exceptionOrNull() as dev.hfps.hoard.server.api.ServerException
        assertThat(missing.status).isEqualTo(404)
        assertThat(missing.code).isEqualTo("not_found")
    }

    @Test
    fun aSyncKeepsEveryPhonesBackupRootsAndTheItemsMirrorLocations() = runBlocking<Unit> {
        server.features = server.features + "backup"
        server.extra = { ex, path, _, _ ->
            if (path == "/v1/backup/roots" && ex.requestMethod == "GET") {
                server.send(ex, 200, """{"roots":[{"id":"r1","physicalDeviceId":"p1","deviceName":"Pixel 8","mine":false,"folder":"Pixel 8 (p1)","volume":"PRIMARY","path":"DCIM/Camera","types":["image","video"],"active":true,"stats":{"files":3}}]}""")
                true
            } else {
                false
            }
        }
        pair()
        val mirror = FileLocation("mirror", path = "DCIM/Camera/PXL_1.jpg", modifiedAt = 5, physicalDeviceId = "p1", deviceName = "Pixel 8", rootId = "r1", volume = "primary", onDevice = false, gone = "deleted")
        server.putItem(item(1).let { it.copy(files = it.files.map { f -> f.copy(locations = f.locations + mirror) }) })
        server.putItem(item(2))
        assertThat(engine.sync()).isTrue()
        val roots = ServerJson.decodeFromString(dev.hfps.hoard.server.api.BackupRoots.serializer(), db.account().get()!!.backupRoots!!)
        assertThat(roots.roots.single().deviceName).isEqualTo("Pixel 8")
        val rows = db.items().observeMirrors().first()
        assertThat(rows.single().itemId).isEqualTo("item-1")
        assertThat(rows.single().onDevice).isFalse()

        // The item no longer names the phone (an admin purge): its row goes with the next page.
        server.putItem(item(1))
        assertThat(engine.sync()).isTrue()
        assertThat(db.items().observeMirrors().first()).isEmpty()
    }

    @Test
    fun itemsCachedBeforeTheUpgradeAreIndexedOnceFromTheirJson() = runBlocking<Unit> {
        pair()
        val mirror = FileLocation("mirror", path = "DCIM/Camera/PXL_1.jpg", physicalDeviceId = "p1", deviceName = "Pixel 8", rootId = "r1", volume = "primary")
        server.putItem(item(1).let { it.copy(files = it.files.map { f -> f.copy(locations = f.locations + mirror) }) })
        assertThat(engine.sync()).isTrue()
        // As gallery.db 4 left it: the items, no mirror rows, not indexed yet.
        db.items().clearMirrors()
        db.account().setMirrorsIndexed(false)
        assertThat(engine.sync()).isTrue()
        assertThat(db.items().observeMirrors().first().single().onDevice).isTrue()
        assertThat(db.account().get()!!.mirrorsIndexed).isTrue()
    }
}
