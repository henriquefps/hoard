package dev.hfps.hoard.smb

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in, read-only regression test for "every share shows the same folder": one session lists
 * the root (and folder names common to several shares) of every disk share, in several orders and
 * interleaved with share enumeration (IPC$ connect + disconnect), the way the app does, and
 * compares each listing with one taken on a fresh connection.
 *
 * It only catches the bug against a server that grants SMB3 directory leases (macOS File Sharing,
 * Windows, Samba >= 4.22 with `smb3 directory leases = yes`): smbj 0.15.0's listing cache keyed by
 * the in-share path only, see [SmbBrowser.listDirectory]. `docs/testing/samba-leases/Dockerfile`
 * is such a server.
 *
 *     HOARD_SMOKE_SHARES=127.0.0.1:445 HOARD_SMOKE_USER=… HOARD_SMOKE_PASSWORD=… \
 *       ./gradlew testDebugUnitTest --tests '*SmbMultiShareTest*'
 *
 * It never writes, so it is safe against a real server.
 */
class SmbMultiShareTest {

    private lateinit var host: String
    private var port = 445
    private lateinit var user: String
    private lateinit var password: String
    private val sessions = SmbSessions()

    @Before
    fun setUp() {
        val spec = System.getenv("HOARD_SMOKE_SHARES").orEmpty()
        assumeTrue("HOARD_SMOKE_SHARES not set", spec.isNotEmpty())
        host = spec.substringBeforeLast(':')
        port = spec.substringAfterLast(':').toInt()
        user = System.getenv("HOARD_SMOKE_USER").orEmpty()
        password = System.getenv("HOARD_SMOKE_PASSWORD").orEmpty()
    }

    @After
    fun tearDown() = sessions.closeAll()

    private fun open(): SmbSession {
        val handle = runBlocking {
            SmbConnector.open(host, port, null, 10_000) { SmbCredentials(user, password.toCharArray(), null) }
        }
        println("connected: dialect=${handle.dialect} name=${handle.serverName}")
        return sessions.add("m1", handle)
    }

    /** Sorted entry names of a folder, or the error code when it cannot be listed. */
    private fun fingerprint(session: SmbSession, share: String, path: String): String = try {
        sessions.use(session.id) { s -> SmbBrowser.list(s, share, path) }
            .map { it.name }.sorted().joinToString("|")
    } catch (e: HoardSmbException) {
        "<${e.code}>"
    }

    private fun short(listing: String) =
        if (listing.startsWith("<")) listing else listing.split('|').let { "${it.size} entries: ${it.take(6)}" }

    @Test
    fun `every share keeps its own listing on one session`() {
        val session = open()
        val shares = sessions.use(session.id) { SmbBrowser.listShares(it) }.filter { !it.isHidden }.map { it.name }
        println("shares: $shares")
        assertTrue("need at least two shares", shares.size >= 2)

        // Paths to compare: each root, plus folder names that exist at the root of two or more
        // shares (the cache was keyed by the path inside the share, so those collided too).
        // Discovered on a fresh connection per share, so the bug under test cannot skew it.
        sessions.remove(session.id)
        val roots = shares.associateWith { share ->
            val fresh = open()
            sessions.use(fresh.id) { s -> SmbBrowser.list(s, share, "") }.filter { it.isDirectory }.map { it.name }
                .also { sessions.remove(fresh.id) }
        }
        val common = roots.values.flatten().groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.sorted().take(3)
        val paths = listOf("") + common
        println("paths: $paths")

        // Reference: each share on its own fresh connection.
        val reference = shares.associateWith { share ->
            val fresh = open()
            paths.associateWith { fingerprint(fresh, share, it) }.also { sessions.remove(fresh.id) }
        }
        reference.forEach { (share, byPath) -> byPath.forEach { (path, l) -> println("reference  $share/$path -> ${short(l)}") } }

        val shared = open()
        val orders = listOf("forward" to shares, "reverse" to shares.reversed(), "forward again" to shares)
        val failures = ArrayList<String>()
        for ((label, order) in orders) {
            for (share in order) {
                // The app enumerates shares (IPC$ tree connect + disconnect) before opening one.
                sessions.use(shared.id) { SmbBrowser.listShares(it) }
                for (path in paths) {
                    val listing = fingerprint(shared, share, path)
                    println("$label  $share/$path -> ${short(listing)}")
                    if (listing != reference.getValue(share).getValue(path)) {
                        failures += "$label: $share/$path listed ${short(listing)}, expected ${short(reference.getValue(share).getValue(path))}"
                    }
                }
            }
        }
        assertEquals("shares showed another share's content:\n" + failures.joinToString("\n"), emptyList<String>(), failures)

        val listable = reference.values.map { it.getValue("") }.filter { !it.startsWith("<") && it.isNotEmpty() }
        if (listable.size >= 2) assertNotEquals("all shares have the same listing", 1, listable.toSet().size)
    }
}
