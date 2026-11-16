package dev.hfps.hoard.server.sync

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.ItemsPage
import dev.hfps.hoard.server.api.ServerJson
import org.junit.Test

/** The items feed as hoard-server 0.9.0 sends it, including what the E2E run found. */
class FeedTest {
    @Test
    fun anUploadedOriginalHasNoFileNameAndStillSyncs() {
        // An uploaded original (ADR 0014 "Uploads"): the file has no name and only a backup location.
        val json = """{"items":[{"id":"4d628a7b-6084-85de-b6b9-e97911c07f38","rev":10039,"deleted":false,"kind":"photo",
            "mainFingerprint":"v1:b467","sortTime":1794700000000,"futureField":{"x":1},
            "files":[{"fingerprint":"v1:b467","role":"rendered","format":"jpeg","size":78929,"locations":[{"source":"backup","sha256":"ab"}]}]}],
            "culling":[],"cursor":"x:i10039","hasMore":false}"""
        val page = ServerJson.decodeFromString(ItemsPage.serializer(), json)
        val writes = Feed.writes(page)
        assertThat(writes.items.single().name).isEmpty()
        assertThat(writes.items.single().folder).isEmpty()
        assertThat(writes.files.single().fingerprint).isEqualTo("v1:b467")
    }

    @Test
    fun aTombstoneWithReplacedByMovesTheRecord() {
        val json = """{"items":[{"id":"old","rev":3,"deleted":true,"replacedBy":"new"}],
            "culling":[{"itemId":"old","rev":4,"fingerprints":[],"replacedBy":"new"}],"cursor":"x:i4","hasMore":false}"""
        val writes = Feed.writes(ServerJson.decodeFromString(ItemsPage.serializer(), json))
        assertThat(writes.goneItems).containsExactly("old")
        assertThat(writes.replaced).containsExactly("old", "new")
        assertThat(writes.records).isEmpty()
    }

    @Test
    fun mirrorLocationsBecomeRowsAndAPhoneOnlyItemIsFiledUnderItsPhone() {
        // hoard-server 0.12.0 (ADR 0024): a phone's backup. One file still on the phone, its pair freed, an unknown
        // `gone` value, and a location without a root (skipped).
        val json = """{"items":[{"id":"i1","rev":5,"kind":"pair","mainFingerprint":"v1:raw","sortTime":1,
            "files":[
              {"fingerprint":"v1:raw","role":"raw","name":"PXL_1.dng","size":40,"locations":[
                {"source":"backup","sha256":"aa"},
                {"source":"mirror","physicalDeviceId":"p1","deviceName":"Pixel 8","rootId":"r1","volume":"primary","path":"DCIM/Camera/PXL_1.dng","modifiedAt":7,"onDevice":true}]},
              {"fingerprint":"v1:jpg","role":"rendered","name":"PXL_1.jpg","size":30,"locations":[
                {"source":"backup","sha256":"bb"},
                {"source":"mirror","physicalDeviceId":"p1","deviceName":"Pixel 8","rootId":"r1","volume":"primary","path":"DCIM/Camera/PXL_1.jpg","modifiedAt":8,"onDevice":false,"gone":"freed"},
                {"source":"mirror","physicalDeviceId":"p2","deviceName":"Tab","rootId":"r2","volume":"1234-ABCD","path":"Pictures/PXL_1.jpg","onDevice":false,"gone":"lost"},
                {"source":"mirror","physicalDeviceId":"p3","path":"x.jpg"}]}]}],
            "culling":[],"cursor":"x:i5","hasMore":false}"""
        val writes = Feed.writes(ServerJson.decodeFromString(ItemsPage.serializer(), json))
        assertThat(writes.mirrors).hasSize(3)
        val raw = writes.mirrors.first { it.fingerprint == "v1:raw" }
        assertThat(raw.onDevice).isTrue()
        assertThat(raw.gone).isNull()
        assertThat(raw.size).isEqualTo(40)
        assertThat(writes.mirrors.first { it.rootId == "r1" && it.fingerprint == "v1:jpg" }.gone).isEqualTo("freed")
        val tab = writes.mirrors.first { it.rootId == "r2" }
        assertThat(tab.gone).isEqualTo("deleted")
        assertThat(tab.volume).isEqualTo("1234-ABCD")
        assertThat(tab.modifiedAt).isNull()
        // No NAS location: the item is filed under the phone and found by its name.
        assertThat(writes.items.single().folder).isEqualTo("Pixel 8/DCIM/Camera")
        assertThat(writes.items.single().search).contains("pixel 8")
    }

    @Test
    fun aTrashedItemKeepsNoMirrorRows() {
        val json = """{"items":[{"id":"i1","rev":5,"kind":"photo","mainFingerprint":"v1:a","sortTime":1,"trashed":{"since":3},
            "files":[{"fingerprint":"v1:a","role":"rendered","name":"a.jpg","size":3,"locations":[
              {"source":"mirror","physicalDeviceId":"p1","deviceName":"P","rootId":"r1","volume":"primary","path":"DCIM/a.jpg"}]}]}],
            "culling":[],"cursor":"x:i5","hasMore":false}"""
        val writes = Feed.writes(ServerJson.decodeFromString(ItemsPage.serializer(), json))
        assertThat(writes.items).hasSize(1)
        assertThat(writes.mirrors).isEmpty()
    }
}
