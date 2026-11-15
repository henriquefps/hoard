package dev.hfps.hoard.server.paths

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
import dev.hfps.hoard.server.sync.DeviceInfo
import dev.hfps.hoard.server.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ADR 0022 against a fake hoard-server 0.10.0: learning with two reads, caching, invalidation, resolution. */
@RunWith(AndroidJUnit4::class)
class SmbServerPathsTest {
    private lateinit var server: FakeServer
    private lateinit var db: GalleryDatabase
    private lateinit var engine: SyncEngine
    private lateinit var paths: SmbServerPaths
    private var clock = 1_000_000L
    private val folderId = "8b0c6f3e-1111-4222-8333-944455556666"
    private val folder = SmbFolderRef("m1", "Photos", "Imagens/2024/Trip", "guid-1")

    /** Fingerprints of the share's files (what a 2 MiB read would give), and which files were read. */
    private val prints = HashMap<String, String>()
    private val read = ArrayList<String>()
    private val reader = FingerprintReader { path -> read += path; prints[path] ?: error("unreadable $path") }

    private fun fp(n: Int) = "v1:" + "%064x".format(n)

    /** Shot n of the trip: on the NAS at `2024/Trip/DSC<n>.JPG` (the share's `Imagens/` is the NAS folder). */
    private fun shot(n: Int, dir: String = "2024/Trip", folder: String = folderId): Item {
        val name = "DSC%05d.JPG".format(n)
        val f = ItemFile(fp(n), "rendered", name, "jpeg", 1000L + n, listOf(FileLocation("nas", folder, "Imagens", "$dir/$name", 50_000L + n)))
        prints["Imagens/$dir/$name"] = fp(n)
        return Item(id = "item-$n", rev = 0, kind = "photo", mainFingerprint = fp(n), sortTime = 1_700_000_000_000L + n, files = listOf(f))
    }

    private fun listing(range: IntRange, dir: String = "Imagens/2024/Trip") =
        range.map { n -> MappingDiscovery.SmbFile("$dir/DSC%05d.JPG".format(n), 1000L + n, 50_000L + n) } + MappingDiscovery.SmbFile("$dir/notes.txt", 10, 1)

    @Before
    fun setUp() {
        server = FakeServer()
        server.features = server.features + "items-by-path"
        server.nasFolders += folderId
        db = GalleryDatabase.open(ApplicationProvider.getApplicationContext(), null)
        engine = SyncEngine(db, MemoryKeyVault(), DeviceInfo("Pixel 8", "hoard-debug", "Google", "Pixel 8", "2.2.0"), now = { clock })
        paths = SmbServerPaths(db, { engine.api() }, now = { clock })
        runBlocking { engine.pair(PairingCode(server.url, "good", "fake-server")) }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun learnsWithTwoSmallReadsThenResolvesTheFolder() = runBlocking<Unit> {
        for (n in 1..5) server.putItem(shot(n))
        val r = paths.resolve(folder, listing(1..5), reader)!!
        // One file found the mapping, a second checked it; no other file was read.
        assertThat(read).containsExactly("Imagens/2024/Trip/DSC00001.JPG", "Imagens/2024/Trip/DSC00005.JPG").inOrder()
        assertThat(server.lookupCalls).isEqualTo(2)
        assertThat(r.mapping.smbPrefix).isEqualTo("Imagens")
        assertThat(r.mapping.serverPrefix).isEqualTo("")
        assertThat(r.mapping.folderId).isEqualTo(folderId)
        assertThat(r.itemOf("Imagens/2024/Trip/DSC00003.JPG")).isEqualTo("item-3")
        assertThat(r.matches).doesNotContainKey("Imagens/2024/Trip/notes.txt")
        assertThat(server.folderQueries).isEqualTo(1)
        // Stored: another folder of the same share is resolved without reading anything.
        for (n in 10..12) server.putItem(shot(n, dir = "2024/Other"))
        val other = paths.resolve(folder.copy(path = "Imagens/2024/Other"), listing(10..12, "Imagens/2024/Other"), reader)!!
        assertThat(read).hasSize(2)
        assertThat(other.itemOf("Imagens/2024/Other/DSC00011.JPG")).isEqualTo("item-11")
        assertThat(paths.mappings()).hasSize(1)
    }

    @Test
    fun cachesPerFolderUntilTheTtlOrARefresh() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(shot(n))
        paths.resolve(folder, listing(1..3), reader)
        paths.resolve(folder, listing(1..3), reader)
        assertThat(server.folderQueries).isEqualTo(1)
        clock += SmbServerPaths.TTL_MS + 1
        paths.resolve(folder, listing(1..3), reader)
        assertThat(server.folderQueries).isEqualTo(2)
        paths.resolve(folder, listing(1..3), reader, refresh = true)
        assertThat(server.folderQueries).isEqualTo(3)
        assertThat(read).hasSize(2)
    }

    @Test
    fun anOlderServerOrAFolderNotOnTheServerReadsAtMostOneFile() = runBlocking<Unit> {
        server.features = server.features - "items-by-path"
        engine.sync()
        assertThat(paths.resolve(folder, listing(1..3), reader)).isNull()
        assertThat(read).isEmpty()
        server.features = server.features + "items-by-path"
        engine.sync()
        // Nothing of the folder on the server: one read, one lookup, then remembered for a day.
        for (n in 1..3) prints["Imagens/2024/Trip/DSC%05d.JPG".format(n)] = fp(n)
        assertThat(paths.resolve(folder, listing(1..3), reader)).isNull()
        assertThat(read).hasSize(1)
        assertThat(paths.resolve(folder, listing(1..3), reader, refresh = true)).isNull()
        assertThat(read).hasSize(1)
        clock += SmbServerPaths.MISS_RETRY_MS + 1
        paths.resolve(folder, listing(1..3), reader)
        assertThat(read).hasSize(2)
    }

    @Test
    fun aSecondFileThatDisagreesIsNotTrusted() = runBlocking<Unit> {
        server.putItem(shot(1))
        // The last file of the folder is on the server elsewhere (another NAS folder).
        server.putItem(shot(3, dir = "backup", folder = "8b0c6f3e-1111-4222-8333-000000000000"))
        prints["Imagens/2024/Trip/DSC00003.JPG"] = fp(3)
        assertThat(paths.resolve(folder, listing(1..3), reader)).isNull()
        assertThat(read).hasSize(2)
        assertThat(paths.mappings()).isEmpty()
    }

    @Test
    fun anotherSmbServerGuidOrPairingForgetsTheMapping() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(shot(n))
        paths.resolve(folder, listing(1..3), reader)
        assertThat(paths.mappings()).hasSize(1)
        paths.clearCache()
        // The machine now answers with another SMB server GUID: the mapping no longer holds, it is learned again.
        paths.resolve(folder.copy(smbGuid = "guid-2"), listing(1..3), reader)
        assertThat(read).hasSize(4)
        assertThat(paths.mappings().single().smbGuid).isEqualTo("guid-2")
        // Unpairing forgets every mapping.
        engine.unpair()
        assertThat(paths.mappings()).isEmpty()
    }

    @Test
    fun aCachedFolderIsMatchedAgainstTheCurrentListing() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(shot(n))
        val first = paths.resolve(folder, listing(1..3), reader)!!
        assertThat(first.itemOf("Imagens/2024/Trip/DSC00002.JPG")).isEqualTo("item-2")
        // DSC00002 overwritten on the share a minute later (another client, or Hoard's own replace): new size and mtime.
        clock += 60_000
        val now = listing(1..3).map { if (it.name == "DSC00002.JPG") it.copy(size = 9_999, modifiedAt = clock) else it }
        val again = paths.resolve(folder, now, reader)!!
        // Inside the TTL the server is not asked again, but the overwritten file is no longer its old item.
        assertThat(server.folderQueries).isEqualTo(1)
        assertThat(again.itemOf("Imagens/2024/Trip/DSC00002.JPG")).isNull()
        assertThat(again.match("Imagens/2024/Trip/DSC00002.JPG")).isInstanceOf(FileMatch.Changed::class.java)
        assertThat(again.itemOf("Imagens/2024/Trip/DSC00001.JPG")).isEqualTo("item-1")
        // A file deleted since: not in the matches; a new one: unknown.
        val other = paths.resolve(folder, listing(1..1) + listing(7..7), reader)!!
        assertThat(other.matches.keys).containsExactly("Imagens/2024/Trip/DSC00001.JPG", "Imagens/2024/Trip/DSC00007.JPG")
        assertThat(other.match("Imagens/2024/Trip/DSC00007.JPG")).isEqualTo(FileMatch.Unknown)
        // The UI's cached hint follows the last revalidation.
        assertThat(paths.cached(folder)!!.matches.keys).containsExactly("Imagens/2024/Trip/DSC00001.JPG", "Imagens/2024/Trip/DSC00007.JPG")
    }

    @Test
    fun hoardsOwnFileOperationsDropTheFolderAndItsSubFolders() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(shot(n))
        for (n in 10..12) server.putItem(shot(n, dir = "2024/Trip/Day2"))
        val day2 = folder.copy(path = "Imagens/2024/Trip/Day2")
        paths.resolve(folder, listing(1..3), reader)
        paths.resolve(day2, listing(10..12, "Imagens/2024/Trip/Day2"), reader)
        val elsewhere = folder.copy(path = "Imagens/2024/Other")
        for (n in 20..22) server.putItem(shot(n, dir = "2024/Other"))
        paths.resolve(elsewhere, listing(20..22, "Imagens/2024/Other"), reader)
        assertThat(server.folderQueries).isEqualTo(3)
        // A transfer into (or a rename / delete in) Trip: Trip and Day2 are asked again, Other is not.
        paths.invalidate("m1", "photos", "Imagens/2024/Trip")
        assertThat(paths.cached(folder)).isNull()
        assertThat(paths.cached(day2)).isNull()
        assertThat(paths.cached(elsewhere)).isNotNull()
        paths.resolve(folder, listing(1..3), reader)
        assertThat(server.folderQueries).isEqualTo(4)
    }

    @Test
    fun anotherPairedServerNeverGetsTheOldServersCachedItems() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(shot(n))
        paths.resolve(folder, listing(1..3), reader)
        assertThat(paths.cached(folder)!!.mapping.serverId).isEqualTo("fake-server")
        // Unpair, then pair another server (inside the 5 minutes) that holds the same files under other item ids.
        engine.unpair()
        val other = FakeServer().apply { serverId = "other-server"; features = features + "items-by-path"; nasFolders += folderId }
        try {
            for (n in 1..3) other.putItem(shot(n).copy(id = "other-$n"))
            engine.pair(PairingCode(other.url, "good", "other-server"))
            val r = paths.resolve(folder, listing(1..3), reader)!!
            assertThat(r.mapping.serverId).isEqualTo("other-server")
            assertThat(r.itemOf("Imagens/2024/Trip/DSC00002.JPG")).isEqualTo("other-2")
            assertThat(other.folderQueries).isEqualTo(1)
        } finally {
            other.close()
        }
        // Unpaired (the app's account observer tells it): no cached hint survives.
        paths.onServer(null)
        assertThat(paths.cached(folder)).isNull()
    }

    @Test
    fun aGoneNasFolderIsForgottenAndLearnedAgain() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(shot(n))
        paths.resolve(folder, listing(1..3), reader)
        // The owner removed the NAS folder and added it again: a new id, the old one is unknown (by-path: 404).
        val newId = "8b0c6f3e-1111-4222-8333-111111111111"
        server.nasFolders.clear()
        server.nasFolders += newId
        for (n in 1..3) server.putItem(shot(n, folder = newId))
        paths.clearCache()
        // The old id answers the query with nothing; by-path tells it is gone: forgotten, learned again at once.
        val again = paths.resolve(folder, listing(1..3), reader)!!
        assertThat(again.mapping.folderId).isEqualTo(newId)
        assertThat(again.itemOf("Imagens/2024/Trip/DSC00002.JPG")).isEqualTo("item-2")
        assertThat(read).hasSize(4)
        assertThat(paths.mappings().single().folderId).isEqualTo(newId)
    }

    @Test
    fun aLookupOutsideTheMappingInvalidatesIt() = runBlocking<Unit> {
        for (n in 1..3) server.putItem(shot(n))
        paths.resolve(folder, listing(1..3), reader)
        // A file of the folder found by its fingerprint in another NAS folder: the share prefix is not that folder.
        server.putItem(shot(9, dir = "elsewhere", folder = "8b0c6f3e-1111-4222-8333-000000000000"))
        prints["Imagens/2024/Trip/DSC00009.JPG"] = fp(9)
        val item = paths.lookupOne(folder, "Imagens/2024/Trip/DSC00009.JPG", reader)
        assertThat(item!!.id).isEqualTo("item-9") // still a server item for the hand-off
        assertThat(paths.mappings()).isEmpty()
        // A lookup inside the mapping keeps it.
        paths.resolve(folder, listing(1..3), reader, refresh = true)
        paths.lookupOne(folder, "Imagens/2024/Trip/DSC00002.JPG", reader)
        assertThat(paths.mappings()).hasSize(1)
    }

    @Test
    fun aBigSubTreeStopsPagingAndAsksByPath() = runBlocking<Unit> {
        // The share root is the whole library: thousands of items below it, three photos in the root itself.
        for (n in 1..2100) server.putItem(shot(n, dir = "2024/D${n % 7}"))
        for (n in 3001..3003) server.putItem(shot(n, dir = "root"))
        val root = SmbFolderRef("m1", "Photos", "Imagens/root", "guid-1")
        // Learn in the small folder first.
        val r = paths.resolve(root, listing(3001..3003, "Imagens/root"), reader)!!
        assertThat(r.itemOf("Imagens/root/DSC03002.JPG")).isEqualTo("item-3002")
        val whole = SmbFolderRef("m1", "Photos", "Imagens", "guid-1")
        server.folderQueries = 0
        val files = listOf(MappingDiscovery.SmbFile("Imagens/top.JPG", 5, 5))
        paths.resolve(whole, files, reader)
        assertThat(server.folderQueries).isEqualTo(SmbServerPaths.MAX_QUERY_PAGES)
        assertThat(server.byPathCalls).isEqualTo(1)
    }
}
