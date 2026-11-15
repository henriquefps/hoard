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
}
