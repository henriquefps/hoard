package dev.hfps.hoard.smb.handoff

import dev.hfps.hoard.smb.stream.StreamAddress
import dev.hfps.hoard.smb.stream.StreamTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HandoffStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private var now = 1_000_000L
    private fun store() = HandoffStore(temp.root.resolve("links.json"), clock = { now })

    private fun HandoffStore.photo(path: String, share: String = "photos", machine: String = "m1", size: Long = 10) =
        smbItem(machine, share, path, "image/jpeg", size, 5L, sidecar = path.endsWith(".xmp")) { id, tag, name -> "content://a/h/$id/$tag%2F$name" }

    @Test
    fun `the same file gets the same id, case-insensitively, also after a restart`() {
        val first = store().photo("Shoot/DSC1.ARW")
        assertTrue(StreamTokens.isWellFormed(first.id))
        assertEquals(first.id, store().photo("shoot/dsc1.arw").id) // a new instance reads the file back
        assertEquals(first.id, store().photo("/Shoot//DSC1.ARW").id)
        assertEquals(first.uri, store().get(first.id)!!.uri)
    }

    @Test
    fun `other files, shares and machines get other ids, the folder's files share a tag`() {
        val s = store()
        val raw = s.photo("Shoot/DSC1.ARW")
        val jpg = s.photo("Shoot/DSC1.JPG")
        val other = s.photo("Other/DSC1.ARW")
        val share = s.photo("Shoot/DSC1.ARW", share = "backup")
        val machine = s.photo("Shoot/DSC1.ARW", machine = "m2")
        assertEquals(5, setOf(raw.id, jpg.id, other.id, share.id, machine.id).size)
        assertEquals(raw.folderTag, jpg.folderTag)
        assertNotEquals(raw.folderTag, other.folderTag)
        assertTrue(raw.uri.contains(raw.folderTag!!) && !raw.uri.contains("Shoot"))
    }

    @Test
    fun `a second hand-off refreshes the file's metadata and last use`() {
        val s = store()
        val first = s.photo("a.jpg", size = 10)
        now += 5_000
        val again = s.photo("a.jpg", size = 20)
        assertEquals(first.id, again.id)
        assertEquals(20L, again.size)
        assertEquals(now, again.lastUsedAt)
        assertEquals(first.createdAt, again.createdAt)
    }

    @Test
    fun `unknown and malformed ids are not found`() {
        val s = store()
        s.photo("a.jpg")
        assertNull(s.get("0".repeat(64)))
        assertNull(s.get("../a.jpg"))
        assertNull(s.get(null))
    }

    @Test
    fun `links unused for 180 days are pruned, used ones stay`() {
        val s = store()
        val old = s.photo("old.jpg")
        val used = s.photo("used.jpg")
        val local = s.localItem("content://a.fileprovider/device/x.jpg", "x.jpg", "image/jpeg", false)
        now += HandoffStore.MAX_IDLE_MS - HandoffStore.TOUCH_EVERY_MS
        s.touch(used.id)
        now += HandoffStore.TOUCH_EVERY_MS
        val pruned = s.prune()
        assertEquals(listOf(old.id), pruned.map { it.id })
        assertNull(store().get(old.id))
        assertEquals(used.id, store().get(used.id)?.id)
        assertEquals(local.id, store().get(local.id)?.id) // LOCAL records are not pruned by age
        // A pruned file handed over again gets a new link.
        assertNotEquals(old.id, s.photo("old.jpg").id)
    }

    @Test
    fun `touch writes at most once an hour`() {
        val s = store()
        val item = s.photo("a.jpg")
        now += 1_000
        s.touch(item.id)
        assertEquals(item.lastUsedAt, store().get(item.id)!!.lastUsedAt)
        now += HandoffStore.TOUCH_EVERY_MS
        s.touch(item.id)
        assertEquals(now, store().get(item.id)!!.lastUsedAt)
    }

    @Test
    fun `revoke all forgets every link, machines stay`() {
        val s = store()
        s.photo("a.jpg")
        s.localItem("content://a.fileprovider/device/x.jpg", "x.jpg", "image/jpeg", false)
        s.setMachines(listOf(HandoffMachine("m1", "guid", listOf(StreamAddress("10.0.0.2", 445)))))
        assertEquals(2, s.count())
        assertEquals(2, s.clear().size)
        assertEquals(0, store().count())
        assertEquals("guid", store().machine("m1")?.serverGuid)
    }

    @Test
    fun `the machine mirror keeps the last good address while it is still an address`() {
        val a = StreamAddress("192.168.1.2", 445)
        val b = StreamAddress("100.64.0.2", 445)
        val s = store()
        s.setMachines(listOf(HandoffMachine("m1", "guid", listOf(a, b))))
        s.setLastGood("m1", b)
        s.setMachines(listOf(HandoffMachine("m1", "guid", listOf(a, b)), HandoffMachine("m2", null, listOf(a))))
        assertEquals(b, store().machine("m1")?.lastGood)
        assertNull(store().machine("m2")?.serverGuid)
        s.setMachines(listOf(HandoffMachine("m1", "guid", listOf(a))))
        assertNull(store().machine("m1")?.lastGood)
        assertNull(store().machine("m2"))
        // An address that is not the machine's is never remembered.
        s.setLastGood("m1", StreamAddress("evil", 445))
        assertNull(store().machine("m1")?.lastGood)
    }

    @Test
    fun `a hand-off notes its route without reordering a known machine`() {
        val a = StreamAddress("192.168.1.2", 445)
        val b = StreamAddress("100.64.0.2", 445)
        val s = store()
        s.noteRoute(HandoffMachine("new", "g", listOf(b, a)))
        assertEquals(listOf(b, a), store().machine("new")?.addresses)
        assertEquals(b, store().machine("new")?.lastGood)
        s.setMachines(listOf(HandoffMachine("m1", null, listOf(a, b))))
        s.noteRoute(HandoffMachine("m1", "g1", listOf(b, a)))
        val m1 = store().machine("m1")!!
        assertEquals(listOf(a, b), m1.addresses)
        assertEquals(b, m1.lastGood)
        assertEquals("g1", m1.serverGuid)
    }

    @Test
    fun `a damaged file starts empty instead of failing`() {
        temp.root.resolve("links.json").writeText("{not json")
        assertEquals(0, store().count())
        assertEquals(1, store().apply { photo("a.jpg") }.count())
    }
}
