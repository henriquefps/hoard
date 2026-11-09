package dev.hfps.hoard.smb

import com.hierynomus.smbj.share.PipelinedFileReader
import dev.hfps.hoard.smb.viewer.RemoteFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Opt-in end-to-end test against a disposable Samba server (skipped in the default run). It expects
 * the layout of `docs/testing/samba/Dockerfile`:
 *
 *     docker build -t hoard-smb-test docs/testing/samba
 *     docker run -d --rm --name hoard-smb-test -p 127.0.0.1:14450:445 hoard-smb-test
 *     HOARD_SMOKE_SMB="127.0.0.1:14450:hoard:hoardpass" ./gradlew testDebugUnitTest --tests '*SmbIntegrationTest*'
 */
class SmbIntegrationTest {

    private lateinit var host: String
    private var port = 445
    private lateinit var user: String
    private lateinit var password: String
    private val sessions = SmbSessions()

    @Before
    fun setUp() {
        val spec = System.getenv("HOARD_SMOKE_SMB").orEmpty()
        assumeTrue("HOARD_SMOKE_SMB not set", spec.isNotEmpty())
        val parts = spec.split(':')
        host = parts[0]
        port = parts[1].toInt()
        user = parts[2]
        password = parts[3]
    }

    @After
    fun tearDown() = sessions.closeAll()

    private fun connect(guid: String? = null, pass: String = password) = runBlocking {
        SmbConnector.open(host, port, guid, 5_000) { SmbCredentials(user, pass.toCharArray(), null) }
    }

    private fun expect(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected $code")
        } catch (e: HoardSmbException) {
            assertEquals(e.message, code, e.code)
        }
    }

    @Test
    fun `connect, pin, browse, mutate and download`() {
        val handle = connect()
        println("connected: guid=${handle.serverGuid} dialect=${handle.dialect} name=${handle.serverName}")
        assertTrue(handle.dialect.startsWith("SMB_3"))

        // GUID pinning: the right GUID connects, a wrong one is refused before SESSION_SETUP.
        connect(handle.serverGuid.uppercase()).close()
        val mismatch = runCatching {
            runBlocking {
                SmbConnector.open(host, port, "11111111-2222-3333-4444-555555555555", 5_000) {
                    throw AssertionError("credentials read before the GUID check")
                }
            }
        }.exceptionOrNull() as HoardSmbException
        assertEquals(ErrorCode.GUID_MISMATCH, mismatch.code)
        assertEquals(handle.serverGuid, mismatch.data["serverGuid"])

        // Wrong password / no credentials.
        expect(ErrorCode.AUTH_FAILED) { connect(pass = "wrong") }
        expect(ErrorCode.AUTH_FAILED) { runBlocking { SmbConnector.open(host, port, null, 5_000) { null } } }
        // Nothing listening.
        expect(ErrorCode.UNREACHABLE) { runBlocking { SmbConnector.open("127.0.0.1", 1, null, 2_000) { null } } }

        val session = sessions.add("m1", handle)

        // Shares through srvsvc: disk shares only, IPC$ filtered, `$` shares hidden.
        val shares = sessions.use(session.id) { SmbBrowser.listShares(it) }
        println("shares: $shares")
        assertEquals(listOf("admin$", "media", "private"), shares.map { it.name.lowercase() })
        assertEquals("Media files", shares.first { it.name == "media" }.remark)
        assertTrue(shares.first { it.name.lowercase() == "admin$" }.isHidden)

        // List: no . / .., hidden flag for dotfiles, sizes, ISO times.
        val root = sessions.use(session.id) { SmbBrowser.list(it, "media", "") }
        assertEquals(setOf(".hidden", "Empty", "Photos", "readme.txt"), root.map { it.name }.toSet())
        assertTrue(root.first { it.name == ".hidden" }.isHidden)
        val readme = root.first { it.name == "readme.txt" }
        assertEquals(6, readme.size)
        assertFalse(readme.isDirectory)
        assertNotNull(readme.modifiedAt)
        assertTrue(root.first { it.name == "Photos" }.isDirectory)
        assertEquals(0, root.first { it.name == "Photos" }.size)
        assertEquals("Photos/big.bin", sessions.use(session.id) { SmbBrowser.list(it, "media", "/Photos/") }.single().path)

        // Stat (root and file), NOT_FOUND, ACCESS_DENIED.
        assertEquals("", sessions.use(session.id) { SmbBrowser.stat(it, "media", "") }.path)
        assertEquals(7_340_033, sessions.use(session.id) { SmbBrowser.stat(it, "media", "Photos/big.bin") }.size)
        expect(ErrorCode.NOT_FOUND) { sessions.use(session.id) { SmbBrowser.stat(it, "media", "nope.txt") } }
        expect(ErrorCode.NOT_FOUND) { sessions.use(session.id) { SmbBrowser.list(it, "nope", "") } }
        expect(ErrorCode.NOT_FOUND) { sessions.use(session.id) { SmbBrowser.list(it, "media", "readme.txt") } }
        expect(ErrorCode.ACCESS_DENIED) { sessions.use(session.id) { SmbBrowser.list(it, "private", "") } }

        // createFolder / ALREADY_EXISTS / rename / delete.
        val created = sessions.use(session.id) { SmbBrowser.createFolder(it, "media", "Empty", "New") }
        assertEquals("Empty/New", created.path)
        assertTrue(created.isDirectory)
        expect(ErrorCode.ALREADY_EXISTS) { sessions.use(session.id) { SmbBrowser.createFolder(it, "media", "Empty", "New") } }
        sessions.use(session.id) { SmbBrowser.createFolder(it, "media", "Empty/New", "Inner") }
        assertEquals("Empty/New", sessions.use(session.id) { SmbBrowser.rename(it, "media", "Empty/New", "New") }.path)
        sessions.use(session.id) { SmbBrowser.createFolder(it, "media", "Empty", "Other") }
        expect(ErrorCode.ALREADY_EXISTS) { sessions.use(session.id) { SmbBrowser.rename(it, "media", "Empty/Other", "New") } }
        val renamed = sessions.use(session.id) { SmbBrowser.rename(it, "media", "Empty/New", "Renamed") }
        assertEquals("Empty/Renamed", renamed.path)
        expect(ErrorCode.ACCESS_DENIED) { sessions.use(session.id) { SmbBrowser.delete(it, "media", "Empty/Renamed", false) } }
        sessions.use(session.id) { SmbBrowser.delete(it, "media", "Empty/Renamed", true) }
        sessions.use(session.id) { SmbBrowser.delete(it, "media", "Empty/Other", false) }
        assertTrue(sessions.use(session.id) { SmbBrowser.list(it, "media", "Empty") }.isEmpty())
        expect(ErrorCode.ACCESS_DENIED) { sessions.use(session.id) { SmbBrowser.delete(it, "media", "", true) } }

        // Pipelined download from an offset (resume) and from 0, compared with a plain read.
        val (pipelined, resumed, plain) = sessions.use(session.id) { s ->
            RemoteFiles.openRead(s, "media", "Photos/big.bin").use { file ->
                val size = RemoteFiles.info(file).size
                fun readFrom(offset: Long): ByteArray {
                    val out = ByteArrayOutputStream()
                    val reader = PipelinedFileReader(file, offset, size, depth = 4)
                    while (true) out.write(reader.next() ?: break)
                    assertEquals(size, reader.position)
                    return out.toByteArray()
                }
                val started = System.nanoTime()
                val all = readFrom(0)
                println("pipelined read: ${all.size} bytes in ${(System.nanoTime() - started) / 1_000_000} ms")
                Triple(all, readFrom(3_000_001), file.inputStream.readBytes())
            }
        }
        assertEquals(7_340_033, pipelined.size)
        assertEquals(sha(plain), sha(pipelined))
        assertEquals(sha(plain.copyOfRange(3_000_001, plain.size)), sha(resumed))

        // A dropped connection turns the session into NOT_FOUND.
        session.handle.connection.close(true)
        expect(ErrorCode.NOT_FOUND) { sessions.use(session.id) { SmbBrowser.list(it, "media", "") } }
        expect(ErrorCode.NOT_FOUND) { sessions.get(session.id) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
