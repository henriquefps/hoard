package dev.hfps.hoard.app

import android.app.Activity
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.hoard.app.cull.CullKey
import dev.hfps.hoard.app.cull.CullStatus
import dev.hfps.hoard.app.cull.CullViewModel
import dev.hfps.hoard.app.cull.Flag
import dev.hfps.hoard.app.cull.withFlag
import dev.hfps.hoard.app.cull.withRating
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.transfers.LiveTransfer
import dev.hfps.hoard.app.ui.cull.CullViewModelFactory
import dev.hfps.hoard.data.MachineAddressEntity
import dev.hfps.hoard.data.MachineEntity
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * End to end on an emulator (`docs/testing/local-copy-handoff.md`): "Edit in Reveal" of SMB photos the server does not
 * hold copies them into MediaStore through the transfer engine and hands their `content://media/…` URIs to a test
 * receiver standing in for Reveal (`dev.hfps.reveal.debug`, it logs what it got); the second hand-off reuses the
 * copies; a cancelled copy leaves nothing; culling on the share writes no `.xmp`; Export XMP writes them; Delete copies
 * frees the space. Opt-in (`-e hoardLocalCopyE2e true`): it adds a machine and copies to this install. Never on the
 * owner's phone. Arguments: `host`, `port`, `share`, `user`, `password`, `folder`.
 */
@RunWith(AndroidJUnit4::class)
class LocalCopyHandoffE2eTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as HoardApplication).container

    private fun status(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })
        android.util.Log.i("HoardE2E", "$key=$value")
    }

    @Test
    fun localCopyHandoff() = runBlocking {
        assumeTrue("opt-in: -e hoardLocalCopyE2e true (adds a machine and copies)", args.getString("hoardLocalCopyE2e") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val host = args.getString("host") ?: "10.0.2.2"
        val port = args.getString("port")?.toInt() ?: 445
        val share = args.getString("share") ?: "media"
        val user = args.getString("user") ?: "hoard"
        val password = args.getString("password") ?: "hoardpass"
        val folder = args.getString("folder") ?: "Shoot"

        // A machine pinned to the test Samba's GUID, as the machine form saves it.
        val guid = SmbConnector.open(host, port, null, 10_000) { SmbCredentials(user, password.toCharArray(), null) }.use { it.serverGuid }
        val machineId = "e2e-localcopy"
        val c = container
        c.database.machines().save(
            MachineEntity(machineId, "E2E Samba", "server", "blue", user, null, guid, 0, Instant.now().toString()),
            listOf(MachineAddressEntity("e2e-addr", machineId, host, port, "lan", null, 0)),
            create = c.database.machines().get(machineId) == null,
        )
        c.gateway.setCredentials(machineId, user, password, "")
        val machine = c.database.machines().get(machineId)!!
        val location = BrowseLocation(machineId, LocationKind.Smb, "E2E Samba", machine, share)
        c.revealCopies.deleteAll()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: Activity? = null
        scenario.onActivity { activity = it }
        val actions = FileActions(c)
        val paths = listOf("$folder/DSC0001.ARW", "$folder/DSC0001.JPG", "$folder/IMG_0003.CR3")
        // A decision, so the CULLING extra has something to carry.
        c.database.cull().putDecision(dev.hfps.hoard.data.CullDecisionEntity(machineId, share, folder, "dsc0001", "pick", 4, Instant.now().toString()))

        // 1. First hand-off: copies (progress reported), then the intent.
        val progress = ArrayList<LiveTransfer>()
        val transfersBefore = c.database.transfers().all().size
        val sent = actions.editInReveal(activity!!, location, folder, paths, "E2E Trip", paths[0]) { it?.let(progress::add) }
        status("first.sent", sent.toString())
        status("first.progressEvents", progress.size.toString())
        status("first.lastProgress", progress.lastOrNull()?.let { "${it.state} ${it.itemsDone}/${it.itemsTotal} ${it.bytesDone}/${it.totalBytes}" } ?: "none")
        status("first.newTransferJobs", (c.database.transfers().all().size - transfersBefore).toString())
        val rows = c.database.revealCopies().all()
        rows.forEach { status("copy.${it.sourcePath}", "${it.uri} size=${it.size} mtime=${it.modifiedMillis}") }
        listMediaStore("after-first")
        delay(3000)

        // 2. Same photos again: every copy reused, no new job.
        val before = c.database.transfers().all().size
        actions.editInReveal(activity!!, location, folder, paths, "E2E Trip", paths[0])
        status("second.newTransferJobs", (c.database.transfers().all().size - before).toString())
        status("second.sameUris", (c.database.revealCopies().all().map { it.uri }.toSet() == rows.map { it.uri }.toSet()).toString())
        delay(3000)

        // 3. Cancel a copy in flight: no row is left, pending or not.
        val big = "$folder/BIG0004.ARW"
        val job = async(Dispatchers.Default) {
            runCatching { actions.editInReveal(activity!!, location, folder, listOf(big), "E2E Cancel", null) { p -> if (p != null && p.bytesDone > 0) status("cancel.progress", "${p.bytesDone}/${p.totalBytes}") } }
        }
        withTimeout(60_000) {
            while (c.transfers.live.value.values.none { it.state == "running" && it.bytesDone > 0 }) delay(50)
        }
        job.cancel()
        val outcome = runCatching { job.await() }
        status("cancel.outcome", outcome.exceptionOrNull()?.javaClass?.simpleName ?: outcome.getOrNull()?.exceptionOrNull()?.let { (it as? HoardSmbException)?.code?.name ?: it.toString() } ?: "sent")
        delay(4000)
        status("cancel.rowsLeft", countRows("E2E Cancel").toString())
        status("cancel.recorded", c.database.revealCopies().all().count { it.sourcePath == big }.toString())

        // 4. Culling on the share writes no sidecar.
        val key = CullKey(machineId, share, folder)
        val vm = withContext(Dispatchers.Main) { CullViewModelFactory(c, location, key).create(CullViewModel::class.java) }
        withTimeout(60_000) { while (vm.view.value.core.status != CullStatus.Ready) delay(100) }
        withContext(Dispatchers.Main) {
            vm.decide(listOf("dsc0002")) { d, _ -> d.withFlag(Flag.Reject).withRating(1) }
        }
        vm.settle()
        delay(5000)
        withContext(Dispatchers.Main) { vm.onPause() }
        delay(2000)
        status("cull.decided", c.database.cull().forFolder(machineId, share, folder).joinToString { "${it.shotId}:${it.flag}:${it.rating}" })

        status("cull.sidecarsOnShare", shareSidecars(location, folder))

        // 5. Export XMP writes them.
        val exported = c.xmpExporter.export(location.place, folder, null, hidden = false)
        status("export", "written=${exported.written} malformed=${exported.malformed} failed=${exported.failed} readOnly=${exported.readOnly}")
        status("export.sidecarsOnShare", shareSidecars(location, folder))

        // 6. Delete copies frees the space.
        val usage = c.revealCopies.usage()
        status("usage.before", "${usage.count} ${usage.bytes}")
        val deleted = c.revealCopies.deleteAll()
        status("deleted", "${deleted.count} ${deleted.bytes}")
        status("usage.after", c.revealCopies.usage().let { "${it.count} ${it.bytes}" })
        listMediaStore("after-delete")
        scenario.close()
        if (sent != paths.size) throw HoardSmbException(ErrorCode.UNKNOWN, "sent $sent")
    }

    /** The `.xmp` names in [folder] of the share, read through the transfer engine's SMB endpoint. */
    private suspend fun shareSidecars(location: BrowseLocation, folder: String): String = withContext(Dispatchers.IO) {
        val endpoint = container.endpointOf(location.place)
        dev.hfps.hoard.smb.transfer.TransferManager.get(context).endpoints.open(endpoint).use { fs ->
            fs.list(folder).filter { it.name.endsWith(".xmp", ignoreCase = true) }.joinToString { it.name }.ifEmpty { "none" }
        }
    }

    /** Every Hoard-owned row under Pictures/Hoard and Download/Hoard, pending ones included. */
    private fun listMediaStore(label: String) {
        for (uri in listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
            val q = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%/Hoard/%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING), q, null)?.use { cur ->
                while (cur.moveToNext()) {
                    status("ms.$label.${cur.getString(1)}${cur.getString(2)}", "${ContentUris.withAppendedId(uri, cur.getLong(0))} ${cur.getString(3)} size=${cur.getLong(4)} pending=${cur.getInt(5)}")
                }
            }
        }
    }

    private fun countRows(folderName: String): Int {
        var n = 0
        for (uri in listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
            val q = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%/Hoard/$folderName/%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), q, null)?.use { n += it.count }
        }
        return n
    }
}
