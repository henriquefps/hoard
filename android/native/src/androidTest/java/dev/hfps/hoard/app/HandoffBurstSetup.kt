package dev.hfps.hoard.app

import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbCredentials
import dev.hfps.hoard.smb.handoff.HandoffMachine
import dev.hfps.hoard.smb.handoff.HandoffServer
import dev.hfps.hoard.smb.stream.StreamAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Hoard side of the hand-off burst stress test (`docs/testing/handoff-burst.md`): registers SMB hand-off links
 * for the files of a test Samba share and local FileProvider files, and grants them (read, persistable) to the
 * client test app, which then opens them all at once from its own uid. Opt-in (`-e hoardHandoffBurst true`): it adds
 * a machine, a login and links to this install. Never on the owner's phone.
 *
 * Arguments: `host`, `port`, `share`, `user`, `password`, `files` (comma-separated share-relative paths), `client`
 * (the client's package), `localCount`, `localBytes`. Prints the URIs as `smb=<uri>` / `local=<uri>` status lines.
 */
@RunWith(AndroidJUnit4::class)
class HandoffBurstSetup {
    @Test
    fun registerAndGrant() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e hoardHandoffBurst true (adds links to this install)", args.getString("hoardHandoffBurst") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = args.getString("host") ?: "10.0.2.2"
        val port = args.getString("port")?.toInt() ?: 445
        val share = args.getString("share") ?: "photos"
        val user = args.getString("user") ?: "hoard"
        val password = args.getString("password") ?: "hoardpass"
        val client = args.getString("client") ?: "dev.hfps.hoard.core.smb.test"
        val files = args.getString("files").orEmpty().split(',').filter { it.isNotBlank() }
        val machineId = "burst-machine"
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        // Pin the server's GUID the way a hand-off does (the provider never connects to an unpinned machine).
        val guid = runBlocking {
            SmbConnector.open(host, port, null, 10_000) { SmbCredentials(user, password.toCharArray(), null) }.use { it.serverGuid }
        }
        CredentialStore(context).set(machineId, user, password, null)
        val store = HandoffServer.store(context)
        store.noteRoute(HandoffMachine(machineId, guid, listOf(StreamAddress(host, port))))
        val out = Bundle()
        files.forEachIndexed { i, path ->
            val item = store.smbItem(machineId, share, path, mimeOf(path), 0, null, path.endsWith(".xmp")) { id, tag, name ->
                HandoffServer.uriOf(context.packageName, id, tag, name).toString()
            }
            context.grantUriPermission(client, android.net.Uri.parse(item.uri), flags)
            out.putString("smb$i", item.uri)
        }

        // Local links: FileProvider files (cache/open/), the path Hoard hands phone files through.
        val localCount = args.getString("localCount")?.toInt() ?: 0
        val localBytes = args.getString("localBytes")?.toInt() ?: (1024 * 1024)
        val dir = File(context.cacheDir, "open/burst").apply { mkdirs() }
        repeat(localCount) { i ->
            val file = File(dir, "local-$i.bin")
            if (file.length() != localBytes.toLong()) file.writeBytes(BurstPattern.bytes(seed = 1000 + i, size = localBytes))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.grantUriPermission(client, uri, flags)
            out.putString("local$i", uri.toString())
        }
        // Hand them over the way Hoard hands photos to Reveal: an intent with the grants, which the client persists
        // (a force-stop of Hoard, or the end of this instrumentation, drops grants that are not persisted).
        val uris = out.keySet().map { android.net.Uri.parse(out.getString(it)) }
        val clip = android.content.ClipData.newRawUri("burst", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        context.startActivity(
            Intent().setClassName(client, "dev.hfps.hoard.smb.handoff.TakeGrantsActivity").apply {
                clipData = clip
                addFlags(flags or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        val deadline = System.currentTimeMillis() + 60_000
        val wanted = uris.map { it.toString() }.toSet()
        while (System.currentTimeMillis() < deadline) {
            val persisted = context.contentResolver.outgoingPersistedUriPermissions.map { it.uri.toString() }.toSet()
            if (persisted.containsAll(wanted)) break
            Thread.sleep(250)
        }
        val persisted = context.contentResolver.outgoingPersistedUriPermissions.map { it.uri.toString() }.toSet()
        org.junit.Assert.assertTrue("the client persisted ${wanted.count { it in persisted }} of ${wanted.size} grants", persisted.containsAll(wanted))
        out.putString("guid", guid)
        instrumentation.sendStatus(0, out)
    }

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "xmp" -> "application/rdf+xml"
        "jpg" -> "image/jpeg"
        "arw" -> "image/x-sony-arw"
        else -> "application/octet-stream"
    }
}

/** The bytes of the burst test's files: verifiable at any offset without the file (`docs/testing/tools/`). */
object BurstPattern {
    fun at(seed: Int, offset: Long): Byte = ((offset and 4095) + seed * 17 + (offset ushr 12) * 13).toInt().toByte()

    fun bytes(seed: Int, size: Int) = ByteArray(size) { at(seed, it.toLong()) }
}
