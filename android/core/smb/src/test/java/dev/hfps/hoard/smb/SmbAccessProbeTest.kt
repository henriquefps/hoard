package dev.hfps.hoard.smb

import dev.hfps.hoard.smb.text.TextFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in probe for read-only detection (docs/testing/read-only.md): prints every share's
 * TREE_CONNECT MaximalAccess and security-descriptor answer, and every top-level folder's "MxAc"
 * answer and write-probe answer (a CREATE asking for FILE_ADD_FILE, closed unused) with the
 * decision Hoard makes, so the rule can be checked against a real server.
 *
 *     HOARD_SMOKE_ACCESS=127.0.0.1:14458 HOARD_SMOKE_USER=hoard HOARD_SMOKE_PASSWORD=hoardpass \
 *       [HOARD_ACCESS_PATHS='share|a/b;share|c'] [HOARD_ACCESS_WRITE_PROBE=1] \
 *       ./gradlew testDebugUnitTest --rerun --tests '*SmbAccessProbeTest*'
 *
 * `HOARD_ACCESS_PATHS` adds deeper folders to check (and files: `share|path/file.txt`, checked
 * through `readTextFile`).
 *
 * Without `HOARD_ACCESS_WRITE_PROBE` it never writes (safe against a real server, e.g. a Mac).
 * With it (disposable test servers only), it also creates and deletes a folder in every listed
 * folder and fails when the detected access disagrees with what the server actually allowed.
 */
class SmbAccessProbeTest {

    private lateinit var host: String
    private var port = 445
    private lateinit var user: String
    private lateinit var password: String
    private var writeProbe = false
    private val sessions = SmbSessions()

    @Before
    fun setUp() {
        val spec = System.getenv("HOARD_SMOKE_ACCESS").orEmpty()
        assumeTrue("HOARD_SMOKE_ACCESS not set", spec.isNotEmpty())
        host = spec.substringBeforeLast(':')
        port = spec.substringAfterLast(':').toInt()
        user = System.getenv("HOARD_SMOKE_USER").orEmpty()
        password = System.getenv("HOARD_SMOKE_PASSWORD").orEmpty()
        writeProbe = System.getenv("HOARD_ACCESS_WRITE_PROBE") == "1"
    }

    @After
    fun tearDown() = sessions.closeAll()

    private fun hex(mask: Long?) = mask?.let { "0x%08x".format(it) } ?: "none"

    /** Can this account create (and then delete) a folder here? Null when the probe is off. */
    private fun canWrite(session: SmbSession, share: String, path: String): Boolean? {
        if (!writeProbe) return null
        val name = ".hoard-access-probe-${System.nanoTime()}"
        return try {
            SmbBrowser.createFolder(session, share, path, name)
            SmbBrowser.delete(session, share, SmbPaths.join(path, name), false)
            true
        } catch (e: Throwable) {
            val code = SmbErrors.map(e).code
            if (code != ErrorCode.ACCESS_DENIED) println("    write probe: $code ${e.message}")
            false
        }
    }

    @Test
    fun `report and check share and folder access`() {
        val handle = runBlocking {
            SmbConnector.open(host, port, null, 10_000) { SmbCredentials(user, password.toCharArray(), null) }
        }
        println("connected: dialect=${handle.dialect} name=${handle.serverName}")
        val session = sessions.add("m1", handle)
        val started = System.nanoTime()
        val shares = sessions.use(session.id) { SmbBrowser.listSharesWithAccess(it) }
        println("listShares with access: ${shares.size} shares in ${(System.nanoTime() - started) / 1_000_000} ms")
        val mismatches = mutableListOf<String>()
        for ((info, access) in shares) {
            val treeMask = runCatching { SmbAccess.toLong(session.share(info.name).treeConnect.maximalAccess) }.getOrNull()
            println(
                "share '${info.name}': tree MaximalAccess=${hex(treeMask)} descriptor=${info.everyoneAccess?.json ?: "unknown"}" +
                    " -> ${access?.json ?: "unknown"}",
            )
            val folders = runCatching {
                val root = SmbBrowser.listWithAccess(session, info.name, "")
                listOf("" to root) + root.entries.filter { it.isDirectory && !it.name.startsWith(".") }.take(8).map { dir ->
                    dir.path to runCatching { SmbBrowser.listWithAccess(session, info.name, dir.path) }.getOrElse { e ->
                        println("    '${dir.path}': ${SmbErrors.map(e).code}")
                        null
                    }
                }
            }.getOrElse { e ->
                println("    root: ${SmbErrors.map(e).code}")
                emptyList()
            }
            for ((path, listing) in folders) {
                if (listing == null) continue
                val raw = SmbBrowser.listDirectoryWithAccess(session.share(info.name), SmbPaths.toSmb(path), probeWrite = true)
                val actual = canWrite(session, info.name, path)
                val verdict = when (actual) {
                    null -> ""
                    (listing.access == SmbAccess.READ_WRITE) -> "  OK (write probe: ${if (actual) "writable" else "denied"})"
                    else -> "  MISMATCH (write probe: ${if (actual) "writable" else "denied"})".also {
                        mismatches += "${info.name}/$path detected=${listing.access?.json} actual=$actual"
                    }
                }
                println("    '/$path': MxAc=${hex(raw.mask)} probe=${raw.probe ?: "none"} -> ${listing.access?.json ?: "unknown"}$verdict")
            }
            // Files: readTextFile's access (MxAc on the file open), for the first small file at the root.
            folders.firstOrNull()?.second?.entries?.firstOrNull { !it.isDirectory && it.size in 1..65_536 }?.let { file ->
                val read = runCatching { TextFiles.read(session, info.name, file.path, 65_536, true) }.getOrNull()
                println("    file '${file.path}': readTextFile access=${read?.access?.json ?: "unknown/not text"}")
            }
        }
        for (spec in System.getenv("HOARD_ACCESS_PATHS").orEmpty().split(";").filter { it.contains('|') }) {
            val share = spec.substringBefore('|')
            val path = spec.substringAfter('|')
            val folder = runCatching { SmbBrowser.listWithAccess(session, share, path) }
            if (folder.isSuccess) {
                val raw = SmbBrowser.listDirectoryWithAccess(session.share(share), SmbPaths.toSmb(path), probeWrite = true)
                println("path '$share/$path': MxAc=${hex(raw.mask)} probe=${raw.probe ?: "none"} -> ${folder.getOrThrow().access?.json ?: "unknown"}")
            } else {
                val read = runCatching { TextFiles.read(session, share, path, 65_536, true) }
                println("path '$share/$path': " + read.fold({ "file, readTextFile access=${it.access?.json ?: "unknown"}" }, { SmbErrors.map(it).code.toString() }))
            }
        }
        assertEquals("detected access disagrees with the server: $mismatches", emptyList<String>(), mismatches)
    }
}
