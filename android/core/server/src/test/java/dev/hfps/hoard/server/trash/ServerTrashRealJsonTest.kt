package dev.hfps.hoard.server.trash

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.api.TrashEmptyRequest
import dev.hfps.hoard.server.api.TrashFigures
import dev.hfps.hoard.server.api.TrashGroupIds
import dev.hfps.hoard.server.api.TrashUnitRef
import dev.hfps.hoard.server.api.TrashUnitsRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * The client against hoard-server 0.15.0's real answers (`src/test/resources/trash-0150/`, recorded from the server's
 * own harness): a backup-only unit (`webOnly`) is listed restore-only and never sent to delete, the server's `web_only`
 * refusal decodes, the listing's `webOnly` figure is kept apart from the groups, and the 409's figures round-trip.
 */
class ServerTrashRealJsonTest {
    private val server = FakeServer()
    private val routes = ApiTrashRoutes(ServerApi(server.url, { server.key }))
    private val deletes = ArrayList<List<TrashUnitRef>>()
    private val empties = ArrayList<TrashEmptyRequest>()
    private val sleep: suspend (Long) -> Unit = {}

    private fun body(name: String) = javaClass.getResourceAsStream("/trash-0150/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val freedId = "89e7439b-baa4-8eba-bddf-96e4d188187b"
    private val nasId = "ae593ca4-d72b-8f80-896f-0f8cea1c5936"

    init {
        server.extra = { ex, path, _, raw ->
            when (path) {
                "/v1/trash" -> { server.send(ex, 200, body("list.json")); true }
                "/v1/trash/delete" -> {
                    deletes += ServerJson.decodeFromString(TrashUnitsRequest.serializer(), raw.toString(Charsets.UTF_8)).units
                    server.send(ex, 200, body("delete_web_only.json")); true
                }
                "/v1/trash/empty" -> {
                    val req = ServerJson.decodeFromString(TrashEmptyRequest.serializer(), raw.toString(Charsets.UTF_8))
                    empties += req
                    // The server's figures now: what its 409 said.
                    if (req.confirm == TrashFigures(1, 327)) server.send(ex, 200, """{"done":[{"itemId":"$nasId","key":"nas:x"}],"refused":{},"size":327}""")
                    else server.send(ex, 409, body("empty_409.json"))
                    true
                }
                else -> false
            }
        }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun backupOnlyUnitIsRestoreOnlyAndItsFiguresApart() = runBlocking<Unit> {
        val listing = ServerTrash.list(routes, sleep)
        assertThat(listing.webOnly).isEqualTo(TrashFigures(1, 287))
        assertThat(listing.figures(TrashGroupIds.HOARD)).isEqualTo(TrashFigures(1, 327))
        val lines = ServerTrash.days(listing).flatMap { d -> d.places.flatMap { it.lines } }
        val backup = lines.single { it.entry.id == freedId }
        assertThat(backup.unit.webOnly).isTrue()
        assertThat(backup.webOnly).isTrue()
        val place = ServerTrash.days(listing).flatMap { it.places }.single { it.source == "server" }
        assertThat(place.free).hasSize(1) // Restore offered
        assertThat(place.deletable).isEmpty() // no Delete permanently

        val r = ServerTrash.delete(routes, lines, sleep)
        assertThat(deletes.flatten().map { it.itemId }).doesNotContain(freedId) // never sent
        assertThat(r.refused[TrashUnitRef(freedId, "server").refKey]).isEqualTo(TrashReasons.WEB_ONLY)
    }

    @Test
    fun theServersOwnWebOnlyRefusalDecodes() = runBlocking<Unit> {
        // A client that sent it anyway: the real answer maps to the same reason.
        val r = ServerTrash.restore(object : TrashRoutes by routes {
            override fun restore(units: List<TrashUnitRef>) = routes.delete(units)
        }, listOf(TrashUnitRef(freedId, "server")), sleep)
        assertThat(r.refusedBy).containsExactly(TrashReasons.WEB_ONLY, 1)
        assertThat(r.done).isEmpty()
    }

    @Test
    fun the409FiguresRoundTrip() = runBlocking<Unit> {
        val stale = mapOf(TrashGroupIds.HOARD to TrashFigures(2, 999))
        val first = ServerTrash.empty(routes, stale, TrashGroupIds.ALL, sleep)
        assertThat(first).isInstanceOf(EmptyResult.Conflict::class.java)
        first as EmptyResult.Conflict
        assertThat(first.groups[TrashGroupIds.HOARD]).isEqualTo(TrashFigures(1, 327))
        assertThat(first.groups[TrashGroupIds.WEB]).isEqualTo(TrashFigures(0, 0))
        assertThat(first.webOnly).isEqualTo(TrashFigures(1, 287))

        val again = ServerTrash.empty(routes, first.groups, TrashGroupIds.ALL, sleep)
        assertThat(again).isInstanceOf(EmptyResult.Done::class.java)
        assertThat(empties.last().confirm).isEqualTo(TrashFigures(1, 327)) // the backup-only unit is not in it
        assertThat(empties.last().groups).containsExactly(TrashGroupIds.WEB, TrashGroupIds.HOARD, TrashGroupIds.COMPRESSED).inOrder()
    }
}
